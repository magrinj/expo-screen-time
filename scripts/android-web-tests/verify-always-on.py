#!/usr/bin/env python3
"""Verify W-R6 always-on coexistence from captured evidence, without a device."""
import argparse
import json
from pathlib import Path
import re


def check(condition, message):
    if not condition:
        raise SystemExit("FAIL: " + message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    root = parser.parse_args().evidence

    def state(name):
        return json.loads((root / name / "state.json").read_text())["web"]

    def companion_network(name):
        connections = re.findall(r"network\{(\d+)\}.*?ni\{VPN CONNECTED extra: VPN:([^}]+)\}",
                                 (root / name / "connectivity.txt").read_text())
        check(len(connections) == 1 and connections[0][1] == "expo.modules.screentime.webtest",
              name + ": companion must be the only connected VPN")
        return connections[0][0]

    active = state("always-02-enabled")
    baseline = state("always-01-before")
    check(baseline["running"] and baseline["consent"]["status"] == "granted", "filter was not initially established")
    actions = json.loads((root / "always-ui-actions.json").read_text())
    settings = json.loads(actions[0]["output"])["data"]["nodes"]
    switches = [node for node in settings if node.get("kind") == "switch"]
    check([node.get("checked") for node in switches] == [True, False], "always-on/lockdown UI settings missing")
    networks = [companion_network("always-02-enabled")]
    for i in range(1, 6):
        name = "always-03-foreground-" + str(i) + ("-confirmed" if i == 1 else "")
        web = state(name)
        check(not web["running"] and web["consent"]["status"] == "denied", name + ": filter restarted")
        check(web["consent"] == active["consent"], name + ": consent changed during status read")
        check(any(g["reason"] == "permissionRevoked" and g.get("to") is None for g in web["gaps"]), name + ": missing consent gap")
        prefix = "always foreground " + str(i) + " read"
        check(any(a["step"].startswith(prefix) and a.get("exit_code") == 0 and 'Tapped text="READ PREVIEW STATE"' in a["output"] for a in actions),
              name + ": successful state-read action missing")
        networks.append(companion_network(name))
    refused = state("always-04-request-refused")
    check(not refused["running"] and refused["consent"]["status"] == "denied", "explicit request displaced always-on")
    check(refused["consent"]["dialogResult"] == "cancelled" and refused["consent"]["dialogAt"] > active["consent"]["dialogAt"], "missing new cancellation result")
    check(refused["consent"]["establishAt"] == active["consent"]["establishAt"], "unexpected filter establishment")
    check(any(a["step"] == "always explicit request" and a.get("exit_code") == 0 for a in actions), "explicit request action missing")
    networks.append(companion_network("always-04-request-refused"))
    check(len(set(networks)) == 1, "companion network changed")
    check((root / "always-04-request-refused/always-on.txt").read_text().strip() == "expo.modules.screentime.webtest", "always-on not independently confirmed")
    check((root / "always-04-request-refused/lockdown.txt").read_text().strip() == "0", "lockdown was enabled")
    check((root / "always-05-disabled/always-on.txt").read_text().strip() == "null", "always-on not cleared after trial")
    print("PASS: always-on companion retained the same VPN through five reads and explicit cancelled request; lockdown off; always-on cleared")
    print("Cancellation is proven by the internal record; the preview permission-step JSON exposes denied without a detail field.")
    print("No claim about a transient dialog is inferred from snapshots alone.")


if __name__ == "__main__":
    main()
