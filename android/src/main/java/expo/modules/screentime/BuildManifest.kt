package expo.modules.screentime

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import expo.modules.screentime.PermissionSteps.Feature

/**
 * What this build's merged manifest declares of the library's components and
 * permissions. The config plugin's `android.features` option removes
 * (`tools:node="remove"`) what the app's features do not need
 * (`plugin/src/android/withScreenTimeAndroid.ts`, `ANDROID_MANIFEST_GATES`).
 * The module reads the result from PackageManager rather than a flag the
 * plugin would write: the answer is then the manifest itself, so a bare
 * project that trims its manifest by hand is reported the same way and
 * nothing can drift, as the iOS module probes the bundle for the report
 * extension (`embedsExtension`). Resolved once per process ([resolve]);
 * [full] is a manifest nothing was removed from, the module's behaviour
 * before the option existed. Read at the module's entry points only; the
 * engine never asks (without the service it does not run, and without the
 * shield the module lifts at launch a block a fuller build left behind).
 */
internal data class BuildManifest(
  /** `ScreenTimeEngineService`: schedules, thresholds, events, every shield. */
  val engine: Boolean,
  /** `ShieldActivity` with `SYSTEM_ALERT_WINDOW` (its background-launch exemption): a block can be enforced. */
  val shield: Boolean,
  /** `ScreenTimeDeviceAdminReceiver`: the `deviceAdmin` permission step. */
  val deviceAdmin: Boolean,
  /** `PACKAGE_USAGE_STATS`: the `usageAccess` permission step. */
  val usageAccess: Boolean,
  /** `POST_NOTIFICATIONS`: the `notifications` permission step (the engine's notice). */
  val notifications: Boolean,
  /**
   * `ScreenTimeAccessibilityService`: the accessibility detector the config
   * plugin's `android.accessibilityEngine` option declares (added on
   * opt-in, never removed by a feature, so [full] does not have it). The
   * `accessibility` permission step is listed iff this holds, with the
   * shield. The engine never asks: if the service is declared, Android
   * binds it when the user enables it; if not, nothing runs.
   */
  val accessibility: Boolean = false,
  val vpn: Boolean = false,
) {
  /** `getCapabilities().blocking` is `friction` iff this holds; else `none`. */
  val blocking: Boolean get() = engine && shield

  /** Whether nothing [feature] needs is missing from the manifest. */
  fun has(feature: Feature): Boolean = when (feature) {
    Feature.blocking, Feature.shieldOpenApp -> blocking
    Feature.monitoring -> engine
    Feature.usageReports -> usageAccess
    Feature.appRemovalProtection -> deviceAdmin
    // Not implemented on Android; nothing of its own in the manifest.
    Feature.webDomainBlocking -> true
  }

  /** The requested features this build cannot serve, in the requested order. */
  fun missing(features: Collection<Feature>): List<Feature> = features.filterNot(::has)

  companion object {
    val full = BuildManifest(engine = true, shield = true, deviceAdmin = true, usageAccess = true, notifications = true)

    /** From the declared names: the resolver's and the unit tests' entry. */
    fun from(services: Collection<String>, activities: Collection<String>, receivers: Collection<String>, permissions: Collection<String>) =
      BuildManifest(
        engine = ScreenTimeEngineService::class.java.name in services,
        shield = ShieldActivity::class.java.name in activities && Manifest.permission.SYSTEM_ALERT_WINDOW in permissions,
        deviceAdmin = ScreenTimeDeviceAdminReceiver::class.java.name in receivers,
        usageAccess = Manifest.permission.PACKAGE_USAGE_STATS in permissions,
        notifications = Manifest.permission.POST_NOTIFICATIONS in permissions,
        accessibility = ScreenTimeAccessibilityService::class.java.name in services,
        vpn = BuildConfig.WEB_PREVIEW && WebPreviewController.SERVICE in services,
      )

    /**
     * Reads the merged manifest: one lookup per component the mapping gates
     * (small parcels, whatever the size of the app's manifest) and the
     * requested permissions. Each probe answers for itself: `NameNotFound`
     * is "not declared"; any other failure (never seen; the package is the
     * app's own) is logged and reads as declared (absent for the opt-in
     * accessibility service), without hiding what the other probes resolved.
     */
    fun resolve(context: Context): BuildManifest {
      val pm = context.packageManager
      val pkg = context.packageName
      fun declared(what: String, assumeDeclared: Boolean = true, lookup: () -> Any?): Boolean = try {
        lookup() != null
      } catch (e: PackageManager.NameNotFoundException) {
        false
      } catch (e: Exception) {
        Log.w(EngineController.TAG, "build manifest: $what not resolved (${e.javaClass.simpleName}: ${e.message}); assuming ${if (assumeDeclared) "declared" else "absent"}")
        assumeDeclared
      }
      val permissions: Collection<String> = try {
        packageInfo(pm, pkg).requestedPermissions?.toList() ?: emptyList()
      } catch (e: Exception) {
        Log.w(EngineController.TAG, "build manifest: permissions not resolved (${e.javaClass.simpleName}: ${e.message}); assuming declared")
        listOf(Manifest.permission.SYSTEM_ALERT_WINDOW, Manifest.permission.PACKAGE_USAGE_STATS, Manifest.permission.POST_NOTIFICATIONS)
      }
      return from(
        services = listOfNotNull(
          ScreenTimeEngineService::class.java.name.takeIf { declared("engine service") { serviceInfo(pm, ComponentName(pkg, it)) } },
          // Added by the plugin on opt-in: "not declared" is the default build, so a failed probe reads as absent here.
          ScreenTimeAccessibilityService::class.java.name.takeIf { declared("accessibility service", assumeDeclared = false) { serviceInfo(pm, ComponentName(pkg, it)) } },
          WebPreviewController.SERVICE.takeIf { BuildConfig.WEB_PREVIEW && declared("VPN service", assumeDeclared = false) { serviceInfo(pm, ComponentName(pkg, it)).takeIf { info -> info.enabled } } },
        ),
        activities = listOfNotNull(ShieldActivity::class.java.name.takeIf { declared("shield activity") { activityInfo(pm, ComponentName(pkg, it)) } }),
        receivers = listOfNotNull(ScreenTimeDeviceAdminReceiver::class.java.name.takeIf { declared("device admin receiver") { receiverInfo(pm, ComponentName(pkg, it)) } }),
        permissions = permissions,
      )
    }

    private fun packageInfo(pm: PackageManager, pkg: String) =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
      } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
      }

    // The flag-typed overloads exist from API 33; the int ones are deprecated there.
    private fun serviceInfo(pm: PackageManager, component: ComponentName) =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
      else @Suppress("DEPRECATION") pm.getServiceInfo(component, 0)

    private fun activityInfo(pm: PackageManager, component: ComponentName) =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
      else @Suppress("DEPRECATION") pm.getActivityInfo(component, 0)

    private fun receiverInfo(pm: PackageManager, component: ComponentName) =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getReceiverInfo(component, PackageManager.ComponentInfoFlags.of(0))
      else @Suppress("DEPRECATION") pm.getReceiverInfo(component, 0)
  }
}

/**
 * Whether a `startMonitoring` config would shield: a `selectionId` (the
 * activity's own block) or an event with `blockOnThreshold`. Read as the
 * validator reads them (`ConfigValues`), so a value of the wrong type
 * counts as absent there and here alike. A build with the engine but no
 * shield rejects such a config.
 */
internal fun monitoringConfigShields(config: Map<String, Any?>): Boolean =
  ConfigValues.string(config["selectionId"]) != null ||
    ConfigValues.list(config["events"])?.any { ConfigValues.bool(ConfigValues.map(it)?.get("blockOnThreshold")) == true } == true
