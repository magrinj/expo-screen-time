---
id: platform-support
title: Platform support
sidebar_position: 2
---

# Platform support

`expo-screen-time` is an **iOS library today**. The package also autolinks on
Android so a cross-platform app installs, builds and renders, but nothing is
enforced there yet.

| Platform | Status |
|---|---|
| **iOS 16+** | Supported. Every API on this site is implemented. |
| **Android** | Installs and builds. `getCapabilities()` reports every capability off; every other function fails with the `UNSUPPORTED` error code; the native views render an empty view. The implementation is planned, see below. |
| **Web** | Not supported. |

## What happens on Android

The Android module declares the same functions, event and views as iOS, so a
screen shared between the platforms does not crash at import or at render:

- **`getCapabilities()`** answers: every capability is off (`blocking: "none"`,
  `usageReports: false`, …), with the cross-platform `limits`.
- **Every other function** fails with a coded error. Synchronous functions (`isBlocked`,
  `blockSelection`, `setShieldConfig`…) throw it; asynchronous ones
  (`startMonitoring`, `getTokens`, `temporaryUnlock`…) reject with it.
  `error.code` is `"UNSUPPORTED"` and the message names the function.
- **Components**: `<ActivityPicker />` and `<TokenLabel />` (and the
  `ApplicationLabel` / `CategoryLabel` / `WebDomainLabel` wrappers) render an
  empty view; their props are accepted and ignored.
  `<DeviceActivityReport />` renders its built-in fallback text.
- **Events**: `onMonitorEvent` is declared but never emitted.
- **Config plugin**: `expo prebuild --platform android` works; the plugin makes
  no Android changes yet.

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
paths and unconditional effects, behind one of these checks.

## Roadmap

The Android port follows the plan in
the platform support page:
a per-API mapping of the iOS behaviour to Android (UsageStats / Accessibility
detection, a blocking Activity as the shield, device admin for anti-tamper),
with the stages and their exit criteria. The current state of the module is
stage 1 of that plan: the contract and the autolinking skeleton.
