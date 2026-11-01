import Foundation

// Minimal harness: each `check` prints one line; the process exits non-zero
// if any check failed. Tokens are plain strings ("app:x", "cat:y", "web:z").

var passed = 0
var failed = 0

func check(_ condition: Bool, _ name: String, file: StaticString = #file, line: UInt = #line) {
    if condition {
        passed += 1
        print("  ok   \(name)")
    } else {
        failed += 1
        print("  FAIL \(name)  (\(file):\(line))")
    }
}

func section(_ title: String) {
    print("\n\(title)")
}

typealias Source = BlockSource<String>

// Fixed calendar so the date tests do not depend on the machine's zone.
var utc = Calendar(identifier: .gregorian)
utc.timeZone = TimeZone(identifier: "UTC")!
var paris = Calendar(identifier: .gregorian)
paris.timeZone = TimeZone(identifier: "Europe/Paris")!

func date(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int, _ s: Int = 0, _ calendar: Calendar = utc) -> Date {
    return calendar.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi, second: s))!
}

func ms(_ date: Date) -> Double { return date.timeIntervalSince1970 * 1000 }

// MARK: - Store naming

section("BlockStore.activity — store names (injective)")
let hashed = { (name: String) in "expo_st_activity_" + name + "-" + BlockStore.fnv1a(name) }
check(BlockStore.activity("workHours") == hashed("workHours"), "a safe name is kept readable and always hashed")
check(BlockStore.activity("bed.time_v2-x") == hashed("bed.time_v2-x"), "dots, underscores and dashes are kept")
let spaced = BlockStore.activity("work hours")
check(spaced == "expo_st_activity_work_hours-" + BlockStore.fnv1a("work hours"),
      "unsafe characters are replaced in the readable part: \(spaced)")
// Reproduced collision: the sanitized form of "work hours" plus its hash IS a
// valid activity name, and both used to map to the same store.
let collider = "work_hours-" + BlockStore.fnv1a("work hours")
check(spaced != BlockStore.activity(collider), "\"work hours\" and \"\(collider)\" get different stores")
check(spaced != BlockStore.activity("work/hours"), "different unsafe names get different stores")
check(spaced != BlockStore.activity("work_hours"), "a sanitized name never collides with a safe one")
check(BlockStore.activity("work hours") == spaced, "deterministic across calls")
check(BlockStore.activity("été 🎉").allSatisfy { $0.isASCII }, "non-ASCII is reduced to ASCII")
check(BlockStore.activity("") == "expo_st_activity_-" + BlockStore.fnv1a(""), "empty name still yields a distinct store")
let long = String(repeating: "a", count: 100)
check(BlockStore.activity(long).count == "expo_st_activity_".count + 32 + 1 + BlockStore.fnv1a(long).count,
      "the readable part is capped at 32 characters")
check(BlockStore.activity(long) != BlockStore.activity(long + "b"), "names that differ past the cap still differ")
let names = ["a", "a ", " a", "a_", "a-", "a_b", "a b", "a/b", "A", "é", "e", "x-y", "x_y", "x y", collider, "work hours"]
check(Set(names.map(BlockStore.activity)).count == names.count, "\(names.count) distinct names ⇒ \(names.count) distinct stores")
check(BlockStore.fnv1a("") == "cbf29ce484222325", "FNV-1a 64 offset basis for the empty string")
check(BlockStore.fnv1a("a") == "af63dc4c8601ec8c", "FNV-1a 64 of \"a\" matches the reference vector")
check(BlockStore.manual == "expo_st_manual" && BlockStore.web == "expo_st_web", "fixed names for manual and web stores")

// MARK: - Union and per-store contents (fix 5)

section("BlockComposition.storeContents — one store per source")
let manual = Source(kind: .manual, startedAt: 1_000, tokens: ["app:insta", "cat:social"])
let scheduleA = Source(kind: .schedule(activityName: "A"), startedAt: 2_000, tokens: ["app:youtube", "web:reddit.com"])
let scheduleB = Source(kind: .schedule(activityName: "B"), startedAt: 3_000, tokens: ["app:tiktok", "app:youtube"])
let thresholdA = Source(kind: .threshold(activityName: "A"), startedAt: 2_500, tokens: ["app:games"])

let all = BlockComposition.storeContents(of: [manual, scheduleA, scheduleB, thresholdA])
check(all[BlockStore.manual] == ["app:insta", "cat:social"], "manual block lives in the manual store")
check(all[BlockStore.activity("A")] == ["app:youtube", "web:reddit.com", "app:games"],
      "an activity's store holds its schedule AND its threshold shields")
check(all[BlockStore.activity("B")] == ["app:tiktok", "app:youtube"], "the other activity has its own store")
check(all.count == 3, "no other store is written")

// Device-observed defect: an unrelated activity's intervalDidEnd removed the manual shields.
let afterAEnds = BlockComposition.storeContents(of: [manual, scheduleB])
check(afterAEnds[BlockStore.manual] == manual.tokens, "unrelated activity end keeps the manual block")
check(afterAEnds[BlockStore.activity("A")] == nil, "the ended activity's store is emptied (absent = empty)")
check(afterAEnds[BlockStore.activity("B")] == scheduleB.tokens, "the other overlapping schedule keeps its shields")
check(afterAEnds[BlockStore.activity("A")] == nil && (all[BlockStore.activity("A")]?.contains("app:games") ?? false),
      "threshold shields of the ended activity go with it (cleared at interval end)")

let afterUnblockAll = BlockComposition.storeContents(of: [Source]())
check(afterUnblockAll.isEmpty, "no sources ⇒ every store empty")

section("BlockComposition.union — what an unlock suspends and meters")
check(BlockComposition.union(of: [manual, scheduleA, scheduleB, thresholdA]) ==
      ["app:insta", "cat:social", "app:youtube", "web:reddit.com", "app:tiktok", "app:games"],
      "union covers every active source")
check(BlockComposition.union(of: [manual]) == manual.tokens, "manual only")
check(BlockComposition.union(of: [Source]()).isEmpty, "nothing active ⇒ empty (NO_ACTIVE_BLOCK)")

// MARK: - Precedence (fix 8)

section("BlockComposition.owner — threshold > latest schedule > manual > default")
let sources = [manual, scheduleA, scheduleB, thresholdA]
check(BlockComposition.owner(ofAnyOf: ["app:games"], in: sources)?.kind == .threshold(activityName: "A"),
      "threshold wins")
check(BlockComposition.owner(ofAnyOf: ["app:youtube"], in: sources)?.kind == .schedule(activityName: "B"),
      "shared by two schedules ⇒ the latest schedule (B started later)")
check(BlockComposition.owner(ofAnyOf: ["web:reddit.com"], in: sources)?.kind == .schedule(activityName: "A"),
      "covered by one schedule ⇒ that schedule")
check(BlockComposition.owner(ofAnyOf: ["app:insta"], in: sources)?.kind == .manual, "manual block")
check(BlockComposition.owner(ofAnyOf: ["app:unknown"], in: sources) == nil, "nothing covers it ⇒ nil (default config)")
check(BlockComposition.owner(ofAnyOf: ["app:unknown", "cat:social"], in: sources)?.kind == .manual,
      "an app shielded through its category matches on the category token")

let manualAlsoYoutube = Source(kind: .manual, startedAt: 9_000, tokens: ["app:youtube"])
check(BlockComposition.owner(ofAnyOf: ["app:youtube"], in: [manualAlsoYoutube, scheduleA])?.kind == .schedule(activityName: "A"),
      "a schedule beats the manual block even when the manual block is newer")
let thresholdB = Source(kind: .threshold(activityName: "B"), startedAt: 100, tokens: ["app:games"])
check(BlockComposition.owner(ofAnyOf: ["app:games"], in: [thresholdA, thresholdB, scheduleB])?.kind == .threshold(activityName: "A"),
      "two thresholds ⇒ the latest one")
let sameStart = Source(kind: .schedule(activityName: "Z"), startedAt: 3_000, tokens: ["app:tiktok"])
check(BlockComposition.owner(ofAnyOf: ["app:tiktok"], in: [sameStart, scheduleB])?.kind == .schedule(activityName: "B"),
      "equal start times break ties by name, deterministically")
check(BlockComposition.owner(ofAnyOf: [], in: sources) == nil, "no target tokens ⇒ nil")

// MARK: - Records → sources, and the unlock suspension (fixes 5, 7 of the contract; review findings 5 and 11)

section("BlockComposition.sources — records to sources")
let selections: [String: Set<String>] = [
    "S1": ["app:insta", "cat:social"],
    "S2": ["app:youtube"],
    "S3": ["app:games"],
    "empty": [],
]
let resolve = { (id: String) -> Set<String>? in selections[id] }
var records = BlockRecords(manualSelectionId: "S1")
records.intervals["A"] = ActiveInterval(startedAt: 1_000, lifted: false)
records.scheduleSelectionIds["A"] = "S2"
records.thresholdSelectionIds["A"] = ["S3"]
let live = BlockComposition.sources(from: records, suspended: nil, resolve: resolve)
check(live.map(\.kind) == [.manual, .schedule(activityName: "A"), .threshold(activityName: "A")],
      "manual, schedule and threshold sources in a stable order")
check(live[1].startedAt == 1_000 && live[2].startedAt == 1_000, "an activity's sources carry its interval start")
check(BlockComposition.union(of: live) == ["app:insta", "cat:social", "app:youtube", "app:games"], "union of every record")

var liftedRecords = records
liftedRecords.intervals["A"] = ActiveInterval(startedAt: 1_000, lifted: true)
check(BlockComposition.sources(from: liftedRecords, suspended: nil, resolve: resolve).map(\.kind) == [.manual, .threshold(activityName: "A")],
      "a lifted interval shields nothing, its thresholds still do")
var orphan = records
orphan.intervals = [:]
check(BlockComposition.sources(from: orphan, suspended: nil, resolve: resolve).map(\.kind) == [.manual],
      "a threshold list without its interval (late callback after cleanup) shields nothing")
var unknown = records
unknown.scheduleSelectionIds["A"] = "missing"
unknown.manualSelectionId = "empty"
check(BlockComposition.sources(from: unknown, suspended: nil, resolve: resolve).map(\.kind) == [.threshold(activityName: "A")],
      "unresolvable or empty selections are omitted")

section("SuspendedSources — what temporaryUnlock suspends (finding 5)")
let grant = SuspendedSources.capturing(records)
check(grant.manual && grant.schedules == ["A": 1_000] && grant.thresholds == ["A": .init(startedAt: 1_000, selectionIds: ["S3"])],
      "a grant captures the manual block, the occurrence in progress and the fired thresholds (with their occurrence)")
check(BlockComposition.sources(from: records, suspended: grant, resolve: resolve).isEmpty,
      "every captured source is excluded from the projection ⇒ every store empty")
check(BlockComposition.storeContents(of: BlockComposition.sources(from: records, suspended: grant, resolve: resolve)).isEmpty,
      "storeContents under the grant writes nothing")

// The delayed, deduplicated intervalDidStart of A re-projects A's store: still suspended.
check(BlockComposition.storeContents(of: BlockComposition.sources(from: records, suspended: grant, resolve: resolve))[BlockStore.activity("A")] == nil,
      "re-projecting A during the grant keeps A's store empty")

// A schedule that starts DURING the grant is not suspended and applies normally.
var during = records
during.intervals["B"] = ActiveInterval(startedAt: 5_000, lifted: false)
during.scheduleSelectionIds["B"] = "S3"
let duringSources = BlockComposition.sources(from: during, suspended: grant, resolve: resolve)
check(duringSources.map(\.kind) == [.schedule(activityName: "B")], "a schedule starting during the grant is the only live source")
check(BlockComposition.storeContents(of: duringSources) == [BlockStore.activity("B"): ["app:games"]],
      "only B's store is written; the suspended stores stay empty")

// A threshold that fires during the grant shields its own selection only.
var thresholdDuring = records
thresholdDuring.thresholdSelectionIds["A"] = ["S3", "S2"]
let thresholdSources = BlockComposition.sources(from: thresholdDuring, suspended: grant, resolve: resolve)
check(thresholdSources.map(\.kind) == [.threshold(activityName: "A")] && thresholdSources[0].tokens == ["app:youtube"],
      "a new threshold during the grant shields its selection, not the suspended one")

// The next occurrence of A is a different source.
var nextDay = records
nextDay.intervals["A"] = ActiveInterval(startedAt: 90_000, lifted: false)
nextDay.thresholdSelectionIds = [:]
check(BlockComposition.sources(from: nextDay, suspended: grant, resolve: resolve).map(\.kind) == [.schedule(activityName: "A")],
      "A's next occurrence (new startedAt) is not suspended")
check(!grant.suspends(scheduleOf: "A", startedAt: 90_000) && grant.suspends(scheduleOf: "A", startedAt: 1_000),
      "suspension is by occurrence, not by name")
check(!grant.suspends(thresholdOf: "A", startedAt: 1_000, selectionId: "S2") && grant.suspends(thresholdOf: "A", startedAt: 1_000, selectionId: "S3"),
      "threshold suspension is per selection")

// Renewal (finding 11): everything active — suspended or not — is restorable and captured again.
let renewalTokens = BlockComposition.union(of: BlockComposition.sources(from: during, suspended: nil, resolve: resolve))
check(renewalTokens == ["app:insta", "cat:social", "app:youtube", "app:games"],
      "renewal meters the union of suspended and live sources (never NO_ACTIVE_BLOCK while something is restorable)")
let renewed = SuspendedSources.capturing(during)
check(renewed.schedules == ["A": 1_000, "B": 5_000] && renewed.manual, "the renewed grant suspends B too")
check(BlockComposition.sources(from: during, suspended: renewed, resolve: resolve).isEmpty, "after renewal every store is empty again")
check(SuspendedSources.capturing(BlockRecords()).isEmpty, "nothing active ⇒ empty suspension")
check(SuspendedSources.capturing(liftedRecords).schedules.isEmpty, "a lifted interval is not captured")
check(SuspendedSources.capturing(orphan).thresholds.isEmpty, "a threshold list without its interval is not captured")

// Property-list round trip (UserDefaults hands numbers back as NSNumber).
let plist = grant.dictionary
var fromDefaults = plist
fromDefaults["schedules"] = ["A": NSNumber(value: 1_000.0)]
check(SuspendedSources(dictionary: plist) == grant, "dictionary round trip")
check(SuspendedSources(dictionary: fromDefaults) == grant, "NSNumber start times are read back")
check(SuspendedSources(dictionary: [:]) == nil, "a dictionary without `manual` is not a suspension")

section("SuspendedSources — threshold suspension is per occurrence (pass 3, finding 3)")
// Reproduced: suspend A's threshold on S3, stop and restart A during the grant
// (a new occurrence), S3 fires again — by name alone it stayed suppressed.
var restarted = records
restarted.intervals["A"] = ActiveInterval(startedAt: 5_000, lifted: false)
restarted.thresholdSelectionIds["A"] = ["S3"]
let afterRestart = BlockComposition.sources(from: restarted, suspended: grant, resolve: resolve)
check(afterRestart.map(\.kind) == [.schedule(activityName: "A"), .threshold(activityName: "A")],
      "after a stop/restart during the grant, a threshold that fires again shields (new occurrence, not suspended)")
check(afterRestart.last?.tokens == ["app:games"], "…its own selection")
check(!grant.suspends(thresholdOf: "A", startedAt: 5_000, selectionId: "S3") && grant.suspends(thresholdOf: "A", startedAt: 1_000, selectionId: "S3"),
      "the suspension is bound to the occurrence it was captured in")
// Re-registering A in place (same occurrence) re-arms its thresholds: a re-fire stays suspended.
var rearmed = records
rearmed.thresholdSelectionIds["A"] = ["S3"]
check(BlockComposition.sources(from: rearmed, suspended: grant, resolve: resolve).isEmpty,
      "a threshold re-firing in the SAME occurrence stays suspended")
let v2 = grant.dictionary
check((v2["thresholds"] as? [String: Any])?.keys.sorted() == ["A"] && v2["thresholdSelectionIds"] == nil,
      "persisted under `thresholds`, with the occurrence")
var v2FromDefaults = v2
v2FromDefaults["thresholds"] = ["A": ["startedAt": NSNumber(value: 1_000.0), "selectionIds": ["S3"]]]
check(SuspendedSources(dictionary: v2FromDefaults) == grant, "NSNumber occurrence start is read back")
let v1: [String: Any] = ["manual": true, "schedules": ["A": 1_000.0], "thresholdSelectionIds": ["A": ["S3"]]]
check(SuspendedSources(dictionary: v1)?.thresholds.isEmpty == true && SuspendedSources(dictionary: v1)?.manual == true,
      "a grant persisted by name only (previous layout) suspends no threshold — fail closed, the shields come back")

section("BlockComposition.owner(preferring:) — configuration and actions agree (finding 6)")
// Instagram is in the manual selection directly and in the Social category that schedule A shields.
let manualInsta = Source(kind: .manual, startedAt: 0, tokens: ["app:insta"])
let socialA = Source(kind: .schedule(activityName: "A"), startedAt: 2_000, tokens: ["cat:social"])
let configurationOwner = BlockComposition.owner(ofAnyOf: ["app:insta", "cat:social"], in: [manualInsta, socialA])
check(configurationOwner?.kind == .schedule(activityName: "A"), "the configuration (app + category) resolves the schedule")
check(BlockComposition.owner(ofAnyOf: ["app:insta"], in: [manualInsta, socialA])?.kind == .manual,
      "the action handler alone (app token only) would resolve the manual block — the defect")
check(BlockComposition.owner(ofAnyOf: ["app:insta"], in: [manualInsta, socialA], preferring: configurationOwner?.kind)?.kind == .schedule(activityName: "A"),
      "preferring the configuration's owner keeps the action on the schedule")
check(BlockComposition.owner(ofAnyOf: ["app:insta"], in: [manualInsta], preferring: .schedule(activityName: "A"))?.kind == .manual,
      "a persisted owner that is no longer active falls back to the action's own resolution")
check(BlockComposition.owner(ofAnyOf: ["app:insta"], in: [manualInsta, socialA], preferring: nil)?.kind == .manual,
      "no persisted owner ⇒ own resolution")
let suspendedView = BlockComposition.sources(from: records, suspended: grant, resolve: resolve)
check(BlockComposition.owner(ofAnyOf: ["app:insta"], in: suspendedView, preferring: .manual) == nil,
      "a suspended source owns nothing, for the configuration and the action alike")
check(BlockSource<String>.Kind(dictionary: BlockSource<String>.Kind.schedule(activityName: "A").dictionary) == .schedule(activityName: "A")
      && BlockSource<String>.Kind(dictionary: BlockSource<String>.Kind.threshold(activityName: "T").dictionary) == .threshold(activityName: "T")
      && BlockSource<String>.Kind(dictionary: BlockSource<String>.Kind.manual.dictionary) == .manual,
      "owner kinds round-trip through their dictionary form")
check(BlockSource<String>.Kind(dictionary: ["kind": "schedule"]) == nil && BlockSource<String>.Kind(dictionary: [:]) == nil,
      "malformed owner records are ignored")

section("BlockComposition.handoffTargets — no app's own owner under a shared category key (pass 3, finding 5)")
// Reproduced: X and Y are in category C. A threshold T shields X directly; a
// schedule A shields C. The configuration for X-in-C resolves T, for Y-in-C
// resolves A — and both used to write the category key: last writer won, so
// Y's shield could serve T's actions (or X's serve A's), by order.
let thresholdX = Source(kind: .threshold(activityName: "T"), startedAt: 10, tokens: ["app:X"])
let scheduleC = Source(kind: .schedule(activityName: "A"), startedAt: 20, tokens: ["cat:C"])
let handoffSources = [thresholdX, scheduleC]
let ownerX = BlockComposition.owner(ofAnyOf: ["app:X", "cat:C"], in: handoffSources)
let ownerY = BlockComposition.owner(ofAnyOf: ["app:Y", "cat:C"], in: handoffSources)
check(ownerX?.kind == .threshold(activityName: "T") && ownerY?.kind == .schedule(activityName: "A"), "X-in-C → T, Y-in-C → A")
check(BlockComposition.handoffTargets(of: ownerX, primary: "app:X", category: "cat:C") == ["app:X"],
      "an owner that covers the app directly is persisted under the app only — never under the category")
check(BlockComposition.handoffTargets(of: ownerY, primary: "app:Y", category: "cat:C") == ["app:Y", "cat:C"],
      "an owner that shields through the category is persisted under the app and the category")
check(BlockComposition.handoffTargets(of: nil as Source?, primary: "app:Z", category: "cat:C") == ["app:Z", "cat:C"],
      "no owner clears both entries")
check(BlockComposition.handoffTargets(of: ownerX, primary: "app:X", category: nil) == ["app:X"], "no category ⇒ the app only")
// Simulate both configuration orders through the map: the category entry is the same either way.
func configure(_ order: [(primary: String, owner: Source?)], into map: inout ShieldOwnerMap) {
    for (index, step) in order.enumerated() {
        let targets = BlockComposition.handoffTargets(of: step.owner, primary: step.primary, category: "cat:C")
        map.record(step.owner?.kind, under: targets.map { ($0, $0 == step.primary ? ShieldOwnerMap.Context.application : .category) }, at: Double(index))
    }
}
var mapXY = ShieldOwnerMap()
configure([("app:X", ownerX), ("app:Y", ownerY)], into: &mapXY)
var mapYX = ShieldOwnerMap()
configure([("app:Y", ownerY), ("app:X", ownerX)], into: &mapYX)
check(mapXY.owner(forKey: "cat:C", context: .category) == .schedule(activityName: "A")
      && mapYX.owner(forKey: "cat:C", context: .category) == .schedule(activityName: "A"),
      "the category entry is A whichever shield was configured last")
check(mapXY.owner(forKey: "app:X", context: .application) == .threshold(activityName: "T")
      && mapYX.owner(forKey: "app:Y", context: .application) == .schedule(activityName: "A"),
      "each app keeps its own entry")
check(mapXY.entries.keys.sorted() == mapYX.entries.keys.sorted() && mapXY.entries.keys.sorted() == ["app:X", "app:Y", "cat:C"],
      "the same three entries in both orders")
// The action extension, with the single token iOS hands it:
check(BlockComposition.owner(ofAnyOf: ["cat:C"], in: handoffSources, preferring: mapXY.owner(forKey: "cat:C", context: .category), coveringTargets: true)?.kind
      == .schedule(activityName: "A"), "Y's action (category context) gets A")
check(BlockComposition.owner(ofAnyOf: ["app:X"], in: handoffSources, preferring: mapXY.owner(forKey: "app:X", context: .application))?.kind
      == .threshold(activityName: "T"), "X's action (application context) gets T")
check(BlockComposition.owner(ofAnyOf: ["cat:C"], in: handoffSources, preferring: .threshold(activityName: "T"), coveringTargets: true)?.kind
      == .schedule(activityName: "A"), "a category entry that does not shield through the category (a foreign owner) is rejected ⇒ own resolution")
check(mapXY.owner(forKey: "cat:C", context: .application) == nil, "an entry is only served to the context that wrote it")

section("ShieldOwnerMap — bounded and pruned (pass 3, finding 11)")
var bounded = ShieldOwnerMap()
for index in 0..<(ShieldOwnerMap.capacity + 10) {
    bounded.record(.schedule(activityName: "A"), under: [("tok:\(index)", .application)], at: Double(index))
}
check(bounded.entries.count == ShieldOwnerMap.capacity, "the map never exceeds \(ShieldOwnerMap.capacity) entries")
check(bounded.owner(forKey: "tok:0", context: .application) == nil && bounded.owner(forKey: "tok:9", context: .application) == nil
      && bounded.owner(forKey: "tok:10", context: .application) != nil, "the oldest entries are evicted first")
bounded.record(.manual, under: [("tok:m", .application)], at: 1_000)
bounded.record(.threshold(activityName: "B"), under: [("tok:b", .category)], at: 1_001)
bounded.prune(keeping: { $0.activityName != "A" })
check(bounded.entries.count == 2 && bounded.owner(forKey: "tok:m", context: .application) == .manual,
      "ending activity A drops every entry it owned, the others stay")
bounded.prune(keeping: { [.manual].contains($0) })
check(bounded.entries.keys.sorted() == ["tok:m"], "a full projection keeps the owners still in force only")
bounded.record(nil, under: [("tok:m", .application)], at: 2_000)
check(bounded.entries.isEmpty, "recording no owner clears the entry")
check(ShieldOwnerMap(dictionary: mapXY.dictionary) == mapXY, "dictionary round trip")
check(ShieldOwnerMap(dictionary: ["bad": ["owner": ["kind": "schedule"], "context": "application"]]).entries.isEmpty
      && ShieldOwnerMap(dictionary: ["bad": ["owner": ["kind": "manual"], "context": "window"]]).entries.isEmpty,
      "malformed entries are dropped")

// MARK: - Daily schedule (fixes 2 and 12)

section("DailySchedule — occurrences")
let office = DailySchedule(start: .init(hour: 9, minute: 0, second: 0), end: .init(hour: 17, minute: 0, second: 0))
check(!office.crossesMidnight, "9–17 does not cross midnight")
// Wednesday 2026-10-28
check(office.isActive(at: date(2026, 10, 28, 10, 0), calendar: utc), "10:00 is inside 9–17")
check(!office.isActive(at: date(2026, 10, 28, 8, 59, 59), calendar: utc), "08:59:59 is before the start")
check(office.isActive(at: date(2026, 10, 28, 9, 0, 0), calendar: utc), "the start instant is inside")
check(!office.isActive(at: date(2026, 10, 28, 17, 0, 0), calendar: utc), "the end instant is outside")
check(office.latestStart(at: date(2026, 10, 28, 10, 0), calendar: utc) == date(2026, 10, 28, 9, 0),
      "latest start at 10:00 is today 09:00")
check(office.latestStart(at: date(2026, 10, 28, 8, 0), calendar: utc) == date(2026, 10, 27, 9, 0),
      "latest start at 08:00 is yesterday 09:00")
check(office.occurrence(at: date(2026, 10, 28, 10, 0), calendar: utc) ==
      DateInterval(start: date(2026, 10, 28, 9, 0), end: date(2026, 10, 28, 17, 0)), "occurrence bounds")

let night = DailySchedule(start: .init(hour: 22, minute: 0, second: 0), end: .init(hour: 6, minute: 0, second: 0))
check(night.crossesMidnight, "22–06 crosses midnight")
check(night.isActive(at: date(2026, 10, 28, 23, 30), calendar: utc), "23:30 is inside 22–06")
check(night.isActive(at: date(2026, 10, 29, 1, 0), calendar: utc), "01:00 next day is inside 22–06")
check(!night.isActive(at: date(2026, 10, 29, 6, 0), calendar: utc), "06:00 is outside")
check(!night.isActive(at: date(2026, 10, 29, 12, 0), calendar: utc), "noon is outside")
check(night.latestStart(at: date(2026, 10, 29, 1, 0), calendar: utc) == date(2026, 10, 28, 22, 0),
      "at 01:00 the occurrence started yesterday 22:00")
check(night.end(ofOccurrenceStarting: date(2026, 10, 28, 22, 0), calendar: utc) == date(2026, 10, 29, 6, 0),
      "a crossing occurrence ends the next day")

let allDay = DailySchedule(start: .init(hour: 0, minute: 0, second: 0), end: .init(hour: 0, minute: 0, second: 0))
check(allDay.crossesMidnight && allDay.isActive(at: date(2026, 10, 28, 12, 0), calendar: utc), "equal bounds = 24 h occurrence")

// Europe/Paris: DST ended on 2026-10-25; a plain daily schedule still resolves to wall-clock times.
check(office.latestStart(at: date(2026, 10, 26, 10, 0, 0, paris), calendar: paris) == date(2026, 10, 26, 9, 0, 0, paris),
      "wall-clock start the day after a DST change (Paris)")
check(office.latestStart(at: date(2026, 10, 25, 8, 0, 0, paris), calendar: paris) == date(2026, 10, 24, 9, 0, 0, paris),
      "yesterday's wall-clock start across the DST change (Paris)")

section("DailySchedule — weekdays (one activity per logical name)")
// 2026-10-26 is a Monday (weekday 2). Mon–Fri = [2,3,4,5,6].
let weekdaysOffice = DailySchedule(start: office.start, end: office.end, weekdays: [2, 3, 4, 5, 6])
check(weekdaysOffice.isActive(at: date(2026, 10, 26, 10, 0), calendar: utc), "Monday 10:00 is active")
check(!weekdaysOffice.isActive(at: date(2026, 10, 25, 10, 0), calendar: utc), "Sunday 10:00 is ignored")
check(!weekdaysOffice.isActive(at: date(2026, 10, 31, 10, 0), calendar: utc), "Saturday 10:00 is ignored")
check(weekdaysOffice.isSelected(occurrenceStart: date(2026, 10, 30, 9, 0), calendar: utc), "Friday occurrence selected")
check(!weekdaysOffice.isSelected(occurrenceStart: date(2026, 10, 31, 9, 0), calendar: utc), "Saturday occurrence not selected")

let mondayNight = DailySchedule(start: night.start, end: night.end, weekdays: [2])
check(mondayNight.isActive(at: date(2026, 10, 27, 1, 0), calendar: utc),
      "Tuesday 01:00 belongs to Monday's 22–06 occurrence ⇒ active")
check(!mondayNight.isActive(at: date(2026, 10, 28, 1, 0), calendar: utc),
      "Wednesday 01:00 belongs to Tuesday's occurrence ⇒ ignored")
check(!mondayNight.isActive(at: date(2026, 10, 26, 21, 0), calendar: utc), "Monday 21:00 is before the start")

section("DailySchedule — intervalDidStart dedupe (fix 12)")
let occurrenceStart = date(2026, 10, 28, 9, 0)
check(DailySchedule.isSameOccurrence(markedAt: ms(date(2026, 10, 28, 9, 0, 1)), occurrenceStart: occurrenceStart),
      "a marker written 1 s after the start is the same occurrence ⇒ duplicate")
check(DailySchedule.isSameOccurrence(markedAt: ms(occurrenceStart), occurrenceStart: occurrenceStart),
      "a marker written at the start instant is the same occurrence")
check(!DailySchedule.isSameOccurrence(markedAt: ms(date(2026, 10, 27, 10, 0)), occurrenceStart: occurrenceStart),
      "yesterday's marker is stale ⇒ a new intervalDidStart")

section("DailySchedule.syncDecision — what startMonitoring applies now (findings 3 and 9)")
let dailyDict: [String: Any] = ["intervalStart": ["hour": 9.0, "minute": 0.0], "intervalEnd": ["hour": 17.0, "minute": 0.0]]
check(DailySchedule.syncDecision(scheduleDict: dailyDict, weekdays: nil, now: date(2026, 10, 28, 10, 0), calendar: utc)
      == .apply(occurrenceStart: date(2026, 10, 28, 9, 0)), "inside ⇒ apply from the occurrence start")
check(DailySchedule.syncDecision(scheduleDict: dailyDict, weekdays: nil, now: date(2026, 10, 28, 18, 0), calendar: utc) == .clear,
      "outside ⇒ clear")
check(DailySchedule.syncDecision(scheduleDict: dailyDict, weekdays: [1.0], now: date(2026, 10, 28, 10, 0), calendar: utc) == .clear,
      "inside but on an unselected weekday ⇒ clear")
let fridayToSunday: [String: Any] = [
    "intervalStart": ["hour": 22.0, "minute": 0.0, "weekday": 6.0],
    "intervalEnd": ["hour": 6.0, "minute": 0.0, "weekday": 1.0],
]
check(DailySchedule.syncDecision(scheduleDict: fridayToSunday, weekdays: nil, now: date(2026, 10, 31, 12, 0), calendar: utc) == .leaveToMonitor,
      "Fri 22:00 → Sun 06:00 cannot be computed ⇒ leave to the monitor, never clear")
check(DailySchedule.syncDecision(scheduleDict: ["intervalStart": ["hour": 9]], weekdays: nil, now: date(2026, 10, 28, 10, 0), calendar: utc) == .leaveToMonitor,
      "an incomplete schedule is not \"outside\" either")
check(DailySchedule.syncDecision(scheduleDict: dailyDict, weekdays: nil, now: date(2026, 10, 28, 9, 0), calendar: utc)
      == .apply(occurrenceStart: date(2026, 10, 28, 9, 0)), "the start instant applies")

// The marker is written from the occurrence BEFORE the registration; the monitor's
// callback, milliseconds later, must see the same occurrence.
if case .apply(let start) = DailySchedule.syncDecision(scheduleDict: dailyDict, weekdays: nil, now: date(2026, 10, 28, 10, 0), calendar: utc) {
    let markedAt = ms(date(2026, 10, 28, 10, 0))
    check(DailySchedule.isSameOccurrence(markedAt: markedAt, occurrenceStart: start), "the app's marker dedupes the monitor's start callback")
    check(!DailySchedule.isSameOccurrence(markedAt: ms(date(2026, 10, 27, 10, 0)), occurrenceStart: start), "yesterday's marker does not")
} else {
    check(false, "decision is apply")
}

section("DailySchedule — parsing the JS config")
// JS numbers arrive as Double inside [String: Any].
let jsSchedule: [String: Any] = [
    "intervalStart": ["hour": 9.0, "minute": 30.0],
    "intervalEnd": ["hour": 17.0, "minute": 0.0],
    "repeats": true,
]
let parsed = DailySchedule(scheduleDict: jsSchedule, weekdays: [2.0, 3.0, 9.0, 0.0, 3.0])
check(parsed?.start == .init(hour: 9, minute: 30, second: 0), "Double hour/minute are read")
check(parsed?.end == .init(hour: 17, minute: 0, second: 0), "end parsed, second defaults to 0")
check(parsed?.weekdays == [2, 3], "weekdays: out-of-range dropped, duplicates collapsed")
check(DailySchedule(scheduleDict: jsSchedule, weekdays: nil)?.weekdays == [], "no weekdays ⇒ every day")
check(DailySchedule(scheduleDict: jsSchedule, weekdays: [])?.weekdays == [], "empty weekdays ⇒ every day")

// Stored configs come back from UserDefaults as NSNumber.
let storedSchedule: [String: Any] = [
    "intervalStart": ["hour": NSNumber(value: 22), "minute": NSNumber(value: 0)],
    "intervalEnd": ["hour": NSNumber(value: 6), "minute": NSNumber(value: 15), "second": NSNumber(value: 30)],
]
let stored = DailySchedule(scheduleDict: storedSchedule, weekdays: [NSNumber(value: 1), NSNumber(value: 7)])
check(stored?.start == .init(hour: 22, minute: 0, second: 0) && stored?.end == .init(hour: 6, minute: 15, second: 30),
      "NSNumber components are read")
check(stored?.weekdays == [1, 7], "NSNumber weekdays are read")

let weekly: [String: Any] = [
    "intervalStart": ["hour": 9, "minute": 0, "weekday": 2],
    "intervalEnd": ["hour": 17, "minute": 0, "weekday": 2],
]
check(DailySchedule(scheduleDict: weekly, weekdays: [3, 4])?.weekdays == [2],
      "a weekday on the components makes the interval weekly (components win over the array)")
let multiDay: [String: Any] = [
    "intervalStart": ["hour": 22, "minute": 0, "weekday": 6],
    "intervalEnd": ["hour": 6, "minute": 0, "weekday": 1],
]
check(DailySchedule(scheduleDict: multiDay, weekdays: nil) == nil, "a multi-day weekly interval is not a daily schedule")
check(DailySchedule(scheduleDict: ["intervalStart": ["hour": 9]], weekdays: nil) == nil, "missing intervalEnd ⇒ nil")

section("DailySchedule.intValue")
check(DailySchedule.intValue(9.0) == 9, "Double 9.0 → 9")
check(DailySchedule.intValue(9.5) == 9, "fractional Double truncates through NSNumber, like the module helper in #27")
check(DailySchedule.intValue(7) == 7, "Int passes through")
check(DailySchedule.intValue(NSNumber(value: 3)) == 3, "NSNumber → Int")
check(DailySchedule.intValue("3") == nil, "String → nil")
check(DailySchedule.intValue(nil) == nil, "nil → nil")

// MARK: - Registrations (findings 4 and 8)

section("ActivityRegistration — physical names")
let sep = ActivityRegistration.generationSeparator
check(ActivityRegistration.physicalName(logical: "work hours", generation: 3) == "work hours\(sep)3", "physical = logical + separator + generation")
check(ActivityRegistration.parse("work hours\(sep)3") == .init(logical: "work hours", generation: 3), "round trip")
check(ActivityRegistration.parse("A") == .init(logical: "A", generation: nil), "a plain name is a legacy registration")
check(ActivityRegistration.parse("A\(sep)") == .init(logical: "A\(sep)", generation: nil), "an empty generation is not one")
check(ActivityRegistration.parse("A\(sep)x1") == .init(logical: "A\(sep)x1", generation: nil), "a non-numeric generation is not one")
check(ActivityRegistration.parse("A\(sep)-1") == .init(logical: "A\(sep)-1", generation: nil), "a negative generation is not one")
check(ActivityRegistration.parse("expo_st_temporary_unlock") == .init(logical: "expo_st_temporary_unlock", generation: nil), "the unlock meter parses as itself")
check(ActivityRegistration.isValidLogicalName("work hours") && ActivityRegistration.isValidLogicalName("A_weekday_2"), "ordinary names are valid")
check(!ActivityRegistration.isValidLogicalName("") && !ActivityRegistration.isValidLogicalName("a\(sep)b"),
      "empty names and names containing the separator are rejected")

section("ActivityRegistration — reserved names (pass 3, finding 10)")
check(!ActivityRegistration.isValidLogicalName("expo_st_temporary_unlock"), "the unlock meter's name is rejected (start and stop)")
check(!ActivityRegistration.isValidLogicalName("expo_st_anything") && !ActivityRegistration.isValidLogicalName("expo_st_"),
      "the whole expo_st_ prefix is reserved")
check(ActivityRegistration.isValidLogicalName("expo_stuff") && ActivityRegistration.isValidLogicalName("my_expo_st_thing"),
      "only the prefix is reserved")
check(ActivityRegistration.isInternalName("expo_st_temporary_unlock") && !ActivityRegistration.isInternalName("A\(sep)1"),
      "isInternalName hides the meter and nothing else")
let physicals = (1...3).map { ActivityRegistration.physicalName(logical: "A", generation: $0) }
check(Set(physicals).count == 3 && physicals.allSatisfy { ActivityRegistration.parse($0).logical == "A" },
      "every generation of A parses back to A")

section("ActivityRegistration — stale callbacks are ignored (finding 4)")
var configs: [String: [String: Any]] = ["A": ["_generation": NSNumber(value: 2), "schedule": dailyDict]]
let lookup = { (name: String) -> [String: Any]? in configs[name] }
check(ActivityRegistration.currentRegistration(forPhysical: "A\(sep)2", config: lookup)?.logical == "A", "the current generation is honoured")
check(ActivityRegistration.currentRegistration(forPhysical: "A\(sep)1", config: lookup) == nil,
      "stop A then restart A: the old registration's intervalDidEnd is stale")
check(ActivityRegistration.currentRegistration(forPhysical: "A\(sep)3", config: lookup) == nil, "a generation from the future is not current either")
check(ActivityRegistration.currentRegistration(forPhysical: "A", config: lookup) == nil,
      "a legacy registration of A is stale once A was re-registered with a generation")
check(ActivityRegistration.currentRegistration(forPhysical: "B\(sep)1", config: lookup) == nil, "an activity without config (stopped) is ignored")
configs["A"] = ["_generation": 2.0]
check(ActivityRegistration.currentRegistration(forPhysical: "A\(sep)2", config: lookup)?.logical == "A", "a Double generation (JS-shaped) is read")
configs = [:]
check(ActivityRegistration.currentRegistration(forPhysical: "A\(sep)2", config: lookup) == nil, "re-calling startMonitoring: once the config is gone nothing is current")

section("ActivityRegistration — legacy registrations (finding 8)")
configs = [
    "A": ["_weekdayActivities": ["A_weekday_2", "A_weekday_3", "A_weekday_9", "A_weekday_x", "B_weekday_2", "A_weekday_22"], "schedule": dailyDict],
    "A_weekday_2": ["_parentActivity": "A"],
    "A_weekday_3": ["_parentActivity": "A"],
    "B": ["schedule": dailyDict],
    "B_weekday_2": ["schedule": dailyDict],
]
check(ActivityRegistration.legacyWeekdayVariants(in: configs["A"], of: "A") == ["A_weekday_2", "A_weekday_3"],
      "only the variants the legacy config lists, with a valid _weekday_<1-7> suffix")
check(ActivityRegistration.legacyWeekdayVariants(in: configs["B"], of: "B").isEmpty, "no list ⇒ no variants, whatever other activities are called")
check(ActivityRegistration.legacyWeekdayVariants(in: nil, of: "B").isEmpty, "no config ⇒ no variants")
check(ActivityRegistration.isLegacyWeekdayVariant("A_weekday_7", of: "A") && !ActivityRegistration.isLegacyWeekdayVariant("A_weekday_8", of: "A")
      && !ActivityRegistration.isLegacyWeekdayVariant("A_weekday_", of: "A") && !ActivityRegistration.isLegacyWeekdayVariant("AB_weekday_1", of: "A"),
      "the suffix is exactly _weekday_<1-7>")
check(ActivityRegistration.currentRegistration(forPhysical: "A_weekday_2", config: lookup)?.logical == "A",
      "a legacy variant callback resolves to its parent through its own config")
check(ActivityRegistration.currentRegistration(forPhysical: "A", config: lookup)?.logical == "A", "a legacy plain registration is current while its config has no generation")
check(ActivityRegistration.currentRegistration(forPhysical: "B_weekday_2", config: lookup)?.logical == "B_weekday_2",
      "a real activity called B_weekday_2 is its own activity, not a variant of B")
check(ActivityRegistration.logicalName(ofRegistered: "B_weekday_2", config: lookup) == "B_weekday_2", "…and is listed under its own name")
check(ActivityRegistration.logicalName(ofRegistered: "A_weekday_3", config: lookup) == "A", "a listed legacy variant is reported under its parent")
check(ActivityRegistration.logicalName(ofRegistered: "C\(sep)4", config: lookup) == "C", "a generation suffix is stripped")
check(ActivityRegistration.currentRegistration(forPhysical: "A_weekday_9", config: lookup) == nil, "an unlisted, unconfigured variant-looking name is stale")

section("ActivityRegistration.isLegacyVariantState — only a legacy variant's state is deleted (pass 3, finding 9)")
check(ActivityRegistration.isLegacyVariantState(["_parentActivity": "A"], variant: "A_weekday_2", of: "A"), "a listed variant with the parent's name and no generation")
check(!ActivityRegistration.isLegacyVariantState(["_parentActivity": "A", "_generation": 7], variant: "A_weekday_2", of: "A"),
      "a live activity called A_weekday_2 (it has a generation) is never deleted with A")
check(!ActivityRegistration.isLegacyVariantState(["schedule": dailyDict], variant: "A_weekday_2", of: "A"),
      "a config that does not name the parent is an independent activity")
check(!ActivityRegistration.isLegacyVariantState(["_parentActivity": "B"], variant: "A_weekday_2", of: "A"), "another parent's variant")
check(!ActivityRegistration.isLegacyVariantState(nil, variant: "A_weekday_2", of: "A"), "no config ⇒ nothing to delete")
check(!ActivityRegistration.isLegacyVariantState(["_parentActivity": "A"], variant: "A_weekday_8", of: "A"), "the suffix must be a weekday")

section("ActivityRegistration.intervalPlan — replacing a registration (pass 3, finding 6)")
let inProgress = ActiveInterval(startedAt: ms(date(2026, 10, 28, 9, 0, 1)), lifted: false)
let tenAM = date(2026, 10, 28, 10, 0)
let applyNow = DailySchedule.syncDecision(scheduleDict: dailyDict, weekdays: nil, now: tenAM, calendar: utc)
check(ActivityRegistration.intervalPlan(for: .leaveToMonitor, previous: inProgress, now: tenAM, restoring: false)
      == .init(marker: .clear, keepsThresholds: false, recordsStart: false, recordsEnd: true),
      "a daily activity replaced by a multi-day one: its interval, thresholds and store are cleared and its end recorded — the monitor applies the new one")
check(ActivityRegistration.intervalPlan(for: .leaveToMonitor, previous: nil, now: tenAM, restoring: false)
      == .init(marker: .clear, keepsThresholds: false, recordsStart: false, recordsEnd: false),
      "…and nothing is recorded when nothing was in progress")
check(ActivityRegistration.intervalPlan(for: applyNow, previous: inProgress, now: tenAM, restoring: false)
      == .init(marker: .set(startedAt: inProgress.startedAt), keepsThresholds: false, recordsStart: false, recordsEnd: false),
      "re-registering in place keeps the occurrence (shields stay up, no event pair) and re-arms the thresholds")
check(ActivityRegistration.intervalPlan(for: applyNow, previous: ActiveInterval(startedAt: inProgress.startedAt, lifted: true), now: tenAM, restoring: false)
      == .init(marker: .set(startedAt: inProgress.startedAt), keepsThresholds: false, recordsStart: false, recordsEnd: false),
      "re-registering un-lifts an interval unblockAll lifted")
let yesterdayMarker = ActiveInterval(startedAt: ms(date(2026, 10, 27, 10, 0)), lifted: false)
check(ActivityRegistration.intervalPlan(for: applyNow, previous: yesterdayMarker, now: tenAM, restoring: false)
      == .init(marker: .set(startedAt: ms(tenAM)), keepsThresholds: false, recordsStart: true, recordsEnd: true),
      "a marker from a previous occurrence (missed end) ends, and the new occurrence starts now")
check(ActivityRegistration.intervalPlan(for: applyNow, previous: nil, now: tenAM, restoring: false)
      == .init(marker: .set(startedAt: ms(tenAM)), keepsThresholds: false, recordsStart: true, recordsEnd: false),
      "inside with no previous interval: start now")
check(ActivityRegistration.intervalPlan(for: .clear, previous: inProgress, now: tenAM, restoring: false)
      == .init(marker: .clear, keepsThresholds: false, recordsStart: false, recordsEnd: true),
      "outside: the previous interval ends")
// Rollback restores (finding 7): keep what the previous registration had.
check(ActivityRegistration.intervalPlan(for: applyNow, previous: inProgress, now: tenAM, restoring: true)
      == .init(marker: .keep, keepsThresholds: true, recordsStart: false, recordsEnd: false),
      "a restore inside the same occurrence keeps the marker and the fired thresholds")
check(ActivityRegistration.intervalPlan(for: .leaveToMonitor, previous: inProgress, now: tenAM, restoring: true)
      == .init(marker: .keep, keepsThresholds: true, recordsStart: false, recordsEnd: false),
      "a restore of a schedule this process cannot evaluate keeps what it had")
check(ActivityRegistration.intervalPlan(for: .clear, previous: inProgress, now: tenAM, restoring: true)
      == .init(marker: .clear, keepsThresholds: false, recordsStart: false, recordsEnd: true),
      "a restore outside the occurrence recovers the end the window missed")
check(ActivityRegistration.intervalPlan(for: applyNow, previous: nil, now: tenAM, restoring: true)
      == .init(marker: .set(startedAt: ms(tenAM)), keepsThresholds: false, recordsStart: true, recordsEnd: false),
      "a restore inside with no marker recovers the start the window missed")

section("ActivityRegistration.rollbackConfig — the restored registration is a fresh generation (pass 3, finding 7)")
let released: [String: Any] = ["_generation": 2, "schedule": dailyDict, "selectionId": "S2", "_weekdayActivities": ["A_weekday_2"], "weekdays": [2, 3]]
let restored = ActivityRegistration.rollbackConfig(previous: released, generation: 4)
var rollbackConfigs: [String: [String: Any]] = ["A": restored]
let rollbackLookup = { (name: String) -> [String: Any]? in rollbackConfigs[name] }
check(ActivityRegistration.generation(of: restored) == 4, "the restored config carries the fresh generation")
check(ActivityRegistration.currentRegistration(forPhysical: "A\(sep)2", config: rollbackLookup) == nil,
      "the released registration's late callbacks are stale — they cannot clear the restored interval")
check(ActivityRegistration.currentRegistration(forPhysical: "A\(sep)3", config: rollbackLookup) == nil, "the failed new registration's are stale too")
check(ActivityRegistration.currentRegistration(forPhysical: "A\(sep)4", config: rollbackLookup)?.logical == "A", "the restored registration is current")
check(restored["_weekdayActivities"] == nil && (restored["weekdays"] as? [Int]) == [2, 3] && (restored["selectionId"] as? String) == "S2",
      "legacy variants are dropped (the parent covers their days); everything else is kept")
rollbackConfigs = [:]

section("ActivityRegistration.legacyActiveInterval — migrating the schedule in progress (finding 2)")
// Wednesday 2026-10-28 10:00; the legacy build pointed at the weekday variant.
let legacyConfigs: [String: [String: Any]] = [
    "W": ["schedule": dailyDict, "weekdays": [3.0, 4.0], "selectionId": "S2", "_weekdayActivities": ["W_weekday_3", "W_weekday_4"]],
    "W_weekday_3": ["_parentActivity": "W", "schedule": dailyDict, "selectionId": "S2"],
    "P": ["schedule": dailyDict],
    "M": ["schedule": fridayToSunday],
]
let legacyLookup = { (name: String) -> [String: Any]? in legacyConfigs[name] }
let reconstructed = ActivityRegistration.legacyActiveInterval(pointer: "W_weekday_3", config: legacyLookup, now: date(2026, 10, 28, 10, 0), calendar: utc)
check(reconstructed?.logical == "W" && reconstructed?.startedAt == ms(date(2026, 10, 28, 9, 0)),
      "a weekday-variant pointer becomes its parent's interval, from the occurrence start")
let plain = ActivityRegistration.legacyActiveInterval(pointer: "P", config: legacyLookup, now: date(2026, 10, 28, 10, 0), calendar: utc)
check(plain?.logical == "P" && plain?.startedAt == ms(date(2026, 10, 28, 9, 0)), "a plain pointer becomes its own interval")
check(ActivityRegistration.legacyActiveInterval(pointer: "P", config: legacyLookup, now: date(2026, 10, 28, 18, 0), calendar: utc) == nil,
      "a pointer left behind outside the occurrence is stale ⇒ nothing reconstructed")
check(ActivityRegistration.legacyActiveInterval(pointer: "W_weekday_3", config: legacyLookup, now: date(2026, 10, 26, 10, 0), calendar: utc) == nil,
      "a pointer on an unselected weekday is stale")
check(ActivityRegistration.legacyActiveInterval(pointer: "gone", config: legacyLookup, now: date(2026, 10, 28, 10, 0), calendar: utc) == nil,
      "no config ⇒ nothing to reconstruct")
let weeklyLegacy = ActivityRegistration.legacyActiveInterval(pointer: "M", config: legacyLookup, now: date(2026, 10, 31, 23, 0), calendar: utc)
check(weeklyLegacy?.logical == "M" && weeklyLegacy?.startedAt == ms(date(2026, 10, 31, 23, 0)),
      "a schedule that cannot be computed is reconstructed from now")

// MARK: - Cross-process mutex (pass 3)

section("BlockStateLock — holders of the same file serialize; nesting; release on throw")
let lockDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("expo-st-lock-\(ProcessInfo.processInfo.processIdentifier)")
try! FileManager.default.createDirectory(at: lockDirectory, withIntermediateDirectories: true)
let lockFile = lockDirectory.appendingPathComponent(BlockStateLock.fileName)
// Two instances on one file are two open file descriptions: they contend on
// `flock` exactly like two processes do. Threads of one instance contend on
// its recursive lock. Both paths are covered by four threads over two instances.
let lockA = BlockStateLock(fileURL: lockFile)
let lockB = BlockStateLock(fileURL: lockFile)
var insideCriticalSection = false
var overlaps = 0
var serializedCounter = 0
let iterationsPerThread = 200
let threadsDone = DispatchGroup()
for lock in [lockA, lockB, lockA, lockB] {
    threadsDone.enter()
    Thread {
        for _ in 0..<iterationsPerThread {
            lock.withLock {
                if insideCriticalSection { overlaps += 1 }
                insideCriticalSection = true
                let seen = serializedCounter
                usleep(20)
                serializedCounter = seen + 1
                insideCriticalSection = false
            }
        }
        threadsDone.leave()
    }.start()
}
threadsDone.wait()
check(overlaps == 0, "no two critical sections overlapped (\(overlaps) overlaps)")
check(serializedCounter == 4 * iterationsPerThread, "every read-modify-write survived: \(serializedCounter) of \(4 * iterationsPerThread)")
var nestedRan = false
lockA.withLock { lockA.withLock { nestedRan = true } }
check(nestedRan, "a nested withLock on the holding thread runs at once")
struct Boom: Error {}
check((try? lockA.withLock { () throws -> Int in throw Boom() }) == nil, "a throwing body propagates")
// If the file lock had leaked past the throw (or past the nested call), this would block forever.
let reacquired = lockB.withLock { true }
check(reacquired, "the file lock is released when the outermost call ends, by return or by throw")
check(FileManager.default.fileExists(atPath: lockFile.path), "the lock file is created on first use")
check(BlockStateLock(fileURL: nil).withLock { 42 } == 42, "without a container the lock degrades to in-process serialization")
try? FileManager.default.removeItem(at: lockDirectory)

// MARK: - Monitor event cursor (#23) — kept here so the whole pure layer runs in one go.

section("MonitorEventCursor — regression")
var cursor = MonitorEventCursor(startingAt: 100)
let burst: [[String: Any]] = [
    ["id": "c", "timestamp": 300.0],
    ["id": "b", "timestamp": 200.0],
    ["id": "a", "timestamp": 200.0],
]
check(cursor.take(from: burst).compactMap { $0["id"] as? String } == ["a", "b", "c"], "burst emitted oldest first, ties included")
check(cursor.take(from: burst).isEmpty, "a re-read emits nothing")

print("\n\(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
