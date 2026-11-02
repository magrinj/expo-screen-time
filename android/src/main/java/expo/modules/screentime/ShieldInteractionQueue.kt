package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject

/**
 * The queue behind `drainShieldInteractions()`: the twin of
 * `ios/ShieldInteractionQueue.swift`, without the state store.
 *
 * The shield Activity records one entry per press of a shield button, and
 * the app reads-and-clears the queue to power "you were blocked N times"
 * UIs. Each entry carries what was shielded (`target`, the handle
 * `getTokens` returns for that app) and the monitored activity whose source
 * owns the shield (`activityName`, null for the manual block).
 *
 * Presses on the SAME target within [COLLAPSE_WINDOW_MS] are one entry: a
 * user tapping both buttons of one shield is one blocked attempt, not two.
 * Presses on different targets within the window are separate entries
 * (review R16: the collapse used to be global). The last press per target
 * survives a drain, so a press right after one still collapses. The queue
 * is capped at [CAPACITY]: it only grows while the app is not draining it.
 *
 * The shield Activity (in the engine process) and the app are different
 * processes: [record] and [drain] are document-in, document-out and must run
 * inside `StateStore.update { }` — unlocked, a press between the drain's
 * read and its write is lost. iOS's migration of entries written before
 * `target` existed is iOS-only; an entry without a target is still a valid
 * press here (a token no handle could be derived for).
 */
internal object ShieldInteractionQueue {
  /** Two presses on one target closer than this are one blocked attempt. */
  const val COLLAPSE_WINDOW_MS = 2_000L

  /** Entries kept while the app is not draining; the oldest go first. */
  const val CAPACITY = 200

  /** The document key the queue lives under. */
  const val KEY = "shieldInteractions"

  /** The module's reserved activity names (the meters) never reach JS. */
  private const val RESERVED_PREFIX = "expo_st_"

  /** What a shield covered, as JS addresses tokens: a `TokenHandle`. */
  data class Target(
    /** `application`, `category` or `webDomain`. */
    val type: String,
    /** The handle `getTokens` returns for this token, so `<TokenLabel>` renders it. Opaque to JS. */
    val handle: String,
  ) {
    /** What "the same target" means for the collapse. */
    internal val key: String
      get() = "$type:$handle"

    fun toJson(): JSONObject = JSONObject().put("handle", handle).put("type", type)

    /** The JS shape of a `TokenHandle`. */
    fun toMap(): Map<String, String> = mapOf("handle" to handle, "type" to type)

    companion object {
      /** A target from its document shape; one without a type or a handle is no target. */
      fun fromJson(json: JSONObject?): Target? {
        if (json == null) return null
        // `opt(…) as? String`, never `optString`: Android's `org.json` turns
        // a JSON null into the string "null" and a number into its digits.
        val type = (json.opt("type") as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val handle = (json.opt("handle") as? String)?.takeIf { it.isNotEmpty() } ?: return null
        return Target(type, handle)
      }
    }
  }

  /** One press, as persisted and as returned to JS. Build one with [Entry.press]: it sanitizes the activity name. */
  data class Entry(
    /** `primary` or `secondary`. */
    val button: String,
    /** Unix milliseconds. */
    val timestamp: Long,
    /** null when no handle could be derived for the pressed token. */
    val target: Target? = null,
    /** The user-facing name of the activity whose source shields the target; null for the manual block. */
    val activityName: String? = null,
  ) {
    /** The persisted shape; null fields are absent. */
    fun toJson(): JSONObject = JSONObject().apply {
      put("button", button)
      put("timestamp", timestamp)
      target?.let { put("target", it.toJson()) }
      activityName?.let { put("activityName", it) }
    }

    /**
     * The `ShieldInteraction` JS receives: every field present, null fields
     * as JSON `null` (not `undefined`); the timestamp a JS number.
     */
    fun toInteraction(): Map<String, Any?> = mapOf(
      "button" to button,
      "timestamp" to timestamp.toDouble(),
      "target" to target?.toMap(),
      "activityName" to activityName,
    )

    companion object {
      /** An entry whose activity name is sanitized: the way every press is built at the seam. */
      fun press(button: String, timestamp: Long, target: Target? = null, activityName: String? = null): Entry =
        Entry(button, timestamp, target, userFacing(activityName))

      /** The persisted shape; anything without a button and a timestamp never was an entry. */
      fun fromJson(json: JSONObject?): Entry? {
        if (json == null) return null
        val button = (json.opt("button") as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val timestamp = JsonNumbers.long(json.opt("timestamp")) ?: return null
        return Entry(button, timestamp, Target.fromJson(json.optJSONObject("target")), userFacing(json.opt("activityName") as? String))
      }

      /** The module's reserved names (`expo_st_…`: the meters) never reach JS as an activity; nor does an empty name. */
      fun userFacing(activityName: String?): String? =
        activityName?.takeIf { it.isNotEmpty() && !it.startsWith(RESERVED_PREFIX) }
    }
  }

  /**
   * The whole persisted state: the entries not yet drained, and the last
   * recorded press per target — kept across drains, pruned to the window.
   */
  data class State(
    val entries: List<Entry> = emptyList(),
    /** Target key → Unix ms of the last press that was recorded for it. */
    val lastPressByTarget: Map<String, Long> = emptyMap(),
  ) {
    /** What [record] did. */
    data class Recorded(val state: State, val kept: Boolean)

    /**
     * Records [entry] unless it collapses into a press on the same target
     * less than [COLLAPSE_WINDOW_MS] earlier. Either way the per-target
     * record is pruned to the window around `entry.timestamp` — on both
     * sides, so a record the clock has since jumped back behind does not
     * linger (it stays bounded by the number of distinct targets pressed in
     * the last two seconds).
     */
    fun record(pressed: Entry): Recorded {
      // The seam's invariant, whoever built the entry: a reserved or empty
      // activity name is never stored, so it is never drained.
      val entry = pressed.copy(activityName = Entry.userFacing(pressed.activityName))
      val now = entry.timestamp
      val key = entry.target?.key ?: ""
      val last = lastPressByTarget[key]
      val pruned = lastPressByTarget.filterValues { kotlin.math.abs(now - it) < COLLAPSE_WINDOW_MS }

      if (last != null && now >= last && now - last < COLLAPSE_WINDOW_MS) {
        return Recorded(State(entries, pruned), kept = false)
      }
      val appended = entries + entry
      val capped = if (appended.size > CAPACITY) appended.drop(appended.size - CAPACITY) else appended
      return Recorded(State(capped, pruned + (key to now)), kept = true)
    }

    /** What [drain] did. */
    data class Drained(val state: State, val interactions: List<Map<String, Any?>>)

    /**
     * Every entry, oldest first, in the JS shape; the queue is empty
     * afterwards. The per-target record stays, so the collapse spans the
     * drain.
     */
    fun drain(): Drained = Drained(State(emptyList(), lastPressByTarget), entries.map { it.toInteraction() })

    val isEmpty: Boolean
      get() = entries.isEmpty() && lastPressByTarget.isEmpty()

    /** The document shape; null for a state with nothing to store. */
    fun toJson(): JSONObject? {
      if (isEmpty) return null
      return JSONObject()
        .put("entries", JSONArray().also { array -> entries.forEach { array.put(it.toJson()) } })
        .put("lastPressByTarget", JSONObject().also { map -> lastPressByTarget.forEach { (key, at) -> map.put(key, at) } })
    }

    companion object {
      val EMPTY = State()

      /** The state a document holds. Entries this build cannot read are dropped; garbage is an empty queue. */
      fun fromJson(json: JSONObject?): State {
        if (json == null) return EMPTY
        val entries = json.optJSONArray("entries")?.let { array ->
          (0 until array.length()).mapNotNull { Entry.fromJson(array.optJSONObject(it)) }
        } ?: emptyList()
        val last = json.optJSONObject("lastPressByTarget")?.let { map ->
          map.keys().asSequence().mapNotNull { key -> JsonNumbers.long(map.opt(key))?.let { key to it } }.toMap()
        } ?: emptyMap()
        return State(entries, last)
      }
    }
  }

  // The seam: document in, document out, inside `StateStore.update { }`.

  /** The queue [document] holds. */
  fun read(document: JSONObject): State = State.fromJson(document.optJSONObject(KEY))

  /** Stores [state] in [document]; an empty state leaves no key. */
  fun write(document: JSONObject, state: State) {
    val json = state.toJson()
    if (json == null) document.remove(KEY) else document.put(KEY, json)
  }

  /**
   * The shield Activity's step: record [entry] in [document]. Returns
   * whether the press was kept (a collapsed press still refreshes nothing:
   * the record is the earlier press).
   */
  fun record(document: JSONObject, entry: Entry): Boolean {
    val recorded = read(document).record(entry)
    write(document, recorded.state)
    return recorded.kept
  }

  /** The app's step: read-and-clear [document]'s queue, in the `ShieldInteraction` shape. */
  fun drain(document: JSONObject): List<Map<String, Any?>> {
    val drained = read(document).drain()
    if (drained.interactions.isNotEmpty()) write(document, drained.state)
    return drained.interactions
  }
}
