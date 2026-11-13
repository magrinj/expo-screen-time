"""Read the system's per-receiver boot timing, without inferring a queue cause.

The two durations printed by BroadcastRecord are scheduledTime - enqueueTime
and terminalTime - scheduledTime. They are neither time since boot nor the
receiver's onReceive execution time. Keep this evidence separate from W-R6's
existing end-to-end deadline check.
"""
import hashlib
from pathlib import Path
import re


ACTION = 'android.intent.action.BOOT_COMPLETED'
FILTER = 'expo.modules.screentime.example'
RECEIVER = 'expo.modules.screentime.ScreenTimeRestartReceiver'
MAX_BYTES = 8 * 1024 * 1024
RECORD = re.compile(r'^\s*BroadcastRecord\{\S+ (\S+)\} to user (-?\d+)\s*$')
ENTRY = re.compile(r'^\s*([A-Z_]+)\b.* #(\d+): (.*)$')
DURATION = re.compile(r'\+(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?')


def duration_ms(value):
    if value == '0':
        return 0
    match = DURATION.fullmatch(value)
    if match is None or not any(match.groups()):
        raise ValueError('Unsupported nonnegative dump duration: ' + value)
    return sum(int(part or 0) * scale for part, scale in
               zip(match.groups(), (86400000, 3600000, 60000, 1000, 1)))


def extract(text, package=FILTER, receiver=RECEIVER, user=0):
    """Require one matching manifest receiver in one exact action/user record."""
    lines = text.splitlines()
    records = [(i, match) for i, line in enumerate(lines)
               if (match := RECORD.fullmatch(line))]
    candidates = []
    for position, (begin, record) in enumerate(records):
        action = record[1].split('/')[0]
        if action != ACTION or int(record[2]) != user:
            continue
        end = records[position + 1][0] if position + 1 < len(records) else len(lines)
        entries = [(i, match) for i in range(begin + 1, end)
                   if (match := ENTRY.fullmatch(lines[i]))]
        for index, (start, entry) in enumerate(entries):
            if entry[3] != '(manifest)':
                continue
            stop = entries[index + 1][0] if index + 1 < len(entries) else end
            fields = lines[start + 1:stop]
            if not (any(line.strip() == 'name=' + receiver for line in fields)
                    and any(line.strip() == 'packageName=' + package for line in fields)):
                continue
            timing = {}
            for word, key in [('scheduled', 'enqueueToScheduleMs'), ('terminal', 'scheduleToTerminalMs')]:
                value = re.search(r'\b' + word + r' (\S+)', lines[start])
                timing[key] = duration_ms(value[1]) if value else None
            if entry[1] == 'DELIVERED' and any(value is None for value in timing.values()):
                raise ValueError('Delivered receiver has incomplete timing fields')
            candidates.append({
                'action': action, 'user': user, 'package': package, 'receiver': receiver,
                'broadcastLine': begin + 1, 'receiverLine': start + 1,
                'state': entry[1], 'timingLine': lines[start].strip(), **timing,
                'schedulingDelayExceeds30Seconds':
                    timing['enqueueToScheduleMs'] > 30000 if timing['enqueueToScheduleMs'] is not None else None,
            })
    if not candidates:
        raise ValueError('No exact boot broadcast/manifest receiver match in dump')
    if len(candidates) != 1:
        raise ValueError('Ambiguous boot receiver records; cannot choose a boot')
    return candidates[0]


def read_delivery(path):
    """Optional diagnostic: missing or invalid input never becomes zero latency."""
    path = Path(path)
    result = {'available': False, 'source': str(path),
              'clock': 'Android uptime durations printed by BroadcastRecord',
              'queueCauseEstablished': False,
              'note': 'Diagnostic only; does not replace the end-to-end boot deadline.'}
    try:
        with path.open('rb') as stream:
            raw = stream.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError('Broadcast dump exceeds 8 MiB bound')
        result['sha256'] = hashlib.sha256(raw).hexdigest()
        result.update(extract(raw.decode('utf8')))
        result['available'] = True
    except (OSError, ValueError) as failure:
        result['error'] = str(failure)
    return result
