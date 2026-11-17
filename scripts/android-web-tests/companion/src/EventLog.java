package expo.modules.screentime.webtest;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class EventLog {
  static synchronized void write(Context context, String event, String detail) {
    try {
      JSONObject row = new JSONObject();
      row.put("at", System.currentTimeMillis());
      row.put("elapsed", SystemClock.elapsedRealtime());
      row.put("event", event);
      row.put("detail", detail);
      Log.i("WebFilterTest", row.toString());
      try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "events.jsonl"), true)) {
        out.write((row.toString() + "\n").getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception failure) {
      Log.e("WebFilterTest", "Cannot save evidence", failure);
    }
  }

  private EventLog() {}
}
