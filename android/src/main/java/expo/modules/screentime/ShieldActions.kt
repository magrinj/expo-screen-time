package expo.modules.screentime

import org.json.JSONObject

/**
 * What a shield button does: the configured `ShieldAction` of the contract,
 * parsed from the stored `ShieldActionConfig` exactly as the iOS
 * `ShieldActionExtension.configuredAction` reads it — `primaryAction` /
 * `secondaryAction`, each an action name or `{ type: "snooze", duration }`
 * / `{ type: "openApp" }`, with the legacy native keys
 * (`primaryButtonAction`, `primaryButtonSnoozeDuration`) still readable. A
 * snooze without a positive `duration` is misconfigured and closes
 * (nothing is lifted for an unknown budget); an unknown action name is no
 * configuration at all (null), which the shield treats as the default,
 * [Close].
 *
 * Pure, so the mapping is unit-tested on the JVM (`ShieldActionsTest`).
 */
internal sealed class ShieldAction {
  /** Dismiss to the Home screen; the block stays. The default. */
  object Close : ShieldAction() {
    override fun toString() = "close"
  }

  /** Lift the shield on the pressed target in every source shielding it, until a block is applied again ([ShieldLifts]). */
  object Unblock : ShieldAction() {
    override fun toString() = "unblock"
  }

  /** Keep the shield up; the press is still recorded. */
  object Defer : ShieldAction() {
    override fun toString() = "defer"
  }

  /** Lift the pressed target for [seconds] of its use ([ShieldSnooze]). */
  data class Snooze(val seconds: Int) : ShieldAction() {
    override fun toString() = "snooze($seconds)"
  }

  /** Bring the host app forward: a direct launch on Android (`shieldOpenApp: "direct"`). */
  object OpenApp : ShieldAction() {
    override fun toString() = "openApp"
  }

  companion object {
    const val PRIMARY = "primary"
    const val SECONDARY = "secondary"

    /**
     * The action a button takes on a RESTRICTION target (the uninstall
     * dialog, a store, the installer: [BlockSourceKind.Restriction]):
     * `openApp` is honoured (the host app is where the restriction is turned
     * off), everything else closes. `unblock` and `snooze` would lift a
     * restriction from a button a child can reach, which iOS has no
     * counterpart for (the flags have no shield), and `defer` would keep a
     * dialog's shield up for nothing. The press is still recorded as
     * configured.
     */
    fun forRestriction(configured: ShieldAction?): ShieldAction = if (configured == OpenApp) OpenApp else Close

    /**
     * Whether a button configured for [configured] still does what its label
     * says on a restriction target: `close` (also what a button with no
     * action does) and `openApp` do; `unblock`, `snooze` and `defer` only
     * close there ([forRestriction]), so their label would lie.
     */
    fun keepsMeaningOnRestriction(configured: ShieldAction?): Boolean {
      val action = configured ?: Close
      return forRestriction(action) == action
    }

    /** The action configured for [button] (`primary` or `secondary`) in [config], or null when none is. */
    fun configured(config: JSONObject?, button: String): ShieldAction? {
      if (config == null) return null
      var type: String? = null
      var duration: Int? = null
      when (val action = config.opt("${button}Action")) {
        is String -> type = action
        is JSONObject -> {
          type = action.opt("type") as? String
          duration = UsageMeter.seconds(action.opt("duration"))
        }
        else -> {
          // Legacy native shape, kept readable.
          type = config.opt("${button}ButtonAction") as? String
          duration = UsageMeter.seconds(config.opt("${button}ButtonSnoozeDuration"))
        }
      }
      return when (type) {
        "close" -> Close
        "unblock" -> Unblock
        "defer" -> Defer
        "snooze" -> if (duration != null && duration > 0) Snooze(duration) else Close
        "openApp" -> OpenApp
        else -> null
      }
    }
  }
}
