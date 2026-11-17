#!/usr/bin/env python3
"""Verify the captured displacement/foreground/reclaim subset of W-R6."""
import argparse
import json
from pathlib import Path
import re


def check(condition, message):
    if not condition:
        raise SystemExit("FAIL: " + message)


def state(checkpoint):
    return json.loads((checkpoint / "state.json").read_text())["web"]


def network(checkpoint, package):
    lines = [line for line in (checkpoint / "connectivity.txt").read_text().splitlines()
             if "ni{VPN CONNECTED extra: VPN:" + package + "}" in line]
    check(len(lines) == 1, str(checkpoint) + ": expected one connected VPN for " + package)
    return int(re.search(r"network\{(\d+)\}", lines[0]).group(1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    root = parser.parse_args().evidence
    before = state(root / "slot-01-filter-active")
    check(before["running"] and before["consent"]["status"] == "granted", "filter was not active initially")
    network(root / "slot-01-filter-active", "expo.modules.screentime.example")
    netids = []
    for name in ["slot-02-competing-active", *["slot-03-foreground-" + str(i) for i in range(1, 6)]]:
        checkpoint = root / name
        web = state(checkpoint)
        check(not web["running"] and web["consent"]["status"] == "denied", name + ": filter must remain denied and stopped")
        check(any(g["reason"] == "permissionRevoked" and g.get("to") is None for g in web["gaps"]), name + ": missing open consent gap")
        netids.append(network(checkpoint, "expo.modules.screentime.webtest"))
        if name.startswith("slot-03"):
            actions = json.loads((checkpoint / "ui-actions.json").read_text())
            check(len(actions) == 3 and all(action["code"] == 0 for action in actions), name + ": failed/missing UI action")
            check(actions[0]["args"] == ["open", "expo.modules.screentime.webtest"], name + ": missing foreground transition")
            check(actions[1]["args"][:3] == ["open", "expo.modules.screentime.example", "expo-screen-time-example://web-preview"], name + ": example not opened")
            check(actions[2]["args"] == ["press", 'text="READ PREVIEW STATE"', "--settle"], name + ": preview not read")
    check(len(set(netids)) == 1, "companion VPN network changed across foreground reads")
    after = state(root / "slot-04-explicit-reclaim")
    check(after["running"] and after["consent"]["status"] == "granted", "explicit reclaim did not start the filter")
    check(after["consent"]["dialogAt"] == before["consent"]["dialogAt"], "unexpected new consent dialog")
    check(after["consent"]["prepareReturnedNullAt"] > before["consent"]["prepareReturnedNullAt"], "prepare did not return null again")
    check(after["consent"]["establishAt"] >= after["consent"]["prepareReturnedNullAt"], "tunnel was not established after prepare")
    check(not any(g.get("to") is None for g in after["gaps"]), "gap remained open after reclaim")
    network(root / "slot-04-explicit-reclaim", "expo.modules.screentime.example")
    print("PASS: displacement, five foreground reads on the same companion VPN, and explicit reclaim")
    print("NOT COVERED: always-on competitor, reboot policies, or later W1c runs")


if __name__ == "__main__":
    main()
