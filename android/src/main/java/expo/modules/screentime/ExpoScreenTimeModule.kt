package expo.modules.screentime

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.modules.ModuleDefinitionBuilder

/**
 * Android counterpart of `ios/ExpoScreenTimeModule.swift`.
 *
 * Stage 3 of the Android port, step 1: the
 * permission steps are real, the engine service and its state store exist,
 * and every function not yet implemented fails with a coded `UNSUPPORTED`
 * error. Nothing is enforced on the device yet.
 */
class ExpoScreenTimeModule : Module() {
  private val permissionRequester by lazy { AndroidPermissionRequester(appContext) }

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
    // not throw. Nothing is enforced on Android yet (step 1 brings the
    // permission steps, the engine skeleton and the state store, nothing a
    // user can see), so every capability stays off until the step that
    // delivers it flips its value ("Android
    // slices"). The limits are the cross-platform ones (the contract
    // enforces the iOS values on both platforms).
    Function("getCapabilities") {
      mapOf(
        "blocking" to "none",
        "selectableKinds" to mapOf("applications" to false, "categories" to false, "webDomains" to false),
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

    // Views keep the iOS names and props so a shared screen renders (an empty
    // view) instead of crashing on `requireNativeView`. Props are ignored.
    View(ExpoScreenTimeActivityPickerView::class) {
      Prop("selectionId") { _: ExpoScreenTimeActivityPickerView, _: String? -> }
      Prop("headerText") { _: ExpoScreenTimeActivityPickerView, _: String? -> }
      Prop("footerText") { _: ExpoScreenTimeActivityPickerView, _: String? -> }
      Events("onSelectionChange", "onOpen", "onClose")
    }

    View(ExpoScreenTimeReportView::class) {
      Prop("filterSegment") { _: ExpoScreenTimeReportView, _: String? -> }
      Prop("filterDate") { _: ExpoScreenTimeReportView, _: Double? -> }
      Prop("templateHash") { _: ExpoScreenTimeReportView, _: String? -> }
    }

    View(ExpoScreenTimeTokenLabelView::class) {
      Prop("tokenType") { _: ExpoScreenTimeTokenLabelView, _: String? -> }
      Prop("displayMode") { _: ExpoScreenTimeTokenLabelView, _: String? -> }
      Prop("selectionId") { _: ExpoScreenTimeTokenLabelView, _: String? -> }
      Prop("handle") { _: ExpoScreenTimeTokenLabelView, _: String? -> }
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
