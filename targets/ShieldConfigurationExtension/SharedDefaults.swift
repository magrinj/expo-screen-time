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
                // Alternative naming conventions (kebab-case)
                .replacingOccurrences(of: ".device-activity-monitor", with: "")
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
        var names = Set<String>()
        if let allKeys = defaults?.dictionaryRepresentation().keys {
            for key in allKeys where key.hasPrefix(SharedDefaultsKeys.monitoringPrefix) {
                let name = String(key.dropFirst(SharedDefaultsKeys.monitoringPrefix.count))
                names.insert(name)
            }
        }
        return Array(names)
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

    func saveEvent(activityName: String, event: [String: Any]) {
        let key = SharedDefaultsKeys.eventHistoryPrefix + activityName
        var events = defaults?.array(forKey: key) as? [[String: Any]] ?? []
        events.append(event)

        // Keep only last 1000 events per activity
        if events.count > 1000 {
            events = Array(events.suffix(1000))
        }

        defaults?.set(events, forKey: key)
        defaults?.synchronize()
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

    // MARK: - Active Activity Tracking

    func getActiveActivity() -> String? {
        return defaults?.string(forKey: "expo_st_active_activity")
    }

    func setActiveActivity(_ activityName: String?) {
        if let name = activityName {
            defaults?.set(name, forKey: "expo_st_active_activity")
        } else {
            defaults?.removeObject(forKey: "expo_st_active_activity")
        }
        defaults?.synchronize()
    }
}
