package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The selection store on a plain JVM, with the same answers as
 * `ios/SelectionStore.swift`: CRUD, metadata, handles, set operations,
 * export/import, through two instances (as two processes).
 */
class SelectionStoreTest {
  @get:Rule
  val folder = TemporaryFolder()

  private fun stateStore() = StateStore(folder.root.resolve("expo_screen_time"))
  private fun store() = SelectionStore(stateStore())

  private val social = Selection(applications = setOf("com.example.social", "com.example.chat"))
  private val games = Selection(applications = setOf("com.example.games", "com.example.chat"))

  @Test
  fun `the install secret is created once and shared by every instance`() {
    val first = stateStore().installSecret()
    assertEquals(32, first.size)
    assertTrue(first.contentEquals(stateStore().installSecret()))
    val other = StateStore(folder.root.resolve("another_install")).installSecret()
    assertFalse(first.contentEquals(other))
  }

  @Test
  fun `an unknown selection answers like iOS`() {
    val store = store()
    assertFalse(store.has("missing"))
    assertNull(store.get("missing"))
    assertNull(store.metadata("missing"))
    assertNull(store.tokens("missing"))
    assertNull(store.export("missing"))
    assertTrue(store.list().isEmpty())
    store.removeToken("missing", "handle") // a no-op, not an error
    store.delete("missing")
  }

  @Test
  fun `a selection round-trips between instances with stable handles`() {
    store().save("social", social)
    val other = store()
    assertTrue(other.has("social"))
    assertEquals(social, other.get("social"))
    assertEquals(mapOf("applicationCount" to 2, "categoryCount" to 0, "webDomainCount" to 0), other.metadata("social"))
    assertEquals(listOf("social"), other.list())
    val tokens = other.tokens("social")!!
    assertEquals(2, tokens.size)
    assertEquals(tokens, store().tokens("social"))
    assertTrue(tokens.none { it["handle"]!!.contains("com.example") })
    // Every handle resolves through the map, with and without its selection.
    for (token in tokens) {
      val handle = token["handle"]!!
      assertEquals(store().resolve(handle, "social"), store().resolve(handle, null))
      assertTrue(store().resolve(handle, null)!!.second in social.applications)
    }
    assertNull(store().resolve("not-a-handle", null))
    assertNull(store().resolve("not-a-handle", "social"))
  }

  @Test
  fun `a handle keeps resolving after its app left the selection`() {
    val store = store()
    store.save("social", social)
    val handle = store.tokens("social")!!.first()["handle"]!!
    val target = store.resolve(handle, null)!!
    store.removeToken("social", handle)
    assertEquals(1, store.metadata("social")!!["applicationCount"])
    assertNull("not inside the selection any more", store.resolve(handle, "social"))
    assertEquals("still self-describing", target, store.resolve(handle, null))
    store.delete("social")
    assertFalse(store.has("social"))
    assertEquals(target, store().resolve(handle, null))
  }

  @Test
  fun `the picker's load creates a missing selection and its tap applies one app to the stored one`() {
    val store = store()
    assertEquals(Selection.EMPTY, store.load("picked"))
    assertTrue("mounting the picker saves an empty selection, as iOS does", store.has("picked"))
    assertEquals(Selection(applications = setOf("com.a")), store.toggleApplication("picked", "com.a", true))
    // Another writer (an import, a set operation) adds apps; the next tap keeps them.
    store().save("picked", Selection(applications = setOf("com.a", "com.b")))
    assertEquals(Selection(applications = setOf("com.b")), store.toggleApplication("picked", "com.a", false))
    assertEquals(Selection(applications = setOf("com.b")), store.load("picked"))
    assertEquals(1, store.tokens("picked")!!.size)
  }

  @Test
  fun `set operations follow the Swift semantics`() {
    val store = store()
    store.save("social", social)
    store.save("games", games)

    store.merge("all", listOf("social", "games", "missing"))
    assertEquals(social.union(games), store.get("all"))
    store.merge("none", listOf("missing"))
    assertEquals("an empty selection is saved", Selection.EMPTY, store.get("none"))

    store.keepCommon("common", listOf("social", "games"))
    assertEquals(Selection(applications = setOf("com.example.chat")), store.get("common"))
    store.keepCommon("unsaved", listOf("missing", "social"))
    assertFalse("a missing first source saves nothing", store.has("unsaved"))
    store.keepCommon("emptied", listOf("social", "missing"))
    assertEquals("a missing later source saves an empty selection", Selection.EMPTY, store.get("emptied"))
    store.keepCommon("nothing", emptyList())
    assertFalse(store.has("nothing"))

    store.subtract("diff", "social", "games")
    assertEquals(Selection(applications = setOf("com.example.social")), store.get("diff"))
    store.subtract("unsaved", "social", "missing")
    assertFalse(store.has("unsaved"))
    store.subtract("unsaved", "missing", "social")
    assertFalse(store.has("unsaved"))
  }

  @Test
  fun `export and import are bound to the install and refuse foreign blobs`() {
    val store = store()
    store.save("social", social)
    val blob = store.export("social")!!
    assertTrue(store().import("restored", blob))
    assertEquals(social, store().get("restored"))
    assertEquals(store.tokens("social"), store().tokens("restored"))

    val other = SelectionStore(StateStore(folder.root.resolve("another_install")))
    assertFalse("another install", other.import("restored", blob))
    assertFalse(other.has("restored"))

    assertFalse(store.import("restored", "not a selection blob"))
    assertFalse(store.import("restored", "AAAA"))
    assertEquals("a refused import leaves the selection untouched", social, store.get("restored"))
  }

  @Test
  fun `the document holds the selection and the handle map as plain JSON`() {
    store().save("social", social)
    val document = stateStore().read { JSONObject(it.toString()) }
    val stored = document.getJSONObject("selections").getJSONObject("social")
    assertEquals(2, stored.getJSONArray("applications").length())
    assertEquals(0, stored.getJSONArray("categories").length())
    val tokens = document.getJSONObject("tokens")
    assertEquals(2, tokens.length())
    for (key in tokens.keys()) {
      assertNotEquals(key, tokens.getJSONObject(key).getString("id"))
      assertEquals("application", tokens.getJSONObject(key).getString("type"))
    }
  }
}
