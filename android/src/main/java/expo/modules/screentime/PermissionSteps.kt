package expo.modules.screentime

/**
 * The permission model behind `getScreenTimePermissionsAsync` and
 * `requestScreenTimePermissionsAsync` on Android, without the OS: which
 * steps the requested features need, in onboarding order, and how their
 * statuses fold into Expo's `PermissionResponse`. The Kotlin twin of
 * `ios/PermissionSteps.swift`; the derivation rules are the contract's
 * (`PermissionStep` in `src/ExpoScreenTime.types.ts`).
 *
 * Steps, for the usageStats engine:
 * - `overlay` (required; requested first: once held, the app may start
 *   activities from the background, which lets it bring the user back from
 *   every later settings screen);
 * - `usageAccess` (required; also for `usageReports` alone);
 * - `accessibility` (recommended, for `blocking` in a build whose config
 *   plugin declared the accessibility service, `android.accessibilityEngine`:
 *   the second detector, which hears apps opened from a work profile,
 *   Private Space or a clone; never required, since the main profile is
 *   enforced without it, and the app must show Google Play's prominent
 *   disclosure before requesting it);
 * - `notifications` (Android 13+; recommended, required for a `child`:
 *   Google Play's parental-control rules demand the persistent notice);
 * - `deviceAdmin` (recommended for blocking: an active admin cannot be
 *   force-stopped; required for `appRemovalProtection` and for a `child`);
 * - `batteryOptimization` (recommended; the system list, no permission);
 * - `backgroundAutostart` (recommended, vendor screen, only where one exists).
 *
 * A build the config plugin trimmed (`android.features`, read back as a
 * [BuildManifest]) never lists a step only a removed component needs: no
 * `overlay` without the shield, no `deviceAdmin` without its receiver, no
 * engine steps without the engine. The module rejects a request for a
 * feature the build lacks before planning ([BuildManifest.missing]).
 *
 * Pure Kotlin on purpose, unit-tested in `android/src/test`.
 */
internal object PermissionSteps {
  enum class Feature { blocking, monitoring, usageReports, shieldOpenApp, appRemovalProtection, webDomainBlocking }

  /** `ScreenTimePermissionOptions.features` when absent. */
  val defaultFeatures = listOf(Feature.blocking, Feature.monitoring)

  /** Public `PermissionStepId` values plus `vpn`, reserved for the internal web preview. */
  enum class StepId { screenTime, usageAccess, overlay, accessibility, notifications, batteryOptimization, backgroundAutostart, deviceAdmin, vpn }

  /** The ids Android has a step for (the usageStats engine, and the accessibility detector in a build that declares it). */
  val androidStepIds: Set<StepId> = setOf(
    StepId.overlay, StepId.usageAccess, StepId.accessibility, StepId.notifications, StepId.deviceAdmin, StepId.batteryOptimization, StepId.backgroundAutostart, StepId.vpn
  )

  enum class Status { granted, denied, undetermined, restricted, unknown }
  enum class Kind { prompt, settings, guide }

  data class StepError(val code: String, val message: String)

  /** What decides which steps exist on this device. */
  data class Device(val sdkInt: Int, val hasVendorAutostartScreen: Boolean)

  /** A step the features need, before its status is read. */
  data class Plan(val id: StepId, val required: Boolean, val kind: Kind)

  /** One step as reported to JS (`PermissionStep`), plus what the derivation needs. */
  data class Step(
    val id: StepId,
    val status: Status,
    val required: Boolean,
    val kind: Kind,
    val error: StepError? = null,
    /**
     * Whether requesting this step again can show its prompt or screen.
     * Settings screens always can; the notification prompt cannot once the
     * user blocked it (Android then only offers Settings).
     */
    val canPrompt: Boolean = true,
  ) {
    fun toMap(): Map<String, Any> {
      val map = mutableMapOf<String, Any>(
        "id" to id.name,
        "status" to status.name,
        "required" to required,
        "kind" to kind.name,
      )
      error?.let { map["error"] = mapOf("code" to it.code, "message" to it.message) }
      return map
    }
  }

  /** What a request for `step` means, from the raw `step` option. */
  sealed class RequestedStep {
    object Next : RequestedStep()
    data class Step(val id: StepId) : RequestedStep()
    /** A contract id Android has no step for (`screenTime`). */
    data class NotOnThisPlatform(val name: String) : RequestedStep()
    /** `accessibility` in a build whose manifest does not declare the accessibility service (`android.accessibilityEngine` off). */
    data class NotInBuild(val name: String) : RequestedStep()
    data class Unknown(val name: String) : RequestedStep()
  }

  // MARK: - Options

  /**
   * `features` from the options: absent, or not a list, is the default
   * (`blocking`, `monitoring`) narrowed to what [build] has, so an app on a
   * trimmed build that does not say what it uses is asked for the steps of
   * what it can use (the hook with no options works on a monitoring-only
   * build); with none of the default in the build the default stands, and
   * the module rejects it naming both. An explicit list is the app's own
   * statement and is taken as is. Names this build does not know are
   * ignored (the JS type is the guard). Order kept, duplicates dropped.
   */
  fun features(from: Any?, build: BuildManifest = BuildManifest.full): List<Feature> {
    val names = from as? List<*> ?: return defaultFeatures.filter(build::has).ifEmpty { defaultFeatures }
    val features = mutableListOf<Feature>()
    for (name in names) {
      val feature = (name as? String)?.let { n -> Feature.values().firstOrNull { it.name == n } } ?: continue
      if (feature !in features) features.add(feature)
    }
    return features
  }

  /** `member` from the options: `child` or (default, and anything else) `individual`. */
  fun isChild(from: Any?): Boolean = from == "child"

  fun requestedStep(from: Any?, build: BuildManifest = BuildManifest.full): RequestedStep {
    if (from == null) return RequestedStep.Next
    val name = from as? String ?: return RequestedStep.Unknown(from.toString())
    val id = StepId.values().firstOrNull { it.name == name } ?: return RequestedStep.Unknown(name)
    if (id == StepId.vpn && !build.vpn) return RequestedStep.Unknown(name)
    return when {
      id !in androidStepIds -> RequestedStep.NotOnThisPlatform(name)
      id == StepId.accessibility && !build.accessibility -> RequestedStep.NotInBuild(name)
      else -> RequestedStep.Step(id)
    }
  }

  // MARK: - Steps

  /**
   * The steps `features` need on [device], in onboarding order, among those
   * [build] can grant: the overlay is the shield's (a build with the engine
   * but no shield only records events), the device admin needs its
   * receiver, the engine steps need the engine.
   */
  fun plan(features: List<Feature>, child: Boolean, device: Device, build: BuildManifest = BuildManifest.full): List<Plan> {
    val engine = build.engine && (Feature.blocking in features || Feature.monitoring in features)
    val plans = mutableListOf<Plan>()
    if (engine && build.shield) plans.add(Plan(StepId.overlay, required = true, kind = Kind.settings))
    if (build.usageAccess && (engine || Feature.usageReports in features)) plans.add(Plan(StepId.usageAccess, required = true, kind = Kind.settings))
    if (build.vpn && Feature.webDomainBlocking in features) plans.add(Plan(StepId.vpn, required = true, kind = Kind.prompt))
    // The coverage step, after the required ones and before the reliability
    // ones: the detector only serves the shield (never for monitoring,
    // usage reports or removal protection alone), and is recommended
    // whatever `member` is.
    if (engine && build.blocking && build.accessibility && Feature.blocking in features) plans.add(Plan(StepId.accessibility, required = false, kind = Kind.settings))
    if (engine && build.notifications && device.sdkInt >= 33) plans.add(Plan(StepId.notifications, required = child, kind = Kind.prompt))
    if (build.deviceAdmin && (engine || Feature.appRemovalProtection in features)) {
      plans.add(Plan(StepId.deviceAdmin, required = child || Feature.appRemovalProtection in features, kind = Kind.settings))
    }
    if (engine) plans.add(Plan(StepId.batteryOptimization, required = false, kind = Kind.settings))
    if (engine && device.hasVendorAutostartScreen) plans.add(Plan(StepId.backgroundAutostart, required = false, kind = Kind.guide))
    return plans
  }

  /** The first required step not yet granted; null when onboarding is done. */
  fun nextStep(steps: List<Step>): StepId? = steps.firstOrNull { it.required && it.status != Status.granted }?.id

  // MARK: - Response

  /**
   * Expo's `PermissionResponse` plus `steps`:
   * - `granted` ⇔ every required step is granted;
   * - `status`: `granted` if so; else `denied` if a required step is denied
   *   or restricted; else `undetermined`;
   * - `canAskAgain` ⇔ no required step is restricted, and every required
   *   step still to grant can show its prompt or screen.
   */
  fun response(steps: List<Step>): Map<String, Any> {
    val required = steps.filter { it.required }
    val pending = required.filter { it.status != Status.granted }
    val granted = pending.isEmpty()
    val status = when {
      granted -> "granted"
      pending.any { it.status == Status.denied || it.status == Status.restricted } -> "denied"
      else -> "undetermined"
    }
    val canAskAgain = required.none { it.status == Status.restricted } && pending.all { it.canPrompt }
    return mapOf(
      "status" to status,
      "granted" to granted,
      "canAskAgain" to canAskAgain,
      "expires" to "never",
      "steps" to steps.map { it.toMap() },
    )
  }
}
