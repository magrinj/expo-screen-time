---
id: installation
title: Installation
sidebar_position: 3
---

# Installation

```bash
npm install expo-screen-time
# or
yarn add expo-screen-time
# or
bun add expo-screen-time
```

Then add the config plugin and run a prebuild — see [Configuration](./configuration.md).

:::tip
`expo-screen-time` is a native module: iOS extension targets on one side, a Kotlin enforcement engine on the other. It cannot run in Expo Go on either platform; you need a [development build](https://docs.expo.dev/develop/development-builds/introduction/).
:::
