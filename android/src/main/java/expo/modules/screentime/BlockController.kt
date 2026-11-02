package expo.modules.screentime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The module-process half of blocking: the twin of the `// MARK: - Blocking`
 * and `// MARK: - Shield Configuration` sections of
 * `ios/ExpoScreenTimeModule.swift` plus `ScreenTimeManager.isBlocked`. The
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
    val block = store.update { _ ->
      val validated = BlockValidation.manualBlock(selectionId, selections.get(selectionId), options?.get("mode"))
      store.manualBlock = validated
      store.engineDesired = true
      validated
    }
    EngineController.sync(context, "block:${block.mode.name}")
  }

  /**
   * `unblockAll`: lifts the manual block (the only source until schedules
   * exist) and releases the engine, which stops polling and dismisses a
   * shield on screen. Monitoring is untouched, as on iOS.
   */
  fun unblockAll() {
    store.update { _ ->
      store.manualBlock = null
      store.engineDesired = false
    }
    EngineController.sync(context, "released")
  }

  /**
   * `isBlocked`: whether the manual block shields something right now
   * ([ActiveBlock.shieldsSomething] over the apps captured at block time,
   * what the engine enforces). Synchronous: one shared-lock file read. This
   * is the *intended* state (review R2): whether the engine is alive to
   * enforce it is a gap for `getEnforcementGaps`, not a `false` here.
   */
  fun isBlocked(): Boolean = activeBlock(store)?.shieldsSomething == true

  // MARK: - Shield configuration

  fun setShieldConfig(config: Map<String, Any?>) = store.setDefaultShieldConfig(toJson(config))

  fun setShieldConfigForActivity(activityName: String, config: Map<String, Any?>) = store.setShieldConfig(activityName, toJson(config))

  fun clearShieldConfig() = store.clearShieldConfigs()

  companion object {
    /** The manual block as the engine applies it, or null when none is set. */
    fun activeBlock(store: StateStore): ActiveBlock? = store.manualBlock?.active

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
