#!/usr/bin/env python3
"""Two-phase TCP trial: start, externally perform/observe the DNS-pair swap, finish.

Only contacts an already running test client's loopback control API. Never changes
VPN state, drives UI, waits for an unobserved swap, or silently reconnects.
"""
import argparse
import base64
import json
import importlib.util
from pathlib import Path
import struct
import urllib.parse
import urllib.request


# Reuse only the strict controlled A/IN message decoder, not the historical
# single-flow packet verifier (which cannot judge a swap campaign).
_spec = importlib.util.spec_from_file_location('tcp_wire', Path(__file__).with_name('verify-tcp-ttl.py'))
_wire = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_wire)


def control_url(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or not parsed.port or
            parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/')):
        raise ValueError('an explicit loopback control origin is required')
    return value.rstrip('/')


def successful(result):
    if result.get('error'):
        raise ValueError(result['error'])
    return result


def same_connection(result, baseline):
    successful(result)
    for field in ('sessionId', 'server', 'port', 'localPort'):
        if result.get(field) != baseline.get(field):
            raise ValueError(f'TCP connection changed: {field}')
    if result.get('closed'):
        raise ValueError('TCP connection is closed')
    if result['network']['handle'] != baseline['network']['handle']:
        raise ValueError('VPN network changed during the DNS-pair swap')


def valid_answer(result, sequence, expected_name):
    successful(result)
    if result.get('sequence') != sequence:
        raise ValueError('unexpected query sequence')
    reply = result['reply']
    packet = base64.b64decode(reply['wireBase64'], validate=True)
    if len(packet) < 12 or len(packet) != reply.get('bytes'):
        raise ValueError('incomplete DNS answer')
    identifier, flags, questions, answers, _, _ = struct.unpack('!6H', packet[:12])
    if identifier != 0x6000 + sequence or reply.get('id') != identifier:
        raise ValueError('wrong DNS response ID')
    if not flags & 0x8000 or flags & 0x7a0f or questions != 1 or not answers:
        raise ValueError('complete positive NOERROR answer required')
    decoded = _wire.message(packet)
    if decoded['name'] != expected_name or any(row['owner'] != expected_name for row in decoded['records']):
        raise ValueError('answer is for a different controlled name')


def start(fetch, output, name):
    if not name.endswith('.r3.test'):
        raise ValueError('controlled .r3.test name required')
    output.mkdir(parents=True, exist_ok=False)
    opened = None
    try:
        opened = successful(fetch(op='open'))
        if (not opened.get('sessionId') or opened.get('closed') or not opened['network'].get('handle') or
                not opened['network'].get('dns') or opened.get('server') != opened['network']['dns'][0]):
            raise ValueError('connected session on the current VPN DNS required')
        before = successful(fetch(op='query', id=opened['sessionId'], name='before.' + name))
        same_connection(before, opened)
        valid_answer(before, 1, 'before.' + name)
        if before['network']['dns'] != opened['network']['dns']:
            raise ValueError('DNS changed before the baseline query')
        record = dict(name=name, opened=opened, baseline=before,
                      packetCaptureRequired=True, campaignComplete=False)
        (output / 'start.json').write_text(json.dumps(record, indent=2) + '\n')
        return record
    except (Exception, KeyboardInterrupt) as error:
        failure = dict(error=f'{type(error).__name__}: {error}', close=None)
        if opened and opened.get('sessionId'):
            try:
                failure['close'] = fetch(op='close', id=opened['sessionId'])
            except Exception as closing:
                failure['closeError'] = str(closing)
        (output / 'start-error.json').write_text(json.dumps(failure, indent=2) + '\n')
        raise


def finish(fetch, output):
    if (output / 'finish.json').exists():
        raise ValueError('this trial was already finished; do not reuse its session')
    record = json.loads((output / 'start.json').read_text())
    baseline, name = record['baseline'], record['name']
    identity = baseline['sessionId']
    result = dict(dnsPairChangeObserved=False, replies=[], errors=[], cleanupVerified=False,
                  packetCaptureRequired=True, campaignComplete=False)
    try:
        status = successful(fetch(op='status', id=identity))
        same_connection(status, baseline)
        old_dns, new_dns = baseline['network']['dns'], status['network']['dns']
        if not new_dns or set(old_dns) & set(new_dns):
            raise ValueError('a disjoint new DNS pair must be observed before finish')
        result['dnsPairChangeObserved'] = True
        result['beforeDns'], result['afterDns'] = old_dns, new_dns
        for index in range(10):
            reply = successful(fetch(op='query', id=identity, name=f'after{index}.' + name))
            result['replies'].append(reply)
            same_connection(reply, baseline)
            valid_answer(reply, index + 2, f'after{index}.' + name)
            if reply['network']['dns'] != new_dns:
                raise ValueError('DNS changed again during the ten queries')
    except (Exception, KeyboardInterrupt) as error:
        result['errors'].append(f'{type(error).__name__}: {error}')
    finally:
        try:
            closed = successful(fetch(op='close', id=identity))
            result['close'] = closed
            result['cleanupVerified'] = closed.get('sessionId') == identity and closed.get('closed') is True
            if not result['cleanupVerified']:
                raise ValueError('session closure not confirmed')
        except (Exception, KeyboardInterrupt) as error:
            result['errors'].append(f'close: {error}')
        (output / 'finish.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('start', 'finish'))
    parser.add_argument('--control', type=control_url, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--name', help='fresh .r3.test name; start only')
    args = parser.parse_args()
    if args.phase == 'start' and not args.name:
        parser.error('--name is required for start')
    if args.phase == 'finish' and args.name:
        parser.error('finish reads the name from start.json')
    def fetch(**parameters):
        url = args.control + '/tcp-session?' + urllib.parse.urlencode(parameters)
        with urllib.request.urlopen(url, timeout=25) as response:
            return json.load(response)
    if args.phase == 'start':
        start(fetch, args.output, args.name)
        print('Baseline saved; connection remains open. Observe the DNS-pair swap before finish.')
    else:
        result = finish(fetch, args.output)
        print(json.dumps({key: result[key] for key in ('dnsPairChangeObserved', 'cleanupVerified', 'errors', 'campaignComplete')}, indent=2))
        if result['errors']:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
