package expo.modules.screentime.web

import org.junit.Assert.*
import org.junit.Test

internal class DnsMessageTest {
  private val v4 = IpPacket.Family.IPV4
  private val v6 = IpPacket.Family.IPV6

  @Test fun headerAndQuestionAreDecoded() {
    val parsed = query(queryWire(name = "WWW.Example.com", type = 65))
    assertEquals(0x1234, parsed.id)
    assertEquals("WWW.Example.com", parsed.question.name)
    assertEquals(65, parsed.question.type)
    assertEquals(1, parsed.question.dnsClass)
    assertTrue(parsed.recursionDesired)
    assertNull(parsed.edns)
  }
  @Test fun rootQuestionIsValidDnsEvenThoughItCannotBeBlocked() {
    assertEquals("", query(queryWire(name = "")).question.name)
  }
  @Test fun nxdomainHasCorrectFlagsCountsAndNoSoaForEveryType() {
    for (type in listOf(1, 28, 65, 5, 16, 255)) for (rd in listOf(false, true)) {
      val q = query(queryWire(type = type, rd = rd))
      val bytes = DnsMessage.response(q, DnsMessage.Answer.NXDOMAIN, v4)
      assertEquals(if (rd) 0x8583 else 0x8483, IpPacket.u16(bytes, 2))
      assertCounts(bytes, 1, 0, 0, 0)
      assertArrayEquals(q.question.wire, bytes.copyOfRange(12, bytes.size))
    }
  }
  @Test fun nodataAndServfailAreNotAuthoritative() {
    for (answer in listOf(DnsMessage.Answer.NODATA, DnsMessage.Answer.SERVFAIL)) {
      val bytes = DnsMessage.response(query(), answer, v4)
      assertEquals(0x8180 or answer.code, IpPacket.u16(bytes, 2))
      assertCounts(bytes, 1, 0, 0, 0)
    }
  }
  @Test fun emptyOptAdvertisesTunnelPayloadAndKeepsDoForBothFamilies() {
    for (family in listOf(v4, v6)) for (doBit in listOf(false, true)) {
      val q = query(queryWire(edns = 4096, doBit = doBit, options = hex("000a00020102")))
      val bytes = DnsMessage.response(q, DnsMessage.Answer.NXDOMAIN, family)
      val at = 12 + q.question.wire.size
      assertEquals(at + 11, bytes.size)
      assertCounts(bytes, 1, 0, 0, 1)
      assertEquals(0, IpPacket.u8(bytes, at))
      assertEquals(41, IpPacket.u16(bytes, at + 1))
      assertEquals(family.udpPayload, IpPacket.u16(bytes, at + 3))
      assertEquals(if (doBit) 0x8000 else 0, IpPacket.u16(bytes, at + 7))
      assertEquals(0, IpPacket.u16(bytes, at + 9))
    }
  }
  @Test fun upwardClampChangesOnlyOptSizeAndKeepsDoAndOptions() {
    val original = queryWire(edns = 4096, doBit = true, options = hex("000a000401020304"))
    val q = query(original)
    val expected = original.copyOf().also { IpPacket.put16(it, q.edns!!.classOffset, 1232) }
    assertArrayEquals(expected, q.upstream())
    assertArrayEquals(original, q.wire)
    for (size in listOf(0, 128, 512, 1200, 1232, 1400, 65535)) {
      val small = query(queryWire(edns = size))
      assertEquals(size.coerceIn(512, 1232), query(small.upstream()).edns!!.payloadSize)
    }
    val plain = query()
    assertArrayEquals(plain.wire, plain.upstream())
  }
  @Test fun edns4096And2000ByteAnswerTruncates() {
    val q = query(queryWire(edns = 4096))
    assertTruncated(q, answerWire(q, 2000), v4)
    assertTruncated(q, answerWire(q, 2000), v6)
  }
  @Test fun noEdnsAnd600ByteAnswerTruncates() {
    val q = query()
    assertTruncated(q, answerWire(q, 600), v4)
  }
  @Test fun edns1400And1300ByteAnswerRelaysVerbatim() {
    val q = query(queryWire(edns = 1400))
    val answer = answerWire(q, 1300)
    assertArrayEquals(answer, relayedAnswer(q, answer, v4))
  }
  @Test fun downstreamUsesClientLimitAndFamilyMtuAtExactBoundaries() {
    for (size in listOf<Int?>(null, 0, 128, 512, 1400, 4096)) for (family in listOf(v4, v6)) {
      val q = query(queryWire(edns = size))
      val limit = minOf(maxOf(size ?: 512, 512), family.udpPayload)
      val exact = answerWire(q, limit)
      assertArrayEquals(exact, relayedAnswer(q, exact, family))
      assertTruncated(q, answerWire(q, limit + 1), family)
    }
  }
  @Test fun tcHasOnlyQuestionAndOptionalEmptyOptNeverAPartialRecord() {
    for (size in listOf<Int?>(null, 4096)) {
      val q = query(queryWire(edns = size))
      val truncated = DnsMessage.truncated(q, v4)
      assertEquals(0x8380, IpPacket.u16(truncated, 2))
      assertCounts(truncated, 1, 0, 0, if (size == null) 0 else 1)
      assertEquals(12 + q.question.wire.size + if (size == null) 0 else 11, truncated.size)
    }
  }
  @Test fun badversUsesExtendedRcodeAndVersionZeroAndDo() {
    val q = query(queryWire(edns = 1400, version = 1, doBit = true))
    for (answer in DnsMessage.Answer.entries) {
      val bytes = DnsMessage.response(q, answer, v6)
      val opt = bytes.size - 11
      assertEquals(0x8180, IpPacket.u16(bytes, 2))
      assertEquals(1, IpPacket.u8(bytes, opt + 5))
      assertEquals(0, IpPacket.u8(bytes, opt + 6))
      assertEquals(0x8000, IpPacket.u16(bytes, opt + 7))
      assertCounts(bytes, 1, 0, 0, 1)
    }
  }
  @Test fun relayingKeepsVerifiedIdAndCopiesRdOnly() {
    val q = query(queryWire(rd = false))
    val upstream = answerWire(q, 200)
    val expected = upstream.copyOf().also { IpPacket.put16(it, 0, q.id); IpPacket.put16(it, 2, 0x8080) }
    assertArrayEquals(expected, relayedAnswer(q, upstream, v4))
  }
  @Test fun tcpRelayIsNotLimitedByUdpSize() {
    val q = query()
    val bytes = answerWire(q, 4000)
    assertArrayEquals(bytes, relayedAnswer(q, bytes, v6, tcp = true))
  }
  @Test fun invalidUpstreamOrDifferentQuestionIsDiscarded() {
    val q = query()
    for (bytes in listOf(byteArrayOf(), q.wire, answerWire(query(queryWire(name = "other.com")), 200),
      answerWire(q, 200).copyOf(199))) {
      assertSame(DnsMessage.RelayResult.Discard, DnsMessage.relay(q, bytes, v4))
    }
  }
  @Test fun incompleteQueriesDropAtEveryBoundary() {
    val wire = queryWire(edns = 1232, options = hex("000a000401020304"))
    for (size in wire.indices) assertNull("size=$size", DnsMessage.parseQuery(wire.copyOf(size)))
    assertNotNull(DnsMessage.parseQuery(wire))
  }
  @Test fun invalidCountsFlagsAndTrailingBytesDrop() {
    val good = queryWire()
    for (at in listOf(4, 6, 8, 10)) {
      assertNull(DnsMessage.parseQuery(good.copyOf().also { IpPacket.put16(it, at, 2) }))
    }
    for (flags in listOf(0x8000, 0x0800, 0x0200, 0x0040, 0x0001)) {
      assertNull(DnsMessage.parseQuery(good.copyOf().also { IpPacket.put16(it, 2, flags) }))
    }
    assertNull(DnsMessage.parseQuery(good + byteArrayOf(0)))
  }
  @Test fun selfForwardHeaderPointersAndOversizedNamesDrop() {
    for (pointer in listOf(0xc00c, 0xc00e, 0xc000, 0xffff)) {
      val bytes = queryWire().copyOf(18)
      IpPacket.put16(bytes, 12, pointer)
      assertNull(DnsMessage.parseQuery(bytes))
    }
    assertNull(DnsMessage.parseQuery(queryWire(name = "a".repeat(64) + ".com")))
    assertNull(DnsMessage.parseQuery(queryWire(name = List(4) { "a".repeat(63) }.joinToString("."))))
  }
  @Test fun duplicateOptNonRootOptAndMalformedOptionsDrop() {
    val good = queryWire(edns = 1232)
    assertNull(DnsMessage.parseQuery((good + good.takeLast(11)).also { IpPacket.put16(it, 10, 2) }))
    val plain = queryWire()
    val nonRoot = plain + hex("016100002904d0000000000000")
    IpPacket.put16(nonRoot, 10, 1)
    assertNull(DnsMessage.parseQuery(nonRoot))
    assertNull(DnsMessage.parseQuery(queryWire(edns = 1232, options = hex("000a000401"))))
    assertNull(DnsMessage.parseQuery(queryWire(edns = 1232, options = hex("00"))))
  }
  @Test fun unknownWellFormedAdditionalRecordIsPreservedUpstream() {
    val bytes = queryWire() + hex("c00c001000010000003c00020161")
    IpPacket.put16(bytes, 10, 1)
    assertArrayEquals(bytes, query(bytes).upstream())
  }

  @Test fun firstQuestionCannotCompressToAnOctetInsideItsOwnLabel() {
    val bytes = queryWire().copyOf(12) + hex("03610062c00e00010001")
    assertNull(DnsMessage.parseQuery(bytes))
  }
  @Test fun binaryLabelSeparatorsCannotMasqueradeAsHostnamesOrSignals() {
    val header = queryWire().copyOf(12)
    val label = "use-application-dns.net".toByteArray()
    val bytes = header + byteArrayOf(label.size.toByte()) + label + hex("0000010001")
    val parsed = query(bytes)
    assertEquals("use-application-dns\\046net", parsed.question.name)
    val matcher = DomainMatcher(listOf("use-application-dns.net"), DomainIdna { it })
    assertEquals(DomainMatcher.Match.ALLOW, matcher.match(parsed.question.name))
    assertArrayEquals(bytes.copyOfRange(12, bytes.size), parsed.question.wire)
  }
  @Test fun badversIsNotTruncatedAndQueryExtendedRcodeMustBeZero() {
    val q = query(queryWire(edns = 1232, version = 2))
    assertEquals(0, IpPacket.u16(DnsMessage.truncated(q, v4), 2) and 0x200)
    val bad = queryWire(edns = 1232)
    bad[bad.size - 6] = 1
    assertNull(DnsMessage.parseQuery(bad))
  }
  @Test fun randomMalformedQueriesNeverThrow() {
    val random = java.util.Random(481516)
    repeat(2000) {
      val bytes = ByteArray(random.nextInt(1000))
      random.nextBytes(bytes)
      DnsMessage.parseQuery(bytes)
      if (bytes.size >= 12) {
        IpPacket.put16(bytes, 2, 0x100)
        IpPacket.put16(bytes, 4, 1)
        IpPacket.put16(bytes, 6, 0)
        IpPacket.put16(bytes, 8, 0)
        DnsMessage.parseQuery(bytes)
      }
    }
  }

  private fun assertTruncated(q: DnsMessage.Query, upstream: ByteArray, family: IpPacket.Family) {
    assertArrayEquals(DnsMessage.truncated(q, family), relayedAnswer(q, upstream, family))
  }
  private fun assertCounts(bytes: ByteArray, vararg counts: Int) {
    counts.forEachIndexed { index, count -> assertEquals(count, IpPacket.u16(bytes, 4 + index * 2)) }
  }
}
