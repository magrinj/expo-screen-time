package expo.modules.screentime

import java.util.TimeZone

/**
 * A time-of-day interval repeated every day, optionally restricted to
 * weekdays (1 = Sunday … 7 = Saturday). An interval whose end is not after
 * its start crosses midnight, and an occurrence belongs to the weekday on
 * which it STARTS — a Monday 22:00–06:00 occurrence is still Monday's at
 * 01:00 on Tuesday. The twin of `ios/DailySchedule.swift`, case for case
 * (`scripts/swift-tests/main.swift`, "DailySchedule" sections, ported to
 * `DailyScheduleTest`).
 *
 * On iOS this type exists next to DeviceActivity: the extension uses it to
 * skip occurrences on unselected weekdays and the module to apply the block
 * synchronously inside an occurrence. On Android it IS the
 * schedule: the engine evaluates it at every wake and every foreground
 * change (`MonitoringState`).
 */
internal data class DailySchedule(
  val start: TimeOfDay,
  val end: TimeOfDay,
  /** Selected weekdays; empty means every day. */
  val weekdays: Set<Int> = emptySet(),
) : ScheduleRule {
  /** `end` at or before `start` means the occurrence ends the next day (equal bounds: a 24 h occurrence). */
  val crossesMidnight: Boolean
    get() = end.secondsSinceMidnight <= start.secondsSinceMidnight

  override val nominalLengthSeconds: Long
    get() {
      val length = (end.secondsSinceMidnight - start.secondsSinceMidnight).toLong()
      return if (length <= 0) length + ScheduleRule.DAY_SECONDS else length
    }

  /** Today's start if it has passed, otherwise yesterday's. */
  override fun latestStart(nowMs: Long, zone: TimeZone): Long {
    val todayStart = ScheduleRule.wallClock(start, nowMs, zone)
    if (todayStart <= nowMs) return todayStart
    return ScheduleRule.wallClock(start, ScheduleRule.plusDays(nowMs, -1, zone), zone)
  }

  override fun end(occurrenceStartMs: Long, zone: TimeZone): Long {
    val endDay = if (crossesMidnight) ScheduleRule.plusDays(occurrenceStartMs, 1, zone) else occurrenceStartMs
    return ScheduleRule.wallClock(end, endDay, zone)
  }

  override fun isSelected(occurrenceStartMs: Long, zone: TimeZone): Boolean =
    weekdays.isEmpty() || ScheduleRule.weekday(occurrenceStartMs, zone) in weekdays

  override fun nextStart(afterMs: Long, zone: TimeZone): Long {
    val todayStart = ScheduleRule.wallClock(start, afterMs, zone)
    if (todayStart > afterMs) return todayStart
    return ScheduleRule.wallClock(start, ScheduleRule.plusDays(afterMs, 1, zone), zone)
  }

  /**
   * What `startMonitoring` does with the block right now. "Cannot compute"
   * is distinct from "outside": on iOS a weekly interval spanning weekdays
   * is left to the monitor. On Android every valid schedule is computable
   * (`MonitoringSchedule.parse` evaluates weekly intervals too), so after
   * validation only `Apply` and `Clear` occur; `LeaveToMonitor` is kept for
   * a malformed stored config, which clears nothing (iOS parity).
   */
  sealed class SyncDecision {
    /** Inside an occurrence on a selected weekday: mark the interval from `occurrenceStartMs` and apply the block now. */
    data class Apply(val occurrenceStartMs: Long) : SyncDecision()

    /** Outside, or on an unselected weekday: nothing from a previous registration may linger. */
    object Clear : SyncDecision()

    /** Not a schedule this code can evaluate. */
    object LeaveToMonitor : SyncDecision()
  }

  companion object {
    /**
     * From the JS `schedule` (`intervalStart` / `intervalEnd` date
     * components) and the optional `weekdays` array, as stored in the
     * monitoring config. Null when the components describe something that
     * is not a daily interval: a weekly interval whose start and end fall on
     * different weekdays, or on the same weekday with the end at or before
     * the start (`WeeklySchedule` reads those). A `weekday` set on the
     * components makes the interval weekly and wins over the array.
     */
    fun fromConfig(schedule: Any?, weekdays: Any?): DailySchedule? {
      val dict = ConfigValues.map(schedule) ?: return null
      val startDict = ConfigValues.map(dict["intervalStart"]) ?: return null
      val endDict = ConfigValues.map(dict["intervalEnd"]) ?: return null
      val startHour = ConfigValues.int(startDict["hour"]) ?: return null
      val endHour = ConfigValues.int(endDict["hour"]) ?: return null
      val start = TimeOfDay(startHour, ConfigValues.int(startDict["minute"]) ?: 0, ConfigValues.int(startDict["second"]) ?: 0)
      val end = TimeOfDay(endHour, ConfigValues.int(endDict["minute"]) ?: 0, ConfigValues.int(endDict["second"]) ?: 0)

      val startWeekday = ConfigValues.int(startDict["weekday"])
      val endWeekday = ConfigValues.int(endDict["weekday"])
      val selected = when {
        startWeekday != null -> {
          if (endWeekday != null && endWeekday != startWeekday) return null
          // Mon 22:00 → Mon 06:00 is a WEEKLY interval to DeviceActivity (about six days), not a daily one.
          if (endWeekday != null && end.secondsSinceMidnight <= start.secondsSinceMidnight) return null
          setOf(startWeekday)
        }
        endWeekday != null -> return null
        else -> weekdays(weekdays)
      }
      return DailySchedule(start, end, selected)
    }

    /** Normalizes a JS `weekdays` array: integers 1…7 only, duplicates dropped. */
    fun weekdays(values: Any?): Set<Int> =
      (ConfigValues.list(values) ?: emptyList()).mapNotNull(ConfigValues::int).filter { it in 1..7 }.toSet()

    /** `ConfigValues.int`, under the name the iOS tests use. */
    fun intValue(value: Any?): Int? = ConfigValues.int(value)

    /**
     * Whether an interval marker written at `markedAtMs` belongs to the
     * occurrence that started at `occurrenceStartMs`: the iOS dedupe between
     * the module's synchronous marker and the system's `intervalDidStart`.
     * The Android marker records the occurrence bounds, and
     * `ActiveInterval.belongsTo` compares those; this keeps the iOS rule for
     * the case where only a timestamp is known.
     */
    fun isSameOccurrence(markedAtMs: Long, occurrenceStartMs: Long): Boolean = markedAtMs >= occurrenceStartMs

    fun syncDecision(schedule: Any?, weekdays: Any?, nowMs: Long, zone: TimeZone): SyncDecision {
      val daily = fromConfig(schedule, weekdays) ?: return SyncDecision.LeaveToMonitor
      return daily.syncDecision(nowMs, zone)
    }
  }
}

/** The decision for any computable schedule: inside a selected occurrence, or not. */
internal fun ScheduleRule.syncDecision(nowMs: Long, zone: TimeZone): DailySchedule.SyncDecision =
  selectedOccurrence(nowMs, zone)?.let { DailySchedule.SyncDecision.Apply(it.startMs) } ?: DailySchedule.SyncDecision.Clear
