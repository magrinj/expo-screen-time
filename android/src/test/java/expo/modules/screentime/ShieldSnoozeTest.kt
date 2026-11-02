package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the Swift sections "ShieldSnooze — naming and persistence",
 * "ShieldSnoozes — today only", "ShieldSnoozes.granting",
 * "ShieldSnoozes.recording" and "ShieldSnoozes.ending"
 * (`scripts/swift-tests/main.swift`). Android has no DeviceActivity meter per
 * snooze, so these cases have no counterpart: the `expo_st_snooze_<id>`
 * activity name and `isActivityName` (2 cases), `ActivityRegistration`'s
 * view of it (1), the step event names (2), and in `recording` the
 * outcomes that are DeviceActivity callbacks — `stale`, the two
 * `implausible` fires, and `malformed` (4). "Recording" is the engine's tick
 * here (`ShieldSnoozes.observing`).
 */
class ShieldSnoozeTest {
  private val t0 = at(2026, 11, 1, 10, 0)
  private val insta = ShieldSnooze("ID-1", "application", "com.insta", 300, t0)
  private val tiktok = ShieldSnooze("ID-2", "application", "com.tiktok", 60, at(2026, 11, 1, 10, 5))
  private val yesterday = ShieldSnooze("ID-0", "webDomain", "reddit.com", 600, at(2026, 10, 31, 23, 50))

  /** Feeds the engine's tick once a second: [packageName] in use from [fromMs] for [seconds]. */
  private fun use(snoozes: List<ShieldSnooze>, packageName: String, fromMs: Long, seconds: Int): ShieldSnoozes.Observed {
    var observed = ShieldSnoozes.Observed(snoozes, emptyList(), changed = false)
    val spent = mutableListOf<ShieldSnooze>()
    for (second in 0..seconds) {
      observed = ShieldSnoozes.observing(using(fromMs + second * 1_000L, packageName), observed.snoozes)
      spent += observed.spent
    }
    return ShieldSnoozes.Observed(observed.snoozes, spent, changed = true)
  }

  // "ShieldSnooze — naming and persistence"

  @Test
  fun `nothing consumed yet`() {
    assertEquals(300, insta.remainingSeconds)
    assertEquals(0, insta.consumedSeconds)
  }

  @Test
  fun `document round trip`() {
    assertEquals(insta, ShieldSnooze.fromJson(JSONObject(insta.toJson().toString())))
    val metered = insta.copy(meter = UsageAccumulator(61_500, t0 + 61_500, true))
    assertEquals(metered, ShieldSnooze.fromJson(JSONObject(metered.toJson().toString())))
  }

  @Test
  fun `number fields written as doubles are read back`() {
    val json = insta.toJson()
      .put("budgetSeconds", 300.0)
      .put("grantedAt", insta.grantedAt.toDouble())
      .put("meter", JSONObject().put("consumedMs", 60_000.0))
    val read = ShieldSnooze.fromJson(json)
    assertEquals(60, read?.consumedSeconds)
    assertEquals(insta.grantedAt, read?.grantedAt)
    assertEquals(300, read?.budgetSeconds)
  }

  @Test
  fun `malformed records are dropped`() {
    assertNull(ShieldSnooze.fromJson(JSONObject("""{"id":"x"}""")))
    assertNull(ShieldSnooze.fromJson(JSONObject("""{"id":"","tokenKind":"application","token":"com.insta","budgetSeconds":30,"grantedAt":1.0}""")))
    assertNull(ShieldSnooze.fromJson(JSONObject("""{"id":"x","tokenKind":"application","token":"com.insta","budgetSeconds":0,"grantedAt":1.0}""")))
    assertNull("no grant instant", ShieldSnooze.fromJson(JSONObject("""{"id":"x","tokenKind":"application","token":"com.insta","budgetSeconds":30}""")))
    assertNull("a JSON null token is no token (Android's optString would say \"null\")", ShieldSnooze.fromJson(JSONObject("""{"id":"x","tokenKind":"application","token":null,"budgetSeconds":30,"grantedAt":1}""")))
    assertNull("a numeric id is no id", ShieldSnooze.fromJson(JSONObject("""{"id":7,"tokenKind":"application","token":"com.insta","budgetSeconds":30,"grantedAt":1}""")))
    assertEquals("a missing meter is a fresh one", UsageAccumulator.EMPTY, ShieldSnooze.fromJson(JSONObject("""{"id":"x","tokenKind":"application","token":"com.insta","budgetSeconds":30,"grantedAt":1}"""))?.meter)
  }

  @Test
  fun `the same target is the same kind and token, whatever the id`() {
    assertTrue(insta.coversSameTargetAs(ShieldSnooze("ID-9", "application", "com.insta", 1, 0)))
    assertFalse(insta.coversSameTargetAs(tiktok))
    assertFalse(insta.coversSameTargetAs(ShieldSnooze("ID-9", "category", "com.insta", 1, 0)))
  }

  // "ShieldSnoozes — today only"

  @Test
  fun `a snooze granted on another calendar day is void, even if the day's end was never ticked`() {
    assertEquals(listOf(insta, tiktok), ShieldSnoozes.current(listOf(yesterday, insta, tiktok), at(2026, 11, 1, 12, 0), UTC))
  }

  @Test
  fun `and in force on its own day`() {
    assertEquals(listOf(yesterday), ShieldSnoozes.current(listOf(yesterday), at(2026, 10, 31, 23, 59), UTC))
  }

  @Test
  fun `the day is the device's calendar day`() {
    assertEquals(listOf(insta), ShieldSnoozes.current(listOf(insta), at(2026, 11, 1, 23, 30, zone = PARIS), PARIS))
  }

  // "ShieldSnoozes.granting — concurrent snoozes; the same target restarts"

  @Test
  fun `the first snooze, a snooze on another target runs alongside, the same target snoozed again restarts`() {
    val one = ShieldSnoozes.granting(insta, emptyList())
    assertEquals(ShieldSnoozes.Granted(listOf(insta), emptyList()), one)
    val two = ShieldSnoozes.granting(tiktok, one.snoozes)
    assertEquals("the first keeps its budget", ShieldSnoozes.Granted(listOf(insta, tiktok), emptyList()), two)
    val instaAgain = ShieldSnooze("ID-3", "application", "com.insta", 120, at(2026, 11, 1, 10, 9))
    val again = ShieldSnoozes.granting(instaAgain, two.snoozes)
    assertEquals("a fresh budget and id; the old snooze is returned", ShieldSnoozes.Granted(listOf(tiktok, instaAgain), listOf(insta)), again)
  }

  // "ShieldSnoozes.recording — the meter's steps" → the engine's tick

  @Test
  fun `observed use is consumed and the other snooze is untouched`() {
    val list = listOf(insta, tiktok)
    val step30 = use(list, "com.insta", t0 + 15_000, 30)
    assertEquals(30, step30.snoozes[0].consumedSeconds)
    assertEquals("not even its meter: an idle snooze costs no write", tiktok, step30.snoozes[1])
    assertTrue(step30.spent.isEmpty())
  }

  @Test
  fun `samples with an earlier clock never move consumption backwards`() {
    val step90 = use(listOf(insta, tiktok), "com.insta", t0 + 10_000, 90)
    assertEquals(90, step90.snoozes[0].consumedSeconds)
    // The last sample was at t0 + 100 s; the clock steps back 5 s.
    val back = ShieldSnoozes.observing(using(t0 + 95_000, "com.insta"), step90.snoozes)
    assertEquals(90, back.snoozes[0].consumedSeconds)
    assertEquals("remaining = budget − consumed", 210, back.snoozes[0].remainingSeconds)
    val forward = ShieldSnoozes.observing(using(t0 + 96_000, "com.insta"), back.snoozes)
    assertEquals("…and the stretch resumes from the new clock", 91, forward.snoozes[0].consumedSeconds)
    // A jump far enough back that the wall-clock since the grant is below
    // what was consumed: nothing more is credited until it catches up (the
    // iOS plausibility rule — consumption never exceeds elapsed time).
    val farBack = ShieldSnoozes.observing(using(t0 + 50_000, "com.insta"), step90.snoozes)
    val catchingUp = ShieldSnoozes.observing(using(t0 + 51_000, "com.insta"), farBack.snoozes)
    assertEquals(90, catchingUp.snoozes[0].consumedSeconds)
  }

  @Test
  fun `the budget's last second spends the snooze - it is gone, the other stays`() {
    val spent = use(listOf(insta, tiktok), "com.insta", t0 + 100_000, 300)
    assertEquals(listOf(tiktok), spent.snoozes)
    assertEquals("ID-1", spent.spent.single().id)
    assertEquals(300, spent.spent.single().consumedSeconds)
  }

  @Test
  fun `use past the budget spends it too`() {
    // tiktok's 60 s budget, ticks every 7 s: the ninth lands at 63 s of use.
    var observed = ShieldSnoozes.Observed(listOf(tiktok), emptyList(), changed = false)
    var spent = emptyList<ShieldSnooze>()
    for (tick in 0..9) {
      observed = ShieldSnoozes.observing(using(tiktok.grantedAt + 1_000 + tick * 7_000L, "com.tiktok"), observed.snoozes)
      spent = spent + observed.spent
    }
    assertTrue(observed.snoozes.isEmpty())
    assertEquals(63, spent.single().consumedSeconds)
  }

  // "ShieldSnoozes.ending — the day's end, or a stop"

  @Test
  fun `ending forgets that snooze only, ending one already gone does nothing`() {
    val dayEnd = ShieldSnoozes.ending("ID-1", listOf(insta, tiktok))
    assertEquals(ShieldSnoozes.Ended(listOf(tiktok), ended = true), dayEnd)
    assertEquals(ShieldSnoozes.Ended(listOf(tiktok), ended = false), ShieldSnoozes.ending("ID-1", listOf(tiktok)))
  }

  // Android: the device's day in Europe/Paris, across DST

  @Test
  fun `a snooze granted after midnight Paris time is in force all day, through the 25-hour DST-end day`() {
    // 2026-10-25: 03:00 CEST becomes 02:00 CET. 01:30 Paris is still 2026-10-24 in UTC.
    val granted = ShieldSnooze("ID-D", "application", "com.insta", 600, at(2026, 10, 25, 1, 30, zone = PARIS))
    assertEquals(listOf(granted), ShieldSnoozes.current(listOf(granted), at(2026, 10, 25, 2, 30, zone = PARIS), PARIS))
    assertEquals("…still at 23:59", listOf(granted), ShieldSnoozes.current(listOf(granted), at(2026, 10, 25, 23, 59, 59, zone = PARIS), PARIS))
    assertEquals("void from 00:00 the next day", emptyList<ShieldSnooze>(), ShieldSnoozes.current(listOf(granted), at(2026, 10, 26, 0, 0, zone = PARIS), PARIS))
    assertEquals("the day's end is 25 hours after the day's start", 25 * 3_600_000L, UsageMeter.dayEndMs(granted.grantedAt, PARIS) - at(2026, 10, 25, 0, 0, zone = PARIS))
    assertEquals("judged in UTC it would be void two hours into the Paris day — the zone is the device's", emptyList<ShieldSnooze>(),
      ShieldSnoozes.current(listOf(granted), at(2026, 10, 25, 2, 30, zone = PARIS), UTC))
  }

  @Test
  fun `a snooze granted just before the DST-start gap survives the gap and ends at the 23-hour day's end`() {
    // 2026-03-29: 02:00 CET becomes 03:00 CEST.
    val granted = ShieldSnooze("ID-S", "application", "com.insta", 600, at(2026, 3, 29, 1, 59, zone = PARIS))
    assertEquals(listOf(granted), ShieldSnoozes.current(listOf(granted), at(2026, 3, 29, 3, 1, zone = PARIS), PARIS))
    assertEquals(at(2026, 3, 30, 0, 0, zone = PARIS), UsageMeter.dayEndMs(granted.grantedAt, PARIS))
    assertEquals(23 * 3_600_000L, UsageMeter.dayEndMs(granted.grantedAt, PARIS) - at(2026, 3, 29, 0, 0, zone = PARIS))
    assertEquals("the last millisecond of the day", listOf(granted), ShieldSnoozes.current(listOf(granted), at(2026, 3, 29, 23, 59, 59, zone = PARIS, ms = 999), PARIS))
    assertEquals(emptyList<ShieldSnooze>(), ShieldSnoozes.current(listOf(granted), at(2026, 3, 30, 0, 0, zone = PARIS), PARIS))
  }

  // Android: metering with screen-off gaps

  @Test
  fun `a snooze is not spent while the screen is off, even with the target in the foreground`() {
    var observed = ShieldSnoozes.Observed(listOf(insta, tiktok), emptyList(), changed = false)
    for (second in 0..10) observed = ShieldSnoozes.observing(using(t0 + second * 1_000L, "com.insta"), observed.snoozes)
    assertEquals(10, observed.snoozes[0].consumedSeconds)
    // Screen off for 30 s, the engine still sees com.insta up front.
    for (second in 10..40) {
      observed = ShieldSnoozes.observing(idle(t0 + second * 1_000L, foreground = "com.insta", screenInteractive = false), observed.snoozes)
      assertFalse("an idle tick changes nothing: no write", second > 10 && observed.changed)
    }
    assertEquals(10, observed.snoozes[0].consumedSeconds)
    for (second in 40..50) observed = ShieldSnoozes.observing(using(t0 + second * 1_000L, "com.insta"), observed.snoozes)
    assertEquals(20, observed.snoozes[0].consumedSeconds)
    assertEquals("the other target was never in use", 0, observed.snoozes[1].consumedSeconds)
    // Another app comes up front 100 ms later: the stretch closes with those
    // 100 ms, and the other app's use is not this target's.
    val elsewhere = ShieldSnoozes.observing(using(t0 + 50_100, "com.maps"), observed.snoozes)
    assertEquals(20_100, elsewhere.snoozes[0].meter.consumedMs)
    assertEquals(20, ShieldSnoozes.observing(using(t0 + 70_000, "com.maps"), elsewhere.snoozes).snoozes[0].consumedSeconds)
  }

  @Test
  fun `only an application can be snoozed in v1 - a category or a web domain cannot be metered`() {
    assertFalse(yesterday.isInUse(using(0, "reddit.com")))
    assertFalse(ShieldSnooze("c", "category", "GAME", 30, 0).isInUse(using(0, "GAME")))
    assertEquals(ShieldSnooze("ID", "application", "com.insta", 30, t0), ShieldSnooze.grant("ID", "application", "com.insta", 5, t0))
    assertEquals(ShieldSnooze("ID", "application", "com.insta", 300, t0), ShieldSnooze.grant("ID", "application", "com.insta", 300, t0))
    assertNull(ShieldSnooze.grant("ID", "category", "GAME", 300, t0))
    assertNull(ShieldSnooze.grant("ID", "webDomain", "reddit.com", 300, t0))
    assertNull(ShieldSnooze.grant("", "application", "com.insta", 300, t0))
    assertNull(ShieldSnooze.grant("ID", "application", "", 300, t0))
  }

  @Test
  fun `a tick is worth persisting once a second while in use, on entering or leaving use, and when spent`() {
    var observed = ShieldSnoozes.observing(using(t0, "com.insta"), listOf(insta, tiktok))
    assertTrue("entering use", observed.changed)
    for (tick in 1..9) {
      observed = ShieldSnoozes.observing(using(t0 + tick * 100L, "com.insta"), observed.snoozes)
      assertFalse("tick $tick: the meter moved, nothing a reader could see", observed.changed)
    }
    observed = ShieldSnoozes.observing(using(t0 + 1_000, "com.insta"), observed.snoozes)
    assertTrue("a whole second consumed", observed.changed)
    observed = ShieldSnoozes.observing(using(t0 + 1_100, "com.insta"), observed.snoozes)
    assertFalse(observed.changed)
    observed = ShieldSnoozes.observing(idle(t0 + 1_200), observed.snoozes)
    assertTrue("leaving use", observed.changed)
    assertFalse("idle", ShieldSnoozes.observing(idle(t0 + 1_300), observed.snoozes).changed)
    val nearlySpent = listOf(tiktok.copy(meter = UsageAccumulator(59_900, tiktok.grantedAt + 60_000, true)))
    assertTrue("spent", ShieldSnoozes.observing(using(tiktok.grantedAt + 60_100, "com.tiktok"), nearlySpent).changed)
  }

  // Android: the engine's write-back over a list the app changed meanwhile

  @Test
  fun `merging keeps the engine's meters, the app's grants and removals, and drops the spent`() {
    val metered = insta.copy(meter = UsageAccumulator(20_000, t0 + 20_000, true))
    val fresh = ShieldSnooze("ID-7", "application", "com.maps", 90, t0 + 30_000)
    val replacedInsta = ShieldSnooze("ID-8", "application", "com.insta", 120, t0 + 31_000)
    val observed = ShieldSnoozes.Observed(listOf(metered), emptyList(), changed = true)
    assertEquals(listOf(metered, fresh), ShieldSnoozes.merging(stored = listOf(insta, fresh), observed = observed))
    assertEquals("a replaced snooze keeps its fresh meter: new id", listOf(replacedInsta), ShieldSnoozes.merging(stored = listOf(replacedInsta), observed = observed))
    assertEquals("ended by the app: gone", emptyList<ShieldSnooze>(), ShieldSnoozes.merging(stored = emptyList(), observed = observed))
    val spent = ShieldSnoozes.Observed(listOf(tiktok), listOf(metered), changed = true)
    assertEquals("spent by the engine: gone", listOf(tiktok), ShieldSnoozes.merging(stored = listOf(insta, tiktok), observed = spent))
  }

  @Test
  fun `the list round-trips through the document, an empty list leaves no key, malformed entries are dropped`() {
    val document = JSONObject()
    ShieldSnoozes.write(document, listOf(insta, tiktok))
    assertEquals(listOf(insta, tiktok), ShieldSnoozes.read(JSONObject(document.toString())))
    document.getJSONArray(ShieldSnoozes.KEY).put(JSONObject("""{"id":"x"}""")).put("garbage")
    assertEquals(listOf(insta, tiktok), ShieldSnoozes.read(document))
    ShieldSnoozes.write(document, emptyList())
    assertFalse(document.has(ShieldSnoozes.KEY))
    assertEquals(emptyList<ShieldSnooze>(), ShieldSnoozes.read(JSONObject()))
  }
}
