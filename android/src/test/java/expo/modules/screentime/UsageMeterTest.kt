package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the Swift section "UsageMeter — the ladder the unlock and the
 * snoozes share" (`scripts/swift-tests/main.swift`), for Android's exact
 * accumulator. Not ported, because they describe the DeviceActivity ladder
 * iOS needs to learn usage it cannot read: `thresholds(budget:)` (5 cases),
 * `step(forBudget:)` (2 cases, replaced by "meteringSeconds is 1"), "the
 * ladder moves in meteringSeconds steps", the `isPlausible` slack (2 cases,
 * replaced by "never more than the wall-clock since the grant"), "the slack
 * can never let a whole step through", and the 15-minute minimum-interval
 * detour of `meterEnd` (1 case).
 */
class UsageMeterTest {
  // "UsageMeter — the ladder the unlock and the snoozes share"

  @Test
  fun `budgets are floored at one step, 30 s`() {
    assertEquals(30, UsageMeter.budget(5))
    assertEquals(30, UsageMeter.budget(30))
    assertEquals(31, UsageMeter.budget(31))
  }

  @Test
  fun `meteringSeconds is 1 for every budget, the floor included - Android meters exactly, iOS in ladder steps`() {
    for (budget in listOf(5, 30, 100, 1_800, 1_801, 7_200, 86_399)) {
      assertEquals(1, UnlockGrant.grant(budget, 0).toResponse()["meteringSeconds"])
    }
    assertEquals(1, UsageMeter.METERING_SECONDS)
  }

  @Test
  fun `a meter never credits more use than the wall-clock since the grant allows - the isPlausible rule`() {
    // A meter that claims to have been in use since before the grant (a
    // stale record) is charged only the 50 ms the grant has existed.
    val grantedAt = at(2026, 11, 1, 10, 0)
    val stale = UsageAccumulator(consumedMs = 0, sampledAtMs = grantedAt - 10_000, inUse = true)
    val charged = stale.observe(grantedAt + 50, inUse = true, maxChargeMs = 50)
    assertEquals(50, charged.consumedMs)
    assertEquals(0, charged.consumedSeconds)
    // The whole snooze path applies the bound from its grant instant.
    val snooze = ShieldSnooze("ID-1", "application", "com.insta", 30, grantedAt, stale)
    assertEquals("the same bound on the whole-block grant", 50, UnlockGrant("G", 30, grantedAt, stale).observing(using(grantedAt + 50, "com.insta"), setOf("com.insta")).meter.consumedMs)
    assertEquals(50, snooze.observing(using(grantedAt + 50, "com.insta")).meter.consumedMs)
    assertFalse("a 30 s snooze is not spent 50 ms after the grant", snooze.observing(using(grantedAt + 50, "com.insta")).isSpent)
  }

  @Test
  fun `a budget granted during the day is void at the day's end`() {
    assertEquals(at(2026, 11, 2, 0, 0), UsageMeter.dayEndMs(at(2026, 11, 1, 10, 0), UTC))
    assertEquals("…whenever in the day", at(2026, 11, 2, 0, 0), UsageMeter.dayEndMs(at(2026, 11, 1, 23, 43, 59), UTC))
    // iOS runs the meter 15 min + 1 into the next day here because
    // DeviceActivity refuses shorter intervals; the budget is void at
    // midnight on both platforms.
    assertEquals(at(2026, 11, 2, 0, 0), UsageMeter.dayEndMs(at(2026, 11, 1, 23, 50), UTC))
  }

  @Test
  fun `the day's end is in the device's calendar`() {
    assertEquals(at(2026, 11, 2, 0, 0, zone = PARIS), UsageMeter.dayEndMs(at(2026, 11, 1, 23, 50, zone = PARIS), PARIS))
  }

  @Test
  fun `durations arrive as Double, Long or Int`() {
    assertEquals(300, UsageMeter.seconds(300.0))
    assertEquals(45, UsageMeter.seconds(45L))
    assertEquals(12, UsageMeter.seconds(12))
    assertEquals(7, UsageMeter.seconds(7.9))
  }

  @Test
  fun `anything else is not a duration`() {
    assertNull(UsageMeter.seconds("300"))
    assertNull(UsageMeter.seconds(null))
    assertNull(UsageMeter.seconds(Double.POSITIVE_INFINITY))
    assertNull(UsageMeter.seconds(Double.NaN))
    assertNull(UsageMeter.seconds(Long.MAX_VALUE))
    assertNull(UsageMeter.seconds(JSONObject.NULL))
  }

  // Android: the exact accumulator

  @Test
  fun `the accumulator charges the time between samples while the target is in use`() {
    val meter = UsageAccumulator.EMPTY
      .observe(0, true)
      .observe(1_000, true)
      .observe(2_500, false)
      .observe(4_000, true)
      .observe(4_500, true)
    assertEquals(3_000, meter.consumedMs)
    assertEquals(3, meter.consumedSeconds)
    assertEquals("whole seconds only", 1, UsageAccumulator(1_999).consumedSeconds)
  }

  @Test
  fun `a screen-off gap is not use, even with the target still in the foreground`() {
    val meter = UsageAccumulator.EMPTY
      .observe(0, true)
      .observe(10_000, false) // the screen went off; the engine says "not in use"
      .observe(30_000, false)
      .observe(60_000, false)
      .observe(60_000, true) // screen on, the target is still up front
      .observe(70_000, true)
    assertEquals(20_000, meter.consumedMs)
  }

  @Test
  fun `an idle sample leaves the meter untouched, so an idle budget never dirties the document`() {
    val idle = UsageAccumulator(consumedMs = 5_000)
    assertTrue(idle.observe(1_000, false) === idle)
    assertEquals(UsageAccumulator(5_000, 1_000, true), idle.observe(1_000, true))
    assertEquals("leaving use closes the stretch", UsageAccumulator(6_000, null, false), idle.observe(1_000, true).observe(2_000, false))
  }

  @Test
  fun `a gap longer than 60 s between samples is unmeasured time, not use`() {
    val stalled = UsageAccumulator.EMPTY.observe(0, true).observe(60_001, true)
    assertEquals("the engine was not observing: nothing is charged", 0, stalled.consumedMs)
    assertEquals("…but the stretch resumes from the new sample", 1_000, stalled.observe(61_001, true).consumedMs)
    assertEquals("exactly 60 s is still charged", 60_000, UsageAccumulator.EMPTY.observe(0, true).observe(60_000, true).consumedMs)
  }

  @Test
  fun `a sample with an earlier clock than the stretch charges nothing and rebases it`() {
    val back = UsageAccumulator.EMPTY.observe(10_000, true).observe(5_000, true)
    assertEquals(0, back.consumedMs)
    assertEquals(5_000L, back.sampledAtMs)
    assertEquals(1_000, back.observe(6_000, true).consumedMs)
  }

  @Test
  fun `the meter round-trips through the document, numbers as another writer left them`() {
    val meter = UsageAccumulator(1_500, 2_000, true)
    assertEquals(meter, UsageAccumulator.fromJson(JSONObject(meter.toJson().toString())))
    assertEquals(UsageAccumulator(6_000), UsageAccumulator.fromJson(JSONObject(UsageAccumulator(6_000).toJson().toString())))
    assertEquals(meter, UsageAccumulator.fromJson(JSONObject("""{"consumedMs":1500.0,"sampledAtMs":2.0E3,"inUse":true}""")))
    assertEquals(UsageAccumulator.EMPTY, UsageAccumulator.fromJson(null))
    assertEquals("garbage is a fresh meter", UsageAccumulator.EMPTY, UsageAccumulator.fromJson(JSONObject("""{"consumedMs":"x"}""")))
    assertEquals("a negative consumption is none", UsageAccumulator.EMPTY, UsageAccumulator.fromJson(JSONObject("""{"consumedMs":-5}""")))
    assertEquals("in use without an instant is not in use", UsageAccumulator(3_000), UsageAccumulator.fromJson(JSONObject("""{"consumedMs":3000,"inUse":true}""")))
  }
}
