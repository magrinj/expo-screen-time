package expo.modules.screentime

import expo.modules.kotlin.exception.CodedException

/**
 * Raised by every function of the module until the Android implementation
 * lands. The code is `UNSUPPORTED`, a
 * member of the `ScreenTimeErrorCode` vocabulary shared with iOS, so a JS
 * caller can test `error.code === "UNSUPPORTED"` on both platforms.
 */
class UnsupportedPlatformException(functionName: String) : CodedException(
  "UNSUPPORTED",
  "expo-screen-time is not implemented on Android yet: '$functionName' is unavailable. " +
    "Track progress in .",
  null
)
