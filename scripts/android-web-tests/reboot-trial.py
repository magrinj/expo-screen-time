#!/usr/bin/env python3
"""Run one real W-R6 emulator reboot without opening either app.

Configure consent, always-on and companion boot via the T3-bound device UI first.
The preview's documented debug receiver selects its boot policy separately.
Agent-device 0.21.31 has no reboot command; adb reboot is used only for this
test-runner lifecycle operation, never to automate UI or Android settings.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


FILTER = "expo.modules.screentime.example"
COMPANION = "expo.modules.screentime.webtest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--policy", choices=("never", "whenNoVpn", "always"), required=True)
    parser.add_argument("--companion-delay", type=int, choices=(-1, 0, 5000), required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"))
    args = parser.parse_args()

    def adb(*command, timeout=30):
        return subprocess.run([args.adb, "-s", args.serial, *command], text=True, capture_output=True, check=True, timeout=timeout).stdout.strip()

    def web():
        # The full document contains an install secret: only persist the web record.
        return json.loads(adb("shell", "run-as", FILTER, "cat", "no_backup/expo_screen_time/state.json"))["web"]

    def owners():
        return re.findall(r"ni\{VPN CONNECTED extra: VPN:([^}]+)\}", adb("shell", "dumpsys", "connectivity"))

    def require(condition, message):
        if not condition:
            parser.error(message)

    require(adb("shell", "getprop", "ro.kernel.qemu") == "1", "Only emulators may be rebooted")
    require(not args.output.exists(), "Evidence output already exists")
    before_boot = int(adb("shell", "settings", "get", "global", "boot_count"))
    before = web()
    require(before.get("bootPolicy", "whenNoVpn") == args.policy, "Configure the preview boot policy first")
    require(before["consent"]["status"] == "granted" and before["consent"].get("preparedBootCount") == before_boot,
            "Explicitly request the filter in the current boot first")
    require(before.get("desired") and before.get("running") and owners() == [FILTER], "The filter must be established before reboot")
    require(adb("shell", "settings", "get", "secure", "always_on_vpn_app") in ("null", ""), "Disable always-on in Settings first")
    require(adb("shell", "settings", "get", "secure", "always_on_vpn_lockdown") in ("null", "0", ""), "Disable lockdown first")
    preferences = ET.fromstring(adb("shell", "run-as", COMPANION, "cat", "shared_prefs/test.xml"))
    delay = preferences.find("./int[@name='bootDelay']")
    require(delay is not None and int(delay.get("value")) == args.companion_delay, "Configure the companion boot delay in its UI first")

    args.output.mkdir(parents=True)
    trial = {"serial": args.serial, "policy": args.policy, "companionDelayMs": args.companion_delay,
             "beforeBoot": before_boot, "startedAt": datetime.now(timezone.utc).isoformat(), "samples": []}

    def save():
        (args.output / "trial.json").write_text(json.dumps(trial, indent=2) + "\n")

    def capture(name):
        subprocess.run([sys.executable, str(Path(__file__).with_name("capture.py")), "--serial", args.serial,
                        "--adb", args.adb, "--output", str(args.output / name)], check=True)

    capture("before")
    save()
    print("Rebooting explicitly selected emulator " + args.serial, flush=True)
    started = time.monotonic()
    try:
        adb("reboot")
        adb("wait-for-device", timeout=120)
        deadline = started + 180
        while adb("shell", "getprop", "sys.boot_completed") != "1":
            if time.monotonic() > deadline:
                raise TimeoutError("Android boot did not complete within 180 seconds")
            time.sleep(1)
        trial["bootCompletedObservedAfterSeconds"] = time.monotonic() - started
        trial["afterBoot"] = int(adb("shell", "settings", "get", "global", "boot_count"))
        if trial["afterBoot"] != before_boot + 1:
            raise RuntimeError("Expected exactly one actual reboot")
        print("Boot " + str(trial["afterBoot"]) + " complete; observing without app launch", flush=True)
        observation = time.monotonic()
        receivers_seen = None
        while True:
            elapsed = time.monotonic() - observation
            logs = adb("logcat", "-d", "-v", "epoch", "ExpoScreenTimeWeb:I", "WebFilterTest:I", "*:S")
            if receivers_seen is None and "web boot policy=" + args.policy + " observed=" in logs and '"event":"boot"' in logs:
                receivers_seen = elapsed
                trial["bothBootReceiversObservedAfterSeconds"] = elapsed
                print("Both boot receivers observed; collecting another 30 seconds", flush=True)
            sample = {"secondsAfterBootCompletedObserved": elapsed,
                      "vpnOwners": owners(), "web": web()}
            trial["samples"].append(sample)
            save()
            if receivers_seen is not None and elapsed - receivers_seen >= 30:
                break
            if elapsed >= 240:
                trial["receiverObservationIncomplete"] = True
                break
            time.sleep(1)
        capture("after")
        trial["finishedAt"] = datetime.now(timezone.utc).isoformat()
        print(json.dumps({"output": str(args.output), "boot": trial["afterBoot"],
                          "vpnOwners": sample["vpnOwners"], "consent": sample["web"]["consent"],
                          "openGaps": [g for g in sample["web"].get("gaps", []) if g.get("to") is None]}, indent=2))
    except Exception as failure:
        trial["error"] = str(failure)
        raise
    finally:
        save()


if __name__ == "__main__":
    main()
