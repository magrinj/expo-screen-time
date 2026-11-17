#!/usr/bin/env python3
"""Fail-closed checks for the deliberately narrow TCP proof reader."""
import copy
import importlib.util
from pathlib import Path
import struct
import sys
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('tcp_proof', Path(__file__).with_name('verify-tcp-ttl.py'))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class TcpProofTest(unittest.TestCase):
    def setUp(self):
        self.wire = bytes.fromhex('535401000001000000000000016104746573740000010001')
        framed = struct.pack('!H', len(self.wire)) + self.wire
        self.rows = [
            {'sequence': 100, 'flags': 2, 'dnsTcpHex': ''},
            {'sequence': 101, 'flags': 24, 'dnsTcpHex': framed[:1].hex()},
            {'sequence': 102, 'flags': 24, 'dnsTcpHex': framed[1:].hex()},
            {'sequence': 101 + len(framed), 'flags': 17, 'dnsTcpHex': ''},
        ]

    def test_split_length_prefix_is_reassembled(self):
        wire, sizes = proof.frame(self.rows)
        self.assertEqual(wire, self.wire)
        self.assertEqual(sizes, [1, len(self.wire) + 1])
        self.assertEqual(proof.message(wire)['name'], 'a.test')

    def test_missing_segment_is_rejected(self):
        del self.rows[1]
        with self.assertRaisesRegex(ValueError, 'gap'):
            proof.frame(self.rows)

    def test_duplicate_segment_is_not_silently_double_counted(self):
        self.rows.insert(2, copy.deepcopy(self.rows[1]))
        with self.assertRaisesRegex(ValueError, 'retransmission'):
            proof.frame(self.rows)

    def test_missing_handshake_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'SYN'):
            proof.frame(self.rows[1:])

    def test_missing_fin_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'FIN'):
            proof.frame(self.rows[:-1])

    def test_reset_is_rejected(self):
        self.rows[-1]['flags'] |= 4
        with self.assertRaisesRegex(ValueError, 'RST'):
            proof.frame(self.rows)

    def test_false_frame_length_is_rejected(self):
        self.rows[1]['dnsTcpHex'] = '01'
        with self.assertRaisesRegex(ValueError, 'truncated or multiple'):
            proof.frame(self.rows)

    def test_extra_dns_bytes_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'unparsed'):
            proof.message(self.wire + b'\x00')

    def test_partial_rdata_is_rejected(self):
        reply = bytearray(self.wire)
        struct.pack_into('!H', reply, 6, 1)
        reply += bytes.fromhex('c00c000100010000003c00040a0002')
        with self.assertRaisesRegex(ValueError, 'truncated DNS answer'):
            proof.message(reply)


if __name__ == '__main__':
    unittest.main()
