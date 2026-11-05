---
id: configuration
title: Configuration
sidebar_position: 4
---

# Configuration

## Basic Setup

Add the plugin to your `app.json` or `app.config.js`:

```json
{
  "expo": {
    "ios": {
      "bundleIdentifier": "com.yourcompany.yourapp",
      "appleTeamId": "YOUR_TEAM_ID"
    },
    "plugins": ["expo-screen-time"]
  }
}
```

The `expo-screen-time` plugin automatically configures:

- **App Groups** entitlement (for sharing data between app and extensions)
- **Family Controls** entitlement
- The **iOS extension targets** (added directly to the Xcode project — no `@bacons/apple-targets` required)

:::warning Run prebuild with `--clean`
Run `npx expo prebuild --clean` after adding the plugin so the extension targets are generated. Always use `--clean` when **upgrading `expo-screen-time`** (a new version may add or remove a target source file) or when **changing `ios.bundleIdentifier`**. Incremental prebuilds (`--no-clean`) reuse the existing extension targets, App Group, and Sources build phase as-is. Since Expo SDK 58, a plain `npx expo prebuild` already cleans.
:::

The shared App Group is `group.<your ios.bundleIdentifier>`, configured automatically and fixed at the first prebuild. It only changes if the bundle identifier changes — and then only with `--clean` (an incremental prebuild leaves the old group on the main app alongside the new one). Besides the shared `UserDefaults` suite, the group's container holds `expo_st_block_state.lock`, an empty file the app and its extensions lock to serialize their changes to the block state; leave it alone.

## Extension Targets

This library ships with 4 iOS extension targets that enable advanced Screen Time features:

| Target | Purpose |
|--------|---------|
| `DeviceActivityReportExtension` | Display usage analytics (screen time reports) |
| `DeviceActivityMonitorExtension` | Schedule-based monitoring and automatic blocking |
| `ShieldConfigurationExtension` | Customize the shield (blocking) screen appearance |
| `ShieldActionExtension` | Handle shield button actions (close, snooze, unblock) |

By default, all 4 targets are generated.

## Target Selection

To include only specific targets, use `expo.extra.expoScreenTime.targets`:

```json
{
  "expo": {
    "extra": {
      "expoScreenTime": {
        "targets": ["ShieldConfigurationExtension", "DeviceActivityReportExtension"]
      }
    },
    "plugins": ["expo-screen-time"]
  }
}
```

- **Omit** `targets` → all 4 targets are generated (default).
- **Empty array** `[]` → no extension targets (basic app/category blocking only).

## Android {#android}

The plugin needs nothing on Android: the permissions the engine uses
(`PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, the foreground service and its
`specialUse` type, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`), the
launcher and home `<queries>` intents (never `QUERY_ALL_PACKAGES`), the enforcement
service in its own `:screentime` process, the boot and update receivers and
the device-admin receiver come from the library's own manifest, merged into
yours by Gradle.

Two options, under `expo.extra.expoScreenTime.android`:

```json
{
  "expo": {
    "extra": {
      "expoScreenTime": {
        "android": { "parentalControl": true, "features": ["blocking", "monitoring"] }
      }
    },
    "plugins": ["expo-screen-time"]
  }
}
```

| Option | Default | |
|---|---|---|
| `parentalControl` | `false` | The app is a parental-control app: adds Google Play's mandatory `IsMonitoringTool` manifest flag with the value `child_monitoring` ([Play Console help](https://support.google.com/googleplay/android-developer/answer/12955211)). Play requires it in every version code of such an app; Google Play Protect then tells the user a monitoring app is installed. Turning the option off removes the entry again. |
| `features` | every feature | The features the app uses, by the names of [`ScreenTimeFeature`](./types.md): `blocking`, `monitoring`, `usageReports`, `shieldOpenApp`, `appRemovalProtection`, `webDomainBlocking`. The plugin removes from your manifest (`tools:node="remove"`) every component and permission no listed feature needs; see below. An unknown name fails the prebuild. Android only: the iOS build is shaped by [`targets`](#target-selection). |

### Features {#android-features}

An app that only reads usage data has no reason to be listed under Settings ›
Special app access › Picture-in-picture or Device admin apps, nor to justify
a `specialUse` foreground service in Play review. List what you use and the
rest of what this library declares leaves the merged manifest:

| Feature | Keeps |
|---|---|
| `blocking` | Everything: the engine service and its restart receiver, the shield activity and its picture-in-picture evictor, the device-admin receiver, and the permissions `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`. Schedules and thresholds that shield are part of it. |
| `monitoring` | The engine service, its restart receiver and their permissions (`PACKAGE_USAGE_STATS`, the foreground-service pair, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`). No shield, no overlay permission, no device admin: schedules and thresholds record events only. |
| `usageReports` | `PACKAGE_USAGE_STATS`. |
| `appRemovalProtection` | The device-admin receiver. |
| `shieldOpenApp`, `webDomainBlocking` | Nothing of their own (the first is a property of the shield, the second is not implemented on Android). |

Omit the option and the manifest is the library's, as before. An empty array
keeps only what the selection store and the picker need. The entries are
idempotent and go away when the option changes back, so no `--clean` is
needed.

A removed permission is removed the way Expo's `android.blockedPermissions`
removes one: from the merged manifest, **whichever library declared it**
(the Android manifest merger offers nothing finer that survives the merge).
So if another library in your app needs `POST_NOTIFICATIONS`,
`RECEIVE_BOOT_COMPLETED` or `FOREGROUND_SERVICE` (a notifications or a
background-task library), list it in `android.permissions`: what your own
manifest requests is never removed, and `android.permissions` always wins.
Check the result in the merged manifest
(`android/app/build/intermediates/merged_manifests/<variant>/processReleaseManifest/AndroidManifest.xml`)
or with `aapt2 dump permissions` on the APK. Two more notes:

- The Expo template's own manifest requests `SYSTEM_ALERT_WINDOW`, so a
  build without `blocking` still has it unless you drop it yourself with
  `android.blockedPermissions: ["SYSTEM_ALERT_WINDOW"]` (and nothing else in
  the app needs it). Your `blockedPermissions` are never touched by this
  option.
- A plugin listed *before* `expo-screen-time` that adds a permission inside
  its own manifest mod runs after this one, sees the name already present
  and skips it: use `android.permissions` for it.

At runtime the module reads what the merged manifest declares, not the
option: `getCapabilities().blocking` is `"none"` in a build without the
shield, the functions of a removed feature reject `UNSUPPORTED` (in a
`monitoring`-only build a `startMonitoring` with a `selectionId` or a
`blockOnThreshold` event does too), and the [permission steps](./api/permissions.md)
never ask for a step only a removed component needs. Requesting permissions
for a feature the build lacks rejects `UNSUPPORTED` as well, naming it; with
no `features` passed, the request covers the default features the build
has. A parental-control app (`member: "child"`) built with `monitoring`
alone has no device admin to require: add `appRemovalProtection` to keep
it. An app updated from a build with the shield to one without lifts at
launch a block the earlier build left in its store.

The engine's persistent notice (channel, title, text) is localized through
string resources you can override in your own `res/values/strings.xml`:
`expo_st_notice_channel`, `expo_st_notice_title`, `expo_st_notice_text`.

## Using with Other Apple Targets

`expo-screen-time` adds its extension targets to the Xcode project directly via Expo's config-plugin system, so it's self-contained and coexists with other plugins that edit the project (including `@bacons/apple-targets` if you use it for widgets or other extensions). No extra wiring is required.
