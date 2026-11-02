package expo.modules.screentime

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.util.concurrent.Executors

/**
 * `<ActivityPicker>` on Android: the twin of `ios/ExpoScreenTimeActivityPickerView.swift`.
 *
 * iOS embeds the system `FamilyActivityPicker`; Android has no system
 * picker, so the library draws one: a searchable, multi-select list of the
 * launchable apps ([LaunchableApps]) with their icons, on its own background
 * in the system's light or dark scheme (as the iOS picker is on
 * `.systemBackground`, whatever the RN style behind it). Apps only in v1
 * (`selectableKinds`): no categories, no web domains. Classic views rather
 * than Compose, as Expo's own native views (expo-video, expo-camera): no
 * extra dependency for a consumer that does not use Compose, and `ListView`
 * recycles its rows for free.
 *
 * Same contract as iOS: mounting with a `selectionId` loads (and creates,
 * when missing) the selection and emits `onSelectionChange` with it, as the
 * iOS model's assignment does; every tap writes one app into the
 * [SelectionStore] and emits the counts and the opaque handles; `onOpen` /
 * `onClose` follow the view's attachment. Package names never leave the
 * native side.
 *
 * Threads: the store runs on one background thread shared by every picker
 * (writes stay in tap order); icons load on another, so a long icon pass
 * never delays the selection; the widgets and the checked set live on the
 * main thread, and a tap before the selection has loaded is ignored rather
 * than saved over it.
 */
class ExpoScreenTimeActivityPickerView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  override val shouldUseAndroidLayout = true

  private val onSelectionChange by EventDispatcher()
  private val onOpen by EventDispatcher<Unit>()
  private val onClose by EventDispatcher<Unit>()

  private val main = Handler(Looper.getMainLooper())

  private var selectionId: String? = null
  private var selectionLoaded = false
  private var apps: List<LaunchableApp> = emptyList()
  private var visible: List<LaunchableApp> = emptyList()
  private var loaded = false
  private val selected = LinkedHashSet<String>()
  private val icons = HashMap<String, Drawable?>()
  private var palette = ViewPalette.of(context)

  /** Set on detach: the icon pass stops (rows load the rest on demand). */
  @Volatile
  private var detached = false

  /** Painted in the scheme's background, as the iOS hosting view paints `.systemBackground` over the RN background. */
  private val content = LinearLayout(context)
  private val header = TextView(context)
  private val search = EditText(context)
  private val list = ListView(context)
  private val empty = TextView(context)
  private val footer = TextView(context)
  private val adapter = Adapter()

  init {
    orientation = VERTICAL
    content.orientation = VERTICAL
    addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    header.setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(4))
    header.textSize = 14f
    header.visibility = GONE
    content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    search.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    search.isSingleLine = true
    search.textSize = 16f
    search.setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
    search.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) = refresh()
    })
    content.addView(
      search,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
      }
    )

    list.adapter = adapter
    list.divider = null
    list.dividerHeight = 0
    list.isNestedScrollingEnabled = true
    list.setOnItemClickListener { _, row, position, _ ->
      val app = visible.getOrNull(position) ?: return@setOnItemClickListener
      val checked = toggle(app.packageName) ?: return@setOnItemClickListener
      // Before the click's accessibility event: TalkBack announces the new state.
      (row as? Row)?.setChecked(checked)
    }
    empty.gravity = Gravity.CENTER
    empty.textSize = 14f
    empty.setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
    empty.visibility = GONE
    val body = FrameLayout(context)
    body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    content.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    footer.setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(12))
    footer.textSize = 12f
    footer.visibility = GONE
    content.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    applyPalette()

    iconIo.execute {
      val found = try {
        LaunchableApps.query(context)
      } catch (e: Exception) {
        Log.w(TAG, "Cannot list the launchable apps", e)
        emptyList()
      }
      main.post {
        apps = found
        loaded = true
        refresh()
      }
      // Icons after the list is up, a few milliseconds each; a detached
      // picker stops (rows load the rest on demand).
      for (app in found) {
        if (detached) break
        val icon = try {
          LaunchableApps.icon(context, app.packageName)
        } catch (e: Exception) {
          null
        }
        synchronized(icons) { icons[app.packageName] = icon }
      }
      main.post { adapter.notifyDataSetChanged() }
    }
  }

  // MARK: - Props

  fun setSelectionId(id: String?) {
    selectionId = id
    selectionLoaded = false
    selected.clear()
    adapter.notifyDataSetChanged()
    if (id == null) return
    storeIo.execute {
      val stored = try {
        store().load(id)
      } catch (e: Exception) {
        Log.w(TAG, "Cannot load selection '$id'", e)
        return@execute
      }
      val payload = payload(stored)
      main.post {
        if (selectionId != id) return@post
        selected.clear()
        selected.addAll(stored.applications)
        selectionLoaded = true
        adapter.notifyDataSetChanged()
        onSelectionChange(payload)
      }
    }
  }

  fun setHeaderText(text: String?) {
    header.text = text
    header.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
  }

  fun setFooterText(text: String?) {
    footer.text = text
    footer.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
  }

  // MARK: - Lifecycle

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    detached = false
    onOpen(Unit)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    detached = true
    onClose(Unit)
  }

  override fun onConfigurationChanged(newConfig: Configuration?) {
    super.onConfigurationChanged(newConfig)
    palette = ViewPalette.of(context)
    applyPalette()
    refresh()
  }

  // MARK: - Selection

  /** Flips [packageName]; the new state, or null when the tap is ignored (no selection, or not loaded yet). */
  private fun toggle(packageName: String): Boolean? {
    val id = selectionId ?: return null
    if (!selectionLoaded) return null
    val checked = !selected.remove(packageName)
    if (checked) selected.add(packageName)
    adapter.notifyDataSetChanged()
    storeIo.execute {
      val result = try {
        store().toggleApplication(id, packageName, checked)
      } catch (e: Exception) {
        Log.w(TAG, "Cannot save selection '$id'", e)
        return@execute
      }
      val payload = payload(result)
      main.post {
        if (selectionId != id) return@post
        // The store is the truth: a concurrent writer's apps show up here.
        selected.clear()
        selected.addAll(result.applications)
        adapter.notifyDataSetChanged()
        onSelectionChange(payload)
      }
    }
    return checked
  }

  private fun payload(selection: Selection): Map<String, Any> =
    mapOf("metadata" to selection.metadata(), "tokens" to store().tokens(selection))

  private fun refresh() {
    val query = search.text?.toString()?.trim().orEmpty()
    visible = if (query.isEmpty()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    adapter.notifyDataSetChanged()
    empty.text = context.getString(if (apps.isEmpty()) R.string.expo_st_picker_empty else R.string.expo_st_picker_no_match)
    empty.visibility = if (loaded && visible.isEmpty()) VISIBLE else GONE
  }

  private fun applyPalette() {
    content.setBackgroundColor(palette.background)
    header.setTextColor(palette.secondary)
    footer.setTextColor(palette.secondary)
    empty.setTextColor(palette.secondary)
    search.hint = context.getString(R.string.expo_st_picker_search_hint)
    search.setTextColor(palette.text)
    search.setHintTextColor(palette.secondary)
    search.background = GradientDrawable().apply {
      cornerRadius = context.dp(12).toFloat()
      setColor(palette.field)
    }
  }

  private fun icon(packageName: String): Drawable? {
    synchronized(icons) { if (icons.containsKey(packageName)) return icons[packageName] }
    val loaded = try {
      LaunchableApps.icon(context, packageName)
    } catch (e: Exception) {
      null
    }
    synchronized(icons) { icons[packageName] = loaded }
    return loaded
  }

  /** Built on the store thread (its constructor reads the install secret once per process). */
  private val store by lazy { SelectionStore(StateStore(context)) }

  private fun store() = store

  private inner class Adapter : BaseAdapter() {
    override fun getCount() = visible.size
    override fun getItem(position: Int) = visible[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      val row = convertView as? Row ?: Row(context)
      val app = visible[position]
      row.bind(app, selected.contains(app.packageName), icon(app.packageName), palette)
      return row
    }
  }

  /** One app: icon, label, check. The row is the accessibility node (a checkbox); the `CheckBox` only draws. */
  private class Row(context: Context) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label = TextView(context)
    private val check = CheckBox(context)
    private var checked = false

    init {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      minimumHeight = context.dp(56)
      setPadding(context.dp(16), context.dp(8), context.dp(8), context.dp(8))
      icon.scaleType = ImageView.ScaleType.FIT_CENTER
      addView(icon, LayoutParams(context.dp(40), context.dp(40)))
      label.textSize = 16f
      label.maxLines = 1
      label.ellipsize = android.text.TextUtils.TruncateAt.END
      addView(label, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(16) })
      check.isClickable = false
      check.isFocusable = false
      check.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
      addView(check, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun bind(app: LaunchableApp, isChecked: Boolean, drawable: Drawable?, palette: ViewPalette) {
      label.text = app.label
      label.setTextColor(palette.text)
      icon.setImageDrawable(drawable ?: context.packageManager.defaultActivityIcon)
      contentDescription = app.label
      setChecked(isChecked)
    }

    fun setChecked(isChecked: Boolean) {
      checked = isChecked
      check.isChecked = isChecked
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
      super.onInitializeAccessibilityNodeInfo(info)
      info.className = CheckBox::class.java.name
      info.isCheckable = true
      info.isChecked = checked
    }
  }

  companion object {
    private const val TAG = "ExpoScreenTime"

    /** One thread for every picker's store work: loads and saves stay in order. */
    private val storeIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "expo-screen-time-picker-store") }

    /** PackageManager listing and icons, never in front of a store operation. */
    private val iconIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "expo-screen-time-picker-icons") }
  }
}
