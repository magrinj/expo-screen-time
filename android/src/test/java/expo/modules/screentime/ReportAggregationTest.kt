package expo.modules.screentime

import expo.modules.screentime.UsageEventKind.ACTIVITY_PAUSED
import expo.modules.screentime.UsageEventKind.ACTIVITY_RESUMED
import expo.modules.screentime.UsageEventKind.ACTIVITY_STOPPED
import expo.modules.screentime.UsageEventKind.DEVICE_SHUTDOWN
import expo.modules.screentime.UsageEventKind.DEVICE_STARTUP
import expo.modules.screentime.UsageEventKind.KEYGUARD_HIDDEN
import expo.modules.screentime.UsageEventKind.KEYGUARD_SHOWN
import expo.modules.screentime.UsageEventKind.SCREEN_INTERACTIVE
import expo.modules.screentime.UsageEventKind.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The report aggregation: every rule of [ReportAggregator] on synthetic
 * streams, and a real stream dumped from the `est_features_api37` emulator
 * (Android 17, `adb shell dumpsys usagestats`, 2026-11-04 09:08–09:11 CET)
 * after a few minutes of use: app switches, screen off and on, a permission
 * dialog, the example app. That device has no lock screen, so the stream
 * has no keyguard event at all.
 */
class ReportAggregationTest {
  private val window = ReportWindow.of("daily", at(2026, 11, 4, 12, 0, zone = PARIS), PARIS, Locale.US)
  private val filter = ReportAppFilter(launchers = setOf(LAUNCHER), hostPackage = HOST)

  private fun t(h: Int, m: Int, s: Int = 0, day: Int = 4) = at(2026, 11, day, h, m, s, zone = PARIS)
  private fun resumed(pkg: String, at: Long, cls: String? = "Main") = UsageEvent(at, pkg, ACTIVITY_RESUMED, cls)
  private fun paused(pkg: String, at: Long, cls: String? = "Main") = UsageEvent(at, pkg, ACTIVITY_PAUSED, cls)
  private fun stopped(pkg: String, at: Long, cls: String? = "Main") = UsageEvent(at, pkg, ACTIVITY_STOPPED, cls)
  private fun device(kind: UsageEventKind, at: Long) = UsageEvent(at, "android", kind)
  private fun aggregate(vararg events: UsageEvent, nowMs: Long = t(23, 59, 59), filter: ReportAppFilter = this.filter, window: ReportWindow = this.window, categories: ReportCategoryResolver = ReportCategoryResolver.NONE, historyStartMs: Long? = null) =
    ReportAggregator(window, filter, categories).addAll(events.toList()).result(nowMs, historyStartMs)
  private fun ReportData.app(pkg: String) = topApps.firstOrNull { it.packageName == pkg }?.durationMs ?: 0L
  private fun hourly(vararg pairs: Pair<Int, Long>) = LongArray(24).also { for ((h, ms) in pairs) it[h] = ms }

  // MARK: The real stream

  @Test
  fun `the emulator stream - totals, apps, longest run, pickups of a device without a lock screen`() {
    val data = ReportAggregator(window, filter).addAll(FIXTURE).result(t(9, 11, 29))
    // Union of the sessions: settings 09:09:26–34, chrome –46, clock 09:09:50–57, calendar –58, gms –09:10:08,
    // clock –13 (screen off), clock 09:10:22–28, contacts (three zero-length), example 09:11:03–10.
    assertEquals(56 * SECOND, data.totalDurationMs)
    assertEquals(
      listOf("com.google.android.deskclock" to 18L, "com.android.chrome" to 12L, "com.google.android.gms" to 10L, "com.android.settings" to 8L, HOST to 7L, "com.google.android.calendar" to 1L),
      data.topApps.map { it.packageName to it.durationMs / SECOND },
    )
    assertEquals("Contacts had three zero-length sessions and the launcher never counts", 6, data.appCount)
    assertEquals("09:09:50 → 09:10:13 with no gap between the apps: one session of 23 s", 23 * SECOND, data.longestActivityMs)
    assertEquals(hourly(9 to 56 * SECOND).toList(), data.hourlyMs.toList())
    // Screen-on sessions: boot 09:08:36 (→ Settings), 09:10:22 (→ Clock), 09:10:37 (launcher only), 09:10:50 (→ Contacts).
    assertEquals("the iOS binding: pickups without application activity", 1, data.pickups)
    assertEquals(3, data.pickupsWithApplicationActivity)
    assertEquals(4, data.totalPickups)
    assertEquals(t(9, 8, 36), data.firstPickupMs)
    assertEquals(1, data.topApps.first { it.packageName == "com.android.settings" }.pickups)
    assertEquals(1, data.topApps.first { it.packageName == "com.google.android.deskclock" }.pickups)
    assertEquals(listOf(ReportCategoryUsage("Other", 56 * SECOND)), data.categories)
    assertEquals(91, FIXTURE.size)
    assertEquals(window.startMs, data.coverage.coveredFromMs)
    assertEquals(t(9, 11, 29), data.coverage.coveredToMs)
  }

  @Test
  fun `the emulator stream - the permission dialog counts when not exempt, and its old instance's stop does not close the new one`() {
    val counting = ReportAppFilter(launchers = setOf(LAUNCHER), exempt = setOf(ReportAppFilter.SYSTEM_UI), hostPackage = HOST)
    val data = ReportAggregator(window, counting).addAll(FIXTURE).result(t(9, 11, 29))
    assertEquals("GrantPermissionsActivity 09:10:57 → 09:11:03", 6 * SECOND, data.app("com.google.android.permissioncontroller"))
    assertEquals(62 * SECOND, data.totalDurationMs)
    assertEquals(7, data.appCount)
    assertEquals(23 * SECOND, data.longestActivityMs)
    assertEquals("the pickup at 09:10:50 still goes to the first app, Contacts", 3, data.pickupsWithApplicationActivity)
  }

  // MARK: Clipping

  @Test
  fun `an app open at the window start counts from the start - the lookback establishes it`() {
    val data = aggregate(resumed("com.a", t(23, 50, day = 3)), paused("com.a", t(0, 20)))
    assertEquals(20 * MINUTE, data.totalDurationMs)
    assertEquals(20 * MINUTE, data.app("com.a"))
    assertEquals(hourly(0 to 20 * MINUTE).toList(), data.hourlyMs.toList())
    assertEquals(20 * MINUTE, data.longestActivityMs)
    assertEquals("a pickup before the window is not the window's", 0, data.totalPickups)
  }

  @Test
  fun `a missing opening event loses the stretch - the orphan pause is ignored, never negative`() {
    val data = aggregate(paused("com.a", t(0, 20)), resumed("com.a", t(1, 0)), paused("com.a", t(1, 10)))
    assertEquals(10 * MINUTE, data.totalDurationMs)
    assertEquals(hourly(1 to 10 * MINUTE).toList(), data.hourlyMs.toList())
  }

  @Test
  fun `an app still open counts until now, and never past the window end`() {
    val open = arrayOf(resumed("com.a", t(9, 0)))
    assertEquals(30 * MINUTE, aggregate(*open, nowMs = t(9, 30)).totalDurationMs)
    assertEquals("now beyond the window: counted to midnight", 15 * HOUR, aggregate(*open, nowMs = t(1, 0, day = 5)).totalDurationMs)
    assertEquals(15 * HOUR, aggregate(*open, nowMs = t(1, 0, day = 5)).app("com.a"))
    assertEquals("events after the window end change nothing", 0L, aggregate(resumed("com.a", t(0, 10, day = 5)), nowMs = t(1, 0, day = 5)).totalDurationMs)
  }

  @Test
  fun `sessions are clipped at the hour - the iOS per-segment artefacts`() {
    val data = aggregate(resumed("com.a", t(9, 30)), paused("com.a", t(11, 30)))
    assertEquals(2 * HOUR, data.totalDurationMs)
    assertEquals(2 * HOUR, data.app("com.a"))
    assertEquals(hourly(9 to 30 * MINUTE, 10 to HOUR, 11 to 30 * MINUTE).toList(), data.hourlyMs.toList())
    assertEquals("longestActivity is the max over hourly segments: one hour, not two", HOUR, data.longestActivityMs)
  }

  @Test
  fun `a weekly window sums each hour of day over the days`() {
    val weekly = ReportWindow.of("weekly", t(12, 0), PARIS, Locale.US)
    val data = aggregate(resumed("com.a", t(9, 0, day = 2)), paused("com.a", t(9, 30, day = 2)), resumed("com.a", t(9, 10, day = 4)), paused("com.a", t(9, 50, day = 4)), window = weekly, nowMs = t(12, 0, day = 5))
    assertEquals(70 * MINUTE, data.totalDurationMs)
    assertEquals(hourly(9 to 70 * MINUTE).toList(), data.hourlyMs.toList())
    assertEquals(40 * MINUTE, data.longestActivityMs)
    assertEquals(70 * MINUTE, data.app("com.a"))
  }

  // MARK: Overlaps and closing

  @Test
  fun `two apps at once count once in the total and fully for each app`() {
    val data = aggregate(resumed("com.a", t(10, 0)), resumed("com.b", t(10, 5)), paused("com.a", t(10, 20)), paused("com.b", t(10, 30)))
    assertEquals(30 * MINUTE, data.totalDurationMs)
    assertEquals(20 * MINUTE, data.app("com.a"))
    assertEquals(25 * MINUTE, data.app("com.b"))
    assertEquals("the apps add up to more than the total, as on iOS", 45 * MINUTE, data.topApps.sumOf { it.durationMs })
    assertEquals(30 * MINUTE, data.longestActivityMs)
    assertEquals(listOf("com.b", "com.a"), data.topApps.map { it.packageName })
  }

  @Test
  fun `switching apps is one session for the longest activity, a gap ends it`() {
    val contiguous = aggregate(resumed("com.a", t(10, 0)), paused("com.a", t(10, 20)), resumed("com.b", t(10, 20)), paused("com.b", t(10, 40)))
    assertEquals(40 * MINUTE, contiguous.longestActivityMs)
    // Real streams have millisecond stamps: the next app resumes a few hundred ms after the last paused.
    val switched = aggregate(resumed("com.a", t(10, 0)), paused("com.a", t(10, 20)), resumed("com.b", t(10, 20) + 180), paused("com.b", t(10, 40)))
    assertEquals("the switch gap is inside the session, as in a date interval", 40 * MINUTE, switched.longestActivityMs)
    assertEquals(40 * MINUTE - 180, switched.totalDurationMs)
    val coldStart = aggregate(resumed("com.a", t(10, 0)), paused("com.a", t(10, 20)), resumed("com.b", t(10, 20) + ReportAggregator.SESSION_GAP_MS), paused("com.b", t(10, 40)))
    assertEquals(40 * MINUTE, coldStart.longestActivityMs)
    val gap = aggregate(resumed("com.a", t(10, 0)), paused("com.a", t(10, 20)), resumed("com.b", t(10, 21)), paused("com.b", t(10, 40)))
    assertEquals("a minute on the launcher ends the session", 20 * MINUTE, gap.longestActivityMs)
    assertEquals(39 * MINUTE, gap.totalDurationMs)
    val acrossHour = aggregate(resumed("com.a", t(9, 50)), paused("com.a", t(9, 59, 59)), resumed("com.b", t(10, 0)), paused("com.b", t(10, 20)))
    assertEquals("never across a segment edge: 20 min in hour 10, not 30", 20 * MINUTE, acrossHour.longestActivityMs)
  }

  @Test
  fun `a package stays in the foreground while any of its activities is resumed`() {
    val data = aggregate(resumed("com.a", t(10, 0), "A"), paused("com.a", t(10, 5), "A"), resumed("com.a", t(10, 5), "B"), stopped("com.a", t(10, 6), "A"), paused("com.a", t(10, 20), "B"))
    assertEquals(20 * MINUTE, data.app("com.a"))
    assertEquals(20 * MINUTE, data.longestActivityMs)
    assertEquals("an unknown class is tracked under the empty key", 10 * MINUTE, aggregate(resumed("com.a", t(10, 0), null), paused("com.a", t(10, 10), null)).app("com.a"))
  }

  @Test
  fun `a stop never closes - it follows a pause, or belongs to an older instance of the class`() {
    val relaunched = aggregate(resumed("com.a", t(10, 0), "X"), stopped("com.a", t(10, 1), "X"), paused("com.a", t(10, 10), "X"))
    assertEquals(10 * MINUTE, relaunched.app("com.a"))
    val normal = aggregate(resumed("com.a", t(10, 0)), paused("com.a", t(10, 10)), stopped("com.a", t(10, 11)))
    assertEquals(10 * MINUTE, normal.app("com.a"))
  }

  @Test
  fun `screen off, keyguard and shutdown close every span - and a missing pause is closed by them`() {
    val screenOff = aggregate(resumed("com.a", t(10, 0)), resumed("com.b", t(10, 10)), device(SCREEN_NON_INTERACTIVE, t(10, 30)), paused("com.a", t(10, 30, 1)), stopped("com.a", t(10, 30, 1)))
    assertEquals("com.a never paused before the screen went off: closed there", 30 * MINUTE, screenOff.app("com.a"))
    assertEquals(20 * MINUTE, screenOff.app("com.b"))
    assertEquals(30 * MINUTE, screenOff.totalDurationMs)
    assertEquals("the trailing pause after the screen-off is an orphan", 30 * MINUTE, screenOff.longestActivityMs)
    assertEquals(5 * MINUTE, aggregate(resumed("com.a", t(10, 0)), device(KEYGUARD_SHOWN, t(10, 5))).totalDurationMs)
    assertEquals(7 * MINUTE, aggregate(resumed("com.a", t(10, 0)), device(DEVICE_SHUTDOWN, t(10, 7))).totalDurationMs)
    val reboot = aggregate(resumed("com.a", t(10, 0)), device(DEVICE_STARTUP, t(10, 9)), resumed("com.b", t(10, 10)), paused("com.b", t(10, 12)))
    assertEquals("a boot without a shutdown closes at the boot", 9 * MINUTE, reboot.app("com.a"))
    assertEquals(11 * MINUTE, reboot.totalDurationMs)
    assertEquals("nothing reopens on screen-on by itself", 0L, aggregate(device(SCREEN_INTERACTIVE, t(10, 0))).totalDurationMs)
  }

  // MARK: Pickups

  @Test
  fun `pickups on a device with a lock screen - the unlock counts, a glance does not, one per screen-on`() {
    val data = aggregate(
      device(KEYGUARD_SHOWN, t(8, 0)),
      // A glance: screen on, never unlocked.
      device(SCREEN_INTERACTIVE, t(9, 0)), device(SCREEN_NON_INTERACTIVE, t(9, 1)),
      // Unlock, open an app: a pickup with application activity.
      device(SCREEN_INTERACTIVE, t(9, 10)), device(KEYGUARD_HIDDEN, t(9, 10, 5)), resumed("com.a", t(9, 11)), paused("com.a", t(9, 19)),
      device(SCREEN_NON_INTERACTIVE, t(9, 20)), device(KEYGUARD_SHOWN, t(9, 20)),
      // Unlock to the home screen only: without application activity.
      device(SCREEN_INTERACTIVE, t(9, 30)), device(KEYGUARD_HIDDEN, t(9, 30, 2)), resumed(LAUNCHER, t(9, 30, 2)),
      device(SCREEN_NON_INTERACTIVE, t(9, 35)),
      // The lock timeout had not elapsed: no keyguard, the screen-on itself is the pickup.
      device(SCREEN_INTERACTIVE, t(9, 40)), resumed(LAUNCHER, t(9, 40)), device(SCREEN_NON_INTERACTIVE, t(9, 45)),
    )
    assertEquals(2, data.pickups)
    assertEquals(1, data.pickupsWithApplicationActivity)
    assertEquals(3, data.totalPickups)
    assertEquals("the first pickup is the unlock, not the glance", t(9, 10, 5), data.firstPickupMs)
    assertEquals(1, data.topApps.single().pickups)
  }

  @Test
  fun `a screen-on then an unlock in one session is one pickup, and a pending pickup at the end counts without an app`() {
    val once = aggregate(device(SCREEN_INTERACTIVE, t(9, 0)), device(KEYGUARD_HIDDEN, t(9, 0, 3)), device(SCREEN_NON_INTERACTIVE, t(9, 5)))
    assertEquals(1, once.totalPickups)
    val pending = aggregate(device(SCREEN_INTERACTIVE, t(9, 0)), nowMs = t(9, 2))
    assertEquals("the person is on the lock screen or launcher right now: a pickup without app activity so far", 1, pending.pickups)
    assertEquals(0, pending.pickupsWithApplicationActivity)
    assertEquals(t(9, 0), pending.firstPickupMs)
  }

  @Test
  fun `without a lock screen every screen-on is a pickup, attributed to the first app`() {
    val data = aggregate(
      device(SCREEN_INTERACTIVE, t(9, 0)), resumed(LAUNCHER, t(9, 0)), resumed("com.a", t(9, 1)), paused("com.a", t(9, 2)), resumed("com.b", t(9, 2)), paused("com.b", t(9, 3)),
      device(SCREEN_NON_INTERACTIVE, t(9, 3)),
      device(SCREEN_INTERACTIVE, t(9, 30)), device(SCREEN_NON_INTERACTIVE, t(9, 31)),
    )
    assertEquals(1, data.pickups)
    assertEquals(1, data.pickupsWithApplicationActivity)
    assertEquals(listOf("com.a" to 1, "com.b" to 0), data.topApps.map { it.packageName to it.pickups })
    assertNull("no screen events at all (API < 28): no pickups, no first pickup", aggregate(resumed("com.a", t(9, 0)), paused("com.a", t(9, 5))).firstPickupMs)
  }

  @Test
  fun `the first pickup is the earliest across segments, and a pickup pending across an hour edge is still attributed`() {
    val data = aggregate(
      device(SCREEN_INTERACTIVE, t(9, 15)), device(SCREEN_NON_INTERACTIVE, t(9, 16)),
      device(SCREEN_INTERACTIVE, t(14, 59, 50)), resumed("com.a", t(15, 0, 10)), paused("com.a", t(15, 5)), device(SCREEN_NON_INTERACTIVE, t(15, 6)),
    )
    assertEquals(t(9, 15), data.firstPickupMs)
    assertEquals(1, data.pickups)
    assertEquals(1, data.pickupsWithApplicationActivity)
    assertEquals(1, data.topApps.single().pickups)
  }

  @Test
  fun `a stray earlier timestamp is clamped to the stream, never counted backwards`() {
    val data = aggregate(
      device(SCREEN_INTERACTIVE, t(14, 30)), device(SCREEN_NON_INTERACTIVE, t(14, 31)),
      device(SCREEN_INTERACTIVE, t(9, 15)), device(SCREEN_NON_INTERACTIVE, t(9, 16)),
    )
    // The second pair is out of order on purpose: it reads as 14:31.
    assertEquals(2, data.pickups)
    assertEquals(t(14, 30), data.firstPickupMs)
    assertEquals(0L, data.totalDurationMs)
  }

  @Test
  fun `unlocking straight into the app on screen is a pickup made to it - the resume precedes KEYGUARD_HIDDEN`() {
    // AOSP resumes the top activity when the keyguard starts going away and reports KEYGUARD_HIDDEN after the exit animation.
    val data = aggregate(
      device(KEYGUARD_SHOWN, t(8, 0)),
      device(SCREEN_INTERACTIVE, t(9, 0)), resumed("com.a", t(9, 0, 1)), device(KEYGUARD_HIDDEN, t(9, 0, 2)),
      paused("com.a", t(9, 10)), device(SCREEN_NON_INTERACTIVE, t(9, 10)), device(KEYGUARD_SHOWN, t(9, 10)),
    )
    assertEquals(0, data.pickups)
    assertEquals(1, data.pickupsWithApplicationActivity)
    assertEquals(1, data.topApps.single().pickups)
    assertEquals(t(9, 0, 2), data.firstPickupMs)
    assertEquals("the app counts from its resume, keyguard or not", 10 * MINUTE - SECOND, data.totalDurationMs)
  }

  @Test
  fun `a pickup assumed while the keyguard state was unknown is taken back when the lock screen shows`() {
    // A boot: screen on, the lock screen, then the real unlock into the launcher and an app.
    val boot = aggregate(
      device(SCREEN_INTERACTIVE, t(8, 0)), resumed(LAUNCHER, t(8, 0)), device(DEVICE_STARTUP, t(8, 0, 1)), device(KEYGUARD_SHOWN, t(8, 0, 2)),
      device(KEYGUARD_HIDDEN, t(8, 1)), resumed("com.a", t(8, 2)), paused("com.a", t(8, 5)), device(SCREEN_NON_INTERACTIVE, t(8, 5)),
    )
    assertEquals(0, boot.pickups)
    assertEquals(1, boot.pickupsWithApplicationActivity)
    assertEquals("the unlock, not the boot's screen-on", t(8, 1), boot.firstPickupMs)
    // Same with the window's first screen-on, when the lookback had no keyguard event.
    val unknown = aggregate(device(SCREEN_INTERACTIVE, t(9, 0)), device(KEYGUARD_SHOWN, t(9, 0, 1)), device(SCREEN_NON_INTERACTIVE, t(9, 0, 30)))
    assertEquals("a glance at a locked device", 0, unknown.totalPickups)
    assertNull(unknown.firstPickupMs)
    val earlier = aggregate(
      device(SCREEN_INTERACTIVE, t(9, 0)), device(SCREEN_NON_INTERACTIVE, t(9, 1)),
      device(SCREEN_INTERACTIVE, t(9, 30)), device(KEYGUARD_SHOWN, t(9, 30, 1)), device(SCREEN_NON_INTERACTIVE, t(9, 31)),
    )
    assertEquals("retracting restores the segment's earlier first pickup", t(9, 0), earlier.firstPickupMs)
    assertEquals(1, earlier.totalPickups)
  }

  @Test
  fun `the autumn DST day files both hour-2 segments under hourly 2`() {
    val dst = ReportWindow.of("daily", at(2026, 10, 25, 12, 0, zone = PARIS), PARIS, Locale.US)
    // 02:30 CEST → 02:30 CET is one hour of use spanning the repeated hour.
    val start = at(2026, 10, 25, 2, 30, zone = PARIS)
    val data = aggregate(resumed("com.a", start), paused("com.a", start + HOUR), window = dst, nowMs = at(2026, 10, 25, 12, 0, zone = PARIS))
    assertEquals(HOUR, data.totalDurationMs)
    assertEquals(hourly(2 to HOUR).toList(), data.hourlyMs.toList())
    assertEquals("two 30-minute segment pieces", 30 * MINUTE, data.longestActivityMs)
  }

  @Test
  fun `a window still in the future, and reading twice`() {
    val future = aggregate(resumed("com.a", t(9, 0, day = 3)), nowMs = t(10, 0, day = 3))
    assertEquals(0L, future.totalDurationMs)
    assertEquals(0L, future.coverage.coveredMs)
    assertNull(future.firstPickupMs)
    val aggregator = ReportAggregator(window, filter).addAll(listOf(resumed("com.a", t(9, 0))))
    assertEquals(30 * MINUTE, aggregator.result(t(9, 30)).totalDurationMs)
    assertEquals("a later reading continues the open span", 45 * MINUTE, aggregator.result(t(9, 45)).totalDurationMs)
    try {
      aggregator.add(paused("com.a", t(9, 50)))
      throw AssertionError("adding after result must fail")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("result"))
    }
  }

  // MARK: Apps

  @Test
  fun `the launcher, SystemUI, the permission controller and the module's own activities are not apps - the host otherwise is`() {
    val data = aggregate(
      resumed(LAUNCHER, t(9, 0)), paused(LAUNCHER, t(9, 10)),
      resumed(ReportAppFilter.SYSTEM_UI, t(9, 10)), paused(ReportAppFilter.SYSTEM_UI, t(9, 11)),
      resumed(ReportAppFilter.PERMISSION_CONTROLLER, t(9, 11)), paused(ReportAppFilter.PERMISSION_CONTROLLER, t(9, 12)),
      resumed(HOST, t(9, 12), ReportAppFilter.SHIELD_ACTIVITY), paused(HOST, t(9, 15), ReportAppFilter.SHIELD_ACTIVITY),
      resumed(HOST, t(9, 15), ReportAppFilter.PIP_EVICTOR_ACTIVITY), paused(HOST, t(9, 16), ReportAppFilter.PIP_EVICTOR_ACTIVITY),
      resumed(HOST, t(9, 16), "expo.modules.screentime.example.MainActivity"), paused(HOST, t(9, 20), "expo.modules.screentime.example.MainActivity"),
      UsageEvent(t(9, 20), null, ACTIVITY_RESUMED, "x"),
    )
    assertEquals(listOf(HOST to 4 * MINUTE), data.topApps.map { it.packageName to it.durationMs })
    assertEquals(4 * MINUTE, data.totalDurationMs)
    val everything = aggregate(resumed(LAUNCHER, t(9, 0)), paused(LAUNCHER, t(9, 10)), filter = ReportAppFilter(launchers = emptySet(), exempt = emptySet()))
    assertEquals("the sets are what decide: with none, the launcher is an app", 10 * MINUTE, everything.totalDurationMs)
    assertTrue(filter.counts("com.a", null))
    assertTrue(!filter.counts("", "x"))
    assertTrue(!filter.counts(null, "x"))
  }

  @Test
  fun `the host's exempt classes are the activities the manifest declares`() {
    val manifest = File("src/main/AndroidManifest.xml").readText()
    for (name in ReportAppFilter.HOST_EXEMPT_CLASSES) {
      assertTrue("$name is declared in the manifest", manifest.contains("android:name=\"$name\""))
    }
    assertEquals(setOf(ReportAppFilter.SHIELD_ACTIVITY, ReportAppFilter.PIP_EVICTOR_ACTIVITY), ReportAppFilter.HOST_EXEMPT_CLASSES)
  }

  @Test
  fun `apps are sorted by duration then name, zero-length sessions do not count as apps`() {
    val data = aggregate(
      resumed("com.b", t(9, 0)), paused("com.b", t(9, 10)),
      resumed("com.a", t(9, 10)), paused("com.a", t(9, 20)),
      resumed("com.c", t(9, 20)), paused("com.c", t(9, 35)),
      resumed("com.z", t(9, 35)), paused("com.z", t(9, 35)),
    )
    assertEquals(listOf("com.c", "com.a", "com.b"), data.topApps.map { it.packageName })
    assertEquals(3, data.appCount)
  }

  @Test
  fun `categories come from the resolver, unknown ones are Other, sorted by duration`() {
    val resolver = ReportCategoryResolver { if (it == "com.a" || it == "com.b") "Social" else if (it == "com.c") "Games" else null }
    val data = aggregate(
      resumed("com.a", t(9, 0)), paused("com.a", t(9, 10)),
      resumed("com.b", t(9, 10)), paused("com.b", t(9, 15)),
      resumed("com.c", t(9, 15), "G"), paused("com.c", t(9, 35), "G"),
      resumed("com.d", t(9, 35)), paused("com.d", t(9, 37)),
      categories = resolver,
    )
    assertEquals(listOf(ReportCategoryUsage("Games", 20 * MINUTE), ReportCategoryUsage("Social", 15 * MINUTE), ReportCategoryUsage("Other", 2 * MINUTE)), data.categories)
    assertTrue(aggregate(categories = resolver).categories.isEmpty())
  }

  // MARK: Coverage and scale

  @Test
  fun `coverage says how much of the window the history and the clock cover`() {
    val full = aggregate(nowMs = t(9, 0))
    assertEquals(window.startMs, full.coverage.coveredFromMs)
    assertEquals(t(9, 0), full.coverage.coveredToMs)
    assertEquals(9.0 / 24, full.coverage.fraction, 1e-9)
    assertTrue(full.coverage.historyComplete)
    val pruned = aggregate(nowMs = t(12, 0), historyStartMs = t(6, 0))
    assertEquals("the OS only has events from 06:00", t(6, 0), pruned.coverage.coveredFromMs)
    assertEquals(6 * HOUR, pruned.coverage.coveredMs)
    assertTrue(!pruned.coverage.historyComplete)
    val past = aggregate(nowMs = t(12, 0, day = 7), historyStartMs = t(0, 0, day = 1))
    assertEquals("history older than the window: complete", window.startMs, past.coverage.coveredFromMs)
    assertEquals(window.endMs, past.coverage.coveredToMs)
    assertEquals(1.0, past.coverage.fraction, 1e-9)
    val future = aggregate(nowMs = t(12, 0, day = 1))
    assertEquals("a window in the future covers nothing", 0L, future.coverage.coveredMs)
    val gone = aggregate(nowMs = t(12, 0, day = 7), historyStartMs = t(0, 0, day = 6))
    assertEquals("history that starts after the window: nothing covered", 0L, gone.coverage.coveredMs)
  }

  @Test
  fun `empty data has zeros, no first pickup and the window's coverage`() {
    val data = ReportData.empty(window, t(9, 0))
    assertEquals(0L, data.totalDurationMs)
    assertEquals(0, data.totalPickups)
    assertNull(data.firstPickupMs)
    assertTrue(data.topApps.isEmpty())
    assertTrue(data.categories.isEmpty())
    assertEquals(24, data.hourlyMs.size)
    assertEquals(t(9, 0), data.coverage.coveredToMs)
  }

  @Test
  fun `a week of a hundred thousand events is one linear pass`() {
    val weekly = ReportWindow.of("weekly", t(12, 0), PARIS, Locale.US)
    val events = ArrayList<UsageEvent>(100_000)
    var at = weekly.startMs
    var i = 0
    while (events.size < 100_000) {
      val pkg = "com.app${i % 40}"
      events.add(resumed(pkg, at))
      events.add(paused(pkg, at + 3_000))
      if (i % 25 == 0) events.add(device(SCREEN_NON_INTERACTIVE, at + 3_500))
      if (i % 25 == 1) events.add(device(SCREEN_INTERACTIVE, at))
      at += 6_000
      i++
    }
    val started = System.nanoTime()
    val data = ReportAggregator(weekly, filter).addAll(events).result(weekly.endMs)
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    assertEquals(3_000L * (events.count { it.kind == ACTIVITY_RESUMED }), data.totalDurationMs)
    assertEquals(40, data.appCount)
    assertTrue("100k events took $elapsedMs ms", elapsedMs < 5_000)
  }

  private companion object {
    const val SECOND = 1_000L
    const val MINUTE = 60_000L
    const val HOUR = 3_600_000L
    const val LAUNCHER = "com.google.android.apps.nexuslauncher"
    const val LAUNCHER_ACTIVITY = "com.google.android.apps.nexuslauncher.NexusLauncherActivity"
    const val HOST = "expo.modules.screentime.example"

    fun e(time: String, kind: UsageEventKind, pkg: String, cls: String?): UsageEvent {
      val (h, m, s) = time.split(":").map { it.toInt() }
      return UsageEvent(at(2026, 11, 4, h, m, s, zone = PARIS), pkg, kind, cls)
    }

    /** `adb shell dumpsys usagestats`, est_features_api37, 2026-11-04 (every event of the session, duplicates included). */
    val FIXTURE = listOf(
      e("09:08:36", SCREEN_INTERACTIVE, "android", null),
      e("09:08:36", ACTIVITY_RESUMED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:08:37", DEVICE_STARTUP, "android", null),
      e("09:09:25", ACTIVITY_PAUSED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:09:26", ACTIVITY_RESUMED, "com.android.settings", "com.android.settings.homepage.SettingsHomepageActivity"),
      e("09:09:28", ACTIVITY_STOPPED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:09:34", ACTIVITY_PAUSED, "com.android.settings", "com.android.settings.homepage.SettingsHomepageActivity"),
      e("09:09:34", ACTIVITY_RESUMED, "com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity"),
      e("09:09:34", ACTIVITY_PAUSED, "com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity"),
      e("09:09:34", ACTIVITY_RESUMED, "com.android.chrome", "org.chromium.chrome.browser.firstrun.FirstRunActivity"),
      e("09:09:36", ACTIVITY_STOPPED, "com.android.settings", "com.android.settings.homepage.SettingsHomepageActivity"),
      e("09:09:36", ACTIVITY_STOPPED, "com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity"),
      e("09:09:46", ACTIVITY_PAUSED, "com.android.chrome", "org.chromium.chrome.browser.firstrun.FirstRunActivity"),
      e("09:09:46", ACTIVITY_RESUMED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:09:47", ACTIVITY_STOPPED, "com.android.chrome", "org.chromium.chrome.browser.firstrun.FirstRunActivity"),
      e("09:09:50", ACTIVITY_PAUSED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:09:50", ACTIVITY_RESUMED, "com.google.android.deskclock", "com.android.deskclock.DeskClock"),
      e("09:09:51", ACTIVITY_STOPPED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:09:57", ACTIVITY_PAUSED, "com.google.android.deskclock", "com.android.deskclock.DeskClock"),
      e("09:09:57", ACTIVITY_RESUMED, "com.google.android.calendar", "com.android.calendar.event.LaunchInfoActivity"),
      e("09:09:57", ACTIVITY_PAUSED, "com.google.android.calendar", "com.android.calendar.event.LaunchInfoActivity"),
      e("09:09:57", ACTIVITY_RESUMED, "com.google.android.calendar", "com.google.android.calendar.allinone.AllInOneCalendarActivity"),
      e("09:09:58", ACTIVITY_PAUSED, "com.google.android.calendar", "com.google.android.calendar.allinone.AllInOneCalendarActivity"),
      e("09:09:58", ACTIVITY_RESUMED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.common.UnpackingRedirectActivity"),
      e("09:09:58", ACTIVITY_PAUSED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.common.UnpackingRedirectActivity"),
      e("09:09:58", ACTIVITY_RESUMED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.AccountIntroActivity"),
      e("09:09:58", ACTIVITY_PAUSED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.AccountIntroActivity"),
      e("09:09:58", ACTIVITY_RESUMED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.WrapperControlledActivity"),
      e("09:09:58", ACTIVITY_STOPPED, "com.google.android.calendar", "com.android.calendar.event.LaunchInfoActivity"),
      e("09:09:59", ACTIVITY_STOPPED, "com.google.android.deskclock", "com.android.deskclock.DeskClock"),
      e("09:09:59", ACTIVITY_PAUSED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.WrapperControlledActivity"),
      e("09:09:59", ACTIVITY_RESUMED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.PreAddAccountActivity"),
      e("09:10:00", ACTIVITY_STOPPED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.common.UnpackingRedirectActivity"),
      e("09:10:00", ACTIVITY_STOPPED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.AccountIntroActivity"),
      e("09:10:00", ACTIVITY_STOPPED, "com.google.android.calendar", "com.google.android.calendar.allinone.AllInOneCalendarActivity"),
      e("09:10:00", ACTIVITY_STOPPED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.WrapperControlledActivity"),
      e("09:10:00", ACTIVITY_PAUSED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.PreAddAccountActivity"),
      e("09:10:00", ACTIVITY_RESUMED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.WrapperControlledActivity"),
      e("09:10:00", ACTIVITY_PAUSED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.WrapperControlledActivity"),
      e("09:10:00", ACTIVITY_RESUMED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.minutemaid.MinuteMaidActivity"),
      e("09:10:02", ACTIVITY_STOPPED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.PreAddAccountActivity"),
      e("09:10:02", ACTIVITY_STOPPED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.addaccount.WrapperControlledActivity"),
      e("09:10:05", ACTIVITY_RESUMED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:08", ACTIVITY_PAUSED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.minutemaid.MinuteMaidActivity"),
      e("09:10:08", ACTIVITY_RESUMED, "com.google.android.deskclock", "com.android.deskclock.DeskClock"),
      e("09:10:09", ACTIVITY_STOPPED, "com.google.android.gms", "com.google.android.gms.auth.uiflows.minutemaid.MinuteMaidActivity"),
      e("09:10:09", ACTIVITY_PAUSED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:09", ACTIVITY_STOPPED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:13", SCREEN_NON_INTERACTIVE, "android", null),
      e("09:10:14", ACTIVITY_PAUSED, "com.google.android.deskclock", "com.android.deskclock.DeskClock"),
      e("09:10:14", ACTIVITY_STOPPED, "com.google.android.deskclock", "com.android.deskclock.DeskClock"),
      e("09:10:22", SCREEN_INTERACTIVE, "android", null),
      e("09:10:22", ACTIVITY_RESUMED, "com.google.android.deskclock", "com.android.deskclock.DeskClock"),
      e("09:10:28", ACTIVITY_PAUSED, "com.google.android.deskclock", "com.android.deskclock.DeskClock"),
      e("09:10:28", ACTIVITY_RESUMED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:29", ACTIVITY_STOPPED, "com.google.android.deskclock", "com.android.deskclock.DeskClock"),
      e("09:10:31", SCREEN_NON_INTERACTIVE, "android", null),
      e("09:10:31", ACTIVITY_PAUSED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:31", ACTIVITY_STOPPED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:37", SCREEN_INTERACTIVE, "android", null),
      e("09:10:37", ACTIVITY_RESUMED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:44", SCREEN_NON_INTERACTIVE, "android", null),
      e("09:10:45", ACTIVITY_PAUSED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:45", ACTIVITY_STOPPED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:50", SCREEN_INTERACTIVE, "android", null),
      e("09:10:50", ACTIVITY_RESUMED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:55", ACTIVITY_PAUSED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:56", ACTIVITY_RESUMED, "com.google.android.contacts", "com.google.android.apps.contacts.activities.PeopleActivity"),
      e("09:10:56", ACTIVITY_PAUSED, "com.google.android.contacts", "com.google.android.apps.contacts.activities.PeopleActivity"),
      e("09:10:56", ACTIVITY_RESUMED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"),
      e("09:10:56", ACTIVITY_PAUSED, "com.google.android.contacts", "com.google.android.apps.contacts.permission.RequestPermissionsActivity"),
      e("09:10:56", ACTIVITY_STOPPED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:10:56", ACTIVITY_PAUSED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"),
      e("09:10:56", ACTIVITY_RESUMED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"),
      e("09:10:56", ACTIVITY_STOPPED, "com.google.android.contacts", "com.google.android.apps.contacts.activities.PeopleActivity"),
      e("09:10:56", ACTIVITY_STOPPED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"),
      e("09:10:56", ACTIVITY_PAUSED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"),
      e("09:10:56", ACTIVITY_RESUMED, "com.google.android.contacts", "com.google.android.apps.contacts.permission.RequestPermissionsActivity"),
      e("09:10:56", ACTIVITY_PAUSED, "com.google.android.contacts", "com.google.android.apps.contacts.permission.RequestPermissionsActivity"),
      e("09:10:57", ACTIVITY_RESUMED, "com.google.android.contacts", "com.google.android.apps.contacts.activities.PeopleActivity"),
      e("09:10:57", ACTIVITY_PAUSED, "com.google.android.contacts", "com.google.android.apps.contacts.activities.PeopleActivity"),
      e("09:10:57", ACTIVITY_RESUMED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"),
      e("09:10:57", ACTIVITY_STOPPED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"),
      e("09:10:57", ACTIVITY_STOPPED, "com.google.android.contacts", "com.google.android.apps.contacts.permission.RequestPermissionsActivity"),
      e("09:11:03", ACTIVITY_PAUSED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"),
      e("09:11:03", ACTIVITY_RESUMED, "expo.modules.screentime.example", "expo.modules.screentime.example.MainActivity"),
      e("09:11:04", ACTIVITY_STOPPED, "com.google.android.contacts", "com.google.android.apps.contacts.activities.PeopleActivity"),
      e("09:11:04", ACTIVITY_STOPPED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"),
      e("09:11:10", ACTIVITY_PAUSED, "expo.modules.screentime.example", "expo.modules.screentime.example.MainActivity"),
      e("09:11:10", ACTIVITY_RESUMED, LAUNCHER, LAUNCHER_ACTIVITY),
      e("09:11:11", ACTIVITY_STOPPED, "expo.modules.screentime.example", "expo.modules.screentime.example.MainActivity"),
    )
  }
}
