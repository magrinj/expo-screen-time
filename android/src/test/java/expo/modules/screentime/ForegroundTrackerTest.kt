package expo.modules.screentime

import expo.modules.screentime.ForegroundTracker.Companion.ACTIVITY_DESTROYED
import expo.modules.screentime.ForegroundTracker.Companion.ACTIVITY_PAUSED
import expo.modules.screentime.ForegroundTracker.Companion.ACTIVITY_RESUMED
import expo.modules.screentime.ForegroundTracker.Companion.ACTIVITY_STOPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-screen set from UsageStats activity events: multi-window, PiP (paused but visible), covered (stopped). */
class ForegroundTrackerTest {
  @Test
  fun `an app coming forward is reported once, per package`() {
    val tracker = ForegroundTracker()
    assertEquals("com.a", tracker.apply(ACTIVITY_RESUMED, "com.a", "Main"))
    // A second activity of the same app: no new app came forward.
    assertNull(tracker.apply(ACTIVITY_RESUMED, "com.a", "Detail"))
    assertEquals(setOf("com.a"), tracker.resumed)
    assertNull(tracker.apply(ACTIVITY_PAUSED, "com.a", "Main"))
    assertEquals(setOf("com.a"), tracker.resumed)
  }

  @Test
  fun `multi-window keeps several apps resumed`() {
    val tracker = ForegroundTracker()
    tracker.apply(ACTIVITY_RESUMED, "com.a", "Main")
    tracker.apply(ACTIVITY_RESUMED, "com.b", "Main")
    assertEquals(setOf("com.a", "com.b"), tracker.resumed)
  }

  @Test
  fun `paused but not stopped stays visible (picture-in-picture) and stopped is gone`() {
    val tracker = ForegroundTracker()
    tracker.apply(ACTIVITY_RESUMED, "com.a", "Main")
    tracker.apply(ACTIVITY_PAUSED, "com.a", "Main")
    assertTrue(tracker.resumed.isEmpty())
    assertEquals(setOf("com.a"), tracker.visible)
    tracker.apply(ACTIVITY_STOPPED, "com.a", "Main")
    assertTrue(tracker.visible.isEmpty())
    // Resumed again after PiP, then destroyed outright.
    assertEquals("com.a", tracker.apply(ACTIVITY_RESUMED, "com.a", "Main"))
    tracker.apply(ACTIVITY_DESTROYED, "com.a", "Main")
    assertTrue(tracker.visible.isEmpty())
  }

  @Test
  fun `a shield covering the app stops it, and the next resume is a new coming forward`() {
    val tracker = ForegroundTracker()
    tracker.apply(ACTIVITY_RESUMED, "com.a", "Main")
    tracker.apply(ACTIVITY_RESUMED, "com.host", "Shield")
    tracker.apply(ACTIVITY_PAUSED, "com.a", "Main")
    tracker.apply(ACTIVITY_STOPPED, "com.a", "Main")
    assertEquals(setOf("com.host"), tracker.resumed)
    assertEquals("com.a", tracker.apply(ACTIVITY_RESUMED, "com.a", "Main"))
    tracker.reset()
    assertTrue(tracker.visible.isEmpty())
  }

  @Test
  fun `unknown event types are ignored`() {
    val tracker = ForegroundTracker()
    assertNull(tracker.apply(7, "com.a", null))
    assertTrue(tracker.visible.isEmpty())
  }

  @Test
  fun `below API 29 there is no stopped event, so a paused activity is gone at once`() {
    val tracker = ForegroundTracker(tracksStopped = false)
    tracker.apply(ACTIVITY_RESUMED, "com.a", "Main")
    tracker.apply(ACTIVITY_PAUSED, "com.a", "Main")
    assertTrue(tracker.visible.isEmpty())
    assertEquals("com.a", tracker.apply(ACTIVITY_RESUMED, "com.a", "Main"))
  }
}
