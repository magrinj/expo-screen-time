#!/usr/bin/env python3
"""Reconstruct W-R5 DNS/TCP observations from a completed single-vantage capture.

This is an offline reader, not a W-R5 acceptance gate. Repeated payload alone
cannot distinguish a retransmission from duplicate capture observations. Keep
client results, tagged DROP counters, swap logs and capture provenance alongside it.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
from tcp_capture import message, packets, require, transport
from tcp_streams import connections


def analyze(pcap, stats, interface):
    counts, tcp, udp, total = {}, [], [], 0
    for packet in packets(pcap):
        total += 1
        index = packet['interface']
        counts[index] = counts.get(index, 0) + 1
        if index != interface:
            continue
        row = transport(packet)
        if row is None:
            continue
        if row['protocol'] == 6:
            tcp.append(row)
        else:
            decoded = message(row['payload'])
            udp.append(dict(packet=row['packet'], atUs=row['atUs'], src=row['src'], dst=row['dst'],
                            sport=row['sport'], dport=row['dport'], dns=decoded))
    text = Path(stats).read_text()
    captured = re.findall(r'^(\d+) packets captured$', text, re.M)
    drops = re.findall(r'^(\d+) packets dropped by kernel$', text, re.M)
    require(len(captured) == 1 and int(captured[0]) == total, 'capture count mismatch/missing statistics')
    require(drops == ['0'], 'missing zero kernel-drop evidence')
    require(interface in counts and tcp, 'chosen interface has no TCP/53 packets')
    flows = connections(tcp)
    decoded = all(not flow['errors'] for flow in flows)
    ended = decoded and all(flow['closureAcknowledged'] or flow['resetObserved'] for flow in flows)
    return dict(pcapSha256=hashlib.sha256(Path(pcap).read_bytes()).hexdigest(),
                statsSha256=hashlib.sha256(Path(stats).read_bytes()).hexdigest(),
                capturedPackets=total, interfacePacketCounts=counts, selectedInterface=interface,
                tcpDnsPackets=len(tcp), udpDnsMessages=udp, connections=flows,
                allStreamsDecoded=decoded, allConnectionsHaveEndMarkers=ended,
                campaignComplete=False, wR5Complete=False, retransmissionVerified=False,
                limitations=[
                    'Only one explicitly selected SLL2 interface; never combine duplicate vantage points.',
                    'Identical overlap is a repeated observation, not by itself proof of loss/retransmission.',
                    'Frame completion is capture visibility; delivery still needs client bytes and results.',
                    'RST observed does not prove responder cleanup or lack of stalls on other connections.',
                    'DNS RDATA and signatures, packet checksums and kernel TCP state are not validated.',
                    'No repetition count, DROP-rule attribution, DNS-pair swap or W-R5 verdict is inferred.',
                ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pcap', required=True, type=Path)
    parser.add_argument('--stats', required=True, type=Path, help='final tcpdump statistics')
    parser.add_argument('--interface', required=True, type=int, help='SLL2 interface index from this capture')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = analyze(args.pcap, args.stats, args.interface)
    except (ValueError, OSError) as error:
        # An old successful output must never survive a failed analysis rerun.
        result = dict(errors=[str(error)], allStreamsDecoded=False, allConnectionsHaveEndMarkers=False,
                      campaignComplete=False, wR5Complete=False, retransmissionVerified=False)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: result[key] for key in
                      ('allStreamsDecoded', 'allConnectionsHaveEndMarkers', 'wR5Complete')}, indent=2))
    if not result['allStreamsDecoded'] or not result['allConnectionsHaveEndMarkers']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
