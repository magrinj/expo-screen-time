---
id: types
title: Types
sidebar_position: 7
---

# Types

As exported from `expo-screen-time` (source of truth:
[`ExpoScreenTime.types.ts`](https://github.com/magrinj/expo-screen-time/blob/main/src/ExpoScreenTime.types.ts)).

## `ScreenTimeFeature`, `PermissionStepId`, `PermissionStep`

What the app intends to use, and the steps the user must allow for it — see
[Permissions](./api/permissions.md).

```typescript
type ScreenTimeFeature =
  | "blocking" | "monitoring" | "usageReports"
  | "shieldOpenApp" | "appRemovalProtection" | "webDomainBlocking";

type PermissionStepId =
  | "screenTime"      // iOS: the Family Controls prompt
  | "notifications"   // iOS: for shieldOpenApp
  // Android ids, so shared code can label every step; iOS never lists them:
  | "usageAccess" | "overlay" | "accessibility"
  | "batteryOptimization" | "backgroundAutostart" | "deviceAdmin";

type PermissionStep = {
  id: PermissionStepId;
  status: "granted" | "denied" | "undetermined" | "restricted" | "unknown";
  required: boolean;                     // needed by the requested features
  kind: "prompt" | "settings" | "guide"; // a system dialog on iOS
  error?: { code: string; message: string };
};

type ScreenTimePermissionOptions = {
  features?: ScreenTimeFeature[];        // default ["blocking", "monitoring"]
  member?: "individual" | "child";
};

type ScreenTimePermissionResponse = PermissionResponse & { steps: PermissionStep[] };
```

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

## `TokenLabelProps`

Props of [`TokenLabel`](./components.md#token-labels); `ApplicationLabel`,
`CategoryLabel` and `WebDomainLabel` take the same props without `tokenType`.

```typescript
type TokenLabelProps = {
  style?: StyleProp<ViewStyle>;
  /** Optional — a handle is self-describing; given, the handle must be in it */
  selectionId?: string;
  handle: string; // opaque TokenHandle.handle
  tokenType: "application" | "category" | "webDomain";
  displayMode?: "icon-only" | "title-only" | "title-and-icon"; // default title-and-icon
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

## `BlockMode`

```typescript
type BlockMode =
  /** Shield the selection (default). */
  | "blocklist"
  /**
   * Shield every app EXCEPT the selection's apps, and the whole web. The
   * selection may hold applications only (else INVALID_CONFIG).
   */
  | "allowlist";
```

Accepted by [`blockSelection`](./api/blocking.md#allowlist) and
[`MonitoringConfiguration.mode`](#monitoringconfiguration). The blocking page
lists what an allowlist never shields, how it combines with other sources,
and why `temporaryUnlock` rejects while one is active.

## `MonitoringConfiguration`

```typescript
type MonitoringConfiguration = {
  /** Stable identity: events, shield configs and getMonitoredActivities use it */
  activityName: string;
  /** intervalEnd at or before intervalStart crosses midnight */
  schedule: DeviceActivitySchedule;
  /** Selection to block while the interval is active — a stored selection ID */
  selectionId?: string;
  /** How selectionId is applied while the interval is active (default "blocklist") */
  mode?: BlockMode;
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

## `ShieldAction`

What a shield button does — see [Shield Actions](./api/shields.md#shield-actions).

```typescript
type ShieldAction =
  | "close"                                // dismiss; the block stays
  | "unblock"                              // lift the pressed target until the next block
  | "defer"                                // keep the shield up; the press is recorded
  | { type: "snooze"; duration: number }   // lift the pressed target for `duration` seconds of use
  | { type: "openApp" };                   // bring the host app forward (iOS: a notification to tap)

type ShieldActionType = "close" | "unblock" | "defer"; // the string actions alone
```

Snooze is expressed as an object with its duration in seconds **of use** of
the pressed target (at least 30) — see [Snooze](./api/shields.md#snooze).
`openApp` posts a notification on iOS — see
[Open app](./api/shields.md#open-app).

## `ShieldActionConfig`

```typescript
type ShieldActionConfig = {
  primaryAction: ShieldAction;
  secondaryAction?: ShieldAction;
};
```

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
  budgetSeconds: number;      // granted budget (after the 30s floor)
  grantedAt: number;          // Unix milliseconds
  meteringSeconds: number;    // the budget is spent in steps of this many seconds
  target: TokenHandle | null; // the one target lifted, or null for a whole-block unlock
};
```

`meteringSeconds` is 30 for budgets up to 30 minutes, then `ceil(budget / 60)`
(120 for a 2-hour budget): `getRemainingUnlockSeconds()` moves by that much at
a time. `target` echoes the `{ target }` option of a
[targeted unlock](./api/blocking.md#targeted-unlock).

## `ShieldInteraction`

One press of a shield button. Returned by
[`drainShieldInteractions`](./api/blocking.md#shield-interactions). Presses on
the same `target` within 2 seconds collapse into one.

```typescript
type ShieldInteraction = {
  button: "primary" | "secondary";
  timestamp: number;            // Unix milliseconds
  target: TokenHandle | null;   // what was shielded — render it with <TokenLabel>
  activityName: string | null;  // monitored activity behind the block; null for the manual block
};
```

`target` is the pressed app, web domain or category (an app shielded through
its category reports the category), as the handle `getTokens` returns for it.
`activityName` is resolved like the shield config: a threshold block, else the
most recently started schedule covering the target, else `null` (the manual
block, or no active block owns the shield any more). Both are `null` for
interactions recorded before these fields existed.

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
| `UNSUPPORTED` | The OS version is too old for the call, or `temporaryUnlock` while an [allowlist](./api/blocking.md#allowlist) block is active. |
| `SELECTION_NOT_FOUND` | `blockSelection` with a `selectionId` that was never saved; `startMonitoring` with `mode: "allowlist"` over one. |
| `NO_ACTIVE_BLOCK` | `temporaryUnlock` while nothing is blocked. |
| `EXCESSIVE_ACTIVITIES` | `startMonitoring` past the system limit of monitored activities. |
| `INTERVAL_TOO_SHORT` | A schedule shorter than 15 minutes. |
| `INTERVAL_TOO_LONG` | A schedule longer than a week. |
| `INVALID_DATE_COMPONENTS` | A schedule the system rejects as invalid dates. |
| `INVALID_CONFIG` | A missing or malformed argument: no `activityName` or `schedule`, a reserved activity name, missing interval bounds, an unknown `mode`, or an allowlist whose selection holds categories or web domains, or no app at all. |
| `MONITORING_ERROR` | The system refused the monitoring request for another reason; the message carries its description. |
| `MONITORING_ROLLBACK_FAILED` | Re-registering an activity failed and its previous schedule could not be restored: the activity is no longer monitored. |

Errors raised by Expo itself (for example `ERR_ARGUMENT_CAST` on a wrong
argument type) are not `ScreenTimeError`s. `SCREEN_TIME_ERROR_CODES` lists
every code at runtime.

If `error.code` is always `undefined`, see
[Troubleshooting](./troubleshooting.md#errorcode-is-undefined).
