package expo.modules.screentime.web

import expo.modules.screentime.web.IpPacket.put16
import expo.modules.screentime.web.IpPacket.u8
import expo.modules.screentime.web.IpPacket.u16
import java.io.ByteArrayOutputStream

/**
 * DNS wire format. An accepted query has exactly one question and at most one well-formed OPT.
 * Duplicate OPT is dropped by the preview spec's malformed-query policy, rather than the FORMERR
 * response required by RFC 6891 section 6.1.1.
 */
internal object DnsMessage {
  enum class Answer(val code: Int) { NODATA(0), SERVFAIL(2), NXDOMAIN(3), BADVERS(16) }
  data class Question(val name: String, val type: Int, val dnsClass: Int, val wire: ByteArray)
  data class Edns(val payloadSize: Int, val version: Int, val dnssecOk: Boolean, val classOffset: Int)
  data class Query(val id: Int, val recursionDesired: Boolean, val question: Question, val edns: Edns?, val wire: ByteArray) {
    fun downstreamLimit(family: IpPacket.Family): Int = minOf(maxOf(edns?.payloadSize ?: 512, 512), family.udpPayload)

    /** "On the way up": cap at 1232, retain a smaller (normalised) client limit. No OPT is invented. */
    fun upstream(): ByteArray = wire.copyOf().also { bytes ->
      require(edns == null || edns.version == 0) { "Answer BADVERS locally; never forward this query" }
      edns?.let { put16(bytes, it.classOffset, it.payloadSize.coerceIn(512, 1232)) }
    }
  }
  private data class Name(val labels: List<ByteArray>, val next: Int) {
    // DNS labels are octets. Escape non-ASCII and literal separators so a binary label cannot
    // masquerade as several hostname labels (or as one of the two local signal names).
    val text: String get() = labels.joinToString(".") { label ->
      label.joinToString("") { byte ->
        val code = byte.toInt() and 255
        if (code in 33..126 && code != 46 && code != 92) code.toChar().toString()
        else "\\" + code.toString().padStart(3, '0')
      }
    }
    fun wire(): ByteArray = ByteArrayOutputStream().apply {
      labels.forEach { write(it.size); write(it) }; write(0)
    }.toByteArray()
  }
  private data class Parsed(val question: Question, val edns: Edns?, val ttlOffsets: List<Int>)

  sealed interface RelayResult {
    data class Answered(val bytes: ByteArray) : RelayResult
    data class Servfail(val bytes: ByteArray) : RelayResult
    data object Discard : RelayResult
  }

  /** BADVERS precedes signal names, which precede the block list; null means forward upstream.
   * Pass the actual family for IPv6 so the echoed OPT advertises its tunnel payload.
   */
  fun localAnswer(query: Query, matcher: DomainMatcher, family: IpPacket.Family = IpPacket.Family.IPV4): ByteArray? {
    val answer = if (query.edns != null && query.edns.version != 0) Answer.BADVERS else when (matcher.match(query.question.name)) {
      DomainMatcher.Match.NXDOMAIN -> Answer.NXDOMAIN
      DomainMatcher.Match.NODATA -> Answer.NODATA
      DomainMatcher.Match.ALLOW -> return null
    }
    return response(query, answer, family)
  }

  fun parseQuery(bytes: ByteArray): Query? {
    if (bytes.size < 12 || bytes.size > 65535) return null
    val flags = u16(bytes, 2)
    // Standard QUERY only; QR, opcode, TC, reserved Z and a nonzero query RCODE are invalid here.
    if (flags and 0xfa4f != 0 || u16(bytes, 6) != 0 || u16(bytes, 8) != 0) return null
    val parsed = parseSections(bytes) ?: return null
    if (parsed.edns != null && u8(bytes, parsed.edns.classOffset + 2) != 0) return null
    return Query(u16(bytes, 0), flags and 0x100 != 0, parsed.question, parsed.edns, bytes.copyOf())
  }

  fun response(query: Query, answer: Answer, family: IpPacket.Family, truncated: Boolean = false): ByteArray {
    // Unsupported EDNS versions take precedence over local policy and upstream failures.
    val actual = if (query.edns != null && query.edns.version != 0) Answer.BADVERS else answer
    val header = ByteArray(12)
    put16(header, 0, query.id)
    put16(header, 2, 0x8080 or (if (query.recursionDesired) 0x100 else 0) or
      (if (actual == Answer.NXDOMAIN) 0x400 else 0) or (if (truncated && actual != Answer.BADVERS) 0x200 else 0) or (actual.code and 15))
    put16(header, 4, 1)
    val opt = if (query.edns == null) byteArrayOf() else ByteArray(11).also {
      put16(header, 10, 1)
      put16(it, 1, 41)
      put16(it, 3, family.udpPayload)
      it[5] = (actual.code ushr 4).toByte() // Extended RCODE; response EDNS version is always zero.
      if (query.edns.dnssecOk) it[7] = 0x80.toByte()
    }
    return header + query.question.wire + opt
  }

  fun truncated(query: Query, family: IpPacket.Family): ByteArray = response(query, Answer.NODATA, family, truncated = true)

  /**
   * Null upstream means a caller-reported timeout/failure. An unrelated or malformed datagram is
   * discarded without completing the pending query. IDs are checked, never rewritten.
   * The optional TTL cap is W-R3's fallback hook; OPT's TTL-shaped EDNS fields are never capped.
   */
  fun relay(query: Query, upstream: ByteArray?, family: IpPacket.Family, tcp: Boolean = false,
    ttlCapSeconds: Int? = null): RelayResult {
    require(ttlCapSeconds == null || ttlCapSeconds >= 0)
    if (query.edns != null && query.edns.version != 0) return RelayResult.Answered(response(query, Answer.BADVERS, family))
    if (upstream == null) return RelayResult.Servfail(response(query, Answer.SERVFAIL, family))
    if (upstream.size !in 12..65535 || u16(upstream, 0) != query.id || u16(upstream, 2) and 0xf800 != 0x8000) return RelayResult.Discard
    val parsed = parseSections(upstream) ?: return RelayResult.Discard
    if (!sameQuestion(parsed.question, query.question)) return RelayResult.Discard
    if (!tcp && upstream.size > query.downstreamLimit(family)) return RelayResult.Answered(truncated(query, family))
    return RelayResult.Answered(upstream.copyOf().also {
      put16(it, 2, (u16(it, 2) and 0xfeff) or if (query.recursionDesired) 0x100 else 0)
      if (ttlCapSeconds != null) parsed.ttlOffsets.forEach { offset ->
        IpPacket.put32(it, offset, minOf(IpPacket.u32(it, offset), ttlCapSeconds.toLong()))
      }
    })
  }

  private fun sameQuestion(a: Question, b: Question): Boolean {
    if (a.type != b.type || a.dnsClass != b.dnsClass || a.wire.size != b.wire.size) return false
    // Compare the expanded wire name, including label boundaries; DNS folds ASCII A–Z only.
    fun fold(byte: Byte): Int = (byte.toInt() and 255).let { if (it in 65..90) it + 32 else it }
    return (0 until a.wire.size - 4).all { fold(a.wire[it]) == fold(b.wire[it]) }
  }

  private fun parseSections(bytes: ByteArray): Parsed? {
    if (u16(bytes, 4) != 1) return null
    // The sole, first question has no preceding domain name that compression could refer to.
    val name = readName(bytes, 12, allowPointers = false) ?: return null
    if (name.next + 4 > bytes.size) return null
    val question = Question(name.text, u16(bytes, name.next), u16(bytes, name.next + 2),
      name.wire() + bytes.copyOfRange(name.next, name.next + 4))
    var at = name.next + 4
    var edns: Edns? = null
    val ttlOffsets = mutableListOf<Int>()
    for (section in 0..2) {
      val count = u16(bytes, 6 + section * 2)
      // A resource record occupies at least 11 bytes. Bound work before walking attacker counts.
      if (count > (bytes.size - at) / 11) return null
      repeat(count) {
        val owner = readName(bytes, at) ?: return null
        at = owner.next
        if (at + 10 > bytes.size) return null
        val type = u16(bytes, at)
        val size = u16(bytes, at + 8)
        val end = at + 10 + size
        if (end > bytes.size) return null
        if (type == 41) {
          if (section != 2 || edns != null || owner.labels.isNotEmpty()) return null
          edns = Edns(u16(bytes, at + 2), u8(bytes, at + 5), u16(bytes, at + 6) and 0x8000 != 0, at + 2)
          var option = at + 10
          while (option < end) {
            if (option + 4 > end) return null
            option += 4 + u16(bytes, option + 2)
            if (option > end) return null
          }
        }
        if (type != 41) ttlOffsets += at + 4
        at = end
      }
    }
    if (at != bytes.size) return null
    return Parsed(question, edns, ttlOffsets)
  }

  private fun readName(bytes: ByteArray, start: Int, allowPointers: Boolean = true): Name? {
    val labels = mutableListOf<ByteArray>()
    val visited = mutableSetOf<Int>()
    var at = start
    var next = -1
    var expanded = 1
    while (true) {
      if (at >= bytes.size || !visited.add(at)) return null
      val length = u8(bytes, at)
      if (length and 0xc0 == 0xc0) {
        if (!allowPointers) return null
        if (at + 1 >= bytes.size) return null
        val target = ((length and 63) shl 8) or u8(bytes, at + 1)
        // Compression refers to a prior name, never the header, itself or forward bytes.
        if (target < 12 || target >= at) return null
        if (next < 0) next = at + 2
        at = target
        continue
      }
      if (length > 63) return null
      at++
      if (length == 0) return Name(labels, if (next < 0) at else next)
      if (at + length > bytes.size) return null
      expanded += length + 1
      if (expanded > 255) return null
      labels += bytes.copyOfRange(at, at + length)
      at += length
    }
  }
}
