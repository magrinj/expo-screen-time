package expo.modules.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision the engine takes per foreground change: blocklist, allowlist inversion, exemptions, isBlocked. */
class ShieldPolicyTest {
  private val launchable = setOf("com.chrome", "com.clock", "com.maps", "com.settings", "com.launcher", "com.host")
  private val exempt = ShieldPolicy.exemptions(
    host = "com.host",
    launchers = listOf("com.launcher"),
    systemUi = "com.android.systemui",
    dialer = "com.dialer",
    emergency = listOf("com.emergency"),
    inputMethod = "com.ime",
    settings = "com.settings",
  )

  @Test
  fun `a blocklist shields the selection's apps and nothing else`() {
    val block = ActiveBlock(BlockMode.blocklist, setOf("com.chrome"))
    assertEquals(setOf("com.chrome"), ShieldPolicy.targets(setOf("com.chrome", "com.clock"), block, launchable, exempt))
    assertTrue(ShieldPolicy.targets(setOf("com.clock"), block, launchable, exempt).isEmpty())
    // Not launchable, still in the selection (an edited selection): shielded.
    assertTrue(ShieldPolicy.shields("com.chrome", block, emptySet(), exempt))
  }

  @Test
  fun `an allowlist shields every launchable app except the selection's`() {
    val block = ActiveBlock(BlockMode.allowlist, setOf("com.clock"))
    assertEquals(setOf("com.chrome", "com.maps"), ShieldPolicy.targets(setOf("com.chrome", "com.clock", "com.maps"), block, launchable, exempt))
    assertFalse(ShieldPolicy.shields("com.clock", block, launchable, exempt))
    // Apps without a launcher entry (permission dialogs, the share sheet) are reached from an allowed app: never shielded.
    assertFalse(ShieldPolicy.shields("com.permissioncontroller", block, launchable, exempt))
  }

  @Test
  fun `exemptions win over both modes`() {
    val allowlist = ActiveBlock(BlockMode.allowlist, setOf("com.clock"))
    val blocklist = ActiveBlock(BlockMode.blocklist, setOf("com.host", "com.launcher", "com.settings", "com.dialer"))
    for (pkg in listOf("com.host", "com.launcher", "com.android.systemui", "com.dialer", "com.emergency", "com.ime", "com.settings")) {
      assertFalse(pkg, ShieldPolicy.shields(pkg, allowlist, launchable, exempt))
      assertFalse(pkg, ShieldPolicy.shields(pkg, blocklist, launchable, exempt))
    }
    assertEquals(7, exempt.size)
    // Null identities (a device without that app) leave no empty entry behind.
    assertEquals(setOf("com.host"), ShieldPolicy.exemptions("com.host", emptyList(), null, null, emptyList(), null, null))
  }

  @Test
  fun `no block shields nothing`() {
    assertTrue(ShieldPolicy.targets(setOf("com.chrome"), null, launchable, exempt).isEmpty())
    assertFalse(ShieldPolicy.shields("com.chrome", null, launchable, exempt))
  }

  @Test
  fun `isBlocked counts a block that shields something`() {
    assertTrue(ActiveBlock(BlockMode.blocklist, setOf("com.chrome")).shieldsSomething)
    // A blocklist over an emptied selection shields nothing (iOS omits such a source).
    assertFalse(ActiveBlock(BlockMode.blocklist, emptySet()).shieldsSomething)
    // An allowlist always does: every other app, even once its selection was emptied.
    assertTrue(ActiveBlock(BlockMode.allowlist, emptySet()).shieldsSomething)
  }
}
