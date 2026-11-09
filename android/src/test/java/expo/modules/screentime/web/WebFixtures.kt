package expo.modules.screentime.web

import java.io.ByteArrayOutputStream

internal fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun queryWire(
  name: String = "example.com", type: Int = 1, id: Int = 0x1234, rd: Boolean = true,
  edns: Int? = null, doBit: Boolean = false, version: Int = 0, options: ByteArray = byteArrayOf(),
): ByteArray {
  val header = ByteArray(12)
  IpPacket.put16(header, 0, id)
  IpPacket.put16(header, 2, if (rd) 0x100 else 0)
  IpPacket.put16(header, 4, 1)
  val question = ByteArrayOutputStream().apply {
    if (name.isNotEmpty()) name.removeSuffix(".").split('.').forEach {
      val bytes = it.toByteArray(); write(bytes.size); write(bytes)
    }
    write(0); write(type ushr 8); write(type); write(0); write(1)
  }.toByteArray()
  val opt = if (edns == null) byteArrayOf() else ByteArray(11).also {
    IpPacket.put16(header, 10, 1)
    IpPacket.put16(it, 1, 41)
    IpPacket.put16(it, 3, edns)
    it[6] = version.toByte()
    if (doBit) it[7] = 0x80.toByte()
    IpPacket.put16(it, 9, options.size)
  } + options
  return header + question + opt
}

internal fun query(bytes: ByteArray = queryWire()): DnsMessage.Query = requireNotNull(DnsMessage.parseQuery(bytes))
internal fun frame(bytes: ByteArray): ByteArray = ByteArray(2).also { IpPacket.put16(it, 0, bytes.size) } + bytes

/** One valid TXT RR, sized exactly; independent of the response encoder under test. */
internal fun answerWire(query: DnsMessage.Query, size: Int): ByteArray {
  val header = ByteArray(12)
  IpPacket.put16(header, 0, query.id)
  IpPacket.put16(header, 2, 0x8180)
  IpPacket.put16(header, 4, 1)
  IpPacket.put16(header, 6, 1)
  val record = hex("c00c001000010000003c0000")
  val data = ByteArray(size - header.size - query.question.wire.size - record.size)
  var at = 0
  while (at < data.size) {
    val count = minOf(255, data.size - at - 1)
    data[at] = count.toByte()
    repeat(count) { data[at + 1 + it] = 'x'.code.toByte() }
    at += 1 + count
  }
  IpPacket.put16(record, 10, data.size)
  return header + query.question.wire + record + data
}

internal fun parsedPacket(bytes: ByteArray): IpPacket.Packet? =
  (IpPacket.parse(bytes) as? IpPacket.ParseResult.Parsed)?.packet

internal fun relayedAnswer(query: DnsMessage.Query, bytes: ByteArray, family: IpPacket.Family, tcp: Boolean = false): ByteArray =
  (DnsMessage.relay(query, bytes, family, tcp) as DnsMessage.RelayResult.Answered).bytes
