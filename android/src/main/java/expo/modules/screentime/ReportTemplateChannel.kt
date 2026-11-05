package expo.modules.screentime

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The Android counterpart of the App Group template slot
 * (`expo_st_report_template`): the one serialized template the mounted
 * `<DeviceActivityReport>` views render. On iOS the slot has to be a file,
 * because the extension is another process; here the module and the views
 * share the host process, so the slot is this object, written by
 * `setReportTemplate` / `clearReportTemplate` on the JS thread and read by
 * the views on their render thread. Not persisted: JS re-acquires the
 * template on every mount (`templateChannel.ts`), as it does on iOS.
 *
 * A view renders when its `templateHash` prop changes AND when the slot
 * changes, whichever lands first: the prop may be committed before or after
 * the write, and a view mounted without children renders whatever the
 * imperative API put here, as the iOS view re-reads the App Group.
 */
internal object ReportTemplateChannel {
  @Volatile
  var json: String? = null
    private set

  private val listeners = CopyOnWriteArraySet<() -> Unit>()
  private val main = Handler(Looper.getMainLooper())

  fun set(json: String?) {
    this.json = json
    for (listener in listeners) main.post(listener)
  }

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }
}
