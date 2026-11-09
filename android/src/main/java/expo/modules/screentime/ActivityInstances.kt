package expo.modules.screentime

/**
 * One activity class, as far as the usage report can count it from a stream
 * that has no instance id (the public `UsageEvents.Event` has none; `dumpsys`
 * shows one). The report keeps main's rule, where a pause ends its class and
 * a closing event ends every class, and adds one thing: an ACTIVITY_STOPPED
 * ends the class when it can only be its one resumed instance dying in the
 * foreground. That is what a process killed in the foreground leaves, a stop
 * with no pause before it (Gmail's welcome tour, resumed and stopped within a
 * second, which the old rule read as 142 s of use; an app force-stopped).
 *
 * - [resumed]: resumes since the class came to the foreground, 0 when it is
 *   not. A pause ends the class, as before.
 * - [owedStops]: the stops the stream still owes the class. A pause seen owes
 *   one, and so does each instance in the foreground at a closing. A stop pays
 *   one first. An owed stop never expires, since an older instance's stop can
 *   come late after a relaunch. Some are never paid: an instance back from
 *   under a dialog or out of picture-in-picture does not stop for that pause.
 *   Such a stale one pays the class's next stop, however late. A death then
 *   reads as main reads it, and the counts turn [uncertain] until a boot.
 * - [closingPauses]: instances in the foreground at the last closing whose
 *   pause has not come yet. Their stop is already owed, so the pause that
 *   follows the closing owes no second one. Cleared at the class's next resume.
 * - [uncertain]: the counts can no longer tell which instance a stop belongs
 *   to. Either two instances of the class were resumed at once (multi-window),
 *   or a stop paid an owed one while an instance was resumed, so it may have
 *   been that instance dying. From then on, until a boot, no stop of the class
 *   is read as a death.
 *
 * So a stop ends the class only with nothing owed, exactly one instance
 * resumed, and no uncertainty. The aggregator also leaves it unread while
 * another class of the package is uncertain. Two properties follow:
 * - the report never counts an app, or the total, as in use when main's rule
 *   does not. Its spans are main's, cut short only by a stop read as a death;
 * - such a stop is a real death, except in the lookback case below (an
 *   instance already alive when the stream begins).
 *
 * So the report is further from the truth than main's rule in two cases:
 * - the lookback case below;
 * - where main was right by luck: a dead app's phantom covered main's own
 *   under-count of a second window of one class (the first pause closes the
 *   class while another window of it is still open), in the same app or in
 *   the total.
 *
 * Measured with a judge that rebuilds the finalized intervals of every app
 * and of the total, and compares them with the truth and with main at every
 * instant. It ran on 136,000 simulated traces with instance ids:
 *
 * | | Worse than main | Better than main |
 * |---|---|---|
 * | Per app | 311 s, in 6 traces | 3.8 million s |
 * | Total | 31,882 s, in 778 traces | 3.4 million s |
 *
 * The traces cover app switches, chained relaunches, old stops 0.1–200 s
 * late, dialog and picture-in-picture returns, deaths, two to five windows
 * of one class, two activity classes in separate processes, screen-offs,
 * locks and boots.
 *
 * On the r12 emulator day's raw stream, the example app counts 289.9 min
 * against an instance-id ground truth of 289.88, and Gmail 0.0; main counts
 * 293.3 and 2.4.
 *
 * The one misreading left starts with an instance already alive when the
 * stream begins: resumed, or paused, before the lookback, so the stream
 * never shows the pause that owes its stop. Its stop, whenever it arrives,
 * can pay another instance's owed stop or be taken for a resumed instance's
 * death. Either way, a stop of the class then reads as a death it is not.
 * That is an undercount, never a double count.
 *
 * Below API 29 the stream logs no ACTIVITY_STOPPED, so the added rule never
 * fires and the report is main's.
 *
 * **The report only.** The engine ([ForegroundTracker], [UsageForegroundState])
 * keeps its own rule, where a stop ends the class. That rule is coarser but
 * safe there, for two reasons:
 * - the engine de-duplicates its overlapping polls by (timestamp, type,
 *   package, class), which counters cannot survive;
 * - a stale entry would draw a phantom shield over whatever is really in
 *   front, and in an enforcement path a missed shield is the safe failure.
 *
 * The report reads one query, with every event once, so it can count.
 * Immutable and pure, unit-tested on the JVM.
 */
internal data class ActivityInstances(
  val resumed: Int = 0,
  val owedStops: Int = 0,
  val closingPauses: Int = 0,
  val uncertain: Boolean = false,
) {
  val isEmpty: Boolean
    get() = resumed == 0 && owedStops == 0 && closingPauses == 0 && !uncertain

  /** ACTIVITY_RESUMED: one more resumed instance; a second at once makes the counts [uncertain]. */
  fun resume(): ActivityInstances =
    copy(resumed = resumed + 1, closingPauses = 0, uncertain = uncertain || resumed >= 1)

  /**
   * ACTIVITY_PAUSED. Returns the next state and whether it ended the class
   * (main's rule: a pause ends it whatever was resumed). It owes a stop,
   * unless it is the pause of an instance a closing already counted.
   */
  fun pause(): Pair<ActivityInstances, Boolean> {
    val countedAtClosing = resumed == 0 && closingPauses > 0
    return copy(
      resumed = 0,
      owedStops = if (countedAtClosing) owedStops else owedStops + 1,
      closingPauses = if (countedAtClosing) closingPauses - 1 else closingPauses,
    ) to (resumed > 0)
  }

  /**
   * ACTIVITY_STOPPED. Returns the next state and whether it ended the class:
   * only a stop with nothing owed, one instance resumed and no uncertainty,
   * which can only be that instance dying. A stop that pays an owed one while
   * an instance is resumed may have been that instance's death instead, so
   * the counts become [uncertain].
   */
  fun stop(): Pair<ActivityInstances, Boolean> = when {
    owedStops > 0 -> copy(owedStops = owedStops - 1, uncertain = uncertain || resumed > 0) to false
    resumed == 1 && !uncertain -> copy(resumed = 0) to true
    else -> this to false
  }

  /**
   * The screen went off or locked: the class's span ends (the caller closes
   * it), and each resumed instance owes the stop that is coming, whether
   * before the next resume or, for an old instance relaunched after the
   * screen-on, after it. Idempotent.
   */
  fun closed(): ActivityInstances =
    copy(resumed = 0, owedStops = owedStops + resumed, closingPauses = closingPauses + resumed)

  companion object {
    val NONE = ActivityInstances()
  }
}
