package expo.modules.screentime.web

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebStateTest {
  private fun wanted(): JSONObject = JSONObject().also { WebState.setDomains(it, setOf("example.com"), 100, 1, 0) }
  private fun grant(document: JSONObject, at: Long = 200, boot: Int = 1) = WebState.event(document, WebState.Event.prepared, at, boot, 0)
  private fun started(): JSONObject = wanted().also { WebState.started(it, 200, 42, 1, 0) }

  @Test fun emptyDocumentIsUndetermined() { assertEquals("undetermined", WebState.consent(JSONObject()).status) }
  @Test fun malformedStatusFailsClosed() {
    val document = JSONObject("""{"web":{"consent":{"status":"bogus","preparedBootCount":1}}}""")
    assertEquals(WebState.Start.waitForConsent, WebState.consent(document).start(true, false, 1))
  }
  @Test fun establishExceptionKeepsConsentButRecordsOutcome() {
    val document = started()
    WebState.event(document, WebState.Event.establishFailed, 300, 1, 0)
    assertEquals(WebState.Consent("granted", 1), WebState.consent(document))
    assertEquals("exception", document.getJSONObject("web").getJSONObject("consent").getString("establishOutcome"))
  }
  @Test fun pairSwapPreservesRunStart() {
    val document = started()
    WebState.started(document, 300, 42, 1, 0)
    val run = document.getJSONObject("web").getJSONObject("run")
    assertEquals(200, run.getLong("start"))
    assertEquals(300, run.getLong("heartbeat"))
  }
  @Test fun oldServiceCleanupCannotHideSuccessorFromUnblock() {
    val document = wanted()
    WebState.claimService(document, "old")
    WebState.started(document, 200, 42, 1, 0)
    WebState.claimService(document, "new")
    WebState.started(document, 300, 42, 1, 0)
    val beforeCleanup = document.toString()
    assertFalse(WebState.stopped(document, "old"))
    assertEquals(beforeCleanup, document.toString())
    WebState.setDomains(document, emptySet(), 400, 1, 0)
    assertEquals(WebState.Start.stop, WebState.consent(document).start(
      WebState.desired(document), WebState.record(document).getBoolean("running"), 1))
  }
  @Test fun consecutiveInstancesInSameProcessHaveDifferentRunStarts() {
    val document = wanted()
    WebState.claimService(document, "old")
    WebState.started(document, 200, 42, 1, 0)
    WebState.claimService(document, "new")
    assertFalse(WebState.ownsService(document, "old"))
    WebState.started(document, 300, 42, 1, 0)
    assertEquals(300, WebState.record(document).getJSONObject("run").getLong("start"))
    assertTrue(WebState.stopped(document, "new"))
    assertFalse(WebState.record(document).getBoolean("running"))
  }
  @Test fun oldServiceCannotStopSuccessorBeforeItsFirstTunnel() {
    val document = started()
    WebState.claimService(document, "new")
    assertFalse(WebState.stopped(document, "old"))
    assertTrue(WebState.ownsService(document, "new"))
    assertFalse(WebState.record(document).getBoolean("running"))
  }
  @Test fun emptyDocumentIsNotDesired() { assertFalse(WebState.desired(JSONObject())) }
  @Test fun domainIdentityIsExactAndDuplicatesCollapse() {
    val document = JSONObject()
    WebState.setDomains(document, setOf("Example.com", "example.com", "https://x", "example.com"), 1, 1, 0)
    assertEquals(setOf("Example.com", "example.com", "https://x"), WebState.domains(document))
  }
  @Test fun domainSubtractionIsExact() {
    val document = wanted()
    WebState.setDomains(document, WebState.domains(document) - "Example.com", 200, 1, 0)
    assertEquals(setOf("example.com"), WebState.domains(document))
  }
  @Test fun desiredTracksTheSet() {
    val document = wanted()
    assertTrue(document.getJSONObject("web").getBoolean("desired"))
    WebState.setDomains(document, emptySet(), 200, 1, 0)
    assertFalse(document.getJSONObject("web").getBoolean("desired"))
  }
  @Test fun dialogOkGrantsAndRecordsBoot() {
    val document = wanted()
    WebState.event(document, WebState.Event.dialogGranted, 200, 3, 0)
    assertEquals(WebState.Consent("granted", 3), WebState.consent(document))
    assertEquals("ok", document.getJSONObject("web").getJSONObject("consent").getString("dialogResult"))
  }
  @Test fun cancelledDialogRecordsDenied() {
    val document = wanted()
    WebState.event(document, WebState.Event.dialogCancelled, 200, 1, 0)
    assertEquals("denied", WebState.consent(document).status)
    assertEquals("cancelled", document.getJSONObject("web").getJSONObject("consent").getString("dialogResult"))
  }
  @Test fun restrictedActivityRecordsRestricted() {
    assertEquals(WebState.Consent("restricted"), WebState.Consent().transition(WebState.Event.restricted, 1))
  }
  @Test fun nullPrepareRecordsGrant() {
    val document = wanted(); grant(document)
    assertEquals(200, document.getJSONObject("web").getJSONObject("consent").getLong("prepareReturnedNullAt"))
    assertEquals("granted", WebState.consent(document).status)
  }
  @Test fun establishDescriptorRecordsGrant() {
    val document = started()
    assertEquals("descriptor", document.getJSONObject("web").getJSONObject("consent").getString("establishOutcome"))
  }
  @Test fun nullEstablishRemovesPreparedState() {
    val document = started()
    WebState.event(document, WebState.Event.establishDenied, 300, 1, 0)
    assertEquals(WebState.Consent("denied"), WebState.consent(document))
  }
  @Test fun revokePreservesDesiredAndRecordsInstant() {
    val document = started()
    WebState.event(document, WebState.Event.displaced, 300, 1, 0)
    assertTrue(WebState.desired(document))
    assertEquals(300, document.getJSONObject("web").getJSONObject("consent").getLong("revokedAt"))
  }
  @Test fun laterGrantKeepsLastRevokeAndDialogEvidence() {
    val document = wanted()
    WebState.event(document, WebState.Event.dialogGranted, 200, 1, 0)
    WebState.event(document, WebState.Event.displaced, 300, 1, 0)
    grant(document, 400)
    val consent = document.getJSONObject("web").getJSONObject("consent")
    assertEquals(300, consent.getLong("revokedAt"))
    assertEquals("ok", consent.getString("dialogResult"))
  }
  @Test fun startWithoutDomainsIsIdle() { assertEquals(WebState.Start.idle, WebState.Consent().start(false, false, 1)) }
  @Test fun releaseOfRunningServiceStopsIt() { assertEquals(WebState.Start.stop, WebState.Consent().start(false, true, 1)) }
  @Test fun neverAskedWaitsForConsent() { assertEquals(WebState.Start.waitForConsent, WebState.Consent().start(true, false, 1)) }
  @Test fun displacedNeverReclaims() { assertEquals(WebState.Start.waitForConsent, WebState.Consent("denied").start(true, false, 1)) }
  @Test fun revokedConsentStopsAnAlreadyRunningFilter() {
    for (status in listOf("undetermined", "denied", "restricted")) {
      assertEquals(WebState.Start.stop, WebState.Consent(status).start(true, true, 1))
    }
  }
  @Test fun sameBootCanRestartWithoutPrepare() { assertEquals(WebState.Start.establish, WebState.Consent("granted", 1).start(true, false, 1)) }
  @Test fun differentBootWaitsForBootPolicy() { assertEquals(WebState.Start.waitForBoot, WebState.Consent("granted", 1).start(true, false, 2)) }
  @Test fun unreadableBootDoesNotClaimPrepared() { assertEquals(WebState.Start.waitForBoot, WebState.Consent("granted").start(true, false, null)) }
  @Test fun rebootDoesNotRevokeConsent() {
    val document = started(); WebState.observe(document, 600, 2, 500)
    assertEquals("granted", WebState.consent(document).status)
  }
  @Test fun defaultBootPolicyIsWhenNoVpn() { assertEquals(WebState.BootPolicy.whenNoVpn, WebState.bootPolicy(JSONObject())) }
  @Test fun invalidStoredBootPolicyUsesDefault() {
    val document = JSONObject(); WebState.record(document).put("bootPolicy", "bad")
    assertEquals(WebState.BootPolicy.whenNoVpn, WebState.bootPolicy(document))
  }
  @Test fun neverPolicyDoesNotPrepare() { assertFalse(bootDecision(WebState.BootPolicy.never, false)) }
  @Test fun whenNoVpnPreparesAfterObservation() { assertTrue(bootDecision(WebState.BootPolicy.whenNoVpn, false)) }
  @Test fun whenNoVpnDoesNotDisplaceObservedVpn() { assertFalse(bootDecision(WebState.BootPolicy.whenNoVpn, true)) }
  @Test fun alwaysPolicyCanPrepareWithVpn() { assertTrue(bootDecision(WebState.BootPolicy.always, true)) }
  @Test fun failedObservationNeverMeansNoVpn() {
    assertFalse(WebState.shouldPrepareAtBoot(WebState.BootPolicy.whenNoVpn, WebState.Consent("granted", 1), true, 2, false, false))
  }
  @Test fun bootNeverPreparesUndeterminedUnderAnyPolicy() {
    assertBootNeverPrepares(WebState.Consent())
    assertEquals(WebState.Start.waitForConsent, WebState.Consent().start(true, false, 2))
  }
  @Test fun bootNeverPreparesDeniedUnderAnyPolicy() { assertBootNeverPrepares(WebState.Consent("denied")) }
  @Test fun bootNeverPreparesRestrictedUnderAnyPolicy() { assertBootNeverPrepares(WebState.Consent("restricted")) }
  @Test fun bootNeverReclaimsAfterRevocationCancellationOrNullEstablish() {
    for (event in listOf(WebState.Event.displaced, WebState.Event.dialogCancelled, WebState.Event.establishDenied)) {
      val document = started()
      WebState.event(document, event, 300, 1, 0)
      assertBootNeverPrepares(WebState.consent(document))
    }
  }
  @Test fun bootDoesNotPrepareWithoutDomains() {
    assertFalse(WebState.shouldPrepareAtBoot(WebState.BootPolicy.always, WebState.Consent("granted", 1), false, 2, false, true))
  }
  @Test fun bootDoesNotReclaimSameBoot() {
    assertFalse(WebState.shouldPrepareAtBoot(WebState.BootPolicy.always, WebState.Consent("granted", 2), true, 2, true, true))
  }
  @Test fun noGapWhenNotWanted() {
    val document = JSONObject(); WebState.event(document, WebState.Event.displaced, 100, 1, 0)
    assertTrue(WebState.gaps(document, null).isEmpty())
  }
  @Test fun consentGapStartsAtFirstBlock() {
    assertEquals(mapOf("from" to 100L, "to" to null, "reason" to "permissionRevoked", "scope" to "webDomains"), WebState.gaps(wanted(), null).single())
  }
  @Test fun observationsDoNotDuplicateAnOpenGap() {
    val document = wanted(); repeat(10) { WebState.observe(document, 200L + it, 1, 0) }
    assertEquals(1, WebState.gaps(document, null).size)
  }
  @Test fun consentGrantClosesGap() {
    val document = wanted(); grant(document)
    assertEquals(200L, WebState.gaps(document, null).single()["to"])
  }
  @Test fun revokeGapStartsAtCallbackNotHeartbeat() {
    val document = started(); WebState.event(document, WebState.Event.displaced, 333, 1, 0)
    assertEquals(333L, WebState.gaps(document, 300).single()["from"])
  }
  @Test fun nullEstablishOpensConsentGap() {
    val document = started(); WebState.event(document, WebState.Event.establishDenied, 333, 1, 0)
    assertEquals("permissionRevoked", WebState.gaps(document, 300).single()["reason"])
  }
  @Test fun rebootGapStartsAtBootAndIsUnknown() {
    val document = started(); WebState.observe(document, 700, 2, 500)
    val gap = WebState.gaps(document, 400).single()
    assertEquals(500L, gap["from"]); assertEquals("unknown", gap["reason"]); assertNull(gap["to"])
    assertTrue(document.getJSONObject("web").getJSONArray("gaps").getJSONObject(1).getString("detail").startsWith("reboot:"))
  }
  @Test fun rebootGapClosesOnlyWhenTunnelIsUp() {
    val document = started(); WebState.observe(document, 700, 2, 500); grant(document, 800, 2)
    assertNull(WebState.gaps(document, 400).single()["to"])
    WebState.started(document, 900, 43, 2, 500)
    assertEquals(900L, WebState.gaps(document, 400).single()["to"])
  }
  @Test fun firstBlockAfterRebootRecordsGapEvenWithoutPreviousTunnel() {
    val document = JSONObject()
    grant(document)
    WebState.setDomains(document, setOf("example.com"), 700, 2, 500)
    val gap = WebState.gaps(document, null).single()
    assertEquals(700L, gap["from"])
    assertEquals("unknown", gap["reason"])
    assertNull(gap["to"])
    assertEquals(WebState.Start.waitForBoot, WebState.consent(document).start(true, false, 2))
    grant(document, 800, 2)
    assertNull(WebState.gaps(document, null).single()["to"])
    WebState.started(document, 900, 42, 2, 500)
    assertEquals(900L, WebState.gaps(document, null).single()["to"])
  }
  @Test fun unusedConsentDoesNotCreateGapInSameBootOrWhileNothingIsBlocked() {
    val document = JSONObject()
    grant(document)
    WebState.observe(document, 700, 2, 500)
    assertTrue(WebState.gaps(document, null).isEmpty())
    WebState.setDomains(document, setOf("example.com"), 800, 1, 0)
    assertTrue(WebState.gaps(document, null).isEmpty())
  }
  @Test fun unknownCurrentBootReportsUncertaintyWithoutInventingReboot() {
    val document = JSONObject()
    grant(document)
    WebState.setDomains(document, setOf("example.com"), 700, null, 0)
    val gap = WebState.gaps(document, null).single()
    assertEquals(700L, gap["from"])
    assertEquals("unknown", gap["reason"])
    assertNull(gap["to"])
    assertEquals("bootUnavailable", document.getJSONObject("web").getJSONArray("gaps").getJSONObject(0).getString("kind"))
    WebState.observe(document, 800, null, 0)
    assertEquals(1, WebState.gaps(document, null).size)
    WebState.started(document, 900, 42, 1, 0)
    assertEquals(900L, WebState.gaps(document, null).single()["to"])
  }
  @Test fun emptyListClosesOpenGapAndReblockClipsIt() {
    val document = wanted(); WebState.setDomains(document, emptySet(), 200, 1, 0)
    WebState.observe(document, 300, 1, 0)
    WebState.setDomains(document, setOf("x"), 400, 1, 0)
    val gaps = WebState.gaps(document, null)
    assertEquals(200L, gaps[0]["to"]); assertEquals(400L, gaps[1]["from"])
  }
  @Test fun sinceClipsAndOmitsEndedGaps() {
    val document = wanted(); grant(document)
    assertEquals(150L, WebState.gaps(document, 150).single()["from"])
    assertTrue(WebState.gaps(document, 200).isEmpty())
  }
  @Test fun refusingStartOnlyRecordsWhileWanted() {
    val document = JSONObject(); WebState.refused(document, 50, "refused")
    assertTrue(WebState.gaps(document, null).isEmpty())
    WebState.setDomains(document, setOf("x"), 100, 1, 0); grant(document)
    WebState.refused(document, 300, "refused")
    assertEquals("unknown", WebState.gaps(document, 250).single()["reason"])
  }
  @Test fun runContainsIndependentEvidence() {
    val document = started(); val run = document.getJSONObject("web").getJSONObject("run")
    assertEquals(200, run.getLong("start")); assertEquals(200, run.getLong("heartbeat"))
    assertEquals(42, run.getInt("pid")); assertEquals(1, run.getInt("bootCount")); assertFalse(document.has("gaps"))
  }
  @Test fun oldDocumentAndEngineKeysSurviveRoundTrip() {
    val document = JSONObject("{\"version\":1,\"engine\":{\"desired\":true},\"custom\":42}")
    WebState.setDomains(document, setOf("x"), 100, 1, 0)
    val reloaded = JSONObject(document.toString())
    assertTrue(reloaded.getJSONObject("engine").getBoolean("desired")); assertEquals(42, reloaded.getInt("custom"))
    assertEquals(setOf("x"), WebState.domains(reloaded))
  }
  private fun bootDecision(policy: WebState.BootPolicy, vpn: Boolean) =
    WebState.shouldPrepareAtBoot(policy, WebState.Consent("granted", 1), true, 2, vpn, true)
  private fun assertBootNeverPrepares(consent: WebState.Consent) {
    for (policy in WebState.BootPolicy.values()) for (vpnSeen in listOf(false, true)) {
      assertFalse("$policy status=${consent.status} vpnSeen=$vpnSeen",
        WebState.shouldPrepareAtBoot(policy, consent, true, 2, vpnSeen, true))
    }
  }
}
