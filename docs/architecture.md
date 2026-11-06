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

### 4. Android — `android/src/main/`

The Android counterpart of layers 2 and 3, in Kotlin (`java/expo/modules/screentime/`):

- `ExpoScreenTimeModule.kt` — the module definition; `NativeFunctions.kt` is
  the registry the parity test reads (every function not in `IMPLEMENTED`
  rejects `UNSUPPORTED`).
- `PermissionSteps.kt` (pure: which steps the features need, the response
  derivation; the twin of `ios/PermissionSteps.swift`) and
  `AndroidPermissionRequester.kt` (the OS reads, the settings screens, the
  return-after-permission watch).
- `StateStore.kt` — **the App Group counterpart**: one JSON document under
  `noBackupFilesDir`, read and written by both processes under a
  `FileChannel` lock on a sidecar file (exclusive for `update`, shared for
  `read`, re-entrant in-process), replaced by atomic rename. Engine desired
  state and failures, permission requests, the install secret, the
  selections, the manual block, the shield configs, the monitoring
  layer (`monitoring`: activities, intervals in progress, the event log,
  the usage-stream cursor) and the enforcement-gap tracker (`gaps`).
  `syncEngineDesired` derives the engine's desired state from the document
  in the same `update` as the mutation: a manual block in force or any
  monitored activity (the controllers call it through
  `EnforcementGapRecorder.syncDesired`, which also records the release).
- `SelectionStore.kt` — the twin of `ios/SelectionStore.swift` over the
  state store: `selections.<id>` holds package-name sets (`Selection.kt`,
  pure: the set algebra and the document codec; categories and web domains
  keep their slot but stay empty in v1). Every set operation is one `update`,
  so the engine process never reads a half-applied one. **Handles**
  (`TokenHandles.kt`): `HMAC-SHA256(installSecret, "<type>\0<package>")`,
  base64url — stable per install, never `base64(package)`, useless on
  another install; `tokens.<handle>` in the document is the only way back to
  a package, which is how a handle labels on its own (as an iOS handle
  decodes on its own). **Export blobs** (`SelectionBlob`): the selection
  JSON encrypted (AES-GCM) under a key derived from the secret, behind the
  textual tag `expo-screen-time/android/1:` — opaque to JS, bound to the
  install, and refused by iOS at `Data(base64Encoded:)` (the tag is not
  base64), so iOS needed no change for "a foreign blob returns `false`".
- `LaunchableApps.kt` — the picker's app list: `queryIntentActivities`
  (`MAIN`/`LAUNCHER`, visible through the manifest's `<queries>`, never
  `QUERY_ALL_PACKAGES`), the host app excluded, main profile only.
  `ExpoScreenTimeActivityPickerView.kt` (classic views: searchable
  multi-select `ListView`, system light/dark) and
  `ExpoScreenTimeTokenLabelView.kt` (PackageManager icon + label) keep the
  iOS props and events; package names stay native.
- `ScreenTimeEngineService.kt` — the enforcement engine's `specialUse`
  foreground service, in the **`:screentime` process** so the React Native
  process stays killable. It runs only while the store says so; a refused
  `startForeground` is retried and, failing that, recorded, never thrown.
  `EngineController.kt` starts and stops it from any process;
  `ScreenTimeRestartReceiver.kt` (boot, update, the two alarms) and the
  module's `OnCreate` are the recovery paths. Every start intent is also
  the "look again" signal: the module process writes the block to the store
  and sends one (`ACTION_SYNC`, never a STOP that would carry state, and
  never `stopService`, which kills a still-spawning foreground service), the
  service re-reads the store; a release sends it only while the service's
  own `engine.running` flag is set. The engine's inexact alarm
  (`AlarmManager.setAndAllowWhileIdle`, armed at
  `MonitoringState.nextWakeMs`) targets the same receiver with
  `ACTION_WAKE` and sends the same SYNC: a schedule boundary due while the
  screen is off is observed within the system's window, with no exact
  alarm and no permission.
- **The restart chain.** A wanted engine that dies (a kill, a
  crash, a Task Manager stop) comes back without relying on the system's
  sticky restart, which Android 12+ may refuse: a background
  `startForegroundService`, and a restarted service's `startForeground`
  when its record was not started from the foreground, are allowed only by
  the exemptions in `ActiveServices` — the uid at TOP, the user's
  battery-optimization exemption, a temp allowlist with foreground-service
  capability (`BOOT_COMPLETED`, exact alarms: not the inexact alarms the
  module uses) and `SYSTEM_ALERT_WINDOW` **while a process of the uid shows
  an overlay window**. The chain uses that last one, which blocking holds
  anyway: `ForegroundStartExemption.kt` puts up a 1×1 transparent,
  untouchable overlay window on the main looper and retries the start at
  80 / 200 / 450 / 900 / 1500 ms, then removes it. Three triggers reach it:
  the service's own refused promotion (the sticky restart, retried in
  place, about a second after the death), the controller's refused start
  intent (the schedule alarm, the watchdog), and the app's launch
  (`OnCreate`, from TOP, no exemption needed). `EngineWatchdog.kt` is the
  floor: an inexact, **non-wakeup**, allow-while-idle `ELAPSED_REALTIME`
  alarm, pushed forward by every heartbeat, every start intent and every
  screen change, so it lands only once the beats have stopped.
  Allow-while-idle because battery saver's forced app standby holds a plain
  alarm of an idle app until it lifts, screen on or off, and only
  rate-limits this kind, and on the Pixel Google's adaptive battery saver
  turns that standby on at every unplug; in exchange the alarm also goes
  through Doze, so in `rare` a stop followed by a sleeping phone can spend
  the hour's delivery before the user is back. With the screen on, it is
  set 120 s ahead and lands within 3.5 minutes of the last beat. With the
  screen off, it is set 8 h ahead: past deep Doze's longest sleep, so it
  never lands on a live engine whose CPU only slept. That delivery would
  spend the app's standby alarm quota, one alarm per hour in `rare`, right
  before the screen-on where a stop happens. A death with the screen off is
  left to the sticky restart. The device admin and the battery-optimization
  exemption lift the quota and battery saver's standby (`EXEMPTED`); no
  wakeup is spent overnight. Cancelled
  on release; nothing is armed when nothing is wanted. With the screen off
  or the keyguard up an overlay window is not shown, so the exemption is
  not asked for (`blockedBy`): the engine is recorded as down and nothing
  is counted. Under the keyguard with the screen on, the revival **waits in
  place** (`UnlockWait.kt`, pure; driven by the controller on the main
  looper while the receiver's broadcast stays open): `isKeyguardLocked` is
  polled every 250 ms for 25 s and the overlay revival runs the moment the
  keyguard is gone, so the engine is back within about 2 s of the unlock
  whenever the process was awake at the screen-on — the common case, since
  the pending floor or watchdog alarm is delivered at the screen-on. The
  wait ends early when the screen goes off or the engine is released, and
  falls back to the floor alarm on timeout. With the screen off nothing can
  wait: a one-shot `USER_PRESENT` / `SCREEN_ON` receiver (`UnlockRetry.kt`,
  dynamic, exported — `USER_PRESENT` comes from SystemUI's uid — no manifest
  entry) and the watchdog at 5 s, the system's minimum (held until a batch
  of wakeup alarms, so it is due at the screen-on), are the floor; on Android 14+ both
  broadcasts are deferred for a cached process, so the receiver only fires
  once an alarm has made the process active. A revival
  the system refused (no overlay permission, a
  background-restricted app) records the gap (`engineRefused`) and re-arms
  with a backoff (`RestartBackoff.kt`: 20 s doubled to 30 min, reset when
  the engine runs), so the chain keeps trying while the engine is wanted
  at a bounded cost. A user force stop
  cancels the alarms with the app's other pending intents and stays final,
  as the stopped state intends; the Task Manager "Stop" leaves alarms
  alive (per Android's own documentation), so the watchdog revives the
  engine and the stop is reported as a `stoppedByUser` gap of a few
  minutes. Gap accounting is unchanged: a revival closes the gap, a
  refused one keeps it open, a death undone within 10 s is dropped.
- **Blocking.**
  `BlockController.kt` is the module-process half (the twin of the Swift
  `blockSelection` / `unblockAll` / `isBlocked` and the shield-config
  functions): `BlockState.kt` (pure) validates in the iOS order and codes
  and persists `blocks.manual` (selection id, mode and the apps captured at
  block time, the iOS projection model: an edited selection changes
  nothing until the next `blockSelection`) together with `engine.desired`
  in one `update`. `EngineLoop.kt` is the engine-process
  half: a `HandlerThread` polling `queryEvents` every 100 ms **only while
  the screen is interactive and a block is in force** (screen receivers
  stop and resume it; no block, no poll), with the 2 s overlap, the
  de-duplication, the clamped window and the per-poll try/catch the device
  runs showed were needed. `ForegroundTracker.kt` (pure) turns the activity
  events into the resumed and the still-visible sets; `ShieldPolicy.kt`
  (pure) decides what to shield from the composed sources: blocklist = the
  source's apps, allowlist = every launchable app but the source's,
  exemptions win (host, launchers, SystemUI, dialer, emergency, IME,
  Settings; the OS reads are in `SystemExemptions.kt`). The selection is
  resolved live from the store on every block change, as iOS re-projects
  the stored selection.
- **Block composition.** `BlockComposition.kt` (pure) is the
  twin of `ios/BlockComposition.swift`: `BlockSource` (kind manual /
  schedule / threshold, `startedAtMs`, apps, mode) and `sources(manual,
  monitoring, selections)` — the manual block with the apps captured at
  block time, then each interval's schedule block and its fired
  `blockOnThreshold` selections merged into one threshold source. The
  union rule: a package is shielded if ANY source covers it (a blocklist
  listing it, an allowlist not allowing it); `owner(target)` is the
  threshold, else the latest schedule, else the manual block, and its
  `activityName` is what the shield resolves its configuration from
  (`StateStore.shieldConfig(owner)`). `isBlocked()` sums the sources, with
  the schedules evaluated at now. `unblockAll` lifts the intervals in
  progress (`ActiveInterval.lifted`, like iOS `liftActiveIntervals`): the
  schedule's and its fired thresholds' shields drop until the next start,
  the interval stays in progress and its end is still recorded.
- **Monitoring wake path.** `MonitoringController.kt` is the
  module-process half (`startMonitoring` validation and registration,
  `stopMonitoring`, `stopAllMonitoring`, the reads, `clearMonitorEvents`,
  and the live `onMonitorEvent` stream: a `MonitorEventCursor` drained on a
  package-local broadcast from the engine and on a 2 s poll, as the iOS
  module polls beside its Darwin notification). Every mutation runs the
  pure `MonitoringState` transition inside one `StateStore.update`, then
  SYNCs the engine. In the engine, `EngineLoop.step` runs on every wake
  (start intent, alarm, screen or clock change) and every poll: read the
  UsageStats stream from the persisted cursor (one query serves the shield
  tracker and the usage accumulators; `SCREEN_NON_INTERACTIVE` and
  `KEYGUARD_SHOWN` close every span), `advance(now)`, persist what is
  durable under the lock (an event, a marker, a history answer; the usage
  totals every 30 s), and only then announce the events, recompose the
  sources, apply the shields and re-arm the alarm. A document the module
  changed meanwhile (`revision`) is reloaded and the step runs again from
  it. A never-fed tracker is seeded from a one-hour lookback
  (`MonitoringState.seed`) so history and the live stream never overlap;
  every pending history request is answered after each step (a restart's
  included, after the seed). With nothing monitored the cursor does not
  move and the tracker forgets (a stale "resumed" set would count usage
  nobody spent). The next boundary is also posted on the engine thread
  (`wake`): while the process lives and the CPU is awake the boundary is
  evaluated at the instant it falls, polling or not; the inexact alarm
  covers a dead process or a sleeping CPU. Polling: 100 ms while a source
  shields something, 1 s while only a threshold has to be observed, none
  with the screen off (one settling step 3 s after `SCREEN_OFF` reads the
  screen-off event) or with nothing to observe. `TIME_SET` shifts the
  state by the wall-clock drift against `elapsedRealtime`, under the lock,
  on whichever state is current; `TIMEZONE_CHANGED` re-evaluates in the
  new zone. `isBlocked()` reads `MonitoringState.schedulesAt`: the
  boundaries at now, the thresholds as the engine persisted them.
- **Temporary unlock and shield interactions.**
  `UnlockController.kt` is the module-process half (the twin of
  the Swift `temporaryUnlock`, `isTemporarilyUnlocked`,
  `getRemainingUnlockSeconds`, `relock` and `drainShieldInteractions`): the
  judge (`TemporaryUnlockRequest.judge`, the iOS order and codes), the
  capture of the suspended sources (`SuspendedSources.capturing`, by
  occurrence, as `ios/BlockComposition.swift`) and the record
  (`UnlockGrant`, the suspension travelling with the grant) land in ONE
  `update`, then the engine is SYNCed; a targeted unlock is a
  `ShieldSnooze` granted from the app (`BlockComposition.targetedUnlockVerdict`
  over the sources with the suspension not honoured, so a suspended block
  still counts). `blockSelection`, `unblockAll` and `relock` clear the
  grant, the snoozes and the shield's unblocks (`ShieldLifts`) in their own
  `update`. The composition honours the grant's suspension (`isBlocked()`
  reads false under a grant that suspended everything) and the policy
  subtracts the lifted set (snoozed apps, unblocks) after the union — the
  Android reading of the iOS `storeShields(of:snoozed:)` projection. In the
  engine, every poll (100 ms while something is shielded, 1 s while only a
  budget or a threshold is observed) feeds one `UsageSample` — the
  tracker's resumed set, the screen state — to the grant's meter (over the
  apps its suspended sources shield) and to each snooze's, persists what is
  worth persisting under the lock with the write-back merges
  (`UnlockGrant.merging`, `ShieldSnoozes.merging`: a renewal or a relock
  the module made meanwhile is kept), and recomposes the sources when a
  budget is spent or expired (the wake alarm covers midnight): the app
  still on screen is re-shielded by the standing check, with the host app
  never running. The standing check reads `ForegroundTracker.visibleAt`:
  a paused, unstopped app counts as on screen (PiP) only once its pause is
  `PAUSE_SETTLE_MS` (3 s) old, because an app switch pauses the app that
  leaves before stopping it; and `DEVICE_SHUTDOWN` / `DEVICE_STARTUP`
  clear the tracker, since nothing stays on screen across a boot. The shield Activity records every press
  (`ShieldInteractionQueue`, the app's handle derived like `getTokens`
  derives it, the owner's activity name) and applies the configured action
  (`ShieldAction.configured` over `StateStore.shieldActions(owner)`, the
  iOS precedence) in one `update`, then tells the engine (same process,
  `refreshListener`) before it finishes, so the next poll never re-shields
  what was just lifted. `openApp` is a direct launch.
- `ShieldActivity.kt` — the shield, in the `:screentime` process (already
  alive, so no React Native start on the hot path): `excludeFromRecents`,
  own task affinity, `singleTask`, `windowDisablePreview` (the first frame
  is the shield in its final colour, no system splash) and no animation;
  launched with `NEW_TASK` through the overlay permission's
  background-activity-launch exemption (a refusal is logged and recorded,
  never thrown). Appearance from `ShieldAppearance.kt` (pure: the nested
  and legacy `ShieldConfig` formats, hex colours, the SF Symbol → Material
  Symbol table) over `StateStore.shieldConfig(owner)` (the iOS precedence:
  the owning activity's config, else the default). `close` and Back do
  the iOS default: Home first, then finish, so the blocked app never
  resumes underneath; `unblock` and `snooze` finish the shield and leave
  the user in the app; `defer` keeps it up; `openApp` launches the host.
  After the shield has drawn, the loop looks once more
  (`followUp`): a target paused but not stopped is a picture-in-picture
  window over the shield, and `PipEvictorActivity.kt` enters PiP itself to
  evict it (one pinned task per display); a target still resumed is a
  multi-window layout the shield could not take over, logged and left
  alone. Split-screen: a background-started task cannot be placed into a
  pane (measured), so the shield covers the screen; `LAUNCH_ADJACENT`
  keeps the split pair alive underneath instead of dismissing it.
- **Enforcement gaps.** `EnforcementGaps.kt` is the pure core
  (the twin of `ios/EnforcementGaps.swift`, wider: Android has more ways to
  lose enforcement): `EnforcementGapTracker` folds the exit history
  (`ExitRecord` reduced from `ApplicationExitInfo`, `ExitReasons` mapping
  `USER_REQUESTED` → `stoppedByUser` and the kills → `killedBySystem`),
  the engine's run (`engineStarted` / `heartbeat` / `engineStopped`, a
  stale heartbeat = a kill with no record, a run from another
  `BOOT_COUNT` = a reboot, whose gap starts at the boot, or at the
  `ACTION_SHUTDOWN` beat when a boot ran without the engine, e.g. safe mode),
  a refused start (`engineRefused`), the app's look at a dead engine
  process (`observeEngine`) and the permission checks (`observePermissions`,
  the iOS tracker over `usageAccess` + `overlay`). **Intent** is
  `EnforcementIntent.of(manual, monitoring, zone)`: the periods a source
  would shield something (the manual block's whole span; each schedule's
  occurrences, a `blockOnThreshold` activity for its interval, a lifted
  occurrence excluded). A gap is stored as observed and **settled** against
  the intent at every look of the app and before every mutation of the
  block or the activities (`settle`, with the intent read **before** the
  mutation: what was observed under the previous intent is committed as
  closed pieces carrying the gap's `origin`, and the open record goes on
  from now, so a later change of intent cannot rewrite it); its end (a
  start, a release, a grant) settles the last part the same way, and a
  read clips the open record up to now; pieces of one observation merge
  back on read. A closed engine gap under 10 s is dropped (the engine's
  start re-shields what is on screen). `EnforcementGapRecorder.kt` is the Android wiring, from either
  process, every fold one `StateStore.update` with the inputs (`now`, the
  boot, the exit history, the permission reads, the intent) taken inside
  it: the service folds `engineStarted` in the same update that re-checks
  `engine.desired` and `engineRefused` when `startForeground` is refused
  (`EngineController.sync` does the same for a refused start intent); the
  loop beats every 60 s and on screen on/off, `USER_PRESENT`,
  `ACTION_SHUTDOWN` and every change of the two enforcement AppOps
  (`startWatchingMode`, so a revocation is seen at once); the module's
  mutations go through `syncDesired`, which derives `engine.desired` and
  folds the release with the intent read **before** the mutation; the app
  observes at launch (before the SYNC that revives the engine, so a dead
  engine is a gap first and a closed one next), on every foreground and in
  `getEnforcementGaps`, which answers `gaps(since, now, intent)`. The
  document key is `gaps`.
- `ScreenTimeDeviceAdminReceiver.kt` — a no-policy device admin: while
  active, Settings disables "Force stop" and "Uninstall" goes through
  "Deactivate & uninstall" first (measured on Android 17);
  SystemUI hides the Task Manager "Stop" button for an active admin
  (`FgsManagerController`, not exercised on the emulator). Deactivation
  is two deliberate steps (the toggle, then the system's confirmation with
  `onDisableRequested`'s localisable text); it lifts the friction and is
  not a gap: the engine process is not touched.
- `AndroidManifest.xml` — the static declarations every consumer needs
  (permissions, `<queries>`, the service and receivers), merged by Gradle;
  the plugin's Android mod (`plugin/src/android/`) only writes optional
  app-level entries: the `isMonitoringTool` flag and, for `android.features`,
  the `tools:node="remove"` entries that drop what the app's features do not
  need. `BuildManifest.kt` reads the merged result back (PackageManager) once
  per process; the module's entry points gate on it (`blocking` reads `none`
  without the shield, a removed feature's functions reject `UNSUPPORTED`,
  `PermissionSteps.plan` skips the steps of removed components).

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
