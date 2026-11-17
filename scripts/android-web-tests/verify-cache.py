#!/usr/bin/env python3
"""Replay the recorded W-R3 evidence checks offline; this is not W1c acceptance."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys


def check(condition, message):
    if not condition:
        raise SystemExit('FAIL: ' + message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    root = parser.parse_args().evidence
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location('cache_capture', Path(__file__).with_name('summarize-cache.py'))
    analyzer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(analyzer)
    events = [json.loads(line) for line in (root / 'r3-client-events.jsonl').read_text().splitlines()]
    result = analyzer.summarize(root / 'r3.pcap', events)
    rows = list(analyzer.packets(root / 'r3.pcap'))
    stats = (root / 'r3-tcpdump-stats.txt').read_text()
    check(re.search(r'^0 packets dropped by kernel$', stats, re.M), 'capture has losses or missing statistics')
    captured = re.search(r'^(\d+) packets captured$', stats, re.M)
    check(captured and int(captured[1]) == len(rows), 'packet count differs from completed capture')
    check(result['tcpDnsPackets'] == 0, 'TCP DNS requires stream decoding')
    check(result['port853PayloadBytes'] == 0, 'unexpected encrypted-transport payload requires investigation')

    def state(name):
        return json.loads((root / name / 'state.json').read_text())['web']

    checkpoints = ['r3-before-block', 'r3-after-block', 'r3-after-unblock',
                   'r3-browser-kept-blocked', 'r3-browser-kept-unblocked',
                   'r3-browser-fresh-blocked', 'r3-browser-cold-blocked', 'r3-browser-cold-unblocked']
    networks = []
    for name in checkpoints:
        web = state(name)
        check(web['running'] and not any(g.get('to') is None for g in web['gaps']), name + ': not actively filtering')
        vpn = re.findall(r'network\{(\d+)\}.*?ni\{VPN CONNECTED extra: VPN:([^}]+)\}',
                         (root / name / 'connectivity.txt').read_text())
        check(len(vpn) == 1 and vpn[0][1] == 'expo.modules.screentime.example', name + ': wrong VPN')
        networks.append(vpn[0][0])
    check(len(set(networks)) == 1, 'VPN network changed across list changes')
    check(rows[0]['atMs'] < state('r3-before-block')['consent']['establishAt'], 'capture did not start before establishment')
    check('case3.r3.test' in state('r3-after-block')['domains'], 'native trial suffix not blocked')
    check(state('r3-after-unblock')['domains'] == ['anchor.r3.test'], 'native trial suffix not unblocked')
    for name in ('r3-browser-kept-blocked', 'r3-browser-fresh-blocked', 'r3-browser-cold-blocked'):
        check('browser1.r3.test' in state(name)['domains'], name + ': browser suffix not blocked')
    for name in ('r3-browser-kept-unblocked', 'r3-browser-cold-unblocked'):
        check(state(name)['domains'] == ['anchor.r3.test'], name + ': browser suffix not unblocked')

    positive, negative = result['trials']
    check(positive['zone'] == 'case3.r3.test' and negative['zone'] == 'negative.case3.r3.test', 'unexpected trials')
    for trial in (positive, negative):
        check(trial['complete'] and trial['networkUnchanged'], 'incomplete trial or network replacement')
        check(trial['before']['dns'] != trial['after']['dns'], 'no DNS-pair change')
        check(trial['vmWarmAgeMs'] < 2000, 'VM lookup was too old at the swap')
        check(len(trial['samples']) == 10 and all(s['success'] for s in trial['samples']), 'lookup failed or samples missing')
    warm = [entry['result'] for entry in json.loads((root / 'r3-warm-3.json').read_text())]
    first, second = warm[:2]
    check(second['startedAtMs'] - first['finishedAtMs'] >= 2000, 'native calibration gap is under two seconds')
    check(first['success'] and second['success'], 'native calibration failed')
    for lookup in warm:
        name = result['dnsNames'][lookup['name']]
        check(any(3600 in r['ttls'] for r in name['tunnelResponses']), 'long TTL did not reach the client')
    native_queries = result['dnsNames'][first['name']]['tunnelQueries']
    check(len(native_queries) == 1 and first['startedAtMs'] <= native_queries[0]['atMs'] <= first['finishedAtMs'],
          'native second lookup or post-block lookup was not a cache hit')
    check(all(s['tunnelQueries'] == 0 for s in positive['samples']), 'positive cache did not persist at every sample')
    for mode in ('native', 'vm'):
        samples = [s for s in positive['samples'] if s['mode'] == mode]
        check(samples[0]['offsetMs'] < 2000 and samples[-1]['offsetMs'] >= 65000, mode + ': missing time bounds')
        queries = result['dnsNames'][samples[0]['name']]['tunnelQueries']
        check(not any(q['atMs'] >= positive['swapAtMs'] for q in queries), mode + ': query after positive-cache swap')
        unblocked = next(s for s in negative['samples'] if s['mode'] == mode)
        check(unblocked['tunnelQueries'] > 0, mode + ': first unblock lookup did not reach tunnel')
        replies = result['dnsNames'][unblocked['name']]['tunnelResponses']
        check(any(r['rcode'] == 3 and r['atMs'] < negative['swapAtMs'] for r in replies), 'missing negative calibration')
    fresh = result['dnsNames']['fresh.case3.r3.test']['tunnelResponses']
    check(fresh and all(r['rcode'] == 3 for r in fresh), 'fresh-name block control missing')

    continuity = result['holdContinuity']
    check(not result['holdErrors'] and len(result['holdConnections']) == 1, 'persistent TCP failure/reconnect')
    check(len(result['echoSyns']) == 1 and not result['echoResets'], 'echo TCP reconnect/reset in capture')
    check(continuity['consecutive'] and len(continuity['localPorts']) == 1, 'echo sequence/socket changed')
    check(continuity['firstAtMs'] < positive['swapAtMs'] and
          continuity['lastAtMs'] > state('r3-browser-cold-unblocked')['consent']['establishAt'],
          'persistent socket did not span all list changes')

    browser = 'browser.browser1.r3.test'
    blocked_at = state('r3-browser-kept-blocked')['consent']['establishAt']
    unblocked_at = state('r3-browser-kept-unblocked')['consent']['establishAt']
    ticks = [r for r in result['httpRequests'] if r['http']['host'] == browser + ':8088' and
             r['http']['request'].startswith('GET /tick')]
    ports = {r['sport'] for r in ticks}
    check(len(ports) == 1 and any(r['atMs'] < blocked_at for r in ticks) and
          any(blocked_at < r['atMs'] < unblocked_at for r in ticks) and
          any(r['atMs'] > unblocked_at for r in ticks), 'Chrome retained connection did not span block/unblock')
    fresh_http = [r for r in result['httpRequests'] if r['http']['host'] == browser + ':8088' and
                  'fresh-after-block=1' in r['http']['request']]
    check(len(fresh_http) == 1 and fresh_http[0]['sport'] not in ports, 'missing fresh Chrome connection')
    reblocked_at = state('r3-browser-fresh-blocked')['consent']['establishAt']
    check(any(reblocked_at < r['atMs'] < fresh_http[0]['atMs'] and r['sport'] == fresh_http[0]['sport']
              for r in result['httpSyns']), 'new Chrome TCP handshake not captured after block')
    check(not any(q['atMs'] > reblocked_at for q in result['dnsNames'][browser]['tunnelQueries']),
          'fresh Chrome navigation resolved again through tunnel')
    actions = [json.loads(line) for line in (root / 'r3-ui-actions.jsonl').read_text().splitlines()]
    for label in ('Close idle sockets', 'Flush socket pools', 'New tab'):
        check(any(a['code'] == 0 and 'Tapped text="' + label + '"' in a['stdout'] for a in actions),
              'missing Chrome UI action: ' + label)
    check(any('DNS_PROBE_FINISHED_NXDOMAIN' in a['stdout'] for a in actions), 'Chrome cold control error not observed')
    cold = result['dnsNames']['cold.browser1.r3.test']['tunnelResponses']
    unblock_at = state('r3-browser-cold-unblocked')['consent']['establishAt']
    check(any(r['rcode'] == 3 and r['atMs'] < unblock_at for r in cold) and
          any(r['rcode'] == 0 and r['atMs'] > unblock_at for r in cold), 'Chrome cold control block/unblock missing')
    check(any(r['http']['host'] == 'cold.browser1.r3.test:8088' and r['atMs'] > unblock_at
              for r in result['httpRequests']), 'Chrome cold page did not load after unblock')
    cleanup = state('r3-cleanup')
    check(not cleanup['running'] and not cleanup['domains'] and not any(g.get('to') is None for g in cleanup['gaps']),
          'filter cleanup incomplete')
    check('ni{VPN CONNECTED' not in (root / 'r3-cleanup/connectivity.txt').read_text(), 'VPN still connected')
    check('ProbeService' not in (root / 'r3-cleanup/services.txt').read_text(), 'test FGS still active')
    print('PASS: calibrated native cache persists beyond 65 s; fresh names block; first unblock lookups reach tunnel.')
    print(f"PASS: {result['holdPings']} consecutive echo replies, one SYN, no RST; VPN network unchanged.")
    print('PASS: retained and fresh Chrome connections confirmed; cold-name block/unblock and cleanup confirmed.')
    print('Swap alone is insufficient. This baseline verifier does not validate the later TTL cap or W1c acceptance.')


if __name__ == '__main__':
    main()
