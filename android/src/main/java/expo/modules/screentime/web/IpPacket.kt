package expo.modules.screentime.web

/** Wire codecs only. No routing, sockets or platform state. Parse results distinguish malformed packets, fragments and unsupported headers. */
internal object IpPacket {
  enum class Family(val addressSize: Int, val headerSize: Int, val udpPayload: Int, val mss: Int) {
    IPV4(4, 20, 1472, 1460), IPV6(16, 40, 1452, 1440)
  }

  enum class DropReason { FRAGMENT, EXTENSION_HEADER, MALFORMED, UNSUPPORTED_PROTOCOL }
  sealed interface ParseResult {
    data class Parsed(val packet: Packet) : ParseResult
    data class Dropped(val reason: DropReason) : ParseResult
  }

  sealed interface Transport { val sourcePort: Int; val destinationPort: Int }
  data class Udp(
    override val sourcePort: Int,
    override val destinationPort: Int,
    val payload: ByteArray,
  ) : Transport
  data class Tcp(
    override val sourcePort: Int,
    override val destinationPort: Int,
    val sequence: Long,
    val acknowledgement: Long = 0,
    val flags: Int,
    val window: Int = 16384,
    val payload: ByteArray = byteArrayOf(),
    val options: ByteArray = byteArrayOf(),
  ) : Transport {
    fun has(flag: Int) = flags and flag != 0
    val sequenceLength: Int get() = payload.size + (if (has(SYN)) 1 else 0) + (if (has(FIN)) 1 else 0)
    /** Null means absent; malformed options are rejected by parse(). */
    fun mss(): Int? {
      var at = 0
      while (at < options.size) {
        val kind = u8(options, at)
        if (kind == 0) break
        if (kind == 1) { at++; continue }
        if (at + 1 >= options.size) return null
        val size = u8(options, at + 1)
        if (size < 2 || at + size > options.size) return null
        if (kind == 2 && size == 4) return u16(options, at + 2)
        at += size
      }
      return null
    }
  }
  data class Packet(
    val family: Family,
    val source: ByteArray,
    val destination: ByteArray,
    val transport: Transport,
    val wire: ByteArray,
  ) {
    val flow: Flow get() = Flow(family, if (transport is Udp) 17 else 6,
      source, destination, transport.sourcePort, transport.destinationPort)
    fun reply(transport: Transport): ByteArray = build(family, destination, source, transport)
  }

  const val FIN = 1
  const val SYN = 2
  const val RST = 4
  const val PSH = 8
  const val ACK = 16

  fun parse(bytes: ByteArray): ParseResult {
    if (bytes.isEmpty()) return ParseResult.Dropped(DropReason.MALFORMED)
    val family: Family
    val start: Int
    val length: Int
    val protocol: Int
    val source: ByteArray
    val destination: ByteArray
    when (u8(bytes, 0) ushr 4) {
      4 -> {
        family = Family.IPV4
        if (bytes.size < 20) return ParseResult.Dropped(DropReason.MALFORMED)
        start = (u8(bytes, 0) and 15) * 4
        length = u16(bytes, 2)
        // Drop MF and all offsets (including a first fragment); DF is allowed.
        if (start < 20 || length < start || length > bytes.size || u16(bytes, 6) and 0x8000 != 0) return ParseResult.Dropped(DropReason.MALFORMED)
        if (checksum(bytes.copyOfRange(0, start)) != 0) return ParseResult.Dropped(DropReason.MALFORMED)
        if (u16(bytes, 6) and 0x3fff != 0) return ParseResult.Dropped(DropReason.FRAGMENT)
        protocol = u8(bytes, 9)
        source = bytes.copyOfRange(12, 16)
        destination = bytes.copyOfRange(16, 20)
      }
      6 -> {
        family = Family.IPV6
        if (bytes.size < 40) return ParseResult.Dropped(DropReason.MALFORMED)
        start = 40
        length = 40 + u16(bytes, 4)
        if (length > bytes.size) return ParseResult.Dropped(DropReason.MALFORMED)
        // All extension headers, including atomic fragments, are deliberately unsupported.
        protocol = u8(bytes, 6)
        if (protocol == 44) return ParseResult.Dropped(DropReason.FRAGMENT)
        if (protocol in setOf(0, 43, 50, 51, 60, 135, 139, 140, 253, 254)) {
          return ParseResult.Dropped(DropReason.EXTENSION_HEADER)
        }
        source = bytes.copyOfRange(8, 24)
        destination = bytes.copyOfRange(24, 40)
      }
      else -> return ParseResult.Dropped(DropReason.MALFORMED)
    }
    val body = bytes.copyOfRange(start, length)
    val transport = when (protocol) {
      17 -> {
        if (body.size < 8 || u16(body, 4) != body.size) return ParseResult.Dropped(DropReason.MALFORMED)
        val sum = u16(body, 6)
        if (sum == 0 && family == Family.IPV6) return ParseResult.Dropped(DropReason.MALFORMED)
        if (sum != 0 && transportChecksum(family, source, destination, protocol, body) != 0) return ParseResult.Dropped(DropReason.MALFORMED)
        Udp(u16(body, 0), u16(body, 2), body.copyOfRange(8, body.size))
      }
      6 -> {
        if (body.size < 20) return ParseResult.Dropped(DropReason.MALFORMED)
        val header = (u8(body, 12) ushr 4) * 4
        if (header < 20 || header > body.size) return ParseResult.Dropped(DropReason.MALFORMED)
        if (transportChecksum(family, source, destination, protocol, body) != 0) return ParseResult.Dropped(DropReason.MALFORMED)
        val options = body.copyOfRange(20, header)
        if (!validTcpOptions(options)) return ParseResult.Dropped(DropReason.MALFORMED)
        Tcp(u16(body, 0), u16(body, 2), u32(body, 4), u32(body, 8), u8(body, 13),
          u16(body, 14), body.copyOfRange(header, body.size), options)
      }
      else -> return ParseResult.Dropped(DropReason.UNSUPPORTED_PROTOCOL)
    }
    return ParseResult.Parsed(Packet(family, source, destination, transport, bytes.copyOf(length)))
  }

  fun build(family: Family, source: ByteArray, destination: ByteArray, transport: Transport): ByteArray {
    val protocol: Int
    val body: ByteArray
    require(transport.sourcePort in 0..65535 && transport.destinationPort in 0..65535)
    when (transport) {
      is Udp -> {
        protocol = 17
        body = ByteArray(8 + transport.payload.size)
        put16(body, 4, body.size)
        transport.payload.copyInto(body, 8)
      }
      is Tcp -> {
        protocol = 6
        require(transport.options.size <= 40 && validTcpOptions(transport.options))
        require(transport.window in 0..65535 && transport.sequence in 0..0xffffffffL &&
          transport.acknowledgement in 0..0xffffffffL)
        val header = 20 + (transport.options.size + 3) / 4 * 4
        body = ByteArray(header + transport.payload.size)
        put32(body, 4, transport.sequence)
        put32(body, 8, transport.acknowledgement)
        body[12] = ((header / 4) shl 4).toByte()
        body[13] = transport.flags.toByte()
        put16(body, 14, transport.window)
        transport.options.copyInto(body, 20)
        transport.payload.copyInto(body, header)
      }
    }
    put16(body, 0, transport.sourcePort)
    put16(body, 2, transport.destinationPort)
    val sum = transportChecksum(family, source, destination, protocol, body)
    put16(body, if (protocol == 17) 6 else 16, if (protocol == 17 && sum == 0) 65535 else sum)
    return wrap(family, source, destination, protocol, body)
  }

  /** RFC 792 quotes the IP header + 8 bytes; RFC 4443 quotes up to the IPv6 minimum MTU. */
  fun portUnreachable(packet: Packet): ByteArray {
    require(packet.transport is Udp)
    val v4 = packet.family == Family.IPV4
    val quoteLength = if (v4) (u8(packet.wire, 0) and 15) * 4 + 8 else 1280 - 40 - 8
    val body = ByteArray(8) + packet.wire.copyOf(minOf(quoteLength, packet.wire.size))
    body[0] = (if (v4) 3 else 1).toByte()
    body[1] = (if (v4) 3 else 4).toByte()
    val sum = if (v4) checksum(body) else transportChecksum(
      packet.family, packet.destination, packet.source, 58, body)
    put16(body, 2, sum)
    return wrap(packet.family, packet.destination, packet.source, if (v4) 1 else 58, body)
  }

  /** RFC 9293 reset response; never respond to a reset with another reset. */
  fun reset(segment: Tcp): Tcp? {
    if (segment.has(RST)) return null
    return Tcp(segment.destinationPort, segment.sourcePort,
      if (segment.has(ACK)) segment.acknowledgement else 0,
      if (segment.has(ACK)) 0 else addSequence(segment.sequence, segment.sequenceLength),
      if (segment.has(ACK)) RST else RST or ACK, window = 0)
  }

  fun checksum(bytes: ByteArray): Int {
    var sum = 0L
    var at = 0
    while (at < bytes.size) {
      sum += (u8(bytes, at) shl 8) + if (at + 1 < bytes.size) u8(bytes, at + 1) else 0
      at += 2
    }
    while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
    return sum.toInt().inv() and 65535
  }

  fun transportChecksum(family: Family, source: ByteArray, destination: ByteArray, protocol: Int, body: ByteArray): Int {
    require(source.size == family.addressSize && destination.size == family.addressSize)
    val tail = if (family == Family.IPV4) byteArrayOf(0, protocol.toByte(), 0, 0).also {
      put16(it, 2, body.size)
    } else ByteArray(8).also { put32(it, 0, body.size.toLong()); it[7] = protocol.toByte() }
    return checksum(source + destination + tail + body)
  }

  private fun wrap(family: Family, source: ByteArray, destination: ByteArray, protocol: Int, body: ByteArray): ByteArray {
    require(source.size == family.addressSize && destination.size == family.addressSize)
    // IPv4's length includes its header; IPv6's 16-bit payload length does not.
    require(body.size <= 65535 - if (family == Family.IPV4) family.headerSize else 0)
    val bytes = ByteArray(family.headerSize + body.size)
    if (family == Family.IPV4) {
      bytes[0] = 0x45
      put16(bytes, 2, bytes.size)
      bytes[8] = 64
      bytes[9] = protocol.toByte()
      source.copyInto(bytes, 12)
      destination.copyInto(bytes, 16)
      put16(bytes, 10, checksum(bytes.copyOf(20)))
    } else {
      bytes[0] = 0x60
      put16(bytes, 4, body.size)
      bytes[6] = protocol.toByte()
      bytes[7] = 64
      source.copyInto(bytes, 8)
      destination.copyInto(bytes, 24)
    }
    body.copyInto(bytes, family.headerSize)
    return bytes
  }

  private fun validTcpOptions(options: ByteArray): Boolean {
    var at = 0
    var mssSeen = false
    while (at < options.size) {
      val kind = u8(options, at)
      if (kind == 0) return true
      if (kind == 1) { at++; continue }
      if (at + 1 >= options.size) return false
      val size = u8(options, at + 1)
      if (size < 2 || at + size > options.size) return false
      if (kind == 2) {
        if (mssSeen || size != 4 || u16(options, at + 2) == 0) return false
        mssSeen = true
      }
      at += size
    }
    return true
  }

  fun addSequence(sequence: Long, length: Int): Long = (sequence + length) and 0xffffffffL
  fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 255
  fun u16(bytes: ByteArray, at: Int): Int = (u8(bytes, at) shl 8) or u8(bytes, at + 1)
  fun u32(bytes: ByteArray, at: Int): Long = (u16(bytes, at).toLong() shl 16) or u16(bytes, at + 2).toLong()
  fun put16(bytes: ByteArray, at: Int, value: Int) { bytes[at] = (value ushr 8).toByte(); bytes[at + 1] = value.toByte() }
  fun put32(bytes: ByteArray, at: Int, value: Long) { put16(bytes, at, (value ushr 16).toInt()); put16(bytes, at + 2, value.toInt()) }
}
