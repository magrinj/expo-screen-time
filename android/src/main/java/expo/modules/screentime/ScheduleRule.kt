package expo.modules.screentime

import java.util.Calendar
import java.util.TimeZone

/** A time of day, as `DateComponents` carries it (`hour`, `minute`, `second`). */
internal data class TimeOfDay(val hour: Int, val minute: Int, val second: Int = 0) {
  val secondsSinceMidnight: Int
    get() = hour * 3600 + minute * 60 + second
}

/** An occurrence of a schedule: `start ≤ t < end`, as Unix ms. */
internal data class Occurrence(val startMs: Long, val endMs: Long) {
  operator fun contains(nowMs: Long): Boolean = nowMs in startMs until endMs

  /**
   * Whether two occurrences are the same interval in real time. Occurrences
   * of one schedule never overlap, so an overlap can only be the same
   * occurrence re-evaluated — after a time-zone change, where the wall-clock
   * bounds moved but the interval in progress is the same one.
   */
  fun overlaps(other: Occurrence): Boolean = startMs < other.endMs && other.startMs < endMs
}

/**
 * A schedule as a pure function of time, the shape both daily and weekly
 * intervals fit: where the latest occurrence started, where an occurrence
 * ends, whether an occurrence is selected (weekdays), and when the next one
 * starts. Everything else — the occurrence in progress, activity, the next
 * transition — derives from those four, below.
 *
 * Time zones are passed in, never read from the device: the engine evaluates
 * every schedule with the zone current at that moment, so a zone change
 * takes effect at the next evaluation, as `Calendar.current` does for the
 * iOS monitor extension. `java.util` on purpose: `java.time` needs
 * desugaring below API 26.
 */
internal interface ScheduleRule {
  /** Start of the most recent occurrence that began at or before `nowMs`, weekdays ignored. */
  fun latestStart(nowMs: Long, zone: TimeZone): Long

  /** End of the occurrence that starts at `occurrenceStartMs`. */
  fun end(occurrenceStartMs: Long, zone: TimeZone): Long

  /** Whether the occurrence starting at `occurrenceStartMs` is on a selected weekday. */
  fun isSelected(occurrenceStartMs: Long, zone: TimeZone): Boolean

  /** Start of the first occurrence that begins strictly after `afterMs`, weekdays ignored. */
  fun nextStart(afterMs: Long, zone: TimeZone): Long

  /**
   * The interval's length as `DateComponents` arithmetic (no zone): what
   * the iOS limits (15 min ≤ length ≤ 1 week) are checked against.
   */
  val nominalLengthSeconds: Long

  /** The occurrence in progress at `nowMs` (start ≤ now < end), weekdays ignored. */
  fun occurrence(nowMs: Long, zone: TimeZone): Occurrence? {
    val start = latestStart(nowMs, zone)
    val end = end(start, zone)
    return if (nowMs < end) Occurrence(start, end) else null
  }

  /** The occurrence in progress at `nowMs` when it is on a selected weekday. */
  fun selectedOccurrence(nowMs: Long, zone: TimeZone): Occurrence? =
    occurrence(nowMs, zone)?.takeIf { isSelected(it.startMs, zone) }

  /** Whether the schedule blocks right now: inside an occurrence on a selected weekday. */
  fun isActive(nowMs: Long, zone: TimeZone): Boolean = selectedOccurrence(nowMs, zone) != null

  /**
   * Start of the first selected occurrence beginning strictly after
   * `afterMs`. Null only when no weekday is selected (every day of the week
   * tried), which parsing never produces.
   */
  fun nextSelectedStart(afterMs: Long, zone: TimeZone): Long? {
    var cursor = afterMs
    repeat(8) {
      val start = nextStart(cursor, zone)
      if (isSelected(start, zone)) return start
      cursor = start
    }
    return null
  }

  /**
   * The next instant at which `isActive` changes: the end of the occurrence
   * in progress, else the start of the next selected one. What the engine
   * asks the alarm manager to wake it for.
   */
  fun nextTransition(nowMs: Long, zone: TimeZone): Long? =
    selectedOccurrence(nowMs, zone)?.endMs ?: nextSelectedStart(nowMs, zone)

  companion object {
    const val DAY_SECONDS = 86_400L
    const val WEEK_SECONDS = 7 * DAY_SECONDS

    /**
     * `time` on the calendar day containing `dayMs`, in `zone`, resolved as
     * Foundation's `Calendar.date(bySettingHour:…)` resolves it, so that a
     * boundary inside a DST transition hour is the same instant on both
     * platforms: a wall time that does not exist (the spring gap) is the
     * transition instant, a wall time that exists twice (the autumn
     * overlap) is the earlier one. (A lenient `java.util.Calendar` would
     * roll the gap forward by the gap length and pick the later overlap.)
     */
    fun wallClock(time: TimeOfDay, dayMs: Long, zone: TimeZone): Long {
      val day = Calendar.getInstance(zone)
      day.timeInMillis = dayMs
      val local = Calendar.getInstance(UTC)
      local.clear()
      local.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), time.hour, time.minute, time.second)
      val localMs = local.timeInMillis
      // The offsets that could apply at this wall time; an instant is valid when its own offset reproduces the wall time.
      val raw = zone.rawOffset
      val offsets = setOf(raw, raw + zone.dstSavings, zone.getOffset(localMs - raw), zone.getOffset(localMs - raw - zone.dstSavings), zone.getOffset(localMs))
      val valid = offsets.map { localMs - it }.filter { zone.getOffset(it) + it == localMs }
      if (valid.isNotEmpty()) return valid.min()
      // A gap: the transition instant, searched between the instants the two offsets would give.
      var low = offsets.map { localMs - it }.min()
      var high = offsets.map { localMs - it }.max()
      val after = zone.getOffset(high)
      while (high - low > 1) {
        val mid = (low + high) / 2
        if (zone.getOffset(mid) == after) high = mid else low = mid
      }
      return high
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** The same wall-clock time `days` calendar days away (23 h or 25 h across a DST change, like `Calendar.date(byAdding: .day)`). */
    fun plusDays(ms: Long, days: Int, zone: TimeZone): Long {
      val calendar = Calendar.getInstance(zone)
      calendar.timeInMillis = ms
      calendar.add(Calendar.DAY_OF_MONTH, days)
      return calendar.timeInMillis
    }

    /** 1 = Sunday … 7 = Saturday, like `DateComponents.weekday` and `Calendar.DAY_OF_WEEK`. */
    fun weekday(ms: Long, zone: TimeZone): Int {
      val calendar = Calendar.getInstance(zone)
      calendar.timeInMillis = ms
      return calendar.get(Calendar.DAY_OF_WEEK)
    }
  }
}
