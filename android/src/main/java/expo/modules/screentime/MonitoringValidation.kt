package expo.modules.screentime

import org.json.JSONArray
import org.json.JSONObject

/** `DateComponents` as the contract types it: `hour`, `minute`, optional `second` and `weekday`. */
internal data class DateComponents(val hour: Int, val minute: Int, val second: Int = 0, val weekday: Int? = null) {
  val time: TimeOfDay
    get() = TimeOfDay(hour, minute, second)

  fun toJson(): JSONObject = JSONObject().put("hour", hour).put("minute", minute).put("second", second).putOpt("weekday", weekday)

  companion object {
    fun fromJson(value: Any?): DateComponents? {
      val dict = ConfigValues.map(value) ?: return null
      val hour = ConfigValues.int(dict["hour"]) ?: return null
      return DateComponents(hour, ConfigValues.int(dict["minute"]) ?: 0, ConfigValues.int(dict["second"]) ?: 0, ConfigValues.int(dict["weekday"]))
    }
  }
}

/** `DeviceActivitySchedule`, validated. */
internal data class ScheduleConfig(
  val intervalStart: DateComponents,
  val intervalEnd: DateComponents,
  val repeats: Boolean = true,
  /** Seconds before the end (and before a threshold) at which the warning events fire; null for none. */
  val warningTimeSeconds: Int? = null,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("intervalStart", intervalStart.toJson())
    .put("intervalEnd", intervalEnd.toJson())
    .put("repeats", repeats)
    .putOpt("warningTime", warningTimeSeconds)

  companion object {
    fun fromJson(value: Any?): ScheduleConfig? {
      val dict = ConfigValues.map(value) ?: return null
      val start = DateComponents.fromJson(dict["intervalStart"]) ?: return null
      val end = DateComponents.fromJson(dict["intervalEnd"]) ?: return null
      return ScheduleConfig(start, end, ConfigValues.bool(dict["repeats"]) ?: true, ConfigValues.int(dict["warningTime"])?.takeIf { it > 0 })
    }
  }
}

/** `DeviceActivityEventThreshold`, validated. */
internal data class ThresholdConfig(
  val eventName: String,
  val selectionId: String,
  val thresholdSeconds: Int,
  val includesPastActivity: Boolean = true,
  val blockOnThreshold: Boolean = false,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("eventName", eventName)
    .put("selectionId", selectionId)
    .put("threshold", thresholdSeconds)
    .put("includesPastActivity", includesPastActivity)
    .put("blockOnThreshold", blockOnThreshold)

  companion object {
    fun fromJson(value: Any?): ThresholdConfig? {
      val dict = ConfigValues.map(value) ?: return null
      return ThresholdConfig(
        ConfigValues.string(dict["eventName"]) ?: return null,
        ConfigValues.string(dict["selectionId"]) ?: return null,
        ConfigValues.int(dict["threshold"]) ?: return null,
        ConfigValues.bool(dict["includesPastActivity"]) ?: true,
        ConfigValues.bool(dict["blockOnThreshold"]) ?: false,
      )
    }
  }
}

/**
 * A `MonitoringConfiguration` that passed `MonitoringValidation`: what the
 * state stores for an activity, and the only way to build a `ScheduleRule`
 * for the engine.
 */
internal data class MonitoringConfig(
  val activityName: String,
  val schedule: ScheduleConfig,
  val selectionId: String? = null,
  /** `blocklist` or `allowlist`. */
  val mode: String = MODE_BLOCKLIST,
  val events: List<ThresholdConfig> = emptyList(),
  /** Normalized: 1…7, sorted, unique; empty means every day. */
  val weekdays: List<Int> = emptyList(),
) {
  /** Weekly when both bounds carry weekdays and `WeeklySchedule.isWeekly`; daily otherwise (a weekday on the start wins over `weekdays`). */
  val rule: ScheduleRule
    get() {
      val startWeekday = schedule.intervalStart.weekday
      val endWeekday = schedule.intervalEnd.weekday
      if (startWeekday != null && endWeekday != null && WeeklySchedule.isWeekly(startWeekday, schedule.intervalStart.time, endWeekday, schedule.intervalEnd.time)) {
        return WeeklySchedule(startWeekday, schedule.intervalStart.time, endWeekday, schedule.intervalEnd.time)
      }
      val selected = if (startWeekday != null) setOf(startWeekday) else weekdays.toSet()
      return DailySchedule(schedule.intervalStart.time, schedule.intervalEnd.time, selected)
    }

  fun toJson(): JSONObject = JSONObject()
    .put("activityName", activityName)
    .put("schedule", schedule.toJson())
    .putOpt("selectionId", selectionId)
    .put("mode", mode)
    .put("events", JSONArray().also { array -> events.forEach { array.put(it.toJson()) } })
    .put("weekdays", JSONArray(weekdays))

  companion object {
    const val MODE_BLOCKLIST = "blocklist"
    const val MODE_ALLOWLIST = "allowlist"

    /** A stored config; null when it no longer parses (then the activity cannot be evaluated and is dropped). */
    fun fromJson(value: Any?): MonitoringConfig? {
      val dict = ConfigValues.map(value) ?: return null
      val events = (ConfigValues.list(dict["events"]) ?: emptyList()).mapNotNull(ThresholdConfig::fromJson)
      return MonitoringConfig(
        ConfigValues.string(dict["activityName"]) ?: return null,
        ScheduleConfig.fromJson(dict["schedule"]) ?: return null,
        ConfigValues.string(dict["selectionId"]),
        ConfigValues.string(dict["mode"]) ?: MODE_BLOCKLIST,
        events,
        DailySchedule.weekdays(dict["weekdays"]).sorted(),
      )
    }
  }
}

/**
 * `startMonitoring` validation, in the order `ios/ExpoScreenTimeModule.swift`
 * checks and with its codes and messages, followed by the checks
 * DeviceActivity makes on iOS (`DeviceActivityCenter.MonitoringError`) in
 * the order Android makes them: the date components, the interval length,
 * the activity count. The contract makes Android enforce the iOS limits
 * (rule 3), so a config that passes here passes there.
 *
 * Pure: the selections are a lookup, the activity count a set. The module
 * calls `validate`, then `MonitoringState.start` with the result.
 */
internal object MonitoringValidation {
  /** `capabilities.limits`, identical on both platforms. */
  data class Limits(
    val maxMonitoredActivities: Int = 19,
    val minIntervalSeconds: Int = 15 * 60,
    val maxIntervalSeconds: Int = 7 * 24 * 60 * 60,
  )

  val defaultLimits = Limits()

  // The `ScreenTimeErrorCode`s this layer rejects with (`ScreenTimeException`).
  const val INVALID_CONFIG = "INVALID_CONFIG"
  const val SELECTION_NOT_FOUND = "SELECTION_NOT_FOUND"
  const val EXCESSIVE_ACTIVITIES = "EXCESSIVE_ACTIVITIES"
  const val INTERVAL_TOO_SHORT = "INTERVAL_TOO_SHORT"
  const val INTERVAL_TOO_LONG = "INTERVAL_TOO_LONG"
  const val INVALID_DATE_COMPONENTS = "INVALID_DATE_COMPONENTS"

  /**
   * The validated config, or a [ScreenTimeException] with the code JS
   * expects. `config` is the JS object (`Map`) or a stored one (`JSONObject`);
   * `selection` resolves a selection id; `existingActivities` are the names
   * monitored now (re-registering one of them does not cost a slot).
   */
  @Throws(ScreenTimeException::class)
  fun validate(
    config: Any?,
    selection: (String) -> Selection?,
    existingActivities: Set<String>,
    limits: Limits = defaultLimits,
  ): MonitoringConfig {
    val dict = ConfigValues.map(config) ?: emptyMap()

    val activityName = ConfigValues.string(dict["activityName"])
      ?: throw ScreenTimeException(INVALID_CONFIG, "activityName is required")
    validateActivityName(activityName)
    val scheduleDict = ConfigValues.map(dict["schedule"])
      ?: throw ScreenTimeException(INVALID_CONFIG, "schedule is required")

    // `mode` says how `selectionId` is applied while the interval is active;
    // validated before anything is registered. Thresholds keep blocklist semantics.
    val selectionId = ConfigValues.string(dict["selectionId"])
    val mode = if (selectionId != null) {
      blockMode(dict["mode"], selectionId, selection)
    } else {
      requestedBlockMode(dict["mode"]).also {
        if (it == MonitoringConfig.MODE_ALLOWLIST) {
          throw ScreenTimeException(INVALID_CONFIG, "mode 'allowlist' requires a selectionId (the applications to allow)")
        }
      }
    }

    val events = buildEvents(dict["events"])
    val weekdays = DailySchedule.weekdays(dict["weekdays"]).sorted()
    val schedule = buildSchedule(scheduleDict)

    val result = MonitoringConfig(activityName, schedule, selectionId, mode, events, weekdays)
    // What DeviceActivity would reject on iOS.
    val length = result.rule.nominalLengthSeconds
    if (length < limits.minIntervalSeconds) throw ScreenTimeException(INTERVAL_TOO_SHORT, "Schedule interval must be at least 15 minutes")
    if (length > limits.maxIntervalSeconds) throw ScreenTimeException(INTERVAL_TOO_LONG, "Schedule interval cannot exceed 1 week")
    if ((existingActivities - activityName).size >= limits.maxMonitoredActivities) {
      throw ScreenTimeException(
        EXCESSIVE_ACTIVITIES,
        "Maximum of ${limits.maxMonitoredActivities} activities can be monitored simultaneously " +
          "(iOS allows 20 and keeps one for the temporary-unlock meter)",
      )
    }
    return result
  }

  /** The rule `startMonitoring` and `stopMonitoring` share: the module's own names are not user activities. */
  @Throws(ScreenTimeException::class)
  fun validateActivityName(activityName: String) {
    if (!ActivityNames.isValidLogicalName(activityName)) throw ScreenTimeException(INVALID_CONFIG, ActivityNames.INVALID_NAME_MESSAGE)
  }

  /** The `BlockMode` a JS `mode` names; null and `blocklist` are the default. */
  private fun requestedBlockMode(value: Any?): String {
    if (value == null) return MonitoringConfig.MODE_BLOCKLIST
    val name = value as? String
    if (name != MonitoringConfig.MODE_BLOCKLIST && name != MonitoringConfig.MODE_ALLOWLIST) {
      throw ScreenTimeException(INVALID_CONFIG, "mode must be 'blocklist' or 'allowlist'")
    }
    return name
  }

  /**
   * `requestedBlockMode`, checked against the selection it applies to: an
   * allowlist holds applications only, at least one, and the selection
   * must exist (an allowlist over nothing is not "shield nothing"). A
   * blocklist's selection is not checked, as on iOS.
   */
  private fun blockMode(value: Any?, selectionId: String, lookup: (String) -> Selection?): String {
    val mode = requestedBlockMode(value)
    if (mode != MonitoringConfig.MODE_ALLOWLIST) return mode
    val selection = lookup(selectionId) ?: throw ScreenTimeException(SELECTION_NOT_FOUND, "Selection '$selectionId' not found")
    val categories = selection.categories.size
    val webDomains = selection.webDomains.size
    if (categories > 0 || webDomains > 0) {
      throw ScreenTimeException(
        INVALID_CONFIG,
        "An allowlist may hold applications only; selection '$selectionId' has $categories categories and $webDomains web domains",
      )
    }
    if (selection.applications.isEmpty()) {
      throw ScreenTimeException(INVALID_CONFIG, "An allowlist needs at least one application; selection '$selectionId' is empty")
    }
    return mode
  }

  /**
   * The thresholds, as iOS builds them: an entry without `eventName` or an
   * integer `threshold` is skipped, a later entry with the same name wins.
   * An entry without `selectionId` meters nothing on iOS (empty token sets)
   * and can never fire, so it is skipped here too — same observable
   * behaviour, no nullable selection downstream.
   */
  private fun buildEvents(value: Any?): List<ThresholdConfig> {
    val result = LinkedHashMap<String, ThresholdConfig>()
    for (entry in ConfigValues.list(value) ?: emptyList()) {
      val event = ThresholdConfig.fromJson(entry) ?: continue
      result.remove(event.eventName)
      result[event.eventName] = event
    }
    return result.values.toList()
  }

  private fun buildSchedule(dict: Map<String, Any?>): ScheduleConfig {
    val startDict = ConfigValues.map(dict["intervalStart"])
    val endDict = ConfigValues.map(dict["intervalEnd"])
    if (startDict == null || endDict == null) {
      throw ScreenTimeException(INVALID_CONFIG, "schedule.intervalStart and schedule.intervalEnd are required")
    }
    val start = dateComponents(startDict)
    val end = dateComponents(endDict)
    // A weekday on one bound only describes no interval DeviceActivity or
    // this engine can evaluate (iOS's DailySchedule declines it too).
    if ((start.weekday == null) != (end.weekday == null)) {
      throw ScreenTimeException(INVALID_DATE_COMPONENTS, "Invalid date components in schedule: weekday must be set on both intervalStart and intervalEnd, or on neither")
    }
    val repeats = ConfigValues.bool(dict["repeats"]) ?: true
    val warningTime = ConfigValues.int(dict["warningTime"])?.takeIf { it > 0 }
    return ScheduleConfig(start, end, repeats, warningTime)
  }

  private fun dateComponents(dict: Map<String, Any?>): DateComponents {
    val hour = ConfigValues.int(dict["hour"])
      ?: throw ScreenTimeException(INVALID_DATE_COMPONENTS, "Invalid date components in schedule: hour is required")
    val minute = ConfigValues.int(dict["minute"]) ?: 0
    val second = ConfigValues.int(dict["second"]) ?: 0
    val weekday = ConfigValues.int(dict["weekday"])
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59 || (weekday != null && weekday !in 1..7)) {
      throw ScreenTimeException(INVALID_DATE_COMPONENTS, "Invalid date components in schedule")
    }
    return DateComponents(hour, minute, second, weekday)
  }
}
