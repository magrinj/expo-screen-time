# Config-plugin rules (pbxproj)

`plugin/src/ios/withTargetXcodeProject.ts` drives the legacy `xcode` lib to add
app-extension targets to a consumer's project. Every rule below was learned by
breaking a real build. **Do not modify this area without reading this file.**

## Rule 0 — expo-widgets is the reference implementation

The pbxproj recipe is adapted from Expo's first-party `expo-widgets`
(`../expo-widgets` locally, MIT). When unsure how to do something, **check how
expo-widgets does it and match it**. Diverging requires a written reason in a
comment. Existing deliberate divergences:

- EAS config also declares `com.apple.developer.family-controls` (restricted
  capability — EAS signing fails without it).
- Host target resolved by product type (`getTarget('com.apple.product-type.application')`)
  instead of `getFirstTarget()` — deterministic in multi-target consumer projects.

And deliberate *non*-divergences (proposals already evaluated and rejected):

- The `onFilesGenerated`/`getFileUris` closure between the source mod and the
  xcode mod stays — expo-widgets uses the same pattern, per-iteration capture is
  correct, and reading from disk carries the same mod-ordering assumption.
- The empty `PBXFrameworksBuildPhase` stays — Swift auto-links system frameworks;
  expo-widgets keeps it too.
- The deep-spread EAS merge stays — expo-widgets' version is worse, ours is fine.

## Rule 1 — idempotency is sacred

Every PBX mutation is guarded by an existence check (target by name, phase by
type+comment, product file by path, group by name). Re-running prebuild with
`--no-clean` must change **nothing** (since SDK 58 a plain `expo prebuild`
cleans by default; `--no-clean` is the incremental path). The jest test asserts re-apply leaves all
object counts unchanged — **extend the test whenever you add wiring**.

## Rule 2 — the known gotchas (each one broke a real build)

| Gotcha | Rule |
|---|---|
| **Basename dedup orphaning** | File paths passed to the xcode lib are relative to the **project root** (`<Target>/SharedDefaults.swift`), never the target dir. The lib dedupes file references by path; colliding basenames (SharedDefaults.swift exists in 3 targets) leave build files without a parent group and break `pod install`'s project save. |
| **`undefined/` folder** | `addPbxGroup` always writes a `path` key; with none it serializes the literal `path = undefined`, which Xcode reads as an `undefined/` folder. The target group must be a *virtual* group: `delete groups[uuid].path` after creation. |
| **Orphan entitlements build file** | The `.entitlements` is referenced via `CODE_SIGN_ENTITLEMENTS` only — never passed to `addPbxGroup` (the lib would mint an orphan `PBXBuildFile` for it). |
| **Non-deterministic mainGroup** | Attach the target group to `project.getFirstProject().firstProject.mainGroup` — never scan for anonymous groups (consumer projects can have several). |
| **`ENABLE_PREVIEWS=NO`** | Required in the extension build settings, or `__preview.dylib` gets embedded in the `.appex` → "AppexBundleContainsClassOrStoryboard" install failure on device. |
| **Sources phase is seeded once** | An existing phase is left as-is (matches expo-widgets). Changing the shipped source set ⇒ consumers need `expo prebuild --clean` — that's documented in the README; keep it true. |
| **Classic `PBXGroup` only** | The wiring assumes prebuild-generated classic groups, not Xcode-16 `PBXFileSystemSynchronizedRootGroup`. |
| **Shield NSExtensionPointIdentifiers** | Live in each target's checked-in `Info.plist` (copied verbatim). The correct values are non-obvious (a known @bacons/apple-targets bug shipped wrong ones) — don't regenerate these plists casually. |

## Rule 3 — mod ordering

Expo runs dangerous mods before `withXcodeProject` mods. `withTargetSourceFiles`
(writes files, computes the file list) must stay registered before
`withTargetXcodeProject` (consumes it via the closure). Entitlements are generated
with `@expo/plist` (`plist.build(...)`) — a **direct** dependency; don't rely on
hoisting.

## Rule 4 — testability layout

The pbxproj mutation core is the exported pure function
`applyTargetToXcodeProject(project, props)`; the `withXcodeProject` wrapper is a
thin shim. Keep it that way — the jest test
(`plugin/src/ios/__tests__/withTargetXcodeProject.test.ts`) runs the pure function
against a vanilla pbxproj fixture and asserts: one app-extension target,
idempotent re-apply, every source build file has a parent group, target group has
no `path`. New wiring ⇒ new assertion.

## Adding a new extension target — checklist

1. `targets/<Name>/` — Swift sources + a correct `Info.plist`
   (NSExtensionPointIdentifier!).
2. `SCREEN_TIME_TARGETS` in `plugin/src/ios/targets.ts` (name + bundle-id slug).
3. Add the new bundle-id suffixes to the strip lists in `ios/SharedDefaults.swift`,
   `ios/SelectionStore.swift`, **and** every `targets/*/SharedDefaults.swift` copy.
4. Extend the pbxproj shape test.
5. Run the full verification loop (see [`development.md`](./development.md)),
   including the clean-prebuild count (now N targets) and idempotency re-run.

## Android — `plugin/src/android/`

- **Static entries live in the library manifest**
  (`android/src/main/AndroidManifest.xml`): permissions, the launcher
  `<queries>` intent, the engine service, the receivers. Gradle's manifest
  merger adds them once, so they are idempotent by construction and do not
  depend on the plugin running. The plugin writes **only optional, app-level
  entries** (`isMonitoringTool` for `android.parentalControl`; the
  accessibility `<service>` and its `QUERY_ADVANCED_PROTECTION_MODE`
  permission for `android.accessibilityEngine`), each
  looked up by name before it is added and removed when its option is off
  (no stale entry on `--no-clean`). Same split as expo-widgets (its receiver
  is in its library manifest; `POST_NOTIFICATIONS` is a plugin option).
- **The accessibility service is added, never gated.** It changes a
  consumer's Play review, so it is not in the library manifest and has no
  row in `ANDROID_MANIFEST_GATES`: the default build stays byte-identical
  (the jest test asserts it, plus the off → on → off round trip). The
  option needs `blocking` in `features`; the resolver throws otherwise.
- **`android.features` gates the library manifest.** `ANDROID_MANIFEST_GATES`
  in `withScreenTimeAndroid.ts` is the one table mapping each library
  component and permission to the features that need it; the plugin writes
  a `tools:node="remove"` for every row no listed feature covers (by name,
  idempotent, removed when the option changes back, never when the app
  manifest declares the element itself). **Adding a component or a
  permission to the library manifest ⇒ add its row**: the jest test fails
  until every element of the library manifest has one. The runtime reading
  is `android/.../BuildManifest.kt` (PackageManager, not a flag), gated at
  the module's entry points only.
- The mutation is the exported pure function
  `applyScreenTimeAndroidManifest(manifest, options)`;
  `plugin/src/android/__tests__/withScreenTimeAndroid.test.ts` asserts
  idempotent re-apply, removal on option off, and the **shape of the library
  manifest** (the exact permission set, never `QUERY_ALL_PACKAGES`, the
  `specialUse` service in `:screentime` with its subtype property, the
  receivers). New wiring ⇒ new assertion, in whichever of the two files it
  lands.
- The Android mod runs **before** the `ios.bundleIdentifier` guard in
  `plugin/src/index.ts`: an Android-only app config must still get it.

## Consumer-facing invariants (don't break)

- No `ios.bundleIdentifier` ⇒ warn + skip (never generate `group.undefined`).
- Unknown names in `expo.extra.expoScreenTime.targets` ⇒ warn, ignore.
- Omitted `targets` ⇒ all 4; empty array ⇒ none.
- The App Group is always `group.<bundleId>` — not configurable by design (native
  derives the same value independently).
