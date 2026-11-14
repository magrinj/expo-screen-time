#!/usr/bin/env python3
"""Read W1c evidence from an explicitly selected emulator; performs no UI actions."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"))
    args = parser.parse_args()

    def adb(*command):
        return subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True, timeout=30)

    emulator = adb("shell", "getprop", "ro.kernel.qemu")
    if emulator.returncode or emulator.stdout.strip() != "1":
        parser.error("Only Android emulators may be used with this harness")
    args.output.mkdir(parents=True, exist_ok=False)
    metadata = {"startedAt": datetime.now(timezone.utc).isoformat(), "serial": args.serial, "commands": {}}
    reads = {
        "fingerprint": ["shell", "getprop", "ro.build.fingerprint"],
        "boot-count": ["shell", "settings", "get", "global", "boot_count"],
        "boot-completed": ["shell", "getprop", "sys.boot_completed"],
        "uptime": ["shell", "cat", "/proc/uptime"],
        "always-on": ["shell", "settings", "get", "secure", "always_on_vpn_app"],
        "lockdown": ["shell", "settings", "get", "secure", "always_on_vpn_lockdown"],
        "connectivity": ["shell", "dumpsys", "connectivity"],
        "vpn": ["shell", "dumpsys", "vpn_management"],
        "services": ["shell", "dumpsys", "activity", "services", "expo.modules.screentime"],
        "web-log": ["logcat", "-d", "-v", "epoch", "ExpoScreenTimeWeb:I", "WebFilterTest:I", "AndroidRuntime:E", "*:S"],
        "companion-events": ["shell", "run-as", "expo.modules.screentime.webtest", "cat", "files/events.jsonl"],
    }
    for name, command in reads.items():
        result = adb(*command)
        (args.output / (name + ".txt")).write_text(result.stdout)
        metadata["commands"][name] = {"args": command, "returncode": result.returncode, "stderr": result.stderr}
    result = adb("shell", "run-as", "expo.modules.screentime.example", "cat", "no_backup/expo_screen_time/state.json")
    # Keep only test state: the full document also contains an install secret and selections.
    result.check_returncode()
    document = json.loads(result.stdout)
    (args.output / "state.json").write_text(json.dumps({key: document.get(key) for key in ("web", "engine")}, indent=2) + "\n")
    metadata["finishedAt"] = datetime.now(timezone.utc).isoformat()
    (args.output / "capture.json").write_text(json.dumps(metadata, indent=2) + "\n")
    web = document.get("web", {})
    print(json.dumps({"output": str(args.output), "running": web.get("running"), "consent": web.get("consent"), "openGaps": [g for g in web.get("gaps", []) if g.get("to") is None]}, indent=2))


if __name__ == "__main__":
    main()
