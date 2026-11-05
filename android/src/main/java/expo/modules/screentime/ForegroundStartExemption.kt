package expo.modules.screentime

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The exemption that lets the engine come back from the background: an app
 * holding `SYSTEM_ALERT_WINDOW` may start a foreground service from the
 * background, and since Android 15 (apps targeting 35+) only **while one of
 * its processes shows an overlay window** (`ActiveServices.
 * shouldAllowFgsStartForegroundNoBindingCheckLocked`: `hasOverlayUi`, set by
 * `Session.onWindowSurfaceVisibilityChanged` when a `TYPE_APPLICATION_OVERLAY`
 * surface is shown). Blocking requires that permission anyway (the shield's
 * background-activity-launch exemption), so the restart chain uses it: a
 * 1×1 transparent, untouchable, unfocusable overlay window is held on the
 * main looper while the attempts run, and removed as soon as one succeeds or
 * the last one fails. The surface is shown asynchronously (a frame), hence
 * the attempt schedule instead of one try.
 *
 * What the window does **not** do: it takes no focus (`FLAG_NOT_FOCUSABLE`,
 * so the app on screen is not paused), draws nothing, is no activity (the
 * foreground tracker sees no event), and lives under two seconds. Its one
 * visible trace is the system's "displaying over other apps" notice, posted
 * and cancelled with it. With the screen off, or under the keyguard, an
 * overlay window is not shown and the exemption is not granted ([blockedBy]):
 * under the keyguard the chain waits for the unlock in place ([UnlockWait]),
 * with the screen off it retries at the next screen-on ([UnlockRetry],
 * [EngineWatchdog.retry]), without counting a refusal.
 *
 * The other exemptions the chain relies on need nothing from here: a
 * process at `TOP` (the app visible), the `BOOT_COMPLETED` /
 * `MY_PACKAGE_REPLACED` broadcasts, and the user's battery-optimization
 * exemption (`batteryOptimization` step), which allows the start directly.
 * An inexact alarm's temp allowlist does **not** (`AlarmManagerService`:
 * `mOptsWithoutFgs` for `setAndAllowWhileIdle`), which is why the alarm
 * wake and the watchdog go through here.
 */
internal object ForegroundStartExemption {
  /**
   * When each attempt runs, ms after the window was added. The first frame
   * lands within a few tens of ms on a Pixel; the tail covers a loaded
   * device.
   */
  val ATTEMPTS_MS = longArrayOf(80, 200, 450, 900, 1_500)

  private val handler = Handler(Looper.getMainLooper())

  /** One UI context for the default display, reused across revivals (a window context is not free). */
  @Volatile private var windowContext: Context? = null

  /** Whether the exemption can be used: the overlay permission is held. */
  fun available(context: Context): Boolean = Settings.canDrawOverlays(context)

  /**
   * What keeps the exemption from being granted right now, or null: an
   * overlay window is never shown with the screen off, and is hidden under
   * the keyguard (it sits below the lock screen's layer), so an attempt
   * would be refused for certain. Measured on Android 17: refused at the
   * lock screen, allowed once unlocked. The chain then waits for the unlock
   * instead of spending attempts and backoff on it ([UnlockWait],
   * [EngineWatchdog.retry]). `isKeyguardLocked` turns false at
   * `keyguardGoingAway`, before the lock screen has finished leaving, which
   * the attempt schedule absorbs.
   */
  fun blockedBy(context: Context): String? {
    val app = context.applicationContext
    val power = app.getSystemService(PowerManager::class.java)
    if (power != null && !power.isInteractive) return UnlockWait.SCREEN_OFF
    val keyguard = app.getSystemService(KeyguardManager::class.java)
    if (keyguard != null && keyguard.isKeyguardLocked) return UnlockWait.KEYGUARD
    return null
  }

  /**
   * Holds the overlay window and runs [attempt] on the main thread at each
   * of [ATTEMPTS_MS] until one returns true (an attempt reports its refusal
   * by returning false; an exception is a failure of that attempt). [done]
   * is called once, on the main thread, with the outcome; the window is
   * gone by then. Without the permission, or when the window cannot be
   * added, [done] gets false at once.
   */
  fun attempt(context: Context, reason: String, attempt: (Int) -> Boolean, done: (Boolean) -> Unit) {
    if (!available(context)) {
      Log.w(TAG, "no overlay permission: the engine cannot be revived from the background ($reason)")
      handler.post { done(false) }
      return
    }
    handler.post {
      val app = context.applicationContext
      val manager: WindowManager
      val view: View
      try {
        val windowContext = windowContext(app)
        manager = windowContext.getSystemService(WindowManager::class.java)
        view = View(windowContext)
        val params = WindowManager.LayoutParams(
          1,
          1,
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
          PixelFormat.TRANSLUCENT,
        ).apply {
          gravity = Gravity.TOP or Gravity.START
          title = "expo-screen-time revival"
          // WindowManager caps an untouchable overlay at 0.8 anyway (and warns above it).
          alpha = 0.8f
        }
        manager.addView(view, params)
      } catch (e: Exception) {
        Log.w(TAG, "overlay window refused ($reason): ${e.javaClass.simpleName}: ${e.message}")
        done(false)
        return@post
      }
      val addedAt = SystemClock.elapsedRealtime()
      var finished = false
      fun finish(ok: Boolean, index: Int) {
        if (finished) return
        finished = true
        try {
          manager.removeViewImmediate(view)
        } catch (e: Exception) {
          // already gone
        }
        Log.i(TAG, "revival under the overlay exemption ($reason): ${if (ok) "started" else "refused"} after ${index + 1} attempt(s), ${SystemClock.elapsedRealtime() - addedAt} ms")
        done(ok)
      }
      ATTEMPTS_MS.forEachIndexed { index, delay ->
        handler.postDelayed({
          if (finished) return@postDelayed
          val ok = try {
            attempt(index)
          } catch (e: Exception) {
            Log.w(TAG, "revival attempt ${index + 1} failed ($reason): ${e.javaClass.simpleName}: ${e.message}")
            false
          }
          if (ok || index == ATTEMPTS_MS.lastIndex) finish(ok, index)
        }, delay)
      }
    }
  }

  /** A UI context for the default display, as `WindowManager.addView` wants on Android 11+. */
  private fun windowContext(app: Context): Context {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return app
    windowContext?.let { return it }
    val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return app
    return app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null).also { windowContext = it }
  }

  private const val TAG = EngineController.TAG
}
