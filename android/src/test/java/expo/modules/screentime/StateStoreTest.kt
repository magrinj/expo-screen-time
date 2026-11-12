package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The state store on a plain JVM (`org.json` from the test classpath): the
 * document round-trips, survives corruption, and `update` serializes
 * writers within one process (the cross-process lock is the kernel's, as
 * for the iOS `lockprobe`; it is exercised on the emulator, where the module
 * and the `:screentime` service share one document).
 */
class StateStoreTest {
  @get:Rule
  val folder = TemporaryFolder()

  private fun store() = StateStore(folder.root.resolve("expo_screen_time"))

  @Test
  fun `an absent document reads as empty state`() {
    val store = store()
    assertFalse(store.engineDesired)
    assertTrue(store.engineFailures().isEmpty())
    assertNull(store.permissionRequestedAt("overlay"))
    assertNull(store.permissionError("overlay"))
  }

  @Test
  fun `the restrictions round-trip and keep the engine wanted on their own`() {
    val store = store()
    assertEquals(Restrictions.NONE, store.restrictions)
    store.restrictions = Restrictions(removal = true)
    assertEquals(Restrictions(removal = true, installation = false), store().restrictions)
    store.update { document -> store.syncEngineDesired(document) }
    assertTrue("a restriction alone keeps the engine wanted", store.engineDesired)
    store.restrictions = Restrictions.NONE
    store.update { document -> store.syncEngineDesired(document) }
    assertFalse(store.engineDesired)
    assertFalse("no restriction leaves no key", store.read { it.has("restrictions") })
  }

  @Test
  fun `the admin's deactivation is stamped beside the restrictions and survives their toggling`() {
    val store = store()
    assertNull(store.adminDisabledAt)
    store.adminDisabledAt = 1_700_000_000_000L
    assertEquals(1_700_000_000_000L, store().adminDisabledAt)
    assertEquals("a stamp alone turns no restriction on", Restrictions.NONE, store.restrictions)
    store.restrictions = Restrictions(removal = true)
    assertEquals(1_700_000_000_000L, store.adminDisabledAt)
    store.restrictions = Restrictions.NONE
    assertEquals("the stamp outlives the flags", 1_700_000_000_000L, store.adminDisabledAt)
    store.adminDisabledAt = null
    assertFalse("nothing left leaves no key", store.read { it.has("restrictions") })
  }

  @Test
  fun `the document round-trips between two instances, as between two processes`() {
    store().engineDesired = true
    store().markPermissionRequested("usageAccess")
    store().setPermissionError("backgroundAutostart", "ActivityNotFoundException", "no screen")
    store().recordEngineFailure("foreground:ForegroundServiceStartNotAllowedException", "not allowed")

    val other = store()
    assertTrue(other.engineDesired)
    assertTrue(other.permissionRequestedAt("usageAccess")!! > 0)
    assertEquals("ActivityNotFoundException" to "no screen", other.permissionError("backgroundAutostart"))
    assertEquals("foreground:ForegroundServiceStartNotAllowedException", other.engineFailures().single().getString("kind"))

    other.setPermissionError("backgroundAutostart", null, null)
    assertNull(store().permissionError("backgroundAutostart"))
  }

  @Test
  fun `the manual block and the shield configs round-trip between instances, with the iOS precedence`() {
    assertNull(store().manualBlock)
    store().manualBlock = ManualBlock("focus", BlockMode.allowlist, setOf("com.a"))
    assertEquals(ManualBlock("focus", BlockMode.allowlist, setOf("com.a")), store().manualBlock)
    assertFalse(store().engineRunning)
    store().engineRunning = true
    assertTrue(store().engineRunning)
    store().manualBlock = null
    assertNull(store().manualBlock)

    assertNull(store().shieldConfig(null))
    store().setDefaultShieldConfig(JSONObject().put("title", JSONObject().put("text", "Default")))
    store().setShieldConfig("bedtime", JSONObject().put("title", JSONObject().put("text", "Bed")))
    // The owner's config when it has one, else the default.
    assertEquals("Bed", store().shieldConfig("bedtime")!!.getJSONObject("title").getString("text"))
    assertEquals("Default", store().shieldConfig("work")!!.getJSONObject("title").getString("text"))
    assertEquals("Default", store().shieldConfig(null)!!.getJSONObject("title").getString("text"))
    store().clearShieldConfigs()
    assertNull(store().shieldConfig("bedtime"))
    assertNull(store().shieldConfig(null))
  }

  @Test
  fun `an update nested in an update keeps both writes, and a nested read sees them`() {
    val store = store()
    store.update { document ->
      document.put("outer", 1)
      // Another instance of the same directory, as SelectionStore's first
      // handle derivation reaches installSecret() from inside save().
      store().update { nested -> nested.put("inner", 2) }
      assertEquals(2, store().read { it.optInt("inner") })
      assertEquals(1, store().read { it.optInt("outer") })
    }
    assertEquals(1, store().read { it.optInt("outer") })
    assertEquals(2, store().read { it.optInt("inner") })
  }

  @Test
  fun `a corrupt document is replaced, not fatal`() {
    val dir = folder.root.resolve("expo_screen_time").apply { mkdirs() }
    dir.resolve("state.json").writeText("{ not json")
    assertFalse(store().engineDesired)
    store().engineDesired = true
    assertTrue(store().engineDesired)
  }

  @Test
  fun `engine failures are capped`() {
    val store = store()
    repeat(25) { store.recordEngineFailure("k$it", null) }
    val kinds = store.engineFailures().map { it.getString("kind") }
    assertEquals(20, kinds.size)
    assertEquals("k5", kinds.first())
    assertEquals("k24", kinds.last())
  }

  @Test
  fun `updates from many threads are serialized`() {
    val store = store()
    val threads = 8
    val perThread = 50
    val ready = CountDownLatch(threads)
    val go = CountDownLatch(1)
    val workers = List(threads) {
      thread {
        ready.countDown()
        go.await()
        repeat(perThread) {
          store.update { document ->
            document.put("counter", document.optInt("counter", 0) + 1)
          }
        }
      }
    }
    ready.await()
    go.countDown()
    workers.forEach { it.join() }
    assertEquals(threads * perThread, store.read { it.optInt("counter", 0) })
  }

  @Test
  fun `an update nested in a read is refused`() {
    val store = store()
    var refused = false
    store.read {
      try {
        store.update { it.put("a", 1) }
      } catch (e: IllegalStateException) {
        refused = true
      }
    }
    assertTrue(refused)
    assertEquals(0, store.read { it.optInt("a", 0) })
  }

  @Test
  fun `a read nested in an update does not deadlock`() {
    val store = store()
    store.update { document ->
      document.put("a", 1)
      // The in-process lock is re-entrant; the file lock is held once, and
      // the nested read sees the document the update is changing.
      assertEquals(1, store.read { it.optInt("a", 0) })
    }
    assertEquals(1, store.read { it.optInt("a", 0) })
  }

  @Test
  fun `shield actions resolve with the iOS precedence - the owning activity's, else the default, else none`() {
    val store = store()
    assertNull(store.read { store.shieldActions(it, null) })
    store.setDefaultShieldActions(JSONObject().put("primaryAction", "close"))
    store.setShieldActions("work", JSONObject().put("primaryAction", "unblock"))
    assertEquals("close", store.read { store.shieldActions(it, null)?.getString("primaryAction") })
    assertEquals("unblock", store.read { store.shieldActions(it, "work")?.getString("primaryAction") })
    assertEquals("an activity without its own falls back to the default", "close", store.read { store.shieldActions(it, "other")?.getString("primaryAction") })
    store.clearShieldActions()
    assertNull(store.read { store.shieldActions(it, "work") })
    assertNull(store.read { store.shieldActions(it, null) })
  }
}
