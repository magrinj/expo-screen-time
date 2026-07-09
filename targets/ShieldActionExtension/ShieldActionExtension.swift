import ManagedSettings
import Foundation

/// Shield Action Extension
/// Handles user interactions with the shield (blocking) screen
class ShieldActionExtension: ShieldActionDelegate {

    private let defaults = SharedDefaults.shared
    private let store = ManagedSettingsStore()

    // MARK: - Action Handlers

    /// Handle action for a shielded application
    override func handle(
        action: ShieldAction,
        for application: ApplicationToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        let response = processAction(action)
        completionHandler(response)
    }

    /// Handle action for a shielded web domain
    override func handle(
        action: ShieldAction,
        for webDomain: WebDomainToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        let response = processAction(action)
        completionHandler(response)
    }

    /// Handle action for a shielded application in a category
    override func handle(
        action: ShieldAction,
        for category: ActivityCategoryToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        let response = processAction(action)
        completionHandler(response)
    }

    // MARK: - Private Helpers

    /// Process a shield action and return the appropriate response
    private func processAction(_ action: ShieldAction) -> ShieldActionResponse {
        // Record the blocked attempt. This handler is the one callback that
        // reliably fires per shield interaction (the configuration data
        // source is cached by the system and not re-invoked on every open),
        // so it doubles as the "user hit a block" counter the app drains via
        // drainShieldInteractions().
        switch action {
        case .primaryButtonPressed:
            defaults.appendShieldInteraction(button: "primary")
        case .secondaryButtonPressed:
            defaults.appendShieldInteraction(button: "secondary")
        @unknown default:
            break
        }

        // Get action configuration
        var actionConfig: [String: Any]?

        if let activeActivity = defaults.getActiveActivity() {
            actionConfig = defaults.getShieldActions(activityName: activeActivity)
        }

        if actionConfig == nil {
            actionConfig = defaults.getDefaultShieldActions()
        }

        // Determine which action to execute based on button pressed
        let actionType: String?
        let snoozeDuration: Int?

        switch action {
        case .primaryButtonPressed:
            actionType = actionConfig?["primaryButtonAction"] as? String
            snoozeDuration = actionConfig?["primaryButtonSnoozeDuration"] as? Int
        case .secondaryButtonPressed:
            actionType = actionConfig?["secondaryButtonAction"] as? String
            snoozeDuration = actionConfig?["secondaryButtonSnoozeDuration"] as? Int
        @unknown default:
            actionType = nil
            snoozeDuration = nil
        }

        guard let type = actionType else {
            // Default: close the shield
            return .close
        }

        return executeAction(type: type, snoozeDuration: snoozeDuration)
    }

    /// Execute an action and return the response
    private func executeAction(type: String, snoozeDuration: Int?) -> ShieldActionResponse {
        switch type {
        case "close":
            // Close the shield, block remains active
            return .close

        case "unblock":
            // Unblock everything
            store.shield.applications = nil
            store.shield.applicationCategories = nil
            store.shield.webDomains = nil
            return .close

        case "snooze":
            // Temporarily unblock (snooze)
            let duration = snoozeDuration ?? 300 // Default 5 minutes

            // Store the current blocks to restore later
            // Note: This is a simplified implementation
            // In a production app, you'd want to persist the snooze state

            // Remove blocks
            store.shield.applications = nil
            store.shield.applicationCategories = nil
            store.shield.webDomains = nil

            // Schedule restoration of blocks
            // Note: This won't work in the extension sandbox
            // The main app needs to handle snooze restoration
            scheduleBlockRestoration(after: duration)

            return .close

        case "defer":
            // Defer the action - allows shield UI to update
            return .defer

        default:
            return .close
        }
    }

    /// Schedule restoration of blocks after snooze duration
    /// Note: This stores the snooze end time for the main app to check
    private func scheduleBlockRestoration(after seconds: Int) {
        let snoozeEndTime = Date().timeIntervalSince1970 + Double(seconds)

        // Store snooze end time in UserDefaults
        // The main app or monitor extension can check this and restore blocks
        if let activeActivity = defaults.getActiveActivity() {
            var config = defaults.getMonitoringConfig(activityName: activeActivity) ?? [:]
            config["snoozeEndTime"] = snoozeEndTime
            defaults.saveMonitoringConfig(activityName: activeActivity, config: config)
        }
    }
}
