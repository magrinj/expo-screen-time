#!/usr/bin/env python3
"""Verify loss-runner cleanup with a fake command transport; never connects to adb."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('loss', Path(__file__).with_name('tcp-loss-trial.py'))
loss = importlib.util.module_from_spec(spec)
spec.loader.exec_module(loss)


class Device:
    def __init__(self, count=1, insert_timeout=False, cleanup_failure=False, counter_failure=False, avd='fixture-avd', root=True):
        self.count = count
        self.insert_timeout = insert_timeout
        self.cleanup_failure = cleanup_failure
        self.counter_failure = counter_failure
        self.avd = avd
        self.root = root
        self.rule = None
        self.calls = []

    def __call__(self, *args):
        self.calls.append(args)
        output, code = '', 0
        if args == ('shell', 'getprop', 'ro.kernel.qemu'):
            output = '1\n'
        elif args == ('emu', 'avd', 'name'):
            output = self.avd + '\nOK\n'
        elif args == ('shell', 'id', '-u'):
            output = '0\n' if self.root else '2000\n'
        elif '-I' in args:
            self.rule = args[args.index('--comment') + 1]
            if self.insert_timeout:
                raise subprocess.TimeoutExpired('insert', 15)
        elif '-D' in args:
            if self.cleanup_failure:
                code = 1
            else:
                assert args[args.index('--comment') + 1] == self.rule
                self.rule = None
        elif '-L' in args:
            output = 'Chain INPUT (policy ACCEPT 0 packets, 0 bytes)\n'
            if self.rule:
                if self.counter_failure:
                    self.counter_failure = False
                    return subprocess.CompletedProcess(args, 1, '', 'counter unavailable')
                output += f'{self.count} 1500 DROP tcp -- tun+ * 0.0.0.0/0 0.0.0.0/0 /* {self.rule} */\n'
        else:
            raise AssertionError(args)
        return subprocess.CompletedProcess(args, code, output, '' if not code else 'failed')


class LossTrialTest(unittest.TestCase):
    def run_trial(self, device, error=None, family=4):
        def fetch():
            if error:
                raise error
            return {'synthetic': True}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'trial'
            report = loss.trial(device, fetch, output, family, 'fixture-avd', lambda value: None)
            self.assertEqual(report, json.loads((output / 'trial.json').read_text()))
            return report

    def test_counter_one_and_cleanup_still_do_not_claim_retransmission(self):
        device = Device()
        result = self.run_trial(device)
        self.assertEqual([], result['errors'])
        self.assertTrue(result['cleanupVerified'])
        self.assertTrue(result['clientResponseValidated'])
        self.assertFalse(result['retransmissionVerified'])
        self.assertFalse(result['campaignComplete'])
        self.assertIsNone(device.rule)

    def test_counter_zero_or_two_fails_and_cleans_up(self):
        for count in (0, 2):
            with self.subTest(count=count):
                device = Device(count=count)
                report = self.run_trial(device)
                self.assertTrue(report['errors'])
                self.assertTrue(report['cleanupVerified'])
                self.assertIsNone(device.rule)

    def test_probe_timeout_cleans_up(self):
        device = Device()
        result = self.run_trial(device, TimeoutError('probe'))
        self.assertTrue(result['cleanupVerified'])
        self.assertFalse(result['clientResponseValidated'])
        self.assertTrue(result['errors'])

    def test_interrupt_cleans_up(self):
        result = self.run_trial(Device(), KeyboardInterrupt('cancel'))
        self.assertTrue(result['cleanupVerified'])
        self.assertTrue(result['errors'])

    def test_insert_timeout_still_removes_rule(self):
        device = Device(insert_timeout=True)
        result = self.run_trial(device)
        self.assertTrue(result['cleanupVerified'])
        self.assertTrue(result['errors'])
        self.assertIsNone(device.rule)

    def test_counter_read_failure_still_removes_rule(self):
        device = Device(counter_failure=True)
        result = self.run_trial(device)
        self.assertTrue(result['errors'])
        self.assertTrue(result['cleanupVerified'])
        self.assertIsNone(device.rule)

    def test_cleanup_failure_is_not_reported_as_success(self):
        device = Device(cleanup_failure=True)
        result = self.run_trial(device)
        self.assertFalse(result['cleanupVerified'])
        self.assertTrue(result['errors'])
        self.assertIsNotNone(device.rule)

    def test_wrong_emulator_or_unprivileged_shell_does_not_insert(self):
        for device in (Device(avd='other-avd'), Device(root=False)):
            result = self.run_trial(device)
            self.assertTrue(result['errors'])
            self.assertFalse(any('-I' in args for args in device.calls))

    def test_ipv6_uses_only_ip6tables(self):
        device = Device()
        result = self.run_trial(device, family=6)
        self.assertFalse(result['errors'])
        self.assertTrue(any('ip6tables' in args for args in device.calls))
        self.assertFalse(any('iptables' in args for args in device.calls))

    def test_url_rejects_unrelated_hosts_or_probe_types(self):
        good = 'http://127.0.0.1:18767/tcp-scenario?mode=query&type=DNSKEY&name=signed.r3.test'
        self.assertEqual(good, loss.probe_url(good))
        for url in (good.replace('127.0.0.1', 'example.com'), good.replace('DNSKEY', 'A'),
                    good.replace('query', 'reset'), good + '&name=another.r3.test'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                loss.probe_url(url)


if __name__ == '__main__':
    unittest.main()
