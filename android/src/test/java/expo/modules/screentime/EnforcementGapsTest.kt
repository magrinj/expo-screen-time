package expo.modules.screentime

import expo.modules.screentime.EnforcementGap.Reason
import expo.modules.screentime.EnforcementGap.Source
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * The iOS `AuthorizationGapTracker` section of `scripts/swift-tests/main.swift`
 * ("AuthorizationGapTracker — authorization revocations"), check by check
 * with the same inputs and expected outputs, read through
 * [EnforcementGapTracker.observePermissions]. Two Android specifics: the
 * reason is `permissionRevoked` (the revocable thing is a settings step,
 * not the Screen Time authorization), and a gap needs an intent to
 * enforce, so the flow runs inside an engine run started at 0 (iOS cannot
 * tell whether a block is intended; Android can, and bounds the gap by it).
 */
class EnforcementGapsSwiftParityTest {
  private val reason = Reason.permissionRevoked

  /** The Swift flow up to and including the observation at [upTo]. */
  private fun flow(upTo: Long): EnforcementGapTracker {
    var tracker = EnforcementGapTracker().engineStarted(0, pid = 1, exits = emptyList(), engineProcess = "p:screentime", bootTime = null)
    val steps = listOf(1_000L to false, 2_000L to true, 3_000L to true, 5_000L to false, 6_000L to false, 7_000L to true, 8_000L to false)
    for ((at, approved) in steps) {
      if (at > upTo) break
      tracker = tracker.observePermissions(approved, at)
    }
    return tracker
  }

  private fun List<EnforcementGap>.shapes() = map { Triple(it.from, it.to, it.reason) }

  @Test
  fun `never having had access is not a gap`() {
    assertTrue(flow(1_000).gaps.isEmpty())
  }

  @Test
  fun `access held - no gap, last grant moves forward`() {
    val tracker = flow(3_000)
    assertEquals(3_000L, tracker.lastApprovedAt)
    assertTrue(tracker.gaps.isEmpty())
  }

  @Test
  fun `a revocation opens a gap at the last observed grant`() {
    assertEquals(listOf(Triple(3_000L, null, reason)), flow(5_000).gaps.shapes())
  }

  @Test
  fun `further observations without access keep the one open gap`() {
    assertEquals(1, flow(6_000).gaps.size)
  }

  @Test
  fun `an open gap is in progress at any later time`() {
    assertEquals(1, flow(6_000).gaps(since = 9_000).size)
  }

  @Test
  fun `access back closes the gap at that observation`() {
    assertEquals(listOf(Triple(3_000L, 7_000L, reason)), flow(7_000).gaps.shapes())
  }

  @Test
  fun `since keeps the gaps ending at or after it`() {
    val tracker = flow(7_000)
    assertEquals(1, tracker.gaps(since = 7_000).size)
    assertTrue(tracker.gaps(since = 7_001).isEmpty())
  }

  @Test
  fun `no since returns every gap`() {
    assertEquals(1, flow(7_000).gaps(since = null).size)
  }

  @Test
  fun `a second revocation opens a second gap`() {
    val tracker = flow(8_000)
    assertEquals(2, tracker.gaps.size)
    assertEquals(7_000L, tracker.gaps[1].from)
    assertTrue(tracker.hasOpenPermissionGap)
  }

  @Test
  fun `past the cap the oldest gaps are dropped`() {
    var capped = EnforcementGapTracker().engineStarted(0, 1, emptyList(), "p:screentime", null)
    for (i in 0 until EnforcementGapTracker.MAX_GAPS + 5) {
      capped = capped.observePermissions(true, i * 10L)
      capped = capped.observePermissions(false, i * 10L + 5)
    }
    assertEquals(EnforcementGapTracker.MAX_GAPS, capped.gaps.size)
    assertEquals(50L, capped.gaps.first().from)
  }

  @Test
  fun `the tracker survives a JSON round trip (persisted in the state store)`() {
    val tracker = flow(8_000)
    assertEquals(tracker, EnforcementGapTracker.fromJson(JSONObject(tracker.toJson().toString())))
  }

  @Test
  fun `an open gap reaches JS with to null`() {
    val payload = EnforcementGap(1, null, reason, Source.permission).toMap()
    assertTrue(payload.containsKey("to"))
    assertNull(payload["to"])
    assertEquals(1L, payload["from"])
    assertEquals("permissionRevoked", payload["reason"])
  }
}

/**
 * What Android adds: exit records, the heartbeat, the engine's life, the
 * merge and retention rules, the codec, and the state-store seam. Fixtures
 * are the Pixel 8a measurements (the device tests
 * and E, and their `dumpsys activity exit-info`): the survival run started
 * at 2026-10-30T22:04:27Z with a heartbeat every 60 s and died at
 * 01:27:11.654Z, 3 h 22 later (a crash, pid 23197, then the refused sticky
 * restart's pid 5825 crashing twice); the tamper runs left `USER REQUESTED`
 * records with the `STOP APP` and `FORCE STOP` descriptions.
 */
class EnforcementGapsAndroidTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val engine = "com.example.app:screentime"
  private val app = "com.example.app"

  private val runStart = Instant.parse("2026-10-30T22:04:27Z").toEpochMilli()
  private val death = Instant.parse("2026-10-31T01:27:11.654Z").toEpochMilli()
  private val restart = Instant.parse("2026-10-31T04:27:11Z").toEpochMilli()
  private val minute = EnforcementGapTracker.HEARTBEAT_INTERVAL_MS
  private val bootBefore = runStart - 3_600_000

  // The records as the Pixel reported them (process name adapted to the module's engine process).
  private fun stopApp(at: Long, pid: Int) =
    ExitRecord(ExitReasons.USER_REQUESTED, at, pid, engine, "fully stop $app/0 by user request")
  private fun forceStop(at: Long, pid: Int) =
    ExitRecord(ExitReasons.USER_REQUESTED, at, pid, engine, "stop $app due to from pid 22737")
  private fun crash(at: Long, pid: Int, process: String = engine) =
    ExitRecord(ExitReasons.CRASH, at, pid, process, "crash")

  /** A run with heartbeats every minute from [from] until the last tick before [until]. */
  private fun running(from: Long, pid: Int, until: Long, permissionsApproved: Boolean? = null): EnforcementGapTracker {
    var tracker = EnforcementGapTracker().engineStarted(from, pid, emptyList(), engine, bootTime = bootBefore)
    var at = from + minute
    while (at < until) {
      tracker = tracker.heartbeat(at, pid, permissionsApproved)
      at += minute
    }
    return tracker
  }

  private fun List<EnforcementGap>.shapes() = map { Triple(it.from, it.to, it.reason) }
  private fun EnforcementGap.shape() = Triple(from, to, reason)

  // MARK: - Exit records

  @Test
  fun `every exit reason maps to a contract reason`() {
    val expected = mapOf(
      ExitReasons.UNKNOWN to Reason.unknown,
      ExitReasons.EXIT_SELF to Reason.unknown,
      ExitReasons.SIGNALED to Reason.killedBySystem,
      ExitReasons.LOW_MEMORY to Reason.killedBySystem,
      ExitReasons.CRASH to Reason.unknown,
      ExitReasons.CRASH_NATIVE to Reason.unknown,
      ExitReasons.ANR to Reason.unknown,
      ExitReasons.INITIALIZATION_FAILURE to Reason.unknown,
      ExitReasons.PERMISSION_CHANGE to Reason.permissionRevoked,
      ExitReasons.EXCESSIVE_RESOURCE_USAGE to Reason.killedBySystem,
      ExitReasons.USER_REQUESTED to Reason.stoppedByUser,
      ExitReasons.USER_STOPPED to Reason.unknown,
      ExitReasons.DEPENDENCY_DIED to Reason.killedBySystem,
      ExitReasons.OTHER to Reason.killedBySystem,
      ExitReasons.FREEZER to Reason.killedBySystem,
      ExitReasons.PACKAGE_STATE_CHANGE to Reason.unknown,
      ExitReasons.PACKAGE_UPDATED to Reason.unknown,
      99 to Reason.unknown,
    )
    for ((code, reason) in expected) assertEquals("reason $code", reason, ExitReasons.reason(code))
    assertEquals("USER REQUESTED (10): fully stop $app/0 by user request", ExitReasons.detail(stopApp(1, 1)))
    assertEquals("REASON_99 (99): no description", ExitReasons.detail(ExitRecord(99, 1, 1, engine, null)))
  }

  @Test
  fun `a USER_REQUESTED exit followed by a restart 3 h later is one stoppedByUser gap (run-1 timing, tamper-run record)`() {
    val before = running(runStart, pid = 16904, until = death)
    assertTrue(before.gaps.isEmpty())
    // The app opens first and looks at the gaps: the death is already a gap, still open.
    val seen = before.recordExits(listOf(stopApp(death, 16904)), engine, now = death + 2 * minute)
    assertEquals(listOf(Triple(death, null, Reason.stoppedByUser)), seen.gaps.shapes())
    assertEquals(Source.exit, seen.gaps.single().source)
    assertEquals("USER REQUESTED (10): fully stop $app/0 by user request", seen.gaps.single().detail)
    // Then the engine comes back 3 h later, in a new process, and sees the same history.
    val after = seen.engineStarted(restart, pid = 17300, exits = listOf(stopApp(death, 16904)), engineProcess = engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(death, restart, Reason.stoppedByUser)), after.gaps.shapes())
    assertEquals(EnforcementGapTracker.EngineRun(restart, 17300, restart, null), after.run)
    // And the restart alone, without the app having looked, gives the same gap.
    val direct = before.engineStarted(restart, 17300, listOf(stopApp(death, 16904)), engine, bootTime = bootBefore)
    assertEquals(after.gaps, direct.gaps)
  }

  @Test
  fun `the run-1 death as recorded - a crash, then the refused restart crashing twice with one pid - is one unknown gap`() {
    val tracker = running(runStart, pid = 23197, until = death)
    val history = listOf(
      crash(Instant.parse("2026-10-31T01:32:13.812Z").toEpochMilli(), 5825),
      crash(Instant.parse("2026-10-31T01:27:13.782Z").toEpochMilli(), 5825),
      crash(death, 23197),
    )
    val seen = tracker.recordExits(history, engine, death + 10 * minute)
    assertEquals(listOf(Triple(death, null, Reason.unknown)), seen.gaps.shapes())
    assertEquals("APP CRASH(EXCEPTION) (4): crash", seen.gaps.single().detail)
    assertEquals(history.map { it.key }.toSet(), seen.seenExits)
    val back = seen.engineStarted(restart, 6000, history, engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(death, restart, Reason.unknown)), back.gaps.shapes())
  }

  @Test
  fun `a force stop is stoppedByUser, a crash is unknown, and only the engine process counts`() {
    val tracker = running(runStart, pid = 100, until = runStart + 10 * minute)
    val forced = tracker.recordExits(listOf(forceStop(runStart + 10 * minute, 100), crash(runStart + 5 * minute, 7, process = app)), engine, runStart + 11 * minute)
    assertEquals(listOf(Triple(runStart + 10 * minute, null, Reason.stoppedByUser)), forced.gaps.shapes())
    assertEquals("USER REQUESTED (10): stop $app due to from pid 22737", forced.gaps.single().detail)
    assertEquals(setOf(forceStop(runStart + 10 * minute, 100).key), forced.seenExits)

    val crashed = tracker.recordExits(listOf(crash(runStart + 10 * minute, 100)), engine, runStart + 11 * minute)
    assertEquals(listOf(Triple(runStart + 10 * minute, null, Reason.unknown)), crashed.gaps.shapes())
  }

  @Test
  fun `duplicate exit records across calls are folded once`() {
    val tracker = running(runStart, pid = 100, until = death)
    val history = listOf(stopApp(death, 100), crash(runStart - 5 * minute, 90))
    val once = tracker.recordExits(history, engine, death + minute)
    val twice = once.recordExits(history, engine, death + 2 * minute)
    assertEquals(once.gaps, twice.gaps)
    assertEquals(history.map { it.key }.toSet(), twice.seenExits)
    // A restart that sees the history a third time: still the one gap, now closed.
    val restarted = twice.engineStarted(restart, 101, history, engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(death, restart, Reason.stoppedByUser)), restarted.gaps.shapes())
    // The history includes the same records on the next death, plus a new one.
    val again = restarted.recordExits(listOf(stopApp(restart + 5 * minute, 101)) + history, engine, restart + 6 * minute)
    assertEquals(2, again.gaps.size)
    assertEquals(Triple(restart + 5 * minute, null, Reason.stoppedByUser), again.gaps.last().shape())
    // The system forgets the oldest record: the seen set follows, and the gap stays.
    val pruned = again.recordExits(listOf(stopApp(restart + 5 * minute, 101), stopApp(death, 100)), engine, restart + 7 * minute)
    assertEquals(again.gaps, pruned.gaps)
    assertEquals(2, pruned.seenExits.size)
    // An empty history (a failed read) forgets nothing.
    assertEquals(pruned.seenExits, pruned.recordExits(emptyList(), engine, restart + 8 * minute).seenExits)
  }

  @Test
  fun `a record stamped before an earlier one (the clock stepped back) is still new`() {
    val tracker = running(runStart, pid = 100, until = death)
    val seen = tracker.recordExits(listOf(stopApp(death, 100)), engine, death + minute)
      .engineStarted(restart, 101, listOf(stopApp(death, 100)), engine, bootTime = bootBefore)
    // The clock steps back 2 h; the new process dies at a timestamp before the previous record.
    val stepped = restart - 2 * 60 * minute
    val folded = seen.recordExits(listOf(stopApp(stepped, 101), stopApp(death, 100)), engine, stepped + minute)
    // It is the run's death (same pid), its own open gap: gaps of different
    // runs never merge, so the earlier closed gap it now appears to overlap
    // keeps its record.
    assertEquals(listOf(Triple(death, restart, Reason.stoppedByUser), Triple(stepped, null, Reason.stoppedByUser)), folded.gaps.shapes())
    assertEquals(listOf(runStart, restart), folded.gaps.map { it.run })
  }

  @Test
  fun `records from before the run, or from a process that never started the engine, are not gaps`() {
    val tracker = running(runStart, pid = 100, until = runStart + 10 * minute)
    // History predating the run (an earlier install, an earlier release), and
    // a short-lived process 101 whose promotion was refused
    // (ForegroundServiceStartNotAllowedException) and that left
    // without ever starting the engine.
    val folded = tracker.recordExits(
      listOf(
        crash(runStart - minute, 90),
        ExitRecord(ExitReasons.OTHER, runStart + 12 * minute, 101, engine, "empty #3"),
      ),
      engine,
      runStart + 13 * minute,
    )
    assertTrue(folded.gaps.isEmpty())
    assertEquals(2, folded.seenExits.size)
  }

  @Test
  fun `an exit after an orderly release is not a gap, and one before it closes at the release`() {
    val tracker = running(runStart, pid = 100, until = runStart + 10 * minute)
    val released = tracker.engineStopped(runStart + 10 * minute, engineAlive = true)
    assertEquals(runStart + 10 * minute, released.run?.stoppedAt)
    assertTrue(released.gaps.isEmpty())
    // The process leaves 30 s after the release: no block was intended.
    val left = released.recordExits(listOf(ExitRecord(ExitReasons.OTHER, runStart + 10 * minute + 30_000, 100, engine, "empty #1")), engine, runStart + 11 * minute)
    assertTrue(left.gaps.isEmpty())
    // The user stopped it at +5 min; the app released the block at +10 min, before anyone looked.
    val stopped = released.recordExits(listOf(stopApp(runStart + 5 * minute, 100)), engine, runStart + 11 * minute)
    assertEquals(listOf(Triple(runStart + 5 * minute, runStart + 10 * minute, Reason.stoppedByUser)), stopped.gaps.shapes())
  }

  @Test
  fun `a refused restart after a user stop leaves the one gap open with its reason`() {
    val tracker = running(runStart, pid = 100, until = death)
    // The sticky restart's process 101 was refused startForeground (recorded
    // by the service in engine.failures) and died as an empty process; its
    // heartbeat, if any fired, is not the run's.
    val history = listOf(
      ExitRecord(ExitReasons.OTHER, death + 2_000, 101, engine, "empty #2"),
      stopApp(death, 100),
    )
    val seen = tracker.recordExits(history, engine, death + minute).heartbeat(death + 1_000, pid = 101)
    assertEquals(listOf(Triple(death, null, Reason.stoppedByUser)), seen.gaps.shapes())
    assertEquals(tracker.run, seen.run)
    val back = seen.engineStarted(restart, 102, history, engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(death, restart, Reason.stoppedByUser)), back.gaps.shapes())
  }

  // MARK: - Heartbeat

  @Test
  fun `a stale heartbeat with no exit record is a killedBySystem gap from the missed tick`() {
    val lastBeat = runStart + 10 * minute
    val tracker = running(runStart, pid = 100, until = lastBeat + 1)
    assertEquals(lastBeat, tracker.run?.heartbeatAt)
    val back = tracker.engineStarted(lastBeat + 20 * minute, 101, emptyList(), engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(lastBeat + minute, lastBeat + 20 * minute, Reason.killedBySystem)), back.gaps.shapes())
    assertEquals(Source.heartbeat, back.gaps.single().source)
  }

  @Test
  fun `a restart inside the staleness threshold records nothing`() {
    val lastBeat = runStart + 10 * minute
    val tracker = running(runStart, pid = 100, until = lastBeat + 1)
    val quick = tracker.engineStarted(lastBeat + EnforcementGapTracker.STALE_AFTER_MS, 101, emptyList(), engine, bootTime = bootBefore)
    assertTrue(quick.gaps.isEmpty())
    val late = tracker.engineStarted(lastBeat + EnforcementGapTracker.STALE_AFTER_MS + 1, 101, emptyList(), engine, bootTime = bootBefore)
    assertEquals(1, late.gaps.size)
  }

  @Test
  fun `the same process starting again after a long sleep is not a gap, only a fresher heartbeat`() {
    val lastBeat = runStart + 10 * minute
    val tracker = running(runStart, pid = 100, until = lastBeat + 1)
    // Screen off for 8 h with the heartbeat timer asleep, then the app launches and `sync` reaches onStartCommand again.
    val again = tracker.engineStarted(lastBeat + 8 * 60 * minute, 100, emptyList(), engine, bootTime = bootBefore)
    assertTrue(again.gaps.isEmpty())
    assertEquals(EnforcementGapTracker.EngineRun(runStart, 100, lastBeat + 8 * 60 * minute, null), again.run)
  }

  @Test
  fun `a reused pid is not the same process across a reboot, or once a gap says it died`() {
    val lastBeat = runStart + 10 * minute
    val tracker = running(runStart, pid = 100, until = lastBeat + 1)
    // Rebooted, and the new engine process happens to get pid 100 again.
    val boot = lastBeat + 30 * minute
    val afterBoot = tracker.engineStarted(boot + minute, 100, emptyList(), engine, bootTime = boot)
    assertEquals(listOf(Triple(boot, boot + minute, Reason.unknown)), afterBoot.gaps.shapes())
    assertEquals(boot + minute, afterBoot.run?.startedAt)
    // The app folded the death of pid 100; a process with pid 100 starting the engine is a new one.
    val dead = tracker.recordExits(listOf(stopApp(lastBeat + 2 * minute, 100)), engine, lastBeat + 3 * minute)
    val reused = dead.engineStarted(lastBeat + 20 * minute, 100, emptyList(), engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(lastBeat + 2 * minute, lastBeat + 20 * minute, Reason.stoppedByUser)), reused.gaps.shapes())
    assertEquals(EnforcementGapTracker.EngineRun(lastBeat + 20 * minute, 100, lastBeat + 20 * minute, null), reused.run)
  }

  @Test
  fun `an exit record plus a stale heartbeat for the same period is one gap, led by the record`() {
    val tracker = running(runStart, pid = 100, until = death)
    val lastBeat = tracker.run!!.heartbeatAt
    assertTrue(lastBeat < death && death < lastBeat + minute)
    val back = tracker.engineStarted(restart, 101, listOf(stopApp(death, 100)), engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(death, restart, Reason.stoppedByUser)), back.gaps.shapes())
    assertEquals(Source.exit, back.gaps.single().source)
    // The other order (the app folded the record first) is the same gap.
    val viaApp = tracker.recordExits(listOf(stopApp(death, 100)), engine, death + minute)
      .engineStarted(restart, 101, listOf(stopApp(death, 100)), engine, bootTime = bootBefore)
    assertEquals(back.gaps, viaApp.gaps)
  }

  @Test
  fun `a stale heartbeat after a release is not a gap`() {
    val tracker = running(runStart, pid = 100, until = runStart + 10 * minute).engineStopped(runStart + 10 * minute, engineAlive = true)
    val back = tracker.engineStarted(runStart + 60 * minute, 101, emptyList(), engine, bootTime = bootBefore)
    assertTrue(back.gaps.isEmpty())
  }

  @Test
  fun `a release while the engine is silently dead records the gap up to the release`() {
    val lastBeat = runStart + 10 * minute
    val tracker = running(runStart, pid = 100, until = lastBeat + 1)
    val released = tracker.engineStopped(lastBeat + 120 * minute, engineAlive = false)
    assertEquals(listOf(Triple(lastBeat + minute, lastBeat + 120 * minute, Reason.killedBySystem)), released.gaps.shapes())
    // …and a release after a reboot starts at the boot.
    val boot = lastBeat + 60 * minute
    val afterBoot = tracker.engineStopped(boot + 5 * minute, engineAlive = false, bootTime = boot)
    assertEquals(listOf(Triple(boot, boot + 5 * minute, Reason.unknown)), afterBoot.gaps.shapes())
    // The engine alive with a stale heartbeat (a schedule ending in a pocket: the timer slept with the CPU) is no gap.
    assertTrue(tracker.engineStopped(lastBeat + 120 * minute, engineAlive = true).gaps.isEmpty())
    // A later start sees a stopped run: nothing more.
    assertEquals(released.gaps, released.engineStarted(lastBeat + 200 * minute, 101, emptyList(), engine, bootTime = bootBefore).gaps)
  }

  @Test
  fun `a heartbeat without a live run, or from another process, changes nothing about the run`() {
    val none = EnforcementGapTracker().heartbeat(runStart, pid = 100)
    assertNull(none.run)
    val released = running(runStart, pid = 100, until = runStart + 5 * minute).engineStopped(runStart + 5 * minute, engineAlive = true)
    // The engine's queued beat lands after the app's release.
    val late = released.heartbeat(runStart + 5 * minute + 10, pid = 100)
    assertEquals(released.run, late.run)
    // A process whose start was refused beats anyway.
    val live = running(runStart, pid = 100, until = runStart + 5 * minute)
    assertEquals(live.run, live.heartbeat(runStart + 6 * minute, pid = 101).run)
    // A permission observation from either still counts.
    assertEquals(runStart + 6 * minute, live.heartbeat(runStart + 6 * minute, pid = 101, permissionsApproved = true).lastApprovedAt)
  }

  // MARK: - Reboot

  @Test
  fun `a reboot is an unknown gap from the boot to the engine start`() {
    val lastBeat = runStart + 10 * minute
    val tracker = running(runStart, pid = 100, until = lastBeat + 1)
    val boot = lastBeat + 30 * minute
    val back = tracker.engineStarted(boot + 2 * minute, 101, emptyList(), engine, bootTime = boot)
    assertEquals(listOf(Triple(boot, boot + 2 * minute, Reason.unknown)), back.gaps.shapes())
    assertEquals(Source.heartbeat, back.gaps.single().source)
    assertTrue(back.gaps.single().detail.startsWith("reboot"))
  }

  @Test
  fun `a reboot after a user stop is the user's gap, not two`() {
    val tracker = running(runStart, pid = 100, until = death)
    val boot = death + 60 * minute
    val back = tracker.engineStarted(boot + minute, 101, listOf(stopApp(death, 100)), engine, bootTime = boot)
    assertEquals(listOf(Triple(death, boot + minute, Reason.stoppedByUser)), back.gaps.shapes())
  }

  @Test
  fun `a boot before the last heartbeat is not a reboot`() {
    val tracker = running(runStart, pid = 100, until = runStart + 10 * minute)
    val back = tracker.engineStarted(runStart + 11 * minute, 101, emptyList(), engine, bootTime = bootBefore)
    assertTrue(back.gaps.isEmpty())
  }

  // MARK: - Permissions while the engine lives and dies

  @Test
  fun `a revocation while the engine is dead is its own gap from the last check that saw access`() {
    val tracker = running(runStart, pid = 100, until = death, permissionsApproved = true)
    val lastCheck = tracker.lastApprovedAt!!
    assertTrue(lastCheck < death)
    // The app opens an hour after the stop: the stop shows up, and usage access is off.
    val looked = tracker
      .recordExits(listOf(stopApp(death, 100)), engine, death + 60 * minute)
      .observePermissions(setOf(PermissionSteps.StepId.overlay, PermissionSteps.StepId.deviceAdmin), death + 60 * minute)
    assertEquals(
      listOf(Triple(lastCheck, null, Reason.permissionRevoked), Triple(death, null, Reason.stoppedByUser)),
      looked.gaps.shapes(),
    )
    assertEquals("missing: usageAccess", looked.gaps.first().detail)
    // The engine restarts without the permission: its gap closes, the permission's stays open.
    val back = looked.engineStarted(restart, 101, listOf(stopApp(death, 100)), engine, bootTime = bootBefore)
      .heartbeat(restart + minute, pid = 101, permissionsApproved = false, missing = listOf("usageAccess"))
    assertEquals(
      listOf(Triple(lastCheck, null, Reason.permissionRevoked), Triple(death, restart, Reason.stoppedByUser)),
      back.gaps.shapes(),
    )
    // Access back at the next heartbeat closes it there.
    val granted = back.heartbeat(restart + 2 * minute, pid = 101, permissionsApproved = true)
    assertEquals(restart + 2 * minute, granted.gaps.first().to)
    assertEquals(restart + 2 * minute, granted.lastApprovedAt)
  }

  @Test
  fun `a permission gap needs an intent to enforce - none without a run, bounded by its start, closed by its release`() {
    // Revoked and restored while nothing is blocked: a grant is noted, no gap.
    val idle = EnforcementGapTracker().observePermissions(true, 1_000).observePermissions(false, 2_000).observePermissions(true, 3_000)
    assertTrue(idle.gaps.isEmpty())
    assertEquals(3_000L, idle.lastApprovedAt)
    // Revoked before the block: the gap starts with the block, not at the old grant.
    val blocked = EnforcementGapTracker().observePermissions(true, 1_000)
      .engineStarted(5_000, 1, emptyList(), engine, bootTime = null)
      .observePermissions(false, 5_001)
    assertEquals(listOf(Triple(5_000L, null, Reason.permissionRevoked)), blocked.gaps.shapes())
    // The block is lifted while still revoked: the gap ends there.
    val lifted = blocked.engineStopped(9_000, engineAlive = true)
    assertEquals(listOf(Triple(5_000L, 9_000L, Reason.permissionRevoked)), lifted.gaps.shapes())
    assertFalse(lifted.observePermissions(false, 9_500).hasOpenPermissionGap)
  }

  @Test
  fun `a clock step back cannot open a second permission gap`() {
    val tracker = EnforcementGapTracker().engineStarted(0, 1, emptyList(), engine, bootTime = null)
      .observePermissions(true, 10_000).observePermissions(false, 11_000)
    // The clock steps back 5 s and access returns, then goes again.
    val stepped = tracker.observePermissions(true, 6_000).observePermissions(false, 7_000)
    assertEquals(listOf(Triple(6_000L, null, Reason.permissionRevoked), Triple(10_000L, 10_000L, Reason.permissionRevoked)), stepped.gaps.shapes())
    assertTrue(stepped.hasOpenPermissionGap)
    val more = stepped.observePermissions(false, 8_000).observePermissions(false, 9_000)
    assertEquals(stepped.gaps, more.gaps)
  }

  @Test
  fun `deviceAdmin and notifications are not enforcement steps`() {
    val all = EnforcementGapTracker.ENFORCEMENT_STEPS + PermissionSteps.StepId.deviceAdmin + PermissionSteps.StepId.notifications
    val tracker = EnforcementGapTracker().engineStarted(0, 1, emptyList(), engine, bootTime = null)
      .observePermissions(all, 1_000).observePermissions(EnforcementGapTracker.ENFORCEMENT_STEPS, 2_000)
    assertTrue(tracker.gaps.isEmpty())
    assertEquals(2_000L, tracker.lastApprovedAt)
    val revoked = tracker.observePermissions(setOf(PermissionSteps.StepId.usageAccess), 3_000)
    assertEquals("missing: overlay", revoked.gaps.single().detail)
  }

  // MARK: - since, retention

  @Test
  fun `since keeps open gaps and the closed ones ending at or after it`() {
    val tracker = running(runStart, pid = 100, until = runStart + 10 * minute)
      .engineStarted(runStart + 30 * minute, 101, listOf(crash(runStart + 10 * minute, 100)), engine, bootTime = null)
      .heartbeat(runStart + 31 * minute, pid = 101)
      .recordExits(listOf(stopApp(runStart + 32 * minute, 101)), engine, runStart + 40 * minute)
    assertEquals(
      listOf(Triple(runStart + 10 * minute, runStart + 30 * minute, Reason.unknown), Triple(runStart + 32 * minute, null, Reason.stoppedByUser)),
      tracker.gaps.shapes(),
    )
    assertEquals(2, tracker.gaps(since = null).size)
    assertEquals(2, tracker.gaps(since = runStart + 30 * minute).size)
    assertEquals(listOf(runStart + 32 * minute), tracker.gaps(since = runStart + 30 * minute + 1).map { it.from })
    assertEquals(listOf(runStart + 32 * minute), tracker.gaps(since = runStart + 365L * 24 * 60 * minute).map { it.from })
    // The C09 shape: `to` is null or at least `from`.
    assertTrue(tracker.gaps.all { it.to == null || it.to!! >= it.from })
  }

  @Test
  fun `the retention cap drops the oldest closed gaps and keeps the open ones`() {
    var tracker = EnforcementGapTracker()
    var at = runStart
    var pid = 100
    repeat(EnforcementGapTracker.MAX_GAPS + 5) {
      tracker = tracker.engineStarted(at, pid, listOf(crash(at - 5 * minute, pid - 1)), engine, bootTime = null)
      // Dies 10 min in, restarts 5 min later with the record.
      at += 15 * minute
      pid += 1
    }
    assertEquals(EnforcementGapTracker.MAX_GAPS, tracker.gaps.size)
    // The first start folded nothing (no run yet); the gaps are the 54 later ones minus the 4 oldest.
    assertEquals(runStart + 10 * minute + 4 * 15 * minute, tracker.gaps.first().from)
    // An open gap survives a cap that would otherwise drop it.
    val revoked = tracker.observePermissions(true, at - minute).observePermissions(false, at)
    assertEquals(EnforcementGapTracker.MAX_GAPS, revoked.gaps.size)
    assertEquals(Triple(at - minute, null, Reason.permissionRevoked), revoked.gaps.last().shape())
    assertEquals(runStart + 10 * minute + 5 * 15 * minute, revoked.gaps.first().from)
  }

  @Test
  fun `closed gaps older than the age limit are dropped, open ones are kept`() {
    val old = running(runStart, pid = 100, until = runStart + 10 * minute)
      .engineStarted(runStart + 30 * minute, 101, listOf(crash(runStart + 10 * minute, 100)), engine, bootTime = null)
      .observePermissions(true, runStart + 31 * minute)
      .observePermissions(false, runStart + 32 * minute)
    assertEquals(2, old.gaps.size)
    val later = old.heartbeat(runStart + 30 * minute + EnforcementGapTracker.MAX_AGE_MS + 1, pid = 101, permissionsApproved = false)
    assertEquals(listOf(Triple(runStart + 31 * minute, null, Reason.permissionRevoked)), later.gaps.shapes())
  }

  @Test
  fun `a record stamped after now starts at now, and to is never before from`() {
    val tracker = running(runStart, pid = 100, until = runStart + 10 * minute)
    val skewed = tracker.recordExits(listOf(stopApp(runStart + 20 * minute, 100)), engine, runStart + 11 * minute)
    assertEquals(listOf(Triple(runStart + 11 * minute, null, Reason.stoppedByUser)), skewed.gaps.shapes())
    assertEquals(listOf(Triple(runStart + 11 * minute, runStart + 12 * minute, Reason.stoppedByUser)), skewed.engineStopped(runStart + 12 * minute, engineAlive = false).gaps.shapes())
    // Inside a start, the skewed record starts at now and so closes at once: a
    // zero-length engine gap is under the minimum and is dropped.
    val back = tracker.engineStarted(runStart + 20 * minute, 101, listOf(stopApp(runStart + 25 * minute, 100)), engine, bootTime = null)
    assertTrue(back.gaps.isEmpty())
  }

  @Test
  fun `a closed engine gap shorter than the minimum is dropped - a sticky restart in seconds left no hole`() {
    val tracker = running(runStart, pid = 100, until = runStart + 10 * minute)
    val killed = runStart + 10 * minute + 30_000
    val signaled = ExitRecord(ExitReasons.SIGNALED, killed, 100, engine, "signal 9")
    val quick = tracker.engineStarted(killed + 2_000, 101, listOf(signaled), engine, bootTime = bootBefore)
    assertTrue(quick.gaps.isEmpty())
    assertEquals(killed + 2_000, quick.run!!.startedAt)
    // The record is still seen: the next history will not fold it again.
    assertTrue(signaled.key in quick.seenExits)
    val slow = tracker.engineStarted(killed + EnforcementGapTracker.MIN_ENGINE_GAP_MS, 101, listOf(signaled), engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(killed, killed + EnforcementGapTracker.MIN_ENGINE_GAP_MS, Reason.killedBySystem)), slow.gaps.shapes())
    // A user stop is reported whatever its length once past the minimum; under it, it is dropped too.
    val stopped = tracker.engineStarted(killed + 5_000, 101, listOf(stopApp(killed, 100)), engine, bootTime = bootBefore)
    assertTrue(stopped.gaps.isEmpty())
    // Open gaps are never subject to the minimum: a death seen one second ago is open.
    val open = tracker.recordExits(listOf(signaled), engine, killed + 1_000)
    assertEquals(listOf(Triple(killed, null, Reason.killedBySystem)), open.gaps.shapes())
  }

  // MARK: - Reboot by boot count, shutdown beat, refused start, the app's look, clock shift

  @Test
  fun `a reboot is known by the boot count, not the wall clock`() {
    val lastBeat = runStart + 10 * minute
    val booted = EnforcementGapTracker().engineStarted(runStart, 100, emptyList(), engine, bootTime = bootBefore, bootCount = 7)
      .heartbeat(lastBeat, 100)
    assertEquals(7, booted.run!!.bootCount)
    // The clock jumped forward by an hour (the boot time, derived from it, now reads after the last beat): same boot, same process, no gap.
    val jumped = booted.engineStarted(lastBeat + 60 * minute, 100, emptyList(), engine, bootTime = lastBeat + 30 * minute, bootCount = 7)
    assertTrue(jumped.gaps.isEmpty())
    assertEquals(runStart, jumped.run!!.startedAt)
    // A new process in the same boot with a stale heartbeat is a kill, not a reboot.
    val killed = booted.engineStarted(lastBeat + 60 * minute, 101, emptyList(), engine, bootTime = lastBeat + 30 * minute, bootCount = 7)
    assertEquals(listOf(Triple(lastBeat + minute, lastBeat + 60 * minute, Reason.killedBySystem)), killed.gaps.shapes())
    // Another boot count is a reboot even when the boot time reads before the last beat (a clock set back after the boot).
    val rebooted = booted.engineStarted(lastBeat + 60 * minute, 101, emptyList(), engine, bootTime = lastBeat - 5 * minute, bootCount = 8)
    assertEquals(listOf(Triple(lastBeat - 5 * minute, lastBeat + 60 * minute, Reason.unknown)), rebooted.gaps.shapes())
    assertTrue(rebooted.gaps.single().detail.startsWith("reboot: boot 8"))
    assertEquals(8, rebooted.run!!.bootCount)
    // A run stored without a count (an older document) falls back to the boot time.
    val legacy = running(runStart, pid = 100, until = lastBeat + 1)
    assertNull(legacy.run!!.bootCount)
    val legacyReboot = legacy.engineStarted(lastBeat + 60 * minute, 101, emptyList(), engine, bootTime = lastBeat + 30 * minute, bootCount = 8)
    assertEquals(listOf(Triple(lastBeat + 30 * minute, lastBeat + 60 * minute, Reason.unknown)), legacyReboot.gaps.shapes())
  }

  @Test
  fun `a single reboot's gap starts at the boot, not at the shutdown (a phone off overnight is not a gap)`() {
    val tracker = EnforcementGapTracker().engineStarted(runStart, 100, emptyList(), engine, bootTime = bootBefore, bootCount = 7)
    val shutdown = runStart + 10 * minute + 20_000
    val off = tracker.heartbeat(runStart + 10 * minute, 100).heartbeat(shutdown, 100, shutdown = true)
    assertEquals(shutdown, off.run!!.shutdownAt)
    assertEquals(shutdown, off.run!!.heartbeatAt)
    val boot = shutdown + 8 * 60 * minute
    val back = off.engineStarted(boot + minute, 101, emptyList(), engine, bootTime = boot, bootCount = 8)
    assertEquals(listOf(Triple(boot, boot + minute, Reason.unknown)), back.gaps.shapes())
    assertTrue(back.gaps.single().detail.contains("shutdown at $shutdown"))
    assertFalse(back.gaps.single().detail.contains("without the engine"))
    // Without a shutdown beat, the same.
    val silent = tracker.heartbeat(runStart + 10 * minute, 100).engineStarted(boot + minute, 101, emptyList(), engine, bootTime = boot, bootCount = 8)
    assertEquals(listOf(Triple(boot, boot + minute, Reason.unknown)), silent.gaps.shapes())
  }

  @Test
  fun `a boot the engine missed (safe mode) starts the gap at the shutdown beat, which later beats keep, else at the last heartbeat`() {
    val tracker = EnforcementGapTracker().engineStarted(runStart, 100, emptyList(), engine, bootTime = bootBefore, bootCount = 7)
    val lastBeat = runStart + 10 * minute
    val shutdown = lastBeat + 20_000
    val off = tracker.heartbeat(lastBeat, 100).heartbeat(shutdown, 100, shutdown = true)
    // Boot 8 ran without the engine (safe mode: no third-party app starts), boot 9 brings it back.
    val boot = shutdown + 40 * minute
    val back = off.engineStarted(boot + minute, 101, emptyList(), engine, bootTime = boot, bootCount = 9)
    assertEquals(listOf(Triple(shutdown, boot + minute, Reason.unknown)), back.gaps.shapes())
    assertTrue(back.gaps.single().detail.endsWith("1 boot(s) without the engine"))
    // The shutdown sequence lasts seconds and other beats land inside it (SCREEN_OFF, the timer): the mark stays.
    val later = off.heartbeat(shutdown + 3_000, 100)
    assertEquals(shutdown, later.run!!.shutdownAt)
    assertEquals(listOf(Triple(shutdown, boot + minute, Reason.unknown)), later.engineStarted(boot + minute, 101, emptyList(), engine, bootTime = boot, bootCount = 9).gaps.shapes())
    // A new run (any process, any boot) starts without one.
    assertNull(off.engineStarted(shutdown + minute, 101, emptyList(), engine, bootTime = bootBefore, bootCount = 7).run!!.shutdownAt)
    // No shutdown beat (a forced power-off): from the last heartbeat, where the engine was last seen.
    val silent = tracker.heartbeat(lastBeat, 100).engineStarted(boot + minute, 101, emptyList(), engine, bootTime = boot, bootCount = 9)
    assertEquals(listOf(Triple(lastBeat, boot + minute, Reason.unknown)), silent.gaps.shapes())
  }

  @Test
  fun `a refused start opens a gap at once, closed by the next successful start`() {
    val lastBeat = runStart + 10 * minute
    val tracker = running(runStart, pid = 100, until = lastBeat + 1)
    // The system refused the promotion 30 s after the last beat: the heartbeat is fresh, the engine is down all the same.
    val refused = tracker.engineRefused(lastBeat + 30_000, "ForegroundServiceStartNotAllowedException")
    assertEquals(listOf(Triple(lastBeat + 30_000, null, Reason.killedBySystem)), refused.gaps.shapes())
    assertTrue(refused.gaps.single().detail.startsWith("start refused"))
    assertEquals(runStart, refused.run!!.startedAt)
    // Refused again: still the one gap.
    assertEquals(1, refused.engineRefused(lastBeat + 60_000, "again").gaps.size)
    // The same process starting the engine later is a new run (the open gap says it was down), and closes the gap.
    val back = refused.engineStarted(lastBeat + 20 * minute, 100, emptyList(), engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(lastBeat + 30_000, lastBeat + 20 * minute, Reason.killedBySystem)), back.gaps.shapes())
    assertEquals(lastBeat + 20 * minute, back.run!!.startedAt)
    // A first start refused before any run: a run with no process holds the gap; any process closes it.
    val first = EnforcementGapTracker().engineRefused(runStart, "background start")
    assertNull(first.run!!.pid)
    assertEquals(listOf(Triple(runStart, null, Reason.killedBySystem)), first.gaps.shapes())
    val started = first.engineStarted(runStart + 5 * minute, 100, emptyList(), engine, bootTime = bootBefore)
    assertEquals(listOf(Triple(runStart, runStart + 5 * minute, Reason.killedBySystem)), started.gaps.shapes())
    assertEquals(100, started.run!!.pid)
    // A release closes it too.
    assertEquals(listOf(Triple(runStart, runStart + minute, Reason.killedBySystem)), first.engineStopped(runStart + minute, engineAlive = false).gaps.shapes())
    // After a release there is nothing to enforce: a refusal is not a gap (the run is not live), the caller does not fold one.
    assertTrue(tracker.engineStopped(lastBeat + minute, engineAlive = true).engineRefused(lastBeat + 2 * minute, "x").run!!.isLive.not())
  }

  @Test
  fun `the app looking at a dead engine folds an open gap, at an alive or fresh one nothing`() {
    val lastBeat = runStart + 10 * minute
    val tracker = running(runStart, pid = 100, until = lastBeat + 1)
    // Fresh heartbeat, process gone: ambiguous, nothing yet (the stale rule).
    assertTrue(tracker.observeEngine(lastBeat + minute, engineAlive = false, bootTime = bootBefore, bootCount = 7).gaps.isEmpty())
    // Stale heartbeat, process alive: asleep, nothing.
    assertTrue(tracker.observeEngine(lastBeat + 60 * minute, engineAlive = true, bootTime = bootBefore, bootCount = 7).gaps.isEmpty())
    // Stale heartbeat, process gone: an open gap from the missed tick.
    val dead = tracker.observeEngine(lastBeat + 60 * minute, engineAlive = false, bootTime = bootBefore, bootCount = 7)
    assertEquals(listOf(Triple(lastBeat + minute, null, Reason.killedBySystem)), dead.gaps.shapes())
    // Looking again changes nothing; the next start closes it and the exit record, when it shows up, leads.
    assertEquals(dead.gaps, dead.observeEngine(lastBeat + 70 * minute, engineAlive = false, bootTime = bootBefore, bootCount = 7).gaps)
    val back = dead.engineStarted(lastBeat + 80 * minute, 101, listOf(stopApp(lastBeat + 30_000, 100)), engine, bootTime = bootBefore, bootCount = 7)
    assertEquals(listOf(Triple(lastBeat + 30_000, lastBeat + 80 * minute, Reason.stoppedByUser)), back.gaps.shapes())
    // A run from a previous boot observed by the app before the engine is back: open from the boot.
    val booted = EnforcementGapTracker().engineStarted(runStart, 100, emptyList(), engine, bootTime = bootBefore, bootCount = 7).heartbeat(lastBeat, 100)
    val boot = lastBeat + 30 * minute
    val afterBoot = booted.observeEngine(boot + minute, engineAlive = false, bootTime = boot, bootCount = 8)
    assertEquals(listOf(Triple(boot, null, Reason.unknown)), afterBoot.gaps.shapes())
    // Without a live run (released), nothing.
    assertTrue(tracker.engineStopped(lastBeat + minute, engineAlive = true).observeEngine(lastBeat + 60 * minute, engineAlive = false, bootTime = bootBefore).gaps.isEmpty())
  }

  @Test
  fun `a clock shift moves the run's instants and the last grant, never the recorded gaps`() {
    val tracker = running(runStart, pid = 100, until = death, permissionsApproved = true)
      .recordExits(listOf(stopApp(death, 100)), engine, death + minute)
    val shifted = tracker.clockShifted(3_600_000)
    assertEquals(runStart + 3_600_000, shifted.run!!.startedAt)
    assertEquals(tracker.run!!.heartbeatAt + 3_600_000, shifted.run!!.heartbeatAt)
    assertEquals(tracker.lastApprovedAt!! + 3_600_000, shifted.lastApprovedAt)
    assertEquals(tracker.gaps.shapes(), shifted.gaps.shapes())
    // The gap still belongs to its run: a later estimate of the same run merges with it.
    assertEquals(runStart + 3_600_000, shifted.gaps.single().run)
    val back = shifted.engineStarted(death + 3_600_000 + 20 * minute, 101, emptyList(), engine, bootTime = null)
    assertEquals(1, back.gaps.size)
    assertEquals(Reason.stoppedByUser, back.gaps.single().reason)
    assertEquals(tracker, tracker.clockShifted(0))
    assertEquals(tracker.copy(lastApprovedAt = tracker.lastApprovedAt!! - 5), tracker.copy(run = null).clockShifted(-5).copy(run = tracker.run))
  }

  // MARK: - Codec and the state-store seam

  @Test
  fun `the codec round-trips the whole state`() {
    val tracker = running(runStart, pid = 100, until = death, permissionsApproved = true)
      .recordExits(listOf(stopApp(death, 100)), engine, death + minute)
      .observePermissions(false, death + minute, listOf("usageAccess"))
      .engineStarted(restart, 101, listOf(stopApp(death, 100)), engine, bootTime = bootBefore)
    assertEquals(2, tracker.gaps.size)
    val json = JSONObject(tracker.toJson().toString())
    assertEquals(tracker, EnforcementGapTracker.fromJson(json))
    val settled = tracker.settle(restart + minute, EnforcementIntent.ALWAYS)
    assertEquals(settled, EnforcementGapTracker.fromJson(JSONObject(settled.toJson().toString())))
    val first = JSONObject(settled.toJson().toString()).getJSONArray("entries").getJSONObject(0)
    assertEquals(first.getLong("from"), first.getLong("origin"))
    // The settled open record moved on; its origin (the piece's too) is where the observation began.
    val open = settled.gaps.single { it.isOpen }
    assertEquals(restart + minute, open.from)
    assertTrue(open.origin < open.from)
    assertEquals(open.origin, settled.gaps.single { !it.isOpen && !it.isEngineFamily }.origin)
    val booted = tracker.copy(run = tracker.run!!.copy(bootCount = 42, shutdownAt = restart + 5))
    assertEquals(booted, EnforcementGapTracker.fromJson(JSONObject(booted.toJson().toString())))
    assertEquals(42, JSONObject(booted.toJson().toString()).getJSONObject("run").getInt("bootCount"))
    assertTrue(json.getJSONArray("entries").getJSONObject(0).isNull("to"))
    assertEquals(death, json.getJSONArray("seenExits").getJSONObject(0).getLong("timestamp"))
    assertEquals(101, json.getJSONObject("run").getInt("pid"))
    assertTrue(json.getJSONObject("run").isNull("stoppedAt"))
    // An empty tracker encodes to an empty log and decodes back.
    assertEquals(EnforcementGapTracker(), EnforcementGapTracker.fromJson(JSONObject(EnforcementGapTracker().toJson().toString())))
  }

  @Test
  fun `a corrupt or foreign document is tolerated entry by entry`() {
    assertEquals(EnforcementGapTracker(), EnforcementGapTracker.fromJson(null))
    assertEquals(EnforcementGapTracker(), EnforcementGapTracker.load(JSONObject("""{"gaps":"garbage"}""")))
    assertEquals(EnforcementGapTracker(), EnforcementGapTracker.load(JSONObject("""{"gaps":{"entries":"nope","run":{"pid":3},"seenExits":[{"pid":1},"x"],"future":true}}""")))
    val mixed = EnforcementGapTracker.fromJson(
      JSONObject(
        """{"entries":[
          {"from":5,"to":null,"reason":"stoppedByUser","source":"exit","detail":"ok"},
          {"from":"x","to":null,"reason":"stoppedByUser","source":"exit"},
          {"from":9,"to":4,"reason":"stoppedByUser","source":"exit"},
          {"from":9,"reason":"stoppedByUser","source":"exit"},
          {"from":1,"to":2,"reason":"teleported","source":"exit"},
          {"from":1,"to":2,"reason":"unknown","source":"psychic"},
          {"from":1,"to":2,"reason":"killedBySystem","source":"heartbeat"},
          "not an object"
        ],"run":{"startedAt":1,"heartbeatAt":"7","pid":null,"stoppedAt":null},"seenExits":[{"timestamp":3,"pid":4}],"lastApprovedAt":2}""",
      ),
    )
    assertEquals(listOf(Triple(1L, 2L, Reason.killedBySystem), Triple(5L, null, Reason.stoppedByUser)), mixed.gaps.shapes())
    assertEquals(EnforcementGapTracker.EngineRun(1, null, 7, null), mixed.run)
    assertEquals(setOf(EnforcementGapTracker.ExitKey(3, 4)), mixed.seenExits)
    assertEquals(2L, mixed.lastApprovedAt)
  }

  @Test
  fun `each fold is one StateStore update, shared by two instances as by two processes`() {
    val directory = folder.root.resolve("expo_screen_time")
    val engineProcess = StateStore(directory)
    val appProcess = StateStore(directory)
    engineProcess.update { EnforcementGapTracker.update(it) { t -> t.engineStarted(runStart, 100, emptyList(), engine, bootTime = null) } }
    engineProcess.update { EnforcementGapTracker.update(it) { t -> t.heartbeat(runStart + minute, pid = 100, permissionsApproved = true) } }
    // The app process folds the death and reads the gaps, under the same lock.
    val gaps = appProcess.update { document ->
      EnforcementGapTracker.update(document) { t -> t.recordExits(listOf(stopApp(runStart + 90_000, 100)), engine, runStart + 2 * minute) }.gaps(since = null)
    }
    assertEquals(listOf(Triple(runStart + 90_000, null, Reason.stoppedByUser)), gaps.shapes())
    assertEquals(listOf(mapOf("from" to runStart + 90_000, "to" to null, "reason" to "stoppedByUser")), gaps.map { it.toMap() })
    // The engine's next start sees the app's fold, not a stale copy.
    val back = engineProcess.update { EnforcementGapTracker.update(it) { t -> t.engineStarted(restart, 101, listOf(stopApp(runStart + 90_000, 100)), engine, bootTime = null) } }
    assertEquals(listOf(Triple(runStart + 90_000, restart, Reason.stoppedByUser)), back.gaps.shapes())
    assertEquals(back, appProcess.read { EnforcementGapTracker.load(it) })
    // The rest of the document is untouched.
    assertFalse(appProcess.engineDesired)
    assertEquals(runStart + minute, appProcess.read { it.getJSONObject(EnforcementGapTracker.DOCUMENT_KEY).getLong("lastApprovedAt") })
  }
}

/**
 * The intent the document describes ([EnforcementIntent.of]) and how the
 * tracker clips its gaps to it: a gap is reported only for the periods a
 * source would have shielded something.
 */
class EnforcementIntentTest {
  private val engine = "com.example.app:screentime"
  private val zone = MonitoringTestClock.utc
  private val apps = Selection(applications = setOf("com.a", "com.b"))
  private val selections: (String) -> Selection? = { id -> if (id == "APPS") apps else null }
  private val minute = EnforcementGapTracker.HEARTBEAT_INTERVAL_MS

  // Monday 2026-11-02.
  private fun at(day: Int, hour: Int, minute: Int = 0) = MonitoringTestClock.date(2026, 11, day, hour, minute, zone = zone)

  private fun components(hour: Int, minute: Int = 0): Map<String, Any> =
    LinkedHashMap<String, Any>().apply { put("hour", hour.toDouble()); put("minute", minute.toDouble()) }

  private fun config(name: String, startHour: Int, endHour: Int, selectionId: String? = "APPS", events: List<Map<String, Any?>>? = null, weekdays: List<Int>? = null): MonitoringConfig {
    val schedule = LinkedHashMap<String, Any?>().apply { put("intervalStart", components(startHour)); put("intervalEnd", components(endHour)); put("repeats", true) }
    val raw = LinkedHashMap<String, Any?>().apply {
      put("activityName", name); put("schedule", schedule)
      if (selectionId != null) put("selectionId", selectionId)
      if (events != null) put("events", events)
      if (weekdays != null) put("weekdays", weekdays.map { it.toDouble() })
    }
    return MonitoringValidation.validate(raw, selections, emptySet())
  }

  private fun threshold(block: Boolean) = LinkedHashMap<String, Any?>().apply {
    put("eventName", "limit"); put("selectionId", "APPS"); put("threshold", 600.0); put("blockOnThreshold", block)
  }

  /** A 09:00–17:00 daily schedule registered at [registeredAt]. */
  private fun workHours(registeredAt: Long, events: List<Map<String, Any?>>? = null, selectionId: String? = "APPS"): MonitoringState =
    MonitoringState().start(config("work", 9, 17, selectionId, events), registeredAt, zone, selections).state

  private fun List<EnforcementGap>.shapes() = map { Triple(it.from, it.to, it.reason) }
  private fun List<Occurrence>.pairs() = map { it.startMs to it.endMs }

  @Test
  fun `a manual block is intended at every instant, nothing without a source`() {
    val manual = ManualBlock("s", BlockMode.blocklist, setOf("com.a"))
    assertEquals(listOf(at(2, 20) to at(3, 10)), EnforcementIntent.of(manual, MonitoringState(), zone).windows(at(2, 20), at(3, 10)).pairs())
    // A blocklist over nothing shields nothing; an allowlist always does.
    assertTrue(EnforcementIntent.of(ManualBlock("s", BlockMode.blocklist, emptySet()), MonitoringState(), zone).windows(at(2, 20), at(3, 10)).isEmpty())
    assertEquals(1, EnforcementIntent.of(ManualBlock("s", BlockMode.allowlist, emptySet()), MonitoringState(), zone).windows(at(2, 20), at(3, 10)).size)
    assertTrue(EnforcementIntent.of(null, MonitoringState(), zone).windows(at(2, 20), at(3, 10)).isEmpty())
    assertTrue(EnforcementIntent.ALWAYS.windows(5, 5).isEmpty())
    assertEquals(listOf(5L to 6L), EnforcementIntent.ALWAYS.windows(5, 6).pairs())
  }

  @Test
  fun `a schedule is intended inside its occurrences only, over any range`() {
    val intent = EnforcementIntent.of(null, workHours(at(2, 8)), zone)
    // Monday 20:00 to Wednesday 10:00: Tuesday's whole occurrence and Wednesday's first hour.
    assertEquals(listOf(at(3, 9) to at(3, 17), at(4, 9) to at(4, 10)), intent.windows(at(2, 20), at(4, 10)).pairs())
    // A range inside an occurrence is the range.
    assertEquals(listOf(at(2, 10) to at(2, 11)), intent.windows(at(2, 10), at(2, 11)).pairs())
    // A range between occurrences is empty.
    assertTrue(intent.windows(at(2, 18), at(3, 8)).isEmpty())
    // Weekdays: a Tuesday-only schedule has no Wednesday window.
    val tuesdays = MonitoringState().start(config("tue", 9, 17, weekdays = listOf(3)), at(2, 8), zone, selections).state
    assertEquals(listOf(at(3, 9) to at(3, 17)), EnforcementIntent.of(null, tuesdays, zone).windows(at(2, 0), at(5, 0)).pairs())
  }

  @Test
  fun `a lifted interval is not intended, a threshold block is for its interval, a meter-only activity is not`() {
    val lifted = workHours(at(2, 10)).lift()
    assertTrue(lifted.intervals.getValue("work").lifted)
    // Monday's occurrence (lifted) is out; Tuesday's is in.
    assertEquals(listOf(at(3, 9) to at(3, 17)), EnforcementIntent.of(null, lifted, zone).windows(at(2, 10), at(3, 20)).pairs())
    val thresholdBlock = workHours(at(2, 8), events = listOf(threshold(block = true)), selectionId = null)
    assertEquals(listOf(at(2, 9) to at(2, 17)), EnforcementIntent.of(null, thresholdBlock, zone).windows(at(2, 0), at(2, 20)).pairs())
    val meterOnly = workHours(at(2, 8), events = listOf(threshold(block = false)), selectionId = null)
    assertTrue(EnforcementIntent.of(null, meterOnly, zone).windows(at(2, 0), at(2, 20)).isEmpty())
  }

  @Test
  fun `two schedules' windows are merged`() {
    val state = MonitoringState().start(config("morning", 8, 12), at(2, 7), zone, selections).state
      .start(config("afternoon", 11, 15), at(2, 7), zone, selections).state
    assertEquals(listOf(at(2, 8) to at(2, 15)), EnforcementIntent.of(null, state, zone).windows(at(2, 0), at(2, 20)).pairs())
    assertEquals(listOf(1L to 5L, 7L to 9L), EnforcementIntent.normalize(listOf(Occurrence(7, 9), Occurrence(1, 3), Occurrence(3, 5), Occurrence(4, 4))).pairs())
  }

  @Test
  fun `an engine gap is clipped to the schedule's occurrences when it closes`() {
    val intent = EnforcementIntent.of(null, workHours(at(2, 8)), zone)
    val stop = at(2, 20)
    var tracker = EnforcementGapTracker().engineStarted(at(2, 8), 100, emptyList(), engine, bootTime = null, intent = intent)
    tracker = tracker.heartbeat(stop - minute, 100)
    val record = ExitRecord(ExitReasons.USER_REQUESTED, stop, 100, engine, "stop")
    // Back at 10:00 the next day: only the hour of Tuesday's occurrence was unenforced.
    val back = tracker.engineStarted(at(3, 10), 101, listOf(record), engine, bootTime = null, intent = intent)
    assertEquals(listOf(Triple(at(3, 9), at(3, 10), EnforcementGap.Reason.stoppedByUser)), back.gaps.shapes())
    // Back at 08:00 the next day: nothing was unenforced, the record is still seen.
    val early = tracker.engineStarted(at(3, 8), 101, listOf(record), engine, bootTime = null, intent = intent)
    assertTrue(early.gaps.isEmpty())
    assertTrue(record.key in early.seenExits)
    // Dead over two occurrences: two gaps, same reason.
    val late = tracker.engineStarted(at(4, 10), 101, listOf(record), engine, bootTime = null, intent = intent)
    assertEquals(
      listOf(Triple(at(3, 9), at(3, 17), EnforcementGap.Reason.stoppedByUser), Triple(at(4, 9), at(4, 10), EnforcementGap.Reason.stoppedByUser)),
      late.gaps.shapes(),
    )
    // A stale heartbeat (no record) is clipped the same way.
    val silent = tracker.engineStarted(at(3, 10), 101, emptyList(), engine, bootTime = null, intent = intent)
    assertEquals(listOf(Triple(at(3, 9), at(3, 10), EnforcementGap.Reason.killedBySystem)), silent.gaps.shapes())
  }

  @Test
  fun `an open gap is read clipped - nothing before the window, open inside it, closed after it`() {
    val intent = EnforcementIntent.of(null, workHours(at(2, 8)), zone)
    val stop = at(2, 20)
    val dead = EnforcementGapTracker().engineStarted(at(2, 8), 100, emptyList(), engine, bootTime = null, intent = intent)
      .recordExits(listOf(ExitRecord(ExitReasons.USER_REQUESTED, stop, 100, engine, "stop")), engine, stop + minute, intent)
    // Stored open from the stop.
    assertEquals(listOf(Triple(stop, null, EnforcementGap.Reason.stoppedByUser)), dead.gaps.shapes())
    assertTrue(dead.gaps(since = null, now = at(3, 8), intent = intent).isEmpty())
    assertEquals(listOf(Triple(at(3, 9), null, EnforcementGap.Reason.stoppedByUser)), dead.gaps(since = null, now = at(3, 10), intent = intent).shapes())
    assertEquals(listOf(Triple(at(3, 9), at(3, 17), EnforcementGap.Reason.stoppedByUser)), dead.gaps(since = null, now = at(3, 18), intent = intent).shapes())
    // At the very end of the occurrence the gap is closed (end is exclusive); one ms before, open.
    assertEquals(at(3, 17), dead.gaps(since = null, now = at(3, 17), intent = intent).single().to)
    assertNull(dead.gaps(since = null, now = at(3, 17) - 1, intent = intent).single().to)
    // `since` applies after the clipping: the closed piece is before a later `since`, the open piece is kept at any `since` (C09).
    assertTrue(dead.gaps(since = at(3, 18), now = at(3, 18), intent = intent).isEmpty())
    assertEquals(1, dead.gaps(since = at(3, 10), now = at(3, 10), intent = intent).size)
    assertEquals(1, dead.gaps(since = at(3, 10) + 5_000, now = at(3, 10), intent = intent).size)
    // With the manual block, the whole dead period, open.
    assertEquals(listOf(Triple(stop, null, EnforcementGap.Reason.stoppedByUser)), dead.gaps(since = null, now = at(3, 10), intent = EnforcementIntent.ALWAYS).shapes())
  }

  @Test
  fun `a permission gap open across stopMonitoring of one of two activities keeps the stopped activity's hours`() {
    // A (09:00–17:00) and B (20:00–22:00) monitored; usage access revoked at 10:00; A stopped at 12:00; access back at 13:00.
    val both = MonitoringState().start(config("a", 9, 17), at(2, 8), zone, selections).state.start(config("b", 20, 22), at(2, 8), zone, selections).state
    val intentBoth = EnforcementIntent.of(null, both, zone)
    val revoked = EnforcementGapTracker().engineStarted(at(2, 8), 100, emptyList(), engine, bootTime = null, intent = intentBoth)
      .observePermissions(true, at(2, 10) - minute, intent = intentBoth)
      .observePermissions(false, at(2, 10), listOf("usageAccess"), intentBoth)
    // The mutation settles with the intent before it: the 10:00–12:00 hole is committed, the open record goes on from 12:00.
    val settled = revoked.settle(at(2, 12), intentBoth)
    assertEquals(
      listOf(Triple(at(2, 10) - minute, at(2, 12), EnforcementGap.Reason.permissionRevoked), Triple(at(2, 12), null, EnforcementGap.Reason.permissionRevoked)),
      settled.gaps.shapes(),
    )
    val onlyB = EnforcementIntent.of(null, both.stop("a", at(2, 12)).state, zone)
    val granted = settled.observePermissions(true, at(2, 13), intent = onlyB)
    // Nothing of B's between 12:00 and 13:00: the hole is A's hours only, one gap (the pieces share an origin).
    assertEquals(listOf(Triple(at(2, 10) - minute, at(2, 12), EnforcementGap.Reason.permissionRevoked)), granted.gaps.shapes())
    // Read before the grant: the settled piece and the open record merge into one open gap under the manual intent, none under B's.
    assertEquals(listOf(Triple(at(2, 10) - minute, null, EnforcementGap.Reason.permissionRevoked)), settled.gaps(since = null, now = at(2, 12) + minute, intent = EnforcementIntent.ALWAYS).shapes())
    assertEquals(listOf(Triple(at(2, 10) - minute, at(2, 12), EnforcementGap.Reason.permissionRevoked)), settled.gaps(since = null, now = at(2, 12) + minute, intent = onlyB).shapes())
    // Without the settlement the hole would have been judged by the later intent and lost.
    assertTrue(revoked.observePermissions(true, at(2, 13), intent = onlyB).gaps.isEmpty())
  }

  @Test
  fun `unblockAll with activities left keeps the lifted occurrence's hours before the lift`() {
    // Registered at 08:00; the engine recorded Monday's start at 09:00 (the marker `unblockAll` lifts).
    val running = workHours(at(2, 8)).schedulesAt(at(2, 9), zone, selections)
    assertTrue(running.intervals.containsKey("work"))
    val intent = EnforcementIntent.of(null, running, zone)
    val stop = at(2, 10)
    val dead = EnforcementGapTracker().engineStarted(at(2, 8), 100, emptyList(), engine, bootTime = null, intent = intent)
      .recordExits(listOf(ExitRecord(ExitReasons.USER_REQUESTED, stop, 100, engine, "stop")), engine, stop + minute, intent)
    // unblockAll at 12:00 lifts Monday's occurrence; the engine comes back at 14:00 under the lifted intent.
    val lifted = EnforcementIntent.of(null, running.lift(), zone)
    val back = dead.settle(at(2, 12), intent).engineStarted(at(2, 14), 101, emptyList(), engine, bootTime = null, intent = lifted)
    assertEquals(listOf(Triple(stop, at(2, 12), EnforcementGap.Reason.stoppedByUser)), back.gaps.shapes())
    // An engine gap settled in two pieces is one gap again when the pieces touch.
    val back2 = dead.settle(at(2, 12), intent).engineStarted(at(2, 14), 101, emptyList(), engine, bootTime = null, intent = intent)
    assertEquals(listOf(Triple(stop, at(2, 14), EnforcementGap.Reason.stoppedByUser)), back2.gaps.shapes())
  }

  @Test
  fun `an activity registered while a gap is open adds nothing before its registration`() {
    val stop = at(2, 10)
    var dead = EnforcementGapTracker().engineStarted(at(2, 7), 100, emptyList(), engine, bootTime = null)
      .recordExits(listOf(ExitRecord(ExitReasons.USER_REQUESTED, stop, 100, engine, "stop")), engine, stop + minute, EnforcementIntent.NEVER)
    // Registered at 12:00 (the mutation settles with the intent before: nothing), the engine starts at 13:00 under the new intent.
    dead = dead.settle(at(2, 12), EnforcementIntent.NEVER)
    val registered = EnforcementIntent.of(null, workHours(at(2, 12)), zone)
    assertEquals(listOf(at(2, 12) to at(2, 17)), registered.windows(at(2, 0), at(2, 20)).pairs())
    val back = dead.engineStarted(at(2, 13), 101, emptyList(), engine, bootTime = null, intent = registered)
    assertEquals(listOf(Triple(at(2, 12), at(2, 13), EnforcementGap.Reason.stoppedByUser)), back.gaps.shapes())
    // A permission gap from 08:00 with the activity registered at 12:00: the hole starts at the registration.
    val revoked = EnforcementGapTracker().engineStarted(at(2, 7), 100, emptyList(), engine, bootTime = null)
      .observePermissions(true, at(2, 8) - minute).observePermissions(false, at(2, 8), listOf("overlay"))
    assertEquals(listOf(Triple(at(2, 12), at(2, 13), EnforcementGap.Reason.permissionRevoked)), revoked.observePermissions(true, at(2, 13), intent = registered).gaps.shapes())
  }

  @Test
  fun `an exit before the release, folded only at the release, closes at the release with the intent before it`() {
    val manual = EnforcementIntent.of(ManualBlock("s", BlockMode.blocklist, setOf("com.a")), MonitoringState(), zone)
    val died = at(2, 10)
    // Beating until 30 s before the death: at the release the heartbeat is not stale yet.
    val tracker = EnforcementGapTracker().engineStarted(at(2, 9), 100, emptyList(), engine, bootTime = null, intent = manual).heartbeat(died - 30_000, 100)
    val record = ExitRecord(ExitReasons.LOW_MEMORY, died, 100, engine, "lmk")
    // unblockAll 90 s later: the heartbeat is not stale, so only the record can tell; folded in the same update, before the stop.
    val released = tracker.recordExits(listOf(record), engine, died + 90_000, manual).engineStopped(died + 90_000, engineAlive = false, intent = manual)
    assertEquals(listOf(Triple(died, died + 90_000, EnforcementGap.Reason.killedBySystem)), released.gaps.shapes())
    assertFalse(released.run!!.isLive)
    // Folded after the release under an empty intent, the record would be clipped to nothing: the wiring folds it before.
    val late = tracker.engineStopped(died + 90_000, engineAlive = false, intent = manual).recordExits(listOf(record), engine, died + 2 * minute, EnforcementIntent.NEVER)
    assertTrue(late.gaps.isEmpty())
  }

  @Test
  fun `a release clips with the intent that held before it, and a permission gap is clipped too`() {
    val intent = EnforcementIntent.of(null, workHours(at(2, 8)), zone)
    val stop = at(2, 16)
    val dead = EnforcementGapTracker().engineStarted(at(2, 8), 100, emptyList(), engine, bootTime = null, intent = intent)
      .recordExits(listOf(ExitRecord(ExitReasons.USER_REQUESTED, stop, 100, engine, "stop")), engine, stop + minute, intent)
    // Released at 20:00 (the schedule stopped): the gap is the hour left of the occurrence.
    val released = dead.engineStopped(at(2, 20), engineAlive = false, intent = intent)
    assertEquals(listOf(Triple(stop, at(2, 17), EnforcementGap.Reason.stoppedByUser)), released.gaps.shapes())
    assertFalse(released.run!!.isLive)
    // A permission revoked at 19:00 Monday, seen back at 10:00 Tuesday: the hour of Tuesday's occurrence.
    val revoked = EnforcementGapTracker().engineStarted(at(2, 8), 100, emptyList(), engine, bootTime = null, intent = intent)
      .observePermissions(true, at(2, 19), intent = intent)
      .observePermissions(false, at(2, 19) + minute, listOf("usageAccess"), intent)
    assertEquals(listOf(Triple(at(2, 19), null, EnforcementGap.Reason.permissionRevoked)), revoked.gaps.shapes())
    assertTrue(revoked.gaps(since = null, now = at(3, 8), intent = intent).isEmpty())
    val granted = revoked.observePermissions(true, at(3, 10), intent = intent)
    assertEquals(listOf(Triple(at(3, 9), at(3, 10), EnforcementGap.Reason.permissionRevoked)), granted.gaps.shapes())
    assertFalse(granted.hasOpenPermissionGap)
    // The step-set overload clips the same way.
    val stepped = revoked.observePermissions(setOf(PermissionSteps.StepId.usageAccess, PermissionSteps.StepId.overlay), at(3, 10), intent)
    assertEquals(granted.gaps, stepped.gaps)
  }
}
