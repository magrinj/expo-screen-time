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
  secondaryButton: { text: 'Snooze 5 min', color: '#ffffff' },
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
  secondaryAction: { type: 'snooze', duration: 300 }, // 5 minutes, in seconds
});
```

**Actions:**

- `'close'` — close the shield, app remains blocked
- `'unblock'` — permanently unblock this specific app
- `'defer'` — defer the action, allowing the shield UI to update
- `{ type: 'snooze', duration }` — temporarily unblock for `duration` seconds

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
