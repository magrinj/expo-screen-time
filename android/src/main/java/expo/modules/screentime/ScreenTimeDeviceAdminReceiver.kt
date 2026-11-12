package expo.modules.screentime

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * A device admin with no policy (`res/xml/expo_st_device_admin.xml`): while
 * active, Settings disables "Force stop", the Task Manager shows no "Stop"
 * button for the engine (SystemUI `FgsManagerController`:
 * `REASON_ACTIVE_DEVICE_ADMIN` hides it) and the app cannot be uninstalled
 * until the admin is deactivated (App info's "Uninstall" is disabled,
 * `pm uninstall` fails with `DELETE_FAILED_DEVICE_POLICY_MANAGER`).
 * Activated by the `deviceAdmin` permission step; measured on Android 17.
 *
 * Deactivation is the user's two deliberate steps: the toggle in Settings ›
 * Security › Device admin apps, then the confirmation the system shows with
 * [onDisableRequested]'s text (`expo_st_device_admin_disable_warning`, a
 * string resource the consumer app overrides and localises like the
 * notice's). Without a text the system deactivates on the first tap, which
 * is no friction at all. A deactivation is friction lifted, not a gap: the
 * engine process is not touched by it (measured), so enforcement goes on
 * until the user stops the app, which `getEnforcementGaps` records.
 *
 * [onDisabled] also stamps the deactivation in the state store: the engine
 * takes the package installer off both restrictions' shields for
 * [RestrictionSources.HOLD_OFF_MS] after it, so the system's "Deactivate &
 * uninstall" route to remove this app never ends under a shield (the Play
 * "clear uninstall" rule).
 * [onEnabled] clears the stamp: once the admin guards this app again, there
 * is no deactivation path left to keep clear.
 */
class ScreenTimeDeviceAdminReceiver : DeviceAdminReceiver() {
  override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
    context.getString(R.string.expo_st_device_admin_disable_warning)

  override fun onEnabled(context: Context, intent: Intent) {
    try {
      val store = StateStore(context)
      if (store.adminDisabledAt == null) return
      store.adminDisabledAt = null
      if (store.restrictions.any) EngineController.sync(context, "admin-enabled")
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "admin activation not recorded: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  override fun onDisabled(context: Context, intent: Intent) {
    Log.i(EngineController.TAG, "device admin deactivated: force-stop and uninstall friction lifted (not a gap)")
    try {
      val store = StateStore(context)
      store.adminDisabledAt = System.currentTimeMillis()
      // The engine (another process) re-composes now rather than at its next reload.
      if (store.restrictions.any) EngineController.sync(context, "admin-disabled")
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "admin deactivation not recorded: ${e.javaClass.simpleName}: ${e.message}")
    }
  }
}
