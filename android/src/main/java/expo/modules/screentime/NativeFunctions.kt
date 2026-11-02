package expo.modules.screentime

internal enum class Kind { SYNC, ASYNC }

/** One exported function: JS-visible name, number of JS arguments, sync or async. */
internal data class NativeFunction(val name: String, val arity: Int, val kind: Kind)

private fun sync(name: String, arity: Int) = NativeFunction(name, arity, Kind.SYNC)
private fun async(name: String, arity: Int) = NativeFunction(name, arity, Kind.ASYNC)

/**
 * The module's function surface, mirroring `ios/ExpoScreenTimeModule.swift`
 * one line per `Function` / `AsyncFunction` (same name, same arity, `promise`
 * excluded). `src/__tests__/nativeApiParity.test.ts` fails when the two drift.
 *
 * Every entry fails with [UnsupportedPlatformException] unless its name is in
 * [IMPLEMENTED], whose definitions live in [ExpoScreenTimeModule]. The list
 * stays the module's complete surface either way (the parity test reads it).
 */
internal val NATIVE_FUNCTIONS: List<NativeFunction> = listOf(
  // Capabilities
  sync("getCapabilities", 0),
  async("getEnforcementGaps", 1),
  async("openApplication", 1),

  // Permissions
  async("getScreenTimePermissionsAsync", 1),
  async("requestScreenTimePermissionsAsync", 1),

  // App Control
  sync("denyAppRemoval", 0),
  sync("allowAppRemoval", 0),
  sync("denyAppInstallation", 0),
  sync("allowAppInstallation", 0),

  // Blocking
  sync("blockSelection", 2),
  sync("unblockAll", 0),
  sync("isBlocked", 0),

  // Temporary unlock (earned time)
  async("temporaryUnlock", 2),
  sync("isTemporarilyUnlocked", 0),
  sync("getRemainingUnlockSeconds", 0),
  sync("relock", 0),
  sync("drainShieldInteractions", 0),

  // Web domains
  sync("blockWebDomains", 1),
  sync("unblockWebDomains", 1),
  sync("unblockAllWebDomains", 0),
  sync("getBlockedWebDomains", 0),

  // Reporting
  sync("getAppGroup", 0),
  sync("setReportTemplate", 1),
  sync("clearReportTemplate", 0),

  // Debug
  async("debugShieldConfig", 0),

  // Selection store
  async("listSelections", 0),
  async("hasSelection", 1),
  async("getSelectionMetadata", 1),
  async("deleteSelection", 1),
  async("getTokens", 1),
  async("removeToken", 2),
  async("mergeSelections", 2),
  async("keepCommonSelections", 2),
  async("subtractSelection", 3),
  async("exportSelection", 1),
  async("importSelection", 2),

  // Monitoring
  async("startMonitoring", 1),
  async("stopMonitoring", 1),
  async("stopAllMonitoring", 0),
  async("getMonitoredActivities", 0),

  // Shield configuration
  sync("setShieldConfig", 1),
  sync("setShieldConfigForActivity", 2),
  sync("clearShieldConfig", 0),

  // Shield actions
  sync("setShieldActions", 1),
  sync("setShieldActionsForActivity", 2),
  sync("clearShieldActions", 0),

  // Event history
  async("getMonitorEvents", 2),
  async("clearMonitorEvents", 1)
)

/** The step-3 surface, behind the preview switch until step 5 flips `blocking`. */
internal val BLOCKING_PREVIEW_FUNCTIONS: Set<String> = setOf(
  // Blocking
  "blockSelection",
  "unblockAll",
  "isBlocked",
  // Shield configuration
  "setShieldConfig",
  "setShieldConfigForActivity",
  "clearShieldConfig",
  // Honest empties until monitoring and unlocks / interactions
  // exist: the conformance suite's reset() calls the first three
  // on every platform where blocking is on, and an app that sees
  // `blocking !== "none"` reads the unlock state (the example's Blocking
  // tab does, at render). Nothing can be monitored, recorded or unlocked
  // yet, so no-op, no-op, [], false and 0 are the true answers.
  "stopAllMonitoring",
  "clearMonitorEvents",
  "drainShieldInteractions",
  "isTemporarilyUnlocked",
  "getRemainingUnlockSeconds",
)

/**
 * Functions with a real Android definition in [ExpoScreenTimeModule]. The
 * blocking functions join only under the internal preview switch
 * (`BuildConfig.BLOCKING_PREVIEW`, `android/build.gradle`): the default build
 * registers exactly the set it registered before the step.
 */
internal val IMPLEMENTED: Set<String> = setOf(
  "getCapabilities",
  "getScreenTimePermissionsAsync",
  "requestScreenTimePermissionsAsync",
  // Selection store
  "listSelections",
  "hasSelection",
  "getSelectionMetadata",
  "deleteSelection",
  "getTokens",
  "removeToken",
  "mergeSelections",
  "keepCommonSelections",
  "subtractSelection",
  "exportSelection",
  "importSelection",
) + if (BuildConfig.BLOCKING_PREVIEW) BLOCKING_PREVIEW_FUNCTIONS else emptySet()
