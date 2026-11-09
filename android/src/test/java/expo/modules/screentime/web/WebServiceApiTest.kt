package expo.modules.screentime.web

import org.junit.Assert.*
import org.junit.Test

internal class WebServiceApiTest {
  private val family = IpPacket.Family.IPV4
  private val matcher = DomainMatcher(listOf("facebook.com", "resolver.arpa"), DomainIdna { it })

  @Test fun badIdAndQuestionAreDiscardedWithoutModifyingTheMessage() {
    val q = query()
    for (bytes in listOf(
      answerWire(q, 200).also { IpPacket.put16(it, 0, q.id xor 1) },
      answerWire(query(queryWire(name = "different.com")), 200),
      answerWire(query(queryWire(type = 28)), 200),
    )) {
      val original = bytes.copyOf()
      assertSame(DnsMessage.RelayResult.Discard, DnsMessage.relay(q, bytes, family))
      assertArrayEquals(original, bytes)
    }
  }
  @Test fun servfailRequiresExplicitUpstreamFailure() {
    val q = query()
    assertSame(DnsMessage.RelayResult.Discard, DnsMessage.relay(q, byteArrayOf(), family))
    val failure = DnsMessage.relay(q, null, family) as DnsMessage.RelayResult.Servfail
    assertEquals(2, IpPacket.u16(failure.bytes, 2) and 15)
    val answer = DnsMessage.relay(q, answerWire(q, 200), family) as DnsMessage.RelayResult.Answered
    assertEquals(q.id, IpPacket.u16(answer.bytes, 0))
  }
  @Test fun localAnswerPrecedenceIsBadversThenSignalsThenList() {
    for (name in listOf("_dns.resolver.arpa", "use-application-dns.net", "facebook.com", "allowed.com")) {
      val q = query(queryWire(name = name, edns = 4096, version = 1))
      val bytes = DnsMessage.localAnswer(q, matcher, IpPacket.Family.IPV6)!!
      assertEquals(1, IpPacket.u8(bytes, bytes.size - 6))
      assertEquals(1452, IpPacket.u16(bytes, bytes.size - 8))
      assertEquals(0, IpPacket.u16(bytes, 2) and 15)
    }
    assertEquals(0, IpPacket.u16(DnsMessage.localAnswer(query(queryWire(name = "_dns.resolver.arpa")), matcher)!!, 2) and 15)
    for (name in listOf("use-application-dns.net", "_443._tcp.facebook.com", "*.facebook.com", "a_b.facebook.com")) {
      assertEquals(3, IpPacket.u16(DnsMessage.localAnswer(query(queryWire(name = name)), matcher)!!, 2) and 15)
    }
    assertNull(DnsMessage.localAnswer(query(queryWire(name = "allowed.com")), matcher))
  }
  @Test(expected = IllegalArgumentException::class) fun unsupportedEdnsVersionCannotBeForwarded() {
    query(queryWire(edns = 1232, version = 1)).upstream()
  }
  @Test fun escapedWireLabelStillHasABlockedSuffix() {
    val header = queryWire().copyOf(12)
    val name = byteArrayOf(3, 0, 46, 92) + queryWire(name = "facebook.com").copyOfRange(12, 30)
    val q = query(header + name)
    assertEquals("\\000\\046\\092.facebook.com", q.question.name)
    assertNotNull(DnsMessage.localAnswer(q, matcher))
  }
  @Test fun ttlCapChangesOnlyResourceTtlsAndDoesNotTouchOpt() {
    val q = query(queryWire(edns = 1232, doBit = true))
    val wire = answerWire(q, 200) + DnsMessage.response(q, DnsMessage.Answer.NODATA, family).takeLast(11)
    IpPacket.put16(wire, 10, 1)
    val ttl = 12 + q.question.wire.size + 6
    IpPacket.put32(wire, ttl, 0xffffffffL)
    val result = DnsMessage.relay(q, wire, family, ttlCapSeconds = 60) as DnsMessage.RelayResult.Answered
    val expected = wire.copyOf().also { IpPacket.put32(it, ttl, 60) }
    assertArrayEquals(expected, result.bytes)
    assertArrayEquals(wire, (DnsMessage.relay(q, wire, family) as DnsMessage.RelayResult.Answered).bytes)
  }
  @Test fun flowEqualityIncludesEveryTuplePartAndCopiesAddresses() {
    val a = hex("c0000201"); val b = hex("c6336402")
    val flow = Flow(family, 17, a, b, 42000, 53)
    val same = Flow(family, 17, a.copyOf(), b.copyOf(), 42000, 53)
    val map = hashMapOf(flow to "pending")
    a[0] = 0; b[0] = 0; flow.source[1] = 0; flow.destination[1] = 0
    assertEquals(flow, same)
    assertEquals("pending", map[same])
    for (different in listOf(
      Flow(family, 6, same.source, same.destination, 42000, 53),
      Flow(family, 17, same.destination, same.source, 42000, 53),
      Flow(family, 17, same.source, same.destination, 42001, 53),
      Flow(family, 17, same.source, same.destination, 42000, 54),
      Flow(IpPacket.Family.IPV6, 17, ByteArray(16), ByteArray(16), 42000, 53),
    )) assertNotEquals(flow, different)
    val packet = parsedPacket(IpPacket.build(family, same.source, same.destination, IpPacket.Udp(42000, 53, byteArrayOf())))!!
    assertEquals(same, packet.flow)
  }
  @Test fun packetDropsExposeSeparateCountingReasons() {
    val v6 = IpPacket.build(IpPacket.Family.IPV6, ByteArray(16), ByteArray(16), IpPacket.Udp(123, 53, byteArrayOf()))
    assertEquals(IpPacket.ParseResult.Dropped(IpPacket.DropReason.FRAGMENT), IpPacket.parse(v6.copyOf().also { it[6] = 44 }))
    assertEquals(IpPacket.ParseResult.Dropped(IpPacket.DropReason.EXTENSION_HEADER), IpPacket.parse(v6.copyOf().also { it[6] = 60 }))
    assertEquals(IpPacket.ParseResult.Dropped(IpPacket.DropReason.MALFORMED), IpPacket.parse(v6.copyOf(30)))
    assertEquals(IpPacket.ParseResult.Dropped(IpPacket.DropReason.UNSUPPORTED_PROTOCOL), IpPacket.parse(v6.copyOf().also { it[6] = 59 }))
    val v4 = IpPacket.build(family, ByteArray(4), ByteArray(4), IpPacket.Udp(123, 53, byteArrayOf()))
    IpPacket.put16(v4, 6, 0x2000); IpPacket.put16(v4, 10, 0)
    IpPacket.put16(v4, 10, IpPacket.checksum(v4.copyOf(20)))
    assertEquals(IpPacket.ParseResult.Dropped(IpPacket.DropReason.FRAGMENT), IpPacket.parse(v4))
  }
}
