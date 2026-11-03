package expo.modules.screentime

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue

/**
 * The colours of the native views, by the light/dark mode of the context
 * they draw in (`uiMode`): for the picker and the labels the host activity's
 * effective mode, the one React Native's `useColorScheme` reads too (the
 * system's, or what `expo-system-ui` / `Appearance.setColorScheme` forced
 * through AppCompat's night mode); for the shield, a plain Activity in the
 * engine's process, the system's. Fixed values rather than theme attributes:
 * the host activity's theme is the consumer's (not always DayNight-aware),
 * and the iOS views follow their trait collection the same way
 * (`.systemBackground`, `Label`).
 */
internal class ViewPalette(val dark: Boolean) {
  val background = if (dark) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
  val text = if (dark) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt()
  val secondary = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
  val field = if (dark) 0xFF2B2930.toInt() else 0xFFECE6F0.toInt()
  val divider = if (dark) 0x33FFFFFF else 0x1F000000
  /**
   * The shield's default button colour (Google Blue 700 in light mode, 300
   * in dark mode) and the secondary button's text. The label on it is
   * white or black by [ShieldTextColors.onBackground]: white on the light
   * blue is 5.4:1, black on the dark-mode blue 10:1 (white there is 2.1:1,
   * and white on Blue 600 `#1A73E8` was 4.49:1, a hair under AA).
   */
  val accent = if (dark) 0xFF8AB4F8.toInt() else 0xFF1967D2.toInt()

  companion object {
    fun of(context: Context): ViewPalette {
      val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
      return ViewPalette(mode == Configuration.UI_MODE_NIGHT_YES)
    }
  }
}

internal fun Context.dp(value: Int): Int =
  TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
