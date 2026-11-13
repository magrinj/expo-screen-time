#!/usr/bin/env python3
"""Warm/arm the separate-UID W-R3 client. Makes no UI or filter changes.

Start the client through agent-device, then adb forward a host port to 8767.
After 'arm', change the domain list through the example UI. The client samples
the caches when its default network's DNS pair changes.
"""
import argparse
import json
from pathlib import Path
import time
from urllib.parse import urlencode
from urllib.request import urlopen


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port', type=int, default=18767)
    p.add_argument('--zone', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('action', choices=('warm', 'arm', 'status', 'hold', 'stop-hold'))
    args = p.parse_args()
    if not args.zone.endswith('.r3.test'):
        p.error('Use a fresh controlled subdomain of r3.test')
    if args.output.exists():
        p.error('Evidence already exists; choose a new output path')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    rows = []

    def call(command, **params):
        started = time.time_ns() // 1000000
        with urlopen(f'http://127.0.0.1:{args.port}/{command}?{urlencode(params)}', timeout=12) as response:
            result = json.load(response)
        rows.append(dict(command=command, params=params, atMs=started,
                         finishedAtMs=time.time_ns() // 1000000, result=result))
        if 'error' in result and 'mode' not in result:
            raise RuntimeError(result['error'])
        return result

    try:
        if args.action == 'warm':
            first = call('native', name='native.' + args.zone)
            time.sleep(2)
            second = call('native', name='native.' + args.zone)
            vm = call('vm', name='vm.' + args.zone)
            if not all(r.get('success') for r in (first, second, vm)):
                raise RuntimeError('Positive warmup failed; do not treat the trial as calibrated')
        elif args.action == 'arm':
            call('arm', zone=args.zone)
        elif args.action == 'hold':
            call('hold', name='allowed.r3.test')
        else:
            call(args.action)
    finally:
        # Never replace the evidence from a previous call.
        with args.output.open('x') as out:
            json.dump(rows, out, indent=2)
            out.write('\n')
    print(json.dumps(rows, indent=2))


if __name__ == '__main__':
    main()
