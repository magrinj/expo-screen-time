package expo.modules.screentime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Starts and stops the engine service from the desired state in [StateStore],
 * from whichever process asks: the module (app launch, a block or schedule
 * being set, later), the restart receiver (boot, update, the alarms) and the
 * service itself.
 *
 * The engine runs only while there is something to enforce: the module's
 * mutations derive the desired state ([StateStore.syncEngineDesired]) and
 * record a release for `getEnforcementGaps` ([EnforcementGapRecorder]).
 *
 * A `startForegroundService` can be refused (the app is in the background on
 * Android 12+ without an exemption, a stopped app on 15+). The refusal is
 * never thrown: while the engine is wanted and its process is gone, the
 * start is retried under the overlay exemption ([ForegroundStartExemption],
 * the **restart chain**: the watchdog alarm and the schedule alarm land
 * here from the background, where only that exemption or the user's
 * battery exemption allows the start); a refusal that survives the retry is
 * recorded as a gap and the watchdog re-armed (at the unlock when the screen
 * was off or locked, else with a backoff), so the chain tries again. Under
 * the keyguard with the screen on, the revival waits for the unlock in
 * place ([UnlockWait], polled on the main looper while the caller's
 * broadcast stays open) and runs the moment the keyguard is gone.
 */
internal object EngineController {
  const val TAG = "ExpoScreenTime"

  /**
   * Brings the service in line with the desired state. [done] (optional)
   * is called exactly once with whether the service was reached — at once,
   * or from the main thread when a revival had to run.
   *
   * Never `stopService`: a stop that lands while a just-started foreground
   * service is still spawning makes the system kill its process with
   * `ForegroundServiceDidNotStartInTimeException` and show a crash dialog,
   * whatever the service then does (seen on Android 17 when `unblockAll`
   * followed `blockSelection` within a millisecond). A release is therefore
   * a start-intent too: the service answers with `startForeground`, as the
   * system requires, re-reads the store and stops itself. It is only sent
   * when the engine process is alive, so a release never spawns the process
   * to stop it; a process still spawning reads the released state from the
   * store and stops on its own. "Alive" is the service's own flag in the
   * store ([StateStore.engineRunning]), not the process: the boot and
   * update receivers run in the engine process, and a launch often finds
   * it cached, which made every boot start the service just to stop it.
   *
   * Always SYNC, never START or STOP: the intent carries no state. Intents
   * from here are delivered later than the store writes they follow, so a
   * STOP that wrote "released" into the store would clobber a block set a
   * millisecond after it (seen in the conformance suite's block / unblock /
   * block sequence). The handler reads the store as it is when the intent
   * arrives, and the last intent always sees the latest state.
   */
  fun sync(context: Context, reason: String, done: ((Boolean) -> Unit)? = null) {
    if (WebPreviewController.built(context)) {
      val completed = WebPreviewController.both(done)
      WebPreviewController.sync(context, reason, completed)
      syncEngine(context, reason, completed)
    } else syncEngine(context, reason, done)
  }

  private fun syncEngine(context: Context, reason: String, done: ((Boolean) -> Unit)?) {
    val app = context.applicationContext
    val store = StateStore(app)
    val desired = store.engineDesired
    if (!desired && !store.engineRunning) {
      // Released while the engine was already down (a refused revival had
      // armed the watchdog): nothing is wanted, nothing stays armed.
      EngineWatchdog.cancel(app)
      done?.invoke(true)
      return
    }
    val refusal = start(app, reason)
    if (refusal == null) {
      done?.invoke(true)
      return
    }
    // A start refused while the exemption could not be granted anyway (the
    // screen off, the keyguard up) is the chain waiting, not a failure worth
    // a record: the ring of failures would fill with it at every retry.
    val blocked = ForegroundStartExemption.blockedBy(app)
    if (blocked == null) store.recordEngineFailure("start:${refusal.javaClass.simpleName}", refusal.message)
    // Wanted and refused: unless the run's process is still alive and
    // serving (a SYNC refused from the background while the service runs
    // changes nothing), the engine is down. Revive it under the overlay
    // exemption; only a revival refused too is a gap (`getEnforcementGaps`).
    // Only the background-start refusal (an IllegalStateException,
    // ForegroundServiceStartNotAllowedException included) can be answered
    // by the exemption: anything else (a SecurityException: a permission
    // missing from the manifest) is recorded once and left alone.
    val recorder = EnforcementGapRecorder(app, store)
    if (!desired || recorder.runAlive()) {
      done?.invoke(false)
      return
    }
    if (refusal !is IllegalStateException) {
      recorder.engineRefused("start:${refusal.javaClass.simpleName}", certain = false)
      done?.invoke(false)
      return
    }
    // With the screen off or the keyguard up the exemption cannot be granted:
    // the engine is down for certain (the gap opens) and no refusal is
    // counted. Under the keyguard the revival waits here for the unlock;
    // with the screen off nothing can wait (the broadcast would hold the
    // alarm's wakelock), so the retry comes at the unlock or the floor alarm.
    blocked?.let {
      Log.i(TAG, "revival deferred ($reason): $blocked")
      recorder.engineRefused("start:${refusal.javaClass.simpleName}", certain = false)
      if (blocked == UnlockWait.KEYGUARD) {
        awaitUnlock(app, reason, refusal, recorder, done)
        return
      }
      EngineWatchdog.retry(app, blocked)
      done?.invoke(false)
      return
    }
    // One revival at a time in this process: a caller that finds a wait, or
    // the revival an unlock allowed, in flight joins it (the exported
    // `USER_PRESENT` can land here right after the poll saw the unlock).
    handler.post {
      inFlight?.let { wait ->
        wait.join { ok -> done?.invoke(ok) }
        Log.i(TAG, "revival joins the one in flight ($reason): ${wait.joined} waiting")
        return@post
      }
      revive(app, reason, refusal, recorder, done)
    }
  }

  /** The revival under the overlay exemption; a refusal records the gap and re-arms the watchdog (or cancels it after a release). */
  private fun revive(app: Context, reason: String, refusal: Exception, recorder: EnforcementGapRecorder, done: ((Boolean) -> Unit)?) {
    ForegroundStartExemption.attempt(
      app,
      reason,
      // A release landing mid-revival ends it: nothing is spawned to stop itself.
      { !StateStore(app).engineDesired || start(app, "$reason:revive") == null },
    ) { ok ->
      if (!ok) {
        recorder.engineRefused("start:${refusal.javaClass.simpleName}", certain = false)
        if (StateStore(app).engineDesired) EngineWatchdog.retry(app, ForegroundStartExemption.blockedBy(app)) else EngineWatchdog.cancel(app)
      }
      done?.invoke(ok)
    }
  }

  // MARK: - The wait at the lock screen

  private val handler = Handler(Looper.getMainLooper())

  /** This process's wait at the lock screen, kept through the revival it ends in; main thread only. */
  private var inFlight: UnlockWait? = null

  /**
   * A start refused under the keyguard with the screen on: the revival
   * waits for the unlock in this process ([UnlockWait]: why a poll, and
   * the window). The caller's `done` runs when the wait is over: after the
   * revival the unlock allowed, or at once with false when the screen went
   * off or the window elapsed (the watchdog then retries as before:
   * [EngineWatchdog.retry]). A caller that finds a wait running joins it.
   *
   * The floor alarm is armed before the wait, past the window and the
   * attempts: a process that dies mid-wait is caught by it, the engine's
   * start pushes it to the watchdog period, and a wait that ends without
   * the engine re-arms it ([RestartBackoff.floorMs]: 5 s once the screen is
   * off, 20 s at a lit lock screen). A wait that ends
   * with the engine running spends no delivery — unless the service's own
   * refused promotion ([ScreenTimeEngineService]) re-armed the floor at
   * 20 s meanwhile, which lands on the wait and joins it. The receiver's
   * broadcast is what keeps the process active meanwhile: the watchdog's
   * and the schedule alarm's (`goAsync`, background queue, 60 s timeout);
   * a wait started by a caller with no open broadcast (the unlock retry,
   * the module) runs on the looper of a process the system may cache, and
   * the floor covers it if it goes.
   */
  private fun awaitUnlock(app: Context, reason: String, refusal: Exception, recorder: EnforcementGapRecorder, done: ((Boolean) -> Unit)?) {
    handler.post {
      inFlight?.let { wait ->
        wait.join { ok -> done?.invoke(ok) }
        Log.i(TAG, "revival joins the wait for the unlock ($reason): ${wait.joined} waiting")
        return@post
      }
      EngineWatchdog.retry(app, UnlockWait.KEYGUARD, floorMs = UnlockWait.WINDOW_MS + RestartBackoff.LOCKED_MS)
      val wait = UnlockWait(SystemClock.uptimeMillis())
      wait.join { ok -> done?.invoke(ok) }
      inFlight = wait
      Log.i(TAG, "revival waits for the unlock ($reason): keyguard polled every ${UnlockWait.POLL_MS} ms for ${UnlockWait.WINDOW_MS / 1000} s")
      pollUnlock(app, reason, refusal, recorder, wait, SystemClock.uptimeMillis())
    }
  }

  private fun pollUnlock(app: Context, reason: String, refusal: Exception, recorder: EnforcementGapRecorder, wait: UnlockWait, startedAt: Long) {
    var blocked: String? = UnlockWait.KEYGUARD
    val next = try {
      blocked = ForegroundStartExemption.blockedBy(app)
      wait.poll(SystemClock.uptimeMillis(), blocked, StateStore(app).engineDesired)
    } catch (e: Exception) {
      // The store unreadable, a system service gone: the wait ends as a timeout would.
      Log.w(TAG, "unlock poll failed ($reason): ${e.javaClass.simpleName}: ${e.message}")
      UnlockWait.Next.End(UnlockWait.Outcome.TIMED_OUT)
    }
    when (next) {
      is UnlockWait.Next.Poll -> handler.postDelayed({ pollUnlock(app, reason, refusal, recorder, wait, startedAt) }, next.delayMs)
      is UnlockWait.Next.End -> {
        Log.i(TAG, "wait for the unlock ended ($reason): ${next.outcome} after ${SystemClock.uptimeMillis() - startedAt} ms, ${wait.joined} waiting")
        try {
          when (next.outcome) {
            UnlockWait.Outcome.UNLOCKED -> {
              // Back by another path meanwhile (a sticky restart, a launch): nothing to revive.
              if (recorder.runAlive()) settle(wait, true) else revive(app, reason, refusal, recorder) { ok -> settle(wait, ok) }
            }
            UnlockWait.Outcome.RELEASED -> {
              // As `sync` answers a release of an engine already down.
              val running = StateStore(app).engineRunning
              if (!running) EngineWatchdog.cancel(app)
              settle(wait, !running)
            }
            UnlockWait.Outcome.SCREEN_OFF, UnlockWait.Outcome.TIMED_OUT -> {
              EngineWatchdog.retry(app, blocked ?: UnlockWait.KEYGUARD)
              settle(wait, false)
            }
          }
        } catch (e: Exception) {
          // Never left unsettled: the callers' broadcasts must finish.
          Log.w(TAG, "wait for the unlock not concluded ($reason): ${e.javaClass.simpleName}: ${e.message}")
          runCatching { EngineWatchdog.retry(app, UnlockWait.KEYGUARD) }
          settle(wait, false)
        }
      }
    }
  }

  /** The wait leaves the process's slot, then its callers are told. */
  private fun settle(wait: UnlockWait, ok: Boolean) {
    if (inFlight === wait) inFlight = null
    wait.settle(ok)
  }

  /** One `startForegroundService`; the refusal when the system throws, null when the intent was accepted. */
  private fun start(app: Context, reason: String): Exception? {
    val intent = Intent(app, ScreenTimeEngineService::class.java)
      .setAction(ScreenTimeEngineService.ACTION_SYNC)
      .putExtra(ScreenTimeEngineService.EXTRA_REASON, reason)
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        app.startForegroundService(intent)
      } else {
        app.startService(intent)
      }
      null
    } catch (e: Exception) {
      // IllegalStateException ("not allowed to start service Intent … app is
      // in background", or ForegroundServiceStartNotAllowedException, its
      // 31+ subclass) and SecurityException are the documented refusals.
      Log.w(TAG, "engine start refused ($reason): ${e.javaClass.simpleName}: ${e.message}")
      e
    }
  }
}
