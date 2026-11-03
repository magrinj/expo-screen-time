package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The default text colours over a custom background: the rule and its guarantees. */
class ShieldTextColorsTest {
  private fun hex(argb: Int?): String? = argb?.let { "#%08X".format(it) }

  @Test
  fun `white or black, whichever contrasts more, always clears 4,5 to 1 on any opaque background`() {
    var worst = Double.MAX_VALUE
    for (r in 0..255 step 3) for (g in 0..255 step 3) for (b in 0..255 step 3) {
      val bg = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      val text = ShieldTextColors.onBackground(bg)
      assertTrue(text == ShieldTextColors.WHITE || text == ShieldTextColors.BLACK)
      worst = minOf(worst, ShieldTextColors.contrast(text, bg))
    }
    assertTrue("worst title contrast $worst", worst >= ShieldTextColors.TITLE_MIN_CONTRAST)
  }

  @Test
  fun `the subtitle is secondary yet clears 3 to 1 on any opaque background`() {
    var worst = Double.MAX_VALUE
    for (r in 0..255 step 3) for (g in 0..255 step 3) for (b in 0..255 step 3) {
      val bg = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      val title = ShieldTextColors.onBackground(bg)
      val subtitle = ShieldTextColors.secondaryOn(bg)
      // Secondary: strictly less contrast than the title (unless the title had to be reused at 100%).
      assertTrue(hex(bg), ShieldTextColors.contrast(subtitle, bg) <= ShieldTextColors.contrast(title, bg))
      worst = minOf(worst, ShieldTextColors.contrast(subtitle, bg))
    }
    assertTrue("worst subtitle contrast $worst", worst >= ShieldTextColors.SUBTITLE_MIN_CONTRAST)
  }

  @Test
  fun `the WCAG reference values`() {
    assertEquals(21.0, ShieldTextColors.contrast(ShieldTextColors.WHITE, ShieldTextColors.BLACK), 1e-9)
    assertEquals(1.0, ShieldTextColors.contrast(0xFF123456.toInt(), 0xFF123456.toInt()), 1e-9)
    assertEquals(1.0, ShieldTextColors.relativeLuminance(ShieldTextColors.WHITE), 1e-9)
    assertEquals(0.0, ShieldTextColors.relativeLuminance(ShieldTextColors.BLACK), 1e-9)
    // #808080: the mid grey of sRGB, L = 0.2159 (WebAIM reference), alpha ignored.
    assertEquals(0.2159, ShieldTextColors.relativeLuminance(0x80808080.toInt()), 5e-5)
  }

  @Test
  fun `no background keeps the platform defaults, a background does not touch explicit colours`() {
    val none = ShieldTextColors.resolve(ShieldAppearance.from(JSONObject("""{"title":{"text":"T"},"subtitle":{"text":"S"},"primaryButton":{"text":"OK"},"secondaryButton":{"text":"No"}}""")))
    assertEquals(ShieldTextColors.Resolved(null, null, null, null), none)
    val explicit = ShieldTextColors.resolve(ShieldAppearance.from(JSONObject("""{"backgroundColor":"#000000","title":{"text":"T","color":"#111111"},"subtitle":{"text":"S","color":"#222222"},"primaryButton":{"text":"OK","color":"#101010","backgroundColor":"#000000"},"secondaryButton":{"text":"No","color":"#121212"}}""")))
    assertEquals(ShieldTextColors.Resolved(0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF101010.toInt(), 0xFF121212.toInt()), explicit)
  }

  @Test
  fun `the example app's config gets a white title, a light grey subtitle and white button labels`() {
    val a = ShieldAppearance.from(JSONObject("""{"backgroundColor":"#1a1a2e","title":{"text":"App Blocked"},"subtitle":{"text":"This app is restricted"},"primaryButton":{"text":"OK","backgroundColor":"#4a4ae8"},"secondaryButton":{"text":"Snooze"}}"""))
    val colors = ShieldTextColors.resolve(a)
    assertEquals("#FFFFFFFF", hex(colors.title))
    assertEquals("#FFBABAC0", hex(colors.subtitle))
    assertEquals("#FFFFFFFF", hex(colors.primaryButton))
    assertEquals("#FFFFFFFF", hex(colors.secondaryButton))
  }

  @Test
  fun `a button background alone decides its own label, the shield background decides the rest`() {
    val colors = ShieldTextColors.resolve(ShieldAppearance.from(JSONObject("""{"primaryButton":{"text":"OK","backgroundColor":"#FFD54F"}}""")))
    assertEquals(ShieldTextColors.Resolved(null, null, ShieldTextColors.BLACK, null), colors)
    val light = ShieldTextColors.resolve(ShieldAppearance.from(JSONObject("""{"backgroundColor":"#F5F5F5","primaryButton":{"text":"OK","backgroundColor":"#1C1B1F"}}""")))
    assertEquals(ShieldTextColors.BLACK, light.title)
    assertEquals(ShieldTextColors.WHITE, light.primaryButton)
    assertNull(ShieldTextColors.resolve(ShieldAppearance.from(JSONObject("""{"backgroundColor":"#F5F5F5","primaryButton":{"text":"OK"}}"""))).primaryButton)
  }
}

/**
 * The cases of `scripts/swift-tests/fixtures/shield-text-colors.tsv`, which
 * the Swift tests (`scripts/swift-tests/main.swift`, "ShieldTextColors —
 * parity") read too: a colour string is parsed by this platform's parser
 * ([ShieldAppearance.color]) and resolved by [ShieldTextColors.resolve];
 * the result must be the fixture's, written by the Python reference next
 * to it. Three implementations, one table.
 */
class ShieldTextColorsSwiftParityTest {
  private fun fixture(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, "scripts/swift-tests/fixtures/shield-text-colors.tsv")
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    error("shield-text-colors.tsv not found above ${System.getProperty("user.dir")}")
  }

  private fun parse(cell: String): Int? = if (cell == "-") null else ShieldAppearance.color(cell)

  /** `#RRGGBB`, or `#RRGGBBAA` when not opaque, as the fixture writes colours; `-` for null. */
  private fun format(argb: Int?): String {
    if (argb == null) return "-"
    val alpha = argb ushr 24
    val rgb = "#%06X".format(argb and 0xFFFFFF)
    return if (alpha == 0xFF) rgb else rgb + "%02X".format(alpha)
  }

  @Test
  fun `every fixture case resolves to the colours the Swift side must produce`() {
    val rows = fixture().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    assertTrue("fixture has cases", rows.size >= 10)
    for (row in rows) {
      val cells = row.split("\t")
      assertEquals("row shape: $row", 12, cells.size)
      assertEquals("=>", cells[7])
      val (name, background, title, subtitle, buttonBackground, button, secondary) = cells
      val resolved = ShieldTextColors.resolve(parse(background), parse(title), parse(subtitle), parse(buttonBackground), parse(button), parse(secondary))
      val actual = listOf(resolved.title, resolved.subtitle, resolved.primaryButton, resolved.secondaryButton).map(::format)
      assertEquals(name, cells.subList(8, 12), actual)
      println("  ok   $name: $actual")
    }
  }

  private operator fun <T> List<T>.component6() = this[5]
  private operator fun <T> List<T>.component7() = this[6]
}
