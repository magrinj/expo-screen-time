package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `scripts/swift-tests/main.swift`, section "MonitorEventCursor — regression", plus the log's bounds, reads and codec. */
class MonitorEventLogTest {
  private fun log(vararg events: Triple<String, String, Long>): MonitorEventLog {
    var log = MonitorEventLog()
    for ((activity, type, at) in events) log = log.append(activity, type, null, at).first
    return log
  }

  @Test
  fun `MonitorEventCursor - regression (Swift section)`() {
    // The iOS burst: three events, two sharing a timestamp, read newest first.
    val burst = log(Triple("A", "a", 200), Triple("A", "b", 200), Triple("A", "c", 300))
    val cursor = MonitorEventCursor(0)
    val (taken, advanced) = cursor.take(burst)
    assertEquals("burst emitted oldest first, ties included", listOf("a", "b", "c"), taken.map { it.eventType })
    assertEquals(listOf("1", "2", "3"), taken.map { it.id })
    assertTrue("a re-read emits nothing", advanced.take(burst).first.isEmpty())
    assertTrue("the un-advanced cursor would re-emit: advancing is the caller's job", cursor.take(burst).first.size == 3)
  }

  @Test
  fun `a cursor started now skips history and survives a clear`() {
    val history = log(Triple("A", "intervalDidStart", 100))
    val cursor = MonitorEventCursor.startingNow(history)
    assertTrue(cursor.take(history).first.isEmpty())
    val (cleared, _) = history.clear(null).append("A", "intervalDidEnd", null, 200)
    val (fresh, _) = cursor.take(cleared)
    assertEquals("only what was appended after the subscription, with a new id", listOf("2"), fresh.map { it.id })
    assertEquals("clearing never resets the sequence", 3, cleared.nextSequence)
  }

  @Test
  fun `getMonitorEvents reads as iOS does`() {
    var log = MonitorEventLog()
    for (i in 1..5) log = log.append(if (i % 2 == 0) "B" else "A", "t$i", null, i * 10L).first
    assertEquals("one activity: oldest first, the last `limit`", listOf("t3", "t5"), log.events("A", 2).map { it.eventType })
    assertEquals(listOf("t1", "t3", "t5"), log.events("A").map { it.eventType })
    assertEquals("all: newest first, the first `limit`", listOf("t5", "t4", "t3"), log.events(null, 3).map { it.eventType })
    assertEquals(5, log.events(null).size)
    assertEquals(emptyList<MonitorEvent>(), log.events("A", 0))
    assertEquals(2, log.clear("A").events.size)
    assertEquals(0, log.clear(null).events.size)
  }

  @Test
  fun `the JS payload is the contract's MonitorEvent`() {
    val (_, event) = MonitorEventLog().append("A", "eventDidReachThreshold", "limit", 1_700_000_000_123)
    assertEquals(
      mapOf("id" to "1", "activityName" to "A", "eventType" to "eventDidReachThreshold", "eventName" to "limit", "timestamp" to 1_700_000_000_123.0),
      event.toJs(),
    )
    val (_, plain) = MonitorEventLog().append("A", "intervalDidStart", null, 5)
    assertTrue("eventName is absent, not null", "eventName" !in plain.toJs())
  }

  @Test
  fun `the log is bounded per activity and in total`() {
    var log = MonitorEventLog()
    for (i in 1..1005) log = log.append("A", "t", null, i.toLong()).first
    assertEquals(MonitorEventLog.PER_ACTIVITY_CAP, log.events.size)
    assertEquals("the oldest went", "6", log.events.first().id)
    for (i in 1..1000) log = log.append("B", "t", null, i.toLong()).first
    assertEquals(2000, log.events.size)
    log = log.append("C", "t", null, 1).first
    assertEquals(MonitorEventLog.TOTAL_CAP, log.events.size)
    assertEquals("the oldest overall went", 999, log.events.count { it.activityName == "A" })
  }

  @Test
  fun `the codec round-trips and tolerates malformed entries`() {
    val log = log(Triple("A", "intervalDidStart", 100), Triple("B", "eventDidReachThreshold", 200)).let { it.append("B", "x", "name", 300).first }
    val json = JSONObject(log.toJson().toString())
    assertEquals(log, MonitorEventLog.fromJson(json))
    assertEquals(MonitorEventLog(), MonitorEventLog.fromJson(null))
    val damaged = JSONObject("""{"nextSeq":9,"events":[{"seq":1,"activityName":"A","eventType":"t","timestamp":1},{"seq":"x"},"junk",{"seq":2,"eventType":"t","timestamp":2}]}""")
    val read = MonitorEventLog.fromJson(damaged)
    assertEquals(listOf(1L), read.events.map { it.sequence })
    assertEquals(9, read.nextSequence)
    assertEquals("a lost sequence is rebuilt above the newest id", 6, MonitorEventLog.fromJson(JSONObject("""{"events":[{"seq":5,"activityName":"A","eventType":"t","timestamp":1}]}""")).nextSequence)
  }
}
