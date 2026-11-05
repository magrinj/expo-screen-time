package expo.modules.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The report window against `ReportContainerView.createFilter`: the expected
 * instants were produced by Foundation (`.proofs/swift/format_probe.swift`,
 * `Calendar.current` with the same locale and zone).
 */
class ReportWindowTest {
  private val enUS = Locale.US
  private val frFR = Locale.FRANCE

  @Test
  fun `daily is the calendar day of the filter date, cut into 24 hours`() {
    val window = ReportWindow.of("daily", at(2026, 11, 4, 9, 11, zone = PARIS), PARIS, enUS)
    assertEquals(at(2026, 11, 4, 0, 0, zone = PARIS), window.startMs)
    assertEquals(at(2026, 11, 5, 0, 0, zone = PARIS), window.endMs)
    assertEquals(24, window.segments.size)
    assertEquals((0..23).toList(), window.segments.map { it.hourOfDay })
    assertTrue(window.segments.all { it.durationMs == 3_600_000L })
    assertEquals(window.startMs, window.segments.first().startMs)
    assertEquals(window.endMs, window.segments.last().endMs)
    assertEquals("segments are contiguous", window.segments.zipWithNext().count { (a, b) -> a.endMs != b.startMs }, 0)
  }

  @Test
  fun `the autumn DST day has 25 segments and the repeated hour twice`() {
    // 2026-10-25, Europe/Paris: 03:00 CEST → 02:00 CET. Foundation: 00:00+02:00 → next 00:00+01:00, 25 h.
    val window = ReportWindow.of("daily", at(2026, 10, 25, 12, 0, zone = PARIS), PARIS, enUS)
    assertEquals(at(2026, 10, 25, 0, 0, zone = PARIS), window.startMs)
    assertEquals(at(2026, 10, 26, 0, 0, zone = PARIS), window.endMs)
    assertEquals(25 * 3_600_000L, window.durationMs)
    assertEquals(25, window.segments.size)
    assertEquals("hour 2 occurs twice: the iOS `hourly[2]` bucket receives both", 2, window.segments.count { it.hourOfDay == 2 })
    assertEquals(listOf(0, 1, 2, 2, 3), window.segments.take(5).map { it.hourOfDay })
  }

  @Test
  fun `the spring DST day has 23 segments and no hour 2`() {
    // 2026-03-29, Europe/Paris: 02:00 CET → 03:00 CEST.
    val window = ReportWindow.of("daily", at(2026, 3, 29, 12, 0, zone = PARIS), PARIS, enUS)
    assertEquals(23 * 3_600_000L, window.durationMs)
    assertEquals(23, window.segments.size)
    assertEquals(0, window.segments.count { it.hourOfDay == 2 })
    assertEquals(listOf(0, 1, 3, 4), window.segments.take(4).map { it.hourOfDay })
  }

  @Test
  fun `weekly starts on the locale's first weekday - Sunday in en-US, Monday in fr-FR`() {
    // Wednesday 2026-10-28 10:00 Paris. Foundation en_US: 2026-10-25T00:00+02:00 → 2026-11-01T00:00+01:00 (169 h);
    // fr_FR: 2026-10-26T00:00+01:00 → 2026-11-02T00:00+01:00 (168 h).
    val wednesday = at(2026, 10, 28, 10, 0, zone = PARIS)
    val us = ReportWindow.of("weekly", wednesday, PARIS, enUS)
    assertEquals(at(2026, 10, 25, 0, 0, zone = PARIS), us.startMs)
    assertEquals(at(2026, 11, 1, 0, 0, zone = PARIS), us.endMs)
    assertEquals("the DST week is 169 hours", 169, us.segments.size)
    assertEquals(169 * 3_600_000L, us.durationMs)

    val fr = ReportWindow.of("weekly", wednesday, PARIS, frFR)
    assertEquals(at(2026, 10, 26, 0, 0, zone = PARIS), fr.startMs)
    assertEquals(at(2026, 11, 2, 0, 0, zone = PARIS), fr.endMs)
    assertEquals(168, fr.segments.size)
    assertEquals("every hour of day appears 7 times in a plain week", (0..23).map { 7 }, (0..23).map { h -> fr.segments.count { it.hourOfDay == h } })
  }

  @Test
  fun `a Sunday belongs to the week that starts on it in en-US and to the one before in fr-FR`() {
    val sunday = at(2026, 11, 1, 10, 0, zone = PARIS)
    assertEquals(at(2026, 11, 1, 0, 0, zone = PARIS), ReportWindow.of("weekly", sunday, PARIS, enUS).startMs)
    assertEquals(at(2026, 10, 26, 0, 0, zone = PARIS), ReportWindow.of("weekly", sunday, PARIS, frFR).startMs)
    // A Monday in fr-FR is its own week start; in en-US the week began the day before.
    val monday = at(2026, 10, 26, 23, 59, zone = PARIS)
    assertEquals(at(2026, 10, 26, 0, 0, zone = PARIS), ReportWindow.of("weekly", monday, PARIS, frFR).startMs)
    assertEquals(at(2026, 10, 25, 0, 0, zone = PARIS), ReportWindow.of("weekly", monday, PARIS, enUS).startMs)
  }

  @Test
  fun `the zone is the device's, not the JVM's`() {
    val instant = at(2026, 11, 4, 23, 30, zone = UTC) // 2026-11-05 00:30 in Paris
    assertEquals(at(2026, 11, 4, 0, 0, zone = UTC), ReportWindow.of("daily", instant, UTC, enUS).startMs)
    assertEquals(at(2026, 11, 5, 0, 0, zone = PARIS), ReportWindow.of("daily", instant, PARIS, enUS).startMs)
  }

  @Test
  fun `an unknown segment falls back to the current day, as iOS does, ignoring the filter date`() {
    val now = at(2026, 11, 4, 9, 0, zone = PARIS)
    val window = ReportWindow.of("monthly", at(2026, 1, 1, 0, 0, zone = PARIS), PARIS, enUS, nowMs = now)
    assertEquals(at(2026, 11, 4, 0, 0, zone = PARIS), window.startMs)
    assertEquals(24, window.segments.size)
    assertEquals(window.startMs, ReportWindow.of(null, 0, PARIS, enUS, nowMs = now).startMs)
  }

  @Test
  fun `hourly segments of an arbitrary range are clipped to its end`() {
    val start = at(2026, 11, 4, 0, 0, zone = UTC)
    val segments = ReportWindow.hourly(start, start + 90 * 60_000L, UTC)
    assertEquals(2, segments.size)
    assertEquals(3_600_000L, segments[0].durationMs)
    assertEquals(30 * 60_000L, segments[1].durationMs)
    assertTrue(ReportWindow.hourly(start, start, UTC).isEmpty())
    assertNull(ReportWindow.hourly(start, start - 1, UTC).firstOrNull())
  }
}
