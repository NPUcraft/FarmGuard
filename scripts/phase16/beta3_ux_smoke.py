#!/usr/bin/env python3
"""Paper smoke for cluster-first top, chunk-load snapshots, and risk flap aggregation."""

from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from common import RESULTS, SERVER, Harness, ensure_results, now_iso  # noqa: E402
from debug_log_smoke import DEBUG_LOG, parse_jsonl, set_debug_enabled, types_of  # noqa: E402
from jsonl_gate import validate_file  # noqa: E402
from pressure import build_hopper_ring, build_observer_clocks  # noqa: E402
from profiler import parse_jfr, run_jfr  # noqa: E402

INDUSTRIAL_SECONDS = int(os.environ.get("BETA3_INDUSTRIAL_SECONDS", "600"))


def log(msg: str) -> None:
    print("[beta3-smoke] " + msg, flush=True)


def spanning_hopper_origin() -> tuple[int, int, int]:
    # Blocks 15/16 span chunk (0,0) and (1,0).
    return 15, 70, 0


def count_display_ranks(text: str) -> int:
    return len(re.findall(r"^#\d+", text, re.MULTILINE))


def snapshot_fields(rows: list[dict]) -> dict:
    snaps = [row for row in rows if row.get("type") == "server_snapshot"]
    if not snaps:
        return {}
    last = snaps[-1]
    return {
        "onlinePlayers": last.get("onlinePlayers"),
        "playersByWorld": last.get("playersByWorld"),
        "chunkLoads": last.get("chunkLoads"),
        "chunkUnloads": last.get("chunkUnloads"),
        "newChunkLoads": last.get("newChunkLoads"),
        "trackedChunksDelta": last.get("trackedChunksDelta"),
        "count": len(snaps),
        "maxChunkLoads": max(int(row.get("chunkLoads") or 0) for row in snaps),
        "maxNewChunkLoads": max(int(row.get("newChunkLoads") or 0) for row in snaps),
    }


def run() -> dict:
    ensure_results()
    out: dict = {"startedAt": now_iso(), "ok": False, "industrialSeconds": INDUSTRIAL_SECONDS}
    h = Harness()
    try:
        h.start_server(reset_plugin_state=True)
        jfr_off = {"ok": False}
        try:
            jfr_off = run_jfr(h, "fg-beta3-debug-off", 8)
        except Exception as exc:  # noqa: BLE001
            jfr_off = {"ok": False, "error": str(exc)}
        out["jfrDebugOff"] = {
            "ok": jfr_off.get("ok"),
            "error": jfr_off.get("error"),
            "file": jfr_off.get("file"),
            "topFarmGuardMethods": (jfr_off.get("topFarmGuardMethods") or [])[:8],
        }

        hx, hy, hz = spanning_hopper_origin()
        ix, iy, iz = 256, 70, 0
        rx, ry, rz = 48, 70, 48
        h.prepare_platform(hx, hy, hz, radius=8)
        h.prepare_platform(ix, iy, iz, radius=8)
        h.prepare_platform(rx, ry, rz, radius=10)
        h.cmd(f"forceload add {hx >> 4} {hz >> 4}")
        h.cmd(f"forceload add {(hx + 1) >> 4} {hz >> 4}")
        h.cmd(f"forceload add {ix >> 4} {iz >> 4}")
        h.cmd(f"forceload add {(ix + 1) >> 4} {iz >> 4}")
        h.cmd(f"forceload add {rx >> 4} {rz >> 4}")
        for zoff in (0, 2, 4, 6):
            build_hopper_ring(h, hx, hy, hz + zoff)
            build_hopper_ring(h, ix, iy, iz + zoff)
        build_observer_clocks(h, rx, ry, rz, pairs=6)
        try:
            h.start_bot("FarmGuardBot01")
            h.tp_bot("FarmGuardBot01", hx + 0.5, hy, hz + 0.5, wait=1.0)
        except Exception as exc:  # noqa: BLE001
            out["botError"] = str(exc)
        time.sleep(25)
        top_zh = h.cmd("fg top")
        inspect_zh = h.cmd(f"fg inspect world {hx >> 4} {hz >> 4}")
        out["topZh"] = top_zh
        out["inspectZh"] = inspect_zh
        out["clusterDedup"] = ("2 个区块" in top_zh or "3 个区块" in top_zh or "4 个区块" in top_zh) and count_display_ranks(top_zh) >= 1
        out["neutralWording"] = "漏斗过多" not in top_zh and "持续负载" not in top_zh
        out["topHasActivity"] = "活跃" in top_zh

        set_debug_enabled(True, 5)
        h.cmd("fg reload")
        for dest in ((800, 70, 800), (1200, 70, -400), (hx + 0.5, hy, hz + 0.5)):
            try:
                h.tp_bot("FarmGuardBot01", dest[0], dest[1], dest[2], wait=1.2)
            except Exception as exc:  # noqa: BLE001
                out["tpError"] = str(exc)
        time.sleep(36)
        rows_mid = parse_jsonl(DEBUG_LOG) if DEBUG_LOG.exists() else []
        out["midTypes"] = sorted(types_of(rows_mid))
        out["rawRiskTransitionsMid"] = sum(1 for row in rows_mid if row.get("type") == "risk_transition")
        out["riskSummariesMid"] = sum(1 for row in rows_mid if row.get("type") == "risk_transition_summary")
        out["snapshotFields"] = snapshot_fields(rows_mid)
        out["chunkLoadsSeen"] = int(out["snapshotFields"].get("maxChunkLoads") or 0) > 0

        jfr_on = {"ok": False}
        try:
            jfr_on = run_jfr(h, "fg-beta3-debug-on", 12)
            methods = [str(item[0]) for item in (jfr_on.get("topFarmGuardMethods") or [])]
            jfr_text = Path(jfr_on["file"]).with_name("fg-beta3-debug-on-execution.txt") if jfr_on.get("file") else None
            server_io = False
            yaml_hot = False
            if jfr_text and jfr_text.exists():
                blob = jfr_text.read_text(encoding="utf-8", errors="replace")
                if 'sampledThread = "Server thread"' in blob:
                    if "java.io.FileOutputStream.write" in blob and "com.npucraft.farmguard.debug" in blob:
                        server_io = True
                    if "LanguageManager" in blob and "YamlConfiguration" in blob and 'sampledThread = "Server thread"' in blob:
                        yaml_hot = True
            jfr_on["mainThreadFileIoHotspot"] = server_io
            jfr_on["yamlReadOnServerThread"] = yaml_hot
            jfr_on["topFarmGuardMethods"] = methods[:12]
        except Exception as exc:  # noqa: BLE001
            jfr_on = {"ok": False, "error": str(exc)}
        out["jfrDebugOn"] = {
            "ok": jfr_on.get("ok"),
            "error": jfr_on.get("error"),
            "file": jfr_on.get("file"),
            "mainThreadFileIoHotspot": jfr_on.get("mainThreadFileIoHotspot"),
            "yamlReadOnServerThread": jfr_on.get("yamlReadOnServerThread"),
            "topFarmGuardMethods": jfr_on.get("topFarmGuardMethods") or [],
        }

        hold_started = time.time()
        samples = []
        while time.time() - hold_started < INDUSTRIAL_SECONDS:
            time.sleep(min(60, max(5, INDUSTRIAL_SECONDS - (time.time() - hold_started))))
            rows = parse_jsonl(DEBUG_LOG) if DEBUG_LOG.exists() else []
            samples.append(
                {
                    "elapsed": int(time.time() - hold_started),
                    "lines": len(rows),
                    "risk_transition": sum(1 for row in rows if row.get("type") == "risk_transition"),
                    "risk_transition_summary": sum(1 for row in rows if row.get("type") == "risk_transition_summary"),
                    "bytes": DEBUG_LOG.stat().st_size if DEBUG_LOG.exists() else 0,
                }
            )
            log("industrial t+" + str(samples[-1]["elapsed"]) + "s lines=" + str(samples[-1]["lines"]))
        out["industrialSamples"] = samples
        rows = parse_jsonl(DEBUG_LOG) if DEBUG_LOG.exists() else []
        raw = sum(1 for row in rows if row.get("type") == "risk_transition")
        summaries = sum(1 for row in rows if row.get("type") == "risk_transition_summary")
        summary_transitions = 0
        high_raw = 0
        for row in rows:
            if row.get("type") == "risk_transition_summary":
                summary_transitions += int(row.get("transitionCount") or 0)
            if row.get("type") == "risk_transition" and row.get("to") in {"HIGH", "CRITICAL"}:
                high_raw += 1
            if row.get("type") == "risk_transition" and row.get("from") in {"HIGH", "CRITICAL"}:
                high_raw += 1
        out["rawRiskTransitions"] = raw
        out["riskSummaries"] = summaries
        out["summarizedTransitionCount"] = summary_transitions
        out["highCriticalRaw"] = high_raw
        out["logBytes"] = DEBUG_LOG.stat().st_size if DEBUG_LOG.exists() else 0
        flap_ok = summaries > 0 or summary_transitions > 0 or raw < 80
        if summaries > 0:
            flap_ok = raw < max(40, summary_transitions)
        out["flapAggregationOk"] = bool(flap_ok)

        switched = h.cmd("fg language en_US")
        top_en = h.cmd("fg top")
        inspect_en = h.cmd(f"fg inspect world {hx >> 4} {hz >> 4}")
        out["switched"] = switched
        out["topEn"] = top_en
        out["inspectEn"] = inspect_en
        out["englishTop"] = "Hotspots" in top_en and "Activity" in top_en
        out["englishInspect"] = "Chunk Inspection" in inspect_en

        jsonl = validate_file(DEBUG_LOG) if DEBUG_LOG.exists() else {"ok": False, "exists": False}
        out["jsonl"] = {k: jsonl.get(k) for k in ("ok", "lines", "objects", "invalid", "types", "schema") if k in jsonl or True}
        out["jsonl"]["ok"] = jsonl.get("ok")
        out["jsonl"]["lines"] = jsonl.get("lines")
        out["jsonl"]["objects"] = jsonl.get("objects")
        out["jsonl"]["invalid"] = jsonl.get("invalid")
        out["jsonl"]["types"] = jsonl.get("types")
        out["jsonl"]["schema"] = jsonl.get("schema")
        schema_ok = jsonl.get("ok") is True and jsonl.get("schema") == 1
        machine_ok = "server_snapshot" in (jsonl.get("types") or {})
        out["ok"] = bool(
            out.get("neutralWording")
            and out.get("topHasActivity")
            and out.get("englishTop")
            and out.get("englishInspect")
            and out.get("chunkLoadsSeen")
            and out.get("flapAggregationOk")
            and schema_ok
            and machine_ok
            and not (out.get("jfrDebugOn") or {}).get("mainThreadFileIoHotspot")
        )
        out["clusterDedupNoted"] = out.get("clusterDedup")
        out["schemaOk"] = schema_ok
    finally:
        try:
            h.stop_server()
        except Exception:
            pass
        out["endedAt"] = now_iso()
        path = RESULTS / "beta3-ux-smoke.json"
        path.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=list), encoding="utf-8")
        log("wrote " + str(path) + " ok=" + str(out.get("ok")))
    return out


if __name__ == "__main__":
    result = run()
    sys.exit(0 if result.get("ok") else 1)
