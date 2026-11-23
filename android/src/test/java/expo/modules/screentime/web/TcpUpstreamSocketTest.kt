package expo.modules.screentime.web

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class TcpUpstreamSocketTest {
  private val first = DnsUpstreamRoute(1, InetAddress.getByName("192.0.2.1"))

  @Test fun requestsToSameNetworkAndResolverReuseTheirSocket() {
    TcpUpstreamSocket().use { upstream ->
      val initial = upstream.connect(first) { }
      val reused = upstream.connect(first.copy()) { fail("same route must reuse the connection") }
      assertSame(initial, reused)
      assertFalse(initial.isClosed)
    }
  }

  @Test fun resolverChangeClosesOldSocketEvenWhenItHasNotFailed() {
    TcpUpstreamSocket().use { upstream ->
      val initial = upstream.connect(first) { }
      val replacement = upstream.connect(first.copy(server = InetAddress.getByName("192.0.2.2"))) {
        assertTrue("old resolver must be closed before reconnecting", initial.isClosed)
      }
      assertNotSame(initial, replacement)
      assertFalse(replacement.isClosed)
    }
  }

  @Test fun sameResolverOnDifferentNetworkRequiresNewSocket() {
    TcpUpstreamSocket().use { upstream ->
      val initial = upstream.connect(first) { }
      val replacement = upstream.connect(first.copy(networkHandle = 2)) { }
      assertTrue(initial.isClosed)
      assertNotSame(initial, replacement)
    }
  }

  @Test fun connectionFailureClosesCandidateAndAllowsRetry() {
    TcpUpstreamSocket().use { upstream ->
      var failed: Socket? = null
      assertThrows(IOException::class.java) {
        upstream.connect(first) { failed = it; throw IOException("bind/protect failed") }
      }
      assertTrue(failed!!.isClosed)
      val retry = upstream.connect(first) { }
      assertNotSame(failed, retry)
      assertFalse(retry.isClosed)
    }
  }

  @Test fun disconnectClosesSocketAndAllowsReconnect() {
    TcpUpstreamSocket().use { upstream ->
      val initial = upstream.connect(first) { }
      upstream.disconnect()
      assertTrue(initial.isClosed)
      assertNotSame(initial, upstream.connect(first) { })
    }
  }

  @Test fun closingPreventsLaterConnects() {
    val upstream = TcpUpstreamSocket()
    val initial = upstream.connect(first) { }
    upstream.close()
    assertTrue(initial.isClosed)
    assertThrows(IOException::class.java) { upstream.connect(first) { fail("closed worker must not connect") } }
  }

  @Test fun shutdownClosesSocketDuringPendingConnectWithoutWaitingForIt() {
    val upstream = TcpUpstreamSocket()
    val connecting = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val candidate = AtomicReference<Socket>()
    val failure = AtomicReference<Throwable>()
    val worker = Thread {
      try {
        upstream.connect(first) {
          candidate.set(it)
          connecting.countDown()
          check(finish.await(3, TimeUnit.SECONDS))
        }
      } catch (error: Throwable) { failure.set(error) }
    }
    worker.start()
    try {
      assertTrue(connecting.await(3, TimeUnit.SECONDS))
      upstream.close()
      assertTrue(candidate.get().isClosed)
    } finally {
      finish.countDown()
      worker.join(3000)
      upstream.close()
    }
    assertFalse(worker.isAlive)
    assertTrue(failure.get() is IOException)
  }
}
