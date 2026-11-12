package expo.modules.screentime

/**
 * What counts as an app in a usage report, the part that varies per device
 * and per host, so the wiring passes it in:
 *
 * - [launchers]: every home app (`SystemExemptions.launchers`), never an app
 *   the person "used";
 * - [exempt]: packages the report ignores. By default SystemUI and the
 *   permission controller (AOSP and Google builds), whose dialogs run under
 *   their own package inside the calling app's task (the fixture: six
 *   seconds of "Permission controller" while Contacts asked for contacts);
 *   the wiring may add the rest of `SystemExemptions` (the keyboard never
 *   resumes; the dialer and Settings are apps a person does use, so they
 *   are not exempt here);
 * - [hostPackage]: the host app counts like any other, EXCEPT for the
 *   module's own activities, which run under the host package
 *   (`AndroidManifest.xml`): the shield drawn over a blocked app and the
 *   picture-in-picture evictor are not the host being used.
 *
 * The class names are string constants, not `::class.java.name`, so the core
 * has no Android class to load; `ReportAggregationTest` checks they match.
 */
internal data class ReportAppFilter(
  val launchers: Set<String>,
  val exempt: Set<String> = DEFAULT_EXEMPT,
  val hostPackage: String? = null,
  val hostExemptClasses: Set<String> = HOST_EXEMPT_CLASSES,
) {
  fun counts(packageName: String?, className: String?): Boolean {
    if (packageName.isNullOrEmpty()) return false
    if (packageName in launchers || packageName in exempt) return false
    if (packageName == hostPackage && className != null && className in hostExemptClasses) return false
    return true
  }

  companion object {
    const val SYSTEM_UI = "com.android.systemui"
    const val PERMISSION_CONTROLLER = "com.android.permissioncontroller"
    const val GOOGLE_PERMISSION_CONTROLLER = "com.google.android.permissioncontroller"
    val DEFAULT_EXEMPT: Set<String> = setOf(SYSTEM_UI, PERMISSION_CONTROLLER, GOOGLE_PERMISSION_CONTROLLER)
    const val SHIELD_ACTIVITY = "expo.modules.screentime.ShieldActivity"
    const val PIP_EVICTOR_ACTIVITY = "expo.modules.screentime.PipEvictorActivity"
    val HOST_EXEMPT_CLASSES: Set<String> = setOf(SHIELD_ACTIVITY, PIP_EVICTOR_ACTIVITY)
  }
}

/**
 * The category of a package, the Android stand-in for
 * `ActivityCategory.localizedDisplayName`. Returns null when the package has
 * none; the aggregation then files it under "Other", as the iOS code does
 * (`category.category.localizedDisplayName ?? "Other"`). The wiring decides
 * the source: `ApplicationInfo.category` (API 26+, coarse and often
 * `CATEGORY_UNDEFINED`), a bundled table, or nothing.
 */
internal fun interface ReportCategoryResolver {
  fun categoryOf(packageName: String): String?

  companion object {
    /** No categories: every app is "Other". */
    val NONE = ReportCategoryResolver { null }
  }
}

/** One app's share of the report: the `TemplateAppData` of iOS (its `id` is the package; the label is resolved at render time). */
internal data class ReportAppUsage(val packageName: String, val durationMs: Long, val pickups: Int)

/** One category's share (`TemplateCategoryData`). */
internal data class ReportCategoryUsage(val name: String, val durationMs: Long)

/**
 * The values a report template binds to: `CustomTemplateData` of
 * `CustomTemplateReport.swift`, field for field, plus what Android can say
 * and iOS cannot ([pickupsWithApplicationActivity]). Durations
 * are milliseconds here; the resolver formats them as the iOS `TimeInterval`
 * seconds.
 *
 * The iOS numbers are per hourly segment, then combined
 * (`makeConfiguration`): this data reproduces those combinations on
 * purpose, artefacts included (see [ReportAggregator]).
 */
internal data class ReportData(
  /** Device activity in the window: the union of app sessions, an instant counted once however many apps share the screen. */
  val totalDurationMs: Long,
  /**
   * Device pickups: every time the screen woke up in the window, a glance at
   * the lock screen included (what Apple's Screen Time shows as pickups, and
   * what iOS binds as the total: pickups without application activity plus
   * every app's `numberOfPickups`).
   */
  val pickups: Int,
  /** Of [pickups], those during which an app came to the foreground (Apple's per-app `numberOfPickups`, summed). */
  val pickupsWithApplicationActivity: Int,
  /** The longest continuous device activity inside one hourly segment; never above one hour (iOS artefact). */
  val longestActivityMs: Long,
  /** Apps with activity in the window. */
  val appCount: Int,
  /** The earliest pickup in the window, or null. */
  val firstPickupMs: Long?,
  /** 24 buckets, milliseconds per hour of day; a weekly window sums the days per hour of day. */
  val hourlyMs: List<Long>,
  /** Apps by duration, descending. */
  val topApps: List<ReportAppUsage>,
  /** Categories by duration, descending. */
  val categories: List<ReportCategoryUsage>,
) {
  /** Of [pickups], those that saw no app: the lock-screen glances and the launcher-only unlocks (Apple's `totalPickupsWithoutApplicationActivity`). */
  val pickupsWithoutApplicationActivity: Int
    get() = pickups - pickupsWithApplicationActivity

  companion object {
    fun empty(window: ReportWindow, nowMs: Long): ReportData = ReportAggregator(window, ReportAppFilter(emptySet())).result(nowMs)
  }
}

/**
 * Computes [ReportData] from the UsageEvents stream of a [ReportWindow].
 * Pure and streaming: feed the events in stream order (`UsageEvents` is
 * chronological) with [add], read the result with [result]; a week of
 * events (about 100k) is one pass, with per-segment arrays for state.
 *
 * ### Events
 * [UsageEvent] / [UsageEventKind] are the engine's own (`UsageAccumulator.kt`),
 * whose constants are the public `UsageEvents.Event` values, checked against
 * `android-36/android.jar`: ACTIVITY_RESUMED 1, ACTIVITY_PAUSED 2,
 * ACTIVITY_STOPPED 23, SCREEN_INTERACTIVE 15, SCREEN_NON_INTERACTIVE 16,
 * KEYGUARD_SHOWN 17, KEYGUARD_HIDDEN 18, DEVICE_SHUTDOWN 26, DEVICE_STARTUP
 * 27. The screen and keyguard kinds exist from API 28: below it, pickups are
 * zero and `firstPickup` is null.
 *
 * ### Mirroring iOS, segment by segment
 * iOS reads one `ActivitySegment` per hour and combines them
 * (`CustomTemplateReport.swift:63-73`):
 * - `totalDuration` and `hourly[hour]` add each segment's activity;
 * - `pickups` sums the segments' pickups (`totalPickupsWithoutApplicationActivity`
 *   plus each app's `numberOfPickups`);
 * - `longestActivity` is the MAX of the segments' longest activity, so a
 *   session that runs 9:30–11:30 reads as one hour, never two;
 * - `firstPickup` is the earliest of the segments'.
 * Sessions are therefore clipped at every segment edge here too, and the
 * longest activity is per segment: those artefacts are reproduced, not
 * fixed, so a template renders the same numbers on both platforms.
 *
 * ### Sessions
 * Durations come from the resume/pause events, never from `queryUsageStats`
 * aggregates ("may be expanded to the nearest whole interval
 * period"). A package is in the foreground while at least one of its
 * activities is resumed (moving between screens emits paused(A),
 * resumed(B), stopped(A), and the late stop must not end the app's
 * session). Spans are clipped to the window and to the segment edges.
 *
 * - **Already open at the window start.** The wiring queries from
 *   `startMs - LOOKBACK_MS`: events before the window only set the state,
 *   so an app resumed at 23:40 counts from 00:00. When the opening resume
 *   is older than the lookback, the app is unknown until its next resume:
 *   its orphan pause is ignored and that stretch is lost (an undercount,
 *   never a double count). Four hours outlasts any film; the screen going
 *   off ends every span, so a longer open span is continuous use.
 * - **Closing.** A package's span ends when its last resumed activity
 *   instance ends. SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN and
 *   DEVICE_SHUTDOWN close every span (`UsageEventKind.closesAll`);
 *   DEVICE_STARTUP too. The public `Event` has no instance id, so the
 *   report keeps that rule (a pause ends its class) and reads an
 *   ACTIVITY_STOPPED as a death only when it can only be one
 *   ([ActivityInstances]): nothing owed in its class, one instance resumed,
 *   no uncertainty there or in another class of the package. That is the
 *   process dying in the foreground (on the Android 17 emulator: Gmail's welcome
 *   tour, resumed and stopped within a second with no pause, read as 142 s
 *   of use when stops closed nothing). Every other stop changes nothing.
 *   Below API 29 there is no stop, and the report is the pause rule's. The
 *   misreading left needs an instance already alive before the four-hour
 *   lookback (resumed or paused there) whose stop then arrives inside the
 *   stream.
 * - **A boot that never logged a shutdown** (battery pulled, kernel panic):
 *   the stream then shows a SCREEN_INTERACTIVE while the screen is already
 *   interactive, which cannot happen on a running device (every open span
 *   lives on a lit screen, and the screen-off closes them). That second
 *   screen-on closes the open spans at the last event before it: the last
 *   instant the device is known to have been alive. The stretch from there
 *   to the death is lost (minutes, at most the last session) rather than the
 *   dead hours counted as use, which is what iOS shows too: nothing is
 *   recorded while the phone is off.
 * - **Two apps at once** (split screen, picture-in-picture): the union
 *   rule of [ThresholdProgress], `totalDuration` counts an instant once;
 *   each app's own total counts it fully, so the apps may add up to more
 *   than the total, as `ApplicationActivity` durations do on iOS.
 *
 * ### Pickups
 * A pickup is a screen wake: every SCREEN_INTERACTIVE in the window counts
 * one, a glance at the lock screen as much as an unlock (Apple's Screen Time
 * counts a pickup whenever the screen turns on, and the report binds the
 * total: `totalPickupsWithoutApplicationActivity` plus every app's
 * `numberOfPickups`). `firstPickup` is the first wake. The keyguard events
 * only close spans. A pickup is "with application activity" when a counted
 * app is on screen at the wake or resumes before the screen goes off (Apple:
 * "pickups made directly to the application", attributed to that app, the
 * first one) and "without" otherwise (Apple: "device pickups without
 * application use"); both counts are kept, [ReportData.pickups] is the sum.
 *
 * ### Reading
 * [result] is terminal: it settles the open spans up to `nowMs`, so [add]
 * refuses events after it.
 */
internal class ReportAggregator(
  private val window: ReportWindow,
  private val filter: ReportAppFilter,
  private val categories: ReportCategoryResolver = ReportCategoryResolver.NONE,
) {
  private val segments = window.segments
  private val segmentCount = segments.size

  private val segmentActivityMs = LongArray(segmentCount)
  private val segmentLongestMs = LongArray(segmentCount)
  private val segmentPickups = IntArray(segmentCount)
  private val segmentPickupsWith = IntArray(segmentCount)
  private val segmentFirstPickupMs = LongArray(segmentCount) { Long.MIN_VALUE }

  /** -1 before the window, [segmentCount] after it. */
  private var segmentIndex = -1

  /** Counted packages with a live activity → per class ("" when unknown), its instances; a package in the foreground has at least one resumed. */
  private val instances = HashMap<String, HashMap<String, ActivityInstances>>()
  /** When each foreground package's current span piece started (clipped to the segment start). */
  private val openSince = HashMap<String, Long>()
  private var unionSince = Long.MIN_VALUE
  private var unionOpen = false

  private val appDurationMs = HashMap<String, Long>()
  private val appPickups = HashMap<String, Int>()

  /** Whether the screen is lit, as far as the stream has said (null before the first screen event). */
  private var screenInteractive: Boolean? = null
  /** The pickup of the current screen-on session awaiting attribution: its segment, -1 when out of the window, -2 when none. */
  private var pendingPickupSegment = -2
  /** The counted package resumed last: what an unlock straight into the app on screen is made to. */
  private var lastResumedPackage: String? = null

  private var lastMs = Long.MIN_VALUE
  private var finished = false
  var eventCount = 0L
    private set

  /** Feeds one stream event. Kinds the report does not know ([UsageEventKind.OTHER]) are ignored. */
  fun add(event: UsageEvent) {
    check(!finished) { "ReportAggregator.result() was read; feed every event before reading it" }
    eventCount++
    val previous = lastMs
    val at = maxOf(event.timestampMs, lastMs) // the stream is chronological; a stray earlier stamp is clamped
    lastMs = at
    // A screen-on while the screen is lit: a screen-off was never logged, so
    // the device died (a boot without a shutdown event). The open spans end at
    // the last event before, the last instant the device is known alive; this
    // runs before the segments advance to `at`, so the dead hours are never
    // settled into them.
    if (event.kind == UsageEventKind.SCREEN_INTERACTIVE && screenInteractive == true && previous != Long.MIN_VALUE) closeAll(previous, forgetInstances = true)
    advanceTo(at)
    when (event.kind) {
      UsageEventKind.ACTIVITY_RESUMED -> resume(event.packageName, event.className, at)
      UsageEventKind.ACTIVITY_PAUSED -> pause(event.packageName, event.className, at)
      UsageEventKind.ACTIVITY_STOPPED -> stop(event.packageName, event.className, at)
      UsageEventKind.SCREEN_INTERACTIVE -> {
        screenInteractive = true
        pickup(at)
      }
      // The lock screen going away opens nothing by itself: the app's resume does.
      UsageEventKind.KEYGUARD_HIDDEN -> Unit
      UsageEventKind.SCREEN_NON_INTERACTIVE -> {
        screenInteractive = false
        closeAll(at)
      }
      // The lock screen up with the screen still lit (a boot, a lock from the
      // power menu): the spans end, the wake's pickup still goes to the first
      // app opened after the unlock.
      UsageEventKind.KEYGUARD_SHOWN -> closeSpans(at)
      UsageEventKind.DEVICE_SHUTDOWN -> {
        screenInteractive = false
        closeAll(at, forgetInstances = true)
      }
      // Nothing survives a boot. The pickup of the boot's screen-on stays
      // pending: the first app opened after the boot is what it was made to.
      UsageEventKind.DEVICE_STARTUP -> closeSpans(at, forgetInstances = true)
      UsageEventKind.OTHER -> Unit
    }
  }

  fun addAll(events: Iterable<UsageEvent>): ReportAggregator {
    for (event in events) add(event)
    return this
  }

  /**
   * The data as of `nowMs`: open spans count until now (the person may be
   * using the device while the report renders), clipped to the window.
   */
  fun result(nowMs: Long): ReportData {
    // Terminal: the open spans are settled up to now, so no event may follow
    // ([add] throws). Reading again with a later `nowMs` is fine.
    finished = true
    val until = maxOf(nowMs, lastMs)
    advanceTo(until)
    settleSpans(minOf(until, window.endMs))

    var totalMs = 0L
    var longestMs = 0L
    var pickups = 0
    var pickupsWith = 0
    var firstPickup = Long.MIN_VALUE
    val hourly = LongArray(24)
    for (i in 0 until segmentCount) {
      totalMs += segmentActivityMs[i]
      longestMs = maxOf(longestMs, segmentLongestMs[i])
      pickups += segmentPickups[i]
      pickupsWith += segmentPickupsWith[i]
      val first = segmentFirstPickupMs[i]
      if (first != Long.MIN_VALUE && (firstPickup == Long.MIN_VALUE || first < firstPickup)) firstPickup = first
      val hour = segments[i].hourOfDay
      if (hour in 0..23) hourly[hour] += segmentActivityMs[i]
    }

    val apps = appDurationMs.entries
      .filter { it.value > 0 }
      .map { ReportAppUsage(it.key, it.value, appPickups[it.key] ?: 0) }
      .sortedWith(compareByDescending<ReportAppUsage> { it.durationMs }.thenBy { it.packageName })
    val categoryTotals = LinkedHashMap<String, Long>()
    for (app in apps) {
      val name = categories.categoryOf(app.packageName) ?: OTHER_CATEGORY
      categoryTotals[name] = (categoryTotals[name] ?: 0L) + app.durationMs
    }
    val sortedCategories = categoryTotals.entries
      .map { ReportCategoryUsage(it.key, it.value) }
      .sortedWith(compareByDescending<ReportCategoryUsage> { it.durationMs }.thenBy { it.name })

    return ReportData(
      totalDurationMs = totalMs,
      pickups = pickups,
      pickupsWithApplicationActivity = pickupsWith,
      longestActivityMs = longestMs,
      appCount = apps.size,
      firstPickupMs = if (firstPickup == Long.MIN_VALUE) null else firstPickup,
      hourlyMs = hourly.toList(),
      topApps = apps,
      categories = sortedCategories,
    )
  }

  // MARK: Segments

  /** Crosses every segment edge up to `at`, settling the open spans at each edge and restarting them there. */
  private fun advanceTo(at: Long) {
    while (segmentIndex < segmentCount) {
      val edge = if (segmentIndex < 0) window.startMs else segments[segmentIndex].endMs
      if (at < edge) return
      settleSpans(edge)
      segmentIndex++
      // Entering the window (or the next hour): the open spans restart at the edge,
      // and so does the run the longest activity is measured on (per segment, as on iOS).
      if (openSince.isNotEmpty()) for (key in openSince.keys) openSince[key] = edge
      if (unionOpen) unionSince = edge
      unionRunMs = 0
      unionClosedAt = Long.MIN_VALUE
    }
  }

  private val inWindow: Boolean
    get() = segmentIndex in 0 until segmentCount

  /** Adds every open span up to `at` (per app and union) to the current segment and moves the spans' start there. */
  private fun settleSpans(at: Long) {
    if (!inWindow) return
    if (openSince.isNotEmpty()) {
      for (entry in openSince.entries) {
        val since = entry.value
        if (at > since) {
          appDurationMs[entry.key] = (appDurationMs[entry.key] ?: 0L) + (at - since)
          entry.setValue(at)
        }
      }
    }
    if (unionOpen && at > unionSince) {
      val span = at - unionSince
      segmentActivityMs[segmentIndex] += span
      // The longest activity is the whole continuous span inside the segment: the
      // settled pieces of one span are contiguous, so their sum is its length.
      unionRunMs += span
      segmentLongestMs[segmentIndex] = maxOf(segmentLongestMs[segmentIndex], unionRunMs)
      unionSince = at
    }
  }

  /** Length of the union span in progress inside the current segment (reset at the segment edge and when the union closes). */
  private var unionRunMs = 0L

  // MARK: Sessions

  private fun resume(packageName: String?, className: String?, at: Long) {
    if (!filter.counts(packageName, className)) return
    val pkg = packageName!!
    val classes = instances.getOrPut(pkg) { HashMap(2) }
    val key = className ?: ""
    classes[key] = (classes[key] ?: ActivityInstances.NONE).resume()
    lastResumedPackage = pkg
    if (pkg !in openSince) {
      openSince[pkg] = at
      if (!unionOpen) {
        unionOpen = true
        unionSince = at
        // Reopened within the switching tolerance: the same session, the gap included
        // (Apple's longest activity is a date interval). Beyond it, a new run.
        if (unionClosedAt != Long.MIN_VALUE && at - unionClosedAt <= SESSION_GAP_MS) unionRunMs += at - unionClosedAt else unionRunMs = 0
      }
    }
    attributePickup(pkg)
  }

  private fun pause(packageName: String?, className: String?, at: Long) {
    // A pause ends its class (main's rule) and owes a stop, an orphan one too
    // (its resume was before the lookback, or the class is already closed):
    // the paused instance's stop may still come after a new one resumes.
    if (!filter.counts(packageName, className)) return
    val pkg = packageName!!
    val classes = instances.getOrPut(pkg) { HashMap(2) }
    val key = className ?: ""
    val (next, ended) = (classes[key] ?: ActivityInstances.NONE).pause()
    classes[key] = next
    if (!ended || classes.values.any { it.resumed > 0 }) return
    closePackage(pkg, at)
  }

  /**
   * A stop ends its class only when it can only be the class's one resumed
   * instance dying in the foreground ([ActivityInstances.stop]), and no other
   * class of the package has uncertain counts: such a class may have an
   * instance its count lost (a second window, closed early by the pause
   * rule), which keeps the package in use. Every other stop changes no span.
   * The engine keeps its coarser rule (a stop ends the class): see
   * [ActivityInstances] for why.
   */
  private fun stop(packageName: String?, className: String?, at: Long) {
    val pkg = packageName ?: return
    val classes = instances[pkg] ?: return
    val key = className ?: ""
    val current = classes[key] ?: return
    val (next, died) = current.stop()
    // Read as main reads it: the package may still be in use through another class.
    if (died && classes.any { (other, state) -> other != key && state.uncertain }) return
    if (next.isEmpty) classes.remove(key) else classes[key] = next
    if (classes.isEmpty()) instances.remove(pkg)
    if (!died || classes.values.any { it.resumed > 0 }) return
    closePackage(pkg, at)
  }

  private fun closePackage(pkg: String, at: Long) {
    val since = openSince.remove(pkg) ?: return
    if (inWindow && at > since) appDurationMs[pkg] = (appDurationMs[pkg] ?: 0L) + (at - since)
    if (openSince.isEmpty()) closeUnion(at)
  }

  /**
   * Closes the union span. The run the longest activity is measured on
   * survives a close followed by a reopen within [SESSION_GAP_MS] (switching
   * apps pauses one a few hundred milliseconds before the other resumes):
   * one session of use, as Apple's "longest activity session" is. A longer
   * gap ends it ([resume]).
   */
  private fun closeUnion(at: Long) {
    if (!unionOpen) return
    if (inWindow && at > unionSince) {
      val span = at - unionSince
      segmentActivityMs[segmentIndex] += span
      unionRunMs += span
      segmentLongestMs[segmentIndex] = maxOf(segmentLongestMs[segmentIndex], unionRunMs)
    }
    unionOpen = false
    unionClosedAt = at
  }

  /** When the union last closed; a reopen later than this starts a new run. */
  private var unionClosedAt = Long.MIN_VALUE

  /**
   * Every span ends at [at]. The instances are forgotten across a boot
   * (nothing from before it is coming), and otherwise kept as owed stops
   * ([ActivityInstances.closed]), so the old instances' late pause and stop
   * events still find what they answer.
   */
  private fun closeSpans(at: Long, forgetInstances: Boolean = false) {
    if (openSince.isNotEmpty()) {
      for (pkg in ArrayList(openSince.keys)) closePackage(pkg, at)
    }
    if (forgetInstances) {
      instances.clear()
    } else {
      for (classes in instances.values) {
        val each = classes.entries.iterator()
        while (each.hasNext()) {
          val entry = each.next()
          val owed = entry.value.closed()
          if (owed.isEmpty) each.remove() else entry.setValue(owed)
        }
      }
      instances.values.removeAll { it.isEmpty() }
    }
    closeUnion(at)
  }

  /** The screen went dark or locked: every span ends, and the session's pickup, if still unattributed, saw no app. */
  private fun closeAll(at: Long, forgetInstances: Boolean = false) {
    closeSpans(at, forgetInstances)
    pendingPickupSegment = -2
  }

  // MARK: Pickups

  /** The screen woke up: one pickup, counted now; which app it was made to, if any, is settled by the next resume. */
  private fun pickup(at: Long) {
    if (!inWindow) {
      pendingPickupSegment = -1
    } else {
      pendingPickupSegment = segmentIndex
      segmentPickups[segmentIndex]++
      val first = segmentFirstPickupMs[segmentIndex]
      if (first == Long.MIN_VALUE || at < first) segmentFirstPickupMs[segmentIndex] = at
    }
    // Woke up with an app already on screen (the lock timeout had not elapsed,
    // or the device unlocked straight into it: AOSP resumes the top activity
    // when the keyguard starts going away): the pickup was made to that app.
    if (openSince.isNotEmpty()) attributePickup(lastResumedPackage?.takeIf { it in openSince } ?: openSince.keys.first())
  }

  /** A counted app came to the foreground: the session's pickup, if still unattributed, was made to it. */
  private fun attributePickup(pkg: String) {
    if (pendingPickupSegment == -2) return
    if (pendingPickupSegment >= 0) {
      segmentPickupsWith[pendingPickupSegment]++
      appPickups[pkg] = (appPickups[pkg] ?: 0) + 1
    }
    pendingPickupSegment = -2
  }

  companion object {
    /** How far before the window the wiring queries, so an app open at the window start has its resume in the stream. */
    const val LOOKBACK_MS = 4 * 3_600_000L
    /**
     * The longest pause between one app leaving and the next resuming that
     * still counts as the same activity session: an app switch, a cold start
     * included (the fixture's switches are within a second). Going to the
     * launcher for longer ends the session.
     */
    const val SESSION_GAP_MS = 3_000L
    /** The category of an app the resolver knows nothing about (`"Other"` on iOS too). */
    const val OTHER_CATEGORY = "Other"
  }
}
