package expo.modules.screentime.dnsclient;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test traffic only. Write boundaries are intentions: only a packet capture proves TCP segments. */
public final class TcpDnsProbe {
  private static final int TIMEOUT_MS = 15000;
  private static final Set<String> MODES = new HashSet<>(Arrays.asList("split", "pipeline", "half-close", "reset", "query", "udp-retry"));

  public static Map<String, Object> run(String server, int port, String name, int type, String mode) throws Exception {
    if (!MODES.contains(mode)) throw new IllegalArgumentException("unknown TCP scenario");
    if (name == null || !name.endsWith(".r3.test")) throw new IllegalArgumentException("controlled .r3.test name required");
    if (type != 1 && type != 16 && type != 48) throw new IllegalArgumentException("A, TXT or DNSKEY required");
    byte[] first = query(name, type, 0x5701);
    byte[] second = query("second." + name, type, 0x5702);
    Map<String, Object> result = new LinkedHashMap<>();
    List<Map<String, Object>> writes = new ArrayList<>();
    List<Map<String, Object>> replies = new ArrayList<>();
    result.put("mode", mode); result.put("name", name); result.put("qtype", type);
    result.put("server", server); result.put("port", port); result.put("writes", writes); result.put("replies", replies);
    long origin = System.nanoTime();
    if (mode.equals("udp-retry")) {
      if (type != 16) throw new IllegalArgumentException("udp-retry requires TXT");
      try (DatagramSocket udp = new DatagramSocket()) {
        udp.connect(InetAddress.getByName(server), port);
        udp.setSoTimeout(TIMEOUT_MS);
        result.put("udpLocalPort", udp.getLocalPort());
        result.put("udpQueryBase64", Base64.getEncoder().encodeToString(first));
        result.put("udpSentAfterMs", elapsed(origin));
        udp.send(new DatagramPacket(first, first.length));
        DatagramPacket packet = new DatagramPacket(new byte[65535], 65535);
        udp.receive(packet);
        Map<String, Object> reply = response(Arrays.copyOf(packet.getData(), packet.getLength()), origin);
        if (!Integer.valueOf(0x5701).equals(reply.get("id"))) throw new IOException("wrong UDP response ID");
        if (!Boolean.TRUE.equals(reply.get("truncated")) || !Integer.valueOf(0).equals(reply.get("rcode")))
          throw new IOException("UDP answer must have TC and NOERROR before TCP retry");
        result.put("udpReply", reply);
      }
    }
    try (Socket socket = connect(server, port)) {
      result.put("localPort", socket.getLocalPort());
      OutputStream output = socket.getOutputStream();
      byte[] framed = frame(first);
      if (mode.equals("split")) {
        write(output, Arrays.copyOfRange(framed, 0, 2), writes, origin);
        Thread.sleep(50);
        write(output, Arrays.copyOfRange(framed, 2, framed.length), writes, origin);
      } else {
        write(output, framed, writes, origin);
      }
      if (mode.equals("pipeline")) write(output, frame(second), writes, origin);
      if (mode.equals("half-close")) {
        socket.shutdownOutput();
        result.put("halfCloseAfterMs", elapsed(origin));
      }
      if (mode.equals("reset")) {
        // Leave the first response unread; pcap must prove the reset reached the responder.
        Thread.sleep(50);
        socket.setSoLinger(true, 0);
      } else {
        Set<Integer> expected = new HashSet<>(Arrays.asList(0x5701));
        if (mode.equals("pipeline")) expected.add(0x5702);
        DataInputStream input = new DataInputStream(socket.getInputStream());
        while (!expected.isEmpty()) {
          Map<String, Object> reply = read(input, origin);
          if (!expected.remove((Integer) reply.get("id"))) throw new IOException("unexpected or duplicate response ID");
          replies.add(reply);
        }
        if (mode.equals("half-close")) {
          if (input.read() != -1) throw new IOException("extra data instead of peer EOF");
          result.put("peerEofAfterMs", elapsed(origin));
        }
      }
    }
    if (mode.equals("reset")) {
      result.put("resetAfterMs", elapsed(origin));
      // A separate connection must still work after aborting the first one.
      try (Socket control = connect(server, port)) {
        result.put("controlLocalPort", control.getLocalPort());
        write(control.getOutputStream(), frame(second), writes, origin);
        Map<String, Object> reply = read(new DataInputStream(control.getInputStream()), origin);
        if (!Integer.valueOf(0x5702).equals(reply.get("id"))) throw new IOException("wrong control response ID");
        replies.add(reply);
      }
    }
    result.put("durationMs", elapsed(origin));
    result.put("packetCaptureRequired", true);
    return result;
  }

  static Socket connect(String server, int port) throws IOException {
    Socket socket = new Socket();
    try {
      socket.setTcpNoDelay(true);
      socket.setSoTimeout(TIMEOUT_MS);
      socket.connect(new InetSocketAddress(InetAddress.getByName(server), port), 5000);
      return socket;
    } catch (IOException | RuntimeException error) { socket.close(); throw error; }
  }

  static byte[] query(String name, int type, int id) throws IOException {
    if (name == null || !name.endsWith(".r3.test")) throw new IllegalArgumentException("controlled .r3.test name required");
    if (type != 1 && type != 16 && type != 48) throw new IllegalArgumentException("A, TXT or DNSKEY required");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeShort(id); out.writeShort(0x0100); out.writeShort(1);
    out.writeShort(0); out.writeShort(0); out.writeShort(type == 48 ? 1 : 0);
    for (String label : name.split("\\.", -1)) {
      if (label.isEmpty() || label.length() > 63 || !label.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("invalid controlled DNS label");
      byte[] part = label.getBytes(StandardCharsets.US_ASCII);
      out.writeByte(part.length); out.write(part);
    }
    out.writeByte(0); out.writeShort(type); out.writeShort(1);
    if (bytes.size() - 16 > 255) throw new IllegalArgumentException("DNS name too long");
    if (type == 48) {
      // Ask for the DNSKEY RRset's signature; no AD claim or client-side trust-chain validation.
      out.writeByte(0); out.writeShort(41); out.writeShort(1232);
      out.writeInt(0x8000); out.writeShort(0);
    }
    return bytes.toByteArray();
  }

  static byte[] frame(byte[] message) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeShort(message.length); out.write(message);
    return bytes.toByteArray();
  }

  static void write(OutputStream out, byte[] bytes, List<Map<String, Object>> writes, long origin) throws IOException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("afterMs", elapsed(origin)); row.put("bytes", bytes.length);
    row.put("wireBase64", Base64.getEncoder().encodeToString(bytes));
    out.write(bytes); out.flush(); writes.add(row);
  }

  static Map<String, Object> read(DataInputStream input, long origin) throws IOException {
    int size = input.readUnsignedShort();
    if (size < 12) throw new IOException("short DNS response");
    byte[] answer = new byte[size]; input.readFully(answer);
    return response(answer, origin);
  }

  private static Map<String, Object> response(byte[] answer, long origin) throws IOException {
    int size = answer.length;
    if (size < 12) throw new IOException("short DNS response");
    if ((answer[2] & 0x80) == 0) throw new IOException("DNS QR not set");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", (answer[0] & 255) * 256 + (answer[1] & 255));
    row.put("rcode", answer[3] & 15); row.put("truncated", (answer[2] & 2) != 0);
    row.put("bytes", size); row.put("afterMs", elapsed(origin));
    row.put("wireBase64", Base64.getEncoder().encodeToString(answer));
    return row;
  }

  private static long elapsed(long origin) { return (System.nanoTime() - origin) / 1000000; }
}
