package expo.modules.screentime.web

import org.junit.Assert.*
import org.junit.Test

internal class IpPacketTest {
  // Independently computed with Python struct.pack('!…'), ipaddress.ip_address().packed and:
  // b += b'\0' * (len(b)%2); s = sum(struct.unpack('!%dH' % (len(b)//2), b))
  // while s >> 16: s = (s & 65535) + (s >> 16)
  // checksum = (~s) & 65535. Pseudo-header = src+dst+00+protocol+u16(length) (v4),
  // or src+dst+u32(length)+000000+protocol (v6). These literals are not codec round trips.
  private val udp4 = hex("450000210000000040118e95c0000201c633640230390035000d9f5c68656c6c6f")
  private val tcp4 = hex("4500002d0000000040068e94c0000201c6336402303900351234567890abcdef501840004808000068656c6c6f")
  private val icmp4 = hex("450000380000000040018e8ec6336402c000020103032d2500000000450000210000000040118e95c0000201c633640230390035000d9f5c")
  private val udp6 = hex("60000000000d114020010db800000000000000000000000120010db800000000000000000000000230390035000d301f68656c6c6f")
  private val tcp6 = hex("600000000019064020010db800000000000000000000000120010db8000000000000000000000002303900351234567890abcdef50184000d8ca000068656c6c6f")
  private val icmp6 = hex("60000000003d3a4020010db800000000000000000000000220010db8000000000000000000000001010431e00000000060000000000d114020010db800000000000000000000000120010db800000000000000000000000230390035000d301f68656c6c6f")

  @Test fun ipv4UdpMatchesPythonVector() = checkUdp(udp4, IpPacket.Family.IPV4)
  @Test fun ipv6UdpMatchesPythonVector() = checkUdp(udp6, IpPacket.Family.IPV6)
  @Test fun ipv4TcpMatchesPythonVector() = checkTcp(tcp4, IpPacket.Family.IPV4)
  @Test fun ipv6TcpMatchesPythonVector() = checkTcp(tcp6, IpPacket.Family.IPV6)
  @Test fun ipv4PortUnreachableMatchesPythonVector() {
    assertArrayEquals(icmp4, IpPacket.portUnreachable(parsedPacket(udp4)!!))
  }
  @Test fun ipv6PortUnreachableMatchesPythonVector() {
    assertArrayEquals(icmp6, IpPacket.portUnreachable(parsedPacket(udp6)!!))
  }
  @Test fun oddLengthChecksumMatchesPython() { assertEquals(0xbc2d, IpPacket.checksum("hello".toByteArray())) }

  @Test fun replySwapsAddressesAndPortsAndChecksums() {
    for (wire in listOf(udp4, udp6)) {
      val packet = parsedPacket(wire)!!
      val reply = parsedPacket(packet.reply(IpPacket.Udp(53, 12345, byteArrayOf(1, 2, 3))))!!
      assertArrayEquals(packet.source, reply.destination)
      assertArrayEquals(packet.destination, reply.source)
      assertEquals(53, reply.transport.sourcePort)
      assertEquals(12345, reply.transport.destinationPort)
    }
  }
  @Test fun shortPacketsAndInvalidVersionsDrop() {
    for (wire in listOf(udp4, udp6, tcp4, tcp6)) for (length in 0 until wire.size) {
      assertNull("length=$length", parsedPacket(wire.copyOf(length)))
    }
    assertNull(parsedPacket(udp4.copyOf().also { it[0] = 0x75 }))
  }
  @Test fun badIpAndTransportChecksumsDrop() {
    assertNull(parsedPacket(udp4.copyOf().also { it[8] = 63 }))
    for (wire in listOf(udp4, udp6, tcp4, tcp6)) {
      assertNull(parsedPacket(wire.copyOf().also { it[it.lastIndex] = 0 }))
    }
  }
  @Test fun zeroUdpChecksumOnlyAllowedOnIpv4() {
    assertNotNull(parsedPacket(udp4.copyOf().also { IpPacket.put16(it, 26, 0) }))
    assertNull(parsedPacket(udp6.copyOf().also { IpPacket.put16(it, 46, 0) }))
  }
  @Test fun computedZeroUdpChecksumEncodedAsAllOnes() {
    val packet = parsedPacket(udp4)!!
    // Payload 0xe334 cancels the v4 pseudo-header + ports + length, independently summed in Python.
    val wire = IpPacket.build(packet.family, packet.source, packet.destination,
      IpPacket.Udp(12345, 53, hex("e334")))
    // Search-free concrete vector: see Python check used alongside the other vectors.
    assertEquals(65535, IpPacket.u16(wire, 26))
    assertNotNull(parsedPacket(wire))
  }
  @Test fun allIpv4FragmentsDropEvenWithValidHeaderChecksum() {
    for (flags in listOf(0x2000, 1, 0x2001, 0x8000)) {
      assertNull(parsedPacket(udp4.copyOf().also { IpPacket.put16(it, 6, flags); fixIp4(it) }))
    }
    assertNotNull(parsedPacket(udp4.copyOf().also { IpPacket.put16(it, 6, 0x4000); fixIp4(it) }))
  }
  @Test fun ipv6ExtensionAndUnknownHeadersDrop() {
    for (next in listOf(0, 43, 44, 50, 51, 59, 60, 253)) {
      assertNull(parsedPacket(udp6.copyOf().also { it[6] = next.toByte() }))
    }
  }
  @Test fun badLengthsAndTcpOffsetsDrop() {
    assertNull(parsedPacket(udp4.copyOf().also { it[0] = 0x44; fixIp4(it) }))
    assertNull(parsedPacket(udp4.copyOf().also { IpPacket.put16(it, 24, 8) }))
    for (offset in listOf(0x40, 0xf0)) {
      assertNull(parsedPacket(tcp4.copyOf().also { it[32] = offset.toByte(); fixTransport(it, 6, 16) }))
    }
  }
  @Test fun ipv4OptionsAreIncludedInHeaderChecksumAndIcmpQuote() {
    val wire = udp4.copyOfRange(0, 20) + byteArrayOf(1, 1, 0, 0) + udp4.copyOfRange(20, udp4.size)
    wire[0] = 0x46
    IpPacket.put16(wire, 2, wire.size)
    fixIp4(wire)
    val packet = parsedPacket(wire)!!
    assertEquals(20 + 8 + 24 + 8, IpPacket.portUnreachable(packet).size)
  }
  @Test fun ipv6IcmpQuoteFitsMinimumMtu() {
    val packet = parsedPacket(udp6)!!
    val large = parsedPacket(IpPacket.build(packet.family, packet.source, packet.destination,
      IpPacket.Udp(12345, 443, ByteArray(1452))))!!
    assertEquals(1280, IpPacket.portUnreachable(large).size)
  }
  @Test fun tcpMssOptionAndPaddingAreParsed() {
    val packet = parsedPacket(tcp6)!!
    val original = packet.transport as IpPacket.Tcp
    val bytes = IpPacket.build(packet.family, packet.source, packet.destination,
      original.copy(options = byteArrayOf(1, 2, 4, 2, 24)))
    val parsed = parsedPacket(bytes)!!.transport as IpPacket.Tcp
    assertEquals(536, parsed.mss())
    assertEquals(8, parsed.options.size)
  }
  @Test fun malformedTcpOptionDropsWithValidChecksum() {
    val packet = parsedPacket(tcp4)!!
    val original = packet.transport as IpPacket.Tcp
    val bytes = IpPacket.build(packet.family, packet.source, packet.destination, original.copy(options = byteArrayOf(2, 4, 2, 24)))
    bytes[41] = 1
    fixTransport(bytes, 6, 16)
    assertNull(parsedPacket(bytes))
  }
  @Test fun resetAcknowledgesSynAndFinOrUsesIncomingAck() {
    val syn = IpPacket.Tcp(123, 853, 0xffffffffL, flags = IpPacket.SYN)
    val reset = IpPacket.reset(syn)!!
    assertEquals(0L, reset.acknowledgement)
    assertEquals(IpPacket.RST or IpPacket.ACK, reset.flags)
    assertEquals(853, reset.sourcePort)
    assertEquals(99L, IpPacket.reset(syn.copy(flags = IpPacket.ACK, acknowledgement = 99))!!.sequence)
    assertEquals(4L, IpPacket.reset(syn.copy(flags = IpPacket.FIN, payload = ByteArray(4)))!!.acknowledgement)
    assertNull(IpPacket.reset(syn.copy(flags = IpPacket.RST)))
  }

  @Test fun ipv6LengthFieldExcludesItsFixedHeader() {
    val packet = parsedPacket(udp6)!!
    val wire = IpPacket.build(packet.family, packet.source, packet.destination,
      IpPacket.Udp(12345, 53, ByteArray(65527)))
    assertEquals(65575, wire.size)
    assertEquals(65535, IpPacket.u16(wire, 4))
    assertEquals(65527, (parsedPacket(wire)!!.transport as IpPacket.Udp).payload.size)
  }

  @Test fun randomMalformedPacketsNeverThrow() {
    val random = java.util.Random(152342)
    repeat(2000) {
      val bytes = ByteArray(random.nextInt(2000))
      random.nextBytes(bytes)
      parsedPacket(bytes)
    }
  }

  private fun checkUdp(wire: ByteArray, family: IpPacket.Family) {
    val packet = parsedPacket(wire)!!
    assertEquals(family, packet.family)
    val udp = packet.transport as IpPacket.Udp
    assertEquals(12345, udp.sourcePort); assertEquals(53, udp.destinationPort)
    assertArrayEquals("hello".toByteArray(), udp.payload)
    assertArrayEquals(wire, IpPacket.build(family, packet.source, packet.destination, udp))
  }
  private fun checkTcp(wire: ByteArray, family: IpPacket.Family) {
    val packet = parsedPacket(wire)!!
    val tcp = packet.transport as IpPacket.Tcp
    assertEquals(0x12345678L, tcp.sequence); assertEquals(0x90abcdefL, tcp.acknowledgement)
    assertEquals(IpPacket.ACK or IpPacket.PSH, tcp.flags); assertEquals(16384, tcp.window)
    assertArrayEquals("hello".toByteArray(), tcp.payload)
    assertArrayEquals(wire, IpPacket.build(family, packet.source, packet.destination, tcp))
  }
  private fun fixIp4(wire: ByteArray) {
    IpPacket.put16(wire, 10, 0)
    IpPacket.put16(wire, 10, IpPacket.checksum(wire.copyOf((wire[0].toInt() and 15) * 4)))
  }
  private fun fixTransport(wire: ByteArray, protocol: Int, checksumOffset: Int) {
    IpPacket.put16(wire, 20 + checksumOffset, 0)
    IpPacket.put16(wire, 20 + checksumOffset, IpPacket.transportChecksum(IpPacket.Family.IPV4,
      wire.copyOfRange(12, 16), wire.copyOfRange(16, 20), protocol, wire.copyOfRange(20, wire.size)))
  }
}
