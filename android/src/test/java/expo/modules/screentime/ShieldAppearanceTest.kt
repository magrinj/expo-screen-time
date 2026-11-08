package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The ShieldConfig mapping: nested and legacy formats, colours, the icon table, the precedence lookup in the store. */
class ShieldAppearanceTest {
  @Test
  fun `a restriction target takes the library's texts and keeps the configured colours, icon and buttons`() {
    val configured = ShieldAppearance.from(
      JSONObject("""{"backgroundColor":"#1a1a2e","icon":"lock.fill","title":{"text":"App Blocked","color":"#ffffff"},"subtitle":{"text":"Work hours","color":"#aaaaaa"},"primaryButton":{"text":"OK"}}""")
    )
    val a = configured.forRestriction("Removing apps is turned off", "Turn it back on in Example.")
    assertEquals(ShieldAppearance.Label("Removing apps is turned off", 0xFFFFFFFF.toInt()), a.title)
    assertEquals(ShieldAppearance.Label("Turn it back on in Example.", 0xFFAAAAAA.toInt()), a.subtitle)
    assertEquals(configured.backgroundColor, a.backgroundColor)
    assertEquals(configured.icon, a.icon)
    assertEquals(configured.primaryButton, a.primaryButton)
    val secondary = ShieldAppearance.from(JSONObject("""{"secondaryButton":{"text":"Snooze"}}"""))
    assertEquals("a secondary button that acts (openApp) is kept", ShieldAppearance.Label("Snooze", null), secondary.forRestriction("t", "s", keepSecondaryButton = true).secondaryButton)
    assertNull("one whose action a restriction ignores is left out", secondary.forRestriction("t", "s", keepSecondaryButton = false).secondaryButton)
    val unblocking = ShieldAppearance.from(JSONObject("""{"primaryButton":{"text":"Unblock","color":"#ffffff"}}"""))
    assertEquals("a primary button whose action a restriction ignores reads the close label, in its colour", ShieldAppearance.Label("OK", 0xFFFFFFFF.toInt()), unblocking.forRestriction("t", "s", primaryText = "OK").primaryButton)
    assertEquals("one that keeps its meaning keeps its label", unblocking.primaryButton, unblocking.forRestriction("t", "s").primaryButton)
    val plain = ShieldAppearance.DEFAULT.forRestriction("t", "s")
    assertEquals(ShieldAppearance.Label("t", null), plain.title)
    assertEquals(ShieldAppearance.Label("s", null), plain.subtitle)
    assertEquals(ShieldAppearance.Icon.App, plain.icon)
  }

  @Test
  fun `the nested contract format maps every field`() {
    val config = JSONObject(
      """{"backgroundColor":"#1a1a2e","backgroundBlurStyle":"dark","icon":"hand.raised.fill",
         "title":{"text":"App Blocked","color":"#ffffff"},"subtitle":{"text":"Work hours","color":"#aaaaaa"},
         "primaryButton":{"text":"OK","color":"#ffffff","backgroundColor":"#4a4ae8"},"secondaryButton":{"text":"Not now","color":"#ffffff"}}"""
    )
    val a = ShieldAppearance.from(config)
    assertEquals(ShieldAppearance.Label("App Blocked", 0xFFFFFFFF.toInt()), a.title)
    assertEquals(ShieldAppearance.Label("Work hours", 0xFFAAAAAA.toInt()), a.subtitle)
    assertEquals(0xFF1A1A2E.toInt(), a.backgroundColor)
    assertEquals(ShieldAppearance.Icon.Symbol("expo_st_ic_hand"), a.icon)
    assertEquals(ShieldAppearance.Label("OK", 0xFFFFFFFF.toInt()), a.primaryButton)
    assertEquals(0xFF4A4AE8.toInt(), a.primaryButtonBackgroundColor)
    assertEquals(ShieldAppearance.Label("Not now", 0xFFFFFFFF.toInt()), a.secondaryButton)
  }

  @Test
  fun `the legacy flat format still reads, as the iOS extension reads it`() {
    val a = ShieldAppearance.from(JSONObject("""{"title":"Blocked","titleColor":"#000000","primaryButtonLabel":"Close","primaryButtonBackgroundColor":"#112233"}"""))
    assertEquals(ShieldAppearance.Label("Blocked", 0xFF000000.toInt()), a.title)
    assertEquals(ShieldAppearance.Label("Close", null), a.primaryButton)
    assertEquals(0xFF112233.toInt(), a.primaryButtonBackgroundColor)
    assertNull(a.subtitle)
  }

  @Test
  fun `colours parse as RRGGBB or RRGGBBAA, anything else is ignored`() {
    assertEquals(0xFF112233.toInt(), ShieldAppearance.color("#112233"))
    assertEquals(0x80112233.toInt(), ShieldAppearance.color("11223380"))
    assertNull(ShieldAppearance.color("#12345"))
    assertNull(ShieldAppearance.color("red"))
    assertNull(ShieldAppearance.color(""))
    assertNull(ShieldAppearance.color(null))
    // A bad colour drops the colour, not the label.
    assertEquals(ShieldAppearance.Label("T", null), ShieldAppearance.from(JSONObject("""{"title":{"text":"T","color":"blue"}}""")).title)
  }

  @Test
  fun `an unmapped or absent icon falls back to the app icon, an empty label is no label`() {
    assertEquals(ShieldAppearance.Icon.App, ShieldAppearance.from(JSONObject("""{"icon":"sparkles"}""")).icon)
    assertEquals(ShieldAppearance.Icon.App, ShieldAppearance.from(JSONObject("{}")).icon)
    assertEquals(ShieldAppearance.Icon.Symbol("expo_st_ic_lock"), ShieldAppearance.from(JSONObject("""{"icon":"lock.fill"}""")).icon)
    assertNull(ShieldAppearance.from(JSONObject("""{"title":{"text":""}}""")).title)
    assertEquals(ShieldAppearance.DEFAULT, ShieldAppearance.from(null))
  }
}
