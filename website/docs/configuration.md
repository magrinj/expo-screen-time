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

## Using with Other Apple Targets

`expo-screen-time` adds its extension targets to the Xcode project directly via Expo's config-plugin system, so it's self-contained and coexists with other plugins that edit the project (including `@bacons/apple-targets` if you use it for widgets or other extensions). No extra wiring is required.
