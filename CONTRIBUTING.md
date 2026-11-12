# Contributing to expo-screen-time

Thanks for your interest in contributing!

## Prerequisites

- Node.js 22 (see `.nvmrc`)
- [Bun](https://bun.sh) — the package manager for this repo
- Xcode 16+ for iOS work (the Swift module and the four app extensions)
- A JDK 17 and the Android SDK for Android work (see [Android](#android))

## Setup

```bash
bun install
bun run build      # builds the module + the config plugin
```

Run the example app:

```bash
cd example
bun install        # postinstall builds the plugin and links the module
bun run ios
bun run android
```

## Checks

```bash
bun run lint
bun run test       # jest (src + plugin), then the reference apps type-checked against src/
bun run build
```

CI runs all three on every PR, plus a prebuild that asserts the config plugin
generates the four iOS extension targets, the Swift logic tests and the
Kotlin unit tests below.

## Commits & pull requests

This repo releases automatically with
[release-please](https://github.com/googleapis/release-please), which derives the
version bump and `CHANGELOG.md` from
**[Conventional Commits](https://www.conventionalcommits.org/)**.

- PRs are **squash-merged**, and the **PR title becomes the commit** — so the PR
  title must be a Conventional Commit. A CI check (`PR Title`) enforces this.
- `feat:` → minor bump, `fix:` → patch, `feat!:` / `BREAKING CHANGE:` → breaking.
  `docs:`, `chore:`, `ci:`, `refactor:`, `test:` don't trigger a release.

Example titles:

```
feat: add scheduled blocking for weekends
fix(plugin): escape paths in metro blockList
docs: document the shield configuration API
```

## Docs site

User-facing docs live in `website/` (Fumadocs on Next.js, a static export):

```bash
cd website && bun install && bun run dev
```

Pages are MDX files in `website/content/docs/`; each folder's `meta.json` sets
the sidebar order.

## Android

The Android implementation lives in `android/` (Kotlin, under
`src/main/java/expo/modules/screentime/`): the module definition, the
selection store, the permission steps, and the enforcement engine — a
foreground service in its own process that detects the foreground app from
UsageStats and draws the shield Activity over it — with its schedules,
usage thresholds, temporary unlock, enforcement-gap recorder and the usage
report renderer. The Android half of the config plugin is `plugin/src/android/`.
How it fits together is in [`docs/architecture.md`](./docs/architecture.md)
(section 4), and the per-API status in
[`platform-support.mdx`](./website/content/docs/platform-support.mdx).

The Gradle build is the example app's: the module has no standalone project.
React Native's Gradle plugin compiles every module for Java 17, so you need a
**JDK 17**, plus the **Android SDK** with the platform Expo SDK 58 compiles
against (`compileSdkVersion` 36; `minSdkVersion` 24). `example/android` is a
gitignored prebuild artifact, so generate it first:

```bash
cd example && bun install
bun expo prebuild --platform android
```

Then, from `example/android`:

```bash
./gradlew :expo-screen-time:testDebugUnitTest   # the Kotlin unit tests (android/src/test)
./gradlew :app:assembleRelease                   # a release build of the example app
```

The unit tests cover the pure parts (schedules, block composition, the
permission derivation, the state store, the shield policy, the report
aggregation); `bun run test` also checks that `NativeFunctions.kt` lists the
same functions as the Swift module and the TypeScript declaration. Runtime
behaviour (the shield, the engine's restarts, the permission screens) needs a
device or an emulator; the example's Conformance suite
([`docs/development.md`](./docs/development.md)) runs on both platforms.
