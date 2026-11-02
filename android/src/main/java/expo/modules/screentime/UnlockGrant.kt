package expo.modules.screentime

import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID

/**
 * The whole-block temporary unlock in progress: the Android twin of the iOS
 * unlock state (`expo_st_unlock_budget` / `_consumed` / `_granted_at` in
 * `ios/SharedDefaults.swift`). A budget of SECONDS spent only while one of
 * the suspended apps is in the foreground (measured by the engine, not
 * wall-clock); unspent budget expires at midnight of the device's day.
 *
 * There is ONE whole-block unlock at a time, as on iOS: a grant while one is
 * in progress is a renewal that replaces it (budget and consumption restart
 * from the new grant, under a new [id]). Targeted unlocks
 * (`temporaryUnlock(…, { target })`) are [ShieldSnooze]s and run
 * independently: `isTemporarilyUnlocked` and `getRemainingUnlockSeconds`
 * describe this grant alone.
 *
 * WHICH sources the grant suspends (the iOS `SuspendedSources`, captured at
 * the commit) belongs to the block model and its wiring: the wiring step
 * stores that beside this record, under its own key, and feeds this meter
 * the set of packages those sources shield.
 */
internal data class UnlockGrant(
  /** Unique per grant, so the engine's write-back can never be taken for a renewal's. */
  val id: String,
  /** The metered budget, after the floor. */
  val budgetSeconds: Int,
  /** Unix milliseconds. */
  val grantedAt: Long,
  val meter: UsageAccumulator = UsageAccumulator.EMPTY,
) {
  val consumedSeconds: Int
    get() = meter.consumedSeconds

  /** The budget is spent, whatever the day. */
  val isSpent: Boolean
    get() = consumedSeconds >= budgetSeconds

  /**
   * Budget minus consumption, floored at 0; 0 for a grant from another
   * calendar day of [zone] — stale even if the day-end tick never ran (the
   * device may have slept through midnight). The iOS
   * `remainingUnlockSeconds`.
   */
  fun remainingSeconds(nowMs: Long, zone: TimeZone): Int =
    if (!UsageMeter.isSameDay(grantedAt, nowMs, zone)) 0 else maxOf(0, budgetSeconds - consumedSeconds)

  /** `isTemporarilyUnlocked`: a grant with budget left today. A grant that is not active is cleared by the caller (a relock). */
  fun isActive(nowMs: Long, zone: TimeZone): Boolean = remainingSeconds(nowMs, zone) > 0

  /**
   * The grant after one engine tick in which one of [meteredPackages] was
   * or was not in the foreground. Consumption can never exceed the
   * wall-clock since the grant, and a sample from before the grant charges
   * nothing.
   */
  fun observing(sample: UsageSample, meteredPackages: Set<String>): UnlockGrant =
    copy(meter = meter.observe(sample.atMs, sample.isUsing(meteredPackages), maxChargeMs = sample.atMs - grantedAt - meter.consumedMs))

  /**
   * Whether storing this grant instead of [previous] changes what another
   * process would read (see [ShieldSnooze.isWorthPersistingOver]): the
   * engine persists at most once a second while the unlock is in use.
   */
  fun isWorthPersistingOver(previous: UnlockGrant): Boolean =
    id != previous.id || consumedSeconds != previous.consumedSeconds || meter.inUse != previous.meter.inUse

  /** The `TemporaryUnlockGrant` JS receives for this grant; [target] is null for the whole block. */
  fun toResponse(target: Map<String, String>? = null): Map<String, Any?> = mapOf(
    "budgetSeconds" to budgetSeconds,
    "grantedAt" to grantedAt.toDouble(),
    "meteringSeconds" to UsageMeter.METERING_SECONDS,
    "target" to target,
  )

  fun toJson(): JSONObject = JSONObject()
    .put(ID, id)
    .put(BUDGET_SECONDS, budgetSeconds)
    .put(GRANTED_AT, grantedAt)
    .put(METER, meter.toJson())

  companion object {
    /** The document key the grant is stored under. */
    const val KEY = "unlock"
    private const val ID = "id"
    private const val BUDGET_SECONDS = "budgetSeconds"
    private const val GRANTED_AT = "grantedAt"
    private const val METER = "meter"

    /** A fresh grant of [requestedSeconds] (floored) at [nowMs]. */
    fun grant(requestedSeconds: Int, nowMs: Long, id: String = UUID.randomUUID().toString()): UnlockGrant =
      UnlockGrant(id, UsageMeter.budget(requestedSeconds), nowMs)

    /**
     * The grant to store when the engine writes [metered] back over the
     * [stored] grant another process may have changed in between: none
     * when the app relocked meanwhile, the stored one when it is a renewal
     * (another id, not metered yet), none when the engine spent the grant
     * it metered, the engine's otherwise.
     */
    fun merging(stored: UnlockGrant?, metered: UnlockGrant): UnlockGrant? = when {
      stored == null -> null
      stored.id != metered.id -> stored
      metered.isSpent -> null
      else -> metered
    }

    /**
     * The grant a document holds, or null when there is none or it is
     * malformed (no id, a budget that is not positive, no grant instant):
     * then nothing is unlocked, fail closed.
     */
    fun fromJson(json: JSONObject?): UnlockGrant? {
      if (json == null) return null
      val id = (json.opt(ID) as? String)?.takeIf { it.isNotEmpty() } ?: return null
      val budget = JsonNumbers.int(json.opt(BUDGET_SECONDS))?.takeIf { it > 0 } ?: return null
      val grantedAt = JsonNumbers.long(json.opt(GRANTED_AT)) ?: return null
      return UnlockGrant(id, budget, grantedAt, UsageAccumulator.fromJson(json.optJSONObject(METER)))
    }

    fun read(document: JSONObject): UnlockGrant? = fromJson(document.optJSONObject(KEY))

    /** Stores [grant] in [document]; null clears it (a relock, an expiry). */
    fun write(document: JSONObject, grant: UnlockGrant?) {
      if (grant == null) document.remove(KEY) else document.put(KEY, grant.toJson())
    }
  }
}

/**
 * The checks of `temporaryUnlock(budgetSeconds, { target? })` that need no
 * block composition, as pure functions of simple inputs — the twin of the
 * guards at the top of the iOS `AsyncFunction("temporaryUnlock")`. The
 * wiring step runs them before touching the store, then applies the block
 * model's own verdicts (the targeted unlock's `targetedUnlockVerdict`, which
 * sources the whole-block grant suspends).
 */
internal object TemporaryUnlockRequest {
  /** A refusal, in the `ScreenTimeErrorCode` vocabulary (`ScreenTimeException(code, message)` at the seam). */
  data class Refusal(val code: String, val message: String)

  /** A request this file could judge; the block model judges the rest. */
  sealed class Verdict {
    /** A whole-block unlock of [budgetSeconds] (floored). */
    data class WholeBlock(val budgetSeconds: Int) : Verdict()

    /** A targeted unlock of [budgetSeconds] (floored) of [target] (`{ handle, type }`, unchanged). */
    data class Targeted(val budgetSeconds: Int, val target: Map<String, String>) : Verdict()

    data class Refused(val refusal: Refusal) : Verdict()
  }

  const val NO_ACTIVE_BLOCK = "NO_ACTIVE_BLOCK"
  const val UNSUPPORTED = "UNSUPPORTED"
  const val INVALID_CONFIG = ScreenTimeException.INVALID_CONFIG

  private val TOKEN_TYPES = setOf(TokenHandles.APPLICATION, TokenHandles.CATEGORY, TokenHandles.WEB_DOMAIN)

  /**
   * Judge a request. [requestedSeconds] is the JS argument as it arrived
   * (any number); [target] is the `target` option as it arrived (a map, or
   * null / absent). [hasActiveBlock] says whether the union of active
   * sources is non-empty right now (sources a grant in progress suspended
   * count: a renewal replaces that grant), and [containsAllowlist] whether
   * any of those sources is an allowlist (the iOS
   * `BlockComposition.containsAllowlist` — the manual block's mode alone is
   * not enough: an allowlist schedule counts).
   *
   * The whole-block rules follow iOS: nothing blocked is `NO_ACTIVE_BLOCK`;
   * an allowlist cannot be unlocked whole (`UNSUPPORTED`): on iOS no usage
   * event can meter "every other app", and the contract keeps one behaviour
   * on both platforms. A targeted request only needs a well-formed handle
   * here; whether that target is shielded is the block model's verdict.
   */
  fun judge(requestedSeconds: Any?, target: Any?, hasActiveBlock: Boolean, containsAllowlist: Boolean): Verdict {
    val requested = UsageMeter.seconds(requestedSeconds)
      ?: return refuse(INVALID_CONFIG, "budgetSeconds must be a finite number of seconds")
    val budget = UsageMeter.budget(requested)

    if (target != null && target != JSONObject.NULL) {
      val handle = parseTarget(target)
        ?: return refuse(
          INVALID_CONFIG,
          "target must be a TokenHandle ({ handle, type }) of this device: pass one returned by getTokens or a shield interaction, unchanged",
        )
      return Verdict.Targeted(budget, handle)
    }

    if (containsAllowlist) {
      return refuse(UNSUPPORTED, "A temporary unlock cannot meter an allowlist block (it shields every other app); use unblockAll or a schedule instead")
    }
    if (!hasActiveBlock) {
      return refuse(NO_ACTIVE_BLOCK, "Nothing is blocked — call blockSelection or start a schedule first")
    }
    return Verdict.WholeBlock(budget)
  }

  /**
   * A `TokenHandle` as JS sends it: a map with non-empty `handle` and
   * `type` strings, the type one this library issues. Resolving it to a
   * package needs the install secret and the selections (`TokenHandles.resolve`).
   */
  fun parseTarget(value: Any?): Map<String, String>? {
    val map = value as? Map<*, *> ?: return null
    val type = map["type"] as? String ?: return null
    val handle = map["handle"] as? String ?: return null
    if (type.isEmpty() || handle.isEmpty() || type !in TOKEN_TYPES) return null
    return mapOf("handle" to handle, "type" to type)
  }

  private fun refuse(code: String, message: String) = Verdict.Refused(Refusal(code, message))
}
