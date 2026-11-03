package expo.modules.screentime

import expo.modules.screentime.PermissionSteps.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the merged manifest of a build makes available: the reading of the
 * config plugin's `android.features` option (`ANDROID_MANIFEST_GATES` in
 * `plugin/src/android/withScreenTimeAndroid.ts`) from the component and
 * permission names PackageManager reports.
 */
class BuildManifestTest {
  private val service = "expo.modules.screentime.ScreenTimeEngineService"
  private val shield = "expo.modules.screentime.ShieldActivity"
  private val admin = "expo.modules.screentime.ScreenTimeDeviceAdminReceiver"
  private val usage = "android.permission.PACKAGE_USAGE_STATS"
  private val overlay = "android.permission.SYSTEM_ALERT_WINDOW"
  private val notifications = "android.permission.POST_NOTIFICATIONS"
  private val everything = listOf(usage, overlay, notifications, "android.permission.FOREGROUND_SERVICE", "android.permission.RECEIVE_BOOT_COMPLETED")

  /** The merged manifest of a build with the given plugin features, as the gate table removes elements. */
  private fun build(vararg features: Feature): BuildManifest {
    val engine = Feature.blocking in features || Feature.monitoring in features
    val blocking = Feature.blocking in features
    return BuildManifest.from(
      services = if (engine) listOf(service) else emptyList(),
      activities = listOf("expo.modules.screentime.example.MainActivity") + if (blocking) listOf(shield, "expo.modules.screentime.PipEvictorActivity") else emptyList(),
      receivers = if (blocking || Feature.appRemovalProtection in features) listOf(admin) else emptyList(),
      permissions = listOf("android.permission.INTERNET") +
        (if (engine || Feature.usageReports in features) listOf(usage) else emptyList()) +
        (if (blocking) listOf(overlay) else emptyList()) +
        (if (engine) listOf(notifications, "android.permission.FOREGROUND_SERVICE", "android.permission.RECEIVE_BOOT_COMPLETED") else emptyList()),
    )
  }

  @Test
  fun `the untouched manifest is the full build`() {
    assertEquals(BuildManifest.full, BuildManifest.from(listOf(service), listOf(shield), listOf(admin), everything))
    assertEquals(BuildManifest.full, build(*Feature.values()))
    assertTrue(BuildManifest.full.blocking)
    assertEquals(emptyList<Feature>(), BuildManifest.full.missing(Feature.values().toList()))
  }

  @Test
  fun `blocking needs the engine, the shield activity and the overlay permission`() {
    assertTrue(build(Feature.blocking).blocking)
    assertFalse(BuildManifest.from(listOf(service), listOf(shield), listOf(admin), everything - overlay).blocking)
    assertFalse(BuildManifest.from(listOf(service), emptyList(), listOf(admin), everything).blocking)
    assertFalse(BuildManifest.from(emptyList(), listOf(shield), listOf(admin), everything).blocking)
  }

  @Test
  fun `a usage-reports build has usage access and nothing else`() {
    val manifest = build(Feature.usageReports)
    assertEquals(BuildManifest(engine = false, shield = false, deviceAdmin = false, usageAccess = true, notifications = false), manifest)
    assertFalse(manifest.blocking)
    assertTrue(manifest.has(Feature.usageReports))
    assertEquals(listOf(Feature.blocking, Feature.monitoring), manifest.missing(PermissionSteps.defaultFeatures))
    assertEquals(listOf(Feature.shieldOpenApp, Feature.appRemovalProtection), manifest.missing(listOf(Feature.usageReports, Feature.shieldOpenApp, Feature.appRemovalProtection)))
  }

  @Test
  fun `a monitoring-only build has the engine without the shield or the device admin`() {
    val manifest = build(Feature.monitoring)
    assertEquals(BuildManifest(engine = true, shield = false, deviceAdmin = false, usageAccess = true, notifications = true), manifest)
    assertFalse(manifest.blocking)
    assertTrue(manifest.has(Feature.monitoring))
    assertEquals(listOf(Feature.blocking), manifest.missing(PermissionSteps.defaultFeatures))
  }

  @Test
  fun `features with nothing of their own follow what they rest on`() {
    // shieldOpenApp is how a shield reaches the app: it needs the shield.
    assertTrue(build(Feature.blocking).has(Feature.shieldOpenApp))
    assertFalse(build(Feature.monitoring).has(Feature.shieldOpenApp))
    // webDomainBlocking removes nothing on Android, so nothing it needs is ever missing.
    assertTrue(build().has(Feature.webDomainBlocking))
    assertTrue(build(Feature.appRemovalProtection).has(Feature.appRemovalProtection))
    assertFalse(build(Feature.usageReports).has(Feature.appRemovalProtection))
  }

  @Test
  fun `a monitoring config shields with a selectionId or a blockOnThreshold event, read as the validator reads them`() {
    assertFalse(monitoringConfigShields(mapOf("activityName" to "a", "schedule" to mapOf<String, Any?>())))
    assertTrue(monitoringConfigShields(mapOf("activityName" to "a", "selectionId" to "apps")))
    assertTrue(monitoringConfigShields(mapOf("events" to listOf(mapOf("eventName" to "e", "selectionId" to "apps", "threshold" to 60, "blockOnThreshold" to true)))))
    assertFalse(monitoringConfigShields(mapOf("events" to listOf(mapOf("eventName" to "e", "selectionId" to "apps", "threshold" to 60, "blockOnThreshold" to false)))))
    assertFalse(monitoringConfigShields(mapOf("events" to listOf(mapOf("eventName" to "e", "selectionId" to "apps", "threshold" to 60)))))
    // A value of the wrong type is absent for the validator, and absent here.
    assertFalse(monitoringConfigShields(mapOf("selectionId" to 42)))
    assertFalse(monitoringConfigShields(mapOf("events" to listOf(mapOf("blockOnThreshold" to "true")))))
    assertFalse(monitoringConfigShields(mapOf("events" to "none")))
  }

  @Test
  fun `the default permission features narrow to what the build has, or stand when it has none`() {
    assertEquals(PermissionSteps.defaultFeatures, PermissionSteps.features(null, BuildManifest.full))
    assertEquals(listOf(Feature.monitoring), PermissionSteps.features(null, build(Feature.monitoring)))
    assertEquals(PermissionSteps.defaultFeatures, PermissionSteps.features(null, build(Feature.usageReports)))
    // An explicit list is the app's statement: not narrowed.
    assertEquals(PermissionSteps.defaultFeatures, PermissionSteps.features(listOf("blocking", "monitoring"), build(Feature.monitoring)))
  }

  @Test
  fun `an empty feature list leaves nothing but the selection store`() {
    val manifest = build()
    assertEquals(BuildManifest(engine = false, shield = false, deviceAdmin = false, usageAccess = false, notifications = false), manifest)
    assertEquals(Feature.values().toList() - Feature.webDomainBlocking, manifest.missing(Feature.values().toList()))
  }
}
