package expo.modules.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The formatting rules against Foundation's own output: every expected
 * string below was printed by `DateComponentsFormatter` / `DateFormatter`
 * configured as `CustomTemplateReport.formatDuration` and the `firstPickup`
 * branch configure them (macOS 27).
 */
class ReportFormattingTest {
  private val en = ReportEnglishText(PARIS)

  @Test
  fun `units are minutes under an hour and hours plus minutes from an hour, truncated, never empty`() {
    assertEquals(ReportDurationParts(null, 0), ReportDurationParts.of(0))
    assertEquals("negative clamps to zero", ReportDurationParts(null, 0), ReportDurationParts.of(-5))
    assertEquals("59 s is still 0 minutes (truncation, as Foundation does)", ReportDurationParts(null, 0), ReportDurationParts.of(59))
    assertEquals(ReportDurationParts(null, 1), ReportDurationParts.of(60))
    assertEquals(ReportDurationParts(null, 1), ReportDurationParts.of(119))
    assertEquals(ReportDurationParts(null, 59), ReportDurationParts.of(3599))
    assertEquals("the hour turns and the zero minute stays", ReportDurationParts(1, 0), ReportDurationParts.of(3600))
    assertEquals(ReportDurationParts(1, 0), ReportDurationParts.of(3659))
    assertEquals(ReportDurationParts(1, 1), ReportDurationParts.of(3660))
    assertEquals(ReportDurationParts(1, 24), ReportDurationParts.of(5040))
    assertEquals(ReportDurationParts(25, 1), ReportDurationParts.of(90061))
    assertEquals("milliseconds are whole seconds first", ReportDurationParts(null, 0), ReportDurationParts.ofMillis(59_999))
    assertEquals(ReportDurationParts(null, 1), ReportDurationParts.ofMillis(60_000))
  }

  @Test
  fun `en-US strings match Foundation for the three styles`() {
    val cases = listOf(
      // seconds, abbreviated, short, full — the probe's table
      Triple(0L, "0m", "0 min" to "0 minutes"),
      Triple(60L, "1m", "1 min" to "1 minute"),
      Triple(120L, "2m", "2 min" to "2 minutes"),
      Triple(1500L, "25m", "25 min" to "25 minutes"),
      Triple(3599L, "59m", "59 min" to "59 minutes"),
      Triple(3600L, "1h 0m", "1 hr, 0 min" to "1 hour, 0 minutes"),
      Triple(3660L, "1h 1m", "1 hr, 1 min" to "1 hour, 1 minute"),
      Triple(5040L, "1h 24m", "1 hr, 24 min" to "1 hour, 24 minutes"),
      Triple(7200L, "2h 0m", "2 hr, 0 min" to "2 hours, 0 minutes"),
      Triple(86399L, "23h 59m", "23 hr, 59 min" to "23 hours, 59 minutes"),
      Triple(90061L, "25h 1m", "25 hr, 1 min" to "25 hours, 1 minute"),
      Triple(360000L, "100h 0m", "100 hr, 0 min" to "100 hours, 0 minutes"),
    )
    for ((seconds, abbreviated, rest) in cases) {
      val parts = ReportDurationParts.of(seconds)
      assertEquals("$seconds s abbreviated", abbreviated, en.duration(parts, ReportDurationStyle.ABBREVIATED))
      assertEquals("$seconds s short", rest.first, en.duration(parts, ReportDurationStyle.SHORT))
      assertEquals("$seconds s full", rest.second, en.duration(parts, ReportDurationStyle.FULL))
    }
  }

  @Test
  fun `the duration style prop maps like the iOS switch`() {
    assertEquals(ReportDurationStyle.SHORT, ReportDurationStyle.of("short"))
    assertEquals(ReportDurationStyle.FULL, ReportDurationStyle.of("full"))
    assertEquals(ReportDurationStyle.ABBREVIATED, ReportDurationStyle.of("abbreviated"))
    assertEquals("anything else is the default branch", ReportDurationStyle.ABBREVIATED, ReportDurationStyle.of("positional"))
    assertEquals(ReportDurationStyle.ABBREVIATED, ReportDurationStyle.of(null))
  }

  @Test
  fun `the first pickup renders as a short en-US time with the narrow no-break space Foundation uses`() {
    assertEquals("9:41\u202FAM", en.time(at(2026, 10, 28, 9, 41, zone = PARIS)))
    assertEquals("12:05\u202FAM", en.time(at(2026, 10, 28, 0, 5, zone = PARIS)))
    assertEquals("1:07\u202FPM", en.time(at(2026, 10, 28, 13, 7, zone = PARIS)))
    assertEquals("12:00\u202FPM", en.time(at(2026, 10, 28, 12, 0, zone = PARIS)))
    assertEquals("11:59\u202FPM", en.time(at(2026, 10, 28, 23, 59, zone = PARIS)))
    assertEquals("the zone is the formatter's, not the JVM's", "10:41\u202FAM", ReportEnglishText(UTC).time(at(2026, 10, 28, 9, 41, zone = UTC).let { it + 3_600_000L }))
    assertEquals("—", ReportText.NO_PICKUP)
  }

  /**
   * fr-FR, as Foundation renders the same durations (the probe): the Android
   * implementation (ICU) must produce these from the same parts. Pinned here
   * through a stub so the expected strings are on record.
   */
  @Test
  fun `fr-FR expectations - the strings an ICU implementation must produce`() {
    val fr = object : ReportText {
      override fun duration(parts: ReportDurationParts, style: ReportDurationStyle): String {
        val h = parts.hours
        val m = parts.minutes
        return when (style) {
          ReportDurationStyle.ABBREVIATED -> if (h == null) "${m}min" else "${h}h ${m}min"
          ReportDurationStyle.SHORT -> if (h == null) "$m min" else "$h h et $m min"
          ReportDurationStyle.FULL -> {
            val minutes = "$m ${if (m <= 1L) "minute" else "minutes"}"
            if (h == null) minutes else "$h ${if (h <= 1L) "heure" else "heures"} et $minutes"
          }
        }
      }

      override fun time(epochMs: Long): String = "09:41"
    }
    val expected = mapOf(
      0L to Triple("0min", "0 min", "0 minute"),
      60L to Triple("1min", "1 min", "1 minute"),
      120L to Triple("2min", "2 min", "2 minutes"),
      3600L to Triple("1h 0min", "1 h et 0 min", "1 heure et 0 minute"),
      5040L to Triple("1h 24min", "1 h et 24 min", "1 heure et 24 minutes"),
      7200L to Triple("2h 0min", "2 h et 0 min", "2 heures et 0 minute"),
    )
    for ((seconds, strings) in expected) {
      val parts = ReportDurationParts.of(seconds)
      assertEquals(strings.first, fr.duration(parts, ReportDurationStyle.ABBREVIATED))
      assertEquals(strings.second, fr.duration(parts, ReportDurationStyle.SHORT))
      assertEquals(strings.third, fr.duration(parts, ReportDurationStyle.FULL))
    }
    assertEquals("fr-FR `.short` time is 24-hour, zero-padded", "09:41", fr.time(0))
  }

  @Test
  fun `hex colors parse like Color(hex)`() {
    assertEquals(0xFFFF6B00.toInt(), ReportProps.color("#FF6B00"))
    assertEquals("the hash is optional", 0xFFFF6B00.toInt(), ReportProps.color("FF6B00"))
    assertEquals("whitespace is trimmed", 0xFFFF6B00.toInt(), ReportProps.color("  #ff6b00 "))
    assertEquals("RRGGBBAA → alpha last in the hex, first in ARGB", 0x80FF6B00.toInt(), ReportProps.color("#FF6B0080"))
    assertNull(ReportProps.color("#FFF"))
    assertNull(ReportProps.color("#GG0000"))
    assertNull(ReportProps.color(""))
    assertNull(ReportProps.color(null))
  }
}
