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
 *   everything else. Every BLOCK source, that is: a restriction source
 *   ([BlockSourceKind.Restriction]) is never lifted — on iOS the
 *   restriction has no shield and no lift, and a snooze pressed on a
 *   store's manual shield must not open the store a parent closed.
 */
internal object ShieldPolicy {
  /** The subset of [foreground] to shield; empty when nothing is. */
  fun targets(foreground: Set<String>, sources: List<BlockSource>, launchable: Set<String>, exempt: Set<String>, lifted: Set<String> = emptySet()): Set<String> {
    if (sources.isEmpty()) return emptySet()
    return foreground.filterTo(LinkedHashSet()) { shields(it, sources, launchable, exempt, lifted) }
  }

  fun shields(packageName: String, sources: List<BlockSource>, launchable: Set<String>, exempt: Set<String>, lifted: Set<String> = emptySet()): Boolean {
    if (packageName in exempt) return false
    return sources.any { (it.kind is BlockSourceKind.Restriction || packageName !in lifted) && shields(packageName, it, launchable) }
  }

  private fun shields(packageName: String, source: BlockSource, launchable: Set<String>): Boolean = when (source.mode) {
    BlockMode.blocklist -> source.covers(packageName)
    BlockMode.allowlist -> packageName in launchable && source.covers(packageName)
  }

  /**
   * Every package the sources would shield, as a set: a blocklist's apps,
   * an allowlist's launchable complement, minus the exemptions (the lifted
   * set is not taken out: it changes with every snooze, and [shields]
   * applies it at decision time). What the accessibility detector is
   * scoped to (`AccessibilityServiceInfo.packageNames`): the system then
   * delivers events for the restricted apps only, which is less work and
   * the honest Play story. Empty when nothing is shielded; the caller must
   * never hand an empty list to the system, which means "every package".
   */
  fun covered(sources: List<BlockSource>, launchable: Set<String>, exempt: Set<String>): Set<String> {
    val result = LinkedHashSet<String>()
    for (source in sources) {
      when (source.mode) {
        BlockMode.blocklist -> result.addAll(source.applications)
        BlockMode.allowlist -> launchable.filterTo(result) { source.covers(it) }
      }
    }
    result.removeAll(exempt)
    return result
  }

  /**
   * Whether a shield launch for [target] is a second sighting of an arrival
   * the last launch already answered: in a build that declares the
   * accessibility detector, the detector and the poll both report a
   * main-profile app, a few ms apart, and the second start would only reach
   * the `singleTask` shield's `onNewIntent`. True only when:
   * - the build declares the detector ([detectorDeclared]), so a default
   *   build launches exactly as before it existed;
   * - the arrival has a time ([arrivedAt], wall ms: the UsageStats event or
   *   the window-state event), so the standing check and a replay always
   *   launch (a schedule's own configuration is never dropped);
   * - it is the same [target], within [windowMs] of the last launch;
   * - and that launch still answers it: the arrival happened at or before
   *   the launch ([lastLaunchAt], wall ms), or the shield is still on its
   *   way ([shownSinceLaunch] false: the window event of the app's own
   *   resume often lands after the poll's launch), or it is up over the
   *   target ([showingTarget]).
   * An arrival after a shield that was shown and has gone (a trampoline's
   * activity over it, the user's close and an instant reopen) always
   * relaunches.
   */
  fun isSameArrival(
    target: String,
    arrivedAt: Long?,
    detectorDeclared: Boolean,
    lastTarget: String?,
    lastLaunchAt: Long,
    sinceLastLaunchMs: Long,
    windowMs: Long,
    showingTarget: String?,
    shownSinceLaunch: Boolean,
  ): Boolean =
    detectorDeclared && arrivedAt != null && target == lastTarget && sinceLastLaunchMs < windowMs &&
      (arrivedAt <= lastLaunchAt || !shownSinceLaunch || showingTarget == target)

  /**
   * The source that owns the shield drawn over [packageName] — its
   * configuration applies ([BlockComposition.owner]); null for the default
   * configuration. The launchable rule above is the engine's own refinement
   * of the allowlist: a target it shields is covered by the composition's
   * rule too, so the owner is resolved with the iOS rule alone. A block
   * source that [lifted] takes the target out of does not own it: when a
   * restriction is what still shields it (a store the user unblocked under
   * `denyAppInstallation`), the restriction's shield is the one drawn, not
   * the lifted block's with its now idle buttons.
   */
  fun owner(packageName: String, sources: List<BlockSource>, lifted: Set<String> = emptySet()): BlockSource? =
    BlockComposition.owner(packageName, if (packageName in lifted) sources.filter { it.kind is BlockSourceKind.Restriction } else sources)

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
