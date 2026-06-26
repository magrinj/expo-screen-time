---
id: blocking
title: Blocking
sidebar_position: 3
---

# Blocking

## Apps & Categories

### `blockSelection(selection: FamilyActivitySelection)`

Block apps, categories, and web domains from a selection.

```typescript
// Selection comes from ExpoScreenTimeActivityPicker's onSelectionChange
ExpoScreenTime.blockSelection(selection);
```

See [`FamilyActivitySelection`](../types.md#familyactivityselection) and the [activity picker](../components.md#activity-picker).

### `unblockAll()`

Remove all blocks applied via `blockSelection()`.

```typescript
ExpoScreenTime.unblockAll();
```

## Web Domains

### `blockWebDomains(domains: string[])`

Programmatically block specific web domains.

```typescript
ExpoScreenTime.blockWebDomains(['facebook.com', 'instagram.com', 'tiktok.com']);
```

### `unblockWebDomains(domains: string[])`

Unblock specific web domains.

```typescript
ExpoScreenTime.unblockWebDomains(['facebook.com']);
```

### `unblockAllWebDomains()`

Unblock all domains blocked via `blockWebDomains()`.

```typescript
ExpoScreenTime.unblockAllWebDomains();
```

### `getBlockedWebDomains(): string[]`

Get the list of currently blocked domain strings.

```typescript
const domains = ExpoScreenTime.getBlockedWebDomains();
// ['facebook.com', 'tiktok.com']
```
