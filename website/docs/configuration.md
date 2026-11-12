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
`<queries>` intents (never `QUERY_ALL_PACKAGES`), the enforcement
service in its own `:screentime` process, the boot and update receivers and
the device-admin receiver come from the library's own manifest, merged into
yours by Gradle. The `<queries>` intents are the launcher and home intents
(the picker's list, and telling a launcher from an app) and the four the
[app restrictions](./api/restrictions.md#android) resolve their targets by:
`ACTION_DELETE` on a `package:` URI (the uninstall dialog),
`ACTION_INSTALL_PACKAGE` and `ACTION_VIEW` of an APK (the package installer),
and `ACTION_VIEW` of a `market://details` link (the stores). Targeted
`<queries>` is the form Google Play prefers.

Three options, under `expo.extra.expoScreenTime.android`:

```json
{
  "expo": {
    "extra": {
      "expoScreenTime": {
        "android": {
          "parentalControl": true,
          "features": ["blocking", "monitoring"],
          "accessibilityEngine": false
        }
      }
    },
    "plugins": ["expo-screen-time"]
  }
}
```

| Option | Default | |
|---|---|---|
| `parentalControl` | `false` | The app is a parental-control app: adds Google Play's mandatory `isMonitoringTool` manifest flag with the value `child_monitoring` ([Play Console help](https://support.google.com/googleplay/android-developer/answer/12955211)). Play requires it in every version code of such an app; Google Play Protect then tells the user a monitoring app is installed. Turning the option off removes the entry again. |
| `features` | every feature | The features the app uses, by the names of [`ScreenTimeFeature`](./types.md): `blocking`, `monitoring`, `usageReports`, `shieldOpenApp`, `appRemovalProtection`, `webDomainBlocking`. The plugin removes from your manifest (`tools:node="remove"`) every component and permission no listed feature needs; see below. An unknown name fails the prebuild. Android only: the iOS build is shaped by [`targets`](#target-selection). |
| `accessibilityEngine` | `false` | Adds the library's `AccessibilityService` to your manifest, so the blocking engine also hears about apps opened from a **work profile**, **Private Space** or a **cloned app** (Samsung Dual Messenger), which usage access alone never reports. The engine runs exactly as without it; the service is one more detector, which the user turns on in Settings › Accessibility through the recommended [`accessibility` permission step](./api/permissions.md#android). **Declares `BIND_ACCESSIBILITY_SERVICE` to Google Play**: see below before turning it on. Needs `blocking` among `features`; the prebuild fails otherwise. Turning the option off removes the entry again. |

### Features {#android-features}

An app that only reads usage data has no reason to be listed under Settings ›
Special app access › Picture-in-picture or Device admin apps, nor to justify
a `specialUse` foreground service in Play review. List what you use and the
rest of what this library declares leaves the merged manifest:

| Feature | Keeps |
|---|---|
| `blocking` | Everything: the engine service and its restart receiver, the shield activity and its picture-in-picture evictor, the device-admin receiver, and the permissions `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_SPECIAL_USE`. Schedules and thresholds that shield are part of it. |
| `monitoring` | The engine service, its restart receiver and their permissions (`PACKAGE_USAGE_STATS`, `FOREGROUND_SERVICE_SPECIAL_USE`). No shield, no overlay permission, no device admin: schedules and thresholds record events only. |
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
That is why the generic permissions the engine also uses (`FOREGROUND_SERVICE`,
`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`) are never removed: other
libraries need them too. If another foreground service of your app needs
`FOREGROUND_SERVICE_SPECIAL_USE`, list it in `android.permissions`: what your
own manifest requests is never removed, and `android.permissions` always wins.
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

### The accessibility engine {#android-accessibility-engine}

Usage access reports the main profile only: a blocked app opened from a work
profile, Private Space or a cloned copy is invisible to the engine, so it is
never shielded. With `accessibilityEngine: true` the plugin declares an
accessibility service in your manifest, in the engine's own process:

```xml
<service
  android:name="expo.modules.screentime.ScreenTimeAccessibilityService"
  android:exported="true"
  android:process=":screentime"
  android:label="@string/expo_st_accessibility_label"
  android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
  <intent-filter>
    <action android:name="android.accessibilityservice.AccessibilityService" />
  </intent-filter>
  <meta-data android:name="android.accessibilityservice"
             android:resource="@xml/expo_st_accessibility_service" />
</service>
```

plus the `QUERY_ADVANCED_PROTECTION_MODE` permission (`normal` level, shown
to nobody), which lets the module tell when Android 17's Advanced
Protection has disabled every non-tool accessibility service. Android tells
the service when a window of a restricted app comes forward, from every
profile, and the engine shields it the same way; the service asks for
window-state events only, is scoped at runtime to the restricted apps, cannot
read window content (`canRetrieveWindowContent` is `false`) and declares
`isAccessibilityTool="false"`, as Google Play requires of a blocker. The
UsageStats engine keeps running underneath: when the service is off, crashed
or blocked, the main profile is enforced as before and the lost coverage is
reported as an [enforcement gap](./api/capabilities.md#android-gaps) with
`scope: "otherProfiles"`.

**What the option costs.** `BIND_ACCESSIBILITY_SERVICE` is the API Google
Play reviews hardest. Before an opted-in build can be published you must:

1. fill in the **AccessibilityService API declaration** in the Play Console
   (the use, whether personal data is collected, and a link to a short video
   of the consent flow); the description must match what the service does,
   which the Settings description string says;
2. show a **prominent disclosure inside the app**, before sending the user to
   Settings, during normal use (not only in the store listing): the
   [permissions page](./api/permissions.md#android-accessibility) gives the
   pattern and a draft text;
3. keep the store listing honest about it.

The prebuild prints a warning naming these whenever the option is on. A
rejection can become a strike on the Play account: publish the build
**without** the option first, then an opted-in build as a separate upload, so
a rejection notice points at one cause. The service's name and description in
Settings are the `expo_st_accessibility_label` and
`expo_st_accessibility_description` string resources; override them in your
own `res/values` to localise them. The default description says only what the
service itself does; your app's data practices go in your override, and only
when they are true, since Play reads it against your declaration. Nothing changes for apps that do not opt
in: the default build is byte-identical.

## Using with Other Apple Targets

`expo-screen-time` adds its extension targets to the Xcode project directly via Expo's config-plugin system, so it's self-contained and coexists with other plugins that edit the project (including `@bacons/apple-targets` if you use it for widgets or other extensions). No extra wiring is required.
