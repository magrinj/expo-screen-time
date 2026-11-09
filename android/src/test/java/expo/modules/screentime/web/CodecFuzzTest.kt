package expo.modules.screentime.web

import org.junit.Test
import java.util.Random

internal class CodecFuzzTest {
  // The outer timeout also fails a non-returning call; per-call timing below catches slow returns.
  @Test(timeout = 45000) fun deterministicTwoHundredThousandInputs() {
    CodecFuzz.run(200_000)
  }
}

/** The same driver runs outside JUnit via main(2000000); no Gradle property or suite-wide switch. */
internal object CodecFuzz {
  private const val SEED = 0x77669293L
  private const val CALL_LIMIT_NS = 1_000_000_000L

  @JvmStatic fun main(args: Array<String>) { run(args.firstOrNull()?.toInt() ?: 2_000_000) }

  fun run(inputs: Int) {
    val random = Random(SEED)
    val q = query(queryWire(edns = 4096))
    val querySeeds = listOf(q.wire, queryWire(), queryWire(edns = 1232, version = 1),
      q.wire.copyOf().also { IpPacket.put16(it, 12, 0xc00c) },
      q.wire.copyOf().also { IpPacket.put16(it, 12, 0xc00e); IpPacket.put16(it, 14, 0xc00c) })
    val answer = answerWire(q, 600)
    val rr = 12 + q.question.wire.size
    val responseSeeds = listOf(answer,
      answer.copyOf().also { IpPacket.put16(it, rr, 0xc000 or rr) },
      answer.copyOf().also { IpPacket.put16(it, rr, 0xc000 or (rr + 2)); IpPacket.put16(it, rr + 2, 0xc000 or rr) },
      answer.copyOf().also { IpPacket.put16(it, 0, q.id xor 1) })
    val packetSeeds = IpPacket.Family.entries.map { family ->
      listOf(
        IpPacket.build(family, ByteArray(family.addressSize) { 1 }, ByteArray(family.addressSize) { 2 }, IpPacket.Udp(40000, 53, q.wire)),
        IpPacket.build(family, ByteArray(family.addressSize) { 1 }, ByteArray(family.addressSize) { 2 },
          IpPacket.Tcp(40000, 53, 0xfffffff0L, flags = IpPacket.SYN, options = byteArrayOf(2, 4, 2, 24))),
      )
    }
    var calls = 0
    var maxCallNs = 0L
    var maxBuffered = 0
    var currentInput = 0
    val started = System.nanoTime()
    fun <T> checked(operation: String, block: () -> T): T {
      val before = System.nanoTime()
      val result = try { block() } catch (failure: Throwable) {
        throw AssertionError("seed=$SEED input=$currentInput operation=$operation", failure)
      }
      val elapsed = System.nanoTime() - before
      check(elapsed < CALL_LIMIT_NS) { "seed=$SEED input=$currentInput $operation exceeded 1 s: $elapsed ns" }
      maxCallNs = maxOf(maxCallNs, elapsed)
      calls++
      return result
    }
    var client = FuzzClient(random)
    fun observe(output: TcpDnsResponder.Output) {
      client.observe(output)
      val buffered = checked("bufferedBytes") { client.responder.bufferedBytes }
      check(buffered in 0..1024 * 1024) { "seed=$SEED input=$currentInput retained $buffered bytes" }
      maxBuffered = maxOf(maxBuffered, buffered)
      val deadline = checked("nextDeadline") { client.responder.nextDeadline() }
      if (client.responder.closed) check(buffered == 0 && deadline == null)
    }
    repeat(inputs) { index ->
      currentInput = index
      when (index % 4) {
        0, 1 -> {
          val family = IpPacket.Family.entries[index % 4]
          val seed = packetSeeds[index % 4][random.nextInt(2)]
          val bytes = mutate(random, seed)
          // Some mutations retain valid checksums, reaching header/transport validation past them.
          if (random.nextInt(4) == 0) repairChecksums(bytes, family)
          checked("IpPacket.parse/$family") { IpPacket.parse(bytes) }
        }
        2 -> {
          val bytes = mutate(random, querySeeds[random.nextInt(querySeeds.size)])
          checked("parseQuery") { DnsMessage.parseQuery(bytes) }
          val response = mutate(random, responseSeeds[random.nextInt(responseSeeds.size)])
          checked("relay") { DnsMessage.relay(q, response, IpPacket.Family.IPV4, tcp = random.nextBoolean()) }
        }
        3 -> {
          if (client.responder.closed || client.operations >= 128) client = FuzzClient(random)
          client.operations++
          client.now += random.nextInt(20)
          if (!client.started) {
            client.started = true
            val options = if (random.nextBoolean()) byteArrayOf(2, 4, 2, 24) else byteArrayOf()
            observe(checked("SYN") { client.responder.receive(client.segment(flags = IpPacket.SYN, sequence = client.isn, options = options), client.now) })
            observe(checked("handshake ACK") { client.responder.receive(client.segment(), client.now) })
          } else when (random.nextInt(16)) {
            in 0..4 -> {
              val payload = when (random.nextInt(8)) {
                0 -> ByteArray(16384) // 8192 empty frames: exercise linear compaction.
                1 -> byteArrayOf(0xff.toByte(), 0xff.toByte()) + ByteArray(1024)
                2 -> frame(mutate(random, q.wire))
                3 -> frame(q.wire).copyOf(random.nextInt(q.wire.size + 2))
                else -> frame(q.wire)
              }
              observe(checked("data") { client.responder.receive(client.segment(payload = payload), client.now) })
            }
            in 5..8 -> {
              if (client.pending.isNotEmpty()) {
                val request = client.pending[random.nextInt(client.pending.size)]
                val bytes = when (random.nextInt(6)) {
                  0 -> null
                  1 -> mutate(random, responseSeeds[0])
                  2 -> answerWire(request.query, 65535)
                  else -> answerWire(request.query, 2000)
                }
                observe(checked("complete") { client.responder.complete(request.key, bytes, client.now) })
                // Keep some old keys too: duplicate and delayed completions must be safe.
                if (random.nextBoolean()) client.pending.remove(request)
              } else observe(checked("empty complete") { client.responder.complete(-1, null, client.now) })
            }
            9, 10 -> {
              val window = listOf(0, 1, 31, 536, 16384, 65535)[random.nextInt(6)]
              observe(checked("window/ACK") { client.responder.receive(client.segment(window = window), client.now) })
            }
            11, 12 -> {
              client.now += random.nextInt(3000)
              val deadline = checked("scheduled deadline") { client.responder.nextDeadline() }
              if (deadline != null && random.nextBoolean()) client.now = maxOf(client.now, deadline)
              observe(checked("tick") { client.responder.tick(client.now) })
              val next = checked("deadline after tick") { client.responder.nextDeadline() }
              check(next == null || next > client.now) { "seed=$SEED input=$index non-advancing deadline $next at ${client.now}" }
            }
            13 -> observe(checked("FIN or alternate SYN") { client.responder.receive(client.segment(
              flags = if (random.nextBoolean()) IpPacket.ACK or IpPacket.FIN else IpPacket.SYN,
              sequence = if (random.nextBoolean()) client.sequence else random.nextLong() and 0xffffffffL), client.now) })
            else -> {
              val bytes = ByteArray(random.nextInt(128)); random.nextBytes(bytes)
              val segment = client.segment(payload = bytes, flags = random.nextInt(256),
                sequence = random.nextLong() and 0xffffffffL, acknowledgement = random.nextLong() and 0xffffffffL,
                window = random.nextInt(65536), options = mutate(random, byteArrayOf(2, 4, 2, 24)).take(40).toByteArray())
              observe(checked("random segment") { client.responder.receive(segment, client.now) })
            }
          }
        }
      }
    }
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    println("CodecFuzz seed=$SEED inputs=$inputs (IPv4=${inputs / 4}, IPv6=${inputs / 4}, DNS=${inputs / 4}, TCP=${inputs / 4}) calls=$calls elapsedMs=$elapsedMs maxCallMs=${maxCallNs / 1_000_000.0} maxBufferedBytes=$maxBuffered crashes=0")
  }

  private class FuzzClient(random: Random) {
    val isn = random.nextLong() and 0xffffffffL
    val responder = TcpDnsResponder(if (random.nextBoolean()) IpPacket.Family.IPV4 else IpPacket.Family.IPV6,
      random.nextLong() and 0xffffffffL)
    var sequence = IpPacket.addSequence(isn, 1)
    var acknowledgement = 0L
    var now = 0L
    var started = false
    var operations = 0
    val pending = mutableListOf<TcpDnsResponder.Request>()
    fun segment(payload: ByteArray = byteArrayOf(), flags: Int = IpPacket.ACK,
      sequence: Long = this.sequence, acknowledgement: Long = this.acknowledgement,
      window: Int = 16384, options: ByteArray = byteArrayOf()) =
      IpPacket.Tcp(40000, 53, sequence, acknowledgement, flags, window, payload, options)
    fun observe(output: TcpDnsResponder.Output) {
      pending += output.requests
      output.segments.forEach {
        if (it.has(IpPacket.ACK)) sequence = it.acknowledgement
        if (it.sequenceLength > 0) acknowledgement = IpPacket.addSequence(it.sequence, it.sequenceLength)
      }
      if (responder.closed) pending.clear()
    }
  }

  private fun mutate(random: Random, seed: ByteArray): ByteArray = when (random.nextInt(6)) {
    0 -> ByteArray(random.nextInt(1024)).also { random.nextBytes(it) }
    1 -> seed.copyOf(random.nextInt(seed.size + 1))
    2, 3 -> seed.copyOf().also { bytes ->
      if (bytes.isNotEmpty()) repeat(1 + random.nextInt(4)) {
        val at = random.nextInt(bytes.size)
        bytes[at] = (bytes[at].toInt() xor (1 shl random.nextInt(8))).toByte()
      }
    }
    4 -> seed + ByteArray(random.nextInt(32))
    else -> seed.copyOf()
  }

  private fun repairChecksums(bytes: ByteArray, family: IpPacket.Family) {
    if (bytes.size < family.headerSize || IpPacket.u8(bytes, 0) ushr 4 != if (family == IpPacket.Family.IPV4) 4 else 6) return
    val start = if (family == IpPacket.Family.IPV4) (IpPacket.u8(bytes, 0) and 15) * 4 else 40
    if (start < family.headerSize || start > bytes.size) return
    val protocol = IpPacket.u8(bytes, if (family == IpPacket.Family.IPV4) 9 else 6)
    if (family == IpPacket.Family.IPV4) {
      IpPacket.put16(bytes, 10, 0)
      IpPacket.put16(bytes, 10, IpPacket.checksum(bytes.copyOf(start)))
    }
    val sumAt = when (protocol) { 17 -> 6; 6 -> 16; else -> return }
    if (start + sumAt + 2 > bytes.size) return
    val addressAt = if (family == IpPacket.Family.IPV4) 12 else 8
    val source = bytes.copyOfRange(addressAt, addressAt + family.addressSize)
    val destination = bytes.copyOfRange(addressAt + family.addressSize, addressAt + family.addressSize * 2)
    IpPacket.put16(bytes, start + sumAt, 0)
    val sum = IpPacket.transportChecksum(family, source, destination, protocol, bytes.copyOfRange(start, bytes.size))
    IpPacket.put16(bytes, start + sumAt, if (protocol == 17 && sum == 0) 65535 else sum)
  }
}
