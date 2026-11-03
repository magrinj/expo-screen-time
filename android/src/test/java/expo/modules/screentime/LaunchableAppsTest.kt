package expo.modules.screentime

import org.junit.Assert.assertEquals
import org.junit.Test

/** The picker's list: the launchable apps minus the ones the engine never shields. */
class LaunchableAppsTest {
  private val clock = LaunchableApp("com.android.deskclock", "Clock")
  private val settings = LaunchableApp("com.android.settings", "Settings")
  private val launcher = LaunchableApp("com.android.launcher3", "Launcher")
  private val chat = LaunchableApp("com.example.chat", "Chat")

  @Test
  fun `the exempt apps are left out, the others keep their order`() {
    val exempt = ShieldPolicy.exemptions(
      host = "com.example.host",
      launchers = setOf("com.android.launcher3"),
      systemUi = "com.android.systemui",
      dialer = "com.android.dialer",
      emergency = setOf("com.android.emergency"),
      inputMethod = "com.google.android.inputmethod.latin",
      settings = "com.android.settings",
    )
    assertEquals(listOf(clock, chat), LaunchableApps.selectable(listOf(settings, clock, launcher, chat), exempt))
  }

  @Test
  fun `no exemptions, same list`() {
    val apps = listOf(settings, clock)
    assertEquals(apps, LaunchableApps.selectable(apps, emptySet()))
  }

  @Test
  fun `an exemption the device does not list is simply absent`() {
    assertEquals(listOf(clock), LaunchableApps.selectable(listOf(clock), setOf("com.android.settings", "com.example.host")))
  }
}
