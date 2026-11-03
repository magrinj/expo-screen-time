package expo.modules.screentime

import android.content.Context
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID

/**
 * The module-process half of the temporary unlock and the shield
 * interactions: the twin of `AsyncFunction("temporaryUnlock")`,
 * `isTemporarilyUnlocked`, `getRemainingUnlockSeconds`, `relock` and
 * `drainShieldInteractions` in `ios/ExpoScreenTimeModule.swift`, with the
 * pure cores of #54 ([UnlockGrant], [ShieldSnooze], [ShieldInteractionQueue])
 * and the composition ([BlockComposition], [SuspendedSources]).
 *
 * Every grant is one read-decide-write under the state store's exclusive
 * lock — the judge, the capture of the suspended sources and the record
 * land in one document write, so the engine process never reads a grant
 * without its suspension — then the engine is told to look again
 * ([EngineController.sync]). iOS needs two critical sections (the meter is
 * armed by a system call the lock must not span); here the engine IS the
 * meter, so one is enough and the "block changed under the grant" retry has
 * no counterpart.
 */
internal class UnlockController(
  private val context: Context,
  private val store: StateStore,
  private val selections: SelectionStore,
  /** Called after this process recorded monitor events (a boundary the grant settled), so the live listener sees them. */
  private val onEventsRecorded: () -> Unit = {},
) {
  /**
   * `temporaryUnlock(budgetSeconds, { target? })`. The judge runs in the
   * iOS order ([TemporaryUnlockRequest.judge] over the sources in force
   * with a grant's suspension NOT honoured, so a renewal never rejects
   * `NO_ACTIVE_BLOCK` while something is restorable): a non-number budget
   * → `INVALID_CONFIG`; with a `target`, a handle that does not resolve on
   * this install → `INVALID_CONFIG`, a target nothing shields →
   * `NO_ACTIVE_BLOCK`, a category under an allowlist → `UNSUPPORTED`;
   * without one, an allowlist in force → `UNSUPPORTED`, nothing blocked →
   * `NO_ACTIVE_BLOCK`. Same messages as iOS.
   *
   * Whole block: ONE grant at a time; a renewal replaces it (new id, budget
   * and consumption restart) and captures the suspension afresh; the
   * shield's unblocks are dropped (a block is being re-applied, as iOS
   * re-projects every store). The snoozes run on. Targeted: a
   * [ShieldSnooze] granted from the app, replacing a snooze over the same
   * target; the whole-block grant is untouched.
   */
  fun temporaryUnlock(budgetSeconds: Any?, options: Map<String, Any?>?): Map<String, Any?> {
    val now = System.currentTimeMillis()
    val zone = TimeZone.getDefault()
    var recorded = false
    val response = store.update { document ->
      val all = BlockController.activeSources(store, selections, honouringSuspension = false)
      when (val verdict = TemporaryUnlockRequest.judge(budgetSeconds, options?.get("target"), BlockComposition.union(all).isNotEmpty(), BlockComposition.containsAllowlist(all))) {
        is TemporaryUnlockRequest.Verdict.Refused -> throw ScreenTimeException(verdict.refusal.code, verdict.refusal.message)
        is TemporaryUnlockRequest.Verdict.WholeBlock -> {
          // The suspension is keyed on the interval markers, so the
          // boundaries this process sees at now are committed first (as
          // `startMonitoring` commits its own, review R7): an occurrence the
          // engine has not recorded yet — it was dead, or has not stepped —
          // gets the marker the grant suspends, instead of one the engine
          // would stamp later with its own clock.
          val stored = MonitoringDocument.read(document)
          val settled = stored.advance(now, zone, emptyList(), minOf(stored.foreground.cursorMs ?: now, now), selections::get)
          val monitoring = if (settled.durable) settled.state.copy(revision = stored.revision + 1).also { MonitoringDocument.write(document, it) } else stored
          recorded = settled.recorded.isNotEmpty()
          val suspended = SuspendedSources.capturing(store.manualBlock, monitoring)
          val grant = UnlockGrant.grant(verdict.budgetSeconds, now, suspended = suspended)
          UnlockGrant.write(document, grant)
          ShieldLifts.write(document, ShieldLifts.NONE)
          grant.toResponse()
        }
        is TemporaryUnlockRequest.Verdict.Targeted -> {
          val handle = verdict.target.getValue("handle")
          val type = verdict.target.getValue("type")
          val (resolvedType, token) = selections.resolve(handle, null)?.takeIf { it.first == type }
            ?: throw ScreenTimeException(
              ScreenTimeException.INVALID_CONFIG,
              "target must be a TokenHandle ({ handle, type }) of this device: pass one returned by getTokens or a shield interaction, unchanged",
            )
          when (BlockComposition.targetedUnlockVerdict(resolvedType, token, all)) {
            BlockComposition.TargetVerdict.NOT_SHIELDED -> throw ScreenTimeException(
              TemporaryUnlockRequest.NO_ACTIVE_BLOCK,
              "The target is not shielded by any active block (an app shielded only through its category is targeted by that category's handle)",
            )
            BlockComposition.TargetVerdict.CATEGORY_UNDER_ALLOWLIST -> throw ScreenTimeException(
              TemporaryUnlockRequest.UNSUPPORTED,
              "A category cannot be unlocked from an allowlist block (it shields every app); target the app instead",
            )
            BlockComposition.TargetVerdict.LIFTABLE -> {}
          }
          val snooze = ShieldSnooze.grant(UUID.randomUUID().toString(), resolvedType, token, verdict.budgetSeconds, now)
            ?: throw ScreenTimeException(TemporaryUnlockRequest.UNSUPPORTED, "A $resolvedType cannot be metered on Android; target an application")
          ShieldSnoozes.write(document, ShieldSnoozes.granting(snooze, ShieldSnoozes.current(ShieldSnoozes.read(document), now, zone)).snoozes)
          mapOf(
            "budgetSeconds" to snooze.budgetSeconds,
            "grantedAt" to snooze.grantedAt.toDouble(),
            "meteringSeconds" to UsageMeter.METERING_SECONDS,
            "target" to verdict.target,
          )
        }
      }
    }
    EngineController.sync(context, if (response["target"] == null) "unlock" else "unlock:target")
    if (recorded) onEventsRecorded()
    return response
  }

  /** `isTemporarilyUnlocked`: a whole-block grant with budget left today. Snoozes are not reported. */
  fun isTemporarilyUnlocked(): Boolean = remainingUnlockSeconds() > 0

  /**
   * `getRemainingUnlockSeconds`: budget minus consumption as the engine
   * persisted it (whole seconds of use, so it moves one second at a time and
   * only while an unlocked app is in use). Reading 0 while a grant is
   * stored means it was spent or the day rolled over: the grant is cleared
   * from here too (the iOS `relockIfExpired` backstop), so the shields come
   * back even if the engine is not running.
   */
  fun getRemainingUnlockSeconds(): Int {
    val remaining = remainingUnlockSeconds()
    if (remaining <= 0) relockIfExpired()
    return remaining
  }

  /**
   * `relock`: ends the whole-block unlock, every targeted grant and every
   * snooze, and the shield's unblocks — "put the shields back" means all of
   * them — and restores exactly the sources still active (the engine
   * recomposes them from the document).
   */
  fun relock() {
    store.update { document -> clearUnlockState(document) }
    EngineController.sync(context, "relock")
  }

  /** `drainShieldInteractions`: read-and-clear the queue the shield Activity writes; the write is skipped when there is nothing to drain. */
  fun drainShieldInteractions(): List<Map<String, Any?>> {
    if (store.read { ShieldInteractionQueue.read(it).entries.isEmpty() }) return emptyList()
    return store.update { ShieldInteractionQueue.drain(it) }
  }

  private fun remainingUnlockSeconds(): Int =
    store.read { UnlockGrant.read(it) }?.remainingSeconds(System.currentTimeMillis(), TimeZone.getDefault()) ?: 0

  /** Relock only if the grant is STILL spent when checked under the lock, so a renewal that landed after the caller read 0 is not cancelled. */
  private fun relockIfExpired() {
    val expired = store.update { document ->
      val grant = UnlockGrant.read(document) ?: return@update false
      if (grant.isActive(System.currentTimeMillis(), TimeZone.getDefault())) return@update false
      UnlockGrant.write(document, null)
      true
    }
    if (expired) EngineController.sync(context, "unlock:expired")
  }

  companion object {
    /**
     * What `blockSelection`, `unblockAll` and `relock` do to the unlock
     * state, inside the caller's `update`: the whole-block grant, the
     * snoozes (targeted grants included) and the shield's unblocks go
     * (`clearUnlockState` + `takeSnoozes` on iOS).
     */
    fun clearUnlockState(document: JSONObject) {
      UnlockGrant.write(document, null)
      ShieldSnoozes.write(document, emptyList())
      ShieldLifts.write(document, ShieldLifts.NONE)
    }

    /** The targets lifted right now by the snoozes in force and the shield's unblocks (those pressed under [sources]), from [document]. */
    fun lifted(document: JSONObject, sources: List<BlockSource>, nowMs: Long, zone: TimeZone): Set<String> =
      ShieldSnoozes.current(ShieldSnoozes.read(document), nowMs, zone).filter { ShieldSnooze.isMeterable(it.tokenKind) }.mapTo(LinkedHashSet()) { it.token } +
        ShieldLifts.read(document).under(sources)
  }
}
