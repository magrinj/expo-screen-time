package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The manual block: the codec, and `blockSelection`'s validation in the iOS order with the iOS codes. */
class BlockStateTest {
  private val apps = Selection(applications = setOf("com.a", "com.b"))

  private fun code(block: () -> Unit): String = try {
    block()
    fail("expected a ScreenTimeException")
    ""
  } catch (e: ScreenTimeException) {
    e.code
  }

  @Test
  fun `a missing selection is SELECTION_NOT_FOUND whatever the mode, before the mode is looked at`() {
    assertEquals("SELECTION_NOT_FOUND", code { BlockValidation.manualBlock("x", null, null) })
    assertEquals("SELECTION_NOT_FOUND", code { BlockValidation.manualBlock("x", null, "allowlist") })
    // iOS checks existence first: an invalid mode on a missing selection reports the selection.
    assertEquals("SELECTION_NOT_FOUND", code { BlockValidation.manualBlock("x", null, "bogus") })
  }

  @Test
  fun `the mode is blocklist by default and must be one of the two names`() {
    assertEquals(ManualBlock("s", BlockMode.blocklist, setOf("com.a", "com.b")), BlockValidation.manualBlock("s", apps, null))
    assertEquals(ManualBlock("s", BlockMode.blocklist, setOf("com.a", "com.b")), BlockValidation.manualBlock("s", apps, "blocklist"))
    assertEquals(ManualBlock("s", BlockMode.allowlist, setOf("com.a", "com.b")), BlockValidation.manualBlock("s", apps, "allowlist"))
    assertEquals("INVALID_CONFIG", code { BlockValidation.manualBlock("s", apps, "deny") })
    assertEquals("INVALID_CONFIG", code { BlockValidation.manualBlock("s", apps, 1) })
  }

  @Test
  fun `an allowlist holds applications only, at least one, and a blocklist takes anything`() {
    val withCategory = Selection(applications = setOf("com.a"), categories = setOf("GAME"))
    val withDomain = Selection(applications = setOf("com.a"), webDomains = setOf("example.com"))
    assertEquals("INVALID_CONFIG", code { BlockValidation.manualBlock("s", withCategory, "allowlist") })
    assertEquals("INVALID_CONFIG", code { BlockValidation.manualBlock("s", withDomain, "allowlist") })
    assertEquals("INVALID_CONFIG", code { BlockValidation.manualBlock("s", Selection.EMPTY, "allowlist") })
    // iOS does not validate a blocklist's content: an empty one shields nothing.
    assertEquals(BlockMode.blocklist, BlockValidation.manualBlock("s", Selection.EMPTY, null).mode)
    assertEquals(BlockMode.blocklist, BlockValidation.manualBlock("s", withCategory, null).mode)
  }

  @Test
  fun `the manual block round-trips with its apps, and an unknown stored mode reads as blocklist`() {
    val block = ManualBlock("focus", BlockMode.allowlist, setOf("com.a", "com.b"))
    assertEquals(block, ManualBlock.fromJson(JSONObject(block.toJson().toString())))
    assertEquals(BlockSource(BlockSourceKind.Manual, 0, setOf("com.a", "com.b"), BlockMode.allowlist), block.source)
    val legacy = ManualBlock.fromJson(JSONObject().put("selectionId", "s").put("mode", "later"))!!
    assertEquals(BlockMode.blocklist, legacy.mode)
    // No apps captured: a blocklist over nothing shields nothing.
    assertEquals(false, legacy.source.shieldsSomething)
    assertNull(ManualBlock.fromJson(JSONObject()))
    assertNull(ManualBlock.fromJson(null))
  }
}
