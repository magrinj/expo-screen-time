package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block composition against the BlockComposition sections of
 * `scripts/swift-tests/main.swift`, case for case where the case exists on
 * Android (applications only: the category and web-domain tokens of the
 * Swift cases have no Android counterpart, `selectableKinds` is apps only).
 *
 * iOS projects each source into its own ManagedSettings store and the
 * system merges the stores; here there are no stores, so the
 * `storeContents` / `storeShields` cases are asserted on their outcome —
 * which app is shielded, by which owner — through [ShieldPolicy.shields]
 * and [BlockSource.covers], the Android reading of the same rules.
 *
 * The unlock and snooze sections are ported too: "SuspendedSources — what
 * temporaryUnlock suspends", "SuspendedSources — threshold suspension is
 * per occurrence", "storeShields — a snooze lifts the pressed target",
 * "storeShields — snoozes under an allowlist" and "targetedUnlockVerdict"
 * (the snooze projection is the policy's lifted set here). iOS-structural,
 * not applicable: "BlockStore.activity — store names", "owner(preferring:)"
 * and "handoffTargets" (the two extensions agreeing on one owner; the
 * Android shield resolves it once), "ShieldOwnerMap", and the "a threshold
 * list without its interval" case (thresholds live inside the interval here).
 */
class BlockCompositionTest {
  private fun manual(vararg apps: String, mode: BlockMode = BlockMode.blocklist, startedAt: Long = 0) =
    BlockSource(BlockSourceKind.Manual, startedAt, apps.toSet(), mode)

  private fun schedule(name: String, startedAt: Long, vararg apps: String, mode: BlockMode = BlockMode.blocklist) =
    BlockSource(BlockSourceKind.Schedule(name), startedAt, apps.toSet(), mode)

  private fun threshold(name: String, startedAt: Long, vararg apps: String) =
    BlockSource(BlockSourceKind.Threshold(name), startedAt, apps.toSet())

  private val launchable = setOf("insta", "youtube", "tiktok", "games", "calendar", "contacts", "mail", "slack", "safari", "music", "unknown")
  private val exempt = emptySet<String>()
  private fun shielded(app: String, sources: List<BlockSource>) = ShieldPolicy.shields(app, sources, launchable, exempt)

  // The Swift fixtures (tokens → packages).
  private val manual = manual("insta")
  private val scheduleA = schedule("A", 2_000, "youtube")
  private val scheduleB = schedule("B", 3_000, "tiktok", "youtube")
  private val thresholdA = threshold("A", 2_500, "games")

  @Test
  fun `storeContents - one store per source, read as what each source shields`() {
    val all = listOf(manual, scheduleA, scheduleB, thresholdA)
    assertTrue("manual block lives in the manual store", shielded("insta", all) && BlockComposition.owner("insta", all)?.kind == BlockSourceKind.Manual)
    assertTrue("an activity's store holds its schedule AND its threshold shields", shielded("youtube", all) && shielded("games", all))
    assertTrue("the other activity has its own store", shielded("tiktok", all))
    assertFalse("no other store is written", shielded("unknown", all))

    // Device-observed defect: an unrelated activity's intervalDidEnd removed the manual shields.
    val afterAEnds = listOf(manual, scheduleB)
    assertTrue("unrelated activity end keeps the manual block", shielded("insta", afterAEnds))
    assertTrue("the other overlapping schedule keeps its shields", shielded("tiktok", afterAEnds) && shielded("youtube", afterAEnds))
    assertFalse("threshold shields of the ended activity go with it (cleared at interval end)", shielded("games", afterAEnds))

    assertFalse("no sources ⇒ every store empty", BlockComposition.shieldsSomething(emptyList()))
    assertTrue(BlockComposition.union(emptyList()).isEmpty())
  }

  @Test
  fun `union - what an unlock suspends and meters`() {
    assertEquals("union covers every active source", setOf("insta", "youtube", "tiktok", "games"), BlockComposition.union(listOf(manual, scheduleA, scheduleB, thresholdA)))
    assertEquals("manual only", setOf("insta"), BlockComposition.union(listOf(manual)))
    assertTrue("nothing active ⇒ empty (NO_ACTIVE_BLOCK)", BlockComposition.union(emptyList()).isEmpty())
  }

  @Test
  fun `owner - threshold then latest schedule then manual then default`() {
    val sources = listOf(manual, scheduleA, scheduleB, thresholdA)
    assertEquals("threshold wins", BlockSourceKind.Threshold("A"), BlockComposition.owner("games", sources)?.kind)
    assertEquals("shared by two schedules ⇒ the latest schedule (B started later)", BlockSourceKind.Schedule("B"), BlockComposition.owner("youtube", sources)?.kind)
    assertEquals("covered by one schedule ⇒ that schedule", BlockSourceKind.Schedule("B"), BlockComposition.owner("tiktok", sources)?.kind)
    assertEquals("manual block", BlockSourceKind.Manual, BlockComposition.owner("insta", sources)?.kind)
    assertNull("nothing covers it ⇒ null (default config)", BlockComposition.owner("unknown", sources))

    val manualAlsoYoutube = manual("youtube", startedAt = 9_000)
    assertEquals("a schedule beats the manual block even when the manual block is newer", BlockSourceKind.Schedule("A"), BlockComposition.owner("youtube", listOf(manualAlsoYoutube, scheduleA))?.kind)
    val thresholdB = threshold("B", 100, "games")
    assertEquals("two thresholds ⇒ the latest one", BlockSourceKind.Threshold("A"), BlockComposition.owner("games", listOf(thresholdA, thresholdB, scheduleB))?.kind)
    val sameStart = schedule("Z", 3_000, "tiktok")
    assertEquals("equal start times break ties by name, deterministically", BlockSourceKind.Schedule("B"), BlockComposition.owner("tiktok", listOf(sameStart, scheduleB))?.kind)
    assertNull("no sources ⇒ null", BlockComposition.owner("tiktok", emptyList()))
    assertEquals("the owner's activity name is what the shield resolves its config from", "B", BlockComposition.owner("tiktok", sources)?.kind?.activityName)
    assertNull(BlockSourceKind.Manual.activityName)
  }

  // MARK: - Records → sources (the manual block and the monitoring state)

  private val selections = mapOf(
    "S1" to Selection(applications = setOf("insta")),
    "S2" to Selection(applications = setOf("youtube")),
    "S3" to Selection(applications = setOf("games")),
    "apps" to Selection(applications = setOf("calendar", "contacts")),
    "mixed" to Selection(applications = setOf("calendar"), categories = setOf("social"), webDomains = setOf("x.com")),
    "empty" to Selection(),
  )
  private val resolve: (String) -> Selection? = { selections[it] }

  /** Activity [name] in progress since [startedAt], its schedule over [selectionId] in [mode], its fired `blockOnThreshold` events over [fired]. */
  private fun monitoring(
    name: String = "A",
    startedAt: Long = 1_000,
    selectionId: String? = "S2",
    mode: String = "blocklist",
    fired: List<Pair<String, String>> = emptyList(),
    lifted: Boolean = false,
    liftedThresholds: Set<String> = emptySet(),
  ): MonitoringState {
    val events = fired.map { (event, selection) -> ThresholdConfig(event, selection, 60, blockOnThreshold = true) }
    val config = MonitoringConfig(name, ScheduleConfig(DateComponents(9, 0), DateComponents(17, 0)), selectionId, mode, events)
    val occurrence = Occurrence(0, 10_000_000)
    val thresholds = events.associate { it.eventName to ThresholdProgress(0, occurrence.endMs, fired = true) }
    return MonitoringState(
      activities = mapOf(name to MonitoredActivity(config, startedAt)),
      intervals = mapOf(name to ActiveInterval(startedAt, occurrence, thresholds = thresholds, lifted = lifted, liftedThresholds = liftedThresholds)),
    )
  }

  private fun sources(manual: ManualBlock?, monitoring: MonitoringState) = BlockComposition.sources(manual, monitoring, resolve)

  @Test
  fun `sources - records to sources`() {
    val records = monitoring(fired = listOf("limit" to "S3"))
    val live = sources(ManualBlock("S1", BlockMode.blocklist, setOf("insta")), records)
    assertEquals("manual, schedule and threshold sources in a stable order", listOf(BlockSourceKind.Manual, BlockSourceKind.Schedule("A"), BlockSourceKind.Threshold("A")), live.map { it.kind })
    assertTrue("an activity's sources carry its interval start", live[1].startedAtMs == 1_000L && live[2].startedAtMs == 1_000L)
    assertEquals("union of every record", setOf("insta", "youtube", "games"), BlockComposition.union(live))

    val lifted = monitoring(fired = listOf("limit" to "S3"), lifted = true)
    assertEquals("a lifted interval shields nothing, its thresholds still do", listOf(BlockSourceKind.Manual, BlockSourceKind.Threshold("A")), sources(ManualBlock("S1", BlockMode.blocklist, setOf("insta")), lifted).map { it.kind })

    val unknown = monitoring(selectionId = "missing", fired = listOf("limit" to "S3"))
    assertEquals("unresolvable or empty selections are omitted", listOf(BlockSourceKind.Threshold("A")), sources(ManualBlock("empty", BlockMode.blocklist, emptySet()), unknown).map { it.kind })
    assertTrue("no records ⇒ no source", sources(null, MonitoringState()).isEmpty())
  }

  @Test
  fun `sources - modes come from the records`() {
    val manualAllow = sources(ManualBlock("apps", BlockMode.allowlist, setOf("calendar", "contacts")), MonitoringState())
    assertEquals("the manual block is an allowlist source when its persisted mode says so", listOf(manual("calendar", "contacts", mode = BlockMode.allowlist)), manualAllow)
    assertEquals("without a mode it is a blocklist", BlockMode.blocklist, sources(ManualBlock("apps", BlockMode.stored(null), setOf("calendar", "contacts")), MonitoringState()).first().mode)
    assertEquals("an empty allowlist selection is a source (allow nothing)…", listOf(manual(mode = BlockMode.allowlist)), sources(ManualBlock("empty", BlockMode.allowlist, emptySet()), MonitoringState()))
    assertTrue("…while an empty blocklist selection is omitted, as before", sources(ManualBlock("empty", BlockMode.blocklist, emptySet()), MonitoringState()).isEmpty())
    // The manual block holds the apps captured at block time (the projection model); the schedule and threshold selections are read live.
    val scheduleAllow = sources(null, monitoring(selectionId = "apps", mode = "allowlist", fired = listOf("limit" to "mixed")))
    assertEquals("a schedule takes its mode from its config; its blockOnThreshold shields keep blocklist semantics", listOf(BlockMode.allowlist, BlockMode.blocklist), scheduleAllow.map { it.mode })
    assertEquals(listOf(BlockSourceKind.Schedule("A"), BlockSourceKind.Threshold("A")), scheduleAllow.map { it.kind })
    assertEquals("the threshold shields the event's selection (its apps; categories and domains are not selectable here)", setOf("calendar"), scheduleAllow.last().applications)
    assertTrue("a deleted selection shields nothing in either mode (no source)", sources(null, monitoring(selectionId = "gone", mode = "allowlist")).isEmpty())
    assertEquals("a selection edited to hold a category or a domain after an allowlist block allows its apps only", setOf("calendar"), sources(null, monitoring(selectionId = "mixed", mode = "allowlist")).single().applications)
    assertEquals("unblockAll lifts an allowlist schedule's interval like a blocklist's", listOf(BlockSourceKind.Threshold("A")), sources(null, monitoring(selectionId = "apps", mode = "allowlist", fired = listOf("limit" to "mixed"), lifted = true)).map { it.kind })
    assertTrue("…and the thresholds it lifted with it", sources(null, monitoring(selectionId = "apps", mode = "allowlist", fired = listOf("limit" to "mixed"), lifted = true, liftedThresholds = setOf("limit"))).isEmpty())
  }

  // MARK: - Allowlist mode

  private val allowManual = manual("calendar", "contacts", mode = BlockMode.allowlist)
  private val allowNothing = manual(mode = BlockMode.allowlist)
  private val allowA = schedule("A", 1_000, "calendar", "mail", "slack", mode = BlockMode.allowlist)
  private val thresholdInA = threshold("A", 1_000, "slack")
  private val scheduleBlock = schedule("B", 2_000, "calendar")

  @Test
  fun `storeShields - an allowlist shields every app except the allowed ones`() {
    assertTrue("the manual store is an allowlist store that shields something", BlockComposition.shieldsSomething(listOf(allowManual)) && BlockComposition.containsAllowlist(listOf(allowManual)))
    assertFalse("it excepts exactly the allowed apps", shielded("calendar", listOf(allowManual)) || shielded("contacts", listOf(allowManual)))
    assertTrue("…and shields every other launchable app", shielded("music", listOf(allowManual)) && shielded("insta", listOf(allowManual)))
    assertTrue("an allowlist store is never empty — it stays in force", allowNothing.shieldsSomething)
    assertTrue("a blocklist store is empty without tokens", !manual().shieldsSomething && manual("x").shieldsSomething)
    assertTrue("storeContents (the blocklist view) shows nothing for it", BlockComposition.union(listOf(allowManual)).isEmpty())
    assertTrue("an empty allowlist shields every app (\"allow nothing\") — it is not omitted like an empty blocklist", shielded("calendar", listOf(allowNothing)) && shielded("music", listOf(allowNothing)))
    assertFalse("an app without a launcher entry is reached from an allowed app (Android: never shielded by an allowlist)", shielded("com.permissioncontroller", listOf(allowNothing)))
  }

  @Test
  fun `storeShields - union with blocklist sources, shielded if ANY source shields it`() {
    // A schedule allowlist with a blockOnThreshold blocklist inside the same activity.
    val mixed = listOf(allowA, thresholdInA)
    assertTrue("an allowed app a blocklist shields directly is NOT excepted — the blocklist wins", shielded("slack", mixed))
    assertFalse("the other allowed apps stay allowed", shielded("calendar", mixed) || shielded("mail", mixed))
    assertTrue("every other app is shielded by the allowlist", shielded("music", mixed))
    // Across stores (manual allowlist + a schedule blocklist): the union is the system's merge of the two stores.
    val across = listOf(allowManual, scheduleBlock)
    assertTrue("the schedule's blocklist shields Calendar on its own", shielded("calendar", across))
    assertFalse("the manual allowlist still allows Contacts", shielded("contacts", across))
    assertTrue(shielded("music", across))
    // Two allowlists in one store intersect: an app is unshielded only if every allowlist allows it.
    val allowA2 = schedule("A", 1_000, "mail", "safari", mode = BlockMode.allowlist)
    val two = listOf(allowA, allowA2)
    assertFalse("two allowlists ⇒ the intersection of the allowed sets", shielded("mail", two))
    assertTrue(shielded("calendar", two) && shielded("safari", two) && shielded("slack", two))
    assertTrue("an allowlist in one store does not turn another store into one", listOf(allowManual, scheduleA).map { it.mode } == listOf(BlockMode.allowlist, BlockMode.blocklist))
  }

  @Test
  fun `covers and owner - an allowlist covers what it does not allow`() {
    assertFalse("an allowed app is not covered", allowManual.covers("calendar"))
    assertTrue("any other app is", allowManual.covers("music"))
    assertTrue("an empty allowlist covers every app", allowNothing.covers("any"))
    assertTrue("a blocklist covers what it lists, as before", manual.covers("insta") && !manual.covers("music"))
    assertEquals("the allowlist owns the shield of an app it does not allow", BlockSourceKind.Manual, BlockComposition.owner("music", listOf(allowManual))?.kind)
    assertNull("…and nothing owns an allowed app (nothing shields it)", BlockComposition.owner("calendar", listOf(allowManual)))
    assertEquals("an app allowed by the manual allowlist but shielded by a schedule is owned by the schedule", BlockSourceKind.Schedule("B"), BlockComposition.owner("calendar", listOf(allowManual, scheduleBlock))?.kind)
    assertEquals("an app only the allowlist shields is owned by the allowlist, whatever else is active", BlockSourceKind.Manual, BlockComposition.owner("music", listOf(allowManual, scheduleBlock))?.kind)
    assertEquals("threshold > allowlist schedule for an app both shield", BlockSourceKind.Threshold("A"), BlockComposition.owner("slack", listOf(allowA, thresholdInA))?.kind)
    assertEquals("two allowlists covering an app: the schedule wins the configuration, as among blocklists", BlockSourceKind.Schedule("A"), BlockComposition.owner("music", listOf(allowManual, allowA))?.kind)
    // Reproduced on iOS with a category: an allowlist schedule allowing Calendar and a manual blocklist on Calendar — the blocklist owns it.
    val allowSchedule = schedule("focus", 5_000, "calendar", mode = BlockMode.allowlist)
    assertEquals(BlockSourceKind.Manual, BlockComposition.owner("calendar", listOf(allowSchedule, manual("calendar")))?.kind)
    assertNull("…and by nothing when only the allowlist is active", BlockComposition.owner("calendar", listOf(allowSchedule)))
    assertEquals("an app the allowlist shields is still owned by the allowlist (precedence over the manual block)", BlockSourceKind.Schedule("focus"), BlockComposition.owner("music", listOf(allowSchedule, manual("calendar")))?.kind)
  }

  @Test
  fun `union and containsAllowlist - what a temporary unlock may meter`() {
    assertEquals("the union holds the blocklist tokens only — an allowlist's shielded set cannot be named", setOf("calendar"), BlockComposition.union(listOf(allowManual, scheduleBlock)))
    assertTrue("containsAllowlist flags the sources an unlock must refuse", BlockComposition.containsAllowlist(listOf(allowManual, scheduleBlock)) && !BlockComposition.containsAllowlist(listOf(manual, scheduleA)))
  }

  // MARK: - The unlock suspension (fixes 5, 7 of the contract; review findings 5 and 11)

  /** The Swift `records`: S1 manual, A in progress since 1 000 over S2 with the fired threshold "limit" over S3. */
  private val manualS1 = ManualBlock("S1", BlockMode.blocklist, setOf("insta"))
  private val records = monitoring(fired = listOf("limit" to "S3"))

  /** [base] with activity [name] in progress since [startedAt] over [selectionId] with the fired [fired] events. */
  private fun plus(base: MonitoringState, name: String, startedAt: Long, selectionId: String?, fired: List<Pair<String, String>> = emptyList()): MonitoringState {
    val other = monitoring(name, startedAt, selectionId, fired = fired)
    return base.copy(activities = base.activities + other.activities, intervals = base.intervals + other.intervals)
  }

  @Test
  fun `SuspendedSources - what temporaryUnlock suspends`() {
    val grant = SuspendedSources.capturing(manualS1, records)
    assertTrue(
      "a grant captures the manual block, the occurrence in progress and the fired thresholds (with their occurrence)",
      grant.manual && grant.schedules == mapOf("A" to 1_000L) && grant.thresholds == mapOf("A" to SuspendedSources.Thresholds(1_000, listOf("S3"))),
    )
    assertTrue("every captured source is excluded from the composition ⇒ nothing shields", BlockComposition.sources(manualS1, records, resolve, grant).isEmpty())
    assertFalse("isBlocked() reads false under the grant (C08)", BlockComposition.shieldsSomething(BlockComposition.sources(manualS1, records, resolve, grant)))

    // A schedule that starts DURING the grant is not suspended and applies normally.
    val during = plus(records, "B", 5_000, "S3")
    val duringSources = BlockComposition.sources(manualS1, during, resolve, grant)
    assertEquals("a schedule starting during the grant is the only live source", listOf(BlockSourceKind.Schedule("B")), duringSources.map { it.kind })
    assertEquals("only B shields; the suspended sources stay lifted", setOf("games"), BlockComposition.union(duringSources))

    // A threshold that fires during the grant shields its own selection only.
    val thresholdDuring = monitoring(fired = listOf("limit" to "S3", "more" to "S2"))
    val thresholdSources = BlockComposition.sources(manualS1, thresholdDuring, resolve, grant)
    assertTrue("a new threshold during the grant shields its selection, not the suspended one", thresholdSources.map { it.kind } == listOf(BlockSourceKind.Threshold("A")) && thresholdSources[0].applications == setOf("youtube"))

    // The next occurrence of A is a different source.
    val nextDay = monitoring(startedAt = 90_000)
    assertEquals("A's next occurrence (new startedAt) is not suspended", listOf(BlockSourceKind.Schedule("A")), BlockComposition.sources(null, nextDay, resolve, grant).map { it.kind })
    assertTrue("suspension is by occurrence, not by name", !grant.suspendsSchedule("A", 90_000) && grant.suspendsSchedule("A", 1_000))
    assertTrue("threshold suspension is per selection", !grant.suspendsThreshold("A", 1_000, "S2") && grant.suspendsThreshold("A", 1_000, "S3"))

    // Renewal (finding 11): everything active — suspended or not — is restorable and captured again.
    assertEquals(
      "renewal meters the union of suspended and live sources (never NO_ACTIVE_BLOCK while something is restorable)",
      setOf("insta", "youtube", "games"),
      BlockComposition.union(BlockComposition.sources(manualS1, during, resolve)),
    )
    val renewed = SuspendedSources.capturing(manualS1, during)
    assertTrue("the renewed grant suspends B too", renewed.schedules == mapOf("A" to 1_000L, "B" to 5_000L) && renewed.manual)
    assertTrue("after renewal nothing shields again", BlockComposition.sources(manualS1, during, resolve, renewed).isEmpty())
    assertTrue("nothing active ⇒ empty suspension", SuspendedSources.capturing(null, MonitoringState()).isEmpty)
    assertTrue("a lifted interval is not captured", SuspendedSources.capturing(null, monitoring(lifted = true)).schedules.isEmpty())
    assertTrue("a lifted threshold is not captured", SuspendedSources.capturing(null, monitoring(fired = listOf("limit" to "S3"), liftedThresholds = setOf("limit"))).thresholds.isEmpty())

    // Document round trip (numbers come back as the writer's type, or as doubles from another process).
    val json = grant.toJson()
    assertEquals("document round trip", grant, SuspendedSources.fromJson(JSONObject(json.toString())))
    val doubles = JSONObject(json.toString()).put("schedules", JSONObject().put("A", 1000.0))
    assertEquals("double start times are read back", grant, SuspendedSources.fromJson(doubles))
    assertNull("a document without `manual` is not a suspension", SuspendedSources.fromJson(JSONObject()))
    assertEquals("the grant carries its suspension through the document", grant, UnlockGrant.fromJson(JSONObject(UnlockGrant.grant(60, 1_000, "g", grant).toJson().toString()))?.suspended)
  }

  @Test
  fun `SuspendedSources - threshold suspension is per occurrence`() {
    val grant = SuspendedSources.capturing(manualS1, records)
    // Reproduced on iOS: suspend A's threshold on S3, stop and restart A during the grant (a new occurrence), S3 fires again.
    val restarted = monitoring(startedAt = 5_000, fired = listOf("limit" to "S3"))
    val afterRestart = BlockComposition.sources(manualS1, restarted, resolve, grant)
    assertEquals("after a stop/restart during the grant, a threshold that fires again shields (new occurrence, not suspended)", listOf(BlockSourceKind.Schedule("A"), BlockSourceKind.Threshold("A")), afterRestart.map { it.kind })
    assertEquals("…its own selection", setOf("games"), afterRestart.last().applications)
    assertTrue("the suspension is bound to the occurrence it was captured in", !grant.suspendsThreshold("A", 5_000, "S3") && grant.suspendsThreshold("A", 1_000, "S3"))
    // Re-registering A in place (same occurrence) re-arms its thresholds: a re-fire stays suspended.
    assertTrue("a threshold re-firing in the SAME occurrence stays suspended", BlockComposition.sources(manualS1, records, resolve, grant).isEmpty())
    val persisted = grant.toJson()
    assertTrue("persisted under `thresholds`, with the occurrence", persisted.getJSONObject("thresholds").keys().asSequence().toList() == listOf("A") && !persisted.has("thresholdSelectionIds"))
    val byNameOnly = JSONObject().put("manual", true).put("schedules", JSONObject().put("A", 1000)).put("thresholdSelectionIds", JSONObject().put("A", org.json.JSONArray(listOf("S3"))))
    val read = SuspendedSources.fromJson(byNameOnly)
    assertTrue("a grant persisted by name only (previous layout) suspends no threshold — fail closed, the shields come back", read?.thresholds?.isEmpty() == true && read.manual)
  }

  // MARK: - Shield snooze: the lifted set is the Android reading of `storeShields(of:snoozed:)`

  private fun shielded(app: String, sources: List<BlockSource>, lifted: Set<String>) = ShieldPolicy.shields(app, sources, launchable, exempt, lifted)

  @Test
  fun `storeShields - a snooze lifts the pressed target in every store, nothing else`() {
    // Instagram is in the manual selection and in schedule B; A shields YouTube and TikTok (the Swift category and domain, as apps).
    val snoozeManual = manual("insta", "tiktok")
    val snoozeA = schedule("A", 2_000, "youtube", "safari")
    val snoozeB = schedule("B", 3_000, "insta")
    val sources = listOf(snoozeManual, snoozeA, snoozeB)
    assertTrue("no snooze ⇒ the plain contents", shielded("insta", sources, emptySet()) && shielded("tiktok", sources, emptySet()) && shielded("youtube", sources, emptySet()))
    val insta = setOf("insta")
    assertFalse("the app is taken out of the manual store…", shielded("insta", sources, insta))
    assertTrue("…the other app stays", shielded("tiktok", sources, insta))
    assertFalse("a store the snooze emptied is absent (B shielded only the app)", shielded("insta", listOf(snoozeB), insta))
    assertTrue("a store that does not cover the app is unchanged", shielded("youtube", sources, insta) && shielded("safari", sources, insta))
    assertEquals("the sources covering the snoozed target stay active and own its configuration", BlockSourceKind.Schedule("B"), BlockComposition.owner("insta", sources)?.kind)
    val two = setOf("insta", "tiktok")
    assertFalse("two concurrent snoozes subtract both; the stores they empty are absent", shielded("insta", sources, two) || shielded("tiktok", sources, two))
    assertFalse("…isBlocked() over the manual block alone reads false then", BlockComposition.shieldsSomething(listOf(snoozeManual), two))
    assertTrue("…but true while A still shields something", BlockComposition.shieldsSomething(sources, two))
    assertTrue("a snoozed target nothing shields changes no store", shielded("insta", sources, setOf("unknown")) && shielded("tiktok", sources, setOf("unknown")))
    assertTrue("no sources ⇒ nothing, snoozes or not", ShieldPolicy.targets(setOf("insta"), emptyList(), launchable, exempt, insta).isEmpty())
    // Composition with the unlock suspension: the snooze is applied to whatever the suspension leaves.
    val grant = SuspendedSources.capturing(manualS1, records)
    val unlockedView = BlockComposition.sources(manualS1, plus(records, "B", 5_000, "S3"), resolve, grant)
    assertFalse("a snooze on the one app a schedule started during the unlock shields ⇒ nothing shields", BlockComposition.shieldsSomething(unlockedView, setOf("games")))
  }

  @Test
  fun `storeShields - snoozes under an allowlist`() {
    assertFalse("a snoozed app joins the except list — the only way to lift one app from \"every other app\"", shielded("music", listOf(allowManual), setOf("music")))
    assertTrue("the store stays an allowlist store", shielded("insta", listOf(allowManual), setOf("music")) && BlockComposition.shieldsSomething(listOf(allowManual), setOf("music")))
    assertFalse("a snooze on an app the blocklist shields inside an allowlist store lifts it from both", shielded("slack", listOf(allowA, thresholdInA), setOf("slack")))
    assertTrue("…the other apps are as before", shielded("music", listOf(allowA, thresholdInA), setOf("slack")) && !shielded("mail", listOf(allowA, thresholdInA), setOf("slack")))
    assertFalse("a snooze still subtracts from the blocklist stores as before", shielded("calendar", listOf(allowManual, scheduleBlock), setOf("calendar")))
    assertTrue("an allowlist always shields something, whatever is snoozed", BlockComposition.shieldsSomething(listOf(allowNothing), setOf("music", "insta")))
  }

  @Test
  fun `targetedUnlockVerdict - what temporaryUnlock(target) may lift`() {
    val verdictManual = manual("insta", "safari")
    val verdictAllow = schedule("F", 1, "mail", mode = BlockMode.allowlist)
    fun verdict(type: String, token: String, sources: List<BlockSource>) = BlockComposition.targetedUnlockVerdict(type, token, sources)
    assertEquals("an app a blocklist lists is liftable", BlockComposition.TargetVerdict.LIFTABLE, verdict(TokenHandles.APPLICATION, "insta", listOf(verdictManual)))
    assertTrue(
      "a token no source lists is not shielded",
      verdict(TokenHandles.APPLICATION, "tiktok", listOf(verdictManual)) == BlockComposition.TargetVerdict.NOT_SHIELDED &&
        verdict(TokenHandles.APPLICATION, "insta", emptyList()) == BlockComposition.TargetVerdict.NOT_SHIELDED,
    )
    assertEquals("an app an allowlist shields is liftable (the projection excepts it)", BlockComposition.TargetVerdict.LIFTABLE, verdict(TokenHandles.APPLICATION, "insta", listOf(verdictAllow)))
    assertEquals("an app the allowlist allows is not shielded", BlockComposition.TargetVerdict.NOT_SHIELDED, verdict(TokenHandles.APPLICATION, "mail", listOf(verdictAllow)))
    assertEquals("a category cannot be lifted while an allowlist is in force", BlockComposition.TargetVerdict.CATEGORY_UNDER_ALLOWLIST, verdict(TokenHandles.CATEGORY, "social", listOf(verdictAllow)))
    assertEquals("…even with a blocklist beside it", BlockComposition.TargetVerdict.CATEGORY_UNDER_ALLOWLIST, verdict(TokenHandles.CATEGORY, "social", listOf(verdictAllow, verdictManual)))
    assertEquals("a category nothing shields (categories are not selectable here) is not shielded", BlockComposition.TargetVerdict.NOT_SHIELDED, verdict(TokenHandles.CATEGORY, "social", listOf(verdictManual)))
    assertEquals("a web domain is never shielded on Android", BlockComposition.TargetVerdict.NOT_SHIELDED, verdict(TokenHandles.WEB_DOMAIN, "reddit.com", listOf(verdictManual, verdictAllow)))
    assertFalse("(the projection confirms it: a liftable app comes out of every store, the allowlist's included)", shielded("insta", listOf(verdictAllow, verdictManual), setOf("insta")))
    // A grant in progress suspends the manual block: the target is still liftable from the sources with the suspension NOT honoured (renewable).
    val suspended = BlockComposition.sources(manualS1, MonitoringState(), resolve, SuspendedSources(manual = true))
    assertTrue("a suspended block shields nothing…", suspended.isEmpty())
    assertEquals("…but still counts as a block for a targeted unlock", BlockComposition.TargetVerdict.LIFTABLE, verdict(TokenHandles.APPLICATION, "insta", BlockComposition.sources(manualS1, MonitoringState(), resolve)))
  }

  @Test
  fun `ShieldLifts - an unblock lasts exactly while the composition it was pressed under is in force`() {
    val sources = listOf(manual, scheduleA)
    val key = BlockComposition.fingerprint(sources)
    val lifts = ShieldLifts.NONE.plus(key, "insta").plus(key, "youtube")
    assertEquals("in force under the same sources", setOf("insta", "youtube"), lifts.under(sources))
    assertEquals("the same sources composed in another process give the same key", key, BlockComposition.fingerprint(listOf(manual("insta"), schedule("A", 2_000, "youtube"))))
    assertTrue("a schedule starting voids it (review finding: an engine restarted after the change too)", lifts.under(listOf(manual, scheduleA, scheduleB)).isEmpty())
    assertTrue("a source ending voids it", lifts.under(listOf(manual)).isEmpty())
    assertTrue("a source's apps changing voids it", lifts.under(listOf(manual("insta", "safari"), scheduleA)).isEmpty())
    assertTrue("an unlock ending (the suspension lifting) voids it", lifts.under(emptyList()).isEmpty())
    assertEquals("a press under another composition replaces what was lifted before", setOf("tiktok"), lifts.plus("other", "tiktok").apps)
    assertEquals("…and is in force under its own", setOf("tiktok"), lifts.plus(BlockComposition.fingerprint(listOf(manual)), "tiktok").under(listOf(manual)))

    val document = JSONObject()
    ShieldLifts.write(document, lifts)
    assertEquals("round trip through the document", lifts, ShieldLifts.read(JSONObject(document.toString())))
    ShieldLifts.write(document, ShieldLifts.NONE)
    assertFalse("no lifted app leaves no key", document.has(ShieldLifts.KEY))
    assertEquals("a record without its key is no lift (fail closed)", ShieldLifts.NONE, ShieldLifts.read(JSONObject().put(ShieldLifts.KEY, JSONObject().put("apps", org.json.JSONArray(listOf("insta"))))))
    assertTrue(ShieldLifts.read(JSONObject().put(ShieldLifts.KEY, JSONObject().put("sources", key).put("apps", org.json.JSONArray(listOf(1, "", JSONObject.NULL))))).isEmpty)
  }
}
