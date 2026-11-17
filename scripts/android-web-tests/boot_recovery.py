"""Bound receiver scheduling -> establishment using the companion's boot callback.

BroadcastRecord durations use uptime. Require matching monotonic/epoch logs and
an elapsedRealtime anchor consistent with no suspend before the companion event.
Clock disagreement leaves the measurement unavailable. Millisecond timestamps
are rounded outward by 2 ms; only the entire interval can satisfy the deadline.
"""
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re

from boot_delivery import MAX_BYTES, extract


PRECISION_MS = 2
LOG = re.compile(r'^\s*(\d+\.\d+)\s+(\d+)\s+\d+ I (WebFilterTest|ExpoScreenTimeWeb): (.*)$')


def one(rows, label):
    if len(rows) != 1:
        raise ValueError(f'Expected one {label}, found {len(rows)}')
    return rows[0]


def log_events(text, boot, pid):
    callbacks, establishments = [], []
    for line in text.splitlines():
        match = LOG.fullmatch(line)
        if not match:
            continue
        at, process, tag, message = match.groups()
        timestamp = int(Decimal(at) * 1000)
        if tag == 'WebFilterTest' and message.startswith('{'):
            if json.loads(message) == boot:
                callbacks.append(timestamp)
        if tag == 'ExpoScreenTimeWeb' and message.startswith('web established '):
            # Do not select a later restart merely because its PID matches the final state.
            establishments.append((timestamp, int(process), message))
    event = one(callbacks, 'matching companion boot log')
    established, process, message = one(establishments, 'establishment log')
    logged_pid = re.search(r'\bpid=(\d+)\b', message)
    if process != pid or logged_pid is None or int(logged_pid[1]) != pid:
        raise ValueError('Establishment PID does not match the current run')
    return event, established, message


def bound_recovery(broadcasts, monotonic, epoch, boot, pid):
    delivery = extract(broadcasts)
    companion = extract(broadcasts, package='expo.modules.screentime.webtest',
                        receiver='expo.modules.screentime.webtest.BootReceiver')
    if delivery['broadcastLine'] != companion['broadcastLine']:
        raise ValueError('Receivers do not belong to the same boot broadcast')
    if delivery['state'] != 'DELIVERED' or companion['state'] != 'DELIVERED':
        raise ValueError('Both boot receivers must have completed delivery')
    mono_boot, mono_end, mono_message = log_events(monotonic, boot, pid)
    epoch_boot, epoch_end, epoch_message = log_events(epoch, boot, pid)
    if mono_message != epoch_message:
        raise ValueError('Epoch and monotonic establishment logs differ')
    if abs(boot['elapsed'] - mono_boot) > PRECISION_MS or abs(boot['at'] - epoch_boot) > PRECISION_MS:
        raise ValueError('Boot clock anchor differs: suspend, clock change or delayed logging')
    deltas = [mono_end - mono_boot, epoch_end - epoch_boot]
    if abs(deltas[0] - deltas[1]) > PRECISION_MS:
        raise ValueError('Recovery clocks disagree: suspend or clock change')
    offset = companion['enqueueToScheduleMs'] - delivery['enqueueToScheduleMs']
    lower = min(deltas) + offset - PRECISION_MS
    upper = max(deltas) + offset + companion['scheduleToTerminalMs'] + PRECISION_MS
    if upper < 0 or lower < -PRECISION_MS:
        raise ValueError('Establishment precedes boot receiver scheduling')
    # The synchronous companion log can occur anywhere inside its full callback.
    return {'millisecondsInterval': [max(0, lower), upper],
            'within30Seconds': upper <= 30000,
            'filterEnqueueToScheduleMs': delivery['enqueueToScheduleMs'],
            'companionEnqueueToScheduleMs': companion['enqueueToScheduleMs'],
            'companionScheduleToTerminalMs': companion['scheduleToTerminalMs']}


def read_recovery(path, epoch, boots, run, current_boot):
    result = {'available': False, 'origin': 'Android boot receiver scheduling',
              'deadlineSeconds': 30, 'precisionAllowanceMs': PRECISION_MS,
              'beforeFirstUnlockGuaranteed': False,
              'clockRequirement': 'Matching uptime, elapsedRealtime and epoch anchors; no detected suspend or clock step',
              'sourceHashes': {}}
    try:
        boot = one(boots, 'current-boot companion event')
        if run.get('bootCount') != current_boot or not isinstance(run.get('pid'), int):
            raise ValueError('Run identity does not match the current boot')
        diagnostics = Path(path) / 'diagnostics'
        metadata = diagnostics / 'capture.json'
        if metadata.exists():
            capture = json.loads(metadata.read_text())
            if (capture['bootBefore'] != current_boot or capture['bootAfter'] != current_boot
                    or any(capture['commands'][name]['returncode'] != 0
                           for name in ('broadcasts', 'web-log-monotonic'))):
                raise ValueError('Boot diagnostic capture failed or crossed a reboot')
        texts = {}
        for name in ('broadcasts', 'web-log-monotonic'):
            source = diagnostics / (name + '.txt')
            with source.open('rb') as stream:
                raw = stream.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                raise ValueError('Boot diagnostic exceeds 8 MiB bound')
            texts[name] = raw.decode('utf8')
            result['sourceHashes'][str(source)] = hashlib.sha256(raw).hexdigest()
        result['epochLogSha256'] = hashlib.sha256(epoch.encode('utf8')).hexdigest()
        result.update(bound_recovery(texts['broadcasts'], texts['web-log-monotonic'], epoch, boot, run['pid']))
        result['available'] = True
    except (OSError, ValueError, KeyError, TypeError) as failure:
        result['error'] = str(failure)
    return result
