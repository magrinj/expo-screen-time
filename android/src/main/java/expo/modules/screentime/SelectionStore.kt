package expo.modules.screentime

import org.json.JSONObject

/**
 * The Android twin of `ios/SelectionStore.swift`: selections addressed by
 * `selectionId`, persisted in the [StateStore] document (the App Group
 * counterpart), so the engine process reads the same selections the React
 * Native process wrote.
 *
 * Document keys owned here:
 * - `selections.<id>`: the [Selection] JSON;
 * - `tokens.<handle>`: `{ type, id }`, the only way from a handle back to a
 *   package name. Written for every member of every saved selection, never
 *   pruned (bounded by the distinct apps ever selected), so a handle resolves
 *   after its selection changed or went away, as a self-describing iOS
 *   handle does. [TokenLabel][ExpoScreenTimeTokenLabelView] and, later, the
 *   shield interactions' `target` depend on it.
 *
 * Every read-decide-write (set operations, `removeToken`) runs in one
 * `update`, under the exclusive cross-process lock. Semantics follow the
 * Swift file: a missing source is skipped by `merge`, makes `keepCommon` save
 * nothing when it is the first and an empty selection otherwise, and makes
 * `subtract` save nothing.
 */
internal class SelectionStore(private val store: StateStore) {
  // Resolved up front, never from inside an `update`: a secret first drawn
  // by a nested update would be cached in this process before the outer
  // save landed, and lost if that save failed. One file read per process
  // (StateStore caches it).
  private val secret: ByteArray = store.installSecret()

  // MARK: - CRUD

  fun save(selectionId: String, selection: Selection): Unit = store.update { document ->
    selections(document).put(selectionId, selection.toJson())
    remember(document, selection)
  }

  fun get(selectionId: String): Selection? = store.read { document ->
    document.optJSONObject(SELECTIONS)?.optJSONObject(selectionId)?.let(Selection::fromJson)
  }

  // Explicitly Unit: an `update` returning the removed JSONObject would
  // reach the bridge, which cannot convert it ("Unknown type: JSONObject").
  fun delete(selectionId: String): Unit = store.update { document ->
    document.optJSONObject(SELECTIONS)?.remove(selectionId)
    Unit
  }

  fun list(): List<String> = store.read { document ->
    document.optJSONObject(SELECTIONS)?.keys()?.asSequence()?.sorted()?.toList() ?: emptyList()
  }

  fun has(selectionId: String): Boolean = store.read { document ->
    document.optJSONObject(SELECTIONS)?.optJSONObject(selectionId) != null
  }

  // MARK: - Metadata and handles

  fun metadata(selectionId: String): Map<String, Int>? = get(selectionId)?.metadata()

  fun tokens(selectionId: String): List<Map<String, String>>? = get(selectionId)?.let { TokenHandles.of(secret, it) }

  fun tokens(selection: Selection): List<Map<String, String>> = TokenHandles.of(secret, selection)

  /**
   * The picker's tap: adds or removes one app inside one `update` (the
   * stored selection, not the picker's snapshot of it, is the base, so a
   * stale picker cannot drop apps another writer added) and returns the
   * result. A missing selection starts empty.
   */
  fun toggleApplication(selectionId: String, packageName: String, selected: Boolean): Selection = store.update { document ->
    val current = selection(document, selectionId) ?: Selection.EMPTY
    val result = current.copy(applications = if (selected) current.applications + packageName else current.applications - packageName)
    selections(document).put(selectionId, result.toJson())
    remember(document, result)
    result
  }

  /** The stored selection, created empty when missing — what mounting the iOS picker does (`setSelectionId` saves through the model). */
  fun load(selectionId: String): Selection = store.update { document ->
    selection(document, selectionId) ?: Selection.EMPTY.also { selections(document).put(selectionId, it.toJson()) }
  }

  fun removeToken(selectionId: String, handle: String): Unit = store.update { document ->
    val selection = document.optJSONObject(SELECTIONS)?.optJSONObject(selectionId)?.let(Selection::fromJson) ?: return@update
    val (type, id) = TokenHandles.resolve(secret, selection, handle) ?: return@update
    val pruned = when (type) {
      TokenHandles.APPLICATION -> selection.copy(applications = selection.applications - id)
      TokenHandles.CATEGORY -> selection.copy(categories = selection.categories - id)
      else -> selection.copy(webDomains = selection.webDomains - id)
    }
    selections(document).put(selectionId, pruned.toJson())
  }

  /**
   * The member [handle] names, as (type, id): inside [selectionId] when
   * given (a handle outside it resolves to nothing, as on iOS), else through
   * the handle map. A process-wide cache answers repeated lookups (every
   * label is one) without a file read; entries are never invalid, the map
   * being append-only.
   */
  fun resolve(handle: String, selectionId: String?): Pair<String, String>? {
    if (selectionId != null) return get(selectionId)?.let { TokenHandles.resolve(secret, it, handle) }
    synchronized(resolved) { resolved[handle] }?.let { return it }
    val target = store.read { document ->
      document.optJSONObject(TOKENS)?.optJSONObject(handle)
        ?.let { it.optString("type") to it.optString("id") }
        ?.takeIf { (type, id) -> type.isNotEmpty() && id.isNotEmpty() }
    } ?: return null
    synchronized(resolved) { resolved[handle] = target }
    return target
  }

  // MARK: - Set operations

  fun merge(targetId: String, sourceIds: List<String>): Unit = store.update { document ->
    val result = sourceIds.fold(Selection.EMPTY) { acc, id -> selection(document, id)?.let(acc::union) ?: acc }
    selections(document).put(targetId, result.toJson())
    remember(document, result)
  }

  fun keepCommon(targetId: String, sourceIds: List<String>): Unit = store.update { document ->
    val firstId = sourceIds.firstOrNull() ?: return@update
    var result = selection(document, firstId) ?: return@update
    for (id in sourceIds.drop(1)) {
      val source = selection(document, id)
      if (source == null) {
        result = Selection.EMPTY
        break
      }
      result = result.intersect(source)
    }
    selections(document).put(targetId, result.toJson())
    remember(document, result)
  }

  fun subtract(targetId: String, fromId: String, subtractId: String): Unit = store.update { document ->
    val from = selection(document, fromId) ?: return@update
    val sub = selection(document, subtractId) ?: return@update
    val result = from.subtract(sub)
    selections(document).put(targetId, result.toJson())
    remember(document, result)
  }

  // MARK: - Export / import

  fun export(selectionId: String): String? = get(selectionId)?.let { SelectionBlob.encode(secret, it) }

  /** False (and nothing written) unless [blob] is one of this install's own Android blobs. */
  fun import(selectionId: String, blob: String): Boolean {
    val selection = SelectionBlob.decode(secret, blob) ?: return false
    save(selectionId, selection)
    return true
  }

  // MARK: - Plumbing

  private fun selection(document: JSONObject, id: String): Selection? =
    document.optJSONObject(SELECTIONS)?.optJSONObject(id)?.let(Selection::fromJson)

  private fun selections(document: JSONObject): JSONObject =
    document.optJSONObject(SELECTIONS) ?: JSONObject().also { document.put(SELECTIONS, it) }

  private fun remember(document: JSONObject, selection: Selection) {
    if (selection.isEmpty) return
    selection.applications.forEach { rememberToken(document, TokenHandles.APPLICATION, it) }
    selection.categories.forEach { rememberToken(document, TokenHandles.CATEGORY, it) }
    selection.webDomains.forEach { rememberToken(document, TokenHandles.WEB_DOMAIN, it) }
  }

  /**
   * Makes the handle of ([type], [id]) resolvable through the handle map,
   * inside the caller's `update`. Every selection member is remembered when
   * the selection is saved; the shield remembers the app it reports in an
   * interaction too, so a handle from `drainShieldInteractions` can be
   * passed back to `temporaryUnlock(…, { target })` even for an app no
   * selection holds (one an allowlist shields). Returns the handle.
   */
  fun rememberToken(document: JSONObject, type: String, id: String): String {
    val tokens = document.optJSONObject(TOKENS) ?: JSONObject().also { document.put(TOKENS, it) }
    val handle = TokenHandles.derive(secret, type, id)
    if (!tokens.has(handle)) tokens.put(handle, JSONObject().put("type", type).put("id", id))
    return handle
  }

  companion object {
    private const val SELECTIONS = "selections"
    private const val TOKENS = "tokens"

    /** handle → (type, id), shared by every instance of the process. */
    private val resolved = HashMap<String, Pair<String, String>>()
  }
}
