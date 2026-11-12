package expo.modules.screentime

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility detector, the opt-in second detector of the blocking
 * engine. Declared in the
 * app manifest by the config plugin's `android.accessibilityEngine` option
 * (never in the library manifest: it changes a consumer's Google Play
 * review), in the engine's `:screentime` process, configured by
 * `res/xml/expo_st_accessibility_service.xml` (window-state events only, no
 * window content, `isAccessibilityTool="false"`). The system binds it once
 * the user enables it in Settings › Accessibility (the `accessibility`
 * permission step); nothing in the library starts it.
 *
 * What it adds: UsageStats reports the calling user only, so a blocked app
 * opened from a work profile, Private Space or a cloned copy was never seen;
 * the accessibility manager "treats calls from a profile
 * as if made by its parent", so this service hears every profile. It runs
 * **next to** the UsageStats engine, never instead of it: a
 * `TYPE_WINDOW_STATE_CHANGED` event is a hint "a window of package P came
 * forward" handed to [EngineLoop.hint] (same process, a direct call through
 * [hintListener], as the shield's `refreshListener`), which runs the same
 * policy, launches the same shield and polls once at the event instant.
 * No state of its own: the resumed and visible sets stay the tracker's.
 * When the service is off, crashed or blocked, the engine runs alone, and
 * the lost coverage is reported as an enforcement gap with
 * `scope: "otherProfiles"`.
 *
 * **Scope.** The engine narrows the packages the system reports
 * ([scope], `AccessibilityServiceInfo.packageNames`) to the apps the block
 * sources cover (`ShieldPolicy.covered`), the host package alone while
 * nothing is shielded: fewer events, and the declaration can say "limited
 * to the restricted apps". An empty list would mean every package to the
 * system, so the host stands in for "none". The scope is kept here and
 * applied at every connect, so a service bound after the engine applied
 * its sources starts narrowed.
 *
 * Its life is recorded in the state store ([StateStore.accessibilityBound]:
 * bound at, pid, unbound at; the twin of the engine's `engine.running`),
 * which is what the module reads to tell "enabled in Settings but not
 * running" (a Task Manager stop leaves it listed and crashed for minutes).
 * The package names it receives stay in this process: nothing of them
 * crosses to JS.
 */
class ScreenTimeAccessibilityService : AccessibilityService() {
  private val main = Handler(Looper.getMainLooper())

  override fun onServiceConnected() {
    super.onServiceConnected()
    current = this
    Log.i(EngineController.TAG, "accessibility service connected pid=${Process.myPid()}")
    applyScope(desiredScope ?: setOf(packageName))
    try {
      StateStore(this).accessibilityBound(Process.myPid())
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "accessibility bind not recorded: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
    val target = event.packageName?.toString()?.takeIf { it.isNotEmpty() } ?: return
    // The host's own windows (the shield, the app) are exempt; dropped here so the engine thread is never woken for them.
    if (target == packageName) return
    // `eventTime` is uptime at the source: the hint's age is measured against it.
    hintListener?.invoke(target, event.eventTime)
  }

  override fun onInterrupt() = Unit

  override fun onUnbind(intent: Intent?): Boolean {
    recordUnbound("unbind")
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    recordUnbound("destroy")
    super.onDestroy()
  }

  private fun recordUnbound(why: String) {
    if (current === this) current = null
    Log.i(EngineController.TAG, "accessibility service $why pid=${Process.myPid()}")
    try {
      StateStore(this).accessibilityUnbound()
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "accessibility unbind not recorded: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  /** `setServiceInfo` with the packages, on the main thread (the service's own thread). */
  private fun applyScope(packages: Set<String>) {
    main.post {
      if (current !== this) return@post
      try {
        val info = serviceInfo ?: return@post
        info.packageNames = packages.toTypedArray()
        serviceInfo = info
        Log.i(EngineController.TAG, "accessibility scope: ${packages.size} package(s)")
      } catch (e: Exception) {
        Log.w(EngineController.TAG, "accessibility scope not applied: ${e.javaClass.simpleName}: ${e.message}")
      }
    }
  }

  companion object {
    /** The bound instance of this process, or null: read by the engine (same process). */
    @Volatile
    var current: ScreenTimeAccessibilityService? = null
      private set

    /** The engine's hook for a window-state event: `(package, eventTime uptime ms)`, set while the loop runs. */
    @Volatile
    var hintListener: ((String, Long) -> Unit)? = null

    /** What the engine last asked for; applied at the next connect when no instance is bound. */
    @Volatile
    private var desiredScope: Set<String>? = null

    /**
     * Narrows the events the system delivers to [packages] (never empty: the
     * engine passes the host package when nothing is shielded). From any
     * thread; applied now when the service is bound, else at its next connect.
     */
    fun scope(packages: Set<String>) {
      if (packages.isEmpty()) return
      if (desiredScope == packages) return
      desiredScope = packages
      current?.applyScope(packages)
    }
  }
}
