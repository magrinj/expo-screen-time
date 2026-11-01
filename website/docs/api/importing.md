---
id: importing
title: Importing
sidebar_position: 1
---

# Importing

```typescript
import ExpoScreenTime, {
  // Hooks
  useScreenTimePermissions,
  useMonitorEvents,
  // Components
  ActivityPicker,
  DeviceActivityReport,
  TokenLabel,
  ApplicationLabel,
  CategoryLabel,
  WebDomainLabel,
  // Report templates (JSX-composed report layouts)
  Report,
  setReportTemplate,
  clearReportTemplate,
  // Functions (also available on the default export)
  blockSelection,
  startMonitoring,
  temporaryUnlock,
  listSelections,
  getTokens,
  // Errors
  isScreenTimeError,
  SCREEN_TIME_ERROR_CODES,
} from 'expo-screen-time';
```

Every function documented in this section is both a named export and a method
of the default export (`ExpoScreenTime`): `blockSelection("work")` and
`ExpoScreenTime.blockSelection("work")` are the same call. Hooks and components
are named exports only. Errors carry a [`ScreenTimeErrorCode`](../types.md#screentimeerrorcode).

## The `selectionId` model

Apple's Screen Time tokens are opaque and never readable from JavaScript. Every
API that works with a set of apps/categories/domains references a **selection
stored natively**, addressed by a `selectionId` string that you choose:

1. Render `<ActivityPicker selectionId="my-blocklist" />` — the user's choice is
   saved natively under that ID.
2. Pass the same ID to the imperative APIs: `blockSelection("my-blocklist")`,
   `startMonitoring({ selectionId: "my-blocklist", ... })`.
3. Inspect it with the [selection utilities](./selections.md) — counts, opaque
   token handles, set operations.

JS never sees token contents — only counts (`SelectionMetadata`) and opaque
[`TokenHandle`](../types.md#tokenhandle)s used to render
[labels](../components.md#token-labels) or remove individual tokens.
