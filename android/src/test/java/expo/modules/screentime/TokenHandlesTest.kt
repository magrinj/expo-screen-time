package expo.modules.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64

/**
 * The handles and the export blobs on a plain JVM: what JS gets is stable
 * on one install, different on another, and never the package name in any
 * encoding.
 */
class TokenHandlesTest {
  private val secret = ByteArray(32) { it.toByte() }
  private val otherInstall = ByteArray(32) { (it + 1).toByte() }
  private val selection = Selection(applications = setOf("com.example.social", "com.example.games"))

  @Test
  fun `a handle is stable for one install and differs across installs`() {
    val handle = TokenHandles.derive(secret, TokenHandles.APPLICATION, "com.example.social")
    assertEquals(handle, TokenHandles.derive(secret, TokenHandles.APPLICATION, "com.example.social"))
    assertNotEquals(handle, TokenHandles.derive(otherInstall, TokenHandles.APPLICATION, "com.example.social"))
    assertNotEquals(handle, TokenHandles.derive(secret, TokenHandles.CATEGORY, "com.example.social"))
    assertNotEquals(handle, TokenHandles.derive(secret, TokenHandles.APPLICATION, "com.example.games"))
  }

  @Test
  fun `a handle is opaque, never an encoding of the package name`() {
    val packageName = "com.example.social"
    val handle = TokenHandles.derive(secret, TokenHandles.APPLICATION, packageName)
    assertFalse(handle.contains(packageName))
    assertNotEquals(Base64.encode(packageName.toByteArray()), handle)
    assertNotEquals(Base64.UrlSafe.encode(packageName.toByteArray()), handle)
    assertTrue("a base64url string", handle.matches(Regex("[A-Za-z0-9_=-]+")))
  }

  @Test
  fun `the TokenHandle records are sorted by type then handle and resolve back natively`() {
    val tokens = TokenHandles.of(secret, selection)
    assertEquals(2, tokens.size)
    assertTrue(tokens.all { it["type"] == TokenHandles.APPLICATION })
    assertEquals(tokens.map { it["handle"]!! }.sorted(), tokens.map { it["handle"]!! })
    for (token in tokens) {
      val (type, id) = TokenHandles.resolve(secret, selection, token["handle"]!!)!!
      assertEquals(TokenHandles.APPLICATION, type)
      assertTrue(id in selection.applications)
    }
    assertNull(TokenHandles.resolve(secret, selection, "not-a-handle"))
    assertNull("another install's handle", TokenHandles.resolve(secret, selection, TokenHandles.derive(otherInstall, TokenHandles.APPLICATION, "com.example.social")))
  }

  @Test
  fun `an export blob round-trips on the same install and hides the packages`() {
    val blob = SelectionBlob.encode(secret, selection)
    assertTrue(blob.startsWith("expo-screen-time/android/1:"))
    assertEquals(selection, SelectionBlob.decode(secret, blob))
    for (packageName in selection.applications) {
      assertFalse(blob.contains(packageName))
      assertFalse(blob.contains(Base64.encode(packageName.toByteArray())))
    }
    assertNotEquals("a fresh IV each time", blob, SelectionBlob.encode(secret, selection))
  }

  @Test
  fun `a blob of another install, an iOS blob or a malformed one is refused`() {
    val blob = SelectionBlob.encode(secret, selection)
    assertNull("another install", SelectionBlob.decode(otherInstall, blob))
    // What iOS exports: base64 of the FamilyActivitySelection JSON, untagged.
    val ios = Base64.encode("""{"applicationTokens":[],"categoryTokens":[],"webDomainTokens":[]}""".toByteArray())
    assertNull("iOS blob", SelectionBlob.decode(secret, ios))
    assertNull(SelectionBlob.decode(secret, "not a selection blob"))
    assertNull(SelectionBlob.decode(secret, "AAAA"))
    assertNull(SelectionBlob.decode(secret, ""))
    assertNull("tag only", SelectionBlob.decode(secret, "expo-screen-time/android/1:"))
    assertNull("bad base64 after the tag", SelectionBlob.decode(secret, "expo-screen-time/android/1:***"))
    assertNull("truncated", SelectionBlob.decode(secret, blob.dropLast(8)))
    val tampered = blob.dropLast(1) + if (blob.last() == 'A') 'B' else 'A'
    assertNull("tampered", SelectionBlob.decode(secret, tampered))
  }

  @Test
  fun `an Android blob is not base64, so Data(base64Encoded) refuses it on iOS`() {
    // iOS imports with `Data(base64Encoded:)` first: the tag's characters
    // outside the base64 alphabet guarantee the failure before any decoding.
    val blob = SelectionBlob.encode(secret, selection)
    assertFalse(blob.matches(Regex("[A-Za-z0-9+/=]+")))
  }
}
