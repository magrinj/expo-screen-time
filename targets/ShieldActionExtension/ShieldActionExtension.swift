import ManagedSettings
import Foundation

/// Shield Action Extension
/// Handles user interactions with the shield (blocking) screen
class ShieldActionExtension: ShieldActionDelegate {

    private let defaults = SharedDefaults.shared
    private let store = ManagedSettingsStore()

    /// What the pressed shield covers — an `unblock` lifts exactly that.
    private enum ShieldedTarget {
        case application(ApplicationToken)
        case webDomain(WebDomainToken)
        case category(ActivityCategoryToken)
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

        // Get action configuration
        var actionConfig: [String: Any]?

        if let activeActivity = defaults.getActiveActivity() {
            actionConfig = defaults.getShieldActions(activityName: activeActivity)
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

    /// Lift the shield on exactly what was pressed, until a block is applied
    /// again (the next `blockSelection`, or the next interval start).
    private func lift(_ target: ShieldedTarget) {
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
