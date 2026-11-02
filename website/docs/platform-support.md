---
id: platform-support
title: Platform support
sidebar_position: 2
---

# Platform support

`expo-screen-time` is an **iOS library today**, with the Android
implementation landing slice by slice. The package autolinks on Android so a
cross-platform app installs, builds and renders; the permission onboarding
and the selections (picker, store, labels) are real there already, and
nothing is enforced yet.

| Platform | Status |
|---|---|
| **iOS 16+** | Supported. Every API on this site is implemented. |
| **Android 7+ (API 24)** | Installs and builds. The [permission steps](./api/permissions.md#android) read and request the real settings; the [picker](./components.md#activity-picker), the [selection store](./api/selections.md) and the [token labels](./components.md#token-labels) work for apps (`getCapabilities().selectableKinds.applications`). Blocking and the shield are implemented but **not yet enabled** (below); monitoring, unlocks and reports still report their capability off. Everything behind an off capability fails with the `UNSUPPORTED` error code; `<DeviceActivityReport />` renders its fallback. |
| **Web** | Not supported. |

## What works on Android now

- **Selections.** `<ActivityPicker />` lists the apps the user can launch
  (icon and name, searchable, multi-select) and stores the choice natively
  under its `selectionId`; `listSelections`, `getSelectionMetadata`,
  `getTokens`, `removeToken`, the set operations and `exportSelection` /
  `importSelection` work as on iOS. `<ApplicationLabel />` renders an app's
  icon and name from a handle. Apps only: `selectableKinds.categories` and
  `webDomains` are `false`, so the picker has no category or web-domain
  rows and `CategoryLabel` / `WebDomainLabel` render their placeholder.
  Details on the [selections page](./api/selections.md#android) and the
  [components page](./components.md#android).

- **`getScreenTimePermissionsAsync()` / `requestScreenTimePermissionsAsync()`**
  list the Android steps with their real status (`overlay`, `usageAccess`,
  `notifications`, `deviceAdmin`, `batteryOptimization`, `backgroundAutostart`)
  and open the matching Settings screen one step at a time. Details on the
  [permissions page](./api/permissions.md#android).
- **Blocking and the shield: preview, not yet enabled.** `blockSelection`
  (blocklist and apps-only allowlist), `unblockAll`, `isBlocked` and the
  shield configuration are implemented, with an enforcement engine that
  detects the foreground app and a shield drawn by the library over it, but
  `getCapabilities().blocking` still reports `"none"` and the functions still
  fail with `UNSUPPORTED`: every blocking flow of the behavioural suite keys
  on that capability, and it flips to `"friction"` only once monitoring and
  unlocks exist too. There is no option to turn it on in a published build.
  What ships, and how it behaves, is on the [blocking](./api/blocking.md#android)
  and [shields](./api/shields.md#android) pages, so an app can be written
  against it now.
- **The config plugin** prepares the app: the permissions the engine needs,
  the launcher `<queries>` intent (no `QUERY_ALL_PACKAGES`), the enforcement
  service in its own `:screentime` process, the boot and update receivers and
  the device-admin receiver. Nothing to configure; one option
  (`android.parentalControl`) on the [configuration page](./configuration.md#android).

## What happens on Android otherwise

The Android module declares the same functions, event and views as iOS, so a
screen shared between the platforms does not crash at import or at render:

- **`getCapabilities()`** answers: `selectableKinds.applications` is `true`;
  every other capability is off (`blocking: "none"`, `usageReports: false`,
  …), with the cross-platform `limits`. Each capability turns on with the
  slice that delivers it; `blocking` turns on together with monitoring and
  unlocks, as `"friction"` (the user can stop the engine: that is recorded,
  not hidden).
- **Every function behind an off capability** fails with a coded error.
  Synchronous functions (`isBlocked`, `blockSelection`, `setShieldConfig`…)
  throw it; asynchronous ones (`startMonitoring`, `temporaryUnlock`…) reject
  with it. `error.code` is `"UNSUPPORTED"` and the message names the function.
- **Components**: `<DeviceActivityReport />` renders its built-in fallback
  text.
- **Events**: `onMonitorEvent` is declared but never emitted.

Branch on [capabilities](./api/capabilities.md) rather than on the platform:
the same code then lights up on Android as the implementation lands.

```tsx
import { blockSelection, getCapabilities } from 'expo-screen-time';

function BlockButton({ selectionId }: { selectionId: string }) {
  if (getCapabilities().blocking === 'none') return null; // Android today
  return <Button title="Block" onPress={() => blockSelection(selectionId)} />;
}
```

`UNSUPPORTED` is part of the error-code vocabulary shared by both platforms, so
a call that slips through can be caught the same way everywhere:

```ts
import { blockSelection, isScreenTimeError } from 'expo-screen-time';

try {
  blockSelection(selectionId);
} catch (error) {
  if (isScreenTimeError(error) && error.code === 'UNSUPPORTED') {
    // not available on this platform yet
  } else {
    throw error;
  }
}
```

Until the Android implementation ships, keep Screen Time calls out of render
paths and unconditional effects, behind one of these checks. The permission
and selection calls are the exception: they answer on both platforms.

## Roadmap

The Android port follows the plan in
the platform support page:
a per-API mapping of the iOS behaviour to Android (UsageStats detection, a
blocking Activity as the shield, device admin for anti-tamper), with the
stages and their exit criteria. The module is in stage 3 of that plan, the
Android MVP, delivered in slices: the foundation (config plugin, engine
service, permission steps), the selections (picker, store, labels) and the
blocking engine with its shield (behind the switch above) are in;
monitoring, unlocks and tamper evidence follow, and the unlock slice turns
`blocking` on.
