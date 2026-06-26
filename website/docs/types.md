---
id: types
title: Types
sidebar_position: 6
---

# Types

## `FamilyActivitySelection`

```typescript
type FamilyActivitySelection = {
  applicationTokens: ActivityToken[];
  categoryTokens: ActivityToken[];
  webDomainTokens: ActivityToken[];
  untokenizedApplicationIdentifiers: string[];
  untokenizedCategoryIdentifiers: string[];
  untokenizedWebDomainIdentifiers: string[];
  includeEntireCategory: boolean;
};
```

## `ActivityToken`

```typescript
type ActivityToken = {
  data: string;  // Base64-encoded token data
};
```

## `DeviceActivitySchedule`

```typescript
type DeviceActivitySchedule = {
  intervalStart: DateComponents;
  intervalEnd: DateComponents;
  repeats: boolean;
  warningTime?: number;  // seconds
};

type DateComponents = {
  hour: number;    // 0-23
  minute: number;  // 0-59
  second?: number; // 0-59
  weekday?: number; // 1 = Sunday, 7 = Saturday
};
```

## `ShieldConfiguration`

```typescript
type ShieldConfiguration = {
  backgroundColor?: string;           // hex color
  backgroundBlurStyle?: UIBlurEffectStyle;
  icon?: string;                      // SF Symbol name
  title?: string;
  titleColor?: string;
  subtitle?: string;
  subtitleColor?: string;
  primaryButtonLabel?: string;
  primaryButtonColor?: string;
  primaryButtonBackgroundColor?: string;
  secondaryButtonLabel?: string;
  secondaryButtonColor?: string;
  secondaryButtonBackgroundColor?: string;
};
```

## `ShieldActionConfiguration`

```typescript
type ShieldActionConfiguration = {
  primaryButtonAction: ShieldActionType;
  primaryButtonSnoozeDuration?: number;    // seconds
  secondaryButtonAction?: ShieldActionType;
  secondaryButtonSnoozeDuration?: number;  // seconds
};

type ShieldActionType = 'close' | 'snooze' | 'unblock' | 'defer';
```

## `MonitorEvent`

```typescript
type MonitorEvent = {
  activityName: string;
  eventType: MonitorEventType;
  eventName?: string;       // for threshold events
  timestamp: number;        // Unix milliseconds
};

type MonitorEventType =
  | 'intervalDidStart'
  | 'intervalDidEnd'
  | 'eventDidReachThreshold'
  | 'intervalWillEndWarning'
  | 'eventWillReachThresholdWarning';
```
