package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * A monitored activity as stored: its validated config, when it was
 * registered, and — for `repeats: false` — the one occurrence it covers:
 * the selected occurrence in progress at registration, else the next one
 * (a one-off starting now applies at once). After that
 * occurrence the activity stays listed by `getMonitoredActivities` until it
 * is stopped, as a one-off DeviceActivity stays registered on iOS, but
 * never blocks again.
 */
internal data class MonitoredActivity(
  val config: MonitoringConfig,
  val registeredAtMs: Long,
  /** The single occurrence's start for a non-repeating schedule; null when repeating or when none is left. */
  val onceStartMs: Long? = null,
) {
  val name: String
    get() = config.activityName

  val rule: ScheduleRule
    get() = config.rule

  /** The selected occurrence in progress at `nowMs`, if any. */
  fun occurrenceAt(nowMs: Long, zone: TimeZone): Occurrence? {
    if (config.schedule.repeats) return rule.selectedOccurrence(nowMs, zone)
    val start = onceStartMs ?: return null
    return Occurrence(start, rule.end(start, zone)).takeIf { nowMs in it }
  }

  /** The next instant at which `occurrenceAt` changes; null when nothing is left (a spent one-off). */
  fun nextTransition(nowMs: Long, zone: TimeZone): Long? {
    if (config.schedule.repeats) return rule.nextTransition(nowMs, zone)
    val start = onceStartMs ?: return null
    if (nowMs < start) return start
    val end = rule.end(start, zone)
    return if (nowMs < end) end else null
  }

  fun toJson(): JSONObject = JSONObject()
    .put(CONFIG, config.toJson())
    .put(REGISTERED_AT, registeredAtMs)
    .putOpt(ONCE_START, onceStartMs)

  companion object {
    private const val CONFIG = "config"
    private const val REGISTERED_AT = "registeredAt"
    private const val ONCE_START = "onceStart"

    fun register(config: MonitoringConfig, nowMs: Long, zone: TimeZone): MonitoredActivity {
      val once = if (config.schedule.repeats) null else {
        config.rule.selectedOccurrence(nowMs, zone)?.startMs ?: config.rule.nextSelectedStart(nowMs, zone)
      }
      return MonitoredActivity(config, nowMs, once)
    }

    fun fromJson(json: JSONObject): MonitoredActivity? {
      val config = MonitoringConfig.fromJson(json.opt(CONFIG)) ?: return null
      val registeredAt = ConfigValues.long(json.opt(REGISTERED_AT)) ?: return null
      return MonitoredActivity(config, registeredAt, ConfigValues.long(json.opt(ONCE_START)))
    }
  }
}

/**
 * An interval in progress: the marker iOS keeps as `expo_st_active_interval_*`,
 * with the occurrence it belongs to and the thresholds armed inside it. It
 * exists exactly between the `intervalDidStart` and `intervalDidEnd` the
 * state recorded, which is what makes those events exactly once: the
 * decision "is there a marker" and the append happen in the same update.
 */
internal data class ActiveInterval(
  /** When the interval was marked: the `intervalDidStart` timestamp. */
  val startedAtMs: Long,
  val occurrence: Occurrence,
  val warningIssued: Boolean = false,
  /** By event name. */
  val thresholds: Map<String, ThresholdProgress> = emptyMap(),
  /**
   * `unblockAll` lifted the schedule's shields: they come back at the next
   * interval start (the iOS `ActiveInterval.lifted` marker). The interval
   * stays in progress — its end is still recorded — and a re-registration
   * inside the same occurrence un-lifts it (iOS `intervalPlan` sets the
   * marker afresh, "not lifted").
   */
  val lifted: Boolean = false,
  /**
   * The `blockOnThreshold` events whose shields `unblockAll` lifted (iOS
   * drops the fired threshold selections). A threshold firing afterwards in
   * the same occurrence shields again, as on iOS.
   */
  val liftedThresholds: Set<String> = emptySet(),
) {
  /**
   * Whether this marker is the occurrence `current` describes: the bounds
   * overlap AND the marker was written at or after the occurrence's start
   * — the iOS rule (`DailySchedule.isSameOccurrence`). Overlap alone is
   * not enough: a zone change at the right moment makes two consecutive
   * 24 h occurrences overlap in real time, and the second must not inherit
   * the first's marker and fired thresholds. A zone change that moves the
   * bounds of the interval in progress still passes (its marker is inside
   * the re-evaluated occurrence).
   */
  fun belongsTo(current: Occurrence): Boolean = occurrence.overlaps(current) && startedAtMs >= current.startMs

  /**
   * The clock was set by `deltaMs`: the marker instant and the stream
   * instants move; the occurrence bounds, derived from the schedule, do
   * not. A counter that starts at the registration (`includesPastActivity`
   * false) follows the registration, which moved too.
   */
  fun shifted(deltaMs: Long, activity: MonitoredActivity): ActiveInterval = copy(
    startedAtMs = startedAtMs + deltaMs,
    thresholds = thresholds.mapValues { (eventName, progress) ->
      val event = activity.config.events.firstOrNull { it.eventName == eventName }
      val countFrom = if (event == null || event.includesPastActivity) progress.countFromMs else maxOf(occurrence.startMs, activity.registeredAtMs + deltaMs)
      progress.shifted(deltaMs).copy(countFromMs = countFrom)
    },
  )

  /** `unblockAll`: the schedule's shields and every fired threshold's are lifted until the next interval start. */
  fun lift(): ActiveInterval = copy(lifted = true, liftedThresholds = liftedThresholds + thresholds.filterValues { it.fired }.keys)

  fun toJson(): JSONObject = JSONObject()
    .put(STARTED_AT, startedAtMs)
    .put(OCCURRENCE_START, occurrence.startMs)
    .put(OCCURRENCE_END, occurrence.endMs)
    .put(WARNING_ISSUED, warningIssued)
    .put(THRESHOLDS, JSONObject().also { json -> thresholds.forEach { (name, progress) -> json.put(name, progress.toJson()) } })
    .put(LIFTED, lifted)
    .put(LIFTED_THRESHOLDS, JSONArray(liftedThresholds.sorted()))

  companion object {
    private const val STARTED_AT = "startedAt"
    private const val OCCURRENCE_START = "occurrenceStart"
    private const val OCCURRENCE_END = "occurrenceEnd"
    private const val WARNING_ISSUED = "warningIssued"
    private const val THRESHOLDS = "thresholds"
    private const val LIFTED = "lifted"
    private const val LIFTED_THRESHOLDS = "liftedThresholds"

    fun fromJson(json: JSONObject): ActiveInterval? {
      val startedAt = ConfigValues.long(json.opt(STARTED_AT)) ?: return null
      val start = ConfigValues.long(json.opt(OCCURRENCE_START)) ?: return null
      val end = ConfigValues.long(json.opt(OCCURRENCE_END)) ?: return null
      val thresholds = LinkedHashMap<String, ThresholdProgress>()
      val json2 = json.optJSONObject(THRESHOLDS)
      if (json2 != null) for (name in json2.keys()) json2.optJSONObject(name)?.let(ThresholdProgress::fromJson)?.let { thresholds[name] = it }
      val lifted = LinkedHashSet<String>()
      json.optJSONArray(LIFTED_THRESHOLDS)?.let { array -> for (i in 0 until array.length()) (array.opt(i) as? String)?.let(lifted::add) }
      return ActiveInterval(startedAt, Occurrence(start, end), json.optBoolean(WARNING_ISSUED, false), thresholds, json.optBoolean(LIFTED, false), lifted)
    }
  }
}

/**
 * What `startMonitoring` does to the activity's interval state, the
 * Android reading of `ActivityRegistration.intervalPlan` (no `restoring`:
 * a validated registration cannot fail here, so there is no rollback).
 *
 * - Inside an occurrence (`current` set) that the previous marker belongs
 *   to: the occurrence keeps its start — the shields never drop, no
 *   `intervalDidEnd`/`intervalDidStart` pair — and the thresholds are
 *   re-armed. Any other marker is a previous occurrence whose end was
 *   missed: it ends here and the new one starts now.
 * - Outside, or on an unselected weekday: the previous interval, if any, ends here.
 */
internal data class IntervalPlan(
  val marker: Marker,
  /** Always false on a registration: a fresh registration re-arms its thresholds. */
  val keepsThresholds: Boolean,
  val recordsStart: Boolean,
  val recordsEnd: Boolean,
) {
  sealed class Marker {
    /** Mark the interval in progress since `startedAtMs`. */
    data class Set(val startedAtMs: Long) : Marker()

    /** No interval in progress. */
    object Clear : Marker()
  }

  companion object {
    fun forRegistration(current: Occurrence?, previous: ActiveInterval?, nowMs: Long): IntervalPlan {
      if (current == null) return IntervalPlan(Marker.Clear, keepsThresholds = false, recordsStart = false, recordsEnd = previous != null)
      if (previous != null && previous.belongsTo(current)) {
        return IntervalPlan(Marker.Set(previous.startedAtMs), keepsThresholds = false, recordsStart = false, recordsEnd = false)
      }
      return IntervalPlan(Marker.Set(nowMs), keepsThresholds = false, recordsStart = true, recordsEnd = previous != null)
    }
  }
}

/** A block source the monitoring state contributes right now, for the block composition ([BlockComposition]) to apply. */
internal data class MonitoringBlock(
  val activityName: String,
  val selectionId: String,
  /** `blocklist` or `allowlist` for the schedule's own block; thresholds always `blocklist`. */
  val mode: String,
  /** The `eventName` of a `blockOnThreshold` block; null for the schedule's block. */
  val eventName: String? = null,
  /** When the interval it belongs to started (`intervalDidStart`): among schedules the latest owns a shared target. */
  val startedAtMs: Long = 0,
)

/** Usage before an accumulator existed that the wiring must measure (`UsageStatsManager.queryEvents(fromMs, toMs)`) and hand to `backfill`. */
internal data class HistoryRequest(val activityName: String, val eventName: String, val selectionId: String, val fromMs: Long, val toMs: Long)

/**
 * The monitoring layer as one immutable value — activities, the intervals
 * in progress, the event log, the foreground stream position — and the
 * pure transitions on it. The Android counterpart of what the iOS module
 * and the monitor extension keep in the App Group, minus everything
 * DeviceActivity does for iOS: here the schedule is evaluated by
 * `advance`, at every wake and every foreground change.
 *
 * **The seam.** Every transition is `state -> Result(state, recorded, …)`.
 * The wiring runs it inside `StateStore.update`, read-modify-write:
 *
 * ```
 * val result = store.update { doc ->
 *   val r = MonitoringDocument.read(doc).advance(now, zone, usage, uptoMs, selections)
 *   MonitoringDocument.write(doc, r.state); r
 * }
 * // then, and only then: emit r.recorded to JS, apply r.state.activeBlocks(), arm r.nextWakeMs
 * ```
 *
 * The marker that justifies an event and the event itself are written in
 * the same document update, under the store's exclusive cross-process
 * lock, replaced by atomic rename. Two processes recording the same
 * transition (the module's `start` inside an occurrence, the engine's
 * `advance` a moment later) serialize on the lock: the second reads the
 * first's marker and records nothing. A process dying between a
 * transition and its record leaves the document as it was: the next
 * `advance` makes the transition again, once.
 *
 * Before the first `advance` the tracker knows nothing (`cursorMs` null):
 * the engine's first window should be a lookback (an hour) so the resumed
 * set is seeded; until then thresholds armed by `start` request history up
 * to now and count from the next event. History requests are answered
 * from `UsageStatsManager.queryEvents(fromMs − lookback, toMs)` and remain
 * listed by `pendingHistoryRequests` until answered, across restarts.
 *
 * Usage accounting runs per poll in memory (`advance` is cheap on a
 * value); the document is written when `Result.durable` says an event or
 * a marker changed, and otherwise at the engine's own cadence. `revision`
 * changes on every mutation the module makes (`start`, `stop`, `clear`),
 * so an engine holding a stale in-memory state can tell: it reloads the
 * document and re-queries UsageStats from `foreground.cursorMs`, which
 * re-creates exactly the spans it lost.
 */
internal data class MonitoringState(
  val activities: Map<String, MonitoredActivity> = emptyMap(),
  val intervals: Map<String, ActiveInterval> = emptyMap(),
  val log: MonitorEventLog = MonitorEventLog(),
  val foreground: UsageForegroundState = UsageForegroundState(),
  /** Bumped by the module's mutations; the engine compares it before writing a state it advanced in memory. */
  val revision: Long = 0,
) {
  /** A transition's outcome. */
  data class Result(
    val state: MonitoringState,
    /** Appended by this transition, in order (an end before a start). */
    val recorded: List<MonitorEvent> = emptyList(),
    /** Usage to measure from history and hand back through `backfill`. */
    val historyRequests: List<HistoryRequest> = emptyList(),
    /** Something other than usage totals changed (a backfill landed, a marker moved). */
    val changed: Boolean = false,
  ) {
    /** Whether the document must be written before anything acts on the result. */
    val durable: Boolean
      get() = recorded.isNotEmpty() || historyRequests.isNotEmpty() || changed
  }

  /** `getMonitoredActivities()`. */
  fun monitoredActivities(): List<String> = activities.keys.sorted()

  /**
   * `startMonitoring`, after validation: register (or re-register) the
   * activity, and apply the interval plan for now (inside an
   * occurrence, the block applies at once and one `intervalDidStart` is
   * recorded). `selections` resolves the packages a threshold meters.
   */
  fun start(config: MonitoringConfig, nowMs: Long, zone: TimeZone, selections: (String) -> Selection?): Result {
    val activity = MonitoredActivity.register(config, nowMs, zone)
    val name = activity.name
    val previous = intervals[name]
    val current = activity.occurrenceAt(nowMs, zone)
    val plan = IntervalPlan.forRegistration(current, previous, nowMs)

    var log = this.log
    val recorded = ArrayList<MonitorEvent>()
    val requests = ArrayList<HistoryRequest>()
    if (plan.recordsEnd) log = log.append(name, MonitorEvent.INTERVAL_DID_END, null, nowMs).also { recorded.add(it.second) }.first
    if (plan.recordsStart) log = log.append(name, MonitorEvent.INTERVAL_DID_START, null, nowMs).also { recorded.add(it.second) }.first

    val intervals = LinkedHashMap(this.intervals)
    when (val marker = plan.marker) {
      is IntervalPlan.Marker.Clear -> intervals.remove(name)
      is IntervalPlan.Marker.Set -> {
        val occurrence = current!!
        // A re-registration re-arms the thresholds: fresh accumulators, the warning kept only for the same occurrence.
        val thresholds = armThresholds(activity, occurrence, nowMs, selections, requests)
        val warningIssued = previous?.takeIf { it.belongsTo(occurrence) }?.warningIssued ?: false
        intervals[name] = ActiveInterval(marker.startedAtMs, occurrence, warningIssued, thresholds)
      }
    }

    val next = copy(
      activities = activities + (name to activity),
      intervals = intervals,
      log = log,
      revision = revision + 1,
    )
    // A threshold already met (0 s, or met before the restart) fires now.
    val evaluated = next.evaluate(name, nowMs)
    return Result(evaluated.state, recorded + evaluated.recorded, requests)
  }

  /** `stopMonitoring`: the activity's `intervalDidEnd` if an interval was in progress, then every trace of it. Unknown names are a no-op. */
  fun stop(activityName: String, nowMs: Long): Result {
    if (activityName !in activities && activityName !in intervals) return Result(this)
    var log = this.log
    val recorded = ArrayList<MonitorEvent>()
    if (activityName in intervals) log = log.append(activityName, MonitorEvent.INTERVAL_DID_END, null, nowMs).also { recorded.add(it.second) }.first
    val remaining = activities - activityName
    // Nothing monitored: the stream is not read any more, so the tracker
    // must not claim to know the resumed set (the next registration seeds afresh).
    val foreground = if (remaining.isEmpty()) UsageForegroundState() else this.foreground
    return Result(copy(activities = remaining, intervals = intervals - activityName, log = log, foreground = foreground, revision = revision + 1), recorded)
  }

  /** `stopAllMonitoring`: every activity, in name order. Manual blocks and unlocks are other layers' and stay. */
  fun stopAll(nowMs: Long): Result {
    var state = this
    val recorded = ArrayList<MonitorEvent>()
    for (name in (activities.keys + intervals.keys).sorted()) {
      val result = state.stop(name, nowMs)
      state = result.state
      recorded.addAll(result.recorded)
    }
    return Result(state, recorded)
  }

  /**
   * The engine's first contact with the stream, when the tracker was never
   * fed (`cursorMs` null): the resumed set is rebuilt from a lookback
   * window of events (an hour) WITHOUT counting any of it as live usage,
   * and every threshold armed meanwhile — by the module, against a tracker
   * that knew nothing: history up to its `now`, no open span — is armed
   * again against the seeded tracker: its open span starts at `uptoMs` for
   * the selected apps on screen, and its history request covers
   * `[countFrom, uptoMs)`, which the wiring answers from the same window.
   * Feeding the lookback through `advance` instead would open spans at the
   * historical instants and count them a second time beside the history
   * answer. A tracker with a cursor is left alone (nothing to seed).
   */
  fun seed(usage: List<UsageEvent>, uptoMs: Long, selections: (String) -> Selection?): MonitoringState {
    if (foreground.cursorMs != null) return this
    var tracker = UsageForegroundState()
    for (event in usage.sortedBy { it.timestampMs }) {
      if (event.timestampMs > uptoMs) continue
      tracker = tracker.apply(event).first
    }
    tracker = tracker.advanceTo(uptoMs)
    val intervals = intervals.mapValues { (name, interval) ->
      val activity = activities[name] ?: return@mapValues interval
      interval.copy(
        thresholds = interval.thresholds.mapValues { (eventName, progress) ->
          val event = activity.config.events.firstOrNull { it.eventName == eventName } ?: return@mapValues progress
          if (progress.fired || progress.accumulatedMs > 0 || progress.activeSinceMs != null) return@mapValues progress
          val selected = selections(event.selectionId)?.applications ?: emptySet()
          ThresholdProgress.arm(progress.countFromMs, progress.countUntilMs, uptoMs, tracker, selected).copy(warned = progress.warned)
        },
      )
    }
    return copy(intervals = intervals, foreground = tracker)
  }

  /** `getMonitorEvents`. */
  fun events(activityName: String?, limit: Int = MonitorEventLog.DEFAULT_LIMIT): List<MonitorEvent> = log.events(activityName, limit)

  /** `clearMonitorEvents`. */
  fun clearEvents(activityName: String?): MonitoringState = copy(log = log.clear(activityName), revision = revision + 1)

  /**
   * One engine step: feed the foreground stream, then evaluate every
   * schedule at `nowMs` — intervals that ended, intervals that started,
   * warnings and thresholds due — recording each transition once.
   *
   * The stream protocol: the wiring queries `UsageStatsManager` from
   * `foreground.cursorMs` (inclusive; from the earliest occurrence start
   * when null) to now, and passes `uptoMs` = the instant every event at or
   * before it is known to be visible — now minus the pipeline lag (1–3 ms
   * measured on a Pixel 8a, 2 s allowed). Events after
   * `uptoMs`, or at or before the cursor, are not fed: the former come back
   * in the next window, the latter were fed already. The cursor then moves
   * to `uptoMs`. A window queried twice (a restart re-queries from the
   * persisted cursor) therefore changes nothing, and no event is fed twice.
   *
   * Occurrences that came and went while nothing observed them (the device
   * off for days) record nothing: they blocked nothing, and both platforms
   * observe a boundary at the first use after it. A clock set backwards
   * ends an interval now outside the schedule and lets it start again when
   * the clock reaches it: the schedule is a function of time, not of
   * history. A zone change re-evaluates the bounds of the interval in
   * progress and keeps it in progress.
   *
   * A clock set back shows as `uptoMs` behind the cursor: the state is
   * then shifted by that difference (`clockShifted`), the least the clock
   * moved. The wiring knows the exact delta (the drift of wall clock minus
   * `elapsedRealtime` at `ACTION_TIME_CHANGED`) and should call
   * `clockShifted` with it first, so forward steps are covered too.
   */
  fun advance(
    nowMs: Long,
    zone: TimeZone,
    usage: List<UsageEvent> = emptyList(),
    uptoMs: Long = nowMs,
    selections: (String) -> Selection?,
  ): Result {
    // Nothing monitored, nothing in progress: the stream is not read, so the
    // cursor must not move (a resumed set "exact as of the cursor" would go
    // stale) — the tracker forgets, and the next registration seeds afresh.
    if (activities.isEmpty() && intervals.isEmpty()) {
      return Result(if (foreground == UsageForegroundState()) this else copy(foreground = UsageForegroundState()))
    }
    val cursor = foreground.cursorMs
    var state = if (cursor != null && uptoMs < cursor) clockShifted(uptoMs - cursor) else this
    val shifted = state !== this
    state = state.feed(usage, uptoMs, selections)
    val recorded = ArrayList<MonitorEvent>()
    val requests = ArrayList<HistoryRequest>()

    for (name in state.activities.keys.sorted()) {
      val activity = state.activities.getValue(name)
      val previous = state.intervals[name]
      val current = activity.occurrenceAt(nowMs, zone)
      var log = state.log
      val intervals = LinkedHashMap(state.intervals)

      if (previous != null && current != null && previous.belongsTo(current)) {
        if (previous.occurrence != current) {
          intervals[name] = previous.copy(
            occurrence = current,
            thresholds = previous.thresholds.mapValues { (_, progress) -> progress.copy(countUntilMs = current.endMs) },
          )
        }
      } else {
        if (previous != null) {
          // A threshold reached inside the occurrence but first seen now (the
          // pass came after the end) still fires, before the end is recorded.
          val reached = state.evaluate(name, nowMs, includeWarnings = false)
          state = reached.state
          recorded.addAll(reached.recorded)
          log = state.log
          log = log.append(name, MonitorEvent.INTERVAL_DID_END, null, nowMs).also { recorded.add(it.second) }.first
          intervals.remove(name)
        }
        if (current != null) {
          log = log.append(name, MonitorEvent.INTERVAL_DID_START, null, nowMs).also { recorded.add(it.second) }.first
          val thresholds = state.armThresholds(activity, current, nowMs, selections, requests)
          intervals[name] = ActiveInterval(nowMs, current, thresholds = thresholds)
        }
      }
      state = state.copy(intervals = intervals, log = log)
      val evaluated = state.evaluate(name, nowMs)
      state = evaluated.state
      recorded.addAll(evaluated.recorded)
    }
    // Intervals whose activity is gone (a malformed stored config): end them.
    for (name in (state.intervals.keys - state.activities.keys).sorted()) {
      val (log, event) = state.log.append(name, MonitorEvent.INTERVAL_DID_END, null, nowMs)
      recorded.add(event)
      state = state.copy(intervals = state.intervals - name, log = log)
    }
    return Result(state, recorded, requests, changed = shifted)
  }

  /**
   * The state as the schedules stand at `nowMs`, for a read-only answer
   * (`isBlocked`): the interval boundaries are evaluated like `advance`
   * does, nothing is fed, and the thresholds stay exactly as the engine
   * persisted them — an open usage span is not projected to now (the stream
   * that would close it is the engine's to read), so a threshold the engine
   * has not fired does not fire here. Nothing is persisted by the caller.
   */
  fun schedulesAt(nowMs: Long, zone: TimeZone, selections: (String) -> Selection?): MonitoringState {
    val cursor = foreground.cursorMs ?: nowMs
    val dry = advance(nowMs, zone, emptyList(), minOf(cursor, nowMs), selections).state
    return dry.copy(
      intervals = dry.intervals.mapValues { (name, interval) ->
        val stored = intervals[name]
        // The occurrence the engine knows keeps its thresholds; one it has
        // not seen yet has fired nothing (its accumulators would be armed
        // over a tracker the engine has not caught up with).
        if (stored != null && stored.belongsTo(interval.occurrence)) interval.copy(thresholds = stored.thresholds, liftedThresholds = stored.liftedThresholds) else interval.copy(thresholds = emptyMap())
      },
      foreground = foreground,
    )
  }

  /**
   * The clock was set by `deltaMs`. The stream and its cursor read on the
   * new clock (the system renames its stats files by the delta and stores
   * events relative to them), so every stream instant held here moves with
   * it, and so does the registration instant; the occurrence bounds do
   * not, they come from the schedule and the next `advance` re-evaluates
   * them.
   */
  fun clockShifted(deltaMs: Long): MonitoringState =
    if (deltaMs == 0L) this else copy(
      activities = activities.mapValues { (_, activity) -> activity.copy(registeredAtMs = activity.registeredAtMs + deltaMs) },
      intervals = intervals.mapValues { (name, interval) -> activities[name]?.let { interval.shifted(deltaMs, it) } ?: interval },
      foreground = foreground.shifted(deltaMs),
    )

  /** History requests still unanswered — after a process death, the wiring answers these before trusting the totals. */
  fun pendingHistoryRequests(): List<HistoryRequest> {
    val result = ArrayList<HistoryRequest>()
    for (name in intervals.keys.sorted()) {
      val activity = activities[name] ?: continue
      for (event in activity.config.events) {
        val progress = intervals.getValue(name).thresholds[event.eventName] ?: continue
        val from = progress.historyFromMs ?: continue
        val to = progress.historyToMs ?: continue
        result.add(HistoryRequest(name, event.eventName, event.selectionId, from, to))
      }
    }
    return result
  }

  /** The answer to a history request: adds the measured usage once, then evaluates the threshold. */
  fun backfill(request: HistoryRequest, events: List<UsageEvent>, nowMs: Long, selections: (String) -> Selection?): Result {
    val interval = intervals[request.activityName] ?: return Result(this)
    val progress = interval.thresholds[request.eventName] ?: return Result(this)
    val selected = selections(request.selectionId)?.applications ?: emptySet()
    val measured = ThresholdProgress.foregroundMs(events, selected, request.fromMs, request.toMs)
    val updated = progress.backfill(request.fromMs, request.toMs, measured)
    if (updated === progress) return Result(this)
    val state = copy(intervals = intervals + (request.activityName to interval.copy(thresholds = interval.thresholds + (request.eventName to updated))))
    val evaluated = state.evaluate(request.activityName, nowMs)
    return evaluated.copy(changed = true)
  }

  /**
   * The blocks the intervals in progress contribute: each schedule's
   * selection, and every fired `blockOnThreshold` — minus those whose
   * selection no longer exists (iOS checks `SelectionStore.has` before it
   * shields a threshold's selection), and minus what `unblockAll` lifted
   * ([lift]) until the next interval start.
   */
  fun activeBlocks(selectionExists: (String) -> Boolean = { true }): List<MonitoringBlock> {
    val result = ArrayList<MonitoringBlock>()
    for (name in intervals.keys.sorted()) {
      val activity = activities[name] ?: continue
      val interval = intervals.getValue(name)
      if (!interval.lifted) {
        activity.config.selectionId?.takeIf(selectionExists)?.let { result.add(MonitoringBlock(name, it, activity.config.mode, null, interval.startedAtMs)) }
      }
      for (event in activity.config.events) {
        if (event.blockOnThreshold && event.eventName !in interval.liftedThresholds &&
          interval.thresholds[event.eventName]?.fired == true && selectionExists(event.selectionId)
        ) {
          result.add(MonitoringBlock(name, event.selectionId, MonitoringConfig.MODE_BLOCKLIST, event.eventName, interval.startedAtMs))
        }
      }
    }
    return result
  }

  /**
   * `unblockAll`: every interval in progress is lifted — its shields and its
   * fired thresholds' drop until the next interval start — and monitoring
   * goes on: the interval's end is still recorded, the next occurrence
   * shields again, a threshold firing later in this one shields again
   * (`SharedDefaults.liftActiveIntervals` on iOS). Nothing to lift is a no-op
   * (same value, same revision).
   */
  fun lift(): MonitoringState {
    if (intervals.values.all { it.lifted && it.liftedThresholds.containsAll(it.thresholds.filterValues { p -> p.fired }.keys) }) return this
    return copy(intervals = intervals.mapValues { (_, interval) -> interval.lift() }, revision = revision + 1)
  }

  /** Whether an interval in progress still has unfired thresholds: usage must be observed. */
  val observesUsage: Boolean
    get() = intervals.any { (name, interval) ->
      val activity = activities[name] ?: return@any false
      activity.config.events.any { interval.thresholds[it.eventName]?.fired == false }
    }

  /**
   * When the engine must evaluate next even if nothing happens on screen:
   * the earliest interval boundary, pending warning, or threshold an open
   * span would reach. Null when nothing is scheduled. The inexact alarm
   * the wiring sets from it may slip in Doze; the boundary
   * is then observed at the next wake, as on iOS.
   */
  fun nextWakeMs(nowMs: Long, zone: TimeZone): Long? {
    var next: Long? = null
    fun consider(at: Long?) {
      if (at != null && at > nowMs && (next == null || at < next!!)) next = at
    }
    for ((name, activity) in activities) {
      consider(activity.nextTransition(nowMs, zone))
      val interval = intervals[name] ?: continue
      val warning = activity.config.schedule.warningTimeSeconds
      if (warning != null && !interval.warningIssued) consider(interval.occurrence.endMs - warning * 1000L)
      for (event in activity.config.events) {
        val progress = interval.thresholds[event.eventName] ?: continue
        if (progress.fired) continue
        consider(progress.reachesAt(event.thresholdSeconds * 1000L, nowMs))
        if (warning != null && !progress.warned && event.thresholdSeconds > warning) {
          consider(progress.reachesAt((event.thresholdSeconds - warning) * 1000L, nowMs))
        }
      }
    }
    return next
  }

  // MARK: - Internals

  private fun feed(usage: List<UsageEvent>, uptoMs: Long, selections: (String) -> Selection?): MonitoringState {
    var tracker = foreground
    val intervals = LinkedHashMap(this.intervals)
    val packages = HashMap<String, Set<String>>()
    fun selected(selectionId: String) = packages.getOrPut(selectionId) { selections(selectionId)?.applications ?: emptySet() }

    for (event in usage.sortedBy { it.timestampMs }) {
      // Events after `uptoMs` are not known to be complete yet (the pipeline
      // lag): they stay in the next window, which the wiring queries from the
      // cursor. "Fed already" is judged against the cursor this batch started
      // from, not the one moving through it: two events at the same instant
      // (paused(A) and resumed(B) share a millisecond routinely) are both new.
      if (event.timestampMs > uptoMs || !foreground.accepts(event)) continue
      val (next, change) = tracker.apply(event)
      tracker = next
      if (change == null) continue
      for (name in intervals.keys.toList()) {
        val interval = intervals.getValue(name)
        val activity = activities[name] ?: continue
        if (interval.thresholds.isEmpty()) continue
        intervals[name] = interval.copy(
          thresholds = interval.thresholds.mapValues { (eventName, progress) ->
            val selectionId = activity.config.events.firstOrNull { it.eventName == eventName }?.selectionId ?: return@mapValues progress
            progress.feed(change, selected(selectionId))
          },
        )
      }
    }
    tracker = tracker.advanceTo(uptoMs)
    for (name in intervals.keys.toList()) {
      intervals[name] = intervals.getValue(name).let { it.copy(thresholds = it.thresholds.mapValues { (_, progress) -> progress.settle(uptoMs) }) }
    }
    return copy(intervals = intervals, foreground = tracker)
  }

  private fun armThresholds(
    activity: MonitoredActivity,
    occurrence: Occurrence,
    nowMs: Long,
    selections: (String) -> Selection?,
    requests: MutableList<HistoryRequest>,
  ): Map<String, ThresholdProgress> {
    val result = LinkedHashMap<String, ThresholdProgress>()
    for (event in activity.config.events) {
      // iOS excludes only usage from before the registration when
      // `includesPastActivity` is false; a late first pass is not a registration.
      val countFrom = if (event.includesPastActivity) occurrence.startMs else maxOf(occurrence.startMs, activity.registeredAtMs)
      val selected = selections(event.selectionId)?.applications ?: emptySet()
      val progress = ThresholdProgress.arm(countFrom, occurrence.endMs, nowMs, foreground, selected)
      if (progress.historyPending) requests.add(HistoryRequest(activity.name, event.eventName, event.selectionId, progress.historyFromMs!!, progress.historyToMs!!))
      result[event.eventName] = progress
    }
    return result
  }

  /** The warning and threshold events due for `name` at `nowMs`, each once per occurrence. */
  private fun evaluate(name: String, nowMs: Long, includeWarnings: Boolean = true): Result {
    val activity = activities[name] ?: return Result(this)
    val interval = intervals[name] ?: return Result(this)
    var log = this.log
    val recorded = ArrayList<MonitorEvent>()
    var updated = interval
    val warning = activity.config.schedule.warningTimeSeconds

    if (includeWarnings && warning != null && !updated.warningIssued && nowMs >= updated.occurrence.endMs - warning * 1000L) {
      log = log.append(name, MonitorEvent.INTERVAL_WILL_END_WARNING, null, nowMs).also { recorded.add(it.second) }.first
      updated = updated.copy(warningIssued = true)
    }
    for (event in activity.config.events) {
      var progress = updated.thresholds[event.eventName] ?: continue
      if (progress.fired) continue
      val total = progress.projectedMs(nowMs)
      if (total >= event.thresholdSeconds * 1000L) {
        log = log.append(name, MonitorEvent.EVENT_DID_REACH_THRESHOLD, event.eventName, nowMs).also { recorded.add(it.second) }.first
        progress = progress.copy(fired = true)
      } else if (includeWarnings && warning != null && !progress.warned && event.thresholdSeconds > warning && total >= (event.thresholdSeconds - warning) * 1000L) {
        log = log.append(name, MonitorEvent.EVENT_WILL_REACH_THRESHOLD_WARNING, event.eventName, nowMs).also { recorded.add(it.second) }.first
        progress = progress.copy(warned = true)
      }
      updated = updated.copy(thresholds = updated.thresholds + (event.eventName to progress))
    }
    if (updated == interval) return Result(this)
    return Result(copy(intervals = intervals + (name to updated), log = log), recorded)
  }

  // MARK: - Codec

  fun toJson(): JSONObject = JSONObject()
    .put(REVISION, revision)
    .put(ACTIVITIES, JSONObject().also { json -> activities.toSortedMap().forEach { (name, activity) -> json.put(name, activity.toJson()) } })
    .put(INTERVALS, JSONObject().also { json -> intervals.toSortedMap().forEach { (name, interval) -> json.put(name, interval.toJson()) } })
    .put(LOG, log.toJson())
    .put(FOREGROUND, foreground.toJson())

  companion object {
    private const val REVISION = "revision"
    private const val ACTIVITIES = "activities"
    private const val INTERVALS = "intervals"
    private const val LOG = "log"
    private const val FOREGROUND = "foreground"

    /** An absent document is the empty state; a malformed activity or interval is dropped (the next `advance` ends an orphaned interval). */
    fun fromJson(json: JSONObject?): MonitoringState {
      if (json == null) return MonitoringState()
      val activities = LinkedHashMap<String, MonitoredActivity>()
      json.optJSONObject(ACTIVITIES)?.let { all ->
        for (name in all.keys()) all.optJSONObject(name)?.let(MonitoredActivity::fromJson)?.takeIf { it.name == name }?.let { activities[name] = it }
      }
      val intervals = LinkedHashMap<String, ActiveInterval>()
      json.optJSONObject(INTERVALS)?.let { all ->
        for (name in all.keys()) all.optJSONObject(name)?.let(ActiveInterval::fromJson)?.let { intervals[name] = it }
      }
      return MonitoringState(
        activities,
        intervals,
        MonitorEventLog.fromJson(json.optJSONObject(LOG)),
        UsageForegroundState.fromJson(json.optJSONObject(FOREGROUND)),
        ConfigValues.long(json.opt(REVISION)) ?: 0,
      )
    }
  }
}

/** Where the monitoring state lives in the state-store document: the `monitoring` member. */
internal object MonitoringDocument {
  const val KEY = "monitoring"

  fun read(document: JSONObject): MonitoringState = MonitoringState.fromJson(document.optJSONObject(KEY))

  fun write(document: JSONObject, state: MonitoringState) {
    document.put(KEY, state.toJson())
  }
}
