# Architecture

How the module is layered and how data flows. Grounded in the actual code — file
references are clickable.

## The three layers (+ extensions)

```
JS/TS (src/)  ──bridge──  Native module (ios/)  ──App Group──  Extensions (targets/)
                                    ▲
                    generated into the consumer's Xcode
                    project by the config plugin (plugin/)
```

### 1. TypeScript layer — `src/`

- `src/ExpoScreenTimeModule.ts` — the single `declare class` describing every
  native function, bound with `requireNativeModule("ExpoScreenTime")`. New native
  functions are declared here, grouped under the existing `// Permissions`,
  `// Blocking`, `// Monitoring`… section comments.
- `src/ExpoScreenTime.types.ts` — **all** shared types live here, organized in
  banner-comment sections (`TOKEN & SELECTION TYPES`, `SCHEDULING TYPES`…). iOS-only
  types carry `@platform ios` TSDoc.
- Views wrap natives with `requireNativeView("ExpoScreenTime", "<ViewName>")` —
  see `src/ExpoScreenTimeActivityPickerView.tsx` (thin passthrough) and
  `src/ExpoScreenTimeTokenLabel.tsx` (default export + named convenience wrappers
  `ApplicationLabel`/`CategoryLabel`/`WebDomainLabel`).
- `src/DeviceActivityReport.tsx` shows the **graceful non-iOS fallback pattern**:
  `Platform.OS` guard, styled fallback `<View>`, styling shipped to the extension
  via `setReportStyle` (App Group), only layout props cross to the native view.
- `src/utils.ts` — 1:1 documented wrappers over selection-store functions.
- `src/index.ts` — the **only** public surface. Everything consumers can import is
  exported here (module default, hooks, components, utils, `export * from` types).
  Adding API = declare in module/types + export in index.

### 2. Native module — `ios/`

- `ios/ExpoScreenTimeModule.swift` — Expo `Module` definition DSL: `Name`,
  `Events("onMonitorEvent")`, `OnCreate` (registers `ScreenTimePermissionRequester`),
  then `Function`/`AsyncFunction` blocks organized with `// MARK: -` sections that
  mirror the TS declaration's grouping. Errors are thrown as `NSError` with domain
  `"ExpoScreenTime"`.
- `ios/ScreenTimeManager.swift` — the only place in the app that touches
  `ManagedSettingsStore`. **Every access runs under `BlockStateLock`**, the
  cross-process mutex below. It also keeps local state for what the store
  can't read back (e.g. `blockedWebDomains` — ManagedSettings is write-mostly).
  `refreshShields()` is a deliberate toggle-off/on workaround to force iOS to
  re-invoke the ShieldConfigurationExtension; its "on" half re-projects the
  current App Group state (never a snapshot). Shields live in **named stores**
  (`BlockStore`: manual, web, one per activity), enumerated from the
  `expo_st_shield_store_*` registry every writer maintains.
- `ios/SelectionStore.swift` — persists `FamilyActivitySelection`s as JSON in the
  App Group `UserDefaults` under `expo_st_sel_<id>`, with an in-memory handle
  cache. This is what makes the opaque-`selectionId` API possible.
- `ios/SharedDefaults.swift` — App Group access + the `expo_st_` key namespace
  (`SharedDefaultsKeys`). All cross-process keys go through this enum.

### 3. App extensions — `targets/<Name>/`

Four extensions, declared in `plugin/src/ios/targets.ts` (`SCREEN_TIME_TARGETS`:
name + bundle-id slug; deployment target 16.0):

| Target | Role |
|---|---|
| `ShieldConfigurationExtension` | draws the blocking screen from stored `ShieldConfig` |
| `ShieldActionExtension` | handles shield buttons (close/snooze/unblock/defer) |
| `DeviceActivityMonitorExtension` | applies/removes blocks on schedule, records events |
| `DeviceActivityReportExtension` | renders usage reports (data never reaches JS) |

- Extensions communicate with the app **exclusively via App Group UserDefaults**
  (`group.<bundleId>`) — configs written by the app under `expo_st_*` keys, events
  written back by the monitor.
- `SharedDefaults.swift` / `SelectionStore.swift` / `BlockComposition.swift` /
  `BlockStateLock.swift` / `DailySchedule.swift` / `ActivityRegistration.swift`
  are **duplicated per target dir on purpose** — the plugin only copies `targets/<targetName>/`, so
  per-target copies are canonical (a shared dir was dead weight and got
  deleted; keep the copies byte-identical when editing — `bun run test:swift`
  fails when they drift).
- The monitor registers **physical** `DeviceActivityName`s:
  `<activityName>__expo_st_gen_<generation>` (`ActivityRegistration`). The
  system keeps calling back for a stopped registration, so the monitor honours
  a callback only when its generation is the one in the activity's config.
  Everything else (events, configs, store names, the JS API) uses the logical
  name.
- **One cross-process mutex — `BlockStateLock`** (`ios/BlockStateLock.swift`,
  copied into every extension). The app and the three extensions are separate
  processes that each read the block state, decide, and write the state and
  the stores; every such read-decide-write runs inside
  `BlockStateLock.shared.withLock { … }`: an exclusive `flock` on
  `expo_st_block_state.lock` in the App Group container (released by the
  kernel if the holder dies), re-entrant within a process. The generation
  check and the writes it guards live in the same critical section; the
  stores are always recomputed from the whole state under the lock (never
  "clear, then re-apply"); a store leaves the registry only when that
  recompute emptied it. The lock is **never held across
  `DeviceActivityCenter` calls** (state first, release, then tell the
  system) — the rationale is in the file header. `scripts/swift-tests`
  shows four threads and two processes serializing on it.
- ⚠️ **Adding a target?** Both `ios/SharedDefaults.swift` and
  `ios/SelectionStore.swift` (and the copies in `targets/*/`) strip known extension
  bundle-id suffixes to derive the base app bundle id — a new target's suffix must
  be added to those strip lists or the extension resolves the wrong App Group.

## The opaque `selectionId` principle

Apple's tokens are opaque and must not be readable from JS. The whole API is built
around that:

1. `<ActivityPicker selectionId="x">` writes the user's selection into the native
   `SelectionStore` under `"x"`.
2. JS receives only `SelectionMetadata` (counts) and `TokenHandle`s. A handle is
   the base64 of the encoded token (stable across launches) — Apple's tokens are
   undecodable blobs even natively, so this reveals nothing, but JS must treat
   handles (and `exportSelection` blobs) as opaque: never parse or construct one.
3. `blockSelection("x")`, `startMonitoring({ selectionId: "x" })` reference the
   stored selection by ID; extensions load it from the App Group.
4. Labels render token names/icons natively (`TokenLabel`) because JS can't.

**Never add an API that requires JS to understand token contents.** This is also
what keeps the API Android-portable (there, an ID will map to a package-name
list) — see the platform support page.

## The App Group

`group.<ios.bundleIdentifier>`, derived identically in three places: the config
plugin (`plugin/src/index.ts`), the app (`SharedDefaults`), and the extensions
(suffix-stripping). It is **not configurable** — a custom value would silently not
be read by native code. Fixed at first prebuild; changing the bundle id requires
`expo prebuild --clean`.

## The config plugin — `plugin/src/`

`index.ts` (entry: bundle-id guard → main-app entitlements → per-target plugins) →
`withScreenTimeTargets` (loops enabled targets) → for each target:
`withTargetSourceFiles` (dangerous mod: copies Swift + Info.plist, generates
entitlements) → `withTargetXcodeProject` (pbxproj wiring) → `withScreenTimeEasConfig`
(EAS signing). Full rules in [`config-plugin.md`](./config-plugin.md).
