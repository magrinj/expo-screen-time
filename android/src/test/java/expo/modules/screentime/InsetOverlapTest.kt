package expo.modules.screentime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The padding a view needs to keep its content clear of the system bars:
 * only the part of the bars its own bounds (in window coordinates) run
 * under, so a picker above a tab bar is not padded for a bar it never
 * touches.
 */
class InsetOverlapTest {
  private val window = InsetOverlap.Rect(0, 0, 1080, 2400)
  private val bars = InsetOverlap.Edges(left = 0, top = 120, right = 0, bottom = 48)

  @Test
  fun `a view filling the window is padded by every bar`() {
    assertEquals(InsetOverlap.Edges(0, 120, 0, 48), InsetOverlap.of(window, window, bars))
  }

  @Test
  fun `a view below the status bar and above the navigation bar needs nothing`() {
    assertEquals(InsetOverlap.Edges(0, 0, 0, 0), InsetOverlap.of(InsetOverlap.Rect(0, 120, 1080, 2352), window, bars))
    assertEquals(InsetOverlap.Edges(0, 0, 0, 0), InsetOverlap.of(InsetOverlap.Rect(0, 300, 1080, 2000), window, bars))
  }

  @Test
  fun `a view partly under a bar is padded by the overlap only`() {
    // Ends 20 px into the 48 px navigation bar; starts 30 px into the status bar.
    assertEquals(InsetOverlap.Edges(0, 30, 0, 20), InsetOverlap.of(InsetOverlap.Rect(0, 90, 1080, 2372), window, bars))
  }

  @Test
  fun `landscape, the bar on a side and a cutout on the other`() {
    val side = InsetOverlap.Edges(left = 100, top = 0, right = 84, bottom = 0)
    val wide = InsetOverlap.Rect(0, 0, 2400, 1080)
    assertEquals(InsetOverlap.Edges(100, 0, 84, 0), InsetOverlap.of(wide, wide, side))
    assertEquals(InsetOverlap.Edges(40, 0, 0, 0), InsetOverlap.of(InsetOverlap.Rect(60, 0, 1200, 1080), wide, side))
  }

  @Test
  fun `a view taller than the bar region never gets a negative or oversized pad`() {
    assertEquals(InsetOverlap.Edges(0, 0, 0, 48), InsetOverlap.of(InsetOverlap.Rect(0, 2380, 1080, 2500), window, bars))
    assertEquals(InsetOverlap.Edges(0, 120, 0, 0), InsetOverlap.of(InsetOverlap.Rect(0, -50, 1080, 1000), window, bars))
  }
}
