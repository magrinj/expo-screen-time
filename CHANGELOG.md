# Changelog

## [0.3.0](https://github.com/magrinj/expo-screen-time/compare/v0.2.0...v0.3.0) (2026-11-13)


### Features

* **android:** blocking and shield behind an internal preview switch ([cbb241c](https://github.com/magrinj/expo-screen-time/commit/cbb241c7154bf658843e329d7a7928ca37c75028))
* **android:** denyAppRemoval and denyAppInstallation on Android (friction) ([b9f0ed3](https://github.com/magrinj/expo-screen-time/commit/b9f0ed36f6a6f7323b85375b40f99900c4081a4c))
* **android:** DNS filter codecs for the web preview (internal) ([39bde35](https://github.com/magrinj/expo-screen-time/commit/39bde3556da1c3b9c362f9ec6f64c462b226255b))
* **android:** enforcement gaps wired and blocking enabled on Android ([4bc68f8](https://github.com/magrinj/expo-screen-time/commit/4bc68f885e08fe1db73f5734c8c8abe15b98ebe6))
* **android:** enforcement-gap tracker core for getEnforcementGaps ([b0dba44](https://github.com/magrinj/expo-screen-time/commit/b0dba4413d2cfdcab10db1c5e46dd064868cc2b0))
* **android:** engine restart path without the sticky restart, device-admin friction ([195e487](https://github.com/magrinj/expo-screen-time/commit/195e4873727b302b96e1b6c42b9c79e2273263aa))
* **android:** monitoring core: schedules as a function of time, exactly-once events, usage thresholds ([316acb5](https://github.com/magrinj/expo-screen-time/commit/316acb59ef3999fb187dbbd0f882b88622522031))
* **android:** monitoring wired: schedules, thresholds, exactly-once events, block composition ([1be9a7e](https://github.com/magrinj/expo-screen-time/commit/1be9a7e628a2b78c721612aea1e06ca3047b1b30))
* **android:** opt-in accessibility engine for work profiles, Private Space and clones ([7e875f1](https://github.com/magrinj/expo-screen-time/commit/7e875f138523cff994457d4bb31024f8713a012e))
* **android:** selections: launchable-app picker, salted handles, selection store, token labels ([c0d0ec9](https://github.com/magrinj/expo-screen-time/commit/c0d0ec92a7c30bce5f457ac33daea96f86ba107b))
* **android:** temporary unlock and shield interactions wired behind the preview switch ([97449d6](https://github.com/magrinj/expo-screen-time/commit/97449d64625bed0b080e85a3ccb0b803ab1db596))
* **android:** unlock and interactions core: snoozes, unlock grant, shield interaction queue ([ac98d21](https://github.com/magrinj/expo-screen-time/commit/ac98d21f481a77aceba32f905d4489aa8427e1b8))
* **android:** usage report core: aggregation, template model and bindings ([ba64a21](https://github.com/magrinj/expo-screen-time/commit/ba64a21ea08b7b1f1cf91806bfe491ad99d6d53c))
* **android:** usage reports rendered natively from UsageEvents ([2c35447](https://github.com/magrinj/expo-screen-time/commit/2c354479d8e980a059d3fd6e855f55a9aa979aba))
* **plugin:** android features option drops the components an app does not use ([18c33dc](https://github.com/magrinj/expo-screen-time/commit/18c33dc84946e3abae198f7a8580e0b30a0792e4))


### Bug Fixes

* **android:** build on Expo SDK 54 to 57 ([7e4b7e0](https://github.com/magrinj/expo-screen-time/commit/7e4b7e097a9e5a88d9e1ca7c55e6ff24f2396e30))
* **android:** readable default labels, picker hides never-blocked apps and clears the gesture bar ([5a22044](https://github.com/magrinj/expo-screen-time/commit/5a220442a342238e4b78ec906307980c668e03e3))
* **android:** the usage report ends a span when its activity stops without a pause ([6826fdb](https://github.com/magrinj/expo-screen-time/commit/6826fdb46f556d586c4cc00f176fc36329230c01))
* follow-ups from the review of the Android port ([672f8a0](https://github.com/magrinj/expo-screen-time/commit/672f8a0e465b6b8f73b0a76a7865083abf1cdebf))
* **ios:** count every device pickup in report templates ([7880ccc](https://github.com/magrinj/expo-screen-time/commit/7880ccc81e0993446f731ec98db79e6d2b96fa69))
* **ios:** enforce the 19-activity limit and cover the next selected weekday for one-off schedules ([ade9567](https://github.com/magrinj/expo-screen-time/commit/ade95670a6a43df93487d56585d564464aefa483))
* **plugin:** write Play's isMonitoringTool flag as the help page spells it ([2a019cd](https://github.com/magrinj/expo-screen-time/commit/2a019cd5d7a3232365ecc737b7c32a852193a262))
* **shield:** readable default text colours on a custom background ([d21e0ac](https://github.com/magrinj/expo-screen-time/commit/d21e0acae333b12448307e5c29c82de63a2ad8cd))


### Performance Improvements

* **android:** fast first shield after an engine start or a long idle ([a0b0aa4](https://github.com/magrinj/expo-screen-time/commit/a0b0aa4be9d6d303223dd540b5f88533d48718b2))

## [0.2.0](https://github.com/magrinj/expo-screen-time/compare/v0.1.1...v0.2.0) (2026-11-02)


### ⚠ BREAKING CHANGES

* JSX-authored report templates rendered in the sandboxed extension. `<DeviceActivityReport>` now takes a JSX template as `children` (see the Usage reports docs); its `context`, `accentColor`, `backgroundColor`, `textColor`, `showPickups`, `showTopApps`, `showChart` and `chartStyle` props are gone, and so are the `ReportStyle` and `ReportContext` types.

### Features

* **android:** foundation — config plugin, engine service, state store, permission steps
* **android:** module skeleton that rejects UNSUPPORTED
* **example:** behavioural conformance suite for both platforms
* export every function as a named export
* **ios:** add getCapabilities, getEnforcementGaps and openApplication
* **ios:** add the allowlist block mode
* **ios:** report permission errors and support child authorization
* **ios:** report the shielded target and its activity in shield interactions
* **ios:** resolve a TokenLabel handle without its selection
* **ios:** shield openApp action and feature-driven permission steps
* **ios:** targeted temporary unlock and meteringSeconds
* **ios:** usage-based temporary unlock and shield interaction queue
* JSX-authored report templates rendered in the sandboxed extension
* type every error code and add isScreenTimeError


### Bug Fixes

* **ci:** auto-enable GitHub Pages, skip Pages steps on PRs
* **docs:** rewrite API docs against the real selectionId API
* harden shield/monitor concurrency, delegate listener lifecycle to expo-modules-core
* **ios:** compose blocks from every active source
* **ios:** emit every monitor event once, oldest first
* **ios:** honor the JS shape of setShieldActions
* **ios:** implement the shield snooze as a targeted usage budget
* **ios:** isBlocked() no longer reads false during the shield refresh after a block
* **ios:** keep the temporary-unlock meter out of monitoring APIs
* **ios:** never stop the unlock meter when no user activity runs
* **ios:** persist blocked web domains across launches
* **ios:** read numeric monitoring values sent from JS
* **ios:** report isBlocked() false after unblockAll()
* **ios:** resolve permission request only after authorization status settles
