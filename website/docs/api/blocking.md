---
id: blocking
title: Blocking
sidebar_position: 4
---

# Blocking

## Apps & Categories

### `blockSelection(selectionId: string, options?: { mode?: BlockMode })`

Shield every app, category, and web domain in a stored selection. The selection
is created by the [`<ActivityPicker />`](../components.md#activity-picker) under
the same ID.

The manual block is one **block source**. Schedules started with
[`startMonitoring`](./monitoring.md) and their `blockOnThreshold` events are
separate sources, and the effective block is the union of every active source:
calling `blockSelection` again replaces only the manual selection, and a
schedule ending never lifts the manual block. An explicit block supersedes
any temporary unlock and every shield [snooze](./shields.md#snooze) in
progress.

```typescript
// 1. The user picked apps in <ActivityPicker selectionId="my-blocklist" />
// 2. Apply the shield:
ExpoScreenTime.blockSelection('my-blocklist');
```

Throws `SELECTION_NOT_FOUND` if no selection exists under that ID, and
`INVALID_CONFIG` for an allowlist whose selection holds categories or web
domains (below).

#### Allowlist mode {#allowlist}

`{ mode: 'allowlist' }` turns the rule around: **every app is shielded except
the selection's apps**. Use it for a focus mode ("only Calendar, Mail and
Slack") or a child profile.

```typescript
// The user picked the apps to KEEP in <ActivityPicker selectionId="focus-apps" />
ExpoScreenTime.blockSelection('focus-apps', { mode: 'allowlist' });
```

- **Applications only, at least one.** An allowlist selection may not hold
  categories or web domains: the call throws `INVALID_CONFIG` (iOS applies
  an allowlist with `ActivityCategoryPolicy.all(except:)`, which takes
  application tokens only; Android enforces the same rule). An empty
  selection throws `INVALID_CONFIG` too, a missing one `SELECTION_NOT_FOUND`.
  Check `getSelectionMetadata` before offering the option, or pick with a
  picker that only lists apps. The selection is read live: a selection
  edited *after* it was applied changes the running allowlist at the next
  re-projection — one edited to hold categories or domains keeps allowing
  its apps only, and one emptied allows nothing (every app is shielded).
- **Never shielded (measured on iOS 26):** your own app, Phone and Settings,
  so the user can always come back and change the rule. `unblockAll()` from
  your app is the recovery path. A [shield action](./shields.md#shield-actions)
  of `'unblock'` lifts the pressed app too, provided iOS reports the shield
  by the app (see the snooze bullet).
- **The web is shielded whole.** The application policy does not reach
  Safari, so an allowlist also sets a web-domain policy of "every domain":
  Safari opens, but every page shows the shield. Other browsers are
  ordinary apps and are shielded unless allowed. Consequently **Safari
  cannot be allowed** by an allowlist today; allow a third-party browser
  instead if the user needs the web.
- **Union with other sources.** The effective block is still the union of
  every active source — a target is shielded if *any* source shields it.
  An app allowed by the allowlist but listed by an active schedule or
  `blockOnThreshold` blocklist is shielded, by that source. One exception,
  inside a single schedule: a *category* in a `blockOnThreshold` selection
  cannot override that schedule's own allowlist — list the apps directly
  (see [monitoring](./monitoring.md)). Two allowlists at once (a manual one
  and a schedule) allow only the apps in both.
- **Snoozes** are per app: a snoozed app is excepted from the allowlist
  until its budget is spent — when iOS reports the shield by the app. When
  it reports an allowlist shield by the app's *category* instead, `'unblock'`
  and snooze buttons close the shield without lifting anything (there is no
  way to single out the app); the press is still recorded. Which callback
  iOS uses for an allowlist shield is not yet measured on a device.
- **`temporaryUnlock` rejects `UNSUPPORTED`** while an allowlist is active:
  the unlock is a usage budget over the shielded apps, and "every other
  app" cannot be metered. Lift the block with `unblockAll()` and apply it
  again, or put the allowlist on a [schedule](./monitoring.md).
- The mode is persisted with the selection: a relaunch, a relock or the
  monitor extension re-applying the block restore the same allowlist.
  `isBlocked()` is `true` while an allowlist is in force.

### `unblockAll()`

Lift every active block now — the manual block, every schedule and threshold
block in progress — and clear the web-domain filters; a temporary unlock and
every shield [snooze](./shields.md#snooze) in progress end. Monitoring keeps
running: a schedule re-applies its block at its next interval start.

```typescript
ExpoScreenTime.unblockAll();
```

### `isBlocked(): boolean`

Whether any source currently shields something — reflects the real
ManagedSettings state, not what you last asked for. `false` during a temporary
unlock. `true` while a snooze lifts one app and others stay shielded.

```typescript
const blocked = ExpoScreenTime.isBlocked();
```

## Web Domains

Block specific domains **without** user selection (independent from
`blockSelection`'s domain shields).

### `blockWebDomains(domains: string[])`

```typescript
ExpoScreenTime.blockWebDomains(['facebook.com', 'instagram.com', 'tiktok.com']);
```

### `unblockWebDomains(domains: string[])`

```typescript
ExpoScreenTime.unblockWebDomains(['facebook.com']);
```

### `unblockAllWebDomains()`

```typescript
ExpoScreenTime.unblockAllWebDomains();
```

### `getBlockedWebDomains(): string[]`

Domains currently blocked via `blockWebDomains()`. The list is persisted, so
it survives app relaunches.

```typescript
const domains = ExpoScreenTime.getBlockedWebDomains();
// ['facebook.com', 'tiktok.com']
```

## Temporary Unlock (earned time)

Let the user "earn" time inside blocked apps without giving up the block.
The budget is **usage-based, not wall-clock**: it is only spent while a
blocked app is actually in the foreground — it pauses when the user leaves
and resumes when they come back. The shield re-applies automatically once
the budget is spent (enforced by the DeviceActivityMonitor extension, so it
works even if your app is killed), and unspent budget expires at midnight.

### `temporaryUnlock(budgetSeconds: number): Promise<TemporaryUnlockGrant>`

Suspends every active block — the manual block, the schedules and threshold
blocks in progress — for a usage budget metered over all of them. Rejects with
`NO_ACTIVE_BLOCK` if nothing is blocked, and with `UNSUPPORTED` while an
[allowlist](#allowlist) is active (its shield covers every other app, which
no usage meter can count). Budgets below 30 seconds are raised
to 30 (iOS usage thresholds are unreliable under that). Calling it again
replaces the active unlock with a fresh budget covering everything blocked or
suspended at that moment — a renewal never rejects `NO_ACTIVE_BLOCK` while
something is restorable; calling `blockSelection` cancels it.

What the grant suspended stays suspended until `relock()` or expiry, whatever
happens in between: a threshold firing or the system reporting an interval
start does not re-shield it. A source that **starts during the grant** — a
schedule reaching its start time, a `blockOnThreshold` event for a selection
that was not suspended, or one firing again in a new interval after its
activity was stopped and started again — is outside the grant: it is shielded
and not metered.

```typescript
await ExpoScreenTime.blockSelection('social-apps');
// ... user earns 15 minutes ...
const grant = await ExpoScreenTime.temporaryUnlock(15 * 60);
// { budgetSeconds: 900, grantedAt: 1720512000000 }
```

### `getRemainingUnlockSeconds(): number`

Seconds left on the active budget, or `0`. Consumption is reported by the
monitor extension in ~30-second steps, **only while a blocked app is in
use** — don't render this as a smooth countdown. Reading `0` after a grant
also re-applies the shield as a backstop.

### `isTemporarilyUnlocked(): boolean`

Whether an earned-time budget is currently active.

### `relock(): void`

Ends any active unlock — and every shield [snooze](./shields.md#snooze) —
immediately and restores exactly the sources that are still active: the manual
block, and every schedule (plus its threshold blocks) whose interval is still
in progress. Safe to call when nothing is unlocked.

## Shield interactions

### `drainShieldInteractions(): ShieldInteraction[]`

Read-and-clear the queue of shield button presses, recorded natively by the
ShieldAction extension. Call it when your app foregrounds and persist the
results app-side to power "you were blocked N times" stats.

Each [`ShieldInteraction`](../types.md#shieldinteraction) says which button
was pressed, when, **what was shielded** (`target`, a
[`TokenHandle`](../types.md#tokenhandle) for the app, web domain or category)
and **which block** shielded it (`activityName`, the monitored activity whose
schedule or threshold owns the shield, `null` for the manual block).

Presses on the **same target** within 2 seconds collapse into one interaction
(tapping both buttons of one shield is one blocked attempt); presses on
different targets in that window are separate interactions, so two apps
opened back to back count twice — unless both are shielded through the same
category, which iOS reports as one target.

```typescript
const attempts = ExpoScreenTime.drainShieldInteractions();
// [{ button: 'primary', timestamp: 1720512000000,
//    target: { handle: '…', type: 'application' }, activityName: 'work-hours' }, ...]
blockedCount += attempts.length;
```

`target` is the same handle [`getTokens`](./selections.md) returns for that
token, so it renders with a [label](../components.md#token-labels) and a
selection that contains it — the blocked selection, or the activity's:

```tsx
{attempts.map((attempt) =>
  attempt.target ? (
    <TokenLabel
      key={attempt.timestamp}
      selectionId="my-blocklist"
      handle={attempt.target.handle}
      tokenType={attempt.target.type}
      displayMode="title-and-icon"
    />
  ) : null,
)}
```

An app shielded through a category reports the **category** (iOS identifies
such a shield by its category only). `target` is `null` when no handle could
be derived; `activityName` is `null` for the manual block and when no active
block owns the shield any more. Both are `null` for interactions recorded by
versions before these fields existed (they still drain).
