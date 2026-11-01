import Foundation
import ManagedSettings
import FamilyControls

/// The only place in the app that touches `ManagedSettingsStore`. Every
/// access runs under `BlockStateLock`, the mutex the app shares with its
/// extensions: the stores are a projection of the App Group state, and the
/// state and the stores must move together — in this process and across the
/// monitor, shield-configuration and shield-action extensions.
///
/// Shields are composed from sources (`BlockComposition`): the manual block,
/// each monitored interval in progress, and the thresholds that fired inside
/// it. Every source has its own named store, written by projecting the App
/// Group state, so ending one source never touches another's shields. Before
/// this, the app and both extensions shared the one unnamed store and any
/// `intervalDidEnd` cleared every shield on the device. The shield snoozes in
/// progress (`ShieldSnooze`) are subtracted by the same projection.
///
/// The unnamed store now only carries the app-control settings.
public class ScreenTimeManager: NSObject {
    private let lock = BlockStateLock.shared
    private let sharedDefaults = SharedDefaults.shared

    /// Serializes the asynchronous half of `refreshShields` off the caller's
    /// thread; the store writes themselves take `lock`.
    private let refreshQueue = DispatchQueue(label: "expo.screentime.refresh", qos: .userInitiated)

    /// The unnamed store: deny-removal / deny-installation. Builds before block
    /// composition also kept every shield here — see `migrateLegacyStore`.
    private lazy var defaultStore = ManagedSettingsStore()

    /// Domains blocked by string. ManagedSettings can't be read back, so the
    /// list is persisted in the App Group: held only in memory it was empty
    /// after a relaunch, and the next `unblockWebDomains` then replaced the
    /// filter with that empty remainder — unblocking every domain.
    private lazy var blockedWebDomains: Set<WebDomain> = Set(
        SharedDefaults.shared.getBlockedWebDomains().map { WebDomain(domain: $0) }
    )

    // MARK: - App control

    public func denyAppRemoval() {
        lock.withLock {
            defaultStore.application.denyAppRemoval = true
        }
    }

    public func allowAppRemoval() {
        lock.withLock {
            defaultStore.application.denyAppRemoval = false
        }
    }

    public func denyAppInstallation() {
        lock.withLock {
            defaultStore.application.denyAppInstallation = true
        }
    }

    public func allowAppInstallation() {
        lock.withLock {
            defaultStore.application.denyAppInstallation = false
        }
    }

    // MARK: - Shields

    /// Project EVERY store from the state: each active source into its
    /// store, every other registered store emptied (and dropped from the
    /// registry, under the same lock — no other process can have filled it in
    /// between). The unlock suspension is part of the state, so a grant is
    /// applied by projecting, never by clearing: a source that started after
    /// the grant captured its sources keeps its shields. Owners of sources no
    /// longer in force are forgotten. `blockSelection`, `unblockAll`,
    /// `temporaryUnlock`, `relock`, migration, the refresh's "on" half.
    public func applyActiveSources() {
        lock.withLock {
            let sources = sharedDefaults.activeBlockSources()
            let shields = BlockComposition.storeShields(of: sources, snoozed: sharedDefaults.snoozedTargets())
            for name in Set(sharedDefaults.shieldStoreNames()).union(shields.keys) {
                write(shields[name] ?? StoreShields(tokens: []), toStoreNamed: name)
            }
            sharedDefaults.pruneShieldOwners(keeping: sources.map(\.kind))
        }
    }

    /// Project ONE activity's store from the state — the store `startMonitoring`
    /// owns; the others are left alone (an `unblock` shield action lifts a
    /// token from them until the next projection that covers them).
    public func applyStore(forActivity activityName: String) {
        lock.withLock {
            let shields = BlockComposition.storeShields(
                of: sharedDefaults.activeBlockSources(),
                snoozed: sharedDefaults.snoozedTargets()
            )
            let name = BlockStore.activity(activityName)
            write(shields[name] ?? StoreShields(tokens: []), toStoreNamed: name)
        }
    }

    public func clearStore(named name: String) {
        lock.withLock {
            write(StoreShields(tokens: []), toStoreNamed: name)
        }
    }

    /// `unblockAll`: the caller has emptied the state (no manual selection,
    /// intervals lifted, thresholds dropped), so the projection empties every
    /// store; the web-content filter and the stored domain list go too.
    public func unblockAll() {
        lock.withLock {
            applyActiveSources()
            blockedWebDomains.removeAll()
            updateWebContentFilter()
        }
    }

    /// Whether any source shields something right now (apps, categories, or
    /// web domains). Reflects the real ManagedSettings state, not the stored
    /// selections; `false` during a temporary unlock.
    public func isBlocked() -> Bool {
        guard #available(iOS 16.0, *) else { return false }
        // Under the lock, so a half-applied projection is never observed.
        return lock.withLock {
            sharedDefaults.shieldStoreNames().contains { Self.hasShields(store(named: $0)) }
        }
    }

    /// Force iOS to reload the shield configuration by toggling the shields
    /// off and on. Call after updating shield configuration via SharedDefaults.
    ///
    /// The "on" half re-projects the CURRENT effective state — the same
    /// projection as `applyActiveSources`, the unlock suspension included —
    /// instead of restoring what was captured before the toggle. Snapshots
    /// guarded by one global generation went wrong both ways: any concurrent
    /// mutation cancelled the restore of unrelated stores, and an interval
    /// the monitor extension ended during the window was resurrected. A
    /// projection another process makes between the two halves is simply
    /// re-made by the "on" half.
    public func refreshShields() {
        refreshQueue.async { [self] in
            let toggledOff: Bool = lock.withLock {
                let shielding = sharedDefaults.shieldStoreNames().filter { Self.hasShields(store(named: $0)) }
                for name in shielding {
                    let store = store(named: name)
                    store.shield.applications = nil
                    store.shield.applicationCategories = nil
                    store.shield.webDomains = nil
                }
                return !shielding.isEmpty
            }
            guard toggledOff else { return }

            // Small delay to ensure iOS processes the change, then write every
            // store from the App Group state as it is THEN.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                self.applyActiveSources()
            }
        }
    }

    /// Builds before block composition kept every shield and the web filter
    /// in the unnamed store, and tracked the schedule in progress only through
    /// one `expo_st_active_activity` pointer. Move them, once, under the lock:
    ///
    /// 1. the pointer becomes that schedule's interval marker (it used to be
    ///    deleted, so an in-progress schedule went unblocked until its next
    ///    start);
    /// 2. the named stores are written from the App Group state and the web
    ///    filter moved to its store, BEFORE the unnamed store is cleared — a
    ///    crash in between leaves both in place and the next launch redoes
    ///    the move;
    /// 3. a done flag is persisted, so this never runs twice.
    ///
    /// Nothing to migrate below iOS 16, where every name maps to the unnamed
    /// store.
    public func migrateLegacyStore() {
        guard #available(iOS 16.0, *) else { return }
        lock.withLock {
            guard !sharedDefaults.isLegacyStoreMigrated() else { return }

            reconstructLegacyActiveInterval()
            applyActiveSources()
            updateWebContentFilter()
            defaultStore.shield.applications = nil
            defaultStore.shield.applicationCategories = nil
            defaultStore.shield.webDomains = nil
            defaultStore.webContent.blockedByFilter = nil
            sharedDefaults.removeLegacyActiveActivity()
            sharedDefaults.setLegacyStoreMigrated()
        }
    }

    /// Builds before block composition tracked the schedule in progress only
    /// through `expo_st_active_activity` — the physical name, `<name>` or
    /// `<name>_weekday_<n>`. Turn it into that activity's interval marker so
    /// the schedule stays a block source, instead of going unblocked until
    /// its next start. Idempotent: an existing marker is kept. Call under
    /// the lock.
    private func reconstructLegacyActiveInterval() {
        let defaults = sharedDefaults
        guard let pointer = defaults.getLegacyActiveActivity() else { return }
        let lookup = { defaults.getMonitoringConfig(activityName: $0) }
        guard let legacy = ActivityRegistration.legacyActiveInterval(
            pointer: pointer, config: lookup, now: Date(), calendar: Calendar.current
        ) else {
            return
        }
        if defaults.getActiveInterval(activityName: legacy.logical) == nil {
            defaults.setActiveInterval(activityName: legacy.logical, startedAt: legacy.startedAt)
        }
        // Legacy builds stored the selection under the variant name only; the
        // parent config still carries the id.
        if defaults.getSelectionId(activityName: legacy.logical) == nil,
           let selectionId = defaults.getSelectionId(activityName: pointer) ?? lookup(legacy.logical)?["selectionId"] as? String {
            defaults.saveSelectionId(activityName: legacy.logical, selectionId: selectionId)
        }
    }

    // MARK: - Web domains

    /// Block specific web domains by their domain strings (e.g., "facebook.com", "twitter.com")
    /// This adds to any existing blocked domains
    public func blockWebDomains(_ domains: [String]) {
        lock.withLock {
            let newDomains = Set(domains.map { WebDomain(domain: $0) })
            blockedWebDomains.formUnion(newDomains)
            updateWebContentFilter()
        }
    }

    /// Unblock specific web domains by their domain strings
    public func unblockWebDomains(_ domains: [String]) {
        lock.withLock {
            let domainsToRemove = Set(domains.map { WebDomain(domain: $0) })
            blockedWebDomains.subtract(domainsToRemove)
            updateWebContentFilter()
        }
    }

    /// Unblock all web domains that were blocked by domain string
    public func unblockAllWebDomains() {
        lock.withLock {
            blockedWebDomains.removeAll()
            updateWebContentFilter()
        }
    }

    /// Get the list of currently blocked domain strings
    public func getBlockedWebDomains() -> [String] {
        return lock.withLock {
            blockedWebDomains.compactMap { $0.domain }
        }
    }

    // MARK: - Private

    /// The store for a source, created per use — a `ManagedSettingsStore` is
    /// a handle on system state, not the state itself, and a cache of handles
    /// keyed by name had nothing to bound it: ends driven by the extensions
    /// never evicted from the app's copy. Named stores need iOS 16 — like
    /// individual authorization — so earlier systems fall back to the single
    /// unnamed store, the pre-composition behaviour. Call under the lock.
    private func store(named name: String) -> ManagedSettingsStore {
        if #available(iOS 16.0, *) {
            return ManagedSettingsStore(named: ManagedSettingsStore.Name(name))
        }
        return defaultStore
    }

    /// Write `tokens` into the store called `name`, keeping the registry in
    /// step: registered before shields go in, unregistered once emptied. Call
    /// under the lock — the registry is pruned only when the projection that
    /// emptied the store ran under it.
    private func write(_ shields: StoreShields<ShieldToken>, toStoreNamed name: String) {
        if shields.tokens.isEmpty {
            store(named: name).setShields(StoreShields(tokens: []))
            sharedDefaults.unregisterShieldStore(name)
        } else {
            sharedDefaults.registerShieldStore(name)
            store(named: name).setShields(shields)
        }
    }

    private static func hasShields(_ store: ManagedSettingsStore) -> Bool {
        return !(store.shield.applications?.isEmpty ?? true) ||
            // `.some(.none)` is a stored policy that shields nothing; comparing
            // the Optional with `.none` means `nil`, so it read as "blocked".
            (store.shield.applicationCategories.map { $0 != ShieldSettings.ActivityCategoryPolicy.none } ?? false) ||
            !(store.shield.webDomains?.isEmpty ?? true)
    }

    /// Apply the domain list to the web store and persist it. Call under the lock.
    private func updateWebContentFilter() {
        sharedDefaults.setBlockedWebDomains(blockedWebDomains.compactMap { $0.domain })
        let store = store(named: BlockStore.web)
        if blockedWebDomains.isEmpty {
            store.webContent.blockedByFilter = nil
        } else {
            store.webContent.blockedByFilter = .specific(blockedWebDomains)
        }
    }
}
