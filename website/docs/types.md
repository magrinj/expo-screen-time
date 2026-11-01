---
id: types
title: Types
sidebar_position: 6
---

# Types

As exported from `expo-screen-time` (source of truth:
[`ExpoScreenTime.types.ts`](https://github.com/magrinj/expo-screen-time/blob/main/src/ExpoScreenTime.types.ts)).

## `TokenHandle`

Opaque, stable reference to a native token. Never parse or construct one in JS.

```typescript
type TokenHandle = {
  handle: string; // opaque, stable across app launches
  type: "application" | "category" | "webDomain";
};
```

## `SelectionMetadata`

Counts for a stored selection — no token data.

```typescript
type SelectionMetadata = {
  applicationCount: number;
  categoryCount: number;
  webDomainCount: number;
};
```

## `OnSelectionChangeEvent`

Payload of the picker's `onSelectionChange` (`event.nativeEvent`).

```typescript
type OnSelectionChangeEvent = {
  metadata: SelectionMetadata;
  tokens: TokenHandle[];
};
```

## `DeviceActivitySchedule`

```typescript
type DeviceActivitySchedule = {
  intervalStart: DateComponents;
  intervalEnd: DateComponents;
  repeats: boolean;
  warningTime?: number; // seconds
};

type DateComponents = {
  hour: number;     // 0-23
  minute: number;   // 0-59
  second?: number;  // 0-59
  weekday?: number; // 1 = Sunday … 7 = Saturday
};
```

## `MonitoringConfiguration`

```typescript
type MonitoringConfiguration = {
  /** Stable identity: events, shield configs and getMonitoredActivities use it */
  activityName: string;
  /** intervalEnd at or before intervalStart crosses midnight */
  schedule: DeviceActivitySchedule;
  /** Selection to block while the interval is active — a stored selection ID */
  selectionId?: string;
  events?: DeviceActivityEventThreshold[];
  /** Restrict to specific weekdays (1 = Sunday … 7 = Saturday) */
  weekdays?: number[];
};

type DeviceActivityEventThreshold = {
  eventName: string;
  /** Stored selection to meter (and to shield with blockOnThreshold) */
  selectionId: string;
  threshold: number; // seconds of usage within the interval
  includesPastActivity?: boolean;
  /** Shield selectionId from the threshold until the interval ends */
  blockOnThreshold?: boolean;
};
```

One activity is registered per `activityName`, `weekdays` included: it costs one
of the 20 DeviceActivity slots, and its events carry that name. Occurrences on
unselected weekdays are ignored (no block, no events); an occurrence that
crosses midnight belongs to the weekday on which it starts.

## `ShieldConfig`

Text elements and buttons are nested objects.

```typescript
type ShieldConfig = {
  title?: { text: string; color?: string };
  subtitle?: { text: string; color?: string };
  icon?: string;                    // SF Symbol name
  backgroundColor?: string;         // hex color
  backgroundBlurStyle?: UIBlurEffectStyle; // 'light' | 'dark' | 'regular' | …
  primaryButton?: { text: string; color?: string; backgroundColor?: string };
  secondaryButton?: { text: string; color?: string };
};
```

## `ShieldActionConfig`

```typescript
type ShieldActionConfig = {
  primaryAction: ShieldActionType | { type: "snooze"; duration: number };
  secondaryAction?: ShieldActionType | { type: "snooze"; duration: number };
};

type ShieldActionType = "close" | "unblock" | "defer";
```

Snooze is expressed as an object with its duration in seconds. It is not
functional yet — see [`setShieldActions`](./api/shields.md).

## `MonitorEvent`

```typescript
type MonitorEvent = {
  activityName: string;
  eventType: MonitorEventType;
  eventName?: string; // for threshold events
  timestamp: number;  // Unix milliseconds
};

type MonitorEventType =
  | "intervalDidStart"
  | "intervalDidEnd"
  | "eventDidReachThreshold"
  | "intervalWillEndWarning"
  | "eventWillReachThresholdWarning";
```


## `TemporaryUnlockGrant`

Result of `temporaryUnlock`. There is deliberately no `expiresAt` — the
budget is spent by usage, not by the clock.

```typescript
type TemporaryUnlockGrant = {
  budgetSeconds: number; // granted budget (after the 30s floor)
  grantedAt: number;     // Unix milliseconds
};
```

## `ShieldInteraction`

One blocked-app attempt the user reacted to on the shield. Returned by
`drainShieldInteractions`.

```typescript
type ShieldInteraction = {
  button: "primary" | "secondary";
  timestamp: number; // Unix milliseconds
};
```

## `ScreenTimeErrorCode` {#screentimeerrorcode}

Every rejected promise and thrown error from the module carries one of these
codes. Narrow a caught value with `isScreenTimeError`, then branch on `code`:

```typescript
import ExpoScreenTime, { isScreenTimeError } from "expo-screen-time";

try {
  await ExpoScreenTime.temporaryUnlock(15 * 60);
} catch (error) {
  if (isScreenTimeError(error) && error.code === "NO_ACTIVE_BLOCK") {
    return; // nothing to unlock
  }
  throw error;
}
```

| Code | When |
|---|---|
| `UNAUTHORIZED` | Family Controls authorization is missing (`startMonitoring`). |
| `UNSUPPORTED` | The OS version is too old for the call. |
| `SELECTION_NOT_FOUND` | `blockSelection` with a `selectionId` that was never saved. |
| `NO_ACTIVE_BLOCK` | `temporaryUnlock` while nothing is blocked. |
| `EXCESSIVE_ACTIVITIES` | `startMonitoring` past the system limit of monitored activities. |
| `INTERVAL_TOO_SHORT` | A schedule shorter than 15 minutes. |
| `INTERVAL_TOO_LONG` | A schedule longer than a week. |
| `INVALID_DATE_COMPONENTS` | A schedule the system rejects as invalid dates. |
| `INVALID_CONFIG` | A missing or malformed argument: no `activityName` or `schedule`, a reserved activity name, missing interval bounds. |
| `MONITORING_ERROR` | The system refused the monitoring request for another reason; the message carries its description. |
| `MONITORING_ROLLBACK_FAILED` | Re-registering an activity failed and its previous schedule could not be restored: the activity is no longer monitored. |

Errors raised by Expo itself (for example `ERR_ARGUMENT_CAST` on a wrong
argument type) are not `ScreenTimeError`s. `SCREEN_TIME_ERROR_CODES` lists
every code at runtime.

If `error.code` is always `undefined`, see
[Troubleshooting](./troubleshooting.md#errorcode-is-undefined).
