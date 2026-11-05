package expo.modules.screentime

/**
 * The current item inside `TopApps` / `Categories`: the iOS `TemplateItem`.
 * An app item carries its package (the icon and label source, where iOS has
 * the token), a category item its name.
 */
internal data class ReportItem(
  val packageName: String?,
  val categoryName: String?,
  val name: String,
  val durationMs: Long,
  /** 1-based position in the list (`bind: "rank"`). */
  val rank: Int,
)

/** A chart entry: the iOS `(label, value)` pair, the value in minutes (`duration / 60`). */
internal data class ReportChartEntry(val label: String, val minutes: Double)

/** One repetition of an item list's children, for one item. */
internal data class ReportItemRow(val item: ReportItem, val children: List<ReportNode>)

/**
 * A template node with its bindings resolved, what the renderer draws.
 * Every node keeps its `props` (the style props: colors, sizes, padding…)
 * untouched; only the data-dependent parts are computed here: texts,
 * fractions, chart series, item rows.
 */
internal sealed class ReportNode {
  abstract val props: Map<String, Any?>

  enum class StackKind { VSTACK, HSTACK, ZSTACK, GRID }
  enum class ListKind { TOP_APPS, CATEGORIES }

  data class Stack(val kind: StackKind, override val props: Map<String, Any?>, val children: List<ReportNode>) : ReportNode()
  data class Spacer(override val props: Map<String, Any?>) : ReportNode()
  data class Divider(override val props: Map<String, Any?>) : ReportNode()
  data class Text(val text: String, override val props: Map<String, Any?>) : ReportNode()
  /** `name` is the SF Symbol name (`"questionmark"` when missing); the renderer maps it to a Material icon or nothing. */
  data class Symbol(val name: String, override val props: Map<String, Any?>) : ReportNode()
  data class Chart(
    val type: String,
    val bind: String,
    val entries: List<ReportChartEntry>,
    /** Mean of the entries in minutes, 0 when empty: the `averageLine` rule. */
    val averageMinutes: Double,
    override val props: Map<String, Any?>,
  ) : ReportNode()
  /** `fraction` is `min(bound / goal, 1)`, 0 when the goal is not positive. */
  data class ProgressBar(val fraction: Double, override val props: Map<String, Any?>) : ReportNode()
  /** `percentLabel` is the circular gauge's center text, `Int(ratio * 100)` truncated ("42%"). */
  data class Gauge(val fraction: Double, val linear: Boolean, val percentLabel: String, override val props: Map<String, Any?>) : ReportNode()
  /**
   * `rows` is the children resolved once per item, `limit` applied; empty
   * with [emptyText] set when there were no items and the prop was given
   * (iOS renders the text), empty with it null when there is nothing to
   * draw (iOS renders an empty VStack).
   */
  data class ItemList(val kind: ListKind, val rows: List<ReportItemRow>, val emptyText: String?, override val props: Map<String, Any?>) : ReportNode()
  /** `item` is null outside an item scope: iOS draws a placeholder square. */
  data class AppIcon(val item: ReportItem?, override val props: Map<String, Any?>) : ReportNode()
  data class AppTitle(val text: String, override val props: Map<String, Any?>) : ReportNode()
}

/** The display name of a package (`localizedDisplayName` on iOS); the wiring answers from PackageManager. */
internal fun interface ReportLabels {
  fun labelOf(packageName: String): String?

  companion object {
    /** No labels: the package name itself. */
    val PACKAGE_NAME = ReportLabels { null }
  }
}

/**
 * Tolerant prop reads, the Kotlin side of the iOS helpers (`cgFloat`,
 * `double`, `as? String`, `as? Bool`, `as? [String]`): a prop of the wrong
 * type reads as absent, never as an error.
 */
internal object ReportProps {
  fun double(props: Map<String, Any?>, key: String): Double? = when (val value = props[key]) {
    is Number -> value.toDouble().takeIf { it.isFinite() }
    else -> null
  }

  fun int(props: Map<String, Any?>, key: String): Int? = ConfigValues.int(props[key])

  fun string(props: Map<String, Any?>, key: String): String? = props[key] as? String

  fun bool(props: Map<String, Any?>, key: String): Boolean? = props[key] as? Boolean

  /** `as? [String]`: every element must be a string, else the list is absent. */
  fun strings(props: Map<String, Any?>, key: String): List<String>? {
    val list = ConfigValues.list(props[key]) ?: return null
    val result = ArrayList<String>(list.size)
    for (entry in list) result.add(entry as? String ?: return null)
    return result
  }

  /**
   * `Color(hex:)`: `#RRGGBB` or `#RRGGBBAA` (the `#` optional) as a packed
   * ARGB int, null for anything else. Hex digits only.
   */
  fun color(hex: String?): Int? {
    val digits = hex?.trim()?.replace("#", "") ?: return null
    if (digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = digits.toLongOrNull(16) ?: return null
    return when (digits.length) {
      6 -> (0xFFL shl 24 or value).toInt()
      8 -> {
        val rgb = value ushr 8
        val alpha = value and 0xFF
        (alpha shl 24 or rgb).toInt()
      }
      else -> null
    }
  }

  fun color(props: Map<String, Any?>, key: String): Int? = color(string(props, key))
}

/**
 * Resolves a [ReportTemplate] against [ReportData] into a [ReportNode] tree:
 * the data half of `TemplateNodeView` (`CustomTemplateReport.swift`), rule
 * for rule, so the renderer only has to draw.
 *
 * - **Bindings** (`resolvedText`, `boundValue`): `totalDuration`,
 *   `longestActivity` and `appDuration` are durations (formatted with the
 *   node's `durationStyle`); `pickups` and `appCount` are counts;
 *   `firstPickup` is a short time or "—"; `appName` and `rank` are the
 *   item's. An unknown binding renders "" as text and `totalDuration` as a
 *   number (the iOS `default` branches). `format: "percent"` renders any
 *   binding as `round(min(value / goal, 1) * 100)%`.
 * - **Item-only bindings outside an item scope** (the serializer rejects
 *   them, an older JS may not): `appDuration` is 0, `appName` "", `rank` 0,
 *   `AppTitle` "", `AppIcon` a placeholder; nothing fails.
 * - **Item scopes**: `TopApps` and `Categories` repeat their children per
 *   item, `limit` (default 5) items at most, `rank` from 1; no items and an
 *   `emptyText` renders the text.
 * - **Charts**: `hourly` (24 entries labelled by hour index), `topApps` and
 *   `categories` (`maxItems`, default 6), values in minutes, with the mean.
 * - **ProgressBar / Gauge**: `min(value / goal, 1)`, 0 when `goal` is not
 *   positive, `goal` defaulting to 1.
 * - **Unknown node types** are dropped and their siblings kept (a newer JS
 *   than this native).
 */
internal class ReportResolver(
  private val data: ReportData,
  private val text: ReportText,
  private val labels: ReportLabels = ReportLabels.PACKAGE_NAME,
) {
  fun resolve(template: ReportTemplate): ReportNode? = resolve(template.root, null)

  fun resolve(node: ReportTemplateNode, item: ReportItem?): ReportNode? {
    val props = node.props
    return when (node.type) {
      "VStack" -> ReportNode.Stack(ReportNode.StackKind.VSTACK, props, children(node, item))
      "HStack" -> ReportNode.Stack(ReportNode.StackKind.HSTACK, props, children(node, item))
      "ZStack" -> ReportNode.Stack(ReportNode.StackKind.ZSTACK, props, children(node, item))
      "Grid" -> ReportNode.Stack(ReportNode.StackKind.GRID, props, children(node, item))
      "Spacer" -> ReportNode.Spacer(props)
      "Divider" -> ReportNode.Divider(props)
      "Text" -> ReportNode.Text(resolvedText(props, item), props)
      "Symbol" -> ReportNode.Symbol(ReportProps.string(props, "name") ?: "questionmark", props)
      "Chart" -> chart(props)
      "ProgressBar" -> ReportNode.ProgressBar(ratio(props, item), props)
      "Gauge" -> {
        val fraction = ratio(props, item)
        ReportNode.Gauge(fraction, ReportProps.string(props, "style") == "linear", "${(fraction * 100).toInt()}%", props)
      }
      "TopApps" -> itemList(ReportNode.ListKind.TOP_APPS, node, appItems())
      "Categories" -> itemList(ReportNode.ListKind.CATEGORIES, node, categoryItems())
      "AppIcon" -> ReportNode.AppIcon(item, props)
      "AppTitle" -> ReportNode.AppTitle(item?.name ?: "", props)
      else -> null
    }
  }

  private fun children(node: ReportTemplateNode, item: ReportItem?): List<ReportNode> = node.children.mapNotNull { resolve(it, item) }

  fun appItems(): List<ReportItem> = data.topApps.mapIndexed { index, app ->
    ReportItem(app.packageName, null, labels.labelOf(app.packageName) ?: app.packageName, app.durationMs, index + 1)
  }

  fun categoryItems(): List<ReportItem> = data.categories.mapIndexed { index, category ->
    ReportItem(null, category.name, category.name, category.durationMs, index + 1)
  }

  private fun itemList(kind: ReportNode.ListKind, node: ReportTemplateNode, items: List<ReportItem>): ReportNode {
    val props = node.props
    val limit = ReportProps.int(props, "limit") ?: DEFAULT_LIST_LIMIT
    val emptyText = ReportProps.string(props, "emptyText")
    if (items.isEmpty() && emptyText != null) return ReportNode.ItemList(kind, emptyList(), emptyText, props)
    val rows = items.take(limit.coerceAtLeast(0)).map { entry -> ReportItemRow(entry, children(node, entry)) }
    return ReportNode.ItemList(kind, rows, null, props)
  }

  /** `boundValue`: the node's binding as a number, seconds for durations; `totalDuration` for anything else. */
  fun boundValue(props: Map<String, Any?>, item: ReportItem?): Double = when (ReportProps.string(props, "bind")) {
    "pickups" -> data.pickups.toDouble()
    "longestActivity" -> data.longestActivityMs / 1000.0
    "appCount" -> data.appCount.toDouble()
    "appDuration" -> (item?.durationMs ?: 0L) / 1000.0
    else -> data.totalDurationMs / 1000.0
  }

  /** `min(boundValue / goal, 1)`, 0 for a non-positive goal; `goal` defaults to 1. */
  fun ratio(props: Map<String, Any?>, item: ReportItem?): Double {
    val goal = ReportProps.double(props, "goal") ?: 1.0
    return if (goal > 0) minOf(boundValue(props, item) / goal, 1.0) else 0.0
  }

  fun resolvedText(props: Map<String, Any?>, item: ReportItem?): String {
    val bind = ReportProps.string(props, "bind")
    var value = if (bind != null) {
      if (ReportProps.string(props, "format") == "percent") {
        "${Math.round(ratio(props, item) * 100)}%"
      } else {
        when (bind) {
          "totalDuration" -> duration(data.totalDurationMs, props)
          "pickups" -> data.pickups.toString()
          "longestActivity" -> duration(data.longestActivityMs, props)
          "appCount" -> data.appCount.toString()
          "firstPickup" -> data.firstPickupMs?.let(text::time) ?: ReportText.NO_PICKUP
          "appDuration" -> duration(item?.durationMs ?: 0L, props)
          "appName" -> item?.name ?: ""
          "rank" -> (item?.rank ?: 0).toString()
          else -> ""
        }
      }
    } else {
      ReportProps.string(props, "text") ?: ""
    }
    if (ReportProps.bool(props, "uppercase") == true) value = value.uppercase()
    return value
  }

  private fun duration(ms: Long, props: Map<String, Any?>): String =
    text.duration(ReportDurationParts.ofMillis(ms), ReportDurationStyle.of(ReportProps.string(props, "durationStyle")))

  private fun chart(props: Map<String, Any?>): ReportNode.Chart {
    val bind = ReportProps.string(props, "bind") ?: "hourly"
    val type = ReportProps.string(props, "type") ?: "bar"
    val maxItems = ReportProps.int(props, "maxItems") ?: DEFAULT_CHART_ITEMS
    val entries = when (bind) {
      "topApps" -> appItems().take(maxItems.coerceAtLeast(0)).map { ReportChartEntry(it.name, it.durationMs / 60_000.0) }
      "categories" -> categoryItems().take(maxItems.coerceAtLeast(0)).map { ReportChartEntry(it.name, it.durationMs / 60_000.0) }
      else -> data.hourlyMs.mapIndexed { hour, ms -> ReportChartEntry(hour.toString(), ms / 60_000.0) }
    }
    val average = if (entries.isEmpty()) 0.0 else entries.sumOf { it.minutes } / entries.size
    return ReportNode.Chart(type, bind, entries, average, props)
  }

  companion object {
    const val DEFAULT_CHART_ITEMS = 6
    const val DEFAULT_LIST_LIMIT = 5
  }
}
