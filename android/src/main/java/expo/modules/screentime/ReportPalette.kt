package expo.modules.screentime

import android.content.Context
import android.content.res.Configuration

/**
 * The default colours of a rendered report, by light/dark mode: the iOS
 * system colours the SwiftUI renderer falls back to (`Color(.label)`,
 * `.secondaryLabel`, `.separator`, `.tertiarySystemFill`, `.systemGray`,
 * `.blue`, `.systemRed`) and Swift Charts' categorical palette, so a
 * template that sets no colour looks the same on both platforms. The mode
 * is the activity's effective one (`uiMode`), what `useColorScheme` reports
 * and what [ViewPalette] uses for the picker and the labels.
 */
internal class ReportPalette(val dark: Boolean) {
  /** `UIColor.label`. */
  val label = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
  /** `UIColor.secondaryLabel` (60 % of a dark grey, composited on the usual background). */
  val secondaryLabel = if (dark) 0xFF98989F.toInt() else 0xFF8A8A8E.toInt()
  /** `UIColor.separator`. */
  val separator = if (dark) 0x99545458.toInt() else 0x4A3C3C43
  /** `UIColor.tertiarySystemFill`. */
  val tertiaryFill = if (dark) 0x3D767680 else 0x1F767680
  /** `UIColor.systemGray`. */
  val systemGray = if (dark) 0xFF8E8E93.toInt() else 0xFF8E8E93.toInt()
  /** `Color.blue`. */
  val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
  /** `UIColor.systemRed`. */
  val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
  /** Swift Charts' default categorical palette, in order (blue, green, orange, purple, red, teal, yellow, pink). */
  val categorical: List<Int> = if (dark) {
    listOf(0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFF453A, 0xFF64D2FF, 0xFFFFD60A, 0xFFFF375F).map(Long::toInt)
  } else {
    listOf(0xFF007AFF, 0xFF34C759, 0xFFFF9500, 0xFFAF52DE, 0xFFFF3B30, 0xFF5AC8FA, 0xFFFFCC00, 0xFFFF2D55).map(Long::toInt)
  }

  companion object {
    fun of(context: Context): ReportPalette {
      val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
      return ReportPalette(mode == Configuration.UI_MODE_NIGHT_YES)
    }
  }
}

/**
 * The SF Symbols a `Report.Symbol` renders on Android, each as a bundled
 * Material Symbol (`res/drawable/expo_st_ic_*`, Apache 2.0): the shield's
 * table ([ShieldAppearance.SYMBOLS]) plus the names the report templates
 * use. Any other name renders an empty slot of the requested size, so the
 * layout around it holds (a wrong glyph would say something the template
 * did not). The website's templates page lists this table; keep them in
 * step.
 */
internal object ReportSymbols {
  val DRAWABLES: Map<String, String> = ShieldAppearance.SYMBOLS + mapOf(
    "hourglass.bottomhalf.filled" to "expo_st_ic_hourglass_bottom",
    "hourglass.tophalf.filled" to "expo_st_ic_hourglass_bottom",
    "chart.bar.fill" to "expo_st_ic_bar_chart",
    "chart.bar" to "expo_st_ic_bar_chart",
    "chart.bar.xaxis" to "expo_st_ic_bar_chart",
    "chart.pie.fill" to "expo_st_ic_pie_chart",
    "chart.pie" to "expo_st_ic_pie_chart",
    "apps.iphone" to "expo_st_ic_apps",
    "square.grid.2x2.fill" to "expo_st_ic_apps",
    "square.grid.2x2" to "expo_st_ic_apps",
    "iphone" to "expo_st_ic_phone",
    "questionmark" to "expo_st_ic_question_mark",
    "questionmark.circle" to "expo_st_ic_question_mark",
    "timer" to "expo_st_ic_timer",
    "star.fill" to "expo_st_ic_star",
    "star" to "expo_st_ic_star",
    "bell.fill" to "expo_st_ic_bell",
    "bell" to "expo_st_ic_bell",
    "eye.fill" to "expo_st_ic_eye",
    "eye" to "expo_st_ic_eye",
    "checkmark.circle.fill" to "expo_st_ic_check_circle",
    "checkmark.circle" to "expo_st_ic_check_circle",
    "arrow.up" to "expo_st_ic_arrow_up",
    "arrow.down" to "expo_st_ic_arrow_down",
    "calendar" to "expo_st_ic_calendar",
    "gamecontroller.fill" to "expo_st_ic_game",
    "gamecontroller" to "expo_st_ic_game",
  )

  /** The drawable resource id for an SF Symbol name, or 0 when the table has no row for it. */
  fun resourceId(context: Context, name: String): Int {
    val drawable = DRAWABLES[name] ?: return 0
    return context.resources.getIdentifier(drawable, "drawable", context.packageName)
  }
}
