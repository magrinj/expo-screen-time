package expo.modules.screentime.web

/** Stable, directional flow identity. Address copies prevent callers from corrupting a map key. */
internal class Flow(
  val family: IpPacket.Family,
  val protocol: Int,
  source: ByteArray,
  destination: ByteArray,
  val sourcePort: Int,
  val destinationPort: Int,
) {
  private val sourceBytes = source.copyOf()
  private val destinationBytes = destination.copyOf()
  val source: ByteArray get() = sourceBytes.copyOf()
  val destination: ByteArray get() = destinationBytes.copyOf()

  init {
    require(source.size == family.addressSize && destination.size == family.addressSize)
    require(protocol in 0..255 && sourcePort in 0..65535 && destinationPort in 0..65535)
  }

  override fun equals(other: Any?): Boolean = other is Flow && family == other.family && protocol == other.protocol &&
    sourcePort == other.sourcePort && destinationPort == other.destinationPort &&
    sourceBytes.contentEquals(other.sourceBytes) && destinationBytes.contentEquals(other.destinationBytes)

  override fun hashCode(): Int {
    var result = family.hashCode()
    result = 31 * result + protocol
    result = 31 * result + sourceBytes.contentHashCode()
    result = 31 * result + destinationBytes.contentHashCode()
    result = 31 * result + sourcePort
    return 31 * result + destinationPort
  }
}
