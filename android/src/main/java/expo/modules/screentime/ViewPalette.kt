package expo.modules.screentime

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue

/**
 * The colours of the native views, by the system's light/dark setting
 * (`uiMode`, which React Native's `useColorScheme` reads too). Fixed values
 * rather than theme attributes: the host activity's theme is the consumer's
 * (not always DayNight-aware), and the iOS views follow the system scheme
 * the same way (`.systemBackground`, `Label`).
 */
internal class ViewPalette(val dark: Boolean) {
  val background = if (dark) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
  val text = if (dark) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt()
  val secondary = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
  val field = if (dark) 0xFF2B2930.toInt() else 0xFFECE6F0.toInt()
  val divider = if (dark) 0x33FFFFFF else 0x1F000000
  /** The shield's default button colour (Material blue 600 / 200). */
  val accent = if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()

  companion object {
    fun of(context: Context): ViewPalette {
      val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
      return ViewPalette(mode == Configuration.UI_MODE_NIGHT_YES)
    }
  }
}

internal fun Context.dp(value: Int): Int =
  TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
