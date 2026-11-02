package expo.modules.screentime

/**
 * The naming rules of a logical activity (the `activityName` JS uses
 * everywhere), the Android part of `ios/ActivityRegistration.swift`.
 *
 * iOS registers one physical DeviceActivity per logical name, generation
 * suffixed (`<name>__expo_st_gen_<n>`), and keeps the module's own meters
 * under `expo_st_*`. Android has no DeviceActivity, so there are no physical
 * names, generations or legacy weekday variants to resolve: the engine is the
 * only caller back, and an activity is current exactly while its config is
 * in the monitoring state. What both platforms share is the VALIDATION: a
 * name that `startMonitoring` accepts on one platform must be accepted on
 * the other (contract rule 3), so the separator is rejected here too even
 * though nothing appends it.
 */
internal object ActivityNames {
  /** Between the logical name and the generation in an iOS physical name. */
  const val GENERATION_SEPARATOR = "__expo_st_gen_"

  /**
   * Names starting with this are the module's own (on iOS the unlock and
   * snooze meters) and never a user activity.
   */
  const val RESERVED_PREFIX = "expo_st_"

  /**
   * Whether `name` can be a logical activity name: not empty, not reserved,
   * free of the generation separator. Gates `startMonitoring` and
   * `stopMonitoring` alike.
   */
  fun isValidLogicalName(name: String): Boolean =
    name.isNotEmpty() && !name.startsWith(RESERVED_PREFIX) && !name.contains(GENERATION_SEPARATOR)

  /** Whether `name` belongs to the module: never listed by, nor stopped through, the monitoring API. */
  fun isInternalName(name: String): Boolean = name.startsWith(RESERVED_PREFIX)

  /** The `INVALID_CONFIG` message, word for word the iOS one. */
  const val INVALID_NAME_MESSAGE =
    "activityName must not be empty, start with '$RESERVED_PREFIX' or contain '$GENERATION_SEPARATOR'"
}
