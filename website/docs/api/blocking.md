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

```typescript
// 1. The user picked apps in <ActivityPicker selectionId="my-blocklist" />
// 2. Apply the shield:
ExpoScreenTime.blockSelection('my-blocklist');
```

Throws if no selection exists under that ID.

### `unblockAll()`

Remove all blocks applied via `blockSelection()` (also clears web-domain filters).

```typescript
ExpoScreenTime.unblockAll();
```

### `isBlocked(): boolean`

Whether anything is currently shielded — reflects the real ManagedSettings state,
not what you last asked for.

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

Domains currently blocked via `blockWebDomains()`.

```typescript
const domains = ExpoScreenTime.getBlockedWebDomains();
// ['facebook.com', 'tiktok.com']
```
