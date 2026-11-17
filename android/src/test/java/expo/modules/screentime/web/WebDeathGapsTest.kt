package expo.modules.screentime.web

import expo.modules.screentime.ExitRecord
import expo.modules.screentime.ExitReasons
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebDeathGapsTest {
  private val process = "test:screentime"
  private fun running() = JSONObject().also {
    WebState.setDomains(it, setOf("example.com"), 100, 1, 0)
    WebState.started(it, 200, 42, 1, 0)
  }
  @Test fun exitClassifiesAndRecoveryClosesGap() {
    val document = running()
    val exits = listOf(ExitRecord(ExitReasons.USER_REQUESTED, 300, 42, process, "user stopped"))
    repeat(2) { WebState.observeRun(document, 500, 1, 0, false, exits, process) }
    val gap = WebState.gaps(document, 250).single()
    assertEquals(300L, gap["from"])
    assertEquals("stoppedByUser", gap["reason"])
    assertEquals("webDomains", gap["scope"])
    WebState.started(document, 600, 43, 1, 0)
    assertEquals(600L, WebState.gaps(document, 250).single()["to"])
  }
  @Test fun delayedExitRefinesAnOpenHeartbeatEstimate() {
    val document = running()
    WebState.observeRun(document, 400, 1, 0, false, null, process)
    WebState.observeRun(document, 500, 1, 0, false,
      listOf(ExitRecord(ExitReasons.USER_REQUESTED, 300, 42, process, "user stop")), process)
    val gap = WebState.gaps(document, 250).single()
    assertEquals(300L, gap["from"])
    assertEquals("stoppedByUser", gap["reason"])
  }
  @Test fun sleepingLiveProcessDoesNotCreateGap() {
    val document = running()
    WebState.observeRun(document, 30_000_000, 1, 0, true, null, process)
    assertTrue(WebState.gaps(document, 250).isEmpty())
  }
  @Test fun missingExitHistoryUsesLastHeartbeatAndClipsToIntent() {
    val document = running()
    WebState.record(document).getJSONObject("run").put("heartbeat", 300)
    WebState.observeRun(document, 500, 1, 0, false, null, process)
    assertEquals(300L, WebState.gaps(document, 250).single()["from"])
    WebState.setDomains(document, emptySet(), 600, 1, 0)
    assertEquals(600L, WebState.gaps(document, 250).single()["to"])
    WebState.observeRun(document, 800, 1, 0, false, null, process)
    assertFalse(WebState.gaps(document, null).any { it["to"] == null })
  }
  @Test fun olderOrForeignExitDoesNotKillCurrentRun() {
    val document = running()
    val exits = listOf(ExitRecord(ExitReasons.CRASH, 199, 42, process, null),
      ExitRecord(ExitReasons.CRASH, 300, 42, "test", null))
    WebState.observeRun(document, 500, 1, 0, true, exits, process)
    assertTrue(WebState.gaps(document, 250).isEmpty())
  }
  @Test fun rebootNeverUsesOldPidExit() {
    val document = running()
    WebState.observeRun(document, 800, 2, 600, false,
      listOf(ExitRecord(ExitReasons.USER_REQUESTED, 300, 42, process, null)), process)
    val gap = WebState.gaps(document, 250).single()
    assertEquals(600L, gap["from"])
    assertEquals("unknown", gap["reason"])
  }
}
