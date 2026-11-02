package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tolerant reads from a monitoring config, whichever shape it arrives in:
 * the JS object Expo hands the module (a `Map` whose numbers are `Double`)
 * or the document the state store persisted (`org.json`, whose numbers are
 * `Int`, `Long` or `Double`). The twin of `DailySchedule.intValue` on iOS,
 * where JS numbers reach Swift as `Double` and stored ones as `NSNumber`
 * (fix 13: reading `hour` as `Int` rejected every
 * schedule on a device).
 */
internal object ConfigValues {
  /**
   * An integer from a JS or stored value: integral and fractional doubles
   * both truncate (iOS truncates through `NSNumber`), strings and booleans
   * are not numbers.
   */
  fun int(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
    is Double -> if (value.isFinite() && kotlin.math.abs(value) < Int.MAX_VALUE.toDouble()) value.toInt() else null
    is Float -> int(value.toDouble())
    is Number -> int(value.toDouble())
    else -> null
  }

  fun long(value: Any?): Long? = when (value) {
    is Long -> value
    is Int -> value.toLong()
    is Double -> if (value.isFinite() && kotlin.math.abs(value) < Long.MAX_VALUE.toDouble()) value.toLong() else null
    is Number -> long(value.toDouble())
    else -> null
  }

  fun bool(value: Any?): Boolean? = value as? Boolean

  fun string(value: Any?): String? = value as? String

  /** A nested object as a map; `null` for anything else (JSON null included). */
  fun map(value: Any?): Map<String, Any?>? = when (value) {
    is JSONObject -> {
      val result = LinkedHashMap<String, Any?>()
      for (key in value.keys()) result[key] = normalize(value.opt(key))
      result
    }
    is Map<*, *> -> {
      val result = LinkedHashMap<String, Any?>()
      for ((key, entry) in value) result[key.toString()] = normalize(entry)
      result
    }
    else -> null
  }

  /** A nested array as a list; `null` for anything else. */
  fun list(value: Any?): List<Any?>? = when (value) {
    is JSONArray -> List(value.length()) { normalize(value.opt(it)) }
    is List<*> -> value.map(::normalize)
    is Array<*> -> value.map(::normalize)
    else -> null
  }

  private fun normalize(value: Any?): Any? = if (value === JSONObject.NULL) null else value
}
