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

struct BlockSource<Token: Hashable>: Equatable {
    typealias Kind = BlockSourceKind

    var kind: Kind
    /// When the source became active, in Unix milliseconds. Among schedules
    /// the latest one wins a shared target.
    var startedAt: Double
    /// Everything the source shields: applications, categories and web domains.
    var tokens: Set<Token>
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
    /// Everything shielded right now — what a temporary unlock must suspend
    /// and meter.
    static func union<Token>(of sources: [BlockSource<Token>]) -> Set<Token> {
        return sources.reduce(into: Set<Token>()) { $0.formUnion($1.tokens) }
    }

    /// The source whose shield configuration and actions apply to a shielded
    /// target. `targets` holds every token the shield may have matched (an app
    /// shielded through its category is matched by either token).
    ///
    /// Precedence: a threshold that covers the target, then the LATEST
    /// schedule that covers it, then the manual block; `nil` when no active
    /// source covers it (the default configuration applies).
    static func owner<Token>(ofAnyOf targets: Set<Token>, in sources: [BlockSource<Token>]) -> BlockSource<Token>? {
        return sources
            .filter { !$0.tokens.isDisjoint(with: targets) }
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
           !coveringTargets || !source.tokens.isDisjoint(with: targets) {
            return source
        }
        return owner(ofAnyOf: targets, in: sources)
    }

    /// Under which of a shield's tokens the configuration extension persists
    /// the owner it resolved, for the action extension to reuse.
    ///
    /// The primary token — the app or the web domain — always: the entry is
    /// specific to it. The category only when the owner shields THROUGH it
    /// (its tokens contain the category): that owner is then the
    /// highest-precedence source covering the category, the same for every
    /// app in it, so the shared entry is consistent whoever writes it. An
    /// owner that covers the app directly is never written under the
    /// category: with X and Y in category C, X's own owner used to land under
    /// C and be served to Y's shield (last writer won). A `nil` owner means
    /// no source covers either token, so both entries are cleared.
    static func handoffTargets<Token>(of owner: BlockSource<Token>?, primary: Token, category: Token?) -> [Token] {
        var targets = [primary]
        if let category = category, owner.map({ $0.tokens.contains(category) }) ?? true {
            targets.append(category)
        }
        return targets
    }

    /// What each store must contain for `sources` to be in force: the manual
    /// block in its store, and each activity's schedule and threshold shields
    /// merged into that activity's store. Stores absent from the result must
    /// be empty.
    static func storeContents<Token>(of sources: [BlockSource<Token>]) -> [String: Set<Token>] {
        var contents: [String: Set<Token>] = [:]
        for source in sources where !source.tokens.isEmpty {
            let storeName: String
            switch source.kind {
            case .manual:
                storeName = BlockStore.manual
            case .schedule(let activityName), .threshold(let activityName):
                storeName = BlockStore.activity(activityName)
            }
            contents[storeName, default: []].formUnion(source.tokens)
        }
        return contents
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
    /// Activity → its interval in progress.
    var intervals: [String: ActiveInterval] = [:]
    /// Activity → the selection its schedule shields.
    var scheduleSelectionIds: [String: String] = [:]
    /// Activity → the selections shielded by `blockOnThreshold` events that
    /// fired inside the interval in progress.
    var thresholdSelectionIds: [String: [String]] = [:]

    init(
        manualSelectionId: String? = nil,
        intervals: [String: ActiveInterval] = [:],
        scheduleSelectionIds: [String: String] = [:],
        thresholdSelectionIds: [String: [String]] = [:]
    ) {
        self.manualSelectionId = manualSelectionId
        self.intervals = intervals
        self.scheduleSelectionIds = scheduleSelectionIds
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
    /// selection ids into tokens with `resolve` (nil or empty = shields
    /// nothing, and the source is omitted). Threshold shields need their
    /// interval: a threshold list left behind by a late callback after the
    /// interval was cleared shields nothing.
    static func sources<Token>(
        from records: BlockRecords,
        suspended: SuspendedSources?,
        resolve: (String) -> Set<Token>?
    ) -> [BlockSource<Token>] {
        var sources: [BlockSource<Token>] = []

        if let selectionId = records.manualSelectionId,
           !(suspended?.manual ?? false),
           let tokens = resolve(selectionId), !tokens.isEmpty {
            sources.append(BlockSource(kind: .manual, startedAt: 0, tokens: tokens))
        }

        for (activityName, interval) in records.intervals.sorted(by: { $0.key < $1.key }) {
            if !interval.lifted,
               !(suspended?.suspends(scheduleOf: activityName, startedAt: interval.startedAt) ?? false),
               let selectionId = records.scheduleSelectionIds[activityName],
               let tokens = resolve(selectionId), !tokens.isEmpty {
                sources.append(BlockSource(
                    kind: .schedule(activityName: activityName),
                    startedAt: interval.startedAt,
                    tokens: tokens
                ))
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
}
