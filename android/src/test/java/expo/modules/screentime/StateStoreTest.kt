package expo.modules.screentime

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
      // The in-process lock is re-entrant; the file lock is held once.
      assertEquals(0, store.read { it.optInt("a", 0) })
    }
    assertEquals(1, store.read { it.optInt("a", 0) })
  }
}
