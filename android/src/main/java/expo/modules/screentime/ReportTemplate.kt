package expo.modules.screentime

import org.json.JSONException
import org.json.JSONObject

/**
 * One node of a report template as `serializeTemplate` (`src/report/
 * serializeTemplate.ts`) emits it: `{ type, props, children }`. Props keep
 * their JSON values (strings, numbers, booleans, lists of those); the
 * resolver reads them tolerantly, as the iOS renderer does with
 * `as? NSNumber` / `as? String`.
 */
internal data class ReportTemplateNode(
  val type: String,
  val props: Map<String, Any?> = emptyMap(),
  val children: List<ReportTemplateNode> = emptyList(),
)

/**
 * A parsed template: `{ version: 1, root }`. [parse] reads the JSON the way
 * `CustomTemplateView.body` does and answers null for everything it would
 * answer with the "No custom template set" placeholder: no JSON, invalid
 * JSON, no object, a version other than 1 (a newer JS than this native),
 * a root that is not an object. Templates degrade, they do not crash.
 *
 * Inside a node the iOS reads are reproduced too:
 * - `type` must be a string, else the node renders nothing (and is kept
 *   here with an empty type, so the resolver drops it where iOS does);
 * - `props` must be an object, else it is empty;
 * - `children` must be an array of objects: ONE element that is not an
 *   object empties the whole list (`as? [[String: Any]]` is all or nothing).
 * Nesting is capped at [MAX_DEPTH], twice what the serializer allows.
 */
internal data class ReportTemplate(val root: ReportTemplateNode) {
  companion object {
    const val VERSION = 1
    const val MAX_DEPTH = 64
    private const val KEY_VERSION = "version"
    private const val KEY_ROOT = "root"
    private const val KEY_TYPE = "type"
    private const val KEY_PROPS = "props"
    private const val KEY_CHILDREN = "children"

    fun parse(json: String?): ReportTemplate? {
      if (json.isNullOrEmpty()) return null
      val parsed = try {
        JSONObject(json)
      } catch (e: JSONException) {
        return null
      }
      return parse(parsed)
    }

    fun parse(json: JSONObject): ReportTemplate? {
      // `(template["version"] as? Int) == 1`: a JSON 1 or 1.0 both read as 1; 1.5 or "1" do not.
      if ((json.opt(KEY_VERSION) as? Number)?.toDouble() != VERSION.toDouble()) return null
      val root = json.optJSONObject(KEY_ROOT) ?: return null
      return ReportTemplate(node(root, 0))
    }

    fun node(json: JSONObject, depth: Int): ReportTemplateNode {
      val type = ConfigValues.string(json.opt(KEY_TYPE)) ?: ""
      val props = ConfigValues.map(json.opt(KEY_PROPS)) ?: emptyMap()
      val children = ArrayList<ReportTemplateNode>()
      val array = json.optJSONArray(KEY_CHILDREN)
      if (array != null && depth < MAX_DEPTH) {
        for (i in 0 until array.length()) {
          val child = array.opt(i) as? JSONObject ?: return ReportTemplateNode(type, props)
          children.add(node(child, depth + 1))
        }
      }
      return ReportTemplateNode(type, props, children)
    }
  }
}
