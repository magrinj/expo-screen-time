package expo.modules.screentime.web

/**
 * Preview budget: 16 TCP connections total, 4 per known owner UID (API 29+).
 * Active responders retain at most 16 MiB (4 MiB per UID); upstream buffers add
 * at most 2 MiB, plus socket/thread overhead and transient worker teardown.
 * UDP: 128 outstanding queries and 128 queued registrations, drained 128 per select.
 * Including cancelled-key handoff, conservatively <4 MiB payload/name storage,
 * plus a shared 64 KiB receive buffer and socket/object overhead (not a RSS bound).
 * Eviction is insertion-order, not LRU, so a busy abusive connection cannot pin its slot.
 */
internal class ConnectionBudget(private val total: Int = 16, private val perOwner: Int = 4) {
  private val owners = linkedMapOf<Flow, Int?>()
  fun admit(flow: Flow, owner: Int?): List<Flow> {
    if (flow in owners) return emptyList()
    val evicted = mutableListOf<Flow>()
    if (owner != null && owners.values.count { it == owner } >= perOwner) {
      owners.entries.first { it.value == owner }.key.let { owners.remove(it); evicted += it }
    }
    if (owners.size >= total) owners.keys.first().let { owners.remove(it); evicted += it }
    owners[flow] = owner
    return evicted
  }
  fun remove(flow: Flow) { owners.remove(flow) }
}

internal data class PendingQueryKey(val flow: Flow, val id: Int)

/** One deadline per query; replacing a duplicate releases its old socket too. */
internal class PendingQueries(private val maximum: Int = 128) {
  private val deadlines = linkedMapOf<PendingQueryKey, Long>()
  val size: Int get() = deadlines.size
  fun add(key: PendingQueryKey, now: Long): List<PendingQueryKey> {
    val removed = mutableListOf<PendingQueryKey>()
    if (deadlines.remove(key) != null) removed += key
    if (deadlines.size >= maximum) deadlines.keys.first().let { deadlines.remove(it); removed += it }
    deadlines[key] = now + TIMEOUT_MS
    return removed
  }
  fun remove(key: PendingQueryKey) { deadlines.remove(key) }
  fun expired(now: Long): List<PendingQueryKey> = deadlines.filterValues { it <= now }.keys.toList()
  companion object { const val TIMEOUT_MS = 5000L }
}

internal enum class DnsPair(val ipv4: String, val ipv6: String) {
  A("10.111.222.1", "fd00:6578:706f::1"), B("10.111.222.3", "fd00:6578:706f::3");
  fun next(): DnsPair = if (this == A) B else A
}
