package expo.modules.screentime

import android.app.ActivityManager
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
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * The detection loop of the engine (`:screentime` process), the usageStats
 * engine stage 2 measured (measured: p95 176 ms at a 100 ms poll, about
 * 1.2 ms of CPU per poll, 0 leaks in 100 launches):
 *
 * - `queryEvents` every [INTERVAL_MS] on its own [HandlerThread], **only
 *   while the screen is interactive and a block is in force**: polling
 *   stops on `SCREEN_OFF` and when the block is lifted, resumes on
 *   `SCREEN_ON` / `USER_PRESENT` and when a block arrives. No block, no
 *   screen, no poll (the battery figure of §10.1 D assumes exactly this).
 * - Each query re-reads [OVERLAP_MS] before the last end (events land after
 *   two handler hops, so one can carry a timestamp earlier than the previous
 *   `end`) and de-duplicates by (timestamp, type, activity).
 * - One bad poll never kills the engine: the 24 h run of §10.1 E died on an
 *   `Invalid period` when the wall clock stepped back. The window is clamped,
 *   a clock step (`TIME_SET`, `TIMEZONE_CHANGED`, or `now < lastEnd`)
 *   restarts it, every poll runs in a try/catch, and repeated failures back
 *   off instead of retrying at 10 Hz.
 * - The decision is [ShieldPolicy], pure: the loop only feeds it the
 *   [ForegroundTracker]'s view, the block read from the [StateStore] and
 *   the exemptions, and launches the [ShieldActivity] for what comes back.
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
 * The block is re-read on every [refresh] (a start intent from the module
 * process: `blockSelection`, `unblockAll`, launch, boot) and the launchable
 * and exempt sets with it, plus on package changes; never per poll.
 */
internal class EngineLoop(private val context: Context) {
  private val thread = HandlerThread("expo-st-engine").apply { start() }
  private val handler = Handler(thread.looper)
  private val usage = context.getSystemService(UsageStatsManager::class.java)
  private val power = context.getSystemService(PowerManager::class.java)
  private val store = StateStore(context)
  // ACTIVITY_STOPPED exists from API 29; below, a paused activity is gone.
  private val tracker = ForegroundTracker(tracksStopped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

  // Engine-thread state.
  private var block: ActiveBlock? = null
  private var launchable: Set<String> = emptySet()
  private var exempt: Set<String> = emptySet()
  private var polling = false
  /** The next poll replays history to rebuild the state: nothing in it "came forward" now. */
  private var seeding = false
  private var lastEnd = 0L
  private val seen = HashMap<String, Long>()
  private var lastShieldAt = 0L
  private var errorStreak = 0
  private var polls = 0L
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
      var delay = INTERVAL_MS
      try {
        pollOnce()
        errorStreak = 0
      } catch (t: Throwable) {
        errorStreak++
        // A short window (not the long seed: an oversized query could be
        // the failure itself) and an exponential back-off, capped at 5 s.
        delay = minOf(INTERVAL_MS shl minOf(errorStreak, 6), MAX_BACKOFF_MS)
        Log.w(TAG, "poll error ${t.javaClass.simpleName}: ${t.message}; next poll in $delay ms")
        restartWindow(OVERLAP_MS)
      }
      handler.postDelayed(this, delay)
    }
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
      guarded("receiver ${intent.action}") {
        when (intent.action) {
          Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> if (block != null) startPolling(intent.action!!.substringAfterLast('.'))
          Intent.ACTION_SCREEN_OFF -> stopPolling("SCREEN_OFF")
          Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
            Log.i(TAG, "clock changed (${intent.action}); window restarted")
            restartWindow(OVERLAP_MS)
          }
          Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_CHANGED -> if (block != null) reloadSets()
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
    ShieldActivity.shownListener = { target -> handler.post { onShieldShown(target) } }
  }

  /** Re-reads the block from the store and starts or stops polling accordingly. */
  fun refresh(reason: String) = handler.post {
    guarded("refresh") {
      val before = block
      block = BlockController.activeBlock(store)?.takeIf { it.shieldsSomething }
      val block = block
      if (block == null) {
        Log.i(TAG, "no block in force ($reason)")
        stopPolling(reason)
        ShieldActivity.dismiss()
        return@guarded
      }
      reloadSets()
      reenforced.clear()
      evicted.clear()
      // A shield over an app the new block allows goes away at once.
      ShieldActivity.showing?.let { if (!ShieldPolicy.shields(it, block, launchable, exempt)) ShieldActivity.dismiss() }
      Log.i(TAG, "block in force ($reason): mode=${block.mode} apps=${block.applications.size} launchable=${launchable.size} exempt=${exempt.size}")
      if (before != null && polling) restartWindow(SEED_MS) else if (power.isInteractive) startPolling(reason) else Log.i(TAG, "screen not interactive; polling waits for SCREEN_ON")
    }
  }

  fun stop() {
    handler.post {
      stopPolling("stop")
      ShieldActivity.dismiss()
      thread.quitSafely()
    }
    ShieldActivity.shownListener = null
    try {
      context.unregisterReceiver(receiver)
    } catch (e: IllegalArgumentException) {
      // not registered: nothing to do
    }
  }

  // MARK: - Polling

  private fun startPolling(why: String) {
    if (polling) return
    if (!hasOverlayPermission(why)) return
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
    Log.i(TAG, "poll on ($why)")
    handler.removeCallbacks(poll)
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
   * can be revoked under a block); recorded once for getEnforcementGaps
   *.
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
    val begin = minOf(lastEnd - OVERLAP_MS, now - 1)
    val events = query(begin, now) ?: return
    val event = UsageEvents.Event()
    val cameForward = LinkedHashMap<String, Long>()
    while (events.hasNextEvent()) {
      events.getNextEvent(event)
      val type = event.eventType
      if (type != UsageEvents.Event.ACTIVITY_RESUMED && type != UsageEvents.Event.ACTIVITY_PAUSED && type != ACTIVITY_STOPPED && type != ACTIVITY_DESTROYED) continue
      val key = "${event.timeStamp}|$type|${event.packageName}/${event.className}"
      if (seen.put(key, event.timeStamp) != null) continue
      // A fresh event for a package resets its re-enforcement budget.
      reenforced.remove(event.packageName)
      if (type == UsageEvents.Event.ACTIVITY_RESUMED) evicted.remove(event.packageName)
      tracker.apply(type, event.packageName, event.className)?.let { cameForward.putIfAbsent(it, event.timeStamp) }
    }
    lastEnd = now
    if (seen.size > SEEN_CAP) seen.values.removeAll { it < now - 2 * OVERLAP_MS }

    val block = block ?: return
    // The replay that rebuilt the state is history: an app that came forward
    // minutes ago and left again must not be shielded now (it was, once:
    // a shield over nothing, from an 8-minute-old event). What is on screen
    // after the replay is handled by the standing check below. Likewise an
    // app that came and went within one poll (a trampoline) is not shielded:
    // the shield would land on its destination.
    val replay = seeding
    seeding = false
    val visibleNow = tracker.visible
    val targets = if (replay) emptySet() else ShieldPolicy.targets(cameForward.keys.filterTo(LinkedHashSet()) { it in visibleNow }, block, launchable, exempt)
    if (targets.isNotEmpty()) {
      val target = targets.first()
      Log.i(TAG, "detected target=$target eventAt=${cameForward[target]} ageMs=${now - cameForward[target]!!}")
      shield(target)
      return
    }
    // Still on screen with no shield on it: the block started while the app
    // was already open (fullscreen, or in a picture-in-picture window, which
    // is paused but visible), or the shield was dismissed under it. The
    // shield goes up; its follow-up evicts a PiP window. Capped per target
    // until a fresh event arrives, so a stale entry never relaunches the
    // shield every second over whatever is really in front.
    val standing = ShieldPolicy.targets(visibleNow, block, launchable, exempt)
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

  private fun query(begin: Long, end: Long): UsageEvents? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      val query = UsageEventsQuery.Builder(begin, end)
        .setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED, ACTIVITY_STOPPED)
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
      // session for getEnforcementGaps; the pin ends with the
      // user's gesture, after which the next event is caught normally.
      if (!lockTaskRecorded) {
        lockTaskRecorded = true
        Log.w(TAG, "screen pinning active: the shield cannot start over $target")
        store.recordEngineFailure("shield:lock-task", "An app is pinned (screen pinning)")
      }
      return
    }
    lockTaskRecorded = false
    ShieldActivity.launch(context, target, owner = null)
  }

  private fun isLockTaskActive(): Boolean {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return false
    return manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
  }

  // MARK: - Follow-up after the shield drew

  private fun onShieldShown(target: String) {
    handler.postDelayed({ followUp(target, confirmed = false) }, FOLLOW_UP_MS)
  }

  private fun followUp(target: String, confirmed: Boolean) {
    if (block == null || ShieldActivity.showing != target) return
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
    const val OVERLAP_MS = 2_000L
    const val SEED_MS = 6 * 60 * 60 * 1_000L
    const val REENFORCE_MS = 1_000L
    const val FOLLOW_UP_MS = 800L
    const val CONFIRM_MS = 500L
    const val EVICT_MS = 2_000L
    const val MAX_BACKOFF_MS = 5_000L
    const val MAX_REENFORCE = 3
    const val MAX_EVICTIONS = 2
    private const val SEEN_CAP = 512

    // Public since API 29 (`ACTIVITY_STOPPED`); `ACTIVITY_DESTROYED` is
    // reported but not in the public API. Same values as ForegroundTracker.
    private const val ACTIVITY_STOPPED = ForegroundTracker.ACTIVITY_STOPPED
    private const val ACTIVITY_DESTROYED = ForegroundTracker.ACTIVITY_DESTROYED
  }
}
