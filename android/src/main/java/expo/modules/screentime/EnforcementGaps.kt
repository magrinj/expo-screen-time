package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject

/**
 * A period during which blocks were intended but not enforced
 * (`getEnforcementGaps`, contract review R2). The Kotlin twin of
 * `ios/EnforcementGaps.swift`, with two fields JS never sees: where the
 * evidence came from ([source]) and what it said ([detail]), which the merge
 * rule and the logs need.
 *
 * Pure Kotlin on purpose, unit-tested in `android/src/test`.
 */
internal data class EnforcementGap(
  /** Unix ms. */
  val from: Long,
  /** Unix ms; null while the gap is still open. */
  val to: Long?,
  val reason: Reason,
  val source: Source,
  val detail: String = "",
  /**
   * For engine-family gaps, the start of the run they belong to: gaps merge
   * within one run only, so a clock step cannot fold one run's death into
   * an earlier run's gap. Null for permission gaps.
   */
  val run: Long? = null,
) {
  /** The contract's `EnforcementGap.reason`. `authorizationRevoked` is iOS-only; Android never records it. */
  enum class Reason { stoppedByUser, permissionRevoked, killedBySystem, authorizationRevoked, unknown }

  /**
   * What produced the gap. `exit` and `heartbeat` are two readings of one
   * fact, "the engine process was not running", so they merge; `permission`
   * is another fact ("the engine ran but could not act") and never merges
   * with them: the two can overlap, and a consumer adding up gap time must
   * merge overlaps first.
   */
  enum class Source { exit, heartbeat, permission }

  val isOpen: Boolean
    get() = to == null

  val isEngineFamily: Boolean
    get() = source != Source.permission

  /** The JS shape: `{ from, to: number | null, reason }`. */
  fun toMap(): Map<String, Any?> = mapOf("from" to from, "to" to to, "reason" to reason.name)

  fun toJson(): JSONObject = JSONObject()
    .put(FROM, from)
    .put(TO, to ?: JSONObject.NULL)
    .put(REASON, reason.name)
    .put(SOURCE, source.name)
    .put(DETAIL, detail)
    .put(RUN, run ?: JSONObject.NULL)

  companion object {
    private const val FROM = "from"
    private const val TO = "to"
    private const val REASON = "reason"
    private const val SOURCE = "source"
    private const val DETAIL = "detail"
    private const val RUN = "run"

    /**
     * The gap a stored entry holds, or null when it is malformed (dropped,
     * the rest of the log is kept). `to` must be present: an entry without
     * it is truncated, not open.
     */
    fun fromJson(json: JSONObject): EnforcementGap? {
      val from = json.optLongOrNull(FROM) ?: return null
      if (!json.has(TO)) return null
      val to = if (json.isNull(TO)) null else json.optLongOrNull(TO) ?: return null
      if (to != null && to < from) return null
      val reason = Reason.values().firstOrNull { it.name == json.optString(REASON) } ?: return null
      val source = Source.values().firstOrNull { it.name == json.optString(SOURCE) } ?: return null
      return EnforcementGap(from, to, reason, source, json.optString(DETAIL), json.optLongOrNull(RUN))
    }
  }
}

/**
 * One `ApplicationExitInfo`, reduced to what the tracker needs so the core
 * stays off the Android framework. The wiring builds it, on API 30+, with
 * `ActivityManager.getHistoricalProcessExitReasons(null, 0, 0)`: every
 * record of the package, newest first, the whole (short) history on every
 * call, hence the seen set in [EnforcementGapTracker]. Below API 30 the
 * list is empty and only the heartbeat speaks.
 */
internal data class ExitRecord(
  /** `ApplicationExitInfo.getReason()`: one of [ExitReasons]. */
  val reason: Int,
  /** `getTimestamp()`, Unix ms. */
  val timestamp: Long,
  /** `getPid()`. */
  val pid: Int,
  /** `getProcessName()`: `<package>` for the app, `<package>:screentime` for the engine. */
  val processName: String,
  /** `getDescription()`, e.g. `fully stop <package>/0 by user request`. */
  val description: String?,
) {
  /** What tells two records apart. The same pid can die twice in the history (seen on the Pixel). */
  val key: EnforcementGapTracker.ExitKey
    get() = EnforcementGapTracker.ExitKey(timestamp, pid)
}

/**
 * `ApplicationExitInfo.REASON_*` and how each one reads as a contract
 * reason, once the exit is known to have happened while the engine was
 * meant to run (a process that leaves after an orderly release is no gap,
 * whatever its reason).
 */
internal object ExitReasons {
  const val UNKNOWN = 0
  const val EXIT_SELF = 1
  const val SIGNALED = 2
  const val LOW_MEMORY = 3
  const val CRASH = 4
  const val CRASH_NATIVE = 5
  const val ANR = 6
  const val INITIALIZATION_FAILURE = 7
  const val PERMISSION_CHANGE = 8
  const val EXCESSIVE_RESOURCE_USAGE = 9
  const val USER_REQUESTED = 10
  const val USER_STOPPED = 11
  const val DEPENDENCY_DIED = 12
  const val OTHER = 13
  const val FREEZER = 14
  const val PACKAGE_STATE_CHANGE = 15
  const val PACKAGE_UPDATED = 16

  /**
   * - `stoppedByUser`: `USER_REQUESTED`, the user acting on the app in the
   *   system UI. Task Manager Stop and force stop both land here (the R2
   *   case, `STOP APP` / `FORCE STOP` in the description). An OEM battery
   *   manager that force-stops the app is recorded the same way; the
   *   description, kept in the detail, is what tells them apart.
   * - `killedBySystem`: the system took the process down for its own
   *   reasons: memory, resources, a signal, a dependency, the freezer, or
   *   `OTHER` (where OEM killers that do not force-stop land).
   * - `permissionRevoked`: a runtime permission was revoked, which kills
   *   the process (`POST_NOTIFICATIONS` is the one this module holds).
   * - `unknown`: the engine's own fault (a crash, an ANR, an exit), a
   *   package event (update, state change), the Android user or profile
   *   being stopped (`USER_STOPPED`: nothing runs in a stopped profile;
   *   what is lost is the time after it resumes and before the engine is
   *   back, which the tracker cannot see, so the record opens a gap like
   *   any other), and codes this build does not know.
   */
  fun reason(code: Int): EnforcementGap.Reason = when (code) {
    USER_REQUESTED -> EnforcementGap.Reason.stoppedByUser
    PERMISSION_CHANGE -> EnforcementGap.Reason.permissionRevoked
    SIGNALED, LOW_MEMORY, EXCESSIVE_RESOURCE_USAGE, DEPENDENCY_DIED, OTHER, FREEZER -> EnforcementGap.Reason.killedBySystem
    else -> EnforcementGap.Reason.unknown
  }

  /** The name `dumpsys activity exit-info` prints, for the detail. */
  fun name(code: Int): String = when (code) {
    UNKNOWN -> "UNKNOWN"
    EXIT_SELF -> "EXIT_SELF"
    SIGNALED -> "SIGNALED"
    LOW_MEMORY -> "LOW_MEMORY"
    CRASH -> "APP CRASH(EXCEPTION)"
    CRASH_NATIVE -> "APP CRASH(NATIVE)"
    ANR -> "ANR"
    INITIALIZATION_FAILURE -> "INITIALIZATION FAILURE"
    PERMISSION_CHANGE -> "PERMISSION CHANGE"
    EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE RESOURCE USAGE"
    USER_REQUESTED -> "USER REQUESTED"
    USER_STOPPED -> "USER STOPPED"
    DEPENDENCY_DIED -> "DEPENDENCY DIED"
    OTHER -> "OTHER KILLS BY SYSTEM"
    FREEZER -> "FREEZER"
    PACKAGE_STATE_CHANGE -> "PACKAGE STATE CHANGE"
    PACKAGE_UPDATED -> "PACKAGE UPDATED"
    else -> "REASON_$code"
  }

  fun detail(record: ExitRecord): String =
    "${name(record.reason)} (${record.reason}): ${record.description?.takeIf { it.isNotEmpty() } ?: "no description"}"
}

/**
 * The enforcement-gap log, folded from what Android lets the module observe.
 * A gap is "a block was intended but not enforced"; on Android the intent
 * is the engine's **run** ([engineStarted] to [engineStopped]), and every
 * gap is bounded by it.
 *
 * - **Exit records** (`ApplicationExitInfo`): a death of the run's process
 *   opens a gap at the death, closed by the next engine start or by the
 *   release that ended the run. Each record is folded once (a persisted set
 *   of `(timestamp, pid)` keys, pruned to the history the system still
 *   returns), because the system hands the whole history back on every
 *   call and the wall clock can step backwards.
 * - **Heartbeats**: the engine records one every [HEARTBEAT_INTERVAL_MS]
 *   while it runs. When a new process starts the engine, or the app
 *   releases it, a live run whose last heartbeat is older than
 *   [STALE_AFTER_MS] means the process was gone with nothing to show for
 *   it (an OEM killer, a record lost from the history): the gap runs from
 *   the last heartbeat plus the interval. That start is an upper bound: a
 *   heartbeat timer stops with the CPU (screen off, Doze), so the wiring
 *   should also beat on screen-on/off, on `USER_PRESENT` and on
 *   `ACTION_SHUTDOWN`, which keeps the bound tight when it matters. A boot
 *   after the last heartbeat is a reboot: the device was off, so the gap
 *   starts at the boot, a lower bound when the engine had already died
 *   before the shutdown. An exit record covering the same period wins over
 *   either estimate (see [mergeEngineGap]).
 * - **Permission checks**: the port of iOS `AuthorizationGapTracker`, with
 *   "approved" meaning every step enforcement needs is granted
 *   ([ENFORCEMENT_STEPS]). A revocation is only visible at the next check,
 *   so the gap opens at the last check that saw access (no earlier than
 *   the run, which is where Android departs from iOS: iOS cannot tell
 *   whether a block was intended, Android can), and closes at the first
 *   check that sees access back, or at the release.
 *
 * **Merge rule.** Engine-family gaps (exit, heartbeat) of one run that
 * overlap or touch are one gap: an exit record's `from` and reason win over a heartbeat's
 * estimate; between two records, the earlier one leads; open wins over
 * closed. Permission gaps are never merged with anything, as on iOS, where a
 * grant and a revocation at the same instant are two gaps.
 *
 * **Retention.** Closed gaps older than [MAX_AGE_MS] are dropped, then the
 * oldest closed ones past [MAX_GAPS]. Open gaps are never dropped (at most
 * one engine gap and one permission gap are open at a time).
 *
 * **Multi-process.** The tracker is an immutable value; every fold returns a
 * new one. The app process (launch, foreground, `getEnforcementGaps`,
 * release) and the `:screentime` process (start, heartbeat) both fold it,
 * so each fold must be one `StateStore.update`: [load] the document's
 * tracker, fold, [store] it back, under the exclusive lock ([update] is
 * that read-fold-write). `now`, the boot time, the permission reads and the
 * exit records are inputs, and the wiring must take them **inside** the
 * update block, so two processes' observations land in lock order. Nothing
 * here touches time or the OS.
 */
internal data class EnforcementGapTracker(
  /** Oldest first (by `from`). */
  val gaps: List<EnforcementGap> = emptyList(),
  /** The engine's current or last run; null until the first start. */
  val run: EngineRun? = null,
  /** The exit records folded so far, pruned to the history the system last returned. */
  val seenExits: Set<ExitKey> = emptySet(),
  /**
   * The last check that saw every enforcement step granted, Unix ms. Null
   * until the first: never having had access is not a gap.
   */
  val lastApprovedAt: Long? = null,
) {
  /**
   * One life of the engine, from `engineStarted` to `engineStopped` or to
   * its death (observed later, when a new process starts: that replaces
   * the run, `startedAt` included). `pid` is its process.
   */
  data class EngineRun(
    val startedAt: Long,
    /** The engine process's pid, so a short-lived process that never started the engine is not mistaken for it. */
    val pid: Int?,
    val heartbeatAt: Long,
    /** Set by an orderly release; null while the engine is meant to run. */
    val stoppedAt: Long?,
  ) {
    val isLive: Boolean
      get() = stoppedAt == null
  }

  data class ExitKey(val timestamp: Long, val pid: Int)

  /** Whether a permission gap is open (iOS `hasOpenGap`). */
  val hasOpenPermissionGap: Boolean
    get() = gaps.any { !it.isEngineFamily && it.isOpen }

  val hasOpenEngineGap: Boolean
    get() = gaps.any { it.isEngineFamily && it.isOpen }

  // MARK: - Queries

  /**
   * The gaps still in progress at, or ending after, `since` (Unix ms); every
   * gap when `since` is null. Oldest first.
   */
  fun gaps(since: Long?): List<EnforcementGap> {
    if (since == null) return gaps
    return gaps.filter { (it.to ?: Long.MAX_VALUE) >= since }
  }

  // MARK: - Permission checks (the iOS tracker)

  /**
   * Fold in one observation at `now`: `approved` is "every step enforcement
   * needs is granted"; `missing` names the steps that are not, for the
   * detail. Call on every engine heartbeat and whenever the app observes
   * its permissions (launch, foreground, `getEnforcementGaps`). Without a
   * live run there is nothing to enforce, so a revocation opens no gap
   * (the grant is still noted); the gap opens at the later of the last
   * grant and the run's start.
   */
  fun observePermissions(approved: Boolean, now: Long, missing: Collection<String> = emptyList()): EnforcementGapTracker {
    if (approved) {
      val closed = gaps.map { gap ->
        if (!gap.isEngineFamily && gap.isOpen) gap.copy(to = maxOf(now, gap.from)) else gap
      }
      return copy(gaps = retain(closed, now), lastApprovedAt = now)
    }
    val since = lastApprovedAt ?: return this
    val live = run?.takeIf { it.isLive } ?: return this
    if (hasOpenPermissionGap) return this
    val gap = EnforcementGap(
      from = maxOf(since, live.startedAt),
      to = null,
      reason = EnforcementGap.Reason.permissionRevoked,
      source = EnforcementGap.Source.permission,
      detail = "missing: ${missing.sorted().joinToString(", ").ifEmpty { "?" }}",
    )
    return copy(gaps = retain(gaps + gap, now))
  }

  /** [observePermissions] from the granted step ids: approved when [ENFORCEMENT_STEPS] are all in. */
  fun observePermissions(granted: Set<PermissionSteps.StepId>, now: Long): EnforcementGapTracker {
    val missing = ENFORCEMENT_STEPS - granted
    return observePermissions(approved = missing.isEmpty(), now = now, missing = missing.map { it.name })
  }

  // MARK: - Exit records

  /**
   * Fold the exit history (always the whole list the system returns; skip
   * the fold rather than pass an empty one when it cannot be read): records of
   * [engineProcess] not folded before, in time order. One counts as a gap
   * when it falls inside the known run (after its start, by the run's
   * process, before any orderly stop); the gap closes at the stop when
   * there was one, else it stays open until the next [engineStarted] or
   * [engineStopped]. Call from the app process whenever it looks at the
   * gaps, and from the engine at start (through [engineStarted]).
   */
  fun recordExits(exits: List<ExitRecord>, engineProcess: String, now: Long): EnforcementGapTracker {
    val history = exits.filter { it.processName == engineProcess }
    var folded = gaps
    val fresh = history.filter { it.key !in seenExits }.sortedWith(compareBy({ it.timestamp }, { it.pid }))
    for (record in fresh) {
      val current = run ?: continue
      // The run's own process is the run's death whatever the clock says
      // (it can step back); without a pid, only the timestamp can tell.
      if (current.pid != null) {
        if (record.pid != current.pid) continue
      } else if (record.timestamp < current.startedAt) {
        continue
      }
      val stoppedAt = current.stoppedAt
      if (stoppedAt != null && record.timestamp >= stoppedAt) continue
      // A record stamped past `now` (the clock stepped) starts no earlier than now.
      val from = minOf(record.timestamp, now)
      val gap = EnforcementGap(
        from = from,
        to = stoppedAt?.let { maxOf(it, from) },
        reason = ExitReasons.reason(record.reason),
        source = EnforcementGap.Source.exit,
        detail = ExitReasons.detail(record),
        run = current.startedAt,
      )
      folded = mergeEngineGap(folded, gap)
    }
    // An empty history (nothing to read, or a failed read the wiring should
    // rather skip) must not forget what was folded: the next full list
    // would fold it again.
    val seen = if (history.isEmpty()) seenExits else history.map { it.key }.toSet()
    return copy(gaps = retain(folded, now), seenExits = seen)
  }

  // MARK: - The engine's life

  /**
   * The engine service is up and promoted, at `now`, in process `pid`.
   * Folds the exit history first; then, if a **new** process takes over a
   * live run, the previous process's last heartbeat (stale, or older than
   * a boot at `bootTime`), closes every open engine gap at `now` and opens
   * the new run. The same process starting again (every `startForegroundService`
   * reaches `onStartCommand`: a launch, a redelivered intent) only refreshes
   * the heartbeat, so a timer that slept with the CPU is not a gap; a pid
   * is "the same process" only within one boot and while no gap says it
   * died. One
   * `StateStore.update`, in the same block that re-checks the desired
   * state: fold nothing when the engine is not wanted.
   *
   * `bootTime` is `System.currentTimeMillis() - SystemClock.elapsedRealtime()`,
   * Unix ms; null when unknown. It follows the wall clock, so a forward
   * clock correction after the last heartbeat reads as a reboot; storing
   * `Settings.Global.BOOT_COUNT` with the run would be exact (later).
   */
  fun engineStarted(now: Long, pid: Int, exits: List<ExitRecord>, engineProcess: String, bootTime: Long?): EnforcementGapTracker {
    val withExits = recordExits(exits, engineProcess, now)
    val previous = withExits.run
    if (previous != null && previous.isLive && previous.pid == pid && !withExits.hasOpenEngineGap && !rebootedSince(previous, bootTime)) {
      return withExits.copy(run = previous.copy(heartbeatAt = maxOf(now, previous.heartbeatAt)))
    }
    var folded = withExits.gaps
    if (previous != null && previous.isLive) {
      staleEstimate(previous, now, bootTime)?.let { folded = mergeEngineGap(folded, it) }
    }
    folded = closeEngineGaps(folded, now)
    return withExits.copy(gaps = retain(folded, now), run = EngineRun(startedAt = now, pid = pid, heartbeatAt = now, stoppedAt = null))
  }

  /**
   * One heartbeat at `now` from process `pid`, with the engine's own
   * permission check when it made one (`permissionsApproved`, `missing` as
   * in [observePermissions]). Only the run's process moves the heartbeat:
   * a beat that lands after a release, or from a process whose start was
   * refused, changes nothing about the run.
   */
  fun heartbeat(now: Long, pid: Int, permissionsApproved: Boolean? = null, missing: Collection<String> = emptyList()): EnforcementGapTracker {
    val current = run
    val beat = if (current != null && current.isLive && (current.pid == null || current.pid == pid)) {
      copy(run = current.copy(heartbeatAt = maxOf(now, current.heartbeatAt)), gaps = retain(gaps, now))
    } else {
      copy(gaps = retain(gaps, now))
    }
    return if (permissionsApproved == null) beat else beat.observePermissions(permissionsApproved, now, missing)
  }

  /**
   * The engine was released (nothing left to enforce) at `now`: the run
   * ends and every open gap closes, since no block is intended past this
   * point. When the engine process is **not** alive (`engineAlive`: in
   * `:screentime`, `Process.myPid() == run.pid`, since a process whose
   * start was refused can handle the stop while the run's process is dead;
   * from the app, whether `ActivityManager.runningAppProcesses` lists the
   * run's pid), a live run
   * whose heartbeat is stale was dead before the release (a kill with no
   * record), so its estimate is folded first, as at a start. An alive
   * engine with a stale heartbeat only slept with the CPU (a schedule
   * ending in a pocket) and is no gap. Call from whichever process
   * releases the engine, in the same `StateStore.update` that clears the
   * desired state.
   */
  fun engineStopped(now: Long, engineAlive: Boolean, bootTime: Long? = null): EnforcementGapTracker {
    val current = run
    var folded = gaps
    if (current != null && current.isLive && !engineAlive) {
      staleEstimate(current, now, bootTime)?.let { folded = mergeEngineGap(folded, it) }
    }
    folded = folded.map { gap -> if (gap.isOpen) gap.copy(to = maxOf(now, gap.from)) else gap }
    val ended = current?.let { if (it.isLive) it.copy(stoppedAt = now) else it }
    return copy(gaps = retain(folded, now), run = ended)
  }

  // MARK: - Folding

  /** What a live run's silence since its last heartbeat says at `now`, if anything. */
  private fun staleEstimate(live: EngineRun, now: Long, bootTime: Long?): EnforcementGap? = when {
    rebootedSince(live, bootTime) -> EnforcementGap(
      from = minOf(bootTime!!, now),
      to = now,
      reason = EnforcementGap.Reason.unknown,
      source = EnforcementGap.Source.heartbeat,
      detail = "reboot: booted at $bootTime, last heartbeat at ${live.heartbeatAt}",
      run = live.startedAt,
    )
    now - live.heartbeatAt > STALE_AFTER_MS -> EnforcementGap(
      from = live.heartbeatAt + HEARTBEAT_INTERVAL_MS,
      to = now,
      reason = EnforcementGap.Reason.killedBySystem,
      source = EnforcementGap.Source.heartbeat,
      detail = "no heartbeat since ${live.heartbeatAt}, no exit record",
      run = live.startedAt,
    )
    else -> null
  }

  /** A boot after the run's last heartbeat: the device was off in between. */
  private fun rebootedSince(live: EngineRun, bootTime: Long?): Boolean = bootTime != null && bootTime > live.heartbeatAt

  private fun closeEngineGaps(gaps: List<EnforcementGap>, at: Long): List<EnforcementGap> =
    gaps.map { gap -> if (gap.isEngineFamily && gap.isOpen) gap.copy(to = maxOf(at, gap.from)) else gap }

  /**
   * Adds an engine-family gap, merging it with the engine-family gaps of
   * the same run it overlaps or touches: exit records lead over heartbeat
   * estimates, the earlier record leads among records, open wins over
   * closed. Gaps of different runs never merge, even when a clock step
   * makes them overlap.
   */
  private fun mergeEngineGap(gaps: List<EnforcementGap>, gap: EnforcementGap): List<EnforcementGap> {
    val touching = gaps.filter { it.isEngineFamily && it.run == gap.run && touches(it, gap) }
    if (touching.isEmpty()) return sorted(gaps + gap)
    val all = touching + gap
    val records = all.filter { it.source == EnforcementGap.Source.exit }
    val leader = records.ifEmpty { all }.minByOrNull { it.from }!!
    val to = if (all.any { it.isOpen }) null else all.maxOf { it.to!! }
    val merged = leader.copy(to = to?.let { maxOf(it, leader.from) })
    return sorted(gaps - touching.toSet() + merged)
  }

  private fun touches(a: EnforcementGap, b: EnforcementGap): Boolean =
    a.from <= (b.to ?: Long.MAX_VALUE) && b.from <= (a.to ?: Long.MAX_VALUE)

  private fun sorted(gaps: List<EnforcementGap>): List<EnforcementGap> = gaps.sortedBy { it.from }

  private fun retain(gaps: List<EnforcementGap>, now: Long): List<EnforcementGap> {
    val oldest = now - MAX_AGE_MS
    val kept = sorted(gaps).filterNot { gap -> gap.to != null && gap.to < oldest }.toMutableList()
    while (kept.size > MAX_GAPS) {
      val index = kept.indexOfFirst { !it.isOpen }
      if (index < 0) break
      kept.removeAt(index)
    }
    return kept
  }

  // MARK: - Codec

  fun toJson(): JSONObject {
    val json = JSONObject().put(ENTRIES, JSONArray().also { array -> gaps.forEach { array.put(it.toJson()) } })
    run?.let {
      json.put(
        RUN,
        JSONObject()
          .put(STARTED_AT, it.startedAt)
          .put(PID, it.pid ?: JSONObject.NULL)
          .put(HEARTBEAT_AT, it.heartbeatAt)
          .put(STOPPED_AT, it.stoppedAt ?: JSONObject.NULL),
      )
    }
    json.put(
      SEEN_EXITS,
      JSONArray().also { array ->
        seenExits.sortedWith(compareBy({ it.timestamp }, { it.pid })).forEach { array.put(JSONObject().put(TIMESTAMP, it.timestamp).put(PID, it.pid)) }
      },
    )
    lastApprovedAt?.let { json.put(LAST_APPROVED_AT, it) }
    return json
  }

  companion object {
    /** The key of the tracker in the `StateStore` document. */
    const val DOCUMENT_KEY = "gaps"

    /**
     * 60 s: one `StateStore.update` (a few KB to flash) per minute while the
     * engine runs, about 1 ms of CPU each, 0.1 % of what the 100 ms poll
     * costs; and the run recorded a heartbeat
     * every 60 s for 3 h 22 without one false gap at a 90 s threshold.
     * (That run was plugged in with stay-awake on: it says nothing about
     * sleep, which is why a live process is never judged stale.)
     */
    const val HEARTBEAT_INTERVAL_MS = 60_000L

    /**
     * 150 s = two intervals plus 30 s of scheduling slack: one missed tick
     * is not a gap. A kill with no record and a restart inside this window
     * goes unrecorded, by design.
     */
    const val STALE_AFTER_MS = 150_000L

    /** As iOS (`AuthorizationGapTracker.maxGaps`). */
    const val MAX_GAPS = 50

    /** Closed gaps older than 30 days are dropped: `since` queries are "since the app last looked", never older. */
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * The steps without which the usageStats engine cannot act:
     * `usageAccess` (no foreground detection) and `overlay` (no shield from
     * the background, Android 10+). `deviceAdmin` is friction, not
     * enforcement; `notifications`, `batteryOptimization` and
     * `backgroundAutostart` show up as kills, which the exit records and
     * the heartbeat cover. An accessibility engine would need its own set.
     */
    val ENFORCEMENT_STEPS: Set<PermissionSteps.StepId> = setOf(PermissionSteps.StepId.usageAccess, PermissionSteps.StepId.overlay)

    private const val ENTRIES = "entries"
    private const val RUN = "run"
    private const val STARTED_AT = "startedAt"
    private const val PID = "pid"
    private const val HEARTBEAT_AT = "heartbeatAt"
    private const val STOPPED_AT = "stoppedAt"
    private const val SEEN_EXITS = "seenExits"
    private const val TIMESTAMP = "timestamp"
    private const val LAST_APPROVED_AT = "lastApprovedAt"

    /** The tracker a stored object holds; an empty one for null or garbage, with malformed entries dropped. */
    fun fromJson(json: JSONObject?): EnforcementGapTracker {
      json ?: return EnforcementGapTracker()
      val entries = json.optJSONArray(ENTRIES)
      val gaps = ArrayList<EnforcementGap>()
      if (entries != null) {
        for (i in 0 until entries.length()) {
          val gap = entries.optJSONObject(i)?.let { EnforcementGap.fromJson(it) } ?: continue
          gaps.add(gap)
        }
      }
      val run = json.optJSONObject(RUN)?.let { r ->
        val startedAt = r.optLongOrNull(STARTED_AT) ?: return@let null
        EngineRun(
          startedAt = startedAt,
          pid = r.optLongOrNull(PID)?.toInt(),
          heartbeatAt = r.optLongOrNull(HEARTBEAT_AT) ?: startedAt,
          stoppedAt = r.optLongOrNull(STOPPED_AT),
        )
      }
      val seen = LinkedHashSet<ExitKey>()
      json.optJSONArray(SEEN_EXITS)?.let { array ->
        for (i in 0 until array.length()) {
          val key = array.optJSONObject(i) ?: continue
          val timestamp = key.optLongOrNull(TIMESTAMP) ?: continue
          val pid = key.optLongOrNull(PID)?.toInt() ?: continue
          seen.add(ExitKey(timestamp, pid))
        }
      }
      return EnforcementGapTracker(
        gaps = gaps.sortedBy { it.from },
        run = run,
        seenExits = seen,
        lastApprovedAt = json.optLongOrNull(LAST_APPROVED_AT),
      )
    }

    /** The tracker in a `StateStore` document (`document.gaps`). */
    fun load(document: JSONObject): EnforcementGapTracker = fromJson(document.optJSONObject(DOCUMENT_KEY))

    fun store(document: JSONObject, tracker: EnforcementGapTracker) {
      document.put(DOCUMENT_KEY, tracker.toJson())
    }

    /**
     * The read-fold-write the wiring runs inside `StateStore.update`:
     * `store.update { document -> EnforcementGapTracker.update(document) { it.heartbeat(now, pid) } }`.
     * Returns the folded tracker.
     */
    inline fun update(document: JSONObject, fold: (EnforcementGapTracker) -> EnforcementGapTracker): EnforcementGapTracker {
      val tracker = fold(load(document))
      store(document, tracker)
      return tracker
    }
  }
}

/** `optLong` cannot tell 0 from absent or non-numeric; this can. */
private fun JSONObject.optLongOrNull(key: String): Long? {
  if (!has(key) || isNull(key)) return null
  return when (val value = opt(key)) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
  }
}
