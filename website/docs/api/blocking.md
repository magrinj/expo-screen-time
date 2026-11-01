---
id: blocking
title: Blocking
sidebar_position: 4
---

# Blocking

## Apps & Categories

### `blockSelection(selectionId: string)`

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

Throws if no selection exists under that ID.

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
`NO_ACTIVE_BLOCK` if nothing is blocked. Budgets below 30 seconds are raised
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

Read-and-clear the queue of blocked-app attempts the user reacted to on the
shield (recorded natively by the ShieldAction extension; interactions within
2 seconds collapse into one attempt). Call it when your app foregrounds and
persist the results app-side to power "you were blocked N times" stats.

```typescript
const attempts = ExpoScreenTime.drainShieldInteractions();
// [{ button: 'primary', timestamp: 1720512000000 }, ...]
blockedCount += attempts.length;
```
