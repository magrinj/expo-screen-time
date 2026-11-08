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
  /**
   * The `from` of the observation this gap descends from: the pieces an
   * open gap is settled into ([EnforcementGapTracker.settle]) carry their
   * origin, so they merge back together (a permission gap never merges
   * with another revocation, only with its own pieces).
   */
  val origin: Long = from,
  /**
   * The contract's `EnforcementGap.scope`: absent for a whole-device gap;
   * `otherProfiles` when only the blocked apps opened from a work profile,
   * Private Space or a clone went unenforced, because the accessibility
   * detector, which alone sees those profiles, was down while the
   * UsageStats engine ran ([Source.accessibility]).
   */
  val scope: Scope? = null,
) {
  /** The contract's `EnforcementGap.reason`. `authorizationRevoked` is iOS-only; Android never records it. */
  enum class Reason { stoppedByUser, permissionRevoked, killedBySystem, authorizationRevoked, unknown }

  /** The contract's `EnforcementGap.scope` values. */
  enum class Scope { otherProfiles }

  /**
   * What produced the gap. `exit` and `heartbeat` are two readings of one
   * fact, "the engine process was not running", so they merge; `permission`
   * is another fact ("the engine ran but could not act") and never merges
   * with them; `accessibility` is a third ("the engine ran, the other
   * profiles were not covered"). The three can overlap, and a consumer
   * adding up gap time must merge overlaps first.
   */
  enum class Source { exit, heartbeat, permission, accessibility }

  /** The merge families: gaps merge within one family only. */
  enum class Family { engine, permission, accessibility }

  val isOpen: Boolean
    get() = to == null

  val family: Family
    get() = when (source) {
      Source.exit, Source.heartbeat -> Family.engine
      Source.permission -> Family.permission
      Source.accessibility -> Family.accessibility
    }

  val isEngineFamily: Boolean
    get() = family == Family.engine

  /** What merging keys on: the run for engine-family gaps, the origin for the others. */
  val mergeKey: Long?
    get() = if (isEngineFamily) run else origin

  /** The JS shape: `{ from, to: number | null, reason, scope? }`. */
  fun toMap(): Map<String, Any?> = buildMap {
    put("from", from)
    put("to", to)
    put("reason", reason.name)
    scope?.let { put("scope", it.name) }
  }

  fun toJson(): JSONObject = JSONObject()
    .put(FROM, from)
    .put(TO, to ?: JSONObject.NULL)
    .put(REASON, reason.name)
    .put(SOURCE, source.name)
    .put(DETAIL, detail)
    .put(RUN, run ?: JSONObject.NULL)
    .put(ORIGIN, origin)
    .apply { scope?.let { put(SCOPE, it.name) } }

  companion object {
    private const val FROM = "from"
    private const val TO = "to"
    private const val REASON = "reason"
    private const val SOURCE = "source"
    private const val DETAIL = "detail"
    private const val RUN = "run"
    private const val ORIGIN = "origin"
    private const val SCOPE = "scope"

    /**
     * The gap a stored entry holds, or null when it is malformed (dropped,
     * the rest of the log is kept). `to` must be present: an entry without
     * it is truncated, not open. A scope this build does not know drops
     * the entry too: reported without it, it would read as a whole-device gap.
     */
    fun fromJson(json: JSONObject): EnforcementGap? {
      val from = json.optLongOrNull(FROM) ?: return null
      if (!json.has(TO)) return null
      val to = if (json.isNull(TO)) null else json.optLongOrNull(TO) ?: return null
      if (to != null && to < from) return null
      val reason = Reason.values().firstOrNull { it.name == json.optString(REASON) } ?: return null
      val source = Source.values().firstOrNull { it.name == json.optString(SOURCE) } ?: return null
      val scope = if (json.has(SCOPE) && !json.isNull(SCOPE)) (Scope.values().firstOrNull { it.name == json.optString(SCOPE) } ?: return null) else null
      return EnforcementGap(
        from, to, reason, source, json.optString(DETAIL), json.optLongOrNull(RUN),
        origin = json.optLongOrNull(ORIGIN) ?: from,
        scope = scope,
      )
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
 * When a block is meant to be enforced, as a function of time: the periods
 * inside `[fromMs, toMs]` during which some source would shield something.
 * The engine is wanted (`engine.desired`) while a manual block is in force
 * or anything is monitored, but a schedule shields only inside its
 * occurrences: a dead engine on a Tuesday night, with one 09:00–17:00
 * schedule registered, left nothing unenforced until 09:00. Every gap the
 * tracker reports is clipped to these windows ([EnforcementIntent.of]),
 * which is what makes "desired" and "intended" agree.
 *
 * The windows are half-open occurrences (`start ≤ t < end`), sorted and
 * disjoint ([EnforcementIntent.normalize]). [ALWAYS] is the whole range,
 * what a tracker fed by tests or by a build without schedules uses.
 */
internal fun interface EnforcementIntent {
  fun windows(fromMs: Long, toMs: Long): List<Occurrence>

  companion object {
    /** A block intended at every instant: the manual block, or no clipping at all. */
    val ALWAYS = EnforcementIntent { from, to -> if (from < to) listOf(Occurrence(from, to)) else emptyList() }

    /** Nothing intended: every gap clips to nothing. */
    val NEVER = EnforcementIntent { _, _ -> emptyList() }

    /** Transitions walked per activity before the rest of a range counts as intended (a daily schedule over 30 days is 60). */
    const val MAX_TRANSITIONS = 4_096

    /**
     * The intent the document describes: everything while a manual block
     * shields something (an allowlist always does; a blocklist with apps);
     * otherwise each monitored activity's occurrences inside the range,
     * when the activity blocks (a schedule block with a `selectionId`, or a
     * `blockOnThreshold` event: the interval is the honest bound of a block
     * the threshold may impose at any point in it), minus an occurrence
     * `unblockAll` lifted (its marker says so; the shields are back at the
     * next start). Evaluated in [zone], the one current when the gap is
     * folded, as the engine evaluates the schedules.
     */
    fun of(manual: ManualBlock?, monitoring: MonitoringState, zone: java.util.TimeZone): EnforcementIntent = EnforcementIntent { from, to ->
      if (from >= to) return@EnforcementIntent emptyList()
      if (manual != null && manual.source.shieldsSomething) return@EnforcementIntent listOf(Occurrence(from, to))
      val windows = ArrayList<Occurrence>()
      for ((name, activity) in monitoring.activities) {
        val blocks = activity.config.selectionId != null || activity.config.events.any { it.blockOnThreshold }
        if (!blocks) continue
        val lifted = monitoring.intervals[name]?.takeIf { it.lifted }
        // Nothing was intended before the activity existed.
        var cursor = maxOf(from, activity.registeredAtMs)
        var steps = 0
        while (cursor < to) {
          if (steps++ >= MAX_TRANSITIONS) {
            // Too old a range to walk: the rest counts as intended (over-report, never a silent hole).
            windows.add(Occurrence(cursor, to))
            break
          }
          val occurrence = activity.occurrenceAt(cursor, zone)
          if (occurrence != null) {
            if (lifted == null || !lifted.belongsTo(occurrence)) windows.add(Occurrence(maxOf(occurrence.startMs, cursor), minOf(occurrence.endMs, to)))
            cursor = occurrence.endMs
          } else {
            val next = activity.nextTransition(cursor, zone) ?: break
            if (next <= cursor) break
            cursor = next
          }
        }
      }
      normalize(windows)
    }

    /** Sorted, merged (touching or overlapping windows become one), empty ones dropped. */
    fun normalize(windows: List<Occurrence>): List<Occurrence> {
      val merged = ArrayList<Occurrence>()
      for (window in windows.filter { it.startMs < it.endMs }.sortedBy { it.startMs }) {
        val last = merged.lastOrNull()
        if (last != null && window.startMs <= last.endMs) {
          merged[merged.size - 1] = Occurrence(last.startMs, maxOf(last.endMs, window.endMs))
        } else {
          merged.add(window)
        }
      }
      return merged
    }
  }
}

/**
 * What the wiring knows about the boot the engine is starting in. The count
 * (`Settings.Global.BOOT_COUNT`, API 24+) is what tells a reboot from a
 * clock change: it is stored with the run, and a run whose count differs
 * from the current one ran in a previous boot, whatever the wall clock
 * says. The time (`currentTimeMillis - elapsedRealtime`) is where a single
 * reboot's gap starts, and the fallback for a run stored without a count.
 */
internal data class Boot(val timeMs: Long?, val count: Int?) {
  companion object {
    val UNKNOWN = Boot(null, null)
  }
}

/**
 * The enforcement-gap log, folded from what Android lets the module observe.
 * A gap is "a block was intended but not enforced"; on Android the intent
 * is the engine's **run** ([engineStarted] to [engineStopped]), clipped to
 * the periods a source would have shielded something ([EnforcementIntent]),
 * and every gap is bounded by it.
 *
 * - **Exit records** (`ApplicationExitInfo`): a death of the run's process
 *   opens a gap at the death, closed by the next engine start or by the
 *   release that ended the run. Each record is folded once (a persisted set
 *   of `(timestamp, pid)` keys, pruned to the history the system still
 *   returns), because the system hands the whole history back on every
 *   call and the wall clock can step backwards.
 * - **Heartbeats**: the engine records one every [HEARTBEAT_INTERVAL_MS]
 *   while it runs, and on screen on/off, `USER_PRESENT` and
 *   `ACTION_SHUTDOWN` (the shutdown beat is remembered for the rest of the
 *   boot: it is where a reboot's gap starts). When a new process starts
 *   the engine, or the app releases it or finds its process gone
 *   ([observeEngine]), a live run whose last heartbeat is older than
 *   [STALE_AFTER_MS] means the process was gone with nothing to show for
 *   it (an OEM killer, a record lost from the history): the gap runs from
 *   the last heartbeat plus the interval. That start is an upper bound: a
 *   heartbeat timer stops with the CPU (screen off, Doze), which is why a
 *   live process is never judged stale. A run from a previous boot
 *   ([Boot.count]) is a reboot: the gap runs from the boot to the engine's
 *   return, or from the shutdown beat (else the last heartbeat) when a boot
 *   ran without the engine in between, e.g. safe mode ([rebootGapStart]). An exit record
 *   covering the same period wins over either estimate (see [mergeGap]).
 * - **A refused start** ([engineRefused]): the service could not be
 *   promoted to the foreground (the Pixel refused the sticky
 *   restart hours after the app was last visible): the engine is down for
 *   certain, so a gap opens at once, whatever the heartbeat says.
 * - **Permission checks**: the port of iOS `AuthorizationGapTracker`, with
 *   "approved" meaning every step enforcement needs is granted
 *   ([ENFORCEMENT_STEPS]). A revocation is only visible at the next check,
 *   so the gap opens at the last check that saw access (no earlier than
 *   the run, which is where Android departs from iOS: iOS cannot tell
 *   whether a block was intended, Android can), and closes at the first
 *   check that sees access back, or at the release.
 * - **The accessibility detector** ([observeAccessibility]): in a build
 *   that declares it, the apps opened from a work profile, Private Space
 *   or a clone are covered only while the service is enabled in Settings
 *   **and** bound. "Covered" moves [lastCoveredAt]; never having been
 *   covered is not a gap (as on iOS). Seen down afterwards while the
 *   engine runs and no whole-device engine gap is open, a gap with
 *   `scope: otherProfiles` opens at the later of the last covered check
 *   and the run's start: `permissionRevoked` when the toggle is off (a
 *   force stop removes the service from the enabled list; the user turned
 *   it off; Android 17's Advanced Protection keeps it from binding), else the reason
 *   of the death that took the service with the engine (the engine-family
 *   gap closed at this run's start: a Task Manager stop reads
 *   `stoppedByUser`), else `unknown`. It closes at the first check that
 *   sees the service covered again, or at the release; one that lasted
 *   under [MIN_ENGINE_GAP_MS] in all is dropped (a rebind within seconds),
 *   and settling waits until it is that old, so an outage the app looks at
 *   repeatedly stays one gap. While the engine itself is down the whole-device gap speaks
 *   and no scoped gap opens; one already open stays open across the
 *   engine's death and return (coverage was down throughout) and overlaps
 *   the whole-device gap, as a permission gap can.
 *
 * **Clipping and settling.** A gap is stored as observed, open, and is
 * **settled** ([settle]) at every later look of the app and before every
 * mutation of the block or the activities (with the intent that held
 * **before** the mutation): the part observed so far is clipped to the
 * intent and committed as closed pieces, and the open record moves its
 * `from` to now, so a change of intent can never rewrite what was already
 * observed. The gap's end (a start, a release, a grant) settles the last
 * part the same way and drops the open record; a read ([gaps]) clips the
 * open record up to now without committing it. Pieces of one observation
 * carry its [EnforcementGap.origin] and merge back together (same run for
 * engine-family gaps, same origin for permission gaps). A closed
 * engine-family piece shorter than [MIN_ENGINE_GAP_MS] is dropped: the
 * engine's start re-shields what is on screen, so a death the system undid
 * within seconds (a sticky restart) left no hole a user could use. The
 * engine's own interval markers are not a settlement point: an occurrence
 * `unblockAll` lifted stays excluded only until the next occurrence
 * replaces its marker, unless the app looked in between (bounded drift,
 * accepted).
 *
 * **Merge rule.** Engine-family gaps (exit, heartbeat) of one run that
 * overlap or touch are one gap: an exit record's `from` and reason win over
 * a heartbeat's estimate; between two records, the earlier one leads; open
 * wins over closed. Permission gaps merge only with their own pieces, as
 * on iOS, where a grant and a revocation at the same instant are two gaps.
 * A closed piece never merges into a stored open gap (the open record is
 * what the pieces are settled from; a read merges them for the answer).
 *
 * **Retention.** Closed gaps older than [MAX_AGE_MS] are dropped, then the
 * oldest closed ones past [MAX_GAPS]. Open gaps are never dropped (at most
 * one engine gap, one permission gap and one `otherProfiles` gap are open
 * at a time).
 *
 * **Multi-process.** The tracker is an immutable value; every fold returns a
 * new one. The app process (launch, foreground, `getEnforcementGaps`,
 * release) and the `:screentime` process (start, heartbeat) both fold it,
 * so each fold must be one `StateStore.update`: [load] the document's
 * tracker, fold, [store] it back, under the exclusive lock ([update] is
 * that read-fold-write). `now`, the boot, the permission reads, the exit
 * records and the intent are inputs, and the wiring must take them
 * **inside** the update block, so two processes' observations land in lock
 * order. Nothing here touches time or the OS.
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
  /**
   * The last check that saw the accessibility detector enabled and bound,
   * Unix ms. Null until the first: never having been covered is not a gap.
   */
  val lastCoveredAt: Long? = null,
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
    /** `Settings.Global.BOOT_COUNT` when the run started; null for a run stored before it was recorded. */
    val bootCount: Int? = null,
    /** The `ACTION_SHUTDOWN` beat, kept for the rest of the boot (a shutdown is not cancelled): where a reboot's gap starts when a boot ran without the engine. */
    val shutdownAt: Long? = null,
  ) {
    val isLive: Boolean
      get() = stoppedAt == null
  }

  data class ExitKey(val timestamp: Long, val pid: Int)

  /** Whether a permission gap is open (iOS `hasOpenGap`). */
  val hasOpenPermissionGap: Boolean
    get() = gaps.any { it.family == EnforcementGap.Family.permission && it.isOpen }

  val hasOpenEngineGap: Boolean
    get() = gaps.any { it.isEngineFamily && it.isOpen }

  /** Whether an `otherProfiles` gap is open. */
  val hasOpenAccessibilityGap: Boolean
    get() = gaps.any { it.family == EnforcementGap.Family.accessibility && it.isOpen }

  // MARK: - Queries

  /**
   * The gaps still in progress at, or ending after, `since` (Unix ms); every
   * gap when `since` is null. Oldest first. As stored: open gaps unclipped.
   */
  fun gaps(since: Long?): List<EnforcementGap> {
    if (since == null) return gaps
    return gaps.filter { (it.to ?: Long.MAX_VALUE) >= since }
  }

  /**
   * What `getEnforcementGaps(since)` answers at `now`: the stored gaps, the
   * open ones clipped to [intent] up to now (a window in force at now keeps
   * the gap open; a window that ended closes it there; no window since the
   * last settlement reports nothing more), pieces of one observation
   * merged, then the `since` filter.
   */
  fun gaps(since: Long?, now: Long, intent: EnforcementIntent): List<EnforcementGap> {
    var report: List<EnforcementGap> = gaps.filterNot { it.isOpen }
    for (open in gaps.filter { it.isOpen }) {
      for (piece in clip(open, now, intent)) report = mergeGap(report, piece, reporting = true)
    }
    val sorted = sorted(report)
    if (since == null) return sorted
    return sorted.filter { (it.to ?: Long.MAX_VALUE) >= since }
  }

  // MARK: - Settling

  /**
   * An observation at `now` under [intent], the intent that held since the
   * last one: every open gap's part observed so far, `[from, now]`, is
   * clipped and committed as closed pieces (its origin kept), and the open
   * record goes on from now. Call before a mutation changes the intent
   * (with the intent read before it) and at every look of the app, so the
   * drift between an open gap and the intent it is judged by stays bounded.
   */
  fun settle(now: Long, intent: EnforcementIntent): EnforcementGapTracker {
    if (gaps.none { settles(it, now) }) return this
    var folded = gaps
    for (open in gaps.filter { settles(it, now) }) {
      folded = folded - open
      for (piece in clip(open.copy(to = now), now, intent)) folded = mergeGap(folded, piece)
      folded = sorted(folded + open.copy(from = now))
    }
    return copy(gaps = retain(folded, now))
  }

  /**
   * Whether settling at [now] commits [gap]'s part so far. An accessibility
   * gap younger than [MIN_ENGINE_GAP_MS] waits: whether it is kept depends
   * on its whole length ([clip]), unknown until it lasts that long or ends,
   * and committing a younger piece would drop it and leave a hole.
   */
  private fun settles(gap: EnforcementGap, now: Long): Boolean =
    gap.isOpen && gap.from < now &&
      !(gap.family == EnforcementGap.Family.accessibility && now - gap.origin < MIN_ENGINE_GAP_MS)

  // MARK: - Permission checks (the iOS tracker)

  /**
   * Fold in one observation at `now`: `approved` is "every step enforcement
   * needs is granted"; `missing` names the steps that are not, for the
   * detail. Call on every engine heartbeat and whenever the app observes
   * its permissions (launch, foreground, `getEnforcementGaps`). Without a
   * live run there is nothing to enforce, so a revocation opens no gap
   * (the grant is still noted); the gap opens at the later of the last
   * grant and the run's start. A grant closes the open gap, its unsettled
   * part clipped to [intent].
   */
  fun observePermissions(
    approved: Boolean,
    now: Long,
    missing: Collection<String> = emptyList(),
    intent: EnforcementIntent = EnforcementIntent.ALWAYS,
  ): EnforcementGapTracker {
    if (approved) {
      val closed = closeGaps(gaps, now, intent) { it.family == EnforcementGap.Family.permission }
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
  fun observePermissions(granted: Set<PermissionSteps.StepId>, now: Long, intent: EnforcementIntent = EnforcementIntent.ALWAYS): EnforcementGapTracker {
    val missing = ENFORCEMENT_STEPS - granted
    return observePermissions(approved = missing.isEmpty(), now = now, missing = missing.map { it.name }, intent = intent)
  }

  // MARK: - The accessibility detector

  /**
   * What the accessibility detector looks like at `now`: `enabled` (the
   * component is in the enabled-services setting), `bound` (its own record
   * says bound and that process is alive), `blocked` (Android 17's Advanced
   * Protection says no non-tool service may run). Covered is enabled **and
   * bound**, whatever the protection says: measured on the Android 17
   * emulator (2026-11-08), a service bound before the protection was turned
   * on kept running and delivering events, and the gap must report what
   * happened, not what the policy intends; `blocked` only names the cause
   * when the service is down. Call from every look of the app and every
   * engine heartbeat. A build that does not declare the service
   * ([declared] false, an update that turned the option off included) has
   * no coverage to lose: an open scoped gap ends there and [lastCoveredAt]
   * is forgotten, since Settings may keep the component enabled and would
   * otherwise read "enabled, not bound" for ever, a gap the build can no
   * longer close.
   */
  fun observeAccessibility(
    now: Long,
    enabled: Boolean,
    bound: Boolean,
    blocked: Boolean = false,
    intent: EnforcementIntent = EnforcementIntent.ALWAYS,
    declared: Boolean = true,
  ): EnforcementGapTracker {
    if (!declared) {
      if (lastCoveredAt == null && !hasOpenAccessibilityGap) return this
      val closed = closeGaps(gaps, now, intent) { it.family == EnforcementGap.Family.accessibility }
      return copy(gaps = retain(closed, now), lastCoveredAt = null)
    }
    if (enabled && bound) {
      val closed = closeGaps(gaps, now, intent) { it.family == EnforcementGap.Family.accessibility }
      return copy(gaps = retain(closed, now), lastCoveredAt = now)
    }
    val since = lastCoveredAt ?: return this
    val live = run?.takeIf { it.isLive } ?: return this
    if (hasOpenAccessibilityGap || hasOpenEngineGap) return this
    val reason = if (!enabled || blocked) EnforcementGap.Reason.permissionRevoked else deathReason(live) ?: EnforcementGap.Reason.unknown
    val detail = when {
      blocked -> "accessibility service blocked (advanced protection)"
      !enabled -> "accessibility service not enabled"
      else -> "accessibility service enabled but not bound"
    }
    val gap = EnforcementGap(
      from = maxOf(since, live.startedAt),
      to = null,
      reason = reason,
      source = EnforcementGap.Source.accessibility,
      detail = detail,
      scope = EnforcementGap.Scope.otherProfiles,
    )
    return copy(gaps = retain(gaps + gap, now))
  }

  /**
   * The reason of the engine-family gap closed at [live]'s start (the death
   * that took the service down with the engine), if any. The app's look
   * that precedes the revival settles the gap a few milliseconds before
   * the run starts (launch: observe, then the SYNC that starts the engine),
   * and the sliver between the two is dropped as under [MIN_ENGINE_GAP_MS],
   * so a gap ending within that margin of the start counts.
   */
  private fun deathReason(live: EngineRun): EnforcementGap.Reason? =
    gaps.filter { it.isEngineFamily && it.to != null && it.to >= live.startedAt - MIN_ENGINE_GAP_MS }.maxByOrNull { it.to!! }?.reason

  // MARK: - Exit records

  /**
   * Fold the exit history (always the whole list the system returns; skip
   * the fold rather than pass an empty one when it cannot be read): records of
   * [engineProcess] not folded before, in time order. One counts as a gap
   * when it falls inside the known run (after its start, by the run's
   * process, before any orderly stop); the gap closes at the stop when
   * there was one (clipped to [intent]), else it stays open until the next
   * [engineStarted] or [engineStopped]. Call from the app process whenever
   * it looks at the gaps, and from the engine at start (through
   * [engineStarted]).
   */
  fun recordExits(exits: List<ExitRecord>, engineProcess: String, now: Long, intent: EnforcementIntent = EnforcementIntent.ALWAYS): EnforcementGapTracker {
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
      folded = if (gap.isOpen) mergeGap(folded, gap) else clip(gap, now, intent).fold(folded) { acc, piece -> mergeGap(acc, piece) }
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
   * live run, the previous process's last heartbeat (stale, or from a
   * previous boot), closes every open engine gap at `now` (clipped to
   * [intent]) and opens the new run. The same process starting again
   * (every `startForegroundService` reaches `onStartCommand`: a launch, a
   * redelivered intent) only refreshes the heartbeat, so a timer that
   * slept with the CPU is not a gap; a pid is "the same process" only
   * within one boot and while no gap says it died. One `StateStore.update`,
   * in the same block that re-checks the desired state: fold nothing when
   * the engine is not wanted.
   *
   * `bootTime` is `System.currentTimeMillis() - SystemClock.elapsedRealtime()`,
   * Unix ms, and `bootCount` is `Settings.Global.BOOT_COUNT`; null when
   * unknown. The count decides whether the run is from a previous boot; the
   * time only stands in for a run stored without one (it follows the wall
   * clock, so a forward correction after the last heartbeat would read as a
   * reboot).
   */
  fun engineStarted(
    now: Long,
    pid: Int,
    exits: List<ExitRecord>,
    engineProcess: String,
    bootTime: Long?,
    bootCount: Int? = null,
    intent: EnforcementIntent = EnforcementIntent.ALWAYS,
  ): EnforcementGapTracker {
    val boot = Boot(bootTime, bootCount)
    val withExits = recordExits(exits, engineProcess, now, intent)
    val previous = withExits.run
    if (previous != null && previous.isLive && previous.pid == pid && !withExits.hasOpenEngineGap && !rebootedSince(previous, boot)) {
      return withExits.copy(run = previous.copy(heartbeatAt = maxOf(now, previous.heartbeatAt)))
    }
    var folded = withExits.gaps
    if (previous != null && previous.isLive) {
      staleEstimate(previous, now, boot)?.let { estimate -> clip(estimate, now, intent).forEach { folded = mergeGap(folded, it) } }
    }
    folded = closeGaps(folded, now, intent) { it.isEngineFamily }
    return withExits.copy(
      gaps = retain(folded, now),
      run = EngineRun(startedAt = now, pid = pid, heartbeatAt = now, stoppedAt = null, bootCount = bootCount),
    )
  }

  /**
   * The service could not be promoted to the foreground at `now`
   * (`startForeground` refused: the system's sticky restart hours after the
   * app was last visible), or the start intent itself was
   * refused from the background: the engine is down for certain, whatever
   * the heartbeat says, so an engine gap opens now (`killedBySystem`, the
   * system's decision) unless one is open already. Without a run (a first
   * start refused), a run with no process is opened so the gap has one to
   * belong to; the next successful start, from any process, closes it.
   * Only while the engine is wanted: after a release there is nothing to
   * enforce and the fold is a no-op.
   */
  fun engineRefused(now: Long, detail: String): EnforcementGapTracker {
    val stored = run
    if (stored != null && !stored.isLive) return this
    val current = stored ?: EngineRun(startedAt = now, pid = null, heartbeatAt = now, stoppedAt = null)
    if (hasOpenEngineGap) return copy(run = current)
    val gap = EnforcementGap(
      from = now,
      to = null,
      reason = EnforcementGap.Reason.killedBySystem,
      source = EnforcementGap.Source.heartbeat,
      detail = "start refused: $detail",
      run = current.startedAt,
    )
    return copy(gaps = retain(mergeGap(gaps, gap), now), run = current)
  }

  /**
   * One heartbeat at `now` from process `pid`, with the engine's own
   * permission check when it made one (`permissionsApproved`, `missing` as
   * in [observePermissions]). Only the run's process moves the heartbeat:
   * a beat that lands after a
   * release, or from a process whose start was refused, changes nothing
   * about the run. A `shutdown` beat (`ACTION_SHUTDOWN`) is remembered as
   * the instant the device went off, for the rest of the boot.
   */
  fun heartbeat(
    now: Long,
    pid: Int,
    permissionsApproved: Boolean? = null,
    missing: Collection<String> = emptyList(),
    shutdown: Boolean = false,
    intent: EnforcementIntent = EnforcementIntent.ALWAYS,
  ): EnforcementGapTracker {
    val current = run
    val beat = if (current != null && current.isLive && (current.pid == null || current.pid == pid)) {
      copy(run = current.copy(heartbeatAt = maxOf(now, current.heartbeatAt), shutdownAt = if (shutdown) now else current.shutdownAt), gaps = retain(gaps, now))
    } else {
      copy(gaps = retain(gaps, now))
    }
    return if (permissionsApproved == null) beat else beat.observePermissions(permissionsApproved, now, missing, intent)
  }

  /**
   * The app looked at the engine at `now` without starting it: whether its
   * process is alive (`ActivityManager.runningAppProcesses` lists the run's
   * pid) and in which boot. A live run whose process is gone and whose
   * heartbeat is stale, or which ran in a previous boot, is dead with
   * nothing to show for it: its estimate is folded as an **open** gap (the
   * engine is still down; the next [engineStarted] closes it, or a
   * [engineStopped]). An alive process with a stale heartbeat slept with
   * the CPU and is no gap. Call with [recordExits] from launch, foreground
   * and `getEnforcementGaps`, inside one update.
   */
  fun observeEngine(now: Long, engineAlive: Boolean, bootTime: Long?, bootCount: Int? = null): EnforcementGapTracker {
    val current = run?.takeIf { it.isLive } ?: return this
    if (engineAlive || hasOpenEngineGap) return this
    val estimate = staleEstimate(current, now, Boot(bootTime, bootCount)) ?: return this
    return copy(gaps = retain(mergeGap(gaps, estimate.copy(to = null)), now))
  }

  /**
   * The engine was released (nothing left to enforce) at `now`: the run
   * ends and every open gap closes, since no block is intended past this
   * point (each clipped to [intent], which must describe what was intended
   * **before** the release: the caller takes it from the document before
   * clearing the block or the activities). When the engine process is
   * **not** alive (`engineAlive`: in `:screentime`, `Process.myPid() ==
   * run.pid`, since a process whose start was refused can handle the stop
   * while the run's process is dead; from the app, whether
   * `ActivityManager.runningAppProcesses` lists the run's pid), a live run
   * whose heartbeat is stale was dead before the release (a kill with no
   * record), so its estimate is folded first, as at a start. An alive
   * engine with a stale heartbeat only slept with the CPU (a schedule
   * ending in a pocket) and is no gap. Call from whichever process
   * releases the engine, in the same `StateStore.update` that clears the
   * desired state.
   */
  fun engineStopped(
    now: Long,
    engineAlive: Boolean,
    bootTime: Long? = null,
    bootCount: Int? = null,
    intent: EnforcementIntent = EnforcementIntent.ALWAYS,
  ): EnforcementGapTracker {
    val current = run
    var folded = gaps
    if (current != null && current.isLive && !engineAlive) {
      staleEstimate(current, now, Boot(bootTime, bootCount))?.let { estimate -> clip(estimate, now, intent).forEach { folded = mergeGap(folded, it) } }
    }
    folded = closeGaps(folded, now, intent) { true }
    val ended = current?.let { if (it.isLive) it.copy(stoppedAt = now) else it }
    return copy(gaps = retain(folded, now), run = ended)
  }

  /**
   * The wall clock was set by [deltaMs] (`TIME_SET`): the run's instants
   * and the last grant move with it, so the staleness and "last seen"
   * arithmetic stays in the clock the next observation is made in. The
   * recorded gaps are history and stay where they were stamped; their run
   * tags follow the run they belong to.
   */
  fun clockShifted(deltaMs: Long): EnforcementGapTracker {
    if (deltaMs == 0L) return this
    val current = run ?: return copy(lastApprovedAt = lastApprovedAt?.plus(deltaMs), lastCoveredAt = lastCoveredAt?.plus(deltaMs))
    val shifted = current.copy(
      startedAt = current.startedAt + deltaMs,
      heartbeatAt = current.heartbeatAt + deltaMs,
      stoppedAt = current.stoppedAt?.plus(deltaMs),
      shutdownAt = current.shutdownAt?.plus(deltaMs),
    )
    return copy(
      run = shifted,
      gaps = gaps.map { gap -> if (gap.run == current.startedAt) gap.copy(run = shifted.startedAt) else gap },
      lastApprovedAt = lastApprovedAt?.plus(deltaMs),
      lastCoveredAt = lastCoveredAt?.plus(deltaMs),
    )
  }

  // MARK: - Folding

  /** What a live run's silence since its last heartbeat says at `now`, if anything. */
  private fun staleEstimate(live: EngineRun, now: Long, boot: Boot): EnforcementGap? = when {
    rebootedSince(live, boot) -> EnforcementGap(
      from = maxOf(live.startedAt, minOf(rebootGapStart(live, boot, now), now)),
      to = now,
      reason = EnforcementGap.Reason.unknown,
      source = EnforcementGap.Source.heartbeat,
      detail = "reboot: boot ${boot.count ?: "?"} at ${boot.timeMs ?: "?"}, run in boot ${live.bootCount ?: "?"}, " +
        (live.shutdownAt?.let { "shutdown at $it" } ?: "no shutdown seen") + ", last heartbeat at ${live.heartbeatAt}" +
        (bootsMissed(live, boot)?.takeIf { it > 0 }?.let { ", $it boot(s) without the engine" } ?: ""),
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

  /**
   * Where a reboot's gap starts. A single reboot (the boot count moved by
   * one) starts at the boot: the phone was off before it and nothing could
   * be used, so a phone switched off overnight under a block does not report
   * the night. A count that moved by more than one means a boot ran without
   * the engine (safe mode, the classic bypass: third-party apps do not run
   * there), so the gap starts where the engine was last seen: the shutdown
   * beat, else the last heartbeat. Unknown counts keep the older bounds: the
   * shutdown beat, else the boot.
   */
  private fun rebootGapStart(live: EngineRun, boot: Boot, now: Long): Long {
    val missed = bootsMissed(live, boot)
    return when {
      missed == 0 -> boot.timeMs ?: live.shutdownAt ?: now
      missed != null && missed > 0 -> live.shutdownAt ?: live.heartbeatAt
      else -> live.shutdownAt ?: boot.timeMs ?: now
    }
  }

  /** Boots between the run's and the current one; null when either count is unknown. */
  private fun bootsMissed(live: EngineRun, boot: Boot): Int? =
    if (boot.count != null && live.bootCount != null) boot.count - live.bootCount - 1 else null

  /**
   * Whether the run belongs to a previous boot: the boot counts differ when
   * both are known; else a boot after the run's last heartbeat (the wall
   * clock, for runs stored before the count was).
   */
  private fun rebootedSince(live: EngineRun, boot: Boot): Boolean {
    if (boot.count != null && live.bootCount != null) return boot.count != live.bootCount
    return boot.timeMs != null && boot.timeMs > live.heartbeatAt
  }

  /**
   * Ends every open gap [which] selects at [at]: what it still holds is
   * clipped to [intent] and committed, the open record goes.
   */
  private fun closeGaps(gaps: List<EnforcementGap>, at: Long, intent: EnforcementIntent, which: (EnforcementGap) -> Boolean): List<EnforcementGap> {
    var folded = gaps
    for (open in gaps.filter { it.isOpen && which(it) }) {
      folded = folded - open
      for (piece in clip(open.copy(to = maxOf(at, open.from)), at, intent)) folded = mergeGap(folded, piece)
    }
    return folded
  }

  /**
   * The pieces of [gap] inside the intent windows up to [now]: a closed gap
   * yields closed pieces (engine-family ones under [MIN_ENGINE_GAP_MS]
   * dropped; accessibility ones dropped only when the whole observation,
   * from its origin, lasted under it: a rebind within seconds, never a
   * settled piece of a longer outage); an open gap yields closed pieces for
   * the windows that ended and one open piece when a window is in force at
   * now. A piece of no length is kept only when the gap itself has none (a
   * grant and a revocation at the same instant, as on iOS).
   */
  private fun clip(gap: EnforcementGap, now: Long, intent: EnforcementIntent): List<EnforcementGap> {
    val end = gap.to ?: now
    if (end < gap.from) return emptyList()
    // One past the end: whether a window is still in force at `end` is what keeps an open gap open.
    val windows = EnforcementIntent.normalize(intent.windows(gap.from, end + 1))
    val pieces = ArrayList<EnforcementGap>()
    for (window in windows) {
      val from = maxOf(gap.from, window.startMs)
      val to = minOf(end, window.endMs)
      if (to < from) continue
      val open = gap.isOpen && now in window
      if (!open && to == from && end != gap.from) continue
      if (!open && gap.isEngineFamily && to - from < MIN_ENGINE_GAP_MS) continue
      if (!open && gap.family == EnforcementGap.Family.accessibility && to - gap.origin < MIN_ENGINE_GAP_MS) continue
      pieces.add(gap.copy(from = from, to = if (open) null else to))
    }
    return pieces
  }

  /**
   * Adds a gap, merging it with the gaps of the same family and key it
   * overlaps or touches (engine family: the run; permission: the origin):
   * exit records lead over heartbeat estimates, the earlier record leads
   * among records, open wins over closed. A closed piece never merges into
   * the stored open gap of its own origin (that record is what the pieces
   * are settled from); when [reporting], the open piece of a read merges
   * with the closed ones so the answer is one gap.
   */
  private fun mergeGap(gaps: List<EnforcementGap>, gap: EnforcementGap, reporting: Boolean = false): List<EnforcementGap> {
    val touching = gaps.filter {
      it.family == gap.family && it.mergeKey == gap.mergeKey && touches(it, gap) &&
        (reporting || gap.isOpen || !it.isOpen || it.origin != gap.origin)
    }
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

  private fun sorted(gaps: List<EnforcementGap>): List<EnforcementGap> = gaps.sortedWith(compareBy({ it.from }, { it.to ?: Long.MAX_VALUE }))

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
          .put(STOPPED_AT, it.stoppedAt ?: JSONObject.NULL)
          .put(BOOT_COUNT, it.bootCount ?: JSONObject.NULL)
          .put(SHUTDOWN_AT, it.shutdownAt ?: JSONObject.NULL),
      )
    }
    json.put(
      SEEN_EXITS,
      JSONArray().also { array ->
        seenExits.sortedWith(compareBy({ it.timestamp }, { it.pid })).forEach { array.put(JSONObject().put(TIMESTAMP, it.timestamp).put(PID, it.pid)) }
      },
    )
    lastApprovedAt?.let { json.put(LAST_APPROVED_AT, it) }
    lastCoveredAt?.let { json.put(LAST_COVERED_AT, it) }
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

    /**
     * A closed engine-family piece shorter than this is dropped when it is
     * settled or closed: the engine's start replays the foreground history
     * and shields the app on screen, so a death undone within seconds (the
     * system's sticky restart lands in about 2 s on the emulator) left no
     * hole a user could use. A user stop followed by a relaunch takes
     * longer than this; one that does not is treated the same.
     */
    const val MIN_ENGINE_GAP_MS = 10_000L

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
    private const val BOOT_COUNT = "bootCount"
    private const val SHUTDOWN_AT = "shutdownAt"
    private const val SEEN_EXITS = "seenExits"
    private const val TIMESTAMP = "timestamp"
    private const val LAST_APPROVED_AT = "lastApprovedAt"
    private const val LAST_COVERED_AT = "lastCoveredAt"

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
          bootCount = r.optLongOrNull(BOOT_COUNT)?.toInt(),
          shutdownAt = r.optLongOrNull(SHUTDOWN_AT),
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
        gaps = gaps.sortedWith(compareBy({ it.from }, { it.to ?: Long.MAX_VALUE })),
        run = run,
        seenExits = seen,
        lastApprovedAt = json.optLongOrNull(LAST_APPROVED_AT),
        lastCoveredAt = json.optLongOrNull(LAST_COVERED_AT),
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
