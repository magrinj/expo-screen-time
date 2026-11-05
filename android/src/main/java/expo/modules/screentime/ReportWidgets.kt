package expo.modules.screentime

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Density-independent points (the iOS unit of every template prop) to pixels. */
internal fun Context.px(points: Double): Float = (points * resources.displayMetrics.density).toFloat()

/**
 * `Report.ProgressBar`: the SwiftUI `progressBarView`, a track and a fill
 * of `width * ratio`, at least one height wide once the ratio is positive,
 * both as rounded rectangles of the same radius.
 */
internal class ReportProgressBarView(context: Context) : View(context) {
  var fraction = 0.0
  var fillColor = 0
  var trackColor = 0
  var radiusPx = 0f
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val rect = RectF()

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    paint.color = trackColor
    rect.set(0f, 0f, w, h)
    canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
    val fill = if (fraction > 0) min(w, max((w * fraction).toFloat(), h)) else 0f
    if (fill > 0f) {
      paint.color = fillColor
      rect.set(0f, 0f, fill, h)
      canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
    }
  }
}

/**
 * `Report.Gauge`: the accessory capacity gauges of SwiftUI. Circular: a ring
 * whose unfilled part is the tint dimmed, the filled arc from twelve o'clock
 * clockwise with round caps, and the percent label at `size * 0.24`
 * semibold in the centre. Linear: a slim capsule, the same dimmed track and
 * tinted fill.
 */
internal class ReportGaugeView(context: Context) : View(context) {
  var fraction = 0.0
  var tint = 0
  var linear = false
  var label = ""
  var labelColor = 0
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
  private val rect = RectF()

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (linear) {
      val radius = h / 2
      paint.style = Paint.Style.FILL
      paint.color = dim(tint)
      rect.set(0f, 0f, w, h)
      canvas.drawRoundRect(rect, radius, radius, paint)
      val fill = (w * fraction).toFloat()
      if (fill > 0f) {
        paint.color = tint
        rect.set(0f, 0f, min(w, max(fill, h)), h)
        canvas.drawRoundRect(rect, radius, radius, paint)
      }
      return
    }
    val size = min(w, h)
    val stroke = size * 0.11f
    val inset = stroke / 2 + 0.5f
    rect.set((w - size) / 2 + inset, (h - size) / 2 + inset, (w + size) / 2 - inset, (h + size) / 2 - inset)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = stroke
    paint.strokeCap = Paint.Cap.ROUND
    paint.color = dim(tint)
    canvas.drawOval(rect, paint)
    if (fraction > 0) {
      paint.color = tint
      canvas.drawArc(rect, -90f, (360 * fraction).toFloat(), false, paint)
    }
    if (label.isNotEmpty()) {
      text.color = labelColor
      text.textSize = size * 0.24f
      text.typeface = semibold()
      val baseline = h / 2 - (text.ascent() + text.descent()) / 2
      canvas.drawText(label, w / 2, baseline, text)
    }
  }

  private fun dim(color: Int): Int = (color and 0x00FFFFFF) or (0x4D shl 24)

  private fun semibold(): Typeface =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) Typeface.create(Typeface.SANS_SERIF, 600, false) else Typeface.DEFAULT_BOLD
}

/**
 * `Report.Chart`: the Swift Charts plot of `chartView`, drawn on a canvas.
 * Bars, a Catmull–Rom line with optional points, a Catmull–Rom area (solid
 * or faded), or a donut; the dashed average and goal rules; a Y axis on the
 * trailing edge with "nice" ticks and grid lines, as Swift Charts places it;
 * an X axis every `xAxisStride` hours for the hourly series, one label per
 * entry for the lists. A pie without a palette gets Swift Charts' legend
 * below it (`foregroundStyle(by:)`), inside the chart's height.
 */
internal class ReportChartView(context: Context) : View(context) {
  class Spec(
    val type: String,
    val hourly: Boolean,
    val entries: List<ReportChartEntry>,
    val averageMinutes: Double,
    val accent: Int,
    val palette: List<Int>,
    val defaultPalette: List<Int>,
    val gradient: Boolean,
    val barRadiusPx: Float,
    val lineWidthPx: Float,
    val showPoints: Boolean,
    val averageLine: Boolean,
    val goalValue: Double?,
    val goalColor: Int,
    val pieInnerRadius: Double,
    val labelColor: Int,
    val gridColor: Int,
    val showXAxis: Boolean,
    val showYAxis: Boolean,
    val xStride: Int,
  )

  var spec: Spec? = null
    set(value) {
      field = value
      invalidate()
    }

  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val text = Paint(Paint.ANTI_ALIAS_FLAG)
  private val path = Path()
  private val rect = RectF()
  private val density = context.resources.displayMetrics.density

  private fun dp(value: Float): Float = value * density

  override fun onDraw(canvas: Canvas) {
    val spec = spec ?: return
    text.textSize = dp(11f)
    text.typeface = Typeface.DEFAULT
    if (spec.type == "pie") drawPie(canvas, spec) else drawCartesian(canvas, spec)
  }

  // MARK: Cartesian

  private fun drawCartesian(canvas: Canvas, spec: Spec) {
    val values = spec.entries.map { max(it.minutes, 0.0) }
    var top = values.maxOrNull() ?: 0.0
    spec.goalValue?.let { if (it > 0) top = max(top, it) }
    if (spec.averageLine && spec.averageMinutes > 0) top = max(top, spec.averageMinutes)
    val ticks = niceTicks(top)
    val yTop = ticks.last()
    val step = if (ticks.size > 1) ticks[1] - ticks[0] else 1.0
    tickDecimals = if (step >= 1) 0 else max(0, -floor(log10(step) + 1e-9).toInt()).coerceAtMost(3)

    text.color = spec.labelColor
    val labelHeight = text.descent() - text.ascent()
    val yLabelWidth = if (spec.showYAxis) ticks.maxOf { text.measureText(tickLabel(it)) } + dp(6f) else 0f
    val plotLeft = dp(2f)
    val plotRight = width - yLabelWidth - dp(2f)
    val plotTop = dp(4f) + if (spec.showYAxis) labelHeight / 2 else 0f
    val plotBottom = height - dp(2f) - if (spec.showXAxis) labelHeight + dp(4f) else 0f
    val plotW = plotRight - plotLeft
    val plotH = plotBottom - plotTop
    if (plotW <= 0 || plotH <= 0) return
    fun y(value: Double): Float = plotBottom - (if (yTop > 0) (value / yTop) * plotH else 0.0).toFloat()

    // Grid and Y labels (trailing edge, as Swift Charts).
    stroke.pathEffect = null
    stroke.strokeWidth = dp(1f)
    stroke.color = spec.gridColor
    if (spec.showYAxis) {
      for (tick in ticks) {
        val ty = y(tick)
        canvas.drawLine(plotLeft, ty, plotRight, ty, stroke)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(tickLabel(tick), plotRight + dp(6f), ty - (text.ascent() + text.descent()) / 2, text)
      }
    }
    val n = spec.entries.size
    if (n == 0) return
    val slot = plotW / n
    fun centerX(index: Int): Float = plotLeft + slot * index + slot / 2

    if (spec.showXAxis) {
      text.textAlign = Paint.Align.CENTER
      val baseline = plotBottom + dp(4f) - text.ascent()
      if (spec.hourly) {
        // `AxisMarks(values: .stride(by:))` over the numeric hour: a mark at the bar's centre every `xStride` hours.
        var hour = 0
        while (hour < n) {
          val gx = centerX(hour)
          canvas.drawLine(gx, plotTop, gx, plotBottom, stroke)
          canvas.drawText(hour.toString(), gx, baseline, text)
          hour += max(spec.xStride, 1)
        }
      } else {
        // Bars are categorical (the label); lines and areas plot the numeric index on iOS, so their axis shows it.
        val categorical = spec.type != "line" && spec.type != "area"
        for (index in 0 until n) {
          val gx = centerX(index)
          canvas.drawLine(gx, plotTop, gx, plotBottom, stroke)
          canvas.drawText(if (categorical) ellipsize(spec.entries[index].label, slot - dp(4f)) else index.toString(), gx, baseline, text)
        }
      }
    }

    canvas.save()
    canvas.clipRect(plotLeft, 0f, plotRight, plotBottom + 1f)
    when (spec.type) {
      "line" -> {
        val points = values.mapIndexed { index, value -> PointF(centerX(index), y(value)) }
        stroke.color = spec.accent
        stroke.strokeWidth = spec.lineWidthPx
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        curve(points, closeTo = null)
        canvas.drawPath(path, stroke)
        if (spec.showPoints) {
          fill.shader = null
          fill.color = spec.accent
          val radius = max(spec.lineWidthPx * 1.5f, dp(3f))
          for (point in points) canvas.drawCircle(point.x, point.y, radius, fill)
        }
      }
      "area" -> {
        val points = values.mapIndexed { index, value -> PointF(centerX(index), y(value)) }
        curve(points, closeTo = plotBottom)
        fill.shader = if (spec.gradient) {
          LinearGradient(0f, plotTop, 0f, plotBottom, spec.accent, (spec.accent and 0x00FFFFFF) or (0x40 shl 24), Shader.TileMode.CLAMP)
        } else {
          null
        }
        fill.color = spec.accent
        canvas.drawPath(path, fill)
        fill.shader = null
      }
      else -> {
        val barWidth = slot * 0.7f
        for (index in 0 until n) {
          val top = y(values[index])
          if (top >= plotBottom) continue
          val left = centerX(index) - barWidth / 2
          fill.color = if (spec.palette.isEmpty()) spec.accent else spec.palette[index % spec.palette.size]
          fill.shader = if (spec.gradient && spec.palette.isEmpty()) {
            LinearGradient(0f, plotTop, 0f, plotBottom, spec.accent, (spec.accent and 0x00FFFFFF) or (0x40 shl 24), Shader.TileMode.CLAMP)
          } else {
            null
          }
          // `BarMark.cornerRadius` rounds every corner, within what the bar's own size allows.
          val radius = min(spec.barRadiusPx, min(barWidth, plotBottom - top) / 2)
          rect.set(left, top, left + barWidth, plotBottom)
          canvas.drawRoundRect(rect, radius, radius, fill)
        }
        fill.shader = null
      }
    }
    canvas.restore()

    if (spec.averageLine && spec.averageMinutes > 0) {
      stroke.color = (spec.accent and 0x00FFFFFF) or (0xB3 shl 24)
      stroke.strokeWidth = dp(1f)
      stroke.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
      val ay = y(spec.averageMinutes)
      canvas.drawLine(plotLeft, ay, plotRight, ay, stroke)
    }
    spec.goalValue?.let { goal ->
      if (goal > 0) {
        stroke.color = spec.goalColor
        stroke.strokeWidth = dp(1.5f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(3f)), 0f)
        val gy = y(goal)
        canvas.drawLine(plotLeft, gy, plotRight, gy, stroke)
      }
    }
    stroke.pathEffect = null
  }

  /** A Catmull–Rom spline through [points] as cubic Béziers (Swift Charts' `.catmullRom`), optionally closed to a baseline. */
  private fun curve(points: List<PointF>, closeTo: Float?) {
    path.reset()
    if (points.isEmpty()) return
    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until points.size - 1) {
      val p0 = points[max(i - 1, 0)]
      val p1 = points[i]
      val p2 = points[i + 1]
      val p3 = points[min(i + 2, points.size - 1)]
      val c1x = p1.x + (p2.x - p0.x) / 6
      val c1y = p1.y + (p2.y - p0.y) / 6
      val c2x = p2.x - (p3.x - p1.x) / 6
      val c2y = p2.y - (p3.y - p1.y) / 6
      path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
    if (closeTo != null) {
      path.lineTo(points.last().x, closeTo)
      path.lineTo(points.first().x, closeTo)
      path.close()
    }
  }

  // MARK: Pie

  private fun drawPie(canvas: Canvas, spec: Spec) {
    val entries = spec.entries
    if (entries.isEmpty()) return
    val colors = if (spec.palette.isEmpty()) spec.defaultPalette else spec.palette
    val legend = spec.palette.isEmpty()
    text.color = spec.labelColor
    text.textAlign = Paint.Align.LEFT
    val rowHeight = (text.descent() - text.ascent()) + dp(4f)
    val dot = dp(8f)
    // The legend rows, laid out first so the pie takes what is left.
    val rows = ArrayList<List<Int>>()
    if (legend) {
      var row = ArrayList<Int>()
      var used = 0f
      for (index in entries.indices) {
        val itemWidth = dot + dp(4f) + text.measureText(entries[index].label) + dp(12f)
        if (row.isNotEmpty() && used + itemWidth > width) {
          rows.add(row)
          row = ArrayList()
          used = 0f
        }
        row.add(index)
        used += itemWidth
      }
      if (row.isNotEmpty()) rows.add(row)
    }
    val legendHeight = rows.size * rowHeight
    val size = max(min(width.toFloat(), height - legendHeight - dp(4f)) - dp(4f), dp(8f))
    val cx = width / 2f
    val cy = (height - legendHeight) / 2f
    val outer = size / 2
    val inner = (outer * spec.pieInnerRadius.coerceIn(0.0, 0.95)).toFloat()
    val total = entries.sumOf { max(it.minutes, 0.0001) }
    var start = -90f
    fill.shader = null
    for ((index, entry) in entries.withIndex()) {
      val sweep = (max(entry.minutes, 0.0001) / total * 360).toFloat()
      fill.color = colors[index % colors.size]
      path.reset()
      rect.set(cx - outer, cy - outer, cx + outer, cy + outer)
      if (sweep >= 359.99f) {
        // A single slice: `Path.arcTo` draws nothing for a 360° sweep, so the ring is two ovals wound against each other.
        path.addOval(rect, Path.Direction.CW)
        if (inner > 0) {
          rect.set(cx - inner, cy - inner, cx + inner, cy + inner)
          path.addOval(rect, Path.Direction.CCW)
        }
      } else {
        path.arcTo(rect, start, sweep)
        if (inner > 0) {
          rect.set(cx - inner, cy - inner, cx + inner, cy + inner)
          path.arcTo(rect, start + sweep, -sweep)
        } else {
          path.lineTo(cx, cy)
        }
        path.close()
      }
      canvas.drawPath(path, fill)
      start += sweep
    }
    if (legend) {
      var y = height - legendHeight
      for (row in rows) {
        val rowWidth = row.sumOf { (dot + dp(4f) + text.measureText(entries[it].label) + dp(12f)).toDouble() }.toFloat() - dp(12f)
        var x = (width - rowWidth) / 2
        val baseline = y + rowHeight / 2 - (text.ascent() + text.descent()) / 2
        for (index in row) {
          fill.color = colors[index % colors.size]
          canvas.drawCircle(x + dot / 2, y + rowHeight / 2, dot / 2, fill)
          canvas.drawText(entries[index].label, x + dot + dp(4f), baseline, text)
          x += dot + dp(4f) + text.measureText(entries[index].label) + dp(12f)
        }
        y += rowHeight
      }
    }
  }

  // MARK: Helpers

  private fun ellipsize(label: String, maxWidth: Float): String {
    if (text.measureText(label) <= maxWidth || label.length <= 1) return label
    var end = label.length - 1
    while (end > 1 && text.measureText(label.substring(0, end) + "…") > maxWidth) end--
    return label.substring(0, end) + "…"
  }

  /** Decimals of the current axis, from its step (a step of 0.05 needs two), so no two labels read the same. */
  private var tickDecimals = 0

  private fun tickLabel(value: Double): String {
    if (tickDecimals == 0) return Math.round(value).toString()
    return String.format(java.util.Locale.getDefault(), "%.${tickDecimals}f", value)
  }

  companion object {
    /** Zero to a round top in at most five steps, Swift Charts' "nice" axis: 1, 2, 5 × 10ⁿ steps. */
    fun niceTicks(max: Double): List<Double> {
      if (max <= 0 || max.isNaN() || max.isInfinite()) return listOf(0.0, 1.0)
      val rawStep = max / 4
      val magnitude = 10.0.pow(floor(log10(rawStep)))
      val residual = rawStep / magnitude
      val step = when {
        residual > 5 -> 10.0
        residual > 2 -> 5.0
        residual > 1 -> 2.0
        else -> 1.0
      } * magnitude
      val count = ceil(max / step - 1e-9).toInt().coerceAtLeast(1)
      return List(count + 1) { it * step }.map { if (abs(it) < 1e-9) 0.0 else it }
    }
  }
}

/**
 * `Report.HStack`: SwiftUI's horizontal stack, laid out as it proposes
 * width. A `LinearLayout` hands the space out in order, so a long title
 * pushes the row's trailing children off the edge; SwiftUI gives the
 * least flexible views their size first, the texts share what is left and
 * truncate, and the spacers take the rest. So here: the rigid children
 * (icons, gauges, fixed frames) are measured first at their content size;
 * the text children (`TextView`s) share the remaining width, each taking
 * what it needs up to its share and the unused shares going round again;
 * the spacers and the full-width children share the leftover (`weight`);
 * `alignment` places the children on the cross axis, and with no flexible
 * child the row's content is centred in a wider frame, as a SwiftUI
 * `.frame(maxWidth: .infinity)` centres it.
 */
internal class ReportRowLayout(context: Context, private val spacingPx: Int, private val crossGravity: Int) : ViewGroup(context) {
  class LayoutParams(width: Int, height: Int, val weight: Float = 0f, val minLengthPx: Int = 0) : ViewGroup.LayoutParams(width, height)

  /** The view a `Report.Spacer` becomes: empty, weighted, and never offered a share of its own. */
  class SpacerMarker(context: Context) : View(context)

  private val widths = HashMap<View, Int>()
  private val isText = HashMap<View, Boolean>()

  private fun children(): List<View> = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val mode = MeasureSpec.getMode(widthMeasureSpec)
    val available = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
    val views = children()
    widths.clear()
    val gaps = if (views.size > 1) spacingPx * (views.size - 1) else 0
    var used = gaps
    val flexible = ArrayList<View>()
    val sharing = ArrayList<View>()
    for (child in views) {
      val lp = child.layoutParams as LayoutParams
      when {
        lp.weight > 0 -> {
          flexible.add(child)
          used += lp.minLengthPx // a spacer's minimum length is reserved before anything shares
        }
        lp.width > 0 -> {
          // Rigid: a fixed frame (icons, symbols, gauges) takes its size first.
          child.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), heightSpec(lp, heightMeasureSpec))
          widths[child] = child.measuredWidth
          used += child.measuredWidth
        }
        // Everything content-sized (a text, a nested stack holding one) shares the rest and shrinks to fit:
        // measured AT_MOST, a nested stack's texts truncate as a bare text would.
        else -> sharing.add(child)
      }
    }
    // Shares: the remaining width divided among the sharing children and the
    // flexible non-spacer children (so a progress bar beside a long title keeps
    // its part, as SwiftUI offers each unresolved child the same proposal); a
    // child that needs less settles and its unused share goes round again.
    var remaining = (available - used).coerceAtLeast(0)
    val reserved = flexible.count { it !is SpacerMarker }
    var pending = ArrayList(sharing)
    while (pending.isNotEmpty()) {
      val share = remaining / (pending.size + reserved)
      val next = ArrayList<View>()
      var settled = 0
      for (child in pending) {
        child.measure(MeasureSpec.makeMeasureSpec(share, MeasureSpec.AT_MOST), heightSpec(child.layoutParams as LayoutParams, heightMeasureSpec))
        if (child.measuredWidth < share) {
          widths[child] = child.measuredWidth
          settled += child.measuredWidth
        } else {
          next.add(child)
        }
      }
      remaining -= settled
      if (next.size == pending.size) {
        // Every remaining child wants its full share: that is what they get.
        for (child in next) {
          widths[child] = share
          child.measure(MeasureSpec.makeMeasureSpec(share, MeasureSpec.EXACTLY), heightSpec(child.layoutParams as LayoutParams, heightMeasureSpec))
        }
        remaining -= share * next.size
        next.clear()
      }
      pending = next
    }
    // Spacers and full-width children: their reserved minimum plus the leftover, by weight.
    val leftover = remaining.coerceAtLeast(0)
    val totalWeight = flexible.sumOf { (it.layoutParams as LayoutParams).weight.toDouble() }.toFloat()
    for (child in flexible) {
      val lp = child.layoutParams as LayoutParams
      val w = lp.minLengthPx + if (totalWeight > 0) (leftover * lp.weight / totalWeight).toInt() else 0
      child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightSpec(lp, heightMeasureSpec))
      widths[child] = w
    }
    val contentWidth = widths.values.sum() + gaps
    var height = 0
    for (child in views) height = max(height, child.measuredHeight)
    val measuredWidth = if (mode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else min(contentWidth + paddingLeft + paddingRight, if (mode == MeasureSpec.AT_MOST) MeasureSpec.getSize(widthMeasureSpec) else Int.MAX_VALUE)
    // `minHeight` (the box prop) is the suggested minimum, as a LinearLayout honours it.
    setMeasuredDimension(measuredWidth, resolveSize(max(height + paddingTop + paddingBottom, suggestedMinimumHeight), heightMeasureSpec))
  }

  private fun heightSpec(lp: LayoutParams, heightMeasureSpec: Int): Int = when {
    lp.height > 0 -> MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
    lp.height == ViewGroup.LayoutParams.MATCH_PARENT && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED ->
      MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0), MeasureSpec.EXACTLY)
    else -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    val views = children()
    val inner = width - paddingLeft - paddingRight
    val innerHeight = height - paddingTop - paddingBottom
    val content = views.sumOf { widths[it] ?: it.measuredWidth } + if (views.size > 1) spacingPx * (views.size - 1) else 0
    val flexible = views.any { (it.layoutParams as LayoutParams).weight > 0 }
    var x = paddingLeft + if (!flexible && inner > content) (inner - content) / 2 else 0
    for (child in views) {
      val w = widths[child] ?: child.measuredWidth
      val h = child.measuredHeight
      val top = paddingTop + when (crossGravity) {
        Gravity.TOP -> 0
        Gravity.BOTTOM -> innerHeight - h
        else -> (innerHeight - h) / 2
      }
      child.layout(x, top, x + w, top + h)
      x += w + spacingPx
    }
  }

  override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

  override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

  override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(p?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT, p?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT)
}

/**
 * `Report.Grid`: a `LazyVGrid` of equal flexible columns. Each child is
 * measured within its column (a child that wants the full width takes it;
 * the others size to content and sit centred in the cell, as a
 * `GridItem(.flexible())` places them), each row is as tall as its tallest
 * cell, and `spacing` separates both columns and rows.
 */
internal class ReportGridLayout(context: Context, private val columns: Int, private val spacingPx: Int) : ViewGroup(context) {
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val width = MeasureSpec.getSize(widthMeasureSpec)
    val inner = width - paddingLeft - paddingRight
    val columnWidth = ((inner - spacingPx * (columns - 1)) / columns).coerceAtLeast(0)
    var height = paddingTop + paddingBottom
    var rowHeight = 0
    var inRow = 0
    var rows = 0
    for (i in 0 until childCount) {
      val child = getChildAt(i)
      if (child.visibility == GONE) continue
      val lp = child.layoutParams
      val widthSpec = if (lp.width == LayoutParams.MATCH_PARENT) {
        MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY)
      } else if (lp.width > 0) {
        MeasureSpec.makeMeasureSpec(min(lp.width, columnWidth), MeasureSpec.EXACTLY)
      } else {
        MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.AT_MOST)
      }
      val heightSpec = if (lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
      child.measure(widthSpec, heightSpec)
      rowHeight = max(rowHeight, child.measuredHeight)
      inRow++
      if (inRow == columns) {
        height += rowHeight
        rows++
        rowHeight = 0
        inRow = 0
      }
    }
    if (inRow > 0) {
      height += rowHeight
      rows++
    }
    if (rows > 1) height += spacingPx * (rows - 1)
    setMeasuredDimension(width, resolveSize(max(height, suggestedMinimumHeight), heightMeasureSpec))
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    val inner = width - paddingLeft - paddingRight
    val columnWidth = ((inner - spacingPx * (columns - 1)) / columns).coerceAtLeast(0)
    // The content block sits centred in a taller frame (`minHeight`, a fixed height), as a `.frame` centres it.
    var contentHeight = 0
    run {
      var rh = 0
      var n = 0
      var rows = 0
      for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child.visibility == GONE) continue
        rh = max(rh, child.measuredHeight)
        if (++n == columns) { contentHeight += rh; rows++; rh = 0; n = 0 }
      }
      if (n > 0) { contentHeight += rh; rows++ }
      if (rows > 1) contentHeight += spacingPx * (rows - 1)
    }
    var y = paddingTop + ((height - paddingTop - paddingBottom - contentHeight) / 2).coerceAtLeast(0)
    var column = 0
    var rowHeight = 0
    val row = ArrayList<View>(columns)
    fun placeRow() {
      for ((index, child) in row.withIndex()) {
        val cellLeft = paddingLeft + index * (columnWidth + spacingPx)
        val left = cellLeft + (columnWidth - child.measuredWidth) / 2
        val top = y + (rowHeight - child.measuredHeight) / 2
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
      }
      y += rowHeight + spacingPx
      row.clear()
      rowHeight = 0
      column = 0
    }
    for (i in 0 until childCount) {
      val child = getChildAt(i)
      if (child.visibility == GONE) continue
      row.add(child)
      rowHeight = max(rowHeight, child.measuredHeight)
      column++
      if (column == columns) placeRow()
    }
    if (row.isNotEmpty()) placeRow()
  }
}
