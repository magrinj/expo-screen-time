package expo.modules.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log

/**
 * The Android half of the report data: what the iOS extension gets from
 * `DeviceActivityResults`, read here from `UsageStatsManager` and
 * `PackageManager` and handed to the pure [ReportAggregator].
 *
 * - **Events.** `queryEvents` from `window.start − LOOKBACK` to the end of
 *   the window or now, whichever is first, mapped to the engine's
 *   [UsageEvent] kinds (the same mapping as `EngineLoop.readUsage`, with
 *   the typed query on Android 15+ so the system filters the kinds). The
 *   stream is fed to the aggregator as it is read: a week is about 100k
 *   events, one pass, nothing kept.
 * - **Usage access.** Without it `queryEvents` answers an empty stream and
 *   the report renders zeros (what iOS renders without Screen Time
 *   authorization); the permission step is the host's to request
 *   (`usageAccess`, with the `usageReports` feature).
 * - **Categories**: `ApplicationInfo.category`
 *   with its localized title from `ApplicationInfo.getCategoryTitle`
 *   (API 26+: Game, Audio, Video, Image, Social, News, Maps, Productivity,
 *   Accessibility); `CATEGORY_UNDEFINED`, below API 26 and an uninstalled
 *   package resolve to null, which the aggregator files under "Other".
 *   Coarse by nature: developers declare it, most do not.
 * - **Labels and icons** from PackageManager, cached per package for the
 *   life of this source (one per view). The label falls back to the
 *   package name in the resolver; the icon to a placeholder in the renderer.
 * - **History start**: the first timestamp of the oldest daily bucket
 *   `queryUsageStats` still has, the closest public answer to "how far back
 *   the OS kept events" (about ten days); null when unknown.
 *
 * Everything here runs on the view's background thread (binder calls, icon
 * decoding); the caches are not shared across threads.
 */
internal class ReportUsageSource(private val context: Context) {
  private val usage: UsageStatsManager? = context.getSystemService(UsageStatsManager::class.java)
  private val pm: PackageManager = context.packageManager
  private val categoryCache = HashMap<String, String?>()
  private val labelCache = HashMap<String, String?>()
  private val iconCache = HashMap<String, Drawable?>()

  val categories = ReportCategoryResolver { packageName -> categoryCache.getOrPut(packageName) { categoryTitle(packageName) } }
  val labels = ReportLabels { packageName -> labelCache.getOrPut(packageName) { LaunchableApps.label(context, packageName) } }

  fun hasUsageAccess(): Boolean = EnforcementPermissions.hasUsageAccess(context)

  /** The data of [window] as of [nowMs]: the whole stream aggregated, zeros without usage access. */
  fun load(window: ReportWindow, nowMs: Long): ReportData {
    val filter = ReportAppFilter(launchers = SystemExemptions.launchers(context), hostPackage = context.packageName)
    val aggregator = ReportAggregator(window, filter, categories)
    val usage = usage ?: return aggregator.result(nowMs)
    if (!hasUsageAccess()) {
      Log.i(TAG, "usage report without usage access: rendering zeros (request the usageAccess permission step)")
      return aggregator.result(nowMs)
    }
    val end = minOf(window.endMs, nowMs)
    val begin = window.startMs - ReportAggregator.LOOKBACK_MS
    if (end > begin) {
      try {
        val events = query(usage, begin, end)
        if (events != null) {
          val event = UsageEvents.Event()
          while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = UsageEventKind.of(event.eventType)
            if (kind != UsageEventKind.OTHER) aggregator.add(UsageEvent(event.timeStamp, event.packageName, kind, event.className))
          }
        }
      } catch (e: Exception) {
        // A failed binder call (never seen) renders what was read so far rather than nothing.
        Log.w(TAG, "usage report: the event stream could not be read (${e.javaClass.simpleName}: ${e.message})")
      }
    }
    return aggregator.result(nowMs, historyStart(usage, nowMs))
  }

  /** The icon of an app with activity, decoded here so the renderer never touches PackageManager on the main thread. */
  fun icon(packageName: String): Drawable? = iconCache.getOrPut(packageName) { LaunchableApps.icon(context, packageName) }

  private fun query(usage: UsageStatsManager, begin: Long, end: Long): UsageEvents? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      val query = UsageEventsQuery.Builder(begin, end)
        .setEventTypes(
          UsageEvents.Event.ACTIVITY_RESUMED,
          UsageEvents.Event.ACTIVITY_PAUSED,
          UsageEvents.Event.ACTIVITY_STOPPED,
          UsageEvents.Event.SCREEN_INTERACTIVE,
          UsageEvents.Event.SCREEN_NON_INTERACTIVE,
          UsageEvents.Event.KEYGUARD_SHOWN,
          UsageEvents.Event.KEYGUARD_HIDDEN,
          UsageEvents.Event.DEVICE_SHUTDOWN,
          UsageEvents.Event.DEVICE_STARTUP,
        )
        .build()
      return usage.queryEvents(query)
    }
    @Suppress("DEPRECATION")
    return usage.queryEvents(begin, end)
  }

  private fun historyStart(usage: UsageStatsManager, nowMs: Long): Long? = try {
    usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0, nowMs)
      ?.minOfOrNull { it.firstTimeStamp }
      ?.takeIf { it > 0 }
  } catch (e: Exception) {
    null
  }

  private fun categoryTitle(packageName: String): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val info = try {
      pm.getApplicationInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
      return null
    }
    if (info.category == ApplicationInfo.CATEGORY_UNDEFINED) return null
    return ApplicationInfo.getCategoryTitle(context, info.category)?.toString()
  }

  private companion object {
    const val TAG = "ExpoScreenTime"
  }
}
