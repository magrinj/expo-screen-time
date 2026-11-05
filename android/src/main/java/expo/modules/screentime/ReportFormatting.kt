package expo.modules.screentime

import java.util.Calendar
import java.util.TimeZone

/** The `durationStyle` prop of `Report.Text`: the `DateComponentsFormatter.UnitsStyle` iOS picks (`formatDuration`). */
internal enum class ReportDurationStyle {
  ABBREVIATED,
  SHORT,
  FULL,
  ;

  companion object {
    /** `"short"` and `"full"` as themselves, anything else `abbreviated`, the iOS default branch. */
    fun of(prop: String?): ReportDurationStyle = when (prop) {
      "short" -> SHORT
      "full" -> FULL
      else -> ABBREVIATED
    }
  }
}

/**
 * The locale-independent half of the iOS `formatDuration`
 * (`CustomTemplateReport.swift`): which units and which numbers. iOS sets
 * `allowedUnits = duration >= 3600 ? [.hour, .minute] : [.minute]` and
 * `zeroFormattingBehavior = .dropLeading`, and `DateComponentsFormatter`
 * truncates to the smallest unit (measured with Foundation: 3599 s → "59m",
 * 3630 s → "1h 0m", 59 s → "0m"). So:
 *
 * - under an hour, [hours] is null and [minutes] is the whole minutes
 *   (0 included: "0m", never an empty string);
 * - from an hour, both, and a zero minute is kept ("1h 0m": `dropLeading`
 *   drops leading zeros only);
 * - negative durations clamp to 0 (`max(duration, 0)`).
 */
internal data class ReportDurationParts(val hours: Long?, val minutes: Long) {
  companion object {
    fun of(seconds: Long): ReportDurationParts {
      val s = seconds.coerceAtLeast(0)
      return if (s >= 3600) ReportDurationParts(s / 3600, (s % 3600) / 60) else ReportDurationParts(null, s / 60)
    }

    /** From milliseconds: whole seconds first, as the iOS `TimeInterval` arrives already truncated by the formatter. */
    fun ofMillis(ms: Long): ReportDurationParts = of(ms.coerceAtLeast(0) / 1000)
  }
}

/**
 * The locale-dependent text of a report: the words of a duration and the
 * short time of `firstPickup` (`DateFormatter` with `timeStyle = .short`).
 * The renderer brings the Android implementation (ICU `MeasureFormat` /
 * `DateFormat`); [ReportEnglishText] is the pure en-US reference the tests
 * pin to Foundation's output.
 */
internal interface ReportText {
  fun duration(parts: ReportDurationParts, style: ReportDurationStyle): String

  /** The time of day of `epochMs`, as `.short` renders it ("9:41 AM" in en-US, "09:41" in fr-FR). */
  fun time(epochMs: Long): String

  companion object {
    /** What `firstPickup` renders when there was none (`CustomTemplateReport.swift`: `value = "—"`). */
    const val NO_PICKUP = "\u2014"
  }
}

/**
 * en-US, byte for byte what Foundation produces (`.proofs/swift/format_probe.swift`
 * on macOS 27, same ICU data as iOS 26):
 *
 * | seconds | abbreviated | short | full |
 * |---|---|---|---|
 * | 0 | `0m` | `0 min` | `0 minutes` |
 * | 60 | `1m` | `1 min` | `1 minute` |
 * | 3600 | `1h 0m` | `1 hr, 0 min` | `1 hour, 0 minutes` |
 * | 5040 | `1h 24m` | `1 hr, 24 min` | `1 hour, 24 minutes` |
 *
 * (The docs page says `short` is "1:24"; Foundation's `.short` is "1 hr,
 * 24 min" and "1:24" is `.positional`, which the template never selects.)
 * The time uses the narrow no-break space (U+202F) before AM/PM, as
 * Foundation and ICU 72+ both do: "9:41 AM".
 */
internal class ReportEnglishText(private val zone: TimeZone) : ReportText {
  override fun duration(parts: ReportDurationParts, style: ReportDurationStyle): String {
    val h = parts.hours
    val m = parts.minutes
    return when (style) {
      ReportDurationStyle.ABBREVIATED -> if (h == null) "${m}m" else "${h}h ${m}m"
      ReportDurationStyle.SHORT -> if (h == null) "$m min" else "$h hr, $m min"
      ReportDurationStyle.FULL -> {
        val minutes = "$m ${if (m == 1L) "minute" else "minutes"}"
        if (h == null) minutes else "$h ${if (h == 1L) "hour" else "hours"}, $minutes"
      }
    }
  }

  override fun time(epochMs: Long): String {
    val calendar = Calendar.getInstance(zone)
    calendar.timeInMillis = epochMs
    val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
    val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    val minute = calendar.get(Calendar.MINUTE)
    return "$hour12:${minute.toString().padStart(2, '0')}\u202F${if (hour24 < 12) "AM" else "PM"}"
  }
}
