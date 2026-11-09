package expo.modules.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report's per-class count ([ActivityInstances]): every transition, read directly. */
class ActivityInstancesTest {
  private val none = ActivityInstances.NONE

  @Test
  fun `a normal switch - resume, pause, stop - owes and pays one stop`() {
    val resumed = none.resume()
    assertEquals(ActivityInstances(resumed = 1), resumed)
    val (paused, ended) = resumed.pause()
    assertTrue("the pause ends the class", ended)
    assertEquals(ActivityInstances(owedStops = 1), paused)
    val (stopped, died) = paused.stop()
    assertFalse(died)
    assertTrue(stopped.isEmpty)
  }

  @Test
  fun `a stop with nothing owed and one instance resumed is a death`() {
    val (next, died) = none.resume().stop()
    assertTrue(died)
    assertTrue(next.isEmpty)
  }

  @Test
  fun `a relaunch's old stop is paid however late it comes - no window`() {
    // A paused, B resumed over it; A's stop, an hour later or a second later, pays A's pause.
    val b = none.resume().pause().first.resume()
    val (afterOld, died) = b.stop()
    assertFalse(died)
    // It came with B resumed, so it might have been B dying: the counts turn uncertain.
    assertEquals(ActivityInstances(resumed = 1, uncertain = true), afterOld)
  }

  @Test
  fun `a stop that pays an owed one while an instance is resumed makes the counts uncertain - no later stop is read as a death`() {
    // A back from under a dialog (its pause owes a stop that never comes), then A dies: the stop pays the stale one.
    val (afterDeath, died) = none.resume().pause().first.resume().stop()
    assertFalse("main's reading: the death is not seen", died)
    assertTrue(afterDeath.uncertain)
    // Later, nothing owed and one instance resumed: still not read as a death while uncertain.
    assertFalse(afterDeath.pause().first.stop().first.resume().stop().second)
  }

  @Test
  fun `two instances resumed at once make the counts uncertain - a pause ends the class, as main does`() {
    val two = none.resume().resume()
    assertEquals(ActivityInstances(resumed = 2, uncertain = true), two)
    val (one, ended) = two.pause()
    assertTrue("main's rule: the first pause ends the class", ended)
    assertFalse("a stop is never read as a death after that", one.stop().first.resume().stop().second)
  }

  @Test
  fun `a closing owes each resumed instance's stop, and its pause after the closing owes no second one`() {
    val closed = none.resume().closed()
    assertEquals(ActivityInstances(owedStops = 1, closingPauses = 1), closed)
    assertEquals("idempotent", closed, closed.closed())
    val (paused, ended) = closed.pause()
    assertFalse(ended)
    assertEquals(ActivityInstances(owedStops = 1), paused)
    assertTrue(paused.stop().first.isEmpty)
  }

  @Test
  fun `an old instance's stop after the screen came back and a new instance resumed is the closing's, not a death`() {
    val (afterOld, died) = none.resume().closed().resume().stop()
    assertFalse(died)
    assertEquals(ActivityInstances(resumed = 1, uncertain = true), afterOld)
  }

  @Test
  fun `orphans change nothing - a stop with nothing resumed and nothing owed`() {
    assertEquals(none to false, none.stop())
    val (owing, ended) = none.pause()
    assertFalse(ended)
    assertEquals("an orphan pause still owes its instance's stop", ActivityInstances(owedStops = 1), owing)
  }
}
