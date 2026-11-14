package expo.modules.screentime

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import expo.modules.screentime.web.WebState

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

  private val vpnPlan = PermissionSteps.Plan(PermissionSteps.StepId.vpn, true, PermissionSteps.Kind.prompt)
  private fun response(document: JSONObject): Map<String, Any> =
    PermissionSteps.response(listOf(WebPermissionStep.read(vpnPlan, document)))
  private fun serializedStep(document: JSONObject): Map<*, *> =
    (response(document)["steps"] as List<*>).single() as Map<*, *>

  @Test fun cancelledOutcomeReachesPermissionResponse() {
    val document = JSONObject()
    WebState.event(document, WebState.Event.dialogCancelled, 100, 1, 0)
    assertEquals(mapOf("id" to "vpn", "status" to "denied", "required" to true,
      "kind" to "prompt", "detail" to "cancelled"), serializedStep(document))
    assertEquals(false, response(document)["granted"])
    assertEquals(true, response(document)["canAskAgain"])
  }

  @Test fun historicalCancellationDoesNotDescribeLaterOutcomes() {
    for (event in WebState.Event.values().filter { it != WebState.Event.dialogCancelled }) {
      val document = JSONObject()
      WebState.event(document, WebState.Event.dialogCancelled, 100, 1, 0)
      WebState.event(document, event, 200, 1, 0)
      assertEquals(if (event == WebState.Event.dialogGranted) "ok" else "cancelled",
        document.getJSONObject("web").getJSONObject("consent").getString("dialogResult"))
      assertFalse("$event must supersede the old cancellation", serializedStep(document).containsKey("detail"))
    }
  }

  @Test fun cancellationCanBeReportedAgainAfterExplicitGrant() {
    val document = JSONObject()
    WebState.event(document, WebState.Event.prepared, 100, 1, 0)
    WebState.event(document, WebState.Event.dialogCancelled, 200, 1, 0)
    assertEquals("cancelled", serializedStep(document)["detail"])
  }

  @Test fun readingCancellationDoesNotMutateConsentOrPrepareTheSlot() {
    val document = JSONObject()
    WebState.event(document, WebState.Event.dialogCancelled, 100, 1, 0)
    val before = document.toString()
    repeat(5) { assertEquals("cancelled", serializedStep(document)["detail"]) }
    assertEquals(before, document.toString())
  }

  @Test fun oldOrInconsistentRecordsDoNotInventCancellation() {
    val records = listOf("{}", "{\"web\":{\"consent\":{\"status\":\"denied\",\"dialogResult\":\"cancelled\"}}}",
      "{\"web\":{\"consent\":{\"status\":\"granted\",\"event\":\"dialogCancelled\"}}}")
    for (record in records) assertFalse(serializedStep(JSONObject(record)).containsKey("detail"))
  }

  @Test fun ordinaryPermissionResponseKeepsItsOriginalShape() {
    val step = PermissionSteps.Step(PermissionSteps.StepId.overlay, PermissionSteps.Status.denied, true, PermissionSteps.Kind.settings)
    assertEquals(mapOf("id" to "overlay", "status" to "denied", "required" to true, "kind" to "settings"), step.toMap())
  }

}
