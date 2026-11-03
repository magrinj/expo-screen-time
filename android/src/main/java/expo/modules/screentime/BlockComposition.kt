package expo.modules.screentime

/**
 * Which source a [BlockSource] is: the twin of `BlockSourceKind` in
 * `ios/BlockComposition.swift`. Lower [precedence] wins the shield
 * configuration of a shared target: threshold > schedule > manual (the
 * default config applies when nothing owns a target).
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

  /** The activity behind a schedule or threshold; null for the manual block — the owner `ShieldActivity` resolves its config from. */
  val activityName: String?
    get() = when (this) {
      is Manual -> null
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
 * two Android block models: step 3's manual block ([ManualBlock], apps
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
 * Pure (no Android import), unit-tested on the JVM against the Swift cases
 * (`BlockCompositionTest`).
 */
internal object BlockComposition {
  /**
   * The sources in force: the manual block (with the apps captured when it
   * was applied), then each interval's schedule and threshold blocks, in
   * activity-name order. A blocklist resolving to an empty or missing
   * selection shields nothing and is omitted; an allowlist keeps its
   * applications only (a selection edited to hold categories or web domains
   * allows nothing more) and an empty one is kept: it shields every app.
   * The threshold selections fired inside one interval merge into one
   * threshold source, as iOS merges them into the activity's store.
   */
  fun sources(manual: ManualBlock?, monitoring: MonitoringState, selections: (String) -> Selection?): List<BlockSource> {
    val result = ArrayList<BlockSource>()
    manual?.source?.takeIf { it.shieldsSomething }?.let(result::add)
    val blocks = monitoring.activeBlocks { selections(it) != null }
    for ((name, ofActivity) in blocks.groupBy { it.activityName }.toSortedMap()) {
      for (block in ofActivity.filter { it.eventName == null }) {
        val selected = selections(block.selectionId)?.applications ?: continue
        val mode = BlockMode.stored(block.mode)
        if (mode == BlockMode.blocklist && selected.isEmpty()) continue
        result.add(BlockSource(BlockSourceKind.Schedule(name), block.startedAtMs, selected, mode))
      }
      val thresholds = ofActivity.filter { it.eventName != null }
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
