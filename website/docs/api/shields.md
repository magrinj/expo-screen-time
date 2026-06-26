---
id: shields
title: Shields
sidebar_position: 6
---

# Shields

## Shield Configuration

:::info Requires the `ShieldConfigurationExtension` target
:::

### `setShieldConfiguration(config: ShieldConfiguration)`

Set the default shield (blocking screen) appearance. See [`ShieldConfiguration`](../types.md#shieldconfiguration).

```typescript
ExpoScreenTime.setShieldConfiguration({
  backgroundColor: '#1a1a2e',
  backgroundBlurStyle: 'dark',
  icon: 'hand.raised.fill', // SF Symbol name
  title: 'App Blocked',
  titleColor: '#ffffff',
  subtitle: 'This app is restricted during work hours',
  subtitleColor: '#aaaaaa',
  primaryButtonLabel: 'OK',
  primaryButtonColor: '#ffffff',
  primaryButtonBackgroundColor: '#4a4ae8',
  secondaryButtonLabel: 'Snooze 5 min',
  secondaryButtonColor: '#ffffff',
  secondaryButtonBackgroundColor: '#666666',
});
```

### `setShieldConfigurationForActivity(activityName: string, config: ShieldConfiguration)`

Set shield configuration for a specific monitoring activity.

```typescript
ExpoScreenTime.setShieldConfigurationForActivity('bedtime', {
  title: 'Time for Bed!',
  subtitle: 'Apps are blocked until morning',
  backgroundColor: '#0d1b2a',
});
```

### `clearShieldConfiguration()`

Clear all shield configurations, reverting to system defaults.

```typescript
ExpoScreenTime.clearShieldConfiguration();
```

## Shield Actions

:::info Requires the `ShieldActionExtension` target
:::

### `setShieldActions(config: ShieldActionConfiguration)`

Configure what happens when shield buttons are pressed.

```typescript
ExpoScreenTime.setShieldActions({
  primaryButtonAction: 'close',        // 'close' | 'snooze' | 'unblock' | 'defer'
  secondaryButtonAction: 'snooze',
  secondaryButtonSnoozeDuration: 300,  // 5 minutes in seconds
});
```

**Action Types:**

- `close` — Close the shield, app remains blocked
- `snooze` — Temporarily unblock (use with `snoozeDuration`)
- `unblock` — Permanently unblock this specific app
- `defer` — Defer the action, allowing shield UI to update

### `setShieldActionsForActivity(activityName: string, config: ShieldActionConfiguration)`

Set shield actions for a specific activity.

```typescript
ExpoScreenTime.setShieldActionsForActivity('workHours', {
  primaryButtonAction: 'close',
  // No secondary button for work hours
});
```

### `clearShieldActions()`

Clear all shield action configurations.

```typescript
ExpoScreenTime.clearShieldActions();
```
