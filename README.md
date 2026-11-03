# expo-screen-time

[![npm version](https://img.shields.io/npm/v/expo-screen-time.svg)](https://www.npmjs.com/package/expo-screen-time)
[![CI](https://github.com/magrinj/expo-screen-time/actions/workflows/ci.yml/badge.svg)](https://github.com/magrinj/expo-screen-time/actions/workflows/ci.yml)
[![license](https://img.shields.io/npm/l/expo-screen-time.svg)](./LICENSE)
[![platform](https://img.shields.io/badge/platform-iOS-lightgrey.svg)](https://developer.apple.com/documentation/familycontrols)

An Expo module that wraps Apple's Screen Time APIs (FamilyControls, ManagedSettings, DeviceActivity) for React Native. Enables parental control features like app blocking, usage monitoring, scheduled restrictions, and custom shield configurations.

📚 **[Full documentation → magrinj.github.io/expo-screen-time](https://magrinj.github.io/expo-screen-time/)**

> **Note:** iOS is OS-enforced; Android ships the module's own engine (blocking, schedules, thresholds, temporary unlock) as `friction` the user can lift, which `getEnforcementGaps()` reports. Categories, web domains and usage reports are iOS-only — see [Platform support](https://magrinj.github.io/expo-screen-time/platform-support).

## Features

- **Permission Management** — Request and check Family Controls authorization
- **App Blocking** — Block apps, categories, and web domains using the native activity picker
- **Web Domain Blocking** — Programmatically block specific domains without user selection
- **App Store Restrictions** — Prevent app installation and removal
- **Schedule Monitoring** — Set up time-based blocking schedules
- **Shield Customization** — Customize the blocking screen appearance and button actions
- **Earned Time** — Usage-based temporary unlocks that re-lock automatically, plus a blocked-attempts counter
- **Usage Reports** — JSX-authored report templates rendered on-device by iOS (usage data never reaches JS)
- **Token Labels** — Display app/category/domain names and icons

## Requirements

- iOS 16.0+
- Expo SDK 56+
- Apple Developer account with Family Controls capability

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
    "plugins": ["expo-screen-time"]
  }
}
```

Then run a prebuild so the iOS extension targets are generated:

```bash
npx expo prebuild --clean
```

```tsx
import ExpoScreenTime, { ActivityPicker } from 'expo-screen-time';

// 1. Ask for Family Controls authorization.
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

- [Configuration & extension targets](https://magrinj.github.io/expo-screen-time/configuration)
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
```

## License

MIT

## Credits

- [expo-widgets](https://github.com/expo/expo/tree/main/packages/expo-widgets) — Expo's first-party config-plugin approach for generating Apple targets, which this library's target generation is modeled on
- [@bacons/apple-targets](https://github.com/EvanBacon/expo-apple-targets) — Evan Bacon's apple-targets plugin, the original inspiration
- Apple's [Screen Time API](https://developer.apple.com/documentation/familycontrols) documentation
