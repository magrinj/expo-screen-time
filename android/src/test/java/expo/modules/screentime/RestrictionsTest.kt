package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-control restrictions (`denyAppRemoval`, `denyAppInstallation`) as
 * the engine composes them: the flags' codec, the target selection and the
 * block sources they become. The iOS flags have no shield, so there is no
 * Swift case to mirror.
 */
class RestrictionsTest {
  private val installer = "com.google.android.packageinstaller"
  private val play = "com.android.vending"
  private val galaxy = "com.sec.android.app.samsungapps"
  private val targets = RestrictionTargets(setOf(installer), setOf(play, galaxy))

  // MARK: - The flags

  @Test
  fun `no flag reads as none, and none is not persisted`() {
    assertEquals(Restrictions.NONE, Restrictions.fromJson(null))
    assertEquals(Restrictions.NONE, Restrictions.fromJson(JSONObject()))
    assertFalse(Restrictions.NONE.any)
  }

  @Test
  fun `the flags round-trip through JSON`() {
    val both = Restrictions(removal = true, installation = true)
    assertEquals(both, Restrictions.fromJson(both.toJson()))
    val one = Restrictions.NONE.with(RestrictionKind.installation, true)
    assertEquals(Restrictions(removal = false, installation = true), Restrictions.fromJson(one.toJson()))
    assertTrue(one.any && one.has(RestrictionKind.installation) && !one.has(RestrictionKind.removal))
    assertEquals(Restrictions.NONE, one.with(RestrictionKind.installation, false))
  }

  @Test
  fun `a flag of another type reads as off`() {
    assertEquals(Restrictions.NONE, Restrictions.fromJson(JSONObject().put("removal", "yes").put("installation", 1)))
  }

  // MARK: - The targets

  @Test
  fun `the installer serves both restrictions, the stores the installation one`() {
    assertEquals(setOf(installer), targets.of(RestrictionKind.removal))
    assertEquals(setOf(installer, play, galaxy), targets.of(RestrictionKind.installation))
    assertFalse(targets.isEmpty)
    assertTrue(RestrictionTargets.NONE.isEmpty)
  }

  @Test
  fun `select drops the host, the resolver's chooser, nulls and empty names, and merges the two installer answers`() {
    val selected = RestrictionTargets.select(
      uninstallers = listOf(installer, null),
      installers = listOf(installer, "android", ""),
      stores = listOf(play, "com.host", galaxy, null),
      host = "com.host",
    )
    assertEquals(setOf(installer), selected.installer)
    assertEquals(setOf(play, galaxy), selected.stores)
  }

  @Test
  fun `a Samsung installer answers the uninstall and install intents with one package`() {
    val samsung = "com.samsung.android.packageinstaller"
    val selected = RestrictionTargets.select(listOf(samsung), listOf(samsung), emptyList(), "com.host")
    assertEquals(setOf(samsung), selected.installer)
    assertTrue(selected.stores.isEmpty())
  }

  // MARK: - The sources

  @Test
  fun `no restriction, no source`() {
    assertTrue(RestrictionSources.compose(Restrictions.NONE, targets).isEmpty())
  }

  @Test
  fun `each restriction in force is one blocklist source over its targets, at precedence 3, started at 0`() {
    val sources = RestrictionSources.compose(Restrictions(removal = true, installation = true), targets)
    assertEquals(listOf(BlockSourceKind.Restriction(RestrictionKind.removal), BlockSourceKind.Restriction(RestrictionKind.installation)), sources.map { it.kind })
    for (source in sources) {
      assertEquals(BlockMode.blocklist, source.mode)
      assertEquals(0L, source.startedAtMs)
      assertEquals(3, source.kind.precedence)
      assertNull("no activity owns a restriction shield", source.kind.activityName)
      assertTrue(source.shieldsSomething)
    }
    assertEquals(setOf(installer), sources[0].applications)
    assertEquals(setOf(installer, play, galaxy), sources[1].applications)
  }

  @Test
  fun `the installer leaves every restriction source for two minutes after the admin's deactivation, the stores do not`() {
    val both = Restrictions(removal = true, installation = true)
    val disabledAt = 1_000_000L
    assertEquals(2 * 60_000L, RestrictionSources.HOLD_OFF_MS)
    assertEquals("no deactivation, no hold-off", 2, RestrictionSources.compose(both, targets, disabledAt, null).size)
    val during = RestrictionSources.compose(both, targets, disabledAt + 60_000, disabledAt)
    // "Deactivate & uninstall" ends in the installer's dialog: no restriction may cover it, the installation one included.
    assertEquals(listOf(BlockSourceKind.Restriction(RestrictionKind.installation)), during.map { it.kind })
    assertEquals(setOf(play, galaxy), during.single().applications)
    assertFalse("the dialog is shielded by no source", ShieldPolicy.shields(installer, during, launchable = emptySet(), exempt = emptySet()))
    assertTrue("the stores stay shielded", ShieldPolicy.shields(play, during, launchable = emptySet(), exempt = emptySet()))
    val installationAlone = RestrictionSources.compose(Restrictions(installation = true), targets, disabledAt + 60_000, disabledAt)
    assertEquals("installation alone releases the installer too", setOf(play, galaxy), installationAlone.single().applications)
    assertTrue("removal alone has nothing left to shield", RestrictionSources.compose(Restrictions(removal = true), targets, disabledAt + 60_000, disabledAt).isEmpty())
    assertTrue(RestrictionSources.heldOff(disabledAt, disabledAt))
    assertTrue(RestrictionSources.heldOff(disabledAt + RestrictionSources.HOLD_OFF_MS - 1, disabledAt))
    assertFalse(RestrictionSources.heldOff(disabledAt + RestrictionSources.HOLD_OFF_MS, disabledAt))
    assertEquals("back after the window", 2, RestrictionSources.compose(both, targets, disabledAt + RestrictionSources.HOLD_OFF_MS, disabledAt).size)
    assertTrue("a stamp a little in the future (a small clock step) holds off too", RestrictionSources.heldOff(disabledAt - 5_000, disabledAt))
    assertFalse("a clock set back a day after the deactivation does not stretch the hold-off", RestrictionSources.heldOff(disabledAt - 86_400_000L, disabledAt))
    assertFalse("nor one set back by the window's length", RestrictionSources.heldOff(disabledAt - RestrictionSources.HOLD_OFF_MS, disabledAt))
  }

  @Test
  fun `the engine re-composes when the hold-off ends, and only while a restriction is on`() {
    val disabledAt = 1_000_000L
    assertEquals(disabledAt + RestrictionSources.HOLD_OFF_MS, RestrictionSources.holdOffEndMs(Restrictions(removal = true), disabledAt + 10, disabledAt))
    assertEquals("installation alone covers the installer too", disabledAt + RestrictionSources.HOLD_OFF_MS, RestrictionSources.holdOffEndMs(Restrictions(installation = true), disabledAt + 10, disabledAt))
    assertNull("no restriction, nothing to wait for", RestrictionSources.holdOffEndMs(Restrictions.NONE, disabledAt + 10, disabledAt))
    assertNull("after the window there is nothing to wait for", RestrictionSources.holdOffEndMs(Restrictions(removal = true), disabledAt + RestrictionSources.HOLD_OFF_MS, disabledAt))
    assertNull(RestrictionSources.holdOffEndMs(Restrictions(removal = true), disabledAt, null))
    assertNull("a clock set back far behind the stamp holds nothing off", RestrictionSources.holdOffEndMs(Restrictions(removal = true), disabledAt - 86_400_000L, disabledAt))
  }

  @Test
  fun `a restriction whose targets resolve to nothing adds no source`() {
    val noStore = RestrictionTargets(emptySet(), emptySet())
    assertTrue(RestrictionSources.compose(Restrictions(installation = true), noStore).isEmpty())
    val installerOnly = RestrictionTargets(setOf(installer), emptySet())
    assertEquals(setOf(installer), RestrictionSources.compose(Restrictions(installation = true), installerOnly).single().applications)
  }

  // MARK: - Composition with the user's blocks

  private val launchable = setOf(play, galaxy, "com.chrome", "com.host")
  private val exempt = ShieldPolicy.exemptions("com.host", listOf("com.launcher"), "com.android.systemui", "com.dialer", emptyList(), "com.ime", "com.settings")
  private val both = RestrictionSources.compose(Restrictions(removal = true, installation = true), targets)

  @Test
  fun `the restriction shields the installer and the stores, and the exemptions still win`() {
    assertTrue(ShieldPolicy.shields(installer, both, launchable, exempt))
    assertTrue(ShieldPolicy.shields(play, both, launchable, exempt))
    assertFalse("Settings stays reachable: the admin's deactivation lives there", ShieldPolicy.shields("com.settings", both, launchable, exempt))
    assertFalse(ShieldPolicy.shields("com.host", both, launchable, exempt))
    assertFalse(ShieldPolicy.shields("com.chrome", both, launchable, exempt))
    assertEquals(setOf(installer, play, galaxy), ShieldPolicy.covered(both, launchable, exempt))
  }

  @Test
  fun `a user's block of a store owns the shield, union keeps the store shielded when either source goes`() {
    val manual = BlockSource(BlockSourceKind.Manual, 0, setOf(play))
    val all = listOf(manual) + both
    assertEquals(BlockSourceKind.Manual, BlockComposition.owner(play, all)?.kind)
    assertEquals(BlockSourceKind.Restriction(RestrictionKind.installation), BlockComposition.owner(galaxy, all)?.kind)
    assertEquals(BlockSourceKind.Restriction(RestrictionKind.removal), BlockComposition.owner(installer, all)?.kind)
    assertTrue(ShieldPolicy.shields(play, both, launchable, exempt))
    assertTrue(ShieldPolicy.shields(play, listOf(manual), launchable, exempt))
  }

  @Test
  fun `a store the user unblocked under a restriction is owned by the restriction, not by the lifted block`() {
    val manual = BlockSource(BlockSourceKind.Manual, startedAtMs = 5, applications = setOf(play))
    val all = listOf(manual) + RestrictionSources.compose(Restrictions(installation = true), targets)
    assertEquals("not lifted: the user's block owns it", BlockSourceKind.Manual, ShieldPolicy.owner(play, all)?.kind)
    assertEquals("lifted: the restriction still shields it, so its shield is drawn", BlockSourceKind.Restriction(RestrictionKind.installation), ShieldPolicy.owner(play, all, lifted = setOf(play))?.kind)
    assertNull("lifted and covered by no restriction: nothing owns it", ShieldPolicy.owner(play, listOf(manual), lifted = setOf(play)))
    assertEquals("another app's lift changes nothing", BlockSourceKind.Manual, ShieldPolicy.owner(play, all, lifted = setOf("com.other"))?.kind)
  }

  @Test
  fun `a restriction source is never lifted by a snooze or an unblock`() {
    val manual = BlockSource(BlockSourceKind.Manual, 0, setOf(play))
    val lifted = setOf(play, installer)
    assertFalse("a lift takes the app out of the block", ShieldPolicy.shields(play, listOf(manual), launchable, exempt, lifted))
    assertTrue("but never out of the restriction", ShieldPolicy.shields(play, listOf(manual) + both, launchable, exempt, lifted))
    assertTrue(ShieldPolicy.shields(installer, both, launchable, exempt, lifted))
    assertTrue(ShieldPolicy.targets(setOf(play, installer, "com.chrome"), listOf(manual) + both, launchable, exempt, lifted).containsAll(setOf(play, installer)))
  }

  @Test
  fun `the composition's fingerprint ignores the restriction sources`() {
    val manual = BlockSource(BlockSourceKind.Manual, 0, setOf(play))
    assertEquals(BlockComposition.fingerprint(listOf(manual)), BlockComposition.fingerprint(listOf(manual) + both))
    assertEquals("", BlockComposition.fingerprint(both))
    val lifts = ShieldLifts.Lifts(BlockComposition.fingerprint(listOf(manual)), setOf(play))
    assertEquals("an unblock pressed under the module's view of the sources applies under the engine's", setOf(play), lifts.under(listOf(manual) + both))
  }

  @Test
  fun `the union the warmer and the meter read includes the restriction targets`() {
    assertEquals(setOf(installer, play, galaxy), BlockComposition.union(both))
    assertFalse(BlockComposition.containsAllowlist(both))
  }

  @Test
  fun `a restriction alone is intended at every instant for the enforcement gaps`() {
    val zone = java.util.TimeZone.getTimeZone("UTC")
    val none = EnforcementIntent.of(null, MonitoringState(), zone)
    assertTrue(none.windows(1_000, 5_000).isEmpty())
    val removal = EnforcementIntent.of(null, MonitoringState(), zone, Restrictions(removal = true))
    assertEquals(listOf(Occurrence(1_000, 5_000)), removal.windows(1_000, 5_000))
    assertTrue(removal.windows(5_000, 1_000).isEmpty())
  }
}
