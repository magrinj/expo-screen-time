package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The configured shield actions, read as the iOS `ShieldActionExtension.configuredAction` reads them. */
class ShieldActionsTest {
  private fun config(json: String) = JSONObject(json)

  @Test
  fun `the JS shape - names and objects`() {
    val config = config("""{"primaryAction":"close","secondaryAction":{"type":"snooze","duration":300}}""")
    assertEquals(ShieldAction.Close, ShieldAction.configured(config, ShieldAction.PRIMARY))
    assertEquals(ShieldAction.Snooze(300), ShieldAction.configured(config, ShieldAction.SECONDARY))
    assertEquals(ShieldAction.Unblock, ShieldAction.configured(config("""{"primaryAction":"unblock"}"""), ShieldAction.PRIMARY))
    assertEquals(ShieldAction.Defer, ShieldAction.configured(config("""{"primaryAction":"defer"}"""), ShieldAction.PRIMARY))
    assertEquals(ShieldAction.OpenApp, ShieldAction.configured(config("""{"primaryAction":{"type":"openApp"}}"""), ShieldAction.PRIMARY))
    assertEquals("a JS number arrives as a double", ShieldAction.Snooze(90), ShieldAction.configured(config("""{"primaryAction":{"type":"snooze","duration":90.0}}"""), ShieldAction.PRIMARY))
  }

  @Test
  fun `on a restriction target, openApp is honoured and every other action closes`() {
    assertEquals(ShieldAction.OpenApp, ShieldAction.forRestriction(ShieldAction.OpenApp))
    assertEquals(ShieldAction.Close, ShieldAction.forRestriction(ShieldAction.Close))
    assertEquals("nothing a button does lifts a restriction", ShieldAction.Close, ShieldAction.forRestriction(ShieldAction.Unblock))
    assertEquals(ShieldAction.Close, ShieldAction.forRestriction(ShieldAction.Snooze(300)))
    assertEquals("a dialog's shield is not kept up for nothing", ShieldAction.Close, ShieldAction.forRestriction(ShieldAction.Defer))
    assertEquals("no configuration", ShieldAction.Close, ShieldAction.forRestriction(null))
  }

  @Test
  fun `on a restriction target, only a close or openApp button keeps its meaning`() {
    assertTrue(ShieldAction.keepsMeaningOnRestriction(ShieldAction.Close))
    assertTrue(ShieldAction.keepsMeaningOnRestriction(ShieldAction.OpenApp))
    assertTrue("a button with no action closes, as it says", ShieldAction.keepsMeaningOnRestriction(null))
    assertFalse(ShieldAction.keepsMeaningOnRestriction(ShieldAction.Unblock))
    assertFalse(ShieldAction.keepsMeaningOnRestriction(ShieldAction.Snooze(300)))
    assertFalse(ShieldAction.keepsMeaningOnRestriction(ShieldAction.Defer))
  }

  @Test
  fun `no configuration, a missing button or an unknown name is null - the shield closes`() {
    assertNull(ShieldAction.configured(null, ShieldAction.PRIMARY))
    assertNull("no secondary action configured", ShieldAction.configured(config("""{"primaryAction":"close"}"""), ShieldAction.SECONDARY))
    assertNull("an unknown name", ShieldAction.configured(config("""{"primaryAction":"explode"}"""), ShieldAction.PRIMARY))
    assertNull("a snooze object with an unknown type", ShieldAction.configured(config("""{"primaryAction":{"type":"nap","duration":30}}"""), ShieldAction.PRIMARY))
  }

  @Test
  fun `a snooze without a positive duration is misconfigured and closes`() {
    assertEquals(ShieldAction.Close, ShieldAction.configured(config("""{"primaryAction":{"type":"snooze"}}"""), ShieldAction.PRIMARY))
    assertEquals(ShieldAction.Close, ShieldAction.configured(config("""{"primaryAction":{"type":"snooze","duration":0}}"""), ShieldAction.PRIMARY))
    assertEquals(ShieldAction.Close, ShieldAction.configured(config("""{"primaryAction":{"type":"snooze","duration":"300"}}"""), ShieldAction.PRIMARY))
  }

  @Test
  fun `the legacy native keys are still readable`() {
    val legacy = config("""{"primaryButtonAction":"snooze","primaryButtonSnoozeDuration":120,"secondaryButtonAction":"unblock"}""")
    assertEquals(ShieldAction.Snooze(120), ShieldAction.configured(legacy, ShieldAction.PRIMARY))
    assertEquals(ShieldAction.Unblock, ShieldAction.configured(legacy, ShieldAction.SECONDARY))
  }
}
