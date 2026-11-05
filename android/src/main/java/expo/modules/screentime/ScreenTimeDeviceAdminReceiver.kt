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
 * Activated by the `deviceAdmin` permission step; measured on Android 17
 *.
 *
 * Deactivation is the user's two deliberate steps: the toggle in Settings ›
 * Security › Device admin apps, then the confirmation the system shows with
 * [onDisableRequested]'s text (`expo_st_device_admin_disable_warning`, a
 * string resource the consumer app overrides and localises like the
 * notice's). Without a text the system deactivates on the first tap, which
 * is no friction at all. A deactivation is friction lifted, not a gap: the
 * engine process is not touched by it (measured), so enforcement goes on
 * until the user stops the app, which `getEnforcementGaps` records.
 */
class ScreenTimeDeviceAdminReceiver : DeviceAdminReceiver() {
  override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
    context.getString(R.string.expo_st_device_admin_disable_warning)

  override fun onDisabled(context: Context, intent: Intent) {
    Log.i(EngineController.TAG, "device admin deactivated: force-stop and uninstall friction lifted (not a gap)")
  }
}
