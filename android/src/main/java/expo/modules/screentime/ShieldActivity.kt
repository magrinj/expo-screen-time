package expo.modules.screentime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
 *   the iOS precedence ([StateStore.shieldConfig]); what the config leaves
 *   open follows the system light/dark setting ([ViewPalette]) and the
 *   blocked app's own label and icon, as the default iOS shield does —
 *   except the text colours over a configured background, chosen for
 *   contrast against it ([ShieldTextColors], the same rule as iOS).
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    target = intent.getStringExtra(EXTRA_TARGET) ?: run {
      finish()
      return
    }
    root = FrameLayout(this)
    setContentView(root)
    render()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close("back") }
    }
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
    val appearance = ShieldAppearance.from(StateStore(this).shieldConfig(intent.getStringExtra(EXTRA_OWNER)))
    val palette = ViewPalette.of(this)
    // The text colours the config leaves open: for contrast against a configured
    // background, else null and the system-adaptive palette below.
    val colors = ShieldTextColors.resolve(appearance)
    val backgroundColor = appearance.backgroundColor ?: palette.background
    window.setBackgroundDrawable(ColorDrawable(backgroundColor))
    WindowCompat.getInsetsController(window, window.decorView).apply {
      // Dark system-bar icons where dark text reads: the same contrast rule as the title.
      isAppearanceLightStatusBars = ShieldTextColors.onBackground(backgroundColor) == ShieldTextColors.BLACK
      isAppearanceLightNavigationBars = isAppearanceLightStatusBars
    }

    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(dp(32), dp(48), dp(32), dp(48))
      fitsSystemWindows = true
    }
    val icon = ImageView(this).apply {
      val drawable = when (val spec = appearance.icon) {
        is ShieldAppearance.Icon.Symbol -> {
          val id = resources.getIdentifier(spec.resource, "drawable", packageName)
          if (id != 0) getDrawable(id)?.also { imageTintList = ColorStateList.valueOf(colors.title ?: palette.text) } else null
        }
        ShieldAppearance.Icon.App -> null
      } ?: LaunchableApps.icon(this@ShieldActivity, target)
      setImageDrawable(drawable)
      layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(24) }
    }
    val title = TextView(this).apply {
      text = appearance.title?.text ?: LaunchableApps.label(this@ShieldActivity, target) ?: getString(R.string.expo_st_shield_title)
      setTextColor(colors.title ?: palette.text)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
    }
    val subtitle = TextView(this).apply {
      text = appearance.subtitle?.text ?: getString(R.string.expo_st_shield_subtitle)
      setTextColor(colors.subtitle ?: palette.secondary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      gravity = Gravity.CENTER
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    val primary = TextView(this).apply {
      text = appearance.primaryButton?.text ?: getString(R.string.expo_st_shield_close)
      setTextColor(colors.primaryButton ?: ShieldTextColors.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      minHeight = dp(48)
      setPadding(dp(24), dp(12), dp(24), dp(12))
      background = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(appearance.primaryButtonBackgroundColor ?: palette.accent)
      }
      isClickable = true
      isFocusable = true
      contentDescription = text
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(40) }
      setOnClickListener { onButton("primary") }
    }
    column.addView(icon)
    column.addView(title)
    column.addView(subtitle)
    column.addView(primary)
    appearance.secondaryButton?.let { label ->
      column.addView(TextView(this).apply {
        text = label.text
        setTextColor(colors.secondaryButton ?: palette.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onButton("secondary") }
      })
    }
    root.removeAllViews()
    root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
