package expo.modules.screentime

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.TimeZone

/**
 * The detection loop of the engine (`:screentime` process), the usageStats
 * engine stage 2 measured (measured: p95 176 ms at a 100 ms poll, about
 * 1.2 ms of CPU per poll, 0 leaks in 100 launches):
 *
 * - `queryEvents` every [INTERVAL_MS] on its own [HandlerThread], **only
 *   while the screen is interactive and a block is in force** — or every
 *   [THRESHOLD_POLL_MS] while an interval in progress has a usage threshold
 *   to observe and nothing to shield: polling stops on `SCREEN_OFF` and when
 *   the last source and threshold are gone, resumes on `SCREEN_ON` /
 *   `USER_PRESENT` and when a source arrives. No source, no screen, no
 *   poll (the battery figure of §10.1 D assumes exactly this).
 * - Each query re-reads [OVERLAP_MS] before the last end (events land after
 *   two handler hops, so one can carry a timestamp earlier than the previous
 *   `end`) and de-duplicates by (timestamp, type, activity).
 * - One bad poll never kills the engine: the 24 h run of §10.1 E died on an
 *   `Invalid period` when the wall clock stepped back. The window is clamped,
 *   a clock step (`TIME_SET`, `TIMEZONE_CHANGED`, or `now < lastEnd`)
 *   restarts it, every poll runs in a try/catch, and repeated failures back
 *   off instead of retrying at 10 Hz.
 * - The decision is [ShieldPolicy], pure: the loop only feeds it the
 *   [ForegroundTracker]'s view, the block sources composed by
 *   [BlockComposition] from the manual block and the monitoring state, and
 *   the exemptions, and launches the [ShieldActivity] for what comes back,
 *   with the owner whose configuration applies (threshold > latest schedule
 *   > manual).
 * - After a shield has drawn, a follow-up ([FOLLOW_UP_MS] later, confirmed
 *   [CONFIRM_MS] after that) tells whether the target is still on screen
 *   beside it: paused but not stopped is a picture-in-picture window
 *   floating over the shield (measured bypass, §10.1 B), countered by
 *   [PipEvictorActivity], at most [MAX_EVICTIONS] times per shield; still
 *   resumed is a multi-window layout the shield could not take over (a
 *   tablet or desktop pane), and the engine sends the user Home, which
 *   ends it.
 * - Every re-enforcement without a fresh event for its target is capped
 *   ([MAX_REENFORCE]): a stale entry must never turn into a shield
 *   relaunched every second over whatever is in front.
 *
 * **Monitoring.** The schedules are a function of time and the
 * usage thresholds a function of the UsageStats stream, both evaluated by
 * the pure [MonitoringState]: on every wake — a start intent from the
 * module process (`startMonitoring`, `blockSelection`, launch, boot), the
 * inexact alarm armed at [MonitoringState.nextWakeMs], a screen or clock
 * change — and on every poll, [step] feeds the stream read since the
 * persisted cursor (`advance`), persists what is durable under the store's
 * lock (an event, a marker, a history answer; the usage totals every
 * [PERSIST_MS]) and only then acts on it: announces the events to the
 * module process, recomposes the sources, applies the shields and re-arms
 * the alarm. A document the module changed meanwhile (`revision`) is
 * reloaded and the step runs again from it, re-reading the stream from its
 * cursor, which re-creates the spans this process lost. The alarm is a
 * plain inexact `setAndAllowWhileIdle` broadcast ([ScreenTimeRestartReceiver]
 * → `SYNC`): a boundary due while the screen is off is observed within the
 * system's window, as iOS observes it at the next use; no exact alarm, no
 * permission.
 *
 * **Unlocks and snoozes.** The engine is the meter iOS delegates
 * to DeviceActivity: on every poll (100 ms while something is shielded,
 * 1 s while only a budget or a threshold is observed), [meter] feeds one
 * [UsageSample] — the resumed set from the tracker, the screen state — to
 * the whole-block grant ([UnlockGrant.observing] over the apps its
 * suspended sources shield) and to each snooze ([ShieldSnoozes.observing]),
 * and persists what is worth persisting (a whole second consumed, a target
 * entering or leaving use, a budget spent) under the store's lock with the
 * write-back merges ([UnlockGrant.merging], [ShieldSnoozes.merging]), so a
 * grant or an end the module made meanwhile is kept. A spent or expired
 * budget is dropped from the document and the sources are recomposed: the
 * suspension ends, the lifted app re-shields, and the standing check puts
 * the shield over the app still on screen — by itself, the host app never
 * runs. The composition honours the grant's [SuspendedSources] and the
 * lifted set (snoozed apps, the shield's unblocks); the grant survives the
 * process (it is read back from the document with its meter).
 *
 * **Enforcement gaps.** The loop is the engine's heartbeat for
 * [EnforcementGapTracker]: one beat every [EnforcementGapTracker.HEARTBEAT_INTERVAL_MS]
 * on the engine thread (a timer that sleeps with the CPU, which is why a
 * live process is never judged stale), plus one on every screen on/off and
 * `USER_PRESENT` (keeps the last beat tight around the periods that
 * matter), one on `ACTION_SHUTDOWN` (where a reboot's gap starts), and one
 * whenever an enforcement AppOp changes (`GET_USAGE_STATS`,
 * `SYSTEM_ALERT_WINDOW`: a revocation is observed at once, not at the next
 * minute). Every beat carries the engine's own permission check, so a
 * permission gap opens and closes from here too ([EnforcementGapRecorder]).
 * Every beat and every start intent ([refresh]) also push the restart
 * chain's watchdog forward ([EngineWatchdog]): it lands only once the beats
 * have stopped.
 */
internal class EngineLoop(private val context: Context) {
  private val thread = HandlerThread("expo-st-engine").apply { start() }
  private val handler = Handler(thread.looper)
  private val usage = context.getSystemService(UsageStatsManager::class.java)
  private val power = context.getSystemService(PowerManager::class.java)
  private val alarms = context.getSystemService(AlarmManager::class.java)
  private val store = StateStore(context)
  private val selections = SelectionStore(store)
  private val gaps = EnforcementGapRecorder(context, store)
  private val appOps = context.getSystemService(AppOpsManager::class.java)
  // ACTIVITY_STOPPED exists from API 29; below, a paused activity is gone.
  private val tracker = ForegroundTracker(tracksStopped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

  // Engine-thread state.
  private var manual: ManualBlock? = null
  private var monitoring = MonitoringState()
  /** The monitoring state as last read from or written to the document. */
  private var persisted = MonitoringState()
  private var lastPersistAt = 0L
  private var blocks: List<MonitoringBlock>? = null
  private var sources: List<BlockSource> = emptyList()
  /** The whole-block unlock in progress (active today, budget left), with its meter; null otherwise. */
  private var grant: UnlockGrant? = null
  /** The snoozes in force (granted today, not spent), with their meters. */
  private var snoozes: List<ShieldSnooze> = emptyList()
  /** The shield's unblocks, with the composition they were pressed under: they apply while it is in force. */
  private var lifts: ShieldLifts.Lifts = ShieldLifts.NONE
  /** What the last composition took out of every source: the snoozed apps and the lifts in force. */
  private var lifted: Set<String> = emptySet()
  /** The suspension the last composition honoured. */
  private var appliedSuspension: SuspendedSources? = null
  /** The apps the grant meters: shielded by a suspended source, by no live one. */
  private var metered: Set<String> = emptySet()
  /** The lifts (in force or not) the last composition judged, so a void one is dropped once. */
  private var liftedApplied: Set<String> = emptySet()
  /** When the unlock state was last read back from the document on the engine's own initiative. */
  private var lastUnlockReconcileAt = 0L
  private var zone: TimeZone = TimeZone.getDefault()
  /** Wall clock minus the monotonic clock: a change is a clock step (`TIME_SET`). */
  private var drift = System.currentTimeMillis() - SystemClock.elapsedRealtime()
  private var armedWakeMs: Long? = null
  private var launchable: Set<String> = emptySet()
  private var exempt: Set<String> = emptySet()
  private var polling = false
  private var pollInterval = INTERVAL_MS
  /** The next poll replays history to rebuild the state: nothing in it "came forward" now. */
  private var seeding = false
  private var lastEnd = 0L
  private val seen = HashMap<String, Long>()
  private var lastShieldAt = 0L
  private var errorStreak = 0
  private var polls = 0L
  /** When the running poll was due (uptime), and how late it ran. */
  private var pollDueAt = 0L
  private var pollLateMs = 0L
  /** The last polls' timings (`late/query` ms), logged with a detection to tell a late poll from a late event. */
  private val recentPolls = ArrayDeque<String>()
  /** Re-enforcements and evictions per target since its last fresh event. */
  private val reenforced = HashMap<String, Int>()
  private val evicted = HashMap<String, Int>()
  /** Uptime until which a PiP eviction is in flight: the translucent evictor pauses the shield, which must not be re-launched under it. */
  private var evictingUntil = 0L
  private var overlayMissingRecorded = false
  private var lockTaskRecorded = false

  private val poll = object : Runnable {
    override fun run() {
      if (!polling) return
      // How late the handler ran this poll (the thread was busy, or the CPU was): one of the
      // three causes of a late detection, measured.
      pollLateMs = if (pollDueAt == 0L) 0L else SystemClock.uptimeMillis() - pollDueAt
      var delay: Long
      try {
        pollOnce()
        errorStreak = 0
        // Read after the poll: an interval the poll itself changed (a spent
        // budget turning 1 s metering into 100 ms shielding) applies to the
        // very next poll, so the shield's follow-up never judges a target
        // from a tracker view that is a second old.
        delay = pollInterval
      } catch (t: Throwable) {
        errorStreak++
        // A short window (not the long seed: an oversized query could be
        // the failure itself) and an exponential back-off, capped at 5 s.
        delay = minOf(INTERVAL_MS shl minOf(errorStreak, 6), MAX_BACKOFF_MS)
        Log.w(TAG, "poll error ${t.javaClass.simpleName}: ${t.message}; next poll in $delay ms")
        restartWindow(OVERLAP_MS)
      }
      pollDueAt = SystemClock.uptimeMillis() + delay
      handler.postDelayed(this, delay)
    }
  }

  private val settleAfterScreenOff = Runnable {
    guarded("screen-off settle") {
      step("screen-off")
      persist("screen-off", force = true)
      // Nothing is in use behind a dark screen: the in-use stretches close
      // here, so the time until the screen comes back is never charged.
      meter("screen-off", interactive = false)
    }
  }

  /** The gap tracker's beat: every minute while the engine runs, and on the events below. */
  private val heartbeat = object : Runnable {
    override fun run() {
      guarded("heartbeat") { beat("timer") }
      // The watchdog is pushed forward on every beat: it lands only when the beats stop
      // (far ahead with the screen off, so a sleeping CPU never lets it come due).
      EngineWatchdog.arm(context, power.isInteractive)
      handler.postDelayed(this, EnforcementGapTracker.HEARTBEAT_INTERVAL_MS)
    }
  }

  /** An enforcement AppOp changed (usage access, the overlay): observed at once, on the engine thread. */
  private val appOpsListener = AppOpsManager.OnOpChangedListener { op, packageName ->
    if (packageName == null || packageName == context.packageName) handler.post { guarded("appops") { beat("appops:$op") } }
  }

  /**
   * The in-process twin of the alarm: while the engine lives, a schedule
   * boundary is evaluated at the instant it falls (a handler callback is
   * exact to the millisecond with the CPU awake, needs no permission and
   * polls nothing), so an app opened a minute after 09:00 with the screen
   * on is shielded whether or not the inexact alarm has landed yet. The
   * alarm stays armed for the cases the handler cannot cover: the process
   * dead, or the CPU asleep (uptime pauses in deep sleep).
   */
  private val wake = Runnable { guarded("wake") { step("wake") } }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
      guarded("receiver ${intent.action}") {
        when (intent.action) {
          Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
            handler.removeCallbacks(settleAfterScreenOff)
            // Back to the short watchdog: the screen is where a stop happens.
            EngineWatchdog.arm(context, interactive = true)
            // The beat on its own: the shield step must never wait on the gap bookkeeping.
            guarded("beat") { beat(intent.action!!.substringAfterLast('.')) }
            step(intent.action!!.substringAfterLast('.'))
            // The user is about to reach an app: the shield's pages are touched now, not under their eyes.
            if (sources.isNotEmpty()) ShieldWarmer.touch(context, intent.action!!.substringAfterLast('.'))
          }
          Intent.ACTION_SCREEN_OFF -> {
            guarded("beat") { beat("SCREEN_OFF") }
            // The long watchdog: it must not come due while this engine only sleeps.
            EngineWatchdog.arm(context, interactive = false)
            // Nothing is in use behind a dark screen: the in-use stretches
            // close now, on the known power state, so no dark second is charged.
            meter("SCREEN_OFF", interactive = false)
            stopPolling("SCREEN_OFF")
            // The stream's SCREEN_NON_INTERACTIVE event closes every open
            // usage span; it is visible after the pipeline lag, so one last
            // step reads it and the totals are written before the process idles.
            handler.postDelayed(settleAfterScreenOff, LAG_MS + 1_000L)
          }
          Intent.ACTION_TIME_CHANGED -> {
            val now = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val delta = now - drift
            drift = now
            Log.i(TAG, "clock changed by $delta ms (TIME_SET); window restarted")
            restartWindow(OVERLAP_MS)
            if (delta != 0L) shiftClock(delta)
            if (delta != 0L) guarded("gaps clock") { gaps.clockShifted(delta) }
            // The posted wake was computed on the old clock: re-arm from scratch.
            armedWakeMs = null
            step("TIME_SET")
          }
          Intent.ACTION_TIMEZONE_CHANGED -> {
            zone = intent.getStringExtra("time-zone")?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
            Log.i(TAG, "time zone changed to ${zone.id}; window restarted")
            restartWindow(OVERLAP_MS)
            armedWakeMs = null
            step("TIMEZONE_CHANGED")
          }
          // The device is going down: the beat marks where a reboot's gap starts, and the totals are written.
          Intent.ACTION_SHUTDOWN -> {
            guarded("beat") { gaps.heartbeat(Process.myPid(), "shutdown", shutdown = true) }
            persist("shutdown", force = true)
            persistMeters("shutdown")
          }
          Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_CHANGED -> {
            // Its label or icon may have changed: the face is dropped, and loaded again if it is shielded.
            intent.data?.schemeSpecificPart?.let(ShieldCache::dropFace)
            if (sources.isNotEmpty()) {
              reloadSets()
              ShieldWarmer.warm(context, BlockComposition.union(sources), intent.action!!.substringAfterLast('.'))
            }
          }
          // Labels are localized: every face is stale.
          Intent.ACTION_LOCALE_CHANGED -> {
            ShieldCache.dropFaces()
            if (sources.isNotEmpty()) ShieldWarmer.warm(context, BlockComposition.union(sources), "LOCALE_CHANGED")
          }
        }
      }
    }
  }

  init {
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_USER_PRESENT)
      addAction(Intent.ACTION_TIME_CHANGED)
      addAction(Intent.ACTION_TIMEZONE_CHANGED)
      addAction(Intent.ACTION_SHUTDOWN)
      addAction(Intent.ACTION_LOCALE_CHANGED)
    }
    val packages = IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addAction(Intent.ACTION_PACKAGE_CHANGED)
      addDataScheme("package")
    }
    // Protected system broadcasts only, so no exported flag is needed on
    // 33+ (the flag is required for receivers that also take app broadcasts).
    context.registerReceiver(receiver, filter, null, handler)
    context.registerReceiver(receiver, packages, null, handler)
    for (op in ENFORCEMENT_OPS) {
      try {
        appOps?.startWatchingMode(op, context.packageName, appOpsListener)
      } catch (e: Exception) {
        Log.w(TAG, "cannot watch $op: ${e.javaClass.simpleName}: ${e.message}")
      }
    }
    handler.postDelayed(heartbeat, EnforcementGapTracker.HEARTBEAT_INTERVAL_MS)
    ShieldActivity.shownListener = { target -> handler.post { onShieldShown(target) } }
    // A press that lifted its target (unblock, snooze) is read back here,
    // ahead of the next poll, so the app the user is returned to is not
    // shielded again from a stale lifted set.
    ShieldActivity.refreshListener = { reason -> refresh(reason) }
  }

  /**
   * A start intent arrived: re-reads the manual block and the monitoring
   * state from the store, answers the history requests a previous process
   * left pending, and runs one step (schedules evaluated at now, sources
   * recomposed, shields applied, alarm armed, polling started or stopped).
   */
  fun refresh(reason: String) = handler.post {
    guarded("refresh") {
      reload()
      zone = TimeZone.getDefault()
      step(reason, reloaded = true)
    }
    EngineWatchdog.arm(context, power.isInteractive)
  }

  /**
   * Called from the service's `onDestroy` (main thread). The engine thread
   * writes its last state and is joined before this returns, so a service
   * started again right after (a release followed by a block) never races
   * a dying loop's persist with its own.
   */
  fun stop() {
    handler.post {
      guarded("stop") {
        stopPolling("stop")
        handler.removeCallbacks(settleAfterScreenOff)
        handler.removeCallbacks(wake)
        handler.removeCallbacks(heartbeat)
        persist("stop", force = true)
        persistMeters("stop")
        // The alarm outlives the service only while something is scheduled;
        // it is then one path that brings the engine back (through the
        // controller's revival when the background start is refused). The
        // watchdog outlives a service destroyed while the engine is still
        // wanted, and goes with a release.
        arm(monitoring.nextWakeMs(System.currentTimeMillis(), zone))
        handler.removeCallbacks(wake)
        if (!store.engineDesired) EngineWatchdog.cancel(context)
        ShieldActivity.dismiss()
        ShieldWarmer.cancel()
      }
      thread.quitSafely()
    }
    ShieldActivity.shownListener = null
    ShieldActivity.refreshListener = null
    try {
      context.unregisterReceiver(receiver)
    } catch (e: IllegalArgumentException) {
      // not registered: nothing to do
    }
    try {
      appOps?.stopWatchingMode(appOpsListener)
    } catch (e: Exception) {
      // never watched: nothing to do
    }
    try {
      thread.join(STOP_JOIN_MS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  /** One beat of the gap tracker from this process, with the engine's own permission check. */
  private fun beat(reason: String) = gaps.heartbeat(Process.myPid(), reason)

  // MARK: - The monitoring step

  private fun reload() {
    store.read { document ->
      manual = store.manualBlock
      monitoring = store.monitoring
      readUnlockState(document)
    }
    ShieldCache.refresh(store)
    persisted = monitoring
    blocks = null
  }

  /**
   * The unlock state as the document holds it, stale records included:
   * [expireMeters] (the first thing every [act] does) drops a spent or
   * expired budget from the document and from here, so nothing acts on one.
   */
  private fun readUnlockState(document: org.json.JSONObject) {
    grant = UnlockGrant.read(document)
    snoozes = ShieldSnoozes.read(document)
    lifts = ShieldLifts.read(document)
    lastUnlockReconcileAt = SystemClock.uptimeMillis()
  }

  /**
   * The engine's own look at the unlock state, every [PERSIST_MS] while
   * one is in force: the module's writes reach the engine through the SYNC
   * start intent, which the system can refuse from the background, and a
   * relock that is not seen keeps everything suspended. A grant, snooze
   * or lift the document no longer holds (or holds anew) is adopted here.
   */
  private fun reconcileUnlockState(reason: String) {
    if (grant == null && snoozes.isEmpty() && lifts.isEmpty) return
    if (SystemClock.uptimeMillis() - lastUnlockReconcileAt < PERSIST_MS) return
    val before = Triple(grant?.id, snoozes.map { it.id }, lifts)
    store.read { document ->
      val stored = UnlockGrant.read(document)
      // The engine's meter is ahead of the document for the same grant; adopt only another grant or none.
      if (stored?.id != grant?.id) grant = stored
      val storedSnoozes = ShieldSnoozes.read(document).associateBy { it.id }
      snoozes = storedSnoozes.values.map { stored -> snoozes.firstOrNull { it.id == stored.id } ?: stored }
      lifts = ShieldLifts.read(document)
    }
    lastUnlockReconcileAt = SystemClock.uptimeMillis()
    if (before != Triple(grant?.id, snoozes.map { it.id }, lifts)) {
      Log.i(TAG, "unlock state changed under the engine ($reason); re-applying")
      act("reconcile:$reason", System.currentTimeMillis())
    }
  }

  /**
   * One evaluation at now: the stream since the cursor is fed to the
   * monitoring state, the durable outcome is persisted, then acted on.
   * [usageEvents] is the stream a poll already read (null: read it here).
   */
  private fun step(reason: String, reloaded: Boolean = false, usageEvents: List<UsageEvent>? = null, queriedUntilMs: Long? = null) {
    var events = usageEvents
    var first = reloaded
    for (attempt in 0..1) {
      val now = System.currentTimeMillis()
      // Events are visible up to the instant the stream was queried (minus the lag), never beyond it.
      val upto = (if (queriedUntilMs != null && events != null) minOf(queriedUntilMs, now) else now) - LAG_MS
      if (monitoring.activities.isNotEmpty()) {
        if (monitoring.foreground.cursorMs == null) {
          // Never fed: seed the resumed set from a lookback and re-arm the
          // thresholds against it (nothing in the lookback counts as live use).
          val lookback = readUsage(now - LOOKBACK_MS, now)
          monitoring = monitoring.seed(lookback, now - LAG_MS, selections::get)
          events = emptyList()
          first = true
        }
        if (events == null) events = readUsage(monitoring.foreground.cursorMs!!, now)
      }
      val result = monitoring.advance(now, zone, events ?: emptyList(), upto, selections::get)
      monitoring = result.state
      val due = result.durable || first || now - lastPersistAt >= PERSIST_MS
      if (due && !persist(reason, force = result.durable || first)) {
        // The module changed the document under us: the step runs again from it.
        events = null
        continue
      }
      if (result.recorded.isNotEmpty()) announce(result.recorded, reason)
      // Every request still unanswered: the ones this step armed, and the
      // ones a previous process left (answered after the seed, never before).
      answerHistory(monitoring.pendingHistoryRequests(), reason)
      act(reason, now)
      return
    }
    Log.w(TAG, "step($reason): the document kept changing; acting on the reloaded state")
    act(reason, System.currentTimeMillis())
  }

  /**
   * Writes the in-memory monitoring state to the document unless the
   * module changed it meanwhile (`revision`), in which case the stored
   * state is taken instead and false is returned. Usage totals alone are
   * written at the [PERSIST_MS] cadence (and when [force]).
   */
  private fun persist(reason: String, force: Boolean): Boolean {
    if (!force && monitoring == persisted) return true
    var reloaded = false
    store.update { document ->
      val stored = MonitoringDocument.read(document)
      if (stored.revision != monitoring.revision) {
        Log.i(TAG, "document changed by the module (revision ${monitoring.revision} → ${stored.revision}); reloading ($reason)")
        monitoring = stored
        manual = store.manualBlock
        readUnlockState(document)
        reloaded = true
      } else {
        MonitoringDocument.write(document, monitoring)
      }
    }
    persisted = monitoring
    lastPersistAt = System.currentTimeMillis()
    blocks = null
    return !reloaded
  }

  /**
   * The clock stepped by [deltaMs]: shifts whichever state is current — the
   * stored one when the module wrote meanwhile — in one update, so the shift
   * is never lost to a reload (a forward step replayed from an unshifted
   * cursor would count the renamed events a second time).
   */
  private fun shiftClock(deltaMs: Long) {
    store.update { document ->
      val stored = MonitoringDocument.read(document)
      if (stored.revision != monitoring.revision) {
        manual = store.manualBlock
        monitoring = stored
      }
      monitoring = monitoring.clockShifted(deltaMs)
      MonitoringDocument.write(document, monitoring)
      // The budgets' instants move too (a clock set back must not hand out
      // the difference as free time): shifted on the document's records,
      // which the module may have replaced meanwhile, then read back.
      UnlockGrant.read(document)?.let { UnlockGrant.write(document, it.shifted(deltaMs)) }
      ShieldSnoozes.write(document, ShieldSnoozes.read(document).map { it.shifted(deltaMs) })
      readUnlockState(document)
    }
    persisted = monitoring
    lastPersistAt = System.currentTimeMillis()
    blocks = null
  }

  private fun announce(recorded: List<MonitorEvent>, reason: String) {
    for (event in recorded) Log.i(TAG, "monitor event ${event.eventType}${event.eventName?.let { ":$it" } ?: ""} activity=${event.activityName} at=${event.timestampMs} id=${event.id} ($reason)")
    MonitoringController.announceEvents(context)
  }

  /** Usage before an accumulator existed, measured from the stream and added once (`backfill`). */
  private fun answerHistory(requests: List<HistoryRequest>, reason: String) {
    if (requests.isEmpty()) return
    val recorded = ArrayList<MonitorEvent>()
    for (request in requests) {
      val events = readUsage(request.fromMs - LOOKBACK_MS, request.toMs)
      val result = monitoring.backfill(request, events, System.currentTimeMillis(), selections::get)
      monitoring = result.state
      recorded.addAll(result.recorded)
      Log.i(TAG, "history ${request.activityName}/${request.eventName} [${request.fromMs}, ${request.toMs}) answered from ${events.size} events ($reason)")
    }
    if (persist("history:$reason", force = true) && recorded.isNotEmpty()) announce(recorded, reason)
  }

  /**
   * Composes the sources from the manual block and the monitoring state —
   * minus what the grant suspended, with the lifted set taken out by the
   * policy — and brings the shield, the polling and the alarm in line. A
   * change of the composed sources drops the shield's unblocks ("until a
   * block is applied again"); a change of the lifted set alone re-applies
   * the same sources (a shield over a lifted app goes, an app whose snooze
   * was spent is caught by the standing check).
   */
  private fun act(reason: String, now: Long) {
    expireMeters(now, reason)
    val current = monitoring.activeBlocks()
    val suspended = grant?.suspended?.takeIf { !it.isEmpty }
    val liftedNow = snoozed() + lifts.under(sources)
    if (current != blocks || suspended != appliedSuspension || liftedNow != lifted || lifts.apps != liftedApplied) {
      val before = sources
      val liftedBefore = lifted
      val all = BlockComposition.sources(manual, monitoring, selections::get)
      sources = if (suspended == null) all else BlockComposition.sources(manual, monitoring, selections::get, suspended)
      metered = BlockComposition.union(all) - BlockComposition.union(sources)
      blocks = current
      appliedSuspension = suspended
      ShieldActivity.sourcesKey = BlockComposition.fingerprint(sources)
      // An unblock pressed under another composition is void: "until a block is applied again".
      if (!lifts.isEmpty && lifts.under(sources).isEmpty()) dropLifts(reason)
      lifted = snoozed() + lifts.under(sources)
      liftedApplied = lifts.apps
      if (sources != before || lifted != liftedBefore) applySources(reason, before)
      if (grant != null) Log.i(TAG, "unlock in force ($reason): remaining=${grant!!.remainingSeconds(now, zone)} s metered=$metered")
      if (snoozes.isNotEmpty()) Log.i(TAG, "snoozes in force ($reason): ${snoozes.map { "${it.token}:${it.remainingSeconds}s" }}")
    }
    arm(
      listOfNotNull(
        monitoring.nextWakeMs(now, zone),
        // A budget is void at midnight: the composition is re-run then.
        grant?.let { UsageMeter.dayEndMs(it.grantedAt, zone) },
        snoozes.minOfOrNull { UsageMeter.dayEndMs(it.grantedAt, zone) },
      ).minOrNull(),
    )
    val observing = monitoring.observesUsage || grant != null || snoozes.isNotEmpty()
    val wanted = if (sources.isNotEmpty()) INTERVAL_MS else if (observing) THRESHOLD_POLL_MS else null
    if (wanted == null) {
      stopPolling(reason)
    } else {
      if (wanted != pollInterval) {
        pollInterval = wanted
        if (polling) Log.i(TAG, "poll interval $wanted ms ($reason)")
      }
      if (!polling) {
        if (power.isInteractive) startPolling(reason) else Log.i(TAG, "screen not interactive; polling waits for SCREEN_ON ($reason)")
      }
    }
  }

  private fun applySources(reason: String, before: List<BlockSource>) {
    if (sources.isEmpty()) {
      Log.i(TAG, "no block in force ($reason)")
      ShieldActivity.dismiss()
      return
    }
    reloadSets()
    reenforced.clear()
    evicted.clear()
    // What the first shield will need is loaded now, on the main thread, ahead of it.
    ShieldWarmer.warm(context, BlockComposition.union(sources), reason)
    // A shield over an app the new sources allow (or a snooze lifted) goes away at once.
    ShieldActivity.showing?.let { if (!ShieldPolicy.shields(it, sources, launchable, exempt, lifted)) ShieldActivity.dismiss() }
    Log.i(TAG, "block in force ($reason): sources=${sources.map { "${it.kind}:${it.mode}:${it.applications.size}" }} lifted=$lifted launchable=${launchable.size} exempt=${exempt.size}")
    // The sources changed while polling: the next poll replays the history
    // to find an app of the new sources already on screen (the standing check).
    if (before.isNotEmpty() && polling) restartWindow(SEED_MS)
  }

  /**
   * The inexact alarm at the next instant the schedules need evaluating
   * (an interval boundary, a warning, a threshold an open span would
   * reach), re-armed only when it moves. It wakes the engine through a
   * broadcast to [ScreenTimeRestartReceiver], which sends the same SYNC
   * start intent as a boot does; `setAndAllowWhileIdle` fires in Doze too
   * (within the system's window), and needs no permission.
   */
  private fun arm(nextWakeMs: Long?) {
    if (nextWakeMs == armedWakeMs) return
    handler.removeCallbacks(wake)
    if (nextWakeMs != null) handler.postDelayed(wake, (nextWakeMs - System.currentTimeMillis()).coerceAtLeast(0L))
    val operation = PendingIntent.getBroadcast(
      context,
      0,
      Intent(context, ScreenTimeRestartReceiver::class.java).setAction(ScreenTimeRestartReceiver.ACTION_WAKE),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    if (nextWakeMs == null) {
      alarms.cancel(operation)
      Log.i(TAG, "alarm cancelled")
    } else {
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWakeMs, operation)
      Log.i(TAG, "alarm armed at $nextWakeMs (in ${nextWakeMs - System.currentTimeMillis()} ms)")
    }
    armedWakeMs = nextWakeMs
  }

  // MARK: - Polling

  private fun startPolling(why: String) {
    if (polling) return
    if (sources.isNotEmpty() && !hasOverlayPermission(why)) return
    polling = true
    // A fresh window reaching hours back, so the tracker rebuilds what is on
    // screen right now from the whole history of the activities still alive:
    // an app the user was already in when the block started (resumed), and
    // a picture-in-picture window open before the block (paused, never
    // stopped; the measured "PiP already open" bypass of).
    // Every activity that left the screen has its PAUSED/STOPPED in the same
    // window, so the replay ends on the current state. Once per block change
    // and screen-on; the polls after it read a 2 s window.
    restartWindow(SEED_MS)
    Log.i(TAG, "poll on ($why) interval=$pollInterval ms")
    handler.removeCallbacks(poll)
    pollDueAt = 0L
    handler.post(poll)
  }

  private fun stopPolling(why: String) {
    if (!polling) return
    polling = false
    handler.removeCallbacks(poll)
    Log.i(TAG, "poll off ($why) polls=$polls")
  }

  private fun restartWindow(reachMs: Long) {
    lastEnd = System.currentTimeMillis() - reachMs
    seen.clear()
    tracker.reset()
    seeding = true
  }

  private fun reloadSets() {
    launchable = LaunchableApps.packages(context)
    exempt = SystemExemptions.read(context)
  }

  /**
   * Without the overlay permission the activity start from this process is
   * refused (no BAL exemption): polling would only burn
   * battery. Checked at every start and before every shield (the permission
   * can be revoked under a block); recorded once in the failures (the gap
   * itself is the tracker's, from the beat the AppOps watcher triggers).
   */
  private fun hasOverlayPermission(why: String): Boolean {
    if (Settings.canDrawOverlays(context)) {
      overlayMissingRecorded = false
      return true
    }
    if (!overlayMissingRecorded) {
      overlayMissingRecorded = true
      Log.w(TAG, "overlay permission missing: the engine cannot shield; not polling ($why)")
      store.recordEngineFailure("shield:overlay-permission", "Display over other apps is off")
    }
    return false
  }

  private fun pollOnce() {
    polls++
    val now = System.currentTimeMillis()
    if (now < lastEnd) {
      Log.i(TAG, "clock stepped back by ${lastEnd - now} ms; window restarted")
      restartWindow(OVERLAP_MS)
    }
    val trackerBegin = minOf(lastEnd - OVERLAP_MS, now - 1)
    // One query serves both trackers: the shield's (from its window) and
    // the monitoring state's (from its persisted cursor; its own cursor
    // filter ignores what it was fed already).
    val cursor = monitoring.foreground.cursorMs.takeIf { monitoring.activities.isNotEmpty() }
    val begin = if (cursor != null) minOf(trackerBegin, cursor) else trackerBegin
    val queryStarted = SystemClock.uptimeMillis()
    val events = query(begin, now) ?: return
    val queryMs = SystemClock.uptimeMillis() - queryStarted
    if (recentPolls.size >= RECENT_POLLS) recentPolls.removeFirst()
    recentPolls.addLast("$pollLateMs/$queryMs")
    if (pollLateMs > SLOW_POLL_MS || queryMs > SLOW_POLL_MS) Log.i(TAG, "slow poll: late=$pollLateMs ms query=$queryMs ms window=${now - begin} ms")
    val event = UsageEvents.Event()
    val cameForward = LinkedHashMap<String, Long>()
    val usage = ArrayList<UsageEvent>()
    while (events.hasNextEvent()) {
      events.getNextEvent(event)
      val type = event.eventType
      if (cursor != null) {
        val kind = UsageEventKind.of(type)
        if (kind != UsageEventKind.OTHER && event.timeStamp > cursor) usage.add(UsageEvent(event.timeStamp, event.packageName, kind, event.className))
      }
      if (type != UsageEvents.Event.ACTIVITY_RESUMED && type != UsageEvents.Event.ACTIVITY_PAUSED && type != ACTIVITY_STOPPED && type != ACTIVITY_DESTROYED &&
        type != ForegroundTracker.DEVICE_SHUTDOWN && type != ForegroundTracker.DEVICE_STARTUP
      ) continue
      if (event.timeStamp < trackerBegin) continue
      val key = "${event.timeStamp}|$type|${event.packageName}/${event.className}"
      if (seen.put(key, event.timeStamp) != null) continue
      // A fresh event for a package resets its re-enforcement budget.
      reenforced.remove(event.packageName)
      if (type == UsageEvents.Event.ACTIVITY_RESUMED) evicted.remove(event.packageName)
      tracker.apply(type, event.packageName, event.className, event.timeStamp)?.let { cameForward.putIfAbsent(it, event.timeStamp) }
    }
    lastEnd = now
    if (seen.size > SEEN_CAP) seen.values.removeAll { it < now - 2 * OVERLAP_MS }

    // The schedules and thresholds at now, from the stream just read.
    // Guarded on its own: a monitoring failure must not keep the shield from the targets below.
    if (monitoring.activities.isNotEmpty()) guarded("poll step") { step("poll", usageEvents = if (cursor != null) usage else null, queriedUntilMs = now) }
    // The budgets, from the same tracker view. Guarded on its own, like the step.
    if (grant != null || snoozes.isNotEmpty()) guarded("meter") { meter("poll") }
    guarded("reconcile") { reconcileUnlockState("poll") }
    // A `setShieldConfig` the module wrote since the last poll is read here (one stat otherwise), not in the shield's onCreate.
    guarded("shield cache") { if (ShieldCache.refresh(store)) Log.i(TAG, "shield configs re-read (document changed)") }

    if (sources.isEmpty()) return
    // The replay that rebuilt the state is history: an app that came forward
    // minutes ago and left again must not be shielded now (it was, once:
    // a shield over nothing, from an 8-minute-old event). What is on screen
    // after the replay is handled by the standing check below. Likewise an
    // app that came and went within one poll (a trampoline) is not shielded:
    // the shield would land on its destination.
    val replay = seeding
    seeding = false
    val visibleNow = tracker.visible
    val targets = if (replay) emptySet() else ShieldPolicy.targets(cameForward.keys.filterTo(LinkedHashSet()) { it in visibleNow }, sources, launchable, exempt, lifted)
    if (targets.isNotEmpty()) {
      val target = targets.first()
      Log.i(TAG, "detected target=$target eventAt=${cameForward[target]} ageMs=${now - cameForward[target]!!} pollLateMs=$pollLateMs queryMs=$queryMs recentPolls=$recentPolls")
      shield(target)
      return
    }
    // Still on screen with no shield on it: the block started while the app
    // was already open (fullscreen, or in a picture-in-picture window, which
    // is paused but visible), or the shield was dismissed under it. The
    // shield goes up; its follow-up evicts a PiP window. Capped per target
    // until a fresh event arrives, so a stale entry never relaunches the
    // shield every second over whatever is really in front.
    // A paused app counts only once its pause has settled ([ForegroundTracker.visibleAt]): read
    // mid-switch, the app leaving looks like a PiP window.
    val standing = ShieldPolicy.targets(tracker.visibleAt(now, PAUSE_SETTLE_MS), sources, launchable, exempt, lifted)
    val up = SystemClock.uptimeMillis()
    if (standing.isNotEmpty() && ShieldActivity.showing == null && up > evictingUntil && (replay || up - lastShieldAt > REENFORCE_MS)) {
      val target = standing.first()
      val count = reenforced[target] ?: 0
      if (count < MAX_REENFORCE) {
        reenforced[target] = count + 1
        Log.i(TAG, "re-enforce target=$target eventAt=$now visible=${visibleNow} resumed=${tracker.resumed}")
        shield(target)
      }
    }
  }

  /** The stream in `[begin, end]` as the monitoring state reads it (every kind it knows, device-level ones included). */
  private fun readUsage(begin: Long, end: Long): List<UsageEvent> {
    val result = ArrayList<UsageEvent>()
    val events = query(minOf(begin, end - 1), end) ?: return result
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
      events.getNextEvent(event)
      val kind = UsageEventKind.of(event.eventType)
      if (kind != UsageEventKind.OTHER) result.add(UsageEvent(event.timeStamp, event.packageName, kind, event.className))
    }
    return result
  }

  private fun query(begin: Long, end: Long): UsageEvents? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      val query = UsageEventsQuery.Builder(begin, end)
        .setEventTypes(
          UsageEvents.Event.ACTIVITY_RESUMED,
          UsageEvents.Event.ACTIVITY_PAUSED,
          ACTIVITY_STOPPED,
          UsageEvents.Event.SCREEN_INTERACTIVE,
          UsageEvents.Event.SCREEN_NON_INTERACTIVE,
          UsageEvents.Event.KEYGUARD_SHOWN,
          UsageEvents.Event.KEYGUARD_HIDDEN,
          UsageEvents.Event.DEVICE_SHUTDOWN,
          UsageEvents.Event.DEVICE_STARTUP,
        )
        .build()
      return usage.queryEvents(query)
    }
    @Suppress("DEPRECATION")
    return usage.queryEvents(begin, end)
  }

  private fun shield(target: String) {
    lastShieldAt = SystemClock.uptimeMillis()
    if (!hasOverlayPermission("shield")) {
      stopPolling("overlay permission revoked")
      return
    }
    if (isLockTaskActive()) {
      // Screen pinning: the system refuses every other task while an app is
      // pinned, so the shield cannot start. Recorded once per pinned
      // session in the failures; the pin ends with the user's gesture,
      // after which the next event is caught normally.
      if (!lockTaskRecorded) {
        lockTaskRecorded = true
        Log.w(TAG, "screen pinning active: the shield cannot start over $target")
        store.recordEngineFailure("shield:lock-task", "An app is pinned (screen pinning)")
      }
      return
    }
    lockTaskRecorded = false
    // The owner's configuration draws the shield: threshold > latest schedule > manual > default.
    val owner = ShieldPolicy.owner(target, sources)
    ShieldActivity.launch(context, target, owner = owner?.kind?.activityName)
  }

  private fun isLockTaskActive(): Boolean {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return false
    return manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
  }

  // MARK: - Metering (the temporary unlock and the snoozes)

  /** The apps the snoozes in force lift. */
  private fun snoozed(): Set<String> = snoozes.mapTo(LinkedHashSet()) { it.token }

  /**
   * One tick of every meter from the tracker's view: the grant is charged
   * while one of [metered] is resumed with the screen on, each snooze while
   * its own target is. Persisted when worth it (once a second per budget in
   * use, at once when one is spent); a spent budget recomposes the sources.
   */
  private fun meter(reason: String, interactive: Boolean = power.isInteractive) {
    val grantBefore = grant
    val snoozesBefore = snoozes
    if (grantBefore == null && snoozesBefore.isEmpty()) return
    val now = System.currentTimeMillis()
    val resumed = tracker.resumed
    fun sampleFor(packages: Set<String>) = UsageSample(now, resumed.firstOrNull { it in packages } ?: resumed.firstOrNull(), interactive)
    val grantNow = grantBefore?.observing(sampleFor(metered), metered)
    val observed = ShieldSnoozes.observing(snoozesBefore) { sampleFor(setOf(it.token)) }
    grant = grantNow
    snoozes = observed.snoozes
    val grantDue = grantBefore != null && grantNow != null && (grantNow.isSpent || grantNow.isWorthPersistingOver(grantBefore))
    if (!grantDue && !observed.changed) return
    for (spent in observed.spent) Log.i(TAG, "snooze spent target=${spent.token} consumed=${spent.consumedSeconds} s ($reason)")
    if (grantNow?.isSpent == true) Log.i(TAG, "unlock spent: budget=${grantNow.budgetSeconds} s consumed=${grantNow.consumedSeconds} s ($reason)")
    persistMeters(reason, observed)
    act("meter:$reason", now)
  }

  /**
   * Writes the meters back over the document another process may have
   * changed meanwhile: a renewal (another id) is taken, a relock keeps the
   * grant gone, a spent budget leaves. The in-memory state is then what the
   * document holds.
   */
  private fun persistMeters(reason: String, observed: ShieldSnoozes.Observed = ShieldSnoozes.Observed(snoozes, emptyList(), changed = false)) {
    val metered = grant
    val now = System.currentTimeMillis()
    store.update { document ->
      if (metered != null) UnlockGrant.write(document, UnlockGrant.merging(UnlockGrant.read(document), metered))
      ShieldSnoozes.write(document, ShieldSnoozes.merging(ShieldSnoozes.read(document), observed))
      readUnlockState(document)
    }
    Log.i(TAG, "meters persisted ($reason): unlock=${grant?.let { "${it.remainingSeconds(now, zone)} s left" } ?: "none"} snoozes=${snoozes.size}")
  }

  /**
   * A budget that is void — the day rolled over, or a persisted grant
   * came back spent — leaves the document here, whether or not a poll
   * ran: the wake at midnight and every step pass through.
   */
  private fun expireMeters(now: Long, reason: String) {
    val staleGrant = grant?.takeIf { !it.isActive(now, zone) }
    val staleSnoozes = snoozes.filter { it.isSpent || !UsageMeter.isSameDay(it.grantedAt, now, zone) }
    if (staleGrant == null && staleSnoozes.isEmpty()) return
    store.update { document ->
      if (staleGrant != null && UnlockGrant.read(document)?.id == staleGrant.id) {
        Log.i(TAG, "unlock expired ($reason): budget=${staleGrant.budgetSeconds} s consumed=${staleGrant.consumedSeconds} s")
        UnlockGrant.write(document, null)
      }
      if (staleSnoozes.isNotEmpty()) {
        val live = ShieldSnoozes.read(document).filter { stored -> staleSnoozes.none { it.id == stored.id } }
        Log.i(TAG, "snoozes expired ($reason): ${staleSnoozes.map { it.token }}")
        ShieldSnoozes.write(document, live)
      }
      readUnlockState(document)
    }
  }

  /**
   * The composed sources changed: the unblocks pressed under the previous
   * composition end ("until a block is applied again"). Only those: one the
   * shield recorded under the current composition in between is kept.
   */
  private fun dropLifts(reason: String) {
    Log.i(TAG, "lifts dropped ($reason): ${lifts.apps}")
    store.update { document ->
      if (ShieldLifts.read(document).under(sources).isEmpty()) ShieldLifts.write(document, ShieldLifts.NONE)
      readUnlockState(document)
    }
  }

  // MARK: - Follow-up after the shield drew

  private fun onShieldShown(target: String) {
    handler.postDelayed({ followUp(target, confirmed = false) }, FOLLOW_UP_MS)
  }

  private fun followUp(target: String, confirmed: Boolean) {
    if (sources.isEmpty() || ShieldActivity.showing != target) return
    val resumed = target in tracker.resumed
    val visible = target in tracker.visible
    when {
      resumed -> {
        // A pane beside the shield (a tablet or desktop layout the shield
        // could not take over): Home ends the layout, the app is stopped.
        Log.i(TAG, "follow-up target=$target still resumed beside the shield (multi-window); sending Home")
        goHome()
      }
      !visible -> Log.i(TAG, "follow-up target=$target covered")
      !confirmed -> {
        // Paused but not stopped can also be a stop event still in flight:
        // confirm before evicting, so an allowed app's own PiP window is
        // never taken by mistake.
        handler.postDelayed({ followUp(target, confirmed = true) }, CONFIRM_MS)
      }
      else -> {
        val count = evicted[target] ?: 0
        if (count >= MAX_EVICTIONS) {
          Log.w(TAG, "follow-up target=$target still in picture-in-picture after $count evictions; giving up until its next resume")
          return
        }
        evicted[target] = count + 1
        Log.i(TAG, "follow-up target=$target paused but visible: picture-in-picture; evicting (${count + 1}/$MAX_EVICTIONS)")
        evictingUntil = SystemClock.uptimeMillis() + EVICT_MS
        PipEvictorActivity.launch(context, target)
      }
    }
  }

  private fun goHome() {
    try {
      context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
      Log.w(TAG, "home intent failed: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  /** Runs [block] on the engine thread without letting an exception take the process (and the shield) down. */
  private inline fun guarded(what: String, block: () -> Unit) {
    try {
      block()
    } catch (t: Throwable) {
      Log.w(TAG, "$what failed: ${t.javaClass.simpleName}: ${t.message}")
    }
  }

  companion object {
    const val TAG = EngineController.TAG
    const val INTERVAL_MS = 100L
    /** With nothing to shield and a usage threshold to observe, the stream is read once a second. */
    const val THRESHOLD_POLL_MS = 1_000L
    const val OVERLAP_MS = 2_000L
    /** Events at or before now minus this are taken as visible in the stream (the pipeline lag). */
    const val LAG_MS = 2_000L
    /** The lookback that seeds a never-fed usage tracker, and the margin before a history window (an open app's resume). */
    const val LOOKBACK_MS = 60 * 60 * 1_000L
    /** How often the usage totals alone are written to the document. */
    const val PERSIST_MS = 30_000L
    const val SEED_MS = 6 * 60 * 60 * 1_000L
    const val REENFORCE_MS = 1_000L

    /**
     * How old a pause must be before the standing check takes a paused, unstopped app as on screen
     * (PiP): an app switch's STOPPED lands within about a second, plus the stream's lag ([LAG_MS]).
     */
    const val PAUSE_SETTLE_MS = 3_000L
    const val FOLLOW_UP_MS = 800L
    const val CONFIRM_MS = 500L
    const val EVICT_MS = 2_000L
    const val MAX_BACKOFF_MS = 5_000L
    /** How long `stop()` waits for the engine thread's last persist. */
    const val STOP_JOIN_MS = 3_000L
    const val MAX_REENFORCE = 3
    const val MAX_EVICTIONS = 2
    /** The AppOps behind the enforcement steps (`EnforcementGapTracker.ENFORCEMENT_STEPS`), watched for a revocation under a block. */
    private val ENFORCEMENT_OPS = listOf(AppOpsManager.OPSTR_GET_USAGE_STATS, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)
    private const val SEEN_CAP = 512
    /** How many polls' timings are kept for the detection log, and what counts as a slow one. */
    private const val RECENT_POLLS = 8
    private const val SLOW_POLL_MS = 50L

    // Public since API 29 (`ACTIVITY_STOPPED`); `ACTIVITY_DESTROYED` is
    // reported but not in the public API. Same values as ForegroundTracker.
    private const val ACTIVITY_STOPPED = ForegroundTracker.ACTIVITY_STOPPED
    private const val ACTIVITY_DESTROYED = ForegroundTracker.ACTIVITY_DESTROYED
  }
}
