package expo.modules.screentime

import expo.modules.screentime.MonitoringTestClock.HOUR
import expo.modules.screentime.MonitoringTestClock.MINUTE
import expo.modules.screentime.MonitoringTestClock.date
import expo.modules.screentime.MonitoringTestClock.london
import expo.modules.screentime.MonitoringTestClock.paris
import expo.modules.screentime.MonitoringTestClock.utc
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The monitoring state as the engine and the module drive it: review R7
 * (a start inside the interval applies at once), the C06 shape (one start,
 * one end, unique ids), exactly-once across a process death and across
 * two processes, midnight wrap, weekdays, `repeats: false`, DST and
 * time-zone changes, a clock set backwards, and the thresholds with their
 * `blockOnThreshold` output. The intervalPlan section of the Swift tests is
 * covered by the re-registration cases.
 */
class MonitoringStateTest {
  private val apps = Selection(applications = setOf("com.a", "com.b"))
  private val social = Selection(applications = setOf("com.insta"))
  private val selections: (String) -> Selection? = { id -> mapOf("APPS" to apps, "SOCIAL" to social)[id] }

  private fun components(hour: Int, minute: Int = 0, weekday: Int? = null): Map<String, Any> =
    LinkedHashMap<String, Any>().apply { put("hour", hour.toDouble()); put("minute", minute.toDouble()); if (weekday != null) put("weekday", weekday.toDouble()) }

  private fun config(
    name: String = "work",
    start: Map<String, Any> = components(9),
    end: Map<String, Any> = components(17),
    selectionId: String? = "APPS",
    repeats: Boolean = true,
    warningTime: Int? = null,
    weekdays: List<Int>? = null,
    events: List<Map<String, Any?>>? = null,
    mode: String? = null,
  ): MonitoringConfig {
    val schedule = LinkedHashMap<String, Any?>().apply {
      put("intervalStart", start); put("intervalEnd", end); put("repeats", repeats)
      if (warningTime != null) put("warningTime", warningTime.toDouble())
    }
    val raw = LinkedHashMap<String, Any?>().apply {
      put("activityName", name); put("schedule", schedule)
      if (selectionId != null) put("selectionId", selectionId)
      if (weekdays != null) put("weekdays", weekdays.map { it.toDouble() })
      if (events != null) put("events", events)
      if (mode != null) put("mode", mode)
    }
    return MonitoringValidation.validate(raw, selections, emptySet())
  }

  private fun threshold(name: String = "limit", selectionId: String = "SOCIAL", seconds: Int, past: Boolean = false, block: Boolean = true) =
    mapOf("eventName" to name, "selectionId" to selectionId, "threshold" to seconds.toDouble(), "includesPastActivity" to past, "blockOnThreshold" to block)

  private fun resumed(pkg: String, at: Long) = UsageEvent(at, pkg, UsageEventKind.ACTIVITY_RESUMED, "Main")
  private fun paused(pkg: String, at: Long) = UsageEvent(at, pkg, UsageEventKind.ACTIVITY_PAUSED, "Main")
  private fun screenOff(at: Long) = UsageEvent(at, null, UsageEventKind.SCREEN_NON_INTERACTIVE)

  private fun MonitoringState.advance(now: Long, zone: TimeZone = utc, usage: List<UsageEvent> = emptyList()) = advance(now, zone, usage, now, selections)
  private fun MonitoringState.start(config: MonitoringConfig, now: Long, zone: TimeZone = utc) = start(config, now, zone, selections)
  private fun MonitoringState.Result.types() = recorded.map { it.eventType + (it.eventName?.let { n -> ":$n" } ?: "") }
  private fun MonitoringState.persisted(): MonitoringState = MonitoringState.fromJson(JSONObject(toJson().toString()))
  private fun MonitoringState.count(name: String, type: String) = events(name, 1000).count { it.eventType == type }
  /** The blocks without their interval start (asserted where it matters). */
  private fun MonitoringState.blocks(exists: (String) -> Boolean = { true }) = activeBlocks(exists).map { it.copy(startedAtMs = 0) }

  // Wednesday 2026-10-28.
  private val d = 28
  private fun at(h: Int, m: Int = 0, s: Int = 0, day: Int = d) = date(2026, 10, day, h, m, s)

  @Test
  fun `R7 - a start inside the interval applies at once and records one intervalDidStart`() {
    val result = MonitoringState().start(config(), at(10))
    assertEquals(listOf("intervalDidStart"), result.types())
    assertEquals(at(10), result.recorded.single().timestampMs)
    assertEquals(listOf(MonitoringBlock("work", "APPS", "blocklist")), result.state.blocks())
    assertEquals(listOf("work"), result.state.monitoredActivities())
    assertEquals("the marker is the occurrence, started now", ActiveInterval(at(10), Occurrence(at(9), at(17))), result.state.intervals.getValue("work"))
    // The engine's pass a second later — in the same process or another one reading the persisted document — records nothing.
    assertTrue(result.state.advance(at(10, 0, 1)).recorded.isEmpty())
    assertTrue(result.state.persisted().advance(at(10, 0, 1)).recorded.isEmpty())
    assertEquals(1, result.state.revision)
    assertTrue(result.durable)
  }

  @Test
  fun `C06 - one start, one end, unique ids, block on then off`() {
    var state = MonitoringState().start(config(), at(8)).let { assertTrue("outside: no event, no block", it.recorded.isEmpty() && it.state.activeBlocks().isEmpty()); it.state }
    assertEquals("the next wake is the start", at(9), state.nextWakeMs(at(8), utc))
    state = state.advance(at(9)).let { assertEquals(listOf("intervalDidStart"), it.types()); it.state }
    assertEquals(1, state.activeBlocks().size)
    assertEquals(at(17), state.nextWakeMs(at(10), utc))
    state = state.advance(at(12)).let { assertTrue(it.recorded.isEmpty()); it.state }
    state = state.advance(at(17)).let { assertEquals(listOf("intervalDidEnd"), it.types()); it.state }
    assertTrue(state.activeBlocks().isEmpty())
    assertTrue(state.intervals.isEmpty())
    state = state.advance(at(18)).let { assertTrue(it.recorded.isEmpty()); it.state }
    val events = state.events("work")
    assertEquals(listOf("intervalDidStart", "intervalDidEnd"), events.map { it.eventType })
    assertEquals("event ids are unique", events.size, events.map { it.id }.toSet().size)
    assertEquals(listOf(at(9), at(17)), events.map { it.timestampMs })
    assertEquals("still listed, repeating tomorrow", at(9, day = 29), state.nextWakeMs(at(18), utc))
  }

  @Test
  fun `stopMonitoring records the end once, stopAllMonitoring stops in name order, unknown names are no-ops`() {
    val started = MonitoringState().start(config(), at(10)).state.start(config("bed", components(22), components(6)), at(10)).state
    assertEquals(listOf("bed", "work"), started.monitoredActivities())
    val stopped = started.stop("work", at(11))
    assertEquals(listOf("intervalDidEnd"), stopped.types())
    assertEquals(listOf("bed"), stopped.state.monitoredActivities())
    assertTrue("the engine finds nothing left to end", stopped.state.advance(at(11, 1)).recorded.isEmpty())
    assertEquals(1, stopped.state.count("work", "intervalDidEnd"))
    assertTrue("stopping outside an interval records nothing", stopped.state.stop("bed", at(11)).recorded.isEmpty())
    assertTrue(stopped.state.stop("nope", at(11)).recorded.isEmpty())
    assertEquals(stopped.state, stopped.state.stop("nope", at(11)).state)

    val all = started.stopAll(at(12))
    assertEquals("only the interval in progress ends", listOf("work"), all.recorded.map { it.activityName })
    assertTrue(all.state.activities.isEmpty() && all.state.intervals.isEmpty() && all.state.activeBlocks().isEmpty())
    assertNull(all.state.nextWakeMs(at(12), utc))
    assertEquals("the history stays", 2, all.state.events(null).count { it.activityName == "work" })
  }

  @Test
  fun `re-registering in place keeps the occurrence, re-arms the thresholds and records no event pair (intervalPlan)`() {
    val armed = config(events = listOf(threshold(seconds = 600)))
    val first = MonitoringState().start(armed, at(10)).state
    val fired = first.advance(at(10), usage = listOf(resumed("com.insta", at(10)))).state.advance(at(10, 10))
    assertEquals(listOf("eventDidReachThreshold:limit"), fired.types())
    assertTrue(fired.state.intervals.getValue("work").thresholds.getValue("limit").fired)

    val again = fired.state.start(armed.copy(selectionId = "SOCIAL"), at(10, 15))
    assertTrue("no intervalDidEnd/intervalDidStart pair", again.recorded.isEmpty())
    assertEquals("the marker keeps its start", at(10), again.state.intervals.getValue("work").startedAtMs)
    assertFalse("the threshold is re-armed", again.state.intervals.getValue("work").thresholds.getValue("limit").fired)
    assertEquals("the new selection applies", listOf(MonitoringBlock("work", "SOCIAL", "blocklist")), again.state.blocks())
    assertEquals(2, again.state.revision)
    // Re-armed with includesPastActivity false: counting restarts from the re-registration; insta is still resumed.
    val refired = again.state.advance(at(10, 20)).let { assertTrue(it.recorded.isEmpty()); it.state }.advance(at(10, 25))
    assertEquals("…and can fire again in the same occurrence, as a fresh iOS registration does", listOf("eventDidReachThreshold:limit"), refired.types())
  }

  @Test
  fun `re-registering over a stale marker ends it and starts the new occurrence, and outside it only ends (intervalPlan)`() {
    // A marker from yesterday whose end was missed (the engine never ran): persisted as the document would hold it.
    val stale = MonitoringState().start(config(), at(10, day = 27)).state
    val replaced = stale.start(config(), at(10))
    assertEquals(listOf("intervalDidEnd", "intervalDidStart"), replaced.types())
    assertEquals(at(10), replaced.state.intervals.getValue("work").startedAtMs)
    val outside = stale.start(config(), at(18))
    assertEquals(listOf("intervalDidEnd"), outside.types())
    assertTrue(outside.state.intervals.isEmpty())
    assertEquals(listOf("work"), outside.state.monitoredActivities())
    assertTrue("inside with no previous interval: start now", MonitoringState().start(config(), at(10)).types() == listOf("intervalDidStart"))

    // A different schedule whose occurrence began after the marker: not the same occurrence (iOS: markedAt ≥ occurrenceStart), end + start.
    val afternoon = MonitoringState().start(config(), at(10)).state.start(config(start = components(12), end = components(20)), at(13))
    assertEquals(listOf("intervalDidEnd", "intervalDidStart"), afternoon.types())
    assertEquals(at(13), afternoon.state.intervals.getValue("work").startedAtMs)
    // A schedule whose occurrence began before the marker keeps it (the marker is inside the new occurrence).
    val widened = MonitoringState().start(config(), at(10)).state.start(config(start = components(8), end = components(18)), at(13))
    assertTrue(widened.recorded.isEmpty())
    assertEquals(Occurrence(at(8), at(18)), widened.state.intervals.getValue("work").occurrence)
    // Stop A then restart A inside the occurrence: an end, then a start — the restart is a new interval.
    val restarted = MonitoringState().start(config(), at(10)).state.stop("work", at(11)).state.start(config(), at(11, 0, 1))
    assertEquals(listOf("intervalDidStart"), restarted.types())
    assertEquals(listOf("intervalDidStart", "intervalDidEnd", "intervalDidStart"), restarted.state.events("work").map { it.eventType })
  }

  @Test
  fun `a process death between the transition and its record loses nothing and duplicates nothing`() {
    val document = JSONObject()
    MonitoringDocument.write(document, MonitoringState().start(config(), at(8)).state)
    val persisted = document.toString()

    // The engine computes the start at 09:00, then dies before `update` persisted the document.
    val lost = MonitoringDocument.read(JSONObject(persisted)).advance(at(9))
    assertEquals(listOf("intervalDidStart"), lost.types())
    // Restart: the document still has no marker; the transition is made again, once.
    val retried = MonitoringDocument.read(JSONObject(persisted)).advance(at(9, 0, 5))
    assertEquals(listOf("intervalDidStart"), retried.types())
    assertEquals("1", retried.recorded.single().id)
    MonitoringDocument.write(document, retried.state)
    // Persisted: a further pass records nothing, in this process or the next.
    assertTrue(MonitoringDocument.read(JSONObject(document.toString())).advance(at(9, 1)).recorded.isEmpty())
    assertEquals(1, MonitoringDocument.read(document).count("work", "intervalDidStart"))
    assertEquals("the document's other members are untouched", setOf("monitoring"), document.keys().asSequence().toSet())
  }

  @Test
  fun `two processes - the module's synchronous start and the engine's pass record one event`() {
    val document = JSONObject().put("engine", JSONObject().put("desired", true))
    // The module, under the store's exclusive lock: start inside the occurrence.
    MonitoringDocument.write(document, MonitoringDocument.read(document).start(config(), at(10)).state)
    // The engine, taking the lock next with the document the module wrote.
    val pass = MonitoringDocument.read(document).advance(at(10, 0, 0))
    assertTrue(pass.recorded.isEmpty())
    assertEquals(listOf(MonitoringBlock("work", "APPS", "blocklist")), pass.state.blocks())
    assertTrue(document.getJSONObject("engine").getBoolean("desired"))
  }

  @Test
  fun `midnight wrap and weekdays - one logical activity per name`() {
    val bed = config("bed", components(22), components(6))
    var state = MonitoringState().start(bed, at(23, 30)).let { assertEquals(listOf("intervalDidStart"), it.types()); it.state }
    state = state.advance(at(1, day = 29)).let { assertTrue("01:00 the next day is the same occurrence", it.recorded.isEmpty()); it.state }
    assertEquals(at(6, day = 29), state.nextWakeMs(at(1, day = 29), utc))
    state = state.advance(at(6, day = 29)).let { assertEquals(listOf("intervalDidEnd"), it.types()); it.state }

    // Monday nights only: 2026-10-26 is a Monday.
    val monday = config("mon", components(22), components(6), weekdays = listOf(2))
    val tuesdayEarly = MonitoringState().start(monday, date(2026, 10, 27, 1, 0))
    assertEquals("Tuesday 01:00 belongs to Monday's occurrence ⇒ applies", listOf("intervalDidStart"), tuesdayEarly.types())
    val wednesdayEarly = MonitoringState().start(monday, date(2026, 10, 28, 1, 0))
    assertTrue("Wednesday 01:00 belongs to Tuesday's ⇒ nothing", wednesdayEarly.recorded.isEmpty() && wednesdayEarly.state.activeBlocks().isEmpty())
    assertEquals("the next wake is next Monday 22:00", date(2026, 11, 2, 22, 0), wednesdayEarly.state.nextWakeMs(date(2026, 10, 28, 1, 0), utc))
    assertEquals(listOf("mon"), wednesdayEarly.state.monitoredActivities())
  }

  @Test
  fun `repeats false - the one occurrence, then inert but listed`() {
    // C06: registered at the current minute, ending 20 minutes later.
    val once = config(start = components(10, 0), end = components(10, 20), repeats = false)
    var state = MonitoringState().start(once, at(10, 0, 30)).let { assertEquals(listOf("intervalDidStart"), it.types()); it.state }
    assertEquals(at(10, 20), state.nextWakeMs(at(10, 1), utc))
    state = state.advance(at(10, 20)).let { assertEquals(listOf("intervalDidEnd"), it.types()); it.state }
    assertEquals(listOf("work"), state.monitoredActivities())
    assertNull("nothing left to wake for", state.nextWakeMs(at(10, 21), utc))
    state = state.advance(at(10, 0, 30, day = 29)).let { assertTrue("the next day's occurrence is not this activity's", it.recorded.isEmpty()); it.state }
    assertTrue(state.activeBlocks().isEmpty())
    assertEquals(1, state.count("work", "intervalDidStart"))

    // Registered before its start: the next selected occurrence only (Mon–Fri, registered Saturday 2026-10-31 ⇒ Monday 11-02).
    val next = MonitoringState().start(config(repeats = false, weekdays = listOf(2, 3, 4, 5, 6)), date(2026, 10, 31, 12, 0)).state
    assertEquals(date(2026, 11, 2, 9, 0), next.activities.getValue("work").onceStartMs)
    assertEquals(date(2026, 11, 2, 9, 0), next.nextWakeMs(date(2026, 10, 31, 12, 0), utc))
    val ran = next.advance(date(2026, 11, 2, 9, 0)).let { assertEquals(listOf("intervalDidStart"), it.types()); it.state }
      .advance(date(2026, 11, 2, 17, 0)).let { assertEquals(listOf("intervalDidEnd"), it.types()); it.state }
    assertTrue(ran.advance(date(2026, 11, 3, 9, 0)).recorded.isEmpty())
  }

  @Test
  fun `DST - a 22-06 night in Europe Paris ends at 06 00 wall clock, 9 hours later`() {
    val night = config("bed", components(22), components(6))
    val start = date(2026, 10, 24, 22, 0, 0, paris)
    var state = MonitoringState().start(night, start, paris).let { assertEquals(listOf("intervalDidStart"), it.types()); it.state }
    assertEquals(start + 9 * HOUR, state.nextWakeMs(start, paris))
    state = state.advance(start + 8 * HOUR + 30 * MINUTE, paris).let { assertTrue("05:30 CET is still inside", it.recorded.isEmpty()); it.state }
    state = state.advance(date(2026, 10, 25, 6, 0, 0, paris), paris).let { assertEquals(listOf("intervalDidEnd"), it.types()); it.state }
    assertEquals(9 * HOUR, state.events("bed").let { it[1].timestampMs - it[0].timestampMs })

    val spring = date(2026, 3, 28, 22, 0, 0, paris)
    val short = MonitoringState().start(night, spring, paris).state
    assertEquals("7 real hours that night", spring + 7 * HOUR, short.nextWakeMs(spring, paris))
    assertEquals(listOf("intervalDidEnd"), short.advance(spring + 7 * HOUR, paris).types())
  }

  @Test
  fun `a time-zone change mid-interval keeps the interval in progress and moves its end`() {
    // 10:00 in Paris (CET, UTC+1): inside 9–17.
    val state = MonitoringState().start(config(), date(2026, 10, 28, 10, 0, 0, paris), paris).state
    // At 15:00 Paris the device moves to London (14:00 GMT): still inside 9–17 London, the same interval.
    val moved = state.advance(date(2026, 10, 28, 15, 0, 0, paris), london)
    assertTrue(moved.recorded.isEmpty())
    val interval = moved.state.intervals.getValue("work")
    assertEquals("the marker keeps its start", date(2026, 10, 28, 10, 0, 0, paris), interval.startedAtMs)
    assertEquals("the end is now 17:00 London", date(2026, 10, 28, 17, 0, 0, london), interval.occurrence.endMs)
    assertEquals(date(2026, 10, 28, 17, 0, 0, london), moved.state.nextWakeMs(date(2026, 10, 28, 15, 0, 0, paris), london))
    // 17:30 Paris = 16:30 London: still blocked.
    val later = moved.state.advance(date(2026, 10, 28, 17, 30, 0, paris), london)
    assertTrue(later.recorded.isEmpty() && later.state.activeBlocks().size == 1)
    val ended = later.state.advance(date(2026, 10, 28, 17, 0, 0, london), london)
    assertEquals(listOf("intervalDidEnd"), ended.types())
    assertEquals("one start, one end", listOf("intervalDidStart", "intervalDidEnd"), ended.state.events("work").map { it.eventType })

    // The other way: Paris → Athens (UTC+2) at 16:30 Paris = 17:30 Athens: the Athens occurrence ended at 17:00 Athens = 16:00 Paris.
    val athens = TimeZone.getTimeZone("Europe/Athens")
    val east = state.advance(date(2026, 10, 28, 16, 30, 0, paris), athens)
    assertEquals("outside in the new zone ⇒ the interval ends", listOf("intervalDidEnd"), east.types())
  }

  @Test
  fun `a zone change cannot merge two consecutive 24 h occurrences`() {
    // Daily 00:00–23:59 with a 1 h blockOnThreshold that fired on Monday 2026-10-26 (UTC).
    val allDay = config(start = components(0), end = components(23, 59), events = listOf(threshold(seconds = 3600)))
    val monday = date(2026, 10, 26, 0, 0)
    var state = MonitoringState().start(allDay, monday).state
    state = state.advance(monday + HOUR, usage = listOf(resumed("com.insta", monday))).let { assertEquals(listOf("eventDidReachThreshold:limit"), it.types()); it.state }
    // At 23:30Z the device moves to UTC+1: it is Tuesday 00:30 there, a new occurrence that overlaps Monday's in real time.
    val plusOne = TimeZone.getTimeZone("Etc/GMT-1")
    val moved = state.advance(monday + 23 * HOUR + 30 * MINUTE, plusOne)
    assertEquals(listOf("intervalDidEnd", "intervalDidStart"), moved.types())
    assertFalse("Tuesday's threshold is armed afresh", moved.state.intervals.getValue("work").thresholds.getValue("limit").fired)
    assertEquals(listOf(MonitoringBlock("work", "APPS", "blocklist")), moved.state.blocks())
  }

  @Test
  fun `a threshold reached inside an occurrence but first seen after its end still fires, before the end`() {
    val armed = config(events = listOf(threshold(seconds = 600)))
    val state = MonitoringState().start(armed, at(16, 50)).state
    val late = state.advance(at(17, 5), usage = listOf(resumed("com.insta", at(16, 50))))
    assertEquals(listOf("eventDidReachThreshold:limit", "intervalDidEnd"), late.types())
    assertTrue("the threshold block went with the interval", late.state.activeBlocks().isEmpty())
    val short = MonitoringState().start(armed, at(16, 55)).state.advance(at(17, 5), usage = listOf(resumed("com.insta", at(16, 55))))
    assertEquals("5 minutes inside, the rest after the end: no fire", listOf("intervalDidEnd"), short.types())
  }

  @Test
  fun `history and the replayed stream never overlap, and includesPastActivity false counts from the registration`() {
    // Another activity keeps the engine reading the stream, so the stored cursor is 09:50; insta started at 09:55; the module starts a 9–17 schedule at 10:00 with includesPastActivity.
    val armed = config(events = listOf(threshold(seconds = 600, past = true)))
    val fed = MonitoringState().start(config("other", components(20), components(21)), at(9)).state.advance(at(9, 50), usage = listOf(paused("com.insta", at(9, 40)))).state
    val started = fed.start(armed, at(10))
    assertEquals("history up to the cursor only", listOf(HistoryRequest("work", "limit", "SOCIAL", at(9), at(9, 50))), started.historyRequests)
    val filled = started.state.backfill(started.historyRequests.single(), listOf(resumed("com.insta", at(9, 10)), paused("com.insta", at(9, 40))), at(10), selections)
    assertTrue(filled.durable)
    assertEquals("30 min of history fire at the backfill", listOf("eventDidReachThreshold:limit"), filled.types())
    val replayed = filled.state.advance(at(10, 0, 1), usage = listOf(resumed("com.insta", at(9, 55))))
    assertEquals("30 min of history + 5 min 1 s of stream, once", 35 * MINUTE + 1000, replayed.state.intervals.getValue("work").thresholds.getValue("limit").projectedMs(at(10, 0, 1)))
    assertTrue("once", replayed.recorded.isEmpty())

    // includesPastActivity false, registered at 08:00, first pass at 09:30: usage since 09:00 is requested (iOS excludes only pre-registration usage).
    val live = config(events = listOf(threshold(seconds = 600)))
    val idle = MonitoringState().start(live, at(8)).state.advance(at(9, 30))
    assertEquals(listOf(HistoryRequest("work", "limit", "SOCIAL", at(9), at(9, 30))), idle.historyRequests)
    assertEquals("…and the pending request survives a reload", idle.historyRequests, idle.state.persisted().pendingHistoryRequests())
    val answered = idle.state.backfill(idle.historyRequests.single(), listOf(resumed("com.insta", at(9)), paused("com.insta", at(9, 29))), at(9, 31), selections)
    assertEquals(listOf("eventDidReachThreshold:limit"), answered.types())
    assertTrue(answered.state.pendingHistoryRequests().isEmpty())
    assertFalse("an answer to a request that is not pending changes nothing", answered.state.backfill(idle.historyRequests.single(), emptyList(), at(9, 32), selections).durable)
    assertTrue("a registration counting from now requests nothing", MonitoringState().start(live, at(10)).historyRequests.isEmpty())
  }

  @Test
  fun `a clock set backwards keeps usage tracking alive`() {
    val armed = config(events = listOf(threshold(seconds = 600)))
    var state = MonitoringState().start(armed, at(10)).state.advance(at(10, 5), usage = listOf(resumed("com.insta", at(10)))).state
    // The clock is set back 30 minutes at 10:05: the stream now reads on the new clock; so does the state.
    val shifted = state.clockShifted(-30 * MINUTE)
    assertEquals(at(9, 35), shifted.foreground.cursorMs)
    assertEquals(at(9, 35), shifted.intervals.getValue("work").thresholds.getValue("limit").activeSinceMs)
    assertEquals("a counter from the registration follows it", at(9, 30), shifted.intervals.getValue("work").thresholds.getValue("limit").countFromMs)
    assertEquals(at(9, 30), shifted.activities.getValue("work").registeredAtMs)
    assertEquals("the occurrence bounds stay the schedule's", Occurrence(at(9), at(17)), shifted.intervals.getValue("work").occurrence)
    state = shifted.advance(at(9, 39)).let { assertTrue(it.recorded.isEmpty()); it.state }
    assertEquals("5 min before the step + 4 after", 9 * MINUTE, state.intervals.getValue("work").thresholds.getValue("limit").accumulatedMs)
    assertEquals("the 10th minute fires", listOf("eventDidReachThreshold:limit"), state.advance(at(9, 40)).types())
    // Unannounced: `advance` sees the clock behind the cursor and shifts by the difference.
    val unannounced = MonitoringState().start(armed, at(10)).state.advance(at(10, 5), usage = listOf(resumed("com.insta", at(10)))).state.advance(at(9, 40))
    assertTrue(unannounced.durable)
    assertEquals(at(9, 40), unannounced.state.foreground.cursorMs)
    assertTrue("new-clock events are accepted again", unannounced.state.advance(at(9, 41), usage = listOf(paused("com.insta", at(9, 40, 30)))).state.foreground.resumedPackages.isEmpty())
  }

  @Test
  fun `blocks of a selection that no longer exists are not emitted, and all-events reads sort by timestamp`() {
    val armed = config(events = listOf(threshold(seconds = 0)))
    val state = MonitoringState().start(armed, at(10)).state
    assertEquals(2, state.activeBlocks().size)
    assertEquals(listOf(MonitoringBlock("work", "APPS", "blocklist")), state.blocks { it == "APPS" })
    assertTrue(state.activeBlocks { false }.isEmpty())
    val back = state.start(config("bed", components(22), components(6)), at(23, day = 27)).state
    assertEquals("newest timestamp first, as iOS sorts", listOf("work", "work", "bed"), back.events(null).map { it.activityName })
  }

  @Test
  fun `a clock set backwards - the schedule is a function of time`() {
    val state = MonitoringState().start(config(), at(10)).state
    val small = state.advance(at(9, 30))
    assertTrue("30 min back, still inside the same occurrence: nothing", small.recorded.isEmpty())
    val big = state.advance(at(8))
    assertEquals("2 h back, before the occurrence: it ends", listOf("intervalDidEnd"), big.types())
    assertTrue(big.state.activeBlocks().isEmpty())
    val again = big.state.advance(at(9))
    assertEquals("…and starts again when the clock reaches 09:00", listOf("intervalDidStart"), again.types())
    assertEquals(listOf("intervalDidStart", "intervalDidEnd", "intervalDidStart"), again.state.events("work").map { it.eventType })
    val yesterday = state.advance(at(10, day = 27))
    assertEquals("a day back: yesterday's occurrence is not this marker's", listOf("intervalDidEnd", "intervalDidStart"), yesterday.types())
  }

  @Test
  fun `occurrences that passed unobserved record nothing - one end, one start at the next wake`() {
    val state = MonitoringState().start(config(), at(10, day = 26)).state
    val thursday = state.advance(at(10, day = 29))
    assertEquals(listOf("intervalDidEnd", "intervalDidStart"), thursday.types())
    assertEquals(listOf(at(10, day = 29), at(10, day = 29)), thursday.recorded.map { it.timestampMs })
    assertEquals(2, thursday.state.count("work", "intervalDidStart"))
  }

  @Test
  fun `thresholds fire once per occurrence, with screen-off gaps and split-screen counted by the union rule`() {
    val armed = config(events = listOf(threshold(seconds = 600), threshold("pair", "APPS", 300, block = false)))
    var state = MonitoringState().start(armed, at(10)).state
    assertEquals(listOf(MonitoringBlock("work", "APPS", "blocklist")), state.blocks())
    // insta from 10:00, screen off at 10:03, back at 10:20.
    state = state.advance(at(10, 5), usage = listOf(resumed("com.insta", at(10)), screenOff(at(10, 3)))).let { assertTrue(it.recorded.isEmpty()); it.state }
    state = state.advance(at(10, 10)).let { assertTrue("3 minutes only: nothing", it.recorded.isEmpty()); it.state }
    state = state.advance(at(10, 25), usage = listOf(resumed("com.insta", at(10, 20)))).let { assertTrue(it.recorded.isEmpty()); it.state }
    assertEquals("reaches 10 min at 10:27", at(10, 27), state.nextWakeMs(at(10, 25), utc))
    state = state.advance(at(10, 27)).let { assertEquals(listOf("eventDidReachThreshold:limit"), it.types()); it.state }
    assertEquals(
      "blockOnThreshold adds the event's selection, blocklist semantics",
      listOf(MonitoringBlock("work", "APPS", "blocklist"), MonitoringBlock("work", "SOCIAL", "blocklist", "limit")),
      state.blocks(),
    )
    state = state.advance(at(10, 40)).let { assertTrue("once per occurrence", it.recorded.isEmpty()); it.state }
    assertEquals(1, state.count("work", "eventDidReachThreshold"))

    // Split screen: com.a and com.b together for 5 minutes count 5, not 10 — the pair threshold (300 s) fires at 10:45:30, not 10:43:00.
    state = state.advance(at(10, 42), usage = listOf(resumed("com.a", at(10, 40, 30)), resumed("com.b", at(10, 40, 30)))).let { assertTrue(it.recorded.isEmpty()); it.state }
    state = state.advance(at(10, 45, 29)).let { assertTrue(it.recorded.isEmpty()); it.state }
    state = state.advance(at(10, 45, 30)).let { assertEquals(listOf("eventDidReachThreshold:pair"), it.types()); it.state }
    assertEquals("no block for an event without blockOnThreshold", 2, state.activeBlocks().size)

    // The interval ends (the screen went dark at 16:59): the threshold block goes with it; the next occurrence re-arms.
    state = state.advance(at(17), usage = listOf(screenOff(at(16, 59)))).let { assertEquals(listOf("intervalDidEnd"), it.types()); it.state }
    assertTrue(state.foreground.resumedPackages.isEmpty())
    assertTrue(state.activeBlocks().isEmpty())
    state = state.advance(at(9, day = 29), usage = listOf(resumed("com.insta", at(8, day = 29)))).let { assertEquals(listOf("intervalDidStart"), it.types()); it.state }
    assertEquals("insta, resumed before the occurrence, counts from its start", at(9, 10, day = 29), state.nextWakeMs(at(9, day = 29), utc))
    assertEquals(listOf("eventDidReachThreshold:limit"), state.advance(at(9, 10, day = 29)).types())
  }

  @Test
  fun `thresholds survive a restart through the persisted totals and the replayed stream`() {
    val armed = config(events = listOf(threshold(seconds = 600)))
    val live = MonitoringState().start(armed, at(10)).state.advance(at(10, 5), usage = listOf(resumed("com.insta", at(10))))
    assertEquals(5 * MINUTE, live.state.intervals.getValue("work").thresholds.getValue("limit").accumulatedMs)
    // Persisted at 10:05, process dead until 10:10, insta on screen throughout: the stream has nothing new.
    val reloaded = live.state.persisted()
    assertEquals(at(10, 5), reloaded.foreground.cursorMs)
    assertEquals(listOf("eventDidReachThreshold:limit"), reloaded.advance(at(10, 10)).types())
    // Dead until 10:30 while the user paused insta at 10:07: the window re-queried from the cursor says so.
    val replayed = reloaded.advance(at(10, 30), usage = listOf(resumed("com.insta", at(10)), paused("com.insta", at(10, 7))))
    assertTrue("7 minutes, no fire; the replayed RESUMED at 10:00 is ignored", replayed.recorded.isEmpty())
    assertEquals(7 * MINUTE, replayed.state.intervals.getValue("work").thresholds.getValue("limit").accumulatedMs)
    assertEquals(at(17), replayed.state.nextWakeMs(at(10, 30), utc))
    // An event after `uptoMs` (the pipeline-lag zone) is left for the next window, then fed once.
    val lagged = replayed.state.advance(at(10, 31), utc, listOf(resumed("com.insta", at(10, 30, 59))), at(10, 30, 58), selections)
    assertEquals(at(10, 30, 58), lagged.state.foreground.cursorMs)
    assertTrue(lagged.state.foreground.resumedPackages.isEmpty())
    val next = lagged.state.advance(at(10, 32), utc, listOf(resumed("com.insta", at(10, 30, 59))), at(10, 31, 58), selections)
    assertEquals(setOf("com.insta"), next.state.foreground.resumedPackages)
    assertEquals(59_000L, next.state.intervals.getValue("work").thresholds.getValue("limit").projectedMs(at(10, 31, 58)) - 7 * MINUTE)
  }

  @Test
  fun `includesPastActivity - usage before the registration is a history request, answered once`() {
    val armed = config(events = listOf(threshold(seconds = 600, past = true)))
    val late = MonitoringState().start(armed, at(10))
    assertEquals(listOf(HistoryRequest("work", "limit", "SOCIAL", at(9), at(10))), late.historyRequests)
    assertTrue(late.durable)
    val history = listOf(resumed("com.insta", at(9)), paused("com.insta", at(9, 8)))
    val filled = late.state.backfill(late.historyRequests.single(), history, at(10, 0, 1), selections)
    assertTrue("8 minutes: not yet", filled.recorded.isEmpty())
    assertEquals(8 * MINUTE, filled.state.intervals.getValue("work").thresholds.getValue("limit").accumulatedMs)
    assertEquals("a second answer changes nothing", filled.state, filled.state.backfill(late.historyRequests.single(), history, at(10, 0, 2), selections).state)
    val fired = filled.state.advance(at(10, 1), usage = listOf(resumed("com.insta", at(10, 0, 30)))).state.advance(at(10, 2, 30))
    assertEquals("8 + 2 minutes", listOf("eventDidReachThreshold:limit"), fired.types())
    // Enough history on its own fires at the backfill.
    val heavy = late.state.backfill(late.historyRequests.single(), listOf(resumed("com.insta", at(9)), paused("com.insta", at(9, 30))), at(10, 0, 1), selections)
    assertEquals(listOf("eventDidReachThreshold:limit"), heavy.types())
    // The engine dead at the boundary: the interval starts at the first pass, the stretch since 09:00 is requested.
    val idle = MonitoringState().start(armed, at(8)).state.advance(at(9, 30))
    assertEquals(listOf(HistoryRequest("work", "limit", "SOCIAL", at(9), at(9, 30))), idle.historyRequests)
    assertTrue("no request when counting starts now", MonitoringState().start(config(events = listOf(threshold(seconds = 600))), at(10)).historyRequests.isEmpty())
  }

  @Test
  fun `warnings fire once - before the end and before a threshold`() {
    val warned = config(warningTime = 300, events = listOf(threshold(seconds = 600)))
    var state = MonitoringState().start(warned, at(10)).state
    assertEquals("the end warning is a wake", at(16, 55), state.nextWakeMs(at(16), utc))
    state = state.advance(at(16, 55), usage = listOf(resumed("com.insta", at(16, 54)))).let { assertEquals(listOf("intervalWillEndWarning"), it.types()); it.state }
    state = state.advance(at(16, 58)).let { assertTrue("4 minutes: no threshold warning yet", it.recorded.isEmpty()); it.state }
    assertEquals("the threshold warning (5 min) is the next wake", at(16, 59), state.nextWakeMs(at(16, 58), utc))
    state = state.advance(at(16, 59)).let { assertEquals(listOf("eventWillReachThresholdWarning:limit"), it.types()); it.state }
    state = state.advance(at(17)).let { assertEquals("the end, the warning not repeated", listOf("intervalDidEnd"), it.types()); it.state }
    assertEquals(listOf("intervalDidStart", "intervalWillEndWarning", "eventWillReachThresholdWarning", "intervalDidEnd"), state.events("work").map { it.eventType })
    assertEquals("a wake after the end skips the warning", listOf("intervalDidEnd"), MonitoringState().start(warned, at(10)).state.advance(at(18)).types())
    assertEquals("a registration inside the warning window warns at once", listOf("intervalDidStart", "intervalWillEndWarning"), MonitoringState().start(warned, at(16, 57)).types())
  }

  @Test
  fun `a weekly interval and an allowlist in the state`() {
    val weekend = config("weekend", components(22, weekday = 6), components(6, weekday = 1), selectionId = "APPS", mode = "allowlist")
    val state = MonitoringState().start(weekend, date(2026, 10, 31, 12, 0)).let { assertEquals(listOf("intervalDidStart"), it.types()); it.state }
    assertEquals(listOf(MonitoringBlock("weekend", "APPS", "allowlist")), state.blocks())
    assertEquals(date(2026, 11, 1, 6, 0), state.nextWakeMs(date(2026, 10, 31, 12, 0), utc))
    val ended = state.advance(date(2026, 11, 1, 6, 0)).let { assertEquals(listOf("intervalDidEnd"), it.types()); it.state }
    assertEquals(date(2026, 11, 6, 22, 0), ended.nextWakeMs(date(2026, 11, 1, 6, 0), utc))
  }

  @Test
  fun `getMonitorEvents and clearMonitorEvents through the state`() {
    val state = MonitoringState().start(config(), at(10)).state.start(config("bed", components(22), components(6)), at(23)).state
    assertEquals(listOf("work"), state.events("work").map { it.activityName })
    assertEquals("all, newest first", listOf("bed", "work"), state.events(null).map { it.activityName })
    val cleared = state.clearEvents("work")
    assertTrue(cleared.events("work").isEmpty() && cleared.events("bed").size == 1)
    assertEquals(3, cleared.revision)
    assertTrue(cleared.clearEvents(null).events(null).isEmpty())
    assertEquals("ids keep growing after a clear", "3", cleared.clearEvents(null).stop("bed", at(23, 30)).recorded.single().id)
  }

  @Test
  fun `the codec round-trips the whole state and drops what it cannot read`() {
    val armed = config(warningTime = 60, events = listOf(threshold(seconds = 600, past = true)))
    val state = MonitoringState().start(armed, at(10)).state.start(config("bed", components(22), components(6), repeats = false), at(10)).state
      .advance(at(10, 5), usage = listOf(resumed("com.insta", at(10)), resumed("com.a", at(10, 1)))).state
    assertEquals(state, state.persisted())
    assertEquals(MonitoringState(), MonitoringState.fromJson(null))
    assertEquals(MonitoringState(), MonitoringState.fromJson(JSONObject("{}")))

    // A malformed stored activity is dropped; its orphaned interval ends at the next pass.
    val json = state.toJson()
    json.getJSONObject("activities").getJSONObject("work").getJSONObject("config").remove("schedule")
    val damaged = MonitoringState.fromJson(JSONObject(json.toString()))
    assertEquals(listOf("bed"), damaged.monitoredActivities())
    assertTrue("work" in damaged.intervals)
    val cleaned = damaged.advance(at(10, 6))
    assertEquals(listOf("work"), cleaned.recorded.map { it.activityName })
    assertEquals("intervalDidEnd", cleaned.recorded.single().eventType)
    assertFalse("work" in cleaned.state.intervals)
  }

  @Test
  fun `unblockAll lifts the schedule and its fired thresholds until the next start, and monitoring goes on (iOS liftActiveIntervals)`() {
    val armed = config(events = listOf(threshold(seconds = 600)))
    var state = MonitoringState().start(armed, at(10)).state
    state = state.advance(at(10), usage = listOf(resumed("com.insta", at(10)))).state.advance(at(10, 10)).let { assertEquals(listOf("eventDidReachThreshold:limit"), it.types()); it.state }
    assertEquals(2, state.blocks().size)

    val lifted = state.lift()
    assertTrue("the schedule's and the fired threshold's shields drop", lifted.activeBlocks().isEmpty())
    assertEquals("the module changed the document", state.revision + 1, lifted.revision)
    assertEquals("the interval stays in progress", listOf("work"), lifted.intervals.keys.toList())
    assertSame("nothing more to lift: the same value", lifted, lifted.lift())
    assertEquals("lifted flags round-trip", lifted, lifted.persisted())
    assertTrue("the engine's next pass records nothing and shields nothing", lifted.advance(at(10, 20)).let { it.recorded.isEmpty() && it.state.activeBlocks().isEmpty() })
    assertEquals("the end is still recorded, once", listOf("intervalDidEnd"), lifted.advance(at(17)).types())
    assertEquals("the next occurrence shields again", listOf(MonitoringBlock("work", "APPS", "blocklist")), lifted.advance(at(17)).state.advance(at(9, day = 29)).state.blocks())

    // A threshold that fires AFTER the lift shields again (iOS appends its selection afresh).
    val two = config(events = listOf(threshold(seconds = 600), threshold("second", "APPS", 1200)))
    var s2 = MonitoringState().start(two, at(10)).state.advance(at(10), usage = listOf(resumed("com.insta", at(10)), resumed("com.a", at(10)))).state
    s2 = s2.advance(at(10, 10)).let { assertEquals(listOf("eventDidReachThreshold:limit"), it.types()); it.state }.lift()
    assertTrue(s2.activeBlocks().isEmpty())
    s2 = s2.advance(at(10, 20)).let { assertEquals(listOf("eventDidReachThreshold:second"), it.types()); it.state }
    assertEquals(listOf(MonitoringBlock("work", "APPS", "blocklist", "second")), s2.blocks())

    // A re-registration inside the same occurrence un-lifts (iOS intervalPlan sets the marker afresh, not lifted).
    val again = lifted.start(armed, at(10, 30))
    assertTrue(again.recorded.isEmpty())
    assertFalse(again.state.intervals.getValue("work").lifted)
    assertEquals(listOf(MonitoringBlock("work", "APPS", "blocklist")), again.state.blocks())
    // stopMonitoring on a lifted interval records its end like any other.
    assertEquals(listOf("intervalDidEnd"), lifted.stop("work", at(11)).types())
  }

  @Test
  fun `two stream events at the same instant are both fed (found wiring step 4)`() {
    // paused(A) and resumed(B) share a millisecond routinely; the second must not read as "fed already".
    val armed = config(events = listOf(threshold("a", "SOCIAL", 300), threshold("b", "APPS", 300)))
    val state = MonitoringState().start(armed, at(10)).state
      .advance(at(10, 0, 1), usage = listOf(resumed("com.insta", at(10)), resumed("com.a", at(10)))).state
    assertEquals(setOf("com.insta", "com.a"), state.foreground.resumedPackages)
    val fired = state.advance(at(10, 5))
    assertEquals(listOf("eventDidReachThreshold:a", "eventDidReachThreshold:b"), fired.types())
    // A re-query from the persisted cursor still feeds nothing twice.
    assertTrue(fired.state.advance(at(10, 5, 1), usage = listOf(resumed("com.insta", at(10)), resumed("com.a", at(10)))).recorded.isEmpty())
  }

  @Test
  fun `seeding a never-fed tracker rebuilds the resumed set without counting the lookback twice`() {
    // The module armed a threshold at 10:00 against a tracker that knew nothing (cursor null): history up to 10:00, no open span.
    val armed = config(events = listOf(threshold(seconds = 600, past = true)))
    val started = MonitoringState().start(armed, at(10))
    assertEquals(listOf(HistoryRequest("work", "limit", "SOCIAL", at(9), at(10))), started.historyRequests)
    // The engine comes up at 10:00:05 and reads an hour back: insta resumed at 09:55 and is still on screen.
    val lookback = listOf(resumed("com.insta", at(9, 55)))
    val seeded = started.state.seed(lookback, at(10, 0, 5), selections)
    assertEquals("the cursor is the seed's upto", at(10, 0, 5), seeded.foreground.cursorMs)
    assertEquals(setOf("com.insta"), seeded.foreground.resumedPackages)
    val progress = seeded.intervals.getValue("work").thresholds.getValue("limit")
    assertEquals("the open span starts at the cursor, the history request ends there", at(10, 0, 5), progress.activeSinceMs)
    assertEquals(listOf(HistoryRequest("work", "limit", "SOCIAL", at(9), at(10, 0, 5))), seeded.pendingHistoryRequests())
    assertSame("a tracker with a cursor is left alone", seeded, seeded.seed(lookback, at(10, 1), selections))
    // The history answer, from the same window: 5 min 5 s of insta inside [09:00, 10:00:05).
    val filled = seeded.backfill(seeded.pendingHistoryRequests().single(), lookback, at(10, 0, 5), selections).state
    assertEquals(5 * MINUTE + 5000, filled.intervals.getValue("work").thresholds.getValue("limit").accumulatedMs)
    // The stream from the cursor: insta leaves at 10:05:00 — 4 min 55 s more, exactly 10 minutes in all, counted once: the threshold fires.
    val fed = filled.advance(at(10, 5), usage = listOf(resumed("com.insta", at(9, 55)), paused("com.insta", at(10, 5))))
    assertEquals(10 * MINUTE, fed.state.intervals.getValue("work").thresholds.getValue("limit").accumulatedMs)
    assertEquals(listOf("eventDidReachThreshold:limit"), fed.types())
    // Feeding the lookback through `advance` instead would have opened the span at 09:55 on top of the history: the defect `seed` avoids.
    val doubled = started.state.backfill(started.historyRequests.single(), lookback, at(10), selections).state.advance(at(10, 0, 5), usage = lookback)
    assertTrue(doubled.state.intervals.getValue("work").thresholds.getValue("limit").projectedMs(at(10, 0, 5)) > 5 * MINUTE + 5000)
  }

  @Test
  fun `nothing monitored - the cursor does not move and the tracker forgets, so the next registration seeds (review 1)`() {
    // The engine persisted insta as resumed; the activity is stopped; the user leaves insta while nothing reads the stream.
    val fed = MonitoringState().start(config(), at(10)).state.advance(at(10, 1), usage = listOf(resumed("com.insta", at(10)))).state
    assertEquals(setOf("com.insta"), fed.foreground.resumedPackages)
    val stopped = fed.stop("work", at(10, 2)).state
    assertEquals("the last stop forgets the resumed set", UsageForegroundState(), stopped.foreground)
    val idle = stopped.advance(at(10, 30)).state
    assertEquals("an idle pass moves nothing", UsageForegroundState(), idle.foreground)
    // 10:40: a threshold on insta is armed — against a tracker that knows nothing (history up to now, no open span), not a stale "insta is open".
    val armed = idle.start(config(events = listOf(threshold(seconds = 600, past = true))), at(10, 40))
    val progress = armed.state.intervals.getValue("work").thresholds.getValue("limit")
    assertNull(progress.activeSinceMs)
    assertEquals(listOf(HistoryRequest("work", "limit", "SOCIAL", at(9), at(10, 40))), armed.historyRequests)
    // A stale cursor with insta still "resumed" would have counted 20 minutes nobody spent in insta.
    assertTrue(armed.state.advance(at(11)).recorded.isEmpty())
    // An interval left behind by a dropped activity still ends (the cursor rule applies only when nothing is in progress).
    val orphan = fed.copy(activities = emptyMap()).advance(at(10, 5))
    assertEquals(listOf("intervalDidEnd"), orphan.types())
  }

  @Test
  fun `schedulesAt evaluates the boundaries at now but leaves the thresholds as persisted (review 5)`() {
    val armed = config(events = listOf(threshold(seconds = 600)))
    // The engine persisted an open insta span at 10:00 and died; the module asks at 10:20.
    val persisted = MonitoringState().start(armed, at(10)).state.advance(at(10, 0, 1), usage = listOf(resumed("com.insta", at(10)))).state
    val view = persisted.schedulesAt(at(10, 20), utc, selections)
    assertEquals("the schedule block is in force", listOf(MonitoringBlock("work", "APPS", "blocklist")), view.blocks())
    assertFalse("the open span is not projected: the threshold the engine never fired does not fire here", view.intervals.getValue("work").thresholds.getValue("limit").fired)
    assertEquals("the stream position is untouched", persisted.foreground, view.foreground)
    // A boundary the engine has not recorded yet is still evaluated: outside at 17:30, the block is gone; inside tomorrow at 09:30, it is back.
    assertTrue(persisted.schedulesAt(at(17, 30), utc, selections).activeBlocks().isEmpty())
    assertEquals(1, persisted.schedulesAt(at(9, 30, day = 29), utc, selections).activeBlocks().size)
    // A threshold the engine did fire stays fired in the view.
    val fired = persisted.advance(at(10, 10)).let { assertEquals(listOf("eventDidReachThreshold:limit"), it.types()); it.state }
    assertEquals(2, fired.schedulesAt(at(10, 11), utc, selections).activeBlocks().size)
  }
}
