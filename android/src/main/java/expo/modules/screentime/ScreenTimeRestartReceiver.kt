package expo.modules.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * After a reboot or an update of the app, brings the engine back when the
 * state store says it should run. Declared in the `:screentime` process so
 * the React Native process is not started for it. A specialUse service may
 * start from BOOT_COMPLETED on Android 15+; a refusal is handled by
 * [EngineController], never thrown.
 *
 * It is also the target of the engine's two alarms, both explicit
 * broadcasts from `AlarmManager`: the inexact schedule alarm ([ACTION_WAKE],
 * armed by [EngineLoop] at the next schedule boundary) and the watchdog
 * ([ACTION_WATCHDOG], [EngineWatchdog]). Either is the same stateless SYNC
 * start intent as a boot, so the service promotes first, reads the store
 * and evaluates the schedules at now. From the background neither alarm
 * allows the start by itself (an inexact alarm's temp allowlist carries no
 * foreground-service capability), so the controller revives the engine
 * under the overlay exemption; the receiver stays alive for it
 * (`goAsync`: under two seconds, or up to [UnlockWait.WINDOW_MS] and the
 * attempts when the alarm lands at the lock screen and the controller
 * waits there for the unlock — under half the background broadcast
 * timeout). A watchdog that lands while the engine
 * lives in this very process (the CPU slept past the period: the heartbeat
 * counts uptime, the alarm counts elapsed time) only re-arms it.
 */
class ScreenTimeRestartReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val reason = when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> intent.action ?: "restart"
      ACTION_WAKE -> "alarm"
      ACTION_WATCHDOG -> "watchdog"
      else -> return
    }
    if (intent.action == ACTION_WATCHDOG && engineLivesHere(context)) {
      EngineWatchdog.arm(context)
      return
    }
    val pending = goAsync()
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    val finish = { if (finished.compareAndSet(false, true)) pending.finish() }
    try {
      EngineController.sync(context, reason) { finish() }
    } catch (e: Exception) {
      // A failed state write (disk full) must not crash the process from a
      // broadcast: the watchdog or the next boundary retries.
      android.util.Log.e("ExpoScreenTime", "restart sync failed ($reason)", e)
      finish()
    }
  }

  /** Whether the recorded run is live in this process (the receiver shares `:screentime` with the service). */
  private fun engineLivesHere(context: Context): Boolean = try {
    val store = StateStore(context.applicationContext)
    store.engineDesired && store.read { document ->
      EnforcementGapTracker.load(document).run?.let { it.isLive && it.pid == Process.myPid() } ?: false
    }
  } catch (e: Exception) {
    false
  }

  companion object {
    const val ACTION_WAKE = "expo.modules.screentime.action.WAKE"
    const val ACTION_WATCHDOG = "expo.modules.screentime.action.WATCHDOG"
  }
}
