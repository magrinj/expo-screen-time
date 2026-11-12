package expo.modules.screentime

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * The OS reads behind the accessibility detector's state
 * (`ScreenTimeAccessibilityService`), shared by the permission requester
 * (the `accessibility` step's status) and the gap recorder (the
 * `otherProfiles` coverage): what the user set, what the system did with
 * it, and whether the service is running.
 *
 * - **Enabled** ([isEnabled]): the component is in
 *   `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, the list the Settings
 *   toggle writes. A force stop removes it; a Task Manager
 *   stop does not.
 * - **Bound** ([isBound]): the service's own record in the state store says
 *   bound ([StateStore.accessibilityBound]) and the process it names is
 *   alive. After a Task Manager stop the system lists the service as
 *   enabled but it sits in "Crashed services" for minutes:
 *   enabled and not bound is that state. A process death leaves the record
 *   saying bound with a dead pid, hence the liveness check.
 * - **Blocked** ([isBlockedByAdvancedProtection]): Android 17's Advanced
 *   Protection disables every accessibility service that is not an
 *   `isAccessibilityTool`; the step then reads
 *   `restricted`. Read through `AdvancedProtectionManager`, which needs the
 *   `normal` permission `QUERY_ADVANCED_PROTECTION_MODE` the config plugin
 *   declares with the service; a build without it reads "not blocked".
 *   Android 16 has the manager but not the accessibility rule, so the read
 *   is gated on API 37.
 */
internal object AccessibilityHealth {
  fun component(context: Context): ComponentName = ComponentName(context, ScreenTimeAccessibilityService::class.java)

  fun isEnabled(context: Context): Boolean {
    val value = try {
      Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    } catch (e: Exception) {
      null
    } ?: return false
    val ours = component(context)
    return value.split(':').any { ComponentName.unflattenFromString(it.trim()) == ours }
  }

  /**
   * Whether the accessibility manager lists the service among the enabled
   * ones it serves. Logged beside [isBound] on the device runs to settle
   * whether this API reads the bound set or the setting;
   * the health decision rests on the store record.
   */
  fun isListedByManager(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    val ours = component(context)
    return try {
      manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
        val service = info.resolveInfo?.serviceInfo ?: return@any false
        ComponentName(service.packageName, service.name) == ours
      }
    } catch (e: Exception) {
      false
    }
  }

  /** Bound right now: the store record says bound and its process is this one or a live one of the package. */
  fun isBound(record: AccessibilityRecord, alive: (Int) -> Boolean): Boolean =
    record.saysBound && record.pid != null && alive(record.pid)

  fun isBlockedByAdvancedProtection(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < ADVANCED_PROTECTION_COVERS_ACCESSIBILITY) return false
    return try {
      context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled() == true
    } catch (e: Exception) {
      // The query permission is not in this build, or the manager is absent: not readable, so not blocked.
      Log.i(EngineController.TAG, "advanced protection not readable: ${e.javaClass.simpleName}: ${e.message}")
      false
    }
  }

  /** Android 17 (API 37): where Advanced Protection gained the non-tool accessibility rule. */
  private const val ADVANCED_PROTECTION_COVERS_ACCESSIBILITY = 37
}
