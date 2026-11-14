package expo.modules.screentime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import expo.modules.screentime.web.ConnectionBudget
import expo.modules.screentime.web.DnsMessage
import expo.modules.screentime.web.DnsPair
import expo.modules.screentime.web.DnsUpstreamRoute
import expo.modules.screentime.web.DomainMatcher
import expo.modules.screentime.web.Flow
import expo.modules.screentime.web.IpPacket
import expo.modules.screentime.web.PendingQueries
import expo.modules.screentime.web.PendingQueryKey
import expo.modules.screentime.web.TcpDnsResponder
import expo.modules.screentime.web.TcpUpstreamSocket
import expo.modules.screentime.web.WebState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Debug-only, DNS-only VPN. No packet outside the two routed DNS pairs is proxied.
 * One control looper serializes state and descriptor writes; a blocking reader per
 * descriptor and a single UDP selector have backpressure (one delivered packet each).
 * TCP upstream workers are capped with ConnectionBudget: 16 total / 4 per owner UID,
 * <=16 MiB active responder buffers, <=2 MiB upstream buffers, plus 16 active workers.
 * UDP has 128 pending queries and 128 queued selector registrations. At most 128
 * registrations are drained per select, bounding retention of cancelled keys too:
 * conservatively <4 MiB payload/name storage including handoff, plus a shared 64 KiB
 * read buffer. Socket/object overhead and transient worker teardown are additional;
 * this bounds application buffers, not process RSS. UDP uses no per-query thread.
 * The notice uses the engine channel with a separate id to avoid replacing its notice.
 * Upstream selection uses the first server advertised by the underlay for both
 * transports; there is no resolver fallback. Bind/protect retries use the
 * next physical-network update, not a hard-coded resolver.
 * API 37 reports our own VPN to the default callback even with the host excluded.
 * A NOT_VPN best-match callback (31+) supplies the physical underlay in that case.
 * On 26-30, a passive NOT_VPN listener prefers validated, then unmetered networks,
 * retaining the last physical default on ties. It never requests an extra network.
 */
class ScreenTimeDnsFilterService : VpnService() {
  private val store by lazy { StateStore(this) }
  private val serviceId = UUID.randomUUID().toString()
  private val manager by lazy { getSystemService(ConnectivityManager::class.java) }
  private lateinit var thread: HandlerThread
  private lateinit var control: Handler
  private lateinit var selector: Selector
  private val registrations = ArrayBlockingQueue<Pair<DatagramChannel, UdpQuery>>(128)
  @Volatile private var destroyed = false
  @Volatile private var tunnel: Tunnel? = null
  private var domains: Set<String> = emptySet()
  private var matcher = DomainMatcher(emptyList())
  private var pair = DnsPair.A
  private val random = SecureRandom()
  private val pending = linkedMapOf<PendingQueryKey, UdpQuery>()
  private val pendingBudget = PendingQueries()
  private val connections = linkedMapOf<Flow, TcpConnection>()
  private val connectionBudget = ConnectionBudget()
  private val counters = linkedMapOf<String, Long>()
  private val networkMonitor = Object()
  @Volatile private var underlay: Underlay? = null
  @Volatile private var networkRevision = 0L
  private var defaultNetwork: Network? = null
  private var physicalDefault: Network? = null
  private var bestPhysical: Network? = null
  private val physicalNetworks = linkedMapOf<Network, Pair<NetworkCapabilities?, LinkProperties?>>()
  private var callbackRegistered = false
  private var physicalCallbackRegistered = false
  @Volatile private var promoted = false
  private var reviving = false
  private var promotionRetryable = false
  private var latestStartId = 0

  private data class Underlay(val network: Network, val servers: List<InetAddress>)
  private class Tunnel(val descriptor: ParcelFileDescriptor) {
    val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    val output = FileOutputStream(descriptor.fileDescriptor)
    fun close() { runCatching { input.close() } }
  }
  private class UdpQuery(val packet: IpPacket.Packet, val query: DnsMessage.Query, val owner: Tunnel) {
    val key = PendingQueryKey(packet.flow, query.id)
    var channel: DatagramChannel? = null
    var timeout: Runnable? = null
    var retryAfter: Long? = null
  }
  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) { control.post { defaultNetwork = network } }
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
      control.post {
        if (network != defaultNetwork) return@post
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) physicalDefault = network
        else Log.i(TAG, "web default is VPN id=$network; retaining physical underlay")
        selectUnderlay()
      }
    }
    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
      control.post {
        if (network == physicalDefault && network == defaultNetwork) {
          physicalNetworks[network] = physicalNetworks[network]?.first to properties
          selectUnderlay()
        }
      }
    }
    override fun onLost(network: Network) {
      control.post { if (network == defaultNetwork) { defaultNetwork = null; selectUnderlay() } }
    }
  }
  private val physicalCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) { control.post {
      physicalNetworks[network] = null to null
      if (Build.VERSION.SDK_INT >= 31) bestPhysical = network
    } }
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { control.post {
      physicalNetworks[network] = capabilities to physicalNetworks[network]?.second
      selectUnderlay()
    } }
    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { control.post {
      physicalNetworks[network] = physicalNetworks[network]?.first to properties
      selectUnderlay()
    } }
    override fun onLost(network: Network) { control.post {
      physicalNetworks.remove(network)
      if (bestPhysical == network) bestPhysical = null
      if (physicalDefault == network) physicalDefault = null
      selectUnderlay()
    } }
  }
  private val heartbeat = object : Runnable {
    override fun run() {
      if (destroyed || tunnel == null) return
      val current = try {
        store.update { document ->
          if (destroyed || !WebState.ownsService(document, serviceId)) return@update false
          WebState.record(document).optJSONObject("run")?.put("heartbeat", System.currentTimeMillis())
          WebPreviewController.armWatchdog(this@ScreenTimeDnsFilterService)
          true
        }
      } catch (failure: Exception) {
        Log.w(TAG, "web heartbeat failed ${failure.javaClass.simpleName}: ${failure.message}")
        control.postDelayed(this, 60_000)
        return
      }
      if (!current) return
      Log.i(TAG, "web counters $counters udp=${pending.size} tcp=${connections.size}")
      control.postDelayed(this, 60_000)
    }
  }

  override fun onCreate() {
    super.onCreate()
    store.update { WebState.claimService(it, serviceId) }
    thread = HandlerThread("expo-st-web-control").also { it.start() }
    control = Handler(thread.looper)
    selector = Selector.open()
    Thread({ receiveUdp() }, "expo-st-web-udp").apply { isDaemon = true; start() }
    try {
      manager.registerDefaultNetworkCallback(callback)
      callbackRegistered = true
      val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
      if (Build.VERSION.SDK_INT >= 31) manager.registerBestMatchingNetworkCallback(request, physicalCallback, control)
      else manager.registerNetworkCallback(request, physicalCallback)
      physicalCallbackRegistered = true
    } catch (failure: Exception) {
      Log.i(TAG, "web underlay unavailable ${failure.javaClass.simpleName}")
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!WebPreviewController.built(this)) { stopSelf(startId); return START_NOT_STICKY }
    latestStartId = startId
    if (WebPreviewController.foreground && !promote()) {
      if (!reviving && promotionRetryable && ForegroundStartExemption.available(this) && ForegroundStartExemption.blockedBy(this) == null) {
        reviving = true
        ForegroundStartExemption.attempt(this, "web:promote", { !destroyed && promote() }) { ok ->
          reviving = false
          if (!destroyed) {
            if (ok || promoted) dispatchSync(latestStartId) else promotionRefused(latestStartId)
          }
        }
      } else if (!reviving) promotionRefused(startId)
      return START_STICKY
    }
    dispatchSync(startId)
    return START_STICKY
  }

  private fun promotionRefused(startId: Int) {
    WebPreviewController.refused(this, "foreground:refused", certain = true)
    stopSelf(startId)
  }

  private fun dispatchSync(startId: Int) {
    control.post {
      try { sync(startId) } catch (failure: Exception) {
        Log.i(TAG, "web sync failed ${failure.javaClass.simpleName}: ${failure.message}")
        WebPreviewController.refused(this, "sync:${failure.javaClass.simpleName}", certain = true)
        shutdown(startId)
      }
    }
  }

  private fun sync(startId: Int) {
    if (destroyed) return
    store.update { document ->
      if (destroyed || !WebState.ownsService(document, serviceId)) return@update
      val boot = EnforcementGapRecorder(this, store).boot()
      WebState.observe(document, System.currentTimeMillis(), boot.count, boot.timeMs ?: 0)
      val desired = WebState.desired(document)
      if (WebState.consent(document).start(desired, tunnel != null, boot.count) != WebState.Start.establish) {
        Log.i(TAG, "web start refused consent=${WebState.consent(document).status} boot=${boot.count} desired=$desired")
        shutdown(startId)
        return@update
      }
      val nextDomains = WebState.domains(document)
      if (tunnel != null && nextDomains == domains) {
        WebPreviewController.armWatchdog(this)
        return@update
      }
      val nextPair = if (tunnel == null) pair else pair.next()
      val builder = Builder().setSession(applicationInfo.loadLabel(packageManager).toString())
        .addAddress("10.111.222.2", 32).addAddress("fd00:6578:706f::2", 128)
        .addDnsServer(nextPair.ipv4).addDnsServer(nextPair.ipv6)
        .addRoute("10.111.222.0", 30).addRoute("fd00:6578:706f::", 126)
        .addDisallowedApplication(packageName).setBlocking(true).setMtu(1500)
      if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
      underlay?.let { builder.setUnderlyingNetworks(arrayOf(it.network)) }
      val descriptor = try { builder.establish() } catch (failure: Exception) {
        WebState.event(document, WebState.Event.establishFailed, System.currentTimeMillis(), boot.count, boot.timeMs ?: 0)
        WebPreviewController.refused(this, "establish:${failure.javaClass.simpleName}", certain = true)
        shutdown(startId)
        return@update
      }
      if (descriptor == null) {
        WebState.event(document, WebState.Event.establishDenied, System.currentTimeMillis(), boot.count, boot.timeMs ?: 0)
        Log.i(TAG, "web establish returned null; consent denied")
        shutdown(startId)
        return@update
      }
      val previous = tunnel
      val next = Tunnel(descriptor)
      tunnel = next
      domains = nextDomains
      matcher = DomainMatcher(domains)
      for (invalid in matcher.invalidNames) Log.v(TAG, "web invalid list name=$invalid")
      pair = nextPair
      pending.values.toList().forEach { dropUdp(it, "pair-swap") }
      previous?.close()
      readTunnel(next)
      WebState.started(document, System.currentTimeMillis(), Process.myPid(), boot.count, boot.timeMs ?: 0)
      Log.i(TAG, "web ${if (previous == null) "established" else "swap"} pair=$pair dns=${pair.ipv4},${pair.ipv6} underlay=${underlay?.network} pid=${Process.myPid()}")
      control.removeCallbacks(heartbeat)
      heartbeat.run()
    }
  }

  private fun readTunnel(owner: Tunnel) {
    Thread({
      val buffer = ByteArray(1500)
      try {
        while (!destroyed && tunnel === owner) {
          val size = owner.input.read(buffer)
          if (size <= 0) break
          val packet = buffer.copyOf(size)
          if (!deliver { if (tunnel === owner) receive(packet, owner) }) {
            control.post { if (tunnel === owner) { count("tun-delivery-timeout"); shutdown() } }
            return@Thread
          }
        }
        control.post { if (tunnel === owner) { count("tun-eof"); shutdown() } }
      } catch (failure: Exception) {
        control.post { if (tunnel === owner) { count("tun-read-error"); shutdown() } }
      }
    }, "expo-st-web-tun").apply { isDaemon = true; start() }
  }

  private fun deliver(block: () -> Unit): Boolean {
    if (destroyed) return false
    val delivered = CountDownLatch(1)
    if (!control.post {
      try { if (!destroyed) block() } catch (failure: Exception) {
        Log.i(TAG, "web delivery failed ${failure.javaClass.simpleName}")
        count("packet-processing-error")
        shutdown()
      } finally { delivered.countDown() }
    }) return false
    return delivered.await(10, TimeUnit.SECONDS) && !destroyed
  }

  private fun receive(bytes: ByteArray, owner: Tunnel) {
    val packet = when (val parsed = IpPacket.parse(bytes)) {
      is IpPacket.ParseResult.Dropped -> { count(parsed.reason.name); return }
      is IpPacket.ParseResult.Parsed -> parsed.packet
    }
    when (val transport = packet.transport) {
      is IpPacket.Udp -> {
        if (transport.destinationPort != 53) { count("udp-port"); write(IpPacket.portUnreachable(packet)); return }
        val query = DnsMessage.parseQuery(transport.payload)
        if (query == null) { count("dns-malformed"); return }
        Log.v(TAG, "web UDP query id=${query.id} name=${query.question.name} type=${query.question.type}")
        val local = DnsMessage.localAnswer(query, matcher, packet.family)
        if (local != null) { udpAnswer(packet, local); return }
        val request = UdpQuery(packet, query, owner)
        pending[request.key]?.let { dropUdp(it, "udp-duplicate") }
        for (evicted in pendingBudget.add(request.key, now())) pending[evicted]?.let { dropUdp(it, "udp-cap-or-duplicate") }
        pending[request.key] = request
        val timeout = Runnable { if (pending[request.key] === request) dropUdp(request, "udp-timeout") }
        request.timeout = timeout
        control.postDelayed(timeout, PendingQueries.TIMEOUT_MS)
        forwardUdp(request)
      }
      is IpPacket.Tcp -> {
        if (transport.destinationPort != 53) {
          count("tcp-port")
          IpPacket.reset(transport)?.let { write(packet.reply(it)) }
          return
        }
        val flow = packet.flow
        var connection = connections[flow]
        if (connection == null || connection.responder.closed) {
          connection?.let { removeTcp(it, reset = false) }
          if (!transport.has(IpPacket.SYN)) { IpPacket.reset(transport)?.let { write(packet.reply(it)) }; return }
          for (evicted in connectionBudget.admit(flow, ownerUid(flow))) connections[evicted]?.let { count("tcp-cap"); removeTcp(it, reset = true) }
          connection = TcpConnection(flow, packet)
          connections[flow] = connection
        }
        connection.lastSegment = transport
        output(connection, connection.responder.receive(transport, now()))
      }
    }
  }

  private fun ownerUid(flow: Flow): Int? {
    if (Build.VERSION.SDK_INT < 29) return null
    return try {
      manager.getConnectionOwnerUid(OsConstants.IPPROTO_TCP,
        InetSocketAddress(InetAddress.getByAddress(flow.source), flow.sourcePort),
        InetSocketAddress(InetAddress.getByAddress(flow.destination), flow.destinationPort)).takeIf { it >= 0 }
    } catch (_: Exception) { count("owner-uid-unavailable"); null }
  }

  private fun write(bytes: ByteArray) {
    val active = tunnel ?: return
    try { active.output.write(bytes) } catch (_: Exception) { count("tun-write-error"); shutdown() }
  }
  private fun udpAnswer(packet: IpPacket.Packet, bytes: ByteArray) = write(packet.reply(IpPacket.Udp(packet.transport.destinationPort, packet.transport.sourcePort, bytes)))

  private fun forwardUdp(request: UdpQuery, retry: Boolean = false) {
    val network = underlay
    if (network == null || network.servers.isEmpty()) { failUdp(request, "no-underlay"); return }
    val channel = try { DatagramChannel.open() } catch (_: Exception) {
      failUdp(request, "udp-socket-unavailable")
      return
    }
    request.channel = channel
    try {
      network.network.bindSocket(channel.socket())
      check(protect(channel.socket())) { "protect refused" }
    } catch (failure: Exception) {
      runCatching { channel.close() }
      request.channel = null
      if (retry) failUdp(request, "udp-bind-protect") else {
        count("udp-bind-protect-retry")
        Log.v(TAG, "web UDP bind/protect failed ${failure.javaClass.simpleName}: ${failure.message}")
        request.retryAfter = networkRevision
      }
      return
    }
    try {
      channel.configureBlocking(false)
      channel.connect(InetSocketAddress(network.servers.first(), 53))
      channel.write(ByteBuffer.wrap(request.query.upstream()))
      if (!registrations.offer(channel to request)) { failUdp(request, "udp-registration-cap"); return }
      selector.wakeup()
      count("udp-forwarded", dropped = false)
    } catch (_: Exception) { failUdp(request, "udp-upstream-failure") }
  }

  private fun receiveUdp() {
    val buffer = ByteBuffer.allocate(65535)
    try {
      while (!destroyed && selector.isOpen) {
        repeat(128) {
          val registration = registrations.poll() ?: return@repeat
          val (channel, request) = registration
          if (!channel.isOpen) return@repeat
          try { channel.register(selector, SelectionKey.OP_READ, request) } catch (_: Exception) {
            control.post { if (pending[request.key] === request) failUdp(request, "udp-registration-failure") }
          }
        }
        selector.select()
        val iterator = selector.selectedKeys().iterator()
        while (iterator.hasNext()) {
          val key = iterator.next()
          iterator.remove()
          if (!key.isValid) continue
          val request = key.attachment() as UdpQuery
          try {
            buffer.clear()
            val size = (key.channel() as DatagramChannel).read(buffer)
            if (size <= 0) continue
            val bytes = buffer.array().copyOf(size)
            if (!deliver {
              if (pending[request.key] === request && tunnel === request.owner) {
                when (val response = DnsMessage.relay(request.query, bytes, request.packet.family, ttlCapSeconds = TTL_CAP_SECONDS)) {
                  DnsMessage.RelayResult.Discard -> count("udp-mismatch")
                  is DnsMessage.RelayResult.Answered -> { count("udp-answered", dropped = false); udpAnswer(request.packet, response.bytes); removeUdp(request) }
                  is DnsMessage.RelayResult.Servfail -> { udpAnswer(request.packet, response.bytes); removeUdp(request) }
                }
              }
            }) {
              control.post { if (!destroyed) { count("udp-delivery-timeout"); shutdown() } }
              return
            }
          } catch (_: Exception) {
            deliver { if (pending[request.key] === request) failUdp(request, "udp-read-failure") }
          }
        }
      }
    } catch (_: Exception) { if (!destroyed) control.post { count("udp-selector-failure"); shutdown() } }
  }

  private fun failUdp(request: UdpQuery, reason: String) {
    count(reason)
    if (pending[request.key] !== request) return
    udpAnswer(request.packet, DnsMessage.response(request.query, DnsMessage.Answer.SERVFAIL, request.packet.family))
    removeUdp(request)
  }
  private fun dropUdp(request: UdpQuery, reason: String) { count(reason); removeUdp(request) }
  private fun removeUdp(request: UdpQuery) {
    pending.remove(request.key)
    pendingBudget.remove(request.key)
    request.timeout?.let { control.removeCallbacks(it) }
    runCatching { request.channel?.close() }
    selector.wakeup()
  }

  private fun selectUnderlay() {
    val network = if (Build.VERSION.SDK_INT >= 31) bestPhysical else physicalNetworks.keys.maxByOrNull { candidate ->
      val capabilities = physicalNetworks[candidate]?.first
      (if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 4 else 0) +
        (if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) 2 else 0) +
        (if (candidate == physicalDefault) 1 else 0)
    }
    updateNetwork(network, physicalNetworks[network]?.second)
  }

  private fun updateNetwork(network: Network?, properties: LinkProperties?) {
    if (destroyed) return
    val next = if (network == null) null else Underlay(network, properties?.dnsServers?.toList() ?: emptyList())
    val changed = next != underlay
    underlay = next
    if (changed) runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
    if (properties != null || network == null) synchronized(networkMonitor) { networkRevision++; networkMonitor.notifyAll() }
    if (changed) Log.i(TAG, "web network id=$network dns=${underlay?.servers?.joinToString { it.hostAddress ?: "?" }}")
    if (properties != null || network == null) {
      pending.values.toList().filter { it.retryAfter != null && it.retryAfter!! < networkRevision }.forEach {
        it.retryAfter = null
        forwardUdp(it, retry = true)
      }
    }
  }

  private inner class TcpConnection(val flow: Flow, val packet: IpPacket.Packet) {
    val responder = TcpDnsResponder(flow.family, random.nextLong().and(0xffffffffL))
    var lastSegment = packet.transport as IpPacket.Tcp
    var timer: Runnable? = null
    val queue = LinkedBlockingQueue<TcpDnsResponder.Request>(64)
    @Volatile var closed = false
    val upstreamSocket = TcpUpstreamSocket()
    var worker: Thread? = null

    fun request(request: TcpDnsResponder.Request) {
      if (!queue.offer(request)) { count("tcp-upstream-cap"); removeTcp(this, reset = true); return }
      if (worker == null) worker = Thread({ runUpstream() }, "expo-st-web-tcp").apply { isDaemon = true; start() }
    }

    private fun runUpstream() {
      try {
        while (!closed && !destroyed) {
          val request = queue.take()
          try {
            val upstream = connectUpstream()
            val writer = DataOutputStream(upstream.getOutputStream())
            val bytes = request.query.upstream()
            writer.writeShort(bytes.size)
            writer.write(bytes)
            writer.flush()
            val reader = DataInputStream(upstream.getInputStream())
            val deadline = now() + 5000
            while (!closed) {
              upstream.soTimeout = (deadline - now()).coerceAtLeast(1).toInt()
              if (now() >= deadline) throw SocketTimeoutException()
              val length = reader.readUnsignedShort()
              val answer = ByteArray(length)
              var offset = 0
              while (offset < length) {
                if (now() >= deadline) throw SocketTimeoutException()
                upstream.soTimeout = (deadline - now()).coerceAtLeast(1).toInt()
                val read = reader.read(answer, offset, length - offset)
                if (read < 0) throw java.io.EOFException()
                offset += read
              }
              val relayed = when (val response = DnsMessage.relay(request.query, answer, flow.family, tcp = true, ttlCapSeconds = TTL_CAP_SECONDS)) {
                DnsMessage.RelayResult.Discard -> {
                  deliver { count("tcp-mismatch") }
                  continue
                }
                is DnsMessage.RelayResult.Answered -> response.bytes
                is DnsMessage.RelayResult.Servfail -> response.bytes
              }
              deliver { if (connections[flow] === this) output(this, responder.complete(request.key, relayed, now())) }
              break
            }
          } catch (_: Exception) {
            upstreamSocket.disconnect()
            deliver { if (connections[flow] === this) { count("tcp-upstream-failure"); output(this, responder.complete(request.key, null, now())) } }
          }
        }
      } catch (_: InterruptedException) { }
    }

    private fun connectUpstream(): Socket {
      for (attempt in 0..1) {
        if (closed || destroyed) throw java.io.IOException("closed")
        val revision = networkRevision
        val network = underlay?.takeIf { it.servers.isNotEmpty() } ?: throw java.io.IOException("no underlay DNS")
        var bound = false
        try {
          return upstreamSocket.connect(DnsUpstreamRoute(network.network.networkHandle, network.servers.first())) { candidate ->
            if (closed || destroyed) throw java.io.IOException("closed")
            network.network.bindSocket(candidate)
            check(protect(candidate)) { "protect refused" }
            bound = true
            candidate.connect(InetSocketAddress(network.servers.first(), 53), 5000)
            candidate.soTimeout = 5000
          }
        } catch (failure: Exception) {
          if (bound || attempt == 1 || closed || destroyed) throw failure
          deliver { count("tcp-bind-protect-retry") }
          synchronized(networkMonitor) {
            val deadline = now() + 5000
            while (!closed && !destroyed && networkRevision == revision && now() < deadline) networkMonitor.wait((deadline - now()).coerceAtLeast(1))
          }
          if (networkRevision == revision) throw java.io.IOException("no new underlay")
        }
      }
      throw java.io.IOException("bind/protect failed")
    }
  }

  private fun output(connection: TcpConnection, output: TcpDnsResponder.Output) {
    if (connections[connection.flow] !== connection) return
    for (segment in output.segments) write(connection.packet.reply(segment))
    for (request in output.requests) {
      Log.v(TAG, "web TCP query id=${request.query.id} name=${request.query.question.name} type=${request.query.question.type}")
      val local = DnsMessage.localAnswer(request.query, matcher, connection.flow.family)
      if (local != null) output(connection, connection.responder.complete(request.key, local, now())) else connection.request(request)
    }
    connection.timer?.let { control.removeCallbacks(it) }
    if (connection.responder.closed) { removeTcp(connection, reset = false); return }
    connection.responder.nextDeadline()?.let { deadline ->
      val timer = Runnable { if (connections[connection.flow] === connection) output(connection, connection.responder.tick(now())) }
      connection.timer = timer
      control.postDelayed(timer, (deadline - now()).coerceAtLeast(1))
    }
  }

  private fun removeTcp(connection: TcpConnection, reset: Boolean) {
    if (connections.remove(connection.flow) !== connection) return
    connectionBudget.remove(connection.flow)
    if (reset) IpPacket.reset(connection.lastSegment)?.let { write(connection.packet.reply(it)) }
    connection.closed = true
    connection.timer?.let { control.removeCallbacks(it) }
    connection.queue.clear()
    connection.upstreamSocket.close()
    connection.worker?.interrupt()
    synchronized(networkMonitor) { networkMonitor.notifyAll() }
  }

  override fun onRevoke() {
    val revokedAt = System.currentTimeMillis()
    tunnel?.close()
    store.update { document ->
      if (!WebState.ownsService(document, serviceId)) return@update
      val boot = EnforcementGapRecorder(this, store).boot()
      WebState.event(document, WebState.Event.displaced, revokedAt, boot.count, boot.timeMs ?: 0)
    }
    Log.i(TAG, "web revoke at=$revokedAt")
    control.post { shutdown() }
  }

  private fun shutdown(startId: Int? = null) {
    val previous = tunnel
    tunnel = null
    previous?.close()
    pending.values.toList().forEach { dropUdp(it, "tunnel-closed") }
    connections.values.toList().forEach { removeTcp(it, reset = false) }
    control.removeCallbacks(heartbeat)
    store.update { document ->
      if (!WebState.stopped(document, serviceId)) return@update
      if (WebState.consent(document).start(WebState.desired(document), false, EnforcementGapRecorder(this, store).boot().count) == WebState.Start.establish) {
        val retries = WebState.record(document).optInt("retries", 0)
        if (retries > 0) WebPreviewController.armWatchdog(this, RestartBackoff.delayMs(retries - 1))
        else WebPreviewController.armWatchdog(this)
      } else WebPreviewController.cancelWatchdog(this)
    }
    if (!destroyed) {
      if (promoted) { stopForeground(STOP_FOREGROUND_REMOVE); promoted = false }
      if (startId == null) stopSelf() else stopSelf(startId)
    }
    Log.i(TAG, "web stopped counters=$counters")
  }

  override fun onDestroy() {
    destroyed = true
    tunnel?.close()
    if (promoted) { stopForeground(STOP_FOREGROUND_REMOVE); promoted = false }
    if (callbackRegistered) runCatching { manager.unregisterNetworkCallback(callback) }
    if (physicalCallbackRegistered) runCatching { manager.unregisterNetworkCallback(physicalCallback) }
    control.post {
      shutdown()
      runCatching { selector.close() }
      registrations.clear()
      thread.quitSafely()
    }
    synchronized(networkMonitor) { networkMonitor.notifyAll() }
    super.onDestroy()
  }

  private fun promote(): Boolean {
    if (promoted) return true
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("expo_st_engine", getString(R.string.expo_st_notice_channel), NotificationManager.IMPORTANCE_LOW))
      }
      val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "expo_st_engine")
        else @Suppress("DEPRECATION") Notification.Builder(this)
      val notice = builder.setSmallIcon(R.drawable.expo_st_notice)
        .setContentTitle(getString(R.string.expo_st_notice_title)).setContentText("Web filter preview is active").setOngoing(true).build()
      if (Build.VERSION.SDK_INT >= 34) startForeground(0x5357, notice,
        if (BuildConfig.WEB_PREVIEW_FGS_TYPE == "systemExempted") ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      else startForeground(0x5357, notice)
      promoted = true
      true
    } catch (failure: Exception) {
      promotionRetryable = failure is IllegalStateException
      Log.i(TAG, "web refused startForeground ${failure.javaClass.simpleName}")
      false
    }
  }

  private fun count(reason: String, dropped: Boolean = true) {
    val count = (counters[reason] ?: 0) + 1
    counters[reason] = count
    if (count and (count - 1) == 0L) Log.i(TAG, "web ${if (dropped) "drop" else "traffic"} reason=$reason count=$count")
  }
  private fun now(): Long = SystemClock.elapsedRealtime()
  companion object {
    private const val TAG = WebPreviewController.TAG
    // In-place DNS-pair swaps preserve the native answer cache. Bound newly learned
    // answers without replacing the network; older caches and existing connections survive.
    private const val TTL_CAP_SECONDS = 60
  }
}
