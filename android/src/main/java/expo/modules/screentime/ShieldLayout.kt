package expo.modules.screentime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The shield's view tree, built the same way for the [ShieldActivity] and
 * for the warmer's dry run ([ShieldWarmer]), so what the warmer touches —
 * the view classes, the theme's resources, the font shaping of the first
 * text layout, the drawables' draw code (recorded, not rasterized) — is the
 * code the first frame runs. The appearance is resolved by the caller ([ShieldAppearance]
 * with [ShieldTextColors] over the [ViewPalette]); the blocked app's face
 * fills what the config leaves open, as the default iOS shield does.
 */
internal object ShieldLayout {
  /** The colours resolved for an appearance: the background and the texts over it. */
  class Colors(val background: Int, val text: ShieldTextColors.Resolved, val palette: ViewPalette)

  fun colors(context: Context, appearance: ShieldAppearance): Colors {
    val palette = ViewPalette.of(context)
    // The text colours the config leaves open: for contrast against a configured
    // background, else null and the system-adaptive palette. The primary button is
    // always painted (the config's colour or the accent), so its label is always
    // chosen against what it is painted in.
    val text = ShieldTextColors.resolve(appearance, defaultPrimaryButtonBackground = palette.accent)
    return Colors(appearance.backgroundColor ?: palette.background, text, palette)
  }

  /**
   * The column of the shield: icon, title, subtitle, the primary button and
   * the optional secondary one. [onPrimary] and [onSecondary] are the press
   * handlers (null in a dry run).
   */
  fun build(
    context: Context,
    appearance: ShieldAppearance,
    colors: Colors,
    face: ShieldCache.Face,
    onPrimary: ((View) -> Unit)? = null,
    onSecondary: ((View) -> Unit)? = null,
  ): LinearLayout {
    val palette = colors.palette
    val resolved = colors.text
    val column = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(context.dp(32), context.dp(48), context.dp(32), context.dp(48))
      fitsSystemWindows = true
    }
    val icon = ImageView(context).apply {
      val drawable = when (val spec = appearance.icon) {
        is ShieldAppearance.Icon.Symbol -> {
          val id = context.resources.getIdentifier(spec.resource, "drawable", context.packageName)
          if (id != 0) context.getDrawable(id)?.also { imageTintList = ColorStateList.valueOf(resolved.title ?: palette.text) } else null
        }
        ShieldAppearance.Icon.App -> null
      } ?: face.icon(context)
      setImageDrawable(drawable)
      layoutParams = LinearLayout.LayoutParams(context.dp(96), context.dp(96)).apply { bottomMargin = context.dp(24) }
    }
    val title = TextView(context).apply {
      setText(appearance.title?.text ?: face.label ?: context.getString(R.string.expo_st_shield_title))
      setTextColor(resolved.title ?: palette.text)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
    }
    val subtitle = TextView(context).apply {
      setText(appearance.subtitle?.text ?: context.getString(R.string.expo_st_shield_subtitle))
      setTextColor(resolved.subtitle ?: palette.secondary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      gravity = Gravity.CENTER
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) }
    }
    val primary = TextView(context).apply {
      setText(appearance.primaryButton?.text ?: context.getString(R.string.expo_st_shield_close))
      // Never null (the default background is passed above); a fallback rather than a crash on the user's screen.
      setTextColor(resolved.primaryButton ?: palette.text)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      minHeight = context.dp(48)
      setPadding(context.dp(24), context.dp(12), context.dp(24), context.dp(12))
      background = GradientDrawable().apply {
        cornerRadius = context.dp(24).toFloat()
        setColor(appearance.primaryButtonBackgroundColor ?: palette.accent)
      }
      isClickable = true
      isFocusable = true
      contentDescription = getText()
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(40) }
      onPrimary?.let { setOnClickListener(it) }
    }
    column.addView(icon)
    column.addView(title)
    column.addView(subtitle)
    column.addView(primary)
    appearance.secondaryButton?.let { label ->
      column.addView(TextView(context).apply {
        setText(label.text)
        setTextColor(resolved.secondaryButton ?: palette.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        minHeight = context.dp(48)
        setPadding(context.dp(24), context.dp(12), context.dp(24), context.dp(12))
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) }
        onSecondary?.let { setOnClickListener(it) }
      })
    }
    return column
  }
}
