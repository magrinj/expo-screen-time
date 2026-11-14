#!/usr/bin/env python3
"""Exercise the exact Java probe against loopback sockets; never validates the Android tunnel."""
import base64
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time
import unittest


SOURCE = Path(__file__).resolve().parent / "dns-client/src/TcpDnsProbe.java"
SESSION_SOURCE = SOURCE.with_name("TcpDnsSession.java")
BRIDGE = r'''
import expo.modules.screentime.dnsclient.TcpDnsProbe;
import java.util.*;
public class ProbeHost {
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Map<String, Object> result = TcpDnsProbe.run("127.0.0.1", Integer.parseInt(args[0]),
      args.length > 3 ? args[3] : "trial.r3.test", Integer.parseInt(args[2]), args[1]);
    for (Map<?, ?> reply : (List<Map<?, ?>>) result.get("replies"))
      System.out.println("reply=" + reply.get("wireBase64"));
    List<Map<?, ?>> writes = (List<Map<?, ?>>) result.get("writes");
    if (args[1].equals("split")) {
      if (!Integer.valueOf(2).equals(writes.get(0).get("bytes"))) throw new AssertionError("prefix");
      long gap = (Long) writes.get(1).get("afterMs") - (Long) writes.get(0).get("afterMs");
      if (gap < 50) throw new AssertionError("write pause=" + gap);
    }
    if (args[1].equals("half-close") && !result.containsKey("peerEofAfterMs")) throw new AssertionError("EOF missing");
    if (!Boolean.TRUE.equals(result.get("packetCaptureRequired"))) throw new AssertionError("capture caveat missing");
  }
}
'''


def read_exact(connection, size):
    data = b""
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise EOFError("short frame")
        data += chunk
    return data


def read_query(connection):
    size = struct.unpack("!H", read_exact(connection, 2))[0]
    packet = read_exact(connection, size)
    if packet[2:12] != bytes.fromhex("01000001000000000000"):
        raise AssertionError("wrong DNS query header")
    return packet


def response(query, large=False):
    answer = query[:2] + bytes.fromhex("81800001") + struct.pack("!HHH", int(large), 0, 0) + query[12:]
    if large:
        txt = (b"\xff" + b"x" * 255) * 8
        answer += b"\xc0\x0c" + struct.pack("!HHIH", 16, 1, 3600, len(txt)) + txt
    return answer


def send_frame(connection, answer):
    framed = struct.pack("!H", len(answer)) + answer
    # The reader must handle a fragmented length prefix and body, regardless of socket write boundaries.
    connection.sendall(framed[:1])
    time.sleep(.005)
    connection.sendall(framed[1:7])
    connection.sendall(framed[7:])


class TcpProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="w-r5-probe-")
        cls.build = Path(cls.temp.name)
        bridge = cls.build / "ProbeHost.java"
        bridge.write_text(BRIDGE)
        subprocess.run(["javac", "--release", "8", "-d", str(cls.build), str(SOURCE), str(bridge)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_probe(self, mode, handler, qtype=1, fails=False):
        with socket.socket() as listener, ThreadPoolExecutor(max_workers=1) as pool:
            listener.bind(("127.0.0.1", 0))
            listener.listen(2)
            listener.settimeout(8)
            future = pool.submit(handler, listener)
            result = subprocess.run(["java", "-cp", str(self.build), "ProbeHost",
                                     str(listener.getsockname()[1]), mode, str(qtype)],
                                    capture_output=True, text=True, timeout=25)
            expected = future.result(timeout=8)
            if fails:
                self.assertNotEqual(0, result.returncode)
            else:
                self.assertEqual(0, result.returncode, result.stderr)
                actual = [base64.b64decode(row.split("=", 1)[1]) for row in result.stdout.splitlines()]
                self.assertEqual(expected, actual)
            return result

    def test_split_prefix_and_body(self):
        def serve(listener):
            with listener.accept()[0] as connection:
                connection.settimeout(5)
                answer = response(read_query(connection))
                send_frame(connection, answer)
                return [answer]
        self.run_probe("split", serve)

    def test_pipeline_reads_out_of_order_answers(self):
        def serve(listener):
            with listener.accept()[0] as connection:
                connection.settimeout(5)
                first, second = read_query(connection), read_query(connection)
                self.assertEqual(b"\x57\x01", first[:2])
                self.assertEqual(b"\x57\x02", second[:2])
                answers = [response(second), response(first)]
                for answer in answers:
                    send_frame(connection, answer)
                return answers
        self.run_probe("pipeline", serve)

    def test_half_close_keeps_read_side_until_answer_and_eof(self):
        def serve(listener):
            with listener.accept()[0] as connection:
                connection.settimeout(5)
                query = read_query(connection)
                self.assertEqual(b"", connection.recv(1))
                answer = response(query)
                send_frame(connection, answer)
                return [answer]
        self.run_probe("half-close", serve)

    def test_reset_is_followed_by_success_on_another_connection(self):
        def serve(listener):
            with listener.accept()[0] as connection:
                connection.settimeout(5)
                read_query(connection)
                with self.assertRaises(ConnectionResetError):
                    connection.recv(1)
            with listener.accept()[0] as control:
                control.settimeout(5)
                answer = response(read_query(control))
                send_frame(control, answer)
                return [answer]
        self.run_probe("reset", serve)

    def test_large_txt_is_read_in_full(self):
        def serve(listener):
            with listener.accept()[0] as connection:
                connection.settimeout(5)
                query = read_query(connection)
                self.assertEqual(b"\x00\x10\x00\x01", query[-4:])
                answer = response(query, large=True)
                self.assertGreater(len(answer), 1460)
                send_frame(connection, answer)
                return [answer]
        self.run_probe("query", serve, qtype=16)

    def test_wrong_id_fails_instead_of_counting_an_answer(self):
        def serve(listener):
            with listener.accept()[0] as connection:
                connection.settimeout(5)
                answer = response(read_query(connection))
                send_frame(connection, b"\x12\x34" + answer[2:])
        result = self.run_probe("query", serve, fails=True)
        self.assertIn("unexpected or duplicate response ID", result.stderr)

    def test_truncated_frame_fails(self):
        def serve(listener):
            with listener.accept()[0] as connection:
                connection.settimeout(5)
                answer = response(read_query(connection))
                connection.sendall(struct.pack("!H", len(answer)) + answer[:-1])
        result = self.run_probe("query", serve, fails=True)
        self.assertIn("EOFException", result.stderr)

    def test_duplicate_pipeline_answer_fails(self):
        def serve(listener):
            with listener.accept()[0] as connection:
                connection.settimeout(5)
                answer = response(read_query(connection))
                read_query(connection)
                framed = struct.pack("!H", len(answer)) + answer
                connection.sendall(framed + framed)
        result = self.run_probe("pipeline", serve, fails=True)
        self.assertIn("unexpected or duplicate response ID", result.stderr)


    def udp_retry(self, flags, expected_error=None, wrong_id=False):
        def serve(listener):
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
                udp.bind(listener.getsockname())
                udp.settimeout(5)
                query, peer = udp.recvfrom(65535)
                self.assertEqual(b"\x00\x10\x00\x01", query[-4:])
                answer = query[:2] + struct.pack("!H", flags) + query[4:]
                if wrong_id:
                    answer = b"\x12\x34" + answer[2:]
                udp.sendto(answer, peer)
            if expected_error:
                listener.settimeout(.3)
                with self.assertRaises(socket.timeout):
                    listener.accept()
                return
            with listener.accept()[0] as tcp:
                tcp.settimeout(5)
                retried = read_query(tcp)
                self.assertEqual(query, retried)
                complete = response(retried, large=True)
                send_frame(tcp, complete)
                return [complete]
        result = self.run_probe("udp-retry", serve, qtype=16, fails=expected_error is not None)
        if expected_error:
            self.assertIn(expected_error, result.stderr)

    def test_udp_tc_precedes_tcp_retry_with_identical_question(self):
        self.udp_retry(0x8380)

    def test_udp_without_tc_does_not_open_tcp(self):
        self.udp_retry(0x8180, "must have TC and NOERROR")

    def test_udp_error_does_not_open_tcp(self):
        self.udp_retry(0x8382, "must have TC and NOERROR")

    def test_wrong_udp_id_does_not_open_tcp(self):
        self.udp_retry(0x8380, "wrong UDP response ID", wrong_id=True)

    def test_dnskey_query_requests_signatures_with_edns_do(self):
        def serve(listener):
            with listener.accept()[0] as tcp:
                tcp.settimeout(5)
                size = struct.unpack("!H", read_exact(tcp, 2))[0]
                query = read_exact(tcp, size)
                self.assertEqual(b"\x00\x01", query[10:12])
                self.assertEqual(bytes.fromhex("0000300001"), query[-16:-11])
                self.assertEqual(bytes.fromhex("00002904d0000080000000"), query[-11:])
                answer = query[:2] + bytes.fromhex("81800001000000000000") + query[12:-11]
                send_frame(tcp, answer)
                return [answer]
        self.run_probe("query", serve, qtype=48)


SESSION_BRIDGE = r'''
import expo.modules.screentime.dnsclient.TcpDnsSession;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
public class SessionHost {
  public static void main(String[] args) throws Exception {
    try (TcpDnsSession session = new TcpDnsSession("127.0.0.1", Integer.parseInt(args[0]))) {
      String id = (String) session.state().get("sessionId");
      if (args[1].equals("failure")) {
        try { session.query("before.r3.test", 1); throw new AssertionError("accepted bad reply"); }
        catch (IOException expected) { }
        if (!Boolean.TRUE.equals(session.state().get("closed"))) throw new AssertionError("still open");
        try { session.query("after.r3.test", 1); throw new AssertionError("reconnected"); }
        catch (IOException expected) { }
        System.out.println("closed without reconnect");
        return;
      }
      if (args[1].equals("cancel")) {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
          Future<?> pending = worker.submit(() -> {
            try { session.query("pending.r3.test", 1); throw new AssertionError("unexpected reply"); }
            catch (IOException expected) { }
          });
          if (!"cancel".equals(new BufferedReader(new InputStreamReader(System.in)).readLine()))
            throw new AssertionError("cancel required");
          session.close();
          pending.get(2, TimeUnit.SECONDS);
          System.out.println("cancelled pending read");
        } finally { worker.shutdownNow(); }
        return;
      }
      try { session.requireId("stale-id"); throw new AssertionError("accepted stale ID"); }
      catch (IllegalArgumentException expected) { }
      session.requireId(id);
      Map<String, Object> before = session.query("before.r3.test", 1);
      int port = (Integer) before.get("localPort");
      System.out.println("ready " + port); System.out.flush();
      if (!"resume".equals(new BufferedReader(new InputStreamReader(System.in)).readLine()))
        throw new AssertionError("explicit resume required");
      for (int i = 0; i < 10; i++) {
        Map<String, Object> after = session.query("after" + i + ".r3.test", 1);
        if (!id.equals(after.get("sessionId")) || !Integer.valueOf(port).equals(after.get("localPort")) ||
            !Integer.valueOf(i + 2).equals(after.get("sequence"))) throw new AssertionError("connection changed");
      }
      session.close();
      if (!Boolean.TRUE.equals(session.state().get("closed"))) throw new AssertionError("not closed");
      System.out.println("11 questions on one connection");
    }
  }
}
'''


class TcpSessionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="w-r5-session-")
        cls.build = Path(cls.temp.name)
        bridge = cls.build / "SessionHost.java"
        bridge.write_text(SESSION_BRIDGE)
        subprocess.run(["javac", "--release", "8", "-d", str(cls.build), str(SOURCE),
                        str(SESSION_SOURCE), str(bridge)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_session(self, mode, handler, resume=False):
        with socket.socket() as listener, ThreadPoolExecutor(max_workers=1) as pool:
            listener.bind(("127.0.0.1", 0))
            listener.listen(2)
            listener.settimeout(5)
            future = pool.submit(handler, listener)
            with subprocess.Popen(["java", "-cp", str(self.build), "SessionHost",
                                   str(listener.getsockname()[1]), mode], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as process:
                if resume:
                    self.assertTrue(process.stdout.readline().startswith("ready "))
                if mode == "cancel":
                    future.result(timeout=5)  # The query reached the server before closing it.
                stdout, stderr = process.communicate("resume\n" if resume else "cancel\n", timeout=10)
                self.assertEqual(0, process.returncode, stderr)
            future.result(timeout=5)
            listener.settimeout(.2)
            with self.assertRaises(socket.timeout):
                listener.accept()
            return stdout

    def test_ten_queries_after_explicit_barrier_keep_socket_and_identity(self):
        def serve(listener):
            with listener.accept()[0] as tcp:
                tcp.settimeout(5)
                for i in range(11):
                    query = read_query(tcp)
                    self.assertEqual(0x6001 + i, int.from_bytes(query[:2], 'big'))
                    send_frame(tcp, response(query))
                self.assertEqual(b"", tcp.recv(1))
        self.assertIn("11 questions", self.run_session("hold", serve, resume=True))

    def test_bad_reply_closes_session_without_reconnect(self):
        def serve(listener):
            with listener.accept()[0] as tcp:
                tcp.settimeout(5)
                answer = response(read_query(tcp))
                send_frame(tcp, b"\x12\x34" + answer[2:])
                self.assertEqual(b"", tcp.recv(1))
        self.assertIn("without reconnect", self.run_session("failure", serve))

    def test_peer_eof_closes_session_without_reconnect(self):
        def serve(listener):
            with listener.accept()[0] as tcp:
                tcp.settimeout(5)
                read_query(tcp)
        self.assertIn("without reconnect", self.run_session("failure", serve))

    def test_close_interrupts_pending_read(self):
        held = []
        def serve(listener):
            tcp = listener.accept()[0]
            held.append(tcp)
            tcp.settimeout(5)
            read_query(tcp)
        try:
            self.assertIn("cancelled", self.run_session("cancel", serve))
        finally:
            for tcp in held:
                tcp.close()


if __name__ == "__main__":
    unittest.main()
