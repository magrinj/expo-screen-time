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
of the 20 DeviceActivity slots), its events are recorded under `workHours`, and
occurrences on Saturday and Sunday are ignored. A `blockOnThreshold` shield
covers the **event's** selection (`social-apps` here, not the activity's
`work-blocklist`) and is lifted with the interval.

Activity names are free-form, except that they must not be empty, start with
`expo_st_` (the module's own namespace — its temporary-unlock meter lives
there) or contain `__expo_st_gen_`, which the module appends to the name it
registers with the system. `startMonitoring` and `stopMonitoring` both reject
such a name with `INVALID_CONFIG`.

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
an interval was in progress). Other activities, a manual block and the
temporary-unlock meter are untouched; a reserved name rejects with
`INVALID_CONFIG`.

## `stopAllMonitoring()`

Stops every activity started with `startMonitoring` and lifts their shields.
A manual block (`blockSelection`) and web-domain filters stay in force. A
temporary unlock in progress (`temporaryUnlock`) is not affected: it keeps
metering and re-applies the remaining sources when its budget is spent.

```typescript
await ExpoScreenTime.stopAllMonitoring();
```

## `getMonitoredActivities(): Promise<string[]>`

The activities you started. Activities the module runs for itself (the
temporary-unlock meter) are never listed.

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
