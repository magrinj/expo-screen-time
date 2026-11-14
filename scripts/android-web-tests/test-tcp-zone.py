#!/usr/bin/env python3
"""Signed fixture validation and real Java-client loopback integration; no emulator."""
import base64
import copy
from concurrent.futures import ThreadPoolExecutor
import importlib.util
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import unittest

import dns.dnssec
import dns.exception
import dns.flags
import dns.message
import dns.rdata
import dns.rdatatype
import dns.rcode
from tcp_dns_zone import SignedZone, generate, validate_zone

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('probe_tests', ROOT / 'test-tcp-probe.py')
probes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probes)
spec_loss = importlib.util.spec_from_file_location('loss_trial', ROOT / 'tcp-loss-trial.py')
loss = importlib.util.module_from_spec(spec_loss)
spec_loss.loader.exec_module(loss)


class SignedZoneTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture, cls.metadata = generate('signed.r3.test.')
        cls.temp = tempfile.TemporaryDirectory(prefix='w-r5-zone-')
        cls.build = Path(cls.temp.name)
        bridge = cls.build / 'ProbeHost.java'
        bridge.write_text(probes.BRIDGE)
        subprocess.run(['javac', '--release', '8', '-d', str(cls.build),
                        str(probes.SOURCE), str(bridge)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def query(self, name='signed.r3.test.', kind='DNSKEY', signed=True):
        return dns.message.make_query(name, kind, want_dnssec=signed).to_wire()

    def validate_answer(self, response):
        keys = self.fixture.zone.find_rrset(self.fixture.zone.origin, 'DNSKEY')
        rrset = next(row for row in response.answer if row.rdtype == dns.rdatatype.DNSKEY)
        signature = next(row for row in response.answer if row.rdtype == dns.rdatatype.RRSIG)
        dns.dnssec.validate(rrset, signature, {self.fixture.zone.origin: keys})

    def test_dnskey_answer_is_large_and_cryptographically_valid(self):
        wire = self.fixture.answer(self.query(), 'tcp')
        self.assertGreater(len(wire), 1460)
        self.validate_answer(dns.message.from_wire(wire))

    def test_ttl_cap_preserves_signature_validation(self):
        response = dns.message.from_wire(self.fixture.answer(self.query(), 'tcp'))
        for rrset in response.answer:
            rrset.ttl = 60
        self.validate_answer(dns.message.from_wire(response.to_wire()))

    def test_corrupted_signature_rejected(self):
        response = dns.message.from_wire(self.fixture.answer(self.query(), 'tcp'))
        signature = response.answer[1]
        raw = signature[0].to_wire()
        signature.clear()
        signature.add(dns.rdata.from_wire(1, 46, raw[:-1] + bytes([raw[-1] ^ 1]), 0, len(raw)), 3600)
        with self.assertRaises(dns.dnssec.ValidationFailure):
            self.validate_answer(response)

    def test_expired_zone_rejected(self):
        with self.assertRaises(dns.dnssec.ValidationFailure):
            validate_zone(self.fixture.zone, now=self.metadata['expiresAt'] + 1)

    def test_serialized_fixture_reloads_without_private_keys(self):
        path = self.build / 'signed.zone'
        self.fixture.zone.to_file(str(path), relativize=False, want_origin=True)
        restored = SignedZone.load(path)
        self.assertEqual(self.fixture.checked, restored.checked)
        self.assertNotIn('PRIVATE', path.read_text())
        self.assertFalse(self.metadata['privateKeysSaved'])

    def test_long_txt_has_tc_over_udp_and_full_answer_over_tcp(self):
        query = self.query('large.signed.r3.test.', 'TXT', signed=False)
        udp = dns.message.from_wire(self.fixture.answer(query, 'udp'))
        tcp = dns.message.from_wire(self.fixture.answer(query, 'tcp'))
        self.assertTrue(udp.flags & dns.flags.TC)
        self.assertLessEqual(len(udp.to_wire()), 512)
        self.assertFalse(tcp.flags & dns.flags.TC)
        self.assertGreater(len(tcp.to_wire()), 1460)
        self.assertEqual(12, len(tcp.answer[0][0].strings))

    def test_dnskey_without_do_does_not_claim_signed_large_response(self):
        answer = dns.message.from_wire(self.fixture.answer(self.query(signed=False), 'tcp'))
        self.assertEqual([dns.rdatatype.DNSKEY], [rr.rdtype for rr in answer.answer])
        self.assertLessEqual(len(answer.to_wire()), 1460)

    def test_unknown_positive_record_refused(self):
        response = dns.message.from_wire(self.fixture.answer(self.query('missing.signed.r3.test.', 'A'), 'tcp'))
        self.assertEqual(dns.rcode.REFUSED, response.rcode())
        self.assertFalse(response.answer)

    def test_outside_zone_rejected(self):
        with self.assertRaises(ValueError):
            self.fixture.answer(self.query('elsewhere.r3.test.', 'A'), 'tcp')

    def loss_reply(self):
        query = dns.message.make_query(self.fixture.zone.origin, 'DNSKEY', want_dnssec=True)
        query.id = 0x5701
        wire = self.fixture.answer(query.to_wire(), 'tcp')
        return dict(mode='query', qtype=48, name=str(self.fixture.zone.origin).rstrip('.'),
                    server='10.111.0.1', port=53,
                    replies=[dict(id=0x5701, bytes=len(wire), wireBase64=base64.b64encode(wire).decode())])

    def test_loss_validator_accepts_real_signed_reply_and_both_families(self):
        reply = self.loss_reply()
        loss.validate_reply(reply, self.fixture, 4)
        reply['server'] = 'fd00::1'
        loss.validate_reply(reply, self.fixture, 6)

    def test_loss_validator_rejects_wrong_family_size_id_and_signature(self):
        good = self.loss_reply()
        variants = []
        wrong_family = copy.deepcopy(good)
        wrong_family['server'] = 'fd00::1'
        variants.append(wrong_family)
        wrong_size = copy.deepcopy(good)
        wrong_size['replies'][0]['bytes'] -= 1
        variants.append(wrong_size)
        for where in ('id', 'signature', 'tc'):
            changed = copy.deepcopy(good)
            row = changed['replies'][0]
            wire = bytearray(base64.b64decode(row['wireBase64']))
            if where == 'id':
                wire[0] ^= 1
            elif where == 'signature':
                # The final RR is OPT; change a byte in the RRSIG, before that OPT.
                wire[-12] ^= 1
            else:
                wire[2] |= 2
            row['wireBase64'] = base64.b64encode(wire).decode()
            variants.append(changed)
        for index, value in enumerate(variants):
            with self.subTest(index=index), self.assertRaises((ValueError, dns.dnssec.ValidationFailure)):
                loss.validate_reply(value, self.fixture, 4)

    def java_trial(self, mode, kind, name):
        with socket.socket() as listener, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            listener.settimeout(5)
            udp.bind(listener.getsockname())
            udp.settimeout(5)
            def serve():
                if mode == 'udp-retry':
                    packet, peer = udp.recvfrom(65535)
                    udp.sendto(self.fixture.answer(packet, 'udp'), peer)
                with listener.accept()[0] as tcp:
                    tcp.settimeout(5)
                    length = struct.unpack('!H', probes.read_exact(tcp, 2))[0]
                    packet = probes.read_exact(tcp, length)
                    answer = self.fixture.answer(packet, 'tcp')
                    probes.send_frame(tcp, answer)
                    return answer
            with ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(serve)
                result = subprocess.run(['java', '-cp', str(self.build), 'ProbeHost',
                                         str(listener.getsockname()[1]), mode, str(kind), name],
                                        capture_output=True, text=True, timeout=20)
                answer = future.result(timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            actual = base64.b64decode(result.stdout.strip().split('=', 1)[1])
            self.assertEqual(answer, actual)
            return dns.message.from_wire(actual)

    def test_java_retries_real_fixture_txt_after_udp_tc(self):
        response = self.java_trial('udp-retry', 16, 'large.signed.r3.test')
        self.assertFalse(response.flags & dns.flags.TC)
        self.assertGreater(len(response.to_wire()), 1460)

    def test_java_reads_complete_signed_dnskey(self):
        response = self.java_trial('query', 48, 'signed.r3.test')
        self.validate_answer(response)
        self.assertGreater(len(response.to_wire()), 1460)


if __name__ == '__main__':
    unittest.main()
