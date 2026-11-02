package expo.modules.screentime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * The enforcement engine's foreground service, in the `:screentime` process
 * (`android/src/main/AndroidManifest.xml`). Step 1 is the skeleton: it
 * promotes itself to the foreground with the persistent notice when the
 * state store says the engine is wanted, and leaves otherwise. Detection and
 * the shield arrive in step 3.
 *
 * Two lessons from the survival run are built in:
 * - `startForeground` can throw `ForegroundServiceStartNotAllowedException`
 *   (Android 12+) on the system's sticky restart hours after the app was last
 *   visible. It is caught, recorded in the store and the service stops
 *   quietly; the next app launch or boot brings the engine back.
 * - The service never trusts an intent alone: the desired state lives in
 *   the store, so a redelivered or stale intent cannot keep a released
 *   engine running.
 */
class ScreenTimeEngineService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val store = StateStore(this)
    when (intent?.action) {
      // The internal entry points (also reachable from `adb shell am
      // start-foreground-service` on a debuggable build, for tests).
      ACTION_START -> store.engineDesired = true
      ACTION_STOP -> store.engineDesired = false
    }
    // stopSelf(startId): a start queued behind this one (released, then
    // wanted again at once) keeps the service alive instead of being dropped.
    if (!store.engineDesired) {
      Log.i(EngineController.TAG, "engine not wanted; stopping (intent=${intent?.action ?: "redelivered"})")
      stopSelf(startId)
      return START_NOT_STICKY
    }
    if (!promote()) {
      stopSelf(startId)
      return START_NOT_STICKY
    }
    Log.i(EngineController.TAG, "engine running in ${processName()} (reason=${intent?.getStringExtra(EXTRA_REASON) ?: intent?.action ?: "redelivered"})")
    // Sticky: the system may restart a killed engine; if it then refuses the
    // promotion, `promote` records it and the service leaves.
    return START_STICKY
  }

  /** `startForeground` with the notice; false (recorded) when the system refuses. */
  private fun promote(): Boolean {
    val notification = buildNotice()
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(NOTICE_ID, notification)
      }
      true
    } catch (e: Exception) {
      // ForegroundServiceStartNotAllowedException (31+) is an
      // IllegalStateException; SecurityException covers a missing FGS
      // permission. Caught by base class so the catch verifies on every API.
      Log.w(EngineController.TAG, "startForeground refused: ${e.javaClass.simpleName}: ${e.message}")
      StateStore(this).recordEngineFailure("foreground:${e.javaClass.simpleName}", e.message)
      false
    }
  }

  private fun buildNotice(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, getString(R.string.expo_st_notice_channel), NotificationManager.IMPORTANCE_LOW).apply {
          setShowBadge(false)
        }
      )
    }
    val launch = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
      PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
    return builder
      .setSmallIcon(R.drawable.expo_st_notice)
      .setContentTitle(getString(R.string.expo_st_notice_title))
      .setContentText(getString(R.string.expo_st_notice_text))
      .setOngoing(true)
      .setContentIntent(launch)
      .build()
  }

  private fun processName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.app.Application.getProcessName() else "?"

  companion object {
    const val ACTION_START = "expo.modules.screentime.action.START"
    const val ACTION_STOP = "expo.modules.screentime.action.STOP"
    const val EXTRA_REASON = "reason"
    private const val CHANNEL_ID = "expo_st_engine"
    private const val NOTICE_ID = 0x5354 // "ST"
  }
}
