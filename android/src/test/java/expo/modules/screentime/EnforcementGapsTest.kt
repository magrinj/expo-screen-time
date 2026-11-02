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
    // Inside a start, the skewed record and the stale estimate are still one gap.
    val back = tracker.engineStarted(runStart + 20 * minute, 101, listOf(stopApp(runStart + 25 * minute, 100)), engine, bootTime = null)
    assertEquals(listOf(Triple(runStart + 20 * minute, runStart + 20 * minute, Reason.stoppedByUser)), back.gaps.shapes())
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
