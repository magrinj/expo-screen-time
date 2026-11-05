package expo.modules.screentime

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The device-locale [ReportText]: Foundation's `DateComponentsFormatter`
 * and `DateFormatter(timeStyle: .short)` are ICU underneath, and so is
 * `android.icu` (API 24+), so the same data produces the same strings.
 * `abbreviated` is `FormatWidth.NARROW` ("1h 24m"), `short` is `SHORT`
 * ("1 hr, 24 min"), `full` is `WIDE` ("1 hour, 24 minutes"); the units are
 * the ones [ReportDurationParts] already chose (hours only from an hour,
 * a zero minute kept). The time of `firstPickup` follows the device's
 * 12/24-hour setting (`DateFormat.getTimeFormat`), as `.short` follows the
 * iOS one. [ReportEnglishText] stays the JVM-testable reference.
 */
internal class ReportIcuText(private val context: Context, private val locale: Locale, private val zone: TimeZone) : ReportText {
  private val formats = HashMap<ReportDurationStyle, MeasureFormat>(3)

  override fun duration(parts: ReportDurationParts, style: ReportDurationStyle): String {
    val format = formats.getOrPut(style) {
      MeasureFormat.getInstance(
        locale,
        when (style) {
          ReportDurationStyle.ABBREVIATED -> MeasureFormat.FormatWidth.NARROW
          ReportDurationStyle.SHORT -> MeasureFormat.FormatWidth.SHORT
          ReportDurationStyle.FULL -> MeasureFormat.FormatWidth.WIDE
        },
      )
    }
    val hours = parts.hours
    return if (hours == null) {
      format.formatMeasures(Measure(parts.minutes, MeasureUnit.MINUTE))
    } else {
      format.formatMeasures(Measure(hours, MeasureUnit.HOUR), Measure(parts.minutes, MeasureUnit.MINUTE))
    }
  }

  override fun time(epochMs: Long): String {
    val format = android.text.format.DateFormat.getTimeFormat(context)
    format.timeZone = zone
    return format.format(Date(epochMs))
  }
}
