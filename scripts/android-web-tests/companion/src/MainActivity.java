package expo.modules.screentime.webtest;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Emulator-only controls. Every prepare call follows a button press or the explicit boot setting. */
public final class MainActivity extends Activity {
  private TextView status;

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(32, 96, 32, 32);
    TextView intro = new TextView(this);
    intro.setText("W1c test companion\nThis test takes the Android VPN slot. It routes only 192.0.2.2, without a remote server.");
    content.addView(intro);
    button(content, "Start competing VPN", () -> {
      Intent consent = VpnService.prepare(this);
      EventLog.write(this, "prepare", consent == null ? "prepared" : "dialog-required");
      if (consent == null) CompetingVpn.start(this);
      else startActivityForResult(consent, 1);
    });
    button(content, "Stop competing VPN", () -> CompetingVpn.stop(this));
    button(content, "Boot start: off", () -> boot(-1));
    button(content, "Boot start: immediate", () -> boot(0));
    button(content, "Boot start: after 5 seconds", () -> boot(5000));
    status = new TextView(this);
    content.addView(status);
    setContentView(content);
    status.setText("Boot delay: " + getSharedPreferences("test", 0).getInt("bootDelay", -1));
  }

  private void button(LinearLayout content, String label, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    button.setOnClickListener(view -> {
      try { action.run(); } catch (Exception failure) {
        EventLog.write(this, "button-failed", failure.toString());
        status.setText(failure.toString());
      }
    });
    content.addView(button);
  }

  private void boot(int delay) {
    boolean saved = getSharedPreferences("test", 0).edit().putInt("bootDelay", delay).commit();
    EventLog.write(this, "boot-setting", "delay=" + delay + ",saved=" + saved);
    status.setText("Boot delay: " + delay + ", saved: " + saved);
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 1) return;
    EventLog.write(this, "consent-result", "result=" + result);
    if (result == RESULT_OK) CompetingVpn.start(this);
  }
}
