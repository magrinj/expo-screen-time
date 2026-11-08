package expo.modules.screentime

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.TimeZone

/**
 * The wiring of [EnforcementGapTracker] (pure) to Android, from either
 * process, the seam PR #55 prescribed: every fold is one `StateStore.update`,
 * and `now`, the boot, the exit history, the permission reads and the
 * intent are taken **inside** the block, so the observations of the app
 * and of the engine land in lock order.
 *
 * | When | Process | Fold |
 * |---|---|---|
 * | the service promoted, in the update that re-checks `engine.desired` | `:screentime` | [engineStarted] |
 * | the promotion refused (certain) or the start intent refused with the run's process gone, while the engine is wanted | either | [engineRefused] |
 * | every 60 s while running; screen on/off, `USER_PRESENT`, `ACTION_SHUTDOWN`; an enforcement AppOp changed | `:screentime` | [heartbeat] |
 * | the engine released, in the update that clears `engine.desired` | either | [released] |
 * | app launch, foreground, `getEnforcementGaps` | app | [observe] |
 * | `getEnforcementGaps(since)` | app | [gaps] |
 *
 * The intent ([EnforcementIntent.of]) is read from the document the fold
 * runs on: the manual block and the monitoring state as they are, in the
 * device's current zone. A release reads it **before** the caller clears
 * the block or the activities ([released] takes it as an argument).
 */
internal class EnforcementGapRecorder(private val context: Context, private val store: StateStore) {
  /** The engine process's name as `ApplicationExitInfo` reports it (`android/src/main/AndroidManifest.xml`, `:screentime`). */
  val engineProcess: String = "${context.packageName}:screentime"

  /** The build declares the accessibility detector; without it no scoped gap is kept ([EnforcementGapTracker.observeAccessibility]). */
  private val detectorDeclared by lazy { BuildManifest.resolve(context).accessibility }

  /** Engine process: the service is up and promoted. Inside the caller's `update`, only when the engine is wanted. */
  fun engineStarted(document: JSONObject, pid: Int) {
    val now = System.currentTimeMillis()
    val boot = boot()
    val tracker = EnforcementGapTracker.update(document) {
      it.engineStarted(now, pid, exits() ?: emptyList(), engineProcess, boot.timeMs, boot.count, intent(document))
    }
    for (gap in tracker.gaps.filter { it.isEngineFamily && it.to == now }) Log.i(TAG, "enforcement gap closed at start: ${describe(gap)}")
  }

  /**
   * Either process: the system refused the promotion (`certain`: this is
   * the engine process and its service could not go to the foreground) or
   * the start intent while the engine is wanted. For a refused intent the
   * run's process may still be alive and serving (a SYNC refused from the
   * background while the service runs changes nothing; the engine's own
   * running flag is not enough, a kill leaves it true): the gap opens only
   * when the run's process is gone. Its own `update`, never throwing.
   */
  fun engineRefused(detail: String, certain: Boolean) {
    try {
      store.update { document ->
        if (!store.engineDesired) return@update
        val before = EnforcementGapTracker.load(document)
        if (!certain && before.run?.pid?.let { engineAlive(it) } == true) return@update
        val after = EnforcementGapTracker.update(document) { it.engineRefused(System.currentTimeMillis(), detail) }
        if (after.hasOpenEngineGap && !before.hasOpenEngineGap) Log.i(TAG, "enforcement gap open: start refused ($detail)")
      }
    } catch (e: Exception) {
      Log.w(TAG, "refused start not recorded: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  /**
   * Engine process: one beat, with the engine's own permission check
   * (usage access and the overlay: [EnforcementGapTracker.ENFORCEMENT_STEPS])
   * and, for `ACTION_SHUTDOWN`, the shutdown mark. Its own `update`.
   */
  fun heartbeat(pid: Int, reason: String, shutdown: Boolean = false) = store.update { document ->
    val now = System.currentTimeMillis()
    val missing = EnforcementGapTracker.ENFORCEMENT_STEPS - granted()
    val coverage = accessibility()
    val before = EnforcementGapTracker.load(document)
    val after = EnforcementGapTracker.update(document) {
      val intent = intent(document)
      it.heartbeat(now, pid, permissionsApproved = missing.isEmpty(), missing = missing.map { step -> step.name }, shutdown = shutdown, intent = intent)
        .observeAccessibility(now, coverage.enabled, coverage.bound, coverage.blocked, intent, declared = detectorDeclared)
    }
    if (after.hasOpenPermissionGap && !before.hasOpenPermissionGap) Log.w(TAG, "enforcement gap open: permission revoked (${missing.joinToString()}; $reason)")
    if (!after.hasOpenPermissionGap && before.hasOpenPermissionGap) Log.i(TAG, "enforcement gap closed: permissions back ($reason)")
    logCoverage(before, after, coverage, reason)
    if (shutdown) Log.i(TAG, "shutdown beat at $now")
  }

  /**
   * Either process, inside the `update` that releases the engine
   * (`engine.desired` true → false): the run ends, every open gap closes,
   * clipped to [intentBefore], the intent read from [document] before the
   * caller cleared the block or the activities. Idempotent.
   */
  fun released(document: JSONObject, intentBefore: EnforcementIntent) {
    val now = System.currentTimeMillis()
    val boot = boot()
    val run = EnforcementGapTracker.load(document).run
    val alive = run?.pid?.let { engineAlive(it) } ?: false
    // The exit history first, under the intent before the release: a death
    // the heartbeat cannot tell yet (under the staleness threshold) is in
    // the records, and folded after the release it would clip to nothing.
    val exits = exits()
    val tracker = EnforcementGapTracker.update(document) { tracker ->
      val withExits = if (exits == null) tracker else tracker.recordExits(exits, engineProcess, now, intentBefore)
      withExits.engineStopped(now, alive, boot.timeMs, boot.count, intentBefore)
    }
    for (gap in tracker.gaps.filter { it.to == now }) Log.i(TAG, "enforcement gap closed at release: ${describe(gap)}")
  }

  /**
   * The module's mutations: the open gaps settled under [intentBefore],
   * the intent read from the document **before** the mutation ([intent]),
   * then [StateStore.syncEngineDesired] over the mutated [document], and
   * when the engine stops being wanted, the release fold with the same
   * intent: the block or the activities just cleared are what was intended
   * until now.
   */
  fun syncDesired(document: JSONObject, intentBefore: EnforcementIntent) {
    val wanted = store.engineDesired
    // Whatever the mutation changes about the intent, what was observed
    // under the previous one is committed first.
    EnforcementGapTracker.update(document) { it.settle(System.currentTimeMillis(), intentBefore) }
    store.syncEngineDesired(document)
    if (wanted && !store.engineDesired) released(document, intentBefore)
  }

  /**
   * App process: launch, foreground and `getEnforcementGaps`. Folds the
   * exit history, the engine's liveness (a dead process with a stale
   * heartbeat, or one from a previous boot, opens a gap even before the
   * engine is started again) and the permissions, then settles the open
   * gaps under the current intent, in one `update`. A history that cannot
   * be read is skipped, not passed empty. Off the main thread (binder
   * calls under the cross-process lock).
   */
  fun observe(reason: String) = store.update { document ->
    val now = System.currentTimeMillis()
    val boot = boot()
    val intent = intent(document)
    val granted = granted()
    val coverage = accessibility()
    val before = EnforcementGapTracker.load(document)
    val after = EnforcementGapTracker.update(document) { tracker ->
      val exits = exits()
      val withExits = if (exits == null) tracker else tracker.recordExits(exits, engineProcess, now, intent)
      val run = withExits.run
      val alive = run?.pid?.let { engineAlive(it) } ?: false
      withExits.observeEngine(now, alive, boot.timeMs, boot.count)
        .observePermissions(granted, now, intent)
        .observeAccessibility(now, coverage.enabled, coverage.bound, coverage.blocked, intent, declared = detectorDeclared)
        .settle(now, intent)
    }
    if (after.gaps != before.gaps) Log.i(TAG, "enforcement gaps observed ($reason): ${after.gaps.size} (${after.gaps.count { it.isOpen }} open)")
    logCoverage(before, after, coverage, reason)
  }

  private fun logCoverage(before: EnforcementGapTracker, after: EnforcementGapTracker, coverage: AccessibilityCoverage, reason: String) {
    if (after.hasOpenAccessibilityGap && !before.hasOpenAccessibilityGap) Log.w(TAG, "enforcement gap open: other profiles not covered ($coverage; $reason)")
    if (!after.hasOpenAccessibilityGap && before.hasOpenAccessibilityGap) Log.i(TAG, "enforcement gap closed: other profiles covered again ($reason)")
  }

  /** `getEnforcementGaps(since)`: the stored gaps, open ones clipped to the intent as of now, as JS records. */
  fun gaps(since: Long?): List<Map<String, Any?>> = store.read { document ->
    EnforcementGapTracker.load(document).gaps(since, System.currentTimeMillis(), intent(document)).map { it.toMap() }
  }

  /** The engine's `TIME_SET`: the run's instants follow the clock (the gaps do not). */
  fun clockShifted(deltaMs: Long) = store.update { document ->
    EnforcementGapTracker.update(document) { it.clockShifted(deltaMs) }
    Unit
  }

  // MARK: - The inputs

  /** The intent the document describes, in the device's current zone. */
  fun intent(document: JSONObject): EnforcementIntent =
    EnforcementIntent.of(store.manualBlock, MonitoringDocument.read(document), TimeZone.getDefault())

  /**
   * The boot: `BOOT_COUNT` (API 24+; null when the setting cannot be read)
   * and the wall-clock boot time (`currentTimeMillis - elapsedRealtime`).
   */
  fun boot(): Boot {
    val count = try {
      Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
      null
    }
    return Boot(System.currentTimeMillis() - SystemClock.elapsedRealtime(), count)
  }

  /**
   * The package's exit history as [ExitRecord]s, every process (the fold
   * filters on [engineProcess]); empty below API 30; null when the read
   * failed (the fold is then skipped, so the seen set is kept).
   */
  fun exits(): List<ExitRecord>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    return try {
      val manager = context.getSystemService(ActivityManager::class.java) ?: return null
      manager.getHistoricalProcessExitReasons(null, 0, 0).map {
        ExitRecord(it.reason, it.timestamp, it.pid, it.processName ?: "", it.description)
      }
    } catch (e: Exception) {
      Log.w(TAG, "exit history unreadable: ${e.javaClass.simpleName}: ${e.message}")
      null
    }
  }

  /**
   * Whether the recorded run is live and its process alive (false without
   * a run, or after a release: a cached process left by a released engine
   * is not an engine): what decides that a refused start needs a revival.
   */
  fun runAlive(): Boolean = store.read { document ->
    EnforcementGapTracker.load(document).run?.takeIf { it.isLive }?.pid?.let { engineAlive(it) } ?: false
  }

  /**
   * Whether the process [pid] is alive: this process, or one of the
   * package's running processes (`runningAppProcesses` lists the caller's
   * own package only, which is what is asked).
   */
  fun engineAlive(pid: Int): Boolean {
    if (pid == Process.myPid()) return true
    val manager = context.getSystemService(ActivityManager::class.java) ?: return false
    return try {
      manager.runningAppProcesses?.any { it.pid == pid && it.processName == engineProcess } == true
    } catch (e: Exception) {
      false
    }
  }

  /**
   * The accessibility detector's state: enabled in Settings, bound (its
   * store record says so and that process is alive; in the engine process
   * the bound instance itself), blocked by Advanced Protection. Also logs
   * whether `AccessibilityManager` lists it, the proposal's open check
   * (§2.6), beside the record the decision rests on.
   */
  fun accessibility(): AccessibilityCoverage {
    val record = store.accessibilityRecord()
    val enabled = AccessibilityHealth.isEnabled(context)
    val bound = ScreenTimeAccessibilityService.current != null || AccessibilityHealth.isBound(record) { pid -> engineAlive(pid) }
    val blocked = enabled && AccessibilityHealth.isBlockedByAdvancedProtection(context)
    return AccessibilityCoverage(enabled, bound, blocked, listedByManager = if (enabled) AccessibilityHealth.isListedByManager(context) else false)
  }

  /** The enforcement steps granted right now: usage access and the overlay, read as the permission requester reads them. */
  fun granted(): Set<PermissionSteps.StepId> {
    val granted = LinkedHashSet<PermissionSteps.StepId>()
    if (EnforcementPermissions.hasUsageAccess(context)) granted.add(PermissionSteps.StepId.usageAccess)
    if (Settings.canDrawOverlays(context)) granted.add(PermissionSteps.StepId.overlay)
    return granted
  }

  private fun describe(gap: EnforcementGap) = "${gap.reason} [${gap.from}, ${gap.to ?: "open"}) ${gap.source}: ${gap.detail}"

  companion object {
    const val TAG = EngineController.TAG
  }
}

/** What [EnforcementGapRecorder.accessibility] read; `listedByManager` is logged, never decided on. */
internal data class AccessibilityCoverage(val enabled: Boolean, val bound: Boolean, val blocked: Boolean, val listedByManager: Boolean) {
  override fun toString() = "enabled=$enabled bound=$bound blocked=$blocked listedByManager=$listedByManager"
}

/** The OS reads of the two enforcement steps, shared by the permission requester and the gap recorder. */
internal object EnforcementPermissions {
  /** Usage access (`PACKAGE_USAGE_STATS`, read through AppOps; the permission itself where the op is in its default mode). */
  fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val uid = context.applicationInfo.uid
    @Suppress("DEPRECATION")
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
    } else {
      appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
    }
    return when (mode) {
      AppOpsManager.MODE_ALLOWED -> true
      AppOpsManager.MODE_DEFAULT -> ContextCompat.checkSelfPermission(context, Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
      else -> false
    }
  }
}
