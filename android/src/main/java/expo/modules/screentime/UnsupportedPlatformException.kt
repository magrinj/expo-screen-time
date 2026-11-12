package expo.modules.screentime

import expo.modules.kotlin.exception.CodedException

/**
 * Raised by every function of the module that Android does not implement
 * (those missing from [IMPLEMENTED]). The code is `UNSUPPORTED`, a
 * member of the `ScreenTimeErrorCode` vocabulary shared with iOS, so a JS
 * caller can test `error.code === "UNSUPPORTED"` on both platforms.
 */
class UnsupportedPlatformException(functionName: String) : CodedException(
  "UNSUPPORTED",
  "'$functionName' is not available on Android.",
  null
)
