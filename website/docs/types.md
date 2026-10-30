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
  activityName: string;
  schedule: DeviceActivitySchedule;
  /** Selection to block when the interval starts — a stored selection ID */
  selectionId?: string;
  events?: DeviceActivityEventThreshold[];
  /** Restrict to specific weekdays (1 = Sunday … 7 = Saturday) */
  weekdays?: number[];
};

type DeviceActivityEventThreshold = {
  eventName: string;
  /** Stored selection to meter */
  selectionId: string;
  threshold: number; // seconds
  includesPastActivity?: boolean;
};
```

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
