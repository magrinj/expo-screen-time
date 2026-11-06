package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The shield configuration cache on a plain JVM: the owner precedence of the
 * resolved configs, and the invalidation rule — the cache is keyed by the
 * document's signature, so a write by another store instance (another
 * process, in production) is seen without the engine being told, and an
 * unchanged document is never re-read.
 */
class ShieldCacheTest {
  @get:Rule
  val folder = TemporaryFolder()

  private fun store() = StateStore(folder.root.resolve("expo_screen_time"))

  @Before
  fun reset() {
    // The cache is process-wide; each test starts with a document of its own
    // (a fresh folder) and a cache that has never read it.
    ShieldCache.refresh(store())
  }

  private val red = JSONObject("""{"backgroundColor":"#ff0000","title":{"text":"Red"}}""")
  private val blue = JSONObject("""{"backgroundColor":"#0000ff","title":{"text":"Blue"}}""")

  @Test
  fun `the configs resolve with the iOS precedence`() {
    val document = JSONObject().put(
      "shield",
      JSONObject().put("default", red).put("activities", JSONObject().put("focus", blue)),
    )
    val configs = ShieldConfigs.from(document)
    assertEquals("Blue", configs.appearance("focus")?.title?.text)
    assertEquals("Red", configs.appearance("other")?.title?.text)
    assertEquals("Red", configs.appearance(null)?.title?.text)
    assertSame(ShieldAppearance.DEFAULT, ShieldConfigs.from(JSONObject()).appearance(null))
    assertSame(ShieldAppearance.DEFAULT, ShieldConfigs.from(JSONObject().put("shield", JSONObject())).appearance("focus"))
  }

  @Test
  fun `an unchanged document is not re-read`() {
    val store = store()
    store.setDefaultShieldConfig(red)
    assertTrue(ShieldCache.refresh(store))
    assertFalse(ShieldCache.refresh(store))
    assertFalse(ShieldCache.refresh(store()))
    assertEquals("Red", ShieldCache.appearance(store, null).title?.text)
  }

  @Test
  fun `a write by another instance is seen on the next lookup without a refresh`() {
    val engine = store()
    engine.setDefaultShieldConfig(red)
    assertEquals("Red", ShieldCache.appearance(engine, null).title?.text)
    // The module process writes; the engine is not told.
    store().setDefaultShieldConfig(blue)
    assertEquals("Blue", ShieldCache.appearance(engine, null).title?.text)
    store().setShieldConfig("focus", red)
    assertEquals("Red", ShieldCache.appearance(engine, "focus").title?.text)
    assertEquals("Blue", ShieldCache.appearance(engine, "other").title?.text)
    store().clearShieldConfigs()
    assertSame(ShieldAppearance.DEFAULT, ShieldCache.appearance(engine, "focus"))
  }

  @Test
  fun `two saves in the same millisecond still differ by signature`() {
    val store = store()
    store.setDefaultShieldConfig(red)
    val first = store.signature()
    // Same length as the first (a colour toggled): only the time can tell them apart.
    store.setDefaultShieldConfig(JSONObject("""{"backgroundColor":"#00ff00","title":{"text":"Red"}}"""))
    val second = store.signature()
    assertNotEquals(first, second)
    assertTrue("mtime must increase strictly", second.modifiedAt > first.modifiedAt)
    assertEquals(first.size, second.size)
  }

  @Test
  fun `the face map is bounded and keeps the recently shown faces`() {
    ShieldCache.dropFaces()
    for (i in 1..ShieldCache.MAX_CACHED_FACES) ShieldCache.remember("app$i", ShieldCache.Face("App $i", null))
    assertEquals(ShieldCache.MAX_CACHED_FACES, ShieldCache.faceCount)
    // app1 is shown again (a lookup), so app2 is the least recently used when one more arrives.
    assertEquals("App 1", ShieldCache.cached("app1")?.label)
    ShieldCache.remember("extra", ShieldCache.Face("Extra", null))
    assertEquals(ShieldCache.MAX_CACHED_FACES, ShieldCache.faceCount)
    assertTrue(ShieldCache.hasFace("extra"))
    assertTrue(ShieldCache.hasFace("app1"))
    assertFalse(ShieldCache.hasFace("app2"))
    ShieldCache.dropFaces()
    assertEquals(0, ShieldCache.faceCount)
  }

  @Test
  fun `a write to another section changes the signature and keeps the configs`() {
    val store = store()
    store.setDefaultShieldConfig(red)
    ShieldCache.refresh(store)
    store.engineDesired = true
    assertTrue(ShieldCache.refresh(store))
    assertEquals("Red", ShieldCache.appearance(store, null).title?.text)
  }
}
