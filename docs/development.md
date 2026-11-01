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

Run the tier that matches what you touched; when in doubt, run everything. CI runs
all of it on every PR.

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

Expect ~90 files / ~320KB. Must contain: `build/`, `plugin/` (build+src), `src/`,
`ios/`, `android/` (`build.gradle` + `src/main/**`), `targets/`, `app.plugin.js`,
`expo-module.config.json`, `README.md`, `LICENSE`. Must NOT contain:
`android/build`, `example/`, `website/`, `docs/`, any `__tests__`, any
`.keystore`, or internal MD files (`TODO`, `PLAN`…). The `files`
allowlist in `package.json` is the mechanism — update it consciously.

### Runtime caveat

**FamilyControls does not run on the iOS simulator.** Permission grants, shields,
and monitoring can only be truly verified on a **physical device**. Builds and
typechecks on the simulator are necessary but not sufficient — say so explicitly
when you can't verify runtime behavior.

## Example app as test bench

`example/` is an expo-router app with one tab per feature area (`blocking`,
`scheduling`, `analytics`, `labels`, `settings`). Its `postinstall` builds the
plugin and symlinks the module (`ln -sfn ../.. node_modules/expo-screen-time`), so
a fresh clone works with just `bun install && bun run ios`. When adding a feature,
wire it into the matching tab.

### Conformance suite (runtime, both platforms)

Settings › **Conformance suite** (`example/app/conformance.tsx`) runs scripted
scenarios through the public API. Each scenario branches on `getCapabilities()`,
never on `Platform.OS`: where a capability is off (Android today), it asserts
the coded `UNSUPPORTED` error instead. The screen ends with
`CONFORMANCE PASS n/n` or `CONFORMANCE FAIL k/n`.

1. Once per install, on that screen: **Pick apps** (at least one app) and
   **Pick a category** (at least one category). On iOS, Screen Time access must
   be granted.
2. Run the flow on the device:

   ```bash
   agent-device replay example/.maestro/conformance.yaml --maestro --platform ios -e APP_ID=<bundle id>
   ```

The suite asserts what the API can observe: state, events and error codes.
Shields are drawn by the OS outside the accessibility tree, so what they cover
still needs screenshots. When an API changes, add or adjust a scenario.

The example screens guard their calls with `getCapabilities()` too, so the app
launches on Android, where the module answers `UNSUPPORTED`.

## Docs site

```bash
cd website && bun install && bun start   # dev server
bun run build                            # must pass — onBrokenLinks: 'throw'
```

Content in `website/docs/`, sidebar autogenerated from `sidebar_position`
frontmatter + `_category_.json`. Broken links fail the build (and the Docs CI job
runs on every PR touching `website/**`), so `bun run build` locally before pushing
doc changes.
