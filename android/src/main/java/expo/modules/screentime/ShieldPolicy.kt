package expo.modules.screentime

/**
 * A block as the engine applies it: the mode and the selection's apps,
 * resolved from the store at the moment of the decision (the selection is
 * read live, as iOS re-projects the stored selection).
 */
internal data class ActiveBlock(val mode: BlockMode, val applications: Set<String>) {
  /**
   * Whether this block shields anything at all: what `isBlocked()` reports.
   * A blocklist over an emptied or deleted selection shields nothing, so it
   * does not count (iOS: `BlockComposition.sources` omits a source with
   * nothing to shield, and `isBlocked` reads the stores). An allowlist
   * always shields: "every other app", even when the selection was emptied
   * after the fact (every app, fail closed).
   */
  val shieldsSomething: Boolean
    get() = mode == BlockMode.allowlist || applications.isNotEmpty()
}

/**
 * The decision the engine takes on every foreground change, as a pure
 * function: which of the apps on screen must be shielded, given the block
 * in force, the launchable apps and the exemptions. No Android imports, so
 * the policy is unit-tested on the JVM (`ShieldPolicyTest`).
 *
 * - **blocklist:** the selection's apps.
 * - **allowlist:** every *launchable* app (`MAIN`/`LAUNCHER`, the set the
 *   picker offers) except the selection's apps. Apps without a launcher
 *   entry are never shielded by an allowlist: they are reached from an
 *   allowed app (the permission controller's dialogs, the share sheet, the
 *   document picker, the package installer) and shielding them would break
 *   the allowed app, which is the opposite of what an allowlist means.
 * - **exemptions** win over both: the apps the user must always reach
 *   ([SystemExemptions]).
 */
internal object ShieldPolicy {
  /** The subset of [foreground] to shield; empty when nothing is. */
  fun targets(foreground: Set<String>, block: ActiveBlock?, launchable: Set<String>, exempt: Set<String>): Set<String> {
    if (block == null) return emptySet()
    return foreground.filterTo(LinkedHashSet()) { shields(it, block, launchable, exempt) }
  }

  fun shields(packageName: String, block: ActiveBlock?, launchable: Set<String>, exempt: Set<String>): Boolean {
    if (block == null || packageName in exempt) return false
    return when (block.mode) {
      BlockMode.blocklist -> packageName in block.applications
      BlockMode.allowlist -> packageName in launchable && packageName !in block.applications
    }
  }

  /**
   * The apps the engine must always leave alone, from the identities the OS
   * reports (any may be null when the device has no such app). The host app
   * holds the controls that lift the block (iOS never shields it either);
   * the launcher, SystemUI and the keyboard are the way to everything else;
   * the dialer and the emergency app must stay reachable, period; Settings
   * is where the user re-grants a revoked permission and, as on iOS (spike
   * S2 measured it never shielded), where a focus tool must not hold the
   * device hostage. The Settings route to lift a block (revoking usage
   * access or the overlay) is the documented `friction` gap, recorded by
   * `getEnforcementGaps`, not something the shield hides.
   */
  fun exemptions(
    host: String,
    launchers: Collection<String>,
    systemUi: String?,
    dialer: String?,
    emergency: Collection<String>,
    inputMethod: String?,
    settings: String?,
  ): Set<String> {
    val result = LinkedHashSet<String>()
    result.add(host)
    result.addAll(launchers)
    result.addAll(emergency)
    listOfNotNull(systemUi, dialer, inputMethod, settings).forEach(result::add)
    result.remove("")
    return result
  }
}
