#!/usr/bin/env python3
"""Local W-R3 DNS (TTL 3600), HTTP keep-alive and TCP echo fixtures.

Only *.r3.test is authoritative. Other A/AAAA questions use the host resolver
so Android's network validation can complete; no outside test zone is used.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import http.server
import json
from pathlib import Path
import socket
import socketserver
import struct
import threading
import time


def question(packet):
    offset, labels = 12, []
    while packet[offset]:
        length = packet[offset]
        if length > 63 or offset + 1 + length >= len(packet):
            raise ValueError('invalid question')
        labels.append(packet[offset + 1:offset + 1 + length].decode('ascii'))
        offset += 1 + length
    offset += 1
    kind, cls = struct.unpack_from('!HH', packet, offset)
    return '.'.join(labels).lower(), kind, cls, offset + 4


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--signed-zone', type=Path, help='optional tcp_dns_zone.py output; requires requirements-dnssec.txt')
    args = p.parse_args()
    signed = None
    if args.signed_zone:
        from tcp_dns_zone import SignedZone
        signed = SignedZone.load(args.signed_zone)
    args.output.mkdir(parents=True, exist_ok=False)
    lock = threading.Lock()
    events = (args.output / 'server.jsonl').open('a', buffering=1)

    def log(kind, **fields):
        with lock:
            events.write(json.dumps(dict(atMs=time.time_ns() // 1000000, event=kind, **fields)) + '\n')

    def answer(packet, peer, transport):
        if peer[0] != '127.0.0.1':
            raise ValueError('Only the host loopback/emulator gateway is allowed')
        name, kind, cls, end = question(packet)
        if signed and signed.contains(name):
            response = signed.answer(packet, transport)
            log('dns', name=name, qtype=kind, controlled=True, signedZone=str(signed.zone.origin),
                transport=transport, peer=list(peer), responseBytes=len(response),
                responseBase64=base64.b64encode(response).decode('ascii'),
                truncated=bool(response[2] & 2), rcode=response[3] & 15)
            return response
        controlled = name.endswith('.r3.test')
        addresses, rcode = [], 0
        if cls == 1 and kind in (1, 28):
            if controlled:
                addresses = ['10.0.2.2'] if kind == 1 else []
            else:
                try:
                    family = socket.AF_INET if kind == 1 else socket.AF_INET6
                    addresses = sorted({row[4][0] for row in socket.getaddrinfo(name, None, family, socket.SOCK_STREAM)})
                except socket.gaierror:
                    rcode = 3
        records = b''
        for address in addresses:
            raw = socket.inet_pton(socket.AF_INET if kind == 1 else socket.AF_INET6, address)
            records += b'\xc0\x0c' + struct.pack('!HHIH', kind, 1, 3600 if controlled else 30, len(raw)) + raw
        flags = 0x8000 | (0x0400 if controlled else 0) | (struct.unpack_from('!H', packet, 2)[0] & 0x0100) | 0x0080 | rcode
        response = packet[:2] + struct.pack('!HHHHH', flags, 1, len(addresses), 0, 0) + packet[12:end] + records
        log('dns', name=name, qtype=kind, controlled=controlled, transport=transport,
            peer=list(peer), ttl=3600 if controlled else 30, rcode=rcode, addresses=addresses)
        return response

    class UDP(socketserver.BaseRequestHandler):
        def handle(self):
            packet, sock = self.request
            try:
                sock.sendto(answer(packet, self.client_address, 'udp'), self.client_address)
            except Exception as e:
                log('dnsError', detail=str(e))

    class DNSTCP(socketserver.StreamRequestHandler):
        def handle(self):
            while length := self.rfile.read(2):
                if len(length) != 2:
                    return
                size = struct.unpack('!H', length)[0]
                packet = self.rfile.read(size)
                if len(packet) != size:
                    return
                response = answer(packet, self.client_address, 'tcp')
                self.wfile.write(struct.pack('!H', len(response)) + response)
                self.wfile.flush()

    class HTTP(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def setup(self):
            super().setup()
            self.connection_id = time.time_ns()

        def do_GET(self):
            log('http', host=self.headers.get('Host'), path=self.path,
                connection=self.connection_id, peer=list(self.client_address))
            if self.path.startswith('/tick'):
                body = json.dumps(dict(connection=self.connection_id, atMs=time.time_ns() // 1000000)).encode()
            else:
                body = b'''<!doctype html><meta name="viewport" content="width=device-width"><title>W-R3 local fixture</title>
<h1>W-R3 local fixture</h1><div id="result">Loaded</div><button onclick="start()">Start live requests</button>
<script>let active=false;async function tick(){try{const r=await fetch('/tick?at='+Date.now(),{cache:'no-store'});document.getElementById('result').textContent='LIVE '+await r.text()}catch(e){document.getElementById('result').textContent='FAILED '+e}if(active)setTimeout(tick,1000)}function start(){if(!active){active=true;tick()}}</script>'''
            self.send_response(200)
            self.send_header('Content-Type', 'text/html' if not self.path.startswith('/tick') else 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    class Echo(socketserver.StreamRequestHandler):
        def handle(self):
            connection = time.time_ns()
            log('echoOpen', connection=connection, peer=list(self.client_address))
            try:
                for line in self.rfile:
                    self.wfile.write(line)
                    self.wfile.flush()
                    log('echo', connection=connection, value=line.decode().strip())
            finally:
                log('echoClose', connection=connection)

    class ThreadingTCP(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    class ThreadingUDP(socketserver.ThreadingUDPServer):
        allow_reuse_address = True
        daemon_threads = True

    servers = [ThreadingUDP(('0.0.0.0', 53), UDP), ThreadingTCP(('0.0.0.0', 53), DNSTCP),
               http.server.ThreadingHTTPServer(('127.0.0.1', 8088), HTTP), ThreadingTCP(('127.0.0.1', 8089), Echo)]
    log('ready', ports=[53, 8088, 8089])
    print('DNS fixture ready on localhost:53; HTTP :8088; echo :8089', flush=True)
    try:
        with ThreadPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(server.serve_forever) for server in servers]
            try:
                for future in futures:
                    future.result()
            except KeyboardInterrupt:
                for server in servers:
                    server.shutdown()
    finally:
        for server in servers:
            server.server_close()
        log('stopped')
        events.close()


if __name__ == '__main__':
    main()
