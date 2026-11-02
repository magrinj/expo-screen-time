package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject

/**
 * One recorded monitor event, the contract's `MonitorEvent`. The `id` is
 * the log's sequence number: unique per state document, monotonic, so
 * "every event once, oldest first" needs no timestamp tie-breaking (iOS
 * ids are UUIDs and `MonitorEventCursor` there sorts by timestamp).
 */
internal data class MonitorEvent(
  val sequence: Long,
  val activityName: String,
  val eventType: String,
  val eventName: String?,
  val timestampMs: Long,
) {
  val id: String
    get() = sequence.toString()

  /** The JS payload: `id`, `activityName`, `eventType`, `eventName` when set, `timestamp` (Unix ms, a number as on iOS). */
  fun toJs(): Map<String, Any> {
    val result = LinkedHashMap<String, Any>()
    result["id"] = id
    result["activityName"] = activityName
    result["eventType"] = eventType
    if (eventName != null) result["eventName"] = eventName
    result["timestamp"] = timestampMs.toDouble()
    return result
  }

  fun toJson(): JSONObject = JSONObject()
    .put(SEQ, sequence)
    .put(ACTIVITY, activityName)
    .put(TYPE, eventType)
    .putOpt(NAME, eventName)
    .put(TIMESTAMP, timestampMs)

  companion object {
    const val INTERVAL_DID_START = "intervalDidStart"
    const val INTERVAL_DID_END = "intervalDidEnd"
    const val EVENT_DID_REACH_THRESHOLD = "eventDidReachThreshold"
    const val INTERVAL_WILL_END_WARNING = "intervalWillEndWarning"
    const val EVENT_WILL_REACH_THRESHOLD_WARNING = "eventWillReachThresholdWarning"

    private const val SEQ = "seq"
    private const val ACTIVITY = "activityName"
    private const val TYPE = "eventType"
    private const val NAME = "eventName"
    private const val TIMESTAMP = "timestamp"

    fun fromJson(json: JSONObject): MonitorEvent? {
      val sequence = ConfigValues.long(json.opt(SEQ)) ?: return null
      val activityName = ConfigValues.string(json.opt(ACTIVITY)) ?: return null
      val eventType = ConfigValues.string(json.opt(TYPE)) ?: return null
      val timestamp = ConfigValues.long(json.opt(TIMESTAMP)) ?: return null
      return MonitorEvent(sequence, activityName, eventType, ConfigValues.string(json.opt(NAME)), timestamp)
    }
  }
}

/**
 * The monitor event log, the Android counterpart of the iOS App Group
 * event history (`expo_st_events_<activity>`), as one immutable value:
 * appending returns a new log. Exactly-once is a property of WHERE it is
 * mutated, not of the log: every append happens inside the state-store
 * `update` that also records why the event exists (the interval marker, the
 * fired threshold), so a second process — or the same process after a
 * death — finds the marker and does not append again. See
 * `MonitoringState`.
 *
 * Bounded like iOS at 1000 events per activity, plus 2000 in total: the
 * iOS log is one UserDefaults array per activity, this one lives in the
 * single state document every update rewrites.
 */
internal data class MonitorEventLog(
  /** In sequence order, oldest first. */
  val events: List<MonitorEvent> = emptyList(),
  /** The sequence the next event gets; never reset, not even by `clear`. */
  val nextSequence: Long = 1,
) {
  fun append(activityName: String, eventType: String, eventName: String?, timestampMs: Long): Pair<MonitorEventLog, MonitorEvent> {
    val event = MonitorEvent(nextSequence, activityName, eventType, eventName, timestampMs)
    var kept = events + event
    val ofActivity = kept.count { it.activityName == activityName }
    if (ofActivity > PER_ACTIVITY_CAP) {
      val oldest = kept.first { it.activityName == activityName }
      kept = kept - oldest
    }
    if (kept.size > TOTAL_CAP) kept = kept.drop(kept.size - TOTAL_CAP)
    return MonitorEventLog(kept, nextSequence + 1) to event
  }

  /**
   * `getMonitorEvents(activityName?, limit)`, as iOS answers it: for one
   * activity, the last `limit` events oldest first; for all, the newest
   * `limit` events by timestamp, newest first (`SharedDefaults.getEvents` /
   * `getAllEvents`). Mirrored as is so a consumer's code reads the same on
   * both platforms.
   */
  fun events(activityName: String?, limit: Int = DEFAULT_LIMIT): List<MonitorEvent> {
    val bound = if (limit < 0) 0 else limit
    return if (activityName != null) {
      events.filter { it.activityName == activityName }.takeLast(bound)
    } else {
      events.sortedWith(compareByDescending<MonitorEvent> { it.timestampMs }.thenByDescending { it.sequence }).take(bound)
    }
  }

  /** `clearMonitorEvents(activityName?)`. The sequence keeps counting, so a cursor never re-emits. */
  fun clear(activityName: String?): MonitorEventLog =
    if (activityName == null) copy(events = emptyList()) else copy(events = events.filter { it.activityName != activityName })

  fun toJson(): JSONObject = JSONObject()
    .put(NEXT, nextSequence)
    .put(EVENTS, JSONArray().also { array -> events.forEach { array.put(it.toJson()) } })

  companion object {
    const val PER_ACTIVITY_CAP = 1000
    const val TOTAL_CAP = 2000
    const val DEFAULT_LIMIT = 100
    private const val NEXT = "nextSeq"
    private const val EVENTS = "events"

    /** An absent or malformed log reads as empty; the sequence is kept when readable so ids stay unique. */
    fun fromJson(json: JSONObject?): MonitorEventLog {
      if (json == null) return MonitorEventLog()
      val array = json.optJSONArray(EVENTS)
      val events = ArrayList<MonitorEvent>()
      if (array != null) {
        for (i in 0 until array.length()) {
          val event = array.optJSONObject(i)?.let(MonitorEvent::fromJson) ?: continue
          events.add(event)
        }
      }
      events.sortBy { it.sequence }
      val next = ConfigValues.long(json.opt(NEXT)) ?: ((events.lastOrNull()?.sequence ?: 0) + 1)
      return MonitorEventLog(events, maxOf(next, (events.lastOrNull()?.sequence ?: 0) + 1))
    }
  }
}

/**
 * Decides which stored events still have to be emitted to JS, the twin of
 * `ios/MonitorEventCursor.swift`: the module reads the log in bursts and
 * every event must reach `onMonitorEvent` exactly once, oldest first. Here
 * the log is sequenced, so the cursor is one number.
 */
internal data class MonitorEventCursor(
  /** Events at or below this sequence were emitted (or predate the subscription). */
  val afterSequence: Long,
) {
  /** The events not yet emitted, oldest first, and the cursor past them. */
  fun take(log: MonitorEventLog): Pair<List<MonitorEvent>, MonitorEventCursor> {
    val fresh = log.events.filter { it.sequence > afterSequence }.sortedBy { it.sequence }
    val last = fresh.lastOrNull()?.sequence ?: return fresh to this
    return fresh to MonitorEventCursor(last)
  }

  companion object {
    /** A cursor that emits only events appended from now on (`OnStartObserving` resets it like this on iOS). */
    fun startingNow(log: MonitorEventLog): MonitorEventCursor = MonitorEventCursor(log.nextSequence - 1)
  }
}
