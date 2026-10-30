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
selection referenced by `selectionId` is blocked; when it ends, blocks are lifted.

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
    },
  ],
  // Optional: restrict to specific weekdays (1 = Sunday … 7 = Saturday)
  weekdays: [2, 3, 4, 5, 6],
});
```

See [`MonitoringConfiguration`](../types.md#monitoringconfiguration).

## `stopMonitoring(activityName: string)`

```typescript
await ExpoScreenTime.stopMonitoring('workHours');
```

## `stopAllMonitoring()`

Stops every activity started with `startMonitoring`. A temporary unlock in
progress (`temporaryUnlock`) is not affected: it keeps metering and re-applies
the block when its budget is spent.

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
