#!/usr/bin/env python3
"""Final Beta.2 jar smoke: startup, MONITOR, debug reload, hopper 64, clean stop."""

from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from common import RESULTS, SERVER, Harness, ensure_results, now_iso  # noqa: E402
from debug_log_smoke import DEBUG_LOG, set_debug_enabled  # noqa: E402
from jsonl_gate import validate_file  # noqa: E402

LOGS_DIR = SERVER / "plugins" / "FarmGuard" / "logs"
LATEST = SERVER / "logs" / "latest.log"


def log(msg: str) -> None:
    print("[beta2-smoke] " + msg, flush=True)


def run() -> dict:
    ensure_results()
    import phase15_common as p15

    props = SERVER / "server.properties"
    if props.exists():
        text = props.read_text(encoding="utf-8")
        text = re.sub(r"rcon.port=\d+", f"rcon.port={p15.RCON_PORT}", text)
        props.write_text(text, encoding="utf-8")

    out: dict = {
        "startedAt": now_iso(),
        "ok": False,
        "jar": str(p15.PLUGIN_JAR_SRC),
        "rconPort": p15.RCON_PORT,
    }
    if not p15.PLUGIN_JAR_SRC.exists():
        raise RuntimeError("Missing release jar: " + str(p15.PLUGIN_JAR_SRC))

    h = Harness()
    try:
        h.start_server(reset_plugin_state=True)
        latest = LATEST.read_text(encoding="utf-8", errors="replace") if LATEST.exists() else ""
        out["bannerVersion"] = "v1.0.0-beta.2" in latest
        status = h.cmd("fg status")
        out["startupStatus"] = status
        out["monitor"] = "模式:" in status and "MONITOR" in status
        out["debugOff"] = "Debug log:" in status and "OFF" in status.split("Debug log:")[-1]

        set_debug_enabled(True, 5)
        reload_on = h.cmd("fg reload")
        out["reloadOn"] = reload_on
        time.sleep(8)
        status_on = h.cmd("fg status")
        out["statusOn"] = status_on
        out["debugOn"] = "Debug log:" in status_on and "ON" in status_on.split("Debug log:")[-1] and "OFF" not in status_on.split("Debug log:")[-1]

        snapshot_ok = False
        deadline = time.time() + 20
        while time.time() < deadline:
            if DEBUG_LOG.exists():
                report = validate_file(DEBUG_LOG)
                types = report.get("types") or {}
                if report.get("ok") and types.get("server_snapshot", 0) >= 1:
                    out["jsonl"] = report
                    snapshot_ok = True
                    break
            time.sleep(2)
        if not snapshot_ok and DEBUG_LOG.exists():
            out["jsonl"] = validate_file(DEBUG_LOG)
        out["snapshot"] = snapshot_ok

        hopper = h.isolated_hopper_conservation(64)
        out["hopper64"] = hopper

        set_debug_enabled(False, 5)
        h.cmd("fg reload")
        time.sleep(1.5)
        status_off = h.cmd("fg status")
        out["statusFinalOff"] = status_off
        out["debugFinalOff"] = "Debug log:" in status_off and "OFF" in status_off.split("Debug log:")[-1]
        out["ok"] = bool(
            out["bannerVersion"]
            and out["monitor"]
            and out["debugOff"]
            and out["debugOn"]
            and snapshot_ok
            and hopper.get("ok")
            and out["debugFinalOff"]
        )
    finally:
        try:
            h.stop_server()
        except Exception:
            pass
        out["endedAt"] = now_iso()
        path = RESULTS / "beta2-release-smoke.json"
        path.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=list), encoding="utf-8")
        log("wrote " + str(path) + " ok=" + str(out.get("ok")))
    return out


if __name__ == "__main__":
    result = run()
    sys.exit(0 if result.get("ok") else 1)
