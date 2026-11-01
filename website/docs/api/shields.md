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
  again (the next `blockSelection()`, `relock()`, the next start of a
  monitored interval, or any full re-projection of the shields — a snooze
  press, a snooze ending). For an app shielded through its category, that
  category is lifted; for a web domain, that domain.
- `'defer'` — keep the shield on screen and let it redraw. The press is still
  recorded for [`drainShieldInteractions()`](./blocking.md#shield-interactions).
- `{ type: 'snooze', duration }` — lift the shield on **this app only**, for
  `duration` seconds of its **use** (a usage budget, like
  [`temporaryUnlock`](./blocking.md#temporary-unlock-earned-time)), then
  shield it again. See [Snooze](#snooze) below.

Up to 0.1.1, a native key mismatch made every configured action behave like
`'close'`, and `snooze` behaved like `'close'` until it was implemented.

### Snooze

Pressing a snooze button lifts the shield on what was pressed — the app, the
web domain, or, for an app shielded through its category, that category (iOS
identifies such a shield by its category only) — and nothing else: every
other shielded app stays shielded, and the sources covering the snoozed
target stay active (a schedule in progress keeps its interval, its events and
its shield configuration). The shield disappears and the user stays in the
app they asked for; it does **not** close the app.

`duration` is a budget of **seconds of use of that target**, not wall-clock
time: it is spent only while the snoozed app is in the foreground, pauses when
the user leaves, and the target is shielded again — by whatever sources still
cover it — once the budget is spent, enforced by the DeviceActivityMonitor
extension even if your app never runs. Budgets below 30 seconds are raised to
30 (iOS usage thresholds are unreliable under that); consumption is reported
in ~30-second steps. Unspent budget expires at midnight. Use of the target
before the press does not count.

Snoozes are independent of one another and of `temporaryUnlock`:

- Pressing snooze on a **second app** starts a second snooze with its own
  budget; the first keeps running. Pressing it again on the same target (for
  instance after its category was snoozed while the app is also shielded
  directly) restarts that target's budget.
- A `temporaryUnlock` granted while a snooze runs meters the snoozed app too;
  when the unlock's budget is spent, the snooze still in progress keeps its
  target lifted, and the other way round.
- `blockSelection()`, `unblockAll()` and `relock()` end every snooze.
  `stopMonitoring()` / `stopAllMonitoring()` and a schedule ending do not: a
  snooze outlives the source that was shielding its target, and still applies
  if another source shields it later that day.

Each snooze in progress occupies one of the 20 DeviceActivity slots iOS gives
an app (an internal activity, never listed by `getMonitoredActivities`) until
it is spent or the day ends. When no slot is free — 19 activities of yours
plus an unlock in progress, say — the press cannot arm a meter: the shield
stays and the app closes, like `'close'`. Keep headroom if you rely on
snoozes.

```typescript
ExpoScreenTime.setShieldActions({
  primaryAction: 'close',
  secondaryAction: { type: 'snooze', duration: 5 * 60 }, // 5 minutes of use
});
```

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
