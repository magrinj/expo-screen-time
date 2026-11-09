package expo.modules.screentime.web

import expo.modules.screentime.web.IpPacket.ACK
import expo.modules.screentime.web.IpPacket.FIN
import expo.modules.screentime.web.IpPacket.PSH
import expo.modules.screentime.web.IpPacket.RST
import expo.modules.screentime.web.IpPacket.SYN
import expo.modules.screentime.web.IpPacket.Tcp
import expo.modules.screentime.web.IpPacket.addSequence

/**
 * One TCP/53 connection, with one segment in flight. The owner supplies a random ISN and monotonic
 * milliseconds, routes a stable flow here across tunnel swaps, and resolves [Request]s asynchronously.
 * [complete] accepts results in any order. Null means upstream failure, not a missing completion;
 * discarded upstream messages leave the request pending. Schedule [tick] using [nextDeadline].
 * The owner must replace closed responders before routing a new SYN on the same flow to them.
 * No sockets, clock, randomness, tunnel lifetime or upstream connection is owned by this class.
 */
internal class TcpDnsResponder(private val family: IpPacket.Family, private val initialSequence: Long) {
  data class Request(val key: Long, val query: DnsMessage.Query)
  data class Output(val segments: List<Tcp> = emptyList(), val requests: List<Request> = emptyList())
  private data class Flight(var segment: Tcp, var sentAt: Long, var delay: Long = 1000, var retries: Int = 0)

  var closed: Boolean = false
    private set
  private var graceful = false
  private var synAck: Tcp? = null
  private var established = false
  private var clientSequence = 0L
  private var receiveNext = 0L
  private var sendNext = initialSequence
  private var sendUnacknowledged = initialSequence
  private var clientPort = 0
  private var serverPort = 53
  private var clientWindow = 0
  private var sendMss = family.mss
  private var flight: Flight? = null
  private var incoming = byteArrayOf()
  private val pending = linkedMapOf<Long, DnsMessage.Query>()
  private val outgoing = ArrayDeque<ByteArray>()
  private var outgoingOffset = 0
  private var queuedBytes = 0
  private var nextKey = 0L
  private var peerFin = false
  private var ownFin = false
  private var ownFinAcknowledged = false
  private var closingForIdle = false
  private var lastActivity = 0L
  private var lastTime = Long.MIN_VALUE
  private var probeAt: Long? = null
  private var probeDelay = 1000L
  private var probeOutstanding = false
  private var persistUnansweredSince: Long? = null
  private var incompleteSince: Long? = null
  private var unansweredProbes = 0

  /** Retained byte-array storage, including partially consumed frames and pending DNS questions.
   * Caller-owned output/request snapshots are outside this connection's buffer budget.
   */
  val bufferedBytes: Int get() = incoming.size + queuedBytes + outgoingOffset +
    pending.values.sumOf { it.wire.size + it.question.wire.size } +
    (flight?.segment?.let { it.payload.size + it.options.size } ?: 0) + (synAck?.options?.size ?: 0)

  /** Earliest actionable deadline, or null when closed/listening/waiting only on the upstream owner. */
  fun nextDeadline(): Long? {
    if (closed || synAck == null) return null
    val deadlines = mutableListOf<Long>()
    if (established && clientWindow == 0) {
      persistUnansweredSince?.let { deadlines += it + 20001 }
      if (needsProbe()) probeAt?.let { deadlines += it }
    }
    flight?.let {
      if (!(established && clientWindow == 0 && it.segment.payload.isNotEmpty())) deadlines += it.sentAt + it.delay
    }
    if (established && pending.isEmpty() && outgoing.isEmpty() && flight == null &&
      (!closingForIdle || ownFinAcknowledged)) deadlines += lastActivity + 10000
    incompleteSince?.let { deadlines += it + 10000 }
    return deadlines.minOrNull()
  }

  init { require(initialSequence in 0..0xffffffffL) }

  fun receive(segment: Tcp, now: Long): Output {
    checkTime(now)
    val sent = mutableListOf<Tcp>()
    val requests = mutableListOf<Request>()
    if (closed) {
      if (graceful && samePorts(segment) && segment.has(FIN) &&
        addSequence(segment.sequence, segment.sequenceLength) == receiveNext) return Output(listOf(ack()))
      return Output(listOfNotNull(IpPacket.reset(segment)))
    }
    if (synAck == null) {
      if (segment.has(RST)) return Output()
      if (segment.destinationPort != 53 || !segment.has(SYN) || segment.flags and (SYN or 0xc0).inv() != 0 || segment.payload.isNotEmpty()) {
        return abort(segment)
      }
      clientPort = segment.sourcePort
      serverPort = segment.destinationPort
      clientSequence = segment.sequence
      receiveNext = addSequence(clientSequence, 1)
      clientWindow = segment.window
      if (clientWindow == 0) persistUnansweredSince = now
      // RFC 9293 section 3.7.1: absent MSS defaults to 536 (v4) or 1220 (v6).
      sendMss = minOf(segment.mss() ?: if (family == IpPacket.Family.IPV4) 536 else 1220, family.mss)
      if (sendMss <= 0) return abort(segment)
      val options = byteArrayOf(2, 4, (family.mss ushr 8).toByte(), family.mss.toByte())
      synAck = reply(SYN or ACK, options = options)
      transmit(synAck!!, now, sent)
      lastActivity = now
      return Output(sent)
    }
    if (!samePorts(segment)) return Output(listOfNotNull(IpPacket.reset(segment)))
    if (segment.has(RST)) {
      // RFC 9293: accept exact RCV.NXT, challenge an in-window reset, silently drop others.
      if (segment.sequence == receiveNext) terminate()
      else if (distance(receiveNext, segment.sequence) < RECEIVE_WINDOW) sent += ack()
      return Output(sent)
    }
    if (segment.has(SYN)) {
      // The proposal explicitly asks for SYN-ACK replay even after establishment. This differs
      // from RFC 9293's synchronized-state ACK/challenge-ACK rule; do not advertise full TCP compliance.
      if (segment.sequence == clientSequence && !segment.has(ACK) && !segment.has(FIN) && segment.payload.isEmpty()) {
        return Output(listOf(synAck!!))
      }
      return Output(listOf(ack())) // Different ISN: challenge ACK; retain the established flow.
    }
    // Validate sequence space before flags/ACKs. Future bytes are re-ACKed without buffering;
    // an overlapping retransmission can contribute its contiguous suffix (including FIN).
    val offset = (segment.sequence - receiveNext).toInt().toLong()
    val length = segment.sequenceLength.toLong()
    val acceptable = if (length == 0L) offset in 0 until RECEIVE_WINDOW.toLong()
      else offset < RECEIVE_WINDOW && offset + length > 0
    if (!acceptable) return Output(listOf(ack()))
    if (!segment.has(ACK)) return Output()
    if (segment.flags and (ACK or PSH or FIN).inv() != 0) return Output(listOf(ack()))
    // An unacceptable handshake ACK elicits a reset but does not destroy SYN-RECEIVED state.
    if (!established && segment.acknowledgement != sendNext) return Output(listOfNotNull(IpPacket.reset(segment)))
    if (offset > 0) return Output(listOf(ack()))
    val skipped = (-offset).toInt()
    val payload = if (skipped == 0) segment.payload else
      segment.payload.copyOfRange(minOf(skipped, segment.payload.size), segment.payload.size)
    val fin = segment.has(FIN) && skipped <= segment.payload.size
    if (peerFin && (payload.isNotEmpty() || fin)) return abort(segment)
    if (payload.size > RECEIVE_WINDOW) return Output(listOf(ack()))

    val ackAdvance = distance(sendUnacknowledged, segment.acknowledgement)
    val inFlight = distance(sendUnacknowledged, sendNext)
    val oldAck = ackAdvance >= 0x80000000L
    if (!oldAck && ackAdvance > inFlight) {
      // A persist probe is the first queued byte at SND.NXT. A reopened receiver may accept it.
      if (probeOutstanding && flight == null && outgoing.isNotEmpty() &&
        segment.acknowledgement == addSequence(sendNext, 1)) {
        consume(1)
        sendNext = segment.acknowledgement
        sendUnacknowledged = sendNext
        probeOutstanding = false
      } else {
        // An ACK of unsent bytes cannot change the window or receive stream.
        return Output(listOf(ack()))
      }
    } else if (!oldAck && ackAdvance > 0) {
      sendUnacknowledged = segment.acknowledgement
      val current = flight
      if (segment.acknowledgement == sendNext) {
        if (current?.segment?.has(FIN) == true) ownFinAcknowledged = true
        flight = null
        probeOutstanding = false
        if (!established) established = true
      } else if (current != null) {
        // A partial ACK retains only the unacknowledged tail, with its original deadline/backoff.
        current.segment = current.segment.copy(sequence = segment.acknowledgement,
          payload = current.segment.payload.copyOfRange(ackAdvance.toInt(), current.segment.payload.size))
      }
    }
    // Old ACKs do not update send state/window, but their in-order payload is still received.
    if (!oldAck) {
      clientWindow = segment.window
      unansweredProbes = 0
      if (clientWindow > 0) {
        probeAt = null; probeDelay = 1000; probeOutstanding = false; persistUnansweredSince = null
      } else {
        persistUnansweredSince = now // Each acceptable ACK answers persist, even at zero window.
        if (flight?.segment?.payload?.isNotEmpty() == true && probeAt == null) probeAt = now + probeDelay
      }
    }
    if (persistExpired(now)) return abortConnection()
    expireIncomplete(now)
    lastActivity = now
    if (payload.isNotEmpty()) {
      if (ownFin || closingForIdle) return abort(segment)
      receiveNext = addSequence(receiveNext, payload.size)
      if (bufferedBytes + payload.size > MAX_BUFFERED_BYTES - family.mss) return abortConnection()
      if (incoming.isEmpty()) incompleteSince = now
      incoming += payload
      var cursor = 0
      while (incoming.size - cursor >= 2) {
        val size = IpPacket.u16(incoming, cursor)
        if (incoming.size - cursor < size + 2) break
        val query = DnsMessage.parseQuery(incoming.copyOfRange(cursor + 2, cursor + size + 2))
        cursor += size + 2
        // Invalid DNS messages are dropped without poisoning the next framed request.
        if (query != null) {
          if (pending.size >= MAX_PENDING || bufferedBytes + query.wire.size + query.question.wire.size > MAX_BUFFERED_BYTES - family.mss) return abort(segment)
          val request = Request(nextKey++, query)
          pending[request.key] = query
          requests += request
        }
      }
      // One compaction per segment, even for thousands of empty/malformed frames.
      if (cursor > 0) {
        incoming = incoming.copyOfRange(cursor, incoming.size)
        incompleteSince = if (incoming.isEmpty()) null else now
      }
    }
    if (fin) {
      receiveNext = addSequence(receiveNext, 1)
      peerFin = true
      incompleteSince = null
      incoming = byteArrayOf() // An incomplete final frame cannot be answered.
    }
    if (payload.isNotEmpty() || fin) sent += ack()
    if (peerFin && ownFinAcknowledged) {
      graceful = true
      terminate()
    } else flush(now, sent)
    return Output(sent, requests)
  }

  fun complete(key: Long, answer: ByteArray?, now: Long): Output {
    checkTime(now)
    if (closed) return Output()
    if (persistExpired(now)) return abortConnection()
    val query = pending[key] ?: return Output()
    val bytes = when (val result = DnsMessage.relay(query, answer, family, tcp = true)) {
      is DnsMessage.RelayResult.Answered -> result.bytes
      is DnsMessage.RelayResult.Servfail -> result.bytes
      DnsMessage.RelayResult.Discard -> return Output()
    }
    expireIncomplete(now)
    pending.remove(key)
    val frame = ByteArray(2 + bytes.size)
    IpPacket.put16(frame, 0, bytes.size)
    bytes.copyInto(frame, 2)
    if (bufferedBytes + frame.size > MAX_BUFFERED_BYTES - family.mss) return abortConnection()
    outgoing.addLast(frame)
    queuedBytes += frame.size
    lastActivity = now
    val sent = mutableListOf<Tcp>()
    flush(now, sent)
    return Output(sent)
  }

  /** Call when a deadline is due (or more often); all deadlines use the supplied monotonic time. */
  fun tick(now: Long): Output {
    checkTime(now)
    if (closed || synAck == null) return Output()
    if (persistExpired(now)) return abortConnection()
    expireIncomplete(now)
    val sent = mutableListOf<Tcp>()
    val current = flight
    if (current != null) {
      if (established && current.segment.payload.isNotEmpty() && clientWindow == 0) {
        // Persist has its own response count and unanswered-time limit.
        probe(now, sent, current.segment.copy(payload = current.segment.payload.copyOf(1)))
      } else if (now - current.sentAt >= current.delay) {
        // Three retransmissions after the original: at 1, 3 and 7 seconds. Abort at 15 seconds.
        if (current.retries == 3) return abortConnection()
        val retransmit = if (current.segment.payload.size > clientWindow && current.segment.payload.isNotEmpty())
          current.segment.copy(payload = current.segment.payload.copyOf(clientWindow)) else current.segment
        sent += retransmit.copy(acknowledgement = receiveNext)
        current.sentAt = now
        current.delay *= 2
        current.retries++
      }
    }
    // Pending upstream work/data must finish before FIN; an incomplete input frame cannot keep an
    // otherwise idle connection alive forever. Its bytes are discarded at the idle deadline.
    if (established && pending.isEmpty() && outgoing.isEmpty() && flight == null &&
      now - lastActivity >= 10000) {
      incoming = byteArrayOf()
      incompleteSince = null
      closingForIdle = true
      // Bound FIN-WAIT-2 if the peer ACKs our idle close but never closes its own send half.
      if (ownFinAcknowledged) return abortConnection()
    }
    flush(now, sent)
    return Output(sent)
  }

  private fun flush(now: Long, sent: MutableList<Tcp>) {
    if (!established || closed || flight != null || ownFin) return
    if (outgoing.isNotEmpty()) {
      val frame = outgoing.first()
      if (clientWindow == 0) {
        probe(now, sent, reply(ACK, payload = byteArrayOf(frame[outgoingOffset])))
        return
      }
      val length = minOf(frame.size - outgoingOffset, sendMss, clientWindow)
      val bytes = frame.copyOfRange(outgoingOffset, outgoingOffset + length)
      consume(length)
      transmit(reply(ACK or PSH, payload = bytes), now, sent)
    } else if (pending.isEmpty() && (peerFin || closingForIdle)) {
      // FIN consumes sequence space and cannot be sent outside a zero receive window.
      if (clientWindow == 0) {
        // With no data to probe, an old-sequence ACK elicits the peer's current window.
        probe(now, sent, ack().copy(sequence = addSequence(sendNext, -1)))
        return
      }
      ownFin = true
      transmit(reply(FIN or ACK), now, sent)
    }
  }

  private fun probe(now: Long, sent: MutableList<Tcp>, segment: Tcp) {
    val due = probeAt
    if (due == null) { probeAt = now + probeDelay; return }
    if (now < due) return
    unansweredProbes++
    sent += segment.copy(acknowledgement = receiveNext)
    probeOutstanding = flight == null && segment.payload.isNotEmpty()
    probeDelay = minOf(probeDelay * 2, 10000)
    probeAt = now + probeDelay
  }

  private fun needsProbe(): Boolean = flight?.segment?.payload?.isNotEmpty() == true || outgoing.isNotEmpty() ||
    (!ownFin && pending.isEmpty() && (peerFin || closingForIdle))

  private fun expireIncomplete(now: Long) {
    if (incompleteSince?.let { now - it >= 10000 } == true) {
      incoming = byteArrayOf()
      incompleteSince = null
      closingForIdle = true
    }
  }

  // Allow a response interval after probe three, then close before sending a fourth. Acceptable
  // ACKs refresh unanswered time; probe intervals stay below the 20-second response cutoff.
  private fun persistExpired(now: Long): Boolean = established && clientWindow == 0 &&
    ((persistUnansweredSince?.let { now - it > 20000 } == true) ||
      (unansweredProbes >= 3 && probeAt?.let { now >= it } == true))

  private fun consume(length: Int) {
    outgoingOffset += length
    queuedBytes -= length
    if (outgoingOffset == outgoing.first().size) { outgoing.removeFirst(); outgoingOffset = 0 }
  }

  private fun transmit(segment: Tcp, now: Long, sent: MutableList<Tcp>) {
    flight = Flight(segment, now)
    sendNext = addSequence(segment.sequence, segment.sequenceLength)
    sent += segment
  }

  private fun reply(flags: Int, payload: ByteArray = byteArrayOf(), options: ByteArray = byteArrayOf()): Tcp =
    Tcp(serverPort, clientPort, sendNext, receiveNext, flags, RECEIVE_WINDOW, payload, options)
  private fun ack(): Tcp = reply(ACK)
  private fun samePorts(segment: Tcp) = segment.sourcePort == clientPort && segment.destinationPort == serverPort
  private fun distance(from: Long, to: Long): Long = (to - from) and 0xffffffffL
  private fun checkTime(now: Long) { require(now >= lastTime) { "Time must be monotonic" }; lastTime = now }
  private fun abort(segment: Tcp): Output {
    terminate()
    return Output(listOfNotNull(IpPacket.reset(segment)))
  }
  private fun abortConnection(): Output {
    val reset = reply(RST or ACK)
    terminate()
    return Output(listOf(reset))
  }
  private fun terminate() {
    closed = true
    pending.clear()
    outgoing.clear()
    queuedBytes = 0
    outgoingOffset = 0
    incoming = byteArrayOf()
    flight = null
    synAck = null
    probeAt = null
    persistUnansweredSince = null
    incompleteSince = null
    unansweredProbes = 0
    probeOutstanding = false
  }

  private companion object {
    const val RECEIVE_WINDOW = 16384
    // Bound asynchronous work for an untrusted local client; overflow resets only this connection.
    const val MAX_PENDING = 64
    const val MAX_BUFFERED_BYTES = 1024 * 1024
  }
}
