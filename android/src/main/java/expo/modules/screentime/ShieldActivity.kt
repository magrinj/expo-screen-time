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
 *   blocked app's own label and icon, as the default iOS shield does.
 * - **Close** (the primary button, the secondary button and Back, until
 *   step 5 maps the configured actions): send the user Home first, then
 *   finish, so the blocked app underneath never resumes. Leaving the shield
 *   any other way (Home, the screen going off) finishes it too: the next
 *   detection starts a fresh one.
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
    val backgroundColor = appearance.backgroundColor ?: palette.background
    window.setBackgroundDrawable(ColorDrawable(backgroundColor))
    WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = isLight(backgroundColor)
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
          if (id != 0) getDrawable(id)?.also { imageTintList = ColorStateList.valueOf(appearance.title?.color ?: palette.text) } else null
        }
        ShieldAppearance.Icon.App -> null
      } ?: LaunchableApps.icon(this@ShieldActivity, target)
      setImageDrawable(drawable)
      layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(24) }
    }
    val title = TextView(this).apply {
      text = appearance.title?.text ?: LaunchableApps.label(this@ShieldActivity, target) ?: getString(R.string.expo_st_shield_title)
      setTextColor(appearance.title?.color ?: palette.text)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
    }
    val subtitle = TextView(this).apply {
      text = appearance.subtitle?.text ?: getString(R.string.expo_st_shield_subtitle)
      setTextColor(appearance.subtitle?.color ?: palette.secondary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      gravity = Gravity.CENTER
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    val primary = TextView(this).apply {
      text = appearance.primaryButton?.text ?: getString(R.string.expo_st_shield_close)
      setTextColor(appearance.primaryButton?.color ?: 0xFFFFFFFF.toInt())
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
        setTextColor(label.color ?: palette.accent)
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

  /**
   * A button press. Step 5 maps the configured `ShieldAction` (unblock,
   * defer, snooze, openApp) and records the interaction; until then every
   * press is the iOS default, `close`.
   */
  private fun onButton(button: String) {
    close(button)
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

  private fun isLight(color: Int): Boolean {
    val r = (color shr 16 and 0xFF) / 255.0
    val g = (color shr 8 and 0xFF) / 255.0
    val b = (color and 0xFF) / 255.0
    return 0.299 * r + 0.587 * g + 0.114 * b > 0.6
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
