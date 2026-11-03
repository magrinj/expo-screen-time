package expo.modules.screentime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * The module-process half of blocking: the twin of the `// MARK: - Blocking`,
 * `// MARK: - Shield Configuration` and `// MARK: - Shield Actions` sections
 * of `ios/ExpoScreenTimeModule.swift` plus `ScreenTimeManager.isBlocked`. The
 * state lives in the [StateStore] document; the engine process
 * ([ScreenTimeEngineService]) reads it and enforces it, and is told to look
 * again through [EngineController] (a start intent, the signal that reaches
 * a process the store cannot wake).
 */
internal class BlockController(private val context: Context, private val store: StateStore, private val selections: SelectionStore) {
  /**
   * `blockSelection(selectionId, { mode })`: validated in the iOS order
   * ([BlockValidation]), then the manual block is replaced (one source; a
   * second call replaces the first, it does not stack) and the engine is
   * wanted. Block and desire land in one `update`, so the engine never
   * starts for a block that is not there yet.
   */
  fun blockSelection(selectionId: String, options: Map<String, Any?>?) {
    val block = store.update { document ->
      val validated = BlockValidation.manualBlock(selectionId, selections.get(selectionId), options?.get("mode"))
      // An explicit block supersedes any temporary unlock in progress, every
      // snooze and the shield's unblocks, as on iOS.
      UnlockController.clearUnlockState(document)
      store.manualBlock = validated
      store.syncEngineDesired(document)
      validated
    }
    EngineController.sync(context, "block:${block.mode.name}")
  }

  /**
   * `unblockAll`: lifts every active block — the manual block, and the
   * schedule and threshold shields of the intervals in progress — as iOS
   * does (`setBlockedSelectionId(nil)` + `liftActiveIntervals`). Monitoring
   * keeps running: an interval in progress stays in progress (its end is
   * still recorded) and a schedule re-applies its block at its next
   * interval start, so the engine stays wanted while anything is
   * monitored.
   */
  fun unblockAll() {
    store.update { document ->
      UnlockController.clearUnlockState(document)
      store.manualBlock = null
      MonitoringDocument.write(document, MonitoringDocument.read(document).lift())
      store.syncEngineDesired(document)
    }
    EngineController.sync(context, "released")
  }

  /**
   * `isBlocked`: whether any source shields something right now
   * ([BlockComposition.shieldsSomething] over the composed sources: the
   * manual block with the apps captured at block time, the schedules in
   * progress and the fired thresholds). The schedules are evaluated at
   * `now` here, not read from the engine's last pass: a boundary the
   * engine has not recorded yet (its alarm is inexact) still answers as
   * the engine will. Synchronous: one shared-lock file read, nothing
   * written. This is the *intended* state (review R2): whether the engine
   * is alive to enforce it is a gap for `getEnforcementGaps`, not a `false`
   * here. A whole-block unlock in progress suspends its sources and a
   * snooze or a shield unblock lifts its app, so `isBlocked()` is false
   * during a grant that suspended everything, as iOS reads its emptied
   * stores (the conformance suite's C08 asserts it).
   */
  fun isBlocked(): Boolean = store.read { document ->
    val now = System.currentTimeMillis()
    val zone = TimeZone.getDefault()
    val sources = activeSources(store, selections)
    BlockComposition.shieldsSomething(sources, UnlockController.lifted(document, sources, now, zone))
  }

  // MARK: - Shield configuration

  fun setShieldConfig(config: Map<String, Any?>) = store.setDefaultShieldConfig(toJson(config))

  fun setShieldConfigForActivity(activityName: String, config: Map<String, Any?>) = store.setShieldConfig(activityName, toJson(config))

  fun clearShieldConfig() = store.clearShieldConfigs()

  // MARK: - Shield actions

  fun setShieldActions(config: Map<String, Any?>) = store.setDefaultShieldActions(toJson(config))

  fun setShieldActionsForActivity(activityName: String, config: Map<String, Any?>) = store.setShieldActions(activityName, toJson(config))

  fun clearShieldActions() = store.clearShieldActions()

  companion object {
    /**
     * The sources in force now, from the document as it is: the schedules
     * evaluated at `now` in memory (`schedulesAt`: boundaries as the engine
     * will record them, thresholds as the engine persisted them; nothing
     * written) and the manual block — minus what the temporary unlock in
     * progress suspended, unless [honouringSuspension] is false (what a
     * relock or a renewal restores: the iOS `activeBlockSources`).
     */
    fun activeSources(store: StateStore, selections: SelectionStore, honouringSuspension: Boolean = true): List<BlockSource> = store.read { document ->
      val now = System.currentTimeMillis()
      val zone = TimeZone.getDefault()
      val monitoring = MonitoringDocument.read(document).schedulesAt(now, zone, selections::get)
      val suspended = if (honouringSuspension) UnlockGrant.read(document)?.takeIf { it.isActive(now, zone) }?.suspended else null
      BlockComposition.sources(store.manualBlock, monitoring, selections::get, suspended)
    }

    /** A JS record (nested maps and lists of primitives) as the JSON the document stores. */
    fun toJson(value: Map<String, Any?>): JSONObject {
      val json = JSONObject()
      for ((key, entry) in value) json.put(key, toJsonValue(entry))
      return json
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
      null -> JSONObject.NULL
      is Map<*, *> -> toJson(value.entries.associate { (k, v) -> k.toString() to v })
      is List<*> -> JSONArray(value.map(::toJsonValue))
      else -> value
    }
  }
}
