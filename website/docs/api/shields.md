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

Shield appearance for a specific monitoring activity.

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
- `'unblock'` — lift the shield on this app until a block is applied again
  (the next `blockSelection()`, or the next start of a monitored interval).
  For an app shielded through its category, that category is lifted; for a
  web domain, that domain.
- `'defer'` — keep the shield on screen and let it redraw. The press is still
  recorded for [`drainShieldInteractions()`](./blocking.md).
- `{ type: 'snooze', duration }` — **not functional yet**: the press is
  recorded and the shield stays. A usage-based snooze (`duration` seconds of
  actual use, like `temporaryUnlock`) is planned.

Up to 0.1.1, a native key mismatch made every configured action behave like
`'close'`.

### `setShieldActionsForActivity(activityName: string, config: ShieldActionConfig)`

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
