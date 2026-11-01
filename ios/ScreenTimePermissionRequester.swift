import ExpoModulesCore
import FamilyControls

/// Family Controls authorization, reported the Expo way (`status`, `granted`,
/// `canAskAgain`) plus the `steps` behind it. iOS has one step, `screenTime`:
/// the system prompt.
public class ScreenTimePermissionRequester: NSObject, EXPermissionsRequester {
    public static func permissionType() -> String {
        return "screenTime"
    }

    /// Why the last request failed: a `FamilyControlsError`, kept until a
    /// request succeeds. In memory only, so a restriction lifted in Settings
    /// is not reported after the app relaunches.
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
    /// `"child"` for the next request: Family Sharing authorization approved
    /// by a parent. Anything else requests `.individual`.
    private var nextMember = "individual"

    /// Set the member the next `requestPermissions` asks for.
    func prepareRequest(member: String?) {
        lock.lock()
        nextMember = member ?? "individual"
        lock.unlock()
    }

    public func getPermissions() -> [AnyHashable: Any] {
        var stepStatus = "undetermined"
        var systemStatus = EXPermissionStatusUndetermined

        if #available(iOS 16.0, *) {
            let status = AuthorizationCenter.shared.authorizationStatus
            if Self.isApproved(status) {
                stepStatus = "granted"
                systemStatus = EXPermissionStatusGranted
            } else if status == .denied {
                stepStatus = "denied"
                systemStatus = EXPermissionStatusDenied
            }
        }

        lock.lock()
        let failure = stepStatus == "granted" ? nil : lastFailure
        lock.unlock()

        var step: [String: Any] = [
            "id": "screenTime",
            "required": true,
            "kind": "prompt",
        ]
        if let failure = failure {
            if failure.restricted {
                // Restricted reads as denied at the top level, which also
                // makes Expo report `canAskAgain: false`.
                stepStatus = "restricted"
                systemStatus = EXPermissionStatusDenied
            }
            step["error"] = ["code": failure.code, "message": failure.message]
        }
        step["status"] = stepStatus

        return [
            "status": systemStatus.rawValue,
            "steps": [step],
        ]
    }

    public func requestPermissions(resolver resolve: @escaping EXPromiseResolveBlock, rejecter reject: @escaping EXPromiseRejectBlock) {
        guard #available(iOS 16.0, *) else {
            reject(ScreenTimeErrorCode.unsupported.rawValue, "Screen Time API requires iOS 16.0 or later", nil)
            return
        }

        // Already decided: re-requesting authorization throws even when it is
        // already approved, so report the current status instead of asking again.
        if Self.isApproved(AuthorizationCenter.shared.authorizationStatus) {
            resolve(self.getPermissions())
            return
        }

        lock.lock()
        let member: FamilyControlsMember = nextMember == "child" ? .child : .individual
        nextMember = "individual"
        lock.unlock()

        Task {
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
            // the status changed out of band): getPermissions drops the failure
            // once access is granted. Never reject — matching Expo's permission
            // convention and avoiding an uncaught rejection in the JS hook; the
            // failure is reported as the step's `error`.
            self.lock.withLock { self.lastFailure = failure }
            if Self.isApproved(AuthorizationCenter.shared.authorizationStatus) {
                UserDefaults.standard.set(member == .child ? "child" : "individual", forKey: Self.authorizedMemberKey)
            }
            resolve(self.getPermissions())
        }
    }

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
