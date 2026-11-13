package expo.modules.screentime

import org.junit.Assert.*
import org.junit.Test

class WebPermissionStepsTest {
  private val device = PermissionSteps.Device(37, false)
  private val web = listOf(PermissionSteps.Feature.webDomainBlocking)
  @Test fun absentPreviewDoesNotListVpn() { assertTrue(PermissionSteps.plan(web, false, device).isEmpty()) }
  @Test fun webOnlyNeedsVpnAlone() {
    val plan = PermissionSteps.plan(web, false, device, BuildManifest.full.copy(vpn = true))
    assertEquals(listOf(PermissionSteps.Plan(PermissionSteps.StepId.vpn, true, PermissionSteps.Kind.prompt)), plan)
  }
  @Test fun blockingAloneNeverNeedsVpn() {
    assertFalse(PermissionSteps.plan(listOf(PermissionSteps.Feature.blocking), false, device, BuildManifest.full.copy(vpn = true)).any { it.id == PermissionSteps.StepId.vpn })
  }
  @Test fun explicitVpnWithoutPreviewRemainsUnknown() {
    assertTrue(PermissionSteps.requestedStep("vpn") is PermissionSteps.RequestedStep.Unknown)
  }
  @Test fun explicitVpnInPreviewIsAvailable() {
    assertEquals(PermissionSteps.RequestedStep.Step(PermissionSteps.StepId.vpn), PermissionSteps.requestedStep("vpn", BuildManifest.full.copy(vpn = true)))
  }
  @Test fun vpnParticipatesInRequiredDerivation() {
    val step = PermissionSteps.Step(PermissionSteps.StepId.vpn, PermissionSteps.Status.undetermined, true, PermissionSteps.Kind.prompt)
    assertEquals(false, PermissionSteps.response(listOf(step))["granted"])
    assertEquals(true, PermissionSteps.response(listOf(step.copy(status = PermissionSteps.Status.granted)))["granted"])
    assertEquals(false, PermissionSteps.response(listOf(step.copy(status = PermissionSteps.Status.restricted)))["canAskAgain"])
  }
}
