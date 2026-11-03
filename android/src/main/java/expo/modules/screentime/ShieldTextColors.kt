package expo.modules.screentime

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The shield's default text colours when the `ShieldConfig` sets a background
 * but leaves a text colour open: chosen for contrast against that background
 * rather than taken from the system light/dark palette, which knows nothing
 * about it (a dark `backgroundColor` in light mode used to get black text).
 * The same rule, with the same arithmetic, is `ios/ShieldTextColors.swift`;
 * `scripts/swift-tests/fixtures/shield-text-colors.tsv` holds the cases both
 * must agree on.
 *
 * - **Title** (and the symbol tint, which follows it): pure white or pure
 *   black, whichever has the higher WCAG 2 contrast ratio against the
 *   background. One of the two always reaches at least 4.58:1 against any
 *   opaque colour (the worst case is the mid grey where they tie), so the
 *   title clears WCAG AA for normal text (4.5:1) by construction.
 * - **Subtitle**: the title colour mixed into the background at 70%, the way
 *   iOS's `secondaryLabel` is the label colour at reduced opacity, so it reads
 *   as secondary; where that mix falls under 3:1 (saturated mid tones, mid
 *   greys), the mix is made more opaque in 5% steps until it reaches 3:1, the
 *   AA floor for large and secondary text. At 100% it is the title colour, so
 *   the floor always holds.
 * - **Primary button label**: against the button's own background when the
 *   config sets one (the shield background is irrelevant to a label on a
 *   button), with or without a shield background. On Android the button
 *   the config leaves unpainted is still painted by the library, in the
 *   palette's accent, so the same rule runs against that colour
 *   ([resolve] with `defaultPrimaryButtonBackground`: white used to be
 *   drawn on the dark-mode light blue, about 2.1:1). The iOS extension
 *   cannot read the colour the system paints its button in, and the
 *   system's own default label is white, so it keeps `.white` there.
 * - **Secondary button label**: a text-only button over the shield background,
 *   so the title's colour.
 * - A colour the config sets is always kept, readable or not; a text colour
 *   that does not parse counts as unset ([ShieldAppearance] drops it).
 * - No background set: null, and the view keeps today's system-adaptive
 *   default ([ViewPalette]).
 * - A translucent background (`#RRGGBBAA`) is judged by its RGB alone. What
 *   shows through it differs by platform (on iOS the blur over the blocked
 *   app, on Android the opaque window, so black) and cannot be known here;
 *   set the text colours explicitly when the background is translucent.
 */
internal object ShieldTextColors {
  const val WHITE = 0xFFFFFFFF.toInt()
  const val BLACK = 0xFF000000.toInt()

  /** AA for normal text, the title's bar. */
  const val TITLE_MIN_CONTRAST = 4.5

  /** AA for large text, the subtitle's bar. */
  const val SUBTITLE_MIN_CONTRAST = 3.0

  /** The colours the view draws with; null keeps the platform default for that element. */
  data class Resolved(val title: Int?, val subtitle: Int?, val primaryButton: Int?, val secondaryButton: Int?)

  /**
   * The rule applied to a parsed config (see the class comment).
   * [defaultPrimaryButtonBackground] is the colour the view paints the
   * primary button in when the config sets none ([ViewPalette.accent]):
   * the label is chosen against the background the button really has.
   * Null (the parity fixture's case) keeps the label null.
   */
  fun resolve(appearance: ShieldAppearance, defaultPrimaryButtonBackground: Int? = null): Resolved = resolve(
    background = appearance.backgroundColor,
    title = appearance.title?.color,
    subtitle = appearance.subtitle?.color,
    primaryButtonBackground = appearance.primaryButtonBackgroundColor ?: defaultPrimaryButtonBackground,
    primaryButton = appearance.primaryButton?.color,
    secondaryButton = appearance.secondaryButton?.color,
  )

  fun resolve(
    background: Int?,
    title: Int?,
    subtitle: Int?,
    primaryButtonBackground: Int?,
    primaryButton: Int?,
    secondaryButton: Int?,
  ): Resolved = Resolved(
    title = title ?: background?.let(::onBackground),
    subtitle = subtitle ?: background?.let(::secondaryOn),
    primaryButton = primaryButton ?: primaryButtonBackground?.let(::onBackground),
    secondaryButton = secondaryButton ?: background?.let(::onBackground),
  )

  /** White or black, whichever contrasts more with [background] (its alpha ignored). */
  fun onBackground(background: Int): Int =
    if (contrast(WHITE, background) >= contrast(BLACK, background)) WHITE else BLACK

  /** The secondary text colour over [background]: the title colour mixed in at 70%, more if 3:1 needs it. */
  fun secondaryOn(background: Int): Int {
    val text = onBackground(background)
    for (percent in 70..100 step 5) {
      val mixed = mix(text, background, percent)
      if (contrast(mixed, background) >= SUBTITLE_MIN_CONTRAST) return mixed
    }
    return text
  }

  /** WCAG 2 contrast ratio between two colours, alpha ignored: 1 (same) to 21 (black on white). */
  fun contrast(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
  }

  /** WCAG 2 relative luminance of an sRGB colour, 0 (black) to 1 (white). */
  fun relativeLuminance(argb: Int): Double =
    0.2126 * linear(argb shr 16 and 0xFF) + 0.7152 * linear(argb shr 8 and 0xFF) + 0.0722 * linear(argb and 0xFF)

  private fun linear(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
  }

  /** [percent] of [foreground] over the rest of [background], per channel, in integers so both platforms round alike; opaque. */
  private fun mix(foreground: Int, background: Int, percent: Int): Int {
    fun channel(shift: Int): Int {
      val f = foreground shr shift and 0xFF
      val b = background shr shift and 0xFF
      return (percent * f + (100 - percent) * b + 50) / 100
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }
}
