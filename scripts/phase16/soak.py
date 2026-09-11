#!/usr/bin/env python3
"""Phase 16D stability soak. PASS only if continuous duration >= 7200s."""

from __future__ import annotations

import json
import re
import subprocess
import time
from pathlib import Path
from typing import Any

from common import RESULTS, SERVER, ZONES, Harness, now_iso
from pressure import (
    build_hopper_ring,
    build_observer_clocks,
    clear_observers,
    start_pressure,
)
from profiler import resolve_java_pid

SOAK_SECONDS = 7380
SAFETY_MSPT = 90.0
RCON_TIMEOUT_ABORT = 8.0
FARMGUARD_DIR = SERVER / "plugins" / "FarmGuard"
LOG = SERVER / "logs" / "latest.log"

SCHEDULE = [
    (0, 900, "monitor_mixed"),
    (900, 1800, "monitor_hopper_redstone"),
    (1800, 2700, "monitor_entities"),
    (2700, 3600, "protect_normal"),
    (3600, 3900, "high_1"),
    (3900, 4500, "recovery_1"),
    (4500, 5400, "protect_mixed"),
    (5400, 5700, "high_2"),
    (5700, 6300, "recovery_2"),
    (6300, 6480, "critical_brief"),
    (6480, 7080, "recovery_3"),
    (7080, 7800, "final_idle"),
]


def log(msg: str) -> None:
    print("[phase16d] " + msg, flush=True)


def dir_size(path: Path) -> int:
    if not path.exists():
        return 0
    total = 0
    for item in path.rglob("*"):
        if item.is_file():
            try:
                total += item.stat().st_size
            except OSError:
                pass
    return total


def file_size(path: Path) -> int:
    try:
        return path.stat().st_size if path.exists() else 0
    except OSError:
        return 0


def heap_info(pid: int | None) -> dict[str, int | None]:
    if not pid:
        return {"heapUsed": None, "heapCommitted": None}
    try:
        out = subprocess.check_output(
            ["jcmd", str(pid), "GC.heap_info"],
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=8,
        )
    except Exception:
        return {"heapUsed": None, "heapCommitted": None}
    used = re.search(r"used\s+(\d+)K", out)
    committed = re.search(r"committed\s+(\d+)K", out) or re.search(r"total\s+(\d+)K", out)
    return {
        "heapUsed": int(used.group(1)) * 1024 if used else None,
        "heapCommitted": int(committed.group(1)) * 1024 if committed else None,
    }


def count_incidents() -> int:
    text = ""
    path = FARMGUARD_DIR / "incidents.yml"
    if path.exists():
        text = path.read_text(encoding="utf-8", errors="replace")
    return len(re.findall(r"(?m)^-\s+id:|^id:", text))


def scan_log(offset: int) -> tuple[int, list[dict[str, Any]]]:
    hits: list[dict[str, Any]] = []
    if not LOG.exists():
        return offset, hits
    data = LOG.read_bytes()
    if offset > len(data):
        offset = 0
    chunk = data[offset:].decode("utf-8", errors="replace")
    for line in chunk.splitlines():
        lower = line.lower()
        if any(
            token in lower
            for token in (
                "exception",
                "watchdog",
                "rejected",
                "illegalstate",
                "concurrentmodification",
            )
        ):
            hits.append({"iso": now_iso(), "line": line[:400]})
        elif "error" in lower and "farmguard" in line:
            hits.append({"iso": now_iso(), "line": line[:400]})
    return len(data), hits


def parse_limits(text: str) -> int:
    plain = text or ""
    found = re.findall(r"EMERGENCY|THROTTLE|WARNING", plain.upper())
    if "当前没有正在限制" in plain or "no active" in plain.lower():
        return 0
    return len(found)


def run_soak(h: Harness, seconds: int = SOAK_SECONDS) -> dict[str, Any]:
    row = h.begin(
        "soak.continuous_2h",
        f"Continuous soak >= 7200s with MONITOR/PROTECT, two HIGH cycles, one brief CRITICAL",
    )
    soak_dir = RESULTS / "soak"
    soak_dir.mkdir(parents=True, exist_ok=True)
    snap_path = soak_dir / "snapshots.jsonl"
    prot_path = RESULTS / "protection-timeline.jsonl"
    corr_path = soak_dir / "correlation.jsonl"
    snap_path.write_text("", encoding="utf-8")
    prot_path.write_text("", encoding="utf-8")
    corr_path.write_text("", encoding="utf-8")

    ax, ay, az = ZONES["farm_a"]
    bx, by, bz = ZONES["farm_b"]
    hx, hy, hz = ZONES["monitor_hopper"]
    rx, ry, rz = ZONES["monitor_redstone"]
    px, py, pz = ZONES["piston"]
    sx, sy, sz = ZONES["spawner"]

    _prepare_world(h, ax, ay, az, hx, hy, hz, rx, ry, rz)
    log("soak clock start; setup excluded from duration")
    start = time.time()
    start_iso = now_iso()
    last_phase = None
    next_sample = 0.0
    consecutive_hot = 0.0
    rcon_timeouts = 0
    safety_aborts: list[dict[str, Any]] = []
    log_hits: list[dict[str, Any]] = []
    a_strong = 0
    log_offset = 0
    last_prot: dict[str, str] = {}
    last_mspt = 0.0
    peak_since = 0.0
    pid = resolve_java_pid(h)
    high_seen = 0
    critical_seen = 0
    recovery_ok = True
    hopper_fail = False
    tracked_trend: list[int] = []

    try:
        while True:
            elapsed = time.time() - start
            if elapsed >= seconds:
                break
            phase = "final_idle"
            for a, b, name in SCHEDULE:
                if a <= elapsed < b:
                    phase = name
                    break
            if phase != last_phase:
                log(f"phase {phase} at t={round(elapsed)}s")
                _enter_phase(h, phase, ax, ay, az, bx, by, bz, hx, hy, hz, rx, ry, rz, px, py, pz, sx, sy, sz)
                last_phase = phase
                peak_since = 0.0
            try:
                status = h.status()
            except Exception as exc:  # noqa: BLE001
                rcon_timeouts += 1
                log("status error " + str(exc))
                if rcon_timeouts >= 4:
                    safety_aborts.append({"t": elapsed, "reason": "RCON timeouts", "error": str(exc)})
                    h.emergency_stop_pressure()
                time.sleep(5)
                continue
            if float(h.last_rcon_latency or 0) > RCON_TIMEOUT_ABORT:
                rcon_timeouts += 1
            else:
                rcon_timeouts = max(0, rcon_timeouts - 1)
            mspt = float(status.get("mspt") or 0)
            peak_since = max(peak_since, mspt)
            last_mspt = mspt
            pressure = str(status.get("pressure") or "").upper()
            if pressure == "HIGH":
                high_seen += 1
            if pressure == "CRITICAL":
                critical_seen += 1
            if mspt >= SAFETY_MSPT:
                consecutive_hot += 5.0
            else:
                consecutive_hot = 0.0
            if consecutive_hot >= 5.0 or rcon_timeouts >= 6:
                reason = "SAFETY_ABORT MSPT>=90" if consecutive_hot >= 5 else "SAFETY_ABORT RCON"
                safety_aborts.append({"t": elapsed, "reason": reason, "mspt": mspt})
                h.emergency_stop_pressure()
                consecutive_hot = 0.0
                rcon_timeouts = 0
                log(reason + f" at t={round(elapsed)} mspt={mspt}")
            if elapsed >= next_sample:
                sample = _snapshot(
                    h, elapsed, phase, status, pid, last_prot, prot_path, corr_path, ax, az, bx, bz
                )
                sample["peakMsptSinceLastSample"] = round(peak_since, 2)
                snap_path.open("a", encoding="utf-8").write(json.dumps(sample, ensure_ascii=False) + "\n")
                tracked_trend.append(int(sample.get("trackedChunks") or 0))
                if str((sample.get("A") or {}).get("correlation") or "").upper() == "STRONG":
                    a_strong += 1
                log(
                    f"t={round(elapsed)}s phase={phase} tps={status.get('tps')} mspt={mspt} "
                    f"pressure={pressure} tracked={status.get('tracked')} limiting={status.get('limiting')} "
                    f"A={(sample.get('A') or {}).get('correlation')} B={(sample.get('B') or {}).get('correlation')}"
                )
                peak_since = mspt
                next_sample = elapsed + 300
                log_offset, new_hits = scan_log(log_offset)
                log_hits.extend(new_hits)
                watchdog = [hit for hit in new_hits if "watchdog" in hit.get("line", "").lower()]
                if watchdog:
                    h.p1.append("Watchdog during soak: " + watchdog[0]["line"][:200])
                    row.finish("FAIL", "watchdog during soak")
                    return _result(False, row, start_iso, time.time() - start, safety_aborts, a_strong)
            if phase.startswith("recovery") or phase == "final_idle":
                if pressure not in {"", "NORMAL"} and elapsed > 120:
                    pass
            _light_tick(h, phase, elapsed, rx, ry, rz, hx, hy, hz)
            time.sleep(5)
        duration = time.time() - start
        _enter_phase(h, "final_idle", ax, ay, az, bx, by, bz, hx, hy, hz, rx, ry, rz, px, py, pz, sx, sy, sz)
        time.sleep(8)
        final = h.status()
        inspect_b = h.inspect_block(bx, bz)
        limiting = int(final.get("limiting") or 0)
        pressure = str(final.get("pressure") or "").upper()
        hopper_check = h.isolated_hopper_conservation(64)
        hopper_fail = not hopper_check.get("ok")
        if a_strong >= 3:
            h.p1.append("Farm A STRONG on >=3 soak snapshots")
        if limiting > 0 and pressure == "NORMAL":
            h.p1.append("active restrictions remain after final idle")
        if duration < 7200:
            row.finish("INCOMPLETE", f"duration {round(duration)}s < 7200")
            soak_pass = False
        elif h.p0 or h.p1:
            row.finish("FAIL", f"duration {round(duration)}s but unresolved P0/P1")
            soak_pass = False
        elif hopper_fail:
            h.p0.append("P0 hopper conservation failed during soak idle check")
            row.finish("FAIL", "hopper conservation failed at soak end isolated=" + str(hopper_check.get("total")))
            soak_pass = False
        else:
            row.finish(
                "PASS",
                f"continuous {round(duration)}s highSamples={high_seen} criticalSamples={critical_seen} "
                f"finalPressure={pressure} limiting={limiting} AstrongSnaps={a_strong}",
            )
            soak_pass = True
        extra = {
            "soakPass": soak_pass,
            "soak": row.actual,
            "soakStart": start_iso,
            "soakEnd": now_iso(),
            "soakDuration": duration,
            "wallClockStart": start_iso,
            "wallClockEnd": now_iso(),
            "durationSeconds": duration,
            "safetyAborts": safety_aborts,
            "logHits": log_hits[-40:],
            "farmAStrongSnapshots": a_strong,
            "highSamples": high_seen,
            "criticalSamples": critical_seen,
            "finalStatus": final,
            "finalB": inspect_b,
            "trackedTrend": tracked_trend[-24:],
            "isolatedHopper": hopper_check,
        }
        row.extra.update(extra)
        h.attach_status(row, inspect_b)
        (soak_dir / "result.json").write_text(json.dumps(extra, indent=2, ensure_ascii=False), encoding="utf-8")
        return extra
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))
        return _result(False, row, start_iso, time.time() - start, safety_aborts, a_strong, error=str(exc))


def _result(ok: bool, row, start_iso: str, duration: float, aborts: list, a_strong: int, error: str | None = None) -> dict[str, Any]:
    return {
        "soakPass": ok,
        "soak": row.actual,
        "soakStart": start_iso,
        "soakEnd": now_iso(),
        "soakDuration": duration,
        "durationSeconds": duration,
        "safetyAborts": aborts,
        "farmAStrongSnapshots": a_strong,
        "error": error,
    }


def _snapshot(
    h: Harness,
    elapsed: float,
    phase: str,
    status: dict[str, Any],
    pid: int | None,
    last_prot: dict[str, str],
    prot_path: Path,
    corr_path: Path,
    ax: int,
    az: int,
    bx: int,
    bz: int,
) -> dict[str, Any]:
    inspect_a = {}
    inspect_b = {}
    try:
        inspect_a = h.inspect_block(ax, az)
        inspect_b = h.inspect_block(bx, bz)
    except Exception:
        pass
    limits_text = ""
    try:
        limits_text = h.cmd("fg limits")
    except Exception:
        pass
    entities = 0
    try:
        region = h.fgtest("region", "world", ax - 8, 64, az - 8, bx + 16, 78, bz + 16)
        entities = int(region.get("entities") or 0)
    except Exception:
        pass
    bot_ok = False
    try:
        bot_ok = "FarmGuardBot01" in h.bots and h.bot("FarmGuardBot01").proc is not None and h.bot("FarmGuardBot01").proc.poll() is None
    except Exception:
        bot_ok = False
    heap = heap_info(pid)
    sample = {
        "timestamp": now_iso(),
        "elapsedSeconds": round(elapsed, 1),
        "phase": phase,
        "tps": status.get("tps"),
        "mspt": status.get("mspt"),
        "avgMspt": status.get("avg_mspt"),
        "pressure": status.get("pressure"),
        "mode": status.get("mode"),
        "trackedChunks": status.get("tracked"),
        "hotspotCount": status.get("active"),
        "highRiskCount": status.get("high_risk"),
        "clusterCount": None,
        "activeProtectionCount": status.get("limiting"),
        "limitsListed": parse_limits(limits_text),
        "incidentCount": count_incidents(),
        "heapUsed": heap.get("heapUsed"),
        "heapCommitted": heap.get("heapCommitted"),
        "testEntityCount": entities,
        "botConnected": bot_ok,
        "farmGuardDirBytes": dir_size(FARMGUARD_DIR),
        "incidentsYmlBytes": file_size(FARMGUARD_DIR / "incidents.yml"),
        "stateYmlBytes": file_size(FARMGUARD_DIR / "state.yml"),
        "A": {
            "activity": inspect_a.get("activity"),
            "risk": inspect_a.get("risk"),
            "correlation": inspect_a.get("correlation"),
            "protection": inspect_a.get("protection"),
        },
        "B": {
            "activity": inspect_b.get("activity"),
            "risk": inspect_b.get("risk"),
            "correlation": inspect_b.get("correlation"),
            "protection": inspect_b.get("protection"),
        },
        "msptAtInspect": status.get("mspt"),
        "pressureAtInspect": status.get("pressure"),
    }
    for key, inspect in (("A", inspect_a), ("B", inspect_b)):
        prot = str(inspect.get("protection") or "NORMAL")
        prev = last_prot.get(key)
        if prev != prot:
            event = {
                "timestamp": now_iso(),
                "elapsedSeconds": round(elapsed, 1),
                "chunk": key,
                "old": prev,
                "new": prot,
                "mspt": status.get("mspt"),
                "pressure": status.get("pressure"),
                "risk": inspect.get("risk"),
                "correlation": inspect.get("correlation"),
            }
            prot_path.open("a", encoding="utf-8").write(json.dumps(event) + "\n")
            last_prot[key] = prot
    corr_path.open("a", encoding="utf-8").write(
        json.dumps(
            {
                "timestamp": now_iso(),
                "elapsedSeconds": round(elapsed, 1),
                "mspt": status.get("mspt"),
                "pressure": status.get("pressure"),
                "A": sample["A"],
                "B": sample["B"],
            }
        )
        + "\n"
    )
    return sample


def _prepare_world(h: Harness, ax, ay, az, hx, hy, hz, rx, ry, rz) -> None:
    if "FarmGuardBot01" not in h.bots:
        h.start_bot("FarmGuardBot01")
    h.cmd("fg mode monitor")
    h.emergency_stop_pressure()
    h.prepare_platform(ax, ay, az)
    h.prepare_platform(hx, hy, hz)
    h.prepare_platform(rx, ry, rz)
    build_hopper_ring(h, ax, ay, az)
    h.tp_bot("FarmGuardBot01", rx + 1.5, ry, rz + 1.5, wait=1.0)
    h.cmd(f"execute in minecraft:overworld run setblock {rx} {ry} {rz} stone_button[face=floor,facing=north]")
    h.cmd(f"execute in minecraft:overworld run setblock {rx + 2} {ry} {rz} lever[face=floor,facing=north]")
    h.cmd(f"execute in minecraft:overworld run setblock {rx + 3} {ry} {rz} oak_door[facing=south,half=lower]")
    h.cmd(f"execute in minecraft:overworld run setblock {rx + 3} {ry + 1} {rz} oak_door[facing=south,half=upper]")


def _enter_phase(h: Harness, phase: str, ax, ay, az, bx, by, bz, hx, hy, hz, rx, ry, rz, px, py, pz, sx, sy, sz) -> None:
    if phase == "monitor_mixed":
        h.emergency_stop_pressure()
        h.cmd("fg mode monitor")
        h.tp_bot("FarmGuardBot01", rx + 1.5, ry, rz + 1.5, wait=0.6)
    elif phase == "monitor_hopper_redstone":
        h.cmd("fg mode monitor")
        build_hopper_ring(h, hx, hy, hz)
        build_observer_clocks(h, rx, ry, rz + 2, pairs=2)
        h.tp_bot("FarmGuardBot01", hx + 2, hy, hz, wait=0.6)
    elif phase == "monitor_entities":
        h.cmd("fg mode monitor")
        h.prepare_platform(sx, sy, sz, dark=True)
        h.tp_bot("FarmGuardBot01", sx + 2, sy, sz + 2, wait=0.8)
        h.cmd(f"execute in minecraft:overworld run setblock {sx} {sy} {sz} spawner")
        for i in range(4):
            h.cmd(f'execute in minecraft:overworld run summon villager {sx + i} {sy} {sz} {{Tags:["FarmGuardTest"]}}')
        h.cmd(f"execute in minecraft:overworld run summon item {sx} {sy} {sz} {{Item:{{id:\"minecraft:cobblestone\",Count:1b}},Tags:[\"FarmGuardTest\"]}}")
    elif phase == "protect_normal":
        h.emergency_stop_pressure()
        h.cmd("fg mode protect")
        build_hopper_ring(h, ax, ay, az)
        h.tp_bot("FarmGuardBot01", ax + 2, ay, az, wait=0.6)
    elif phase in {"high_1", "high_2"}:
        h.cmd("fg mode protect")
        start_pressure(h, 50, 300, 70)
    elif phase.startswith("recovery"):
        h.emergency_stop_pressure()
        h.cmd("fg mode protect")
        clear_observers(h, rx, ry, rz + 2, pairs=2)
        h.kill_test_entities()
    elif phase == "protect_mixed":
        h.emergency_stop_pressure()
        h.cmd("fg mode protect")
        build_hopper_ring(h, hx, hy, hz)
        build_observer_clocks(h, rx, ry, rz + 2, pairs=1)
        h.prepare_platform(px, py, pz)
        h.cmd(f"execute in minecraft:overworld run setblock {px} {py} {pz} sticky_piston[facing=east]")
        h.tp_bot("FarmGuardBot01", hx + 2, hy, hz, wait=0.6)
    elif phase == "critical_brief":
        h.cmd("fg mode protect")
        start_pressure(h, 66, 180, 70)
    elif phase == "final_idle":
        h.emergency_stop_pressure()
        h.cmd("fg mode monitor")
        clear_observers(h, rx, ry, rz + 2, pairs=2)
        h.kill_test_entities()
        h.cmd(f"execute in minecraft:overworld run kill @e[type=minecraft:item,x={ax-20},y=60,z={az-20},dx=80,dy=30,dz=80]")


def _light_tick(h: Harness, phase: str, elapsed: float, rx, ry, rz, hx, hy, hz) -> None:
    if int(elapsed) % 60 > 8:
        return
    try:
        if phase in {"monitor_mixed", "monitor_hopper_redstone", "protect_mixed"}:
            h.bot("FarmGuardBot01").call("activate", x=rx, y=ry, z=rz, timeout=8)
    except Exception:
        try:
            if "FarmGuardBot01" not in h.bots or h.bot("FarmGuardBot01").proc.poll() is not None:
                h.start_bot("FarmGuardBot01")
                h.p2.append("Mineflayer reconnect during soak (test infra)")
        except Exception:
            pass


def _quick_hopper_ok(h: Harness, x: int, y: int, z: int) -> bool:
    try:
        return bool(h.isolated_hopper_conservation(64).get("ok"))
    except Exception:
        return False
