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
 * (`android/src/main/AndroidManifest.xml`). It promotes itself to the
 * foreground with the persistent notice when the state store says the
 * engine is wanted, and leaves otherwise; while it runs, [EngineLoop]
 * detects the foreground app and launches the [ShieldActivity],
 * evaluates the schedules and usage thresholds and records their events
 *.
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
  private var loop: EngineLoop? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val store = StateStore(this)
    when (intent?.action) {
      // Debug entry points, reachable from `adb shell am
      // start-foreground-service` on a debuggable build; nothing in the app
      // sends them. The app sends SYNC (EngineController), which carries no
      // state: the store decides.
      ACTION_START -> store.engineDesired = true
      ACTION_STOP -> store.engineDesired = false
    }
    // Promote FIRST, whatever the store says: a `startForegroundService`
    // must be answered by `startForeground`, or the system kills the process
    // with ForegroundServiceDidNotStartInTimeException and shows a crash
    // dialog, which is exactly what happened when `unblockAll` followed
    // `blockSelection` within a millisecond (the stop arrived before the
    // start was honoured). A refused promotion is recorded by `promote`.
    val promoted = promote()
    // stopSelf(startId): a start queued behind this one (released, then
    // wanted again at once) keeps the service alive instead of being dropped.
    if (!store.engineDesired || !promoted) {
      if (!store.engineDesired) Log.i(EngineController.TAG, "engine not wanted; stopping (intent=${intent?.action ?: "redelivered"})")
      if (promoted) stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf(startId)
      return START_NOT_STICKY
    }
    val reason = intent?.getStringExtra(EXTRA_REASON) ?: intent?.action ?: "redelivered"
    Log.i(EngineController.TAG, "engine running in ${processName()} (reason=$reason)")
    store.engineRunning = true
    // Every start intent is also the "look again" signal from the module
    // process, the restart receiver and the alarm: the manual block, the
    // monitoring state (and the sets they need) are re-read from the store.
    // The loop exists only under the preview switch: a default build has
    // no way to write a block, and must not enforce one a preview build
    // left behind.
    if (BuildConfig.BLOCKING_PREVIEW) (loop ?: EngineLoop(this).also { loop = it }).refresh(reason)
    // Sticky: the system may restart a killed engine; if it then refuses the
    // promotion, `promote` records it and the service leaves.
    return START_STICKY
  }

  override fun onDestroy() {
    loop?.stop()
    loop = null
    StateStore(this).engineRunning = false
    super.onDestroy()
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
    const val ACTION_SYNC = "expo.modules.screentime.action.SYNC"
    const val EXTRA_REASON = "reason"
    private const val CHANNEL_ID = "expo_st_engine"
    private const val NOTICE_ID = 0x5354 // "ST"
  }
}
