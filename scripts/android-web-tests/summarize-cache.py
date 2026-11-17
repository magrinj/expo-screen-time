#!/usr/bin/env python3
"""Correlate W-R3 client events with a completed tcpdump Linux-SLL2 capture.

No service counters are used to infer packets. Client and pcap timestamps both
come from Android; host/server wall clocks are deliberately not subtracted.
TCP DNS is not reassembled: captures containing TCP/53 refuse absence claims.
HTTP entries are packet-local GET headers, not a complete HTTP stream decoder.
"""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import struct

TUNNEL = {'10.111.222.1', '10.111.222.3', 'fd00:6578:706f::1', 'fd00:6578:706f::3'}


def dns_name(data, offset, seen=None):
    seen = set() if seen is None else set(seen)
    labels = []
    while True:
        if offset in seen or offset >= len(data):
            raise ValueError('invalid DNS name')
        seen.add(offset)
        size = data[offset]
        if size == 0:
            return '.'.join(labels), offset + 1
        if size & 0xc0 == 0xc0:
            if offset + 1 >= len(data):
                raise ValueError('truncated DNS pointer')
            ptr = ((size & 0x3f) << 8) | data[offset + 1]
            suffix, _ = dns_name(data, ptr, seen)
            labels.append(suffix)
            return '.'.join(labels), offset + 2
        if size > 63 or offset + size + 1 > len(data):
            raise ValueError('invalid label')
        labels.append(data[offset + 1:offset + size + 1].decode('ascii'))
        offset += size + 1


def packets(path):
    with path.open('rb') as f:
        header = f.read(24)
        if len(header) != 24:
            raise ValueError('missing pcap header')
        endian = '<' if header[:4] == b'\xd4\xc3\xb2\xa1' else '>' if header[:4] == b'\xa1\xb2\xc3\xd4' else None
        if endian is None or struct.unpack_from(endian + 'I', header, 20)[0] != 276:
            raise ValueError('Expected microsecond pcap with Linux SLL2 link type')
        while record := f.read(16):
            if len(record) != 16:
                raise ValueError('truncated pcap record')
            sec, micro, captured, original = struct.unpack(endian + 'IIII', record)
            raw = f.read(captured)
            if len(raw) != captured or captured != original:
                raise ValueError('truncated packet; absence claims are not valid')
            if len(raw) < 20:
                raise ValueError('truncated SLL2 header')
            protocol, _, index, hardware, direction, addrlen = struct.unpack_from('!HHIHBB', raw)
            payload = raw[20:]
            row = dict(atMs=sec * 1000 + micro / 1000, interface=index, direction=direction)
            if protocol == 0x0800:
                ihl = (payload[0] & 15) * 4
                if struct.unpack_from('!H', payload, 6)[0] & 0x3fff:
                    raise ValueError('Fragmented IPv4 packet not supported by this cache analyzer')
                kind = payload[9]
                src, dst = (str(ipaddress.ip_address(payload[i:i + 4])) for i in (12, 16))
                transport = payload[ihl:]
            elif protocol == 0x86dd:
                kind, offset = payload[6], 40
                src, dst = (str(ipaddress.ip_address(payload[i:i + 16])) for i in (8, 24))
                while kind in (0, 43, 60):
                    next_kind, length = payload[offset], (payload[offset + 1] + 1) * 8
                    offset += length
                    kind = next_kind
                if kind in (44, 51):
                    raise ValueError('Fragment/AH IPv6 packet not supported by this cache analyzer')
                transport = payload[offset:]
            else:
                continue
            row.update(src=src, dst=dst, protocol=kind)
            if kind not in (6, 17):
                continue
            sport, dport = struct.unpack_from('!HH', transport)
            row.update(sport=sport, dport=dport)
            if kind == 17:
                if 53 not in (sport, dport):
                    continue
                data = transport[8:]
                ident, flags, questions, answers, auth, extra = struct.unpack_from('!HHHHHH', data)
                if questions != 1:
                    raise ValueError('Expected exactly one DNS question')
                name, offset = dns_name(data, 12)
                qtype, cls = struct.unpack_from('!HH', data, offset)
                offset += 4
                ttls = []
                for _ in range(answers):
                    _, offset = dns_name(data, offset)
                    _, _, ttl, size = struct.unpack_from('!HHIH', data, offset)
                    ttls.append(ttl)
                    offset += 10 + size
                row.update(dns=dict(name=name, qtype=qtype, id=ident, response=bool(flags & 0x8000),
                                    rcode=flags & 15, ttls=ttls, authorityCount=auth))
            else:
                offset = (transport[12] >> 4) * 4
                row.update(flags=transport[13], payloadBytes=len(transport) - offset)
                if 53 in (sport, dport):
                    row.update(sequence=struct.unpack_from('!I', transport, 4)[0],
                               dnsTcpHex=transport[offset:].hex())
                if 8088 in (sport, dport):
                    content = transport[offset:]
                    if content.startswith(b'GET '):
                        lines = content.decode('utf-8', errors='replace').split('\r\n')
                        row['http'] = dict(request=lines[0], host=next((l[6:] for l in lines if l.lower().startswith('host: ')), None))
            yield row


def summarize(pcap, events):
    rows = list(packets(pcap))
    tcp_dns = [r for r in rows if r['protocol'] == 6 and 53 in (r['sport'], r['dport'])]
    if tcp_dns:
        raise ValueError('TCP/53 is present; use a DNS-over-TCP stream decoder before making absence claims')
    dns = [r for r in rows if 'dns' in r]
    tunneled = [r for r in dns if r['dst'] in TUNNEL and not r['dns']['response']]
    trials = []
    for i, event in enumerate(events):
        if event['event'] != 'swapObserved':
            continue
        end = next((x for x in events[i + 1:] if x['event'] == 'watchDone' and x.get('zone') == event['zone']), None)
        if end is None:
            trials.append(dict(zone=event['zone'], complete=False))
            continue
        samples = []
        for lookup in end['samples']:
            sent = [r for r in tunneled if r['dns']['name'] == lookup['name'] and
                    lookup['startedAtMs'] - 2 <= r['atMs'] <= lookup['finishedAtMs'] + 2]
            samples.append(dict(mode=lookup['mode'], name=lookup['name'], success=lookup['success'],
                                offsetMs=lookup['startedAtMs'] - event['atMs'], durationMs=lookup['durationMs'],
                                tunnelQueries=len(sent), firstTunnelOffsetMs=sent[0]['atMs'] - event['atMs'] if sent else None))
        last_warm = next((x for x in reversed(events[:i]) if x['event'] == 'lookup' and x.get('name') == 'vm.' + event['zone']), None)
        trials.append(dict(zone=event['zone'], complete=True, swapAtMs=event['atMs'],
                           before=event['before'], after=event['after'], networkUnchanged=event['before']['network'] == event['after']['network'],
                           vmWarmAgeMs=event['atMs'] - last_warm['finishedAtMs'] if last_warm else None, samples=samples))
    names = sorted({r['dns']['name'] for r in dns if r['dns']['name'].endswith('.r3.test')})
    pings = [r for r in events if r['event'] == 'holdPing']
    summary = dict(pcapSha256=hashlib.sha256(pcap.read_bytes()).hexdigest(),
        parsedPackets=len(rows), tcpDnsPackets=len(tcp_dns),
        port853Packets=sum(853 in (r['sport'], r['dport']) for r in rows),
        port853PayloadBytes=sum(r.get('payloadBytes', 0) for r in rows if 853 in (r['sport'], r['dport'])),
        trials=trials, dnsNames={name: dict(
        tunnelQueries=[dict(atMs=r['atMs'], dst=r['dst'], qtype=r['dns']['qtype']) for r in tunneled if r['dns']['name'] == name],
        tunnelResponses=[dict(atMs=r['atMs'], src=r['src'], qtype=r['dns']['qtype'],
                              rcode=r['dns']['rcode'], ttls=r['dns']['ttls']) for r in dns
                         if r['src'] in TUNNEL and r['dns']['response'] and r['dns']['name'] == name],
        answerTtls=sorted({ttl for r in dns if r['dns']['name'] == name for ttl in r['dns']['ttls']}),
        answerRcodes=sorted({r['dns']['rcode'] for r in dns if r['dns']['name'] == name and r['dns']['response']})) for name in names},
        holdErrors=[r for r in events if r['event'] == 'holdError'],
        holdConnections=[r for r in events if r['event'] == 'holdOpen'],
        holdPings=len(pings),
        holdContinuity=dict(firstAtMs=pings[0]['atMs'], lastAtMs=pings[-1]['atMs'],
                            durationMs=pings[-1]['atMs'] - pings[0]['atMs'],
                            localPorts=sorted({r['localPort'] for r in pings}),
                            consecutive=[r['sequence'] for r in pings] == list(range(1, len(pings) + 1)),
                            maxPingIntervalMs=max((b['elapsedMs'] - a['elapsedMs'] for a, b in zip(pings, pings[1:])), default=0)) if pings else None,
        httpSyns=[r for r in rows if r['protocol'] == 6 and r['dport'] == 8088 and r['flags'] & 2 and not r['flags'] & 16],
        echoSyns=[r for r in rows if r['protocol'] == 6 and r['dport'] == 8089 and r['flags'] & 2],
        echoResets=[r for r in rows if r['protocol'] == 6 and 8089 in (r['sport'], r['dport']) and r['flags'] & 4],
        httpRequests=[r for r in rows if 'http' in r])
    return summary


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--pcap', type=Path, required=True)
    p.add_argument('--events', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    events = [json.loads(line) for line in args.events.read_text().splitlines()]
    result = summarize(args.pcap, events)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ('trials', 'holdErrors', 'holdPings')}, indent=2))


if __name__ == '__main__':
    main()
