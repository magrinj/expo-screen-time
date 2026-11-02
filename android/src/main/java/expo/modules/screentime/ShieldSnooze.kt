package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * A shield snooze in progress: the twin of `ShieldSnooze` in
 * `ios/BlockComposition.swift`. The target whose shield the user pressed the
 * snooze button on — or passed to `temporaryUnlock(…, { target })`, the same
 * grant made from the app — lifted until it has been USED for
 * [budgetSeconds] (the `duration` of `{ type: "snooze", duration }`, floored
 * by [UsageMeter.budget]).
 *
 * iOS meters a snooze with its own DeviceActivity (`expo_st_snooze_<id>`)
 * that the monitor extension spends; Android meters it with a
 * [UsageAccumulator] the engine feeds on every tick, so the budget is spent
 * only while that target is in the foreground with the screen on, and the
 * shield comes back even if the host app is never launched.
 *
 * A snooze is a suspension of ONE token, applied at projection time by the
 * block model: the sources covering the target stay active and
 * own its shield configuration, the other shields stay up. It is
 * independent of the whole-block [UnlockGrant]: both meter the target while
 * both run, and the one that ends first does not end the other.
 * `blockSelection`, `unblockAll` and `relock` end every snooze (the wiring
 * clears the list); a spent budget or the day's end ends one.
 *
 * The target is persisted as its kind and its native identity — the package
 * name for an application — so every process reads the same target. The JS
 * handle is derived from it by `TokenHandles` at the seam, never stored.
 * Only applications can be metered in v1 ([isMeterable]): a category or a
 * web domain cannot be observed, so a snooze over one would never be spent
 * and is refused at the grant rather than lifted until midnight.
 */
internal data class ShieldSnooze(
  /**
   * Unique per grant, so the engine's write-back can never be taken for
   * another snooze's — the same target snoozed again gets a new id.
   */
  val id: String,
  /** `application`, `category` or `webDomain` (`TokenHandles` constants). */
  val tokenKind: String,
  /** The token's native identity: the package name for an application. */
  val token: String,
  val budgetSeconds: Int,
  /** Unix milliseconds. */
  val grantedAt: Long,
  val meter: UsageAccumulator = UsageAccumulator.EMPTY,
) {
  val consumedSeconds: Int
    get() = meter.consumedSeconds

  val remainingSeconds: Int
    get() = maxOf(0, budgetSeconds - consumedSeconds)

  val isSpent: Boolean
    get() = consumedSeconds >= budgetSeconds

  /** The same target as [other] (whatever the ids and budgets). */
  fun coversSameTargetAs(other: ShieldSnooze): Boolean = tokenKind == other.tokenKind && token == other.token

  /** Whether [sample] shows this snooze's target in use. */
  fun isInUse(sample: UsageSample): Boolean = isMeterable(tokenKind) && sample.isUsing(token)

  /**
   * The snooze after one engine tick. Consumption can never exceed the
   * wall-clock since the grant (the iOS plausibility rule), and a sample
   * from before the grant charges nothing.
   */
  fun observing(sample: UsageSample): ShieldSnooze =
    copy(meter = meter.observe(sample.atMs, isInUse(sample), maxChargeMs = sample.atMs - grantedAt - meter.consumedMs))

  /**
   * Whether storing this snooze instead of [previous] changes what another
   * process would read: another snooze, the consumption in whole seconds,
   * or whether the target is in use. The in-use stretch's last instant is
   * in-memory detail: the engine persists at most once a second per snooze
   * in use, and a restart re-reads a meter at most a second old.
   */
  fun isWorthPersistingOver(previous: ShieldSnooze): Boolean =
    id != previous.id || consumedSeconds != previous.consumedSeconds || meter.inUse != previous.meter.inUse

  // Document shape, for the state store.

  fun toJson(): JSONObject = JSONObject()
    .put(ID, id)
    .put(TOKEN_KIND, tokenKind)
    .put(TOKEN, token)
    .put(BUDGET_SECONDS, budgetSeconds)
    .put(GRANTED_AT, grantedAt)
    .put(METER, meter.toJson())

  companion object {
    private const val ID = "id"
    private const val TOKEN_KIND = "tokenKind"
    private const val TOKEN = "token"
    private const val BUDGET_SECONDS = "budgetSeconds"
    private const val GRANTED_AT = "grantedAt"
    private const val METER = "meter"

    /** Which kinds the engine can meter: an application's foreground time. */
    fun isMeterable(tokenKind: String): Boolean = tokenKind == TokenHandles.APPLICATION

    /**
     * A fresh snooze of [requestedSeconds] (floored) over the target,
     * granted at [nowMs]; null for a target that cannot be metered (the
     * seam answers `UNSUPPORTED`) or an empty id or token.
     */
    fun grant(id: String, tokenKind: String, token: String, requestedSeconds: Int, nowMs: Long): ShieldSnooze? {
      if (id.isEmpty() || token.isEmpty() || !isMeterable(tokenKind)) return null
      return ShieldSnooze(id, tokenKind, token, UsageMeter.budget(requestedSeconds), nowMs)
    }

    /**
     * The snooze a document holds, or null when it is not one: a missing or
     * empty id, kind or token, a budget that is not positive. A malformed
     * record is dropped — the target is then shielded again, fail closed.
     * Strings are read with `opt(…) as? String`, never `optString`:
     * Android's `org.json` turns a JSON null into the string "null".
     */
    fun fromJson(json: JSONObject): ShieldSnooze? {
      val id = (json.opt(ID) as? String)?.takeIf { it.isNotEmpty() } ?: return null
      val tokenKind = (json.opt(TOKEN_KIND) as? String)?.takeIf { it.isNotEmpty() } ?: return null
      val token = (json.opt(TOKEN) as? String)?.takeIf { it.isNotEmpty() } ?: return null
      val budget = JsonNumbers.int(json.opt(BUDGET_SECONDS))?.takeIf { it > 0 } ?: return null
      val grantedAt = JsonNumbers.long(json.opt(GRANTED_AT)) ?: return null
      return ShieldSnooze(id, tokenKind, token, budget, grantedAt, UsageAccumulator.fromJson(json.optJSONObject(METER)))
    }
  }
}

/**
 * The rules over the list of snoozes in progress (the iOS `ShieldSnoozes`).
 *
 * Two writers: the app and the shield Activity grant and end snoozes, one
 * read-decide-write each under the state store's exclusive lock
 * ([read] in, [write] out). The engine alone advances the meters: it keeps
 * the list in memory between ticks, and when a tick is worth persisting
 * ([Observed.changed]) it re-reads the stored list under the lock and
 * writes [merging] back, so a grant or an end the other processes made in
 * between is kept.
 */
internal object ShieldSnoozes {
  /** The document key the list is stored under. */
  const val KEY = "snoozes"

  /**
   * The snoozes still in force at [nowMs]: a budget never carries across
   * midnight (like the temporary unlock), so a snooze granted on another
   * calendar day of [zone] is void — even before the engine's day-end tick,
   * which a device asleep at midnight may miss, has cleared it.
   */
  fun current(snoozes: List<ShieldSnooze>, nowMs: Long, zone: TimeZone): List<ShieldSnooze> =
    snoozes.filter { UsageMeter.isSameDay(it.grantedAt, nowMs, zone) }

  /** What [granting] did to the list. */
  data class Granted(val snoozes: List<ShieldSnooze>, val replaced: List<ShieldSnooze>)

  /**
   * [snoozes] (the [current] ones) with [snooze] added. A snooze over the
   * same target is replaced (its budget restarts); snoozes over other
   * targets run on, each with its own budget — pressing snooze on a second
   * app does not end the first.
   */
  fun granting(snooze: ShieldSnooze, snoozes: List<ShieldSnooze>): Granted {
    val replaced = snoozes.filter { it.coversSameTargetAs(snooze) }
    return Granted(snoozes.filter { !it.coversSameTargetAs(snooze) } + snooze, replaced)
  }

  /** What one engine tick did to the list. */
  data class Observed(
    val snoozes: List<ShieldSnooze>,
    /** The snoozes whose budget this tick spent: their targets' shields come back with the next projection. */
    val spent: List<ShieldSnooze>,
    /**
     * Whether the tick is worth persisting: a snooze was spent, a whole
     * second was consumed, or a target entered or left use. False on an
     * idle tick and on the nine ticks a second that move only the meter's
     * last instant, so a snooze in use costs one document write a second,
     * not ten.
     */
    val changed: Boolean,
  )

  /**
   * Apply [sample] to every snooze: the ones whose target is in use are
   * charged, a spent one is removed. The iOS counterpart records the
   * meters' threshold steps (`ShieldSnoozes.recording`); here the engine is
   * the meter.
   */
  fun observing(sample: UsageSample, snoozes: List<ShieldSnooze>): Observed {
    val observed = snoozes.map { it.observing(sample) }
    val (spent, live) = observed.partition { it.isSpent }
    val changed = spent.isNotEmpty() || observed.zip(snoozes).any { (now, before) -> now.isWorthPersistingOver(before) }
    return Observed(live, spent, changed)
  }

  /**
   * The list to store when the engine writes [observed] back over the
   * [stored] list another process may have changed in between: a stored
   * snooze the engine metered takes the engine's meter (same id — a
   * replaced snooze has a new id and keeps its fresh meter), a snooze the
   * engine does not know yet is kept as stored, one the engine spent is
   * gone, and one the store no longer holds (ended, replaced) stays gone.
   */
  fun merging(stored: List<ShieldSnooze>, observed: Observed): List<ShieldSnooze> {
    val byId = observed.snoozes.associateBy { it.id }
    val spentIds = observed.spent.map { it.id }.toSet()
    return stored.filter { it.id !in spentIds }.map { byId[it.id] ?: it }
  }

  /** What [ending] did to the list. */
  data class Ended(val snoozes: List<ShieldSnooze>, val ended: Boolean)

  /**
   * [snoozes] without the one of [id] (its day is over, or the app ended
   * it). Ending a snooze already gone does nothing.
   */
  fun ending(id: String, snoozes: List<ShieldSnooze>): Ended {
    val remaining = snoozes.filter { it.id != id }
    return Ended(remaining, remaining.size != snoozes.size)
  }

  /**
   * The persisted list, every snooze included (expired ones too —
   * [current] is the judge of what is in force). Records this build cannot
   * read are dropped.
   */
  fun read(document: JSONObject): List<ShieldSnooze> {
    val array = document.optJSONArray(KEY) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(ShieldSnooze::fromJson) }
  }

  /** Stores [snoozes] in [document]; an empty list leaves no key. */
  fun write(document: JSONObject, snoozes: List<ShieldSnooze>) {
    if (snoozes.isEmpty()) {
      document.remove(KEY)
    } else {
      document.put(KEY, JSONArray().also { array -> snoozes.forEach { array.put(it.toJson()) } })
    }
  }
}
