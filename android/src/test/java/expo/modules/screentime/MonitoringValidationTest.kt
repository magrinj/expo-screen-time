package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `startMonitoring` validation: the iOS order of checks, codes and
 * messages (`ios/ExpoScreenTimeModule.swift`), then the DeviceActivity
 * limits the contract makes Android enforce (C07 of the conformance suite).
 */
class MonitoringValidationTest {
  private val apps = Selection(applications = setOf("com.a"))
  private val mixed = Selection(applications = setOf("com.a"), categories = setOf("GAME"))
  private val selections: (String) -> Selection? = { id -> mapOf("APPS" to apps, "MIXED" to mixed, "EMPTY" to Selection.EMPTY)[id] }

  private fun components(hour: Int, minute: Int = 0, weekday: Int? = null): Map<String, Any> =
    LinkedHashMap<String, Any>().apply { put("hour", hour.toDouble()); put("minute", minute.toDouble()); if (weekday != null) put("weekday", weekday.toDouble()) }

  private fun schedule(startHour: Int, endHour: Int, startMinute: Int = 0, endMinute: Int = 0, repeats: Boolean = true): Map<String, Any> =
    mapOf("intervalStart" to components(startHour, startMinute), "intervalEnd" to components(endHour, endMinute), "repeats" to repeats)

  private fun config(vararg entries: Pair<String, Any?>): Map<String, Any?> =
    mapOf("activityName" to "work", "schedule" to schedule(9, 17), "selectionId" to "APPS") + entries

  private fun code(config: Any?, existing: Set<String> = emptySet()): String = try {
    MonitoringValidation.validate(config, selections, existing)
    "OK"
  } catch (e: ScreenTimeException) {
    e.code + ": " + e.message
  }

  @Test
  fun `the iOS checks, in the iOS order, with the iOS codes and messages`() {
    assertEquals("INVALID_CONFIG: activityName is required", code(config("activityName" to null)))
    assertEquals("INVALID_CONFIG: activityName is required", code(null))
    assertEquals("INVALID_CONFIG: " + ActivityNames.INVALID_NAME_MESSAGE, code(config("activityName" to "expo_st_temporary_unlock")))
    assertEquals("INVALID_CONFIG: " + ActivityNames.INVALID_NAME_MESSAGE, code(config("activityName" to "")))
    assertEquals("a reserved name is rejected before the schedule is looked at", "INVALID_CONFIG: " + ActivityNames.INVALID_NAME_MESSAGE, code(config("activityName" to "expo_st_x", "schedule" to null)))
    assertEquals("INVALID_CONFIG: schedule is required", code(config("schedule" to null)))
    assertEquals("INVALID_CONFIG: mode must be 'blocklist' or 'allowlist'", code(config("mode" to "deny")))
    assertEquals("INVALID_CONFIG: mode must be 'blocklist' or 'allowlist'", code(config("mode" to "deny", "selectionId" to null)))
    assertEquals("INVALID_CONFIG: mode 'allowlist' requires a selectionId (the applications to allow)", code(config("mode" to "allowlist", "selectionId" to null)))
    assertEquals("SELECTION_NOT_FOUND: Selection 'nope' not found", code(config("mode" to "allowlist", "selectionId" to "nope")))
    assertEquals(
      "INVALID_CONFIG: An allowlist may hold applications only; selection 'MIXED' has 1 categories and 0 web domains",
      code(config("mode" to "allowlist", "selectionId" to "MIXED")),
    )
    assertEquals("INVALID_CONFIG: An allowlist needs at least one application; selection 'EMPTY' is empty", code(config("mode" to "allowlist", "selectionId" to "EMPTY")))
    assertEquals("OK", code(config("mode" to "allowlist")))
    assertEquals("a blocklist's selection is not checked, as on iOS", "OK", code(config("selectionId" to "nope")))
    assertEquals("INVALID_CONFIG: schedule.intervalStart and schedule.intervalEnd are required", code(config("schedule" to mapOf("intervalStart" to components(9)))))
    assertEquals("the mode is checked before the schedule's shape, as on iOS", "INVALID_CONFIG: mode must be 'blocklist' or 'allowlist'", code(config("mode" to "x", "schedule" to mapOf<String, Any>())))
  }

  @Test
  fun `the DeviceActivity checks - date components, then the interval length, then the activity count`() {
    assertEquals("INVALID_DATE_COMPONENTS: Invalid date components in schedule", code(config("schedule" to schedule(24, 17))))
    assertEquals("INVALID_DATE_COMPONENTS: Invalid date components in schedule", code(config("schedule" to schedule(9, 17, startMinute = 60))))
    assertEquals("INVALID_DATE_COMPONENTS: Invalid date components in schedule", code(config("schedule" to mapOf("intervalStart" to components(9, weekday = 8), "intervalEnd" to components(17, weekday = 8)))))
    assertEquals("INVALID_DATE_COMPONENTS: Invalid date components in schedule: hour is required", code(config("schedule" to mapOf("intervalStart" to mapOf("minute" to 5.0), "intervalEnd" to components(17)))))
    assertTrue("a weekday on one bound only", code(config("schedule" to mapOf("intervalStart" to components(9), "intervalEnd" to components(17, weekday = 2)))).startsWith("INVALID_DATE_COMPONENTS"))
    assertEquals("C07: a 5-minute interval", "INTERVAL_TOO_SHORT: Schedule interval must be at least 15 minutes", code(config("schedule" to schedule(10, 10, 0, 5))))
    assertEquals("14:59 is too short", "INTERVAL_TOO_SHORT: Schedule interval must be at least 15 minutes", code(config("schedule" to mapOf("intervalStart" to components(10), "intervalEnd" to mapOf("hour" to 10.0, "minute" to 14.0, "second" to 59.0)))))
    assertEquals("exactly 15 minutes passes", "OK", code(config("schedule" to schedule(10, 10, 0, 15))))
    assertEquals("midnight wrap 22–06 is 8 h", "OK", code(config("schedule" to schedule(22, 6))))
    assertEquals("equal bounds: a 24 h occurrence", "OK", code(config("schedule" to schedule(0, 0))))
    assertEquals("a week-long weekly interval passes", "OK", code(config("schedule" to mapOf("intervalStart" to components(22, weekday = 6), "intervalEnd" to components(22, weekday = 6)))))
    assertEquals("Fri 22:00 → Sun 06:00 passes", "OK", code(config("schedule" to mapOf("intervalStart" to components(22, weekday = 6), "intervalEnd" to components(6, weekday = 1)))))
    val limits = MonitoringValidation.Limits(maxIntervalSeconds = 24 * 3600)
    try {
      MonitoringValidation.validate(config("schedule" to mapOf("intervalStart" to components(22, weekday = 6), "intervalEnd" to components(6, weekday = 1))), selections, emptySet(), limits)
      fail("too long")
    } catch (e: ScreenTimeException) {
      assertEquals("INTERVAL_TOO_LONG", e.code)
      assertEquals("Schedule interval cannot exceed 1 week", e.message)
    }

    val nineteen = (1..19).map { "a$it" }.toSet()
    assertTrue("a 20th logical activity", code(config(), nineteen).startsWith("EXCESSIVE_ACTIVITIES: Maximum of 19 activities"))
    assertEquals("re-registering one of the 19 costs no slot", "OK", code(config("activityName" to "a7"), nineteen))
    assertEquals("18 others leave room", "OK", code(config(), nineteen - "a1"))
    assertEquals("a too-short interval is reported before the count", "INTERVAL_TOO_SHORT: Schedule interval must be at least 15 minutes", code(config("schedule" to schedule(10, 10, 0, 5)), nineteen))
    assertEquals(19, MonitoringValidation.defaultLimits.maxMonitoredActivities)
    assertEquals(900, MonitoringValidation.defaultLimits.minIntervalSeconds)
    assertEquals(604_800, MonitoringValidation.defaultLimits.maxIntervalSeconds)
  }

  @Test
  fun `the validated config is normalized and round-trips`() {
    val events = listOf(
      mapOf("eventName" to "social", "selectionId" to "APPS", "threshold" to 3600.0, "includesPastActivity" to false, "blockOnThreshold" to true),
      mapOf("eventName" to "noThreshold", "selectionId" to "APPS"),
      mapOf("selectionId" to "APPS", "threshold" to 60.0),
      mapOf("eventName" to "noSelection", "threshold" to 60.0),
      mapOf("eventName" to "social", "selectionId" to "MIXED", "threshold" to 120.0),
      mapOf("eventName" to "other", "selectionId" to "APPS", "threshold" to 10),
    )
    val validated = MonitoringValidation.validate(
      config(
        "schedule" to schedule(9, 17, repeats = false) + mapOf("warningTime" to 300.0),
        "events" to events,
        "weekdays" to listOf(6.0, 2.0, 2.0, 9.0, 0.0, 3.0),
      ),
      selections,
      emptySet(),
    )
    assertEquals("work", validated.activityName)
    assertEquals(ScheduleConfig(DateComponents(9, 0), DateComponents(17, 0), repeats = false, warningTimeSeconds = 300), validated.schedule)
    assertEquals("APPS", validated.selectionId)
    assertEquals("blocklist", validated.mode)
    assertEquals(
      "malformed events are skipped and a later one with the same name wins, as iOS's dictionary does",
      listOf(ThresholdConfig("social", "MIXED", 120), ThresholdConfig("other", "APPS", 10)),
      validated.events,
    )
    assertEquals(listOf(2, 3, 6), validated.weekdays)
    assertTrue(validated.rule is DailySchedule)
    assertEquals(setOf(2, 3, 6), (validated.rule as DailySchedule).weekdays)
    assertEquals(validated, MonitoringConfig.fromJson(JSONObject(validated.toJson().toString())))

    val plain = MonitoringValidation.validate(config("schedule" to schedule(9, 17) + mapOf("warningTime" to 0.0), "selectionId" to null), selections, emptySet())
    assertEquals("warningTime 0 is none, repeats defaults to true", ScheduleConfig(DateComponents(9, 0), DateComponents(17, 0)), plain.schedule)
    assertEquals(null, plain.selectionId)
    assertTrue(plain.events.isEmpty() && plain.weekdays.isEmpty())
    assertEquals(plain, MonitoringConfig.fromJson(JSONObject(plain.toJson().toString())))

    val weekly = MonitoringValidation.validate(config("schedule" to mapOf("intervalStart" to components(22, weekday = 6), "intervalEnd" to components(6, weekday = 1)), "weekdays" to listOf(2.0)), selections, emptySet())
    assertEquals("the components win over the weekdays array", WeeklySchedule(6, TimeOfDay(22, 0), 1, TimeOfDay(6, 0)), weekly.rule)
    assertEquals(weekly, MonitoringConfig.fromJson(JSONObject(weekly.toJson().toString())))
    assertEquals(null, MonitoringConfig.fromJson(JSONObject("""{"activityName":"x"}""")))
  }

  @Test
  fun `a stored config reads back through the same validation`() {
    val stored = JSONObject("""{"activityName":"work","schedule":{"intervalStart":{"hour":9,"minute":0},"intervalEnd":{"hour":17,"minute":0},"repeats":true},"selectionId":"APPS","weekdays":[2,3]}""")
    val validated = MonitoringValidation.validate(stored, selections, emptySet())
    assertEquals(listOf(2, 3), validated.weekdays)
    assertEquals(DateComponents(9, 0), validated.schedule.intervalStart)
  }

  @Test
  fun `validateActivityName is the rule stopMonitoring applies`() {
    MonitoringValidation.validateActivityName("work hours")
    try {
      MonitoringValidation.validateActivityName("expo_st_snooze_1")
      fail()
    } catch (e: ScreenTimeException) {
      assertEquals("INVALID_CONFIG", e.code)
      assertEquals(ActivityNames.INVALID_NAME_MESSAGE, e.message)
    }
    assertFalse(ActivityNames.isValidLogicalName("a${ActivityNames.GENERATION_SEPARATOR}1"))
  }
}
