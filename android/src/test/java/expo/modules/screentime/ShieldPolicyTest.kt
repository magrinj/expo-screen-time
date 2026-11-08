package expo.modules.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

  // MARK: - One arrival seen by two detectors

  /** The decision with the last launch at wall 10_000 for com.clock, 50 ms ago, its shield already shown and gone. */
  private fun same(
    target: String = "com.clock",
    arrivedAt: Long? = 9_990,
    declared: Boolean = true,
    sinceLaunchMs: Long = 50,
    showing: String? = null,
    shown: Boolean = true,
  ) = ShieldPolicy.isSameArrival(
    target, arrivedAt, declared, lastTarget = "com.clock", lastLaunchAt = 10_000,
    sinceLastLaunchMs = sinceLaunchMs, windowMs = 700, showingTarget = showing, shownSinceLaunch = shown,
  )

  @Test
  fun `the second sighting of an arrival the last launch answered is skipped, in a build with the detector`() {
    assertTrue(same())
    assertTrue(same(arrivedAt = 10_000))
  }

  @Test
  fun `a sighting after the launch is skipped while that shield is on its way or up over the target`() {
    // The window event of the app's own resume, landing after the poll's launch.
    assertTrue(same(arrivedAt = 10_040, shown = false))
    assertTrue(same(arrivedAt = 10_200, sinceLaunchMs = 200, showing = "com.clock"))
  }

  @Test
  fun `a default build never skips a launch`() {
    assertFalse(same(declared = false))
    assertFalse(same(declared = false, arrivedAt = 10_040, shown = false))
  }

  @Test
  fun `an arrival after a shield that was shown and has gone always relaunches - a trampoline, a close and an instant reopen`() {
    assertFalse(same(arrivedAt = 10_001))
    assertFalse(same(arrivedAt = 10_300, sinceLaunchMs = 300))
  }

  @Test
  fun `the standing check and a replay, which carry no arrival, always launch`() {
    assertFalse(same(arrivedAt = null))
    assertFalse(same(arrivedAt = null, shown = false))
  }

  @Test
  fun `another target, or a launch older than the window, launches`() {
    assertFalse(same(target = "com.maps"))
    assertFalse(same(sinceLaunchMs = 700))
    assertFalse(same(sinceLaunchMs = 700, shown = false))
  }

  @Test
  fun `a blocklist shields the selection's apps and nothing else`() {
    val block = listOf(BlockSource(BlockSourceKind.Manual, 0, setOf("com.chrome"), BlockMode.blocklist))
    assertEquals(setOf("com.chrome"), ShieldPolicy.targets(setOf("com.chrome", "com.clock"), block, launchable, exempt))
    assertTrue(ShieldPolicy.targets(setOf("com.clock"), block, launchable, exempt).isEmpty())
    // Not launchable, still in the selection (an edited selection): shielded.
    assertTrue(ShieldPolicy.shields("com.chrome", block, emptySet(), exempt))
  }

  @Test
  fun `an allowlist shields every launchable app except the selection's`() {
    val block = listOf(BlockSource(BlockSourceKind.Manual, 0, setOf("com.clock"), BlockMode.allowlist))
    assertEquals(setOf("com.chrome", "com.maps"), ShieldPolicy.targets(setOf("com.chrome", "com.clock", "com.maps"), block, launchable, exempt))
    assertFalse(ShieldPolicy.shields("com.clock", block, launchable, exempt))
    // Apps without a launcher entry (permission dialogs, the share sheet) are reached from an allowed app: never shielded.
    assertFalse(ShieldPolicy.shields("com.permissioncontroller", block, launchable, exempt))
  }

  @Test
  fun `exemptions win over both modes`() {
    val allowlist = listOf(BlockSource(BlockSourceKind.Manual, 0, setOf("com.clock"), BlockMode.allowlist))
    val blocklist = listOf(BlockSource(BlockSourceKind.Manual, 0, setOf("com.host", "com.launcher", "com.settings", "com.dialer"), BlockMode.blocklist))
    for (pkg in listOf("com.host", "com.launcher", "com.android.systemui", "com.dialer", "com.emergency", "com.ime", "com.settings")) {
      assertFalse(pkg, ShieldPolicy.shields(pkg, allowlist, launchable, exempt))
      assertFalse(pkg, ShieldPolicy.shields(pkg, blocklist, launchable, exempt))
    }
    assertEquals(7, exempt.size)
    // Null identities (a device without that app) leave no empty entry behind.
    assertEquals(setOf("com.host"), ShieldPolicy.exemptions("com.host", emptyList(), null, null, emptyList(), null, null))
  }

  @Test
  fun `covered is what the accessibility detector is scoped to - blocklist apps, allowlist complement, exemptions out, lifted kept`() {
    val blocklist = BlockSource(BlockSourceKind.Manual, 0, setOf("com.chrome", "com.settings"), BlockMode.blocklist)
    assertEquals(setOf("com.chrome"), ShieldPolicy.covered(listOf(blocklist), launchable, exempt))
    // An allowlist covers every launchable app but its own and the exemptions; a non-launchable app is never in it.
    val allowlist = BlockSource(BlockSourceKind.Manual, 0, setOf("com.clock"), BlockMode.allowlist)
    assertEquals(setOf("com.chrome", "com.maps"), ShieldPolicy.covered(listOf(allowlist), launchable, exempt))
    // The union over sources, and a blocklist app that is not launchable (an edited selection) stays.
    val threshold = BlockSource(BlockSourceKind.Threshold("a"), 1, setOf("com.game"), BlockMode.blocklist)
    assertEquals(setOf("com.game", "com.chrome", "com.maps"), ShieldPolicy.covered(listOf(threshold, allowlist), launchable, exempt))
    // Nothing shielded: empty (the caller substitutes the host package; an empty list means every package to the system).
    assertTrue(ShieldPolicy.covered(emptyList(), launchable, exempt).isEmpty())
    assertTrue(ShieldPolicy.covered(listOf(BlockSource(BlockSourceKind.Manual, 0, setOf("com.settings"), BlockMode.blocklist)), launchable, exempt).isEmpty())
  }

  @Test
  fun `no source shields nothing`() {
    assertTrue(ShieldPolicy.targets(setOf("com.chrome"), emptyList(), launchable, exempt).isEmpty())
    assertFalse(ShieldPolicy.shields("com.chrome", emptyList(), launchable, exempt))
  }

  @Test
  fun `isBlocked counts a source that shields something`() {
    assertTrue(BlockSource(BlockSourceKind.Manual, 0, setOf("com.chrome")).shieldsSomething)
    // A blocklist over an emptied selection shields nothing (iOS omits such a source).
    assertFalse(BlockSource(BlockSourceKind.Manual, 0, emptySet()).shieldsSomething)
    // An allowlist always does: every other app, even once its selection was emptied.
    assertTrue(BlockSource(BlockSourceKind.Manual, 0, emptySet(), BlockMode.allowlist).shieldsSomething)
  }

  @Test
  fun `the union of several sources - shielded if any source shields it, the owner's config applies`() {
    val manual = BlockSource(BlockSourceKind.Manual, 0, setOf("com.chrome"))
    val focus = BlockSource(BlockSourceKind.Schedule("focus"), 2_000, setOf("com.clock", "com.maps"), BlockMode.allowlist)
    val sources = listOf(manual, focus)
    // Chrome: the manual blocklist and the allowlist; Maps and Clock: allowed and not listed; anything else launchable: the allowlist.
    assertEquals(setOf("com.chrome"), ShieldPolicy.targets(setOf("com.chrome", "com.clock", "com.maps"), sources, launchable, exempt))
    assertTrue(ShieldPolicy.shields("com.chrome", listOf(focus, manual), launchable, exempt))
    assertFalse("an app without a launcher entry is reached from an allowed app", ShieldPolicy.shields("com.permissioncontroller", sources, launchable, exempt))
    assertEquals("a schedule owns an app it and the manual block both shield", BlockSourceKind.Schedule("focus"), ShieldPolicy.owner("com.chrome", listOf(focus, manual))?.kind)
    assertEquals(BlockSourceKind.Manual, ShieldPolicy.owner("com.chrome", listOf(manual))?.kind)
    assertNull("nothing covers an allowed app", ShieldPolicy.owner("com.clock", sources))
  }

  @Test
  fun `a lifted app - a snooze or an unblock - is shielded by no source, an allowlist included`() {
    val block = listOf(BlockSource(BlockSourceKind.Manual, 0, setOf("com.chrome", "com.clock"), BlockMode.blocklist))
    assertEquals(setOf("com.clock"), ShieldPolicy.targets(setOf("com.chrome", "com.clock"), block, launchable, exempt, lifted = setOf("com.chrome")))
    assertFalse(ShieldPolicy.shields("com.chrome", block, launchable, exempt, lifted = setOf("com.chrome")))
    val allow = listOf(BlockSource(BlockSourceKind.Manual, 0, setOf("com.maps"), BlockMode.allowlist))
    assertFalse("excepted from \"every other app\"", ShieldPolicy.shields("com.chrome", allow, launchable, exempt, lifted = setOf("com.chrome")))
    assertTrue("the others stay shielded", ShieldPolicy.shields("com.clock", allow, launchable, exempt, lifted = setOf("com.chrome")))
    assertEquals("the sources still own the lifted app's configuration", BlockSourceKind.Manual, ShieldPolicy.owner("com.chrome", block)?.kind)
  }
}
