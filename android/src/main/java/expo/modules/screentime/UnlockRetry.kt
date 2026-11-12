package expo.modules.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * The restart chain's retry at the unlock: when a revival could not be
 * attempted because the screen was off or the keyguard was up
 * ([ForegroundStartExemption.blockedBy]), a one-shot receiver for
 * `ACTION_USER_PRESENT` and `ACTION_SCREEN_ON` (protected system
 * broadcasts, registered dynamically on the application context — no
 * manifest component) retries the SYNC when one of them reaches this
 * process, then unregisters. It lives as long as the process that armed it.
 *
 * What it can and cannot do, measured on a Pixel 8a (Android 17). `USER_PRESENT` is sent by SystemUI, not the system uid, so the
 * receiver must be **exported** or the system drops it at enqueue (both
 * are protected broadcasts: only the platform can send them, so exported
 * costs nothing). And from Android 14 both broadcasts are
 * `DEFERRAL_POLICY_UNTIL_ACTIVE`: a cached process — this one, between
 * alarms — gets them only once something else makes it active, which is
 * the floor alarm ([EngineWatchdog.retry], [RestartBackoff.SCREEN_OFF_MS])
 * landing at the screen-on. So on 14+ this receiver fires at the screen-on
 * a few milliseconds before the watchdog does, and the wait for the unlock
 * that follows ([UnlockWait]), kept alive by the alarm's open broadcast,
 * is what revives the engine; on 13 and below, where nothing is deferred,
 * it fires at the unlock itself. This receiver never holds its broadcast:
 * `SCREEN_ON` is an ordered foreground broadcast (10 s timeout, and the
 * next screen broadcast waits for it).
 */
internal object UnlockRetry {
  private var receiver: BroadcastReceiver? = null

  @Synchronized
  fun arm(context: Context) {
    if (receiver != null) return
    val app = context.applicationContext
    val listener = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        disarm(app)
        Log.i(EngineController.TAG, "retrying the engine's start at ${intent.action?.substringAfterLast('.')}")
        // Never `goAsync` here: SCREEN_ON is an ordered foreground broadcast
        // (10 s timeout, the next screen broadcast waits for it); the wait the
        // controller may start runs on the looper after this returns.
        EngineController.sync(app, "unlock")
      }
    }
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_USER_PRESENT)
      addAction(Intent.ACTION_SCREEN_ON)
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Exported: USER_PRESENT comes from SystemUI's uid, which a
        // not-exported receiver never sees; both actions are protected.
        app.registerReceiver(listener, filter, Context.RECEIVER_EXPORTED)
      } else {
        app.registerReceiver(listener, filter)
      }
      receiver = listener
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "unlock retry not armed: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  @Synchronized
  fun disarm(context: Context) {
    val listener = receiver ?: return
    receiver = null
    try {
      context.applicationContext.unregisterReceiver(listener)
    } catch (e: IllegalArgumentException) {
      // not registered: nothing to do
    }
  }
}
