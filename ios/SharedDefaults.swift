import Foundation
import FamilyControls
import ManagedSettings

/// Keys for UserDefaults storage across extensions
enum SharedDefaultsKeys {
    // Monitoring configurations
    static let monitoringPrefix = "expo_st_monitor_"
    static let selectionPrefix = "expo_st_selection_"
    static let eventsPrefix = "expo_st_events_"

    // Shield configuration
    static let shieldConfigDefault = "expo_st_shield_config_default"
    static let shieldConfigPrefix = "expo_st_shield_config_"

    // Shield actions
    static let shieldActionsDefault = "expo_st_shield_actions_default"
    static let shieldActionsPrefix = "expo_st_shield_actions_"

    // Event history
    static let eventHistoryPrefix = "expo_st_event_history_"

    // Domains blocked by string (webContent filter)
    static let blockedWebDomains = "expo_st_blocked_web_domains"

    // Block sources (see BlockComposition)
    /// `<prefix><activityName>` → `{ startedAt: ms, lifted: Bool }` while the
    /// activity's interval is in progress.
    static let activeIntervalPrefix = "expo_st_active_interval_"
    /// `<prefix><activityName>` → `[selectionId]` shielded by `blockOnThreshold`
    /// events that fired inside the current interval.
    static let thresholdSelectionsPrefix = "expo_st_threshold_selections_"
    /// Pre-composition single "active activity" pointer. Removed on launch.
    static let legacyActiveActivity = "expo_st_active_activity"
    /// `SuspendedSources` of the temporary unlock in progress (see
    /// BlockComposition) — what every projection must leave unshielded.
    static let unlockSuspended = "expo_st_unlock_suspended"
    /// `<prefix><storeName>` → true while that named `ManagedSettingsStore`
    /// may hold shields. Written by whichever process writes the store, so
    /// every process can enumerate the stores actually in use.
    static let shieldStorePrefix = "expo_st_shield_store_"
    /// The `ShieldOwnerMap`: token key → the owner the shield configuration
    /// extension resolved for a shield over that token, for the action
    /// extension. One bounded record (see BlockComposition).
    static let shieldOwners = "expo_st_shield_owners"
    /// Pre-map layout, one key per token, unbounded. Removed at launch.
    static let legacyShieldOwnerPrefix = "expo_st_shield_owner_"
    /// The `ShieldSnooze`s in progress (see BlockComposition): the targets
    /// every projection leaves unshielded until their usage budget is spent.
    static let snoozes = "expo_st_snoozes"

    // Shield interactions (see ShieldInteractionQueue)
    /// JSON array of the presses not yet drained, oldest first.
    static let shieldInteractions = "expo_st_shield_interactions"
    /// Target key → Unix ms of the last recorded press on it (the 2 s
    /// per-target collapse; kept across drains, pruned to the window).
    static let shieldInteractionLastByTarget = "expo_st_shield_interaction_last_by_target"
    /// The one global "last press" of builds with a global collapse. Removed
    /// on the next write.
    static let legacyShieldInteractionLast = "expo_st_shield_interaction_last"
}

extension BlockStateLock {
    /// The mutex over the block state, shared by the app and its extensions:
    /// a file in the App Group container (see BlockStateLock).
    static let shared = BlockStateLock.inAppGroupContainer(SharedDefaults.shared.getAppGroup())
}

/// A token of any kind, so a selection's applications, categories and web
/// domains can be handled as one set (`BlockSource.tokens`).
enum ShieldToken: Hashable {
    case application(ApplicationToken)
    case category(ActivityCategoryToken)
    case webDomain(WebDomainToken)
}

extension ShieldToken {
    /// The token's kind and encoded form (base64), stable across processes
    /// and launches — what a `ShieldSnooze` persists. Tokens are opaque, but
    /// their encoding is stable (it is what `SelectionStore` persists). The
    /// encoded form is also the token's JS handle (`SelectionStore
    /// .stableHandle`), which is what makes a handle self-describing.
    var encoded: (kind: String, token: String)? {
        let data: Data?
        let kind: String
        switch self {
        case .application(let token):
            data = try? JSONEncoder().encode(token)
            kind = "application"
        case .category(let token):
            data = try? JSONEncoder().encode(token)
            kind = "category"
        case .webDomain(let token):
            data = try? JSONEncoder().encode(token)
            kind = "webDomain"
        }
        return data.map { (kind, $0.base64EncodedString()) }
    }

    /// The token persisted as `encoded`; `nil` for a kind or an encoding
    /// this build cannot read (the snooze is then void — fail closed).
    init?(kind: String, encoded token: String) {
        guard let data = Data(base64Encoded: token) else { return nil }
        switch kind {
        case "application":
            guard let token = try? JSONDecoder().decode(ApplicationToken.self, from: data) else { return nil }
            self = .application(token)
        case "category":
            guard let token = try? JSONDecoder().decode(ActivityCategoryToken.self, from: data) else { return nil }
            self = .category(token)
        case "webDomain":
            guard let token = try? JSONDecoder().decode(WebDomainToken.self, from: data) else { return nil }
            self = .webDomain(token)
        default:
            return nil
        }
    }

    /// A key for this token that is stable across processes and launches:
    /// the hash of its encoded form.
    var persistentKey: String? {
        return encoded.map { BlockStore.fnv1a($0.token) }
    }
}

extension ManagedSettingsStore {
    /// Put exactly `shields` in this store: its applications, its
    /// categories — with the snoozed applications excepted, so an app
    /// shielded through its category is lifted by its snooze too — and its
    /// web domains; empty sets clear the setting. The one writer of shield
    /// settings for the app and the extensions. Call under `BlockStateLock`.
    func setShields(_ shields: StoreShields<ShieldToken>) {
        let applications = shields.tokens.applicationTokens
        let categories = shields.tokens.categoryTokens
        let webDomains = shields.tokens.webDomainTokens
        shield.applications = applications.isEmpty ? nil : applications
        shield.applicationCategories = categories.isEmpty
            ? nil
            : .specific(categories, except: shields.excepted.applicationTokens)
        shield.webDomains = webDomains.isEmpty ? nil : webDomains
    }
}

extension FamilyActivitySelection {
    var shieldTokens: Set<ShieldToken> {
        var tokens = Set<ShieldToken>()
        for token in applicationTokens { tokens.insert(.application(token)) }
        for token in categoryTokens { tokens.insert(.category(token)) }
        for token in webDomainTokens { tokens.insert(.webDomain(token)) }
        return tokens
    }
}

extension Set where Element == ShieldToken {
    /// The three ManagedSettings sets a store (or a usage event) is written from.
    var applicationTokens: Set<ApplicationToken> {
        return Set<ApplicationToken>(compactMap { element -> ApplicationToken? in
            if case .application(let token) = element { return token }
            return nil
        })
    }

    var categoryTokens: Set<ActivityCategoryToken> {
        return Set<ActivityCategoryToken>(compactMap { element -> ActivityCategoryToken? in
            if case .category(let token) = element { return token }
            return nil
        })
    }

    var webDomainTokens: Set<WebDomainToken> {
        return Set<WebDomainToken>(compactMap { element -> WebDomainToken? in
            if case .webDomain(let token) = element { return token }
            return nil
        })
    }
}

/// Shared utilities for accessing App Group UserDefaults
class SharedDefaults {
    static let shared = SharedDefaults()

    private var defaults: UserDefaults?
    private var appGroup: String?

    private init() {
        // Try to find the app group from bundle identifier
        if let bundleId = Bundle.main.bundleIdentifier {
            // For extensions, bundleId includes the extension suffix
            // e.g., "com.example.app.DeviceActivityMonitorExtension"
            // We need to get the base app bundle identifier
            let baseBundleId = bundleId
                // @bacons/apple-targets naming convention
                .replacingOccurrences(of: ".DeviceActivityMonitorExtension", with: "")
                .replacingOccurrences(of: ".ShieldConfigurationExtension", with: "")
                .replacingOccurrences(of: ".ShieldActionExtension", with: "")
                .replacingOccurrences(of: ".DeviceActivityReportExtension", with: "")
                // Alternative naming conventions (kebab-case); `shield-config` is
                // the slug the config plugin generates.
                .replacingOccurrences(of: ".device-activity-monitor", with: "")
                .replacingOccurrences(of: ".shield-configuration", with: "")
                .replacingOccurrences(of: ".shield-config", with: "")
                .replacingOccurrences(of: ".shield-action", with: "")
                .replacingOccurrences(of: ".device-activity-report", with: "")

            appGroup = "group.\(baseBundleId)"
            defaults = UserDefaults(suiteName: appGroup)

            #if DEBUG
            print("[SharedDefaults] Bundle ID: \(bundleId)")
            print("[SharedDefaults] Base Bundle ID: \(baseBundleId)")
            print("[SharedDefaults] App Group: \(appGroup ?? "nil")")
            print("[SharedDefaults] UserDefaults initialized: \(defaults != nil)")
            #endif
        }
    }

    func getAppGroup() -> String? {
        return appGroup
    }

    // MARK: - Monitoring Configuration

    func saveMonitoringConfig(activityName: String, config: [String: Any]) {
        let key = SharedDefaultsKeys.monitoringPrefix + activityName
        defaults?.set(config, forKey: key)
        defaults?.synchronize()
    }

    func getMonitoringConfig(activityName: String) -> [String: Any]? {
        let key = SharedDefaultsKeys.monitoringPrefix + activityName
        return defaults?.dictionary(forKey: key)
    }

    func removeMonitoringConfig(activityName: String) {
        let key = SharedDefaultsKeys.monitoringPrefix + activityName
        defaults?.removeObject(forKey: key)
        defaults?.synchronize()
    }

    func getAllActivityNames() -> [String] {
        return names(withPrefix: SharedDefaultsKeys.monitoringPrefix)
    }

    // MARK: - Selection ID Storage (for monitoring → extension communication)

    func saveSelectionId(activityName: String, selectionId: String) {
        let key = SharedDefaultsKeys.selectionPrefix + activityName
        defaults?.set(selectionId, forKey: key)
        defaults?.synchronize()
    }

    func getSelectionId(activityName: String) -> String? {
        let key = SharedDefaultsKeys.selectionPrefix + activityName
        return defaults?.string(forKey: key)
    }

    func removeSelectionId(activityName: String) {
        let key = SharedDefaultsKeys.selectionPrefix + activityName
        defaults?.removeObject(forKey: key)
        defaults?.synchronize()
    }

    // MARK: - Shield Configuration

    func saveDefaultShieldConfig(_ config: [String: Any]) {
        defaults?.set(config, forKey: SharedDefaultsKeys.shieldConfigDefault)
        defaults?.synchronize()
    }

    func getDefaultShieldConfig() -> [String: Any]? {
        return defaults?.dictionary(forKey: SharedDefaultsKeys.shieldConfigDefault)
    }

    func saveShieldConfig(activityName: String, config: [String: Any]) {
        let key = SharedDefaultsKeys.shieldConfigPrefix + activityName
        defaults?.set(config, forKey: key)
        defaults?.synchronize()
    }

    func getShieldConfig(activityName: String) -> [String: Any]? {
        let key = SharedDefaultsKeys.shieldConfigPrefix + activityName
        return defaults?.dictionary(forKey: key)
    }

    func clearShieldConfigs() {
        defaults?.removeObject(forKey: SharedDefaultsKeys.shieldConfigDefault)
        if let allKeys = defaults?.dictionaryRepresentation().keys {
            for key in allKeys where key.hasPrefix(SharedDefaultsKeys.shieldConfigPrefix) {
                defaults?.removeObject(forKey: key)
            }
        }
        defaults?.synchronize()
    }

    // MARK: - Shield Actions

    func saveDefaultShieldActions(_ config: [String: Any]) {
        defaults?.set(config, forKey: SharedDefaultsKeys.shieldActionsDefault)
        defaults?.synchronize()
    }

    func getDefaultShieldActions() -> [String: Any]? {
        return defaults?.dictionary(forKey: SharedDefaultsKeys.shieldActionsDefault)
    }

    func saveShieldActions(activityName: String, config: [String: Any]) {
        let key = SharedDefaultsKeys.shieldActionsPrefix + activityName
        defaults?.set(config, forKey: key)
        defaults?.synchronize()
    }

    func getShieldActions(activityName: String) -> [String: Any]? {
        let key = SharedDefaultsKeys.shieldActionsPrefix + activityName
        return defaults?.dictionary(forKey: key)
    }

    func clearShieldActions() {
        defaults?.removeObject(forKey: SharedDefaultsKeys.shieldActionsDefault)
        if let allKeys = defaults?.dictionaryRepresentation().keys {
            for key in allKeys where key.hasPrefix(SharedDefaultsKeys.shieldActionsPrefix) {
                defaults?.removeObject(forKey: key)
            }
        }
        defaults?.synchronize()
    }

    // MARK: - Event History

    /// Appends under `BlockStateLock`: the app (a schedule registered inside
    /// its occurrence) and the monitor extension both append to this log, and
    /// an unlocked read-append-write from each process loses one of the events.
    func saveEvent(activityName: String, event: [String: Any]) {
        BlockStateLock.shared.withLock { appendEvent(activityName: activityName, event: event) }
    }

    private func appendEvent(activityName: String, event: [String: Any]) {
        let key = SharedDefaultsKeys.eventHistoryPrefix + activityName
        var events = defaults?.array(forKey: key) as? [[String: Any]] ?? []
        // A unique id lets the module emit every event exactly once, even
        // when several share a millisecond timestamp (MonitorEventCursor).
        var event = event
        if event["id"] == nil {
            event["id"] = UUID().uuidString
        }
        events.append(event)

        // Keep only last 1000 events per activity
        if events.count > 1000 {
            events = Array(events.suffix(1000))
        }

        defaults?.set(events, forKey: key)
        defaults?.synchronize()
    }

    /// Record a monitor event (`intervalDidStart`, `intervalDidEnd`, …) the
    /// same way the monitor extension does, with the current time.
    func recordEvent(activityName: String, eventType: String, eventName: String? = nil) {
        var event: [String: Any] = [
            "activityName": activityName,
            "eventType": eventType,
            "timestamp": Date().timeIntervalSince1970 * 1000
        ]
        if let eventName = eventName {
            event["eventName"] = eventName
        }
        saveEvent(activityName: activityName, event: event)
    }

    func getEvents(activityName: String, limit: Int = 100) -> [[String: Any]] {
        let key = SharedDefaultsKeys.eventHistoryPrefix + activityName
        let events = defaults?.array(forKey: key) as? [[String: Any]] ?? []
        return Array(events.suffix(limit))
    }

    func getAllEvents(limit: Int = 100) -> [[String: Any]] {
        var allEvents: [[String: Any]] = []

        if let allKeys = defaults?.dictionaryRepresentation().keys {
            for key in allKeys where key.hasPrefix(SharedDefaultsKeys.eventHistoryPrefix) {
                if let events = defaults?.array(forKey: key) as? [[String: Any]] {
                    allEvents.append(contentsOf: events)
                }
            }
        }

        // Sort by timestamp descending (newest first)
        allEvents.sort { (e1, e2) -> Bool in
            let t1 = e1["timestamp"] as? Double ?? 0
            let t2 = e2["timestamp"] as? Double ?? 0
            return t1 > t2
        }

        return Array(allEvents.prefix(limit))
    }

    func clearEvents(activityName: String? = nil) {
        BlockStateLock.shared.withLock { removeEvents(activityName: activityName) }
    }

    private func removeEvents(activityName: String?) {
        if let activityName = activityName {
            let key = SharedDefaultsKeys.eventHistoryPrefix + activityName
            defaults?.removeObject(forKey: key)
        } else {
            if let allKeys = defaults?.dictionaryRepresentation().keys {
                for key in allKeys where key.hasPrefix(SharedDefaultsKeys.eventHistoryPrefix) {
                    defaults?.removeObject(forKey: key)
                }
            }
        }
        defaults?.synchronize()
    }

    // MARK: - Blocked Web Domains

    func getBlockedWebDomains() -> [String] {
        return defaults?.stringArray(forKey: SharedDefaultsKeys.blockedWebDomains) ?? []
    }

    func setBlockedWebDomains(_ domains: [String]) {
        defaults?.set(domains.sorted(), forKey: SharedDefaultsKeys.blockedWebDomains)
        defaults?.synchronize()
    }

    // MARK: - Block sources
    //
    // Which sources shield something right now, visible to every process. The
    // monitor extension writes interval markers (`intervalDidStart`) and
    // threshold lists (`blockOnThreshold`), and clears both at
    // `intervalDidEnd`; the module writes the manual selection, and an
    // interval marker when it applies a block synchronously. Each source is
    // projected into its own named store — see BlockComposition.

    /// Mark `activityName`'s interval as in progress since `startedAt` (ms).
    func setActiveInterval(activityName: String, startedAt: Double, lifted: Bool = false) {
        let key = SharedDefaultsKeys.activeIntervalPrefix + activityName
        defaults?.set(["startedAt": startedAt, "lifted": lifted], forKey: key)
        defaults?.synchronize()
    }

    func getActiveInterval(activityName: String) -> ActiveInterval? {
        let key = SharedDefaultsKeys.activeIntervalPrefix + activityName
        guard let marker = defaults?.dictionary(forKey: key),
              let startedAt = marker["startedAt"] as? Double else {
            return nil
        }
        return ActiveInterval(startedAt: startedAt, lifted: marker["lifted"] as? Bool ?? false)
    }

    /// Activities whose interval is in progress (lifted ones included).
    func getActiveIntervalNames() -> [String] {
        return names(withPrefix: SharedDefaultsKeys.activeIntervalPrefix)
    }

    /// The interval ended (or monitoring stopped): forget it and the
    /// thresholds that fired inside it.
    func clearActiveInterval(activityName: String) {
        defaults?.removeObject(forKey: SharedDefaultsKeys.activeIntervalPrefix + activityName)
        defaults?.removeObject(forKey: SharedDefaultsKeys.thresholdSelectionsPrefix + activityName)
        defaults?.synchronize()
    }

    /// `unblockAll`: every interval in progress stays in progress (its
    /// `intervalDidEnd` is still recorded) but shields nothing until its next
    /// start. Threshold shields are dropped for good — a threshold cannot fire
    /// twice in one interval.
    func liftActiveIntervals() {
        for activityName in getActiveIntervalNames() {
            if let interval = getActiveInterval(activityName: activityName) {
                setActiveInterval(activityName: activityName, startedAt: interval.startedAt, lifted: true)
            }
        }
        for activityName in names(withPrefix: SharedDefaultsKeys.thresholdSelectionsPrefix) {
            defaults?.removeObject(forKey: SharedDefaultsKeys.thresholdSelectionsPrefix + activityName)
        }
        defaults?.synchronize()
    }

    /// A `blockOnThreshold` event fired: shield `selectionId` until the interval ends.
    func appendThresholdSelection(activityName: String, selectionId: String) {
        let key = SharedDefaultsKeys.thresholdSelectionsPrefix + activityName
        var selectionIds = defaults?.stringArray(forKey: key) ?? []
        if !selectionIds.contains(selectionId) {
            selectionIds.append(selectionId)
        }
        defaults?.set(selectionIds, forKey: key)
        defaults?.synchronize()
    }

    func getThresholdSelectionIds(activityName: String) -> [String] {
        return defaults?.stringArray(forKey: SharedDefaultsKeys.thresholdSelectionsPrefix + activityName) ?? []
    }

    func clearThresholdSelections(activityName: String) {
        defaults?.removeObject(forKey: SharedDefaultsKeys.thresholdSelectionsPrefix + activityName)
        defaults?.synchronize()
    }

    /// Put a threshold list back (rolling a failed registration back).
    func setThresholdSelectionIds(activityName: String, selectionIds: [String]) {
        let key = SharedDefaultsKeys.thresholdSelectionsPrefix + activityName
        if selectionIds.isEmpty {
            defaults?.removeObject(forKey: key)
        } else {
            defaults?.set(selectionIds, forKey: key)
        }
        defaults?.synchronize()
    }

    /// The records every process composes the block sources from.
    func blockRecords() -> BlockRecords {
        var records = BlockRecords(manualSelectionId: getBlockedSelectionId())
        for activityName in getActiveIntervalNames() {
            if let interval = getActiveInterval(activityName: activityName) {
                records.intervals[activityName] = interval
            }
            if let selectionId = getSelectionId(activityName: activityName) {
                records.scheduleSelectionIds[activityName] = selectionId
            }
        }
        for activityName in names(withPrefix: SharedDefaultsKeys.thresholdSelectionsPrefix) {
            let selectionIds = getThresholdSelectionIds(activityName: activityName)
            if !selectionIds.isEmpty {
                records.thresholdSelectionIds[activityName] = selectionIds
            }
        }
        return records
    }

    /// Every source that shields something right now: the manual block, each
    /// interval in progress that was not lifted, and the thresholds that fired
    /// inside those intervals — minus what the temporary unlock in progress
    /// suspended, unless `honouringSuspension` is false (what a relock or a
    /// renewal restores). Selections are resolved here so callers work with
    /// tokens. Sources with nothing to shield are omitted. Read it under
    /// `BlockStateLock`, in the critical section that acts on it.
    func activeBlockSources(honouringSuspension: Bool = true) -> [BlockSource<ShieldToken>] {
        return BlockComposition.sources(
            from: blockRecords(),
            suspended: honouringSuspension ? getSuspendedSources() : nil,
            resolve: { SelectionStore.shared.get($0)?.shieldTokens }
        )
    }

    // MARK: - Legacy store migration

    /// The pre-composition "active activity" pointer: the physical name of
    /// the schedule a legacy build had in progress.
    func getLegacyActiveActivity() -> String? {
        return defaults?.string(forKey: SharedDefaultsKeys.legacyActiveActivity)
    }

    /// Drop the pre-composition "active activity" pointer, which no longer
    /// drives anything.
    func removeLegacyActiveActivity() {
        defaults?.removeObject(forKey: SharedDefaultsKeys.legacyActiveActivity)
        defaults?.synchronize()
    }

    /// Whether the one-time move out of the unnamed store has completed.
    func isLegacyStoreMigrated() -> Bool {
        return (defaults?.integer(forKey: "expo_st_store_migration") ?? 0) >= 1
    }

    func setLegacyStoreMigrated() {
        defaults?.set(1, forKey: "expo_st_store_migration")
        defaults?.synchronize()
    }

    /// Activity names found after `prefix` in the suite's keys.
    private func names(withPrefix prefix: String) -> [String] {
        var names = Set<String>()
        if let allKeys = defaults?.dictionaryRepresentation().keys {
            for key in allKeys where key.hasPrefix(prefix) {
                names.insert(String(key.dropFirst(prefix.count)))
            }
        }
        return Array(names).sorted()
    }

    // MARK: - Registration generations (see ActivityRegistration)

    /// A fresh generation for a registration. One counter for every activity,
    /// never reset — a stopped activity's generation must never come back, or
    /// the stale callbacks of its old registration would match a new one.
    /// Only the app registers activities, so there is one writer.
    func nextRegistrationGeneration() -> Int {
        let next = (defaults?.integer(forKey: "expo_st_registration_counter") ?? 0) + 1
        defaults?.set(next, forKey: "expo_st_registration_counter")
        defaults?.synchronize()
        return next
    }

    // MARK: - Shield owner handoff
    //
    // iOS asks the configuration extension for a shield with the app AND
    // its category, but tells the action extension about one of them. Each
    // resolves the owning source by token membership, so they could disagree
    // for an app shielded through its category. The configuration step
    // records the owner it resolved (`ShieldOwnerMap`, under the tokens
    // `BlockComposition.handoffTargets` picks); the action step reuses it
    // while that source is still active. Read and written under
    // `BlockStateLock` only.

    func getShieldOwnerMap() -> ShieldOwnerMap {
        return ShieldOwnerMap(dictionary: defaults?.dictionary(forKey: SharedDefaultsKeys.shieldOwners) ?? [:])
    }

    func setShieldOwnerMap(_ map: ShieldOwnerMap) {
        if map.entries.isEmpty {
            defaults?.removeObject(forKey: SharedDefaultsKeys.shieldOwners)
        } else {
            defaults?.set(map.dictionary, forKey: SharedDefaultsKeys.shieldOwners)
        }
        defaults?.synchronize()
    }

    /// The activity's sources ended (interval end, stop): its owners go.
    func pruneShieldOwners(ofActivity activityName: String) {
        var map = getShieldOwnerMap()
        guard !map.entries.isEmpty else { return }
        map.prune(keeping: { $0.activityName != activityName })
        setShieldOwnerMap(map)
    }

    /// After a full projection: only the sources in force may own a shield.
    func pruneShieldOwners(keeping kinds: [BlockSourceKind]) {
        var map = getShieldOwnerMap()
        guard !map.entries.isEmpty else { return }
        map.prune(keeping: { kinds.contains($0) })
        setShieldOwnerMap(map)
    }

    /// `unblockAll`: nothing owns anything.
    func clearShieldOwners() {
        setShieldOwnerMap(ShieldOwnerMap())
    }

    /// Drop the per-token owner keys of builds before the map.
    func removeLegacyShieldOwnerKeys() {
        guard let allKeys = defaults?.dictionaryRepresentation().keys else { return }
        var removed = false
        for key in allKeys where key.hasPrefix(SharedDefaultsKeys.legacyShieldOwnerPrefix) {
            defaults?.removeObject(forKey: key)
            removed = true
        }
        if removed {
            defaults?.synchronize()
        }
    }

    // MARK: - Shield store registry
    //
    // Which named stores may hold shields. Enumerating them from the
    // monitoring configs missed a store written by a late threshold callback
    // after its activity was stopped — `isBlocked()` and `unblockAll()` never
    // saw it. One key per store (not one array), and every write under
    // `BlockStateLock`.

    /// Call BEFORE writing shields into `storeName`.
    func registerShieldStore(_ storeName: String) {
        defaults?.set(true, forKey: SharedDefaultsKeys.shieldStorePrefix + storeName)
        defaults?.synchronize()
    }

    /// Call AFTER emptying `storeName`.
    func unregisterShieldStore(_ storeName: String) {
        defaults?.removeObject(forKey: SharedDefaultsKeys.shieldStorePrefix + storeName)
        defaults?.synchronize()
    }

    /// Every store that may hold shields right now, sorted.
    ///
    /// An entry is removed only under `BlockStateLock`, right after the
    /// recompute that emptied the store: with every writer serialized, no
    /// other process can have filled the store between the two.
    func shieldStoreNames() -> [String] {
        return names(withPrefix: SharedDefaultsKeys.shieldStorePrefix)
    }

    // MARK: - Temporary Unlock (earned time)
    //
    // A temporary unlock is a budget of SECONDS spent only while a blocked app
    // is actually in the foreground (measured by DeviceActivity usage
    // thresholds, not wall-clock). The module grants and arms it; the monitor
    // extension records consumption and re-applies the shield when the budget
    // is spent. All state lives here so both processes see the same numbers.

    /// The selection of the manual block (`blockSelection`) — one block source.
    func setBlockedSelectionId(_ selectionId: String?) {
        if let selectionId = selectionId {
            defaults?.set(selectionId, forKey: "expo_st_blocked_selection_id")
        } else {
            defaults?.removeObject(forKey: "expo_st_blocked_selection_id")
        }
        defaults?.synchronize()
    }

    func getBlockedSelectionId() -> String? {
        return defaults?.string(forKey: "expo_st_blocked_selection_id")
    }

    /// Grant `budgetSeconds` from `grantedAt`, suspending `suspended` — the
    /// sources shielding at that moment — until relock or expiry.
    func setUnlockState(budgetSeconds: Int, grantedAt: Date, suspended: SuspendedSources) {
        defaults?.set(budgetSeconds, forKey: "expo_st_unlock_budget")
        defaults?.set(0, forKey: "expo_st_unlock_consumed")
        defaults?.set(grantedAt.timeIntervalSince1970, forKey: "expo_st_unlock_granted_at")
        defaults?.set(suspended.dictionary, forKey: SharedDefaultsKeys.unlockSuspended)
        defaults?.synchronize()
    }

    /// What the unlock in progress suspended; nil when no grant is active. A
    /// grant from a previous calendar day is expired (unspent budget never
    /// carries across midnight), so its suspension is void even before the
    /// app or the monitor clears it.
    func getSuspendedSources() -> SuspendedSources? {
        guard getUnlockBudgetSeconds() > 0,
              let grantedAt = getUnlockGrantedAt(),
              Calendar.current.isDate(grantedAt, inSameDayAs: Date()),
              let dictionary = defaults?.dictionary(forKey: SharedDefaultsKeys.unlockSuspended) else {
            return nil
        }
        return SuspendedSources(dictionary: dictionary)
    }

    func getUnlockBudgetSeconds() -> Int {
        return defaults?.integer(forKey: "expo_st_unlock_budget") ?? 0
    }

    func getUnlockConsumedSeconds() -> Int {
        return defaults?.integer(forKey: "expo_st_unlock_consumed") ?? 0
    }

    func setUnlockConsumedSeconds(_ seconds: Int) {
        defaults?.set(seconds, forKey: "expo_st_unlock_consumed")
        defaults?.synchronize()
    }

    func getUnlockGrantedAt() -> Date? {
        guard let epoch = defaults?.object(forKey: "expo_st_unlock_granted_at") as? Double else { return nil }
        return Date(timeIntervalSince1970: epoch)
    }

    func clearUnlockState() {
        defaults?.removeObject(forKey: "expo_st_unlock_budget")
        defaults?.removeObject(forKey: "expo_st_unlock_consumed")
        defaults?.removeObject(forKey: "expo_st_unlock_granted_at")
        defaults?.removeObject(forKey: SharedDefaultsKeys.unlockSuspended)
        defaults?.synchronize()
    }

    // MARK: - Shield snoozes
    //
    // The `ShieldSnooze`s in progress, one per target the user pressed a
    // snooze button for. Written by the shield action extension (grant), the
    // monitor extension (consumption, spent, day end) and the app (ended by
    // `blockSelection`, `unblockAll`, `relock`, or reconciled at launch);
    // read by every projection. Only under `BlockStateLock`.

    /// Every persisted snooze, expired ones included (`currentSnoozes` is
    /// what the projection uses).
    func getSnoozes() -> [ShieldSnooze] {
        let dictionaries = defaults?.array(forKey: SharedDefaultsKeys.snoozes) as? [[String: Any]] ?? []
        return dictionaries.compactMap(ShieldSnooze.init(dictionary:))
    }

    func setSnoozes(_ snoozes: [ShieldSnooze]) {
        if snoozes.isEmpty {
            defaults?.removeObject(forKey: SharedDefaultsKeys.snoozes)
        } else {
            defaults?.set(snoozes.map(\.dictionary), forKey: SharedDefaultsKeys.snoozes)
        }
        defaults?.synchronize()
    }

    /// The snoozes in force right now — granted today and not spent.
    func currentSnoozes() -> [ShieldSnooze] {
        return ShieldSnoozes.current(getSnoozes(), now: Date(), calendar: Calendar.current)
    }

    /// The tokens the snoozes in force lift — what every projection
    /// subtracts (`BlockComposition.storeShields`). A snooze whose token
    /// this build cannot rebuild lifts nothing.
    func snoozedTargets() -> Set<ShieldToken> {
        return Set(currentSnoozes().compactMap { ShieldToken(kind: $0.tokenKind, encoded: $0.token) })
    }

    /// Forget every snooze and return the meters to stop — AFTER this, by
    /// the caller, outside the lock: the stop's `intervalDidEnd` then finds
    /// no snooze and does nothing.
    func takeSnoozes() -> [String] {
        let names = getSnoozes().map(\.activityName)
        setSnoozes([])
        return names
    }

    // MARK: - Shield interactions
    //
    // One entry per shield button press the user made, with what was
    // shielded and which activity shields it (`ShieldInteractionQueue`).
    // Written by the ShieldAction extension (the configuration data source
    // is cached by the system, so the action handler is the only callback
    // that reliably fires per open) and drained by the app to power "you
    // were blocked N times" UIs. Presses on the same target within 2 s
    // collapse into one; presses on different targets do not.

    /// Under `BlockStateLock` (re-entrant: the extension records inside the
    /// critical section that resolved the owner): the ShieldAction extension
    /// appends while the app may be draining; unlocked, an append between
    /// the drain's read and its remove is lost.
    ///
    /// `target` is the pressed token; its handle is derived exactly as
    /// `SelectionStore.getTokenHandles` derives handles, so JS can hand it
    /// to `<TokenLabel>`. `activityName` is the logical name of the activity
    /// whose source owns the shield, nil for the manual block.
    func appendShieldInteraction(button: String, target: ShieldToken?, activityName: String?) {
        BlockStateLock.shared.withLock {
            let entry = ShieldInteractionQueue.Entry(
                button: button,
                timestamp: Date().timeIntervalSince1970 * 1000,
                target: target?.encoded.map { ShieldInteractionQueue.Target(type: $0.kind, handle: $0.token) },
                activityName: activityName
            )
            var state = shieldInteractionState()
            state.record(entry)
            setShieldInteractionState(state)
        }
    }

    /// Read-and-clear, in the `ShieldInteraction` shape (legacy entries
    /// without a target drain with `target` and `activityName` null).
    func drainShieldInteractions() -> [[String: Any]] {
        return BlockStateLock.shared.withLock {
            var state = shieldInteractionState()
            let interactions = state.drain()
            if !interactions.isEmpty {
                setShieldInteractionState(state)
            }
            return interactions
        }
    }

    private func shieldInteractionState() -> ShieldInteractionQueue.State {
        guard let defaults = defaults else { return ShieldInteractionQueue.State() }
        // Re-read the suite: the writes come from a separate extension process.
        defaults.synchronize()
        return ShieldInteractionQueue.State(
            entries: ShieldInteractionQueue.entries(fromJSON: defaults.string(forKey: SharedDefaultsKeys.shieldInteractions)),
            lastPressByTarget: defaults.dictionary(forKey: SharedDefaultsKeys.shieldInteractionLastByTarget) as? [String: Double] ?? [:]
        )
    }

    private func setShieldInteractionState(_ state: ShieldInteractionQueue.State) {
        guard let defaults = defaults else { return }
        if let json = ShieldInteractionQueue.json(of: state.entries) {
            defaults.set(json, forKey: SharedDefaultsKeys.shieldInteractions)
        } else {
            defaults.removeObject(forKey: SharedDefaultsKeys.shieldInteractions)
        }
        if state.lastPressByTarget.isEmpty {
            defaults.removeObject(forKey: SharedDefaultsKeys.shieldInteractionLastByTarget)
        } else {
            defaults.set(state.lastPressByTarget, forKey: SharedDefaultsKeys.shieldInteractionLastByTarget)
        }
        // The global collapse record of earlier builds drives nothing now.
        defaults.removeObject(forKey: SharedDefaultsKeys.legacyShieldInteractionLast)
        defaults.synchronize()
    }
}
