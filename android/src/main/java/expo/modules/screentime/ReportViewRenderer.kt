package expo.modules.screentime

import android.content.Context
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Draws a resolved [ReportNode] tree with Android views: the layout half of
 * `TemplateNodeView` (`CustomTemplateReport.swift`), node type by node type,
 * with SwiftUI's sizing rules reproduced where Android's differ:
 *
 * - **Stacks** are `LinearLayout`s with `spacing` as a transparent divider
 *   (SwiftUI's default of about 8 points when unset); `alignment` is the
 *   children's gravity. A stack sizes to its content, like a SwiftUI stack,
 *   except that it fills the axis along which a child wants the room: a
 *   `Spacer`, `fillWidth`, a `Divider`, a `Chart`, a `ProgressBar`, a linear
 *   `Gauge`, a `Grid` or a `Text` with `align` (all `maxWidth: .infinity`
 *   on iOS). Inside an `HStack` such a child shares the remaining width
 *   (`weight`), as SwiftUI shares it among flexible children.
 * - **Box props** (`BoxModifier`): padding inside a fixed frame, a solid or
 *   two-stop gradient background behind the padding, the corner radius
 *   clipping background and content, the border, the opacity, and the shadow
 *   as elevation with the shadow colour where the platform has it (API 28+;
 *   `shadowY` has no counterpart, the elevation's own offset stands in).
 * - **Text** uses the system font: `rounded` has no Android face and falls
 *   back to the default sans-serif; `serif` and `monospaced` are the system
 *   ones; weights are real from API 28, bold/regular below. Sizes are
 *   points, not scaled pixels, as `.system(size:)` ignores Dynamic Type.
 * - **Symbols** are the bundled Material Symbols of [ReportSymbols]; an
 *   unmapped name keeps its slot empty.
 * - **AppIcon** is the app's launcher icon from PackageManager (decoded
 *   beforehand by [ReportUsageSource]); a category has no icon on Android
 *   and shows the category glyph on the fill the iOS placeholder uses.
 * - Charts, gauges and progress bars are the custom views of
 *   `ReportWidgets.kt`.
 *
 * Nothing here is interactive: the report is display-only on both platforms.
 */
internal class ReportViewRenderer(
  private val context: Context,
  private val palette: ReportPalette,
  private val icons: (String) -> Drawable?,
) {
  /** A built view and how it wants to be sized by its parent. */
  class Built(
    val view: View,
    /** Takes the width offered (`maxWidth: .infinity`). */
    val fullWidth: Boolean = false,
    /** Takes the height offered (a `Spacer` inside, or a child that does). */
    val fullHeight: Boolean = false,
    val fixedWidthPx: Int? = null,
    val fixedHeightPx: Int? = null,
    /** A `Spacer`: zero-sized, weight 1 along the parent's axis. */
    val spacer: Boolean = false,
    val minLengthPx: Int = 0,
  )

  fun render(root: ReportNode): Built = build(root)

  private fun build(node: ReportNode): Built = when (node) {
    is ReportNode.Stack -> stack(node)
    is ReportNode.Spacer -> Built(ReportRowLayout.SpacerMarker(context), spacer = true, minLengthPx = px(ReportProps.double(node.props, "minLength") ?: 0.0))
    is ReportNode.Divider -> divider(node)
    is ReportNode.Text -> text(node)
    is ReportNode.Symbol -> symbol(node)
    is ReportNode.Chart -> chart(node)
    is ReportNode.ProgressBar -> progressBar(node)
    is ReportNode.Gauge -> gauge(node)
    is ReportNode.ItemList -> itemList(node)
    is ReportNode.AppIcon -> appIcon(node)
    is ReportNode.AppTitle -> appTitle(node)
  }

  // MARK: Stacks

  private fun stack(node: ReportNode.Stack): Built {
    val props = node.props
    val children = node.children.map(::build)
    val fixedWidth = ReportProps.double(props, "width")?.let(::px)
    val fixedHeight = ReportProps.double(props, "height")?.let(::px)
    val view: ViewGroup
    val fullWidth: Boolean
    val fullHeight: Boolean
    when (node.kind) {
      ReportNode.StackKind.GRID -> {
        val grid = ReportGridLayout(context, (ReportProps.int(props, "columns") ?: 2).coerceAtLeast(1), px(ReportProps.double(props, "spacing") ?: 8.0))
        for (child in children) {
          if (child.spacer) continue
          grid.addView(child.view, ViewGroup.LayoutParams(child.fixedWidthPx ?: if (child.fullWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, child.fixedHeightPx ?: ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        view = grid
        fullWidth = true
        fullHeight = false
      }
      ReportNode.StackKind.ZSTACK -> {
        val frame = FrameLayout(context)
        val gravity = when (ReportProps.string(props, "alignment")) {
          "topLeading" -> Gravity.TOP or Gravity.START
          "topTrailing" -> Gravity.TOP or Gravity.END
          "bottomLeading" -> Gravity.BOTTOM or Gravity.START
          "bottomTrailing" -> Gravity.BOTTOM or Gravity.END
          else -> Gravity.CENTER
        }
        for (child in children) {
          if (child.spacer) continue
          val lp = FrameLayout.LayoutParams(
            child.fixedWidthPx ?: if (child.fullWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            child.fixedHeightPx ?: if (child.fullHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            gravity,
          )
          frame.addView(child.view, lp)
        }
        view = frame
        fullWidth = children.any { it.fullWidth }
        fullHeight = children.any { it.fullHeight }
      }
      ReportNode.StackKind.HSTACK -> {
        val alignment = ReportProps.string(props, "alignment")
        val row = ReportRowLayout(
          context,
          px(ReportProps.double(props, "spacing") ?: 8.0),
          when (alignment) {
            "top" -> Gravity.TOP
            "bottom" -> Gravity.BOTTOM
            else -> Gravity.CENTER_VERTICAL
          },
        )
        for (child in children) {
          val lp = if (child.spacer) {
            ReportRowLayout.LayoutParams(0, 0, weight = 1f, minLengthPx = child.minLengthPx)
          } else {
            ReportRowLayout.LayoutParams(
              child.fixedWidthPx ?: ViewGroup.LayoutParams.WRAP_CONTENT,
              child.fixedHeightPx ?: if (child.fullHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
              weight = if (child.fixedWidthPx == null && child.fullWidth) 1f else 0f,
            )
          }
          row.addView(child.view, lp)
        }
        view = row
        fullWidth = children.any { it.spacer || it.fullWidth }
        fullHeight = children.any { it.fullHeight }
      }
      else -> {
        val linear = LinearLayout(context)
        linear.orientation = LinearLayout.VERTICAL
        // `.frame(...)` aligns its content at the centre: a fixed height or a `minHeight` centres the column vertically.
        linear.gravity = Gravity.CENTER_VERTICAL
        val spacing = px(ReportProps.double(props, "spacing") ?: 8.0)
        if (spacing > 0) {
          linear.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
          linear.dividerDrawable = GradientDrawable().apply { setSize(spacing, spacing) }
        }
        val childGravity = when (ReportProps.string(props, "alignment")) {
          "leading" -> Gravity.START
          "trailing" -> Gravity.END
          else -> Gravity.CENTER_HORIZONTAL
        }
        for (child in children) {
          val lp: LinearLayout.LayoutParams
          if (child.spacer) {
            lp = LinearLayout.LayoutParams(0, 0, 1f)
            child.view.minimumHeight = child.minLengthPx
          } else {
            val width = child.fixedWidthPx ?: if (child.fullWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            val height = child.fixedHeightPx ?: if (child.fullHeight) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            lp = LinearLayout.LayoutParams(width, height)
            if (child.fixedHeightPx == null && child.fullHeight) lp.weight = 1f
          }
          lp.gravity = childGravity
          linear.addView(child.view, lp)
        }
        view = linear
        fullWidth = children.any { it.fullWidth }
        fullHeight = children.any { it.spacer || it.fullHeight }
      }
    }
    // A child's shadow (elevation) is drawn outside its bounds: the containers
    // must not clip it; the report view itself still clips to its frame.
    view.clipChildren = false
    view.clipToPadding = false
    box(view, props)
    val fillWidth = ReportProps.bool(props, "fillWidth") == true
    return Built(
      view,
      fullWidth = fixedWidth == null && (fillWidth || fullWidth),
      fullHeight = fixedHeight == null && fullHeight,
      fixedWidthPx = fixedWidth,
      fixedHeightPx = fixedHeight,
    )
  }

  /** `BoxModifier`: padding, background, corner radius, border, shadow, opacity, `minHeight`. */
  private fun box(view: View, props: Map<String, Any?>) {
    val padding = ReportProps.double(props, "padding") ?: 0.0
    val horizontal = ReportProps.double(props, "paddingHorizontal") ?: 0.0
    val vertical = ReportProps.double(props, "paddingVertical") ?: 0.0
    view.setPadding(px(padding + horizontal), px(padding + vertical), px(padding + horizontal), px(padding + vertical))
    ReportProps.double(props, "minHeight")?.let { view.minimumHeight = px(it) }
    val radius = pxF(ReportProps.double(props, "cornerRadius") ?: 0.0)
    val stops = ReportProps.strings(props, "backgroundGradient")
    val start = stops?.getOrNull(0)?.let(ReportProps::color)
    val end = stops?.getOrNull(1)?.let(ReportProps::color)
    val background = GradientDrawable()
    if (stops != null && stops.size >= 2 && start != null && end != null) {
      background.orientation = when (ReportProps.string(props, "gradientDirection")) {
        "horizontal" -> GradientDrawable.Orientation.LEFT_RIGHT
        "diagonal" -> GradientDrawable.Orientation.TL_BR
        else -> GradientDrawable.Orientation.TOP_BOTTOM
      }
      background.colors = intArrayOf(start, end)
    } else {
      background.setColor(ReportProps.color(props, "backgroundColor") ?: 0)
    }
    background.cornerRadius = radius
    val borderWidth = ReportProps.double(props, "borderWidth") ?: 0.0
    val borderColor = ReportProps.color(props, "borderColor")
    if (borderWidth > 0 && borderColor != null) background.setStroke(px(borderWidth), borderColor)
    view.background = background
    if (radius > 0) view.clipToOutline = true
    val shadowRadius = ReportProps.double(props, "shadowRadius") ?: 0.0
    val shadowColor = ReportProps.color(props, "shadowColor")
    if (shadowRadius > 0 && shadowColor != null && (shadowColor ushr 24) > 0) {
      view.elevation = pxF(shadowRadius)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.outlineSpotShadowColor = shadowColor
        view.outlineAmbientShadowColor = shadowColor
      }
    }
    ReportProps.double(props, "opacity")?.let { view.alpha = it.coerceIn(0.0, 1.0).toFloat() }
  }

  // MARK: Leaves

  private fun divider(node: ReportNode.Divider): Built {
    val view = View(context)
    view.setBackgroundColor(ReportProps.color(node.props, "color") ?: palette.separator)
    val thickness = ReportProps.double(node.props, "thickness")?.let(::px)?.coerceAtLeast(1) ?: 1
    return Built(view, fullWidth = true, fixedHeightPx = thickness)
  }

  private fun text(node: ReportNode.Text): Built {
    val props = node.props
    val view = textView(node.text, props, defaultSize = 15.0, defaultLineLimit = null)
    val align = ReportProps.string(props, "align")
    view.gravity = when (align) {
      "leading" -> Gravity.START
      "trailing" -> Gravity.END
      else -> Gravity.CENTER_HORIZONTAL
    }
    if (ReportProps.bool(props, "underline") == true) view.paintFlags = view.paintFlags or Paint.UNDERLINE_TEXT_FLAG
    if (ReportProps.bool(props, "strikethrough") == true) view.paintFlags = view.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
    if (ReportProps.bool(props, "monospacedDigit") == true) view.fontFeatureSettings = "tnum"
    ReportProps.double(props, "letterSpacing")?.let { kerning ->
      val size = ReportProps.double(props, "size") ?: 15.0
      if (size > 0) view.letterSpacing = (kerning / size).toFloat()
    }
    return Built(view, fullWidth = align != null)
  }

  private fun appTitle(node: ReportNode.AppTitle): Built =
    Built(textView(node.text, node.props, defaultSize = 15.0, defaultLineLimit = 1))

  private fun textView(text: String, props: Map<String, Any?>, defaultSize: Double, defaultLineLimit: Int?): TextView {
    val view = TextView(context)
    view.text = text
    view.includeFontPadding = false
    view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, (ReportProps.double(props, "size") ?: defaultSize).toFloat())
    view.typeface = typeface(props)
    view.setTextColor(ReportProps.color(props, "color") ?: palette.label)
    ReportProps.double(props, "opacity")?.let { view.alpha = it.coerceIn(0.0, 1.0).toFloat() }
    val lineLimit = ReportProps.int(props, "lineLimit") ?: defaultLineLimit
    if (lineLimit != null && lineLimit > 0) {
      view.maxLines = lineLimit
      view.ellipsize = TextUtils.TruncateAt.END
    }
    return view
  }

  private fun typeface(props: Map<String, Any?>): Typeface {
    val base = when (ReportProps.string(props, "design")) {
      "serif" -> Typeface.SERIF
      "monospaced" -> Typeface.MONOSPACE
      else -> Typeface.SANS_SERIF
    }
    val weight = when (ReportProps.string(props, "weight")) {
      "light" -> 300
      "medium" -> 500
      "semibold" -> 600
      "bold" -> 700
      "heavy" -> 800
      else -> 400
    }
    val italic = ReportProps.bool(props, "italic") == true
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      Typeface.create(base, weight, italic)
    } else {
      val style = (if (weight >= 600) Typeface.BOLD else Typeface.NORMAL) or (if (italic) Typeface.ITALIC else 0)
      Typeface.create(base, style)
    }
  }

  private fun symbol(node: ReportNode.Symbol): Built {
    val props = node.props
    val size = px(ReportProps.double(props, "size") ?: 17.0)
    val view = ImageView(context)
    view.scaleType = ImageView.ScaleType.FIT_CENTER
    val id = ReportSymbols.resourceId(context, node.name)
    if (id != 0) {
      view.setImageDrawable(ContextCompat.getDrawable(context, id)?.mutate())
      view.imageTintList = android.content.res.ColorStateList.valueOf(ReportProps.color(props, "color") ?: palette.label)
    }
    ReportProps.double(props, "opacity")?.let { view.alpha = it.coerceIn(0.0, 1.0).toFloat() }
    view.contentDescription = node.name
    return Built(view, fixedWidthPx = size, fixedHeightPx = size)
  }

  private fun chart(node: ReportNode.Chart): Built {
    val props = node.props
    val view = ReportChartView(context)
    val accent = ReportProps.color(props, "accentColor") ?: palette.blue
    // `bind == "hourly"` on iOS decides the numeric axis and the X axis default; any other bind is categorical.
    val hourly = node.bind == "hourly"
    view.spec = ReportChartView.Spec(
      type = node.type,
      hourly = hourly,
      entries = node.entries,
      averageMinutes = node.averageMinutes,
      accent = accent,
      palette = ReportProps.strings(props, "colorPalette")?.mapNotNull(ReportProps::color) ?: emptyList(),
      defaultPalette = palette.categorical,
      gradient = ReportProps.bool(props, "gradient") == true,
      barRadiusPx = pxF(ReportProps.double(props, "barCornerRadius") ?: 0.0),
      lineWidthPx = pxF(ReportProps.double(props, "lineWidth") ?: 2.0),
      showPoints = ReportProps.bool(props, "showPoints") == true,
      averageLine = ReportProps.bool(props, "averageLine") == true,
      goalValue = ReportProps.double(props, "goalValue"),
      goalColor = ReportProps.color(props, "goalColor") ?: palette.red,
      pieInnerRadius = ReportProps.double(props, "pieInnerRadius") ?: 0.55,
      labelColor = ReportProps.color(props, "labelColor") ?: palette.secondaryLabel,
      gridColor = palette.separator,
      showXAxis = ReportProps.bool(props, "showXAxis") ?: !hourly,
      showYAxis = ReportProps.bool(props, "showYAxis") ?: true,
      xStride = ReportProps.int(props, "xAxisStride") ?: 6,
    )
    view.contentDescription = chartDescription(node)
    return Built(view, fullWidth = true, fixedHeightPx = px(ReportProps.double(props, "height") ?: 120.0))
  }

  /** What a screen reader (and a test) gets from a chart: its series, one value per entry, in minutes. */
  private fun chartDescription(node: ReportNode.Chart): String =
    "${node.type} chart of ${node.bind}: " + node.entries.joinToString(", ") { "${it.label} ${it.minutes.roundToInt()}" }

  private fun progressBar(node: ReportNode.ProgressBar): Built {
    val props = node.props
    val height = ReportProps.double(props, "height") ?: 8.0
    val view = ReportProgressBarView(context)
    view.fraction = node.fraction
    view.fillColor = ReportProps.color(props, "color") ?: palette.blue
    view.trackColor = ReportProps.color(props, "trackColor") ?: palette.tertiaryFill
    view.radiusPx = pxF(ReportProps.double(props, "cornerRadius") ?: (height / 2))
    view.contentDescription = "${(node.fraction * 100).roundToInt()}%"
    return Built(view, fullWidth = true, fixedHeightPx = px(height))
  }

  private fun gauge(node: ReportNode.Gauge): Built {
    val props = node.props
    val view = ReportGaugeView(context)
    view.fraction = node.fraction
    view.tint = ReportProps.color(props, "color") ?: palette.blue
    view.linear = node.linear
    view.label = if (node.linear) "" else node.percentLabel
    view.labelColor = palette.label
    view.contentDescription = node.percentLabel
    if (node.linear) return Built(view, fullWidth = true, fixedHeightPx = px(LINEAR_GAUGE_HEIGHT))
    val size = px(ReportProps.double(props, "size") ?: 56.0)
    return Built(view, fixedWidthPx = size, fixedHeightPx = size)
  }

  private fun itemList(node: ReportNode.ItemList): Built {
    val props = node.props
    if (node.rows.isEmpty() && node.emptyText != null) {
      val view = TextView(context)
      view.text = node.emptyText
      view.includeFontPadding = false
      view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
      view.setTextColor(palette.secondaryLabel)
      view.gravity = Gravity.CENTER_HORIZONTAL
      view.setPadding(0, px(8.0), 0, px(8.0))
      return Built(view, fullWidth = true)
    }
    val list = LinearLayout(context)
    list.orientation = LinearLayout.VERTICAL
    val spacing = px(ReportProps.double(props, "spacing") ?: 8.0)
    if (spacing > 0) {
      list.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
      list.dividerDrawable = GradientDrawable().apply { setSize(spacing, spacing) }
    }
    var fullWidth = false
    for (row in node.rows) {
      for (child in row.children) {
        val built = build(child)
        if (built.spacer) continue
        val lp = LinearLayout.LayoutParams(
          built.fixedWidthPx ?: if (built.fullWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
          built.fixedHeightPx ?: ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = Gravity.START
        list.addView(built.view, lp)
        fullWidth = fullWidth || built.fullWidth
      }
    }
    return Built(list, fullWidth = fullWidth)
  }

  private fun appIcon(node: ReportNode.AppIcon): Built {
    val props = node.props
    val size = px(ReportProps.double(props, "size") ?: 28.0)
    val radius = pxF(ReportProps.double(props, "cornerRadius") ?: 0.0)
    val view = ImageView(context)
    view.scaleType = ImageView.ScaleType.FIT_CENTER
    val item = node.item
    // The cached drawable is shared by every view of that app: each view gets its own instance (bounds, callback).
    val icon = item?.packageName?.let(icons)?.let { it.constantState?.newDrawable(context.resources)?.mutate() ?: it }
    view.setImageDrawable(icon ?: placeholder(if (item?.categoryName != null) R.drawable.expo_st_ic_category else R.drawable.expo_st_ic_apps, size))
    if (radius > 0) {
      view.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
          outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
      }
      view.clipToOutline = true
    }
    view.contentDescription = item?.name
    return Built(view, fixedWidthPx = size, fixedHeightPx = size)
  }

  /** The iOS placeholder: a rounded square of `tertiarySystemFill` with a grey glyph (`app.fill` there, the category glyph for a category here). */
  private fun placeholder(glyph: Int, sizePx: Int): Drawable {
    val square = GradientDrawable().apply {
      setColor(palette.tertiaryFill)
      cornerRadius = pxF(6.0)
    }
    val symbol = ContextCompat.getDrawable(context, glyph)?.mutate()?.apply { setTint(palette.systemGray) }
    val inset = (sizePx * 0.22).roundToInt()
    return if (symbol == null) square else LayerDrawable(arrayOf(square, InsetDrawable(symbol, inset)))
  }

  // MARK: Units

  private fun px(points: Double): Int = context.px(points).roundToInt()

  private fun pxF(points: Double): Float = context.px(points)

  private companion object {
    /** The slim `accessoryLinearCapacity` bar, in points. */
    const val LINEAR_GAUGE_HEIGHT = 6.0
  }
}
