#!/usr/bin/env bash
# Logic tests for the pure Swift types the module and its extensions share.
#
# ManagedSettings tokens cannot be built outside the OS and FamilyControls
# does not run in the simulator, so the block-composition, precedence,
# weekday, registration, shield-interaction, permission-step and shield-text-colour rules live in Foundation-only types and are tested
# here with plain `swiftc` on macOS; the cross-process mutex (BlockStateLock)
# is exercised by threads in-process and by two concurrent processes. Also
# asserts that every per-target copy of those files is byte-identical to the
# `ios/` original (the config plugin copies each `targets/<Name>/` folder
# verbatim, so the copies are canonical).
#
# Usage: scripts/swift-tests/run.sh   (or `bun run test:swift`)
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

status=0
check_copy() {
  if cmp -s "$1" "$2"; then
    echo "identical: $2"
  else
    echo "DIFFERS:   $2 (vs $1)"
    status=1
  fi
}

for target in DeviceActivityMonitorExtension ShieldActionExtension ShieldConfigurationExtension; do
  check_copy ios/BlockComposition.swift "targets/$target/BlockComposition.swift"
  check_copy ios/BlockStateLock.swift "targets/$target/BlockStateLock.swift"
  check_copy ios/SharedDefaults.swift "targets/$target/SharedDefaults.swift"
  check_copy ios/ShieldInteractionQueue.swift "targets/$target/ShieldInteractionQueue.swift"
done
check_copy ios/DailySchedule.swift targets/DeviceActivityMonitorExtension/DailySchedule.swift
check_copy ios/ActivityRegistration.swift targets/DeviceActivityMonitorExtension/ActivityRegistration.swift
check_copy ios/MonitorEventCursor.swift ios/MonitorEventCursor.swift
check_copy ios/ShieldTextColors.swift targets/ShieldConfigurationExtension/ShieldTextColors.swift
# The extension copies of SelectionStore are the CRUD subset of ios/SelectionStore.swift.
check_copy targets/DeviceActivityMonitorExtension/SelectionStore.swift targets/ShieldActionExtension/SelectionStore.swift
check_copy targets/DeviceActivityMonitorExtension/SelectionStore.swift targets/ShieldConfigurationExtension/SelectionStore.swift

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
swiftc -O \
  ios/BlockComposition.swift \
  ios/BlockStateLock.swift \
  ios/DailySchedule.swift \
  ios/ActivityRegistration.swift \
  ios/MonitorEventCursor.swift \
  ios/EnforcementGaps.swift \
  ios/ShieldInteractionQueue.swift \
  ios/PermissionSteps.swift \
  ios/ShieldTextColors.swift \
  scripts/swift-tests/main.swift \
  -o "$out/swift-tests"
"$out/swift-tests" || status=1

# Two processes contend on the real flock: each repeats a read-modify-write of
# a counter under the lock; the counter must end at 2 × iterations.
echo
echo "BlockStateLock — two processes"
swiftc -O ios/BlockStateLock.swift scripts/swift-tests/lockprobe/main.swift -o "$out/lockprobe"
iterations=300
echo 0 > "$out/counter"
"$out/lockprobe" "$out/state.lock" "$out/counter" "$iterations" &
"$out/lockprobe" "$out/state.lock" "$out/counter" "$iterations" &
wait
count="$(cat "$out/counter")"
if [ "$count" -eq $((2 * iterations)) ]; then
  echo "  ok   two processes under the lock: $count of $((2 * iterations)) updates survived"
else
  echo "  FAIL two processes under the lock: $count of $((2 * iterations)) updates survived"
  status=1
fi
# What the probe detects, for the record (not asserted: a lost update is likely, not certain).
echo 0 > "$out/counter"
"$out/lockprobe" "$out/state.lock" "$out/counter" "$iterations" nolock &
"$out/lockprobe" "$out/state.lock" "$out/counter" "$iterations" nolock &
wait
echo "  info without the lock: $(cat "$out/counter") of $((2 * iterations)) updates survived"

exit $status
