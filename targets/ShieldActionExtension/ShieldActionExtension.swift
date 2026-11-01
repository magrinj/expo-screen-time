import ManagedSettings
import Foundation

/// Shield Action Extension
/// Handles user interactions with the shield (blocking) screen
class ShieldActionExtension: ShieldActionDelegate {

    private let defaults = SharedDefaults.shared
    private let lock = BlockStateLock.shared

    /// What the pressed shield covers — an `unblock` lifts exactly that.
    private enum ShieldedTarget {
        case application(ApplicationToken)
        case webDomain(WebDomainToken)
        case category(ActivityCategoryToken)

        /// The token as a block-source member, to find which source owns it.
        var shieldToken: ShieldToken {
            switch self {
            case .application(let token): return .application(token)
            case .webDomain(let token): return .webDomain(token)
            case .category(let token): return .category(token)
            }
        }

        /// The configuration context whose persisted owner this action may
        /// reuse (`ShieldOwnerMap`).
        var ownerContext: ShieldOwnerMap.Context {
            switch self {
            case .application: return .application
            case .webDomain: return .webDomain
            case .category: return .category
            }
        }

        /// A category's persisted owner must still shield through that
        /// category; an app's or a domain's is specific to it.
        var requiresCoverage: Bool {
            if case .category = self { return true }
            return false
        }
    }

    // MARK: - Action Handlers

    /// Handle action for a shielded application
    override func handle(
        action: ShieldAction,
        for application: ApplicationToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(processAction(action, target: .application(application)))
    }

    /// Handle action for a shielded web domain
    override func handle(
        action: ShieldAction,
        for webDomain: WebDomainToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(processAction(action, target: .webDomain(webDomain)))
    }

    /// Handle action for a shielded application in a category
    override func handle(
        action: ShieldAction,
        for category: ActivityCategoryToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(processAction(action, target: .category(category)))
    }

    // MARK: - Private Helpers

    /// Process a shield action and return the appropriate response
    private func processAction(_ action: ShieldAction, target: ShieldedTarget) -> ShieldActionResponse {
        let button: String
        switch action {
        case .primaryButtonPressed:
            button = "primary"
        case .secondaryButtonPressed:
            button = "secondary"
        @unknown default:
            return .close
        }

        // Record the blocked attempt. This handler is the one callback that
        // reliably fires per shield interaction (the configuration data
        // source is cached by the system and not re-invoked on every open),
        // so it doubles as the "user hit a block" counter the app drains via
        // drainShieldInteractions().
        defaults.appendShieldInteraction(button: button)

        // The actions of the source that shields this target — a threshold,
        // else the latest schedule covering it, else the manual block — then
        // the default. Several sources can be active at once; a single
        // "active activity" pointer used to pick whichever started last.
        // The configuration extension resolved the owner with the app AND
        // its category and recorded it under the tokens it is valid for:
        // reuse it while that source is still active, so the buttons match
        // the screen. Suspended sources are not active.
        //
        // One critical section: the sources, the owner map, and the stores an
        // `unblock` edits — so neither a projection in another process nor
        // the registry pruning after the lift can interleave.
        return lock.withLock {
            var actionConfig: [String: Any]?

            let token = target.shieldToken
            let resolvedByConfiguration = token.persistentKey.flatMap {
                defaults.getShieldOwnerMap().owner(forKey: $0, context: target.ownerContext)
            }
            let owner = BlockComposition.owner(
                ofAnyOf: [token],
                in: defaults.activeBlockSources(),
                preferring: resolvedByConfiguration,
                coveringTargets: target.requiresCoverage
            )
            if let activityName = owner?.kind.activityName {
                actionConfig = defaults.getShieldActions(activityName: activityName)
            }

            if actionConfig == nil {
                actionConfig = defaults.getDefaultShieldActions()
            }

            guard let type = Self.configuredAction(in: actionConfig, button: button) else {
                // Default: close the shield
                return .close
            }

            return executeAction(type: type, target: target)
        }
    }

    /// The action configured for `button` ("primary" or "secondary").
    ///
    /// `setShieldActions` stores the JS shape — `primaryAction` /
    /// `secondaryAction`, each an action name or `{ type: "snooze", duration }`.
    /// This extension used to read `primaryButtonAction`, a key nothing writes,
    /// so every configured action silently fell back to `.close`.
    private static func configuredAction(in config: [String: Any]?, button: String) -> String? {
        guard let config = config else { return nil }
        if let name = config["\(button)Action"] as? String {
            return name
        }
        if let object = config["\(button)Action"] as? [String: Any] {
            return object["type"] as? String
        }
        // Legacy native shape, kept readable.
        return config["\(button)ButtonAction"] as? String
    }

    /// Execute an action and return the response
    private func executeAction(type: String, target: ShieldedTarget) -> ShieldActionResponse {
        switch type {
        case "close":
            // Close the shield, block remains active
            return .close

        case "unblock":
            lift(target)
            return .close

        case "defer":
            // Keep the shield up and let it redraw; the press is recorded above.
            return .defer

        case "snooze":
            // A usage-budget snooze needs the temporary-unlock rework, and
            // proof that this extension may arm DeviceActivity monitoring.
            // Until then the shield stays: the previous code lifted every
            // shield and never restored them.
            return .close

        default:
            return .close
        }
    }

    /// Lift the shield on exactly what was pressed, in every store that
    /// shields it (the manual block and each activity in progress), until a
    /// block is applied again (the next `blockSelection`, relock, or interval
    /// start). Call under the lock: a store leaves the registry only when
    /// the lift that emptied it ran under it.
    private func lift(_ target: ShieldedTarget) {
        // Every store that may hold shields — the registry every writer
        // maintains, not the monitoring configs (which miss a store written
        // by a late threshold callback).
        for name in defaults.shieldStoreNames() {
            let store = ManagedSettingsStore(named: ManagedSettingsStore.Name(name))
            lift(target, in: store)
            if !Self.hasShields(store) {
                defaults.unregisterShieldStore(name)
            }
        }
    }

    private static func hasShields(_ store: ManagedSettingsStore) -> Bool {
        return !(store.shield.applications?.isEmpty ?? true) ||
            (store.shield.applicationCategories.map { $0 != ShieldSettings.ActivityCategoryPolicy.none } ?? false) ||
            !(store.shield.webDomains?.isEmpty ?? true)
    }

    private func lift(_ target: ShieldedTarget, in store: ManagedSettingsStore) {
        switch target {
        case .application(let token):
            if var applications = store.shield.applications {
                applications.remove(token)
                store.shield.applications = applications.isEmpty ? nil : applications
            }
            // The app may also be covered through its category.
            switch store.shield.applicationCategories {
            case .specific(let categories, var except)?:
                except.insert(token)
                store.shield.applicationCategories = .specific(categories, except: except)
            case .all(var except)?:
                except.insert(token)
                store.shield.applicationCategories = .all(except: except)
            default:
                break
            }

        case .webDomain(let token):
            if var webDomains = store.shield.webDomains {
                webDomains.remove(token)
                store.shield.webDomains = webDomains.isEmpty ? nil : webDomains
            }

        case .category(let token):
            if case .specific(var categories, let except)? = store.shield.applicationCategories {
                categories.remove(token)
                store.shield.applicationCategories = categories.isEmpty
                    ? nil
                    : .specific(categories, except: except)
            }
        }
    }
}
