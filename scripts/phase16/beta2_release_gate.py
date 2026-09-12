#!/usr/bin/env python3
"""Beta.2 Paper gates: reload cycling, JSONL, short pressure, JFR. Not a 2h soak."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from common import RESULTS, SERVER, ZONES, Harness, ensure_results, gradle_cmd, now_iso  # noqa: E402
from debug_log_smoke import DEBUG_LOG, set_debug_enabled, types_of  # noqa: E402
from jsonl_gate import validate_dir, validate_file  # noqa: E402
from pressure import build_hopper_ring, build_observer_clocks  # noqa: E402
from profiler import resolve_java_pid, run_jfr  # noqa: E402

ACTIVITY_SECONDS = int(os.environ.get("FG_BETA2_ACTIVITY", "120"))
LOGS_DIR = SERVER / "plugins" / "FarmGuard" / "logs"
LATEST = SERVER / "logs" / "latest.log"


def log(msg: str) -> None:
    print("[beta2-gate] " + msg, flush=True)


def status_debug_on(text: str) -> bool:
    return "Debug log:" in text and "ON" in text and "OFF" not in text.split("Debug log:")[-1]


def status_debug_off(text: str) -> bool:
    return "Debug log:" in text and "OFF" in text.split("Debug log:")[-1]


def debug_log_threads(pid: int | None) -> int:
    if not pid:
        return -1
    proc = subprocess.run(
        ["jcmd", str(pid), "Thread.print"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    blob = (proc.stdout or "") + (proc.stderr or "")
    return len(re.findall(r'"FarmGuard-DebugLog"', blob))


def farmguard_stacktraces(path: Path) -> list[str]:
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8", errors="replace")
    hits = []
    for match in re.finditer(r"(?m)^.*Exception.*(?:\n\tat .*)+", text):
        block = match.group(0)
        if "FarmGuard" in block or "farmguard" in block.lower():
            hits.append(block[:800])
    return hits[:8]


def activity(h: Harness) -> None:
    hx, hy, hz = ZONES["monitor_hopper"]
    rx, ry, rz = ZONES["monitor_redstone"]
    h.prepare_platform(hx, hy, hz, radius=4)
    h.prepare_platform(rx, ry, rz, radius=8)
    h.cmd(f"forceload add {hx >> 4} {hz >> 4}")
    h.cmd(f"forceload add {rx >> 4} {rz >> 4}")
    build_hopper_ring(h, hx, hy, hz)
    build_observer_clocks(h, rx, ry, rz, pairs=4)
    try:
        if "FarmGuardBot01" not in h.bots:
            h.start_bot("FarmGuardBot01")
        h.tp_bot("FarmGuardBot01", hx + 0.5, hy, hz + 0.5, wait=0.8)
    except Exception as exc:  # noqa: BLE001
        log("bot skipped: " + str(exc))


def sample_mspt(h: Harness, seconds: int, every: float = 15.0) -> list[float]:
    values: list[float] = []
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            mspt = h.status().get("mspt")
            if mspt is not None:
                values.append(float(mspt))
        except Exception:
            pass
        time.sleep(every)
    return values


def run() -> dict:
    ensure_results()
    import phase15_common as p15

    props = SERVER / "server.properties"
    if props.exists():
        text = props.read_text(encoding="utf-8")
        text = re.sub(r"rcon.port=\d+", f"rcon.port={p15.RCON_PORT}", text)
        props.write_text(text, encoding="utf-8")
    out: dict = {"startedAt": now_iso(), "ok": False, "activitySeconds": ACTIVITY_SECONDS, "rconPort": p15.RCON_PORT}
    log("gradle test jar")
    out["gradleTail"] = gradle_cmd("test", "jar")[-1500:]
    h = Harness()
    try:
        h.start_server(reset_plugin_state=True)
        pid = resolve_java_pid(h)
        out["javaPid"] = pid
        status0 = h.cmd("fg status")
        out["startupStatus"] = status0
        out["startupDebugOff"] = status_debug_off(status0)
        out["startupThreads"] = debug_log_threads(pid)

        cycles = []
        leak = False
        for i in range(5):
            set_debug_enabled(True, 5)
            h.cmd("fg reload")
            time.sleep(1.5)
            on = h.cmd("fg status")
            threads_on = debug_log_threads(pid)
            set_debug_enabled(False, 5)
            h.cmd("fg reload")
            time.sleep(1.5)
            off = h.cmd("fg status")
            threads_off = debug_log_threads(pid)
            row = {
                "i": i,
                "statusOnOk": status_debug_on(on),
                "statusOffOk": status_debug_off(off),
                "threadsOn": threads_on,
                "threadsOff": threads_off,
            }
            cycles.append(row)
            if threads_on not in (1, -1) or threads_off not in (0, -1):
                leak = True
            if not row["statusOnOk"] or not row["statusOffOk"]:
                leak = True
            log("cycle %s onThreads=%s offThreads=%s" % (i, threads_on, threads_off))
        out["reloadCycles"] = cycles
        out["reloadCycleOk"] = not leak and len(cycles) == 5

        activity(h)
        log("debug OFF activity %ss" % ACTIVITY_SECONDS)
        t_off = time.time()
        jfr_off = run_jfr(h, "beta2-debug-off", min(20, ACTIVITY_SECONDS))
        mspt_off = sample_mspt(h, max(5, ACTIVITY_SECONDS - int(time.time() - t_off)))
        out["jfrOff"] = {
            "ok": jfr_off.get("ok"),
            "share": jfr_off.get("farmGuardSampleShare"),
            "samples": jfr_off.get("farmGuardExecutionSamples"),
            "serverSamples": jfr_off.get("serverThreadExecutionSamples"),
            "top": jfr_off.get("topFarmGuardMethods"),
        }
        out["msptOff"] = mspt_off
        out["threadsDuringOff"] = debug_log_threads(pid)

        set_debug_enabled(True, 5)
        h.cmd("fg reload")
        time.sleep(6)
        status_on = h.cmd("fg status")
        out["statusAfterEnable"] = status_on
        out["threadsDuringOn"] = debug_log_threads(pid)
        log("debug ON activity %ss" % ACTIVITY_SECONDS)
        t_on = time.time()
        try:
            out["pressure"] = h.pressure_cmd("start", "55", "20")
        except Exception as exc:  # noqa: BLE001
            out["pressure"] = {"ok": False, "error": str(exc)}
        jfr_on = run_jfr(h, "beta2-debug-on", min(20, ACTIVITY_SECONDS))
        h.cmd("fg mode protect")
        mspt_on = sample_mspt(h, max(5, ACTIVITY_SECONDS - int(time.time() - t_on)))
        try:
            h.pressure_stop("beta2-gate")
        except Exception:
            pass
        out["jfrOn"] = {
            "ok": jfr_on.get("ok"),
            "share": jfr_on.get("farmGuardSampleShare"),
            "samples": jfr_on.get("farmGuardExecutionSamples"),
            "serverSamples": jfr_on.get("serverThreadExecutionSamples"),
            "top": jfr_on.get("topFarmGuardMethods"),
            "mainThreadFileIoHotspot": jfr_on.get("mainThreadFileIoHotspot"),
        }
        out["msptOn"] = mspt_on
        jsonl = validate_dir(LOGS_DIR)
        out["jsonl"] = jsonl
        types = set()
        for report in jsonl.get("files") or []:
            types.update((report.get("types") or {}).keys())
        out["eventTypes"] = sorted(types)
        out["pressureEvents"] = sorted(
            types & {"server_pressure_transition", "incident_start", "server_snapshot", "protection_transition", "correlation_transition"}
        )

        hopper = h.isolated_hopper_conservation(64)
        out["hopper64"] = hopper

        set_debug_enabled(False, 5)
        h.cmd("fg reload")
        time.sleep(1.5)
        out["statusFinalOff"] = h.cmd("fg status")
        out["threadsFinalOff"] = debug_log_threads(pid)
        out["stacktraces"] = farmguard_stacktraces(LATEST)
        out["ok"] = bool(
            out["startupDebugOff"]
            and out["reloadCycleOk"]
            and jsonl.get("ok")
            and hopper.get("ok")
            and status_debug_off(out["statusFinalOff"])
            and out["threadsFinalOff"] in (0, -1)
            and not out["stacktraces"]
            and "server_snapshot" in types
            and "server_pressure_transition" in types
            and "incident_start" in types
        )
    finally:
        try:
            h.stop_server()
        except Exception:
            pass
    out["endedAt"] = now_iso()
    path = RESULTS / "beta2-release-gate.json"
    path.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=list), encoding="utf-8")
    log("wrote " + str(path) + " ok=" + str(out.get("ok")))
    return out


if __name__ == "__main__":
    result = run()
    sys.exit(0 if result.get("ok") else 1)
