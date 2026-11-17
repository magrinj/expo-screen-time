#!/usr/bin/env python3
"""Replay the completed subset of November 11's W-R3 TTL-cap campaign.

Checks UDP wire TTLs, native/VM cache expiry, unblock and the allowed TCP socket.
Chrome controls, DNS-over-TCP and cleanup remain explicitly incomplete after
the device-tool failure. This is not a generic W1c acceptance gate.
"""
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
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--output', type=Path)
    args = p.parse_args()
    root = args.evidence
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location('cache_capture', Path(__file__).with_name('summarize-cache.py'))
    capture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(capture)
    events = [json.loads(line) for line in (root / 'final-events.jsonl').read_text().splitlines()]
    result = capture.summarize(root / 'final.pcap', events)
    rows = list(capture.packets(root / 'final.pcap'))
    stats = (root / 'main-tcpdump-stats.txt').read_text()
    check(re.search(r'^0 packets dropped by kernel$', stats, re.M), 'capture losses or missing statistics')
    count = re.search(r'^(\d+) packets captured$', stats, re.M)
    check(count and int(count[1]) == len(rows), 'incomplete capture')
    warm = [x['result'] for x in json.loads((root / 'warm.json').read_text())]
    check(warm[1]['startedAtMs'] - warm[0]['finishedAtMs'] >= 2000, 'native calibration gap too short')
    check(all(x['success'] for x in warm), 'warmup failed')
    positive, negative = result['trials']
    check([positive['zone'], negative['zone']] == ['ttl1.r3.test', 'negative.ttl1.r3.test'], 'wrong campaign')
    report = {'pcapSha256': result['pcapSha256'], 'campaignComplete': False, 'caches': {},
              'unmetChecks': ['Chrome socket flush, fresh-name control and unblock/reload',
                              'Real DNS-over-TCP TTL-cap probe', 'Device and network cleanup']}
    for trial in (positive, negative):
        check(trial['complete'] and trial['networkUnchanged'], 'trial incomplete or VPN network replaced')
        check(trial['vmWarmAgeMs'] < 2000 and trial['before']['dns'] != trial['after']['dns'], 'uncalibrated swap')
    for mode in ('native', 'vm'):
        samples = [s for s in positive['samples'] if s['mode'] == mode]
        check(len(samples) == 9, 'missing positive samples')
        name = samples[0]['name']
        replies = result['dnsNames'][name]['tunnelResponses']
        learned = next(r for r in replies if r['rcode'] == 0)
        upstream = [r for r in rows if r['src'] == '10.0.2.2' and r.get('dns', {}).get('name') == name and r['dns']['response']]
        check(learned['ttls'] == [60] and upstream and upstream[0]['dns']['ttls'] == [3600], 'UDP cap not seen on both sides')
        check(0 <= positive['swapAtMs'] - learned['atMs'] < 60000, 'answer already expired before block')
        if mode == 'native':
            check(any(warm[0]['startedAtMs'] <= q['atMs'] <= warm[0]['finishedAtMs']
                      for q in result['dnsNames'][name]['tunnelQueries']), 'first native lookup did not reach tunnel')
            check(not any(warm[1]['startedAtMs'] - 2 <= q['atMs'] <= warm[1]['finishedAtMs'] + 2
                          for q in result['dnsNames'][name]['tunnelQueries']), 'second native lookup was not a cache hit')
        check(samples[0]['success'] and samples[0]['tunnelQueries'] == 0, 'missing initial post-block cache hit')
        first_blocked = next((s for s in samples if not s['success']), None)
        check(first_blocked is not None, 'positive cache never expired during the trial')
        late = [s for s in samples if s['offsetMs'] >= first_blocked['offsetMs']]
        check(all(not s['success'] and s['tunnelQueries'] > 0 for s in late), 'late probe bypassed the block or did not reach tunnel')
        check(first_blocked['offsetMs'] <= 60000 and samples[-1]['offsetMs'] >= 65000, 'missing observed within-minute block or final sample')
        check(all(r['rcode'] == 3 for r in replies if r['atMs'] > positive['swapAtMs']), 'post-block response was not NXDOMAIN')
        last_allowed = [s for s in samples if s['success']][-1]
        check(all(s['tunnelQueries'] == 0 for s in samples if s['success']), 'early success was not cached')
        report['caches'][mode] = {'lastCachedOffsetMs': last_allowed['offsetMs'],
            'firstBlockedOffsetMs': first_blocked['offsetMs'],
            'firstBlockedAnswerAgeMs': positive['swapAtMs'] + first_blocked['offsetMs'] - learned['atMs']}
        unblocked = [s for s in negative['samples'] if s['mode'] == mode]
        check(len(unblocked) == 9 and all(s['success'] for s in unblocked) and unblocked[0]['tunnelQueries'] > 0, 'unblock failed')
        before_unblock = json.loads((root / ('negative-' + mode + '.json')).read_text())['result']
        check(not before_unblock['success'], 'negative control was not blocked')
        check(any(r['rcode'] == 3 and r['atMs'] < negative['swapAtMs']
                  for r in result['dnsNames'][before_unblock['name']]['tunnelResponses']), 'negative control missing NXDOMAIN')
        report['caches'][mode]['unblockFirstTunnelOffsetMs'] = unblocked[0]['firstTunnelOffsetMs']
    fresh = json.loads((root / 'fresh-blocked.json').read_text())['result']
    fresh_dns = result['dnsNames'][fresh['name']]
    check(not fresh['success'] and fresh['rcode'] == 3 and fresh_dns['tunnelQueries'] and
          all(r['rcode'] == 3 for r in fresh_dns['tunnelResponses']) and fresh_dns['tunnelResponses'],
          'fresh-name control missing NXDOMAIN')
    check(not result['holdErrors'] and len(result['holdConnections']) == 1 and len(result['echoSyns']) == 1,
          'persistent connection error or reconnect')
    check(not result['echoResets'] and result['holdContinuity']['consecutive'], 'persistent connection reset or missing echo')
    report['hold'] = dict(pings=result['holdPings'], **result['holdContinuity'])
    check(json.loads((root / 'hold-stop.json').read_text())['result']['holding'] is False, 'echo not deliberately stopped')

    def state(name):
        return json.loads((root / name / 'state.json').read_text())['web']

    networks = []
    for name in ('before-block', 'after-block', 'after-unblock', 'browser-kept-blocked', 'stopped-ui-incomplete-cleanup'):
        web = state(name)
        check(web['running'] and not any(g.get('to') is None for g in web['gaps']), name + ': filter not active')
        vpn = re.findall(r'network\{(\d+)\}.*?ni\{VPN CONNECTED extra: VPN:([^}]+)\}', (root / name / 'connectivity.txt').read_text())
        check(len(vpn) == 1 and vpn[0][1] == 'expo.modules.screentime.example', name + ': wrong VPN')
        networks.append(vpn[0][0])
    check(len(set(networks)) == 1, 'VPN network changed')
    blocked_at = state('browser-kept-blocked')['consent']['establishAt']
    check(result['holdContinuity']['firstAtMs'] < positive['swapAtMs'] and
          result['holdContinuity']['lastAtMs'] > blocked_at, 'echo did not span all measured swaps')
    # Report only what these packets show, not a completed browser acceptance test.
    browser = result['dnsNames']['keep.ttlb1.r3.test']
    check(all(r['ttls'] == [60] for r in browser['tunnelResponses']), 'browser did not learn capped A answers')
    check(not any(q['atMs'] > blocked_at for q in browser['tunnelQueries']), 'browser resolved again after block')
    learned_at = max(r['atMs'] for r in browser['tunnelResponses'])
    requests = [r for r in result['httpRequests'] if r['http']['host'] == 'keep.ttlb1.r3.test:8088' and r['atMs'] > blocked_at]
    ports = {r['sport'] for r in requests}
    syns = [r for r in result['httpSyns'] if r['sport'] in ports and r['atMs'] > blocked_at]
    check(len(syns) == 1 and syns[0]['atMs'] - learned_at > 60000, 'missing new HTTP connection after capped answer aged 60 s')
    report['browserPartial'] = {'newSynOffsetMs': syns[0]['atMs'] - blocked_at,
        'answerAgeAtNewSynMs': syns[0]['atMs'] - learned_at, 'postBlockGetPackets': len(requests),
        'lastGetOffsetMs': requests[-1]['atMs'] - blocked_at, 'socketFlushCompleted': False}
    report['cleanup'] = {'complete': False, 'remainingDomains': state('stopped-ui-incomplete-cleanup')['domains']}
    report['vpnNetwork'] = networks[0]
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    print('PASS: recorded UDP/native/VM and allowed-socket observations. INCOMPLETE: browser controls, TCP DNS and cleanup; W1c remains open.')


if __name__ == '__main__':
    main()
