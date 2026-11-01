import Foundation

/// The permission model behind `getScreenTimePermissionsAsync` and
/// `requestScreenTimePermissionsAsync`, without the OS: which steps the
/// requested features need, and how their statuses fold into Expo's
/// `PermissionResponse` (`status`, `granted`, `canAskAgain`).
///
/// iOS has two steps. `screenTime` (the Family Controls prompt) is always
/// required. `notifications` is listed, and required, only when the app
/// requests the `shieldOpenApp` feature: a shield's `openApp` action reaches
/// the host app through a local notification the user taps, which needs
/// notification authorization. The Android step ids exist in the JS type so
/// shared code compiles; iOS never lists them, and rejects a request for one.
///
/// The response is composed here rather than by Expo's permission service,
/// which recomputes `granted` and `canAskAgain` from `status` alone: that
/// cannot tell a denied Family Controls prompt (iOS shows it again on the
/// next request) from a denied notification prompt (shown once; the user has
/// to go to Settings), nor a `restricted` step from a denied one.
///
/// Pure Foundation on purpose, unit-tested in `scripts/swift-tests`.
enum PermissionSteps {
    /// What the app intends to use (`ScreenTimeFeature`).
    enum Feature: String, CaseIterable {
        case blocking
        case monitoring
        case usageReports
        case shieldOpenApp
        case appRemovalProtection
        case webDomainBlocking
    }

    /// `ScreenTimePermissionOptions.features` when absent.
    static let defaultFeatures: [Feature] = [.blocking, .monitoring]

    /// The steps iOS knows (`PermissionStepId`, iOS subset).
    enum StepId: String, CaseIterable {
        case screenTime
        case notifications
    }

    /// Every id of the contract's `PermissionStepId`, so a request for an
    /// Android step is told apart from a misspelt one.
    static let contractStepIds: Set<String> = [
        "screenTime", "usageAccess", "overlay", "accessibility",
        "notifications", "batteryOptimization", "backgroundAutostart", "deviceAdmin",
    ]

    enum Status: String {
        case granted
        case denied
        case undetermined
        case restricted
        case unknown
    }

    /// One step as reported to JS (`PermissionStep`), plus what the response
    /// derivation needs to know about it.
    struct Step: Equatable {
        var id: StepId
        var status: Status
        var required: Bool
        /// Both iOS steps are system dialogs.
        var kind = "prompt"
        /// Why the last request failed, verbatim from the OS.
        var error: StepError?
        /// Whether requesting this step again can show its prompt. Family
        /// Controls asks every time unless the user is `restricted`; the
        /// notification prompt is shown once, then only Settings can grant it.
        var canPrompt: Bool

        init(id: StepId, status: Status, required: Bool, error: StepError? = nil, canPrompt: Bool) {
            self.id = id
            self.status = status
            self.required = required
            self.error = error
            self.canPrompt = canPrompt
        }

        /// The `PermissionStep` JS receives; `error` only when present.
        var dictionary: [String: Any] {
            var dictionary: [String: Any] = [
                "id": id.rawValue,
                "status": status.rawValue,
                "required": required,
                "kind": kind,
            ]
            if let error = error {
                dictionary["error"] = ["code": error.code, "message": error.message]
            }
            return dictionary
        }
    }

    struct StepError: Equatable {
        var code: String
        var message: String
    }

    /// What a request for `step` means, from the raw `step` option.
    enum RequestedStep: Equatable {
        /// No `step`: the first required step not yet granted.
        case next
        case step(StepId)
        /// An id of the contract that iOS has no step for (an Android step).
        case notOnThisPlatform(String)
        /// Not a `PermissionStepId` at all.
        case unknown(String)
    }

    // MARK: - Options

    /// `features` from the request options: absent, or not an array, is the
    /// default; names this build does not know are ignored (the JS type is
    /// the guard), so a feature added later does not make older natives
    /// fail. Order is kept, duplicates dropped.
    static func features(from value: Any?) -> [Feature] {
        guard let names = value as? [Any] else { return defaultFeatures }
        var features: [Feature] = []
        for name in names {
            if let name = name as? String, let feature = Feature(rawValue: name), !features.contains(feature) {
                features.append(feature)
            }
        }
        return features
    }

    /// The `step` option, classified. Absent, `undefined` (dropped by the
    /// bridge) and `null` all mean the next step.
    static func requestedStep(from value: Any?) -> RequestedStep {
        guard let value = value, !(value is NSNull) else { return .next }
        guard let name = value as? String else { return .unknown(String(describing: value)) }
        if let id = StepId(rawValue: name) { return .step(id) }
        return contractStepIds.contains(name) ? .notOnThisPlatform(name) : .unknown(name)
    }

    // MARK: - Steps

    /// The steps iOS reports for `features`, in onboarding order:
    /// `screenTime` always, `notifications` when `shieldOpenApp` is requested.
    /// Every listed step is required (the notification is the only way the
    /// `openApp` action can reach the app).
    static func stepIds(for features: [Feature]) -> [StepId] {
        var ids: [StepId] = [.screenTime]
        if features.contains(.shieldOpenApp) {
            ids.append(.notifications)
        }
        return ids
    }

    /// The first required step not yet granted; nil when onboarding is done.
    static func nextStep(in steps: [Step]) -> StepId? {
        return steps.first { $0.required && $0.status != .granted }?.id
    }

    // MARK: - Response

    /// Expo's `PermissionResponse` plus `steps`:
    /// - `granted` ⇔ every required step is granted;
    /// - `status`: `granted` if so; else `denied` if a required step is
    ///   denied or restricted; else `undetermined`;
    /// - `canAskAgain` ⇔ no required step is restricted, and every required
    ///   step still to grant can show its prompt.
    static func response(steps: [Step]) -> [String: Any] {
        let required = steps.filter(\.required)
        let pending = required.filter { $0.status != .granted }
        let granted = pending.isEmpty
        let status: String
        if granted {
            status = "granted"
        } else if pending.contains(where: { $0.status == .denied || $0.status == .restricted }) {
            status = "denied"
        } else {
            status = "undetermined"
        }
        let canAskAgain = !required.contains { $0.status == .restricted } && pending.allSatisfy(\.canPrompt)
        return [
            "status": status,
            "granted": granted,
            "canAskAgain": canAskAgain,
            "expires": "never",
            "steps": steps.map(\.dictionary),
        ]
    }
}
