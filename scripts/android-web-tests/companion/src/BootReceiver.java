package expo.modules.screentime.webtest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;

public final class BootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
    int delay = context.getSharedPreferences("test", 0).getInt("bootDelay", -1);
    EventLog.write(context, "boot", "delay=" + delay);
    if (delay < 0) return;
    PendingResult pending = goAsync();
    new Handler(Looper.getMainLooper()).postDelayed(() -> {
      try {
        Intent consent = VpnService.prepare(context);
        EventLog.write(context, "boot-prepare", consent == null ? "prepared" : "dialog-required");
        if (consent == null) CompetingVpn.start(context);
      } catch (Exception failure) {
        EventLog.write(context, "boot-failed", failure.toString());
      } finally {
        pending.finish();
      }
    }, delay);
  }
}
