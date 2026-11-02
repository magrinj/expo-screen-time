package expo.modules.screentime

import expo.modules.screentime.MonitoringTestClock.HOUR
import expo.modules.screentime.MonitoringTestClock.date
import expo.modules.screentime.MonitoringTestClock.paris
import expo.modules.screentime.MonitoringTestClock.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-only schedule cases: the weekly interval iOS leaves to
 * DeviceActivity, midnight wrap across the Europe/Paris DST changes, and
 * the next transition the engine arms an alarm for.
 */
class MonitoringScheduleTest {
  private val office = DailySchedule(TimeOfDay(9, 0), TimeOfDay(17, 0))
  private val night = DailySchedule(TimeOfDay(22, 0), TimeOfDay(6, 0))
  // Friday 22:00 → Sunday 06:00 (1 = Sunday … 7 = Saturday).
  private val weekend = WeeklySchedule(6, TimeOfDay(22, 0), 1, TimeOfDay(6, 0))

  @Test
  fun `a weekly interval spans its days`() {
    // 2026-10-30 is a Friday, 10-31 a Saturday, 11-01 a Sunday, 11-02 a Monday.
    assertTrue("Saturday noon is inside", weekend.isActive(date(2026, 10, 31, 12, 0), utc))
    assertTrue("Sunday 05:59 is inside", weekend.isActive(date(2026, 11, 1, 5, 59), utc))
    assertFalse("Sunday 06:00 is outside", weekend.isActive(date(2026, 11, 1, 6, 0), utc))
    assertFalse("Monday is outside", weekend.isActive(date(2026, 11, 2, 12, 0), utc))
    assertFalse("Friday 21:59 is outside", weekend.isActive(date(2026, 10, 30, 21, 59), utc))
    assertEquals(Occurrence(date(2026, 10, 30, 22, 0), date(2026, 11, 1, 6, 0)), weekend.occurrence(date(2026, 10, 31, 12, 0), utc))
    assertEquals("latest start on a Monday is last Friday", date(2026, 10, 30, 22, 0), weekend.latestStart(date(2026, 11, 2, 12, 0), utc))
    assertEquals("next start on a Monday is this Friday", date(2026, 11, 6, 22, 0), weekend.nextStart(date(2026, 11, 2, 12, 0), utc))
    assertEquals("next start on Friday 22:00 itself is next week's", date(2026, 11, 6, 22, 0), weekend.nextStart(date(2026, 10, 30, 22, 0), utc))
    assertEquals("32 h nominal", 32 * 3600L, weekend.nominalLengthSeconds)
    assertEquals("equal weekly bounds are one week", 7 * 86_400L, WeeklySchedule(6, TimeOfDay(22, 0), 6, TimeOfDay(22, 0)).nominalLengthSeconds)
    assertEquals("a week-wrapping interval (Sun 06:00 → Fri 22:00)", (5 * 24 + 16) * 3600L, WeeklySchedule(1, TimeOfDay(6, 0), 6, TimeOfDay(22, 0)).nominalLengthSeconds)
    assertEquals("a weekly interval ends the next week when end ≤ start", date(2026, 11, 6, 22, 0), WeeklySchedule(6, TimeOfDay(22, 0), 6, TimeOfDay(22, 0)).end(date(2026, 10, 30, 22, 0), utc))
  }

  @Test
  fun `MonitoringSchedule parse picks the rule the components describe`() {
    val weekly = mapOf(
      "intervalStart" to mapOf("hour" to 22.0, "minute" to 0.0, "weekday" to 6.0),
      "intervalEnd" to mapOf("hour" to 6.0, "minute" to 0.0, "weekday" to 1.0),
    )
    assertEquals(weekend, MonitoringSchedule.parse(weekly, listOf(2.0)))
    val sameDay = mapOf(
      "intervalStart" to mapOf("hour" to 9, "minute" to 0, "weekday" to 2),
      "intervalEnd" to mapOf("hour" to 17, "minute" to 0, "weekday" to 2),
    )
    assertEquals(DailySchedule(TimeOfDay(9, 0), TimeOfDay(17, 0), setOf(2)), MonitoringSchedule.parse(sameDay, null))
    val sameDayWrap = mapOf(
      "intervalStart" to mapOf("hour" to 22, "minute" to 0, "weekday" to 2),
      "intervalEnd" to mapOf("hour" to 6, "minute" to 0, "weekday" to 2),
    )
    val wrap = MonitoringSchedule.parse(sameDayWrap, null)
    assertEquals("Mon 22:00 → Mon 06:00 is weekly to DeviceActivity: six days and eight hours", WeeklySchedule(2, TimeOfDay(22, 0), 2, TimeOfDay(6, 0)), wrap)
    assertEquals((6 * 24 + 8) * 3600L, wrap!!.nominalLengthSeconds)
    assertEquals(date(2026, 11, 2, 6, 0), wrap.end(date(2026, 10, 26, 22, 0), utc))
    assertTrue(wrap.isActive(date(2026, 10, 29, 12, 0), utc))
    assertNull("a weekday on one bound only is malformed", MonitoringSchedule.parse(mapOf("intervalStart" to mapOf("hour" to 9), "intervalEnd" to mapOf("hour" to 17, "weekday" to 2)), null))
    assertNull(MonitoringSchedule.parse(null, null))
  }

  @Test
  fun `midnight wrap across the DST changes in Europe Paris`() {
    // DST ends 2026-10-25 at 03:00 CEST → 02:00 CET: the night is 25 h long.
    val fallStart = date(2026, 10, 24, 22, 0, 0, paris)
    assertEquals("22–06 lasts 9 real hours that night", fallStart + 9 * HOUR, night.end(fallStart, paris))
    assertEquals("…and ends at 06:00 CET", date(2026, 10, 25, 6, 0, 0, paris), night.end(fallStart, paris))
    assertTrue("02:30 CET (the second 02:30) is inside", night.isActive(fallStart + 5 * HOUR - 30 * 60_000, paris))
    assertEquals(fallStart, night.latestStart(fallStart + 7 * HOUR, paris))

    // DST starts 2026-03-29 at 02:00 CET → 03:00 CEST: the night is 23 h long.
    val springStart = date(2026, 3, 28, 22, 0, 0, paris)
    assertEquals("22–06 lasts 7 real hours that night", springStart + 7 * HOUR, night.end(springStart, paris))
    assertEquals(date(2026, 3, 29, 6, 0, 0, paris), night.end(springStart, paris))
    assertTrue(night.isActive(springStart + 6 * HOUR, paris))
    assertFalse(night.isActive(springStart + 7 * HOUR, paris))

    // A daytime schedule on the change day keeps its wall-clock bounds.
    assertEquals(Occurrence(date(2026, 3, 29, 9, 0, 0, paris), date(2026, 3, 29, 17, 0, 0, paris)), office.occurrence(date(2026, 3, 29, 12, 0, 0, paris), paris))
    assertEquals("8 h on the spring day too", 8 * HOUR, office.occurrence(date(2026, 3, 29, 12, 0, 0, paris), paris)!!.let { it.endMs - it.startMs })

    // A start inside the spring gap (02:30 does not exist) is the transition instant, 03:00 CEST, as Foundation resolves it.
    val gap = DailySchedule(TimeOfDay(2, 30), TimeOfDay(4, 0))
    assertEquals(date(2026, 3, 29, 3, 0, 0, paris), gap.latestStart(date(2026, 3, 29, 3, 45, 0, paris), paris))
    assertEquals("the gap occurrence is 60 min long (03:00–04:00), as on iOS", 60 * 60_000L, gap.occurrence(date(2026, 3, 29, 3, 45, 0, paris), paris)!!.let { it.endMs - it.startMs })
    // A time in the autumn overlap (02:30 exists twice on 2026-10-25) is the earlier instant, 02:30 CEST = 00:30Z.
    assertEquals(date(2026, 10, 25, 0, 30, 0, utc), ScheduleRule.wallClock(TimeOfDay(2, 30), date(2026, 10, 25, 12, 0, 0, paris), paris))
    assertEquals("New York, 2026-03-08: 02:30 is 03:00 EDT = 07:00Z", date(2026, 3, 8, 7, 0, 0, utc), ScheduleRule.wallClock(TimeOfDay(2, 30), date(2026, 3, 8, 12, 0, 0, utc), java.util.TimeZone.getTimeZone("America/New_York")))
    assertEquals("an ordinary wall time is itself", date(2026, 10, 28, 9, 0, 0, paris), ScheduleRule.wallClock(TimeOfDay(9, 0), date(2026, 10, 28, 15, 0, 0, paris), paris))
    assertEquals("a schedule's day-to-day step is 23 h across the spring change", date(2026, 3, 29, 9, 0, 0, paris), office.nextStart(date(2026, 3, 28, 9, 0, 0, paris), paris))
    assertEquals(23 * HOUR, date(2026, 3, 29, 9, 0, 0, paris) - date(2026, 3, 28, 9, 0, 0, paris))
  }

  @Test
  fun `the next transition is the end inside and the next selected start outside`() {
    val weekdays = DailySchedule(TimeOfDay(9, 0), TimeOfDay(17, 0), setOf(2, 3, 4, 5, 6))
    assertEquals("inside ⇒ the end", date(2026, 10, 28, 17, 0), weekdays.nextTransition(date(2026, 10, 28, 10, 0), utc))
    assertEquals("Wednesday evening ⇒ Thursday 09:00", date(2026, 10, 29, 9, 0), weekdays.nextTransition(date(2026, 10, 28, 18, 0), utc))
    assertEquals("Saturday ⇒ Monday 09:00", date(2026, 11, 2, 9, 0), weekdays.nextTransition(date(2026, 10, 31, 10, 0), utc))
    assertEquals("Friday 17:00 (just ended) ⇒ Monday", date(2026, 11, 2, 9, 0), weekdays.nextTransition(date(2026, 10, 30, 17, 0), utc))
    assertEquals("a Monday-only night from Tuesday 06:00 ⇒ next Monday 22:00", date(2026, 11, 2, 22, 0), DailySchedule(TimeOfDay(22, 0), TimeOfDay(6, 0), setOf(2)).nextTransition(date(2026, 10, 27, 6, 0), utc))
    assertEquals("weekly, from Monday ⇒ Friday 22:00", date(2026, 11, 6, 22, 0), weekend.nextTransition(date(2026, 11, 2, 12, 0), utc))
    assertEquals("weekly, inside ⇒ Sunday 06:00", date(2026, 11, 1, 6, 0), weekend.nextTransition(date(2026, 10, 31, 12, 0), utc))
    assertEquals("every day: the next start", date(2026, 10, 29, 9, 0), office.nextSelectedStart(date(2026, 10, 28, 9, 0), utc))
  }

  @Test
  fun `occurrences overlap only when they are the same one`() {
    val today = Occurrence(date(2026, 10, 28, 9, 0), date(2026, 10, 28, 17, 0))
    assertTrue(today.overlaps(today))
    assertFalse("yesterday's", today.overlaps(Occurrence(date(2026, 10, 27, 9, 0), date(2026, 10, 27, 17, 0))))
    assertFalse("adjacent 24 h occurrences", Occurrence(0, 10).overlaps(Occurrence(10, 20)))
    assertTrue("the same occurrence read in another zone", today.overlaps(Occurrence(date(2026, 10, 28, 10, 0), date(2026, 10, 28, 18, 0))))
    assertTrue(date(2026, 10, 28, 9, 0) in today)
    assertFalse(date(2026, 10, 28, 17, 0) in today)
  }
}
