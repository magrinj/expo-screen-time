package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject

/**
 * The UsageStats event kinds the engine sees, with the
 * `UsageEvents.Event` constants the wiring maps from. No Android import:
 * the values are the platform's documented constants.
 */
internal enum class UsageEventKind(val usageEventsType: Int) {
  ACTIVITY_RESUMED(1),
  ACTIVITY_PAUSED(2),
  ACTIVITY_STOPPED(23),
  SCREEN_INTERACTIVE(15),
  SCREEN_NON_INTERACTIVE(16),
  KEYGUARD_SHOWN(17),
  KEYGUARD_HIDDEN(18),
  DEVICE_SHUTDOWN(26),
  DEVICE_STARTUP(27),
  OTHER(-1);

  /** Whether the kind closes every foreground span: nothing is in use on a dark or locked screen. */
  val closesAll: Boolean
    get() = this == SCREEN_NON_INTERACTIVE || this == KEYGUARD_SHOWN || this == DEVICE_SHUTDOWN

  companion object {
    fun of(usageEventsType: Int): UsageEventKind = values().firstOrNull { it.usageEventsType == usageEventsType } ?: OTHER
  }
}

/**
 * One UsageStats event: when, which package (null for device-level kinds),
 * which activity class (`Event.getClassName()`; null when unknown), what.
 * The class matters: moving between screens of one app emits
 * paused(A), resumed(B), stopped(A), and the late stop must not count as
 * the app leaving the foreground.
 */
internal data class UsageEvent(val timestampMs: Long, val packageName: String?, val kind: UsageEventKind, val className: String? = null)

/**
 * The activities currently resumed, per package, as the UsageStats stream
 * says, and how far that stream was consumed. Multi-window keeps several
 * activities resumed at once, so this is a set of
 * activities, not a single package. A package is in the foreground while
 * at least one of its activities is resumed.
 *
 * Events at or before `cursorMs` are ignored: a window queried twice (a
 * process restart re-queries from the persisted cursor) changes nothing.
 * The cursor is wall-clock, like the stream: when the clock is set, the
 * system renames its stats files by the delta and stores events relative
 * to them, so the stream reads back on the new clock — `shifted` moves the
 * cursor the same way.
 */
internal data class UsageForegroundState(
  /** Package → the keys of its resumed activities (the class name, or "" when unknown). */
  val resumed: Map<String, Set<String>> = emptyMap(),
  /** The instant up to which the stream was fed; the wiring queries the next window from here. */
  val cursorMs: Long? = null,
) {
  val resumedPackages: Set<String>
    get() = resumed.keys

  fun isResumed(packageName: String): Boolean = packageName in resumed

  fun accepts(event: UsageEvent): Boolean = cursorMs == null || event.timestampMs > cursorMs

  /**
   * Applies one stream event. Returns the tracker and the PACKAGE-level
   * change it implies, if any: a device-level close, or the package
   * entering or leaving the foreground (an activity swap inside an app is
   * no change). Accumulators consume the change, never the raw event.
   */
  fun apply(event: UsageEvent): Pair<UsageForegroundState, UsageEvent?> {
    val cursor = maxOf(cursorMs ?: event.timestampMs, event.timestampMs)
    if (event.kind.closesAll) return UsageForegroundState(emptyMap(), cursor) to event
    val packageName = event.packageName ?: return copy(cursorMs = cursor) to null
    val key = event.className ?: ""
    val keys = resumed[packageName] ?: emptySet()
    val next = when (event.kind) {
      UsageEventKind.ACTIVITY_RESUMED -> keys + key
      UsageEventKind.ACTIVITY_PAUSED, UsageEventKind.ACTIVITY_STOPPED -> keys - key
      else -> keys
    }
    val tracker = UsageForegroundState(
      if (next.isEmpty()) resumed - packageName else resumed + (packageName to next),
      cursor,
    )
    val change = when {
      keys.isEmpty() && next.isNotEmpty() -> UsageEvent(event.timestampMs, packageName, UsageEventKind.ACTIVITY_RESUMED)
      keys.isNotEmpty() && next.isEmpty() -> UsageEvent(event.timestampMs, packageName, UsageEventKind.ACTIVITY_PAUSED)
      else -> null
    }
    return tracker to change
  }

  fun advanceTo(uptoMs: Long): UsageForegroundState = copy(cursorMs = maxOf(cursorMs ?: uptoMs, uptoMs))

  /** The clock was set by `deltaMs` (negative when set back): the stream now reads on the new clock, so does the cursor. */
  fun shifted(deltaMs: Long): UsageForegroundState = copy(cursorMs = cursorMs?.plus(deltaMs))

  fun toJson(): JSONObject = JSONObject()
    .put(RESUMED, JSONObject().also { json -> resumed.toSortedMap().forEach { (pkg, keys) -> json.put(pkg, JSONArray(keys.sorted())) } })
    .putOpt(CURSOR, cursorMs)

  companion object {
    private const val RESUMED = "resumed"
    private const val CURSOR = "cursor"

    fun fromJson(json: JSONObject?): UsageForegroundState {
      if (json == null) return UsageForegroundState()
      val resumed = LinkedHashMap<String, Set<String>>()
      json.optJSONObject(RESUMED)?.let { all ->
        for (pkg in all.keys()) {
          val array = all.optJSONArray(pkg) ?: continue
          val keys = LinkedHashSet<String>()
          for (i in 0 until array.length()) (array.opt(i) as? String)?.let(keys::add)
          if (keys.isNotEmpty()) resumed[pkg] = keys
        }
      }
      return UsageForegroundState(resumed, ConfigValues.long(json.opt(CURSOR)))
    }
  }
}

/**
 * Foreground time of one selection inside one occurrence, for one
 * threshold event — the Android measurement where iOS has a
 * `DeviceActivityEvent`. No ladder: Android reads the stream, so the count
 * is exact to the tick.
 *
 * The rule for overlapping apps: **the selection is in use while at least
 * one of its apps is in the foreground, and an instant counts once**
 * however many of its apps share the screen. Two selected apps side by
 * side for a minute add one minute (the union of their spans), never two;
 * a selected app next to an unselected one counts fully. That is what a
 * usage budget means to a person, and it keeps the count under the wall
 * clock, the plausibility bound the iOS meter enforces
 * (`UsageMeter.isPlausible`).
 *
 * Counting is bounded to `[countFromMs, countUntilMs]`: from the
 * occurrence start (`includesPastActivity`, the default) or the
 * registration, until the occurrence ends — usage observed late, after
 * the end, is not the occurrence's. An open span is folded into
 * `accumulatedMs` at every `settle`, so what is persisted needs no
 * re-derivation, and the stream the tracker replays from its cursor
 * re-creates any span the process lost.
 *
 * An accumulator is armed at the tracker's cursor, the instant the
 * resumed set is exact for; what happened in `[countFromMs, cursor)` is a
 * history request the wiring answers from `UsageStatsManager` and
 * `backfill` adds once. The stream then continues from the cursor, so
 * history and stream never overlap.
 */
internal data class ThresholdProgress(
  val countFromMs: Long,
  val countUntilMs: Long,
  /** Closed foreground time so far, within the counting bounds. */
  val accumulatedMs: Long = 0,
  /** Selected packages in the foreground right now. */
  val open: Set<String> = emptySet(),
  /** Since when at least one selected package is in the foreground (already folded up to the last settle). */
  val activeSinceMs: Long? = null,
  /** History still to add: usage in `[historyFromMs, historyToMs)` was before this accumulator existed. */
  val historyFromMs: Long? = null,
  val historyToMs: Long? = null,
  val fired: Boolean = false,
  val warned: Boolean = false,
) {
  val historyPending: Boolean
    get() = historyFromMs != null

  /** Applies one package-level change (`UsageForegroundState.apply`); `selected` is the selection's packages as of now (read live, as on iOS). */
  fun feed(change: UsageEvent, selected: Set<String>): ThresholdProgress {
    val at = change.timestampMs
    return when {
      change.kind.closesAll -> closeAll(at)
      change.kind == UsageEventKind.ACTIVITY_RESUMED && change.packageName != null && change.packageName in selected ->
        copy(open = open + change.packageName, activeSinceMs = activeSinceMs ?: at)
      change.kind == UsageEventKind.ACTIVITY_PAUSED && change.packageName in open -> {
        val remaining = open - change.packageName!!
        if (remaining.isEmpty()) closeAll(at) else copy(open = remaining)
      }
      else -> this
    }
  }

  private fun closeAll(at: Long): ThresholdProgress =
    copy(accumulatedMs = accumulatedMs + contribution(at), open = emptySet(), activeSinceMs = null)

  /** Folds the open span up to `at` (never beyond the counting bounds) and moves its start there. */
  fun settle(at: Long): ThresholdProgress {
    val since = activeSinceMs ?: return this
    if (at <= since) return this
    return copy(accumulatedMs = accumulatedMs + contribution(at), activeSinceMs = at)
  }

  /** The total if the open span ran until `at`: the live reading. */
  fun projectedMs(at: Long): Long = accumulatedMs + contribution(at)

  /** When the open span, if it stays open, reaches `targetMs` of use; null when nothing is in use or it is reached already. */
  fun reachesAt(targetMs: Long, nowMs: Long): Long? {
    if (activeSinceMs == null || nowMs >= countUntilMs) return null
    val remaining = targetMs - projectedMs(nowMs)
    if (remaining <= 0) return null
    val at = nowMs + remaining
    return if (at <= countUntilMs) at else null
  }

  /** Adds the history the wiring measured for the pending request; a second answer changes nothing. */
  fun backfill(fromMs: Long, toMs: Long, foregroundMs: Long): ThresholdProgress =
    if (historyFromMs != fromMs || historyToMs != toMs) this
    else copy(accumulatedMs = accumulatedMs + foregroundMs.coerceAtLeast(0), historyFromMs = null, historyToMs = null)

  /** The clock was set by `deltaMs`: the stream instants move, the occurrence bounds (re-evaluated from the schedule) do not. */
  fun shifted(deltaMs: Long): ThresholdProgress =
    copy(activeSinceMs = activeSinceMs?.plus(deltaMs), historyFromMs = historyFromMs?.plus(deltaMs), historyToMs = historyToMs?.plus(deltaMs))

  private fun contribution(at: Long): Long {
    val since = activeSinceMs ?: return 0
    val from = maxOf(since, countFromMs)
    val until = minOf(at, countUntilMs)
    return (until - from).coerceAtLeast(0)
  }

  fun toJson(): JSONObject = JSONObject()
    .put(COUNT_FROM, countFromMs)
    .put(COUNT_UNTIL, countUntilMs)
    .put(ACCUMULATED, accumulatedMs)
    .put(OPEN, JSONArray(open.sorted()))
    .putOpt(ACTIVE_SINCE, activeSinceMs)
    .putOpt(HISTORY_FROM, historyFromMs)
    .putOpt(HISTORY_TO, historyToMs)
    .put(FIRED, fired)
    .put(WARNED, warned)

  companion object {
    private const val COUNT_FROM = "countFrom"
    private const val COUNT_UNTIL = "countUntil"
    private const val ACCUMULATED = "accumulated"
    private const val OPEN = "open"
    private const val ACTIVE_SINCE = "activeSince"
    private const val HISTORY_FROM = "historyFrom"
    private const val HISTORY_TO = "historyTo"
    private const val FIRED = "fired"
    private const val WARNED = "warned"

    /**
     * A fresh accumulator at `nowMs`, seeded from the tracker: its resumed
     * set is exact as of its cursor, so the live span starts there and the
     * stretch `[countFromMs, cursor)` becomes a history request. A tracker
     * that was never fed knows nothing: everything up to now is history and
     * the live span starts with the next event.
     */
    fun arm(countFromMs: Long, countUntilMs: Long, nowMs: Long, tracker: UsageForegroundState, selected: Set<String>): ThresholdProgress {
      val known = tracker.cursorMs?.let { minOf(it, nowMs) }
      val open = if (known == null) emptySet() else tracker.resumedPackages intersect selected
      val historyTo = known ?: nowMs
      return ThresholdProgress(
        countFromMs = countFromMs,
        countUntilMs = countUntilMs,
        open = open,
        activeSinceMs = if (open.isEmpty()) null else known,
        historyFromMs = if (countFromMs < historyTo) countFromMs else null,
        historyToMs = if (countFromMs < historyTo) historyTo else null,
      )
    }

    fun fromJson(json: JSONObject): ThresholdProgress? {
      val countFrom = ConfigValues.long(json.opt(COUNT_FROM)) ?: return null
      val countUntil = ConfigValues.long(json.opt(COUNT_UNTIL)) ?: return null
      val open = LinkedHashSet<String>()
      val array = json.optJSONArray(OPEN)
      if (array != null) for (i in 0 until array.length()) (array.opt(i) as? String)?.let(open::add)
      return ThresholdProgress(
        countFromMs = countFrom,
        countUntilMs = countUntil,
        accumulatedMs = ConfigValues.long(json.opt(ACCUMULATED)) ?: 0,
        open = open,
        activeSinceMs = ConfigValues.long(json.opt(ACTIVE_SINCE)),
        historyFromMs = ConfigValues.long(json.opt(HISTORY_FROM)),
        historyToMs = ConfigValues.long(json.opt(HISTORY_TO)),
        fired = json.optBoolean(FIRED, false),
        warned = json.optBoolean(WARNED, false),
      )
    }

    /**
     * Foreground time of `selected` within `[fromMs, toMs]` from a stream
     * queried for that window, the answer to a history request. Events
     * before `fromMs` are welcome: they establish what was already open at
     * `fromMs` (counting starts there regardless), so the wiring should
     * query from `fromMs` minus a lookback (an hour covers a long session).
     */
    fun foregroundMs(events: List<UsageEvent>, selected: Set<String>, fromMs: Long, toMs: Long): Long {
      var tracker = UsageForegroundState()
      var scratch = ThresholdProgress(countFromMs = fromMs, countUntilMs = toMs)
      for (event in events.sortedBy { it.timestampMs }) {
        if (event.timestampMs > toMs) continue
        val (next, change) = tracker.apply(event)
        tracker = next
        if (change != null) scratch = scratch.feed(change, selected)
      }
      return scratch.settle(toMs).accumulatedMs
    }
  }
}
