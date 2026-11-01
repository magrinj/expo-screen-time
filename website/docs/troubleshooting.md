---
id: troubleshooting
title: Troubleshooting
sidebar_position: 7
---

# Troubleshooting

## "Family Controls authorization required"

The user must grant Family Controls permission before any blocking features work. Use [`requestScreenTimePermissionsAsync()`](./api/permissions.md) to prompt for authorization.

## Extensions not appearing

1. Ensure `"expo-screen-time"` is in your `plugins` array
2. Run `npx expo prebuild --clean` to regenerate native code
3. Check that your `ios.bundleIdentifier` and `ios.appleTeamId` are set
4. If filtering targets, confirm the extension is listed in `expo.extra.expoScreenTime.targets`

## Shield configuration not applying

The `ShieldConfigurationExtension` must be included in your targets. Check your `expo.extra.expoScreenTime.targets` configuration if you're filtering targets.

## Schedule monitoring not working

The `DeviceActivityMonitorExtension` must be included. Monitor events fire when the device is first used after the schedule time (not exactly at the scheduled time).

## `error.code` is `undefined`

Rejected calls carry a `code` (`NO_ACTIVE_BLOCK`, `INVALID_CONFIG`, `MONITORING_ROLLBACK_FAILED`, …) that you can branch on:

```ts
try {
  await ExpoScreenTime.temporaryUnlock(60);
} catch (error: any) {
  if (error.code === "NO_ACTIVE_BLOCK") {
    // nothing is blocked
  }
}
```

If `error.code` is `undefined` while `error.message` is set, your app resolves an `expo-modules-jsi` older than 56.0.11, which drops the code on rejected promises. This library does not ship that package; your app's Expo SDK provides it. Update to the latest SDK 56 patch (`npx expo install --fix`), or to SDK 57 or later.

## Running the example app

See the [`example/`](https://github.com/magrinj/expo-screen-time/tree/main/example) directory for a complete app demonstrating all features:

```bash
cd example
bun install   # postinstall builds the plugin (../plugin/build) and symlinks the module
bun run ios
```

The config plugin loads from the gitignored `plugin/build`. The example's `postinstall` runs the root `bun run build` for you; if you build the module manually, run `bun run build` at the repo root before `expo prebuild`.
