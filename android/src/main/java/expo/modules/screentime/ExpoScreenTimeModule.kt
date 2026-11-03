package expo.modules.screentime

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.modules.ModuleDefinitionBuilder
import expo.modules.screentime.PermissionSteps.Feature

/**
 * Android counterpart of `ios/ExpoScreenTimeModule.swift`.
 *
 * Stage 3 of the Android port. Step 1: the
 * permission steps are real, the engine service and its state store exist.
 * Step 2: the selection store, the handles, the picker and the token label.
 * Step 3: the manual block, the shield and its configuration. Step 4:
 * monitoring (schedules, thresholds, the event history and the live
 * events). Step 5: the temporary unlock (whole block and targeted), the
 * shield actions and the interaction queue. Step 6: the enforcement gaps,
 * which is where `blocking` became `"friction"` for real and the internal
 * preview switch the steps 3–5 shipped behind went away. Every function
 * not implemented fails with a coded `UNSUPPORTED` error, and so does every
 * function of a feature the config plugin left out of this build
 * (`android.features`, read back from the merged manifest: [BuildManifest]).
 */
class ExpoScreenTimeModule : Module() {
  private val context: android.content.Context
    get() = requireNotNull(appContext.reactContext) { "React context is not available" }
  /** What the merged manifest declares, resolved once; `getCapabilities()` and the gates below read it. */
  private val build by lazy { BuildManifest.resolve(context) }
  private val permissionRequester by lazy { AndroidPermissionRequester(appContext) { build } }
  private val store by lazy { StateStore(context) }
  private val selections by lazy { SelectionStore(store) }
  private val blocks by lazy { BlockController(context, store, selections) }
  private val monitoring by lazy { MonitoringController(context, store, selections) { payload -> sendEvent("onMonitorEvent", payload) } }
  private val unlocks by lazy { UnlockController(context, store, selections) { monitoring.notifyListeners() } }
  private val gaps by lazy { EnforcementGapRecorder(context, store) }
  /** The gap observations: binder calls under the cross-process lock, kept off the main thread, in order. */
  private val observer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "expo-st-gaps") }

  /** [EnforcementGapRecorder.observe] on the observer thread, for the lifecycle hooks and `getEnforcementGaps`; waits when [sync]. */
  internal fun observe(reason: String, sync: Boolean) {
    val task = observer.submit {
      try {
        gaps.observe(reason)
      } catch (e: Exception) {
        android.util.Log.w(EngineController.TAG, "enforcement gaps not observed ($reason): ${e.javaClass.simpleName}: ${e.message}")
      }
    }
    if (sync) task.get()
  }

  /** The gate of a function of [feature]: `UNSUPPORTED` when this build lacks what the feature needs. */
  private fun requireInBuild(function: String, feature: Feature) {
    if (!build.has(feature)) throw FeatureNotInBuildException(function, listOf(feature))
  }

  /** The gate of a permission request: every requested feature must be in the build (one error names them all). */
  private fun requireInBuild(function: String, features: List<Feature>) {
    val missing = build.missing(features)
    if (missing.isNotEmpty()) throw FeatureNotInBuildException(function, missing)
  }

  override fun definition() = ModuleDefinition {
    Name("ExpoScreenTime")

    Events("onMonitorEvent")

    OnCreate {
      appContext.reactContext?.let { context ->
        // A block a fuller build left in the store cannot be enforced
        // without the shield, and `unblockAll` rejects here: lift it at
        // launch, so a monitoring-only engine is not left with sources it
        // cannot shield (which would stop its polling) and `isBlocked`
        // never reads true from a build that cannot block.
        if (!build.blocking && blocks.isBlocked()) {
          android.util.Log.i(EngineController.TAG, "lifting a block left by a build with the shield (this build has none)")
          blocks.unblockAll()
        }
        // Nothing to revive or observe in a build without the engine (a
        // document a previous, fuller build left behind must not start a
        // service this manifest does not declare).
        if (!build.engine) return@let
        // The enforcement gaps are observed at launch, as the iOS module
        // observes its authorization: the exit history (a user stop, a
        // kill), the engine's liveness and the permissions. Off the main
        // thread; whichever of this and the engine's start lands first in
        // the store, the gap is reported the same.
        if (build.blocking) observe("launch", sync = false)
        // The recovery path that does not depend on the system's sticky
        // restart: every launch brings the engine in line with the stored
        // desired state (and stops a stray one).
        EngineController.sync(context, "launch")
      }
    }

    OnActivityEntersForeground {
      permissionRequester.onForeground()
      if (build.blocking) observe("foreground", sync = false)
    }

    OnDestroy {
      permissionRequester.dispose()
      monitoring.stopObserving()
      observer.shutdown()
    }

    NATIVE_FUNCTIONS.filterNot { it.name in IMPLEMENTED }.forEach { unsupported(it) }

    // MARK: - Permissions

    // `options` (`ScreenTimePermissionOptions`): `features` decides which
    // steps are listed and required, `member: "child"` makes the
    // notification notice and the device admin required (PermissionSteps).
    AsyncFunction("getScreenTimePermissionsAsync") { options: Map<String, Any?>?, promise: Promise ->
      val features = PermissionSteps.features(options?.get("features"), build)
      requireInBuild("getScreenTimePermissionsAsync", features)
      promise.resolve(permissionRequester.response(features, child = PermissionSteps.isChild(options?.get("member"))))
    }

    // Advances onboarding by one step: `step` if given, else the first
    // required step not granted. Never rejects for a refused permission; an
    // iOS step id, an unknown one, or a step the requested features do not
    // list (its outcome could not be reported) is a caller error.
    AsyncFunction("requestScreenTimePermissionsAsync") { options: Map<String, Any?>?, promise: Promise ->
      val features = PermissionSteps.features(options?.get("features"), build)
      requireInBuild("requestScreenTimePermissionsAsync", features)
      val child = PermissionSteps.isChild(options?.get("member"))
      val step = when (val requested = PermissionSteps.requestedStep(options?.get("step"))) {
        is PermissionSteps.RequestedStep.Next -> null
        is PermissionSteps.RequestedStep.Step -> {
          val device = PermissionSteps.Device(android.os.Build.VERSION.SDK_INT, VendorAutostart.screens().isNotEmpty())
          if (PermissionSteps.plan(features, child, device, build).none { it.id == requested.id }) {
            throw ScreenTimeException(
              ScreenTimeException.INVALID_CONFIG,
              "The permission step '${requested.id.name}' is not needed by the requested features (${features.joinToString(", ") { it.name }}); request the steps the response lists"
            )
          }
          requested.id
        }
        is PermissionSteps.RequestedStep.NotOnThisPlatform ->
          throw ScreenTimeException(ScreenTimeException.INVALID_CONFIG, "The permission step '${requested.name}' does not exist on Android; request the steps the response lists")
        is PermissionSteps.RequestedStep.Unknown ->
          throw ScreenTimeException(ScreenTimeException.INVALID_CONFIG, "Unknown permission step '${requested.name}'")
      }
      permissionRequester.request(step, child, features, promise)
    }

    // Apps call this at render time to decide what to show; it must answer,
    // not throw. Each capability stays off until the step that delivers it
    // flips its value ("Android steps"): step 2
    // turned `selectableKinds.applications` on (the picker lists apps, the
    // store holds them); categories and web domains stay off in v1 (review
    // R10: `ApplicationInfo.category` is mostly unset; no web blocking).
    // `blocking` is "friction" (review R2): the engine shields, and a user
    // can stop it from the Task Manager or Settings, which
    // `getEnforcementGaps` reports. The limits are the cross-platform ones
    // (the contract enforces the iOS values on both platforms). `blocking`
    // is `"none"` when the config plugin left the engine or the shield out
    // of this build (`android.features`): nothing can be enforced, and the
    // blocking functions reject `UNSUPPORTED`.
    Function("getCapabilities") {
      mapOf(
        "blocking" to if (build.blocking) "friction" else "none",
        "selectableKinds" to mapOf("applications" to true, "categories" to false, "webDomains" to false),
        "webDomainBlocking" to false,
        "appRemovalProtection" to "none",
        "appInstallationBlocking" to "none",
        "usageReports" to false,
        "shieldOpenApp" to "direct",
        "openApplication" to false,
        "limits" to mapOf(
          "maxMonitoredActivities" to 19,
          "minIntervalSeconds" to 15 * 60,
          "maxIntervalSeconds" to 7 * 24 * 60 * 60,
        ),
      )
    }

    // MARK: - Enforcement gaps

    // Periods when a block was intended but not enforced, oldest first
    // (EnforcementGapRecorder): the exit history (`stoppedByUser` for a
    // Task Manager stop or a force stop, `killedBySystem`), a stale
    // heartbeat, a reboot, a refused start, and the enforcement permissions
    // revoked (`permissionRevoked`, open while they are). Observed here as
    // iOS observes its authorization in this function.
    AsyncFunction("getEnforcementGaps") { since: Double?, promise: Promise ->
      requireInBuild("getEnforcementGaps", Feature.blocking)
      observe("getEnforcementGaps", sync = true)
      promise.resolve(gaps.gaps(since?.toLong()))
    }

    // MARK: - Blocking

    // Same validation order, codes and semantics as the Swift `blockSelection`
    // (BlockValidation): one manual block that a second call replaces; the
    // engine process is told to look again.
    Function("blockSelection") { selectionId: String, options: Map<String, Any?>? ->
      requireInBuild("blockSelection", Feature.blocking)
      blocks.blockSelection(selectionId, options)
    }

    // Lifts every active block — manual, schedule, threshold — as iOS does;
    // monitoring keeps running (BlockController.unblockAll).
    Function("unblockAll") {
      requireInBuild("unblockAll", Feature.blocking)
      blocks.unblockAll()
    }

    Function("isBlocked") {
      requireInBuild("isBlocked", Feature.blocking)
      blocks.isBlocked()
    }

    // MARK: - Shield Configuration

    Function("setShieldConfig") { config: Map<String, Any?> ->
      requireInBuild("setShieldConfig", Feature.blocking)
      blocks.setShieldConfig(config)
    }

    Function("setShieldConfigForActivity") { activityName: String, config: Map<String, Any?> ->
      requireInBuild("setShieldConfigForActivity", Feature.blocking)
      blocks.setShieldConfigForActivity(activityName, config)
    }

    Function("clearShieldConfig") {
      requireInBuild("clearShieldConfig", Feature.blocking)
      blocks.clearShieldConfig()
    }

    // MARK: - Monitoring

    // Same validation order, codes and semantics as the Swift
    // `startMonitoring` (MonitoringValidation); review R7: inside the
    // interval the block applies at once and one intervalDidStart is
    // recorded. The engine process is told to look again.
    // A build with the engine but no shield (`features: ["monitoring"]`)
    // records events and thresholds; a config that would shield is a
    // blocking feature it does not have.
    AsyncFunction("startMonitoring") { config: Map<String, Any?>, promise: Promise ->
      requireInBuild("startMonitoring", Feature.monitoring)
      if (!build.blocking && monitoringConfigShields(config)) {
        throw FeatureNotInBuildException("startMonitoring", listOf(Feature.blocking), "a selectionId or a blockOnThreshold event shields apps")
      }
      monitoring.start(config)
      promise.resolve(null)
    }

    AsyncFunction("stopMonitoring") { activityName: String, promise: Promise ->
      requireInBuild("stopMonitoring", Feature.monitoring)
      monitoring.stop(activityName)
      promise.resolve(null)
    }

    AsyncFunction("stopAllMonitoring") { promise: Promise ->
      requireInBuild("stopAllMonitoring", Feature.monitoring)
      monitoring.stopAll()
      promise.resolve(null)
    }

    AsyncFunction("getMonitoredActivities") {
      requireInBuild("getMonitoredActivities", Feature.monitoring)
      monitoring.monitoredActivities()
    }

    // MARK: - Event History

    AsyncFunction("getMonitorEvents") { activityName: String?, limit: Int? ->
      requireInBuild("getMonitorEvents", Feature.monitoring)
      monitoring.events(activityName, limit)
    }

    AsyncFunction("clearMonitorEvents") { activityName: String?, promise: Promise ->
      requireInBuild("clearMonitorEvents", Feature.monitoring)
      monitoring.clearEvents(activityName)
      promise.resolve(null)
    }

    // MARK: - Real-time Event Listening

    OnStartObserving("onMonitorEvent") { if (build.engine) monitoring.startObserving() }

    OnStopObserving("onMonitorEvent") { monitoring.stopObserving() }

    // MARK: - Temporary unlock

    // Same judge order, codes and messages as the Swift `temporaryUnlock`
    // (UnlockController): the whole block (every active source suspended,
    // the budget metered over their apps by the engine) or one target (a
    // shield snooze granted from the app). `budgetSeconds` is taken as it
    // arrives so a non-number is INVALID_CONFIG, not a bridge error.
    AsyncFunction("temporaryUnlock") { budgetSeconds: Any?, options: Map<String, Any?>?, promise: Promise ->
      requireInBuild("temporaryUnlock", Feature.blocking)
      promise.resolve(unlocks.temporaryUnlock(budgetSeconds, options))
    }

    Function("isTemporarilyUnlocked") {
      requireInBuild("isTemporarilyUnlocked", Feature.blocking)
      unlocks.isTemporarilyUnlocked()
    }

    Function("getRemainingUnlockSeconds") {
      requireInBuild("getRemainingUnlockSeconds", Feature.blocking)
      unlocks.getRemainingUnlockSeconds()
    }

    Function("relock") {
      requireInBuild("relock", Feature.blocking)
      unlocks.relock()
    }

    // MARK: - Shield interactions

    // Read-and-clear the queue the shield Activity writes (one entry per
    // button press, collapsed per target within 2 s).
    Function("drainShieldInteractions") {
      requireInBuild("drainShieldInteractions", Feature.blocking)
      unlocks.drainShieldInteractions()
    }

    // MARK: - Shield Actions

    Function("setShieldActions") { config: Map<String, Any?> ->
      requireInBuild("setShieldActions", Feature.blocking)
      blocks.setShieldActions(config)
    }

    Function("setShieldActionsForActivity") { activityName: String, config: Map<String, Any?> ->
      requireInBuild("setShieldActionsForActivity", Feature.blocking)
      blocks.setShieldActionsForActivity(activityName, config)
    }

    Function("clearShieldActions") {
      requireInBuild("clearShieldActions", Feature.blocking)
      blocks.clearShieldActions()
    }

    // MARK: - Selection Store

    // Same answers as `ios/SelectionStore.swift` for the same inputs: null
    // metadata / handles / blob for an unknown selection, a no-op removal,
    // set operations that skip a missing source, `false` for a blob that is
    // not this install's (SelectionStore.kt).
    AsyncFunction("listSelections") { selections.list() }

    AsyncFunction("hasSelection") { selectionId: String -> selections.has(selectionId) }

    AsyncFunction("getSelectionMetadata") { selectionId: String -> selections.metadata(selectionId) }

    AsyncFunction("deleteSelection") { selectionId: String -> selections.delete(selectionId) }

    AsyncFunction("getTokens") { selectionId: String -> selections.tokens(selectionId) }

    AsyncFunction("removeToken") { selectionId: String, handle: String -> selections.removeToken(selectionId, handle) }

    // MARK: - Selection Set Operations

    AsyncFunction("mergeSelections") { targetId: String, sourceIds: List<String> -> selections.merge(targetId, sourceIds) }

    AsyncFunction("keepCommonSelections") { targetId: String, sourceIds: List<String> -> selections.keepCommon(targetId, sourceIds) }

    AsyncFunction("subtractSelection") { targetId: String, fromId: String, subtractId: String ->
      selections.subtract(targetId, fromId, subtractId)
    }

    // MARK: - Selection Export/Import

    AsyncFunction("exportSelection") { selectionId: String -> selections.export(selectionId) }

    AsyncFunction("importSelection") { selectionId: String, data: String -> selections.import(selectionId, data) }

    // MARK: - View Registration

    // The iOS names, props and events (the parity test holds them equal).
    View(ExpoScreenTimeActivityPickerView::class) {
      Prop("selectionId") { view: ExpoScreenTimeActivityPickerView, selectionId: String? -> view.setSelectionId(selectionId) }
      Prop("headerText") { view: ExpoScreenTimeActivityPickerView, headerText: String? -> view.setHeaderText(headerText) }
      Prop("footerText") { view: ExpoScreenTimeActivityPickerView, footerText: String? -> view.setFooterText(footerText) }
      Events("onSelectionChange", "onOpen", "onClose")
    }

    // Props accepted and ignored until the report renderer.
    View(ExpoScreenTimeReportView::class) {
      Prop("filterSegment") { _: ExpoScreenTimeReportView, _: String? -> }
      Prop("filterDate") { _: ExpoScreenTimeReportView, _: Double? -> }
      Prop("templateHash") { _: ExpoScreenTimeReportView, _: String? -> }
    }

    // Props are stored, then resolved once per batch (the handle lookup
    // and the PackageManager reads run off the main thread).
    View(ExpoScreenTimeTokenLabelView::class) {
      Prop("tokenType") { view: ExpoScreenTimeTokenLabelView, tokenType: String? -> view.tokenType = tokenType }
      Prop("displayMode") { view: ExpoScreenTimeTokenLabelView, displayMode: String? -> view.displayMode = displayMode }
      Prop("selectionId") { view: ExpoScreenTimeTokenLabelView, selectionId: String? -> view.selectionId = selectionId }
      Prop("handle") { view: ExpoScreenTimeTokenLabelView, handle: String? -> view.handle = handle }
      OnViewDidUpdateProps { view: ExpoScreenTimeTokenLabelView -> view.render() }
    }
  }
}

private fun fail(name: String): Any? = throw UnsupportedPlatformException(name)

/**
 * Registers [fn] with the arity iOS declares. Arguments are accepted as `Any?`
 * so the call reaches the body (and its `UNSUPPORTED` error) whatever JS passes;
 * sync functions throw, async functions reject.
 *
 * Async functions use the explicit `promise: Promise` form on purpose: with
 * `Any?` parameters a plain `(p0, p1, p2) -> R` lambda also matches Expo's
 * `AsyncFunctionWithPromise` overload (a `Promise` is an `Any?`), Kotlin picks
 * that more specific one, and the function is registered with one argument
 * fewer — JS then gets `ERR_INVALID_ARGS_NUMBER` instead of `UNSUPPORTED`
 * (seen on an emulator for the 3-argument `subtractSelection`).
 */
private fun ModuleDefinitionBuilder.unsupported(fn: NativeFunction) {
  val name = fn.name
  when (fn.kind) {
    Kind.SYNC -> when (fn.arity) {
      0 -> Function(name) { fail(name) }
      1 -> Function(name) { _: Any? -> fail(name) }
      2 -> Function(name) { _: Any?, _: Any? -> fail(name) }
      3 -> Function(name) { _: Any?, _: Any?, _: Any? -> fail(name) }
      else -> error("Unsupported arity ${fn.arity} for '$name'")
    }
    Kind.ASYNC -> when (fn.arity) {
      0 -> AsyncFunction(name) { promise: Promise -> promise.reject(UnsupportedPlatformException(name)) }
      1 -> AsyncFunction(name) { _: Any?, promise: Promise -> promise.reject(UnsupportedPlatformException(name)) }
      2 -> AsyncFunction(name) { _: Any?, _: Any?, promise: Promise -> promise.reject(UnsupportedPlatformException(name)) }
      3 -> AsyncFunction(name) { _: Any?, _: Any?, _: Any?, promise: Promise -> promise.reject(UnsupportedPlatformException(name)) }
      else -> error("Unsupported arity ${fn.arity} for '$name'")
    }
  }
}
