package expo.modules.screentime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * `<DeviceActivityReport>` on Android: the twin of
 * `ios/ExpoScreenTimeReportView.swift` plus the report extension it hosts.
 * iOS hands the template and a filter to Apple's sandboxed extension; here
 * the same template JSON ([ReportTemplateChannel]) is resolved against the
 * UsageEvents of the same window ([ReportWindow], [ReportUsageSource]) and
 * drawn with Android views ([ReportViewRenderer]) in the host process. The
 * values stay native either way: JS authored the layout, nothing comes back.
 *
 * Renders when it is attached, when a prop changes (`filterSegment`,
 * `filterDate`, `templateHash`), when the template slot changes and when
 * the light/dark mode changes; the aggregation runs on one background
 * thread shared by every report, the views are built on the main thread,
 * and a result for props that changed since is dropped. Like iOS, the view
 * is sized by its `style` and clips the template to it, and it is
 * display-only (no child takes a touch, the surrounding scroll view gets
 * them). Without a template, the iOS placeholder: a dashed square and "No
 * custom template set".
 */
class ExpoScreenTimeReportView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  override val shouldUseAndroidLayout = true

  var filterSegment: String? = null
  var filterDate: Double? = null
  var templateHash: String? = null

  private var palette = ReportPalette.of(context)
  private val main = Handler(Looper.getMainLooper())
  @Volatile
  private var generation = 0
  private val onTemplateChanged: () -> Unit = { render() }
  private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Built on the render thread, once per view (the PackageManager caches live in it). */
  private val source by lazy { ReportUsageSource(context) }

  init {
    orientation = VERTICAL
    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    clipChildren = true
    clipToPadding = true
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    ReportTemplateChannel.addListener(onTemplateChanged)
    render()
  }

  override fun onDetachedFromWindow() {
    ReportTemplateChannel.removeListener(onTemplateChanged)
    generation++
    super.onDetachedFromWindow()
  }

  override fun onConfigurationChanged(newConfig: Configuration?) {
    super.onConfigurationChanged(newConfig)
    val next = ReportPalette.of(context)
    if (next.dark != palette.dark) {
      palette = next
      render()
    }
  }

  /** What the render thread produced: a resolved tree with the icons it needs, or nothing (no template). */
  private class Rendered(val root: ReportNode?, val icons: Map<String, Drawable?>, val data: ReportData?)

  fun render() {
    if (!isAttachedToWindow) return
    val ticket = ++generation
    // A view detached during a light/dark switch never saw onConfigurationChanged: read the mode again.
    palette = ReportPalette.of(context)
    val json = ReportTemplateChannel.json
    val segment = filterSegment ?: ReportWindow.DAILY
    val dateMs = filterDate?.toLong() ?: System.currentTimeMillis()
    io.execute {
      // Superseded before it started (a prop batch and a template write land together): skip the aggregation.
      if (ticket != generation) return@execute
      val rendered = try {
        compute(json, segment, dateMs)
      } catch (e: Exception) {
        Log.w(TAG, "usage report not rendered", e)
        Rendered(null, emptyMap(), null)
      }
      main.post {
        if (ticket != generation) return@post
        show(rendered)
      }
    }
  }

  private fun compute(json: String?, segment: String, dateMs: Long): Rendered {
    val template = ReportTemplate.parse(json) ?: return Rendered(null, emptyMap(), null)
    val now = System.currentTimeMillis()
    val zone = TimeZone.getDefault()
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    val window = ReportWindow.of(segment, dateMs, zone, locale, now)
    val data = source.load(window, now)
    if (debuggable) Log.d(TAG, describe(window, data))
    val root = ReportResolver(data, ReportIcuText(context, locale, zone), source.labels).resolve(template)
    // The icons the resolved tree shows (the lists' rows, `limit` applied), decoded here, off the main thread.
    val icons = LinkedHashMap<String, Drawable?>()
    if (root != null) collectIconPackages(root, icons)
    for (packageName in icons.keys) icons[packageName] = source.icon(packageName)
    return Rendered(root, icons, data)
  }

  private fun collectIconPackages(node: ReportNode, into: MutableMap<String, Drawable?>) {
    when (node) {
      is ReportNode.Stack -> node.children.forEach { collectIconPackages(it, into) }
      is ReportNode.ItemList -> for (row in node.rows) row.children.forEach { collectIconPackages(it, into) }
      is ReportNode.AppIcon -> node.item?.packageName?.let { into[it] = null }
      else -> Unit
    }
  }

  private fun show(rendered: Rendered) {
    removeAllViews()
    val root = rendered.root
    if (root == null) {
      addView(placeholder(), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    } else {
      val built = ReportViewRenderer(context, palette) { rendered.icons[it] }.render(root)
      val lp = LayoutParams(
        built.fixedWidthPx ?: if (built.fullWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        built.fixedHeightPx ?: if (built.fullHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
      )
      lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      addView(built.view, lp)
    }
    requestLayout()
  }

  /** `CustomTemplateView`'s fail-open body: a dashed square and the text, centred, in the secondary label colour. */
  private fun placeholder(): LinearLayout {
    val column = LinearLayout(context)
    column.orientation = VERTICAL
    column.gravity = Gravity.CENTER
    val square = android.view.View(context)
    square.background = GradientDrawable().apply {
      setColor(0)
      cornerRadius = context.px(6.0)
      setStroke(context.px(2.0).toInt(), palette.secondaryLabel, context.px(5.0), context.px(4.0))
    }
    column.addView(square, LayoutParams(context.px(32.0).toInt(), context.px(32.0).toInt()).apply { bottomMargin = context.px(8.0).toInt() })
    val text = TextView(context)
    text.text = context.getString(R.string.expo_st_report_no_template)
    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
    text.setTextColor(palette.secondaryLabel)
    text.includeFontPadding = false
    column.addView(text, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return column
  }

  /** The aggregated numbers, for a debuggable host only (a release build logs nothing of the person's usage). */
  private fun describe(window: ReportWindow, data: ReportData): String {
    val apps = data.topApps.joinToString(" ") { "${it.packageName}=${it.durationMs}ms/${it.pickups}" }
    val categories = data.categories.joinToString(" ") { "${it.name}=${it.durationMs}ms" }
    return "report window=${window.startMs}..${window.endMs} total=${data.totalDurationMs}ms pickups=${data.pickups} withApp=${data.pickupsWithApplicationActivity} " +
      "longest=${data.longestActivityMs}ms apps=${data.appCount} firstPickup=${data.firstPickupMs} hourly=${data.hourlyMs} covered=${data.coverage.coveredFromMs}..${data.coverage.coveredToMs} " +
      "topApps=[$apps] categories=[$categories]"
  }

  private companion object {
    const val TAG = "ExpoScreenTime"

    /** One thread for every report's aggregation and PackageManager reads. */
    val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "expo-screen-time-reports") }
  }
}
