package expo.modules.screentime

import android.content.Context
import android.graphics.Picture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View

/**
 * Pre-warms the shield in the engine process, off the critical path, so the
 * first shield after the engine started and the first after a long idle
 * cost what a warm one does inside `onCreate` (on the Galaxy
 * A54 the shield's `onCreate` took 96 ms after 28 h and 32 ms at t0 against
 * 5 ms warm — the state document read under the file lock, the target's APK
 * opened for its label and icon, the view classes faulted in).
 *
 * Two passes, both on the process's main thread (the shield runs there, so
 * that is the thread whose code paths and class state must be hot; nothing
 * else runs on it in `:screentime`):
 * - [warm], when the composed sources change: the faces of the apps the
 *   blocklist sources shield are loaded into [ShieldCache] (label and icon
 *   from PackageManager), **one per main-thread message, each posted when
 *   the previous one finished**, so a shield launch that lands meanwhile
 *   runs between two loads, never behind the batch (a face costs up to
 *   110 ms on the emulator in a fresh process; 32 would hold a shield for
 *   seconds). Capped at [MAX_FACES], what [ShieldCache] keeps; an allowlist
 *   shields "every other app", those load on demand. The pass ends with
 *   the dry run below.
 * - [touch], at every `SCREEN_ON` and `USER_PRESENT` (the moments before a
 *   user can reach an app): a dry run of the shield's view tree
 *   ([ShieldLayout], the same code as the Activity) with the default
 *   configuration and the first shielded app's face, measured and laid out
 *   at the display size and **recorded into a [Picture]**, so every child's
 *   `onDraw` runs with no pixels allocated. That touches the view classes,
 *   the theme, the font shaping of the first text layout, the drawables'
 *   draw code and the configuration cache. It does not decode or upload the
 *   icon's pixels, and it does not touch the renderer: the first frame
 *   (RenderThread, the surface) stays cold too on a cold launch (the A54:
 *   resume → drawn 125 ms after 28 h, 94 ms at t0, 40 ms warm) and is not
 *   covered here. A touch never cancels a warm in flight: the warm's own
 *   final dry run covers it.
 *
 * Nothing here is visible or needs a window: no overlay is added, the
 * `HIDE_OVERLAY_WINDOWS` constraint is untouched.
 */
internal object ShieldWarmer {
  private val main = Handler(Looper.getMainLooper())

  // Main-thread state: the faces still to load, the warm's reason and start, whether a chain runs.
  private val pending = ArrayDeque<String>()
  private var reason = ""
  private var startedAt = 0L
  private var loaded = 0
  private var faceMs = 0L
  private var chained = false

  /** The packages the last warm asked for, in order (the first one is the dry run's). */
  @Volatile
  private var wanted: List<String> = emptyList()

  /** Faces for [packages], then a dry run; [reason] for the log. Any thread. */
  fun warm(context: Context, packages: Collection<String>, reason: String) {
    val list = packages.take(MAX_FACES)
    wanted = list
    val app = context.applicationContext
    post {
      // A warm replaces the queue of the one in flight (faces already loaded are skipped), never
      // its chain: at most one step is ever posted.
      pending.clear()
      pending.addAll(list)
      this.reason = reason
      startedAt = SystemClock.uptimeMillis()
      loaded = 0
      faceMs = 0L
      if (!chained) {
        chained = true
        step(app)
      }
    }
  }

  /** The dry run alone, with the faces already loaded; [reason] for the log. Any thread. Never cancels a warm. */
  fun touch(context: Context, reason: String) {
    val app = context.applicationContext
    post {
      val layoutMs = dryRun(app)
      Log.i(EngineController.TAG, "shield-warm reason=$reason faces=${ShieldCache.faceCount} layoutMs=$layoutMs")
    }
  }

  /** Every pending pass is dropped (the engine stops). */
  fun cancel() {
    main.removeCallbacksAndMessages(TOKEN)
    main.post { pending.clear(); chained = false }
  }

  /** One face, then the next step posted — behind whatever reached the main queue meanwhile. */
  private fun step(app: Context) {
    val next = pending.removeFirstOrNull()
    if (next == null) {
      chained = false
      val layoutMs = dryRun(app)
      Log.i(EngineController.TAG, "shield-warm reason=$reason faces=$loaded/${wanted.size} faceMs=$faceMs layoutMs=$layoutMs totalMs=${SystemClock.uptimeMillis() - startedAt}")
      return
    }
    if (!ShieldCache.hasFace(next)) {
      faceMs += ShieldCache.warmFace(app, next)
      loaded++
    }
    post { step(app) }
  }

  private fun post(block: () -> Unit) {
    main.postAtTime({
      try {
        block()
      } catch (t: Throwable) {
        // A warm-up must never take the engine (and the shield) down.
        chained = false
        Log.w(EngineController.TAG, "shield-warm failed: ${t.javaClass.simpleName}: ${t.message}")
      }
    }, TOKEN, SystemClock.uptimeMillis())
  }

  /** Builds, measures, lays out and records the shield once, off screen; returns how long it took, in ms. */
  private fun dryRun(app: Context): Long {
    val started = SystemClock.uptimeMillis()
    val themed = ContextThemeWrapper(app, R.style.ExpoScreenTime_Shield)
    val appearance = ShieldCache.appearance(StateStore(app), owner = null)
    val target = wanted.firstOrNull()
    val face = if (target != null) ShieldCache.face(app, target) else ShieldCache.Face(null, null)
    val column = ShieldLayout.build(themed, appearance, ShieldLayout.colors(themed, appearance), face)
    val metrics = app.resources.displayMetrics
    column.measure(
      View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
    )
    column.layout(0, 0, column.measuredWidth, column.measuredHeight)
    // A recording canvas at full size: no quick-reject of the centred children (a small bitmap
    // would skip every one of them), no pixels.
    val picture = Picture()
    column.draw(picture.beginRecording(column.measuredWidth, column.measuredHeight))
    picture.endRecording()
    return SystemClock.uptimeMillis() - started
  }

  private val TOKEN = Any()
  const val MAX_FACES = ShieldCache.MAX_CACHED_FACES
}
