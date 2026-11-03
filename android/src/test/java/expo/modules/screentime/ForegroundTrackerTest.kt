package expo.modules.screentime

import expo.modules.screentime.ForegroundTracker.Companion.ACTIVITY_DESTROYED
import expo.modules.screentime.ForegroundTracker.Companion.ACTIVITY_PAUSED
import expo.modules.screentime.ForegroundTracker.Companion.ACTIVITY_RESUMED
import expo.modules.screentime.ForegroundTracker.Companion.ACTIVITY_STOPPED
import expo.modules.screentime.ForegroundTracker.Companion.DEVICE_SHUTDOWN
import expo.modules.screentime.ForegroundTracker.Companion.DEVICE_STARTUP
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

  @Test
  fun `nothing stays on screen across a shutdown or a boot`() {
    // Measured on the Android 17 emulator: rebooting under the shield left the
    // blocked app's last event a PAUSED with no STOPPED, then DEVICE_SHUTDOWN.
    // Kept "visible", it read as a picture-in-picture window after the boot and
    // the standing check shielded it over whatever the user opened.
    for (marker in listOf(DEVICE_SHUTDOWN, DEVICE_STARTUP)) {
      val tracker = ForegroundTracker()
      tracker.apply(ACTIVITY_RESUMED, "com.blocked", "Main")
      tracker.apply(ACTIVITY_PAUSED, "com.blocked", "Main")
      tracker.apply(ACTIVITY_RESUMED, "com.other", "Main")
      assertEquals(setOf("com.blocked", "com.other"), tracker.visible)
      assertNull(tracker.apply(marker, "android", null))
      assertTrue(tracker.visible.isEmpty())
      assertTrue(tracker.resumed.isEmpty())
      // The first resume after the boot is a new app coming forward.
      assertEquals("com.other", tracker.apply(ACTIVITY_RESUMED, "com.other", "Main"))
    }
  }

  @Test
  fun `a paused app counts as visible only once its pause has settled, so an app switch is not read as picture-in-picture`() {
    // Measured on the Android 17 emulator: a force stop killed the shield, the
    // blocked app resumed for an instant and paused as the host app came
    // forward; its STOPPED came later. The engine, starting in that gap, read
    // the blocked app as a PiP window and shielded it over the host app.
    val tracker = ForegroundTracker()
    tracker.apply(ACTIVITY_RESUMED, "com.blocked", "Main", atMs = 1_000)
    tracker.apply(ACTIVITY_PAUSED, "com.blocked", "Main", atMs = 1_050)
    tracker.apply(ACTIVITY_RESUMED, "com.host", "Main", atMs = 1_060)
    assertEquals(setOf("com.blocked", "com.host"), tracker.visible)
    assertEquals(setOf("com.host"), tracker.visibleAt(1_200, settleMs = 2_000))
    // The switch completes: the stop arrives and the app is gone either way.
    tracker.apply(ACTIVITY_STOPPED, "com.blocked", "Main", atMs = 1_400)
    assertEquals(setOf("com.host"), tracker.visibleAt(10_000, settleMs = 2_000))
    // A PiP window is never stopped: once settled, it counts.
    tracker.apply(ACTIVITY_RESUMED, "com.video", "Player", atMs = 20_000)
    tracker.apply(ACTIVITY_PAUSED, "com.video", "Player", atMs = 20_100)
    assertEquals(setOf("com.host"), tracker.visibleAt(21_000, settleMs = 2_000))
    assertEquals(setOf("com.host", "com.video"), tracker.visibleAt(22_100, settleMs = 2_000))
    // A resume clears the pause time; a later pause starts the wait again.
    tracker.apply(ACTIVITY_RESUMED, "com.video", "Player", atMs = 30_000)
    tracker.apply(ACTIVITY_PAUSED, "com.video", "Player", atMs = 30_500)
    assertEquals(setOf("com.host"), tracker.visibleAt(31_000, settleMs = 2_000))
  }
}
