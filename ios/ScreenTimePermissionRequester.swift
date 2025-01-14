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
        if #available(iOS 16.0, *) {
            Task {
                do {
                    try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
                    resolve(self.getPermissions())
                } catch {
                    reject("E_SCREEN_TIME_PERMISSION", "Failed to request Screen Time permissions", error)
                }
            }
        } else {
            reject("E_UNSUPPORTED_IOS", "Screen Time API is not supported on this iOS version", nil)
        }
    }
    
}
