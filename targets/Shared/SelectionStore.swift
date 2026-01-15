import Foundation
import FamilyControls

@available(iOS 15.0, *)
class SelectionStore {
    static let shared = SelectionStore()

    private let keyPrefix = "expo_st_sel_"
    private var defaults: UserDefaults?

    private init() {
        if let bundleId = Bundle.main.bundleIdentifier {
            let baseBundleId = bundleId
                .replacingOccurrences(of: ".DeviceActivityMonitorExtension", with: "")
                .replacingOccurrences(of: ".ShieldConfigurationExtension", with: "")
                .replacingOccurrences(of: ".ShieldActionExtension", with: "")
                .replacingOccurrences(of: ".DeviceActivityReportExtension", with: "")
                .replacingOccurrences(of: ".device-activity-monitor", with: "")
                .replacingOccurrences(of: ".shield-configuration", with: "")
                .replacingOccurrences(of: ".shield-action", with: "")
                .replacingOccurrences(of: ".device-activity-report", with: "")

            defaults = UserDefaults(suiteName: "group.\(baseBundleId)")
        }
    }

    func save(_ selectionId: String, _ selection: FamilyActivitySelection) {
        guard let data = try? JSONEncoder().encode(selection) else { return }
        defaults?.set(data, forKey: keyPrefix + selectionId)
        defaults?.synchronize()
    }

    func get(_ selectionId: String) -> FamilyActivitySelection? {
        guard let data = defaults?.data(forKey: keyPrefix + selectionId) else { return nil }
        return try? JSONDecoder().decode(FamilyActivitySelection.self, from: data)
    }

    func delete(_ selectionId: String) {
        defaults?.removeObject(forKey: keyPrefix + selectionId)
        defaults?.synchronize()
    }

    func list() -> [String] {
        guard let allKeys = defaults?.dictionaryRepresentation().keys else { return [] }
        return allKeys
            .filter { $0.hasPrefix(keyPrefix) }
            .map { String($0.dropFirst(keyPrefix.count)) }
            .sorted()
    }

    func has(_ selectionId: String) -> Bool {
        return defaults?.data(forKey: keyPrefix + selectionId) != nil
    }
}
