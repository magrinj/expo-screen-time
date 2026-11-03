package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole-block temporary unlock as a pure function of time and usage —
 * the iOS unlock state (`setUnlockState` / `remainingUnlockSeconds` /
 * `handleUnlockStep`), which has no Swift unit-test section of its own — and
 * the validation of `temporaryUnlock` that needs no block composition
 * (`AsyncFunction("temporaryUnlock")` in `ios/ExpoScreenTimeModule.swift`).
 */
class UnlockGrantTest {
  private val t0 = at(2026, 11, 1, 10, 0)
  private val blocked = setOf("com.insta", "com.tiktok")

  /** The engine's tick once a second: [packageName] in use from [fromMs] for [seconds]. */
  private fun use(grant: UnlockGrant, packageName: String, fromMs: Long, seconds: Int): UnlockGrant {
    var metered = grant
    for (second in 0..seconds) metered = metered.observing(using(fromMs + second * 1_000L, packageName), blocked)
    return metered
  }

  // The grant

  @Test
  fun `a grant is floored at 30 s and answers JS with meteringSeconds 1 and a null target`() {
    assertEquals(UnlockGrant("G", 30, t0), UnlockGrant.grant(5, t0, "G"))
    assertEquals(UnlockGrant("G", 300, t0), UnlockGrant.grant(300, t0, "G"))
    assertTrue("a fresh id per grant", UnlockGrant.grant(300, t0).id.isNotEmpty() && UnlockGrant.grant(300, t0).id != UnlockGrant.grant(300, t0).id)
    assertEquals(
      mapOf("budgetSeconds" to 300, "grantedAt" to t0.toDouble(), "meteringSeconds" to 1, "target" to null),
      UnlockGrant.grant(300, t0).toResponse(),
    )
    val handle = mapOf("handle" to "QUJD", "type" to "application")
    assertEquals("a targeted grant echoes the handle", handle, UnlockGrant.grant(300, t0).toResponse(handle)["target"])
  }

  @Test
  fun `the budget is spent only by use of a blocked app, and pauses elsewhere`() {
    val grant = UnlockGrant.grant(300, t0)
    assertEquals(300, grant.remainingSeconds(t0, UTC))
    val used = use(grant, "com.insta", t0 + 1_000, 100)
    assertEquals(200, used.remainingSeconds(t0 + 101_000, UTC))
    assertTrue(used.isActive(t0 + 101_000, UTC))
    val elsewhere = use(used, "com.maps", t0 + 200_000, 500)
    assertEquals("wall-clock does not spend it", 200, elsewhere.remainingSeconds(t0 + 700_000, UTC))
    val other = use(elsewhere, "com.tiktok", t0 + 800_000, 199)
    assertEquals("every suspended app meters the same budget", 1, other.remainingSeconds(t0 + 1_000_000, UTC))
    val spent = other.observing(using(t0 + 1_000_000, "com.tiktok"), blocked)
    assertEquals(0, spent.remainingSeconds(t0 + 1_000_000, UTC))
    assertFalse(spent.isActive(t0 + 1_000_000, UTC))
  }

  @Test
  fun `a grant from another calendar day is stale, in the device's zone`() {
    val grant = UnlockGrant.grant(300, at(2026, 11, 1, 23, 50, zone = PARIS))
    assertEquals(300, grant.remainingSeconds(at(2026, 11, 1, 23, 59, zone = PARIS), PARIS))
    assertEquals("23:50 → 00:01 Paris is 22:50 → 23:01 UTC: the same UTC day, another Paris day", 0, grant.remainingSeconds(at(2026, 11, 2, 0, 1, zone = PARIS), PARIS))
    assertFalse(grant.isActive(at(2026, 11, 2, 0, 1, zone = PARIS), PARIS))
    assertEquals("…and judged in UTC it would still be in force", 300, grant.remainingSeconds(at(2026, 11, 2, 0, 1, zone = PARIS), UTC))
  }

  @Test
  fun `a meter never credits more use than the wall-clock since the grant`() {
    val stale = UnlockGrant("G", 300, t0, UsageAccumulator(0, t0 - 10_000, true))
    assertEquals(50, stale.observing(using(t0 + 50, "com.insta"), blocked).meter.consumedMs)
    assertEquals("a sample from before the grant charges nothing", 0, stale.observing(using(t0 - 1, "com.insta"), blocked).meter.consumedMs)
  }

  @Test
  fun `there is one whole-block unlock at a time - a renewal replaces it, consumption included`() {
    val document = JSONObject()
    UnlockGrant.write(document, use(UnlockGrant.grant(300, t0), "com.insta", t0 + 1_000, 100))
    assertEquals(100, UnlockGrant.read(document)?.consumedSeconds)
    val renewal = UnlockGrant.grant(600, t0 + 200_000)
    UnlockGrant.write(document, renewal)
    assertEquals(renewal, UnlockGrant.read(document))
    assertEquals(600, UnlockGrant.read(document)?.remainingSeconds(t0 + 200_000, UTC))
    UnlockGrant.write(document, null)
    assertNull("a relock clears it", UnlockGrant.read(document))
    assertFalse(document.has(UnlockGrant.KEY))
  }

  @Test
  fun `the grant round-trips through the document and a malformed one is no grant`() {
    val grant = UnlockGrant("G", 300, t0, UsageAccumulator(61_500, t0 + 61_500, true))
    assertEquals(grant, UnlockGrant.fromJson(JSONObject(grant.toJson().toString())))
    assertEquals(UnlockGrant("G", 300, t0), UnlockGrant.fromJson(JSONObject("""{"id":"G","budgetSeconds":300.0,"grantedAt":${t0.toDouble()}}""")))
    assertNull(UnlockGrant.fromJson(null))
    assertNull(UnlockGrant.fromJson(JSONObject("""{"id":"G","budgetSeconds":0,"grantedAt":1}""")))
    assertNull(UnlockGrant.fromJson(JSONObject("""{"id":"G","budgetSeconds":300}""")))
    assertNull("no id", UnlockGrant.fromJson(JSONObject("""{"budgetSeconds":300,"grantedAt":1}""")))
    assertNull(UnlockGrant.read(JSONObject()))
  }

  @Test
  fun `the engine's write-back never undoes a relock or a renewal the app made meanwhile`() {
    val metered = UnlockGrant("G", 300, t0, UsageAccumulator(20_000, t0 + 20_000, true))
    assertEquals(metered, UnlockGrant.merging(stored = UnlockGrant("G", 300, t0), metered = metered))
    assertNull("relocked meanwhile", UnlockGrant.merging(stored = null, metered = metered))
    val renewal = UnlockGrant("R", 600, t0 + 25_000)
    assertEquals("renewed meanwhile: the renewal wins, unmetered", renewal, UnlockGrant.merging(stored = renewal, metered = metered))
    assertNull("spent: cleared", UnlockGrant.merging(stored = UnlockGrant("G", 300, t0), metered = metered.copy(meter = UsageAccumulator(300_000, t0 + 300_000, true))))
  }

  @Test
  fun `a tick is worth persisting once a second while in use`() {
    val grant = UnlockGrant.grant(300, t0, "G")
    val entering = grant.observing(using(t0, "com.insta"), blocked)
    assertTrue(entering.isWorthPersistingOver(grant))
    val moved = entering.observing(using(t0 + 100, "com.insta"), blocked)
    assertFalse(moved.isWorthPersistingOver(entering))
    assertTrue(moved.observing(using(t0 + 1_000, "com.insta"), blocked).isWorthPersistingOver(moved))
    assertTrue("leaving use", moved.observing(idle(t0 + 200), blocked).isWorthPersistingOver(moved))
    assertFalse("idle", grant.observing(idle(t0 + 200), blocked).isWorthPersistingOver(grant))
  }

  // The validation of temporaryUnlock(budgetSeconds, { target? })

  private fun judge(seconds: Any?, target: Any? = null, hasActiveBlock: Boolean = true, containsAllowlist: Boolean = false) =
    TemporaryUnlockRequest.judge(seconds, target, hasActiveBlock, containsAllowlist)

  private fun code(verdict: TemporaryUnlockRequest.Verdict) = (verdict as TemporaryUnlockRequest.Verdict.Refused).refusal.code

  @Test
  fun `a whole-block request needs an active block that is not an allowlist`() {
    assertEquals(TemporaryUnlockRequest.Verdict.WholeBlock(300), judge(300))
    assertEquals("the floor applies", TemporaryUnlockRequest.Verdict.WholeBlock(30), judge(5))
    assertEquals("JS numbers arrive as doubles", TemporaryUnlockRequest.Verdict.WholeBlock(300), judge(300.0))
    assertEquals("NO_ACTIVE_BLOCK", code(judge(300, hasActiveBlock = false)))
    assertEquals("UNSUPPORTED", code(judge(300, containsAllowlist = true)))
    assertEquals("the allowlist refusal comes first, as on iOS", "UNSUPPORTED", code(judge(300, hasActiveBlock = false, containsAllowlist = true)))
    assertEquals("a null target is no target", TemporaryUnlockRequest.Verdict.WholeBlock(300), judge(300, target = JSONObject.NULL))
  }

  @Test
  fun `a budget that is not a number is INVALID_CONFIG`() {
    assertEquals("INVALID_CONFIG", code(judge("300")))
    assertEquals("INVALID_CONFIG", code(judge(null)))
    assertEquals("INVALID_CONFIG", code(judge(Double.NaN)))
  }

  @Test
  fun `a targeted request needs a well-formed handle, whether it is shielded is the block model's verdict`() {
    val handle = mapOf("handle" to "QUJD", "type" to "application")
    assertEquals(TemporaryUnlockRequest.Verdict.Targeted(300, handle), judge(300, target = handle, hasActiveBlock = false))
    assertEquals("an allowlist does not refuse a targeted request here", TemporaryUnlockRequest.Verdict.Targeted(30, handle), judge(5, target = handle, containsAllowlist = true))
    assertEquals("extra keys are dropped", TemporaryUnlockRequest.Verdict.Targeted(300, handle), judge(300, target = handle + ("label" to "Instagram")))
    assertEquals("INVALID_CONFIG", code(judge(300, target = mapOf("handle" to "", "type" to "application"))))
    assertEquals("INVALID_CONFIG", code(judge(300, target = mapOf("handle" to "QUJD", "type" to "token"))))
    assertEquals("INVALID_CONFIG", code(judge(300, target = mapOf("handle" to "QUJD"))))
    assertEquals("INVALID_CONFIG", code(judge(300, target = "QUJD")))
    assertEquals("INVALID_CONFIG", code(judge(300, target = mapOf("handle" to 1, "type" to "application"))))
    assertEquals(mapOf("handle" to "UkVE", "type" to "webDomain"), TemporaryUnlockRequest.parseTarget(mapOf("type" to "webDomain", "handle" to "UkVE")))
  }

  @Test
  fun `the grant carries the sources it suspended, and the engine's write-back keeps them`() {
    val suspended = SuspendedSources(manual = true, schedules = mapOf("A" to 1_000L), thresholds = mapOf("A" to SuspendedSources.Thresholds(1_000, listOf("S3"))))
    val grant = UnlockGrant.grant(60, at(2026, 11, 1, 10, 0), "g1", suspended)
    val document = JSONObject()
    UnlockGrant.write(document, grant)
    assertEquals("round trip through the document", grant, UnlockGrant.read(JSONObject(document.toString())))
    val metered = use(grant, "com.insta", at(2026, 11, 1, 10, 0), 10)
    assertEquals("the write-back keeps the suspension", suspended, UnlockGrant.merging(grant, metered)?.suspended)
    val legacy = JSONObject(document.toString()).also { it.getJSONObject("unlock").remove("suspended") }
    assertEquals("a grant without a readable suspension suspends nothing (fail closed)", SuspendedSources.NONE, UnlockGrant.read(legacy)?.suspended)
  }

  @Test
  fun `a clock set back during a grant hands out no free time - the instants move with the clock`() {
    val t0 = at(2026, 11, 1, 10, 0)
    val grant = use(UnlockGrant.grant(600, t0, "g"), "com.insta", t0, 60)
    assertEquals(60, grant.consumedSeconds)
    // TIME_SET: the clock goes back 5 minutes while the app is in use.
    val delta = -5 * 60_000L
    val unshifted = use(grant, "com.insta", t0 + 60_000 + delta, 30)
    assertEquals("without the shift, the plausibility bound (atMs - grantedAt) charges nothing for about five minutes", 60, unshifted.consumedSeconds)
    val shifted = use(grant.shifted(delta), "com.insta", t0 + 60_000 + delta, 30)
    assertEquals("shifted, the next 30 s of use are charged as before", 90, shifted.consumedSeconds)
    assertEquals("the grant instant moved with the clock", t0 + delta, shifted.grantedAt)
    assertEquals("a zero shift is the same grant", grant, grant.shifted(0))
    assertEquals("an idle meter has no instant to move", UsageAccumulator.EMPTY, UsageAccumulator.EMPTY.shifted(delta))
  }
}
