import ExpoModulesCore
import FamilyControls

public class ScreenTimePermissionRequester: NSObject, EXPermissionsRequester {
    public static func permissionType() -> String {
        return "screenTime"
    }
    
    @objc private func handleAuthorizationStatusChange() {
        // Log or handle updates to authorization status
        print("Authorization status changed to: \(AuthorizationCenter.shared.authorizationStatus)")
    }
    
    public func getPermissions() -> [AnyHashable: Any] {
        let systemStatus: EXPermissionStatus
        
        if #available(iOS 16.0, *) {
            switch AuthorizationCenter.shared.authorizationStatus {
            case .notDetermined:
                systemStatus = EXPermissionStatusUndetermined
            case .denied:
                systemStatus = EXPermissionStatusDenied
            case .approved:
                systemStatus = EXPermissionStatusGranted
            @unknown default:
                systemStatus = EXPermissionStatusUndetermined
            }
        } else {
            systemStatus = EXPermissionStatusUndetermined
        }
        
        return [
            "status": systemStatus.rawValue,
        ]
    }
    
    public func requestPermissions(resolver resolve: @escaping EXPromiseResolveBlock, rejecter reject: @escaping EXPromiseRejectBlock) {
        guard #available(iOS 16.0, *) else {
            reject("E_UNSUPPORTED_IOS", "Screen Time API is not supported on this iOS version", nil)
            return
        }

        // Already decided: re-requesting authorization throws even when it is
        // already approved, so report the current status instead of asking again.
        if AuthorizationCenter.shared.authorizationStatus == .approved {
            resolve(self.getPermissions())
            return
        }

        Task {
            do {
                try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
            } catch {
                // The request can throw even when the user ultimately approved (or
                // the status changed out of band). Report the resulting status
                // instead of rejecting — matching Expo's permission convention and
                // avoiding an uncaught rejection in the JS permission hook.
                print("[expo-screen-time] requestAuthorization error: \(error)")
            }
            resolve(self.getPermissions())
        }
    }
    
}
