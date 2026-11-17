#!/usr/bin/env python3
"""Acceptance-reader regression cases; synthetic clocks and dumps, no device."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from boot_recovery import bound_recovery, read_recovery

spec = importlib.util.spec_from_file_location('summarizer', Path(__file__).with_name('summarize-reboots.py'))
summarizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summarizer)
BOOT = {'at': 1100000, 'elapsed': 100000, 'event': 'boot', 'detail': 'delay=-1'}
RUN = {'bootCount': 2, 'pid': 42, 'start': 1105000}
HEADER = 'BroadcastRecord{abc android.intent.action.BOOT_COMPLETED/u0/0x20c1} to user 0\n'
FILTER = 'expo.modules.screentime.example'
COMPANION = 'expo.modules.screentime.webtest'


def entries(schedule=1000, companion=2000, terminal=20):
    return [f'''DELIVERED scheduled +{schedule}ms terminal +1ms (-1) #0: (manifest)
name=expo.modules.screentime.ScreenTimeRestartReceiver
packageName={FILTER}
''', f'''DELIVERED scheduled +{companion}ms terminal +{terminal}ms (-1) #1: (manifest)
name={COMPANION}.BootReceiver
packageName={COMPANION}
''']


def logs(end_ms=105000, epoch=False):
    offset = 1000000 if epoch else 0
    return (f'{(100000 + offset) / 1000:.3f} 43 43 I WebFilterTest: {json.dumps(BOOT)}\n'
            f'{(end_ms + offset) / 1000:.3f} 42 44 I ExpoScreenTimeWeb: web established pair=A pid=42\n')


class BootRecoveryTest(unittest.TestCase):
    def test_bounds_include_the_whole_callback_window(self):
        result = bound_recovery(HEADER + ''.join(entries()), logs(), logs(epoch=True), BOOT, 42)
        self.assertEqual([5998, 6022], result['millisecondsInterval'])
        self.assertTrue(result['within30Seconds'])

    def test_upper_bound_decides_including_timestamp_precision(self):
        for end, passes in [(129998, True), (129999, False), (140000, False)]:
            with self.subTest(end=end):
                result = bound_recovery(HEADER + ''.join(entries(companion=1000, terminal=0)),
                                        logs(end), logs(end, epoch=True), BOOT, 42)
                self.assertEqual(passes, result['within30Seconds'])

    def test_callback_window_straddling_deadline_is_not_a_pass(self):
        result = bound_recovery(HEADER + ''.join(entries(companion=1000, terminal=1000)),
                                logs(129500), logs(129500, epoch=True), BOOT, 42)
        self.assertLess(result['millisecondsInterval'][0], 30000)
        self.assertGreater(result['millisecondsInterval'][1], 30000)
        self.assertFalse(result['within30Seconds'])

    def test_long_system_delivery_is_separate_from_recovery(self):
        result = bound_recovery(HEADER + ''.join(entries(schedule=80174, companion=80930)),
                                logs(), logs(epoch=True), BOOT, 42)
        self.assertTrue(result['within30Seconds'])
        self.assertEqual(80174, result['filterEnqueueToScheduleMs'])

    def test_different_broadcasts_are_not_correlated(self):
        a, b = entries()
        with self.assertRaisesRegex(ValueError, 'same boot broadcast'):
            bound_recovery(HEADER + a + HEADER + b, logs(), logs(epoch=True), BOOT, 42)

    def test_pending_receiver_is_not_recovery_evidence(self):
        with self.assertRaisesRegex(ValueError, 'completed delivery'):
            bound_recovery((HEADER + ''.join(entries())).replace('DELIVERED', 'PENDING', 1),
                           logs(), logs(epoch=True), BOOT, 42)

    def test_missing_ambiguous_or_different_identity_is_unavailable(self):
        cases = [('', logs(epoch=True), BOOT, 42),
                 (logs() + logs(), logs(epoch=True), BOOT, 42),
                 (logs(), logs(epoch=True), BOOT, 99),
                 (logs().replace('pid=42', 'pid=99'), logs(epoch=True), BOOT, 42),
                 (logs(), logs(epoch=True).replace('pair=A', 'pair=B'), BOOT, 42),
                 (logs(98000), logs(98000, epoch=True), BOOT, 42)]
        for mono, epoch, boot, pid in cases:
            with self.subTest(mono=mono, pid=pid), self.assertRaises(ValueError):
                bound_recovery(HEADER + ''.join(entries()), mono, epoch, boot, pid)

    def test_detected_suspend_or_clock_step_cannot_pass(self):
        shifted = dict(BOOT, elapsed=100500)
        for mono, epoch, boot in [
            (logs().replace(json.dumps(BOOT), json.dumps(shifted)),
             logs(epoch=True).replace(json.dumps(BOOT), json.dumps(shifted)), shifted),
            (logs(), logs(106000, epoch=True), BOOT),
            (logs(), logs(104000, epoch=True), BOOT),
        ]:
            with self.subTest(boot=boot), self.assertRaisesRegex(ValueError, 'clock|clocks'):
                bound_recovery(HEADER + ''.join(entries()), mono, epoch, boot, 42)

    def test_reader_requires_complete_current_boot_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertFalse(read_recovery(root, logs(epoch=True), [BOOT], RUN, 2)['available'])
            diagnostics = root / 'diagnostics'
            diagnostics.mkdir()
            (diagnostics / 'broadcasts.txt').write_text(HEADER + ''.join(entries()))
            (diagnostics / 'web-log-monotonic.txt').write_text(logs())
            self.assertTrue(read_recovery(root, logs(epoch=True), [BOOT], RUN, 2)['available'])
            (diagnostics / 'capture.json').write_text(json.dumps({
                'bootBefore': 2, 'bootAfter': 2,
                'commands': {name: {'returncode': 0} for name in ('broadcasts', 'web-log-monotonic')}}))
            self.assertTrue(read_recovery(root, logs(epoch=True), [BOOT], RUN, 2)['available'])
            for boots, run in [([], RUN), ([BOOT, BOOT], RUN), ([BOOT], dict(RUN, bootCount=1))]:
                self.assertFalse(read_recovery(root, logs(epoch=True), boots, run, 2)['available'])
            for metadata in [
                {'bootBefore': 2, 'bootAfter': 3, 'commands': {}},
                {'bootBefore': 2, 'bootAfter': 2, 'commands': {'broadcasts': {'returncode': 1}}},
            ]:
                (diagnostics / 'capture.json').write_text(json.dumps(metadata))
                self.assertFalse(read_recovery(root, logs(epoch=True), [BOOT], RUN, 2)['available'])

    def test_summary_uses_approved_criterion_and_preserves_historical_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ['before', 'after', 'diagnostics']:
                (root / name).mkdir()
            before = {'consent': {'at': 999000, 'dialogAt': 999000}}
            after = {'consent': {'status': 'granted', 'preparedBootCount': 2, 'dialogAt': 999000},
                     'running': True, 'run': RUN,
                     'gaps': [{'kind': 'reboot', 'reason': 'unknown', 'from': 1000000, 'to': 1105000}]}
            trial = {'policy': 'whenNoVpn', 'companionDelayMs': -1, 'beforeBoot': 1, 'afterBoot': 2}
            (root / 'before/state.json').write_text(json.dumps({'web': before}))
            (root / 'after/state.json').write_text(json.dumps({'web': after}))
            values = {'web-log.txt': logs(epoch=True) + '1103.000 42 42 I ExpoScreenTimeWeb: web boot policy=whenNoVpn observed=true vpnSeen=false prepared=true\n',
                      'connectivity.txt': 'ni{VPN CONNECTED extra: VPN:' + FILTER + '}',
                      'companion-events.txt': json.dumps(BOOT), 'boot-count.txt': '2',
                      'always-on.txt': 'null', 'lockdown.txt': 'null', 'uptime.txt': '140.0 30.0'}
            for name, content in values.items():
                (root / 'after' / name).write_text(content)
            (root / 'diagnostics/broadcasts.txt').write_text(HEADER + ''.join(entries()))
            (root / 'diagnostics/web-log-monotonic.txt').write_text(logs())
            for error in [None, 'capture interrupted']:
                if error:
                    trial['error'] = error
                (root / 'trial.json').write_text(json.dumps(trial))
                result = summarizer.summarize(root)
                self.assertTrue(result['checks']['restoredWithin30SecondsOfBootSignal'])
                self.assertEqual(105, result['firstEstablishmentSecondsFromBoot'])
                self.assertFalse(result['historicalChecks']['restoredWithin30SecondsOfBoot'])
                self.assertEqual(['captureComplete'] if error else [], result['unmetChecks'])
                self.assertEqual(after['gaps'], result['rebootGaps'])
            trial.pop('error')
            (root / 'trial.json').write_text(json.dumps(trial))
            for gaps in [[], [dict(after['gaps'][0], **{'from': 1099000})],
                         [dict(after['gaps'][0], to=1104000)]]:
                with self.subTest(gaps=gaps):
                    (root / 'after/state.json').write_text(json.dumps({'web': dict(after, gaps=gaps)}))
                    result = summarizer.summarize(root)
                    self.assertEqual(['rebootGapRecorded'], result['unmetChecks'])


if __name__ == '__main__':
    unittest.main()
