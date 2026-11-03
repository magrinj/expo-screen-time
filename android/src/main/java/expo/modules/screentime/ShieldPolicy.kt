package expo.modules.screentime

/**
 * The decision the engine takes on every foreground change, as a pure
 * function: which of the apps on screen must be shielded, given the block
 * sources in force ([BlockComposition.sources]), the launchable apps and
 * the exemptions. No Android imports, so the policy is unit-tested on the
 * JVM (`ShieldPolicyTest`).
 *
 * - **union:** a package is shielded when ANY source covers it
 *   ([BlockSource.covers]): a blocklist listing it, or an allowlist not
 *   allowing it.
 * - **blocklist:** the source's apps.
 * - **allowlist:** every *launchable* app (`MAIN`/`LAUNCHER`, the set the
 *   picker offers) except the source's apps. Apps without a launcher
 *   entry are never shielded by an allowlist: they are reached from an
 *   allowed app (the permission controller's dialogs, the share sheet, the
 *   document picker, the package installer) and shielding them would break
 *   the allowed app, which is the opposite of what an allowlist means.
 * - **exemptions** win over everything: the apps the user must always
 *   reach ([SystemExemptions]).
 * - **lifted** targets — the apps a shield snooze or the shield's `unblock`
 *   lifted ([ShieldSnooze], [ShieldLifts]) — are taken out of every source
 *   covering them, an allowlist's included (the iOS projection excepts a
 *   snoozed app from `.all(except:)`); the sources stay in force for
 *   everything else.
 */
internal object ShieldPolicy {
  /** The subset of [foreground] to shield; empty when nothing is. */
  fun targets(foreground: Set<String>, sources: List<BlockSource>, launchable: Set<String>, exempt: Set<String>, lifted: Set<String> = emptySet()): Set<String> {
    if (sources.isEmpty()) return emptySet()
    return foreground.filterTo(LinkedHashSet()) { shields(it, sources, launchable, exempt, lifted) }
  }

  fun shields(packageName: String, sources: List<BlockSource>, launchable: Set<String>, exempt: Set<String>, lifted: Set<String> = emptySet()): Boolean {
    if (packageName in exempt || packageName in lifted) return false
    return sources.any { shields(packageName, it, launchable) }
  }

  private fun shields(packageName: String, source: BlockSource, launchable: Set<String>): Boolean = when (source.mode) {
    BlockMode.blocklist -> source.covers(packageName)
    BlockMode.allowlist -> packageName in launchable && source.covers(packageName)
  }

  /**
   * The source that owns the shield drawn over [packageName] — its
   * configuration applies ([BlockComposition.owner]); null for the default
   * configuration. The launchable rule above is the engine's own refinement
   * of the allowlist: a target it shields is covered by the composition's
   * rule too, so the owner is resolved with the iOS rule alone.
   */
  fun owner(packageName: String, sources: List<BlockSource>): BlockSource? = BlockComposition.owner(packageName, sources)

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
