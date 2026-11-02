package expo.modules.screentime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selection model: the algebra and the document codec, with its refusal of malformed documents. */
class SelectionTest {
  private val a = Selection(applications = setOf("com.a", "com.b"), categories = setOf("GAME"))
  private val b = Selection(applications = setOf("com.b", "com.c"), webDomains = setOf("example.com"))

  @Test
  fun `union, intersection and difference work per kind`() {
    assertEquals(Selection(setOf("com.a", "com.b", "com.c"), setOf("GAME"), setOf("example.com")), a.union(b))
    assertEquals(Selection(applications = setOf("com.b")), a.intersect(b))
    assertEquals(Selection(applications = setOf("com.a"), categories = setOf("GAME")), a.subtract(b))
    assertTrue(Selection.EMPTY.isEmpty)
    assertEquals(mapOf("applicationCount" to 2, "categoryCount" to 1, "webDomainCount" to 0), a.metadata())
  }

  @Test
  fun `the document round-trips with sorted members`() {
    val json = a.toJson()
    assertEquals("""["com.a","com.b"]""", json.getJSONArray("applications").toString())
    assertEquals(a, Selection.fromJson(JSONObject(json.toString())))
    assertEquals(Selection.EMPTY, Selection.fromJson(Selection.EMPTY.toJson()))
  }

  @Test
  fun `a malformed document is refused whole`() {
    assertNull(Selection.fromJson(JSONObject()))
    assertNull("a missing kind", Selection.fromJson(JSONObject("""{"applications":[],"categories":[]}""")))
    assertNull("not an array", Selection.fromJson(JSONObject("""{"applications":"com.a","categories":[],"webDomains":[]}""")))
    assertNull("not a string", Selection.fromJson(JSONObject("""{"applications":[1],"categories":[],"webDomains":[]}""")))
    assertNull("empty id", Selection.fromJson(JSONObject("""{"applications":[""],"categories":[],"webDomains":[]}""")))
    assertNull("an iOS document", Selection.fromJson(JSONObject("""{"applicationTokens":[],"categoryTokens":[],"webDomainTokens":[]}""")))
  }
}
