package expo.modules.screentime

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * One hourly segment of a report window, the Android twin of the
 * `DeviceActivityData.ActivitySegment` the iOS extension iterates over
 * (`CustomTemplateReport.swift`, `makeConfiguration`). `hourOfDay` is the
 * zone's hour at the segment start, the bucket `hourly[hour]` the iOS code
 * adds the segment to (`Calendar.current.component(.hour, from:
 * segment.dateInterval.start)`).
 */
internal data class ReportHourSegment(val startMs: Long, val endMs: Long, val hourOfDay: Int) {
  val durationMs: Long
    get() = endMs - startMs
}

/**
 * The interval a report covers, cut into hourly segments: what
 * `ReportContainerView.createFilter` (`ios/ExpoScreenTimeReportView.swift`)
 * builds with `Calendar.current`, reproduced with `java.util.Calendar` in the
 * zone and locale the view is given (the device's, read by the wiring).
 *
 * - `daily`: the calendar day of `filterDate`, `startOfDay` to the next
 *   `startOfDay` (23 or 25 hours on a DST day, like `date(byAdding: .day)`).
 * - `weekly`: the locale's week containing `filterDate`
 *   (`dateComponents([.yearForWeekOfYear, .weekOfYear])`): it starts on
 *   Sunday in en-US and on Monday in fr-FR, at the start of that day, and
 *   ends one week later, same wall time.
 * - anything else: iOS falls back to the current day of **now**, not of
 *   `filterDate`; so does [of].
 *
 * The filter is always `.hourly(during:)` ("Templates bind hourly buckets"),
 * so the segments are the calendar hours of the interval: 23, 24 or 25 for a
 * day, 167 to 169 for a week. On the autumn DST day two segments share
 * `hourOfDay` (the repeated hour), on the spring day one hour is absent,
 * exactly as the iOS `hourly` buckets come out.
 *
 * `java.util.Calendar`, not `java.time`: the module's floor is API 24
 * (`UsageMeter.day`, `ScheduleRule`). The wiring passes the device's format
 * locale (`Locale.getDefault(Locale.Category.FORMAT)`), extensions included:
 * Android 14 lets the person override the first day of the week (`-u-fw-`),
 * which iOS's `Calendar.current` honours too.
 */
internal data class ReportWindow(val startMs: Long, val endMs: Long, val segments: List<ReportHourSegment>) {
  val durationMs: Long
    get() = endMs - startMs

  companion object {
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    private const val HOUR_MS = 3_600_000L

    /**
     * The window for `filterSegment` / `filterDate` (the view's props; the
     * date is Unix ms as JS sends it) in `zone` and `locale`. `nowMs` only
     * matters for an unknown segment, the iOS fallback.
     */
    fun of(filterSegment: String?, filterDateMs: Long, zone: TimeZone, locale: Locale, nowMs: Long = System.currentTimeMillis()): ReportWindow {
      val calendar = Calendar.getInstance(zone, locale)
      val start: Long
      val end: Long
      when (filterSegment) {
        DAILY -> {
          start = startOfDay(calendar, filterDateMs)
          end = plus(calendar, start, Calendar.DAY_OF_MONTH, 1)
        }
        WEEKLY -> {
          start = startOfWeek(calendar, filterDateMs)
          end = plus(calendar, start, Calendar.DAY_OF_MONTH, 7)
        }
        else -> {
          start = startOfDay(calendar, nowMs)
          end = plus(calendar, start, Calendar.DAY_OF_MONTH, 1)
        }
      }
      return ReportWindow(start, end, hourly(start, end, zone))
    }

    /** The calendar hours of `[startMs, endMs)`: `startMs` is a day start, so every segment begins on the hour. */
    fun hourly(startMs: Long, endMs: Long, zone: TimeZone): List<ReportHourSegment> {
      val segments = ArrayList<ReportHourSegment>((((endMs - startMs) / HOUR_MS).coerceAtLeast(0) + 1).toInt())
      val calendar = Calendar.getInstance(zone)
      var cursor = startMs
      while (cursor < endMs) {
        val next = minOf(cursor + HOUR_MS, endMs)
        calendar.timeInMillis = cursor
        segments.add(ReportHourSegment(cursor, next, calendar.get(Calendar.HOUR_OF_DAY)))
        cursor = next
      }
      return segments
    }

    private fun startOfDay(calendar: Calendar, ms: Long): Long {
      calendar.timeInMillis = ms
      calendar.set(Calendar.HOUR_OF_DAY, 0)
      calendar.set(Calendar.MINUTE, 0)
      calendar.set(Calendar.SECOND, 0)
      calendar.set(Calendar.MILLISECOND, 0)
      return calendar.timeInMillis
    }

    /** The start of the day `firstDayOfWeek` (the locale's) at or before `ms`. */
    private fun startOfWeek(calendar: Calendar, ms: Long): Long {
      var day = startOfDay(calendar, ms)
      // At most six steps back; a lenient add handles the month and year wrap.
      repeat(7) {
        calendar.timeInMillis = day
        if (calendar.get(Calendar.DAY_OF_WEEK) == calendar.firstDayOfWeek) return day
        day = plus(calendar, day, Calendar.DAY_OF_MONTH, -1)
      }
      return day
    }

    private fun plus(calendar: Calendar, ms: Long, field: Int, amount: Int): Long {
      calendar.timeInMillis = ms
      calendar.add(field, amount)
      return calendar.timeInMillis
    }
  }
}
