package expo.modules.screentime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * The watchdog of the restart chain: an inexact, non-wakeup, allow-while-idle
 * alarm the engine pushes forward on every heartbeat, every start intent and
 * every screen change while it is wanted, so it lands only when the engine
 * stopped beating. It targets [ScreenTimeRestartReceiver] with
 * [ScreenTimeRestartReceiver.ACTION_WATCHDOG], which sends the same
 * stateless SYNC as a boot does and, when the start is refused from the
 * background, revives the engine under the overlay exemption
 * ([ForegroundStartExemption]). A live engine that receives it only
 * re-arms it.
 *
 * **Screen on: [PERIOD_MS].** The alarm is inexact (`setAndAllowWhileIdle`
 * takes the same window as `set`): the system may add up to three quarters
 * of the delay
 * (`AlarmManagerService.maxTriggerTime`), so the alarm lands within
 * [[PERIOD_MS], [BOUND_MS]] of the last beat — **3.5 minutes** after the
 * last heartbeat, which is at most one minute before the death, when the
 * standby quota below allows it. This is the bound that matters: a Task
 * Manager stop is made with the screen on, and no sticky restart follows it.
 *
 * **Screen off: [IDLE_PERIOD_MS].** Nothing can be opened behind a dark
 * screen, and the engine's beats stop whenever the CPU sleeps (in deep Doze
 * they run only in the maintenance windows, spaced up to 6 hours: measured
 * on the Pixel). With the short period, the alarm would come
 * due over every sleep and land on the live engine at the next wake — often
 * the screen-on itself — and every delivery spends the app's standby quota
 * at the moment a stop is likeliest. The long period never comes due while
 * the engine lives, and `SCREEN_ON` brings the short one back at once. A
 * death with the screen off is left to the sticky restart (seconds) and
 * caught by this alarm within [IDLE_PERIOD_MS] (plus at most an hour of
 * inexact window) at worst.
 *
 * **Standby quota.** On battery, `AlarmManagerService` delivers at most a
 * fixed number of non-exempt alarms per app per hour, by standby bucket
 * (active 720, working set 10, frequent 2, rare 1; restricted 1 a day).
 * Every delivery counts, non-wakeup ones included; past the quota, the next
 * alarm waits for the oldest delivery to leave the hour. A running
 * foreground service does not lift the bucket: on the Pixel, the example
 * app fell to rare (adaptive battery's prediction) about an hour after its
 * last use, its engine running. The device admin and the battery-
 * optimization exemption make the app `EXEMPTED` (no quota). A revival
 * under the overlay counts as a system interaction and lifts the app to
 * active for 10 minutes. Without either step, a rare app gets one watchdog
 * an hour: once the bucket is back to rare, a death within the hour of the
 * previous delivery waits for that hour to pass (measured on the emulator:
 * 57 minutes). Allow-while-idle does not lift that quota:
 * for an app targeting S or later, an inexact allow-while-idle alarm is
 * downgraded to `FLAG_ALLOW_WHILE_IDLE_COMPAT`, which the bucket quota does
 * not exempt.
 *
 * **Allow-while-idle, for battery saver.** Battery saver's forced app
 * standby holds a plain alarm of an app with no active process for as long
 * as it lasts (`AlarmManagerService.adjustDeliveryTimeBasedOnBatterySaver`:
 * "Not allowed"; the alarms are re-evaluated when it lifts), screen on or
 * off, and lets an allow-while-idle one through within a quota
 * (`allow_while_idle_compat_quota`, 7 an hour on the Pixel, shared with the
 * schedule alarm; Doze applies the same one). The user's battery saver is
 * not the only one: on the Pixel, Google's adaptive battery saver turns that
 * standby on at every unplug and lifts it for one minute every 6 to 14
 * minutes, so a plain watchdog brought the engine back 12.5 minutes after a
 * Task Manager stop made and used with the screen on. The
 * battery-optimization exemption and the device admin (`EXEMPTED`) are
 * outside that standby.
 *
 * The price: the flag lets the alarm through Doze too. With the screen off,
 * a dead engine's watchdog can land at a CPU wake before the user is back
 * and, in `rare`, spend the hour's only delivery on a revival the dark
 * screen blocks, where a plain alarm would have waited in Doze for the
 * wake: a stop followed at once by the lock, the phone left asleep, can
 * leave the engine down until that delivery leaves the hour. The stop the
 * chain exists for is made and used with the screen on, and the device
 * admin or the battery exemption remove both costs. The bucket quota above
 * still applies, and the flag's temp allowlist carries no
 * foreground-service capability, so the revival still goes through the
 * overlay.
 *
 * **Not a wakeup alarm, on purpose.** A non-wakeup alarm is delivered when
 * the CPU is awake anyway, and at the latest when the device becomes
 * interactive, which is when enforcement resumes. It costs no wakeups.
 *
 * **After a refused revival** ([retry]) the delay backs off
 * ([RestartBackoff]: 20 s, 40 s, 80 s … up to 30 minutes, doubled on every
 * refusal and reset once the engine runs, [StateStore.engineRetries]); the
 * cap bounds the cost of a revival that cannot succeed (the overlay
 * permission revoked, a background-restricted app) to two spawns an hour,
 * and a re-granted permission is picked up within it. A revival the screen
 * or the keyguard blocked is not a refusal: the controller waits for the
 * unlock while the screen is on ([UnlockWait]), and this alarm is the floor
 * otherwise — with the screen off at [RestartBackoff.SCREEN_OFF_MS], held by
 * the system until a batch of wakeup alarms, so it lands at those until the
 * unlock, uselessly (on battery each landing counts against the hour's
 * quota): it is what makes an alarm due at the screen-on, which is what
 * wakes the cached process and lets the chain see the unlock (research
 * §10.2).
 *
 * The user's force stop cancels the alarm with the app's other pending
 * intents (the stopped state): a force stop stays final, as intended.
 *
 * Nothing is armed when nothing is wanted: cancelled when the engine is
 * released.
 */
internal object EngineWatchdog {
  /** The delay the engine re-arms at on every heartbeat while the screen is on. */
  const val PERIOD_MS = 120_000L

  /** Where the inexact alarm can land at the latest after the last re-arm: `PERIOD_MS` plus the system's 75 % window. */
  const val BOUND_MS = PERIOD_MS + PERIOD_MS * 3 / 4

  /**
   * The delay while the screen is off: past deep Doze's longest sleep
   * between two maintenance windows ([DOZE_MAX_IDLE_MS]), so the beats of a
   * live engine always push the alarm forward before it comes due.
   */
  const val IDLE_PERIOD_MS = 8 * 60 * 60 * 1000L

  /** Deep Doze's longest idle stretch (`DeviceIdleController`'s default max idle timeout), what [IDLE_PERIOD_MS] must exceed. */
  const val DOZE_MAX_IDLE_MS = 6 * 60 * 60 * 1000L

  /** The period for the screen's state. */
  fun periodMs(interactive: Boolean): Long = if (interactive) PERIOD_MS else IDLE_PERIOD_MS

  /** Pushes the alarm to `now + periodMs(interactive)` (an existing one is replaced, nothing is delivered). */
  fun arm(context: Context, interactive: Boolean = isInteractive(context)) = set(context, periodMs(interactive))

  /** Unknown counts as on: the short period is the safe side. */
  private fun isInteractive(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

  /**
   * The next try after a revival that did not happen. [blocked] (from
   * [ForegroundStartExemption.blockedBy]) means the exemption could not be
   * granted at all — the screen off, the keyguard up — so the system was not
   * asked: the retry waits for the unlock ([UnlockRetry]) with this alarm at
   * [floorMs] ([RestartBackoff.floorMs] by default: 5 s with the screen off,
   * 20 s under a lit keyguard; the controller's wait at the lock screen sets
   * it past its window, [UnlockWait]) as the floor, and nothing is counted.
   * A refusal by the system counts and backs off ([RestartBackoff]: 20 s
   * doubled per refusal, capped at 30 min).
   */
  fun retry(context: Context, blocked: String?, floorMs: Long = RestartBackoff.floorMs(blocked)) {
    val app = context.applicationContext
    if (blocked != null) {
      set(app, floorMs)
      UnlockRetry.arm(app)
      Log.i(EngineController.TAG, "watchdog retry at the unlock ($blocked), or in ${floorMs / 1000} s")
      return
    }
    val store = StateStore(app)
    val retries = store.engineRetries
    val delay = RestartBackoff.delayMs(retries)
    store.engineRetries = retries + 1
    set(app, delay)
    Log.i(EngineController.TAG, "watchdog retry ${retries + 1} in ${delay / 1000} s")
  }

  fun cancel(context: Context) {
    val app = context.applicationContext
    val alarms = app.getSystemService(AlarmManager::class.java) ?: return
    try {
      alarms.cancel(operation(app))
      Log.i(EngineController.TAG, "watchdog cancelled")
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "watchdog not cancelled: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  private fun set(context: Context, delayMs: Long) {
    val app = context.applicationContext
    val alarms = app.getSystemService(AlarmManager::class.java) ?: return
    try {
      // Allow-while-idle: battery saver holds a plain alarm of an idle app indefinitely (see the class doc).
      alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, operation(app))
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "watchdog not armed: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  private fun operation(app: Context): PendingIntent = PendingIntent.getBroadcast(
    app,
    REQUEST_CODE,
    Intent(app, ScreenTimeRestartReceiver::class.java).setAction(ScreenTimeRestartReceiver.ACTION_WATCHDOG),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

  /** Distinct from the schedule alarm's (0), which targets the same receiver with another action. */
  private const val REQUEST_CODE = 1
}
