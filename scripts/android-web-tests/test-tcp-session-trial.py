#!/usr/bin/env python3
"""Test two-phase trial guards with synthetic controller replies; no device calls."""
import base64
import copy
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('session_trial', Path(__file__).with_name('tcp-session-trial.py'))
trial = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trial)


class Client:
    def __init__(self):
        self.state = dict(sessionId='test-session', server='10.111.0.1', port=53, localPort=4242,
                          closed=False, sequence=0, network=dict(handle=104, dns=['10.111.0.1']))
        self.fail_at = None
        self.close_failure = False
        self.calls = []

    def __call__(self, **args):
        self.calls.append(args)
        operation = args['op']
        if operation != 'open' and args['id'] != self.state['sessionId']:
            return {'error': 'session ID mismatch'}
        if operation == 'query':
            self.state['sequence'] += 1
            if self.state['sequence'] == self.fail_at:
                return {'error': 'connection reset'}
            identifier = 0x6000 + self.state['sequence']
            name = args['name']
            question = b''.join(bytes([len(label)]) + label.encode() for label in name.split('.')) + b'\0\0\1\0\1'
            packet = struct.pack('!6H', identifier, 0x8180, 1, 1, 0, 0) + question
            packet += b'\xc0\x0c' + struct.pack('!HHIH', 1, 1, 60, 4) + bytes([10, 0, 2, 2])
            reply = copy.deepcopy(self.state)
            reply['reply'] = dict(id=identifier, bytes=len(packet), wireBase64=base64.b64encode(packet).decode())
            return reply
        if operation == 'close':
            if self.close_failure:
                return {'error': 'close failed'}
            self.state['closed'] = True
        return copy.deepcopy(self.state)


class SessionTrialTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.output = Path(self.temp.name) / 'trial'
        self.client = Client()

    def tearDown(self):
        self.temp.cleanup()

    def start(self):
        return trial.start(self.client, self.output, 'swap1.r3.test')

    def swap(self):
        self.client.state['network']['dns'] = ['10.111.0.2']

    def test_one_baseline_then_ten_queries_after_observed_pair_change(self):
        self.start()
        self.assertFalse(self.client.state['closed'])
        self.swap()
        result = trial.finish(self.client, self.output)
        self.assertEqual([], result['errors'])
        self.assertEqual(10, len(result['replies']))
        self.assertTrue(result['dnsPairChangeObserved'])
        self.assertTrue(result['cleanupVerified'])
        self.assertFalse(result['campaignComplete'])
        self.assertEqual(1, sum(call['op'] == 'open' for call in self.client.calls))

    def test_no_swap_fails_and_closes_without_extra_queries(self):
        self.start()
        result = trial.finish(self.client, self.output)
        self.assertTrue(result['errors'])
        self.assertFalse(result['dnsPairChangeObserved'])
        self.assertTrue(result['cleanupVerified'])
        self.assertEqual([], result['replies'])

    def test_changed_network_or_port_fails(self):
        for field in ('network', 'localPort'):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                client = Client()
                output = Path(directory) / 'trial'
                trial.start(client, output, 'swap.r3.test')
                client.state['network']['dns'] = ['10.111.0.2']
                if field == 'network':
                    client.state['network']['handle'] = 105
                else:
                    client.state['localPort'] += 1
                result = trial.finish(client, output)
                self.assertTrue(result['errors'])
                self.assertTrue(result['cleanupVerified'])

    def test_failure_midway_does_not_reconnect_and_closes(self):
        self.start()
        self.swap()
        self.client.fail_at = 5
        result = trial.finish(self.client, self.output)
        self.assertEqual(3, len(result['replies']))
        self.assertTrue(result['errors'])
        self.assertTrue(result['cleanupVerified'])
        self.assertEqual(1, sum(call['op'] == 'open' for call in self.client.calls))

    def test_failed_baseline_closes_session(self):
        self.client.fail_at = 1
        with self.assertRaises(ValueError):
            self.start()
        self.assertTrue(self.client.state['closed'])
        self.assertTrue((self.output / 'start-error.json').exists())
        self.assertFalse((self.output / 'start.json').exists())

    def test_close_failure_remains_visible(self):
        self.start()
        self.swap()
        self.client.close_failure = True
        result = trial.finish(self.client, self.output)
        self.assertFalse(result['cleanupVerified'])
        self.assertTrue(result['errors'])

    def test_duplicate_finish_is_refused_without_more_calls(self):
        self.start()
        self.swap()
        trial.finish(self.client, self.output)
        count = len(self.client.calls)
        with self.assertRaises(ValueError):
            trial.finish(self.client, self.output)
        self.assertEqual(count, len(self.client.calls))

    def test_truncated_rdata_is_rejected_even_if_reported_length_matches(self):
        reply = self.client(op='query', id='test-session', name='probe.r3.test')
        row = reply['reply']
        raw = base64.b64decode(row['wireBase64'])[:-1]
        row['wireBase64'] = base64.b64encode(raw).decode()
        row['bytes'] = len(raw)
        with self.assertRaisesRegex(ValueError, 'truncated DNS answer'):
            trial.valid_answer(reply, 1, 'probe.r3.test')

    def test_different_question_with_matching_id_is_rejected(self):
        reply = self.client(op='query', id='test-session', name='other.r3.test')
        with self.assertRaisesRegex(ValueError, 'different controlled name'):
            trial.valid_answer(reply, 1, 'probe.r3.test')

    def test_bad_control_origins_rejected(self):
        for value in ('https://127.0.0.1:18767', 'http://example.com:18767', 'http://127.0.0.1:18767/tcp-session'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                trial.control_url(value)


if __name__ == '__main__':
    unittest.main()
