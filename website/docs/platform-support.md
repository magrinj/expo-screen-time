---
id: platform-support
title: Platform support
sidebar_position: 2
---

# Platform support

`expo-screen-time` runs on **iOS and Android** behind one API. On Android the
restrictions are `friction`, not OS-enforced: a foreground service draws the
shield, and the user can stop it from the system settings, which
`getEnforcementGaps()` reports. Usage reports are still iOS-only.

| Platform | Status |
|---|---|
| **iOS 16+** | Supported. Every API on this site is implemented. |
| **Android 7+ (API 24)** | Supported for apps: the [permission steps](./api/permissions.md#android), the [picker](./components.md#activity-picker), the [selection store](./api/selections.md) and the [token labels](./components.md#token-labels), [blocking](./api/blocking.md#android) and the [shield](./api/shields.md#android), [monitoring](./api/monitoring.md#android), the temporary unlock, the shield actions and the [enforcement gaps](./api/capabilities.md#getenforcementgapssince). `getCapabilities().blocking` is `"friction"`. Categories, web domains, reports and `openApplication` report their capability off and fail with the `UNSUPPORTED` error code; `<DeviceActivityReport />` renders its fallback. |
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
- **Blocking and the shield.** `blockSelection` (blocklist and apps-only
  allowlist), `unblockAll`, `isBlocked` and the shield configuration work as
  on iOS. An enforcement engine, a foreground service in its own process,
  detects the foreground app from UsageStats while a block is in force and
  draws the shield, a full-screen Activity, over it (about 120 ms after the
  app comes forward on a Pixel 8a). It is `"friction"`: the user can stop
  the engine from the Task Manager or Settings, and such periods are
  reported by `getEnforcementGaps()`. Details on the
  [blocking](./api/blocking.md#android) and [shields](./api/shields.md#android)
  pages.
- **Monitoring.** `startMonitoring` and the rest of the
  [monitoring page](./api/monitoring.md#android): schedules evaluated by the
  engine as a function of time (midnight wrap, weekdays, the iOS limits),
  exactly-once events, usage thresholds measured from UsageStats and
  `blockOnThreshold`, with the iOS block composition. Boundaries that fall
  with the screen off are observed at an inexact alarm.
- **Temporary unlock, snoozes and shield actions.** `temporaryUnlock` (the
  whole block, or one app with `target`), `isTemporarilyUnlocked`,
  `getRemainingUnlockSeconds`, `relock`, `setShieldActions` /
  `setShieldActionsForActivity` (close, unblock, defer, snooze, a direct
  `openApp`) and `drainShieldInteractions`. The engine meters the budget in
  exact seconds of use (`meteringSeconds` is 1); the shield comes back by
  itself when the budget is spent.
- **Enforcement gaps.** `getEnforcementGaps()` reports when a block was
  intended but not enforced: the user stopped the app (`stoppedByUser`), the
  system killed the engine or refused to restart it (`killedBySystem`), a
  reboot (`unknown`), or usage access / the overlay permission was revoked
  (`permissionRevoked`, open while it is). Details on the
  [capabilities page](./api/capabilities.md#getenforcementgapssince).
- **The config plugin** prepares the app: the permissions the engine needs,
  the launcher `<queries>` intent (no `QUERY_ALL_PACKAGES`), the enforcement
  service in its own `:screentime` process, the boot and update receivers and
  the device-admin receiver. Nothing to configure; one option
  (`android.parentalControl`) on the [configuration page](./configuration.md#android).

## What is off on Android

The Android module declares the same functions, event and views as iOS, so a
screen shared between the platforms does not crash at import or at render:

- **`getCapabilities()`** answers: `blocking: "friction"`,
  `selectableKinds.applications: true`, `shieldOpenApp: "direct"`, the
  cross-platform `limits`; `categories`, `webDomains`, `webDomainBlocking`,
  `usageReports`, `openApplication`, `appRemovalProtection` and
  `appInstallationBlocking` are off.
- **Every function behind an off capability** fails with a coded error.
  Synchronous functions (`blockWebDomains`, `denyAppRemoval`…) throw it;
  asynchronous ones (`openApplication`…) reject with it. `error.code` is
  `"UNSUPPORTED"` and the message names the function.
- **Components**: `<DeviceActivityReport />` renders its built-in fallback
  text.

Branch on [capabilities](./api/capabilities.md) rather than on the platform:

```tsx
import { blockSelection, getCapabilities } from 'expo-screen-time';

function BlockButton({ selectionId }: { selectionId: string }) {
  if (getCapabilities().blocking === 'none') return null;
  return <Button title="Block" onPress={() => blockSelection(selectionId)} />;
}
```

`UNSUPPORTED` is part of the error-code vocabulary shared by both platforms, so
a call that slips through can be caught the same way everywhere:

```ts
import { blockWebDomains, isScreenTimeError } from 'expo-screen-time';

try {
  blockWebDomains(['example.com']);
} catch (error) {
  if (isScreenTimeError(error) && error.code === 'UNSUPPORTED') {
    // not available on this platform
  } else {
    throw error;
  }
}
```

## Roadmap

The Android port follows the plan in
the platform support page:
a per-API mapping of the iOS behaviour to Android (UsageStats detection, a
blocking Activity as the shield, device admin for anti-tamper), with the
stages and their exit criteria. The module is in stage 3 of that plan, the
Android MVP: the foundation, the selections, blocking and the shield,
monitoring, the temporary unlock with the shield actions and the enforcement
gaps are in. Still open: a restart path that does not rely on the system's
sticky restart, the device-admin friction, and 24 h survival runs on a Pixel
and a Samsung; usage reports are a later phase.
