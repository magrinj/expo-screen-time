package expo.modules.screentime

import expo.modules.kotlin.exception.CodedException

/**
 * Raised by a function whose feature the config plugin left out of this
 * build (`expo.extra.expoScreenTime.android.features`, read back from the
 * merged manifest by [BuildManifest]). The code is `UNSUPPORTED`, the one
 * the contract uses for a capability a platform does not have, so a JS
 * caller branches on `getCapabilities()` and tests `error.code` the same
 * way as for [UnsupportedPlatformException].
 */
internal class FeatureNotInBuildException(
  functionName: String,
  features: Collection<PermissionSteps.Feature>,
  detail: String? = null,
) : CodedException(
  "UNSUPPORTED",
  "'$functionName' is unavailable in this build: it needs the ${features.joinToString(", ") { "'${it.name}'" }} " +
    "feature${if (features.size > 1) "s" else ""}, which `expo.extra.expoScreenTime.android.features` leaves out" +
    (detail?.let { " ($it)" } ?: "") + ". Check getCapabilities() first.",
  null
)
