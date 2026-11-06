package expo.modules.screentime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import androidx.core.view.WindowCompat
import java.lang.ref.WeakReference
import java.util.TimeZone
import java.util.UUID

/**
 * The shield: the Android counterpart of the iOS ShieldConfiguration
 * extension, drawn by the library over the blocked app
 * ("Shield"; measured: an Activity, because
 * overlays are hidden by any app with `HIDE_OVERLAY_WINDOWS`).
 *
 * - **Launched by the engine** with `FLAG_ACTIVITY_NEW_TASK` from the
 *   `:screentime` process, through the background-activity-launch exemption
 *   the overlay permission grants (`BAL_ALLOW_SAW_PERMISSION`, §10.1 A). It
 *   runs in that process too: it is already alive (the engine), so the first
 *   frame is not paid for by starting the React Native process, and the
 *   shield does not die with it.
 * - **Not in Recents, its own task** (`excludeFromRecents`, `singleTask`,
 *   `taskAffinity`), **no animation** (theme and flag), and **no starting
 *   window** (`windowDisablePreview`): the first frame the user sees is the
 *   shield itself, in the configured colour, instead of a system splash in
 *   a colour the theme fixed before the config was known.
 * - **Appearance** from the stored `ShieldConfig` ([ShieldAppearance]) with
 *   the iOS precedence ([ShieldConfigs], read through [ShieldCache]: the
 *   engine keeps it and the blocked apps' labels and icons in memory and
 *   pre-warms the view tree ([ShieldWarmer]), so `onCreate` reads no file
 *   and opens no APK on the way to the first frame); what the config leaves
 *   open follows the system light/dark setting ([ViewPalette]) and the
 *   blocked app's own label and icon, as the default iOS shield does —
 *   except the text colours over a configured background, chosen for
 *   contrast against it ([ShieldTextColors], the same rule as iOS), and
 *   the primary button's label, chosen the same way against the colour the
 *   button is painted in, the default accent included.
 * - **Buttons**: every press is recorded for
 *   `drainShieldInteractions` ([ShieldInteractionQueue]: the pressed app's
 *   handle and the owning activity) and does what `setShieldActions` /
 *   `setShieldActionsForActivity` configured for the owner ([ShieldAction],
 *   the iOS precedence), the iOS default `close` otherwise. `close` and Back
 *   send the user Home first, then finish, so the blocked app underneath
 *   never resumes; `unblock` records the lift ([ShieldLifts]) and `snooze`
 *   grants a [ShieldSnooze], then the shield finishes and the user is in
 *   the app they asked for (the iOS `.defer` outcome of a snooze; on iOS an
 *   `unblock` closes the app — a platform constraint, documented);
 *   `defer` keeps the shield up; `openApp` launches the host app directly
 *   (`shieldOpenApp: "direct"`). The record and the grant are one
 *   read-decide-write under the state store's lock, and the engine (same
 *   process) is told at once, before the shield goes, so its next poll
 *   never re-shields what was just lifted. Leaving the shield any other
 *   way (Home, the screen going off) finishes it too: the next detection
 *   starts a fresh one.
 * - **Split-screen.** Stock Android gives a background-started task no way
 *   into one pane of a split (verified on Android 17: the shield takes the
 *   whole screen, as device tests measured on the Pixel). What the library can
 *   do, it does: `FLAG_ACTIVITY_LAUNCH_ADJACENT` keeps the split pair alive
 *   underneath instead of dismissing it, so the innocent app is not closed
 *   and comes back with the pair from Recents (the blocked app with it,
 *   shielded again). The shield is resizeable in case a future release
 *   places it.
 */
class ShieldActivity : Activity() {
  private var target: String = ""
  private lateinit var root: FrameLayout
  /** Uptime at which this instance was constructed (the main thread reached the launch). */
  private val constructedAt = SystemClock.uptimeMillis()
  /** The phases of the last `render`, in ms, for the `shield-created` log line. */
  private var phases = ""

  init {
    Log.i(EngineController.TAG, "shield-construct at=${System.currentTimeMillis()}")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    val started = SystemClock.uptimeMillis()
    super.onCreate(savedInstanceState)
    val superMs = SystemClock.uptimeMillis() - started
    target = intent.getStringExtra(EXTRA_TARGET) ?: run {
      finish()
      return
    }
    val contentStarted = SystemClock.uptimeMillis()
    root = FrameLayout(this)
    setContentView(root)
    val contentMs = SystemClock.uptimeMillis() - contentStarted
    render()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close("back") }
    }
    // One line per shield: where the time before the first frame goes.
    Log.i(EngineController.TAG, "shield-created target=$target totalMs=${SystemClock.uptimeMillis() - started} preCreateMs=${started - constructedAt} superMs=$superMs contentMs=$contentMs $phases")
    root.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
      override fun onDraw() {
        Log.i(EngineController.TAG, "shield-drawn target=$target at=${System.currentTimeMillis()}")
        root.post { root.viewTreeObserver.removeOnDrawListener(this) }
      }
    })
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val next = intent.getStringExtra(EXTRA_TARGET) ?: return
    if (next != target) {
      target = next
      render()
    }
  }

  override fun onResume() {
    super.onResume()
    current = WeakReference(this)
    showing = target
    Log.i(EngineController.TAG, "shield-resumed target=$target at=${System.currentTimeMillis()} multiWindow=${isInMultiWindowMode}")
    shownListener?.invoke(target)
  }

  override fun onPause() {
    super.onPause()
    if (showing == target) showing = null
  }

  override fun onStop() {
    super.onStop()
    // Left by any route other than close() (Home, screen off, another app
    // on top): a fresh shield for the next detection, never a stale one.
    if (!isFinishing && !isChangingConfigurations) finish()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    // Reached when the app has not opted into predictive back (< 33, or
    // enableOnBackInvokedCallback false); the dispatcher callback otherwise.
    close("back")
  }

  // MARK: - Rendering

  private fun render() {
    var mark = SystemClock.uptimeMillis()
    fun lap(): Long { val now = SystemClock.uptimeMillis(); val d = now - mark; mark = now; return d }
    // From the cache the engine keeps current (one stat to check it): no file read on the way to the first frame.
    val appearance = ShieldCache.appearance(StateStore(this), intent.getStringExtra(EXTRA_OWNER))
    val storeMs = lap()
    val colors = ShieldLayout.colors(this, appearance)
    window.setBackgroundDrawable(ColorDrawable(colors.background))
    WindowCompat.getInsetsController(window, window.decorView).apply {
      // Dark system-bar icons where dark text reads: the same contrast rule as the title.
      isAppearanceLightStatusBars = ShieldTextColors.onBackground(colors.background) == ShieldTextColors.BLACK
      isAppearanceLightNavigationBars = isAppearanceLightStatusBars
    }
    val windowMs = lap()
    // Loaded by the warmer for the apps the sources shield; loaded now for any other.
    val face = ShieldCache.face(this, target)
    val faceMs = lap()
    val column = ShieldLayout.build(this, appearance, colors, face, onPrimary = { onButton("primary") }, onSecondary = { onButton("secondary") })
    root.removeAllViews()
    root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    phases = "storeMs=$storeMs windowMs=$windowMs faceMs=$faceMs viewsMs=${lap()}"
  }

  // MARK: - Actions

  /** A button press: recorded, then the configured action (see the class comment). */
  private fun onButton(button: String) {
    val owner = intent.getStringExtra(EXTRA_OWNER)
    val action = try {
      record(button, owner)
    } catch (e: Exception) {
      // A document that cannot be written right now: the press is lost, the
      // shield behaves like the default. Never a crash in front of the user.
      Log.w(EngineController.TAG, "shield-press target=$target button=$button failed: ${e.javaClass.simpleName}: ${e.message}")
      ShieldAction.Close
    }
    Log.i(EngineController.TAG, "shield-press target=$target button=$button owner=$owner action=$action")
    when (action) {
      ShieldAction.Close -> close(button)
      ShieldAction.Defer -> Unit
      ShieldAction.Unblock -> lifted("unblock")
      is ShieldAction.Snooze -> lifted("snooze")
      ShieldAction.OpenApp -> openHostApp()
    }
  }

  /**
   * Records the press and applies the configured action's state change in
   * one critical section: the interaction (with the app's handle, derived
   * from the install secret as `getTokens` derives it, and the owning
   * activity), a lift for `unblock`, a snooze grant for `snooze` (replacing
   * one over the same app; the others run on). Returns the action to act on.
   */
  private fun record(button: String, owner: String?): ShieldAction {
    val store = StateStore(this)
    // Resolved before the critical section (a first draw writes the document).
    val selections = SelectionStore(store)
    return store.update { document ->
      val now = System.currentTimeMillis()
      // The handle `getTokens` derives for this app, made resolvable even
      // for an app no selection holds (shielded by an allowlist), so the
      // interaction's target can be passed back to temporaryUnlock.
      val handle = ShieldInteractionQueue.Target(TokenHandles.APPLICATION, selections.rememberToken(document, TokenHandles.APPLICATION, target))
      ShieldInteractionQueue.record(document, ShieldInteractionQueue.Entry.press(button, now, handle, owner))
      when (val action = ShieldAction.configured(store.shieldActions(document, owner), button) ?: ShieldAction.Close) {
        ShieldAction.Unblock -> {
          // Recorded against the composition on screen: the lift lasts while these sources do.
          ShieldLifts.write(document, ShieldLifts.read(document).plus(sourcesKey, target))
          action
        }
        is ShieldAction.Snooze -> {
          val snooze = ShieldSnooze.grant(UUID.randomUUID().toString(), TokenHandles.APPLICATION, target, action.seconds, now) ?: return@update ShieldAction.Close
          val current = ShieldSnoozes.current(ShieldSnoozes.read(document), now, TimeZone.getDefault())
          ShieldSnoozes.write(document, ShieldSnoozes.granting(snooze, current).snoozes)
          action
        }
        else -> action
      }
    }
  }

  /**
   * The target was lifted (an unblock, a snooze): the engine re-reads the
   * document now — in this process, on its own thread, ahead of its next
   * poll — then the shield finishes and the app underneath resumes.
   */
  private fun lifted(route: String) {
    refreshListener?.invoke("shield:$route") ?: EngineController.sync(this, "shield:$route")
    Log.i(EngineController.TAG, "shield-lifted target=$target route=$route")
    finishAndRemoveTask()
  }

  /** `openApp`, direct on Android: the host app comes forward over the blocked app and the shield goes. */
  private fun openHostApp() {
    val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (launch == null) {
      close("openApp")
      return
    }
    try {
      startActivity(launch)
      Log.i(EngineController.TAG, "shield-open-app target=$target")
      finishAndRemoveTask()
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "host app launch failed: ${e.javaClass.simpleName}: ${e.message}")
      close("openApp")
    }
  }

  /** Home first, then finish: the blocked app underneath must not resume. */
  private fun close(route: String) {
    Log.i(EngineController.TAG, "shield-close target=$target route=$route")
    try {
      startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "home intent failed: ${e.javaClass.simpleName}: ${e.message}")
    }
    finishAndRemoveTask()
  }

  companion object {
    const val EXTRA_TARGET = "target"
    const val EXTRA_OWNER = "owner"

    @Volatile
    private var current: WeakReference<ShieldActivity>? = null

    /** The target of the shield on screen (resumed), or null. Read by the engine thread. */
    @Volatile
    var showing: String? = null
      private set

    /** The engine's hook for the follow-up check, set while the loop runs. */
    @Volatile
    var shownListener: ((String) -> Unit)? = null

    /** The engine's hook for "look again" after a press changed the document (same process), set while the loop runs. */
    @Volatile
    var refreshListener: ((String) -> Unit)? = null

    /** The fingerprint of the sources the engine last applied (`BlockComposition.fingerprint`): what an `unblock` is recorded under. */
    @Volatile
    var sourcesKey: String = ""

    /** Starts the shield for [target]; a refusal (no overlay permission) is logged, never thrown. */
    fun launch(context: Context, target: String, owner: String?) {
      val intent = Intent(context, ShieldActivity::class.java)
        // LAUNCH_ADJACENT has no source activity here, so it does not place
        // the shield in a pane, but measured on Android 17 it keeps a split
        // pair alive behind the shield where a plain NEW_TASK dismissed it.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        .putExtra(EXTRA_TARGET, target)
        .putExtra(EXTRA_OWNER, owner)
      try {
        context.startActivity(intent)
        Log.i(EngineController.TAG, "shield-launch target=$target at=${System.currentTimeMillis()}")
      } catch (e: Exception) {
        Log.w(EngineController.TAG, "shield-launch failed target=$target: ${e.javaClass.simpleName}: ${e.message}")
        StateStore(context).recordEngineFailure("shield:${e.javaClass.simpleName}", e.message)
      }
    }

    /** The block was lifted: a shield on screen goes away (the app under it is allowed now). */
    fun dismiss() {
      val activity = current?.get() ?: return
      activity.runOnUiThread {
        if (!activity.isFinishing) {
          Log.i(EngineController.TAG, "shield-dismissed target=${activity.target}")
          activity.finishAndRemoveTask()
        }
      }
    }
  }
}
