package expo.modules.screentime

import org.json.JSONObject
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * How a usage budget is metered on Android: the twin of the iOS `UsageMeter`
 * enum in `ios/BlockComposition.swift`, shared by the temporary unlock (the
 * whole block, [UnlockGrant]) and the shield snooze (one target,
 * [ShieldSnooze]) so the two meters behave the same.
 *
 * iOS cannot read usage, so it registers a ladder of DeviceActivity
 * thresholds (one event per step, 30 s to 120 s apart) and learns
 * consumption from the events that fire: that is why `meteringSeconds` is
 * `max(30, ceil(budget / 60))` there. Android's engine sees the foreground
 * app on every tick, so the budget is an exact accumulator of observed
 * foreground time ([UsageAccumulator]) and `meteringSeconds` is 1: the
 * remaining budget moves one second at a time, as the contract says
 * (`TemporaryUnlockGrant.meteringSeconds`, "Android: 1 [review R10]").
 *
 * The floor stays: "30 s floor on both platforms" (the contract), strictest
 * common denominator. The day boundary stays: a budget never carries across
 * midnight of the device's calendar.
 *
 * Pure JVM, unit-tested in `android/src/test`.
 */
internal object UsageMeter {
  /** The floor of every budget (iOS `UsageMeter.stepSeconds`). */
  const val FLOOR_SECONDS = 30

  /**
   * The grain the remaining budget moves in, reported to JS as the grant's
   * `meteringSeconds`: whole seconds of observed foreground use.
   */
  const val METERING_SECONDS = 1

  /**
   * An interval between two consecutive samples longer than this is not
   * charged, whatever the previous sample said: the engine was not
   * observing (frozen, killed, the device asleep), so that time is
   * unmeasured — step 6 reports it as an enforcement gap. Below it, a short
   * stall (a GC pause, a late tick) is charged in full when the target was
   * in use, fail closed. The engine ticks every 100 ms; sixty seconds is
   * far outside any tick jitter.
   */
  const val MAX_SAMPLE_GAP_MS = 60_000L

  /** The budget actually metered for a requested one: never below the floor. */
  fun budget(requestedSeconds: Int): Int = maxOf(FLOOR_SECONDS, requestedSeconds)

  /**
   * A duration from a JS value: numbers reach Kotlin as `Int`, `Long` or
   * `Double`, never reliably one of them. Anything else — a string, null, a
   * non-finite double — is not a duration. The iOS `UsageMeter.seconds`.
   */
  fun seconds(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
    // Double, Float, and the BigDecimal / BigInteger `org.json` parses
    // decimals and wide integers into on the desktop JVM.
    is Number -> value.toDouble().let { if (it.isFinite() && kotlin.math.abs(it) < Int.MAX_VALUE) it.toInt() else null }
    else -> null
  }

  /**
   * The calendar day of [epochMs] in [zone] (the device's zone), as
   * (year, day of year). `java.util.Calendar`, not `java.time`: the module's
   * floor is API 24 and `java.time` needs 26 or core-library desugaring.
   */
  fun day(epochMs: Long, zone: TimeZone): Pair<Int, Int> =
    calendar(epochMs, zone).let { it.get(Calendar.YEAR) to it.get(Calendar.DAY_OF_YEAR) }

  /** Whether two instants fall on the same calendar day of [zone]. */
  fun isSameDay(aMs: Long, bMs: Long, zone: TimeZone): Boolean = day(aMs, zone) == day(bMs, zone)

  /**
   * When a budget granted at [grantedAtMs] is void whatever is left: the
   * first instant of the next calendar day in [zone] (exclusive bound). The
   * iOS `meterEnd` ends its DeviceActivity at 23:59:59 for the same reason;
   * its 15-minute-minimum detour exists only because DeviceActivity refuses
   * shorter intervals, so it has no counterpart here.
   */
  fun dayEndMs(grantedAtMs: Long, zone: TimeZone): Long = calendar(grantedAtMs, zone).apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, 1)
  }.timeInMillis

  private fun calendar(epochMs: Long, zone: TimeZone): Calendar = GregorianCalendar(zone).apply { timeInMillis = epochMs }
}

/**
 * What the engine knows on one tick: the package in the foreground (null
 * when none — the launcher, the lock screen) and whether the screen is
 * interactive. Use is counted only while the screen is on: a target left
 * in the foreground behind a dark screen is not being used.
 */
internal data class UsageSample(
  val atMs: Long,
  val foregroundPackage: String?,
  val screenInteractive: Boolean,
) {
  /** Whether one of [packages] is in use right now. */
  fun isUsing(packages: Set<String>): Boolean = screenInteractive && foregroundPackage != null && foregroundPackage in packages

  fun isUsing(packageName: String): Boolean = screenInteractive && foregroundPackage == packageName
}

/**
 * An exact meter of foreground time: the budget's consumption, fed one
 * sample per engine tick. Each sample says whether the metered target is in
 * use at that instant; while it is, the time between two consecutive
 * samples is charged (the state held until the next observation), capped
 * by [UsageMeter.MAX_SAMPLE_GAP_MS]. A sample with an earlier clock than the
 * previous one charges nothing and rebases the stretch: consumption only
 * ever grows, like iOS's out-of-order steps.
 *
 * While the target is NOT in use, a sample leaves the meter untouched — so
 * an idle meter never dirties the document and the engine writes the store
 * only while a budget is being spent.
 *
 * Persisted with its budget, so the engine process resumes the meter where
 * it left it; immutable, so every update is one read-decide-write under the
 * state store's exclusive lock.
 */
internal data class UsageAccumulator(
  /** Observed foreground time, in milliseconds. */
  val consumedMs: Long = 0,
  /** The instant the in-use stretch in progress was last charged at; null while not in use. */
  val sampledAtMs: Long? = null,
  /** Whether the last sample saw the target in use. */
  val inUse: Boolean = false,
) {
  /** Whole seconds of observed use — what `consumedSeconds` means on iOS. */
  val consumedSeconds: Int
    get() = (consumedMs / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

  /**
   * The meter after observing that the target is ([inUse]) or is not in use
   * at [atMs]. [maxChargeMs] bounds what this sample may add, so a caller can
   * refuse to credit more use than the wall-clock since the grant allows.
   */
  fun observe(atMs: Long, inUse: Boolean, maxChargeMs: Long = Long.MAX_VALUE): UsageAccumulator {
    if (!this.inUse) return if (inUse) copy(sampledAtMs = atMs, inUse = true) else this
    val since = sampledAtMs
    val elapsed = if (since != null && atMs > since) atMs - since else 0L
    val charged = if (elapsed in 1..UsageMeter.MAX_SAMPLE_GAP_MS) minOf(elapsed, maxOf(0L, maxChargeMs)) else 0L
    return UsageAccumulator(consumedMs + charged, if (inUse) atMs else null, inUse)
  }

  fun toJson(): JSONObject = JSONObject()
    .put(CONSUMED_MS, consumedMs)
    .put(SAMPLED_AT_MS, sampledAtMs ?: JSONObject.NULL)
    .put(IN_USE, inUse)

  companion object {
    private const val CONSUMED_MS = "consumedMs"
    private const val SAMPLED_AT_MS = "sampledAtMs"
    private const val IN_USE = "inUse"

    val EMPTY = UsageAccumulator()

    /**
     * The meter a document holds; a missing or malformed one is a fresh
     * meter — the budget is then metered from here on, nothing is lost but
     * a sample.
     */
    fun fromJson(json: JSONObject?): UsageAccumulator {
      if (json == null) return EMPTY
      val consumed = JsonNumbers.long(json.opt(CONSUMED_MS)) ?: 0L
      val inUse = json.optBoolean(IN_USE, false)
      val sampledAt = if (inUse) JsonNumbers.long(json.opt(SAMPLED_AT_MS)) else null
      return UsageAccumulator(maxOf(0L, consumed), sampledAt, inUse && sampledAt != null)
    }
  }
}

/**
 * Numbers in a document written by another process or another build of
 * this library arrive as `Int`, `Long` or `Double` — or `BigDecimal` on the
 * desktop `org.json` the unit tests run on (`org.json` keeps whatever the
 * writer put in, and a JS number travels as a double).
 */
internal object JsonNumbers {
  fun long(value: Any?): Long? = when (value) {
    is Long -> value
    is Int -> value.toLong()
    is Number -> value.toDouble().let { if (it.isFinite() && kotlin.math.abs(it) < Long.MAX_VALUE.toDouble()) it.toLong() else null }
    else -> null
  }

  fun int(value: Any?): Int? = UsageMeter.seconds(value)
}
