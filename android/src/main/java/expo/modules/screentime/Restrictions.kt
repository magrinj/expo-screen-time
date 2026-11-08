package expo.modules.screentime

import org.json.JSONObject
import kotlin.math.abs

/**
 * The two app-control restrictions of the contract: `denyAppRemoval` and
 * `denyAppInstallation`. On iOS each is a ManagedSettings flag the OS
 * enforces; here each is a BLOCK SOURCE of the engine
 * ([BlockSourceKind.Restriction]) over the system UIs that remove or
 * install apps ([RestrictionTargets]), which is the `friction` level of
 * `getCapabilities().appRemovalProtection` / `appInstallationBlocking`.
 */
internal enum class RestrictionKind {
  /** The uninstall dialog (the package installer's). */
  removal,

  /** The stores and the package installer's install flow. */
  installation,
}

/**
 * Which restrictions the app turned on (`restrictions` in the state
 * document): the twin of the iOS `denyAppRemoval` / `denyAppInstallation`
 * flags, persisted across engine deaths and reboots. Not a block: `unblockAll`,
 * `isBlocked()`, the temporary unlock and the snoozes leave them alone, as
 * iOS leaves its flags.
 */
internal data class Restrictions(val removal: Boolean = false, val installation: Boolean = false) {
  val any: Boolean
    get() = removal || installation

  fun has(kind: RestrictionKind): Boolean = when (kind) {
    RestrictionKind.removal -> removal
    RestrictionKind.installation -> installation
  }

  fun with(kind: RestrictionKind, enabled: Boolean): Restrictions = when (kind) {
    RestrictionKind.removal -> copy(removal = enabled)
    RestrictionKind.installation -> copy(installation = enabled)
  }

  fun toJson(): JSONObject = JSONObject().put(REMOVAL, removal).put(INSTALLATION, installation)

  companion object {
    val NONE = Restrictions()
    private const val REMOVAL = "removal"
    private const val INSTALLATION = "installation"

    /** The flags a `restrictions` object holds; none for an absent one or a key of another type. */
    fun fromJson(json: JSONObject?): Restrictions {
      if (json == null) return NONE
      return Restrictions(json.optBoolean(REMOVAL, false), json.optBoolean(INSTALLATION, false))
    }
  }
}

/**
 * Which packages each restriction shields on this device, resolved from
 * intents at block time by [RestrictionTargetResolver], never from
 * hard-coded names (as [SystemExemptions] finds the launcher, the dialer
 * and Settings): the names differ per OEM and per image (the stores
 * installed differ, and an OEM may ship its own installer; One UI 8.5
 * measured with Google's).
 *
 * | Restriction | Resolved from |
 * |---|---|
 * | removal | the system app that handles `ACTION_DELETE` / `ACTION_UNINSTALL_PACKAGE` with a `package:` URI: the system's uninstall dialog, where every removal route ends (App info, the launcher's gesture, a file manager) |
 * | installation | the system app that handles `ACTION_INSTALL_PACKAGE` (`content:` + `application/vnd.android.package-archive`): the package installer, whose confirmation every install route ends in (an APK opened from a browser, Files or a messaging app, a third-party installer's session); and every activity that handles `ACTION_VIEW` of `market://details?id=`: the stores (Play, the Galaxy Store, any other one installed) |
 *
 * Targets are PACKAGE-level: the installer hosts the uninstall dialog and
 * the install flow, so each restriction also covers the other's half of
 * it (a documented coupling; an activity-level split would rest on AOSP
 * class names that are not API). The host app is never a target (it holds
 * the off switch); the engine's exemptions ([ShieldPolicy.exemptions]) are
 * applied by the policy at decision time, as for every source.
 */
internal data class RestrictionTargets(
  /** The package installer: the uninstall dialog and the install flow. */
  val installer: Set<String>,
  /** Every `market://` handler. */
  val stores: Set<String>,
) {
  /** The packages [kind] shields: the installer for both, the stores for installation. */
  fun of(kind: RestrictionKind): Set<String> = when (kind) {
    RestrictionKind.removal -> installer
    RestrictionKind.installation -> installer + stores
  }

  val isEmpty: Boolean
    get() = installer.isEmpty() && stores.isEmpty()

  override fun toString() = "installer=$installer stores=$stores"

  companion object {
    val NONE = RestrictionTargets(emptySet(), emptySet())

    /**
     * The pure half of the resolution: the packages the OS answered for
     * each intent, minus [host] (never shielded; a host that handles
     * `market://` links is not a store), the empty name and "android" (the
     * resolver's chooser, not a handler).
     */
    fun select(uninstallers: Collection<String?>, installers: Collection<String?>, stores: Collection<String?>, host: String): RestrictionTargets {
      fun clean(names: Collection<String?>) = names.filterNotNullTo(LinkedHashSet()).apply { remove(""); remove("android"); remove(host) }
      return RestrictionTargets(clean(uninstallers) + clean(installers), clean(stores))
    }
  }
}

/**
 * The restrictions as block sources, composed by the engine after the
 * manual block and the monitoring blocks ([BlockComposition.sources] does
 * not know them: the module process never composes them, so `isBlocked()`
 * and the temporary unlock's judge see blocks alone, as on iOS where the
 * flags have no shield).
 *
 * - One blocklist source per restriction in force, over its resolved
 *   targets ([RestrictionTargets]), started at 0 like the manual block (a
 *   restriction has no occurrence); a restriction whose targets resolve to
 *   nothing on this device (no store installed) adds no source.
 * - **The installer is held off** for [HOLD_OFF_MS] after the device admin
 *   was deactivated ([adminDisabledAtMs], written by
 *   `ScreenTimeDeviceAdminReceiver.onDisabled`, cleared by `onEnabled`): it
 *   leaves EVERY restriction source in that window, since the targets are
 *   package-level and both restrictions cover it. Settings' "Deactivate &
 *   uninstall" force-stops this app before it opens the installer's
 *   uninstall dialog (AOSP's DevicePolicyManager; measured on the Android
 *   17 emulator), so no engine is left to shield that one;
 *   the hold-off keeps the two-step path clear (the admin turned off in
 *   Settings › Device admin apps, the engine still running, then App info ›
 *   Uninstall), under `denyAppRemoval` and `denyAppInstallation` alike, and
 *   so any Settings that does not force-stop first. Once the admin is off
 *   the user can remove the host app, and with it every restriction, so the
 *   window costs little; what it does open (any app removable, an APK
 *   installable, for two minutes) is documented on the restrictions page.
 *   The stores stay shielded: they are not on that path. Not a gap (nothing
 *   failed), not reported.
 *
 * Pure (no Android import), unit-tested on the JVM (`RestrictionsTest`).
 */
internal object RestrictionSources {
  /** How long after the admin's deactivation the installer's shield stays down: long enough for the Settings flow, short enough to matter to nobody else. */
  const val HOLD_OFF_MS = 2 * 60 * 1_000L

  fun compose(restrictions: Restrictions, targets: RestrictionTargets, nowMs: Long = 0L, adminDisabledAtMs: Long? = null): List<BlockSource> {
    val heldOff = heldOff(nowMs, adminDisabledAtMs)
    val result = ArrayList<BlockSource>(2)
    for (kind in RestrictionKind.entries) {
      if (!restrictions.has(kind)) continue
      val apps = if (heldOff) targets.of(kind) - targets.installer else targets.of(kind)
      if (apps.isEmpty()) continue
      result.add(BlockSource(BlockSourceKind.Restriction(kind), startedAtMs = 0, applications = apps))
    }
    return result
  }

  /**
   * Whether the installer is held off at [nowMs]: a deactivation less than
   * [HOLD_OFF_MS] away from now, on either side. The stamp is wall-clock
   * time: measured from one side only, a clock set back after the
   * deactivation would keep the installer unshielded for as long as the
   * clock stays behind, with no gap recorded; this way a clock step
   * stretches the window by less than its own length at most.
   */
  fun heldOff(nowMs: Long, adminDisabledAtMs: Long?): Boolean = adminDisabledAtMs != null && abs(nowMs - adminDisabledAtMs) < HOLD_OFF_MS

  /** When the hold-off in force at [nowMs] ends, so the engine re-composes then; null when none is. */
  fun holdOffEndMs(restrictions: Restrictions, nowMs: Long, adminDisabledAtMs: Long?): Long? =
    if (restrictions.any && heldOff(nowMs, adminDisabledAtMs)) adminDisabledAtMs!! + HOLD_OFF_MS else null
}
