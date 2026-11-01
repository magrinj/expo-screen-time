import ExpoModulesCore

/// The `code` of every error this module rejects or throws with. Mirrors the
/// `ScreenTimeErrorCode` union in `src/errors.ts`; `src/__tests__/errors.test.ts`
/// fails if the two lists drift apart.
enum ScreenTimeErrorCode: String, CaseIterable {
    case unauthorized = "UNAUTHORIZED"
    case unsupported = "UNSUPPORTED"
    case selectionNotFound = "SELECTION_NOT_FOUND"
    case noActiveBlock = "NO_ACTIVE_BLOCK"
    case excessiveActivities = "EXCESSIVE_ACTIVITIES"
    case intervalTooShort = "INTERVAL_TOO_SHORT"
    case intervalTooLong = "INTERVAL_TOO_LONG"
    case invalidDateComponents = "INVALID_DATE_COMPONENTS"
    case invalidConfig = "INVALID_CONFIG"
    case monitoringError = "MONITORING_ERROR"
    case monitoringRollbackFailed = "MONITORING_ROLLBACK_FAILED"
}

/// An error that reaches JS with `code` set, so `isScreenTimeError` narrows
/// it. Throw it from a `Function`, or pass it to `promise.reject`.
func screenTimeError(_ code: ScreenTimeErrorCode, _ message: String) -> Exception {
    return Exception(name: code.rawValue, description: message, code: code.rawValue)
}

extension Promise {
    /// Reject with one of the module's codes.
    func reject(_ code: ScreenTimeErrorCode, _ message: String) {
        reject(screenTimeError(code, message))
    }
}
