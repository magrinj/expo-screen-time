#!/usr/bin/env python3
"""Synthetic wire transcripts: test the offline reader, never Android acceptance."""
import copy
import importlib.util
import ipaddress
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
from tcp_capture import message
from tcp_streams import connections

_spec = importlib.util.spec_from_file_location('tcp_analysis', Path(__file__).with_name('analyze-tcp.py'))
reader = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(reader)


def dns(identifier=1, response=False, name='a.r3.test', qtype=1, payload=None, tc=False):
    labels = b''.join(bytes([len(label)]) + label.encode() for label in name.split('.')) + b'\x00'
    wire = struct.pack('!6H', identifier, (0x8180 if response else 0x100) | (0x200 if tc else 0),
                       1, int(response and not tc), 0, 0) + labels + struct.pack('!HH', qtype, 1)
    if response and not tc:
        rdata = b'\x0a\x00\x02\x02' if payload is None else payload
        wire += b'\xc0\x0c' + struct.pack('!HHIH', qtype, 1, 60, len(rdata)) + rdata
    return wire


def framed(wire):
    return struct.pack('!H', len(wire)) + wire


class Trace:
    def __init__(self, family=4, client_isn=100, server_isn=500, interface=9):
        self.rows = []
        self.addresses = (['10.111.222.2', '10.111.222.3'] if family == 4 else
                          ['fd00:6578:706f::2', 'fd00:6578:706f::3'])
        self.next = [client_isn, server_isn]
        self.interface = interface
        self.send(flags=2, ack=0)
        self.send(server=True, flags=18)
        self.send()

    def send(self, payload=b'', server=False, flags=16, offset=None, ack=None):
        side = int(server)
        sequence = self.next[side] if offset is None else offset
        self.rows.append(dict(packet=len(self.rows) + 1, atUs=1000000 + len(self.rows) * 10000,
                              interface=self.interface, direction=0 if server else 4,
                              src=self.addresses[side], dst=self.addresses[1 - side],
                              sport=53 if server else 40001, dport=40001 if server else 53,
                              protocol=6, sequence=sequence % (1 << 32),
                              ack=(self.next[1 - side] if ack is None else ack) % (1 << 32),
                              flags=flags, payload=payload))
        if offset is None:
            self.next[side] += len(payload) + int(bool(flags & 2)) + int(bool(flags & 1))
        return self.rows[-1]

    def exchange(self, identifier=1, qtype=1, payload=None):
        self.send(framed(dns(identifier, qtype=qtype)))
        self.send(framed(dns(identifier, True, qtype=qtype, payload=payload)), server=True)
        self.send()

    def close(self):
        self.send(flags=17)
        self.send(server=True)
        self.send(server=True, flags=17)
        self.send()

    def flow(self):
        return connections(self.rows)[0]


def raw_packet(row):
    body = row['payload']
    if row['protocol'] == 6:
        body = struct.pack('!HHIIBBHHH', row['sport'], row['dport'], row['sequence'], row['ack'],
                           0x50, row['flags'], 65535, 0, 0) + body
    else:
        body = struct.pack('!HHHH', row['sport'], row['dport'], len(body) + 8, 0) + body
    source, dest = ipaddress.ip_address(row['src']), ipaddress.ip_address(row['dst'])
    if source.version == 4:
        protocol = 0x0800
        body = struct.pack('!BBHHHBBH4s4s', 0x45, 0, len(body) + 20, row['packet'], 0x4000,
                           64, row['protocol'], 0, source.packed, dest.packed) + body
    else:
        protocol = 0x86dd
        body = struct.pack('!IHBB16s16s', 6 << 28, len(body), row['protocol'], 64,
                           source.packed, dest.packed) + body
    return struct.pack('!HHIHBB8s', protocol, 0, row['interface'], 1, row['direction'], 0, b'\x00' * 8) + body


def pcap(rows, endian='<'):
    header = struct.pack(endian + 'IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 276)
    for row in rows:
        raw = raw_packet(row)
        sec, micro = divmod(row['atUs'], 1000000)
        header += struct.pack(endian + '4I', sec, micro, len(raw), len(raw)) + raw
    return header


class StreamTest(unittest.TestCase):
    def good(self, trace):
        result = trace.flow()
        self.assertEqual(result['errors'], [])
        return result

    def bad(self, trace, pattern):
        self.assertRegex(' '.join(trace.flow()['errors']), pattern)

    def test_normal_exchange_with_acknowledged_close(self):
        trace = Trace()
        trace.exchange()
        trace.close()
        result = self.good(trace)
        self.assertTrue(result['closureAcknowledged'])
        self.assertTrue(result['allQueriesAnswered'])
        self.assertTrue(result['transactions'][0]['responseAcknowledged'])

    def test_dns_prefix_and_body_in_separate_captured_segments(self):
        trace = Trace()
        frame = framed(dns())
        trace.send(frame[:2])
        trace.send(frame[2:])
        self.assertTrue(self.good(trace)['client']['frames'][0]['prefixBodySeparateSegments'])

    def test_prefix_split_one_byte_each_is_distinguished(self):
        trace = Trace()
        frame = framed(dns())
        trace.send(frame[:1])
        trace.send(frame[1:])
        decoded = self.good(trace)['client']['frames'][0]
        self.assertTrue(decoded['prefixSplitAcrossSegments'])
        self.assertFalse(decoded['prefixBodySeparateSegments'])

    def test_coalesced_write_is_not_a_split(self):
        trace = Trace()
        trace.exchange()
        self.assertFalse(self.good(trace)['client']['frames'][0]['prefixBodySeparateSegments'])

    def test_pipeline_and_reverse_reply_order(self):
        trace = Trace()
        trace.send(framed(dns(1)) + framed(dns(2, name='b.r3.test')))
        trace.send(framed(dns(2, True, name='b.r3.test')) + framed(dns(1, True)), server=True)
        trace.send()
        trace.close()
        result = self.good(trace)
        self.assertEqual(result['queryOrder'], [1, 2])
        self.assertEqual(result['replyOrder'], [2, 1])
        self.assertEqual(result['queriesBeforeFirstReply'], [0, 1])
        self.assertEqual([row['query'] for row in result['transactions']], [1, 0])

    def test_eleven_exchanges_stay_on_one_connection(self):
        trace = Trace()
        for index in range(11):
            trace.exchange(index + 1)
        trace.close()
        result = self.good(trace)
        self.assertEqual(len(result['transactions']), 11)
        self.assertEqual(len(connections(trace.rows)), 1)

    def test_out_of_order_data_is_reassembled(self):
        trace = Trace()
        wire = framed(dns())
        trace.send(wire[8:], offset=109)
        trace.send(wire[:8], offset=101)
        trace.next[0] += len(wire)
        trace.send(framed(dns(response=True)), server=True)
        trace.send()
        trace.close()
        result = self.good(trace)
        self.assertEqual(result['client']['frames'][0]['name'], 'a.r3.test')
        self.assertEqual(result['client']['frames'][0]['completePacket'], 5)

    def test_later_frame_waits_for_earlier_stream_gap(self):
        trace = Trace()
        first, second = framed(dns(1)), framed(dns(2))
        trace.send(second, offset=101 + len(first))
        trace.send(first)
        trace.next[0] += len(second)
        trace.send(framed(dns(1, True)) + framed(dns(2, True)), server=True)
        trace.send()
        result = self.good(trace)
        frames = result['client']['frames']
        self.assertEqual([row['completePacket'] for row in frames], [5, 5])
        self.assertEqual([row['bytesObservedPacket'] for row in frames], [5, 4])
        self.assertEqual([row['query'] for row in result['transactions']], [0, 1])

    def test_identical_retransmission_does_not_duplicate_dns_frame(self):
        trace = Trace()
        trace.send(framed(dns(qtype=48)))
        wire = framed(dns(response=True, qtype=48, payload=b'K' * 1700))
        trace.send(wire[:1400], server=True)
        trace.send(wire[1400:], server=True)
        trace.send(wire[:1400], server=True, offset=501)
        trace.send()
        trace.close()
        result = self.good(trace)
        self.assertEqual(len(result['server']['frames']), 1)
        repeat = result['server']['repeatedPayload'][0]
        self.assertEqual(repeat['repeatedBytes'], 1400)
        self.assertEqual(repeat['ackedThroughBefore'], 0)
        self.assertIsNotNone(repeat['ackCoversSegmentAfterPacket'])
        self.assertFalse(result['retransmissionVerified'])

    def test_repeat_after_ack_is_distinguished(self):
        trace = Trace()
        trace.exchange()
        trace.send(framed(dns(response=True)), server=True, offset=501)
        result = self.good(trace)
        self.assertEqual(result['server']['repeatedPayload'][0]['ackedThroughBefore'],
                         len(framed(dns(response=True))))

    def test_partial_identical_overlap_is_accepted_once(self):
        trace = Trace()
        wire = framed(dns())
        trace.send(wire[:10])
        trace.send(wire[6:], offset=107)
        result = self.good(trace)
        self.assertEqual(result['client']['payloadBytes'], len(wire))
        self.assertEqual(result['client']['repeatedPayload'][0]['repeatedBytes'], 4)

    def test_contradictory_overlap_fails(self):
        trace = Trace()
        trace.send(framed(dns()))
        trace.send(b'wrong', offset=103)
        self.bad(trace, 'contradictory')

    def test_missing_segment_fails_even_if_later_fin_exists(self):
        trace = Trace()
        wire = framed(dns())
        trace.send(wire[4:], offset=105)
        trace.next[0] += len(wire)
        trace.close()
        self.bad(trace, 'missing TCP')

    def test_missing_prefix_and_body_tail_fail(self):
        for payload in (b'\x00', b'\x00\xff' + dns(), b'\x00\x00'):
            with self.subTest(payload=payload):
                trace = Trace()
                trace.send(payload)
                self.bad(trace, 'incomplete or invalid|incomplete DNS length')

    def test_missing_acknowledged_bytes_fail(self):
        trace = Trace()
        trace.exchange()
        trace.send(server=True, ack=trace.next[0] + 1)
        self.bad(trace, 'ACK covers uncaptured')

    def test_ack_of_missing_original_is_not_repaired_by_later_repeat(self):
        trace = Trace()
        wire = framed(dns())
        trace.send(server=True, ack=101 + len(wire))
        trace.send(wire)
        self.bad(trace, 'ACK precedes captured bytes')

    def test_duplicate_syn_ack_cannot_acknowledge_unsent_bytes(self):
        trace = Trace()
        retry = copy.deepcopy(trace.rows[1])
        retry['ack'] += 1
        trace.rows.insert(2, retry)
        for index, row in enumerate(trace.rows, 1):
            row['packet'] = index
        trace.exchange()
        self.bad(trace, 'conflicting SYN/ACK')

    def test_reset_and_other_connection_remain_separate(self):
        trace, control = Trace(), Trace()
        trace.send(framed(dns()))
        trace.send(flags=20)
        control.exchange(2)
        control.close()
        shift = len(trace.rows)
        for row in control.rows:
            row['packet'] += shift
            row['sport' if row['sport'] != 53 else 'dport'] = 40002
        flows = connections(trace.rows + control.rows)
        self.assertEqual(len(flows), 2)
        self.assertTrue(flows[0]['resetObserved'])
        self.assertEqual(flows[0]['unansweredQueries'], [0])
        self.assertTrue(flows[1]['allQueriesAnswered'])
        self.assertTrue(flows[1]['closureAcknowledged'])

    def test_missing_fin_ack_is_not_a_completed_close(self):
        trace = Trace()
        trace.exchange()
        trace.close()
        trace.rows.pop()
        self.assertFalse(self.good(trace)['closureAcknowledged'])

    def test_no_fin_is_explicitly_open(self):
        trace = Trace()
        trace.exchange()
        self.assertFalse(self.good(trace)['closureAcknowledged'])

    def test_half_close_pending_reply_then_server_fin(self):
        trace = Trace()
        trace.send(framed(dns()))
        trace.send(flags=17)
        trace.send(framed(dns(response=True)), server=True)
        trace.send()
        trace.send(server=True, flags=17)
        trace.send()
        result = self.good(trace)
        self.assertTrue(result['transactions'][0]['clientFinBeforeReply'])
        self.assertTrue(result['transactions'][0]['replyBeforeServerFin'])
        self.assertTrue(result['closureAcknowledged'])

    def test_answer_then_half_close_is_not_pending(self):
        trace = Trace()
        trace.exchange()
        trace.close()
        self.assertFalse(self.good(trace)['transactions'][0]['clientFinBeforeReply'])

    def test_reset_keeps_unanswered_question_visible(self):
        trace = Trace()
        trace.send(framed(dns()))
        trace.send(flags=20)
        result = self.good(trace)
        self.assertTrue(result['resetObserved'])
        self.assertEqual(result['unansweredQueries'], [0])
        self.assertFalse(result['allQueriesAnswered'])

    def test_reset_does_not_hide_incomplete_frame(self):
        trace = Trace()
        trace.send(framed(dns())[:8])
        trace.send(flags=20)
        result = trace.flow()
        self.assertTrue(result['resetPackets'])
        self.assertRegex(result['errors'][0], 'incomplete')

    def test_sequence_number_wrap(self):
        trace = Trace(client_isn=(1 << 32) - 8, server_isn=(1 << 32) - 12)
        trace.exchange()
        trace.close()
        self.assertTrue(self.good(trace)['closureAcknowledged'])

    def test_same_tuple_reconnect_produces_two_generations(self):
        for same_isn in (True, False):
            with self.subTest(same_isn=same_isn):
                trace, second = Trace(), Trace(client_isn=100 if same_isn else 12345)
                trace.exchange()
                trace.close()
                second.exchange(2)
                second.close()
                shift = len(trace.rows)
                for row in second.rows:
                    row['packet'] += shift
                    row['atUs'] += shift * 10000
                decoded = connections(trace.rows + second.rows)
                self.assertEqual(len(decoded), 2)
                self.assertEqual([flow['queryOrder'] for flow in decoded], [[1], [2]])

    def test_active_tuple_new_syn_is_ambiguous(self):
        trace = Trace()
        trace.exchange()
        trace.send(flags=2, offset=999)
        self.bad(trace, 'SYN after established')

    def test_syn_retries_before_handshake(self):
        trace = Trace()
        retry = copy.deepcopy(trace.rows[0])
        trace.rows.insert(1, retry)
        for index, row in enumerate(trace.rows, 1):
            row['packet'], row['atUs'] = index, 1000000 + index * 10000
        trace.exchange()
        self.good(trace)

    def test_missing_handshake_or_wrong_handshake_ack(self):
        trace = Trace()
        trace.exchange()
        trace.rows[1]['ack'] = 999
        self.bad(trace, 'wrong SYN')
        trace.rows.pop(0)
        self.bad(trace, 'expected SYN')

    def test_wrong_reply_identity_and_duplicate_query_fail(self):
        trace = Trace()
        trace.send(framed(dns()))
        trace.send(framed(dns(2, True)), server=True)
        self.bad(trace, 'unmatched')
        trace = Trace()
        trace.send(framed(dns()) * 2)
        self.bad(trace, 'ambiguous outstanding')

    def test_wrong_dns_direction_fails(self):
        trace = Trace()
        trace.send(framed(dns(response=True)))
        self.bad(trace, 'wrong direction')

    def test_payload_beyond_fin_fails(self):
        trace = Trace()
        trace.send(flags=17)
        trace.send(framed(dns()))
        self.bad(trace, 'beyond FIN')

    def test_huge_sequence_jump_is_bounded(self):
        trace = Trace()
        trace.send(framed(dns()), offset=1 << 29)
        self.bad(trace, 'bounded stream')


class CaptureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.capture, self.stats = self.root / 'test.pcap', self.root / 'stats.txt'

    def write(self, trace, endian='<'):
        self.capture.write_bytes(pcap(trace.rows, endian))
        self.stats.write_text(f'{len(trace.rows)} packets captured\n0 packets dropped by kernel\n')

    def analyze(self, interface=9):
        return reader.analyze(self.capture, self.stats, interface)

    def test_ipv4_and_ipv6_both_endians(self):
        for family in (4, 6):
            for endian in ('<', '>'):
                with self.subTest(family=family, endian=endian):
                    trace = Trace(family)
                    trace.exchange()
                    trace.close()
                    self.write(trace, endian)
                    result = self.analyze()
                    self.assertTrue(result['allStreamsDecoded'])
                    self.assertTrue(result['allConnectionsHaveEndMarkers'])
                    self.assertFalse(result['wR5Complete'])
                    self.assertFalse(result['campaignComplete'])

    def test_interfaces_are_selected_not_concatenated(self):
        trace = Trace()
        trace.exchange()
        trace.close()
        dup = copy.deepcopy(trace.rows)
        for row in dup:
            row['interface'] = 10
        trace.rows = sorted(trace.rows + dup, key=lambda row: row['atUs'])
        self.write(trace)
        result = self.analyze()
        self.assertEqual(len(result['connections']), 1)
        self.assertEqual(result['capturedPackets'], len(trace.rows))
        self.assertEqual(result['tcpDnsPackets'], len(dup))
        with self.assertRaisesRegex(ValueError, 'chosen interface'):
            self.analyze(20)

    def test_udp_tc_and_tcp_full_answer_are_kept_separate(self):
        trace = Trace()
        trace.exchange(qtype=16, payload=b'x' * 2000)
        trace.close()
        template = copy.deepcopy(trace.rows[0])
        template.update(protocol=17, payload=dns(qtype=16), atUs=900000)
        reply = copy.deepcopy(template)
        reply.update(src=template['dst'], dst=template['src'], sport=53, dport=40001,
                     atUs=950000, payload=dns(response=True, qtype=16, tc=True))
        trace.rows = [template, reply] + trace.rows
        self.write(trace)
        result = self.analyze()
        self.assertTrue(result['udpDnsMessages'][1]['dns']['truncated'])
        self.assertGreater(result['connections'][0]['server']['frames'][0]['bytes'], 1460)

    def test_capture_count_or_drops_refuse_evidence(self):
        trace = Trace()
        trace.exchange()
        self.write(trace)
        for stats in ('0 packets captured\n0 packets dropped by kernel\n',
                      f'{len(trace.rows)} packets captured\n1 packets dropped by kernel\n', ''):
            with self.subTest(stats=stats):
                self.stats.write_text(stats)
                with self.assertRaises(ValueError):
                    self.analyze()

    def test_truncated_capture_refused(self):
        trace = Trace()
        trace.exchange()
        self.write(trace)
        self.capture.write_bytes(self.capture.read_bytes()[:-1])
        with self.assertRaisesRegex(ValueError, 'truncated'):
            self.analyze()

    def test_packet_length_headers_and_fragments_refused(self):
        trace = Trace()
        trace.exchange()
        self.write(trace)
        original = self.capture.read_bytes()
        # First packet: PCAP24 + record16 + SLL20, then IPv4 and TCP.
        for offset, replacement in ((62, b'\x00\x01'), (66, b'\x20\x00'), (92, b'\xf0')):
            with self.subTest(offset=offset):
                data = bytearray(original)
                data[offset:offset + len(replacement)] = replacement
                self.capture.write_bytes(data)
                with self.assertRaises(ValueError):
                    self.analyze()

    def test_ipv6_hop_options_decode_and_fragment_header_refuses(self):
        trace = Trace(family=6)
        trace.exchange()
        trace.close()
        self.write(trace)
        original = self.capture.read_bytes()
        data = bytearray(original)
        # Add a minimal hop-by-hop header to the first IPv6 packet.
        captured = struct.unpack_from('<I', data, 32)[0]
        struct.pack_into('<II', data, 32, captured + 8, captured + 8)
        payload = struct.unpack_from('!H', data, 64)[0]
        struct.pack_into('!H', data, 64, payload + 8)
        data[66] = 0
        data[100:100] = b'\x06\x00' + b'\x00' * 6
        self.capture.write_bytes(data)
        self.assertTrue(self.analyze()['allStreamsDecoded'])
        data[66] = 44
        self.capture.write_bytes(data)
        with self.assertRaisesRegex(ValueError, 'fragment'):
            self.analyze()

    def test_decreasing_timestamps_refused(self):
        trace = Trace()
        trace.rows[1]['atUs'] = 999999
        self.write(trace)
        with self.assertRaisesRegex(ValueError, 'timestamp'):
            self.analyze()

    def test_incomplete_stream_is_reported_and_cli_fails(self):
        trace = Trace()
        trace.send(b'\x00')
        self.write(trace)
        result = self.analyze()
        self.assertFalse(result['allStreamsDecoded'])
        self.assertTrue(result['connections'][0]['errors'])
        output = self.root / 'analysis.json'
        run = subprocess.run([sys.executable, str(Path(reader.__file__)), '--pcap', str(self.capture),
                              '--stats', str(self.stats), '--interface', '9', '--output', str(output)],
                             capture_output=True, text=True)
        self.assertEqual(run.returncode, 1)
        self.assertIn('incomplete DNS length', output.read_text())

    def test_failure_overwrites_old_success_output(self):
        trace = Trace()
        self.write(trace)
        self.stats.write_text('bad statistics')
        output = self.root / 'analysis.json'
        output.write_text('{"allStreamsDecoded":true}')
        run = subprocess.run([sys.executable, str(Path(reader.__file__)), '--pcap', str(self.capture),
                              '--stats', str(self.stats), '--interface', '9', '--output', str(output)],
                             capture_output=True, text=True)
        self.assertEqual(run.returncode, 1)
        self.assertIn('capture count', output.read_text())


class DnsTest(unittest.TestCase):
    def test_a_txt_dnskey_and_raw_rdata(self):
        for kind, payload in ((1, b'\x0a\x00\x02\x02'), (16, b'\x03txt'), (48, b'key')):
            with self.subTest(kind=kind):
                decoded = message(dns(response=True, qtype=kind, payload=payload))
                self.assertEqual(decoded['qtype'], kind)
                self.assertEqual(decoded['records'][0]['bytes'], len(payload))

    def test_bad_pointer_truncated_rdata_and_extra_bytes(self):
        wire = dns(response=True)
        for malformed in (wire[:-1], wire + b'\x00', wire[:12] + b'\xc0\x0c' + wire[14:]):
            with self.subTest(wire=malformed):
                with self.assertRaises(ValueError):
                    message(malformed)


if __name__ == '__main__':
    unittest.main()
