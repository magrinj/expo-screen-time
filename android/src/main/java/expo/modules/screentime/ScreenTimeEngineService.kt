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
import android.os.Process
import android.util.Log

/**
 * The enforcement engine's foreground service, in the `:screentime` process
 * (`android/src/main/AndroidManifest.xml`). It promotes itself to the
 * foreground with the persistent notice when the state store says the
 * engine is wanted, and leaves otherwise; while it runs, [EngineLoop]
 * detects the foreground app and launches the [ShieldActivity],
 * evaluates the schedules and usage thresholds and records their events
 *, meters the unlocks and beats for the gap tracker
 *.
 *
 * The engine's run is recorded for `getEnforcementGaps`
 * ([EnforcementGapRecorder]): its start (in the update that re-checks the
 * desired state), a refused promotion, and its release (`onDestroy` after
 * the module cleared the desired state).
 *
 * Two lessons from the survival run are built in:
 * - `startForeground` can throw `ForegroundServiceStartNotAllowedException`
 *   (Android 12+) on the system's sticky restart: the restarted service's
 *   `startForeground` is judged on the app's state again (`ActiveServices`,
 *   `mStartForegroundCount >= 1`), and a background process without the
 *   battery exemption is refused. It is caught and retried under the
 *   overlay exemption ([ForegroundStartExemption]); only when that fails
 *   too is the refusal recorded as a gap, the watchdog armed
 *   ([EngineWatchdog]) and the service stopped quietly.
 * - The service never trusts an intent alone: the desired state lives in
 *   the store, so a redelivered or stale intent cannot keep a released
 *   engine running.
 */
class ScreenTimeEngineService : Service() {
  private var loop: EngineLoop? = null
  /** Whether `startForeground` succeeded for this instance; the loop runs only once it has. */
  private var promoted = false
  /** A refused promotion being retried under the overlay exemption, with the starts that wait for it. */
  private var reviving = false
  private val pendingStartIds = mutableListOf<Int>()
  private var pendingReason: String? = null
  private var lastRefusal: String? = null
  /** Whether the refusal can be answered by the overlay exemption (a background-start denial), or not at all (a SecurityException). */
  private var retryable = false
  private var destroyed = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val store = StateStore(this)
    when (intent?.action) {
      // Debug entry points, reachable from `adb shell am
      // start-foreground-service` on a debuggable build; nothing in the app
      // sends them. The app sends SYNC (EngineController), which carries no
      // state: the store decides.
      ACTION_START -> store.engineDesired = true
      ACTION_STOP -> store.update { document ->
        val recorder = EnforcementGapRecorder(this, store)
        val before = recorder.intent(document)
        store.engineDesired = false
        recorder.released(document, before)
      }
    }
    val reason = intent?.getStringExtra(EXTRA_REASON) ?: intent?.action ?: "redelivered"
    // Promote FIRST, whatever the store says: a `startForegroundService`
    // must be answered by `startForeground`, or the system kills the process
    // with ForegroundServiceDidNotStartInTimeException and shows a crash
    // dialog, which is exactly what happened when `unblockAll` followed
    // `blockSelection` within a millisecond (the stop arrived before the
    // start was honoured).
    if (promoted || promote()) {
      run(reason, startId)
      return START_STICKY
    }
    // Refused: the sticky restart of a killed engine, judged on the app's
    // background state. Retried under the overlay exemption on the main
    // looper (the window needs a frame, so this cannot block here); the
    // starts that arrive meanwhile wait for the outcome. (The system's
    // 10 s deadline does not run here: a refused `startForeground` clears
    // it, so a later `stopSelf` is quiet.)
    pendingStartIds += startId
    pendingReason = reason
    if (!retryable || ForegroundStartExemption.blockedBy(this) != null) {
      // Not answerable by the exemption (a SecurityException), or not right
      // now (screen off, keyguard: the retry comes at the unlock).
      refused(reason, pendingStartIds.toList().also { pendingStartIds.clear() })
      return START_NOT_STICKY
    }
    if (!reviving) {
      reviving = true
      ForegroundStartExemption.attempt(this, "promote:$reason", { !destroyed && (promoted || promote()) }) { ok ->
        reviving = false
        if (destroyed) return@attempt
        val ids = pendingStartIds.toList()
        pendingStartIds.clear()
        val why = pendingReason ?: reason
        pendingReason = null
        // A start that arrived meanwhile may have promoted and run already: nothing to redo.
        if (ok || promoted) { if (loop == null) run(why, ids.last()) } else refused(why, ids)
      }
    }
    return START_STICKY
  }

  /** Promoted: the desired state decides whether the engine runs or the service leaves. */
  private fun run(reason: String, startId: Int) {
    val store = StateStore(this)
    // The desired state is re-checked and the engine's run is recorded in
    // ONE update (EnforcementGapRecorder.engineStarted: the exit history is
    // folded, a previous process's death or a reboot becomes a gap that
    // closes now, and this process's run opens), so a release landing in
    // between can never leave a run that nothing stops.
    val gaps = EnforcementGapRecorder(this, store)
    val desired = try {
      store.update { document ->
        val desired = store.engineDesired
        if (desired) gaps.engineStarted(document, Process.myPid())
        desired
      }
    } catch (e: Exception) {
      // The run's bookkeeping must never keep the engine from running.
      Log.w(EngineController.TAG, "run not recorded at start: ${e.javaClass.simpleName}: ${e.message}")
      store.engineDesired
    }
    // stopSelf(startId): a start queued behind this one (released, then
    // wanted again at once) keeps the service alive instead of being dropped.
    if (!desired) {
      Log.i(EngineController.TAG, "engine not wanted; stopping (intent=$reason)")
      EngineWatchdog.cancel(this)
      stopForeground(STOP_FOREGROUND_REMOVE)
      // A start queued behind this one must promote again: the system kills a
      // started service that does not answer with `startForeground`.
      promoted = false
      stopSelf(startId)
      return
    }
    Log.i(EngineController.TAG, "engine running in ${processName()} (reason=$reason)")
    store.engineRunning = true
    // The chain's backoff starts over once the engine runs.
    if (store.engineRetries != 0) store.engineRetries = 0
    // Every start intent is also the "look again" signal from the module
    // process, the restart receiver and the alarm: the manual block, the
    // monitoring state (and the sets they need) are re-read from the store.
    (loop ?: EngineLoop(this).also { loop = it }).refresh(reason)
  }

  /**
   * Every promotion refused, the overlay one included: the engine is down
   * for certain while it is wanted, so a gap opens now and the watchdog
   * keeps the chain trying (with its backoff; not for a refusal the
   * exemption cannot answer); the service leaves. `stopSelf(startId)` with
   * the last start waited for: a newer start keeps the service alive and
   * promotes on its own. Not a gap after a release.
   */
  private fun refused(reason: String, startIds: List<Int>) {
    val detail = "foreground:${lastRefusal ?: "refused"}"
    val store = StateStore(this)
    EnforcementGapRecorder(this, store).engineRefused(detail, certain = true)
    if (!store.engineDesired) EngineWatchdog.cancel(this) else if (retryable) EngineWatchdog.retry(this, ForegroundStartExemption.blockedBy(this))
    Log.w(EngineController.TAG, "engine not promoted ($reason): $detail; stopping")
    startIds.lastOrNull()?.let { stopSelf(it) } ?: stopSelf()
  }

  override fun onDestroy() {
    destroyed = true
    loop?.stop()
    loop = null
    val store = StateStore(this)
    try {
      store.update { document ->
        store.engineRunning = false
        // A release the module folded already finds the run ended (idempotent);
        // a service destroyed while the engine is still wanted is not a release.
        val recorder = EnforcementGapRecorder(this, store)
        if (!store.engineDesired) recorder.released(document, recorder.intent(document))
      }
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "release not recorded at destroy: ${e.javaClass.simpleName}: ${e.message}")
    }
    super.onDestroy()
  }

  /**
   * `startForeground` with the notice; false when the system refuses (the
   * failure is recorded for `engineFailures`, the gap only once the retry
   * under the overlay exemption has failed too, in [refused]).
   */
  private fun promote(): Boolean {
    val notification = buildNotice()
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(NOTICE_ID, notification)
      }
      promoted = true
      true
    } catch (e: Exception) {
      // ForegroundServiceStartNotAllowedException (31+) is an
      // IllegalStateException; SecurityException covers a missing FGS
      // permission. Caught by base class so the catch verifies on every API.
      Log.w(EngineController.TAG, "startForeground refused: ${e.javaClass.simpleName}: ${e.message}")
      lastRefusal = e.javaClass.simpleName
      // The overlay exemption answers a background-start denial only.
      retryable = e is IllegalStateException
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
