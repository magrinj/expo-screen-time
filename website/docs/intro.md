---
slug: /
id: intro
title: Introduction
sidebar_position: 1
sidebar_label: Introduction
---

# expo-screen-time

An Expo module that wraps Apple's Screen Time APIs (FamilyControls, ManagedSettings, DeviceActivity) for React Native. Enables parental control features like app blocking, usage monitoring, scheduled restrictions, and custom shield configurations.

:::note iOS only
Screen Time APIs are not available on Android. The module installs and builds
there, but every function fails with the `UNSUPPORTED` error code and the views
render empty — see [Platform support](./platform-support.md). An Android
implementation is planned.
:::

## Features

- **Permission Management** — Request and check Family Controls authorization
- **App Blocking** — Block apps, categories, and web domains using the native activity picker
- **Web Domain Blocking** — Programmatically block specific domains without user selection
- **App Store Restrictions** — Prevent app installation and removal
- **Schedule Monitoring** — Set up time-based blocking schedules
- **Shield Customization** — Customize the blocking screen appearance
- **Earned Time** — Usage-based temporary unlocks that re-lock automatically
- **Shield Actions** — Configure button behaviors (close, snooze, unblock)
- **Usage Reports** — JSX-authored report templates rendered on-device by iOS (usage data never reaches JS)
- **Token Labels** — Display app/category/domain names and icons

## Requirements

- iOS 16.0+
- Expo SDK 56+
- Apple Developer account with Family Controls capability

## Next steps

- [Install the module](./installation.md)
- [Configure the config plugin](./configuration.md)
- [Browse the API reference](./api/importing.md)
