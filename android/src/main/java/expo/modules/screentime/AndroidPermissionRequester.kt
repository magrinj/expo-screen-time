package expo.modules.screentime

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import expo.modules.interfaces.permissions.PermissionsStatus
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.screentime.PermissionSteps.Kind
import expo.modules.screentime.PermissionSteps.Status
import expo.modules.screentime.PermissionSteps.Step
import expo.modules.screentime.PermissionSteps.StepId

/**
 * Reads and requests the Android permission steps. The model (which steps,
 * the response derivation) is [PermissionSteps]; this class is the OS half.
 *
 * Statuses:
 * - A settings toggle has no "denied" state in the OS: it is on or off. Off
 *   reads `undetermined` until the app has sent the user to that screen
 *   once (recorded in the [StateStore]); after that it reads `denied`, so an
 *   onboarding UI can tell "never asked" from "went there and came back
 *   without it".
 * - `restricted`: Android 13+ "restricted settings" for sideloaded apps
 *   (AppOps `access_restricted_settings` in error mode); overlay on a
 *   low-RAM (Go) device, where the system never grants it; `accessibility`
 *   under Android 17's Advanced Protection, which disables every non-tool
 *   accessibility service ([AccessibilityHealth]).
 * - `unknown`: the vendor autostart screen, which nothing reads back.
 *
 * Requests: a settings step opens its screen and resolves when the activity
 * comes back to the foreground ([onForeground], wired to the module's
 * `OnActivityEntersForeground`), with fresh statuses. For `usageAccess` and
 * `overlay` the library also watches the AppOps toggle and brings the app
 * back as soon as it flips, which works once the overlay permission is held
 * (the background-activity-launch exemption), hence `overlay` first; for
 * `accessibility` a `ContentObserver` on the enabled-services setting does
 * the same. The
 * notification step is a runtime prompt through Expo's permission service.
 * Never rejects for a refused permission: a screen that could not be opened
 * is kept as the step's `error`.
 */
internal class AndroidPermissionRequester(private val appContext: AppContext, private val build: () -> BuildManifest) {
  private val context: Context
    get() = requireNotNull(appContext.reactContext) { "React context is not available" }

  private val store by lazy { StateStore(context) }

  /** Guarded by `synchronized(waiters)`: added on the module thread, drained on the main thread. */
  private val waiters = mutableListOf<() -> Unit>()
  /** Set on the module thread, cleared from the main thread or the AppOps callback thread. */
  @Volatile
  private var appOpsWatch: AppOpsManager.OnOpChangedListener? = null
  /** The `accessibility` step's return-after-permission watch; same lifetime rules as [appOpsWatch]. */
  @Volatile
  private var accessibilityWatch: ContentObserver? = null

  // MARK: - Entry points

  fun response(features: List<PermissionSteps.Feature>, child: Boolean): Map<String, Any> =
    PermissionSteps.response(steps(features, child))

  /**
   * Advance onboarding by one step: [step] if given (the module has checked
   * the features list it), else the first required step not granted. A step
   * already granted, or whose prompt the OS will not show again, is not
   * requested: the current response comes back.
   */
  fun request(step: StepId?, child: Boolean, features: List<PermissionSteps.Feature>, promise: Promise) {
    val current = steps(features, child)
    val id = step ?: PermissionSteps.nextStep(current)
    val target = current.firstOrNull { it.id == id }
    if (target == null || target.status == Status.granted || !target.canPrompt) {
      promise.resolve(PermissionSteps.response(current))
      return
    }
    val resolveFresh = { promise.resolve(response(features, child)) }
    store.markPermissionRequested(target.id.name)
    when (target.id) {
      StepId.notifications -> requestNotifications(resolveFresh)
      else -> openScreen(target.id, resolveFresh)
    }
  }

  /** The host activity is back in the foreground: resolve the pending requests. */
  fun onForeground() {
    stopWatchingAppOps()
    stopWatchingAccessibility()
    val pending = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
    pending.forEach { it() }
  }

  /**
   * The module is going away (a reload, the host destroyed): stop watching
   * and forget the pending requests, whose promises die with the runtime.
   */
  fun dispose() {
    stopWatchingAppOps()
    stopWatchingAccessibility()
    synchronized(waiters) { waiters.clear() }
  }

  // MARK: - Statuses

  private fun steps(features: List<PermissionSteps.Feature>, child: Boolean): List<Step> {
    val device = PermissionSteps.Device(Build.VERSION.SDK_INT, VendorAutostart.screens().isNotEmpty())
    return PermissionSteps.plan(features, child, device, build()).map { plan ->
      val (status, canPrompt) = read(plan.id)
      val error = if (status == Status.granted) null else store.permissionError(plan.id.name)?.let { PermissionSteps.StepError(it.first, it.second) }
      Step(plan.id, status, plan.required, plan.kind, error, canPrompt)
    }
  }

  private fun read(id: StepId): Pair<Status, Boolean> = when (id) {
    // A low-RAM (Go) device never grants the overlay: nothing to open.
    StepId.overlay -> if (!Settings.canDrawOverlays(context) && isLowRamDevice()) Status.restricted to false
      else toggle(id, Settings.canDrawOverlays(context), restricted = isRestrictedSettings())
    StepId.usageAccess -> toggle(id, hasUsageAccess(), restricted = isRestrictedSettings())
    StepId.deviceAdmin -> toggle(id, isDeviceAdminActive(), restricted = isRestrictedSettings())
    StepId.batteryOptimization -> toggle(id, isIgnoringBatteryOptimizations(), restricted = false)
    StepId.notifications -> notificationsStatus()
    StepId.backgroundAutostart -> Status.unknown to true
    // Advanced Protection first: it blocks the service whatever the toggle says. Restricted
    // settings lock the accessibility toggle from Android 13 (two releases before the others).
    StepId.accessibility -> when {
      AccessibilityHealth.isBlockedByAdvancedProtection(context) -> Status.restricted to true
      else -> toggle(id, AccessibilityHealth.isEnabled(context), restricted = isRestrictedSettings(since = Build.VERSION_CODES.TIRAMISU))
    }
    StepId.screenTime -> Status.unknown to false
  }

  /**
   * A settings toggle can always be opened (`canPrompt`), even when
   * restricted: the Settings screen is where the user learns about the
   * restriction and how to lift it (App info › "Allow restricted
   * settings"). The response still reports `canAskAgain: false` for a
   * restricted required step, per the contract.
   */
  private fun toggle(id: StepId, on: Boolean, restricted: Boolean): Pair<Status, Boolean> = when {
    on -> Status.granted to true
    restricted -> Status.restricted to true
    store.permissionRequestedAt(id.name) != null -> Status.denied to true
    else -> Status.undetermined to true
  }

  private fun hasUsageAccess(): Boolean = EnforcementPermissions.hasUsageAccess(context)

  /**
   * "Restricted settings": Android 13 keeps sideloaded apps out of the
   * accessibility and notification-listener toggles until the user allows
   * them from App info, and Android 15 extends that to the overlay, usage
   * access and device-admin toggles (`EnhancedConfirmationService`);
   * [since] is the release the asking step's toggle got it. The op is not in the public
   * constants, and checking it is not always permitted: Android 17 answers
   * with a SecurityException (`verifyIncomingOp`), an older build may not
   * know the op at all. Best effort: any refusal reads as not restricted,
   * so the step then reads `undetermined` / `denied`; either way the
   * Settings screen (which explains the restriction itself) is opened.
   */
  private fun isRestrictedSettings(since: Int = Build.VERSION_CODES.VANILLA_ICE_CREAM): Boolean {
    if (Build.VERSION.SDK_INT < since) return false
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    return try {
      appOps.unsafeCheckOpNoThrow(OPSTR_ACCESS_RESTRICTED_SETTINGS, context.applicationInfo.uid, context.packageName) == AppOpsManager.MODE_ERRORED
    } catch (e: Exception) {
      false
    }
  }

  private fun isLowRamDevice(): Boolean =
    context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true

  private fun isDeviceAdminActive(): Boolean =
    context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(deviceAdminComponent()) == true

  private fun deviceAdminComponent() = ComponentName(context, ScreenTimeDeviceAdminReceiver::class.java)

  private fun isIgnoringBatteryOptimizations(): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

  /**
   * Granted when notifications can be posted (the runtime permission and the
   * app-level switch). Otherwise `denied` once asked, else `undetermined`;
   * the prompt can be shown again until the user blocked it, which Android
   * reports as "no rationale to show" after a refusal.
   */
  private fun notificationsStatus(): Pair<Status, Boolean> {
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return Status.granted to true
    val asked = store.permissionRequestedAt(StepId.notifications.name) != null
    if (!asked) return Status.undetermined to true
    val activity = appContext.currentActivity
    val canPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity != null &&
      ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    return Status.denied to canPrompt
  }

  // MARK: - Requests

  private fun requestNotifications(done: () -> Unit) {
    val permissions = appContext.permissions
    if (permissions == null) {
      store.setPermissionError(StepId.notifications.name, "unavailable", "Expo's permissions service is not available")
      done()
      return
    }
    permissions.askForPermissions({ result ->
      val response = result[Manifest.permission.POST_NOTIFICATIONS]
      // A refusal is not an error (the step reads `denied`); only a prompt
      // that could not be shown is.
      store.setPermissionError(StepId.notifications.name, null, null)
      if (response != null && response.status == PermissionsStatus.DENIED && !response.canAskAgain) {
        Log.i(EngineController.TAG, "notifications blocked by the user; Settings only")
      }
      done()
    }, Manifest.permission.POST_NOTIFICATIONS)
  }

  /** Opens the screen of a settings or guide step; resolves on the next foreground. */
  private fun openScreen(id: StepId, done: () -> Unit) {
    val intents = intents(id)
    val activity = appContext.currentActivity
    var opened = false
    var failure: Exception? = null
    for (intent in intents) {
      try {
        if (activity != null) activity.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        opened = true
        break
      } catch (e: ActivityNotFoundException) {
        failure = e
      } catch (e: SecurityException) {
        failure = e
      }
    }
    if (!opened) {
      store.setPermissionError(id.name, failure?.javaClass?.simpleName ?: "unavailable", failure?.message ?: "no settings screen for ${id.name}")
      done()
      return
    }
    store.setPermissionError(id.name, null, null)
    synchronized(waiters) { waiters.add(done) }
    when (id) {
      StepId.usageAccess -> watchAppOps(AppOpsManager.OPSTR_GET_USAGE_STATS)
      StepId.overlay -> watchAppOps(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)
      StepId.accessibility -> watchAccessibility()
      else -> Unit
    }
  }

  private fun intents(id: StepId): List<Intent> {
    val packageUri = Uri.parse("package:${context.packageName}")
    return when (id) {
      StepId.overlay -> listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
      )
      StepId.usageAccess -> listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
      )
      StepId.deviceAdmin -> listOf(
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
          .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent())
          .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.expo_st_device_admin_explanation)),
      )
      // The system list, deliberately not ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
      // (its permission is not acceptable use for a blocker on Google Play).
      StepId.batteryOptimization -> listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
      StepId.backgroundAutostart -> VendorAutostart.screens().map { Intent().setComponent(it) } +
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
      // The service's own page (Android 10+; Settings falls back to the list itself when it
      // will not show the page for this component), then the list.
      StepId.accessibility -> listOfNotNull(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, AccessibilityHealth.component(context).flattenToString())
        } else {
          null
        },
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
      )
      StepId.notifications, StepId.screenTime -> emptyList()
    }
  }

  // MARK: - Return after permission

  /**
   * Watches [op] while the user is in Settings and brings the app back the
   * moment it is allowed. The launch is only honoured when the app holds the
   * overlay permission (or on Android 9 and below); otherwise the user
   * returns by hand and [onForeground] resolves the request all the same.
   */
  private fun watchAppOps(op: String) {
    // Captured once: the callback runs on a binder thread, possibly after
    // the React context is gone, and must never throw there.
    val app = context.applicationContext
    val appOps = app.getSystemService(AppOpsManager::class.java) ?: return
    val packageName = app.packageName
    val uid = app.applicationInfo.uid
    stopWatchingAppOps()
    val listener = AppOpsManager.OnOpChangedListener { changedOp, changedPackage ->
      try {
        if (changedOp != op || changedPackage != packageName) return@OnOpChangedListener
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(op, uid, packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) return@OnOpChangedListener
        stopWatchingAppOps(appOps)
        bringAppBack(app)
      } catch (e: Exception) {
        Log.i(EngineController.TAG, "AppOps watch: ${e.javaClass.simpleName}: ${e.message}")
      }
    }
    appOpsWatch = listener
    try {
      appOps.startWatchingMode(op, packageName, listener)
    } catch (e: Exception) {
      appOpsWatch = null
    }
  }

  private fun stopWatchingAppOps(appOps: AppOpsManager? = appContext.reactContext?.getSystemService(AppOpsManager::class.java)) {
    val listener = appOpsWatch ?: return
    appOpsWatch = null
    try {
      appOps?.stopWatchingMode(listener)
    } catch (e: Exception) {
      // nothing to stop
    }
  }

  /**
   * The accessibility twin of [watchAppOps]: the enabled-services setting is
   * what the Settings toggle writes (after its confirmation dialog), so the
   * app comes back the moment the user allowed the service. Honoured under
   * the same condition (the overlay permission held).
   */
  private fun watchAccessibility() {
    val app = context.applicationContext
    stopWatchingAccessibility()
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        try {
          if (!AccessibilityHealth.isEnabled(app)) return
          stopWatchingAccessibility(app)
          bringAppBack(app)
        } catch (e: Exception) {
          Log.i(EngineController.TAG, "accessibility watch: ${e.javaClass.simpleName}: ${e.message}")
        }
      }
    }
    accessibilityWatch = observer
    try {
      app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
    } catch (e: Exception) {
      accessibilityWatch = null
    }
  }

  private fun stopWatchingAccessibility(app: Context? = appContext.reactContext?.applicationContext) {
    val observer = accessibilityWatch ?: return
    accessibilityWatch = null
    try {
      app?.contentResolver?.unregisterContentObserver(observer)
    } catch (e: Exception) {
      // never registered: nothing to stop
    }
  }

  private fun bringAppBack(app: Context) {
    val activity: Activity? = appContext.currentActivity
    val intent = if (activity != null) {
      Intent(app, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    } else {
      app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
    }
    try {
      app.startActivity(intent)
    } catch (e: Exception) {
      Log.i(EngineController.TAG, "could not bring the app back after the permission flipped: ${e.message}")
    }
  }

  companion object {
    private const val OPSTR_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"
    /** `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` (Android 10+), not in the public SDK: the one service's page, by `EXTRA_COMPONENT_NAME`. */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
  }
}

/**
 * Vendor "autostart" screens, the `backgroundAutostart` step: the makers
 * dontkillmyapp ranks worst. The step exists on these makers
 * only; the request tries each known screen, then falls back to App info
 * (package visibility keeps the library from resolving them up front). The
 * step is omitted elsewhere (stock Android; Samsung's sleeping-apps list is
 * a later addition).
 */
internal object VendorAutostart {
  private val candidates: Map<String, List<ComponentName>> = mapOf(
    "xiaomi" to listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    "oppo" to listOf(
      ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
      ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    ),
    "realme" to listOf(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
    "vivo" to listOf(
      ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
      ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
    ),
    "huawei" to listOf(
      ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
      ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    ),
    "honor" to listOf(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
    "oneplus" to listOf(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
  )

  private val manufacturer: String = Build.MANUFACTURER.lowercase()

  fun screens(): List<ComponentName> = candidates[manufacturer] ?: emptyList()
}
