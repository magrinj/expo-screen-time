---
id: platform-support
title: Platform support
sidebar_position: 2
---

# Platform support

`expo-screen-time` is an **iOS library today**, with the Android
implementation landing slice by slice. The package autolinks on Android so a
cross-platform app installs, builds and renders; the permission onboarding is
real there already, and nothing is enforced yet.

| Platform | Status |
|---|---|
| **iOS 16+** | Supported. Every API on this site is implemented. |
| **Android 7+ (API 24)** | Installs and builds. The [permission steps](./api/permissions.md#android) read and request the real settings. `getCapabilities()` still reports every capability off, and every other function fails with the `UNSUPPORTED` error code; the native views render an empty view. |
| **Web** | Not supported. |

## What works on Android now

- **`getScreenTimePermissionsAsync()` / `requestScreenTimePermissionsAsync()`**
  list the Android steps with their real status (`overlay`, `usageAccess`,
  `notifications`, `deviceAdmin`, `batteryOptimization`, `backgroundAutostart`)
  and open the matching Settings screen one step at a time. Details on the
  [permissions page](./api/permissions.md#android).
- **The config plugin** prepares the app: the permissions the engine needs,
  the launcher `<queries>` intent (no `QUERY_ALL_PACKAGES`), the enforcement
  service in its own `:screentime` process, the boot and update receivers and
  the device-admin receiver. Nothing to configure; one option
  (`android.parentalControl`) on the [configuration page](./configuration.md#android).

## What happens on Android otherwise

The Android module declares the same functions, event and views as iOS, so a
screen shared between the platforms does not crash at import or at render:

- **`getCapabilities()`** answers: every capability is off (`blocking: "none"`,
  `usageReports: false`, …), with the cross-platform `limits`. Each capability
  turns on with the step that delivers it.
- **Every other function** fails with a coded error. Synchronous functions (`isBlocked`,
  `blockSelection`, `setShieldConfig`…) throw it; asynchronous ones
  (`startMonitoring`, `getTokens`, `temporaryUnlock`…) reject with it.
  `error.code` is `"UNSUPPORTED"` and the message names the function.
- **Components**: `<ActivityPicker />` and `<TokenLabel />` (and the
  `ApplicationLabel` / `CategoryLabel` / `WebDomainLabel` wrappers) render an
  empty view; their props are accepted and ignored.
  `<DeviceActivityReport />` renders its built-in fallback text.
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
calls are the exception: they answer on both platforms.

## Roadmap

The Android port follows the plan in
the platform support page:
a per-API mapping of the iOS behaviour to Android (UsageStats detection, a
blocking Activity as the shield, device admin for anti-tamper), with the
stages and their exit criteria. The module is in stage 3 of that plan, the
Android MVP, delivered in slices: the foundation (config plugin, engine
service, permission steps) is in; selections, blocking and the shield,
monitoring, unlocks and tamper evidence follow.
