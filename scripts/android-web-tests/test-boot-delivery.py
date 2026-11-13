#!/usr/bin/env python3
"""Synthetic dump tests; no Android commands or device access."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from boot_delivery import ACTION, FILTER, RECEIVER, duration_ms, extract, read_delivery

spec = importlib.util.spec_from_file_location('summarize_reboots', Path(__file__).with_name('summarize-reboots.py'))
summarizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summarizer)


def record(action=ACTION, user=0, package=FILTER, receiver=RECEIVER,
           timing='DELIVERED scheduled +1m31s495ms terminal +254ms (-1)'):
    return f'''  Pending broadcast #0:
      BroadcastRecord{{abc123 {action}/u{user}/0x20c1}} to user {user}
      Intent {{ act={action} }}
      {timing} #117: (manifest)
        ActivityInfo:
          name={receiver}
          packageName={package}
          processName={package}:screentime
        reason: remote app
'''


class BootDeliveryTest(unittest.TestCase):
    def test_duration_units_and_zero(self):
        for text, expected in [('0', 0), ('+254ms', 254), ('+1m31s495ms', 91495),
                               ('+2m0s0ms', 120000), ('+1d2h3m4s5ms', 93784005)]:
            with self.subTest(text=text):
                self.assertEqual(expected, duration_ms(text))

    def test_invalid_or_negative_durations_refuse(self):
        for text in ['+', '', '-1ms', '10ms', '+1s2m', '+1.5s', '+1msx']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                duration_ms(text)

    def test_scheduling_and_terminal_are_separate_durations(self):
        result = extract(record())
        self.assertEqual(91495, result['enqueueToScheduleMs'])
        self.assertEqual(254, result['scheduleToTerminalMs'])
        self.assertEqual(2, result['broadcastLine'])
        self.assertEqual(4, result['receiverLine'])
        self.assertTrue(result['schedulingDelayExceeds30Seconds'])

    def test_thirty_seconds_is_not_exceeded_at_the_boundary(self):
        result = extract(record(timing='DELIVERED scheduled +30s0ms terminal 0 (-1)'))
        self.assertFalse(result['schedulingDelayExceeds30Seconds'])
        self.assertEqual(0, result['scheduleToTerminalMs'])

    def test_exact_action_user_package_and_receiver(self):
        others = [record(action='android.intent.action.LOCKED_BOOT_COMPLETED'), record(user=10),
                  record(package=FILTER + '.other'), record(receiver=RECEIVER + 'Other')]
        for other in others:
            with self.subTest(other=other), self.assertRaises(ValueError):
                extract(other)
        self.assertEqual(91495, extract(''.join(others) + record())['enqueueToScheduleMs'])

    def test_duplicate_record_is_ambiguous(self):
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            extract(record() + record())

    def test_pending_receiver_has_unknown_timing(self):
        result = extract(record(timing='PENDING (-1)'))
        self.assertEqual('PENDING', result['state'])
        self.assertIsNone(result['enqueueToScheduleMs'])
        self.assertIsNone(result['scheduleToTerminalMs'])
        self.assertIsNone(result['schedulingDelayExceeds30Seconds'])

    def test_delivered_without_both_durations_refuses(self):
        for timing in ['DELIVERED (-1)', 'DELIVERED scheduled +2ms (-1)', 'DELIVERED terminal 0 (-1)']:
            with self.subTest(timing=timing), self.assertRaisesRegex(ValueError, 'incomplete'):
                extract(record(timing=timing))

    def test_registered_entry_cannot_supply_manifest_fields(self):
        text = record(package='wrong.package') + '''      DELIVERED scheduled +2ms terminal 0 (-1) #118: BroadcastFilter{def}
          name=expo.modules.screentime.ScreenTimeRestartReceiver
          packageName=expo.modules.screentime.example
'''
        with self.assertRaises(ValueError):
            extract(text)

    def test_missing_invalid_or_oversized_file_stays_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'broadcasts.txt'
            self.assertFalse(read_delivery(path)['available'])
            path.write_text('incomplete dump')
            self.assertFalse(read_delivery(path)['available'])
            path.write_bytes(b'\xff')
            self.assertFalse(read_delivery(path)['available'])
            path.write_text(record())
            with patch('boot_delivery.MAX_BYTES', 32):
                self.assertFalse(read_delivery(path)['available'])

    def test_source_is_hashed_but_queue_cause_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'broadcasts.txt'
            path.write_text(record())
            result = read_delivery(path)
            self.assertTrue(result['available'])
            self.assertEqual(64, len(result['sha256']))
            self.assertFalse(result['queueCauseEstablished'])
            self.assertIn('does not replace', result['note'])


    def test_diagnostic_never_replaces_the_boot_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'before').mkdir()
            (root / 'after').mkdir()
            before = {'consent': {'at': 999000, 'dialogAt': 999000}}
            after = {'consent': {'status': 'granted', 'preparedBootCount': 2, 'dialogAt': 999000},
                     'running': True, 'run': {'bootCount': 2}, 'gaps': []}
            (root / 'before/state.json').write_text(json.dumps({'web': before}))
            (root / 'after/state.json').write_text(json.dumps({'web': after}))
            (root / 'trial.json').write_text(json.dumps({'policy': 'whenNoVpn', 'companionDelayMs': -1,
                                                        'beforeBoot': 1, 'afterBoot': 2}))
            values = {
                'web-log.txt': '1103.000 ExpoScreenTimeWeb: web boot policy=whenNoVpn observed=true vpnSeen=false prepared=true\n'
                               '1105.000 ExpoScreenTimeWeb: web established\n',
                'connectivity.txt': 'ni{VPN CONNECTED extra: VPN:' + FILTER + '}',
                'companion-events.txt': json.dumps({'at': 1100000, 'elapsed': 100000, 'event': 'boot', 'detail': 'delay=-1'}),
                'boot-count.txt': '2', 'always-on.txt': 'null', 'lockdown.txt': '0', 'uptime.txt': '140.0 30.0',
            }
            for name, text in values.items():
                (root / 'after' / name).write_text(text)
            absent = summarizer.summarize(root)
            self.assertFalse(absent['bootBroadcastDelivery']['available'])
            self.assertEqual(105, absent['firstEstablishmentSecondsFromBoot'])
            self.assertEqual(['restoredWithin30SecondsOfBoot'], absent['unmetChecks'])
            (root / 'diagnostics').mkdir()
            (root / 'diagnostics/broadcasts.txt').write_text(
                record(timing='DELIVERED scheduled +1s terminal +2ms (-1)'))
            diagnostic = summarizer.summarize(root)
            self.assertTrue(diagnostic['bootBroadcastDelivery']['available'])
            self.assertFalse(diagnostic['bootBroadcastDelivery']['schedulingDelayExceeds30Seconds'])
            self.assertEqual(absent['checks'], diagnostic['checks'])


if __name__ == '__main__':
    unittest.main()
