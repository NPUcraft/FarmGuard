#!/usr/bin/env python3
"""FarmGuard debug-log integration smoke. Not a 2h soak."""

from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from common import RESULTS, SERVER, ZONES, Harness, ensure_results, gradle_cmd, now_iso  # noqa: E402
from pressure import build_hopper_ring, build_observer_clocks  # noqa: E402
from profiler import parse_jfr, run_jfr  # noqa: E402

DEBUG_LOG = SERVER / "plugins" / "FarmGuard" / "logs" / "debug.log"
CONFIG = SERVER / "plugins" / "FarmGuard" / "config.yml"


def log(msg: str) -> None:
    print("[debug-log] " + msg, flush=True)


def parse_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows


def set_debug_enabled(enabled: bool, interval: int = 5) -> None:
    text = CONFIG.read_text(encoding="utf-8") if CONFIG.exists() else ""
    if re.search(r"^debug-log:\s*$", text, re.MULTILINE):
        text = re.sub(r"(debug-log:\s*\n(?:[ \t].*\n)*)", "", text)
    block = (
        "debug-log:\n"
        f"  enabled: {'true' if enabled else 'false'}\n"
        f"  snapshot-interval-seconds: {interval}\n"
        "  hotspot-top-n: 10\n"
        "  max-file-size-mb: 32\n"
        "  max-files: 5\n"
    )
    text = text.rstrip() + "\n\n" + block
    CONFIG.write_text(text, encoding="utf-8")


def types_of(rows: list[dict]) -> set[str]:
    return {str(row.get("type") or "") for row in rows}


def run() -> dict:
    ensure_results()
    out: dict = {"startedAt": now_iso(), "ok": False}
    log("gradle test jar")
    test_out = gradle_cmd("test", "jar")
    out["gradleTail"] = test_out[-2000:]
    h = Harness()
    try:
        h.start_server(reset_plugin_state=True)
        status = h.cmd("fg status")
        out["statusDisabled"] = status
        existed = DEBUG_LOG.exists()
        size0 = DEBUG_LOG.stat().st_size if existed else 0
        jfr_off = {"ok": False}
        try:
            jfr_off = run_jfr(h, "fg-debug-off", 8)
        except Exception as exc:  # noqa: BLE001
            jfr_off = {"ok": False, "error": str(exc)}
        out["jfrDebugOff"] = jfr_off
        size1 = DEBUG_LOG.stat().st_size if DEBUG_LOG.exists() else 0
        disabled_ok = (not existed and not DEBUG_LOG.exists()) or size1 == size0
        out["disabledNoGrowth"] = disabled_ok
        out["debugLogAfterDisabledWait"] = str(DEBUG_LOG) if DEBUG_LOG.exists() else None
        if DEBUG_LOG.exists():
            DEBUG_LOG.unlink()

        set_debug_enabled(True, 5)
        reload_on = h.cmd("fg reload")
        status_on = h.cmd("fg status")
        out["reloadOn"] = reload_on
        out["statusEnabled"] = status_on
        time.sleep(8)
        rows_after_enable = parse_jsonl(DEBUG_LOG)
        out["enableTypes"] = sorted(types_of(rows_after_enable))
        out["enableCount"] = len(rows_after_enable)
        enable_ok = DEBUG_LOG.exists() and "server_snapshot" in types_of(rows_after_enable)

        hx, hy, hz = ZONES["monitor_hopper"]
        rx, ry, rz = ZONES["monitor_redstone"]
        h.prepare_platform(hx, hy, hz, radius=4)
        h.prepare_platform(rx, ry, rz, radius=8)
        h.cmd(f"forceload add {hx >> 4} {hz >> 4}")
        h.cmd(f"forceload add {rx >> 4} {rz >> 4}")
        build_hopper_ring(h, hx, hy, hz)
        build_observer_clocks(h, rx, ry, rz, pairs=4)
        try:
            h.start_bot("FarmGuardBot01")
            h.tp_bot("FarmGuardBot01", hx + 0.5, hy, hz + 0.5, wait=1.0)
        except Exception as exc:  # noqa: BLE001
            out["botError"] = str(exc)
        time.sleep(8)
        rows_activity = parse_jsonl(DEBUG_LOG)
        out["activityTypes"] = sorted(types_of(rows_activity))
        activity_ok = "server_snapshot" in types_of(rows_activity) and (
            "hotspot_snapshot" in types_of(rows_activity) or "protection_activity" in types_of(rows_activity)
        )

        pressure = {}
        try:
            pressure = h.pressure_cmd("start", "55", "18")
            time.sleep(14)
            h.cmd("fg mode protect")
            time.sleep(8)
            h.pressure_stop("debug-log-smoke")
        except Exception as exc:  # noqa: BLE001
            out["pressureError"] = str(exc)
        out["pressure"] = pressure
        rows_pressure = parse_jsonl(DEBUG_LOG)
        out["pressureTypes"] = sorted(types_of(rows_pressure))
        transition_ok = bool(
            types_of(rows_pressure)
            & {
                "server_pressure_transition",
                "correlation_transition",
                "protection_transition",
                "risk_transition",
                "incident_start",
            }
        )

        jfr = {"ok": False}
        try:
            jfr = run_jfr(h, "fg-debug-on", 12)
            methods = [str(item[0]) for item in (jfr.get("topFarmGuardMethods") or [])]
            jfr_text = Path(jfr["file"]).with_name("fg-debug-on-execution.txt") if jfr.get("file") else None
            server_io = False
            if jfr_text and jfr_text.exists():
                blob = jfr_text.read_text(encoding="utf-8", errors="replace")
                if 'sampledThread = "Server thread"' in blob and "FarmGuard-DebugLog" not in blob:
                    if "java.io.FileOutputStream.write" in blob and "com.npucraft.farmguard.debug" in blob:
                        server_io = True
            jfr["mainThreadFileIoHotspot"] = server_io
            jfr["topFarmGuardMethods"] = methods[:12]
        except Exception as exc:  # noqa: BLE001
            jfr = {"ok": False, "error": str(exc)}
        out["jfrDebugOn"] = jfr

        mspt_on = h.status().get("mspt")
        out["msptDebugOn"] = mspt_on

        set_debug_enabled(False, 5)
        reload_off = h.cmd("fg reload")
        out["reloadOff"] = reload_off
        time.sleep(2)
        size_off = DEBUG_LOG.stat().st_size if DEBUG_LOG.exists() else 0
        time.sleep(7)
        size_off2 = DEBUG_LOG.stat().st_size if DEBUG_LOG.exists() else 0
        out["disableStoppedGrowth"] = size_off2 <= size_off + 16
        out["statusDisabledAgain"] = h.cmd("fg status")
        rows_final = parse_jsonl(DEBUG_LOG)
        out["finalTypes"] = sorted(types_of(rows_final))
        raw_lines = DEBUG_LOG.read_text(encoding="utf-8", errors="replace").splitlines() if DEBUG_LOG.exists() else []
        out["exampleLines"] = raw_lines[:10]
        out["debugLogPath"] = str(DEBUG_LOG)
        ok = (
            disabled_ok
            and enable_ok
            and activity_ok
            and out["disableStoppedGrowth"]
            and "Debug log:" in status_on
            and "ON" in status_on
        )
        out["ok"] = bool(ok)
        out["enableOk"] = enable_ok
        out["activityOk"] = activity_ok
        out["transitionSeen"] = transition_ok
        out["endedAt"] = now_iso()
    finally:
        try:
            h.stop_server()
        except Exception:
            pass
    path = RESULTS / "debug-log-smoke.json"
    path.write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
    log("wrote " + str(path) + " ok=" + str(out.get("ok")))
    return out


if __name__ == "__main__":
    result = run()
    sys.exit(0 if result.get("ok") else 1)
