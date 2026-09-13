#!/usr/bin/env python3
"""FarmGuard Beta.3 Paper 1.21.8 gates.

Gate A: real cross-chunk hopper cluster, Cluster-first /fg top.
Gate B: real RiskEngine NONE/LOW flap aggregation in debug.log.

Does not change FarmGuard algorithms or production thresholds.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from common import RESULTS, SERVER, Harness, ensure_results, now_iso  # noqa: E402
import phase15_common as p15  # noqa: E402
from debug_log_smoke import DEBUG_LOG, set_debug_enabled  # noqa: E402
from jsonl_gate import validate_dir, validate_file  # noqa: E402
from pressure import build_hopper_ring, build_observer_clocks, clear_observers  # noqa: E402

LOGS_DIR = SERVER / "plugins" / "FarmGuard" / "logs"
CONFIG = SERVER / "plugins" / "FarmGuard" / "config.yml"
FLAP_SECONDS = int(os.environ.get("BETA3_FLAP_SECONDS", "240"))
CLUSTER_WAIT = int(os.environ.get("BETA3_CLUSTER_WAIT", "90"))

# Chunk A (0,0) and Chunk B (1,0) — adjacent, inside start_server forceload -2..8.
CHUNK_A = (0, 0)
CHUNK_B = (1, 0)
# Standalone hotspot in chunk (7,7), still forceloaded, not a neighbor of 0/1.
STANDALONE = (7, 7)
# Risk flap clocks entirely inside chunk (5,0).
FLAP = (5, 0)

Y = 70
ENUM_LEAK = re.compile(
    r"\b(STORAGE|SUSTAINED_LOAD|EXCESSIVE_HOPPERS|POSSIBLE|STRONG|"
    r"REDSTONE_MACHINE|UNKNOWN_AUTOMATION)\b"
)

# 2x2 rings fully inside chunk 0 (blocks 0-15) and chunk 1 (16-31).
RINGS_A = [(2, 0), (2, 3), (2, 6), (2, 9), (6, 0), (6, 3), (10, 0), (10, 3)]
RINGS_B = [(18, 0), (18, 3), (18, 6), (18, 9), (22, 0), (22, 3), (26, 0), (26, 3)]
RINGS_STANDALONE = [(114, 114), (114, 117)]
LAYERS = (0, 2)


def log(msg: str) -> None:
    print("[beta3-gates] " + msg, flush=True)


def patch_rcon_port() -> None:
    props = SERVER / "server.properties"
    if not props.exists():
        return
    text = props.read_text(encoding="utf-8")
    text = re.sub(r"rcon.port=\d+", f"rcon.port={p15.RCON_PORT}", text)
    props.write_text(text, encoding="utf-8")


def parse_inspect_ui(text: str) -> dict:
    plain = p15.strip_color(text or "")
    out: dict = {"raw": plain}
    m = re.search(r"(?:活动评分|Activity Score)\s+([\d.]+)", plain)
    if m:
        out["activity"] = float(m.group(1))
    m = re.search(r"(?:风险评分|Risk Score)\s+([\d.]+)", plain)
    if m:
        out["riskScore"] = float(m.group(1))
    m = re.search(r"(?:风险等级|Risk Level)\s+(\S+)", plain)
    if m:
        out["riskLevel"] = m.group(1)
    m = re.search(r"ID\s+(\S+:\-?\d+,\-?\d+)", plain)
    if m:
        out["clusterId"] = m.group(1)
    m = re.search(r"(?:区块数|Chunks)\s+(\d+)", plain)
    if m:
        out["clusterChunks"] = int(m.group(1))
    out["noCluster"] = "未加入设施集群" in plain or "Not part of an automation cluster" in plain
    return out


def rank_lines(text: str) -> list[str]:
    plain = p15.strip_color(text or "")
    return re.findall(r"^#\d+.*", plain, re.MULTILINE)


def rank_coords(text: str) -> list[tuple[int, int]]:
    coords = []
    for line in rank_lines(text):
        m = re.search(r"\((-?\d+),\s*(-?\d+)\)", line)
        if m:
            coords.append((int(m.group(1)), int(m.group(2))))
    return coords


def fill_ring_items(h: Harness, x: int, y: int, z: int) -> None:
    for ox, oz in ((0, 0), (1, 0), (1, 1), (0, 1)):
        for slot in range(2):
            h.cmd(
                f"item replace block {x + ox} {y} {z + oz} container.{slot} "
                "with minecraft:cobblestone 64"
            )


def place_rings(h: Harness, origins: list[tuple[int, int]], y: int) -> None:
    for x, z in origins:
        for yoff in LAYERS:
            build_hopper_ring(h, x, y + yoff, z)
            fill_ring_items(h, x, y + yoff, z)


def refill_rings(h: Harness, origins: list[tuple[int, int]], y: int) -> None:
    for x, z in origins:
        for yoff in LAYERS:
            fill_ring_items(h, x, y + yoff, z)


def silence_hoppers(h: Harness) -> None:
    h.cmd("execute in minecraft:overworld run fill 0 69 0 31 75 15 air replace hopper")
    h.cmd("execute in minecraft:overworld run fill 110 69 110 120 75 122 air replace hopper")


def clock_rows() -> list[tuple[int, int, int, int]]:
    # (x, y, z, pairs) all inside chunk (5,0): x 80-95, z 0-15.
    return [
        (80, Y, 1, 8),
        (80, Y, 3, 8),
        (80, Y, 5, 8),
        (80, Y + 2, 1, 8),
        (80, Y + 2, 3, 8),
    ]


def clocks_on(h: Harness) -> None:
    for x, y, z, pairs in clock_rows():
        build_observer_clocks(h, x, y, z, pairs)


def clocks_off(h: Harness) -> None:
    h.cmd("execute in minecraft:overworld run fill 80 69 0 95 75 10 air")


def inspect_chunk(h: Harness, cx: int, cz: int) -> dict:
    text = h.cmd(f"fg inspect world {cx} {cz}")
    parsed = parse_inspect_ui(text)
    parsed["command"] = f"fg inspect world {cx} {cz}"
    return parsed


def leaks(text: str) -> list[str]:
    return sorted(set(ENUM_LEAK.findall(p15.strip_color(text or ""))))


def load_jsonl(path: Path) -> tuple[list[dict], int]:
    rows: list[dict] = []
    invalid = 0
    if not path.exists():
        return rows, 0
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            invalid += 1
    return rows, invalid


def filter_chunk(rows: list[dict], cx: int, cz: int) -> list[dict]:
    out = []
    for row in rows:
        try:
            if int(row.get("chunkX")) == cx and int(row.get("chunkZ")) == cz:
                out.append(row)
        except (TypeError, ValueError):
            continue
    return out


def copy_debug_logs(dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    if not LOGS_DIR.exists():
        return
    for path in LOGS_DIR.glob("debug*.log"):
        shutil.copy2(path, dest / path.name)


def run_gate_a(h: Harness, out: dict) -> bool:
    log("Gate A: clear leftover observers, build per-chunk hopper rings")
    clear_observers(h, 0, Y, 0, pairs=16)
    clear_observers(h, 48, Y, 48, pairs=10)
    clocks_off(h)
    h.cmd("forceload add 0 0")
    h.cmd("forceload add 1 0")
    h.cmd("forceload add 5 0")
    h.cmd("forceload add 7 7")
    h.prepare_platform(8, Y, 6, radius=10)
    h.prepare_platform(24, Y, 6, radius=10)
    place_rings(h, RINGS_A, Y)
    place_rings(h, RINGS_B, Y)
    try:
        h.start_bot("FarmGuardBot01")
        h.tp_bot("FarmGuardBot01", 16.5, Y, 6.5, wait=1.2)
        out["bot"] = "ok"
    except Exception as exc:  # noqa: BLE001
        out["bot"] = str(exc)
        log("bot start failed: " + str(exc))

    samples: list[dict] = []
    ready = False
    deadline = time.time() + CLUSTER_WAIT
    while time.time() < deadline:
        refill_rings(h, RINGS_A + RINGS_B, Y)
        try:
            h.tp_bot("FarmGuardBot01", 16.5, Y, 6.5, wait=0.4)
        except Exception:
            pass
        a = inspect_chunk(h, *CHUNK_A)
        b = inspect_chunk(h, *CHUNK_B)
        row = {
            "aActivity": a.get("activity"),
            "bActivity": b.get("activity"),
            "aId": a.get("clusterId"),
            "bId": b.get("clusterId"),
            "aChunks": a.get("clusterChunks"),
            "bChunks": b.get("clusterChunks"),
            "aNoCluster": a.get("noCluster"),
            "bNoCluster": b.get("noCluster"),
        }
        samples.append(row)
        log(
            f"inspect A act={row['aActivity']} id={row['aId']} n={row['aChunks']} | "
            f"B act={row['bActivity']} id={row['bId']} n={row['bChunks']}"
        )
        act_ok = (a.get("activity") or 0) >= 16 and (b.get("activity") or 0) >= 16
        same = (
            act_ok
            and a.get("clusterId")
            and a.get("clusterId") == b.get("clusterId")
            and int(a.get("clusterChunks") or 0) >= 2
            and int(b.get("clusterChunks") or 0) >= 2
        )
        if same:
            time.sleep(6)
            a = inspect_chunk(h, *CHUNK_A)
            b = inspect_chunk(h, *CHUNK_B)
            out["inspectA"] = a
            out["inspectB"] = b
            ready = (
                (a.get("activity") or 0) >= 16
                and (b.get("activity") or 0) >= 16
                and a.get("clusterId")
                and a.get("clusterId") == b.get("clusterId")
                and int(a.get("clusterChunks") or 0) == 2
                and int(b.get("clusterChunks") or 0) == 2
            )
            if ready:
                break
        time.sleep(6)

    if "inspectA" not in out:
        out["inspectA"] = inspect_chunk(h, *CHUNK_A)
        out["inspectB"] = inspect_chunk(h, *CHUNK_B)
    out["clusterSamples"] = samples[-8:]
    try:
        h.fgtest("reset")
        time.sleep(5)
        events = h.fgtest("events")
        out["hopperEvents"] = events
    except Exception as exc:  # noqa: BLE001
        out["hopperEventsError"] = str(exc)

    log("Gate A: /fg top zh_CN")
    h.cmd("fg language zh_CN")
    top_zh = h.cmd("fg top 10")
    top1_zh = h.cmd("fg top 1")
    status_zh = h.cmd("fg status")
    out["topZh"] = top_zh
    out["top1Zh"] = top1_zh
    out["statusZh"] = status_zh
    coords_zh = rank_coords(top_zh)
    out["ranksZh"] = rank_lines(top_zh)
    out["coordsZh"] = coords_zh
    cluster_once_zh = ("2 个区块" in top_zh) and (CHUNK_A in coords_zh) and (CHUNK_B not in coords_zh)
    out["clusterOnceZh"] = cluster_once_zh
    out["top1HasSingleRank"] = len(rank_lines(top1_zh)) == 1

    log("Gate A: /fg language en_US then /fg top")
    lang_en = h.cmd("fg language en_US")
    top_en = h.cmd("fg top 10")
    inspect_en_a = inspect_chunk(h, *CHUNK_A)
    inspect_en_b = inspect_chunk(h, *CHUNK_B)
    status_en = h.cmd("fg status")
    out["languageEn"] = lang_en
    out["topEn"] = top_en
    out["inspectEnA"] = inspect_en_a
    out["inspectEnB"] = inspect_en_b
    out["statusEn"] = status_en
    coords_en = rank_coords(top_en)
    out["ranksEn"] = rank_lines(top_en)
    out["coordsEn"] = coords_en
    cluster_once_en = ("2 chunks" in top_en) and (CHUNK_A in coords_en) and (CHUNK_B not in coords_en)
    out["clusterOnceEn"] = cluster_once_en
    same_id_en = inspect_en_a.get("clusterId") == inspect_en_b.get("clusterId") == out["inspectA"].get("clusterId")
    out["languageDidNotChangeCluster"] = same_id_en and int(inspect_en_a.get("clusterChunks") or 0) == 2

    h.cmd("fg language zh_CN")

    log("Gate A: standalone hopper hotspot at chunk 7,7")
    h.prepare_platform(114, Y, 114, radius=6)
    place_rings(h, RINGS_STANDALONE, Y)
    try:
        h.tp_bot("FarmGuardBot01", 114.5, Y, 114.5, wait=0.8)
    except Exception:
        pass
    time.sleep(20)
    refill_rings(h, RINGS_STANDALONE, Y)
    try:
        h.tp_bot("FarmGuardBot01", 16.5, Y, 6.5, wait=0.6)
    except Exception:
        pass
    time.sleep(8)
    inspect_s = inspect_chunk(h, *STANDALONE)
    top_mix = h.cmd("fg top 10")
    out["inspectStandalone"] = inspect_s
    out["topMixed"] = top_mix
    coords_mix = rank_coords(top_mix)
    out["coordsMixed"] = coords_mix
    standalone_shown = STANDALONE in coords_mix
    cluster_still_once = ("2 个区块" in top_mix) and (CHUNK_B not in coords_mix)
    out["standaloneShown"] = standalone_shown
    out["clusterStillOnceWithStandalone"] = cluster_still_once

    a = out["inspectA"]
    b = out["inspectB"]
    ui_blob = "\n".join(
        [
            status_zh,
            top_zh,
            a.get("raw") or "",
            b.get("raw") or "",
            status_en,
            top_en,
            inspect_en_a.get("raw") or "",
            inspect_en_b.get("raw") or "",
        ]
    )
    out["i18nLeaksGateA"] = leaks(ui_blob)

    ok = bool(
        ready
        and (a.get("activity") or 0) >= 16
        and (b.get("activity") or 0) >= 16
        and a.get("clusterId")
        and a.get("clusterId") == b.get("clusterId")
        and int(a.get("clusterChunks") or 0) == 2
        and cluster_once_zh
        and cluster_once_en
        and standalone_shown
        and cluster_still_once
        and not out["i18nLeaksGateA"]
    )
    out["ok"] = ok
    log("Gate A " + ("PASS" if ok else "FAIL"))
    return ok


def run_gate_b(h: Harness, out: dict) -> bool:
    log("Gate B: silence hoppers, enable debug log, cycle observer clocks")
    silence_hoppers(h)
    clocks_off(h)
    try:
        h.tp_bot("FarmGuardBot01", 88.5, Y, 4.5, wait=1.0)
    except Exception as exc:  # noqa: BLE001
        out["botError"] = str(exc)

    if LOGS_DIR.exists():
        for path in LOGS_DIR.glob("debug*.log"):
            path.unlink(missing_ok=True)

    set_debug_enabled(True, 5)
    reload_on = h.cmd("fg reload")
    out["reloadOn"] = reload_on
    time.sleep(2)

    levels: list[dict] = []
    started = time.time()

    def sample_flap(phase: str) -> None:
        snap = inspect_chunk(h, *FLAP)
        try:
            status = h.status()
        except Exception:
            status = {}
        row = {
            "t": round(time.time() - started, 1),
            "phase": phase,
            "activity": snap.get("activity"),
            "riskScore": snap.get("riskScore"),
            "riskLevel": snap.get("riskLevel"),
            "mspt": status.get("mspt"),
            "tps": status.get("tps"),
        }
        levels.append(row)
        log(
            f"flap t={row['t']}s {phase} act={row['activity']} "
            f"risk={row['riskScore']} lvl={row['riskLevel']} mspt={row['mspt']}"
        )

    while time.time() - started < FLAP_SECONDS:
        clocks_on(h)
        try:
            h.tp_bot("FarmGuardBot01", 88.5, Y, 4.5, wait=0.2)
        except Exception:
            pass
        time.sleep(7)
        sample_flap("on")
        clocks_off(h)
        time.sleep(8)
        sample_flap("off")

    clocks_off(h)
    time.sleep(8)
    out["flapDuration"] = round(time.time() - started, 1)
    out["flapSamples"] = levels
    out["inspectFlapEnd"] = inspect_chunk(h, *FLAP)

    copy_debug_logs(RESULTS / "beta3-gate-b-logs-before-off")
    before_rows, before_invalid = load_jsonl(DEBUG_LOG)
    out["jsonlBeforeOffInvalid"] = before_invalid

    set_debug_enabled(False, 5)
    reload_off = h.cmd("fg reload")
    out["reloadOff"] = reload_off
    time.sleep(2)
    copy_debug_logs(RESULTS / "beta3-gate-b-logs")

    rows, invalid_lines = load_jsonl(DEBUG_LOG)
    rotated = []
    if LOGS_DIR.exists():
        for path in sorted(LOGS_DIR.glob("debug.[0-9]*.log")):
            extra, extra_invalid = load_jsonl(path)
            rows.extend(extra)
            invalid_lines += extra_invalid
            rotated.append(path.name)
    out["rotatedFiles"] = rotated
    flap_rows = filter_chunk(rows, *FLAP)
    raw = [r for r in flap_rows if r.get("type") == "risk_transition"]
    summaries = [r for r in flap_rows if r.get("type") == "risk_transition_summary"]
    out["rawTransitions"] = raw
    out["summaries"] = summaries
    out["rawCount"] = len(raw)
    out["summaryCount"] = len(summaries)
    out["summaryTransitionTotal"] = sum(int(r.get("transitionCount") or 0) for r in summaries)
    out["rawHighOrCritical"] = [
        r for r in raw if r.get("to") in {"HIGH", "CRITICAL"} or r.get("from") in {"HIGH", "CRITICAL"}
    ]

    observed_levels = {str(s.get("riskLevel") or "") for s in levels}
    out["observedUiLevels"] = sorted(observed_levels)
    crossed = any(
        token in observed_levels
        for token in ("低风险", "中风险", "Low", "Medium", "高风险", "High")
    )
    out["crossedLowOrMedium"] = crossed

    sample = None
    summary_ok = False
    for row in summaries:
        try:
            count = int(row.get("transitionCount") or 0)
            min_r = float(row.get("minRiskScore"))
            max_r = float(row.get("maxRiskScore"))
            amin = float(row.get("activityMin"))
            amax = float(row.get("activityMax"))
            fields_ok = all(
                k in row
                for k in (
                    "world",
                    "chunkX",
                    "chunkZ",
                    "windowSeconds",
                    "transitionCount",
                    "firstLevel",
                    "lastLevel",
                    "highestLevel",
                    "minRiskScore",
                    "maxRiskScore",
                    "activityMin",
                    "activityMax",
                    "schema",
                )
            )
            numeric_ok = count > 1 and min_r <= max_r and amin <= amax
            schema_ok = row.get("schema") == 1
            if fields_ok and numeric_ok and schema_ok:
                sample = row
                summary_ok = True
                break
        except (TypeError, ValueError):
            continue
    if sample is None and summaries:
        sample = summaries[0]
    out["sampleSummary"] = sample
    out["sampleSummaryOk"] = summary_ok

    # Low/medium flaps must not emit one raw line per transition.
    actual = out["summaryTransitionTotal"] + len(raw)
    out["actualTransitionCount"] = actual
    aggregated = (
        out["summaryCount"] >= 1
        and out["summaryTransitionTotal"] > out["rawCount"]
        and not (actual >= 8 and out["rawCount"] >= actual)
    )
    out["aggregated"] = aggregated

    jsonl_dir = validate_dir(LOGS_DIR) if LOGS_DIR.exists() else {"ok": False, "files": []}
    out["jsonl"] = jsonl_dir
    schema_ok = True
    for row in summaries:
        if row.get("schema") != 1:
            schema_ok = False
    out["summarySchemaOk"] = schema_ok
    out["invalidLines"] = invalid_lines
    jsonl_ok = bool(jsonl_dir.get("ok")) and invalid_lines == 0 and schema_ok

    i18n_zh = {
        "status": h.cmd("fg status"),
        "top": h.cmd("fg top 10"),
        "inspectFlap": inspect_chunk(h, *FLAP).get("raw") or "",
        "inspectA": inspect_chunk(h, *CHUNK_A).get("raw") or "",
    }
    h.cmd("fg language en_US")
    i18n_en = {
        "status": h.cmd("fg status"),
        "top": h.cmd("fg top 10"),
        "inspectFlap": inspect_chunk(h, *FLAP).get("raw") or "",
        "inspectA": inspect_chunk(h, *CHUNK_A).get("raw") or "",
    }
    h.cmd("fg language zh_CN")
    out["i18nZh"] = i18n_zh
    out["i18nEn"] = i18n_en
    leak_zh = leaks("\n".join(i18n_zh.values()))
    leak_en = leaks("\n".join(i18n_en.values()))
    out["i18nLeaksZh"] = leak_zh
    out["i18nLeaksEn"] = leak_en
    out["i18nOk"] = not leak_zh and not leak_en

    debug_has_enums = any(
        r.get("type") in {"risk_transition", "risk_transition_summary"}
        and (
            r.get("firstLevel") in {"NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
            or r.get("to") in {"NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
            or r.get("highestLevel") in {"NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
        )
        for r in flap_rows
    )
    out["debugKeptEnglishEnums"] = debug_has_enums or not flap_rows

    ok = bool(
        crossed
        and summary_ok
        and aggregated
        and jsonl_ok
        and out["i18nOk"]
        and out["flapDuration"] >= 180
    )
    out["ok"] = ok
    out["jsonlOk"] = jsonl_ok
    log(
        f"Gate B raw={out['rawCount']} summaries={out['summaryCount']} "
        f"transitionSum={out['summaryTransitionTotal']} jsonl={jsonl_ok} "
        + ("PASS" if ok else "FAIL")
    )
    return ok


def run() -> dict:
    ensure_results()
    patch_rcon_port()
    if not p15.PLUGIN_JAR_SRC.exists():
        raise RuntimeError("FarmGuard jar missing: " + str(p15.PLUGIN_JAR_SRC))
    result: dict = {
        "startedAt": now_iso(),
        "jar": str(p15.PLUGIN_JAR_SRC),
        "jarSha256": p15.sha256_file(p15.PLUGIN_JAR_SRC),
        "rconPort": p15.RCON_PORT,
        "flapSecondsRequested": FLAP_SECONDS,
        "gateA": {"ok": False},
        "gateB": {"ok": False},
        "ok": False,
    }
    h = Harness()
    try:
        result["startupSeconds"] = h.start_server(reset_plugin_state=True)
        result["gateA"] = {"ok": False}
        a_ok = run_gate_a(h, result["gateA"])
        result["gateB"] = {"ok": False}
        b_ok = run_gate_b(h, result["gateB"])
        result["ok"] = bool(a_ok and b_ok)
        result["CLUSTER_TOP_PAPER"] = "PASS" if a_ok else "FAIL"
        result["RISK_FLAP_PAPER"] = "PASS" if b_ok else "FAIL"
        result["JSONL"] = "PASS" if result["gateB"].get("jsonlOk") else "FAIL"
        result["I18N"] = "PASS" if result["gateB"].get("i18nOk") and not result["gateA"].get("i18nLeaksGateA") else "FAIL"
    finally:
        try:
            h.stop_server()
        except Exception:
            pass
        result["endedAt"] = now_iso()
        path = RESULTS / "beta3-paper-gates.json"
        path.write_text(json.dumps(result, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
        log("wrote " + str(path) + " ok=" + str(result.get("ok")))
    return result


if __name__ == "__main__":
    payload = run()
    sys.exit(0 if payload.get("ok") else 1)
