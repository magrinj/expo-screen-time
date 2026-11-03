---
id: monitoring
title: Schedule Monitoring
sidebar_position: 6
---

# Schedule Monitoring

:::info Requires the `DeviceActivityMonitorExtension` target
See [Target Selection](../configuration.md#target-selection).
:::

## `startMonitoring(config: MonitoringConfiguration)`

Start monitoring device activity with a schedule. When the interval starts, the
selection referenced by `selectionId` is blocked; when it ends, that block is
lifted. Each activity is its own block source: ending one interval never touches
the manual block (`blockSelection`), another schedule, or a web-domain filter —
the effective block is the union of every active source.

If the current time is already inside the interval, the block is applied at
once and an `intervalDidStart` event is recorded immediately (the system's own
callback fires asynchronously, at the first device use after the boundary).

```typescript
await ExpoScreenTime.startMonitoring({
  activityName: 'workHours',
  schedule: {
    intervalStart: { hour: 9, minute: 0 },
    intervalEnd: { hour: 17, minute: 0 },
    repeats: true,
    warningTime: 300, // 5 minute warning before interval ends (seconds)
  },
  // Selection to block during the interval — a stored selection ID
  selectionId: 'work-blocklist',
  // Optional: how selectionId is applied — 'blocklist' (default) shields it,
  // 'allowlist' shields every app EXCEPT its apps (applications only)
  mode: 'blocklist',
  // Optional usage thresholds within the interval
  events: [
    {
      eventName: 'socialMediaLimit',
      selectionId: 'social-apps', // stored selection to meter
      threshold: 3600,            // 1 hour, in seconds
      includesPastActivity: false,
      // Optional: shield 'social-apps' from the threshold until the interval ends
      blockOnThreshold: true,
    },
  ],
  // Optional: restrict to specific weekdays (1 = Sunday … 7 = Saturday)
  weekdays: [2, 3, 4, 5, 6],
});
```

See [`MonitoringConfiguration`](../types.md#monitoringconfiguration).

`weekdays` does not multiply activities: `workHours` above is one activity (one
of the 19 you can monitor at once, see below), its events are recorded under
`workHours`, and occurrences on Saturday and Sunday are ignored. A one-off
schedule (`repeats: false`) with `weekdays` covers its next occurrence on a
selected weekday — the one in progress when it is on a selected weekday —
rather than the next occurrence at all: the schedule above with
`repeats: false`, started on a Friday evening, blocks on Monday from 09:00 to
17:00. A `blockOnThreshold` shield
covers the **event's** selection (`social-apps` here, not the activity's
`work-blocklist`) and is lifted with the interval.

`mode: 'allowlist'` makes the interval a focus window: every app except the
selection's apps is shielded while it is active, with the same rules as
[`blockSelection`'s allowlist](./blocking.md#allowlist) — applications only
(`startMonitoring` rejects `INVALID_CONFIG` for a selection with categories or
web domains, or for an allowlist without `selectionId`), the web shielded
whole, your app, Phone and Settings never shielded. `blockOnThreshold` events
of the same activity keep blocklist semantics: an app their selection **lists
directly** is shielded even if the allowlist allows it. A *category* in a
threshold selection cannot override the allowlist of its own activity (the
two share one store, where "every app except…" already covers the category
and an allowed app inside it cannot be told apart), so list the apps. The
selection is read live: editing it changes the running allowlist at the
next re-projection. `temporaryUnlock` without a target rejects `UNSUPPORTED`
while such an interval is active (a
[targeted unlock](./blocking.md#targeted-unlock) of a shielded app works).

Activity names are free-form, except that they must not be empty, start with
`expo_st_` (the module's own namespace — its temporary-unlock meter and its
shield-snooze meters live there) or contain `__expo_st_gen_`, which the
module appends to the name it registers with the system. `startMonitoring`
and `stopMonitoring` both reject such a name with `INVALID_CONFIG`.

You can monitor **19 activities at once**
([`limits.maxMonitoredActivities`](./capabilities.md), the same on both
platforms): `startMonitoring` rejects a 20th `activityName` with
`EXCESSIVE_ACTIVITIES` before anything is registered. Starting an activity
that is already monitored replaces it and costs no slot. The limit comes from
iOS, which allows 20 DeviceActivities per app: the temporary-unlock meter
takes one while an unlock is in progress, and each shield
[snooze](./shields.md#snooze) in progress takes one, so keep fewer activities
if you rely on snoozes — when the meters hold the remaining slot,
`startMonitoring` rejects with `EXCESSIVE_ACTIVITIES` as well (the message
names the slots), and a snooze press cannot arm its meter (the shield stays).

Calling `startMonitoring` again with the same `activityName` **updates the
activity in place**: the new schedule, selection and events replace the old
ones, and an interval in progress keeps its shields throughout — no
`intervalDidEnd`/`intervalDidStart` pair is recorded for the swap — while its
`blockOnThreshold` shields are reset, since the new registration re-arms the
thresholds. Stopping an activity and starting it again right away is just as
safe: the system keeps reporting on a stopped registration for a moment, and
those late callbacks are ignored.

If `startMonitoring` rejects, the previous registration keeps running with its
previous settings. When the device is at the 20-activity limit the previous
registration has to be released before the new one can be tried; if the new
one then fails, the previous schedule is registered again as it was (its
interval in progress included). Should that fail as well, the call rejects
with `MONITORING_ROLLBACK_FAILED` and the activity is stopped: its
`intervalDidEnd` is recorded if an interval was in progress, and it is no
longer listed by `getMonitoredActivities`.

A schedule that is not daily — `intervalStart` and `intervalEnd` on different
`weekday`s, e.g. Friday 22:00 to Sunday 06:00 — is registered as given; its
block is applied when the system reports the interval start, not synchronously.
Replacing a daily schedule with such a schedule ends the daily interval in
progress (its `intervalDidEnd` is recorded and its shields are lifted) and the
new interval is applied when the system reports it — within moments when the
device is already inside it.

## `stopMonitoring(activityName: string)`

```typescript
await ExpoScreenTime.stopMonitoring('workHours');
```

Stops the activity and lifts its shields (its `intervalDidEnd` is recorded if
an interval was in progress). Other activities, a manual block, the
temporary-unlock meter and the shield snoozes are untouched; a reserved name
rejects with `INVALID_CONFIG`.

## `stopAllMonitoring()`

Stops every activity started with `startMonitoring` and lifts their shields.
A manual block (`blockSelection`) and web-domain filters stay in force. A
temporary unlock in progress (`temporaryUnlock`) is not affected: it keeps
metering and re-applies the remaining sources when its budget is spent; nor
are shield snoozes.

```typescript
await ExpoScreenTime.stopAllMonitoring();
```

## `getMonitoredActivities(): Promise<string[]>`

The activities you started. Activities the module runs for itself (the
temporary-unlock meter, the shield-snooze meters) are never listed.

```typescript
const activities = await ExpoScreenTime.getMonitoredActivities();
// ['workHours', 'bedtime']
```

## Monitor Events

The monitor extension records an event each time something happens (interval
start/end, threshold reached…). See
[`MonitorEvent`](../types.md#monitorevent) for the payload and event types.

### `useMonitorEvents(callback?)` hook

Subscribe to events in real time from a component:

```typescript
import { useMonitorEvents } from 'expo-screen-time';

function MonitorLog() {
  const { events, isListening, clearEvents } = useMonitorEvents((event) => {
    console.log('monitor event', event.activityName, event.eventType);
  });

  return <Text>{events.length} events</Text>;
}
```

Keeps the latest 100 events in state. Several mounted hooks share one native
listener — native observation starts with the first subscriber and stops with
the last, managed by expo-modules-core. (Lower-level: subscribe to the module's
`onMonitorEvent` event with `ExpoScreenTime.addListener`.)

### `getMonitorEvents(activityName?: string, limit?: number): Promise<MonitorEvent[]>`

Read stored events (default limit 100). Omit `activityName` for all activities.

```typescript
const events = await ExpoScreenTime.getMonitorEvents('workHours', 50);
// [{ activityName: 'workHours', eventType: 'intervalDidStart', timestamp: 1234567890 }]
```

### `clearMonitorEvents(activityName?: string)`

```typescript
await ExpoScreenTime.clearMonitorEvents('workHours'); // one activity
await ExpoScreenTime.clearMonitorEvents();            // all
```

:::note Timing
Monitor events fire when the device is first used after the schedule time — not
exactly at the scheduled time.
:::

## Android {#android}

Monitoring works on Android with the same API; the engine behind
[blocking](./blocking.md#android) evaluates the schedules and measures the
usage. What differs:

- **The same API, the same codes.** `startMonitoring` validates in the iOS
  order with the iOS codes: `INVALID_CONFIG` (no `activityName`, a reserved
  name, no `schedule`, an allowlist without `selectionId` or over a selection
  that is not apps only), `INVALID_DATE_COMPONENTS`, `INTERVAL_TOO_SHORT`
  (under 15 minutes), `INTERVAL_TOO_LONG` (over a week),
  `EXCESSIVE_ACTIVITIES` (a 20th logical activity; the contract's limit is
  19 on both platforms). `stopMonitoring`, `stopAllMonitoring`,
  `getMonitoredActivities`, `getMonitorEvents`, `clearMonitorEvents` and the
  live `onMonitorEvent` stream (`useMonitorEvents`) behave as on iOS, down
  to `getMonitorEvents` ordering: one activity oldest first, all activities
  newest first. In a build the config plugin trimmed
  ([`android.features`](../configuration.md#android-features)) every
  monitoring function rejects `UNSUPPORTED` without `monitoring`, and with
  `monitoring` but not `blocking` a `startMonitoring` that would shield (a
  `selectionId`, a `blockOnThreshold` event) rejects `UNSUPPORTED` too:
  such a build records events and thresholds only.
- **Schedules are a function of time.** Android has no DeviceActivity: the
  engine evaluates `block = f(now, schedules)` at every wake and every
  foreground change, so a schedule starting *now* shields at once and
  records its `intervalDidStart` synchronously (as iOS does since fix 12),
  midnight wrap and `weekdays` work the same, and `repeats: false` covers
  the one occurrence in progress or next. Boundaries that fall while the
  screen is off are observed at an **inexact alarm** (no exact-alarm
  permission): an `intervalDidEnd` due at 17:00 may be recorded later —
  within the window Android gives inexact alarms, up to an hour for a
  boundary a day away, and at the next maintenance window in Doze — or at
  the next screen-on, whichever comes first; iOS records it at the first
  device use after the boundary. The shields themselves are exact at the
  moment of use: a boundary is also evaluated the moment the screen comes
  on and on every foreground change. A device off for days records nothing for the occurrences
  it missed, like iOS.
- **Events are exactly once.** Every event is recorded in the same store
  write as the state that justifies it (the interval marker, the fired
  threshold), under a cross-process lock, so a process killed between a
  transition and its record, or the app and the engine observing the same
  boundary, never duplicate an event; ids are sequence numbers, unique per
  install.
- **Usage thresholds are measured from the UsageStats event stream**, exact
  to the tick — no 30 s ladder: the selection counts while at least one of
  its apps is in the foreground, an instant counts once however many of
  them share the screen, and nothing counts while the screen is off.
  `includesPastActivity` reads the OS's history for the part of the interval
  before the registration (needs `usageAccess`; the OS keeps about 10 days).
  `blockOnThreshold` shields the **event's** selection from the fire until
  the interval ends, and its shield shows the threshold's configuration
  (precedence: threshold, then the latest schedule, then the manual block —
  the iOS rule).
- **`unblockAll`** lifts every active block — the manual block, the
  schedules in progress and their fired thresholds — and monitoring keeps
  running: the interval's end is still recorded and the schedule shields
  again at its next start, as on iOS. `stopAllMonitoring` leaves the manual
  block alone. `isBlocked()` is `true` during a schedule-driven block.
- **The engine runs while anything is monitored** (its persistent notice
  stays), polling the foreground only while the screen is on and a block or
  a threshold needs it. A monitored activity whose interval is not in
  progress costs one inexact alarm, nothing else.
- **A `weekday` on one bound only** rejects `INVALID_DATE_COMPONENTS` (no
  platform defines that interval; iOS hands it to the system, which declines
  it). The weekly shape (`weekday` on both bounds, Friday 22:00 to Sunday
  06:00) is evaluated by the engine.

