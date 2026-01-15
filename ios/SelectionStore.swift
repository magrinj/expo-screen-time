import Foundation
import FamilyControls
import ManagedSettings

@available(iOS 15.0, *)
class SelectionStore {
    static let shared = SelectionStore()

    private let keyPrefix = "expo_st_sel_"
    private var defaults: UserDefaults?

    /// In-memory cache of token handles for O(1) lookup
    /// Key: selectionId, Value: dict of handle → token type info
    private var handleCache: [String: [String: TokenHandleInfo]] = [:]

    struct TokenHandleInfo {
        let type: String // "application", "category", "webDomain"
        let index: Int   // index in the corresponding token set
    }

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

    // MARK: - CRUD

    func save(_ selectionId: String, _ selection: FamilyActivitySelection) {
        guard let data = try? JSONEncoder().encode(selection) else { return }
        defaults?.set(data, forKey: keyPrefix + selectionId)
        defaults?.synchronize()
        rebuildHandleCache(for: selectionId, selection: selection)
    }

    func get(_ selectionId: String) -> FamilyActivitySelection? {
        guard let data = defaults?.data(forKey: keyPrefix + selectionId) else { return nil }
        return try? JSONDecoder().decode(FamilyActivitySelection.self, from: data)
    }

    func delete(_ selectionId: String) {
        defaults?.removeObject(forKey: keyPrefix + selectionId)
        defaults?.synchronize()
        handleCache.removeValue(forKey: selectionId)
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

    // MARK: - Metadata

    func getMetadata(_ selectionId: String) -> [String: Int]? {
        guard let selection = get(selectionId) else { return nil }
        return [
            "applicationCount": selection.applicationTokens.count,
            "categoryCount": selection.categoryTokens.count,
            "webDomainCount": selection.webDomainTokens.count,
        ]
    }

    // MARK: - Token Handles

    func getTokenHandles(_ selectionId: String) -> [[String: String]]? {
        guard let selection = get(selectionId) else { return nil }
        ensureHandleCache(for: selectionId, selection: selection)

        var handles: [[String: String]] = []

        for token in selection.applicationTokens {
            let handle = stableHandle(for: token)
            handles.append(["handle": handle, "type": "application"])
        }
        for token in selection.categoryTokens {
            let handle = stableHandle(for: token)
            handles.append(["handle": handle, "type": "category"])
        }
        for token in selection.webDomainTokens {
            let handle = stableHandle(for: token)
            handles.append(["handle": handle, "type": "webDomain"])
        }

        // Sort by type then handle for stable ordering across app launches
        handles.sort { a, b in
            if a["type"]! != b["type"]! { return a["type"]! < b["type"]! }
            return a["handle"]! < b["handle"]!
        }

        return handles
    }

    /// Generate a stable handle by encoding the token to JSON and base64-ing it.
    /// Unlike hashValue, this is deterministic across app launches.
    private func stableHandle<T: Encodable>(for token: T) -> String {
        guard let data = try? JSONEncoder().encode(token) else { return "unknown" }
        return data.base64EncodedString()
    }

    func removeToken(_ selectionId: String, handle: String) {
        guard var selection = get(selectionId) else { return }

        selection.applicationTokens = Set(selection.applicationTokens.filter {
            stableHandle(for: $0) != handle
        })
        selection.categoryTokens = Set(selection.categoryTokens.filter {
            stableHandle(for: $0) != handle
        })
        selection.webDomainTokens = Set(selection.webDomainTokens.filter {
            stableHandle(for: $0) != handle
        })

        save(selectionId, selection)
    }

    /// Resolve a handle to the actual ApplicationToken (for TokenLabel rendering)
    func resolveApplicationToken(_ selectionId: String, handle: String) -> ApplicationToken? {
        guard let selection = get(selectionId) else { return nil }
        return selection.applicationTokens.first { stableHandle(for: $0) == handle }
    }

    func resolveCategoryToken(_ selectionId: String, handle: String) -> ActivityCategoryToken? {
        guard let selection = get(selectionId) else { return nil }
        return selection.categoryTokens.first { stableHandle(for: $0) == handle }
    }

    func resolveWebDomainToken(_ selectionId: String, handle: String) -> WebDomainToken? {
        guard let selection = get(selectionId) else { return nil }
        return selection.webDomainTokens.first { stableHandle(for: $0) == handle }
    }

    // MARK: - Set Operations

    func merge(_ targetId: String, _ sourceIds: [String]) {
        var result = FamilyActivitySelection()

        for sourceId in sourceIds {
            guard let source = get(sourceId) else { continue }
            result.applicationTokens.formUnion(source.applicationTokens)
            result.categoryTokens.formUnion(source.categoryTokens)
            result.webDomainTokens.formUnion(source.webDomainTokens)
        }

        save(targetId, result)
    }

    func keepCommon(_ targetId: String, _ sourceIds: [String]) {
        guard let firstId = sourceIds.first, var result = get(firstId) else { return }

        for sourceId in sourceIds.dropFirst() {
            guard let source = get(sourceId) else {
                save(targetId, FamilyActivitySelection())
                return
            }
            result.applicationTokens.formIntersection(source.applicationTokens)
            result.categoryTokens.formIntersection(source.categoryTokens)
            result.webDomainTokens.formIntersection(source.webDomainTokens)
        }

        save(targetId, result)
    }

    func subtract(_ targetId: String, fromId: String, subtractId: String) {
        guard var from = get(fromId), let sub = get(subtractId) else { return }

        from.applicationTokens.subtract(sub.applicationTokens)
        from.categoryTokens.subtract(sub.categoryTokens)
        from.webDomainTokens.subtract(sub.webDomainTokens)

        save(targetId, from)
    }

    // MARK: - Export/Import

    func exportSelection(_ selectionId: String) -> String? {
        guard let data = defaults?.data(forKey: keyPrefix + selectionId) else { return nil }
        return data.base64EncodedString()
    }

    func importSelection(_ selectionId: String, base64Data: String) -> Bool {
        guard let data = Data(base64Encoded: base64Data) else { return false }
        guard (try? JSONDecoder().decode(FamilyActivitySelection.self, from: data)) != nil else { return false }
        defaults?.set(data, forKey: keyPrefix + selectionId)
        defaults?.synchronize()
        return true
    }

    // MARK: - Private

    private func ensureHandleCache(for selectionId: String, selection: FamilyActivitySelection) {
        if handleCache[selectionId] == nil {
            rebuildHandleCache(for: selectionId, selection: selection)
        }
    }

    private func rebuildHandleCache(for selectionId: String, selection: FamilyActivitySelection) {
        var cache: [String: TokenHandleInfo] = [:]

        for (i, token) in selection.applicationTokens.enumerated() {
            cache[stableHandle(for: token)] = TokenHandleInfo(type: "application", index: i)
        }
        for (i, token) in selection.categoryTokens.enumerated() {
            cache[stableHandle(for: token)] = TokenHandleInfo(type: "category", index: i)
        }
        for (i, token) in selection.webDomainTokens.enumerated() {
            cache[stableHandle(for: token)] = TokenHandleInfo(type: "webDomain", index: i)
        }

        handleCache[selectionId] = cache
    }
}
