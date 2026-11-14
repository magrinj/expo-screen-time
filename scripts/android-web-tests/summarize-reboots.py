#!/usr/bin/env python3
"""Summarize captured W-R6 trials, including unmet criteria; never declare W1c accepted."""
import argparse
import json
from pathlib import Path
import re

from boot_delivery import read_delivery


FILTER = "expo.modules.screentime.example"
COMPANION = "expo.modules.screentime.webtest"


def summarize(path):
    trial = json.loads((path / "trial.json").read_text())
    checkpoint = path / ("after-broadcast" if (path / "after-broadcast").exists() else "after")
    if not checkpoint.exists():
        return {"trial": path.name, "incomplete": True, "error": trial.get("error")}
    web = json.loads((checkpoint / "state.json").read_text())["web"]
    logs = (checkpoint / "web-log.txt").read_text()
    owners = re.findall(r"ni\{VPN CONNECTED extra: VPN:([^}]+)\}", (checkpoint / "connectivity.txt").read_text())
    events = [json.loads(line) for line in (checkpoint / "companion-events.txt").read_text().splitlines() if line.strip()]
    current_boot = int((checkpoint / "boot-count.txt").read_text())
    before = json.loads((path / "before/state.json").read_text())["web"]
    current_events = [e for e in events if e["at"] > before["consent"]["at"]]
    boots = [e for e in current_events if e["event"] == "boot"]
    gaps = [g for g in web.get("gaps", []) if g.get("to") is None]
    policy = trial["policy"]
    companion_on = trial["companionDelayMs"] >= 0
    boot_rows = [line.strip() for line in logs.splitlines() if "web boot policy=" in line]
    checks = {
        "oneActualReboot": current_boot == trial["beforeBoot"] + 1 == trial.get("afterBoot"),
        "bothReceiversObserved": bool(boots and boot_rows),
        "alwaysOnOff": (checkpoint / "always-on.txt").read_text().strip() == "null",
        "lockdownOff": (checkpoint / "lockdown.txt").read_text().strip() in ("0", "null"),
        "noNewConsentResult": web["consent"]["dialogAt"] == before["consent"]["dialogAt"],
        "companionBootSetting": bool(boots and boots[-1]["detail"] == "delay=" + str(trial["companionDelayMs"])),
    }
    if companion_on:
        checks["companionEstablished"] = any(e["event"] == "establish" and e["detail"] == "descriptor" for e in current_events)
    if policy == "never":
        checks["policyState"] = (owners == ([COMPANION] if companion_on else []) and
                                 web["consent"]["status"] == "granted" and
                                 web["consent"]["preparedBootCount"] == trial["beforeBoot"] and
                                 any(g["reason"] == "unknown" and g.get("kind") == "reboot" for g in gaps) and
                                 not any(g["reason"] == "permissionRevoked" for g in gaps))
    elif not companion_on:
        checks["policyState"] = (owners == [FILTER] and web["consent"]["status"] == "granted" and
                                 web["consent"]["preparedBootCount"] == current_boot and not gaps)
    elif policy == "whenNoVpn":
        skipped = any("vpnSeen=true prepared=false" in line for line in boot_rows)
        checks["competitorLossRecorded"] = (owners == [COMPANION] and
            ((skipped and any(g.get("kind") == "reboot" for g in gaps)) or
             (web["consent"]["status"] == "denied" and any(g["reason"] == "permissionRevoked" for g in gaps))))
    else:
        checks["alwaysCalledPrepare"] = any("prepare=prepared" in line for line in boot_rows)
        checks["filterWonFinalSlot"] = owners == [FILTER]
        checks["companionWasDisplaced"] = any(e["event"] == "revoke" for e in current_events)
    uptime = float((checkpoint / "uptime.txt").read_text().split()[0])
    rows = re.findall(r"^\s*([\d.]+).*ExpoScreenTimeWeb: web established", logs, flags=re.MULTILINE)
    boot_epoch = (boots[-1]["at"] - boots[-1]["elapsed"]) / 1000 if boots else None
    establishment_seconds = round(float(rows[0]) - boot_epoch, 3) if rows and boot_epoch else None
    if policy == "whenNoVpn" and not companion_on:
        checks["restoredWithin30SecondsOfBoot"] = establishment_seconds is not None and establishment_seconds <= 30
    return {"trial": path.name, "boot": current_boot, "policy": policy,
            "companionDelayMs": trial["companionDelayMs"], "vpnOwners": owners,
            "consent": web["consent"]["status"], "openGaps": gaps,
            "storedRunning": web.get("running"), "storedRunBoot": web.get("run", {}).get("bootCount"),
            "companionBootCallbackSeconds": round(boots[-1]["elapsed"] / 1000, 3) if boots else None,
            "checkpointSecondsAfterCompanionBootCallback": round(uptime - boots[-1]["elapsed"] / 1000, 3) if boots else None,
            "firstEstablishmentSecondsFromBoot": establishment_seconds,
            "filterBootLog": boot_rows, "checks": checks,
            "bootBroadcastDelivery": read_delivery(path / "diagnostics/broadcasts.txt"),
            "unmetChecks": [name for name, passed in checks.items() if not passed],
            "runnerError": trial.get("error"), "receiverObservationIncomplete": trial.get("receiverObservationIncomplete", False)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    root = parser.parse_args().evidence
    rows = [summarize(path.parent) for path in sorted(root.glob("reboot-*/trial.json"))]
    print(json.dumps({"trials": rows, "note": "Unmet checks are retained. This is a report, not W1c acceptance."}, indent=2))


if __name__ == "__main__":
    main()
