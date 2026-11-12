package expo.modules.screentime

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * The opaque strings JS sees for a selection's members, derived from a
 * per-install secret ([StateStore.installSecret]).
 *
 * On iOS a `TokenHandle.handle` is the token's own encoding: Apple's tokens
 * are undecodable blobs even natively, so the handle reveals nothing. A
 * package name is not undecodable, so the Android handle is
 * `HMAC-SHA256(secret, "<type>\0<id>")`, base64url: stable for the life of
 * the install (the same app gets the same handle after a restart), never
 * `base64(packageName)`, and useless on another install (a
 * different secret). Nothing in JS can get a package name out of a handle,
 * or build a handle for a package it names: the native map in
 * [SelectionStore] is the only way back.
 *
 * Pure (javax.crypto only), unit-tested on a plain JVM.
 */
internal object TokenHandles {
  const val APPLICATION = "application"
  const val CATEGORY = "category"
  const val WEB_DOMAIN = "webDomain"

  fun derive(secret: ByteArray, type: String, id: String): String =
    Base64.UrlSafe.encode(hmac(secret, "$type\u0000$id".toByteArray(Charsets.UTF_8)))

  /** The `TokenHandle` records of [selection], sorted by type then handle, as `SelectionStore.swift` orders them. */
  fun of(secret: ByteArray, selection: Selection): List<Map<String, String>> =
    (selection.applications.map { APPLICATION to it } +
      selection.categories.map { CATEGORY to it } +
      selection.webDomains.map { WEB_DOMAIN to it })
      .map { (type, id) -> mapOf("handle" to derive(secret, type, id), "type" to type) }
      .sortedWith(compareBy({ it["type"] }, { it["handle"] }))

  /** The member of [selection] whose handle is [handle], as (type, id), or null. */
  fun resolve(secret: ByteArray, selection: Selection, handle: String): Pair<String, String>? =
    selection.applications.firstOrNull { derive(secret, APPLICATION, it) == handle }?.let { APPLICATION to it }
      ?: selection.categories.firstOrNull { derive(secret, CATEGORY, it) == handle }?.let { CATEGORY to it }
      ?: selection.webDomains.firstOrNull { derive(secret, WEB_DOMAIN, it) == handle }?.let { WEB_DOMAIN to it }

  internal fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256"))
    doFinal(data)
  }
}

/**
 * `exportSelection` / `importSelection` blobs.
 *
 * The contract makes a blob platform-tagged and bound to the device: an iOS
 * blob is the selection's encoded tokens (meaningless elsewhere), and
 * `importSelection` answers `false` for a blob of the other platform. The
 * Android blob is the selection JSON encrypted (AES-256-GCM) under a
 * key derived from the install secret, behind a textual tag:
 *
 *     expo-screen-time/android/1:<base64(iv ‖ ciphertext ‖ tag)>
 *
 * - Opaque to JS: a package name never appears in a blob, in any encoding,
 *   so nothing in JS can start depending on package names (the reason the
 *   handles are salted).
 * - Bound to the install, as the handles are: another install (another
 *   secret) cannot decrypt it, so `importSelection` answers `false` there,
 *   as it does on another iPhone.
 * - The tag is what makes an Android blob refused on iOS, with no change
 *   there: `/`, `-` and `:` are not base64 characters, so
 *   `Data(base64Encoded:)` fails before the token decoder runs; an untagged
 *   iOS blob still imports on iOS and is refused here (no tag).
 */
internal object SelectionBlob {
  private const val TAG = "expo-screen-time/android/1:"
  private const val IV_BYTES = 12
  private const val TAG_BITS = 128
  private val random = SecureRandom()

  fun encode(secret: ByteArray, selection: Selection): String {
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(TAG_BITS, iv))
    val ciphertext = cipher.doFinal(selection.toJson().toString().toByteArray(Charsets.UTF_8))
    return TAG + Base64.encode(iv + ciphertext)
  }

  /** The selection [blob] holds, or null: not an Android blob, another install's, truncated, tampered with, or malformed inside. */
  fun decode(secret: ByteArray, blob: String): Selection? {
    if (!blob.startsWith(TAG)) return null
    return try {
      val bytes = Base64.decode(blob.substring(TAG.length))
      if (bytes.size <= IV_BYTES) return null
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
      val json = String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
      Selection.fromJson(org.json.JSONObject(json))
    } catch (e: Exception) {
      // IllegalArgumentException (base64), AEADBadTagException (another
      // install, tampering), JSONException (not a document): all "no".
      null
    }
  }

  private fun key(secret: ByteArray) = SecretKeySpec(TokenHandles.hmac(secret, "selection-blob".toByteArray(Charsets.UTF_8)), "AES")
}
