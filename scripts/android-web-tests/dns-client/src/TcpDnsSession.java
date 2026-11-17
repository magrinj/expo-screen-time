package expo.modules.screentime.dnsclient;

import java.io.*;
import java.net.Socket;
import java.util.*;

/** One connection across externally observed DNS-pair changes. Never reconnects after failure. */
public final class TcpDnsSession implements Closeable {
  private final Socket socket;
  private final String id = UUID.randomUUID().toString();
  private final String server;
  private final int port;
  private final long origin = System.nanoTime();
  private int sequence;

  public TcpDnsSession(String server, int port) throws IOException {
    this.server = server;
    this.port = port;
    socket = TcpDnsProbe.connect(server, port);
  }

  public synchronized Map<String, Object> state() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sessionId", id); result.put("server", server); result.put("port", port);
    result.put("localPort", socket.getLocalPort()); result.put("closed", socket.isClosed());
    result.put("sequence", sequence); result.put("packetCaptureRequired", true);
    return result;
  }

  public synchronized void requireId(String requested) {
    if (!id.equals(requested)) throw new IllegalArgumentException("TCP session ID mismatch");
  }

  public synchronized Map<String, Object> query(String name, int type) throws IOException {
    if (socket.isClosed()) throw new IOException("TCP session is closed; no reconnect");
    if (sequence >= 256) throw new IOException("TCP session query limit reached");
    int queryId = 0x6000 + sequence + 1;
    byte[] packet = TcpDnsProbe.query(name, type, queryId);
    sequence++;
    List<Map<String, Object>> writes = new ArrayList<>();
    try {
      TcpDnsProbe.write(socket.getOutputStream(), TcpDnsProbe.frame(packet), writes, origin);
      Map<String, Object> reply = TcpDnsProbe.read(new DataInputStream(socket.getInputStream()), origin);
      if (!Integer.valueOf(queryId).equals(reply.get("id"))) throw new IOException("unexpected response ID");
      Map<String, Object> result = state();
      result.put("name", name); result.put("qtype", type); result.put("writes", writes);
      result.put("reply", reply);
      return result;
    } catch (IOException error) {
      try { close(); } catch (IOException closing) { error.addSuppressed(closing); }
      throw error;
    }
  }

  @Override public void close() throws IOException { socket.close(); }
}
