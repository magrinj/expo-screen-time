package expo.modules.screentime

import org.json.JSONObject

/**
 * What the shield draws, resolved from a stored `ShieldConfig` the way the
 * iOS `ShieldConfigurationExtension` reads it: the nested format of the
 * contract (`title: { text, color }`) first, then the legacy flat keys
 * (`title`, `titleColor`, `primaryButtonLabel`…) the extension still
 * honours. Colours are `#RRGGBB` or `#RRGGBBAA` (the iOS `UIColor(hex:)`
 * reading); an unparsable colour is ignored, like there. Pure, so the
 * mapping is unit-tested on the JVM; the Activity supplies the defaults a
 * null leaves open (the app's label and icon, the system label colours).
 *
 * `backgroundBlurStyle` is accepted and ignored: the shield is an opaque
 * Activity over the blocked app, there is no window to blur behind it.
 */
internal data class ShieldAppearance(
  val title: Label?,
  val subtitle: Label?,
  val backgroundColor: Int?,
  val icon: Icon,
  val primaryButton: Label?,
  val primaryButtonBackgroundColor: Int?,
  val secondaryButton: Label?,
) {
  data class Label(val text: String, val color: Int?)

  /**
   * This appearance over a restriction target: the library's [title] and
   * [subtitle] in place of the configured texts (the user did not choose
   * these apps, so the shield says what happened and where to turn it off),
   * with the configured colours, icon and buttons kept, except a button
   * whose action a restriction ignores (a "Snooze" label that only closes
   * would lie): the primary one then reads [primaryText] (the library's
   * close label) in its configured colour, the secondary one is left out
   * ([keepSecondaryButton] false). The iOS flags have no shield, so there
   * is no iOS text to match.
   */
  fun forRestriction(title: String, subtitle: String, keepSecondaryButton: Boolean = true, primaryText: String? = null): ShieldAppearance =
    copy(
      title = Label(title, this.title?.color),
      subtitle = Label(subtitle, this.subtitle?.color),
      primaryButton = if (primaryText != null) Label(primaryText, primaryButton?.color) else primaryButton,
      secondaryButton = if (keepSecondaryButton) secondaryButton else null,
    )

  sealed class Icon {
    /** No `icon` key: the shielded app's own icon, as the iOS default shield shows it. */
    object App : Icon()

    /** A documented SF Symbol name, mapped to the Material Symbol drawable [resource] (`expo_st_ic_*`). */
    data class Symbol(val resource: String) : Icon()
  }

  companion object {
    val DEFAULT = ShieldAppearance(null, null, null, Icon.App, null, null, null)

    /**
     * The SF Symbols the shield maps to a bundled Material Symbol. Any other
     * name falls back to the app icon ("Shield":
     * "for a documented set, else the app icon"). The website's
     * shields page lists this table; keep them in step.
     */
    val SYMBOLS: Map<String, String> = mapOf(
      "hand.raised.fill" to "expo_st_ic_hand",
      "hand.raised" to "expo_st_ic_hand",
      "lock.fill" to "expo_st_ic_lock",
      "lock" to "expo_st_ic_lock",
      "nosign" to "expo_st_ic_block",
      "clock.fill" to "expo_st_ic_clock",
      "clock" to "expo_st_ic_clock",
      "hourglass" to "expo_st_ic_hourglass",
      "moon.fill" to "expo_st_ic_moon",
      "moon.zzz.fill" to "expo_st_ic_moon",
      "exclamationmark.triangle.fill" to "expo_st_ic_warning",
    )

    fun from(config: JSONObject?): ShieldAppearance {
      if (config == null) return DEFAULT
      return ShieldAppearance(
        title = label(config, "title", "title", "titleColor"),
        subtitle = label(config, "subtitle", "subtitle", "subtitleColor"),
        backgroundColor = color(config.optString("backgroundColor")),
        icon = config.optString("icon").takeIf { it.isNotEmpty() }?.let { SYMBOLS[it] }?.let { Icon.Symbol(it) } ?: Icon.App,
        primaryButton = label(config, "primaryButton", "primaryButtonLabel", "primaryButtonColor"),
        primaryButtonBackgroundColor = color(config.optJSONObject("primaryButton")?.optString("backgroundColor"))
          ?: color(config.optString("primaryButtonBackgroundColor")),
        secondaryButton = label(config, "secondaryButton", "secondaryButtonLabel", "secondaryButtonColor"),
      )
    }

    /** The nested `{ text, color }` object under [nested], else the legacy flat [textKey] + [colorKey]. */
    private fun label(config: JSONObject, nested: String, textKey: String, colorKey: String): Label? {
      config.optJSONObject(nested)?.let { obj ->
        val text = obj.optString("text").takeIf { it.isNotEmpty() } ?: return null
        return Label(text, color(obj.optString("color")))
      }
      val text = (config.opt(textKey) as? String)?.takeIf { it.isNotEmpty() } ?: return null
      return Label(text, color(config.optString(colorKey)))
    }

    /** `#RRGGBB` / `#RRGGBBAA` (with or without `#`) as an ARGB int; null otherwise. */
    fun color(hex: String?): Int? {
      val digits = hex?.trim()?.removePrefix("#") ?: return null
      if (!digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
      return when (digits.length) {
        6 -> (0xFF shl 24) or digits.toInt(16)
        8 -> {
          val rgba = digits.toLong(16)
          val alpha = (rgba and 0xFF).toInt()
          val rgb = (rgba shr 8).toInt()
          (alpha shl 24) or rgb
        }
        else -> null
      }
    }
  }
}
