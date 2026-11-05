package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The template model and the resolver against the iOS renderer's reads (`CustomTemplateReport.swift`). */
class ReportTemplateTest {
  private val window = ReportWindow.of("daily", at(2026, 11, 4, 12, 0, zone = PARIS), PARIS, java.util.Locale.US)

  /** A day of data: 1 h 24 min total, hour 9 and 10 busy, three apps, two categories, one pickup. */
  private val data = ReportData(
    totalDurationMs = 5_040_000L,
    pickups = 7,
    pickupsWithApplicationActivity = 5,
    longestActivityMs = 3_600_000L,
    appCount = 3,
    firstPickupMs = at(2026, 11, 4, 9, 41, zone = PARIS),
    hourlyMs = LongArray(24).also { it[9] = 3_600_000L; it[10] = 1_440_000L }.toList(),
    topApps = listOf(ReportAppUsage("com.a", 3_000_000L, 20), ReportAppUsage("com.b", 1_500_000L, 10), ReportAppUsage("com.c", 540_000L, 0)),
    categories = listOf(ReportCategoryUsage("Social", 4_500_000L), ReportCategoryUsage("Other", 540_000L)),
    coverage = ReportCoverage(window.startMs, window.endMs, window.startMs, at(2026, 11, 4, 12, 0, zone = PARIS)),
  )
  private val labels = ReportLabels { if (it == "com.a") "Alpha" else if (it == "com.b") "Beta" else null }
  private val resolver = ReportResolver(data, ReportEnglishText(PARIS), labels)

  private fun node(type: String, props: String = "{}", children: String = "[]") = """{"type":"$type","props":$props,"children":$children}"""
  private fun template(root: String) = ReportTemplate.parse("""{"version":1,"root":$root}""")!!
  private fun resolve(root: String): ReportNode? = resolver.resolve(template(root))
  private fun text(props: String): String = (resolve(node("Text", props)) as ReportNode.Text).text

  // MARK: Parsing

  @Test
  fun `parse accepts the serializer's shape and refuses what iOS shows the placeholder for`() {
    val template = ReportTemplate.parse("""{"version":1,"root":{"type":"VStack","props":{"spacing":12},"children":[{"type":"Text","props":{"text":"hi"}}]}}""")
    assertNotNull(template)
    assertEquals("VStack", template!!.root.type)
    assertEquals(12, template.root.props["spacing"])
    assertEquals(listOf("Text"), template.root.children.map { it.type })
    assertEquals("hi", template.root.children[0].props["text"])

    assertNull("no template", ReportTemplate.parse(null))
    assertNull("empty", ReportTemplate.parse(""))
    assertNull("not JSON", ReportTemplate.parse("{version"))
    assertNull("a newer version than this native", ReportTemplate.parse("""{"version":2,"root":{"type":"VStack"}}"""))
    assertNull("no version", ReportTemplate.parse("""{"root":{"type":"VStack"}}"""))
    assertNull("a string version", ReportTemplate.parse("""{"version":"1","root":{"type":"VStack"}}"""))
    assertNull("a fractional version is not 1 (`as? Int` fails)", ReportTemplate.parse("""{"version":1.5,"root":{"type":"VStack"}}"""))
    assertNull("no root", ReportTemplate.parse("""{"version":1}"""))
    assertNull("a root that is not an object", ReportTemplate.parse("""{"version":1,"root":"VStack"}"""))
    assertNotNull("1.0 reads as 1 (`as? Int` of an NSNumber)", ReportTemplate.parse("""{"version":1.0,"root":{"type":"VStack"}}"""))
  }

  @Test
  fun `malformed nodes read like the iOS casts - missing type, props not an object, mixed children`() {
    val root = ReportTemplate.parse(
      """{"version":1,"root":{"type":"VStack","props":"nope","children":[{"props":{"text":"x"}},{"type":"Text","props":{"text":"y"},"children":"no"}]}}""",
    )!!.root
    assertEquals("props that are not an object are empty", emptyMap<String, Any?>(), root.props)
    assertEquals(2, root.children.size)
    assertEquals("a node without a string type keeps an empty type (rendered as nothing)", "", root.children[0].type)
    assertEquals(emptyList<ReportTemplateNode>(), root.children[1].children)

    val mixed = ReportTemplate.parse("""{"version":1,"root":{"type":"VStack","children":[{"type":"Text"},"stray",{"type":"Text"}]}}""")!!.root
    assertEquals("`as? [[String: Any]]` is all or nothing: one stray element empties the list", 0, mixed.children.size)

    val typed = ReportTemplate.parse(JSONObject("""{"version":1,"root":{"type":7}}"""))!!.root
    assertEquals("", typed.type)
  }

  @Test
  fun `nesting is capped`() {
    var json = """{"type":"Text","props":{"text":"deep"}}"""
    repeat(80) { json = """{"type":"VStack","children":[$json]}""" }
    val root = ReportTemplate.parse("""{"version":1,"root":$json}""")!!.root
    var depth = 0
    var cursor: ReportTemplateNode? = root
    while (cursor != null && cursor.children.isNotEmpty()) {
      cursor = cursor.children[0]
      depth++
    }
    assertEquals(ReportTemplate.MAX_DEPTH, depth)
  }

  // MARK: Bindings

  @Test
  fun `root bindings render like resolvedText`() {
    assertEquals("1h 24m", text("""{"bind":"totalDuration"}"""))
    assertEquals("1 hr, 24 min", text("""{"bind":"totalDuration","durationStyle":"short"}"""))
    assertEquals("1 hour, 24 minutes", text("""{"bind":"totalDuration","durationStyle":"full"}"""))
    assertEquals("7", text("""{"bind":"pickups"}"""))
    assertEquals("1h 0m", text("""{"bind":"longestActivity"}"""))
    assertEquals("3", text("""{"bind":"appCount"}"""))
    assertEquals("9:41\u202FAM", text("""{"bind":"firstPickup"}"""))
    assertEquals("an unknown binding renders empty", "", text("""{"bind":"notifications"}"""))
    assertEquals("static text", "Screen time", text("""{"text":"Screen time"}"""))
    assertEquals("no text and no bind is empty", "", text("{}"))
    assertEquals("a non-string text is absent", "", text("""{"text":42}"""))
    assertEquals("uppercase applies after resolution", "1H 24M", text("""{"bind":"totalDuration","uppercase":true}"""))
    assertEquals("SCREEN TIME", text("""{"text":"Screen time","uppercase":true}"""))
  }

  @Test
  fun `firstPickup renders a dash when there was none`() {
    val none = ReportResolver(data.copy(firstPickupMs = null), ReportEnglishText(PARIS))
    assertEquals("—", (none.resolve(template(node("Text", """{"bind":"firstPickup"}"""))) as ReportNode.Text).text)
  }

  @Test
  fun `percent rounds half away from zero, the gauge label truncates, goals clamp`() {
    // 5040 / 40320 = 0.125 → 12.5 % → "13%" (Swift `.rounded()`), gauge "12%" (`Int(ratio * 100)`).
    assertEquals("13%", text("""{"bind":"totalDuration","format":"percent","goal":40320}"""))
    val gauge = resolve(node("Gauge", """{"bind":"totalDuration","goal":40320}""")) as ReportNode.Gauge
    assertEquals(0.125, gauge.fraction, 1e-9)
    assertEquals("12%", gauge.percentLabel)
    assertEquals(false, gauge.linear)
    assertEquals(true, (resolve(node("Gauge", """{"goal":1,"style":"linear"}""")) as ReportNode.Gauge).linear)

    assertEquals("over the goal clamps to 100", "100%", text("""{"bind":"totalDuration","format":"percent","goal":60}"""))
    assertEquals("a zero goal is 0, not infinity", "0%", text("""{"bind":"pickups","format":"percent","goal":0}"""))
    assertEquals("a negative goal is 0", "0%", text("""{"bind":"pickups","format":"percent","goal":-5}"""))
    assertEquals("counts bind as numbers: 7 of 20 pickups", "35%", text("""{"bind":"pickups","format":"percent","goal":20}"""))
    assertEquals("goal defaults to 1", "100%", text("""{"bind":"pickups","format":"percent"}"""))

    val bar = resolve(node("ProgressBar", """{"bind":"longestActivity","goal":7200}""")) as ReportNode.ProgressBar
    assertEquals(0.5, bar.fraction, 1e-9)
    assertEquals("no bind is totalDuration (the iOS default branch)", 5040.0 / 14400, (resolve(node("ProgressBar", """{"goal":14400}""")) as ReportNode.ProgressBar).fraction, 1e-9)
    assertEquals("a non-numeric goal reads as absent → 1 → clamped to 1", 1.0, (resolve(node("ProgressBar", """{"goal":"four hours"}""")) as ReportNode.ProgressBar).fraction, 1e-9)
    assertEquals("an unknown bind is totalDuration too", 0.5, (resolve(node("ProgressBar", """{"bind":"steps","goal":10080}""")) as ReportNode.ProgressBar).fraction, 1e-9)
  }

  @Test
  fun `item-only bindings outside an item scope degrade like iOS`() {
    assertEquals("0m", text("""{"bind":"appDuration"}"""))
    assertEquals("", text("""{"bind":"appName"}"""))
    assertEquals("0", text("""{"bind":"rank"}"""))
    assertEquals("", (resolve(node("AppTitle")) as ReportNode.AppTitle).text)
    assertNull((resolve(node("AppIcon")) as ReportNode.AppIcon).item)
    assertEquals("appDuration as a number is 0", 0.0, (resolve(node("ProgressBar", """{"bind":"appDuration","goal":60}""")) as ReportNode.ProgressBar).fraction, 1e-9)
  }

  // MARK: Item scopes

  @Test
  fun `TopApps repeats its children per app with rank, label and duration, limit 5 by default`() {
    val row = """[${node("AppIcon")},${node("AppTitle")},${node("Text", """{"bind":"appDuration","durationStyle":"short"}""")},${node("Text", """{"bind":"rank"}""")},${node("Text", """{"bind":"appName"}""")}]"""
    val list = resolve(node("TopApps", "{}", row)) as ReportNode.ItemList
    assertEquals(ReportNode.ListKind.TOP_APPS, list.kind)
    assertNull(list.emptyText)
    assertEquals(3, list.rows.size)
    val first = list.rows[0]
    assertEquals(ReportItem("com.a", null, "Alpha", 3_000_000L, 1), first.item)
    assertEquals("com.a", (first.children[0] as ReportNode.AppIcon).item?.packageName)
    assertEquals("Alpha", (first.children[1] as ReportNode.AppTitle).text)
    assertEquals("50 min", (first.children[2] as ReportNode.Text).text)
    assertEquals("1", (first.children[3] as ReportNode.Text).text)
    assertEquals("Alpha", (first.children[4] as ReportNode.Text).text)
    assertEquals("an app without a label shows its package", "com.c", (list.rows[2].children[1] as ReportNode.AppTitle).text)
    assertEquals("3", (list.rows[2].children[3] as ReportNode.Text).text)

    assertEquals(2, (resolve(node("TopApps", """{"limit":2}""", row)) as ReportNode.ItemList).rows.size)
    assertEquals("limit 0 is an empty list, not the empty text (items were not empty)", 0, (resolve(node("TopApps", """{"limit":0,"emptyText":"none"}""", row)) as ReportNode.ItemList).rows.size)
    assertNull((resolve(node("TopApps", """{"limit":0,"emptyText":"none"}""", row)) as ReportNode.ItemList).emptyText)
  }

  @Test
  fun `Categories items carry the category name and nested scopes resolve`() {
    val row = """[${node("HStack", "{}", """[${node("AppIcon")},${node("Text", """{"bind":"appName"}""")},${node("Text", """{"bind":"appDuration","format":"percent","goal":5040}""")}]""")}]"""
    val list = resolve(node("Categories", "{}", row)) as ReportNode.ItemList
    assertEquals(ReportNode.ListKind.CATEGORIES, list.kind)
    assertEquals(listOf("Social", "Other"), list.rows.map { it.item.name })
    assertEquals(listOf(1, 2), list.rows.map { it.item.rank })
    val stack = list.rows[0].children[0] as ReportNode.Stack
    assertEquals(ReportNode.StackKind.HSTACK, stack.kind)
    assertEquals("Social", (stack.children[0] as ReportNode.AppIcon).item?.categoryName)
    assertNull((stack.children[0] as ReportNode.AppIcon).item?.packageName)
    assertEquals("Social", (stack.children[1] as ReportNode.Text).text)
    assertEquals("4500 of 5040 s → 89.28… → 89%", "89%", (stack.children[2] as ReportNode.Text).text)
  }

  @Test
  fun `an empty list renders its emptyText, or nothing without one`() {
    val empty = ReportResolver(data.copy(topApps = emptyList(), categories = emptyList()), ReportEnglishText(PARIS))
    val withText = empty.resolve(template(node("TopApps", """{"emptyText":"No app usage yet"}""", "[${node("AppTitle")}]"))) as ReportNode.ItemList
    assertEquals("No app usage yet", withText.emptyText)
    assertTrue(withText.rows.isEmpty())
    val without = empty.resolve(template(node("Categories", "{}", "[${node("AppTitle")}]"))) as ReportNode.ItemList
    assertNull(without.emptyText)
    assertTrue(without.rows.isEmpty())
    assertNull("a non-string emptyText is absent", (empty.resolve(template(node("TopApps", """{"emptyText":3}"""))) as ReportNode.ItemList).emptyText)
  }

  // MARK: Charts

  @Test
  fun `chart series - hourly is 24 minutes-per-hour entries, lists are capped at 6 by default`() {
    val hourly = resolve(node("Chart", """{"type":"area","bind":"hourly"}""")) as ReportNode.Chart
    assertEquals("area", hourly.type)
    assertEquals("hourly", hourly.bind)
    assertEquals(24, hourly.entries.size)
    assertEquals((0..23).map { it.toString() }, hourly.entries.map { it.label })
    assertEquals(60.0, hourly.entries[9].minutes, 1e-9)
    assertEquals(24.0, hourly.entries[10].minutes, 1e-9)
    assertEquals(0.0, hourly.entries[0].minutes, 1e-9)
    assertEquals("average over the 24 buckets", 84.0 / 24, hourly.averageMinutes, 1e-9)

    val apps = resolve(node("Chart", """{"type":"bar","bind":"topApps"}""")) as ReportNode.Chart
    assertEquals(listOf("Alpha", "Beta", "com.c"), apps.entries.map { it.label })
    assertEquals(listOf(50.0, 25.0, 9.0), apps.entries.map { it.minutes })
    assertEquals(28.0, apps.averageMinutes, 1e-9)

    val categories = resolve(node("Chart", """{"type":"pie","bind":"categories","maxItems":1}""")) as ReportNode.Chart
    assertEquals(listOf("Social"), categories.entries.map { it.label })
    assertEquals(75.0, categories.entries[0].minutes, 1e-9)

    val many = ReportResolver(data.copy(topApps = (1..10).map { ReportAppUsage("com.app$it", it * 60_000L, 0) }), ReportEnglishText(PARIS))
    assertEquals(6, (many.resolve(template(node("Chart", """{"bind":"topApps"}"""))) as ReportNode.Chart).entries.size)
    assertEquals(10, (many.resolve(template(node("Chart", """{"bind":"topApps","maxItems":20}"""))) as ReportNode.Chart).entries.size)

    val defaults = resolve(node("Chart")) as ReportNode.Chart
    assertEquals("no bind is hourly, no type is bar", "hourly" to "bar", defaults.bind to defaults.type)
    assertEquals("an unknown bind is hourly too", 24, (resolve(node("Chart", """{"bind":"weekdays"}""")) as ReportNode.Chart).entries.size)
    val none = ReportResolver(data.copy(topApps = emptyList()), ReportEnglishText(PARIS))
    assertEquals("empty series average 0", 0.0, (none.resolve(template(node("Chart", """{"bind":"topApps"}"""))) as ReportNode.Chart).averageMinutes, 1e-9)
  }

  // MARK: Tree

  @Test
  fun `unknown node types are dropped and their siblings kept, props pass through`() {
    val root = resolve(
      node(
        "VStack",
        """{"spacing":12,"padding":16,"backgroundGradient":["#111111","#222222"]}""",
        """[${node("Text", """{"text":"a"}""")},${node("Hologram", """{"x":1}""")},${node("Spacer", """{"minLength":4}""")},${node("Divider")},${node("Symbol")},${node("Symbol", """{"name":"hourglass"}""")},${node("Grid", """{"columns":3}""")},${node("ZStack")},{"props":{}}]""",
      ),
    ) as ReportNode.Stack
    assertEquals(ReportNode.StackKind.VSTACK, root.kind)
    assertEquals(12, root.props["spacing"])
    assertEquals(listOf("#111111", "#222222"), ReportProps.strings(root.props, "backgroundGradient"))
    assertEquals(7, root.children.size)
    assertTrue(root.children[0] is ReportNode.Text)
    assertTrue(root.children[1] is ReportNode.Spacer)
    assertEquals(4, root.children[1].props["minLength"])
    assertTrue(root.children[2] is ReportNode.Divider)
    assertEquals("a Symbol without a name is a question mark", "questionmark", (root.children[3] as ReportNode.Symbol).name)
    assertEquals("hourglass", (root.children[4] as ReportNode.Symbol).name)
    assertEquals(ReportNode.StackKind.GRID, (root.children[5] as ReportNode.Stack).kind)
    assertEquals(ReportNode.StackKind.ZSTACK, (root.children[6] as ReportNode.Stack).kind)
    assertNull("an unknown root renders nothing", resolve(node("Hologram")))
  }

  @Test
  fun `prop reads are tolerant`() {
    val props = mapOf<String, Any?>("n" to 3, "d" to 2.5, "s" to "x", "b" to true, "list" to listOf("a", "b"), "mixed" to listOf("a", 1), "big" to 1e300)
    assertEquals(3.0, ReportProps.double(props, "n")!!, 1e-9)
    assertEquals(2.5, ReportProps.double(props, "d")!!, 1e-9)
    assertNull(ReportProps.double(props, "s"))
    assertNull(ReportProps.double(props, "missing"))
    assertEquals(3, ReportProps.int(props, "n"))
    assertEquals("a fractional number truncates like NSNumber.intValue", 2, ReportProps.int(props, "d"))
    assertNull(ReportProps.int(props, "big"))
    assertEquals("x", ReportProps.string(props, "s"))
    assertNull(ReportProps.string(props, "n"))
    assertEquals(true, ReportProps.bool(props, "b"))
    assertNull(ReportProps.bool(props, "s"))
    assertEquals(listOf("a", "b"), ReportProps.strings(props, "list"))
    assertNull("`as? [String]` fails on a mixed list", ReportProps.strings(props, "mixed"))
    assertNull(ReportProps.strings(props, "s"))
  }

  @Test
  fun `the docs example resolves end to end`() {
    val json = """{"version":1,"root":{"type":"VStack","props":{"spacing":14,"padding":16,"alignment":"leading"},"children":[
      {"type":"HStack","props":{"spacing":14},"children":[
        {"type":"Gauge","props":{"bind":"totalDuration","goal":14400,"size":64,"color":"#AF52DE"}},
        {"type":"VStack","props":{"spacing":2,"alignment":"leading"},"children":[
          {"type":"Text","props":{"text":"SCREEN TIME","size":11,"color":"#8E8E93","letterSpacing":1}},
          {"type":"Text","props":{"bind":"totalDuration","durationStyle":"abbreviated","size":30,"weight":"bold","monospacedDigit":true}},
          {"type":"Text","props":{"text":"of 4h daily goal","size":12,"color":"#8E8E93"}}]}]},
      {"type":"ProgressBar","props":{"bind":"totalDuration","goal":14400,"height":10,"color":"#AF52DE"}},
      {"type":"Chart","props":{"type":"area","bind":"hourly","accentColor":"#AF52DE","gradient":true,"height":90,"showYAxis":false}},
      {"type":"Divider","props":{}},
      {"type":"TopApps","props":{"limit":3,"spacing":10,"emptyText":"No app usage yet"},"children":[
        {"type":"HStack","props":{"spacing":10},"children":[
          {"type":"AppIcon","props":{"size":26,"cornerRadius":6}},
          {"type":"AppTitle","props":{"size":14}},
          {"type":"Spacer","props":{}},
          {"type":"Text","props":{"bind":"appDuration","durationStyle":"short","size":13,"monospacedDigit":true}}]}]}]}}"""
    val root = resolver.resolve(ReportTemplate.parse(json)!!) as ReportNode.Stack
    assertEquals(5, root.children.size)
    val header = root.children[0] as ReportNode.Stack
    val gauge = header.children[0] as ReportNode.Gauge
    assertEquals(0.35, gauge.fraction, 1e-9)
    assertEquals("35%", gauge.percentLabel)
    assertEquals(0xFFAF52DE.toInt(), ReportProps.color(gauge.props, "color"))
    val titles = header.children[1] as ReportNode.Stack
    assertEquals(listOf("SCREEN TIME", "1h 24m", "of 4h daily goal"), titles.children.map { (it as ReportNode.Text).text })
    assertEquals(0.35, (root.children[1] as ReportNode.ProgressBar).fraction, 1e-9)
    assertEquals(24, (root.children[2] as ReportNode.Chart).entries.size)
    val list = root.children[4] as ReportNode.ItemList
    assertEquals(3, list.rows.size)
    val row = list.rows[1].children[0] as ReportNode.Stack
    assertEquals("Beta", (row.children[1] as ReportNode.AppTitle).text)
    assertEquals("25 min", (row.children[3] as ReportNode.Text).text)
  }
}
