package expo.modules.screentime

/**
 * The restart chain's wait at the lock screen: the pure state of one wait
 * (what each poll decides, who is told the outcome), driven by
 * [EngineController] on the main looper and tested on the JVM with the
 * clock and the poll results injected.
 *
 * **Why a poll and not a broadcast.** A revival refused at the lock screen
 * can only succeed once the keyguard is gone, and the process that found
 * the keyguard is, from Android 14 on, told about the unlock late or
 * never: `USER_PRESENT` is sent by SystemUI, which a `RECEIVER_NOT_EXPORTED`
 * receiver never sees, and both `USER_PRESENT` and `SCREEN_ON` are
 * `DEFERRAL_POLICY_UNTIL_ACTIVE` broadcasts, held back from a cached
 * process until something else makes it active (measured on a Pixel 8a,
 * Android 17: the unlock reached the chain 27.8 s later, through the floor
 * alarm;). So when a start is refused under the keyguard,
 * polling `KeyguardManager` every [POLL_MS] for [WINDOW_MS] is what tells
 * the process about the unlock within a poll. What keeps the process
 * active meanwhile is the alarm receiver's open broadcast (`goAsync` on
 * the watchdog and the schedule alarm: a background broadcast, 60 s): the
 * alarm due at the screen-on is the common case. The unlock retry's
 * `SCREEN_ON` is an ordered foreground broadcast (10 s) and is never held;
 * a wait it starts runs on the looper of a process the system may cache,
 * and the floor alarm covers the process if it goes.
 *
 * **The window.** The system finishes a background broadcast's receiver
 * after [BACKGROUND_BROADCAST_TIMEOUT_MS] (`ActivityManagerService.
 * BROADCAST_BG_TIMEOUT`, uptime, times `Build.HW_TIMEOUT_MULTIPLIER`) and
 * raises an ANR, which kills a background process without a dialog. The
 * window plus the overlay attempts that follow an unlock
 * ([ForegroundStartExemption.ATTEMPTS_MS]) stays under half of it. A
 * phone's lock screen goes dark after about 10 s without a touch, so most
 * waits end at [Outcome.SCREEN_OFF] well before the window.
 *
 * **One wait per process.** Several callers can find the keyguard within
 * milliseconds (the `SCREEN_ON` retry and the watchdog both reach `sync`
 * at the screen-on), and the exported `USER_PRESENT` can land right after
 * the poll saw the unlock; every caller joins the one wait, through the
 * revival it ends in, and is told the result once ([settle]).
 */
internal class UnlockWait(
  private val startedAtMs: Long,
  private val windowMs: Long = WINDOW_MS,
  private val pollMs: Long = POLL_MS,
) {
  /** How a wait ended. */
  enum class Outcome {
    /** The keyguard is gone with the screen on: revive now. */
    UNLOCKED,

    /** The screen went off: back to the screen-off chain (the floor alarm, the unlock retry). */
    SCREEN_OFF,

    /** The window elapsed at the lock screen: back to the floor alarm. */
    TIMED_OUT,

    /** Nothing is wanted any more: nothing to revive. */
    RELEASED,
  }

  /** What the driver does after a poll. */
  sealed class Next {
    /** Poll again after [delayMs]. */
    data class Poll(val delayMs: Long) : Next()

    /** The wait is over. */
    data class End(val outcome: Outcome) : Next()
  }

  private val waiters = ArrayList<(Boolean) -> Unit>()

  /** The outcome once a poll ended the wait, else null. */
  var outcome: Outcome? = null
    private set

  /** Whether [settle] ran: the waiters were told. */
  var settled: Boolean = false
    private set

  /** How many callers wait on this. */
  val joined: Int get() = waiters.size

  /** Adds a caller to be told, once, whether the engine was reached. Only before [settle]. */
  fun join(done: (Boolean) -> Unit) {
    check(!settled) { "joined after the wait was settled" }
    waiters += done
  }

  /**
   * One poll at [nowMs] (the driver's uptime clock): [blocked] is
   * [ForegroundStartExemption.blockedBy] right now, [wanted] the engine's
   * desired state. The order matters: a release ends the wait whatever the
   * screen does, the screen off ends it before the window is checked, and
   * the window is checked before another poll is scheduled, never past it.
   */
  fun poll(nowMs: Long, blocked: String?, wanted: Boolean): Next {
    check(outcome == null) { "polled after the wait ended" }
    val elapsed = nowMs - startedAtMs
    val ended = when {
      !wanted -> Outcome.RELEASED
      blocked == null -> Outcome.UNLOCKED
      blocked != KEYGUARD -> Outcome.SCREEN_OFF
      elapsed >= windowMs -> Outcome.TIMED_OUT
      else -> return Next.Poll(minOf(pollMs, windowMs - elapsed))
    }
    outcome = ended
    return Next.End(ended)
  }

  /** Tells every waiter [ok], once each, in join order, and empties the list. Idempotent. */
  fun settle(ok: Boolean) {
    if (settled) return
    settled = true
    val told = waiters.toList()
    waiters.clear()
    for (done in told) done(ok)
  }

  companion object {
    /** What [ForegroundStartExemption.blockedBy] answers under the keyguard with the screen on. */
    const val KEYGUARD = "keyguard"

    /** What [ForegroundStartExemption.blockedBy] answers with the screen off. */
    const val SCREEN_OFF = "screen off"

    /** How long the keyguard is polled before the wait gives up. */
    const val WINDOW_MS = 25_000L

    /** How often the keyguard is polled: an unlock is seen within this. */
    const val POLL_MS = 250L

    /** `BROADCAST_BG_TIMEOUT` on a device with `HW_TIMEOUT_MULTIPLIER` 1: the receiver is finished, and the process ANRed, past it. */
    const val BACKGROUND_BROADCAST_TIMEOUT_MS = 60_000L
  }
}
