package expo.modules.screentime.web

import org.junit.Assert.*
import org.junit.Test

class WebLimitsTest {
  private fun flow(port: Int) = Flow(IpPacket.Family.IPV4, 6, byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), port, 53)
  @Test fun capEvictsOldestPerOwner() {
    val budget = ConnectionBudget(8, 2)
    budget.admit(flow(1), 100); budget.admit(flow(2), 200); budget.admit(flow(3), 100)
    assertEquals(listOf(flow(1)), budget.admit(flow(4), 100))
  }
  @Test fun totalCapAppliesAcrossOwners() {
    val budget = ConnectionBudget(2, 2)
    budget.admit(flow(1), 100); budget.admit(flow(2), 200)
    assertEquals(listOf(flow(1)), budget.admit(flow(3), 300))
  }
  @Test fun unknownOwnersUseOnlyTotalCap() {
    val budget = ConnectionBudget(3, 1)
    repeat(3) { assertTrue(budget.admit(flow(it), null).isEmpty()) }
    assertEquals(listOf(flow(0)), budget.admit(flow(4), null))
  }
  @Test fun activityDoesNotMakeAnOldConnectionImmune() {
    val budget = ConnectionBudget(2, 2)
    budget.admit(flow(1), 100); budget.admit(flow(2), 100); budget.admit(flow(1), 100)
    assertEquals(listOf(flow(1)), budget.admit(flow(3), 100))
  }
  @Test fun removalFreesTheOwnerSlot() {
    val budget = ConnectionBudget(2, 1)
    budget.admit(flow(1), 100); budget.remove(flow(1))
    assertTrue(budget.admit(flow(2), 100).isEmpty())
  }
  @Test fun defaultsCapAtFourPerUid() {
    val budget = ConnectionBudget()
    repeat(4) { assertTrue(budget.admit(flow(it), 100).isEmpty()) }
    assertEquals(listOf(flow(0)), budget.admit(flow(4), 100))
  }
  @Test fun defaultsCapAtSixteenTotal() {
    val budget = ConnectionBudget()
    repeat(16) { assertTrue(budget.admit(flow(it), it).isEmpty()) }
    assertEquals(listOf(flow(0)), budget.admit(flow(16), 16))
  }
  @Test fun pairAlternatesAndRoutesBothFamilies() {
    assertEquals(DnsPair.B, DnsPair.A.next()); assertEquals(DnsPair.A, DnsPair.B.next())
    assertEquals("10.111.222.1", DnsPair.A.ipv4); assertEquals("fd00:6578:706f::3", DnsPair.B.ipv6)
  }
  @Test fun pendingBoundEvictsOldest() {
    val pending = PendingQueries(2)
    pending.add(PendingQueryKey(flow(1), 1), 0); pending.add(PendingQueryKey(flow(2), 1), 0)
    assertEquals(listOf(PendingQueryKey(flow(1), 1)), pending.add(PendingQueryKey(flow(3), 1), 0))
    assertEquals(2, pending.size)
  }
  @Test fun duplicatePendingKeyReplacesOldSocket() {
    val pending = PendingQueries(); val key = PendingQueryKey(flow(1), 1)
    pending.add(key, 0); assertEquals(listOf(key), pending.add(key, 100)); assertEquals(1, pending.size)
  }
  @Test fun pendingExpiresAtFiveSeconds() {
    val pending = PendingQueries(); val key = PendingQueryKey(flow(1), 1)
    pending.add(key, 100)
    assertTrue(pending.expired(5099).isEmpty()); assertEquals(listOf(key), pending.expired(5100))
  }
  @Test fun sameIdOnDifferentFlowsDoesNotCollide() {
    val pending = PendingQueries()
    pending.add(PendingQueryKey(flow(1), 1), 0); pending.add(PendingQueryKey(flow(2), 1), 0)
    assertEquals(2, pending.size)
  }
  @Test fun differentIdsOnSameFlowDoNotCollide() {
    val pending = PendingQueries()
    pending.add(PendingQueryKey(flow(1), 1), 0); pending.add(PendingQueryKey(flow(1), 2), 0)
    assertEquals(2, pending.size)
  }
  @Test fun pendingDefaultsBoundAt128() {
    val pending = PendingQueries()
    repeat(128) { pending.add(PendingQueryKey(flow(it), it), 0) }
    assertEquals(1, pending.add(PendingQueryKey(flow(129), 129), 0).size); assertEquals(128, pending.size)
  }
}
