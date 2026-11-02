package expo.modules.screentime

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.modules.ModuleDefinitionBuilder

/**
 * Android counterpart of `ios/ExpoScreenTimeModule.swift`.
 *
 * Stage 3 of the Android port. Step 1: the
 * permission steps are real, the engine service and its state store exist.
 * Step 2: the selection store, the handles, the picker and the token label.
 * Step 3: the manual block, the shield and its configuration, behind the
 * internal preview switch (`BuildConfig.BLOCKING_PREVIEW`): off, the build
 * behaves exactly as before the step. Every function not yet implemented
 * fails with a coded `UNSUPPORTED` error.
 */
class ExpoScreenTimeModule : Module() {
  private val permissionRequester by lazy { AndroidPermissionRequester(appContext) }
  private val context: android.content.Context
    get() = requireNotNull(appContext.reactContext) { "React context is not available" }
  private val store by lazy { StateStore(context) }
  private val selections by lazy { SelectionStore(store) }
  private val blocks by lazy { BlockController(context, store, selections) }

  override fun definition() = ModuleDefinition {
    Name("ExpoScreenTime")

    // Declared for parity with iOS; never emitted until monitoring exists.
    Events("onMonitorEvent")

    OnCreate {
      // The recovery path that does not depend on the system's sticky
      // restart: every launch brings the engine in line with the stored
      // desired state (and stops a stray one).
      appContext.reactContext?.let { EngineController.sync(it, "launch") }
    }

    OnActivityEntersForeground {
      permissionRequester.onForeground()
    }

    OnDestroy {
      permissionRequester.dispose()
    }

    NATIVE_FUNCTIONS.filterNot { it.name in IMPLEMENTED }.forEach { unsupported(it) }

    // MARK: - Permissions

    // `options` (`ScreenTimePermissionOptions`): `features` decides which
    // steps are listed and required, `member: "child"` makes the
    // notification notice and the device admin required (PermissionSteps).
    AsyncFunction("getScreenTimePermissionsAsync") { options: Map<String, Any?>?, promise: Promise ->
      promise.resolve(
        permissionRequester.response(
          features = PermissionSteps.features(options?.get("features")),
          child = PermissionSteps.isChild(options?.get("member")),
        )
      )
    }

    // Advances onboarding by one step: `step` if given, else the first
    // required step not granted. Never rejects for a refused permission; an
    // iOS step id, an unknown one, or a step the requested features do not
    // list (its outcome could not be reported) is a caller error.
    AsyncFunction("requestScreenTimePermissionsAsync") { options: Map<String, Any?>?, promise: Promise ->
      val features = PermissionSteps.features(options?.get("features"))
      val child = PermissionSteps.isChild(options?.get("member"))
      val step = when (val requested = PermissionSteps.requestedStep(options?.get("step"))) {
        is PermissionSteps.RequestedStep.Next -> null
        is PermissionSteps.RequestedStep.Step -> {
          val device = PermissionSteps.Device(android.os.Build.VERSION.SDK_INT, VendorAutostart.screens().isNotEmpty())
          if (PermissionSteps.plan(features, child, device).none { it.id == requested.id }) {
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
    // `blocking` is "friction" (review R2: a user can stop the engine) only
    // under the preview switch; it flips for real in step 5, once every
    // scenario that keys on it (C03–C10) has its behaviour. The limits are
    // the cross-platform ones (the contract enforces the iOS values on both
    // platforms).
    Function("getCapabilities") {
      mapOf(
        "blocking" to if (BuildConfig.BLOCKING_PREVIEW) "friction" else "none",
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

    // MARK: - Blocking

    if (BuildConfig.BLOCKING_PREVIEW) {
      // Same validation order, codes and semantics as the Swift `blockSelection`
      // (BlockValidation): one manual block that a second call replaces; the
      // engine process is told to look again.
      Function("blockSelection") { selectionId: String, options: Map<String, Any?>? -> blocks.blockSelection(selectionId, options) }

      Function("unblockAll") { blocks.unblockAll() }

      Function("isBlocked") { blocks.isBlocked() }

      // MARK: - Shield Configuration

      Function("setShieldConfig") { config: Map<String, Any?> -> blocks.setShieldConfig(config) }

      Function("setShieldConfigForActivity") { activityName: String, config: Map<String, Any?> -> blocks.setShieldConfigForActivity(activityName, config) }

      Function("clearShieldConfig") { blocks.clearShieldConfig() }

      // Honest empties: nothing can be monitored yet, no shield
      // interaction is recorded and no unlock can be granted yet,
      // so stopping, clearing and draining have nothing to do, nothing is
      // unlocked and no budget remains. The suite's reset() calls the first
      // three; a screen that sees `blocking !== "none"` reads the last two
      // (the example's Blocking tab crashed on UNSUPPORTED there).
      // `temporaryUnlock` and `relock` keep rejecting UNSUPPORTED: granting
      // or restoring is not something that can be honestly no-op'd.
      AsyncFunction("stopAllMonitoring") { promise: Promise -> promise.resolve(null) }

      AsyncFunction("clearMonitorEvents") { _: Any?, promise: Promise -> promise.resolve(null) }

      Function("drainShieldInteractions") { emptyList<Map<String, Any?>>() }

      Function("isTemporarilyUnlocked") { false }

      Function("getRemainingUnlockSeconds") { 0 }
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
