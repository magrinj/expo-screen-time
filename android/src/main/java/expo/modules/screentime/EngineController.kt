package expo.modules.screentime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts and stops the engine service from the desired state in [StateStore],
 * from whichever process asks: the module (app launch, a block or schedule
 * being set, later), the restart receiver (boot, update) and the service
 * itself.
 *
 * The engine runs only while there is something to enforce. Nothing sets the
 * desired state yet; blocks, schedules and unlocks will, through
 * [setDesired].
 *
 * A `startForegroundService` can be refused (the app is in the background on
 * Android 12+ without an exemption, a stopped app on 15+); the refusal is
 * recorded, never thrown: the run showed the system's own sticky
 * restart being refused hours later, so every entry point here is a
 * recovery path that must not crash the caller.
 */
internal object EngineController {
  const val TAG = "ExpoScreenTime"

  fun setDesired(context: Context, desired: Boolean) {
    StateStore(context).engineDesired = desired
    sync(context, if (desired) "desired" else "released")
  }

  /** Brings the service in line with the desired state. */
  fun sync(context: Context, reason: String) {
    val app = context.applicationContext
    val intent = Intent(app, ScreenTimeEngineService::class.java)
    if (!StateStore(app).engineDesired) {
      app.stopService(intent)
      return
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        app.startForegroundService(intent.putExtra(ScreenTimeEngineService.EXTRA_REASON, reason))
      } else {
        app.startService(intent)
      }
    } catch (e: Exception) {
      // IllegalStateException ("not allowed to start service Intent … app is
      // in background", or ForegroundServiceStartNotAllowedException, its
      // 31+ subclass) and SecurityException are the documented refusals.
      Log.w(TAG, "engine start refused ($reason): ${e.javaClass.simpleName}: ${e.message}")
      StateStore(app).recordEngineFailure("start:${e.javaClass.simpleName}", e.message)
    }
  }
}
