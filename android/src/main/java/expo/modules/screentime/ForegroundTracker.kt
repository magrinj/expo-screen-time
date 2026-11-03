package expo.modules.screentime

/**
 * What is on screen, reconstructed from UsageStats activity events
 * (`ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED`; `DEVICE_SHUTDOWN`
 * and `DEVICE_STARTUP` clear it). The engine
 * feeds it every event of every poll, oldest first, and reads back:
 *
 * - [resumed]: the packages with a resumed activity. A set, not a single
 *   app: in multi-window every pane is resumed.
 * - [visible]: the packages with an activity that is resumed or paused but
 *   not stopped. A paused, unstopped activity is still on screen: in
 *   picture-in-picture, or under a translucent window. The shield covering
 *   an app fullscreen stops it; a PiP window never gets stopped, which is
 *   how the engine tells the two apart.
 *
 * Events are de-duplicated by the caller (polls overlap on purpose). Pure:
 * event types are the `UsageEvents.Event` integer constants, so the tracker
 * is unit-tested on the JVM.
 *
 * [tracksStopped] is false below API 29, where `ACTIVITY_STOPPED` does not
 * exist (only the foreground/background pair): a paused activity is then
 * taken as gone at once, or it would stay "visible" for ever and the
 * standing check would shield every app ever backgrounded. The price is
 * that picture-in-picture cannot be told apart from leaving on 24–28.
 */
internal class ForegroundTracker(private val tracksStopped: Boolean = true) {
  private val resumedActivities = HashSet<String>()
  private val pausedActivities = HashSet<String>()
  /** When each paused activity was paused (the event's timestamp), for [visibleAt]. */
  private val pausedAt = HashMap<String, Long>()

  val resumed: Set<String>
    get() = resumedActivities.mapTo(LinkedHashSet()) { it.substringBefore('/') }

  val visible: Set<String>
    get() = (resumedActivities + pausedActivities).mapTo(LinkedHashSet()) { it.substringBefore('/') }

  /**
   * [visible], counting a paused activity only once its pause is at least
   * [settleMs] old. An app switch pauses the app that leaves and stops it a
   * few hundred milliseconds later, once the new window is drawn; a
   * picture-in-picture window stays paused. Read in that gap, a leaving app
   * looks like a PiP window: the engine, starting at that instant (the
   * host app relaunched after a force stop), shielded it over the app the
   * user had just opened. The standing check reads this view.
   */
  fun visibleAt(nowMs: Long, settleMs: Long): Set<String> {
    val settled = pausedActivities.filter { (pausedAt[it] ?: Long.MIN_VALUE) <= nowMs - settleMs }
    return (resumedActivities + settled).mapTo(LinkedHashSet()) { it.substringBefore('/') }
  }

  /**
   * Applies one event. Returns the package when the event is a resume of a
   * package that had nothing resumed before (a new app came forward), null
   * otherwise.
   */
  fun apply(eventType: Int, packageName: String, className: String?, atMs: Long = 0L): String? {
    val activity = "$packageName/${className ?: ""}"
    return when (eventType) {
      ACTIVITY_RESUMED -> {
        val wasForeground = packageName in resumed
        pausedActivities.remove(activity)
        pausedAt.remove(activity)
        resumedActivities.add(activity)
        if (wasForeground) null else packageName
      }
      ACTIVITY_PAUSED -> {
        if (resumedActivities.remove(activity) && tracksStopped) {
          pausedActivities.add(activity)
          pausedAt[activity] = atMs
        }
        null
      }
      ACTIVITY_STOPPED, ACTIVITY_DESTROYED -> {
        resumedActivities.remove(activity)
        pausedActivities.remove(activity)
        pausedAt.remove(activity)
        null
      }
      // Nothing stays on screen across a shutdown or a boot. Without this, an
      // app whose last event before a reboot was a PAUSED (the shield over
      // it while the system shut down) read as a picture-in-picture window
      // after the boot, and the standing check shielded it over whatever the
      // user opened.
      DEVICE_SHUTDOWN, DEVICE_STARTUP -> {
        reset()
        null
      }
      else -> null
    }
  }

  /** Forgets everything: the query window restarted (a clock step, the screen coming back on). */
  fun reset() {
    resumedActivities.clear()
    pausedActivities.clear()
    pausedAt.clear()
  }

  companion object {
    // `android.app.usage.UsageEvents.Event` constants, repeated so this
    // file compiles without Android.
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val ACTIVITY_STOPPED = 23
    const val ACTIVITY_DESTROYED = 24
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27
  }
}
