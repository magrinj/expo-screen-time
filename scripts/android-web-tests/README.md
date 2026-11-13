# Android web-filter device tests

Emulator fixtures and offline capture readers for the internal
[DNS filter preview](../../docs/development.md#web-filter-preview).
These tools are excluded from the npm package and do not enable the public
web-domain capability. Run device trials on a dedicated emulator with an
explicit serial; keep builds, captures and logs under ignored `.proofs/`.

## Build the fixtures

The builders use an installed JDK 17+ and Android SDK. Defaults are platform
`android-37.0` and build-tools `37.0.0`; override them with `--platform`,
`--build-tools` and `--sdk`. Each build prints its APK/hash and retains source
hashes and a disposable signing key. Preserve the signing identity when
updating an installed fixture without clearing its state.

```sh
python3 scripts/android-web-tests/build-companion.py --output .proofs/web-filter
python3 scripts/android-web-tests/build-companion.py \
  --fixture dns-client --output .proofs/web-filter
python3 scripts/android-web-tests/build-tcpdump.py \
  --ndk <installed-ndk-path> --output .proofs/web-filter/tools
```

The capture builder requires macOS, Python 3.12+, make, flex and bison. It checks
pinned tcpdump/libpcap archive hashes and builds with 16-KiB ELF alignment for
emulators with 16-KiB pages. Building against a newer SDK does not verify older
Android runtime compatibility.

## VPN coexistence and reboot

The companion (`expo.modules.screentime.webtest`) takes the VPN slot but routes
only `192.0.2.2/32`. It provides neither DNS nor traffic forwarding. Do not enable
lockdown; perform traffic measurements after the filter has replaced it.

1. Install both APKs, open `/web-preview`, request VPN and block a domain.
2. Start the companion and accept its consent dialog. Capture the filter's
   denied state and open gap, plus independent system VPN ownership.
3. Read the example's state repeatedly while foregrounding both apps. Reads
   must leave the companion active; an explicit VPN request must reclaim it.
4. Repeat with the companion selected as always-on in Android VPN settings;
   the filter must remain denied. Disable always-on afterwards.
5. For reboot trials, set the companion's boot delay in its UI, select the
   preview boot policy, and explicitly restore the filter as the active VPN.

The companion's Stop button closes its descriptor explicitly: `stopService()`
alone can leave a VPN alive while Android keeps the service bound. Its events
are in `files/events.jsonl` and logcat tag `WebFilterTest`.

```sh
python3 scripts/android-web-tests/capture.py \
  --serial <emulator-serial> --output .proofs/web-filter/checkpoint
python3 scripts/android-web-tests/reboot-trial.py \
  --serial <emulator-serial> --policy whenNoVpn --companion-delay -1 \
  --output .proofs/web-filter/reboot-trial
python3 scripts/android-web-tests/summarize-reboots.py --evidence .proofs/web-filter
```

Use fresh output directories. `capture.py` reads system state, filtered logs
and web state without dumping the install secret or selections. The reboot
runner checks policy/consent/ownership before rebooting and requires the boot
count to increase. It observes receiver delivery plus 30 seconds, bounded at
240 seconds after `sys.boot_completed=1`. Keep receiver delays separate from
time since boot; missing receivers and unmet deadlines stay explicit. Opening
the example during this interval contaminates automatic-start evidence.

When retained, `diagnostics/broadcasts.txt` supplies enqueue/schedule/terminal
durations via `boot_delivery.py`. These are system-delivery observations, not
receiver CPU time or proof of an internal queue cause. Persisted `running=true`
alone does not prove current-boot liveness.

## DNS caches and existing connections

The separate-UID client requires API 29+. It probes native `DnsResolver`, VM
`InetAddress`, DNS over TCP and a persistent echo socket on the default network,
without cache bypass or physical-network binding. Its foreground service keeps
it runnable during app switches; stop it before service endurance measurements.
On API 37, grant its local-network permission before the echo test.

```sh
python3 scripts/android-web-tests/dns-fixture.py --output .proofs/web-filter/server
adb -s <emulator-serial> forward tcp:18767 tcp:8767
```

The host serves DNS on 53, HTTP on 8088 and echo on 8089. Controlled `*.r3.test`
names resolve to `10.0.2.2` with TTL 3600; other A/AAAA names use the host resolver.
Configure the emulator's DNS through Settings, then verify LinkProperties and
an actual query. IPv6 router-advertised DNS can override the visible IPv4 DNS
field. Any trial disabling IPv6 must be labelled IPv4-only and restore it later.

On a rootable emulator, install the built tcpdump and capture before establishing
the filter. Include DNS and fixture traffic:

```sh
adb -s <emulator-serial> shell /data/local/tmp/web-filter-tcpdump \
  -i any -U -s 0 -w /data/local/tmp/web-filter.pcap \
  'port 53 or port 853 or port 8088 or port 8089'
python3 scripts/android-web-tests/cache-trial.py --zone case01.r3.test \
  --output .proofs/web-filter/warm.json warm
python3 scripts/android-web-tests/cache-trial.py --zone case01.r3.test \
  --output .proofs/web-filter/hold.json hold
python3 scripts/android-web-tests/cache-trial.py --zone case01.r3.test \
  --output .proofs/web-filter/arm.json arm
```

Use a fresh suffix and keep an unrelated anchor domain blocked so removing the
trial suffix leaves the tunnel active. Warm the cache, establish the allowed
socket, arm the probe, then block the suffix within the 90-second arm window.
Read `status` until `watch.phase=done`. Prove a cache hit with an initial DNS
packet and no packet for the repeated lookup; client timing alone is insufficient.

For negative-cache tests, warm blocked names through `/native?name=…` and
`/vm?name=…`, arm their suffix, then unblock it. For browser checks, distinguish
existing connections from fresh resolutions using a new SYN/port and a
never-resolved blocked name. A loaded page alone proves neither.

Stop tcpdump with SIGINT before pulling its PCAP and final drop statistics.
Pull client `files/events.jsonl` as well. Device timestamps share a clock;
host/UI timestamps need calibration before subtraction.

```sh
python3 scripts/android-web-tests/summarize-cache.py --pcap <completed.pcap> \
  --events <client-events.jsonl> --output <summary.json>
```

This reader supports microsecond PCAP, Linux SLL2 and unfragmented UDP DNS.
It rejects TCP/53; its HTTP observations are packet-local, not reassembled
streams. Port 853 counts do not establish a bypass. Use a separate capture and
the TCP reader below for TCP probes.

## TCP framing, loss and DNS-pair changes

The client's loopback endpoint is
`/tcp-scenario?mode=<mode>&name=<fresh>.r3.test&type=A`.
Types are A, TXT and DNSKEY. Replies retain raw bytes, IDs, timing and errors;
successful socket calls still require packet evidence.

| Mode | Exercise |
| --- | --- |
| `split` | Prefix and body writes separated by 50 ms; verify real segment boundaries. |
| `pipeline` | Two questions before reading either response; compare IDs and arrival order. |
| `half-close` | Send, shut down output, receive the answer and require peer EOF. |
| `reset` | Reset a connection, then query through an independent connection. |
| `query` | One framed query, including DNSKEY with EDNS DO for a large answer. |
| `udp-retry` | Require a matching truncated UDP answer, then retry it over TCP. |

The optional signed fixture uses Python 3.11+ and no app dependencies:

```sh
python3 -m venv .proofs/web-filter/venv
.proofs/web-filter/venv/bin/python -m pip install -r scripts/android-web-tests/requirements-dnssec.txt
.proofs/web-filter/venv/bin/python scripts/android-web-tests/tcp_dns_zone.py \
  --output .proofs/web-filter/zone
```

Run `dns-fixture.py` with that interpreter and `--signed-zone <signed.zone>`.
The locally trusted `signed.r3.test` zone supplies large DNSKEY/TXT answers;
it has no public delegation. Private signing keys remain in memory. Preserve
one fixture for serving and verification; expired signatures are rejected.
Restore device DNS before stopping or replacing a resolver it still uses.

For a loss trial, start capture, use the rooted emulator's actual AVD name,
and retain the exact tagged DROP rule and its cleanup result:

```sh
.proofs/web-filter/venv/bin/python scripts/android-web-tests/tcp-loss-trial.py \
  --serial <emulator-serial> --avd <avd-name> --family 4 \
  --url 'http://127.0.0.1:18767/tcp-scenario?mode=query&type=DNSKEY&name=signed.r3.test' \
  --signed-zone .proofs/web-filter/zone/signed.zone --output .proofs/web-filter/loss
```

`--family 6` requires an IPv6 DNS path. One DROP counter increment alone does
not prove retransmission: correlate the rule, connection, captured segment and
client bytes. Cleanup failures include the removal command in `trial.json`;
check for a leftover rule after abrupt termination.

For a persistent connection, run `tcp-session-trial.py start`, change the domain
list through the example UI, capture the DNS-pair swap, then run `finish`:

```sh
python3 scripts/android-web-tests/tcp-session-trial.py start \
  --control http://127.0.0.1:18767 --name swap01.r3.test --output .proofs/web-filter/swap
python3 scripts/android-web-tests/tcp-session-trial.py finish \
  --control http://127.0.0.1:18767 --output .proofs/web-filter/swap
```

The runner requires a changed DNS pair on the same VPN network and socket,
then sends ten fresh queries. If abandoning between phases, close the returned
session through `/tcp-session?op=close&id=<sessionId>` and retain the result.

```sh
python3 scripts/android-web-tests/analyze-tcp.py \
  --pcap <completed.pcap> --stats <tcpdump-stats.txt> \
  --interface <captured-interface-index> --output <report.json>
```

Select one actual interface per analysis; combining tunnel/underlay observations
can double-count bytes. The bounded reader reconstructs multiple DNS frames,
segment boundaries, ordering, FIN/RST and ACK evidence. Missing bytes,
contradictory overlaps and unsupported captures are errors. Repeated payload
alone is not proof of retransmission. RDATA semantics, DNSSEC and checksums need
separate validation. Exit zero means decoding succeeded, not that the filter
passed all transport scenarios.

## Host checks and evidence

Run the `test-*.py` files with Python; `test-tcp-probe.py` also needs Java 8+,
and `test-tcp-zone.py` uses the optional signed-fixture environment. These tests
validate the tools against synthetic inputs without operating a device.

The `verify-*.py` scripts replay named historical checkpoints. They are not
generic acceptance gates for new APKs or captures; read each script's input
layout and scope before using it. Keep revision/APK hashes, captures, client
results, system observations and incomplete checks together. Report measured
results in the PR rather than appending a dated report to the repository.

For endurance, isolate the filter from the app enforcement engine, overlay
permission and other foreground services in `:screentime`. Record actual idle
state, boot identity, PID/start time, continuous death/restart logs and sampling
gaps. Sticky restarts or competing triggers make start-path attribution
ambiguous. A final live PID or elapsed host time does not prove uninterrupted
survival in device idle.

Cleanup: disable companion boot/always-on, unblock domains and stop the client
and companion. Restore DNS/DHCP and any IPv6, firewall, idle or power overrides;
verify system state before stopping the resolver fixture. Remove only this
run's forwards and collectors. Keep failed attempts and incomplete cleanup
explicit in the local handoff.
