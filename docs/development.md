# Development workflow

Setup, and the verification loop every change must pass before it's called done.

## Setup

```bash
# Node 22 (.nvmrc), bun (packageManager: bun@1.3.14)
bun install
bun run build        # module (tsc → build/) + plugin (tsc → plugin/build/)
```

The config plugin loads from the **gitignored** `plugin/build/` — a stale build is
the classic "my plugin change does nothing" trap. Rebuild after every plugin edit.

⚠️ `expo-module build` auto-enters `tsc --watch` in an interactive TTY. Scripts
that must terminate (like `prepack`) set `EXPO_NONINTERACTIVE=1`. If a build
"hangs", that's why.

## The verification loop

Run the tier that matches what you touched; when in doubt, run everything. CI
(`.github/workflows/ci.yml`) runs Tiers 1, 1b and 2, the tarball check of Tier 3
and the example app's type-check, each only when the PR touches files it covers;
`CI OK` is the one check to require. It does not build the apps
(`:app:assembleRelease`, Xcode) or run anything on a device.

### Tier 1 — always

```bash
bun run build && bun run lint && bun run test
```

`bun run test` runs the module's jest suite (`src/**/__tests__/`, including
`nativeApiParity.test.ts`: the TS declaration, the Swift module and the Kotlin
registry must list the same functions with the same arity and sync/async kind,
and the same views and events) and the plugin's (`plugin/src/ios/__tests__/`) —
the pbxproj shape test that guards every past regression. It ends with
`tsc -p test/reference-apps`: the cross-platform reference apps
(`test/reference-apps/apps/`) must type-check against `src/`, so an API change that
breaks the contract fails CI.

Touched `ios/ExpoScreenTimeModule.swift` or `src/ExpoScreenTimeModule.ts`? Add
the matching line to `android/.../NativeFunctions.kt` (it fails with
`UNSUPPORTED` until implemented) or the parity test fails.

CI runs it too (the **Swift logic tests** job, on macOS). On a Mac, also run `bun run test:swift` after touching `ios/BlockComposition.swift`,
`ios/BlockStateLock.swift`, `ios/DailySchedule.swift`, `ios/ActivityRegistration.swift`,
`ios/MonitorEventCursor.swift` or any `SharedDefaults.swift` copy: it compiles those
Foundation-only types with plain `swiftc`, runs the block-composition / precedence /
weekday / registration / lock tests (`scripts/swift-tests/main.swift`), runs two
`lockprobe` processes against the real `flock` (`scripts/swift-tests/lockprobe/`),
and fails if a per-target copy under `targets/*/` drifted from `ios/`.

### Tier 1b — after any `android/` or `plugin/src/android/` change

CI runs the unit tests (job "Android unit tests"); run them locally first. `example/android` is a gitignored
prebuild artifact; a JDK 17 and the Android SDK (`compileSdkVersion` 36) are
needed.

```bash
cd example && bun expo prebuild --platform android
cd android
./gradlew :expo-screen-time:testDebugUnitTest   # the Kotlin unit tests (android/src/test)
./gradlew :app:assembleRelease                   # the example app builds in release
```

Touched `plugin/src/android/`? Also re-run the prebuild without `--clean` and
confirm the merged manifest is unchanged (the manifest entries are idempotent;
`plugin/src/android/__tests__` guards it).

### Tier 2 — after any `plugin/` or `targets/` change

```bash
cd example && bun expo prebuild --platform ios --clean
grep -c 'com.apple.product-type.app-extension' ios/exposcreentimeexample.xcodeproj/project.pbxproj
# → must be 4
grep -c 'undefined/' ios/exposcreentimeexample.xcodeproj/project.pbxproj
# → must be 0 (group-path regression)
```

Then the **idempotency check** — re-run prebuild with `--no-clean` (since SDK 58
a plain `expo prebuild` cleans by default) and confirm the pbxproj is unchanged (the jest test asserts this on a fixture; the real
project is the belt-and-braces check).

### Tier 3 — packaging (before release-affecting changes)

```bash
npm pack --dry-run --ignore-scripts
```

Expect ~250 files / ~510 kB. Must contain: `build/`, `plugin/` (build+src), `src/`,
`ios/`, `android/` (`build.gradle` + `src/main/**`), `targets/`, `app.plugin.js`,
`expo-module.config.json`, `README.md`, `LICENSE`. Must NOT contain:
`android/build`, `example/`, `website/`, `docs/`, any `__tests__`, any
`.keystore`, or internal MD files. The `files`
allowlist in `package.json` is the mechanism — update it consciously.

### Runtime caveat

**FamilyControls does not run on the iOS simulator.** Permission grants, shields,
and monitoring can only be truly verified on a **physical device**. Builds and
typechecks on the simulator are necessary but not sufficient — say so explicitly
when you can't verify runtime behavior.

## Web filter preview

The Android DNS filter is an internal debug preview. Public
`webDomainBlocking` stays `false`; the example's `/web-preview` route uses a
local `vpn` permission step and `webDomains` gap scope. Ordinary builds return
`UNSUPPORTED`. Filtering covers every app except the host, requires **Request
VPN**, and does not support always-on or lockdown.

After the Android prebuild above, build from `example/android`:

```bash
./gradlew :app:assembleDebug -PexpoScreenTime.webPreview=true
```

`-PexpoScreenTime.webPreviewFgsType=none|systemExempted|specialUse` selects the
debug service variant; the default is `systemExempted`. `none` measures VPN
binding without `startForeground`. Release builds exclude the preview even
with the property set; consumer build types with `matchingFallbacks = ['debug']`
can select it. Verify release isolation in both the merged manifest and DEX
class definitions, and run the Kotlin tests with and without the preview flag.

The boot policy defaults to `whenNoVpn`. The debug receiver requires
`android.permission.DUMP` (available to the adb shell):

```bash
adb -s <emulator-serial> shell am broadcast \
  -a expo.modules.screentime.action.WEB_BOOT_POLICY \
  -n expo.modules.screentime.example/expo.modules.screentime.WebPreviewReceiver \
  --es policy whenNoVpn
```

Accepted values are `never`, `whenNoVpn` and `always`. Changing the policy only
persists it. At boot, `whenNoVpn` observes VPN callbacks for three seconds and
prepares the filter only if none was seen and consent is still granted. A
required dialog is never shown from boot. After displacement, a user-initiated
permission request selecting `vpn` is required to reclaim the slot.

Both transports use the underlay's first advertised DNS server without resolver
fallback. Unknown boot identity prevents startup and opens a web gap while
domains are wanted. Preview gaps retain at most 256 entries;
`unblockAll` also clears web domains. Logs use the `ExpoScreenTimeWeb` tag.

## Example app as test bench

`example/` is an expo-router app with one tab per feature area (`blocking`,
`scheduling`, `analytics`, `labels`, `settings`). Its `postinstall` builds the
plugin and symlinks the module (`ln -sfn ../.. node_modules/expo-screen-time`), so
a fresh clone works with just `bun install && bun run ios`. When adding a feature,
wire it into the matching tab.

### Conformance suite (runtime, both platforms)

Settings › **Conformance suite** (`example/app/conformance.tsx`) runs scripted
scenarios through the public API. Each scenario branches on `getCapabilities()`,
never on `Platform.OS`: where a capability is off (categories, web domains and
`openApplication` on Android),
it asserts the coded `UNSUPPORTED` error instead. The screen ends with
`CONFORMANCE PASS n/n` or `CONFORMANCE FAIL k/n`.

1. Once per install, on that screen: **Pick apps** (at least one app) and
   **Pick a category** (at least one category). On iOS, Screen Time access must
   be granted.
2. Run the flow on the device:

   ```bash
   agent-device replay example/.maestro/conformance.yaml --maestro --platform ios -e APP_ID=<bundle id>
   agent-device replay example/.maestro/conformance.yaml --maestro --platform android -e APP_ID=<application id>
   ```

The suite asserts what the API can observe: state, events and error codes.
Shields are drawn by the OS outside the accessibility tree, so what they cover
still needs screenshots. When an API changes, add or adjust a scenario.

The example screens guard their calls with `getCapabilities()` too, so a
screen shared between the platforms never calls an API whose capability is off.

## Docs site

```bash
cd website && bun install && bun run dev   # dev server
bun run build                              # must pass: checks links, then exports
```

Content in `website/content/docs/` (MDX), sidebar order from each folder's
`meta.json`. The build first runs `scripts/check-links.mjs`: a link to a missing
page or a missing `#anchor` fails it (and the Docs CI job runs on every PR
touching `website/**`), so `bun run build` locally before pushing doc changes.
The landing page is `website/app/(home)/page.tsx`.
