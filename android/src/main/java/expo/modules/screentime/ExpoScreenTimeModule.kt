package expo.modules.screentime

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.modules.ModuleDefinitionBuilder

/**
 * Android counterpart of `ios/ExpoScreenTimeModule.swift`.
 *
 * Stage 1 of the Android port: the module autolinks,
 * exports the same functions, event and views as iOS, and every function fails
 * with a coded `UNSUPPORTED` error. Nothing is enforced on the device yet.
 */
class ExpoScreenTimeModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoScreenTime")

    // Declared for parity with iOS; never emitted until monitoring exists.
    Events("onMonitorEvent")

    NATIVE_FUNCTIONS.filterNot { it.name in IMPLEMENTED }.forEach { unsupported(it) }

    // Apps call this at render time to decide what to show; it must answer,
    // not throw. Nothing is enforced on Android yet, so everything is off.
    // The limits are the cross-platform ones (the contract enforces the iOS
    // values on both platforms).
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
