package expo.modules.screentime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import java.util.concurrent.Executors

/**
 * `<TokenLabel>` on Android: the twin of `ios/ExpoScreenTimeTokenLabelView.swift`.
 *
 * iOS renders `Label(token)`; Android resolves the handle to its package
 * through the [SelectionStore] (inside `selectionId` when given, else the
 * handle map) and draws the app's icon and label from PackageManager. The
 * handle stays opaque: the package name is read here and never leaves.
 *
 * What it shows:
 * - a resolved, installed app: its icon and/or name, per `displayMode`;
 * - an app that was selected and uninstalled since: a generic icon and
 *   "Unavailable app" (its name is gone with it);
 * - a handle this install does not know, or one of another `tokenType`:
 *   the iOS placeholder text ("No app token"), never a crash. Category and
 *   web-domain handles do not exist on Android (`selectableKinds`), so those
 *   types always show their placeholder.
 *
 * The props are plain fields; the module's `OnViewDidUpdateProps` calls
 * [render] once per batch. The store read (a file under the cross-process
 * lock) and the PackageManager calls run on one background thread shared
 * by every label; a result for props that changed since is dropped.
 */
class ExpoScreenTimeTokenLabelView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  override val shouldUseAndroidLayout = true

  var tokenType: String? = null
  var displayMode: String? = null
  var selectionId: String? = null
  var handle: String? = null

  private val icon = ImageView(context)
  private val title = TextView(context)
  private var palette = ViewPalette.of(context)
  private val main = Handler(Looper.getMainLooper())
  private var generation = 0

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    icon.scaleType = ImageView.ScaleType.FIT_CENTER
    addView(icon, LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)))
    title.textSize = 16f
    title.maxLines = 1
    title.ellipsize = TextUtils.TruncateAt.END
    addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    placeholder(PLACEHOLDERS.getValue(TokenHandles.APPLICATION), palette.secondary)
  }

  override fun onConfigurationChanged(newConfig: Configuration?) {
    super.onConfigurationChanged(newConfig)
    palette = ViewPalette.of(context)
    render()
  }

  /** What the label draws, resolved off the main thread. */
  private sealed class Content {
    data class Placeholder(val text: String, val error: Boolean = false) : Content()
    data class App(val label: String, val icon: Drawable?, val unavailable: Boolean) : Content()
  }

  fun render() {
    val type = tokenType ?: TokenHandles.APPLICATION
    val handle = handle
    val selectionId = selectionId
    val mode = displayMode ?: "title-and-icon"
    val ticket = ++generation
    io.execute {
      val content = try {
        resolve(type, handle, selectionId)
      } catch (e: Exception) {
        Log.w(TAG, "Cannot resolve a token handle", e)
        Content.Placeholder(PLACEHOLDERS[type] ?: "Unknown type: $type", error = type !in PLACEHOLDERS)
      }
      main.post {
        if (ticket != generation) return@post
        show(content, mode)
      }
    }
  }

  private fun resolve(type: String, handle: String?, selectionId: String?): Content {
    if (type !in PLACEHOLDERS) return Content.Placeholder("Unknown type: $type", error = true)
    val target = handle?.let { store.resolve(it, selectionId) }
    if (target == null || target.first != type || type != TokenHandles.APPLICATION) {
      return Content.Placeholder(PLACEHOLDERS.getValue(type))
    }
    val packageName = target.second
    val label = LaunchableApps.label(context, packageName)
      ?: return Content.App(context.getString(R.string.expo_st_label_unavailable), null, unavailable = true)
    return Content.App(label, LaunchableApps.icon(context, packageName), unavailable = false)
  }

  private fun show(content: Content, mode: String) {
    when (content) {
      is Content.Placeholder -> placeholder(content.text, if (content.error) Color.RED else palette.secondary)
      is Content.App -> app(
        content.label,
        if (content.unavailable) palette.secondary else palette.text,
        content.icon ?: context.packageManager.defaultActivityIcon,
        mode,
      )
    }
  }

  private fun placeholder(text: String, color: Int) {
    icon.visibility = GONE
    title.visibility = VISIBLE
    title.text = text
    title.setTextColor(color)
    contentDescription = text
    requestLayout()
  }

  private fun app(label: String, color: Int, drawable: Drawable, mode: String) {
    val iconOnly = mode == "icon-only"
    val titleOnly = mode == "title-only"
    icon.visibility = if (titleOnly) GONE else VISIBLE
    icon.setImageDrawable(drawable)
    icon.layoutParams = if (iconOnly) {
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    } else {
      LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP))
    }
    title.visibility = if (iconOnly) GONE else VISIBLE
    title.text = label
    title.setTextColor(color)
    (title.layoutParams as LinearLayout.LayoutParams).marginStart = if (titleOnly) 0 else context.dp(8)
    // The app's name is the label's accessible name in every mode, as `Label(token)` reads on iOS.
    contentDescription = label
    requestLayout()
  }

  companion object {
    private const val TAG = "ExpoScreenTime"
    private const val ICON_DP = 28
    private val PLACEHOLDERS = mapOf(
      TokenHandles.APPLICATION to "No app token",
      TokenHandles.CATEGORY to "No category token",
      TokenHandles.WEB_DOMAIN to "No domain token",
    )

    /** One thread for every label's store and PackageManager reads. */
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "expo-screen-time-labels") }
  }

  /** Built on the label thread (its constructor reads the install secret once per process). */
  private val store by lazy { SelectionStore(StateStore(context)) }
}
