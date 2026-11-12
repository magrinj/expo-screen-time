package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject

/**
 * A stored selection: the Android twin of `FamilyActivitySelection`, holding
 * package names where iOS holds application tokens. Categories and web
 * domains keep their slot so the document shape (and the set algebra) is the
 * same as on iOS, but they stay empty: `selectableKinds.categories` and
 * `webDomains` are off on Android.
 *
 * Pure: no Android imports, so the algebra is unit-tested on a plain JVM.
 */
internal data class Selection(
  val applications: Set<String> = emptySet(),
  val categories: Set<String> = emptySet(),
  val webDomains: Set<String> = emptySet(),
) {
  val isEmpty: Boolean
    get() = applications.isEmpty() && categories.isEmpty() && webDomains.isEmpty()

  fun union(other: Selection) = Selection(
    applications + other.applications,
    categories + other.categories,
    webDomains + other.webDomains,
  )

  fun intersect(other: Selection) = Selection(
    applications intersect other.applications,
    categories intersect other.categories,
    webDomains intersect other.webDomains,
  )

  fun subtract(other: Selection) = Selection(
    applications - other.applications,
    categories - other.categories,
    webDomains - other.webDomains,
  )

  /** `SelectionMetadata`: the counts JS sees, never the members. */
  fun metadata(): Map<String, Int> = mapOf(
    "applicationCount" to applications.size,
    "categoryCount" to categories.size,
    "webDomainCount" to webDomains.size,
  )

  fun toJson(): JSONObject = JSONObject()
    .put(APPLICATIONS, JSONArray(applications.sorted()))
    .put(CATEGORIES, JSONArray(categories.sorted()))
    .put(WEB_DOMAINS, JSONArray(webDomains.sorted()))

  companion object {
    private const val APPLICATIONS = "applications"
    private const val CATEGORIES = "categories"
    private const val WEB_DOMAINS = "webDomains"

    val EMPTY = Selection()

    /**
     * The selection a document holds, or null when it is not one (a missing
     * or non-array member, a non-string entry): a malformed import must be
     * refused, never half-read.
     */
    fun fromJson(json: JSONObject): Selection? {
      val applications = strings(json, APPLICATIONS) ?: return null
      val categories = strings(json, CATEGORIES) ?: return null
      val webDomains = strings(json, WEB_DOMAINS) ?: return null
      return Selection(applications, categories, webDomains)
    }

    private fun strings(json: JSONObject, key: String): Set<String>? {
      val array = json.optJSONArray(key) ?: return null
      val result = LinkedHashSet<String>()
      for (i in 0 until array.length()) {
        val value = array.opt(i)
        if (value !is String || value.isEmpty()) return null
        result.add(value)
      }
      return result
    }
  }
}
