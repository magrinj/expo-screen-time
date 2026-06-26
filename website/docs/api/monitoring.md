---
id: monitoring
title: Schedule Monitoring
sidebar_position: 5
---

# Schedule Monitoring

:::info Requires the `DeviceActivityMonitorExtension` target
See [Target Selection](../configuration.md#target-selection).
:::

## `startMonitoring(config: MonitoringConfiguration)`

Start monitoring device activity with a schedule. When the interval starts, blocks are activated; when it ends, blocks are deactivated.

```typescript
await ExpoScreenTime.startMonitoring({
  activityName: 'workHours',
  schedule: {
    intervalStart: { hour: 9, minute: 0 },
    intervalEnd: { hour: 17, minute: 0 },
    repeats: true,
    warningTime: 300, // 5 minute warning before interval ends
  },
  selectionToBlock: mySelection, // FamilyActivitySelection from picker
  events: [
    {
      eventName: 'socialMediaLimit',
      familyActivitySelection: socialMediaSelection,
      threshold: 3600, // 1 hour in seconds
    },
  ],
});
```

## `stopMonitoring(activityName: string)`

Stop a specific monitoring activity.

```typescript
await ExpoScreenTime.stopMonitoring('workHours');
```

## `stopAllMonitoring()`

Stop all active monitoring.

```typescript
await ExpoScreenTime.stopAllMonitoring();
```

## `getMonitoredActivities(): Promise<string[]>`

Get list of currently monitored activity names.

```typescript
const activities = await ExpoScreenTime.getMonitoredActivities();
// ['workHours', 'bedtime']
```

## Monitor Events

### `getMonitorEvents(activityName?: string, limit?: number): Promise<MonitorEvent[]>`

Get recent monitor events from storage.

```typescript
const events = await ExpoScreenTime.getMonitorEvents('workHours', 50);
// [{ activityName: 'workHours', eventType: 'intervalDidStart', timestamp: 1234567890 }]
```

**Event Types:**

- `intervalDidStart` — Scheduled interval started
- `intervalDidEnd` — Scheduled interval ended
- `eventDidReachThreshold` — Usage threshold was reached
- `intervalWillEndWarning` — Warning before interval ends
- `eventWillReachThresholdWarning` — Warning before threshold is reached

### `clearMonitorEvents(activityName?: string)`

Clear stored monitor events.

```typescript
await ExpoScreenTime.clearMonitorEvents('workHours'); // Clear specific activity
await ExpoScreenTime.clearMonitorEvents(); // Clear all events
```
