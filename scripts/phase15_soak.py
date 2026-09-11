#!/usr/bin/env python3
"""FarmGuard 2-hour controlled soak. Default duration is 7200 seconds.

Do not claim PASS unless this process actually ran for the requested duration.
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from phase15_common import (  # noqa: E402
    ROOT,
    SERVER,
    PaperServer,
    freeze_record,
    now_iso,
    plugin_files,
    write_json,
)

SOAK_SECONDS = int(os.environ.get("PHASE15_SOAK_SECONDS", "7200"))
SNAPSHOT_SECONDS = int(os.environ.get("PHASE15_SOAK_SNAPSHOT", "900"))
SUMMARY = SERVER / "phase15-soak-summary.json"


def current_phase(elapsed: float) -> str:
    minute = int(elapsed // 60) % 60
    if minute < 20:
        return "MONITOR_LIGHT"
    if minute < 35:
        return "MONITOR_MOBS"
    if minute < 50:
        return "PROTECT_HOPPER"
    return "MONITOR_IDLE"


def apply_phase(server: PaperServer, phase: str) -> str:
    if phase == "MONITOR_LIGHT":
        server.apply_profile("monitor")
        server.cmd("execute in minecraft:overworld run setblock 16 70 16 observer[facing=south]")
        server.cmd("execute in minecraft:overworld run setblock 16 70 17 observer[facing=north]")
        server.place_hopper(8, 8)
        server.fill_hopper(8, 8, stacks=1)
        return "MONITOR light redstone+hopper"
    if phase == "MONITOR_MOBS":
        server.apply_profile("monitor")
        for i in range(8):
            server.cmd(
                f"execute in minecraft:overworld run summon minecraft:villager {40 + i} 70 40 {{Age:0,Tags:[\"fgsoak\"]}}"
            )
        server.cmd(
            'execute in minecraft:overworld run summon minecraft:item 12.5 80 12.5 {Item:{id:"minecraft:dirt",count:8},Tags:["fgsoak"]}'
        )
        return "MONITOR villagers+items"
    if phase == "PROTECT_HOPPER":
        server.apply_profile("safe-protect")
        server.place_hopper(8, 8)
        server.fill_hopper(8, 8, stacks=2)
        return "PROTECT hopper"
    server.apply_profile("monitor")
    server.cmd("execute in minecraft:overworld run kill @e[tag=fgsoak]")
    return "MONITOR idle"


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    os.chdir(ROOT)
    if SOAK_SECONDS < 7200:
        print(
            f"WARNING: PHASE15_SOAK_SECONDS={SOAK_SECONDS} is under 2 hours. "
            "Do not report this run as the Beta.1 soak gate.",
            flush=True,
        )
    payload = {
        "startedAt": now_iso(),
        "requestedSeconds": SOAK_SECONDS,
        "freeze": freeze_record(),
        "snapshots": [],
        "errors": [],
        "complete": False,
        "actualSeconds": 0,
    }
    server = PaperServer()
    try:
        startup = server.start(reset_plugin_state=True)
        payload["startupSeconds"] = startup
        t0 = time.time()
        last_snap = -SNAPSHOT_SECONDS
        last_phase = ""
        while True:
            elapsed = time.time() - t0
            if elapsed >= SOAK_SECONDS:
                break
            phase = current_phase(elapsed)
            if phase != last_phase:
                last_phase = phase
                try:
                    label = apply_phase(server, phase)
                except Exception as exc:  # noqa: BLE001
                    label = f"cycle-error {exc}"
                    payload["errors"].append({"t": round(elapsed, 1), "error": str(exc)})
                print(f"[soak] t={elapsed:.0f}s phase={label}", flush=True)
            if elapsed - last_snap >= SNAPSHOT_SECONDS:
                try:
                    status = server.status()
                    top = server.cmd("fg top 5")
                    limits = server.cmd("fg limits")
                    snap = {
                        "t": round(elapsed, 1),
                        "status": status,
                        "top": top[:400],
                        "limits": limits[:300],
                        "files": plugin_files(),
                    }
                    payload["snapshots"].append(snap)
                    print(
                        f"[snap] t={snap['t']} tps={status.get('tps')} mspt={status.get('mspt')} "
                        f"tracked={status.get('tracked')} limiting={status.get('limiting')} files={snap['files']}",
                        flush=True,
                    )
                except Exception as exc:  # noqa: BLE001
                    payload["errors"].append({"t": round(elapsed, 1), "error": str(exc)})
                    print(f"[snap-error] {exc}", flush=True)
                last_snap = elapsed
            time.sleep(20)
        payload["actualSeconds"] = round(time.time() - t0, 1)
        payload["complete"] = payload["actualSeconds"] >= SOAK_SECONDS - 5
        payload["filesEnd"] = plugin_files()
        payload["finalStatus"] = server.status()
        server.cmd("stop")
    except Exception as exc:  # noqa: BLE001
        payload["errors"].append({"t": 0, "error": str(exc)})
        payload["complete"] = False
        print(f"SOAK FAILED: {exc}", flush=True)
    finally:
        try:
            server.stop()
        except Exception:
            pass
    payload["finishedAt"] = now_iso()
    payload["meetsTwoHourGate"] = bool(payload.get("complete") and payload.get("actualSeconds", 0) >= 7200)
    write_json(SUMMARY, payload)
    print(f"Wrote {SUMMARY} complete={payload.get('complete')} seconds={payload.get('actualSeconds')}", flush=True)
    return 0 if payload.get("meetsTwoHourGate") else 2


if __name__ == "__main__":
    sys.exit(main())
