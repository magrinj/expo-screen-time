package expo.modules.screentime

import java.util.TimeZone

/**
 * A weekly interval from one weekday and time of day to another — Friday
 * 22:00 to Sunday 06:00 — what `DateComponents.weekday` on both bounds
 * describes. iOS registers such a schedule with DeviceActivity as given
 * and leaves its boundaries to the system; Android evaluates it here. An
 * end at or before the start wraps to the next week (equal bounds: one
 * week), on the same weekday too: Mon 22:00 → Mon 06:00 is six days and
 * eight hours. The `weekdays` array is ignored, as the iOS monitor ignores it for
 * a weekly interval (the components win).
 */
internal data class WeeklySchedule(
  val startWeekday: Int,
  val start: TimeOfDay,
  val endWeekday: Int,
  val end: TimeOfDay,
) : ScheduleRule {
  override val nominalLengthSeconds: Long
    get() {
      val length = (endWeekday - startWeekday) * ScheduleRule.DAY_SECONDS + (end.secondsSinceMidnight - start.secondsSinceMidnight)
      return Math.floorMod(length, ScheduleRule.WEEK_SECONDS).let { if (it == 0L) ScheduleRule.WEEK_SECONDS else it }
    }

  override fun latestStart(nowMs: Long, zone: TimeZone): Long {
    for (daysBack in 0..7) {
      val day = ScheduleRule.plusDays(nowMs, -daysBack, zone)
      if (ScheduleRule.weekday(day, zone) != startWeekday) continue
      val candidate = ScheduleRule.wallClock(start, day, zone)
      if (candidate <= nowMs) return candidate
    }
    return ScheduleRule.wallClock(start, ScheduleRule.plusDays(nowMs, -7, zone), zone)
  }

  override fun end(occurrenceStartMs: Long, zone: TimeZone): Long {
    for (daysAhead in 0..7) {
      val day = ScheduleRule.plusDays(occurrenceStartMs, daysAhead, zone)
      if (ScheduleRule.weekday(day, zone) != endWeekday) continue
      val candidate = ScheduleRule.wallClock(end, day, zone)
      if (candidate > occurrenceStartMs) return candidate
    }
    return ScheduleRule.wallClock(end, ScheduleRule.plusDays(occurrenceStartMs, 7, zone), zone)
  }

  /** A weekly interval has no weekday selection: the system restricts it already on iOS. */
  override fun isSelected(occurrenceStartMs: Long, zone: TimeZone): Boolean = true

  override fun nextStart(afterMs: Long, zone: TimeZone): Long {
    for (daysAhead in 0..7) {
      val day = ScheduleRule.plusDays(afterMs, daysAhead, zone)
      if (ScheduleRule.weekday(day, zone) != startWeekday) continue
      val candidate = ScheduleRule.wallClock(start, day, zone)
      if (candidate > afterMs) return candidate
    }
    return ScheduleRule.wallClock(start, ScheduleRule.plusDays(afterMs, 7, zone), zone)
  }

  companion object {
    /** Weekly: different weekdays, or the same weekday with the end at or before the start (which wraps the week). */
    fun isWeekly(startWeekday: Int, start: TimeOfDay, endWeekday: Int, end: TimeOfDay): Boolean =
      startWeekday != endWeekday || end.secondsSinceMidnight <= start.secondsSinceMidnight

    /** Null unless both bounds carry a weekday and `isWeekly` (otherwise it is a `DailySchedule`). */
    fun fromConfig(schedule: Any?): WeeklySchedule? {
      val dict = ConfigValues.map(schedule) ?: return null
      val startDict = ConfigValues.map(dict["intervalStart"]) ?: return null
      val endDict = ConfigValues.map(dict["intervalEnd"]) ?: return null
      val startWeekday = ConfigValues.int(startDict["weekday"]) ?: return null
      val endWeekday = ConfigValues.int(endDict["weekday"]) ?: return null
      val startHour = ConfigValues.int(startDict["hour"]) ?: return null
      val endHour = ConfigValues.int(endDict["hour"]) ?: return null
      val start = TimeOfDay(startHour, ConfigValues.int(startDict["minute"]) ?: 0, ConfigValues.int(startDict["second"]) ?: 0)
      val end = TimeOfDay(endHour, ConfigValues.int(endDict["minute"]) ?: 0, ConfigValues.int(endDict["second"]) ?: 0)
      if (!isWeekly(startWeekday, start, endWeekday, end)) return null
      return WeeklySchedule(startWeekday, start, endWeekday, end)
    }
  }
}

/** The rule a stored or JS schedule describes: daily, weekly, or null when malformed. */
internal object MonitoringSchedule {
  fun parse(schedule: Any?, weekdays: Any?): ScheduleRule? =
    DailySchedule.fromConfig(schedule, weekdays) ?: WeeklySchedule.fromConfig(schedule)
}
