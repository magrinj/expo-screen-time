package expo.modules.screentime

import expo.modules.screentime.PermissionSteps.Feature
import expo.modules.screentime.PermissionSteps.Kind
import expo.modules.screentime.PermissionSteps.Status
import expo.modules.screentime.PermissionSteps.Step
import expo.modules.screentime.PermissionSteps.StepId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derivation rules of the contract (`src/ExpoScreenTime.types.ts`,
 * PERMISSIONS), the Kotlin twin of the `PermissionSteps` cases in
 * `scripts/swift-tests/main.swift`. Run with
 * `cd example/android && ./gradlew :expo-screen-time:testDebugUnitTest`.
 */
class PermissionStepsTest {
  private val stock = PermissionSteps.Device(sdkInt = 36, hasVendorAutostartScreen = false)
  private val xiaomi = PermissionSteps.Device(sdkInt = 36, hasVendorAutostartScreen = true)
  private val android12 = PermissionSteps.Device(sdkInt = 32, hasVendorAutostartScreen = false)

  private fun ids(plans: List<PermissionSteps.Plan>) = plans.map { it.id }
  private fun required(plans: List<PermissionSteps.Plan>) = plans.filter { it.required }.map { it.id }

  @Test
  fun `default features list the engine steps in onboarding order`() {
    val plans = PermissionSteps.plan(PermissionSteps.defaultFeatures, child = false, device = stock)
    assertEquals(listOf(StepId.overlay, StepId.usageAccess, StepId.notifications, StepId.deviceAdmin, StepId.batteryOptimization), ids(plans))
    assertEquals(listOf(StepId.overlay, StepId.usageAccess), required(plans))
    assertEquals(Kind.settings, plans.first { it.id == StepId.overlay }.kind)
    assertEquals(Kind.prompt, plans.first { it.id == StepId.notifications }.kind)
  }

  @Test
  fun `a child makes notifications and the device admin required`() {
    val plans = PermissionSteps.plan(PermissionSteps.defaultFeatures, child = true, device = stock)
    assertEquals(listOf(StepId.overlay, StepId.usageAccess, StepId.notifications, StepId.deviceAdmin), required(plans))
  }

  @Test
  fun `usage reports alone need usage access only`() {
    val plans = PermissionSteps.plan(listOf(Feature.usageReports), child = false, device = stock)
    assertEquals(listOf(StepId.usageAccess), ids(plans))
    assertTrue(plans.single().required)
  }

  @Test
  fun `app removal protection requires the device admin`() {
    val plans = PermissionSteps.plan(listOf(Feature.appRemovalProtection), child = false, device = stock)
    assertEquals(listOf(StepId.deviceAdmin), ids(plans))
    assertTrue(plans.single().required)
    val withBlocking = PermissionSteps.plan(listOf(Feature.blocking, Feature.appRemovalProtection), child = false, device = stock)
    assertTrue(withBlocking.first { it.id == StepId.deviceAdmin }.required)
  }

  @Test
  fun `shieldOpenApp adds nothing on Android, where openApp is direct`() {
    val plain = PermissionSteps.plan(listOf(Feature.blocking), child = false, device = stock)
    val withOpenApp = PermissionSteps.plan(listOf(Feature.blocking, Feature.shieldOpenApp), child = false, device = stock)
    assertEquals(plain, withOpenApp)
    assertFalse(withOpenApp.first { it.id == StepId.notifications }.required)
  }

  @Test
  fun `the notification step exists from Android 13, the vendor step where a screen exists`() {
    assertFalse(StepId.notifications in ids(PermissionSteps.plan(PermissionSteps.defaultFeatures, child = false, device = android12)))
    assertFalse(StepId.backgroundAutostart in ids(PermissionSteps.plan(PermissionSteps.defaultFeatures, child = false, device = stock)))
    val vendor = PermissionSteps.plan(PermissionSteps.defaultFeatures, child = false, device = xiaomi)
    assertEquals(StepId.backgroundAutostart, vendor.last().id)
    assertEquals(Kind.guide, vendor.last().kind)
    assertFalse(vendor.last().required)
  }

  @Test
  fun `features parse like iOS - default, unknown names dropped, order kept`() {
    assertEquals(PermissionSteps.defaultFeatures, PermissionSteps.features(null))
    assertEquals(PermissionSteps.defaultFeatures, PermissionSteps.features("blocking"))
    assertEquals(listOf(Feature.monitoring, Feature.blocking), PermissionSteps.features(listOf("monitoring", "bogus", "blocking", "monitoring")))
    assertEquals(emptyList<Feature>(), PermissionSteps.features(emptyList<String>()))
  }

  @Test
  fun `a requested step is classified`() {
    assertEquals(PermissionSteps.RequestedStep.Next, PermissionSteps.requestedStep(null))
    assertEquals(PermissionSteps.RequestedStep.Step(StepId.usageAccess), PermissionSteps.requestedStep("usageAccess"))
    assertEquals(PermissionSteps.RequestedStep.NotOnThisPlatform("screenTime"), PermissionSteps.requestedStep("screenTime"))
    assertEquals(PermissionSteps.RequestedStep.NotOnThisPlatform("accessibility"), PermissionSteps.requestedStep("accessibility"))
    assertEquals(PermissionSteps.RequestedStep.Unknown("usage"), PermissionSteps.requestedStep("usage"))
  }

  private fun step(id: StepId, status: Status, required: Boolean = true, canPrompt: Boolean = true) =
    Step(id, status, required, Kind.settings, null, canPrompt)

  @Test
  fun `granted iff every required step is granted`() {
    val response = PermissionSteps.response(listOf(
      step(StepId.overlay, Status.granted), step(StepId.usageAccess, Status.granted), step(StepId.batteryOptimization, Status.undetermined, required = false),
    ))
    assertEquals(true, response["granted"])
    assertEquals("granted", response["status"])
    assertEquals(true, response["canAskAgain"])
  }

  @Test
  fun `status is denied when a required step is denied or restricted, else undetermined`() {
    assertEquals("undetermined", PermissionSteps.response(listOf(step(StepId.overlay, Status.undetermined)))["status"])
    assertEquals("denied", PermissionSteps.response(listOf(step(StepId.overlay, Status.granted), step(StepId.usageAccess, Status.denied)))["status"])
    val restricted = PermissionSteps.response(listOf(step(StepId.usageAccess, Status.restricted, canPrompt = false)))
    assertEquals("denied", restricted["status"])
    assertEquals(false, restricted["canAskAgain"])
    // A recommended step never decides the top-level values.
    val recommendedDenied = PermissionSteps.response(listOf(step(StepId.overlay, Status.undetermined), step(StepId.deviceAdmin, Status.denied, required = false)))
    assertEquals("undetermined", recommendedDenied["status"])
  }

  @Test
  fun `canAskAgain is false once a pending required step cannot prompt`() {
    val blocked = PermissionSteps.response(listOf(step(StepId.notifications, Status.denied, canPrompt = false)))
    assertEquals(false, blocked["canAskAgain"])
    // ...but a granted step that could not prompt again does not matter.
    val granted = PermissionSteps.response(listOf(step(StepId.notifications, Status.granted, canPrompt = false)))
    assertEquals(true, granted["canAskAgain"])
  }

  @Test
  fun `the next step is the first required one not granted`() {
    val steps = listOf(
      step(StepId.overlay, Status.granted), step(StepId.usageAccess, Status.undetermined),
      step(StepId.notifications, Status.undetermined, required = false), step(StepId.deviceAdmin, Status.denied),
    )
    assertEquals(StepId.usageAccess, PermissionSteps.nextStep(steps))
    assertNull(PermissionSteps.nextStep(listOf(step(StepId.overlay, Status.granted), step(StepId.notifications, Status.denied, required = false))))
  }

  @Test
  fun `a step serializes as the contract's PermissionStep`() {
    val plain = step(StepId.overlay, Status.undetermined).toMap()
    assertEquals(mapOf("id" to "overlay", "status" to "undetermined", "required" to true, "kind" to "settings"), plain)
    val failed = Step(StepId.backgroundAutostart, Status.unknown, false, Kind.guide, PermissionSteps.StepError("ActivityNotFoundException", "no screen")).toMap()
    assertEquals(mapOf("code" to "ActivityNotFoundException", "message" to "no screen"), failed["error"])
  }
}
