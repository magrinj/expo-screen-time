#!/usr/bin/env python3
"""Replay November 11's two TCP-DNS probes, independently of the UDP campaign.

Intentionally bounded: one question per connection, SYN/FIN in both directions,
ordered contiguous payload, no retransmission/reset, at most 65537 framed bytes.
Reject unsupported captures rather than inferring a general TCP/W-R5 verdict.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import sys

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('cache_capture', Path(__file__).with_name('summarize-cache.py'))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


def check(condition, message):
    if not condition:
        raise ValueError(message)


def frame(rows):
    """One directional stream; unsupported reordering/retransmission fails closed."""
    syns = [r for r in rows if r['flags'] & 2]
    fins = [r for r in rows if r['flags'] & 1]
    check(len(syns) == len(fins) == 1, 'one SYN and FIN required per direction')
    check(rows[0] is syns[0], 'capture starts after handshake')
    check(not any(r['flags'] & 4 for r in rows), 'RST is outside this probe')
    expected = (syns[0]['sequence'] + 1) % (1 << 32)
    content = bytearray()
    sizes = []
    ended = False
    for row in rows:
        payload = bytes.fromhex(row['dnsTcpHex'])
        if payload:
            check(not ended and not row['flags'] & 2, 'payload outside established stream')
            check(row['sequence'] == expected, 'gap, reordering or retransmission')
            check(len(content) + len(payload) <= 65537, 'stream exceeds one DNS frame')
            content.extend(payload)
            sizes.append(len(payload))
            expected = (expected + len(payload)) % (1 << 32)
        if row['flags'] & 1:
            check((row['sequence'] + len(payload)) % (1 << 32) == expected, 'FIN before complete payload')
            ended = True
    check(len(content) >= 14, 'missing DNS frame')
    check(struct.unpack_from('!H', content)[0] == len(content) - 2, 'truncated or multiple DNS frames')
    return bytes(content[2:]), sizes


def message(data):
    check(len(data) >= 12, 'truncated DNS header')
    ident, flags, questions, answers, authority, additional = struct.unpack_from('!6H', data)
    check(questions == 1 and authority == additional == 0, 'unexpected DNS sections')
    name, offset = capture.dns_name(data, 12)
    check(data[offset:offset + 4] == b'\x00\x01\x00\x01', 'expected A/IN question')
    offset += 4
    records = []
    for _ in range(answers):
        owner, offset = capture.dns_name(data, offset)
        kind, cls, ttl, length = struct.unpack_from('!HHIH', data, offset)
        check(kind == cls == 1 and length == 4, 'expected IPv4 A answer')
        check(offset + 10 + length <= len(data), 'truncated DNS answer')
        records.append({'owner': owner, 'ttl': ttl, 'ttlOffset': offset + 4,
                        'address': data[offset + 10:offset + 14].hex()})
        offset += 10 + length
    check(offset == len(data), 'unparsed DNS bytes')
    return {'id': ident, 'flags': flags, 'name': name, 'records': records}


def verify(root):
    pcap = root / 'capture.pcap'
    rows = list(capture.packets(pcap))
    stats = (root / 'tcpdump-stats.txt').read_text()
    count = re.search(r'^(\d+) packets captured$', stats, re.M)
    check(count and int(count[1]) == len(rows), 'capture count mismatch')
    check(re.search(r'^0 packets dropped by kernel$', stats, re.M), 'missing zero-drop evidence')
    flows = {}
    for row in rows:
        check(row['protocol'] == 6 and 53 in (row['sport'], row['dport']), 'expected TCP/53-only capture')
        server_to_client = row['sport'] == 53
        key = (row['dst'], row['dport'], row['src']) if server_to_client else (row['src'], row['sport'], row['dst'])
        flows.setdefault(key, [[], []])[int(server_to_client)].append(row)
    check(len(flows) == 3, 'expected two tunnel connections and one upstream connection')
    decoded = []
    for (client, port, server), directions in flows.items():
        query, query_sizes = frame(directions[0])
        reply, reply_sizes = frame(directions[1])
        q, a = message(query), message(reply)
        check(q['id'] == a['id'] == 0x5354 and q['name'] == a['name'], 'mismatched DNS reply')
        check(q['flags'] == 0x0100 and not q['records'] and a['flags'] & 0x8000, 'unexpected DNS flags')
        decoded.append({'client': client, 'port': port, 'server': server, 'query': query, 'reply': reply,
                        'answer': a, 'querySegmentBytes': query_sizes, 'replySegmentBytes': reply_sizes})
    allowed_name, blocked_name = 'allowed.tcp1.r3.test', 'cold.tcp1.ttlb1.r3.test'
    def one(name, server):
        matching = [f for f in decoded if f['answer']['name'] == name and f['server'] == server]
        check(len(matching) == 1, 'missing or ambiguous named flow')
        return matching[0]
    upstream = one(allowed_name, '10.0.2.2')
    allowed = one(allowed_name, '10.111.222.3')
    blocked = one(blocked_name, '10.111.222.3')
    check(upstream['query'] == allowed['query'], 'upstream query changed')
    check(upstream['answer']['flags'] == allowed['answer']['flags'] == 0x8580, 'unexpected allowed response flags')
    for flow, ttl in ((upstream, 3600), (allowed, 60)):
        records = flow['answer']['records']
        check(len(records) == 1 and records[0]['owner'] == allowed_name and
              records[0]['ttl'] == ttl and records[0]['address'] == '0a000202', 'unexpected TTL/address')
    capped = bytearray(upstream['reply'])
    struct.pack_into('!I', capped, upstream['answer']['records'][0]['ttlOffset'], 60)
    check(bytes(capped) == allowed['reply'], 'bytes besides the TTL changed')
    check(blocked['answer']['flags'] == 0x8583 and not blocked['answer']['records'], 'blocked reply is not empty NXDOMAIN')
    events = [json.loads(line) for line in (root / 'client-events.jsonl').read_text().splitlines()]
    clients = {}
    for label, flow in (('allowed', allowed), ('blocked', blocked)):
        client = json.loads((root / (label + '.json')).read_text())['result']
        check(client['name'] == flow['answer']['name'] and client['server'] == flow['server'], 'wrong client endpoint')
        check(client['network']['network'] == '104', 'unexpected VPN network')
        check(base64.b64decode(client['wireBase64'], validate=True) == flow['reply'], 'client/wire bytes differ')
        check(client in events, 'client result absent from on-device event log')
        clients[label] = {'name': client['name'], 'rcode': flow['answer']['flags'] & 15,
                          'responseBytes': len(flow['reply']), 'clientBytesMatch': True,
                          'durationMs': client['finishedAtMs'] - client['startedAtMs']}
    return {'pcapSha256': hashlib.sha256(pcap.read_bytes()).hexdigest(), 'packets': len(rows),
            'upstreamTtl': 3600, 'tunnelTtl': 60, 'onlyTtlChanged': True, 'clients': clients,
            'streams': [{k: v for k, v in f.items() if k not in ('query', 'reply', 'answer')} for f in decoded],
            'campaignComplete': False, 'wR5Complete': False,
            'scope': 'Two IPv4 A queries on separate connections; not the W-R5 transport/retry matrix'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = verify(args.evidence)
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    print(text, end='')


if __name__ == '__main__':
    main()
