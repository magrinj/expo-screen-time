package expo.modules.screentime

/**
 * The restart chain's backoff after a revival the system refused
 * ([EngineWatchdog.retry]): 20 s, 40 s, 80 s … doubled on every refusal and
 * capped at 30 minutes; the cap bounds the cost of a revival that cannot
 * succeed to two process spawns an hour. A revival the screen or the
 * keyguard blocked is not a refusal and gets a floor instead ([floorMs]).
 * Pure Kotlin, unit-tested in `android/src/test`.
 */
internal object RestartBackoff {
  const val FIRST_MS = 20_000L
  const val MAX_MS = 30L * 60 * 1000

  /**
   * The alarm floor under a lit keyguard (the screen on, locked): the wait
   * for the unlock ([UnlockWait]) catches the unlock itself, this only
   * spaces its rounds.
   */
  const val LOCKED_MS = 20_000L

  /**
   * The alarm floor with the screen off: AlarmManager's minimum delay for
   * an app (`min_futurity`, 5 s; no inexact window under 10 s). With the
   * screen off the system holds a due non-wakeup alarm until the next batch
   * of wakeup alarms (`delay_nonwakeup_alarms_while_screen_off`) and
   * delivers the held ones at the screen-on, so the shortest floor lands at
   * those batches as a longer one would, and is due at the screen-on unless
   * a batch delivered it under 5 s before or, on battery, the landings spent
   * the hour's deliveries (the bucket's; under Doze or battery saver also
   * the 7 allow-while-idle ones). With [LOCKED_MS] there, a push
   * notification's batch 5.6 s before the user woke the phone to read it
   * left the cached process asleep, and the engine came back 27 s after
   * the unlock (Pixel).
   */
  const val SCREEN_OFF_MS = 5_000L

  /** The floor for what blocks the exemption ([ForegroundStartExemption.blockedBy]). */
  fun floorMs(blocked: String?): Long = if (blocked == UnlockWait.SCREEN_OFF) SCREEN_OFF_MS else LOCKED_MS

  /** The delay before retry number `retries + 1`. */
  fun delayMs(retries: Int): Long {
    var delay = FIRST_MS
    repeat(retries.coerceIn(0, 20)) { delay = minOf(delay * 2, MAX_MS) }
    return delay
  }
}
