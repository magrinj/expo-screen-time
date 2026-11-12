package expo.modules.screentime.web

import expo.modules.screentime.web.IpPacket.ACK
import expo.modules.screentime.web.IpPacket.FIN
import expo.modules.screentime.web.IpPacket.PSH
import expo.modules.screentime.web.IpPacket.RST
import expo.modules.screentime.web.IpPacket.SYN
import expo.modules.screentime.web.IpPacket.Tcp
import expo.modules.screentime.web.IpPacket.addSequence
import org.junit.Assert.*
import org.junit.Test

internal class TcpDnsResponderTest {
  /** Explicit sequence numbers/time: this driver does not implement retransmission on behalf of the server. */
  private class Client(
    val family: IpPacket.Family = IpPacket.Family.IPV4,
    val isn: Long = 100,
    val serverIsn: Long = 900,
    val mss: Int? = 1460,
    var window: Int = 16384,
  ) {
    val server = TcpDnsResponder(family, serverIsn)
    var sequence = addSequence(isn, 1)
    var ack = addSequence(serverIsn, 1)
    var now = 0L
    fun syn() = Tcp(42000, 53, isn, flags = SYN, window = window, options =
      mss?.let { byteArrayOf(2, 4, (it ushr 8).toByte(), it.toByte()) } ?: byteArrayOf())
    fun open(): Tcp {
      val synAck = server.receive(syn(), now).segments.single()
      assertEquals(SYN or ACK, synAck.flags)
      assertTrue(send().segments.isEmpty())
      return synAck
    }
    fun send(payload: ByteArray = byteArrayOf(), flags: Int = ACK, seq: Long = sequence,
      acknowledgement: Long = ack, advance: Boolean = true): TcpDnsResponder.Output {
      val result = server.receive(Tcp(42000, 53, seq, acknowledgement, flags, window, payload), now)
      if (advance) sequence = addSequence(sequence, payload.size + if (flags and FIN != 0) 1 else 0)
      return result
    }
    fun acknowledge(segment: Tcp): TcpDnsResponder.Output {
      ack = addSequence(segment.sequence, segment.sequenceLength)
      return send()
    }
    fun request(wire: ByteArray = queryWire()): TcpDnsResponder.Request = send(frame(wire)).requests.single()
    fun answer(request: TcpDnsResponder.Request, size: Int = 200): TcpDnsResponder.Output =
      server.complete(request.key, answerWire(request.query, size), now)
    fun drain(first: TcpDnsResponder.Output): Pair<ByteArray, List<Tcp>> {
      val bytes = java.io.ByteArrayOutputStream()
      val segments = mutableListOf<Tcp>()
      var output = first
      var safety = 0
      while (output.segments.any { it.payload.isNotEmpty() }) {
        check(safety++ < 1000)
        val segment = output.segments.single { it.payload.isNotEmpty() }
        segments += segment
        bytes.write(segment.payload)
        output = acknowledge(segment)
      }
      return bytes.toByteArray() to segments
    }
  }

  @Test fun handshakeOffersOnlyFamilyMssAnd16KiBWindow() {
    for (family in IpPacket.Family.entries) {
      val client = Client(family)
      val synAck = client.open()
      assertEquals(900L, synAck.sequence)
      assertEquals(101L, synAck.acknowledgement)
      assertEquals(16384, synAck.window)
      assertEquals(family.mss, synAck.mss())
      assertEquals(4, synAck.options.size)
      assertEquals(53, synAck.sourcePort)
      assertEquals(42000, synAck.destinationPort)
    }
  }
  @Test fun splitLengthPrefixAndBodyReassembleAcrossManySegments() {
    val client = Client(); client.open()
    val bytes = frame(queryWire())
    assertTrue(client.send(bytes.copyOfRange(0, 1)).requests.isEmpty())
    assertTrue(client.send(bytes.copyOfRange(1, 2)).requests.isEmpty())
    assertTrue(client.send(bytes.copyOfRange(2, 10)).requests.isEmpty())
    val request = client.send(bytes.copyOfRange(10, bytes.size)).requests.single()
    assertEquals("example.com", request.query.question.name)
    assertEquals(101L + bytes.size, client.send().segments.firstOrNull()?.acknowledgement ?: client.sequence)
    val (answer, _) = client.drain(client.answer(request))
    assertArrayEquals(frame(answerWire(request.query, 200)), answer)
  }
  @Test fun twoPipelinedRequestsCanCompleteInReverseOrder() {
    val client = Client(); client.open()
    val requests = client.send(frame(queryWire(id = 1)) + frame(queryWire(id = 2))).requests
    assertEquals(2, requests.size)
    val second = client.answer(requests[1]).segments.single()
    assertTrue(client.answer(requests[0]).segments.isEmpty())
    val (bytes, _) = client.drain(TcpDnsResponder.Output(listOf(second)))
    assertArrayEquals(frame(answerWire(requests[1].query, 200)) + frame(answerWire(requests[0].query, 200)), bytes)
  }
  @Test fun connectionRemainsUsableAfterAnAnswer() {
    val client = Client(); client.open()
    repeat(3) { id ->
      val request = client.request(queryWire(id = id))
      val (bytes, _) = client.drain(client.answer(request))
      assertEquals(id, IpPacket.u16(bytes, 2))
      assertFalse(client.server.closed)
    }
  }
  @Test fun sameDnsIdDoesNotCollideBetweenPipelinedRequests() {
    val client = Client(); client.open()
    val requests = client.send(frame(queryWire()) + frame(queryWire())).requests
    assertNotEquals(requests[0].key, requests[1].key)
    val first = client.answer(requests[0])
    assertTrue(client.answer(requests[1]).segments.isEmpty())
    assertEquals(404, client.drain(first).first.size)
  }
  @Test fun udpTruncationThenTcpRetryDeliversFullAnswer() {
    val q = query()
    val large = answerWire(q, 3000)
    assertTrue(IpPacket.u16(relayedAnswer(q, large, IpPacket.Family.IPV4), 2) and 0x200 != 0)
    val client = Client(); client.open()
    val request = client.request(q.wire)
    val output = client.server.complete(request.key, large, client.now)
    assertArrayEquals(frame(large), client.drain(output).first)
  }
  @Test fun lostLargeAnswerSegmentIsRetransmittedThenReassembles() {
    for (family in IpPacket.Family.entries) {
      val client = Client(family); client.open()
      val request = client.request()
      val first = client.answer(request, 4000).segments.single()
      assertEquals(family.mss, first.payload.size)
      assertTrue(client.server.tick(999).segments.isEmpty())
      val retransmitted = client.server.tick(1000).segments.single()
      assertEquals(first.sequence, retransmitted.sequence)
      assertArrayEquals(first.payload, retransmitted.payload)
      client.now = 1000
      assertArrayEquals(frame(answerWire(request.query, 4000)), client.drain(TcpDnsResponder.Output(listOf(retransmitted))).first)
    }
  }
  @Test fun clientMss536LimitsEverySegment() {
    val client = Client(mss = 536); client.open()
    val (bytes, segments) = client.drain(client.answer(client.request(), 3000))
    assertEquals(3002, bytes.size)
    assertEquals(listOf(536, 536, 536, 536, 536, 322), segments.map { it.payload.size })
  }
  @Test fun absentMssUsesFamilyDefault() {
    for ((family, default) in listOf(IpPacket.Family.IPV4 to 536, IpPacket.Family.IPV6 to 1220)) {
      val client = Client(family, mss = null); client.open()
      assertEquals(default, client.answer(client.request(), 3000).segments.single().payload.size)
    }
  }
  @Test fun hugeClientMssIsCappedAtOwnMss() {
    val client = Client(IpPacket.Family.IPV6, mss = 65535); client.open()
    assertEquals(1440, client.answer(client.request(), 3000).segments.single().payload.size)
  }
  @Test fun clientWindowLimitsSendAndOnlyOneSegmentIsInFlight() {
    val client = Client(window = 31); client.open()
    val output = client.answer(client.request(), 200)
    assertEquals(31, output.segments.single().payload.size)
    assertTrue(client.send().segments.isEmpty())
    val (bytes, segments) = client.drain(output)
    assertEquals(202, bytes.size)
    assertTrue(segments.all { it.payload.size <= 31 })
  }
  @Test fun zeroWindowProbesBackOffAndResumeWhenWindowOpens() {
    val client = Client(window = 0); client.open()
    val request = client.request()
    assertTrue(client.answer(request).segments.isEmpty())
    assertTrue(client.server.tick(999).segments.isEmpty())
    val probe1 = client.server.tick(1000).segments.single()
    assertEquals(1, probe1.payload.size)
    assertEquals(client.ack, probe1.sequence)
    assertTrue(client.server.tick(2999).segments.isEmpty())
    val probe2 = client.server.tick(3000).segments.single()
    assertArrayEquals(probe1.payload, probe2.payload)
    client.now = 3000; client.window = 16384
    val resumed = client.send()
    assertArrayEquals(frame(answerWire(request.query, 200)), client.drain(resumed).first)
  }
  @Test fun acceptedZeroWindowProbeByteIsNotDuplicated() {
    val client = Client(window = 0); client.open()
    val request = client.request(); client.answer(request)
    val probe = client.server.tick(1000).segments.single()
    client.now = 1000; client.window = 16384
    val rest = client.acknowledge(probe)
    val bytes = probe.payload + client.drain(rest).first
    assertArrayEquals(frame(answerWire(request.query, 200)), bytes)
  }
  @Test fun windowClosingWithDataInFlightHasBoundedPersist() {
    val client = Client(); client.open()
    val request = client.request()
    val first = client.answer(request).segments.single()
    client.window = 0; client.send()
    for (time in listOf(1000L, 3000L, 7000L)) {
      val probe = client.server.tick(time).segments.single()
      assertEquals(1, probe.payload.size)
      assertEquals(first.sequence, probe.sequence)
      assertFalse(client.server.closed)
    }
    assertTrue(client.server.tick(15000).segments.single().has(RST))
    assertTrue(client.server.closed)
    assertEquals(0, client.server.bufferedBytes)
  }
  @Test fun partialAckRetransmitsOnlyRemainingBytesAndHonoursShrunkWindow() {
    val client = Client(); client.open()
    val first = client.answer(client.request()).segments.single()
    client.ack = addSequence(first.sequence, 10); client.window = 15; client.send()
    val tail = client.server.tick(1000).segments.single()
    assertEquals(client.ack, tail.sequence)
    assertEquals(15, tail.payload.size)
    assertArrayEquals(first.payload.copyOfRange(10, 25), tail.payload)
  }
  @Test fun duplicateSynRepeatsOriginalSynAckWithoutResettingStream() {
    val client = Client()
    val initial = client.open()
    val request = client.request()
    val duplicate = client.server.receive(client.syn(), client.now).segments.single()
    assertEquals(initial.sequence, duplicate.sequence)
    assertEquals(initial.acknowledgement, duplicate.acknowledgement)
    assertArrayEquals(initial.options, duplicate.options)
    assertEquals(202, client.drain(client.answer(request)).first.size)
  }
  @Test fun synAckRetransmitsAtOneThreeSevenSecondsThenResetsAtFifteen() {
    val client = Client()
    val first = client.server.receive(client.syn(), 0).segments.single()
    for (time in listOf(1000L, 3000L, 7000L)) {
      val retry = client.server.tick(time).segments.single()
      assertEquals(first.sequence, retry.sequence)
      assertEquals(SYN or ACK, retry.flags)
    }
    assertTrue(client.server.tick(14999).segments.isEmpty())
    assertTrue(client.server.tick(15000).segments.single().has(RST))
    assertTrue(client.server.closed)
  }
  @Test fun dataRetransmissionBackoffIsTheSameAsSyn() {
    val client = Client(); client.open()
    val first = client.answer(client.request()).segments.single()
    for (time in listOf(1000L, 3000L, 7000L)) {
      assertArrayEquals(first.payload, client.server.tick(time).segments.single().payload)
    }
    assertTrue(client.server.tick(15000).segments.single().has(RST))
  }
  @Test fun halfCloseWaitsForPendingAnswerAndAllDataAcksBeforeOwnFin() {
    val client = Client(); client.open()
    val request = client.request()
    val finAck = client.send(flags = ACK or FIN).segments.single()
    assertEquals(ACK, finAck.flags)
    assertEquals(client.sequence, finAck.acknowledgement)
    val first = client.answer(request, 2000).segments.single()
    assertFalse(first.has(FIN))
    val second = client.acknowledge(first).segments.single()
    assertFalse(second.has(FIN))
    val ownFin = client.acknowledge(second).segments.single()
    assertEquals(ACK or FIN, ownFin.flags)
    assertTrue(client.acknowledge(ownFin).segments.isEmpty())
    assertTrue(client.server.closed)
  }
  @Test fun requestAndFinInSameSegmentAreAnsweredBeforeOwnFin() {
    val client = Client(); client.open()
    val output = client.send(frame(queryWire()), ACK or FIN)
    assertEquals(1, output.segments.size)
    assertEquals(ACK, output.segments.single().flags)
    assertEquals(client.sequence, output.segments.single().acknowledgement)
    val data = client.answer(output.requests.single()).segments.single()
    val fin = client.acknowledge(data).segments.single()
    assertEquals(FIN or ACK, fin.flags)
  }
  @Test fun duplicateFinReacksWhilePendingAndAfterGracefulClose() {
    val client = Client(); client.open()
    val request = client.request()
    val finSequence = client.sequence
    client.send(flags = ACK or FIN)
    val duplicate = client.send(flags = ACK or FIN, seq = finSequence, advance = false)
    assertEquals(ACK, duplicate.segments.single().flags)
    val data = client.answer(request).segments.single()
    val fin = client.acknowledge(data).segments.single()
    client.acknowledge(fin)
    val afterClose = client.send(flags = ACK or FIN, seq = finSequence, advance = false)
    assertEquals(ACK, afterClose.segments.single().flags)
    assertEquals(client.sequence, afterClose.segments.single().acknowledgement)
  }
  @Test fun ownFinRetransmitsAndCanBeAcknowledgedAfterLoss() {
    val client = Client(); client.open()
    val output = client.send(flags = ACK or FIN)
    val fin = output.segments.single { it.has(FIN) }
    val retry = client.server.tick(1000).segments.single()
    assertEquals(fin.sequence, retry.sequence)
    assertEquals(FIN or ACK, retry.flags)
    client.now = 1000
    client.acknowledge(retry)
    assertTrue(client.server.closed)
  }
  @Test fun upstreamFailureProducesServfailOverPersistentTcp() {
    val client = Client(); client.open()
    val request = client.request(queryWire(edns = 1232))
    val (bytes, _) = client.drain(client.server.complete(request.key, null, client.now))
    assertArrayEquals(frame(DnsMessage.response(request.query, DnsMessage.Answer.SERVFAIL, client.family)), bytes)
    assertFalse(client.server.closed)
    assertNotNull(client.request())
  }
  @Test fun outOfOrderAndDuplicateDataAreReackedWithoutDuplicateRequests() {
    val client = Client(); client.open()
    val bytes = frame(queryWire())
    val originalSequence = client.sequence
    val future = client.send(bytes, seq = originalSequence + 10, advance = false)
    assertTrue(future.requests.isEmpty())
    assertEquals(originalSequence, future.segments.single().acknowledgement)
    assertEquals(1, client.send(bytes).requests.size)
    val duplicate = client.send(bytes, seq = originalSequence, advance = false)
    assertTrue(duplicate.requests.isEmpty())
    assertEquals(client.sequence, duplicate.segments.single().acknowledgement)
  }
  @Test fun midstreamResetDropsOnlyThisConnectionAndLateCompletions() {
    val a = Client(); a.open()
    val b = Client(); b.open()
    val pending = a.request()
    assertTrue(a.send(flags = RST).segments.isEmpty())
    assertTrue(a.server.closed)
    assertTrue(a.answer(pending).segments.isEmpty())
    assertEquals(202, b.drain(b.answer(b.request())).first.size)
  }
  @Test fun resetOutsideReceiveSequenceIsChallenged() {
    val client = Client(); client.open()
    assertEquals(ACK, client.send(flags = RST, seq = client.sequence + 1, advance = false).segments.single().flags)
    assertFalse(client.server.closed)
  }
  @Test fun resetOutsideReceiveWindowIsSilentlyDropped() {
    val client = Client(); client.open()
    assertTrue(client.send(flags = RST, seq = client.sequence + 16384, advance = false).segments.isEmpty())
    assertTrue(client.send(flags = RST, seq = client.sequence - 1, advance = false).segments.isEmpty())
    assertFalse(client.server.closed)
  }
  @Test fun idleClosesAtTenSecondsAndFinAckThenPeerFinClosesFully() {
    val client = Client(); client.open()
    assertTrue(client.server.tick(9999).segments.isEmpty())
    val fin = client.server.tick(10000).segments.single()
    assertEquals(FIN or ACK, fin.flags)
    client.now = 10000
    assertTrue(client.acknowledge(fin).segments.isEmpty())
    assertFalse(client.server.closed)
    assertEquals(ACK, client.send(flags = ACK or FIN).segments.single().flags)
    assertTrue(client.server.closed)
  }
  @Test fun pendingAnswerIsNotLostToIdleClose() {
    val client = Client(); client.open()
    val pending = client.request()
    assertTrue(client.server.tick(10000).segments.isEmpty())
    client.now = 10000
    assertEquals(202, client.drain(client.answer(pending)).first.size)
  }
  @Test fun malformedFramedDnsIsDroppedButNextFrameStillWorks() {
    val client = Client(); client.open()
    val output = client.send(frame(byteArrayOf(1, 2, 3)) + frame(byteArrayOf()) + frame(queryWire()))
    assertEquals(1, output.requests.size)
  }
  @Test fun incompleteFinalFrameIsDroppedOnHalfClose() {
    val client = Client(); client.open()
    val output = client.send(frame(queryWire()).copyOf(10), ACK or FIN)
    assertTrue(output.requests.isEmpty())
    assertTrue(output.segments.any { it.has(FIN) })
  }
  @Test fun unexpectedSegmentAndPort853GetReset() {
    val client = Client()
    assertEquals(RST, client.send().segments.single().flags)
    val other = TcpDnsResponder(IpPacket.Family.IPV4, 900)
    val reset = other.receive(client.syn().copy(destinationPort = 853), 0).segments.single()
    assertEquals(RST or ACK, reset.flags)
    val established = Client(); established.open()
    assertTrue(established.send(flags = PSH).segments.isEmpty())
    assertFalse(established.server.closed)
  }
  @Test fun ackOfUnsentBytesCannotAdvanceOrOpenWindow() {
    val client = Client(window = 0); client.open()
    val request = client.request(); client.answer(request)
    client.window = 16384
    assertEquals(ACK, client.send(acknowledgement = client.ack + 200).segments.single().flags)
    assertEquals(1, client.server.tick(1000).segments.single().payload.size)
  }
  @Test fun sequenceWrapWorksForHandshakeDataAndAck() {
    val client = Client(isn = 0xffffffffL, serverIsn = 0xffffffffL); client.open()
    assertEquals(0L, client.sequence)
    assertEquals(0L, client.ack)
    val output = client.answer(client.request(), 3000)
    assertEquals(0L, output.segments.single().sequence)
    assertEquals(3002, client.drain(output).first.size)
  }
  @Test fun streamCanReassembleMaximumLengthDnsFrameBeyondReceiveWindow() {
    val client = Client(); client.open()
    val q = queryWire() + hex("c00c001000010000003c") + ByteArray(2).also { IpPacket.put16(it, 0, 65494) } + ByteArray(65494)
    IpPacket.put16(q, 10, 1)
    assertEquals(65535, q.size)
    val bytes = frame(q)
    val requests = mutableListOf<TcpDnsResponder.Request>()
    var at = 0
    while (at < bytes.size) {
      val end = minOf(bytes.size, at + 10000)
      requests += client.send(bytes.copyOfRange(at, end)).requests
      at = end
    }
    assertEquals(1, requests.size)
  }
  @Test fun receiveWindowOverflowIsNotAccepted() {
    val client = Client(); client.open()
    val output = client.send(ByteArray(16385), advance = false)
    assertEquals(client.sequence, output.segments.single().acknowledgement)
    assertTrue(output.requests.isEmpty())
  }
  @Test fun flowStateDoesNotDependOnTunnelDescriptorOrActiveDnsPair() {
    // Packets addressed to the original pair reuse the same responder after the owner changes its
    // active pair. Descriptor handover itself is outside this class.
    val client = Client(); client.open()
    val request = client.request()
    val first = client.answer(request, 2000).segments.single()
    val source = hex("0a6fde01"); val destination = hex("0a6fde02")
    val wire = IpPacket.build(client.family, source, destination, first)
    val parsed = parsedPacket(wire)!!.transport as Tcp
    val next = client.acknowledge(parsed).segments.single()
    assertEquals(addSequence(first.sequence, first.payload.size), next.sequence)
    assertEquals(53, next.sourcePort)
    assertFalse(client.server.closed)
  }
  @Test fun incompleteIdleFrameStillClosesAtTenSeconds() {
    val client = Client(); client.open()
    client.send(byteArrayOf(1))
    assertEquals(FIN or ACK, client.server.tick(10000).segments.single().flags)
  }
  @Test fun finWaitTwoExpiresIfPeerNeverClosesItsSendHalf() {
    val client = Client(); client.open()
    val fin = client.server.tick(10000).segments.single()
    client.now = 10000; client.acknowledge(fin)
    assertTrue(client.server.tick(20000).segments.single().has(RST))
    assertTrue(client.server.closed)
  }
  @Test fun duplicateCompletionCannotSendAnotherAnswer() {
    val client = Client(); client.open()
    val request = client.request()
    client.drain(client.answer(request))
    assertTrue(client.answer(request).segments.isEmpty())
  }
  @Test fun completedInflightProbeDoesNotAuthorizeAnAckForUnsentData() {
    val client = Client(); client.open()
    val first = client.answer(client.request()).segments.single()
    client.window = 0; client.send()
    client.server.tick(1000)
    client.now = 1000
    client.acknowledge(first)
    val invalid = client.send(acknowledgement = client.ack + 1)
    assertEquals(ACK, invalid.segments.single().flags)
    assertFalse(client.server.closed)
  }
  @Test fun finalAnswerWithZeroWindowStillPrecedesFinAfterReopening() {
    val client = Client(window = 0); client.open()
    val request = client.request()
    client.send(flags = ACK or FIN)
    assertTrue(client.answer(request).segments.isEmpty())
    val probe = client.server.tick(1000).segments.single()
    assertEquals(1, probe.payload.size)
    client.now = 1000; client.window = 16384
    val answer = client.send().segments.single()
    assertFalse(answer.has(FIN))
    assertEquals(FIN or ACK, client.acknowledge(answer).segments.single().flags)
  }
  @Test fun excessiveOutstandingQueriesResetOnlyTheOffendingConnection() {
    val client = Client(); client.open()
    val frame = frame(queryWire())
    val bytes = java.io.ByteArrayOutputStream().apply { repeat(65) { write(frame) } }.toByteArray()
    assertTrue(client.send(bytes).segments.single().has(RST))
    assertTrue(client.server.closed)
  }
  @Test fun dataOnThirdHandshakeSegmentIsAccepted() {
    val client = Client()
    client.server.receive(client.syn(), 0)
    assertEquals(1, client.send(frame(queryWire())).requests.size)
  }

  @Test fun discardedUpstreamMessageKeepsRequestPendingUntilValidAnswerOrFailure() {
    val client = Client(); client.open()
    val request = client.request()
    val wrongId = answerWire(request.query, 200).also { IpPacket.put16(it, 0, request.query.id + 1) }
    val before = client.server.bufferedBytes
    assertTrue(client.server.complete(request.key, wrongId, 0).segments.isEmpty())
    assertEquals(before, client.server.bufferedBytes)
    assertEquals(202, client.drain(client.answer(request)).first.size)
    val second = client.request()
    val wrongQuestion = answerWire(query(queryWire(name = "other.com")), 200)
    assertTrue(client.server.complete(second.key, wrongQuestion, 0).segments.isEmpty())
    val bytes = client.drain(client.server.complete(second.key, null, 0)).first
    assertEquals(2, IpPacket.u16(bytes, 4) and 15)
  }
  @Test fun differentIsnSynGetsChallengeAckAndOriginalConnectionSurvives() {
    val client = Client(); client.open()
    val reply = client.server.receive(client.syn().copy(sequence = 5000), 0).segments.single()
    assertEquals(ACK, reply.flags)
    assertEquals(client.sequence, reply.acknowledgement)
    assertFalse(client.server.closed)
    assertEquals(202, client.drain(client.answer(client.request())).first.size)
  }
  @Test fun badHandshakeAckResetsTheSegmentButKeepsSynReceivedState() {
    val client = Client()
    client.server.receive(client.syn(), 0)
    val reset = client.send(acknowledgement = 9999).segments.single()
    assertEquals(RST, reset.flags)
    assertEquals(9999L, reset.sequence)
    assertFalse(client.server.closed)
    assertEquals(1000L, client.server.nextDeadline())
    assertTrue(client.send().segments.isEmpty())
    assertNotNull(client.request())
  }
  @Test fun hourOfTicksCannotKeepUnansweredPersistAliveIncludingFinOnly() {
    for (finOnly in listOf(false, true)) {
      val client = Client(window = 0); client.open()
      if (finOnly) client.send(flags = ACK or FIN) else client.answer(client.request(), 2000)
      var probes = 0
      var resets = 0
      for (second in 1..3600) {
        client.server.tick(second * 1000L).segments.forEach {
          if (it.has(RST)) resets++ else probes++
        }
      }
      assertEquals(3, probes)
      assertEquals(1, resets)
      assertTrue(client.server.closed)
      assertEquals(0, client.server.bufferedBytes)
      assertNull(client.server.nextDeadline())
    }
  }
  @Test fun hourOfAcknowledgedPersistStaysOpenIncludingFinOnlyThenExpiresWithoutAnswers() {
    for (finOnly in listOf(false, true)) {
      val client = Client(window = 0); client.open()
      if (finOnly) client.send(flags = ACK or FIN) else client.answer(client.request(), 2000)
      for (second in 1..3600) {
        client.now = second * 1000L
        assertTrue(client.server.tick(client.now).segments.none { it.has(RST) })
        client.send()
        assertFalse(client.server.closed)
      }
      // Even with a long backoff, twenty seconds without an acceptable ACK bounds persist.
      assertTrue(client.server.tick(client.now + 20001).segments.single().has(RST))
      assertEquals(0, client.server.bufferedBytes)
      assertNull(client.server.nextDeadline())
    }
  }
  @Test fun answeringOnlyPersistProbesKeepsConnectionAliveAndWindowCanReopen() {
    val client = Client(window = 0); client.open()
    client.answer(client.request(), 2000)
    repeat(400) {
      client.now = client.server.nextDeadline()!!
      assertEquals(1, client.server.tick(client.now).segments.single().payload.size)
      client.send()
      assertFalse(client.server.closed)
    }
    assertTrue(client.now > 3600000)
    client.window = 16384
    assertEquals(2002, client.drain(client.send()).first.size)
    assertFalse(client.server.closed)
  }
  @Test fun outOfWindowFinWithoutAckAndInWindowNonAckDoNotDestroyState() {
    val client = Client(); client.open()
    val rejected = client.send(flags = FIN, seq = 12345678, advance = false)
    assertEquals(ACK, rejected.segments.single().flags)
    assertEquals(client.sequence, rejected.segments.single().acknowledgement)
    assertFalse(client.server.closed)
    assertTrue(client.send(flags = FIN, advance = false).segments.isEmpty())
    assertFalse(client.server.closed)
    assertNotNull(client.request())
  }
  @Test fun overlappingRetransmissionDeliversContiguousSuffixAndFinAcrossWrap() {
    for (isn in listOf(100L, 0xfffffff8L)) for (withFin in listOf(false, true)) {
      val client = Client(isn = isn); client.open()
      val bytes = frame(queryWire())
      val start = client.sequence
      client.send(bytes.copyOfRange(0, 10))
      val output = client.send(bytes, flags = ACK or if (withFin) FIN else 0, seq = start, advance = false)
      assertEquals(1, output.requests.size)
      val end = addSequence(start, bytes.size + if (withFin) 1 else 0)
      assertEquals(end, output.segments.single().acknowledgement)
      client.sequence = end
      val duplicate = client.send(bytes, flags = ACK or if (withFin) FIN else 0, seq = start, advance = false)
      assertTrue(duplicate.requests.isEmpty())
      assertEquals(end, duplicate.segments.single().acknowledgement)
      val answer = client.answer(output.requests.single())
      assertTrue(answer.segments.single().payload.isNotEmpty())
      assertEquals(withFin, client.acknowledge(answer.segments.single()).segments.any { it.has(FIN) })
    }
  }
  @Test fun retransmissionEndingAtReceiveNextCanContributeOnlyItsFin() {
    val client = Client(); client.open()
    val start = client.sequence
    val bytes = frame(queryWire())
    val pending = client.send(bytes).requests.single()
    val output = client.send(bytes, flags = ACK or FIN, seq = start, advance = false)
    assertTrue(output.requests.isEmpty())
    assertEquals(addSequence(client.sequence, 1), output.segments.single().acknowledgement)
    client.sequence = addSequence(client.sequence, 1)
    val answer = client.answer(pending).segments.single()
    assertTrue(client.acknowledge(answer).segments.single().has(FIN))
  }
  @Test fun oldAckWithFreshPayloadStillDeliversQueryAcrossWrap() {
    for (serverIsn in listOf(900L, 0xfffffff8L)) {
      val client = Client(serverIsn = serverIsn); client.open()
      val first = client.request()
      val sent = client.answer(first, 80).segments.single()
      val oldAck = client.ack
      client.acknowledge(sent)
      val output = client.send(frame(queryWire()), acknowledgement = oldAck)
      assertEquals(1, output.requests.size)
      assertEquals(client.sequence, output.segments.single().acknowledgement)
      assertFalse(client.server.closed)
    }
  }
  @Test fun incompleteFrameTrickleCannotExtendDnsProgressDeadline() {
    val client = Client(); client.open()
    client.send(byteArrayOf(0xff.toByte(), 0xff.toByte()))
    assertEquals(10000L, client.server.nextDeadline())
    for (time in listOf(9000L, 18000L, 27000L, 36000L, 45000L, 54000L, 63000L)) {
      client.now = time
      assertTrue(client.send(byteArrayOf(0)).requests.isEmpty())
      client.server.tick(time)
    }
    assertTrue(client.server.closed)
    assertEquals(0, client.server.bufferedBytes)
    assertNull(client.server.nextDeadline())
  }
  @Test fun incompleteFrameDeadlineSurvivesOtherPendingWorkAndTransportAcks() {
    val client = Client(); client.open()
    val request = client.request()
    client.now = 1000
    client.send(byteArrayOf(0xff.toByte()))
    client.now = 9000; client.send()
    assertEquals(11000L, client.server.nextDeadline())
    assertTrue(client.server.tick(11000).segments.isEmpty()) // Finish already accepted DNS work.
    assertNull(client.server.nextDeadline())
    client.now = 11000
    val answer = client.answer(request).segments.single()
    assertTrue(client.acknowledge(answer).segments.single().has(FIN))
  }
  @Test fun unknownCompletionCannotConsumeIncompleteFrameDeadline() {
    val client = Client(); client.open()
    client.send(byteArrayOf(0))
    assertTrue(client.server.complete(999, null, 10000).segments.isEmpty())
    assertEquals(10000L, client.server.nextDeadline())
    assertTrue(client.server.tick(10000).segments.single().has(FIN))
    assertEquals(11000L, client.server.nextDeadline())
  }
  @Test fun completedFrameStartsFreshDeadlineForTrailingPartialFrame() {
    val client = Client(); client.open()
    val bytes = frame(queryWire())
    client.send(bytes.copyOfRange(0, 1))
    client.now = 9000
    assertEquals(1, client.send(bytes.copyOfRange(1, bytes.size) + byteArrayOf(0)).requests.size)
    assertEquals(19000L, client.server.nextDeadline())
    assertTrue(client.server.tick(10000).segments.isEmpty())
    assertEquals(19000L, client.server.nextDeadline())
  }
  @Test fun deadlinesCoverHandshakeDataPersistIdleAndFinWithoutPolling() {
    val client = Client()
    assertNull(client.server.nextDeadline())
    client.server.receive(client.syn(), 0)
    assertEquals(1000L, client.server.nextDeadline())
    client.send()
    assertEquals(10000L, client.server.nextDeadline())
    val request = client.request()
    assertNull(client.server.nextDeadline()) // The upstream owner owns its timeout.
    val data = client.answer(request).segments.single()
    assertEquals(1000L, client.server.nextDeadline())
    client.server.tick(1000)
    assertEquals(3000L, client.server.nextDeadline())
    client.now = 1000; client.acknowledge(data)
    assertEquals(11000L, client.server.nextDeadline())
    val fin = client.server.tick(11000).segments.single()
    assertEquals(12000L, client.server.nextDeadline())
    client.now = 11000; client.acknowledge(fin)
    assertEquals(21000L, client.server.nextDeadline())
    client.send(flags = ACK or FIN)
    assertNull(client.server.nextDeadline())
  }
  @Test fun emptyFramesAreCompactedOnceAndDoNotStallTheStream() {
    val client = Client(); client.open()
    assertTrue(client.send(ByteArray(16384)).requests.isEmpty())
    assertTrue(client.server.bufferedBytes < 16)
    assertNotNull(client.request())
  }
  @Test fun queuedFramesAndPendingRequestsShareOneMiBTotalBudget() {
    val client = Client(window = 0); client.open()
    repeat(30) {
      if (!client.server.closed) client.answer(client.request(), 65535)
      assertTrue(client.server.bufferedBytes <= 1024 * 1024)
    }
    assertTrue(client.server.closed)
    assertEquals(0, client.server.bufferedBytes)
  }

  @Test(expected = IllegalArgumentException::class) fun injectedTimeMustBeMonotonic() {
    val client = Client(); client.open()
    client.server.tick(20)
    client.server.tick(19)
  }
}
