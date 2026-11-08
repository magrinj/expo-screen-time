---
slug: /
id: intro
title: Introduction
sidebar_position: 1
sidebar_label: Introduction
---

# expo-screen-time

App blocking, schedules, shields and usage reports for Expo and React Native,
on iOS and Android, behind one API. On iOS the module wraps Apple's Screen
Time APIs (FamilyControls, ManagedSettings, DeviceActivity). On Android, which
has no Screen Time API, it ships its own enforcement engine.

:::note How enforcement differs
iOS restrictions are enforced by the OS. On Android the engine is `friction`:
a foreground service draws the shield, and the user can stop it from the
system settings, which `getEnforcementGaps()` reports. Categories, web
domains and the app-removal and installation restrictions are iOS-only
today — see [Platform support](./platform-support.md).
:::

## Features

- **Permissions** — one call site; the steps the user must allow come back as data (iOS: the Family Controls prompt; Android: the Settings toggles, one screen at a time)
- **App blocking** — block the apps the user picks in the native picker, as a blocklist or an allowlist (categories and web domains: iOS)
- **Web domain blocking** — block specific domains without user selection (iOS)
- **App Store restrictions** — prevent app installation and removal (iOS)
- **Schedule monitoring** — time-based blocking and usage thresholds, with events
- **Shield customization** — customize the blocking screen appearance
- **Shield actions** — configure button behaviors (close, snooze, unblock, open app)
- **Earned time** — usage-based temporary unlocks that re-lock automatically
- **Enforcement gaps** — `getEnforcementGaps()` tells when an Android block was intended but not enforced
- **Usage reports** — JSX-authored report templates rendered natively on both platforms (usage data never reaches JS)
- **Token labels** — display app names and icons from opaque handles (category and domain labels: iOS)

## Requirements

- iOS 16.0+, and an Apple Developer account with the Family Controls capability
- Android 7.0+ (API 24)
- Expo SDK 56+
- A [development build](https://docs.expo.dev/develop/development-builds/introduction/) — the module cannot run in Expo Go

## Next steps

- [Check what works on each platform](./platform-support.md)
- [Install the module](./installation.md)
- [Configure the config plugin](./configuration.md)
- [Browse the API reference](./api/importing.md)
