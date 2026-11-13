package expo.modules.screentime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import expo.modules.screentime.web.WebState
import java.util.concurrent.atomic.AtomicInteger

/** String-only bridge: no release references the debug source set's classes. */
internal object WebPreviewController {
  const val SERVICE = "expo.modules.screentime.ScreenTimeDnsFilterService"
  const val ACTION_SYNC = "expo.modules.screentime.action.WEB_SYNC"
  const val ACTION_WATCHDOG = "expo.modules.screentime.action.WEB_WATCHDOG"
  const val TAG = "ExpoScreenTimeWeb"
  val functions = setOf("blockWebDomains", "unblockWebDomains", "unblockAllWebDomains", "getBlockedWebDomains")
  val foreground: Boolean get() = BuildConfig.WEB_PREVIEW_FGS_TYPE != "none"
  private val handler = Handler(Looper.getMainLooper())

  fun built(context: Context): Boolean = BuildConfig.WEB_PREVIEW && try {
    @Suppress("DEPRECATION")
    context.packageManager.getServiceInfo(ComponentName(context.packageName, SERVICE), 0).enabled
  } catch (_: Exception) { false }

  fun mutate(context: Context, transform: (Set<String>) -> Set<String>) {
    if (!built(context)) throw UnsupportedPlatformException("blockWebDomains")
    val store = StateStore(context)
    store.update { document ->
      val boot = EnforcementGapRecorder(context, store).boot()
      WebState.setDomains(document, transform(WebState.domains(document)), System.currentTimeMillis(), boot.count, boot.timeMs ?: 0)
      store.syncEngineDesired(document, webBuilt = true)
    }
    sync(context, "domains")
  }

  fun event(context: Context, event: WebState.Event) {
    if (!built(context)) return
    val store = StateStore(context)
    store.update { document ->
      val boot = EnforcementGapRecorder(context, store).boot()
      WebState.event(document, event, System.currentTimeMillis(), boot.count, boot.timeMs ?: 0)
    }
  }

  fun gaps(context: Context, since: Long?): List<Map<String, Any?>> {
    if (!built(context)) return emptyList()
    val store = StateStore(context)
    return store.update { document ->
      val boot = EnforcementGapRecorder(context, store).boot()
      WebState.observe(document, System.currentTimeMillis(), boot.count, boot.timeMs ?: 0)
      WebState.gaps(document, since)
    }
  }

  fun sync(context: Context, reason: String, done: (Boolean) -> Unit = {}) {
    val app = context.applicationContext
    if (!built(app)) { done(true); return }
    if (reason == Intent.ACTION_BOOT_COMPLETED) { boot(app, done); return }
    val decision = decision(app)
    if (decision !in setOf(WebState.Start.establish, WebState.Start.stop)) {
      cancelWatchdog(app)
      gaps(app, null)
      Log.i(TAG, "web start skipped reason=$reason decision=$decision")
      done(true)
      return
    }
    val refusal = start(app, reason)
    if (refusal == null) { done(true); return }
    Log.i(TAG, "web refused start reason=$reason error=${refusal.javaClass.simpleName}")
    if (foreground && refusal is IllegalStateException && ForegroundStartExemption.available(app) && ForegroundStartExemption.blockedBy(app) == null) {
      ForegroundStartExemption.attempt(app, "web:$reason", { start(app, "$reason:revive") == null }) { ok ->
        if (!ok) refused(app, "start:${refusal.javaClass.simpleName}")
        done(ok)
      }
    } else {
      refused(app, "start:${refusal.javaClass.simpleName}")
      done(false)
    }
  }

  private fun decision(context: Context): WebState.Start {
    val store = StateStore(context)
    return store.read { document ->
      WebState.consent(document).start(WebState.desired(document), document.optJSONObject("web")?.optBoolean("running") == true,
        EnforcementGapRecorder(context, store).boot().count)
    }
  }

  private fun start(context: Context, reason: String): Exception? = try {
    if (decision(context) in setOf(WebState.Start.establish, WebState.Start.stop)) {
      val intent = Intent(ACTION_SYNC).setComponent(ComponentName(context.packageName, SERVICE)).putExtra("reason", reason)
      val result = if (foreground && Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
      checkNotNull(result) { "web service not resolved" }
    }
    null
  } catch (failure: Exception) { failure }

  fun refused(context: Context, detail: String, certain: Boolean = false) {
    if (!built(context)) return
    val store = StateStore(context)
    val delay = store.update { document ->
      if (!WebState.desired(document)) return@update null
      val web = WebState.record(document)
      val pid = web.optJSONObject("run")?.optInt("pid")
      val alive = web.optBoolean("running") && pid != null && EnforcementGapRecorder(context, store).engineAlive(pid)
      if (!certain && alive) return@update EngineWatchdog.periodMs(context.getSystemService(PowerManager::class.java)?.isInteractive ?: true)
      WebState.refused(document, System.currentTimeMillis(), detail)
      val retries = web.optInt("retries", 0)
      web.put("retries", retries + 1).put("lastRefusal", detail)
      RestartBackoff.delayMs(retries)
    }
    Log.i(TAG, "web refused start detail=$detail retryMs=$delay")
    if (delay != null) armWatchdog(context, delay) else cancelWatchdog(context)
  }

  fun armWatchdog(context: Context, delay: Long = EngineWatchdog.periodMs(context.getSystemService(PowerManager::class.java)?.isInteractive ?: true)) {
    if (!built(context)) return
    runCatching { context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
      AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, operation(context)) }
      .onFailure { Log.i(TAG, "web watchdog refused ${it.javaClass.simpleName}") }
  }
  fun cancelWatchdog(context: Context) {
    if (!built(context)) return
    runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(operation(context)) }
  }
  private fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 3,
    Intent(context, ScreenTimeRestartReceiver::class.java).setAction(ACTION_WATCHDOG), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  /** The only background prepare call. Observe VPNs for 3 s; an unreadable observation fails closed. */
  private fun boot(context: Context, done: (Boolean) -> Unit) {
    val store = StateStore(context)
    gaps(context, null)
    val policy = store.read(WebState::bootPolicy)
    val consentAtBoot = store.read { it.optJSONObject("web")?.optJSONObject("consent")?.optLong("at") }
    fun finish(vpnSeen: Boolean, observed: Boolean) {
      val prepared = store.update { document ->
        if (document.optJSONObject("web")?.optJSONObject("consent")?.optLong("at") != consentAtBoot) return@update false
        val count = EnforcementGapRecorder(context, store).boot().count
        if (!WebState.shouldPrepareAtBoot(policy, WebState.consent(document), WebState.desired(document), count, vpnSeen, observed)) return@update false
        try {
          val intent = VpnService.prepare(context)
          Log.i(TAG, "web boot policy=$policy vpnSeen=$vpnSeen prepare=${if (intent == null) "prepared" else "dialog-required"}")
          if (intent == null) {
            val boot = EnforcementGapRecorder(context, store).boot()
            WebState.event(document, WebState.Event.prepared, System.currentTimeMillis(), boot.count, boot.timeMs ?: 0)
            true
          } else false
        } catch (failure: Exception) {
          Log.i(TAG, "web boot prepare refused ${failure.javaClass.simpleName}")
          false
        }
      }
      Log.i(TAG, "web boot policy=$policy observed=$observed vpnSeen=$vpnSeen prepared=$prepared")
      sync(context, "boot-policy", done)
    }
    if (policy != WebState.BootPolicy.whenNoVpn || decision(context) in setOf(WebState.Start.idle, WebState.Start.stop, WebState.Start.establish)) { finish(false, false); return }
    val manager = context.getSystemService(ConnectivityManager::class.java)
    if (manager == null) { finish(false, false); return }
    val seen = java.util.concurrent.atomic.AtomicBoolean(false)
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) { seen.set(true) }
    }
    try {
      manager.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), callback)
      handler.postDelayed({
        runCatching { manager.unregisterNetworkCallback(callback) }
        finish(seen.get(), true)
      }, 3000)
    } catch (failure: Exception) {
      Log.i(TAG, "web boot observation refused ${failure.javaClass.simpleName}")
      finish(false, false)
    }
  }

  fun both(done: ((Boolean) -> Unit)?): (Boolean) -> Unit {
    val remaining = AtomicInteger(2)
    val success = java.util.concurrent.atomic.AtomicBoolean(true)
    return { ok ->
      if (!ok) success.set(false)
      if (remaining.decrementAndGet() == 0) done?.invoke(success.get())
    }
  }
}
