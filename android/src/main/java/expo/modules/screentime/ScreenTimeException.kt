package expo.modules.screentime

import expo.modules.kotlin.exception.CodedException

/**
 * A coded error of the `ScreenTimeErrorCode` vocabulary shared with iOS
 * (`src/ExpoScreenTime.types.ts`), so JS tests `error.code` the same way on
 * both platforms.
 */
class ScreenTimeException(code: String, message: String) : CodedException(code, message, null) {
  companion object {
    const val INVALID_CONFIG = "INVALID_CONFIG"
    const val SELECTION_NOT_FOUND = "SELECTION_NOT_FOUND"
  }
}
