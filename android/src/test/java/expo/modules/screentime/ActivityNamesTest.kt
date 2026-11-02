package expo.modules.screentime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `scripts/swift-tests/main.swift`, sections "ActivityRegistration — physical
 * names" (the validation cases; physical names, generations and their
 * parsing are iOS-only) and "— reserved names (pass 3, finding 10)".
 */
class ActivityNamesTest {
  private val sep = ActivityNames.GENERATION_SEPARATOR

  @Test
  fun `ActivityRegistration - physical names, the validation cases (Swift section)`() {
    assertTrue("ordinary names are valid", ActivityNames.isValidLogicalName("work hours") && ActivityNames.isValidLogicalName("A_weekday_2"))
    assertTrue(
      "empty names and names containing the separator are rejected",
      !ActivityNames.isValidLogicalName("") && !ActivityNames.isValidLogicalName("a${sep}b"),
    )
  }

  @Test
  fun `ActivityRegistration - reserved names (Swift section)`() {
    assertFalse("the unlock meter's name is rejected (start and stop)", ActivityNames.isValidLogicalName("expo_st_temporary_unlock"))
    assertTrue(
      "the whole expo_st_ prefix is reserved",
      !ActivityNames.isValidLogicalName("expo_st_anything") && !ActivityNames.isValidLogicalName("expo_st_"),
    )
    assertTrue(
      "only the prefix is reserved",
      ActivityNames.isValidLogicalName("expo_stuff") && ActivityNames.isValidLogicalName("my_expo_st_thing"),
    )
    assertTrue(
      "isInternalName hides the meter and nothing else",
      ActivityNames.isInternalName("expo_st_temporary_unlock") && !ActivityNames.isInternalName("A${sep}1"),
    )
  }

  @Test
  fun `the INVALID_CONFIG message names both rules, as on iOS`() {
    assertTrue(ActivityNames.INVALID_NAME_MESSAGE.contains("expo_st_") && ActivityNames.INVALID_NAME_MESSAGE.contains(sep))
  }
}
