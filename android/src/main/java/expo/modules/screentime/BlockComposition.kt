package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which source a [BlockSource] is: the twin of `BlockSourceKind` in
 * `ios/BlockComposition.swift`. Lower [precedence] wins the shield
 * configuration of a shared target: threshold > schedule > manual >
 * restriction (the default config applies when nothing owns a target).
 */
internal sealed class BlockSourceKind(val precedence: Int) {
  /** `blockSelection` — a single selection, replaced by the next call. */
  object Manual : BlockSourceKind(2) {
    override fun toString() = "manual"
  }

  /** A monitored interval that is in progress. */
  data class Schedule(val name: String) : BlockSourceKind(1)

  /** A usage threshold with `blockOnThreshold` that fired inside an interval. */
  data class Threshold(val name: String) : BlockSourceKind(0)

  /**
   * `denyAppRemoval` / `denyAppInstallation` over the system UIs that
   * remove or install apps ([RestrictionSources]). No iOS twin: there the
   * flags are enforced by the OS without a shield. Lowest precedence, so a
   * user's own block of a store owns the shield and its configuration when
   * both cover it. Composed by the engine only: the module's compositions
   * (`isBlocked`, the unlock judge) never hold one.
   */
  data class Restriction(val kind: RestrictionKind) : BlockSourceKind(3) {
    override fun toString() = "restriction:${kind.name}"
  }

  /** The activity behind a schedule or threshold; null for the manual block and a restriction — the owner `ShieldActivity` resolves its config from. */
  val activityName: String?
    get() = when (this) {
      is Manual, is Restriction -> null
      is Schedule -> name
      is Threshold -> name
    }
}

/**
 * One source of shields, resolved to the packages it applies to: the twin of
 * the Swift `BlockSource`, with package names where iOS has tokens. The
 * effective block is the UNION of every source ([BlockComposition]).
 */
internal data class BlockSource(
  val kind: BlockSourceKind,
  /** When the source became active, Unix ms (0 for the manual block). Among schedules the latest one wins a shared target. */
  val startedAtMs: Long,
  /** Blocklist: the apps it shields. Allowlist: the apps it leaves unshielded (every other app is shielded; empty = allow nothing). */
  val applications: Set<String>,
  /** How [applications] is applied. Thresholds are always blocklists. */
  val mode: BlockMode = BlockMode.blocklist,
) {
  /**
   * Whether this source shields anything at all: what `isBlocked()` sums. A
   * blocklist over an emptied or deleted selection shields nothing (iOS
   * omits such a source); an allowlist always shields "every other app",
   * even when its selection was emptied after the fact (fail closed).
   */
  val shieldsSomething: Boolean
    get() = mode == BlockMode.allowlist || applications.isNotEmpty()

  /**
   * Whether this source shields [packageName]: the Swift `covers(anyOf:)`
   * for an application token. A blocklist covers what it lists; an
   * allowlist covers every app it does not allow.
   */
  fun covers(packageName: String): Boolean = when (mode) {
    BlockMode.blocklist -> packageName in applications
    BlockMode.allowlist -> packageName !in applications
  }
}

/**
 * The block composition, the twin of `ios/BlockComposition.swift` over the
 * two Android block models: the manual block ([ManualBlock], apps
 * captured at block time) and the monitoring state's blocks
 * ([MonitoringState.activeBlocks]: schedules in progress and fired
 * `blockOnThreshold` events, resolved live from the selection store, as iOS
 * re-projects the stored selection).
 *
 * Union rule: a target is shielded if ANY source shields it — a blocklist
 * listing it, or an allowlist not allowing it; an app a blocklist shields
 * directly stays shielded whatever an allowlist allows, and under two
 * allowlists an app is unshielded only if both allow it. Over applications,
 * that is exactly what iOS's per-store projection (`storeShields`) merged by
 * ManagedSettings yields, so [ShieldPolicy] decides per package from
 * [BlockSource.covers] alone; there are no stores to project here.
 *
 * **Lifted targets.** A shield snooze ([ShieldSnooze]) and the
 * shield's `unblock` action ([ShieldLifts]) take ONE app out of every
 * source covering it, and nothing else — the iOS `storeShields(of:snoozed:)`
 * projection, which removes a snoozed token from every store and excepts it
 * from an allowlist's `.all(except:)`. Here the policy subtracts the lifted
 * set after the union ([ShieldPolicy.shields]), and `isBlocked()` counts a
 * blocklist source only for the apps it still shields ([shieldsSomething]).
 * A whole-block unlock is different: it suspends whole SOURCES by identity
 * ([SuspendedSources]), so a source that starts during the grant shields.
 *
 * Pure (no Android import), unit-tested on the JVM against the Swift cases
 * (`BlockCompositionTest`).
 */
internal object BlockComposition {
  /**
   * The sources in force: the manual block (with the apps captured when it
   * was applied), then each interval's schedule and threshold blocks, in
   * activity-name order — minus what [suspended] (the temporary unlock in
   * progress) suspends, when given. A blocklist resolving to an empty or
   * missing selection shields nothing and is omitted; an allowlist keeps
   * its applications only (a selection edited to hold categories or web
   * domains allows nothing more) and an empty one is kept: it shields every
   * app. The threshold selections fired inside one interval merge into one
   * threshold source, as iOS merges them into the activity's store; a
   * suspended threshold selection stays out of that merge, so a threshold
   * firing during the grant for another selection shields that selection
   * alone.
   */
  fun sources(manual: ManualBlock?, monitoring: MonitoringState, selections: (String) -> Selection?, suspended: SuspendedSources? = null): List<BlockSource> {
    val result = ArrayList<BlockSource>()
    if (suspended?.manual != true) manual?.source?.takeIf { it.shieldsSomething }?.let(result::add)
    val blocks = monitoring.activeBlocks { selections(it) != null }
    for ((name, ofActivity) in blocks.groupBy { it.activityName }.toSortedMap()) {
      for (block in ofActivity.filter { it.eventName == null }) {
        if (suspended?.suspendsSchedule(name, block.startedAtMs) == true) continue
        val selected = selections(block.selectionId)?.applications ?: continue
        val mode = BlockMode.stored(block.mode)
        if (mode == BlockMode.blocklist && selected.isEmpty()) continue
        result.add(BlockSource(BlockSourceKind.Schedule(name), block.startedAtMs, selected, mode))
      }
      val thresholds = ofActivity.filter { it.eventName != null && suspended?.suspendsThreshold(name, it.startedAtMs, it.selectionId) != true }
      if (thresholds.isNotEmpty()) {
        val apps = thresholds.flatMapTo(LinkedHashSet()) { selections(it.selectionId)?.applications ?: emptySet() }
        if (apps.isNotEmpty()) result.add(BlockSource(BlockSourceKind.Threshold(name), thresholds.first().startedAtMs, apps))
      }
    }
    return result
  }

  /** `isBlocked()`: whether any source shields something right now. */
  fun shieldsSomething(sources: List<BlockSource>): Boolean = sources.any { it.shieldsSomething }

  /**
   * The identity of a composition, stable across processes: a shield
   * `unblock` is recorded against it ([ShieldLifts]) and lasts exactly as
   * long as the same sources are in force — "until a block is applied
   * again". Any source starting, ending or changing its apps (an edit a
   * `blockSelection` captured, a suspension ending) is a new key. The
   * restriction sources are left out: a restriction is not a block, so
   * toggling one does not void an unblock, and the engine (which composes
   * them) and the module (which does not) agree on the key.
   */
  fun fingerprint(sources: List<BlockSource>): String =
    sources.filter { it.kind !is BlockSourceKind.Restriction }.joinToString("|") { "${it.kind}:${it.startedAtMs}:${it.mode}:${it.applications.sorted().joinToString(",")}" }

  /**
   * `isBlocked()` with the [lifted] targets (snoozed apps, the shield's
   * unblocks) taken out: a blocklist whose every app is lifted shields
   * nothing — on iOS a store the snoozes emptied is absent and `isBlocked`
   * reads the stores; an allowlist still shields every other app.
   */
  fun shieldsSomething(sources: List<BlockSource>, lifted: Set<String>): Boolean = sources.any { source ->
    source.mode == BlockMode.allowlist || source.applications.any { it !in lifted }
  }

  /**
   * Whether a targeted unlock — `temporaryUnlock(…, { target })`, the
   * app-side twin of the shield snooze — can lift the target from
   * [sources], the ones in force with the suspension of a grant in progress
   * NOT honoured (an unlock is renewable, so a suspended block still counts
   * as a block). The twin of the Swift `TargetVerdict`.
   */
  enum class TargetVerdict {
    /** Some source shields it, and the lifted set takes it out: an app a blocklist lists or an allowlist does not allow. */
    LIFTABLE,

    /** No active source shields it — `NO_ACTIVE_BLOCK`. */
    NOT_SHIELDED,

    /**
     * A category while an allowlist is in force — `UNSUPPORTED` on both
     * platforms (iOS: `.all(except:)` cannot except a category). Categories
     * are not selectable on Android in v1, so a category handle is never
     * shielded otherwise; the verdict keeps the iOS order and code for a
     * handle imported with a category in it.
     */
    CATEGORY_UNDER_ALLOWLIST,
  }

  /** The verdict for a target of [type] (`TokenHandles` kinds) and native identity [token] (the package name for an application). */
  fun targetedUnlockVerdict(type: String, token: String, sources: List<BlockSource>): TargetVerdict = when (type) {
    TokenHandles.APPLICATION -> if (sources.any { it.covers(token) }) TargetVerdict.LIFTABLE else TargetVerdict.NOT_SHIELDED
    // An allowlist covers every bare category on iOS; nothing lists a category here.
    TokenHandles.CATEGORY -> if (containsAllowlist(sources)) TargetVerdict.CATEGORY_UNDER_ALLOWLIST else TargetVerdict.NOT_SHIELDED
    else -> TargetVerdict.NOT_SHIELDED
  }

  /**
   * Everything the BLOCKLIST sources shield right now — what a temporary
   * unlock must suspend and meter. Allowlist sources contribute
   * nothing: "every other app" cannot be named.
   */
  fun union(sources: List<BlockSource>): Set<String> =
    sources.filter { it.mode == BlockMode.blocklist }.flatMapTo(LinkedHashSet()) { it.applications }

  /** Whether any of [sources] is an allowlist — the shields a usage meter cannot cover. */
  fun containsAllowlist(sources: List<BlockSource>): Boolean = sources.any { it.mode == BlockMode.allowlist }

  /**
   * The source whose shield configuration applies to a shielded [target]:
   * a threshold that covers it, then the LATEST schedule that covers it,
   * then the manual block; null when no source covers it (the default
   * configuration applies). Ties on precedence and start time break by
   * activity name, deterministically, as on iOS.
   */
  fun owner(target: String, sources: List<BlockSource>): BlockSource? =
    sources.filter { it.covers(target) }.minWithOrNull(
      compareBy<BlockSource> { it.kind.precedence }
        .thenByDescending { it.startedAtMs }
        .thenBy { it.kind.activityName ?: "" }
    )
}

/**
 * What a temporary unlock suspended when it was granted — persisted with
 * the grant ([UnlockGrant.suspended]), so EVERY later composition (the
 * module's `isBlocked`, the engine's) leaves those sources unshielded until
 * relock or expiry: the twin of the Swift `SuspendedSources`.
 *
 * Suspension is by identity, not by name: a schedule is suspended for the
 * occurrence it was in (its `startedAtMs`), so the activity's NEXT
 * occurrence shields normally; a threshold is suspended per selection AND
 * per occurrence — stopping the activity and starting it again during the
 * grant makes a new occurrence, and a threshold that fires again in it
 * shields its selection. A source that starts during the grant is not
 * suspended: the meter only covers what was.
 */
internal data class SuspendedSources(
  val manual: Boolean = false,
  /** Activity → `startedAtMs` of the suspended occurrence. */
  val schedules: Map<String, Long> = emptyMap(),
  /** Activity → its suspended threshold selections, for one occurrence. */
  val thresholds: Map<String, Thresholds> = emptyMap(),
) {
  /** The threshold selections suspended for one activity, bound to the occurrence they fired in. */
  data class Thresholds(val startedAtMs: Long, val selectionIds: List<String>)

  val isEmpty: Boolean
    get() = !manual && schedules.isEmpty() && thresholds.isEmpty()

  fun suspendsSchedule(activityName: String, startedAtMs: Long): Boolean = schedules[activityName] == startedAtMs

  /** Whether [selectionId]'s threshold shield is suspended for the occurrence of [activityName] that started at [startedAtMs]. */
  fun suspendsThreshold(activityName: String, startedAtMs: Long, selectionId: String): Boolean {
    val entry = thresholds[activityName] ?: return false
    return entry.startedAtMs == startedAtMs && selectionId in entry.selectionIds
  }

  fun toJson(): JSONObject = JSONObject()
    .put(MANUAL, manual)
    .put(SCHEDULES, JSONObject().also { json -> schedules.forEach { (name, startedAt) -> json.put(name, startedAt) } })
    .put(
      THRESHOLDS,
      JSONObject().also { json ->
        thresholds.forEach { (name, entry) -> json.put(name, JSONObject().put(STARTED_AT, entry.startedAtMs).put(SELECTION_IDS, JSONArray(entry.selectionIds))) }
      },
    )

  companion object {
    val NONE = SuspendedSources()
    private const val MANUAL = "manual"
    private const val SCHEDULES = "schedules"
    private const val THRESHOLDS = "thresholds"
    private const val STARTED_AT = "startedAt"
    private const val SELECTION_IDS = "selectionIds"

    /**
     * Everything that may shield right now, i.e. what a grant suspends: the
     * manual block, every interval in progress that was not lifted and has a
     * schedule selection (whatever the selection resolves to: a source is
     * suspended by identity, the empty ones included, as iOS captures the
     * records), and the fired `blockOnThreshold` selections of each interval,
     * bound to its occurrence. A renewal captures again: the previous grant's
     * sources are still in the records (or gone for good), so no merge is
     * needed.
     */
    fun capturing(manual: ManualBlock?, monitoring: MonitoringState): SuspendedSources {
      val schedules = LinkedHashMap<String, Long>()
      val thresholds = LinkedHashMap<String, Thresholds>()
      for (block in monitoring.activeBlocks()) {
        if (block.eventName == null) {
          schedules[block.activityName] = block.startedAtMs
        } else {
          val entry = thresholds[block.activityName]
          thresholds[block.activityName] = Thresholds(block.startedAtMs, (entry?.selectionIds ?: emptyList()) + block.selectionId)
        }
      }
      return SuspendedSources(manual != null, schedules, thresholds.mapValues { (_, entry) -> entry.copy(selectionIds = entry.selectionIds.distinct()) })
    }

    /**
     * The suspension a document holds; null when it is not one (no
     * `manual`). A threshold entry without its occurrence (a layout that
     * suspended thresholds by name only) is dropped — fail closed, the
     * shields come back.
     */
    fun fromJson(json: JSONObject?): SuspendedSources? {
      if (json == null || !json.has(MANUAL)) return null
      val manual = json.optBoolean(MANUAL, false)
      val schedules = LinkedHashMap<String, Long>()
      json.optJSONObject(SCHEDULES)?.let { map -> map.keys().forEach { name -> JsonNumbers.long(map.opt(name))?.let { schedules[name] = it } } }
      val thresholds = LinkedHashMap<String, Thresholds>()
      json.optJSONObject(THRESHOLDS)?.let { map ->
        map.keys().forEach { name ->
          val entry = map.optJSONObject(name) ?: return@forEach
          val startedAt = JsonNumbers.long(entry.opt(STARTED_AT)) ?: return@forEach
          val ids = entry.optJSONArray(SELECTION_IDS) ?: return@forEach
          thresholds[name] = Thresholds(startedAt, (0 until ids.length()).mapNotNull { ids.opt(it) as? String })
        }
      }
      return SuspendedSources(manual, schedules, thresholds)
    }
  }
}

/**
 * The apps the shield's `unblock` action lifted: taken out of every source
 * covering them "until a block is applied again" (the contract). On iOS the
 * lift edits the stores and lasts until the next projection; here the set is
 * persisted with the [BlockComposition.fingerprint] of the sources it was
 * pressed under, and applies only while that composition is still in force
 * ([Lifts.under]): `blockSelection`, `relock`, `unblockAll` and a whole-block
 * `temporaryUnlock` clear it outright, and any change of the composed
 * sources (an interval starting or ending, a threshold firing, an unlock
 * ending) voids it by key — in whichever process reads it, so an engine
 * restarted after the change honours it no more than a live one. A snooze
 * on another app does not clear it.
 */
internal object ShieldLifts {
  const val KEY = "lifts"
  private const val SOURCES = "sources"
  private const val APPS = "apps"

  /** The lifted apps and the composition they were pressed under. */
  data class Lifts(val sourcesKey: String, val apps: Set<String>) {
    /** The lifted apps if [sources] are still the composition they were pressed under; none otherwise. */
    fun under(sources: List<BlockSource>): Set<String> = if (apps.isNotEmpty() && sourcesKey == BlockComposition.fingerprint(sources)) apps else emptySet()

    val isEmpty: Boolean
      get() = apps.isEmpty()

    /** [app] lifted under [key] too: a press under another composition replaces what was lifted before. */
    fun plus(key: String, app: String): Lifts = if (key == sourcesKey) copy(apps = apps + app) else Lifts(key, setOf(app))
  }

  val NONE = Lifts("", emptySet())

  fun read(document: JSONObject): Lifts {
    val json = document.optJSONObject(KEY) ?: return NONE
    val key = (json.opt(SOURCES) as? String) ?: return NONE
    val array = json.optJSONArray(APPS) ?: return NONE
    return Lifts(key, (0 until array.length()).mapNotNullTo(LinkedHashSet()) { (array.opt(it) as? String)?.takeIf { s -> s.isNotEmpty() } })
  }

  /** Stores [lifts] in [document]; no lifted app leaves no key. */
  fun write(document: JSONObject, lifts: Lifts) {
    if (lifts.isEmpty) document.remove(KEY) else document.put(KEY, JSONObject().put(SOURCES, lifts.sourcesKey).put(APPS, JSONArray(lifts.apps.sorted())))
  }
}
