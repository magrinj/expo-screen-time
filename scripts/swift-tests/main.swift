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

// The test tokens carry their kind in their prefix, like the real ones do in
// their case (`ShieldToken.blockTokenKind`).
extension String: BlockToken {
    var blockTokenKind: BlockTokenKind {
        if hasPrefix("app:") { return .application }
        if hasPrefix("cat:") { return .category }
        return .webDomain
    }
}

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

section("DailySchedule — a one-off restricted to weekdays covers the next selected occurrence")
// Today a one-off (`repeats: false`) is registered with the system as given,
// so its single occurrence is the next 09:00 — Saturday's, when registered
// on Friday evening with weekdays Mon–Fri. The monitor extension ignores an
// occurrence on an unselected weekday, and a one-off never calls back
// again: the activity never blocks. The fix covers the next SELECTED
// occurrence (the one in progress when it is selected), as Android does.
let friday18 = date(2026, 10, 30, 18, 0)   // Friday 2026-10-30
let monday9 = date(2026, 11, 2, 9, 0)      // Monday 2026-11-02
check(weekdaysOffice.coveredOccurrenceStart(at: friday18, calendar: utc) == monday9,
      "Friday 18:00, Mon–Fri ⇒ Monday 09:00 (the system's own next occurrence, Saturday's, is unselected)")
check(weekdaysOffice.coveredOccurrenceStart(at: date(2026, 10, 30, 10, 0), calendar: utc) == date(2026, 10, 30, 9, 0),
      "inside Friday's occurrence ⇒ that occurrence")
check(weekdaysOffice.coveredOccurrenceStart(at: date(2026, 10, 31, 10, 0), calendar: utc) == monday9,
      "inside Saturday's (unselected) occurrence ⇒ Monday's")
check(weekdaysOffice.coveredOccurrenceStart(at: date(2026, 10, 26, 8, 0), calendar: utc) == date(2026, 10, 26, 9, 0),
      "Monday 08:00 ⇒ Monday 09:00 (today, later)")
check(weekdaysOffice.coveredOccurrenceStart(at: date(2026, 10, 26, 9, 0), calendar: utc) == date(2026, 10, 26, 9, 0),
      "the start instant is in progress")
check(mondayNight.coveredOccurrenceStart(at: date(2026, 10, 27, 1, 0), calendar: utc) == date(2026, 10, 26, 22, 0),
      "Tuesday 01:00 is inside Monday's 22–06 ⇒ Monday 22:00")
check(mondayNight.coveredOccurrenceStart(at: date(2026, 10, 27, 7, 0), calendar: utc) == date(2026, 11, 2, 22, 0),
      "Tuesday 07:00 ⇒ next Monday 22:00 (six days later)")
check(office.coveredOccurrenceStart(at: friday18, calendar: utc) == date(2026, 10, 31, 9, 0),
      "no weekdays ⇒ the next start, Saturday 09:00, as the system computes it")
check(office.coveredOccurrenceStart(at: date(2026, 10, 25, 8, 0, 0, paris), calendar: paris) == date(2026, 10, 25, 9, 0, 0, paris),
      "wall-clock start on the day of a DST change (Paris)")

// What `startMonitoring` registers: the covered occurrence's weekdays pinned
// on the system's date components — only when that occurrence starts after
// now. A selected occurrence in progress is registered as given (the
// system's own in-progress interval, the path every one-off takes today).
let oneOffDict: [String: Any] = [
    "intervalStart": ["hour": 9.0, "minute": 0.0], "intervalEnd": ["hour": 17.0, "minute": 0.0], "repeats": false,
]
let monToFri: [Any] = [2.0, 3.0, 4.0, 5.0, 6.0]
check(DailySchedule.oneOffPinning(scheduleDict: oneOffDict, weekdays: monToFri, now: friday18, calendar: utc)
      == .init(start: 2, end: 2), "Friday 18:00 ⇒ pinned to Monday, start and end")
check(DailySchedule.oneOffPinning(scheduleDict: oneOffDict, weekdays: monToFri, now: date(2026, 10, 30, 10, 0), calendar: utc) == nil,
      "inside a selected occurrence ⇒ registered as given")
check(DailySchedule.oneOffPinning(scheduleDict: oneOffDict, weekdays: monToFri, now: date(2026, 10, 31, 10, 0), calendar: utc)
      == .init(start: 2, end: 2), "inside an unselected occurrence ⇒ pinned to Monday")
check(DailySchedule.oneOffPinning(scheduleDict: oneOffDict, weekdays: monToFri, now: date(2026, 10, 26, 8, 0), calendar: utc)
      == .init(start: 2, end: 2), "Monday 08:00 ⇒ pinned to today")
let nightOneOff: [String: Any] = [
    "intervalStart": ["hour": 22.0, "minute": 0.0], "intervalEnd": ["hour": 6.0, "minute": 0.0], "repeats": false,
]
check(DailySchedule.oneOffPinning(scheduleDict: nightOneOff, weekdays: [2.0], now: date(2026, 10, 27, 7, 0), calendar: utc)
      == .init(start: 2, end: 3), "a crossing occurrence ends on the next weekday (Mon 22:00 → Tue 06:00, never Mon → Mon = six days)")
check(DailySchedule.oneOffPinning(scheduleDict: nightOneOff, weekdays: [7.0], now: date(2026, 10, 27, 7, 0), calendar: utc)
      == .init(start: 7, end: 1), "Saturday night ends on Sunday (the weekday wraps)")
let allDayOneOff: [String: Any] = [
    "intervalStart": ["hour": 0.0, "minute": 0.0], "intervalEnd": ["hour": 0.0, "minute": 0.0], "repeats": false,
]
check(DailySchedule.oneOffPinning(scheduleDict: allDayOneOff, weekdays: [1.0], now: date(2026, 10, 27, 7, 0), calendar: utc)
      == .init(start: 1, end: 2), "a 24 h occurrence ends on the next weekday")
check(DailySchedule.oneOffPinning(scheduleDict: dailyDict, weekdays: monToFri, now: friday18, calendar: utc) == nil,
      "repeats defaults to true ⇒ registered as given (the monitor skips unselected occurrences)")
check(DailySchedule.oneOffPinning(scheduleDict: jsSchedule, weekdays: monToFri, now: friday18, calendar: utc) == nil,
      "repeats: true ⇒ as given")
check(DailySchedule.oneOffPinning(scheduleDict: oneOffDict, weekdays: nil, now: friday18, calendar: utc) == nil
      && DailySchedule.oneOffPinning(scheduleDict: oneOffDict, weekdays: [], now: friday18, calendar: utc) == nil
      && DailySchedule.oneOffPinning(scheduleDict: oneOffDict, weekdays: [0.0, 9.0], now: friday18, calendar: utc) == nil,
      "no (valid) weekdays ⇒ as given")
var weeklyOneOff = weekly
weeklyOneOff["repeats"] = false
check(DailySchedule.oneOffPinning(scheduleDict: weeklyOneOff, weekdays: [3.0], now: friday18, calendar: utc) == nil,
      "components carrying a weekday are already pinned ⇒ as given")
check(DailySchedule.oneOffPinning(scheduleDict: ["intervalStart": ["hour": 9.0], "repeats": false], weekdays: monToFri, now: friday18, calendar: utc) == nil,
      "an incomplete schedule ⇒ as given (buildSchedule rejects it)")
var storedOneOff = storedSchedule
storedOneOff["repeats"] = NSNumber(value: false)
check(DailySchedule.oneOffPinning(scheduleDict: storedOneOff, weekdays: [NSNumber(value: 2)], now: date(2026, 10, 27, 7, 0), calendar: utc)
      == .init(start: 2, end: 3), "a stored config (NSNumber repeats and weekdays) pins the same way — the rollback path")

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

section("ActivityRegistration — the 19-activity limit (limits.maxMonitoredActivities)")
// The system refuses the 21st DeviceActivity (`excessiveActivities`), so a
// 20th logical activity used to go through whenever no meter held its slot
// — and took the slot the unlock meter or a snooze then needed. The
// contract enforces 19 logical activities on both platforms.
check(ActivityRegistration.maxLogicalActivities == 19, "19: iOS caps DeviceActivity at 20 and the temporary-unlock meter holds one")
let nineteen = Set((1...19).map { "a\($0)" })
check(ActivityRegistration.exceedsLimit(registering: "a20", among: nineteen), "a 20th logical activity is refused")
check(!ActivityRegistration.exceedsLimit(registering: "a7", among: nineteen), "re-registering one of the 19 costs no slot")
check(!ActivityRegistration.exceedsLimit(registering: "a20", among: nineteen.subtracting(["a1"])), "18 others leave room")
check(!ActivityRegistration.exceedsLimit(registering: "a", among: []), "the first activity")
check(ActivityRegistration.exceedsLimit(registering: "a2", among: ["a1"], limit: 1), "the limit is a parameter")
// What the system's registrations amount to, in logical activities: every
// generation of a name is that name, a legacy variant is its parent, and
// the module's own meters are not activities.
configs = [
    "A": ["_generation": 3, "schedule": dailyDict],
    "C": ["_weekdayActivities": ["C_weekday_2"], "schedule": dailyDict],
    "C_weekday_2": ["_parentActivity": "C"],
]
check(ActivityRegistration.logicalNames(ofRegistered: [
    "A\(sep)3", "A\(sep)2", "B", "C_weekday_2", "expo_st_temporary_unlock", "expo_st_snooze_1",
], config: lookup) == ["A", "B", "C"], "generations collapse, a legacy variant folds into its parent, meters are left out")
check(ActivityRegistration.logicalNames(ofRegistered: [], config: lookup).isEmpty, "nothing registered")

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

// MARK: - Shield snooze (fix 1, spike S1)

section("BlockComposition.storeShields — a snooze lifts the pressed target in every store, nothing else")
// Instagram is in the manual selection and in schedule B; the Social category is shielded by A.
let snoozeManual = Source(kind: .manual, startedAt: 0, tokens: ["app:insta", "app:tiktok"])
let snoozeA = Source(kind: .schedule(activityName: "A"), startedAt: 2_000, tokens: ["cat:social", "web:reddit.com"])
let snoozeB = Source(kind: .schedule(activityName: "B"), startedAt: 3_000, tokens: ["app:insta"])
let snoozeSources = [snoozeManual, snoozeA, snoozeB]
let noSnooze = BlockComposition.storeShields(of: snoozeSources, snoozed: [])
check(noSnooze.mapValues(\.tokens) == BlockComposition.storeContents(of: snoozeSources) && noSnooze.values.allSatisfy { $0.excepted.isEmpty },
      "no snooze ⇒ the plain store contents, nothing excepted")
let instaSnoozed = BlockComposition.storeShields(of: snoozeSources, snoozed: ["app:insta"])
check(instaSnoozed[BlockStore.manual]?.tokens == ["app:tiktok"], "the app is taken out of the manual store; the other app stays")
check(instaSnoozed[BlockStore.activity("B")] == nil, "a store the snooze emptied is absent (B shielded only the app)")
check(instaSnoozed[BlockStore.activity("A")]?.tokens == ["cat:social", "web:reddit.com"], "a store that does not cover the app is unchanged…")
check(instaSnoozed[BlockStore.activity("A")]?.excepted == ["app:insta"],
      "…but excepts the snoozed app from its categories (the app may be in Social)")
check(instaSnoozed.values.allSatisfy { $0.excepted == ["app:insta"] }, "every remaining store carries the exception")
let categorySnoozed = BlockComposition.storeShields(of: snoozeSources, snoozed: ["cat:social"])
check(categorySnoozed[BlockStore.activity("A")]?.tokens == ["web:reddit.com"], "a category snooze lifts the category shield")
check(categorySnoozed[BlockStore.manual]?.tokens == ["app:insta", "app:tiktok"] && categorySnoozed[BlockStore.activity("B")]?.tokens == ["app:insta"],
      "an app shielded directly stays shielded under a category snooze")
let webSnoozed = BlockComposition.storeShields(of: snoozeSources, snoozed: ["web:reddit.com"])
check(webSnoozed[BlockStore.activity("A")]?.tokens == ["cat:social"], "a web-domain snooze lifts the domain")
let twoSnoozed = BlockComposition.storeShields(of: snoozeSources, snoozed: ["app:insta", "app:tiktok"])
check(twoSnoozed[BlockStore.manual] == nil && twoSnoozed[BlockStore.activity("B")] == nil && twoSnoozed.count == 1,
      "two concurrent snoozes subtract both; the stores they empty are absent")
check(BlockComposition.storeShields(of: snoozeSources, snoozed: ["app:unknown"]).mapValues(\.tokens) == noSnooze.mapValues(\.tokens),
      "a snoozed target nothing shields changes no store")
check(BlockComposition.storeShields(of: [Source](), snoozed: ["app:insta"]).isEmpty, "no sources ⇒ nothing, snoozes or not")
// Composition with the unlock suspension: the snooze is applied to whatever the suspension leaves.
let unlockedView = BlockComposition.sources(from: during, suspended: grant, resolve: resolve)
check(BlockComposition.storeShields(of: unlockedView, snoozed: ["app:games"]).isEmpty,
      "a snooze on the one app a schedule started during the unlock shields ⇒ its store is empty too")

// MARK: - Allowlist mode

section("BlockMode — persisted form")
check(BlockMode.stored("allowlist") == .allowlist && BlockMode.stored("blocklist") == .blocklist, "both modes read back")
check(BlockMode.stored(nil) == .blocklist && BlockMode.stored("whitelist") == .blocklist && BlockMode.stored(3) == .blocklist,
      "a missing or unknown value is the default (a record from a build without modes is a blocklist)")
check(BlockMode.allowlist.rawValue == "allowlist" && BlockMode.blocklist.rawValue == "blocklist", "raw values are the JS names")

section("BlockComposition.storeShields — an allowlist store shields every app except the allowed ones")
let allowManual = Source(kind: .manual, startedAt: 0, tokens: ["app:calendar", "app:contacts"], mode: .allowlist)
let allowOnly = BlockComposition.storeShields(of: [allowManual], snoozed: [])
check(allowOnly.count == 1 && allowOnly[BlockStore.manual]?.allowlist == true, "the manual store is an allowlist store")
check(allowOnly[BlockStore.manual]?.tokens.isEmpty == true, "it lists no shielded token of its own…")
check(allowOnly[BlockStore.manual]?.excepted == ["app:calendar", "app:contacts"], "…and excepts exactly the allowed apps")
check(allowOnly[BlockStore.manual]?.isEmpty == false, "an allowlist store is never empty — it stays in the registry")
check(StoreShields<String>(tokens: []).isEmpty && !StoreShields<String>(tokens: ["app:x"]).isEmpty, "a blocklist store is empty without tokens")
check(BlockComposition.storeContents(of: [allowManual]).isEmpty, "storeContents (the blocklist view) shows nothing for it")
let allowNothing = Source(kind: .manual, startedAt: 0, tokens: [], mode: .allowlist)
check(BlockComposition.storeShields(of: [allowNothing], snoozed: [])[BlockStore.manual] == StoreShields(tokens: [], excepted: [], allowlist: true),
      "an empty allowlist shields every app (\"allow nothing\") — it is not omitted like an empty blocklist")
let leakedCategory = Source(kind: .manual, startedAt: 0, tokens: ["app:calendar", "cat:social", "web:x.com"], mode: .allowlist)
check(BlockComposition.storeShields(of: [leakedCategory], snoozed: [])[BlockStore.manual]?.excepted == ["app:calendar"],
      "categories and web domains in an allowlist allow nothing (fail closed)")

section("BlockComposition.storeShields — union with blocklist sources: shielded if ANY source shields it")
// A schedule allowlist with a blockOnThreshold blocklist inside the same activity (one store).
let allowA = Source(kind: .schedule(activityName: "A"), startedAt: 1_000, tokens: ["app:calendar", "app:mail", "app:slack"], mode: .allowlist)
let thresholdInA = Source(kind: .threshold(activityName: "A"), startedAt: 1_000, tokens: ["app:slack", "cat:games", "web:reddit.com"])
let mixed = BlockComposition.storeShields(of: [allowA, thresholdInA], snoozed: [])[BlockStore.activity("A")]
check(mixed?.allowlist == true, "the activity's store stays an allowlist store")
check(mixed?.excepted == ["app:calendar", "app:mail"], "an allowed app a blocklist shields directly is NOT excepted — the blocklist wins")
check(mixed?.tokens == ["app:slack", "web:reddit.com"], "the blocklist's app and web domain are listed; its category is dropped (`.all` covers it)")
// Across stores (manual allowlist + a schedule blocklist) each store is projected on its own; iOS merges them.
let scheduleBlock = Source(kind: .schedule(activityName: "B"), startedAt: 2_000, tokens: ["app:calendar", "cat:social"])
let acrossStores = BlockComposition.storeShields(of: [allowManual, scheduleBlock], snoozed: [])
check(acrossStores[BlockStore.manual] == StoreShields(tokens: [], excepted: ["app:calendar", "app:contacts"], allowlist: true),
      "the manual allowlist store is unchanged by another store's blocklist")
check(acrossStores[BlockStore.activity("B")] == StoreShields(tokens: ["app:calendar", "cat:social"], excepted: [], allowlist: false),
      "the schedule's blocklist store shields Calendar on its own — the union is the system's merge of the two stores")
// Two allowlists in one store intersect: an app is unshielded only if every allowlist allows it.
let allowA2 = Source(kind: .schedule(activityName: "A"), startedAt: 1_000, tokens: ["app:mail", "app:safari"], mode: .allowlist)
check(BlockComposition.storeShields(of: [allowA, allowA2], snoozed: [])[BlockStore.activity("A")]?.excepted == ["app:mail"],
      "two allowlists ⇒ the intersection of the allowed sets")
let allowAndBlockApart = BlockComposition.storeShields(of: [allowManual, scheduleA], snoozed: [])
check(allowAndBlockApart.count == 2 && allowAndBlockApart[BlockStore.activity("A")]?.allowlist == false,
      "an allowlist in one store does not turn another store into one")

section("BlockComposition.storeShields — snoozes under an allowlist")
let snoozedUnderAllow = BlockComposition.storeShields(of: [allowManual], snoozed: ["app:music"])[BlockStore.manual]
check(snoozedUnderAllow?.excepted == ["app:calendar", "app:contacts", "app:music"],
      "a snoozed app joins the except list — the only way to lift one app from `.all`")
check(snoozedUnderAllow?.allowlist == true && snoozedUnderAllow?.tokens.isEmpty == true, "the store stays an allowlist store")
let webSnoozedUnderAllow = BlockComposition.storeShields(of: [allowManual], snoozed: ["web:docs.example.com"])[BlockStore.manual]
check(webSnoozedUnderAllow?.excepted == ["app:calendar", "app:contacts", "web:docs.example.com"],
      "a snoozed web domain is excepted from the web-wide policy")
let catSnoozedUnderAllow = BlockComposition.storeShields(of: [allowManual], snoozed: ["cat:social"])[BlockStore.manual]
check(catSnoozedUnderAllow == allowOnly[BlockStore.manual], "a snoozed category changes nothing under `.all` (no \"except this category\")")
let mixedSnoozed = BlockComposition.storeShields(of: [allowA, thresholdInA], snoozed: ["app:slack"])[BlockStore.activity("A")]
check(mixedSnoozed?.tokens == ["web:reddit.com"] && mixedSnoozed?.excepted == ["app:calendar", "app:mail", "app:slack"],
      "a snooze on an app the blocklist shields inside an allowlist store lifts it from both: out of the list, into the exceptions")
check(BlockComposition.storeShields(of: [allowManual, scheduleBlock], snoozed: ["app:calendar"])[BlockStore.activity("B")]?.tokens == ["cat:social"],
      "a snooze still subtracts from the blocklist stores as before")
check(BlockComposition.storeShields(of: [snoozeManual, snoozeA, snoozeB], snoozed: ["cat:social"]).values.allSatisfy { $0.excepted.isEmpty },
      "a snoozed category is never an exception (the except lists take apps and web domains)")

section("BlockSource.covers / BlockComposition.owner — an allowlist covers what it does not allow")
check(!allowManual.covers(anyOf: ["app:calendar"]), "an allowed app is not covered")
check(allowManual.covers(anyOf: ["app:music"]), "any other app is")
check(allowManual.covers(anyOf: ["app:music", "cat:music"]) && allowManual.covers(anyOf: ["cat:music"]),
      "the configuration's (app, category) pair and the action's bare category are covered")
check(!allowManual.covers(anyOf: ["app:calendar", "cat:social"]),
      "an ALLOWED app shielded through a category is not covered — the app decides, not the category (review finding 1)")
// Reproduced: an allowlist schedule allowing Calendar, a manual blocklist on Social (Calendar ∈ Social).
// The configuration for (Calendar, Social) used to resolve the schedule, which does not shield Calendar.
let allowSchedule = Source(kind: .schedule(activityName: "focus"), startedAt: 5_000, tokens: ["app:calendar"], mode: .allowlist)
let manualSocial = Source(kind: .manual, startedAt: 0, tokens: ["cat:social"])
let calendarInSocial = BlockComposition.owner(ofAnyOf: ["app:calendar", "cat:social"], in: [allowSchedule, manualSocial])
check(calendarInSocial?.kind == .manual, "an allowed app in a category a lower-precedence blocklist shields is owned by that blocklist")
check(BlockComposition.owner(ofAnyOf: ["app:calendar", "cat:social"], in: [allowSchedule]) == nil,
      "…and by nothing when only the allowlist is active")
check(BlockComposition.handoffTargets(of: calendarInSocial, primary: "app:calendar", category: "cat:social") == ["app:calendar", "cat:social"],
      "the blocklist shielding through the category is handed off under both tokens, as before")
check(BlockComposition.owner(ofAnyOf: ["cat:social"], in: [allowSchedule, manualSocial], preferring: .manual, coveringTargets: true)?.kind == .manual,
      "Calendar's action in the category context then reuses the manual block, which can lift the category")
check(BlockComposition.owner(ofAnyOf: ["app:music", "cat:social"], in: [allowSchedule, manualSocial])?.kind == .schedule(activityName: "focus"),
      "an app the allowlist shields is still owned by the allowlist (precedence over the manual block)")
check(allowManual.covers(anyOf: ["web:reddit.com"]), "a web domain is covered (the web is shielded whole)")
check(!allowManual.covers(anyOf: []), "no target ⇒ not covered")
check(!allowNothing.covers(anyOf: []) && allowNothing.covers(anyOf: ["app:any"]), "an empty allowlist covers every app")
check(manual.covers(anyOf: ["app:insta"]) && !manual.covers(anyOf: ["app:music"]), "a blocklist covers what it lists, as before")
check(BlockComposition.owner(ofAnyOf: ["app:music"], in: [allowManual])?.kind == .manual, "the allowlist owns the shield of an app it does not allow")
check(BlockComposition.owner(ofAnyOf: ["app:calendar"], in: [allowManual]) == nil, "…and nothing owns an allowed app (nothing shields it)")
check(BlockComposition.owner(ofAnyOf: ["app:calendar"], in: [allowManual, scheduleBlock])?.kind == .schedule(activityName: "B"),
      "an app allowed by the manual allowlist but shielded by a schedule is owned by the schedule")
check(BlockComposition.owner(ofAnyOf: ["app:music"], in: [allowManual, scheduleBlock])?.kind == .manual,
      "an app only the allowlist shields is owned by the allowlist, whatever else is active")
check(BlockComposition.owner(ofAnyOf: ["app:slack", "cat:chat"], in: [allowA, thresholdInA])?.kind == .threshold(activityName: "A"),
      "threshold > allowlist schedule for an app both shield")
check(BlockComposition.owner(ofAnyOf: ["app:music"], in: [allowManual, allowA])?.kind == .schedule(activityName: "A"),
      "two allowlists covering an app: the schedule wins the configuration, as among blocklists")
check(BlockComposition.owner(ofAnyOf: ["app:music"], in: [allowManual, scheduleBlock], preferring: .manual)?.kind == .manual
      && BlockComposition.owner(ofAnyOf: ["cat:music"], in: [allowManual, scheduleBlock], preferring: .manual, coveringTargets: true)?.kind == .manual,
      "a persisted allowlist owner is reused by the action, in the application and the category context")
check(BlockComposition.owner(ofAnyOf: ["app:calendar"], in: [allowManual, scheduleBlock], preferring: .manual)?.kind == .manual,
      "a persisted owner is reused without a coverage check in the application context, as before")
check(BlockComposition.owner(ofAnyOf: ["cat:social"], in: [scheduleBlock], preferring: .manual, coveringTargets: true)?.kind == .schedule(activityName: "B"),
      "a persisted owner that ended falls back to the action's own resolution")
check(BlockComposition.handoffTargets(of: allowManual, primary: "app:music", category: "cat:music") == ["app:music", "cat:music"],
      "an allowlist owner is handed off under the app AND the category (it shields through every category)")
check(BlockComposition.handoffTargets(of: scheduleBlock, primary: "app:calendar", category: "cat:music") == ["app:calendar"],
      "a blocklist owner that covers the app directly is still handed off under the app only")

section("BlockComposition.union / containsAllowlist — what a temporary unlock may meter")
check(BlockComposition.union(of: [allowManual, scheduleBlock]) == ["app:calendar", "cat:social"],
      "the union holds the blocklist tokens only — an allowlist's shielded set cannot be named")
check(BlockComposition.containsAllowlist([allowManual, scheduleBlock]) && !BlockComposition.containsAllowlist([manual, scheduleA]),
      "containsAllowlist flags the sources an unlock must refuse")
check(BlockComposition.union(of: [allowManual]).isEmpty && BlockComposition.containsAllowlist([allowManual]),
      "an allowlist alone: empty union but not NO_ACTIVE_BLOCK — UNSUPPORTED")

section("BlockComposition.sources — modes come from the records")
let allowSelections: [String: Set<String>] = [
    "apps": ["app:calendar", "app:contacts"],
    "mixed": ["app:calendar", "cat:social", "web:x.com"],
    "empty": [],
]
let resolveAllow = { (id: String) -> Set<String>? in allowSelections[id] }
let manualAllowRecords = BlockRecords(manualSelectionId: "apps", manualMode: .allowlist)
let manualAllowSources = BlockComposition.sources(from: manualAllowRecords, suspended: nil, resolve: resolveAllow)
check(manualAllowSources == [Source(kind: .manual, startedAt: 0, tokens: ["app:calendar", "app:contacts"], mode: .allowlist)],
      "the manual block is an allowlist source when its persisted mode says so")
check(BlockComposition.sources(from: BlockRecords(manualSelectionId: "apps"), suspended: nil, resolve: resolveAllow).first?.mode == .blocklist,
      "without a mode it is a blocklist (records from before this build)")
check(BlockComposition.sources(from: BlockRecords(manualSelectionId: "empty", manualMode: .allowlist), suspended: nil, resolve: resolveAllow)
      == [Source(kind: .manual, startedAt: 0, tokens: [], mode: .allowlist)],
      "an empty allowlist selection is a source (allow nothing)…")
check(BlockComposition.sources(from: BlockRecords(manualSelectionId: "empty"), suspended: nil, resolve: resolveAllow).isEmpty,
      "…while an empty blocklist selection is omitted, as before")
check(BlockComposition.sources(from: BlockRecords(manualSelectionId: "gone", manualMode: .allowlist), suspended: nil, resolve: resolveAllow).isEmpty,
      "a deleted selection shields nothing in either mode (no source)")
check(BlockComposition.sources(from: BlockRecords(manualSelectionId: "mixed", manualMode: .allowlist), suspended: nil, resolve: resolveAllow).first?.tokens
      == ["app:calendar"], "a selection edited to hold a category or a domain after an allowlist block allows its apps only")
var scheduleAllowRecords = BlockRecords()
scheduleAllowRecords.intervals["A"] = ActiveInterval(startedAt: 1_000, lifted: false)
scheduleAllowRecords.scheduleSelectionIds["A"] = "apps"
scheduleAllowRecords.scheduleModes["A"] = .allowlist
scheduleAllowRecords.thresholdSelectionIds["A"] = ["mixed"]
let scheduleAllowSources = BlockComposition.sources(from: scheduleAllowRecords, suspended: nil, resolve: resolveAllow)
check(scheduleAllowSources.map(\.mode) == [.allowlist, .blocklist] && scheduleAllowSources.map(\.kind) == [.schedule(activityName: "A"), .threshold(activityName: "A")],
      "a schedule takes its mode from its config; its blockOnThreshold shields keep blocklist semantics")
check(scheduleAllowSources.last?.tokens == ["app:calendar", "cat:social", "web:x.com"], "the threshold shields the event's whole selection")
let allowGrant = SuspendedSources.capturing(manualAllowRecords)
check(allowGrant.manual && BlockComposition.sources(from: manualAllowRecords, suspended: allowGrant, resolve: resolveAllow).isEmpty,
      "a suspension captured over an allowlist suspends it like any source (the unlock itself is refused upstream)")
var liftedAllow = scheduleAllowRecords
liftedAllow.intervals["A"] = ActiveInterval(startedAt: 1_000, lifted: true)
check(BlockComposition.sources(from: liftedAllow, suspended: nil, resolve: resolveAllow).map(\.kind) == [.threshold(activityName: "A")],
      "unblockAll lifts an allowlist schedule's interval like a blocklist's")

section("UsageMeter — the ladder the unlock and the snoozes share")
check(UsageMeter.budget(for: 5) == 30 && UsageMeter.budget(for: 30) == 30 && UsageMeter.budget(for: 31) == 31,
      "budgets are floored at one step (30 s)")
check(UsageMeter.thresholds(budget: 30) == [30], "a 30 s budget is one step")
check(UsageMeter.thresholds(budget: 100) == [30, 60, 90, 100], "steps every 30 s, then the budget")
check(UsageMeter.thresholds(budget: 5) == [30], "a budget below the floor meters the floor")
let longLadder = UsageMeter.thresholds(budget: 7_200)
check(longLadder.count <= UsageMeter.maxSteps && longLadder.last == 7_200 && longLadder.first == 120,
      "a 2 h budget coarsens to 120 s steps (\(longLadder.count) events)")
check(UsageMeter.thresholds(budget: 1_800) == Array(stride(from: 30, through: 1_800, by: 30)), "30 min: 60 steps of 30 s")
check(UsageMeter.step(forBudget: 5) == 30 && UsageMeter.step(forBudget: 300) == 30 && UsageMeter.step(forBudget: 1_800) == 30,
      "meteringSeconds: 30 s up to a 30 min budget (the floor included)")
check(UsageMeter.step(forBudget: 1_801) == 31 && UsageMeter.step(forBudget: 7_200) == 120 && UsageMeter.step(forBudget: 3 * 3_600) == 180,
      "…then ceil(budget / 60): 120 s for 2 h, 180 s for 3 h")
check([5, 30, 100, 1_800, 1_801, 7_200, 86_399].allSatisfy { budget in
    let step = UsageMeter.step(forBudget: budget)
    let ladder = UsageMeter.thresholds(budget: budget)
    return ladder.dropLast().allSatisfy { $0 % step == 0 } && ladder.count <= UsageMeter.maxSteps
        && zip(ladder, ladder.dropFirst()).allSatisfy { $1 - $0 <= step }
}, "the ladder moves in meteringSeconds steps (the last one at most a step)")
check(!UsageMeter.isPlausible(step: 30, elapsedSeconds: 0.05) && UsageMeter.isPlausible(step: 30, elapsedSeconds: 26),
      "an immediate fire of the FIRST step (50 ms after the grant) is impossible; 5 s of slack for jitter")
check(UsageMeter.isPlausible(step: 300, elapsedSeconds: 296) && !UsageMeter.isPlausible(step: 300, elapsedSeconds: 290),
      "the slack is 5 s, well under a step")
check(UsageMeter.slackSeconds < UsageMeter.stepSeconds, "the slack can never let a whole step through")
check(UsageMeter.meterEnd(now: date(2026, 11, 1, 10, 0), calendar: utc) == date(2026, 11, 1, 23, 59, 59),
      "a meter armed during the day ends at 23:59:59")
check(UsageMeter.meterEnd(now: date(2026, 11, 1, 23, 43, 59), calendar: utc) == date(2026, 11, 1, 23, 59, 59),
      "…down to 16 minutes before midnight")
check(UsageMeter.meterEnd(now: date(2026, 11, 1, 23, 50), calendar: utc) == date(2026, 11, 2, 0, 6),
      "armed later, it runs the system's 15-minute minimum (plus a minute) into the next day instead of being refused")
check(UsageMeter.meterEnd(now: date(2026, 11, 1, 23, 50, 0, paris), calendar: paris) == date(2026, 11, 2, 0, 6, 0, paris),
      "in the device's calendar")
check(UsageMeter.seconds(300.0) == 300 && UsageMeter.seconds(NSNumber(value: 45)) == 45 && UsageMeter.seconds(12) == 12,
      "durations arrive as Double, NSNumber or Int")
check(UsageMeter.seconds("300") == nil && UsageMeter.seconds(nil) == nil && UsageMeter.seconds(Double.infinity) == nil,
      "anything else is not a duration")

section("BlockComposition.targetedUnlockVerdict — what temporaryUnlock(…, { target }) may lift")
let verdictManual = Source(kind: .manual, startedAt: 0, tokens: ["app:insta", "cat:social", "web:reddit.com"])
let verdictAllow = Source(kind: .schedule(activityName: "F"), startedAt: 1, tokens: ["app:mail"], mode: .allowlist)
check(BlockComposition.targetedUnlockVerdict(for: "app:insta", in: [verdictManual]) == .liftable
      && BlockComposition.targetedUnlockVerdict(for: "cat:social", in: [verdictManual]) == .liftable
      && BlockComposition.targetedUnlockVerdict(for: "web:reddit.com", in: [verdictManual]) == .liftable,
      "an app, a category or a web domain a blocklist lists is liftable")
check(BlockComposition.targetedUnlockVerdict(for: "app:tiktok", in: [verdictManual]) == .notShielded
      && BlockComposition.targetedUnlockVerdict(for: "app:insta", in: [Source]()) == .notShielded,
      "a token no source lists is not shielded (an app inside a listed category included: tokens are opaque)")
check(BlockComposition.targetedUnlockVerdict(for: "app:insta", in: [verdictAllow]) == .liftable
      && BlockComposition.targetedUnlockVerdict(for: "web:reddit.com", in: [verdictAllow]) == .liftable,
      "an app or a web domain an allowlist shields is liftable (the projection excepts it)")
check(BlockComposition.targetedUnlockVerdict(for: "app:mail", in: [verdictAllow]) == .notShielded,
      "an app the allowlist allows is not shielded")
check(BlockComposition.targetedUnlockVerdict(for: "cat:social", in: [verdictAllow]) == .categoryUnderAllowlist,
      "a category cannot be lifted while an allowlist is in force")
check(BlockComposition.targetedUnlockVerdict(for: "cat:social", in: [verdictAllow, verdictManual]) == .categoryUnderAllowlist,
      "…even one a blocklist lists: the allowlist's store keeps shielding its apps")
check(BlockComposition.storeShields(of: [verdictAllow, verdictManual], snoozed: ["cat:social"])[BlockStore.activity("F")] == StoreShields(tokens: [], excepted: ["app:mail"], allowlist: true),
      "(the projection confirms it: a snoozed category excepts nothing from `.all`)")
check(BlockComposition.storeShields(of: [verdictAllow, verdictManual], snoozed: ["app:insta"]).values.allSatisfy { $0.excepted.contains("app:insta") }
      && BlockComposition.storeShields(of: [verdictAllow, verdictManual], snoozed: ["app:insta"])[BlockStore.manual]?.tokens == ["cat:social", "web:reddit.com"],
      "and the projection does lift a liftable app from every store, the allowlist's included")

section("ShieldSnooze — naming and persistence")
let insta = ShieldSnooze(id: "ID-1", tokenKind: "application", token: "QUJD", budgetSeconds: 300, grantedAt: ms(date(2026, 11, 1, 10, 0)))
check(insta.activityName == "expo_st_snooze_ID-1" && ShieldSnooze.isActivityName(insta.activityName),
      "the meter is a reserved activity: \(insta.activityName)")
check(ActivityRegistration.isInternalName(insta.activityName) && !ActivityRegistration.isValidLogicalName(insta.activityName),
      "…hidden from getMonitoredActivities and unreachable through stopMonitoring")
check(!ShieldSnooze.isActivityName("expo_st_temporary_unlock") && !ShieldSnooze.isActivityName("snooze"), "other names are not snooze meters")
check(ShieldSnooze.stepEventName(90) == "expo_st_snooze_step_90" && ShieldSnooze.stepSeconds(ofEvent: "expo_st_snooze_step_90") == 90,
      "step events carry their threshold")
check(ShieldSnooze.stepSeconds(ofEvent: "expo_st_snooze_step_0") == nil && ShieldSnooze.stepSeconds(ofEvent: "expo_st_unlock_step_30") == nil
      && ShieldSnooze.stepSeconds(ofEvent: "expo_st_snooze_step_x") == nil, "a zero, foreign or malformed step is not a step")
check(insta.remainingSeconds == 300, "nothing consumed yet")
var instaPlist = insta.dictionary
check(ShieldSnooze(dictionary: instaPlist) == insta, "dictionary round trip")
instaPlist["budgetSeconds"] = NSNumber(value: 300)
instaPlist["grantedAt"] = NSNumber(value: insta.grantedAt)
instaPlist["consumedSeconds"] = NSNumber(value: 60)
check(ShieldSnooze(dictionary: instaPlist)?.consumedSeconds == 60 && ShieldSnooze(dictionary: instaPlist)?.grantedAt == insta.grantedAt,
      "NSNumber fields are read back")
check(ShieldSnooze(dictionary: ["id": "x"]) == nil && ShieldSnooze(dictionary: ["id": "", "tokenKind": "application", "token": "QUJD", "budgetSeconds": 30, "grantedAt": 1.0]) == nil
      && ShieldSnooze(dictionary: ["id": "x", "tokenKind": "application", "token": "QUJD", "budgetSeconds": 0, "grantedAt": 1.0]) == nil,
      "malformed records are dropped")
let tiktok = ShieldSnooze(id: "ID-2", tokenKind: "application", token: "VElL", budgetSeconds: 60, grantedAt: ms(date(2026, 11, 1, 10, 5)))
check(insta.covers(sameTargetAs: ShieldSnooze(id: "ID-9", tokenKind: "application", token: "QUJD", budgetSeconds: 1, grantedAt: 0))
      && !insta.covers(sameTargetAs: tiktok)
      && !insta.covers(sameTargetAs: ShieldSnooze(id: "ID-9", tokenKind: "category", token: "QUJD", budgetSeconds: 1, grantedAt: 0)),
      "the same target is the same kind and token, whatever the id")

section("ShieldSnoozes — today only")
let yesterday = ShieldSnooze(id: "ID-0", tokenKind: "webDomain", token: "UkVE", budgetSeconds: 600, grantedAt: ms(date(2026, 10, 31, 23, 50)))
check(ShieldSnoozes.current([yesterday, insta, tiktok], now: date(2026, 11, 1, 12, 0), calendar: utc) == [insta, tiktok],
      "a snooze granted on another calendar day is void, even if its meter never reported the day's end")
check(ShieldSnoozes.current([yesterday], now: date(2026, 10, 31, 23, 59), calendar: utc) == [yesterday], "…and in force on its own day")
check(ShieldSnoozes.current([insta], now: date(2026, 11, 1, 23, 30, 0, paris), calendar: paris) == [insta],
      "the day is the device's calendar day")

section("ShieldSnoozes.granting — concurrent snoozes; the same target restarts")
let one = ShieldSnoozes.granting(insta, in: [])
check(one.snoozes == [insta] && one.replaced.isEmpty, "the first snooze")
let two = ShieldSnoozes.granting(tiktok, in: one.snoozes)
check(two.snoozes == [insta, tiktok] && two.replaced.isEmpty, "a snooze on another target runs alongside: the first keeps its budget")
let instaAgain = ShieldSnooze(id: "ID-3", tokenKind: "application", token: "QUJD", budgetSeconds: 120, grantedAt: ms(date(2026, 11, 1, 10, 9)))
let again = ShieldSnoozes.granting(instaAgain, in: two.snoozes)
check(again.snoozes == [tiktok, instaAgain] && again.replaced == [insta],
      "the same target snoozed again gets a fresh budget and id; the old snooze is returned for its meter to be released")

section("ShieldSnoozes.recording — the meter's steps")
let t0 = date(2026, 11, 1, 10, 0)
let list = [insta, tiktok]
let staleStep = ShieldSnoozes.recording(event: "expo_st_snooze_step_30", ofActivity: "expo_st_snooze_GONE", in: list, now: t0.addingTimeInterval(40))
check(staleStep.outcome == .stale && staleStep.snoozes == list, "a step of a meter without a snooze (spent, replaced, ended) is ignored")
let early = ShieldSnoozes.recording(event: "expo_st_snooze_step_300", ofActivity: insta.activityName, in: list, now: t0.addingTimeInterval(5))
check(early.outcome == .implausible && early.snoozes == list,
      "an immediate fire of the final step (5 s after the grant) is ignored — the budget is not spent instantly")
let immediate = ShieldSnoozes.recording(event: "expo_st_snooze_step_30", ofActivity: insta.activityName, in: list, now: t0.addingTimeInterval(0.05))
check(immediate.outcome == .implausible && immediate.snoozes == list,
      "an immediate fire of the first step (the app was in use just before the press) is ignored — a 30 s snooze is not spent at once")
let step30 = ShieldSnoozes.recording(event: "expo_st_snooze_step_30", ofActivity: insta.activityName, in: list, now: t0.addingTimeInterval(45))
check(step30.outcome == .consumed && step30.snoozes[0].consumedSeconds == 30 && step30.snoozes[1] == tiktok,
      "a plausible step is consumed; the other snooze is untouched")
let step90 = ShieldSnoozes.recording(event: "expo_st_snooze_step_90", ofActivity: insta.activityName, in: step30.snoozes, now: t0.addingTimeInterval(100))
let step60Late = ShieldSnoozes.recording(event: "expo_st_snooze_step_60", ofActivity: insta.activityName, in: step90.snoozes, now: t0.addingTimeInterval(101))
check(step90.snoozes[0].consumedSeconds == 90 && step60Late.outcome == .consumed && step60Late.snoozes[0].consumedSeconds == 90,
      "steps out of order never move consumption backwards")
check(step60Late.snoozes[0].remainingSeconds == 210, "remaining = budget − consumed")
let spent = ShieldSnoozes.recording(event: "expo_st_snooze_step_300", ofActivity: insta.activityName, in: step90.snoozes, now: t0.addingTimeInterval(400))
check(spent.outcome == .spent && spent.snoozes == [tiktok], "the final step spends the snooze: it is gone, the other stays")
let overshoot = ShieldSnoozes.recording(event: "expo_st_snooze_step_120", ofActivity: tiktok.activityName, in: spent.snoozes, now: t0.addingTimeInterval(900))
check(overshoot.outcome == .spent && overshoot.snoozes.isEmpty, "a step past the budget spends it too")
let malformed = ShieldSnoozes.recording(event: "expo_st_unlock_step_30", ofActivity: insta.activityName, in: list, now: t0.addingTimeInterval(40))
check(malformed.outcome == .malformed && malformed.snoozes == [tiktok], "an event that is not a step on a live snooze ends it — fail closed")

section("ShieldSnoozes.ending — the day's end, or a stop")
let dayEnd = ShieldSnoozes.ending(activityName: insta.activityName, in: list)
check(dayEnd.ended && dayEnd.snoozes == [tiktok], "the meter's interval end forgets that snooze only")
let alreadyGone = ShieldSnoozes.ending(activityName: insta.activityName, in: [tiktok])
check(!alreadyGone.ended && alreadyGone.snoozes == [tiktok], "the end of a meter the app already stopped (snooze taken first) does nothing")

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

// MARK: - Enforcement gaps (authorization revoked)

section("AuthorizationGapTracker — authorization revocations")
var tracker = AuthorizationGapTracker()
tracker.observe(approved: false, at: 1_000)
check(tracker.gaps.isEmpty, "never having had access is not a gap")
tracker.observe(approved: true, at: 2_000)
tracker.observe(approved: true, at: 3_000)
check(tracker.lastApprovedAt == 3_000 && tracker.gaps.isEmpty, "access held: no gap, last grant moves forward")
tracker.observe(approved: false, at: 5_000)
check(tracker.gaps == [EnforcementGap(from: 3_000, to: nil, reason: "authorizationRevoked")], "a revocation opens a gap at the last observed grant")
tracker.observe(approved: false, at: 6_000)
check(tracker.gaps.count == 1, "further observations without access keep the one open gap")
check(tracker.gaps(since: 9_000).count == 1, "an open gap is in progress at any later time")
tracker.observe(approved: true, at: 7_000)
check(tracker.gaps == [EnforcementGap(from: 3_000, to: 7_000, reason: "authorizationRevoked")], "access back closes the gap at that observation")
check(tracker.gaps(since: 7_000).count == 1 && tracker.gaps(since: 7_001).isEmpty, "since keeps the gaps ending at or after it")
check(tracker.gaps(since: nil).count == 1, "no since returns every gap")
tracker.observe(approved: false, at: 8_000)
check(tracker.gaps.count == 2 && tracker.gaps[1].from == 7_000 && tracker.hasOpenGap, "a second revocation opens a second gap")
var capped = AuthorizationGapTracker()
for i in 0..<(AuthorizationGapTracker.maxGaps + 5) {
    capped.observe(approved: true, at: Double(i * 10))
    capped.observe(approved: false, at: Double(i * 10 + 5))
}
check(capped.gaps.count == AuthorizationGapTracker.maxGaps && capped.gaps.first?.from == 50, "past the cap the oldest gaps are dropped")
let gapTrackerRoundTrip = try? JSONDecoder().decode(AuthorizationGapTracker.self, from: JSONEncoder().encode(tracker))
check(gapTrackerRoundTrip == tracker, "the tracker survives a JSON round trip (persisted in UserDefaults)")
let openGapPayload = EnforcementGap(from: 1, to: nil, reason: "authorizationRevoked").dictionary
check(openGapPayload["to"] is NSNull && (openGapPayload["from"] as? Double) == 1, "an open gap reaches JS with to: null")
// MARK: - Shield interactions (review R16) — the per-target collapse and the legacy queue

section("ShieldInteractionQueue.State.record — presses on one target within 2 s are one attempt; other targets are not")
typealias Target = ShieldInteractionQueue.Target
typealias Press = ShieldInteractionQueue.Entry
let instaApp = Target(type: "application", handle: "QUJD")
let tiktokApp = Target(type: "application", handle: "REVG")
let socialCategory = Target(type: "category", handle: "QUJD")  // same handle bytes as instaApp, another kind
var presses = ShieldInteractionQueue.State()
check(presses.record(Press(button: "primary", timestamp: 1_000, target: instaApp, activityName: "work")), "the first press on a target is recorded")
check(!presses.record(Press(button: "secondary", timestamp: 2_500, target: instaApp, activityName: "work")),
      "a second button on the same shield 1.5 s later is the same attempt")
check(presses.record(Press(button: "primary", timestamp: 2_500, target: tiktokApp)), "a press on ANOTHER app at the same instant is its own attempt")
check(presses.record(Press(button: "primary", timestamp: 2_600, target: socialCategory)),
      "a category shield is another target than an app with the same handle bytes")
check(!presses.record(Press(button: "primary", timestamp: 2_999, target: instaApp)), "1.999 s after the first press: still collapsed")
check(presses.record(Press(button: "primary", timestamp: 3_000, target: instaApp)), "2 s after it: a new attempt (the window is open at 2 s)")
check(presses.entries.map(\.timestamp) == [1_000, 2_500, 2_600, 3_000], "four attempts, oldest first")
check(presses.entries.first?.activityName == "work" && presses.entries[1].activityName == nil,
      "each entry keeps its own owner (a schedule here, the manual block there)")
check(presses.lastPressByTarget.count == 3, "the per-target record holds the targets pressed in the last 2 s")
check(presses.record(Press(button: "primary", timestamp: 10_000, target: tiktokApp)) && presses.lastPressByTarget.count == 1,
      "…and is pruned to the window on every press (bounded by distinct targets, not by history)")
check(presses.record(Press(button: "primary", timestamp: 9_000, target: tiktokApp)), "a press with an earlier clock than its record is kept, never collapsed")
check(presses.record(Press(button: "primary", timestamp: 6_500, target: instaApp)) && presses.lastPressByTarget.count == 1,
      "…and a record more than 2 s in the future (the clock jumped back) is dropped like a stale one")

section("ShieldInteractionQueue.State.record — targets without a handle, and the cap")
var handleless = ShieldInteractionQueue.State()
check(handleless.record(Press(button: "primary", timestamp: 0)) && !handleless.record(Press(button: "secondary", timestamp: 500)),
      "presses whose token has no handle collapse among themselves (today's global rule, for them only)")
check(handleless.record(Press(button: "primary", timestamp: 500, target: instaApp)), "…and never swallow a press on a known target")
var flood = ShieldInteractionQueue.State()
for index in 0..<(ShieldInteractionQueue.capacity + 5) {
    flood.record(Press(button: "primary", timestamp: Double(index) * 10, target: Target(type: "application", handle: "H\(index)")))
}
check(flood.entries.count == ShieldInteractionQueue.capacity && flood.entries.first?.target?.handle == "H5",
      "the queue keeps the newest \(ShieldInteractionQueue.capacity) presses while the app is not draining")

section("ShieldInteractionQueue.State.drain — the JS shape, and a collapse that spans a drain")
var drained = ShieldInteractionQueue.State()
drained.record(Press(button: "primary", timestamp: 1_000, target: instaApp, activityName: "work"))
drained.record(Press(button: "secondary", timestamp: 1_100, target: tiktokApp))
let interactions = drained.drain()
check(interactions.count == 2 && drained.entries.isEmpty, "a drain returns every entry and empties the queue")
check((interactions[0]["target"] as? [String: Any]).flatMap(Target.init(dictionary:)) == instaApp
      && interactions[0]["activityName"] as? String == "work"
      && interactions[0]["button"] as? String == "primary" && interactions[0]["timestamp"] as? Double == 1_000,
      "an entry drains as { button, timestamp, target: TokenHandle, activityName }")
check(interactions[1]["target"] is [String: Any] && interactions[1]["activityName"] is NSNull,
      "a manual block drains with activityName null (JS null, not undefined)")
check(!drained.record(Press(button: "primary", timestamp: 2_000, target: instaApp)), "a press 1 s after a drained press on the same target still collapses")
check(drained.drain().isEmpty, "an empty queue drains to nothing")

section("ShieldInteractionQueue — persistence, legacy entries, reserved names")
let persisted = Press(button: "primary", timestamp: 1_720_512_000_000, target: instaApp, activityName: "work")
check(Press(dictionary: persisted.dictionary) == persisted, "an entry round-trips through its property-list shape")
let roundTrip = ShieldInteractionQueue.entries(fromJSON: ShieldInteractionQueue.json(of: [persisted, Press(button: "secondary", timestamp: 1)]))
check(roundTrip == [persisted, Press(button: "secondary", timestamp: 1)], "…and through the JSON array the App Group stores")
check(ShieldInteractionQueue.json(of: []) == nil, "an empty queue is not stored")
let legacy = ShieldInteractionQueue.entries(fromJSON: #"[{"button":"primary","timestamp":1720512000000},{"button":"secondary","timestamp":1720512003000}]"#)
check(legacy.count == 2 && legacy.allSatisfy { $0.target == nil && $0.activityName == nil }, "the queue of a build without targets still decodes")
check(legacy[0].interaction["target"] is NSNull && legacy[0].interaction["activityName"] is NSNull && legacy[0].interaction["timestamp"] as? Double == 1_720_512_000_000,
      "…and drains with target and activityName null")
check(ShieldInteractionQueue.entries(fromJSON: #"[{"timestamp":1},{"button":"primary"},{"button":"primary","timestamp":2,"target":{"type":"application"}}]"#)
      == [Press(button: "primary", timestamp: 2)],
      "entries without a button or a timestamp are dropped; a target without a handle is no target")
check(ShieldInteractionQueue.entries(fromJSON: nil).isEmpty && ShieldInteractionQueue.entries(fromJSON: "not json").isEmpty, "no queue, or garbage, is an empty queue")
check(Press(button: "primary", timestamp: 1, activityName: "expo_st_snooze_X").activityName == nil
      && Press(dictionary: ["button": "primary", "timestamp": 1, "activityName": "expo_st_temporary_unlock"])?.activityName == nil
      && Press(button: "primary", timestamp: 1, activityName: "").activityName == nil,
      "a reserved (expo_st_) or empty activity name is never exposed")
check(Press(button: "primary", timestamp: 1, activityName: "work").activityName == "work", "a user's activity name is")

// MARK: - PermissionSteps

section("PermissionSteps — features and the steps they need")
typealias PermStep = PermissionSteps.Step
check(PermissionSteps.features(from: nil) == [.blocking, .monitoring], "no features option is the default: blocking and monitoring")
check(PermissionSteps.features(from: "blocking") == [.blocking, .monitoring], "a non-array features option is the default")
check(PermissionSteps.features(from: ["shieldOpenApp", "blocking", "shieldOpenApp", "teleport", 3]) == [.shieldOpenApp, .blocking],
      "features keep their order, drop duplicates, and ignore names this build does not know")
check(PermissionSteps.features(from: [String]()) == [], "an empty list is no feature (nothing beyond screenTime is needed)")
check(PermissionSteps.stepIds(for: [.blocking, .monitoring]) == [.screenTime], "the default features need the screenTime step alone")
check(PermissionSteps.stepIds(for: [.blocking, .shieldOpenApp]) == [.screenTime, .notifications], "shieldOpenApp adds the notifications step, after screenTime")
check(PermissionSteps.stepIds(for: []) == [.screenTime], "screenTime is required whatever the features")
check(PermissionSteps.requestedStep(from: nil) == .next && PermissionSteps.requestedStep(from: NSNull()) == .next, "no step option, or null: the next required step")
check(PermissionSteps.requestedStep(from: "notifications") == .step(.notifications), "an iOS step id is that step")
check(PermissionSteps.requestedStep(from: "usageAccess") == .notOnThisPlatform("usageAccess"), "an Android step id is told apart…")
check(PermissionSteps.requestedStep(from: "screentime") == .unknown("screentime") && PermissionSteps.requestedStep(from: 1) == .unknown("1"),
      "…from an id that is not a PermissionStepId")

section("PermissionSteps.response — granted, status and canAskAgain from the steps")
let stGranted = PermStep(id: .screenTime, status: .granted, required: true, canPrompt: true)
let stUndetermined = PermStep(id: .screenTime, status: .undetermined, required: true, canPrompt: true)
let stDenied = PermStep(id: .screenTime, status: .denied, required: true, error: .init(code: "authorizationCanceled", message: "canceled"), canPrompt: true)
let stRestricted = PermStep(id: .screenTime, status: .restricted, required: true, error: .init(code: "restricted", message: "restricted"), canPrompt: false)
let nGranted = PermStep(id: .notifications, status: .granted, required: true, canPrompt: true)
let nUndetermined = PermStep(id: .notifications, status: .undetermined, required: true, canPrompt: true)
let nDenied = PermStep(id: .notifications, status: .denied, required: true, canPrompt: false)
func permResponse(_ steps: [PermStep]) -> (status: String, granted: Bool, canAskAgain: Bool) {
    let response = PermissionSteps.response(steps: steps)
    return (response["status"] as? String ?? "?", response["granted"] as? Bool ?? false, response["canAskAgain"] as? Bool ?? false)
}
check(permResponse([stGranted]) == ("granted", true, true), "screenTime granted: granted")
check(permResponse([stUndetermined]) == ("undetermined", false, true), "screenTime undetermined: undetermined, can ask")
check(permResponse([stDenied]) == ("denied", false, true), "screenTime denied: denied, but Family Controls prompts again — can ask")
check(permResponse([stRestricted]) == ("denied", false, false), "screenTime restricted: denied, cannot ask")
check(permResponse([stGranted, nGranted]) == ("granted", true, true), "both steps granted: granted")
check(permResponse([stGranted, nUndetermined]) == ("undetermined", false, true), "screenTime granted, notifications not asked yet: undetermined (not granted), can ask")
check(permResponse([stGranted, nDenied]) == ("denied", false, false), "notifications denied: denied, and the prompt cannot be shown again — cannot ask")
check(permResponse([stDenied, nUndetermined]) == ("denied", false, true), "screenTime denied, notifications pending: denied, can ask")
check(permResponse([stRestricted, nGranted]) == ("denied", false, false), "a restricted required step: cannot ask whatever the others")
check(permResponse([stUndetermined, nDenied]) == ("denied", false, false), "a denied required step wins over an undetermined one")
let shieldOpenAppResponse = PermissionSteps.response(steps: [stGranted, nDenied])
check(shieldOpenAppResponse["expires"] as? String == "never" && (shieldOpenAppResponse["steps"] as? [[String: Any]])?.count == 2,
      "the response carries Expo's `expires: never` and every step")
let deniedDictionary = stDenied.dictionary
check(deniedDictionary["id"] as? String == "screenTime" && deniedDictionary["status"] as? String == "denied"
      && deniedDictionary["required"] as? Bool == true && deniedDictionary["kind"] as? String == "prompt"
      && (deniedDictionary["error"] as? [String: String]) == ["code": "authorizationCanceled", "message": "canceled"],
      "a step is { id, status, required, kind: prompt, error }")
check(nGranted.dictionary["error"] == nil && nGranted.dictionary["id"] as? String == "notifications", "…with no error key when nothing failed")

section("PermissionSteps.nextStep — the one step a request advances")
check(PermissionSteps.nextStep(in: [stUndetermined, nUndetermined]) == .screenTime, "screenTime comes first")
check(PermissionSteps.nextStep(in: [stGranted, nUndetermined]) == .notifications, "then notifications")
check(PermissionSteps.nextStep(in: [stGranted, nDenied]) == .notifications, "a denied step is still the next one (the response says it cannot prompt)")
check(PermissionSteps.nextStep(in: [stGranted, nGranted]) == nil && PermissionSteps.nextStep(in: [stGranted]) == nil, "nothing pending: no step to request")

// MARK: - Shield text colours (default colours over a custom background)

section("ShieldTextColors — the rule and its guarantees")
typealias RGBA = ShieldTextColors.RGBA
check(RGBA(hex: "#1a1a2e") == RGBA(red: 0x1A, green: 0x1A, blue: 0x2E, alpha: 255), "#RRGGBB parses")
check(RGBA(hex: " 1a1a2e80 ") == RGBA(red: 0x1A, green: 0x1A, blue: 0x2E, alpha: 0x80), "RRGGBBAA parses, # optional, whitespace trimmed")
check(RGBA(hex: "#12345") == nil && RGBA(hex: "blue") == nil && RGBA(hex: "") == nil && RGBA(hex: "#1a1a2g") == nil, "anything else is nil")
check(RGBA(hex: "#1a1a2e80")?.hex == "#1A1A2E80" && RGBA.white.hex == "#FFFFFF", "hex prints the fixture's spelling")
check(abs(ShieldTextColors.contrast(.white, .black) - 21) < 1e-9, "white on black is 21:1")
check(abs(ShieldTextColors.relativeLuminance(RGBA(hex: "#80808080")!) - 0.2159) < 5e-5, "#808080 has L = 0.2159 (WebAIM), alpha ignored")
var worstTitle = Double.infinity
var worstSubtitle = Double.infinity
var subtitleNeverLouder = true
for r in stride(from: 0, through: 255, by: 5) {
    for g in stride(from: 0, through: 255, by: 5) {
        for b in stride(from: 0, through: 255, by: 5) {
            let bg = RGBA(red: UInt8(r), green: UInt8(g), blue: UInt8(b), alpha: 255)
            let title = ShieldTextColors.onBackground(bg)
            let subtitle = ShieldTextColors.secondaryOn(bg)
            worstTitle = min(worstTitle, ShieldTextColors.contrast(title, bg))
            worstSubtitle = min(worstSubtitle, ShieldTextColors.contrast(subtitle, bg))
            if ShieldTextColors.contrast(subtitle, bg) > ShieldTextColors.contrast(title, bg) { subtitleNeverLouder = false }
        }
    }
}
check(worstTitle >= ShieldTextColors.titleMinContrast, "white or black always clears 4.5:1 on an opaque background (worst \(String(format: "%.2f", worstTitle)):1)")
check(worstSubtitle >= ShieldTextColors.subtitleMinContrast, "the subtitle always clears 3:1 (worst \(String(format: "%.2f", worstSubtitle)):1)")
check(subtitleNeverLouder, "the subtitle never contrasts more than the title")
let none = ShieldTextColors.resolve(background: nil, title: nil, subtitle: nil, primaryButtonBackground: nil, primaryButton: nil, secondaryButton: nil)
check(none == .init(title: nil, subtitle: nil, primaryButton: nil, secondaryButton: nil), "no background: every default stays the system's")

section("ShieldTextColors — parity (scripts/swift-tests/fixtures/shield-text-colors.tsv, shared with the Kotlin test)")
let fixturePath = "scripts/swift-tests/fixtures/shield-text-colors.tsv"
if let fixture = try? String(contentsOfFile: fixturePath, encoding: .utf8) {
    let rows = fixture.split(separator: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
    check(rows.count >= 10, "the fixture has \(rows.count) cases")
    func parse(_ cell: Substring) -> RGBA? { return cell == "-" ? nil : RGBA(hex: String(cell)) }
    for row in rows {
        let cells = row.split(separator: "\t", omittingEmptySubsequences: false)
        guard cells.count == 12, cells[7] == "=>" else {
            check(false, "row shape: \(row)")
            continue
        }
        let resolved = ShieldTextColors.resolve(
            background: parse(cells[1]), title: parse(cells[2]), subtitle: parse(cells[3]),
            primaryButtonBackground: parse(cells[4]), primaryButton: parse(cells[5]), secondaryButton: parse(cells[6])
        )
        let actual = [resolved.title, resolved.subtitle, resolved.primaryButton, resolved.secondaryButton].map { $0?.hex ?? "-" }
        let expected = cells[8...11].map(String.init)
        check(actual == expected, "\(cells[0]): \(actual)" + (actual == expected ? "" : " expected \(expected)"))
    }
} else {
    check(false, "fixture not found at \(fixturePath) (run from the repo root)")
}

print("\n\(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
