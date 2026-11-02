package expo.modules.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or an update of the app, brings the engine back when the
 * state store says it should run. Declared in the `:screentime` process so
 * the React Native process is not started for it. A specialUse service may
 * start from BOOT_COMPLETED on Android 15+; a refusal is recorded by
 * [EngineController], not thrown.
 */
class ScreenTimeRestartReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
        EngineController.sync(context, intent.action ?: "restart")
    }
  }
}
