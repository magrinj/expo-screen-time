package expo.modules.screentime

import expo.modules.screentime.ShieldInteractionQueue.Entry
import expo.modules.screentime.ShieldInteractionQueue.State
import expo.modules.screentime.ShieldInteractionQueue.Target
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the Swift sections "ShieldInteractionQueue.State.record — presses
 * on one target within 2 s are one attempt; other targets are not",
 * "…targets without a handle, and the cap", "…drain — the JS shape, and a
 * collapse that spans a drain" and "ShieldInteractionQueue — persistence,
 * legacy entries, reserved names" (`scripts/swift-tests/main.swift`). Every
 * case is ported; "the queue of a build without targets still decodes" is
 * iOS's legacy migration, which here is simply an entry whose token had no
 * handle (the same shape, kept as that case).
 */
class ShieldInteractionQueueTest {
  private val instaApp = Target("application", "QUJD")
  private val tiktokApp = Target("application", "REVG")
  private val socialCategory = Target("category", "QUJD") // same handle bytes as instaApp, another kind

  /** A mutable queue over the immutable state, the way the Swift `var presses` reads. */
  private class Queue(var state: State = State.EMPTY) {
    fun record(entry: Entry): Boolean = state.record(entry).also { state = it.state }.kept
    fun drain(): List<Map<String, Any?>> = state.drain().also { state = it.state }.interactions
    val entries get() = state.entries
    val lastPressByTarget get() = state.lastPressByTarget
  }

  // "ShieldInteractionQueue.State.record — presses on one target within 2 s are one attempt; other targets are not"

  @Test
  fun `presses on one target within 2 s are one attempt, other targets are not`() {
    val presses = Queue()
    assertTrue("the first press on a target is recorded", presses.record(Entry.press("primary", 1_000, instaApp, "work")))
    assertFalse("a second button on the same shield 1.5 s later is the same attempt", presses.record(Entry.press("secondary", 2_500, instaApp, "work")))
    assertTrue("a press on ANOTHER app at the same instant is its own attempt", presses.record(Entry.press("primary", 2_500, tiktokApp)))
    assertTrue("a category shield is another target than an app with the same handle bytes", presses.record(Entry.press("primary", 2_600, socialCategory)))
    assertFalse("1.999 s after the first press: still collapsed", presses.record(Entry.press("primary", 2_999, instaApp)))
    assertTrue("2 s after it: a new attempt (the window is open at 2 s)", presses.record(Entry.press("primary", 3_000, instaApp)))
    assertEquals("four attempts, oldest first", listOf(1_000L, 2_500L, 2_600L, 3_000L), presses.entries.map { it.timestamp })
    assertEquals("each entry keeps its own owner", "work", presses.entries.first().activityName)
    assertNull(presses.entries[1].activityName)
    assertEquals("the per-target record holds the targets pressed in the last 2 s", 3, presses.lastPressByTarget.size)
    assertTrue(presses.record(Entry.press("primary", 10_000, tiktokApp)))
    assertEquals("…and is pruned to the window on every press", 1, presses.lastPressByTarget.size)
    assertTrue("a press with an earlier clock than its record is kept, never collapsed", presses.record(Entry.press("primary", 9_000, tiktokApp)))
    assertTrue(presses.record(Entry.press("primary", 6_500, instaApp)))
    assertEquals("a record more than 2 s in the future (the clock jumped back) is dropped like a stale one", 1, presses.lastPressByTarget.size)
  }

  // "ShieldInteractionQueue.State.record — targets without a handle, and the cap"

  @Test
  fun `presses whose token has no handle collapse among themselves and never swallow a press on a known target`() {
    val handleless = Queue()
    assertTrue(handleless.record(Entry.press("primary", 0)))
    assertFalse(handleless.record(Entry.press("secondary", 500)))
    assertTrue(handleless.record(Entry.press("primary", 500, instaApp)))
  }

  @Test
  fun `the queue keeps the newest 200 presses while the app is not draining`() {
    val flood = Queue()
    for (index in 0 until ShieldInteractionQueue.CAPACITY + 5) {
      flood.record(Entry.press("primary", index * 10L, Target("application", "H$index")))
    }
    assertEquals(ShieldInteractionQueue.CAPACITY, flood.entries.size)
    assertEquals("H5", flood.entries.first().target?.handle)
  }

  // "ShieldInteractionQueue.State.drain — the JS shape, and a collapse that spans a drain"

  @Test
  fun `a drain returns every entry in the JS shape and empties the queue, the collapse spans the drain`() {
    val drained = Queue()
    drained.record(Entry.press("primary", 1_000, instaApp, "work"))
    drained.record(Entry.press("secondary", 1_100, tiktokApp))
    val interactions = drained.drain()
    assertEquals(2, interactions.size)
    assertTrue(drained.entries.isEmpty())
    assertEquals(
      "an entry drains as { button, timestamp, target: TokenHandle, activityName }",
      mapOf("button" to "primary", "timestamp" to 1_000.0, "target" to mapOf("handle" to "QUJD", "type" to "application"), "activityName" to "work"),
      interactions[0],
    )
    assertTrue("a manual block drains with activityName null (JS null, not undefined)", interactions[1].containsKey("activityName"))
    assertNull(interactions[1]["activityName"])
    assertEquals(mapOf("handle" to "REVG", "type" to "application"), interactions[1]["target"])
    assertFalse("a press 1 s after a drained press on the same target still collapses", drained.record(Entry.press("primary", 2_000, instaApp)))
    assertTrue("an empty queue drains to nothing", drained.drain().isEmpty())
  }

  // "ShieldInteractionQueue — persistence, legacy entries, reserved names"

  @Test
  fun `an entry round-trips through its document shape, and the queue through the document`() {
    val persisted = Entry.press("primary", 1_720_512_000_000, instaApp, "work")
    assertEquals(persisted, Entry.fromJson(JSONObject(persisted.toJson().toString())))
    val state = Queue().apply { record(persisted); record(Entry.press("secondary", 1_720_512_003_000)) }.state
    val document = JSONObject()
    ShieldInteractionQueue.write(document, state)
    assertEquals(state, ShieldInteractionQueue.read(JSONObject(document.toString())))
    assertNull("an empty queue is not stored", State.EMPTY.toJson())
    ShieldInteractionQueue.write(document, State.EMPTY)
    assertFalse(document.has(ShieldInteractionQueue.KEY))
  }

  @Test
  fun `entries without a target still decode and drain with target and activityName null`() {
    val document = JSONObject("""{"shieldInteractions":{"entries":[{"button":"primary","timestamp":1720512000000},{"button":"secondary","timestamp":1720512003000}]}}""")
    val state = ShieldInteractionQueue.read(document)
    assertEquals(2, state.entries.size)
    assertTrue(state.entries.all { it.target == null && it.activityName == null })
    val drained = ShieldInteractionQueue.drain(document)
    assertEquals(mapOf("button" to "primary", "timestamp" to 1_720_512_000_000.0, "target" to null, "activityName" to null), drained[0])
  }

  @Test
  fun `entries without a button or a timestamp are dropped, a target without a handle is no target`() {
    val state = State.fromJson(JSONObject("""{"entries":[{"timestamp":1},{"button":"primary"},{"button":"primary","timestamp":2,"target":{"type":"application"}}]}"""))
    assertEquals(listOf(Entry.press("primary", 2)), state.entries)
    // JSON nulls and numbers where strings belong: Android's `optString`
    // would read "null" and "7"; these never become strings.
    val nulls = State.fromJson(JSONObject("""{"entries":[{"button":null,"timestamp":1},{"button":"primary","timestamp":3,"target":{"type":"application","handle":7},"activityName":null}]}"""))
    assertEquals(listOf(Entry.press("primary", 3)), nulls.entries)
  }

  @Test
  fun `no queue, or garbage, is an empty queue`() {
    assertEquals(State.EMPTY, State.fromJson(null))
    assertEquals(State.EMPTY, ShieldInteractionQueue.read(JSONObject("""{"shieldInteractions":"not a queue"}""")))
    assertEquals(State.EMPTY, State.fromJson(JSONObject("""{"entries":"x","lastPressByTarget":[1]}""")))
    assertEquals(mapOf("application:QUJD" to 5L), State.fromJson(JSONObject("""{"lastPressByTarget":{"application:QUJD":5.0,"bad":"x"}}""")).lastPressByTarget)
  }

  @Test
  fun `a reserved or empty activity name is never exposed, a user's activity name is`() {
    assertNull(Entry.press("primary", 1, activityName = "expo_st_snooze_X").activityName)
    assertNull(Entry.fromJson(JSONObject("""{"button":"primary","timestamp":1,"activityName":"expo_st_temporary_unlock"}"""))?.activityName)
    assertNull(Entry.press("primary", 1, activityName = "").activityName)
    assertEquals("work", Entry.press("primary", 1, activityName = "work").activityName)
    assertNull("the record step sanitizes too, whoever built the entry", State.EMPTY.record(Entry("primary", 1, null, "expo_st_x")).state.entries.single().activityName)
  }

  // Android: the exact boundary of the window

  @Test
  fun `the 2 s collapse at the exact boundary`() {
    val presses = Queue()
    assertTrue(presses.record(Entry.press("primary", 0, instaApp)))
    assertFalse("1999 ms later: collapsed", presses.record(Entry.press("secondary", 1_999, instaApp)))
    assertTrue("2000 ms later: a new attempt, timed from the FIRST press (the collapsed one did not restart the window)", presses.record(Entry.press("primary", 2_000, instaApp)))
    assertFalse("…which opens its own window", presses.record(Entry.press("primary", 3_999, instaApp)))
    assertTrue(presses.record(Entry.press("primary", 4_000, instaApp)))
    assertEquals(listOf(0L, 2_000L, 4_000L), presses.entries.map { it.timestamp })
    assertEquals("a record exactly 2 s old is pruned by a press on another target", 1,
      Queue().apply { record(Entry.press("primary", 0, instaApp)); record(Entry.press("primary", 2_000, tiktokApp)) }.lastPressByTarget.size)
    assertEquals("…and one 1999 ms old is kept", 2,
      Queue().apply { record(Entry.press("primary", 0, instaApp)); record(Entry.press("primary", 1_999, tiktokApp)) }.lastPressByTarget.size)
  }

  // Android: two processes over one document

  @Test
  fun `a press recorded by the shield process between two drains is drained next, and the collapse spans processes`() {
    // The document as the state store holds it; each process parses its own copy.
    var stored = JSONObject()
    fun shieldRecords(entry: Entry): Boolean {
      val document = JSONObject(stored.toString())
      val kept = ShieldInteractionQueue.record(document, entry)
      stored = document
      return kept
    }
    fun appDrains(): List<Map<String, Any?>> {
      val document = JSONObject(stored.toString())
      val drained = ShieldInteractionQueue.drain(document)
      stored = document
      return drained
    }

    assertTrue(shieldRecords(Entry.press("primary", 1_000, instaApp, "work")))
    assertEquals(1, appDrains().size)
    assertFalse("the shield process's next press 500 ms later collapses into the drained one", shieldRecords(Entry.press("secondary", 1_500, instaApp, "work")))
    assertTrue(appDrains().isEmpty())
    assertTrue(shieldRecords(Entry.press("primary", 5_000, tiktokApp)))
    assertTrue(shieldRecords(Entry.press("primary", 5_100, instaApp)))
    val drained = appDrains()
    assertEquals(listOf(5_000.0, 5_100.0), drained.map { it["timestamp"] })
    assertEquals("the record survives in the document, so the next press still collapses", false, shieldRecords(Entry.press("primary", 6_000, tiktokApp)))
    val before = stored.toString()
    assertTrue(appDrains().isEmpty())
    assertEquals("a drain of an empty queue writes nothing", before, stored.toString())
  }
}
