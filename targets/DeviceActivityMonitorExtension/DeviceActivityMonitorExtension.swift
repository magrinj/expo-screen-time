import DeviceActivity
import FamilyControls
import ManagedSettings
import Foundation

/// Device Activity Monitor Extension
/// Handles scheduled monitoring events and usage thresholds
class DeviceActivityMonitorExtension: DeviceActivityMonitor {

    private let defaults = SharedDefaults.shared
    private let store = ManagedSettingsStore()

    // MARK: - Temporary Unlock (internal activity)

    // Must mirror the constants in ExpoScreenTimeModule.swift.
    private let unlockActivityName = "expo_st_temporary_unlock"
    private let unlockStepPrefix = "expo_st_unlock_step_"

    // MARK: - Interval Events

    /// Called when a scheduled interval starts (first device use after start time)
    override func intervalDidStart(for activity: DeviceActivityName) {
        let activityName = activity.rawValue

        // The unlock's metering schedule is module-internal: no monitoring
        // config, no JS event, and it must never become the "active activity"
        // (that would repoint shield configs).
        guard activityName != unlockActivityName else { return }

        // Record event
        saveEvent(activityName: activityName, eventType: "intervalDidStart")

        // Get monitoring configuration and apply blocks
        if defaults.getMonitoringConfig(activityName: activityName) != nil,
           let selectionId = defaults.getSelectionId(activityName: activityName),
           let selection = SelectionStore.shared.get(selectionId) {
            applyBlocks(selection: selection)
        }

        // Mark this activity as active
        defaults.setActiveActivity(activityName)
    }

    /// Called when a scheduled interval ends (first device use after end time)
    override func intervalDidEnd(for activity: DeviceActivityName) {
        let activityName = activity.rawValue

        guard activityName != unlockActivityName else {
            handleUnlockIntervalEnd()
            return
        }

        // Record event
        saveEvent(activityName: activityName, eventType: "intervalDidEnd")

        // Remove blocks when interval ends
        removeBlocks()

        // Clear active activity
        defaults.setActiveActivity(nil)
    }

    // MARK: - Threshold Events

    /// Called when a usage threshold is reached
    override func eventDidReachThreshold(_ event: DeviceActivityEvent.Name, activity: DeviceActivityName) {
        let activityName = activity.rawValue
        let eventName = event.rawValue

        guard activityName != unlockActivityName else {
            handleUnlockStep(eventName: eventName)
            return
        }

        // Record event
        saveEvent(activityName: activityName, eventType: "eventDidReachThreshold", eventName: eventName)

        // Get event configuration to decide what to do
        if let config = defaults.getMonitoringConfig(activityName: activityName),
           let events = config["events"] as? [[String: Any]] {

            // Find the event configuration
            for eventConfig in events {
                if eventConfig["eventName"] as? String == eventName {
                    // Check if we should block on threshold
                    if eventConfig["blockOnThreshold"] as? Bool == true,
                       let selectionId = defaults.getSelectionId(activityName: activityName),
                       let selection = SelectionStore.shared.get(selectionId) {
                        applyBlocks(selection: selection)
                    }
                    break
                }
            }
        }
    }

    // MARK: - Warning Events

    /// Called before interval ends (if warningTime is set)
    override func intervalWillEndWarning(for activity: DeviceActivityName) {
        let activityName = activity.rawValue
        guard activityName != unlockActivityName else { return }
        saveEvent(activityName: activityName, eventType: "intervalWillEndWarning")
    }

    /// Called before threshold is reached
    override func eventWillReachThresholdWarning(
        _ event: DeviceActivityEvent.Name,
        activity: DeviceActivityName
    ) {
        let activityName = activity.rawValue
        guard activityName != unlockActivityName else { return }
        let eventName = event.rawValue
        saveEvent(activityName: activityName, eventType: "eventWillReachThresholdWarning", eventName: eventName)
    }

    // MARK: - Temporary Unlock Handling

    /// A usage step fired: `<budget spent so far>` seconds of the blocked
    /// selection have been used since the unlock was granted. Record it, and
    /// re-apply the shield once the budget is spent.
    private func handleUnlockStep(eventName: String) {
        guard eventName.hasPrefix(unlockStepPrefix),
              let stepSeconds = Int(eventName.dropFirst(unlockStepPrefix.count)),
              stepSeconds > 0 else {
            // Unrecognized event on the internal activity — fail closed:
            // ending the unlock early beats leaving the shield off forever.
            defaults.clearUnlockState()
            relockFromPersistedSelection()
            return
        }

        // Sanity bound: measured usage can never exceed the wall-clock time
        // since the grant. iOS occasionally fires thresholds immediately on
        // registration (observed on iOS 26 betas) — such a fire claims more
        // usage than physically possible and must be ignored, or a fresh
        // grant would be consumed instantly.
        if let grantedAt = defaults.getUnlockGrantedAt() {
            let elapsed = Date().timeIntervalSince(grantedAt)
            if Double(stepSeconds) > elapsed + 30 { return }
        }

        // Steps can arrive out of order — consumption only moves forward.
        if stepSeconds > defaults.getUnlockConsumedSeconds() {
            defaults.setUnlockConsumedSeconds(stepSeconds)
        }

        let budget = defaults.getUnlockBudgetSeconds()
        if budget <= 0 || stepSeconds >= budget {
            defaults.clearUnlockState()
            relockFromPersistedSelection()
        }
    }

    /// The metering interval ended. When that genuinely happens (23:59:59)
    /// it's the daily reset: unspent budget expires at midnight. But
    /// `stopMonitoring` also fires this callback — on every re-grant or
    /// cancel, at any time of day — so only honor it near the boundary.
    private func handleUnlockIntervalEnd() {
        let components = Calendar.current.dateComponents([.hour, .minute], from: Date())
        guard components.hour == 23, (components.minute ?? 0) >= 58 else { return }
        defaults.clearUnlockState()
        relockFromPersistedSelection()
    }

    /// Restore the shield from the selection `blockSelection` persisted.
    private func relockFromPersistedSelection() {
        guard let selectionId = defaults.getBlockedSelectionId(),
              let selection = SelectionStore.shared.get(selectionId) else {
            return
        }
        applyBlocks(selection: selection)
    }

    // MARK: - Private Helpers

    /// Apply blocks to the ManagedSettingsStore
    private func applyBlocks(selection: FamilyActivitySelection) {
        store.shield.applications = selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
        store.shield.applicationCategories = selection.categoryTokens.isEmpty
            ? nil
            : ShieldSettings.ActivityCategoryPolicy.specific(selection.categoryTokens)
        store.shield.webDomains = selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
    }

    /// Remove all blocks from the ManagedSettingsStore
    private func removeBlocks() {
        store.shield.applications = nil
        store.shield.applicationCategories = nil
        store.shield.webDomains = nil
    }

    /// Save an event to history
    private func saveEvent(activityName: String, eventType: String, eventName: String? = nil) {
        var event: [String: Any] = [
            "activityName": activityName,
            "eventType": eventType,
            "timestamp": Date().timeIntervalSince1970 * 1000
        ]

        if let eventName = eventName {
            event["eventName"] = eventName
        }

        defaults.saveEvent(activityName: activityName, event: event)

        // Post Darwin notification to alert the main app
        postDarwinNotification()
    }

    /// Post a Darwin notification to notify the main app of a new event
    private func postDarwinNotification() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        CFNotificationCenterPostNotification(
            center,
            CFNotificationName("com.expo.screentime.monitorEvent" as CFString),
            nil,
            nil,
            true
        )
    }
}
