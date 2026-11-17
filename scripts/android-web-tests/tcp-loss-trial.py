#!/usr/bin/env python3
"""One future W-R5 loss trial on an explicitly selected rooted emulator; never drives UI.

Records the exact DROP counter and removes only its own tagged rule even on failure.
A successful run still requires the packet capture to prove retransmission.
"""
import argparse
import base64
import ipaddress
import json
import os
from pathlib import Path
import signal
import subprocess
import urllib.parse
import urllib.request
import uuid


def probe_url(value):
    parsed = urllib.parse.urlsplit(value)
    query = urllib.parse.parse_qs(parsed.query)
    if (parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or not parsed.port or
            parsed.username or parsed.password or parsed.fragment or parsed.path != '/tcp-scenario' or
            query.get('mode') != ['query'] or query.get('type') != ['DNSKEY'] or
            len(query.get('name', [])) != 1 or not query['name'][0].endswith('.r3.test')):
        raise ValueError('explicit loopback control URL with mode=query, type=DNSKEY and .r3.test name required')
    return value


def validate_reply(result, fixture, family):
    import dns.dnssec
    import dns.flags
    import dns.message
    import dns.rdatatype
    if result.get('error') or result.get('mode') != 'query' or result.get('qtype') != 48:
        raise ValueError('client did not complete a DNSKEY query')
    if ipaddress.ip_address(result['server'].split('%')[0]).version != family:
        raise ValueError('response used the wrong IP family for the DROP rule')
    if result.get('port') != 53 or len(result.get('replies', [])) != 1:
        raise ValueError('one TCP/53 answer required')
    row = result['replies'][0]
    wire = base64.b64decode(row['wireBase64'], validate=True)
    response = dns.message.from_wire(wire)
    if len(wire) <= 1460 or row.get('bytes') != len(wire) or response.rcode() or response.flags & dns.flags.TC:
        raise ValueError('complete NOERROR answer larger than 1460 bytes required')
    if response.id != 0x5701 or row.get('id') != response.id or not response.flags & dns.flags.QR:
        raise ValueError('wrong response ID or QR flag')
    if len(response.question) != 1 or response.question[0].name != fixture.zone.origin or response.question[0].rdtype != 48:
        raise ValueError('answer is not for the pinned test zone DNSKEY')
    if result.get('name') + '.' != str(fixture.zone.origin):
        raise ValueError('client name is not the pinned zone')
    rrset = next(row for row in response.answer if row.rdtype == dns.rdatatype.DNSKEY)
    signature = next(row for row in response.answer if row.rdtype == dns.rdatatype.RRSIG)
    trusted = fixture.zone.find_rrset(fixture.zone.origin, 'DNSKEY')
    dns.dnssec.validate(rrset, signature, {fixture.zone.origin: trusted})


def trial(adb, fetch, output, family, expected_avd, validate):
    output.mkdir(parents=True, exist_ok=False)
    tag = 'wr5-' + uuid.uuid4().hex
    program = 'iptables' if family == 4 else 'ip6tables'
    rule = ['-i', 'tun+', '-p', 'tcp', '--sport', '53', '-m', 'length', '--length', '1000:',
            '-m', 'statistic', '--mode', 'nth', '--every', '1000', '--packet', '0',
            '-m', 'comment', '--comment', tag, '-j', 'DROP']
    report = dict(ruleTag=tag, family=family, commands=[], errors=[], counter=None,
                  cleanupVerified=False, clientResponseValidated=False,
                  retransmissionVerified=False, packetCaptureRequired=True, campaignComplete=False)
    attempted = False

    def command(*args, check=True):
        result = adb(*args)
        report['commands'].append(dict(args=list(args), returncode=result.returncode,
                                       stdout=result.stdout, stderr=result.stderr))
        if check and result.returncode:
            raise RuntimeError(f'command failed: {args!r}: {result.stderr.strip()}')
        return result

    def listing():
        return command('shell', program, '-w', '5', '-L', 'INPUT', '-v', '-x', '-n').stdout

    def tagged(text):
        return [line for line in text.splitlines() if tag in line]

    try:
        if command('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != '1':
            raise ValueError('only an emulator is allowed')
        if command('emu', 'avd', 'name').stdout.splitlines()[0].strip() != expected_avd:
            raise ValueError('emulator AVD does not match the explicitly selected target')
        if command('shell', 'id', '-u').stdout.strip() != '0':
            raise ValueError('root adbd required; this runner does not enable it')
        (output / 'rules-before.txt').write_text(listing())
        attempted = True  # A timed-out insert may still have reached the emulator.
        command('shell', program, '-w', '5', '-I', 'INPUT', '1', *rule)
        response = fetch()
        (output / 'client.json').write_text(json.dumps(response, indent=2) + '\n')
        validate(response)
        report['clientResponseValidated'] = True
    except (Exception, KeyboardInterrupt) as error:
        report['errors'].append(f'{type(error).__name__}: {error}')
    finally:
        if attempted:
            try:
                current = listing()
                (output / 'rules-after-probe.txt').write_text(current)
                rows = tagged(current)
                if len(rows) != 1:
                    raise ValueError('expected exactly one tagged rule when reading its counter')
                report['counter'] = int(rows[0].split()[0])
                if report['counter'] != 1:
                    raise ValueError(f'exactly one dropped segment required; got {report["counter"]}')
            except (Exception, KeyboardInterrupt) as error:
                report['errors'].append(f'counter: {error}')
            try:
                deleted = command('shell', program, '-w', '5', '-D', 'INPUT', *rule, check=False)
                after = listing()
                (output / 'rules-after-cleanup.txt').write_text(after)
                report['cleanupVerified'] = not tagged(after)
                if deleted.returncode or not report['cleanupVerified']:
                    raise ValueError('tagged DROP rule cleanup did not complete cleanly')
            except (Exception, KeyboardInterrupt) as error:
                report['errors'].append(f'cleanup: {error}')
        report['cleanupCommand'] = ['shell', program, '-w', '5', '-D', 'INPUT', *rule]
        (output / 'trial.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--family', type=int, choices=(4, 6), required=True)
    parser.add_argument('--url', type=probe_url, required=True)
    parser.add_argument('--signed-zone', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--adb', default=str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'))
    args = parser.parse_args()
    from tcp_dns_zone import SignedZone
    fixture = SignedZone.load(args.signed_zone)
    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command], text=True, capture_output=True, timeout=15)
    def fetch():
        with urllib.request.urlopen(args.url, timeout=25) as response:
            return json.load(response)
    def interrupted(signum, frame):
        raise KeyboardInterrupt(f'signal {signum}')
    signal.signal(signal.SIGTERM, interrupted)
    report = trial(adb, fetch, args.output, args.family, args.avd, lambda value: validate_reply(value, fixture, args.family))
    print(json.dumps({key: report[key] for key in ('counter', 'clientResponseValidated', 'cleanupVerified', 'errors', 'retransmissionVerified', 'campaignComplete')}, indent=2))
    if report['errors']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
