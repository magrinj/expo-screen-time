---
id: shields
title: Shields
sidebar_position: 7
---

# Shields

## Shield Configuration

:::info Requires the `ShieldConfigurationExtension` target
:::

### `setShieldConfig(config: ShieldConfig)`

Set the default shield (blocking screen) appearance. Text elements and buttons
are **nested objects** — see [`ShieldConfig`](../types.md#shieldconfig).

```typescript
ExpoScreenTime.setShieldConfig({
  backgroundColor: '#1a1a2e',
  backgroundBlurStyle: 'dark',
  icon: 'hand.raised.fill', // SF Symbol name
  title: { text: 'App Blocked', color: '#ffffff' },
  subtitle: { text: 'This app is restricted during work hours', color: '#aaaaaa' },
  primaryButton: { text: 'OK', color: '#ffffff', backgroundColor: '#4a4ae8' },
  secondaryButton: { text: 'Not now', color: '#ffffff' },
});
```

### `setShieldConfigForActivity(activityName: string, config: ShieldConfig)`

Shield appearance for a specific monitoring activity. When several block
sources cover the same app, the configuration is chosen by membership:
a `blockOnThreshold` block covering the app wins, then the schedule covering it
that started most recently, then the manual block (which uses the default
configuration), then the default configuration.

```typescript
ExpoScreenTime.setShieldConfigForActivity('bedtime', {
  title: { text: 'Time for Bed!' },
  subtitle: { text: 'Apps are blocked until morning' },
  backgroundColor: '#0d1b2a',
});
```

### `clearShieldConfig()`

Revert to the system default shield.

```typescript
ExpoScreenTime.clearShieldConfig();
```

## Shield Actions

:::info Requires the `ShieldActionExtension` target
:::

### `setShieldActions(config: ShieldActionConfig)`

Configure what the shield buttons do. Actions are `'close'`, `'unblock'`,
`'defer'`, or a snooze object — see
[`ShieldActionConfig`](../types.md#shieldactionconfig).

```typescript
ExpoScreenTime.setShieldActions({
  primaryAction: 'close',
  secondaryAction: 'defer', // keep the shield up; the press is still recorded
});
```

**Actions:**

- `'close'` — close the blocked app; the block stays.
- `'unblock'` — lift the shield on this app in every source that shields it
  (the manual block and each schedule in progress), until a block is applied
  again (the next `blockSelection()`, `relock()`, or the next start of a
  monitored interval). For an app shielded through its category, that category
  is lifted; for a web domain, that domain.
- `'defer'` — keep the shield on screen and let it redraw. The press is still
  recorded for [`drainShieldInteractions()`](./blocking.md).
- `{ type: 'snooze', duration }` — **not functional yet**: it behaves like
  `'close'`. The press is recorded, the app closes and the block stays. A
  usage-based snooze (`duration` seconds of actual use, like
  `temporaryUnlock`) is planned.

Up to 0.1.1, a native key mismatch made every configured action behave like
`'close'`.

### `setShieldActionsForActivity(activityName: string, config: ShieldActionConfig)`

Resolved like [`setShieldConfigForActivity`](#setshieldconfigforactivityactivityname-string-config-shieldconfig):
threshold block, then latest schedule, then the default actions.

The buttons follow the screen: when iOS asks for a shield's appearance it
names the app **and** its category, while a button press names only one of
them. The appearance step records the source it resolved for each of those
tokens, and the button press reuses it as long as that source is still active
(a source suspended by `temporaryUnlock` never owns a shield). Two limits
remain: iOS caches shield appearances, so if the active sources change
without the shield being drawn again, the press resolves on its own, by token
membership; and the record for a category is shared by every app shielded
through that category, so the most recently drawn shield wins.

```typescript
ExpoScreenTime.setShieldActionsForActivity('workHours', {
  primaryAction: 'close',
  // no secondary button for work hours
});
```

### `clearShieldActions()`

```typescript
ExpoScreenTime.clearShieldActions();
```
