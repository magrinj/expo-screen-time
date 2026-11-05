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
| `blocking` | `"enforced"`: the OS draws the shield. Android: `"friction"`: the library's engine draws it, and the user can stop the engine from the Task Manager or Settings, which `getEnforcementGaps` records ([platform support](../platform-support.md)); `"none"` when the config plugin's [`android.features`](../configuration.md#android-features) left the shield out of the build, and every blocking function then rejects `UNSUPPORTED`. |
| `selectableKinds` | Applications, categories and web domains. Android: applications only (`categories` and `webDomains` are `false`: [selections](./selections.md#android)). |
| `webDomainBlocking` | `true` |
| `appRemovalProtection`, `appInstallationBlocking` | `"friction"` after an `individual` authorization (the user can revoke Screen Time access in Settings, which lifts them); `"enforced"` after a `child` one (a parent holds it). |
| `usageReports` | `true` when `<DeviceActivityReport />` renders data: on iOS the report extension is in the build (the config plugin can leave it out), on Android the build requests usage access (the `usageReports` feature of [`android.features`](../configuration.md#android-features)). |
| `shieldOpenApp` | `"notification"`: a shield's [`openApp` action](./shields.md#open-app) posts a notification the user taps, which needs the `notifications` [permission step](./permissions.md#steps-on-ios). Android: `"direct"` (the shield launches the app). |
| `openApplication` | `false`: iOS cannot launch another app. |
| `limits` | 19 monitored activities, intervals from 15 minutes to 7 days. |

Values are `Enforcement` levels: `"enforced"` (the OS refuses the action),
`"friction"` (the restriction holds, but the user can lift it through a route
the app cannot close) or `"none"`. On iOS before 16, every capability is off.
The answer reflects the build where a field exists for it: `usageReports`
on iOS (the report extension can be left out), `blocking` on Android (the
config plugin's `android.features` can leave the shield out). Monitoring has
no field: a `monitoring`-only Android build and a `usageReports`-only one
both read `blocking: "none"`, and only the call tells them apart
(`startMonitoring` rejects `UNSUPPORTED` in the second). `appRemovalProtection`
is `"none"` on Android whatever the build.

`limits.maxMonitoredActivities` is what `startMonitoring` enforces (a 20th
activity rejects `EXCESSIVE_ACTIVITIES`): iOS has 20 DeviceActivity slots and
the temporary-unlock meter holds one. Each [snooze](./shields.md) in progress
also holds one until it is spent.

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

### Android {#android-gaps}

Android enforcement is `"friction"`: the engine that draws the shield can be
stopped. A gap is recorded for the periods a block would have shielded
something (a manual block; a schedule inside its occurrences, including an
activity with a `blockOnThreshold` event; not an occurrence `unblockAll`
lifted) while the engine could not act:

| `reason` | What happened |
|---|---|
| `stoppedByUser` | The user stopped the app: Task Manager "Stop", "Force stop" in App info, or a vendor battery manager that force-stops. Read from the system's exit history (Android 11+). |
| `killedBySystem` | The system killed the engine (memory, a signal, the freezer) or refused to restart it, or the engine went silent with no record (its heartbeat stale). |
| `permissionRevoked` | Usage access or "Display over other apps" was turned off, or a runtime permission revocation killed the process. Open (`to: null`) while a step is off; closes when both are back. |
| `unknown` | The device rebooted (from the boot to the engine's return; from the shutdown when a boot ran without the engine, such as safe mode), the engine crashed, the app was updated, or a reason this version does not know. |

Gaps are observed at launch, whenever the app returns to the foreground, by
the engine itself every minute and on every permission change, and by
`getEnforcementGaps()`. A death the system undid within 10 seconds (its own
restart of the engine) is not reported: the engine shields the app on screen
when it starts. An engine gap and a permission gap can overlap; add up gap
time only after merging overlaps. Closed gaps are kept for 30 days, 50 at
most.

## `openApplication(handle)`

Bring an app to the foreground by handle. iOS cannot launch another app, so it
rejects with `UNSUPPORTED`; check `getCapabilities().openApplication` first.
