package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject

/**
 * How a block applies its selection: the twin of `BlockMode` in
 * `ios/BlockComposition.swift` and of the JS `BlockMode` type.
 */
internal enum class BlockMode {
  /** Shield the selection's apps (default). */
  blocklist,

  /**
   * Shield every launchable app EXCEPT the selection's apps. Apps only, on
   * both platforms (review R3): a selection with categories or web domains
   * is refused up front, and one edited into that shape afterwards is
   * applied as its applications alone (fail closed, as iOS projects it).
   */
  allowlist;

  companion object {
    /** The stored name; blocklist for an absent or unknown one (what iOS `BlockMode.stored` does). */
    fun stored(name: String?): BlockMode = entries.firstOrNull { it.name == name } ?: blocklist

    /**
     * The JS `mode` option: `null` and `"blocklist"` are the default,
     * anything else is `INVALID_CONFIG` with the iOS message
     * (`ExpoScreenTimeModule.swift`, `requestedBlockMode`).
     */
    fun requested(value: Any?): BlockMode {
      if (value == null) return blocklist
      return (value as? String)?.let { name -> entries.firstOrNull { it.name == name } }
        ?: throw ScreenTimeException(ScreenTimeException.INVALID_CONFIG, "mode must be 'blocklist' or 'allowlist'")
    }
  }
}

/**
 * The manual block (`blockSelection`): one block source, persisted with its
 * mode and the selection's apps **as they were when the block was applied**,
 * so the engine process applies exactly what the app asked for, a relaunch
 * restores it, and `isBlocked()` and the engine never disagree. This is the
 * iOS projection model: `blockSelection` projects the selection into the
 * ManagedSettings store, and an edit of the selection afterwards changes
 * nothing until the next projection (the next `blockSelection`). The twin
 * of the iOS `expo_st_blocked_selection_id` + `_mode` pair.
 */
internal data class ManualBlock(val selectionId: String, val mode: BlockMode, val applications: Set<String>) {
  /** The block as a source of the composition ([BlockComposition]): the manual kind, started at 0 as on iOS. */
  val source: BlockSource
    get() = BlockSource(BlockSourceKind.Manual, 0, applications, mode)

  fun toJson(): JSONObject = JSONObject()
    .put(SELECTION_ID, selectionId)
    .put(MODE, mode.name)
    .put(APPLICATIONS, JSONArray(applications.sorted()))

  companion object {
    private const val SELECTION_ID = "selectionId"
    private const val MODE = "mode"
    private const val APPLICATIONS = "applications"

    /** Null for a document that is not a manual block (no selection id). */
    fun fromJson(json: JSONObject?): ManualBlock? {
      val selectionId = json?.optString(SELECTION_ID)?.takeIf { it.isNotEmpty() } ?: return null
      val array = json.optJSONArray(APPLICATIONS)
      val applications = LinkedHashSet<String>()
      if (array != null) for (i in 0 until array.length()) (array.opt(i) as? String)?.takeIf { it.isNotEmpty() }?.let(applications::add)
      return ManualBlock(selectionId, BlockMode.stored(json.optString(MODE)), applications)
    }
  }
}

/**
 * The checks `blockSelection` runs before it writes anything, in the iOS
 * order (`ExpoScreenTimeModule.swift`, `blockSelection` and `blockMode(of:)`):
 *
 * 1. the selection must exist, whatever the mode (`SELECTION_NOT_FOUND`);
 * 2. the mode must be `blocklist`, `allowlist` or absent (`INVALID_CONFIG`);
 * 3. an allowlist may hold applications only, and at least one
 *    (`INVALID_CONFIG`): an empty allowlist would shield every app, far more
 *    likely a picker accident than a request.
 *
 * Pure, so the order and the codes are unit-tested on the JVM.
 */
internal object BlockValidation {
  fun manualBlock(selectionId: String, selection: Selection?, modeOption: Any?): ManualBlock {
    if (selection == null) {
      throw ScreenTimeException(ScreenTimeException.SELECTION_NOT_FOUND, "Selection '$selectionId' not found")
    }
    val mode = BlockMode.requested(modeOption)
    if (mode == BlockMode.allowlist) {
      val categories = selection.categories.size
      val webDomains = selection.webDomains.size
      if (categories > 0 || webDomains > 0) {
        throw ScreenTimeException(
          ScreenTimeException.INVALID_CONFIG,
          "An allowlist may hold applications only; selection '$selectionId' has $categories categories and $webDomains web domains"
        )
      }
      if (selection.applications.isEmpty()) {
        throw ScreenTimeException(ScreenTimeException.INVALID_CONFIG, "An allowlist needs at least one application; selection '$selectionId' is empty")
      }
    }
    return ManualBlock(selectionId, mode, selection.applications)
  }
}
