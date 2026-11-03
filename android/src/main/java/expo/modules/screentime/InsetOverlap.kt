package expo.modules.screentime

import kotlin.math.max
import kotlin.math.min

/**
 * The padding a view needs to keep its content clear of the system bars
 * (status and navigation bars, a display cutout) when the window is drawn
 * edge to edge (the default from targetSdk 35): only the part of the bars
 * the view's own bounds run under. A picker above the app's tab bar sits
 * entirely over the navigation bar's region and gets nothing; one filling
 * the screen gets the bars' full height. A view that runs past the window's
 * edge (laid out inside a scroll container) is padded by the bar at most,
 * not by everything off screen: the part beyond the bar scrolls into view,
 * the bar never does. Pure, so the arithmetic is tested on the JVM
 * (`InsetOverlapTest`).
 */
internal object InsetOverlap {
  /** A rectangle in window coordinates (pixels). */
  data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

  /** Four inset sizes, as `androidx.core.graphics.Insets` holds them. */
  data class Edges(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isZero: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0
  }

  /** How far [view] runs under each bar of [insets], the bars lining the inside of [window]; never negative, never more than the bar. */
  fun of(view: Rect, window: Rect, insets: Edges): Edges = Edges(
    left = clamp((window.left + insets.left) - view.left, insets.left),
    top = clamp((window.top + insets.top) - view.top, insets.top),
    right = clamp(view.right - (window.right - insets.right), insets.right),
    bottom = clamp(view.bottom - (window.bottom - insets.bottom), insets.bottom),
  )

  private fun clamp(overlap: Int, bar: Int): Int = max(0, min(overlap, bar))
}
