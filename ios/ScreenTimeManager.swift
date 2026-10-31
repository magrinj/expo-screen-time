import ManagedSettings
import FamilyControls

public class ScreenTimeManager: NSObject {
    private let queue = DispatchQueue(label: "expo.screentime.manager", qos: .userInitiated)
    private var _managedSettingsStore: ManagedSettingsStore?

    /// Domains blocked by string. ManagedSettings can't be read back, so the
    /// list is persisted in the App Group: held only in memory it was empty
    /// after a relaunch, and the next `unblockWebDomains` then replaced the
    /// filter with that empty remainder — unblocking every domain.
    private lazy var blockedWebDomains: Set<WebDomain> = Set(
        SharedDefaults.shared.getBlockedWebDomains().map { WebDomain(domain: $0) }
    )

    /// Bumped (on `queue`) by every shield mutation
    private var shieldGeneration: UInt64 = 0

    private var managedSettingsStore: ManagedSettingsStore {
        if _managedSettingsStore == nil {
            _managedSettingsStore = ManagedSettingsStore()
        }
        return _managedSettingsStore!
    }

    public func denyAppRemoval() {
        queue.sync {
            managedSettingsStore.application.denyAppRemoval = true
        }
    }

    public func allowAppRemoval() {
        queue.sync {
            managedSettingsStore.application.denyAppRemoval = false
        }
    }

    public func denyAppInstallation() {
        queue.sync {
            managedSettingsStore.application.denyAppInstallation = true
        }
    }

    public func allowAppInstallation() {
        queue.sync {
            managedSettingsStore.application.denyAppInstallation = false
        }
    }

    public func blockSelection(_ selection: FamilyActivitySelection) {
        queue.sync {
            shieldGeneration += 1
            managedSettingsStore.shield.applicationCategories = ShieldSettings.ActivityCategoryPolicy.specific(selection.categoryTokens)
            managedSettingsStore.shield.applications = selection.applicationTokens
            managedSettingsStore.shield.webDomains = selection.webDomainTokens
        }
    }

    /// Drop the shields WITHOUT touching the web-content filter or the stored
    /// domain list — the temporary unlock suspends and later restores exactly
    /// what `blockSelection` applied, nothing more.
    public func suspendShields() {
        queue.sync {
            shieldGeneration += 1
            managedSettingsStore.shield.applicationCategories = ShieldSettings.ActivityCategoryPolicy.none
            managedSettingsStore.shield.applications = Set()
            managedSettingsStore.shield.webDomains = Set()
        }
    }

    public func unblockAll() {
        queue.sync {
            shieldGeneration += 1
            managedSettingsStore.shield.applicationCategories = ShieldSettings.ActivityCategoryPolicy.none
            managedSettingsStore.shield.applications = Set()
            managedSettingsStore.shield.webDomains = Set()
            blockedWebDomains.removeAll()
            updateWebContentFilter()
        }
    }

    /// Block specific web domains by their domain strings (e.g., "facebook.com", "twitter.com")
    /// This adds to any existing blocked domains
    public func blockWebDomains(_ domains: [String]) {
        queue.sync {
            let newDomains = Set(domains.map { WebDomain(domain: $0) })
            blockedWebDomains.formUnion(newDomains)
            updateWebContentFilter()
        }
    }

    /// Unblock specific web domains by their domain strings
    public func unblockWebDomains(_ domains: [String]) {
        queue.sync {
            let domainsToRemove = Set(domains.map { WebDomain(domain: $0) })
            blockedWebDomains.subtract(domainsToRemove)
            updateWebContentFilter()
        }
    }

    /// Unblock all web domains that were blocked by domain string
    public func unblockAllWebDomains() {
        queue.sync {
            blockedWebDomains.removeAll()
            updateWebContentFilter()
        }
    }

    /// Get the list of currently blocked domain strings
    public func getBlockedWebDomains() -> [String] {
        return queue.sync {
            return blockedWebDomains.compactMap { $0.domain }
        }
    }

    /// Whether anything is currently shielded (apps, categories, or web domains).
    /// Reflects the real ManagedSettings state, not the stored selection.
    public func isBlocked() -> Bool {
        guard #available(iOS 16.0, *) else { return false }
        // Read on the same serial queue as the writers so we never observe a
        // half-applied mutation
        return queue.sync {
            !(managedSettingsStore.shield.applications?.isEmpty ?? true) ||
            // `unblockAll` stores `.some(.none)`; comparing the Optional with
            // `.none` means `nil`, so that read as "blocked" forever after.
            (managedSettingsStore.shield.applicationCategories.map { $0 != ShieldSettings.ActivityCategoryPolicy.none } ?? false) ||
            !(managedSettingsStore.shield.webDomains?.isEmpty ?? true)
        }
    }

    /// Force iOS to reload shield configuration by toggling shields
    /// Call this after updating shield configuration via SharedDefaults
    public func refreshShields() {
        queue.async { [self] in
            // Capture current shield state
            let apps = managedSettingsStore.shield.applications
            let categories = managedSettingsStore.shield.applicationCategories
            let domains = managedSettingsStore.shield.webDomains

            // Check if anything is actually shielded
            let hasShields = !(apps?.isEmpty ?? true) ||
                             categories != .none ||
                             !(domains?.isEmpty ?? true)

            guard hasShields else { return }

            // This toggle is itself a mutation; capture its generation so a
            // concurrent blockSelection()/unblockAll() invalidates the restore.
            shieldGeneration += 1
            let generation = shieldGeneration

            // Clear shields
            managedSettingsStore.shield.applications = nil
            managedSettingsStore.shield.applicationCategories = .none
            managedSettingsStore.shield.webDomains = nil

            // Small delay to ensure iOS processes the change
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [self] in
                self.queue.async {
                    // Skip the restore if the shield state was mutated during the
                    // window
                    guard self.shieldGeneration == generation else { return }
                    // Restore shields - this forces iOS to call ShieldConfigurationExtension
                    self.managedSettingsStore.shield.applications = apps
                    self.managedSettingsStore.shield.applicationCategories = categories
                    self.managedSettingsStore.shield.webDomains = domains
                }
            }
        }
    }

    /// Apply the domain list to the store and persist it. Call on `queue`.
    private func updateWebContentFilter() {
        SharedDefaults.shared.setBlockedWebDomains(blockedWebDomains.compactMap { $0.domain })
        if blockedWebDomains.isEmpty {
            managedSettingsStore.webContent.blockedByFilter = nil
        } else {
            managedSettingsStore.webContent.blockedByFilter = .specific(blockedWebDomains)
        }
    }
}
