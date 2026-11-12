import Foundation

/// One source of shields: the manual block, a monitored interval in progress,
/// or a `blockOnThreshold` event that fired inside an interval.
///
/// The effective block is the UNION of every active source, and each source is
/// projected into its own named `ManagedSettingsStore` (see `BlockStore`), so
/// ending one source clears only its own shields. Before this, the app and
/// both extensions shared one unnamed store and any `intervalDidEnd` removed
/// every shield on the device, the manual block included.
///
/// Pure Foundation and generic over the token type on purpose: ManagedSettings
/// tokens cannot be constructed outside the OS, so these rules are unit-tested
/// with plain values (`scripts/swift-tests`). Copied verbatim into every
/// extension target — keep the copies byte-identical.

/// What kind of thing a token names. The composition needs it for the
/// allowlist mode, whose shield is "every APPLICATION except these": only
/// application tokens can be allowed, categories and web domains are always
/// shielded under it.
enum BlockTokenKind: Equatable {
    case application
    case category
    case webDomain
}

/// A token the composition can reason about: `ShieldToken` in the app and
/// the extensions, plain strings in the tests.
protocol BlockToken: Hashable {
    var blockTokenKind: BlockTokenKind { get }
}

/// How a source applies its selection — the `BlockMode` of the JS API.
enum BlockMode: String, Equatable {
    /// Shield the selection (default).
    case blocklist
    /// Shield every app EXCEPT the selection's applications — iOS
    /// `ActivityCategoryPolicy.all(except:)` — and, because that policy does
    /// not reach Safari, every web domain too
    /// (`WebDomainCategoryPolicy.all(except:)`). The selection's categories
    /// and web domains, if it has any, allow nothing: `blockSelection` and
    /// `startMonitoring` reject such a selection with `INVALID_CONFIG`, and
    /// one edited into that shape afterwards is projected as its
    /// applications alone (fail closed).
    case allowlist

    /// The mode a stored string means; anything but `allowlist` (including
    /// a missing value) is the default.
    static func stored(_ value: Any?) -> BlockMode {
        return (value as? String).flatMap(BlockMode.init(rawValue:)) ?? .blocklist
    }
}

/// Which source a `BlockSource` is. Hoisted out of the generic struct so a
/// kind can be persisted and compared without a token type (`ShieldOwnerMap`):
/// `BlockSource<String>.Kind` and `BlockSource<ShieldToken>.Kind` are one type.
enum BlockSourceKind: Equatable {
    /// `blockSelection` — a single selection, replaced by the next call.
    case manual
    /// A monitored interval that is in progress.
    case schedule(activityName: String)
    /// A usage threshold with `blockOnThreshold` that fired inside an interval.
    case threshold(activityName: String)

    /// The activity behind a schedule or threshold; `nil` for the manual block.
    var activityName: String? {
        switch self {
        case .manual: return nil
        case .schedule(let name), .threshold(let name): return name
        }
    }

    /// Lower wins. Threshold > schedule > manual (the default config
    /// applies when nothing owns a target).
    fileprivate var precedence: Int {
        switch self {
        case .threshold: return 0
        case .schedule: return 1
        case .manual: return 2
        }
    }

    // Property-list shape, so the shield configuration extension can
    // hand the owner it resolved to the shield action extension.

    init?(dictionary: [String: Any]) {
        switch dictionary["kind"] as? String {
        case "manual":
            self = .manual
        case "schedule":
            guard let name = dictionary["activityName"] as? String else { return nil }
            self = .schedule(activityName: name)
        case "threshold":
            guard let name = dictionary["activityName"] as? String else { return nil }
            self = .threshold(activityName: name)
        default:
            return nil
        }
    }

    var dictionary: [String: Any] {
        switch self {
        case .manual:
            return ["kind": "manual"]
        case .schedule(let name):
            return ["kind": "schedule", "activityName": name]
        case .threshold(let name):
            return ["kind": "threshold", "activityName": name]
        }
    }
}

struct BlockSource<Token: BlockToken>: Equatable {
    typealias Kind = BlockSourceKind

    var kind: Kind
    /// When the source became active, in Unix milliseconds. Among schedules
    /// the latest one wins a shared target.
    var startedAt: Double
    /// Blocklist: everything the source shields — applications, categories
    /// and web domains. Allowlist: the applications it leaves unshielded
    /// (every other app, every category and every web domain is shielded);
    /// empty means "allow nothing".
    var tokens: Set<Token>
    /// How `tokens` is applied. Thresholds are always blocklists.
    var mode: BlockMode

    init(kind: Kind, startedAt: Double, tokens: Set<Token>, mode: BlockMode = .blocklist) {
        self.kind = kind
        self.startedAt = startedAt
        self.tokens = tokens
        self.mode = mode
    }

    /// Whether this source shields any of `targets` — the tokens a shield
    /// may have matched. A blocklist covers what it lists. An allowlist
    /// covers every application it does not list and every web domain (the
    /// web is shielded whole). A category in `targets` only says which
    /// category the app was shielded through: when the app (or domain) is
    /// named, IT decides — an allowed app in a category some blocklist
    /// shields is not covered by the allowlist, whatever the category — and
    /// only a bare category (the action callback's view) counts as covered,
    /// since under `.all(except:)` every category is shielded.
    func covers(anyOf targets: Set<Token>) -> Bool {
        switch mode {
        case .blocklist:
            return !tokens.isDisjoint(with: targets)
        case .allowlist:
            let named = targets.filter { $0.blockTokenKind != .category }
            guard !named.isEmpty else { return !targets.isEmpty }
            return named.contains { $0.blockTokenKind == .webDomain || !tokens.contains($0) }
        }
    }
}

/// Names of the `ManagedSettingsStore`s the module writes. iOS allows about
/// 50 named stores per process (WWDC22, not re-verified); this layout uses at
/// most 2 + the number of monitored activities (≤ 19), i.e. ≤ 21.
enum BlockStore {
    /// `blockSelection`.
    static let manual = "expo_st_manual"
    /// `blockWebDomains` — the web-content filter, never shields.
    static let web = "expo_st_web"
    /// One per logical activity: the interval's selection plus any threshold
    /// selections that fired inside it. Cleared when the interval ends.
    static let activityPrefix = "expo_st_activity_"

    /// Store name for an activity: the prefix, a readable part and the FNV-1a
    /// hash of the full name. The readable part — the name reduced to
    /// `[A-Za-z0-9._-]`, at most 32 characters — is for debugging only; the
    /// hash, always present, is what makes the mapping injective (up to a
    /// 64-bit collision). Sanitizing alone was not: "work hours" and
    /// "work_hours-<its hash>" mapped to the same store. Deterministic across
    /// processes (no `hashValue`).
    static func activity(_ activityName: String) -> String {
        var readable = ""
        for character in activityName.prefix(32) {
            if character.isASCII, character.isLetter || character.isNumber || "._-".contains(character) {
                readable.append(character)
            } else {
                readable.append("_")
            }
        }
        return activityPrefix + readable + "-" + fnv1a(activityName)
    }

    /// FNV-1a, 64-bit, over UTF-8, as lowercase hex — stable across launches
    /// and processes.
    static func fnv1a(_ string: String) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in string.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        return String(hash, radix: 16)
    }
}

enum BlockComposition {
    /// Everything the BLOCKLIST sources shield right now — what a temporary
    /// unlock must suspend and meter. Allowlist sources contribute nothing:
    /// what they shield is "every other app", which no usage event can
    /// name, so an unlock is refused while one is active
    /// (`containsAllowlist`).
    static func union<Token>(of sources: [BlockSource<Token>]) -> Set<Token> {
        return sources.reduce(into: Set<Token>()) { union, source in
            if source.mode == .blocklist {
                union.formUnion(source.tokens)
            }
        }
    }

    /// Whether any of `sources` is an allowlist — the shields a usage meter
    /// cannot cover.
    static func containsAllowlist<Token>(_ sources: [BlockSource<Token>]) -> Bool {
        return sources.contains { $0.mode == .allowlist }
    }

    /// Whether a targeted unlock — `temporaryUnlock(…, { target })`, the
    /// app-side twin of the shield snooze — can lift `target` from
    /// `sources` (the sources in force, the ones a grant in progress
    /// suspended included: an unlock is renewable, so a suspended block
    /// still counts as a block).
    enum TargetVerdict: Equatable {
        /// Some source shields it, and the snooze projection
        /// (`storeShields`) takes it out: an app or a web domain a blocklist
        /// lists or an allowlist shields, or a category a blocklist lists.
        case liftable
        /// No active source shields it — `NO_ACTIVE_BLOCK`. Tokens are
        /// opaque, so an app shielded only through its category is not
        /// known to be shielded: the category's own handle (the one the
        /// shield reports for such an app) is the target to lift.
        case notShielded
        /// A category while an allowlist is in force — `UNSUPPORTED`: the
        /// allowlist's store shields every app through `.all(except:)`,
        /// which cannot except a category, so the category's apps would
        /// stay shielded whatever a blocklist lifted, and the grant would
        /// hold a meter that never advances until midnight (the shield
        /// action extension closes such a press for the same reason).
        case categoryUnderAllowlist
    }

    static func targetedUnlockVerdict<Token>(for target: Token, in sources: [BlockSource<Token>]) -> TargetVerdict {
        let covering = sources.filter { $0.covers(anyOf: [target]) }
        guard !covering.isEmpty else { return .notShielded }
        // An allowlist covers every bare category, so "any allowlist in
        // force" and "an allowlist covers it" are the same test here.
        if target.blockTokenKind == .category, covering.contains(where: { $0.mode == .allowlist }) {
            return .categoryUnderAllowlist
        }
        return .liftable
    }

    /// The source whose shield configuration and actions apply to a shielded
    /// target. `targets` holds every token the shield may have matched (an app
    /// shielded through its category is matched by either token).
    ///
    /// Precedence: a threshold that covers the target, then the LATEST
    /// schedule that covers it, then the manual block; `nil` when no active
    /// source covers it (the default configuration applies). An allowlist
    /// covers every app it does not allow (`BlockSource.covers`).
    static func owner<Token>(ofAnyOf targets: Set<Token>, in sources: [BlockSource<Token>]) -> BlockSource<Token>? {
        return sources
            .filter { $0.covers(anyOf: targets) }
            .sorted { lhs, rhs in
                if lhs.kind.precedence != rhs.kind.precedence {
                    return lhs.kind.precedence < rhs.kind.precedence
                }
                if lhs.startedAt != rhs.startedAt {
                    return lhs.startedAt > rhs.startedAt
                }
                // Deterministic tie-break — same precedence and start time.
                return (lhs.kind.activityName ?? "") < (rhs.kind.activityName ?? "")
            }
            .first
    }

    /// The owner for a shield ACTION: the source the configuration step
    /// resolved for the same shield (`resolved`, persisted per token by
    /// `ShieldOwnerMap`), as long as it is still active — and, when
    /// `coveringTargets` is set, still shields one of `targets` — else
    /// resolved afresh from `targets`.
    ///
    /// The configuration callback receives the app AND its category while
    /// the action callback receives one of them, so resolving independently
    /// could pick two different owners for one shield; reusing the
    /// configuration's owner keeps the buttons consistent with the screen.
    /// The coverage check is for the category context: the entry under a
    /// category is only ever an owner that shields through that category
    /// (`handoffTargets`), and a stale or foreign entry must not survive a
    /// selection edit.
    static func owner<Token>(
        ofAnyOf targets: Set<Token>,
        in sources: [BlockSource<Token>],
        preferring resolved: BlockSourceKind?,
        coveringTargets: Bool = false
    ) -> BlockSource<Token>? {
        if let resolved = resolved,
           let source = sources.first(where: { $0.kind == resolved }),
           !coveringTargets || source.covers(anyOf: targets) {
            return source
        }
        return owner(ofAnyOf: targets, in: sources)
    }

    /// Under which of a shield's tokens the configuration extension persists
    /// the owner it resolved, for the action extension to reuse.
    ///
    /// The primary token — the app or the web domain — always: the entry is
    /// specific to it. The category only when the owner shields THROUGH it
    /// (a blocklist listing the category, or an allowlist, which shields
    /// through every category): among blocklists that owner is the
    /// highest-precedence source covering the category, the same for every
    /// app in it, so the shared entry is consistent whoever writes it. An
    /// owner that covers the app directly is never written under the
    /// category: with X and Y in category C, X's own owner used to land
    /// under C and be served to Y's shield (last writer won). A `nil` owner
    /// means no source covers either token, so both entries are cleared.
    /// While an allowlist is active the category entry does depend on the
    /// app (an allowed app in C resolves past the allowlist): the action
    /// extension then either sees the blocklist that really shields its
    /// app, or an allowlist in the category context, which it declines to
    /// act on (it cannot single out the app under `.all`).
    static func handoffTargets<Token>(of owner: BlockSource<Token>?, primary: Token, category: Token?) -> [Token] {
        var targets = [primary]
        if let category = category, owner.map({ $0.covers(anyOf: [category]) }) ?? true {
            targets.append(category)
        }
        return targets
    }

    /// What each store must contain for `sources` to be in force: the manual
    /// block in its store, and each activity's schedule and threshold shields
    /// merged into that activity's store. Stores absent from the result must
    /// be empty. The blocklist view — `storeShields` is what the writers
    /// project, allowlists and snoozes included.
    static func storeContents<Token>(of sources: [BlockSource<Token>]) -> [String: Set<Token>] {
        var contents: [String: Set<Token>] = [:]
        for source in sources where source.mode == .blocklist && !source.tokens.isEmpty {
            contents[storeName(of: source.kind), default: []].formUnion(source.tokens)
        }
        return contents
    }

    /// The named store a source is projected into.
    static func storeName(of kind: BlockSourceKind) -> String {
        switch kind {
        case .manual:
            return BlockStore.manual
        case .schedule(let activityName), .threshold(let activityName):
            return BlockStore.activity(activityName)
        }
    }

    /// What each store must shield for `sources` to be in force, with the
    /// shield snoozes in progress taken out. Stores absent from the result
    /// must be empty.
    ///
    /// Union rule: a target is shielded if ANY source shields it. Within a
    /// store (an activity's schedule plus its thresholds; the manual block
    /// alone):
    /// - the blocklist sources' tokens are shielded, minus the snoozed ones;
    /// - an allowlist turns the store into "every app except…" (and every
    ///   web domain): the excepted apps are the ones EVERY allowlist of the
    ///   store allows — two allowlists intersect, since an app one of them
    ///   shields is shielded — minus the apps a blocklist of the store
    ///   shields directly (any source shielding it wins), plus the snoozed
    ///   apps. The blocklist's categories are dropped: `.all` already covers
    ///   them, and an allowed app inside one cannot be told apart (tokens
    ///   are opaque), so it stays allowed within that store. Its web domains
    ///   are kept alongside the web-wide policy (an `unblock` edits them).
    /// - a snoozed token is removed from every store, and the snoozed
    ///   applications and web domains are excepted from every store's
    ///   category and web policies (an app shielded through its category
    ///   is matched by the category token, which stays for the category's
    ///   other apps; under an allowlist the exception is the only way to
    ///   lift it). A store the snoozes emptied is absent, like any other
    ///   empty store. The other sources are untouched: a snooze lifts
    ///   exactly what was pressed, in every source covering it, and only
    ///   until its budget is spent.
    static func storeShields<Token>(of sources: [BlockSource<Token>], snoozed: Set<Token>) -> [String: StoreShields<Token>] {
        var blocked: [String: Set<Token>] = [:]
        var allowed: [String: Set<Token>] = [:]
        for source in sources {
            let name = storeName(of: source.kind)
            switch source.mode {
            case .blocklist:
                blocked[name, default: []].formUnion(source.tokens)
            case .allowlist:
                let applications = Set(source.tokens.filter { $0.blockTokenKind == .application })
                if let existing = allowed[name] {
                    allowed[name] = existing.intersection(applications)
                } else {
                    allowed[name] = applications
                }
            }
        }

        var shields: [String: StoreShields<Token>] = [:]
        for name in Set(blocked.keys).union(allowed.keys) {
            let listed = blocked[name] ?? []
            var remaining = listed.subtracting(snoozed)
            var excepted = Set(snoozed.filter { $0.blockTokenKind != .category })
            let allowlist = allowed[name] != nil
            if let allowedApplications = allowed[name] {
                let shieldedDirectly = Set(listed.filter { $0.blockTokenKind == .application })
                excepted.formUnion(allowedApplications.subtracting(shieldedDirectly))
                remaining = Set(remaining.filter { $0.blockTokenKind != .category })
            }
            if allowlist || !remaining.isEmpty {
                shields[name] = StoreShields(tokens: remaining, excepted: excepted, allowlist: allowlist)
            }
        }
        return shields
    }
}

/// What one named store must shield. Blocklist (`allowlist` false): `tokens`
/// — applications, categories and web domains — with the applications among
/// `excepted` left out of the category shields (the targets a shield snooze
/// lifted). Allowlist (`allowlist` true): every application except the
/// applications in `excepted` (`ActivityCategoryPolicy.all(except:)`), every
/// web domain except the web domains in `excepted`
/// (`WebDomainCategoryPolicy.all(except:)`), plus the applications and web
/// domains in `tokens`, which a blocklist of the same store shields
/// directly. `tokens` never holds a category when `allowlist` is set.
struct StoreShields<Token: BlockToken>: Equatable {
    var tokens: Set<Token>
    var excepted: Set<Token>
    var allowlist: Bool

    init(tokens: Set<Token>, excepted: Set<Token> = [], allowlist: Bool = false) {
        self.tokens = tokens
        self.excepted = excepted
        self.allowlist = allowlist
    }

    /// Whether the store shields nothing at all — the condition under which
    /// it leaves the registry. An allowlist shields something even when it
    /// lists no token.
    var isEmpty: Bool {
        return tokens.isEmpty && !allowlist
    }
}

// MARK: - App Group records → sources

/// A monitored interval in progress, as recorded in the App Group.
struct ActiveInterval: Equatable {
    /// When the interval started, in Unix milliseconds.
    var startedAt: Double
    /// `unblockAll` lifted its shields; they come back at the next interval start.
    var lifted: Bool
}

/// The App Group records every process reads to compose the block sources.
/// Pure data, so the projection rules can be tested without ManagedSettings.
struct BlockRecords: Equatable {
    /// `blockSelection`'s selection, if any.
    var manualSelectionId: String?
    /// How `blockSelection` applies it — persisted with the id, so a relock
    /// or a relaunch restores the same shield.
    var manualMode: BlockMode = .blocklist
    /// Activity → its interval in progress.
    var intervals: [String: ActiveInterval] = [:]
    /// Activity → the selection its schedule shields.
    var scheduleSelectionIds: [String: String] = [:]
    /// Activity → how its schedule applies the selection (`mode` of its
    /// stored configuration); absent means blocklist.
    var scheduleModes: [String: BlockMode] = [:]
    /// Activity → the selections shielded by `blockOnThreshold` events that
    /// fired inside the interval in progress. Always blocklists.
    var thresholdSelectionIds: [String: [String]] = [:]

    init(
        manualSelectionId: String? = nil,
        manualMode: BlockMode = .blocklist,
        intervals: [String: ActiveInterval] = [:],
        scheduleSelectionIds: [String: String] = [:],
        scheduleModes: [String: BlockMode] = [:],
        thresholdSelectionIds: [String: [String]] = [:]
    ) {
        self.manualSelectionId = manualSelectionId
        self.manualMode = manualMode
        self.intervals = intervals
        self.scheduleSelectionIds = scheduleSelectionIds
        self.scheduleModes = scheduleModes
        self.thresholdSelectionIds = thresholdSelectionIds
    }
}

/// What a temporary unlock suspended when it was granted — persisted with
/// the grant, so EVERY later projection (the app's, the monitor's) leaves
/// those sources unshielded until relock or expiry. A later callback used to
/// re-apply them: a deduplicated `intervalDidStart`, a new threshold.
///
/// Suspension is by identity, not by name: a schedule is suspended for the
/// occurrence it was in (its `startedAt`), so the activity's NEXT occurrence
/// shields normally; a threshold is suspended per selection AND per
/// occurrence — stopping the activity and starting it again during the
/// grant makes a new occurrence, and a threshold that fires again in it
/// shields its selection (by name alone, it stayed suppressed). A source that
/// starts during the grant is not suspended — the meter only covers what
/// was.
struct SuspendedSources: Equatable {
    /// The threshold selections suspended for one activity, bound to the
    /// occurrence they fired in.
    struct Thresholds: Equatable {
        /// `startedAt` of the interval the thresholds fired in.
        var startedAt: Double
        var selectionIds: [String]
    }

    var manual: Bool = false
    /// Activity → `startedAt` of the suspended occurrence.
    var schedules: [String: Double] = [:]
    /// Activity → its suspended threshold selections, for one occurrence.
    var thresholds: [String: Thresholds] = [:]

    init(manual: Bool = false, schedules: [String: Double] = [:], thresholds: [String: Thresholds] = [:]) {
        self.manual = manual
        self.schedules = schedules
        self.thresholds = thresholds
    }

    /// Everything that may shield right now, i.e. what a grant suspends. A
    /// renewal captures again: the previous grant's sources are still in the
    /// records (or gone for good), so no merge is needed.
    static func capturing(_ records: BlockRecords) -> SuspendedSources {
        var suspended = SuspendedSources(manual: records.manualSelectionId != nil)
        for (activityName, interval) in records.intervals where !interval.lifted && records.scheduleSelectionIds[activityName] != nil {
            suspended.schedules[activityName] = interval.startedAt
        }
        for (activityName, selectionIds) in records.thresholdSelectionIds where !selectionIds.isEmpty {
            if let interval = records.intervals[activityName] {
                suspended.thresholds[activityName] = Thresholds(startedAt: interval.startedAt, selectionIds: selectionIds)
            }
        }
        return suspended
    }

    var isEmpty: Bool {
        return !manual && schedules.isEmpty && thresholds.isEmpty
    }

    func suspends(scheduleOf activityName: String, startedAt: Double) -> Bool {
        return schedules[activityName] == startedAt
    }

    /// Whether `selectionId`'s threshold shield is suspended for the
    /// occurrence of `activityName` that started at `startedAt`.
    func suspends(thresholdOf activityName: String, startedAt: Double, selectionId: String) -> Bool {
        guard let thresholds = thresholds[activityName], thresholds.startedAt == startedAt else { return false }
        return thresholds.selectionIds.contains(selectionId)
    }

    // Property-list shape, for the App Group.

    init?(dictionary: [String: Any]) {
        guard let manual = dictionary["manual"] as? Bool else { return nil }
        self.manual = manual
        var schedules: [String: Double] = [:]
        for (name, value) in dictionary["schedules"] as? [String: Any] ?? [:] {
            if let startedAt = Self.double(value) {
                schedules[name] = startedAt
            }
        }
        self.schedules = schedules
        var thresholds: [String: Thresholds] = [:]
        for (name, value) in dictionary["thresholds"] as? [String: Any] ?? [:] {
            if let entry = value as? [String: Any],
               let startedAt = Self.double(entry["startedAt"]),
               let selectionIds = entry["selectionIds"] as? [String] {
                thresholds[name] = Thresholds(startedAt: startedAt, selectionIds: selectionIds)
            }
        }
        // A grant persisted by a build that suspended thresholds by name only
        // (`thresholdSelectionIds`) carries no occurrence: those thresholds
        // are not suspended any more — fail closed, the shields come back.
        self.thresholds = thresholds
    }

    var dictionary: [String: Any] {
        var thresholdsDictionary: [String: Any] = [:]
        for (name, entry) in thresholds {
            thresholdsDictionary[name] = ["startedAt": entry.startedAt, "selectionIds": entry.selectionIds]
        }
        return [
            "manual": manual,
            "schedules": schedules,
            "thresholds": thresholdsDictionary,
        ]
    }

    /// UserDefaults hands numbers back as `NSNumber`.
    private static func double(_ value: Any?) -> Double? {
        if let double = value as? Double { return double }
        if let number = value as? NSNumber { return number.doubleValue }
        return nil
    }
}

/// The owners the shield configuration extension resolved, by token, for the
/// shield action extension (`BlockComposition.owner(ofAnyOf:in:preferring:)`).
/// One record under one App Group key — written and read only under
/// `BlockStateLock` — so it can be bounded and pruned: one key per token used
/// to accumulate for every app ever shielded, with nothing to bound it.
///
/// Lifecycle: an entry is written when a shield is configured, replaced when
/// the same token is configured again, dropped when its source ends
/// (`prune(keeping:)` from the activity's end and from every full
/// projection), cleared by `unblockAll`, and evicted oldest-first past
/// `capacity`.
struct ShieldOwnerMap: Equatable {
    /// Which configuration callback persisted an entry. The action callback
    /// of the same context reuses it; another context resolves on its own.
    enum Context: String {
        case application
        case category
        case webDomain
    }

    struct Entry: Equatable {
        var owner: BlockSourceKind
        var context: Context
        /// When it was recorded, Unix ms — eviction order.
        var recordedAt: Double
    }

    /// Enough for every app of a large selection plus their categories; a
    /// token past the cap evicts the oldest entry.
    static let capacity = 128

    /// Token key (`ShieldToken.persistentKey`) → entry.
    var entries: [String: Entry] = [:]

    init(entries: [String: Entry] = [:]) {
        self.entries = entries
    }

    /// Record `owner` under each (key, context); `nil` clears those keys.
    mutating func record(_ owner: BlockSourceKind?, under targets: [(key: String, context: Context)], at recordedAt: Double) {
        for target in targets {
            if let owner = owner {
                entries[target.key] = Entry(owner: owner, context: target.context, recordedAt: recordedAt)
            } else {
                entries.removeValue(forKey: target.key)
            }
        }
        evict()
    }

    /// The owner recorded under `key` by a configuration of `context`.
    func owner(forKey key: String, context: Context) -> BlockSourceKind? {
        guard let entry = entries[key], entry.context == context else { return nil }
        return entry.owner
    }

    /// Drop every entry whose owner `isActive` rejects.
    mutating func prune(keeping isActive: (BlockSourceKind) -> Bool) {
        entries = entries.filter { isActive($0.value.owner) }
    }

    private mutating func evict() {
        guard entries.count > Self.capacity else { return }
        let oldestFirst = entries.sorted { lhs, rhs in
            if lhs.value.recordedAt != rhs.value.recordedAt {
                return lhs.value.recordedAt < rhs.value.recordedAt
            }
            return lhs.key < rhs.key
        }
        for (key, _) in oldestFirst.prefix(entries.count - Self.capacity) {
            entries.removeValue(forKey: key)
        }
    }

    // Property-list shape, for the App Group.

    init(dictionary: [String: Any]) {
        var entries: [String: Entry] = [:]
        for (key, value) in dictionary {
            guard let entry = value as? [String: Any],
                  let ownerDictionary = entry["owner"] as? [String: Any],
                  let owner = BlockSourceKind(dictionary: ownerDictionary),
                  let context = (entry["context"] as? String).flatMap(Context.init(rawValue:)) else {
                continue
            }
            let recordedAt = (entry["recordedAt"] as? Double) ?? (entry["recordedAt"] as? NSNumber)?.doubleValue ?? 0
            entries[key] = Entry(owner: owner, context: context, recordedAt: recordedAt)
        }
        self.entries = entries
    }

    var dictionary: [String: Any] {
        var dictionary: [String: Any] = [:]
        for (key, entry) in entries {
            dictionary[key] = [
                "owner": entry.owner.dictionary,
                "context": entry.context.rawValue,
                "recordedAt": entry.recordedAt,
            ]
        }
        return dictionary
    }
}

extension BlockComposition {
    /// The sources in force given the records and the suspension, resolving
    /// selection ids into tokens with `resolve` (nil = no such selection,
    /// and the source is omitted). A blocklist resolving to an empty
    /// selection shields nothing and is omitted too; an allowlist keeps its
    /// applications only — a selection edited to hold categories or web
    /// domains after it was applied allows nothing more — and an empty one
    /// is kept: it shields every app ("allow nothing"), which is what was
    /// asked. Threshold shields need their interval: a threshold list left
    /// behind by a late callback after the interval was cleared shields
    /// nothing.
    static func sources<Token>(
        from records: BlockRecords,
        suspended: SuspendedSources?,
        resolve: (String) -> Set<Token>?
    ) -> [BlockSource<Token>] {
        var sources: [BlockSource<Token>] = []

        if let selectionId = records.manualSelectionId,
           !(suspended?.manual ?? false),
           let source = source(kind: .manual, startedAt: 0, mode: records.manualMode, tokens: resolve(selectionId)) {
            sources.append(source)
        }

        for (activityName, interval) in records.intervals.sorted(by: { $0.key < $1.key }) {
            if !interval.lifted,
               !(suspended?.suspends(scheduleOf: activityName, startedAt: interval.startedAt) ?? false),
               let selectionId = records.scheduleSelectionIds[activityName],
               let source = source(
                   kind: .schedule(activityName: activityName),
                   startedAt: interval.startedAt,
                   mode: records.scheduleModes[activityName] ?? .blocklist,
                   tokens: resolve(selectionId)
               ) {
                sources.append(source)
            }

            var thresholdTokens = Set<Token>()
            for selectionId in records.thresholdSelectionIds[activityName] ?? []
            where !(suspended?.suspends(thresholdOf: activityName, startedAt: interval.startedAt, selectionId: selectionId) ?? false) {
                thresholdTokens.formUnion(resolve(selectionId) ?? [])
            }
            if !thresholdTokens.isEmpty {
                sources.append(BlockSource(
                    kind: .threshold(activityName: activityName),
                    startedAt: interval.startedAt,
                    tokens: thresholdTokens
                ))
            }
        }

        return sources
    }

    /// A manual or schedule source over a resolved selection, or `nil` when
    /// it shields nothing (see `sources(from:suspended:resolve:)`).
    private static func source<Token>(
        kind: BlockSourceKind,
        startedAt: Double,
        mode: BlockMode,
        tokens: Set<Token>?
    ) -> BlockSource<Token>? {
        guard let tokens = tokens else { return nil }
        switch mode {
        case .blocklist:
            guard !tokens.isEmpty else { return nil }
            return BlockSource(kind: kind, startedAt: startedAt, tokens: tokens, mode: .blocklist)
        case .allowlist:
            let applications = Set(tokens.filter { $0.blockTokenKind == .application })
            return BlockSource(kind: kind, startedAt: startedAt, tokens: applications, mode: .allowlist)
        }
    }
}

// MARK: - Usage budgets (the temporary unlock and the shield snoozes)

/// How a usage budget is metered: a ladder of DeviceActivity usage
/// thresholds over the lifted tokens, one event per step, registered by the
/// process that grants the budget and consumed by the monitor extension.
/// Shared by `temporaryUnlock` (the whole block, armed by the app) and the
/// shield snooze (one target, armed by the shield action extension), so the
/// two meters behave the same.
enum UsageMeter {
    /// Metering grain. Below ~30 s Apple's usage thresholds fire late or not
    /// at all, so this is the finest step worth registering — and the floor
    /// of every budget.
    static let stepSeconds = 30
    /// Ceiling on registered events — DeviceActivity degrades with large
    /// event sets, so the step coarsens for big budgets.
    static let maxSteps = 60
    /// A step may report this much more use than wall-clock time allows
    /// before it is deemed impossible. Use since the grant can never exceed
    /// the time since the grant; the slack covers clock jitter between the
    /// granting process and the monitor, and the interval start being
    /// truncated to the second. It must stay well below a step: a slack of
    /// one step let an immediate fire of the first step through, which
    /// spent a floor budget in the same instant it was granted.
    static let slackSeconds = 5
    /// DeviceActivity rejects intervals shorter than this
    /// (`MonitoringError.intervalTooShort`).
    static let minimumIntervalSeconds = 15 * 60

    /// The budget actually metered for a requested one: never below a step.
    static func budget(for requestedSeconds: Int) -> Int {
        return max(stepSeconds, requestedSeconds)
    }

    /// The step (seconds of use) the ladder for `budget` moves in: the
    /// grain, widened so the ladder has at most `maxSteps` events — 30 s up
    /// to a 30 min budget, 120 s for 2 h. Reported to JS as the grant's
    /// `meteringSeconds`: the remaining budget only ever moves by this much.
    static func step(forBudget budget: Int) -> Int {
        return max(stepSeconds, Int(ceil(Double(self.budget(for: budget)) / Double(maxSteps))))
    }

    /// The thresholds (seconds of use) to register for `budget`: every
    /// multiple of the step below it, then the budget itself.
    static func thresholds(budget: Int) -> [Int] {
        let budget = self.budget(for: budget)
        let step = self.step(forBudget: budget)
        var thresholds = Array(stride(from: step, to: budget, by: step))
        thresholds.append(budget)
        return thresholds
    }

    /// When a meter armed at `now` must end: the end of the day — a budget
    /// never carries across midnight, and the interval's end is the
    /// monitor's day-end callback — unless that is under the system's
    /// minimum interval away, in which case the interval runs the minimum
    /// (plus a minute of margin) into the next day: a snooze pressed at
    /// 23:50 is then armed instead of refused, and the snooze itself is
    /// void at midnight anyway (`ShieldSnoozes.current`).
    static func meterEnd(now: Date, calendar: Calendar) -> Date {
        let startOfDay = calendar.startOfDay(for: now)
        let endOfDay = calendar.date(byAdding: DateComponents(day: 1, second: -1), to: startOfDay) ?? now
        let earliest = now.addingTimeInterval(TimeInterval(minimumIntervalSeconds + 60))
        return max(endOfDay, earliest)
    }

    /// Whether a step reporting `stepSeconds` of use could be true
    /// `elapsedSeconds` after the grant. iOS occasionally fires thresholds
    /// at once on registration (seen on iOS 26 betas, and when the target
    /// was in use just before): such a fire claims more use than physically
    /// possible and must be ignored, or a fresh budget is spent instantly.
    static func isPlausible(step stepSeconds: Int, elapsedSeconds: Double) -> Bool {
        return Double(stepSeconds) <= elapsedSeconds + Double(slackSeconds)
    }

    /// A duration from a stored action config: JS numbers reach the App
    /// Group as `Double` or `NSNumber`, never reliably `Int`.
    static func seconds(_ value: Any?) -> Int? {
        if let int = value as? Int { return int }
        // Double and NSNumber both bridge to NSNumber; a non-finite value
        // must not reach `Int(_:)`.
        guard let number = value as? NSNumber else { return nil }
        let double = number.doubleValue
        guard double.isFinite, abs(double) < Double(Int.max) else { return nil }
        return Int(double)
    }
}

// MARK: - Shield snooze

/// A shield snooze in progress: the target whose shield the user pressed the
/// snooze button on, lifted until it has been USED for `budgetSeconds` (the
/// `duration` of `{ type: "snooze", duration }`). Metered by its own
/// DeviceActivity, `expo_st_snooze_<id>`, armed by the shield action
/// extension (iOS lets it, checked on a device) and consumed by the monitor extension,
/// so the budget is spent only while that target is in the foreground and
/// the shield comes back even if the app is never launched.
///
/// A snooze is a suspension of ONE token, applied at projection time
/// (`BlockComposition.storeShields`): the sources covering the target stay
/// active and own its shield configuration, the other shields stay up, and
/// a source that starts or ends during the snooze is composed as usual. It
/// is independent of the temporary unlock (which suspends whole sources):
/// both meter the target while both run, and the one that ends first does
/// not end the other. `blockSelection`, `unblockAll` and `relock` end every
/// snooze; a spent budget, the day's end, or the extension failing to arm
/// the meter end one.
///
/// The target is persisted as its token kind and the token's encoded form,
/// so every process rebuilds the same token (`ShieldToken`, in
/// SharedDefaults.swift). Pure Foundation, unit-tested in
/// `scripts/swift-tests`.
struct ShieldSnooze: Equatable {
    /// The reserved DeviceActivity names of the meters (`expo_st_` is the
    /// module's namespace: never listed by, nor stopped through, the
    /// monitoring API).
    static let activityPrefix = "expo_st_snooze_"
    /// Event names carry their threshold: `expo_st_snooze_step_<seconds>`.
    static let stepPrefix = "expo_st_snooze_step_"

    /// Unique per grant, so a meter's callbacks can never be taken for
    /// another snooze's — the same target snoozed again gets a new id.
    var id: String
    /// `ShieldToken` kind: `application`, `category` or `webDomain`.
    var tokenKind: String
    /// The token's encoded form, base64.
    var token: String
    var budgetSeconds: Int
    /// Unix milliseconds.
    var grantedAt: Double
    var consumedSeconds: Int = 0

    var activityName: String {
        return Self.activityPrefix + id
    }

    var remainingSeconds: Int {
        return max(0, budgetSeconds - consumedSeconds)
    }

    /// The same target as `other` (whatever the ids and budgets).
    func covers(sameTargetAs other: ShieldSnooze) -> Bool {
        return tokenKind == other.tokenKind && token == other.token
    }

    /// Whether `name` is a snooze meter's DeviceActivity name.
    static func isActivityName(_ name: String) -> Bool {
        return name.hasPrefix(activityPrefix)
    }

    /// The threshold a step event reports, or `nil` for any other event.
    static func stepSeconds(ofEvent name: String) -> Int? {
        guard name.hasPrefix(stepPrefix),
              let seconds = Int(name.dropFirst(stepPrefix.count)),
              seconds > 0 else {
            return nil
        }
        return seconds
    }

    static func stepEventName(_ seconds: Int) -> String {
        return stepPrefix + String(seconds)
    }

    // Property-list shape, for the App Group.

    init(id: String, tokenKind: String, token: String, budgetSeconds: Int, grantedAt: Double, consumedSeconds: Int = 0) {
        self.id = id
        self.tokenKind = tokenKind
        self.token = token
        self.budgetSeconds = budgetSeconds
        self.grantedAt = grantedAt
        self.consumedSeconds = consumedSeconds
    }

    init?(dictionary: [String: Any]) {
        guard let id = dictionary["id"] as? String, !id.isEmpty,
              let tokenKind = dictionary["tokenKind"] as? String,
              let token = dictionary["token"] as? String,
              let budgetSeconds = UsageMeter.seconds(dictionary["budgetSeconds"]), budgetSeconds > 0,
              let grantedAt = Self.double(dictionary["grantedAt"]) else {
            return nil
        }
        self.id = id
        self.tokenKind = tokenKind
        self.token = token
        self.budgetSeconds = budgetSeconds
        self.grantedAt = grantedAt
        self.consumedSeconds = max(0, UsageMeter.seconds(dictionary["consumedSeconds"]) ?? 0)
    }

    var dictionary: [String: Any] {
        return [
            "id": id,
            "tokenKind": tokenKind,
            "token": token,
            "budgetSeconds": budgetSeconds,
            "grantedAt": grantedAt,
            "consumedSeconds": consumedSeconds,
        ]
    }

    private static func double(_ value: Any?) -> Double? {
        if let double = value as? Double { return double }
        if let number = value as? NSNumber { return number.doubleValue }
        return nil
    }
}

/// The rules over the list of snoozes in progress. Every operation runs
/// under `BlockStateLock`, on the list read in the same critical section.
enum ShieldSnoozes {
    /// The snoozes still in force at `now`: a budget never carries across
    /// midnight (like the temporary unlock), so a snooze granted on another
    /// calendar day is void — even before its meter's day-end callback, which
    /// a device asleep at midnight may miss, has cleared it.
    static func current(_ snoozes: [ShieldSnooze], now: Date, calendar: Calendar) -> [ShieldSnooze] {
        return snoozes.filter { calendar.isDate(Date(timeIntervalSince1970: $0.grantedAt / 1000), inSameDayAs: now) }
    }

    /// `snoozes` with `snooze` added. A snooze over the same target is
    /// replaced (its budget restarts, its meter is released by the caller);
    /// snoozes over other targets run on, each with its own meter and
    /// budget — pressing snooze on a second app does not end the first.
    static func granting(_ snooze: ShieldSnooze, in snoozes: [ShieldSnooze]) -> (snoozes: [ShieldSnooze], replaced: [ShieldSnooze]) {
        let replaced = snoozes.filter { $0.covers(sameTargetAs: snooze) }
        return (snoozes.filter { !$0.covers(sameTargetAs: snooze) } + [snooze], replaced)
    }

    /// What a meter callback did to the list.
    enum Outcome: Equatable {
        /// No snooze has this meter: a stale callback (the snooze was spent,
        /// replaced or ended, and its meter stopped) — nothing to do.
        case stale
        /// An event that is not a step of the ladder on a live snooze. The
        /// snooze is ended: leaving the target lifted for good would be
        /// worse than re-shielding it early (fail closed).
        case malformed
        /// The step claims more use than the time since the grant allows
        /// (`UsageMeter.isPlausible`); consumption is left as it was.
        case implausible
        /// Consumption moved forward (steps can arrive out of order; it only
        /// ever grows).
        case consumed
        /// The budget is spent: the snooze is removed and the target's
        /// shields come back with the next projection.
        case spent
    }

    /// Apply the event `eventName` of the meter `activityName`, reported at
    /// `now`, to `snoozes`.
    static func recording(
        event eventName: String,
        ofActivity activityName: String,
        in snoozes: [ShieldSnooze],
        now: Date
    ) -> (snoozes: [ShieldSnooze], outcome: Outcome) {
        guard let index = snoozes.firstIndex(where: { $0.activityName == activityName }) else {
            return (snoozes, .stale)
        }
        var updated = snoozes
        guard let step = ShieldSnooze.stepSeconds(ofEvent: eventName) else {
            updated.remove(at: index)
            return (updated, .malformed)
        }
        let elapsed = now.timeIntervalSince1970 - snoozes[index].grantedAt / 1000
        guard UsageMeter.isPlausible(step: step, elapsedSeconds: elapsed) else {
            return (snoozes, .implausible)
        }
        if step >= updated[index].budgetSeconds {
            updated.remove(at: index)
            return (updated, .spent)
        }
        if step > updated[index].consumedSeconds {
            updated[index].consumedSeconds = step
        }
        return (updated, .consumed)
    }

    /// `snoozes` without the one metered by `activityName` (its interval
    /// ended — the day is over — or the app ended it).
    static func ending(activityName: String, in snoozes: [ShieldSnooze]) -> (snoozes: [ShieldSnooze], ended: Bool) {
        let remaining = snoozes.filter { $0.activityName != activityName }
        return (remaining, remaining.count != snoozes.count)
    }
}
