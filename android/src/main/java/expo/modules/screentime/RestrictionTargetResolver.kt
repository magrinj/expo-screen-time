package expo.modules.screentime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * The OS half of [RestrictionTargets]: the intents resolved through
 * PackageManager (declared in the library manifest's `<queries>`, so the
 * resolution works under Android 11's package visibility without
 * `QUERY_ALL_PACKAGES`). Read once per restriction change and per package
 * change, never per poll.
 *
 * The installer is asked of SYSTEM apps only, and from every handler, not
 * the default one: a third-party app the user made the default for APKs
 * (an installer app, a file manager) is neither the uninstall dialog nor
 * the install confirmation, which the system's package installer shows on
 * every route, and must not be shielded whole; and a default lookup with
 * two handlers answers the chooser, which would leave the restriction with
 * no installer at all.
 */
internal object RestrictionTargetResolver {
  const val APK_MIME_TYPE = "application/vnd.android.package-archive"

  fun resolve(context: Context): RestrictionTargets {
    val pm = context.packageManager
    val host = context.packageName
    val probe = Uri.parse("package:$host")
    val uninstallers = query(pm, Intent(Intent.ACTION_DELETE, probe), systemOnly = true) +
      @Suppress("DEPRECATION")
      query(pm, Intent(Intent.ACTION_UNINSTALL_PACKAGE, probe), systemOnly = true)
    // The installer's filter takes a `content:` URI with the APK type; a bare type matches nothing.
    val apk = Uri.parse("content://$host.expo_st_probe/probe.apk")
    @Suppress("DEPRECATION")
    val installers = query(pm, Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(apk, APK_MIME_TYPE), systemOnly = true)
    val stores = query(pm, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$host")), systemOnly = false)
    return RestrictionTargets.select(uninstallers, installers, stores, host)
  }

  private fun query(pm: PackageManager, intent: Intent, systemOnly: Boolean): List<String> {
    val flags = PackageManager.MATCH_DEFAULT_ONLY or (if (systemOnly) PackageManager.MATCH_SYSTEM_ONLY else 0)
    val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
      @Suppress("DEPRECATION")
      pm.queryIntentActivities(intent, flags)
    }
    return infos.map { it.activityInfo.packageName }
  }
}
