package expo.modules.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.TimeZone

/**
 * The module-process half of monitoring: the twin of the `// MARK: -
 * Monitoring`, `// MARK: - Event History` and `// MARK: - Real-time Event
 * Listening` sections of `ios/ExpoScreenTimeModule.swift`. Every mutation
 * runs the pure [MonitoringState] transition inside one `StateStore.update`
 * — the decision and its record land in one document write, under the
 * cross-process lock — then tells the engine process to look again
 * ([EngineController.sync], a stateless start intent) and delivers the
 * events this process recorded to JS. The engine process answers history
 * requests and advances usage; this side only ever calls `start`, `stop`,
 * `stopAll` and `clearEvents`, as the seam prescribes. The desire to run
 * is derived through [EnforcementGapRecorder.syncDesired], which records
 * a release for `getEnforcementGaps` when the last activity goes.
 */
internal class MonitoringController(
  private val context: Context,
  private val store: StateStore,
  private val selections: SelectionStore,
  private val emit: (Map<String, Any?>) -> Unit,
) {
  private val main = Handler(Looper.getMainLooper())
  private val gaps = EnforcementGapRecorder(context, store)

  /**
   * `startMonitoring(config)`: validated in the iOS order with the iOS
   * codes ([MonitoringValidation]), then registered (inside the
   * interval, the block applies at once and one `intervalDidStart` is
   * recorded). Everything the engine needs — the activity, the marker, the
   * desire to run — is in the document before it is told to look.
   */
  fun start(config: Map<String, Any?>) {
    val recorded = store.update { document ->
      val intentBefore = gaps.intent(document)
      val state = MonitoringDocument.read(document)
      val validated = MonitoringValidation.validate(config, selections::get, state.activities.keys)
      val result = state.start(validated, System.currentTimeMillis(), TimeZone.getDefault(), selections::get)
      MonitoringDocument.write(document, result.state)
      gaps.syncDesired(document, intentBefore)
      result.recorded
    }
    EngineController.sync(context, "monitoring:start")
    if (recorded.isNotEmpty()) notifyListeners()
  }

  /** `stopMonitoring(activityName)`: the reserved-name rule first, then the end (recorded once if an interval was in progress). */
  fun stop(activityName: String) {
    MonitoringValidation.validateActivityName(activityName)
    val recorded = store.update { document ->
      val intentBefore = gaps.intent(document)
      val result = MonitoringDocument.read(document).stop(activityName, System.currentTimeMillis())
      MonitoringDocument.write(document, result.state)
      gaps.syncDesired(document, intentBefore)
      result.recorded
    }
    EngineController.sync(context, "monitoring:stop")
    if (recorded.isNotEmpty()) notifyListeners()
  }

  /** `stopAllMonitoring()`: every activity; the manual block stays, as on iOS. */
  fun stopAll() {
    val recorded = store.update { document ->
      val intentBefore = gaps.intent(document)
      val result = MonitoringDocument.read(document).stopAll(System.currentTimeMillis())
      MonitoringDocument.write(document, result.state)
      gaps.syncDesired(document, intentBefore)
      result.recorded
    }
    EngineController.sync(context, "monitoring:stopAll")
    if (recorded.isNotEmpty()) notifyListeners()
  }

  fun monitoredActivities(): List<String> = store.monitoring.monitoredActivities()

  /** `getMonitorEvents(activityName?, limit?)`, read as iOS reads it (`MonitorEventLog.events`). */
  fun events(activityName: String?, limit: Int?): List<Map<String, Any>> =
    store.monitoring.events(activityName, limit ?: MonitorEventLog.DEFAULT_LIMIT).map { it.toJs() }

  /** `clearMonitorEvents(activityName?)`. The sequence keeps counting, so a live cursor never re-emits. */
  fun clearEvents(activityName: String?) {
    store.update { document ->
      MonitoringDocument.write(document, MonitoringDocument.read(document).clearEvents(activityName))
    }
  }

  // MARK: - Real-time event listening

  private var cursor: MonitorEventCursor? = null
  private var receiver: BroadcastReceiver? = null

  private val poll = object : Runnable {
    override fun run() {
      if (cursor == null) return
      drain()
      main.postDelayed(this, POLL_MS)
    }
  }

  /**
   * `OnStartObserving("onMonitorEvent")`: only events recorded from now on
   * are emitted live (history stays available through `getMonitorEvents`),
   * each exactly once, oldest first ([MonitorEventCursor]). The engine
   * process announces what it records with a package-local broadcast; a
   * 2 s poll is the fallback, as the iOS module polls beside its Darwin
   * notification.
   */
  fun startObserving() {
    // The cursor is taken now, synchronously: an event the next call records
    // (a `startMonitoring` inside its interval) must reach the listener.
    val starting = MonitorEventCursor.startingNow(store.monitoring.log)
    main.post {
      if (cursor != null) return@post
      cursor = starting
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
          drain()
        }
      }
      this.receiver = receiver
      val filter = IntentFilter(ACTION_EVENTS_RECORDED)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        context.registerReceiver(receiver, filter)
      }
      main.postDelayed(poll, POLL_MS)
    }
  }

  fun stopObserving() {
    main.post {
      cursor = null
      main.removeCallbacks(poll)
      receiver?.let {
        try {
          context.unregisterReceiver(it)
        } catch (e: IllegalArgumentException) {
          // not registered: nothing to do
        }
      }
      receiver = null
    }
  }

  /** An event was written by this process: deliver it like the engine's broadcast would. */
  fun notifyListeners() = main.post { drain() }

  private fun drain() {
    val cursor = cursor ?: return
    val fresh = try {
      val (fresh, next) = cursor.take(store.monitoring.log)
      this.cursor = next
      fresh
    } catch (e: Exception) {
      // A document that cannot be read right now is read at the next poll.
      return
    }
    for (event in fresh) emit(mapOf("event" to event.toJs()))
  }

  companion object {
    /** Sent by the engine process (package-local) after it persisted new events. */
    const val ACTION_EVENTS_RECORDED = "expo.modules.screentime.action.EVENTS_RECORDED"
    const val POLL_MS = 2_000L

    /** The engine's side of the announcement. */
    fun announceEvents(context: Context) {
      context.sendBroadcast(Intent(ACTION_EVENTS_RECORDED).setPackage(context.packageName))
    }
  }
}
