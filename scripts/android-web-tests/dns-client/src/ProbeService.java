package expo.modules.screentime.dnsclient;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the measuring process runnable while Settings/the filter/Chrome is in front. */
public final class ProbeService extends Service {
  @Override public void onCreate() {
    super.onCreate();
    getSystemService(NotificationManager.class).createNotificationChannel(
      new NotificationChannel("probe", "W-R3 test measurement", NotificationManager.IMPORTANCE_LOW));
    startForeground(1, new Notification.Builder(this, "probe")
      .setContentTitle("W-R3 DNS cache measurement")
      .setContentText("Test client active; close the app after the trial.")
      .setSmallIcon(android.R.drawable.ic_menu_info_details).build());
  }
  @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
  @Override public IBinder onBind(Intent intent) { return null; }
}
