package expo.modules.screentime

import expo.modules.screentime.MonitoringTestClock.MINUTE
import expo.modules.screentime.MonitoringTestClock.date
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The usage accumulator: the union rule for overlapping apps, screen-off gaps, counting bounds, replay safety and the codec. */
class UsageAccumulatorTest {
  private val t0 = date(2026, 10, 28, 10, 0)
  private val selected = setOf("com.a", "com.b")
  private fun resumed(pkg: String, at: Long) = UsageEvent(at, pkg, UsageEventKind.ACTIVITY_RESUMED)
  private fun paused(pkg: String, at: Long) = UsageEvent(at, pkg, UsageEventKind.ACTIVITY_PAUSED)
  private fun device(kind: UsageEventKind, at: Long) = UsageEvent(at, null, kind)
  private fun fresh(countFrom: Long = t0 - 60 * MINUTE, countUntil: Long = t0 + 7 * 60 * MINUTE) = ThresholdProgress(countFrom, countUntil)
  private fun ThresholdProgress.feedAll(vararg events: UsageEvent) = events.fold(this) { p, e -> p.feed(e, selected) }

  @Test
  fun `two selected apps side by side count once (the union rule)`() {
    val split = fresh().feedAll(resumed("com.a", t0), resumed("com.b", t0 + MINUTE), paused("com.a", t0 + 10 * MINUTE), paused("com.b", t0 + 10 * MINUTE))
    assertEquals("10 minutes, not 19", 10 * MINUTE, split.accumulatedMs)
    val staggered = fresh().feedAll(resumed("com.a", t0), resumed("com.b", t0 + 5 * MINUTE), paused("com.a", t0 + 8 * MINUTE), paused("com.b", t0 + 12 * MINUTE))
    assertEquals("the union of [0,8] and [5,12] is 12 minutes", 12 * MINUTE, staggered.accumulatedMs)
    val beside = fresh().feedAll(resumed("com.a", t0), resumed("com.other", t0), paused("com.other", t0 + 3 * MINUTE), paused("com.a", t0 + 4 * MINUTE))
    assertEquals("a selected app next to an unselected one counts fully", 4 * MINUTE, beside.accumulatedMs)
    assertEquals("an unselected app alone counts nothing", 0, fresh().feedAll(resumed("com.other", t0), paused("com.other", t0 + MINUTE)).accumulatedMs)
  }

  @Test
  fun `screen-off and keyguard close every span`() {
    val gap = fresh().feedAll(resumed("com.a", t0), device(UsageEventKind.SCREEN_NON_INTERACTIVE, t0 + 5 * MINUTE), resumed("com.a", t0 + 20 * MINUTE))
    assertEquals(5 * MINUTE, gap.accumulatedMs)
    assertEquals("the span reopened at 10:20", 15 * MINUTE, gap.settle(t0 + 30 * MINUTE).accumulatedMs)
    val locked = fresh().feedAll(resumed("com.a", t0), device(UsageEventKind.KEYGUARD_SHOWN, t0 + 2 * MINUTE))
    assertEquals(2 * MINUTE, locked.accumulatedMs)
    assertNull(locked.activeSinceMs)
    assertTrue("a screen-on event alone reopens nothing", fresh().feedAll(device(UsageEventKind.SCREEN_INTERACTIVE, t0)).open.isEmpty())
    assertEquals("shutdown too", 1 * MINUTE, fresh().feedAll(resumed("com.a", t0), device(UsageEventKind.DEVICE_SHUTDOWN, t0 + MINUTE)).accumulatedMs)
  }

  @Test
  fun `counting is bounded to the occurrence and settling is idempotent`() {
    val bounded = ThresholdProgress(countFromMs = t0, countUntilMs = t0 + 10 * MINUTE)
      .feedAll(resumed("com.a", t0 - 5 * MINUTE))
    assertEquals("a span that began before the bound counts from the bound", 3 * MINUTE, bounded.projectedMs(t0 + 3 * MINUTE))
    assertEquals("nothing after the end", 10 * MINUTE, bounded.projectedMs(t0 + 30 * MINUTE))
    val settled = bounded.settle(t0 + 4 * MINUTE)
    assertEquals(4 * MINUTE, settled.accumulatedMs)
    assertEquals("settling twice adds nothing", settled, settled.settle(t0 + 4 * MINUTE))
    assertEquals("…and continues from the settle point", 6 * MINUTE, settled.settle(t0 + 6 * MINUTE).accumulatedMs)
    assertEquals("a pause after the end adds only up to the end", 10 * MINUTE, settled.feedAll(paused("com.a", t0 + 12 * MINUTE)).accumulatedMs)
    assertEquals("reaches 8 min at 10:08", t0 + 8 * MINUTE, bounded.reachesAt(8 * MINUTE, t0 + 3 * MINUTE))
    assertNull("a target beyond the end is never reached in this occurrence", bounded.reachesAt(15 * MINUTE, t0 + 3 * MINUTE))
    assertNull("nothing open ⇒ no estimate", fresh().reachesAt(MINUTE, t0))
  }

  @Test
  fun `a fresh accumulator is seeded at the tracker's cursor and asks for history before it`() {
    val tracker = UsageForegroundState(mapOf("com.a" to setOf("A"), "com.other" to setOf("O")), t0 - 5 * MINUTE)
    val live = ThresholdProgress.arm(t0 - 5 * MINUTE, t0 + 60 * MINUTE, t0, tracker, selected)
    assertEquals(setOf("com.a"), live.open)
    assertEquals("the span starts where the set is exact: the cursor", t0 - 5 * MINUTE, live.activeSinceMs)
    assertFalse("nothing before the cursor to ask for", live.historyPending)
    assertEquals("the stream from the cursor continues it: no overlap", 5 * MINUTE, live.projectedMs(t0))
    val late = ThresholdProgress.arm(t0 - 30 * MINUTE, t0 + 60 * MINUTE, t0, tracker, selected)
    assertTrue(late.historyPending)
    assertEquals(t0 - 30 * MINUTE, late.historyFromMs)
    assertEquals("history ends at the cursor, where the stream takes over", t0 - 5 * MINUTE, late.historyToMs)
    val blind = ThresholdProgress.arm(t0 - 30 * MINUTE, t0 + 60 * MINUTE, t0, UsageForegroundState(), selected)
    assertTrue("a tracker never fed: everything up to now is history, nothing is open", blind.open.isEmpty() && blind.activeSinceMs == null && blind.historyToMs == t0)
    assertEquals("a stale cursor (before counting starts): no history, the replay covers it", null, ThresholdProgress.arm(t0, t0 + 60 * MINUTE, t0 + 10 * MINUTE, tracker, selected).historyFromMs)
    val filled = late.backfill(t0 - 30 * MINUTE, t0 - 5 * MINUTE, 7 * MINUTE)
    assertEquals(7 * MINUTE, filled.accumulatedMs)
    assertFalse(filled.historyPending)
    assertEquals("a second answer changes nothing", filled, filled.backfill(t0 - 30 * MINUTE, t0 - 5 * MINUTE, 7 * MINUTE))
    assertEquals("an answer to another request changes nothing", late, late.backfill(t0 - 10 * MINUTE, t0, 7 * MINUTE))
    assertEquals("a clock shift moves the stream instants, not the bounds", late.copy(activeSinceMs = t0 - 6 * MINUTE, historyFromMs = t0 - 31 * MINUTE, historyToMs = t0 - 6 * MINUTE), late.shifted(-MINUTE))
  }

  @Test
  fun `foregroundMs measures a history window`() {
    val events = listOf(resumed("com.a", t0 + MINUTE), paused("com.a", t0 + 4 * MINUTE), resumed("com.b", t0 + 10 * MINUTE), resumed("com.other", t0 + 50 * MINUTE))
    assertEquals("3 min closed + the span open at the window end", 8 * MINUTE, ThresholdProgress.foregroundMs(events, selected, t0, t0 + 15 * MINUTE))
    assertEquals("events after the window are ignored", 0, ThresholdProgress.foregroundMs(events, selected, t0 + 5 * MINUTE, t0 + 9 * MINUTE))
    assertEquals("an app open since before the window counts from the window start", 4 * MINUTE, ThresholdProgress.foregroundMs(listOf(resumed("com.a", t0 - 70 * MINUTE)), selected, t0, t0 + 4 * MINUTE))
    val swap = listOf(UsageEvent(t0 + MINUTE, "com.a", UsageEventKind.ACTIVITY_PAUSED, "S1"), UsageEvent(t0 + MINUTE, "com.a", UsageEventKind.ACTIVITY_RESUMED, "S2"), UsageEvent(t0 + MINUTE + 50, "com.a", UsageEventKind.ACTIVITY_STOPPED, "S1"))
    assertEquals("an activity swap inside the app keeps it in the foreground", 4 * MINUTE, ThresholdProgress.foregroundMs(listOf(UsageEvent(t0, "com.a", UsageEventKind.ACTIVITY_RESUMED, "S1")) + swap, selected, t0, t0 + 4 * MINUTE))
    assertEquals(0, ThresholdProgress.foregroundMs(emptyList(), selected, t0, t0 + MINUTE))
  }

  @Test
  fun `the tracker keeps the resumed set per activity and ignores a replayed window`() {
    var tracker = UsageForegroundState()
    fun UsageForegroundState.fed(event: UsageEvent) = apply(event).first
    tracker = tracker.fed(resumed("com.a", t0)).fed(resumed("com.b", t0 + 1)).fed(paused("com.a", t0 + 2))
    assertEquals(setOf("com.b"), tracker.resumedPackages)
    assertEquals(t0 + 2, tracker.cursorMs)
    assertFalse("an event at the cursor is a replay", tracker.accepts(paused("com.a", t0 + 2)))
    assertFalse(tracker.accepts(resumed("com.a", t0)))
    assertTrue(tracker.accepts(resumed("com.a", t0 + 3)))
    assertEquals("the stopped kind closes too", emptySet<String>(), tracker.fed(UsageEvent(t0 + 3, "com.b", UsageEventKind.ACTIVITY_STOPPED)).resumedPackages)
    assertEquals(emptySet<String>(), tracker.fed(device(UsageEventKind.SCREEN_NON_INTERACTIVE, t0 + 3)).resumedPackages)
    assertEquals(t0 + 60, tracker.advanceTo(t0 + 60).cursorMs)
    assertEquals("advancing never goes back", t0 + 60, tracker.advanceTo(t0 + 60).advanceTo(t0).cursorMs)
    assertEquals("a clock shift moves the cursor", t0 - 58, tracker.advanceTo(t0 + 60).shifted(-118).cursorMs)
    // Screens of one app: paused(S1), resumed(S2), stopped(S1) — the late stop must not evict the package.
    val swap = UsageForegroundState().fed(UsageEvent(t0, "com.insta", UsageEventKind.ACTIVITY_RESUMED, "S1"))
      .fed(UsageEvent(t0 + 10, "com.insta", UsageEventKind.ACTIVITY_PAUSED, "S1"))
      .fed(UsageEvent(t0 + 11, "com.insta", UsageEventKind.ACTIVITY_RESUMED, "S2"))
    val (afterStop, change) = swap.apply(UsageEvent(t0 + 40, "com.insta", UsageEventKind.ACTIVITY_STOPPED, "S1"))
    assertEquals(setOf("com.insta"), afterStop.resumedPackages)
    assertNull("no package-level change", change)
    assertEquals("package-level changes are what accumulators see", UsageEventKind.ACTIVITY_PAUSED, afterStop.apply(UsageEvent(t0 + 50, "com.insta", UsageEventKind.ACTIVITY_PAUSED, "S2")).second?.kind)
    assertNull("a second resumed activity of the same package is no change", afterStop.apply(UsageEvent(t0 + 50, "com.insta", UsageEventKind.ACTIVITY_RESUMED, "S3")).second)
    assertEquals(UsageEventKind.ACTIVITY_RESUMED, UsageEventKind.of(1))
    assertEquals(UsageEventKind.SCREEN_NON_INTERACTIVE, UsageEventKind.of(16))
    assertEquals(UsageEventKind.OTHER, UsageEventKind.of(99))
  }

  @Test
  fun `the codecs round-trip`() {
    val progress = fresh().feedAll(resumed("com.a", t0), resumed("com.b", t0 + 1)).settle(t0 + 2).copy(fired = true, warned = true, historyFromMs = t0 - 9, historyToMs = t0)
    assertEquals(progress, ThresholdProgress.fromJson(JSONObject(progress.toJson().toString())))
    val tracker = UsageForegroundState(mapOf("com.b" to setOf("B1", "B2"), "com.a" to setOf("")), t0)
    assertEquals(tracker, UsageForegroundState.fromJson(JSONObject(tracker.toJson().toString())))
    assertEquals(UsageForegroundState(), UsageForegroundState.fromJson(null))
    assertNull(ThresholdProgress.fromJson(JSONObject("{}")))
  }
}
