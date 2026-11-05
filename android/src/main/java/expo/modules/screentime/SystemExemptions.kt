package expo.modules.screentime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager

/**
 * The OS half of [ShieldPolicy.exemptions]: who the launcher, the dialer,
 * the emergency app, the keyboard and Settings are on this device, read
 * from intent resolution and the telecom manager (`RoleManager.getRoleHolders`
 * is a system API). Read once per block change and per package change,
 * never per poll.
 */
internal object SystemExemptions {
  private const val SYSTEM_UI = "com.android.systemui"
  private const val AOSP_EMERGENCY = "com.android.emergency"
  /** `TelephonyManager.ACTION_EMERGENCY_ASSISTANCE` (31+), a system API constant: the emergency app resolves it. */
  private const val ACTION_EMERGENCY_ASSISTANCE = "android.telephony.action.EMERGENCY_ASSISTANCE"

  fun read(context: Context): Set<String> {
    val pm = context.packageManager
    val emergency = LinkedHashSet<String>()
    emergency.add(AOSP_EMERGENCY)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      resolve(pm, Intent(ACTION_EMERGENCY_ASSISTANCE))?.let(emergency::add)
    }
    return ShieldPolicy.exemptions(
      host = context.packageName,
      launchers = launchers(pm),
      systemUi = SYSTEM_UI,
      dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        ?: resolve(pm, Intent(Intent.ACTION_DIAL)),
      emergency = emergency,
      inputMethod = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.let { ComponentName.unflattenFromString(it)?.packageName },
      settings = resolve(pm, Intent(Settings.ACTION_SETTINGS)),
    )
  }

  /**
   * The home apps alone, for the usage report's app filter (a launcher is
   * never an app the person "used"), minus Settings: AOSP Settings answers
   * the HOME intent too (`FallbackHome`, the stand-in home before the first
   * unlock, priority -1000), and Settings is an app a person uses.
   */
  fun launchers(context: Context): Set<String> {
    val pm = context.packageManager
    val settings = resolve(pm, Intent(Settings.ACTION_SETTINGS))
    return launchers(pm).filterTo(LinkedHashSet()) { it != settings }
  }

  /** Every installed home app, not just the current one: the user may switch launchers under a block. */
  private fun launchers(pm: PackageManager): Set<String> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val result = LinkedHashSet<String>()
    resolve(pm, intent)?.let(result::add)
    val homes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
      @Suppress("DEPRECATION")
      pm.queryIntentActivities(intent, 0)
    }
    homes.mapTo(result) { it.activityInfo.packageName }
    return result
  }

  private fun resolve(pm: PackageManager, intent: Intent): String? {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
      @Suppress("DEPRECATION")
      pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return info?.activityInfo?.packageName?.takeIf { it != "android" }
  }
}
