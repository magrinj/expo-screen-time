package expo.modules.screentime.web

import java.io.IOException
import java.net.InetAddress
import java.net.Socket

internal data class DnsUpstreamRoute(val networkHandle: Long, val server: InetAddress)

/**
 * One TCP worker reuses a socket only for the same physical network and resolver.
 * Shutdown may close it from another thread, including while bind/connect is pending.
 * Blocking connection setup never holds the lock needed by shutdown.
 */
internal class TcpUpstreamSocket : AutoCloseable {
  private data class Connection(val route: DnsUpstreamRoute, val socket: Socket)
  private var connection: Connection? = null
  private var closed = false

  fun connect(route: DnsUpstreamRoute, configure: (Socket) -> Unit): Socket {
    val next = synchronized(this) {
      if (closed) throw IOException("closed")
      connection?.takeIf { it.route == route && !it.socket.isClosed }?.let { return it.socket }
      disconnect()
      Connection(route, Socket()).also { connection = it }
    }
    try {
      configure(next.socket)
      synchronized(this) {
        if (closed || connection !== next || next.socket.isClosed) throw IOException("closed during connect")
      }
      return next.socket
    } catch (failure: Exception) {
      synchronized(this) { if (connection === next) disconnect() }
      throw failure
    }
  }

  @Synchronized fun disconnect() {
    val previous = connection
    connection = null
    runCatching { previous?.socket?.close() }
  }

  @Synchronized override fun close() {
    closed = true
    disconnect()
  }
}
