# Changelog

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
