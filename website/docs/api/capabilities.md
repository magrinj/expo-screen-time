---
id: capabilities
title: Capabilities
sidebar_position: 9
---

# Capabilities

Branch on what the device can do, not on the platform.

## `getCapabilities()`

What this build, on this device, with the authorization last granted, can do.
Synchronous and cheap: call it at render time.

```typescript
const { usageReports, appRemovalProtection, limits } = ExpoScreenTime.getCapabilities();

if (appRemovalProtection !== "none") ExpoScreenTime.denyAppRemoval();
const shortest = limits.minIntervalSeconds; // 900 on iOS
```

| Field | iOS |
|---|---|
| `blocking` | `"enforced"`: the OS draws the shield. |
| `selectableKinds` | Applications, categories and web domains. |
| `webDomainBlocking` | `true` |
| `appRemovalProtection`, `appInstallationBlocking` | `"friction"` after an `individual` authorization (the user can revoke Screen Time access in Settings, which lifts them); `"enforced"` after a `child` one (a parent holds it). |
| `usageReports` | `true` when the report extension is in the build (the config plugin can leave it out). |
| `shieldOpenApp` | `"notification"`: a shield's [`openApp` action](./shields.md#open-app) posts a notification the user taps, which needs the `notifications` [permission step](./permissions.md#steps). |
| `openApplication` | `false`: iOS cannot launch another app. |
| `limits` | 19 monitored activities, intervals from 15 minutes to 7 days. |

Values are `Enforcement` levels: `"enforced"` (the OS refuses the action),
`"friction"` (the restriction holds, but the user can lift it through a route
the app cannot close) or `"none"`. On iOS before 16, every capability is off.

`limits.maxMonitoredActivities` is what you can count on: iOS has 20
DeviceActivity slots and the temporary-unlock meter holds one. Each
[snooze](./shields.md) in progress also holds one until it is spent.

## `getEnforcementGaps(since?)`

Periods when a block should have applied but could not, oldest first. With
`since` (Unix ms), only the gaps still in progress at or ending after it.

```typescript
const gaps = await ExpoScreenTime.getEnforcementGaps(Date.now() - 7 * 24 * 3600 * 1000);
// [{ from: 1793524064004, to: 1793527664004, reason: "authorizationRevoked" }]
```

iOS reports `authorizationRevoked`: revoking Screen Time access lifts every
shield and stops monitoring. The revocation itself is not observable, so the
module checks at launch and whenever the app returns to the foreground: a gap
opens at the last time access was seen granted, and closes (`to`) when it is
seen granted again. An open gap has `to: null`. Never having had access is not
a gap.

## `openApplication(handle)`

Bring an app to the foreground by handle. iOS cannot launch another app, so it
rejects with `UNSUPPORTED`; check `getCapabilities().openApplication` first.
