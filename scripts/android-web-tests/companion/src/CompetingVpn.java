package expo.modules.screentime.webtest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

/** Owns the VPN slot but routes only an unused documentation address. */
public final class CompetingVpn extends VpnService {
  private static final String ACTION_STOP = "expo.modules.screentime.webtest.STOP";
  private ParcelFileDescriptor tunnel;

  static void start(Context context) {
    Intent intent = new Intent(context, CompetingVpn.class);
    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
    else context.startService(intent);
  }

  static void stop(Context context) {
    // stopService alone does not destroy a service still bound by Android's VPN manager.
    context.startService(new Intent(context, CompetingVpn.class).setAction(ACTION_STOP));
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      closeTunnel();
      EventLog.write(this, "stop", "explicit");
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    try {
      Notification.Builder notice;
      if (Build.VERSION.SDK_INT >= 26) {
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("test-vpn", "Test VPN", NotificationManager.IMPORTANCE_LOW));
        notice = new Notification.Builder(this, "test-vpn");
      } else {
        notice = new Notification.Builder(this);
      }
      Notification notification = notice.setSmallIcon(android.R.drawable.ic_lock_lock)
          .setContentTitle("Competing test VPN active")
          .setContentText("Only 192.0.2.2 is routed; no internet traffic is proxied.")
          .setOngoing(true).build();
      if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
      else startForeground(1, notification);
      if (tunnel == null) {
        tunnel = new Builder().setSession("W1c competing VPN")
            .addAddress("192.0.2.1", 32).addRoute("192.0.2.2", 32).establish();
        EventLog.write(this, "establish", tunnel == null ? "null" : "descriptor");
      }
      if (tunnel == null) stopSelf(startId);
    } catch (Exception failure) {
      EventLog.write(this, "start-failed", failure.toString());
      stopSelf(startId);
    }
    return START_NOT_STICKY;
  }

  @Override public void onRevoke() {
    EventLog.write(this, "revoke", "");
    closeTunnel();
    stopSelf();
  }

  private void closeTunnel() {
    if (tunnel != null) {
      try { tunnel.close(); } catch (Exception failure) { EventLog.write(this, "close-failed", failure.toString()); }
      tunnel = null;
    }
    stopForeground(STOP_FOREGROUND_REMOVE);
  }

  @Override public void onDestroy() {
    closeTunnel();
    EventLog.write(this, "destroy", "");
    super.onDestroy();
  }
}
