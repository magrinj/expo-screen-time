---
id: selections
title: Selections
sidebar_position: 3
---

# Selections

A **selection** is a natively-stored set of app/category/web-domain tokens,
created by the [`<ActivityPicker />`](../components.md#activity-picker) and
addressed everywhere by a `selectionId` string. These utilities manage stored
selections. All are named exports and also live on the default export.

## Inspecting

### `listSelections(): Promise<string[]>`

All persisted selection IDs.

### `hasSelection(selectionId: string): Promise<boolean>`

Whether a selection exists under that ID.

### `getSelectionMetadata(selectionId: string): Promise<SelectionMetadata | null>`

Counts only — no token data.

```typescript
const meta = await getSelectionMetadata('my-blocklist');
// { applicationCount: 12, categoryCount: 2, webDomainCount: 0 }
```

### `getTokens(selectionId: string): Promise<TokenHandle[] | null>`

Opaque token handles for a selection — pass them to
[token labels](../components.md#token-labels) to render app/category names, or to
`removeToken`. Call this on app restart to restore handles from the native store.

```typescript
const tokens = await getTokens('my-blocklist');
// [{ handle: '…', type: 'application' }, { handle: '…', type: 'category' }]
```

### `removeToken(selectionId: string, handle: string): Promise<void>`

Remove a single token from a selection by its handle.

### `deleteSelection(selectionId: string): Promise<void>`

Delete the stored selection.

## Set operations

### `mergeSelections(targetId: string, sourceIds: string[]): Promise<void>`

Union of the sources, saved under `targetId`.

### `keepCommonSelections(targetId: string, sourceIds: string[]): Promise<void>`

Intersection of the sources, saved under `targetId`.

### `subtractSelection(targetId: string, fromId: string, subtractId: string): Promise<void>`

`fromId` minus `subtractId`, saved under `targetId`.

```typescript
await mergeSelections('all-distractions', ['social', 'games']);
await subtractSelection('weekday-blocklist', 'all-distractions', 'work-tools');
```

## Export / Import

### `exportSelection(selectionId: string): Promise<string | null>`

Export a selection as an opaque base64 string for backup.

:::warning Device-bound
Tokens are device-specific and tied to the user's iCloud account. An exported
selection only imports back on the **same device/account** — this is not a
cross-device sync mechanism. Treat the string as an opaque blob.
:::

### `importSelection(selectionId: string, data: string): Promise<boolean>`

Import a previously exported selection. Returns `false` if the data doesn't
decode to a valid selection.
