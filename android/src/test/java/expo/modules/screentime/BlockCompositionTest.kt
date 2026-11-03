package expo.modules.screentime

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
 * Deferred to step 5 (temporary unlock and shield snooze), not ported
 * here: "SuspendedSources — what temporaryUnlock suspends", "SuspendedSources
 * — threshold suspension is per occurrence", "storeShields — a snooze lifts
 * the pressed target", "storeShields — snoozes under an allowlist",
 * "targetedUnlockVerdict". iOS-structural, not applicable: "BlockStore.activity
 * — store names", "owner(preferring:)" and "handoffTargets" (the two
 * extensions agreeing on one owner; the Android shield resolves it once),
 * "ShieldOwnerMap", and the "a threshold list without its interval" case
 * (thresholds live inside the interval here).
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
    // The manual block holds the apps captured at block time; the schedule and threshold selections are read live.
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
}
