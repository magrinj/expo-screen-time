package expo.modules.screentime.dnsclient;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Emulator-only, separate-UID client. No VPN, network binding or cache bypass. */
public final class MainActivity extends Activity {
  private final ExecutorService pool = Executors.newCachedThreadPool();
  private final Object eventLock = new Object();
  private volatile boolean alive = true;
  private volatile boolean watching;
  private volatile boolean holding;
  private volatile JSONObject status = new JSONObject();
  private ServerSocket control;
  private Socket echo;
  private volatile TcpDnsSession tcpSession;
  private TextView text;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    startForegroundService(new android.content.Intent(this, ProbeService.class));
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    text = new TextView(this);
    text.setPadding(24, 48, 24, 24);
    text.setText("W-R3 DNS cache probe\nSeparate UID; control on localhost:8767\nWaiting for test runner.");
    setContentView(text);
    if (android.os.Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"}, 1);
    }
    pool.execute(() -> {
      try {
        control = new ServerSocket(8767, 8, InetAddress.getByName("127.0.0.1"));
        event("ready", new JSONObject().put("uid", android.os.Process.myUid()).put("pid", android.os.Process.myPid()));
        while (alive) {
          Socket client = control.accept();
          pool.execute(() -> serve(client));
        }
      } catch (Exception e) { eventError("control", e); }
    });
  }

  private JSONObject network() throws Exception {
    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    Network net = cm.getActiveNetwork();
    LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
    JSONArray dns = new JSONArray();
    if (lp != null) for (InetAddress address : lp.getDnsServers()) dns.put(address.getHostAddress());
    return new JSONObject().put("network", net == null ? JSONObject.NULL : net.toString())
      .put("handle", net == null ? 0 : net.getNetworkHandle()).put("dns", dns);
  }

  private void event(String kind, JSONObject fields) {
    try {
      fields.put("event", kind).put("atMs", System.currentTimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime());
      synchronized (eventLock) {
        try (FileOutputStream stream = openFileOutput("events.jsonl", MODE_APPEND)) {
          stream.write((fields.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        }
      }
    } catch (Exception e) { android.util.Log.e("DnsCacheProbe", "event write", e); }
  }

  private void eventError(String kind, Exception error) {
    try { event(kind + "Error", new JSONObject().put("error", error.toString())); }
    catch (Exception ignored) { }
  }

  /** One real TCP/53 query to the default VPN's DNS server, without binding or bypass. */
  private JSONObject tcpLookup(String name) throws Exception {
    if (name == null || !name.endsWith(".r3.test")) throw new IllegalArgumentException("controlled name required");
    long start = System.currentTimeMillis();
    JSONObject net = network();
    String server = net.getJSONArray("dns").getString(0);
    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    DataOutputStream query = new DataOutputStream(packet);
    query.writeShort(0x5354); query.writeShort(0x0100); query.writeShort(1);
    query.writeShort(0); query.writeShort(0); query.writeShort(0);
    for (String label : name.split("\\.")) {
      byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
      if (bytes.length == 0 || bytes.length > 63) throw new IllegalArgumentException("invalid label");
      query.writeByte(bytes.length); query.write(bytes);
    }
    query.writeByte(0); query.writeShort(1); query.writeShort(1);
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getByName(server), 53), 5000);
      socket.setSoTimeout(5000);
      DataOutputStream output = new DataOutputStream(socket.getOutputStream());
      output.writeShort(packet.size()); output.write(packet.toByteArray()); output.flush();
      DataInputStream input = new DataInputStream(socket.getInputStream());
      byte[] answer = new byte[input.readUnsignedShort()]; input.readFully(answer);
      JSONObject result = new JSONObject().put("name", name).put("network", net).put("server", server)
        .put("startedAtMs", start).put("finishedAtMs", System.currentTimeMillis())
        .put("wireBase64", android.util.Base64.encodeToString(answer, android.util.Base64.NO_WRAP));
      event("tcpLookup", result);
      return result;
    }
  }

  private JSONObject lookup(String mode, String name, int type) throws Exception {
    long start = System.currentTimeMillis();
    JSONObject result = new JSONObject().put("mode", mode).put("name", name).put("qtype", type)
      .put("startedAtMs", start).put("network", network());
    try {
      if (mode.equals("vm")) {
        JSONArray addresses = new JSONArray();
        for (InetAddress a : InetAddress.getAllByName(name)) addresses.put(a.getHostAddress());
        result.put("addresses", addresses).put("success", true);
      } else {
        CompletableFuture<JSONObject> pending = new CompletableFuture<>();
        android.os.CancellationSignal cancel = new android.os.CancellationSignal();
        DnsResolver.getInstance().query(null, name, type, DnsResolver.FLAG_EMPTY, pool, cancel,
          new DnsResolver.Callback<List<InetAddress>>() {
            @Override public void onAnswer(List<InetAddress> answer, int rcode) {
              try {
                JSONArray addresses = new JSONArray();
                for (InetAddress a : answer) addresses.put(a.getHostAddress());
                pending.complete(new JSONObject().put("addresses", addresses).put("rcode", rcode).put("success", rcode == 0 && !answer.isEmpty()));
              } catch (Exception e) { pending.completeExceptionally(e); }
            }
            @Override public void onError(DnsResolver.DnsException e) {
              pending.completeExceptionally(e);
            }
          });
        try {
          JSONObject answer = pending.get(8, TimeUnit.SECONDS);
          for (Iterator<String> it = answer.keys(); it.hasNext();) {
            String key = it.next(); result.put(key, answer.get(key));
          }
        } finally { cancel.cancel(); }
      }
    } catch (Exception error) { result.put("success", false).put("error", error.toString()); }
    result.put("finishedAtMs", System.currentTimeMillis()).put("durationMs", System.currentTimeMillis() - start);
    event("lookup", result);
    return result;
  }

  private void watch(String zone) throws Exception {
    synchronized (this) {
      if (watching) throw new IllegalStateException("watch already active");
      watching = true;
    }
    pool.execute(() -> {
      try {
        JSONObject before = network();
        status = new JSONObject().put("phase", "armed").put("zone", zone).put("before", before);
        event("watchArmed", new JSONObject(status.toString()));
        long deadline = SystemClock.elapsedRealtime() + 90000;
        while (alive && SystemClock.elapsedRealtime() < deadline) {
          JSONObject current = network();
          if (!current.toString().equals(before.toString())) {
            long changed = System.currentTimeMillis();
            event("swapObserved", new JSONObject().put("before", before).put("after", current).put("zone", zone));
            JSONArray samples = new JSONArray();
            for (long offset : new long[]{0, 2500, 5000, 15000, 30000, 45000, 55000, 60000, 65000}) {
              long remaining = changed + offset - System.currentTimeMillis();
              if (remaining > 0) Thread.sleep(remaining);
              if (!alive) break;
              // Separate names prevent the native query from changing the VM name's cache.
              samples.put(lookup("vm", "vm." + zone, 1));
              samples.put(lookup("native", "native." + zone, 1));
            }
            status = new JSONObject().put("phase", "done").put("zone", zone).put("samples", samples);
            event("watchDone", new JSONObject(status.toString()));
            return;
          }
          lookup("vm", "vm." + zone, 1);
          Thread.sleep(100);
        }
        status = new JSONObject().put("phase", "timeout");
        event("watchTimeout", status);
      } catch (Exception e) { eventError("watch", e); }
      finally { watching = false; }
    });
  }

  private synchronized void hold(String name) throws Exception {
    if (holding) throw new IllegalStateException("connection already active");
    echo = new Socket();
    echo.connect(new InetSocketAddress(name, 8089), 5000);
    echo.setSoTimeout(3000);
    holding = true;
    JSONObject connection = new JSONObject().put("localPort", echo.getLocalPort()).put("remote", echo.getRemoteSocketAddress().toString());
    event("holdOpen", connection);
    pool.execute(() -> {
      try {
        BufferedReader input = new BufferedReader(new InputStreamReader(echo.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter output = new PrintWriter(echo.getOutputStream(), true);
        long count = 0;
        while (alive && holding) {
          String ping = Long.toString(++count);
          output.println(ping);
          String pong = input.readLine();
          if (!ping.equals(pong)) throw new IOException("echo mismatch or closed");
          event("holdPing", new JSONObject().put("sequence", count).put("localPort", echo.getLocalPort()));
          Thread.sleep(500);
        }
      } catch (Exception e) { if (holding) eventError("hold", e); }
      finally { holding = false; try { echo.close(); } catch (Exception ignored) { } }
    });
  }

  private static int tcpQueryType(String type) {
    if (type != null && !Arrays.asList("A", "TXT", "DNSKEY").contains(type))
      throw new IllegalArgumentException("A, TXT or DNSKEY required");
    return "TXT".equals(type) ? 16 : "DNSKEY".equals(type) ? 48 : 1;
  }

  private synchronized JSONObject tcpSession(Uri uri) throws Exception {
    if (!alive) throw new IllegalStateException("client is stopping");
    JSONObject net = network();
    String operation = uri.getQueryParameter("op");
    Map<String, Object> values;
    if ("open".equals(operation)) {
      if (tcpSession != null) throw new IllegalStateException("close the existing TCP session first");
      TcpDnsSession opened = new TcpDnsSession(net.getJSONArray("dns").getString(0), 53);
      tcpSession = opened;
      if (!alive) { closeTcpSession(); throw new IllegalStateException("client stopped during connect"); }
      values = opened.state();
    } else {
      if (tcpSession == null) throw new IllegalStateException("no TCP session");
      tcpSession.requireId(uri.getQueryParameter("id"));
      switch (operation == null ? "" : operation) {
        case "query": values = tcpSession.query(uri.getQueryParameter("name"), tcpQueryType(uri.getQueryParameter("type"))); break;
        case "status": values = tcpSession.state(); break;
        case "close": tcpSession.close(); values = tcpSession.state(); tcpSession = null; break;
        default: throw new IllegalArgumentException("unknown TCP session operation");
      }
    }
    JSONObject result = new JSONObject(values).put("operation", operation).put("network", net);
    event("tcpSession", result);
    return result;
  }

  private void closeTcpSession() {
    TcpDnsSession current = tcpSession;
    if (current != null) try { current.close(); } catch (IOException ignored) { }
  }

  private void serve(Socket socket) {
    try (Socket client = socket) {
      client.setSoTimeout(5000);
      BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
      String first = reader.readLine();
      if (first == null) return;
      Uri uri = Uri.parse("http://localhost" + first.split(" ")[1]);
      String line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
      JSONObject result;
      try {
        String name = uri.getQueryParameter("name");
        switch (uri.getPath()) {
          case "/native":
            result = lookup("native", name, "AAAA".equals(uri.getQueryParameter("type")) ? 28 : 1); break;
          case "/vm": result = lookup("vm", name, 1); break;
          case "/tcp": result = tcpLookup(name); break;
          case "/tcp-scenario": {
            JSONObject net = network();
            int qtype = tcpQueryType(uri.getQueryParameter("type"));
            result = new JSONObject(TcpDnsProbe.run(net.getJSONArray("dns").getString(0), 53,
              name, qtype, uri.getQueryParameter("mode"))).put("network", net);
            event("tcpScenario", result);
            break;
          }
          case "/tcp-session": result = tcpSession(uri); break;
          case "/arm": watch(uri.getQueryParameter("zone")); result = new JSONObject().put("armed", true); break;
          case "/hold": hold(name); result = new JSONObject().put("holding", true); break;
          case "/stop-hold": holding = false; if (echo != null) echo.close(); result = new JSONObject().put("holding", false); break;
          case "/status": result = new JSONObject().put("watch", status).put("holding", holding).put("network", network()); break;
          default: throw new IllegalArgumentException("unknown command");
        }
      } catch (Exception e) { result = new JSONObject().put("error", e.toString()); }
      byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
      OutputStream out = client.getOutputStream();
      out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(bytes); out.flush();
    } catch (Exception e) { eventError("request", e); }
  }

  @Override public void onDestroy() {
    stopService(new android.content.Intent(this, ProbeService.class));
    alive = false; holding = false;
    try { if (control != null) control.close(); } catch (Exception ignored) { }
    try { if (echo != null) echo.close(); } catch (Exception ignored) { }
    closeTcpSession();
    pool.shutdownNow();
    super.onDestroy();
  }
}
