import ExpoModulesCore
import FamilyControls
import UserNotifications

/// The permission steps of iOS, read and requested: `screenTime` (Family
/// Controls authorization, the system prompt) and `notifications` (for a
/// shield's `openApp` action, which reaches the app through a local
/// notification). The response is composed by `PermissionSteps`, which
/// documents the rules.
///
/// Registered with Expo's permission service (`EXPermissionsRequester`), so
/// `Permissions.getAsync("screenTime")` keeps working with the default
/// features; the module itself calls `response(features:)` and
/// `request(step:member:features:)`, which honour the requested features and
/// derive `canAskAgain` the way the service cannot.
public class ScreenTimePermissionRequester: NSObject, EXPermissionsRequester {
    public static func permissionType() -> String {
        return "screenTime"
    }

    /// Why the last Family Controls request failed: a `FamilyControlsError`,
    /// kept until a request succeeds. In memory only, so a restriction
    /// lifted in Settings is not reported after the app relaunches.
    private struct RequestFailure {
        let code: String
        let message: String
        /// The OS refuses to let this user grant access (Screen Time
        /// restrictions, a managed device).
        let restricted: Bool
    }

    /// `UserDefaults.standard` key: the member of the last successful request.
    private static let authorizedMemberKey = "expo_st_authorized_member"

    /// Who granted access last: `"child"` or `"individual"`. Access granted
    /// before this was recorded reads as `"individual"`, the only kind
    /// earlier builds requested.
    static var authorizedMember: String {
        return UserDefaults.standard.string(forKey: authorizedMemberKey) ?? "individual"
    }

    private let lock = NSLock()
    private var lastFailure: RequestFailure?
    /// Why the last notification request failed (an error from
    /// `requestAuthorization`, not a refusal), kept until one succeeds.
    private var lastNotificationFailure: PermissionSteps.StepError?

    /// Scoped locking that async contexts may call (`NSLock.lock()` is not
    /// async-safe; `NSLock.withLock` is, but needs iOS 16).
    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    // MARK: - Steps

    /// The `screenTime` step as it stands now.
    private func screenTimeStep() -> PermissionSteps.Step {
        var status = PermissionSteps.Status.undetermined
        if #available(iOS 16.0, *) {
            let authorization = AuthorizationCenter.shared.authorizationStatus
            if Self.isApproved(authorization) {
                status = .granted
            } else if authorization == .denied {
                status = .denied
            }
        }

        let failure = locked { status == .granted ? nil : lastFailure }

        var error: PermissionSteps.StepError?
        if let failure = failure {
            if failure.restricted {
                status = .restricted
            }
            error = PermissionSteps.StepError(code: failure.code, message: failure.message)
        }
        // Family Controls shows its prompt on every request that is not yet
        // approved — a denial is not final — unless the user is restricted.
        return PermissionSteps.Step(id: .screenTime, status: status, required: true, error: error, canPrompt: status != .restricted)
    }

    /// The `notifications` step: `authorized`, `provisional` and `ephemeral`
    /// all deliver a tapped notification, so they are granted.
    private func notificationsStep() async -> PermissionSteps.Step {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let status: PermissionSteps.Status
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            status = .granted
        case .denied:
            status = .denied
        case .notDetermined:
            status = .undetermined
        @unknown default:
            status = .unknown
        }
        let error = locked { status == .granted ? nil : lastNotificationFailure }
        // iOS shows the notification prompt once; after a denial only
        // Settings can grant it.
        return PermissionSteps.Step(id: .notifications, status: status, required: true, error: error, canPrompt: status != .denied)
    }

    private func steps(for features: [PermissionSteps.Feature]) async -> [PermissionSteps.Step] {
        var steps: [PermissionSteps.Step] = []
        for id in PermissionSteps.stepIds(for: features) {
            switch id {
            case .screenTime:
                steps.append(screenTimeStep())
            case .notifications:
                steps.append(await notificationsStep())
            }
        }
        return steps
    }

    // MARK: - Module entry points

    /// `getScreenTimePermissionsAsync(options)`: the response for `features`,
    /// without prompting.
    func response(features: [PermissionSteps.Feature]) async -> [String: Any] {
        return PermissionSteps.response(steps: await steps(for: features))
    }

    /// `requestScreenTimePermissionsAsync(options)`: advance onboarding by one
    /// step — `step` if given (the module has checked it is one the
    /// features list), else the first required step not granted — and
    /// return the whole response. A step already granted is not asked
    /// again (Family Controls throws when re-requested while approved), nor
    /// one whose prompt the OS will not show again (a denied notification
    /// step: only Settings can grant it, and the response says so with
    /// `canAskAgain`); nothing pending means no prompt. Never rejects: a
    /// failed request resolves with the resulting status and the reason in
    /// the step's `error`, matching Expo's permission convention (an
    /// uncaught rejection would surface in the JS hook).
    func request(step: PermissionSteps.StepId?, member: String?, features: [PermissionSteps.Feature]) async -> [String: Any] {
        let current = await steps(for: features)
        guard let id = step ?? PermissionSteps.nextStep(in: current),
              let target = current.first(where: { $0.id == id }),
              target.status != .granted, target.canPrompt else {
            return PermissionSteps.response(steps: current)
        }
        switch target.id {
        case .screenTime:
            // Below iOS 16 the module rejects with UNSUPPORTED before this.
            if #available(iOS 16.0, *) {
                await requestScreenTime(member: member == "child" ? .child : .individual)
            }
        case .notifications:
            await requestNotifications()
        }
        return PermissionSteps.response(steps: await steps(for: features))
    }

    // MARK: - Requests

    @available(iOS 16.0, *)
    private func requestScreenTime(member: FamilyControlsMember) async {
        var failure: RequestFailure?
        do {
            try await AuthorizationCenter.shared.requestAuthorization(for: member)
        } catch let error as FamilyControlsError {
            failure = Self.failure(from: error)
        } catch {
            failure = RequestFailure(code: "unknown", message: error.localizedDescription, restricted: false)
        }
        // authorizationStatus is a published property that can lag behind the
        // await — resolving immediately can report the stale .notDetermined,
        // forcing callers to request twice. Wait briefly for it to settle
        // (approved/denied); give up after ~2s (e.g. dialog dismissed).
        for _ in 0..<20 where AuthorizationCenter.shared.authorizationStatus == .notDetermined {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        // The request can throw even when the user ultimately approved (or
        // the status changed out of band): the step drops the failure once
        // access is granted.
        locked { lastFailure = failure }
        if Self.isApproved(AuthorizationCenter.shared.authorizationStatus) {
            UserDefaults.standard.set(member == .child ? "child" : "individual", forKey: Self.authorizedMemberKey)
        }
    }

    /// Ask for alerts and sounds: what a tapped shield notification needs.
    /// A refusal is not an error (the step reads `denied`); a thrown error
    /// is kept as the step's `error`.
    private func requestNotifications() async {
        var failure: PermissionSteps.StepError?
        do {
            _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
        } catch {
            failure = PermissionSteps.StepError(code: "unknown", message: error.localizedDescription)
        }
        locked { lastNotificationFailure = failure }
    }

    // MARK: - EXPermissionsRequester

    /// The default features, synchronously: the `screenTime` step alone, so
    /// no notification settings are read. Expo's service derives `granted`
    /// and `canAskAgain` from `status` here (the module does not use this path).
    public func getPermissions() -> [AnyHashable: Any] {
        let step = screenTimeStep()
        let systemStatus: EXPermissionStatus
        switch step.status {
        case .granted:
            systemStatus = EXPermissionStatusGranted
        case .denied, .restricted:
            systemStatus = EXPermissionStatusDenied
        case .undetermined, .unknown:
            systemStatus = EXPermissionStatusUndetermined
        }
        return [
            "status": systemStatus.rawValue,
            "steps": [step.dictionary],
        ]
    }

    public func requestPermissions(resolver resolve: @escaping EXPromiseResolveBlock, rejecter reject: @escaping EXPromiseRejectBlock) {
        guard #available(iOS 16.0, *) else {
            reject(ScreenTimeErrorCode.unsupported.rawValue, "Screen Time API requires iOS 16.0 or later", nil)
            return
        }
        Task {
            if !Self.isApproved(AuthorizationCenter.shared.authorizationStatus) {
                await self.requestScreenTime(member: .individual)
            }
            resolve(self.getPermissions())
        }
    }

    // MARK: - Family Controls

    /// Access is granted. iOS 26.4 added `approvedWithDataAccess`, which the
    /// SDKs before it cannot name (hence the compiler check: Xcode 26.4 ships
    /// Swift 6.3); reading it as "undetermined" sent granted users back to
    /// the prompt.
    @available(iOS 16.0, *)
    static func isApproved(_ status: AuthorizationStatus) -> Bool {
        if status == .approved {
            return true
        }
        #if compiler(>=6.3)
        if #available(iOS 26.4, *), status == .approvedWithDataAccess {
            return true
        }
        #endif
        return false
    }

    @available(iOS 16.0, *)
    private static func failure(from error: FamilyControlsError) -> RequestFailure {
        // The case name (`restricted`, `invalidAccountType`, …): stable, and
        // cases added by later SDKs come through without a code change.
        let code = String(describing: error)
        var restricted = false
        if case .restricted = error {
            restricted = true
        }
        return RequestFailure(code: code, message: error.localizedDescription, restricted: restricted)
    }
}
