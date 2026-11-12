package expo.modules.screentime

import expo.modules.screentime.MonitoringTestClock.date
import expo.modules.screentime.MonitoringTestClock.paris
import expo.modules.screentime.MonitoringTestClock.utc
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `scripts/swift-tests/main.swift`, sections "DailySchedule — occurrences",
 * "— weekdays", "— intervalDidStart dedupe", "DailySchedule.syncDecision",
 * "— parsing the JS config" and "DailySchedule.intValue", case for case with
 * the same inputs and expectations.
 */
class DailyScheduleTest {
  private val office = DailySchedule(TimeOfDay(9, 0, 0), TimeOfDay(17, 0, 0))
  private val night = DailySchedule(TimeOfDay(22, 0, 0), TimeOfDay(6, 0, 0))
  private val dailyDict = mapOf(
    "intervalStart" to mapOf("hour" to 9.0, "minute" to 0.0),
    "intervalEnd" to mapOf("hour" to 17.0, "minute" to 0.0),
  )

  @Test
  fun `DailySchedule - occurrences (Swift section)`() {
    assertFalse("9–17 does not cross midnight", office.crossesMidnight)
    // Wednesday 2026-10-28
    assertTrue("10:00 is inside 9–17", office.isActive(date(2026, 10, 28, 10, 0), utc))
    assertFalse("08:59:59 is before the start", office.isActive(date(2026, 10, 28, 8, 59, 59), utc))
    assertTrue("the start instant is inside", office.isActive(date(2026, 10, 28, 9, 0, 0), utc))
    assertFalse("the end instant is outside", office.isActive(date(2026, 10, 28, 17, 0, 0), utc))
    assertEquals("latest start at 10:00 is today 09:00", date(2026, 10, 28, 9, 0), office.latestStart(date(2026, 10, 28, 10, 0), utc))
    assertEquals("latest start at 08:00 is yesterday 09:00", date(2026, 10, 27, 9, 0), office.latestStart(date(2026, 10, 28, 8, 0), utc))
    assertEquals("occurrence bounds", Occurrence(date(2026, 10, 28, 9, 0), date(2026, 10, 28, 17, 0)), office.occurrence(date(2026, 10, 28, 10, 0), utc))

    assertTrue("22–06 crosses midnight", night.crossesMidnight)
    assertTrue("23:30 is inside 22–06", night.isActive(date(2026, 10, 28, 23, 30), utc))
    assertTrue("01:00 next day is inside 22–06", night.isActive(date(2026, 10, 29, 1, 0), utc))
    assertFalse("06:00 is outside", night.isActive(date(2026, 10, 29, 6, 0), utc))
    assertFalse("noon is outside", night.isActive(date(2026, 10, 29, 12, 0), utc))
    assertEquals("at 01:00 the occurrence started yesterday 22:00", date(2026, 10, 28, 22, 0), night.latestStart(date(2026, 10, 29, 1, 0), utc))
    assertEquals("a crossing occurrence ends the next day", date(2026, 10, 29, 6, 0), night.end(date(2026, 10, 28, 22, 0), utc))

    val allDay = DailySchedule(TimeOfDay(0, 0, 0), TimeOfDay(0, 0, 0))
    assertTrue("equal bounds = 24 h occurrence", allDay.crossesMidnight && allDay.isActive(date(2026, 10, 28, 12, 0), utc))

    // Europe/Paris: DST ended on 2026-10-25; a plain daily schedule still resolves to wall-clock times.
    assertEquals(
      "wall-clock start the day after a DST change (Paris)",
      date(2026, 10, 26, 9, 0, 0, paris), office.latestStart(date(2026, 10, 26, 10, 0, 0, paris), paris),
    )
    assertEquals(
      "yesterday's wall-clock start across the DST change (Paris)",
      date(2026, 10, 24, 9, 0, 0, paris), office.latestStart(date(2026, 10, 25, 8, 0, 0, paris), paris),
    )
  }

  @Test
  fun `DailySchedule - weekdays, one activity per logical name (Swift section)`() {
    // 2026-10-26 is a Monday (weekday 2). Mon–Fri = [2,3,4,5,6].
    val weekdaysOffice = DailySchedule(office.start, office.end, setOf(2, 3, 4, 5, 6))
    assertTrue("Monday 10:00 is active", weekdaysOffice.isActive(date(2026, 10, 26, 10, 0), utc))
    assertFalse("Sunday 10:00 is ignored", weekdaysOffice.isActive(date(2026, 10, 25, 10, 0), utc))
    assertFalse("Saturday 10:00 is ignored", weekdaysOffice.isActive(date(2026, 10, 31, 10, 0), utc))
    assertTrue("Friday occurrence selected", weekdaysOffice.isSelected(date(2026, 10, 30, 9, 0), utc))
    assertFalse("Saturday occurrence not selected", weekdaysOffice.isSelected(date(2026, 10, 31, 9, 0), utc))

    val mondayNight = DailySchedule(night.start, night.end, setOf(2))
    assertTrue("Tuesday 01:00 belongs to Monday's 22–06 occurrence ⇒ active", mondayNight.isActive(date(2026, 10, 27, 1, 0), utc))
    assertFalse("Wednesday 01:00 belongs to Tuesday's occurrence ⇒ ignored", mondayNight.isActive(date(2026, 10, 28, 1, 0), utc))
    assertFalse("Monday 21:00 is before the start", mondayNight.isActive(date(2026, 10, 26, 21, 0), utc))
  }

  @Test
  fun `DailySchedule - intervalDidStart dedupe (Swift section)`() {
    val occurrenceStart = date(2026, 10, 28, 9, 0)
    assertTrue(
      "a marker written 1 s after the start is the same occurrence ⇒ duplicate",
      DailySchedule.isSameOccurrence(date(2026, 10, 28, 9, 0, 1), occurrenceStart),
    )
    assertTrue("a marker written at the start instant is the same occurrence", DailySchedule.isSameOccurrence(occurrenceStart, occurrenceStart))
    assertFalse("yesterday's marker is stale ⇒ a new intervalDidStart", DailySchedule.isSameOccurrence(date(2026, 10, 27, 10, 0), occurrenceStart))

    // The Android marker carries the occurrence bounds: the same three cases through `ActiveInterval.belongsTo`.
    val today = Occurrence(occurrenceStart, date(2026, 10, 28, 17, 0))
    assertTrue(ActiveInterval(date(2026, 10, 28, 9, 0, 1), today).belongsTo(today))
    assertTrue(ActiveInterval(occurrenceStart, today).belongsTo(today))
    assertFalse(ActiveInterval(date(2026, 10, 27, 10, 0), Occurrence(date(2026, 10, 27, 9, 0), date(2026, 10, 27, 17, 0))).belongsTo(today))
  }

  @Test
  fun `DailySchedule syncDecision - what startMonitoring applies now (Swift section)`() {
    assertEquals(
      "inside ⇒ apply from the occurrence start",
      DailySchedule.SyncDecision.Apply(date(2026, 10, 28, 9, 0)),
      DailySchedule.syncDecision(dailyDict, null, date(2026, 10, 28, 10, 0), utc),
    )
    assertEquals("outside ⇒ clear", DailySchedule.SyncDecision.Clear, DailySchedule.syncDecision(dailyDict, null, date(2026, 10, 28, 18, 0), utc))
    assertEquals(
      "inside but on an unselected weekday ⇒ clear",
      DailySchedule.SyncDecision.Clear, DailySchedule.syncDecision(dailyDict, listOf(1.0), date(2026, 10, 28, 10, 0), utc),
    )
    val fridayToSunday = mapOf(
      "intervalStart" to mapOf("hour" to 22.0, "minute" to 0.0, "weekday" to 6.0),
      "intervalEnd" to mapOf("hour" to 6.0, "minute" to 0.0, "weekday" to 1.0),
    )
    assertEquals(
      "Fri 22:00 → Sun 06:00 cannot be computed by the daily twin ⇒ leave to the monitor, never clear",
      DailySchedule.SyncDecision.LeaveToMonitor, DailySchedule.syncDecision(fridayToSunday, null, date(2026, 10, 31, 12, 0), utc),
    )
    assertEquals(
      "an incomplete schedule is not \"outside\" either",
      DailySchedule.SyncDecision.LeaveToMonitor,
      DailySchedule.syncDecision(mapOf("intervalStart" to mapOf("hour" to 9)), null, date(2026, 10, 28, 10, 0), utc),
    )
    assertEquals(
      "the start instant applies",
      DailySchedule.SyncDecision.Apply(date(2026, 10, 28, 9, 0)), DailySchedule.syncDecision(dailyDict, null, date(2026, 10, 28, 9, 0), utc),
    )

    // The marker is written from the occurrence BEFORE the engine's pass; the pass must see the same occurrence.
    val decision = DailySchedule.syncDecision(dailyDict, null, date(2026, 10, 28, 10, 0), utc) as DailySchedule.SyncDecision.Apply
    assertTrue("the app's marker dedupes the engine's start", DailySchedule.isSameOccurrence(date(2026, 10, 28, 10, 0), decision.occurrenceStartMs))
    assertFalse("yesterday's marker does not", DailySchedule.isSameOccurrence(date(2026, 10, 27, 10, 0), decision.occurrenceStartMs))

    // Android: the weekly interval iOS leaves to DeviceActivity is evaluated here (Saturday 2026-10-31 noon is inside Fri 22:00 → Sun 06:00).
    val weekly = MonitoringSchedule.parse(fridayToSunday, null)!!
    assertTrue(weekly is WeeklySchedule)
    assertEquals(DailySchedule.SyncDecision.Apply(date(2026, 10, 30, 22, 0)), weekly.syncDecision(date(2026, 10, 31, 12, 0), utc))
  }

  @Test
  fun `DailySchedule - parsing the JS config (Swift section)`() {
    // JS numbers arrive as Double inside the map.
    val jsSchedule = mapOf(
      "intervalStart" to mapOf("hour" to 9.0, "minute" to 30.0),
      "intervalEnd" to mapOf("hour" to 17.0, "minute" to 0.0),
      "repeats" to true,
    )
    val parsed = DailySchedule.fromConfig(jsSchedule, listOf(2.0, 3.0, 9.0, 0.0, 3.0))
    assertEquals("Double hour/minute are read", TimeOfDay(9, 30, 0), parsed?.start)
    assertEquals("end parsed, second defaults to 0", TimeOfDay(17, 0, 0), parsed?.end)
    assertEquals("weekdays: out-of-range dropped, duplicates collapsed", setOf(2, 3), parsed?.weekdays)
    assertEquals("no weekdays ⇒ every day", emptySet<Int>(), DailySchedule.fromConfig(jsSchedule, null)?.weekdays)
    assertEquals("empty weekdays ⇒ every day", emptySet<Int>(), DailySchedule.fromConfig(jsSchedule, emptyList<Any>())?.weekdays)

    // Stored configs come back from the state document as org.json numbers (iOS: NSNumber).
    val storedSchedule = JSONObject(
      """{"intervalStart":{"hour":22,"minute":0},"intervalEnd":{"hour":6,"minute":15,"second":30}}""",
    )
    val stored = DailySchedule.fromConfig(storedSchedule, org.json.JSONArray(listOf(1, 7)))
    assertTrue("stored components are read", stored?.start == TimeOfDay(22, 0, 0) && stored?.end == TimeOfDay(6, 15, 30))
    assertEquals("stored weekdays are read", setOf(1, 7), stored?.weekdays)

    val weekly = mapOf(
      "intervalStart" to mapOf("hour" to 9, "minute" to 0, "weekday" to 2),
      "intervalEnd" to mapOf("hour" to 17, "minute" to 0, "weekday" to 2),
    )
    assertEquals(
      "a weekday on the components makes the interval weekly (components win over the array)",
      setOf(2), DailySchedule.fromConfig(weekly, listOf(3, 4))?.weekdays,
    )
    val multiDay = mapOf(
      "intervalStart" to mapOf("hour" to 22, "minute" to 0, "weekday" to 6),
      "intervalEnd" to mapOf("hour" to 6, "minute" to 0, "weekday" to 1),
    )
    assertNull("a multi-day weekly interval is not a daily schedule", DailySchedule.fromConfig(multiDay, null))
    assertNull("missing intervalEnd ⇒ null", DailySchedule.fromConfig(mapOf("intervalStart" to mapOf("hour" to 9)), null))
  }

  @Test
  fun `DailySchedule intValue (Swift section)`() {
    assertEquals("Double 9.0 → 9", 9, DailySchedule.intValue(9.0))
    assertEquals("fractional Double truncates, like the module helper in #27", 9, DailySchedule.intValue(9.5))
    assertEquals("Int passes through", 7, DailySchedule.intValue(7))
    assertEquals("Long (org.json) → Int", 3, DailySchedule.intValue(3L))
    assertNull("String → null", DailySchedule.intValue("3"))
    assertNull("null → null", DailySchedule.intValue(null))
    assertNull("Boolean → null", DailySchedule.intValue(true))
  }
}
