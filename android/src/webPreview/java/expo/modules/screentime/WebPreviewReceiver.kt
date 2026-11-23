package expo.modules.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import expo.modules.screentime.web.WebState

/** Debug-only adb driver. It changes policy, never prepares or starts a VPN. */
class WebPreviewReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!WebPreviewController.built(context) || intent.action != "expo.modules.screentime.action.WEB_BOOT_POLICY") return
    val policy = runCatching { WebState.BootPolicy.valueOf(intent.getStringExtra("policy") ?: "") }.getOrNull()
    if (policy == null) {
      Log.i(WebPreviewController.TAG, "web boot policy rejected")
      resultCode = 0
      return
    }
    StateStore(context).update { WebState.record(it).put("bootPolicy", policy.name) }
    Log.i(WebPreviewController.TAG, "web boot policy stored=$policy")
    resultCode = -1
    resultData = policy.name
  }
}
