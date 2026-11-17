package expo.modules.screentime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Both services live in :screentime and share one notice for their combined protection. */
internal object ProtectionNotice {
  const val ID = 0x5354
  private const val CHANNEL = "expo_st_engine"
  private val owners = mutableSetOf<Service>()
  private val main = Handler(Looper.getMainLooper())

  fun build(service: Service): Notification {
    val manager = service.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
      NotificationChannel(CHANNEL, service.getString(R.string.expo_st_notice_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    val launch = service.packageManager.getLaunchIntentForPackage(service.packageName)?.let {
      PendingIntent.getActivity(service, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(service, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(service)
    return builder.setSmallIcon(R.drawable.expo_st_notice)
      .setContentTitle(service.getString(R.string.expo_st_notice_title))
      .setContentText(service.getString(R.string.expo_st_notice_text))
      .setContentIntent(launch).setOngoing(true).build()
  }

  @Synchronized fun promoted(service: Service) { owners.add(service) }

  @Synchronized fun release(service: Service) {
    if (!owners.remove(service)) return
    service.stopForeground(if (owners.isEmpty()) Service.STOP_FOREGROUND_REMOVE else Service.STOP_FOREGROUND_DETACH)
    // Re-post after Android processes the departing service's notification cleanup.
    main.post { synchronized(this) {
      owners.firstOrNull()?.let { it.getSystemService(NotificationManager::class.java).notify(ID, build(it)) }
    } }
  }
}
