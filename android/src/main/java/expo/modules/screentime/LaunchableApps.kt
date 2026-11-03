package expo.modules.screentime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.text.Collator

/** One app the picker offers: its package name stays native; the label is what the user sees. */
internal data class LaunchableApp(val packageName: String, val label: String)

/**
 * The apps a user can pick: those with a `MAIN`/`LAUNCHER` activity, which
 * the library manifest's `<queries>` intent makes visible on Android 11+
 * without `QUERY_ALL_PACKAGES` (Play restricts it; the launcher intent is
 * enough for a blocker). Launcher apps of the main profile
 * only: work-profile and Private Space apps are not listed in v1.
 *
 * The host app is left out: blocking the app that holds the blocker's
 * controls would lock the user out of lifting the block, and iOS cannot
 * shield the host either (`ActivityCategoryPolicy.all(except:)` never does).
 * The picker goes further ([selectable]): it leaves out every app the engine
 * exempts ([SystemExemptions]: the launchers, the dialer, the emergency app,
 * the keyboard, Settings, SystemUI), so it never offers an app a block would
 * silently skip. The engine's own universe ([packages]) keeps them: there the
 * exemptions are applied by [ShieldPolicy] itself.
 */
internal object LaunchableApps {
  /**
   * The apps the picker offers: [apps] minus the [exempt] ones, in order. One
   * source of truth for "never blocked": the exemption set the engine reads,
   * never a second list. A stored selection that already holds an exempt
   * package (picked before this rule, or imported from a device where that
   * package is an ordinary app) is left as it is: the picker has no row for
   * it, the engine ignores it, `getTokens` still lists it and `removeToken`
   * drops it — pruning on load would silently alter an imported selection,
   * and the set is device-specific.
   */
  fun selectable(apps: List<LaunchableApp>, exempt: Set<String>): List<LaunchableApp> =
    if (exempt.isEmpty()) apps else apps.filter { it.packageName !in exempt }

  fun query(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
      @Suppress("DEPRECATION")
      pm.queryIntentActivities(intent, 0)
    }
    val host = context.packageName
    val collator = Collator.getInstance()
    return activities.asSequence()
      .map { it.activityInfo.applicationInfo }
      .filter { it.packageName != host }
      .distinctBy { it.packageName }
      .map { LaunchableApp(it.packageName, it.loadLabel(pm).toString()) }
      .sortedWith(compareBy(collator) { it.label })
      .toList()
  }

  /** The package names of [query], without loading a label (the engine's allowlist universe, refreshed on package changes). */
  fun packages(context: Context): Set<String> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
      @Suppress("DEPRECATION")
      pm.queryIntentActivities(intent, 0)
    }
    return activities.mapTo(HashSet()) { it.activityInfo.packageName }.also { it.remove(context.packageName) }
  }

  /** The app's label, or null when the package is not installed (or not visible). */
  fun label(context: Context, packageName: String): String? = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
  } catch (e: PackageManager.NameNotFoundException) {
    null
  }

  fun icon(context: Context, packageName: String): Drawable? = try {
    context.packageManager.getApplicationIcon(packageName)
  } catch (e: PackageManager.NameNotFoundException) {
    null
  }
}
