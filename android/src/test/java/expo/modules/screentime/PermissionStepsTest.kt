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

  // The merged manifests of the builds the config plugin's `android.features` produces.
  private val usageReportsBuild = BuildManifest(engine = false, shield = false, deviceAdmin = false, usageAccess = true, notifications = false)
  private val monitoringBuild = BuildManifest(engine = true, shield = false, deviceAdmin = false, usageAccess = true, notifications = true)
  private val appRemovalBuild = BuildManifest(engine = false, shield = false, deviceAdmin = true, usageAccess = false, notifications = false)

  @Test
  fun `the full build plans as before the option existed`() {
    for (features in listOf(PermissionSteps.defaultFeatures, listOf(Feature.usageReports), listOf(Feature.monitoring), listOf(Feature.appRemovalProtection))) {
      assertEquals(PermissionSteps.plan(features, child = false, device = xiaomi), PermissionSteps.plan(features, child = false, device = xiaomi, build = BuildManifest.full))
    }
  }

  @Test
  fun `a usage-reports build asks for usage access only, whatever is requested`() {
    assertEquals(listOf(StepId.usageAccess), ids(PermissionSteps.plan(listOf(Feature.usageReports), child = false, device = xiaomi, build = usageReportsBuild)))
    // The module rejects a request for a feature the build lacks first; the plan still never lists a removed step.
    assertEquals(emptyList<StepId>(), ids(PermissionSteps.plan(PermissionSteps.defaultFeatures, child = true, device = xiaomi, build = usageReportsBuild)))
  }

  @Test
  fun `a monitoring-only build asks for the engine steps without the overlay or the device admin`() {
    val plans = PermissionSteps.plan(listOf(Feature.monitoring), child = false, device = xiaomi, build = monitoringBuild)
    assertEquals(listOf(StepId.usageAccess, StepId.notifications, StepId.batteryOptimization, StepId.backgroundAutostart), ids(plans))
    assertEquals(listOf(StepId.usageAccess), required(plans))
    // A child still requires the notice; the device admin cannot be required without its receiver.
    assertEquals(listOf(StepId.usageAccess, StepId.notifications), required(PermissionSteps.plan(listOf(Feature.monitoring), child = true, device = xiaomi, build = monitoringBuild)))
  }

  @Test
  fun `an app-removal build asks for the device admin only`() {
    val plans = PermissionSteps.plan(listOf(Feature.appRemovalProtection), child = false, device = stock, build = appRemovalBuild)
    assertEquals(listOf(StepId.deviceAdmin), ids(plans))
    assertTrue(plans.single().required)
  }

  @Test
  fun `features parse like iOS - default, unknown names dropped, order kept`() {
    assertEquals(PermissionSteps.defaultFeatures, PermissionSteps.features(null))
    assertEquals(PermissionSteps.defaultFeatures, PermissionSteps.features("blocking"))
    assertEquals(listOf(Feature.monitoring, Feature.blocking), PermissionSteps.features(listOf("monitoring", "bogus", "blocking", "monitoring")))
    assertEquals(emptyList<Feature>(), PermissionSteps.features(emptyList<String>()))
  }

  @Test
  fun `the accessibility step is listed only in a build that declares the service, for blocking, recommended, after usage access`() {
    val optedIn = BuildManifest.full.copy(accessibility = true)
    val plans = PermissionSteps.plan(PermissionSteps.defaultFeatures, child = false, device = stock, build = optedIn)
    assertEquals(listOf(StepId.overlay, StepId.usageAccess, StepId.accessibility, StepId.notifications, StepId.deviceAdmin, StepId.batteryOptimization), ids(plans))
    // Recommended, whatever the member: the main profile is enforced without it.
    assertEquals(listOf(StepId.overlay, StepId.usageAccess), required(plans))
    assertEquals(listOf(StepId.overlay, StepId.usageAccess, StepId.notifications, StepId.deviceAdmin), required(PermissionSteps.plan(PermissionSteps.defaultFeatures, child = true, device = stock, build = optedIn)))
    assertEquals(Kind.settings, plans.first { it.id == StepId.accessibility }.kind)
    // Not in the default build.
    assertFalse(StepId.accessibility in ids(PermissionSteps.plan(PermissionSteps.defaultFeatures, child = false, device = stock)))
    // Only the shield uses it: not for monitoring, usage reports or removal protection alone.
    assertFalse(StepId.accessibility in ids(PermissionSteps.plan(listOf(Feature.monitoring), child = false, device = stock, build = optedIn)))
    assertFalse(StepId.accessibility in ids(PermissionSteps.plan(listOf(Feature.usageReports), child = false, device = stock, build = optedIn)))
    assertFalse(StepId.accessibility in ids(PermissionSteps.plan(listOf(Feature.appRemovalProtection), child = false, device = stock, build = optedIn)))
    assertTrue(StepId.accessibility in ids(PermissionSteps.plan(listOf(Feature.blocking), child = false, device = stock, build = optedIn)))
    // A hand-trimmed manifest that kept the service but lost the shield has nothing for it to serve.
    assertFalse(StepId.accessibility in ids(PermissionSteps.plan(listOf(Feature.blocking), child = false, device = stock, build = optedIn.copy(shield = false))))
    assertFalse(StepId.accessibility in ids(PermissionSteps.plan(listOf(Feature.blocking), child = false, device = stock, build = optedIn.copy(engine = false))))
    // Android 12: no notification step, the coverage step still right after usage access.
    assertEquals(listOf(StepId.overlay, StepId.usageAccess, StepId.accessibility, StepId.deviceAdmin, StepId.batteryOptimization), ids(PermissionSteps.plan(PermissionSteps.defaultFeatures, child = false, device = android12, build = optedIn)))
  }

  @Test
  fun `a requested step is classified`() {
    assertEquals(PermissionSteps.RequestedStep.Next, PermissionSteps.requestedStep(null))
    assertEquals(PermissionSteps.RequestedStep.Step(StepId.usageAccess), PermissionSteps.requestedStep("usageAccess"))
    assertEquals(PermissionSteps.RequestedStep.NotOnThisPlatform("screenTime"), PermissionSteps.requestedStep("screenTime"))
    // The accessibility step exists only in a build that declares the service (today's INVALID_CONFIG elsewhere).
    assertEquals(PermissionSteps.RequestedStep.NotInBuild("accessibility"), PermissionSteps.requestedStep("accessibility"))
    assertEquals(PermissionSteps.RequestedStep.Step(StepId.accessibility), PermissionSteps.requestedStep("accessibility", BuildManifest.full.copy(accessibility = true)))
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
