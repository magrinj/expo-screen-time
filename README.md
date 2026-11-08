# expo-screen-time

[![npm version](https://img.shields.io/npm/v/expo-screen-time.svg)](https://www.npmjs.com/package/expo-screen-time)
[![CI](https://github.com/magrinj/expo-screen-time/actions/workflows/ci.yml/badge.svg)](https://github.com/magrinj/expo-screen-time/actions/workflows/ci.yml)
[![license](https://img.shields.io/npm/l/expo-screen-time.svg)](./LICENSE)
[![platforms](https://img.shields.io/badge/platforms-iOS%20%7C%20Android-lightgrey.svg)](https://magrinj.github.io/expo-screen-time/platform-support)

App blocking, schedules, shields and usage reports for Expo and React Native, on iOS and Android, behind one API. On iOS the module wraps Apple's Screen Time APIs (FamilyControls, ManagedSettings, DeviceActivity). On Android, which has no Screen Time API, it ships its own enforcement engine.

📚 **[Full documentation → magrinj.github.io/expo-screen-time](https://magrinj.github.io/expo-screen-time/)**

> **Note:** iOS restrictions are enforced by the OS. On Android the engine is `friction`: a foreground service draws the shield, and the user can stop it from the system settings, which `getEnforcementGaps()` reports. Categories and web domains are iOS-only today — see [Platform support](https://magrinj.github.io/expo-screen-time/platform-support).

## Features

- **Permissions** — one call site; the steps the user must allow come back as data (iOS: the Family Controls prompt; Android: the Settings toggles, one screen at a time)
- **App blocking** — block the apps the user picks in the native picker, as a blocklist or an allowlist (categories and web domains: iOS)
- **Web domain blocking** — block specific domains without user selection (iOS)
- **App Store restrictions** — prevent app installation and removal (Android: a shield over the stores, the package installer and the uninstall dialog)
- **Schedule monitoring** — time-based blocking and usage thresholds, with events
- **Shields** — customize the blocking screen and its buttons (close, snooze, unblock, open app)
- **Earned time** — usage-based temporary unlocks that re-lock automatically
- **Enforcement gaps** — `getEnforcementGaps()` tells when an Android block was intended but not enforced
- **Usage reports** — JSX-authored report templates rendered natively on both platforms (usage data never reaches JS)
- **Token labels** — display app names and icons from opaque handles (category and domain labels: iOS)

## Requirements

- iOS 16.0+, and an Apple Developer account with the Family Controls capability
- Android 7.0+ (API 24)
- Expo SDK 56+
- A [development build](https://docs.expo.dev/develop/development-builds/introduction/) — the module cannot run in Expo Go

## Installation

```bash
npm install expo-screen-time
# or
yarn add expo-screen-time
# or
bun add expo-screen-time
```

## Quick start

Add the plugin to your `app.json`:

```json
{
  "expo": {
    "ios": {
      "bundleIdentifier": "com.yourcompany.yourapp",
      "appleTeamId": "YOUR_TEAM_ID"
    },
    "android": {
      "package": "com.yourcompany.yourapp"
    },
    "plugins": ["expo-screen-time"]
  }
}
```

Then run a prebuild. On iOS it generates the extension targets; on Android the plugin needs nothing, the library's own manifest brings the permissions and the engine's service:

```bash
npx expo prebuild --clean
```

```tsx
import ExpoScreenTime, { ActivityPicker } from 'expo-screen-time';

// 1. Ask for access. iOS shows the Family Controls prompt; Android opens
//    one Settings screen per step (usage access, display over other apps),
//    so call it again until `granted` is true.
await ExpoScreenTime.requestScreenTimePermissionsAsync();

// 2. Let the user pick apps — stored natively under the ID you choose.
<ActivityPicker selectionId="my-blocklist" style={{ flex: 1 }} />

// 3. Shield everything in that selection.
ExpoScreenTime.blockSelection('my-blocklist');
```

> Always re-run `expo prebuild --clean` when upgrading `expo-screen-time` or
> changing `ios.bundleIdentifier`. See [Configuration](https://magrinj.github.io/expo-screen-time/configuration).

## Documentation

The full guide and API reference live at **[magrinj.github.io/expo-screen-time](https://magrinj.github.io/expo-screen-time/)**:

- [Platform support](https://magrinj.github.io/expo-screen-time/platform-support) — what works where, and how Android enforcement differs
- [Configuration](https://magrinj.github.io/expo-screen-time/configuration) — iOS extension targets, Android plugin options
- [API reference](https://magrinj.github.io/expo-screen-time/api/importing) — permissions, blocking, restrictions, monitoring, shields
- [Components](https://magrinj.github.io/expo-screen-time/components) — activity picker, activity report, token labels
- [Types](https://magrinj.github.io/expo-screen-time/types)
- [Troubleshooting](https://magrinj.github.io/expo-screen-time/troubleshooting)

The docs site is generated from `website/` (Docusaurus). To work on it: `cd website && bun install && bun start`.

## Example App

See the [`example/`](./example) directory for a complete app demonstrating all features:

```bash
cd example
bun install   # postinstall builds the plugin (../plugin/build) and symlinks the module
bun run ios
bun run android
```

## License

MIT

## Credits

- [expo-widgets](https://github.com/expo/expo/tree/main/packages/expo-widgets) — Expo's first-party config-plugin approach for generating Apple targets, which this library's target generation is modeled on
- [@bacons/apple-targets](https://github.com/EvanBacon/expo-apple-targets) — Evan Bacon's apple-targets plugin, the original inspiration
- Apple's [Screen Time API](https://developer.apple.com/documentation/familycontrols) documentation
