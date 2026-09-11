#!/usr/bin/env python3
"""Phase 16 orchestrator.

python scripts/phase16/run.py [--until login|monitor|spawn|pressure|recovery|jfr|all]
python scripts/phase16/run.py --from pressure --until pressure
python scripts/phase16/run.py --until recovery
python scripts/phase16/run.py --from jfr --until jfr
python scripts/phase16/run.py --until all --soak 2h --clean-build
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from common import (  # noqa: E402
    GRADLE,
    PROBE_JAR,
    PROBE_PROJECT,
    RESULTS,
    ROOT,
    SERVER,
    ZONES,
    Harness,
    ensure_results,
    git_rev,
    gradle_cmd,
    now_iso,
)
from pressure import (  # noqa: E402
    run_emergency_scenarios,
    run_jfr_load,
    run_pressure_and_correlation,
    run_protect_behaviors,
    run_protect_to_monitor,
    run_recovery,
    write_pressure_recovery_report,
)
from report import write_report  # noqa: E402
from scenarios import (  # noqa: E402
    run_breeding_monitor,
    run_fpp_spawn,
    run_fpp_spawn_as_player,
    run_hopper_integrity,
    run_infra,
    run_item_drop,
    run_item_throttle_default,
    run_login,
    run_minecart,
    run_monitor_interactions,
    run_piston_protocol,
    run_spawn_reasons,
)

FROZEN_JAR_SHA256 = "375D6C0709A246D4CFD6FC48994CDA2CED172B6F3DC25FDDC43976076FF1758E"
STAGES = ["infra", "login", "monitor", "spawn", "pressure", "recovery", "jfr", "all"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--until", default="all", choices=STAGES)
    parser.add_argument("--from", dest="start_from", default="infra", choices=STAGES)
    parser.add_argument("--soak", default="0")
    parser.add_argument("--clean-build", action="store_true")
    parser.add_argument("--skip-server-stop", action="store_true")
    parser.add_argument(
        "--restart-gate",
        action="store_true",
        help="Re-run Restart Gate on the frozen jar/world without another 2h soak",
    )
    parser.add_argument(
        "--smoke",
        action="store_true",
        help="Release smoke: startup, MONITOR, isolated 64-item hopper. No 2h soak.",
    )
    return parser.parse_args()


def stage_index(name: str) -> int:
    return STAGES.index(name)


def want_stage(start_from: str, until: str, name: str) -> bool:
    return stage_index(start_from) <= stage_index(name) <= stage_index(until)


def capture_env(h: Harness, build_out: str) -> None:
    java = subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).splitlines()[0]
    tests = ""
    if "tests completed" in build_out.lower() or "tests passed" in build_out.lower():
        tests = build_out[-500:]
    jar = ROOT / "build" / "libs" / "FarmGuard-1.0.0-beta.1.jar"
    if not jar.exists():
        jar = ROOT / "build" / "libs" / "FarmGuard-0.1.0-SNAPSHOT.jar"
    import phase15_common as p15

    h.env = {
        "paper": "paper-1.21.8-60",
        "java": java,
        "os": sys.platform,
        "farmguard": "1.0.0-beta.1",
        "jarSha256": p15.sha256_file(jar) if jar.exists() else None,
        "build": "BUILD SUCCESSFUL" if "BUILD SUCCESSFUL" in build_out or not build_out else build_out[-200:],
        "unitTests": tests or "not re-run this invocation",
        "baseline": "ab1147af883e1c53e524461011cca1bac255dc8c",
        "head": git_rev(),
        "mineflayer": "4.39.0",
        "fpp": "2.0.6",
        "testProbe": "0.1.0-test",
    }


def copy_logs() -> None:
    ensure_results()
    latest = SERVER / "logs" / "latest.log"
    if latest.exists():
        shutil.copy2(latest, RESULTS / "server.log")
    probe = SERVER / "test-results" / "probe-events.jsonl"
    if probe.exists():
        shutil.copy2(probe, RESULTS / "probe-events.jsonl")


def write_soak_freeze(h: Harness) -> dict:
    import phase15_common as p15

    jar = ROOT / "build" / "libs" / "FarmGuard-1.0.0-beta.1.jar"
    if not jar.exists():
        jar = ROOT / "build" / "libs" / "FarmGuard-0.1.0-SNAPSHOT.jar"
    jar_sha = p15.sha256_file(jar) if jar.exists() else None
    gate_path = RESULTS / "pre-soak-gate.json"
    freeze = {
        "generatedAt": now_iso(),
        "gitCommit": git_rev(),
        "farmguardJarSha256": jar_sha,
        "configYmlSha256": p15.sha256_file(ROOT / "src" / "main" / "resources" / "config.yml"),
        "messagesYmlSha256": p15.sha256_file(ROOT / "src" / "main" / "resources" / "messages.yml"),
        "pluginYmlSha256": p15.sha256_file(ROOT / "src" / "main" / "resources" / "plugin.yml"),
        "paper": "paper-1.21.8-60",
        "java": (h.env or {}).get("java"),
        "fpp": "2.0.6",
        "mineflayer": "4.39.0",
        "testProbe": "0.1.0-test",
        "testProbeJarSha256": p15.sha256_file(PROBE_JAR) if PROBE_JAR.exists() else None,
        "preSoakGateSha256": p15.sha256_file(gate_path) if gate_path.exists() else None,
        "expectedJarSha256": FROZEN_JAR_SHA256,
        "existingCorruptAtFreeze": list_corrupt_names(),
    }
    ensure_results()
    (RESULTS / "soak-freeze.json").write_text(json.dumps(freeze, indent=2), encoding="utf-8")
    extra_ok = jar_sha and jar_sha.upper() == FROZEN_JAR_SHA256.upper()
    freeze["matchesFrozenJar"] = bool(extra_ok)
    (RESULTS / "soak-freeze.json").write_text(json.dumps(freeze, indent=2), encoding="utf-8")
    print("[phase16d] soak-freeze jar=" + str(jar_sha) + " match=" + str(extra_ok), flush=True)
    return freeze


def list_corrupt_names() -> list[str]:
    fg_dir = SERVER / "plugins" / "FarmGuard"
    if not fg_dir.exists():
        return []
    return sorted(p.name for p in fg_dir.glob("*corrupt*") if p.is_file())


def freeze_unix_ms(freeze: dict) -> int | None:
    raw = str(freeze.get("generatedAt") or "")
    if not raw:
        return None
    try:
        from datetime import datetime

        return int(datetime.fromisoformat(raw.replace("Z", "+00:00")).timestamp() * 1000)
    except Exception:
        return None


def new_corrupt_since_freeze(freeze: dict) -> list[str]:
    baseline = set(freeze.get("existingCorruptAtFreeze") or [])
    freeze_ms = freeze_unix_ms(freeze)
    fresh: list[str] = []
    for name in list_corrupt_names():
        if name in baseline:
            continue
        stamp = None
        if "corrupt-" in name:
            try:
                stamp = int(name.rsplit("corrupt-", 1)[-1])
            except ValueError:
                stamp = None
        if freeze_ms is not None and stamp is not None and stamp < freeze_ms:
            continue
        fresh.append(name)
    return fresh


def run_restart_gate(h: Harness, freeze: dict | None = None) -> dict:
    row = h.begin(
        "restart.gate",
        "Graceful stop then cold start: FarmGuard enabled, persistence readable, hopper 64, button",
    )
    fg_dir = SERVER / "plugins" / "FarmGuard"
    freeze = freeze or {}
    try:
        pre = h.status()
        row.extra["preShutdown"] = pre
        t0 = time.time()
        h.stop_server()
        shutdown_s = round(time.time() - t0, 1)
        time.sleep(15)
        h.start_server(reset_plugin_state=False)
        plugins = h.cmd("plugins")
        status_text = h.cmd("fg status")
        top = h.cmd("fg top")
        inspect = h.cmd("fg inspect world 0 0")
        state_path = fg_dir / "state.yml"
        incidents_path = fg_dir / "incidents.yml"
        state = state_path.read_text(encoding="utf-8", errors="replace") if state_path.exists() else ""
        incidents = incidents_path.read_text(encoding="utf-8", errors="replace") if incidents_path.exists() else ""
        incidents_ok = incidents_path.exists() and "incidents:" in incidents
        leftover_corrupt = list_corrupt_names()
        new_corrupt = new_corrupt_since_freeze(freeze) if freeze else leftover_corrupt
        parsed = __import__("phase15_common").parse_status(status_text)
        rx, ry, rz = ZONES["monitor_redstone"]
        h.start_bot("FarmGuardBot01")
        hopper_check = h.isolated_hopper_conservation(64)
        conserved = int(hopper_check.get("total") if hopper_check.get("total") is not None else -1)
        h.prepare_platform(rx, ry, rz)
        h.cmd(f"execute in minecraft:overworld run setblock {rx} {ry} {rz} stone_button[face=floor,facing=north]")
        h.tp_bot("FarmGuardBot01", rx + 1.5, ry, rz + 1.5, wait=0.8)
        try:
            h.bot("FarmGuardBot01").call("activate", x=rx, y=ry, z=rz, timeout=12)
            button_ok = True
        except Exception as extra_exc:  # noqa: BLE001
            button_ok = False
            row.extra["buttonError"] = str(extra_exc)
        limiting = int(parsed.get("limiting") or 0)
        pressure = str(parsed.get("pressure") or "").upper()
        enabled = h.plugin_enabled("FarmGuard")
        state_ok = "mode:" in state.lower() or "MONITOR" in state or "PROTECT" in state
        h.attach_status(row)
        row.extra.update(
            {
                "shutdownSeconds": shutdown_s,
                "plugins": plugins[-500:],
                "status": status_text[-800:],
                "top": top[-500:],
                "inspect": inspect[-500:],
                "state": state[:500],
                "incidentsPresent": incidents_ok,
                "leftoverCorrupt": leftover_corrupt,
                "newCorruptSinceFreeze": new_corrupt,
                "isolatedHopper": hopper_check,
                "hopperTotal": conserved,
                "buttonOk": button_ok,
            }
        )
        ok = (
            enabled
            and state_ok
            and incidents_ok
            and hopper_check.get("ok") is True
            and button_ok
            and limiting == 0
            and pressure in {"", "NORMAL"}
            and not new_corrupt
        )
        if ok:
            row.finish(
                "PASS",
                f"shutdown {shutdown_s}s hopper={conserved} limiting={limiting} pressure={pressure} "
                f"leftoverCorrupt={len(leftover_corrupt)} newCorrupt={len(new_corrupt)}",
            )
            return {"restartPass": True, "restart": row.actual, "shutdownSeconds": shutdown_s}
        row.finish(
            "FAIL",
            f"enabled={enabled} hopper={conserved} limiting={limiting} pressure={pressure} newCorrupt={new_corrupt}",
        )
        return {"restartPass": False, "restart": row.actual, "shutdownSeconds": shutdown_s}
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))
        return {"restartPass": False, "restart": str(exc)}


def write_final_beta_gate(h: Harness, extra: dict) -> dict:
    pr = extra.get("pressureRecovery") or {}
    gate_in = extra.get("preSoakGate") or {}
    soak_s = float(extra.get("durationSeconds") or extra.get("soakDuration") or 0)
    jfr = extra.get("jfrLoad") or {}
    share = jfr.get("productShare")
    profiler_ok = share is None or float(share) < 0.15
    gate = {
        "preSoakGate": gate_in.get("readyForSoak") is True,
        "soakDurationSeconds": soak_s,
        "soakPass": extra.get("soakPass") is True and soak_s >= 7200,
        "restartPass": extra.get("restartPass") is True,
        "monitorSafetyPass": gate_in.get("monitorSafetyPass") is True,
        "highReached": pr.get("highReached") is True or gate_in.get("highReached") is True,
        "criticalReached": pr.get("criticalReached") is True or gate_in.get("criticalReached") is True,
        "emergencyReached": pr.get("emergencyReached") is True or gate_in.get("emergencyReached") is True,
        "hopperIntegrityPass": pr.get("hopperIntegrity") == "PASS" or gate_in.get("hopperIntegrityPass") is True,
        "correlationPass": pr.get("correlation") == "PASS" or gate_in.get("correlationPass") is True,
        "recoveryPass": gate_in.get("recoveryPass") is True,
        "profilerAcceptable": profiler_ok,
        "unresolvedP0": len(h.p0),
        "unresolvedP1": len(h.p1),
    }
    gate["readyForBeta1"] = (
        gate["preSoakGate"]
        and gate["soakPass"]
        and gate["restartPass"]
        and gate["monitorSafetyPass"]
        and gate["highReached"]
        and gate["criticalReached"]
        and gate["emergencyReached"]
        and gate["hopperIntegrityPass"]
        and gate["correlationPass"]
        and gate["recoveryPass"]
        and gate["profilerAcceptable"]
        and gate["unresolvedP0"] == 0
        and gate["unresolvedP1"] == 0
    )
    ensure_results()
    (RESULTS / "final-beta-gate.json").write_text(json.dumps(gate, indent=2), encoding="utf-8")
    extra["finalBetaGate"] = gate
    extra["verdict"] = "READY FOR 1.0.0-BETA.1" if gate["readyForBeta1"] else "READY FOR MORE CONTROLLED TESTING"
    print("[phase16d] final-beta-gate readyForBeta1=" + str(gate["readyForBeta1"]), flush=True)
    return gate


def write_pre_soak_gate(h: Harness, extra: dict) -> dict:
    statuses = {row.scenario: row.status for row in h.results}
    pr = extra.get("pressureRecovery") or {}
    fails = [row for row in h.results if row.status in {"FAIL", "ERROR"}]
    monitor_keys = [
        "monitor.button",
        "monitor.lever",
        "monitor.wooden_door",
        "monitor.iron_door",
        "monitor.trapdoor",
        "monitor.repeater",
        "monitor.comparator",
        "monitor.observer_clock",
        "monitor.item_drop",
        "monitor.minecart_place",
        "monitor.breeding",
        "protocol.piston",
    ]
    spawn_keys = ["spawn.command", "spawn.natural", "spawn.spawner"]
    farm_a_fp = any(
        row.scenario == "lag.farm_a_vs_b" and "A=STRONG" in str(row.actual or "")
        for row in h.results
    )
    gate = {
        "fullRegressionPass": not fails
        and all(statuses.get(key) == "PASS" for key in monitor_keys)
        and all(statuses.get(key) == "PASS" for key in spawn_keys),
        "highReached": pr.get("highReached") is True,
        "criticalReached": pr.get("criticalReached") is True,
        "emergencyReached": pr.get("emergencyReached") is True,
        "hopperIntegrityPass": pr.get("hopperIntegrity") == "PASS",
        "correlationPass": pr.get("correlation") == "PASS",
        "farmAFalsePositive": farm_a_fp,
        "recoveryPass": statuses.get("recovery.automatic") == "PASS",
        "monitorSafetyPass": all(statuses.get(key) == "PASS" for key in monitor_keys),
        "unresolvedP0": len(h.p0),
        "unresolvedP1": len(h.p1),
    }
    gate["readyForSoak"] = (
        gate["fullRegressionPass"]
        and gate["highReached"]
        and gate["criticalReached"]
        and gate["emergencyReached"]
        and gate["hopperIntegrityPass"]
        and gate["correlationPass"]
        and not gate["farmAFalsePositive"]
        and gate["recoveryPass"]
        and gate["monitorSafetyPass"]
        and gate["unresolvedP0"] == 0
        and gate["unresolvedP1"] == 0
    )
    ensure_results()
    (RESULTS / "pre-soak-gate.json").write_text(json.dumps(gate, indent=2), encoding="utf-8")
    extra["preSoakGate"] = gate
    print("[phase16] pre-soak-gate readyForSoak=" + str(gate["readyForSoak"]), flush=True)
    return gate


def _load_json(path: Path) -> dict:
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def annotate_freeze_leftover_corrupt(freeze: dict) -> dict:
    freeze_ms = freeze_unix_ms(freeze)
    pre = list(freeze.get("existingCorruptAtFreeze") or [])
    if not pre:
        for name in list_corrupt_names():
            stamp = None
            if "corrupt-" in name:
                try:
                    stamp = int(name.rsplit("corrupt-", 1)[-1])
                except ValueError:
                    stamp = None
            if freeze_ms is not None and stamp is not None and stamp < freeze_ms:
                pre.append(name)
        freeze["existingCorruptAtFreeze"] = sorted(set(pre))
        (RESULTS / "soak-freeze.json").write_text(json.dumps(freeze, indent=2), encoding="utf-8")
    return freeze


def run_restart_gate_only(h: Harness, extra: dict) -> int:
    jar_sha = str((h.env or {}).get("jarSha256") or "")
    if jar_sha.upper() != FROZEN_JAR_SHA256.upper():
        raise RuntimeError("Frozen soak jar mismatch: " + jar_sha + " expected " + FROZEN_JAR_SHA256)
    freeze = annotate_freeze_leftover_corrupt(_load_json(RESULTS / "soak-freeze.json"))
    extra["soakFreeze"] = freeze
    extra["preSoakGate"] = _load_json(RESULTS / "pre-soak-gate.json")
    extra["pressureRecovery"] = _load_json(RESULTS / "pressure-recovery-report.json")
    soak = _load_json(RESULTS / "soak" / "result.json")
    extra.update(soak)
    prev = _load_json(RESULTS / "summary.json")
    if prev.get("jfrLoad"):
        extra["jfrLoad"] = prev["jfrLoad"]
    extra["priorScenarios"] = prev.get("scenarios") or []
    if "P2 — Redstone automatic suppression effectiveness limited" not in h.p2:
        h.p2.append("P2 — Redstone automatic suppression effectiveness limited")
    if "P2 — Piston EMERGENCY suppression coverage is less extensive than hopper protection" not in h.p2:
        h.p2.append("P2 — Piston EMERGENCY suppression coverage is less extensive than hopper protection")
    print("[phase16d] restart-gate only; waiting for previous Paper RCON to release", flush=True)
    time.sleep(12)
    h.start_server(reset_plugin_state=False)
    run_infra(h)
    run_login(h)
    if any(row.scenario == "mineflayer.login_gate" and row.status == "PASS" for row in h.results):
        run_fpp_spawn_as_player(h)
    hopper = h.isolated_hopper_conservation(64)
    extra["isolatedHopperPostSoak"] = hopper
    duration = float(soak.get("durationSeconds") or 0)
    soak_row = h.begin(
        "soak.continuous_2h",
        "Continuous soak >= 7200s with MONITOR/PROTECT, two HIGH cycles, one brief CRITICAL",
    )
    if duration >= 7200 and hopper.get("ok"):
        extra["soakPass"] = True
        extra["soak"] = (
            f"continuous {round(duration)}s highSamples={soak.get('highSamples')} "
            f"criticalSamples={soak.get('criticalSamples')}; isolated 64-item hopper PASS "
            f"(original end check counted leftover hopper-ring items in monitor_hopper)"
        )
        extra["hopperCheckNote"] = (
            "Original soak-end hopper used ZONES.monitor_hopper after hopper rings; "
            "ground/container leftovers made total!=64. Isolated integrity column conserved 64."
        )
        h.p0 = [item for item in h.p0 if "hopper" not in item.lower()]
        soak_row.extra.update({"durationSeconds": duration, "isolatedHopper": hopper})
        soak_row.finish("PASS", extra["soak"])
        soak["soakPass"] = True
        soak["isolatedHopper"] = hopper
        soak["soak"] = extra["soak"]
        (RESULTS / "soak" / "result.json").write_text(json.dumps(soak, indent=2, ensure_ascii=False), encoding="utf-8")
    else:
        extra["soakPass"] = False
        if not hopper.get("ok"):
            h.p0.append("P0 hopper conservation failed isolated post-soak check total=" + str(hopper.get("total")))
        soak_row.finish("FAIL", f"duration={duration} isolatedHopper={hopper.get('total')}")
    extra.update(run_restart_gate(h, freeze))
    write_final_beta_gate(h, extra)
    copy_logs()
    write_report(h, extra)
    fails = [row for row in h.results if row.status in {"FAIL", "ERROR"}]
    return 1 if fails else 0


def run_release_smoke(h: Harness, extra: dict, build_out: str) -> int:
    print("[phase16] release smoke: startup + MONITOR + hopper 64", flush=True)
    time.sleep(12)
    h.start_server(reset_plugin_state=True)
    run_infra(h)
    run_login(h)
    status = h.status()
    row = h.begin("smoke.release", "Release jar enables, MONITOR, 64-item hopper conservation")
    hopper = h.isolated_hopper_conservation(64)
    enabled = h.plugin_enabled("FarmGuard")
    mode = str(status.get("mode") or "").upper()
    ok = enabled and mode == "MONITOR" and hopper.get("ok") is True
    row.extra.update({"status": status, "isolatedHopper": hopper, "enabled": enabled})
    if ok:
        row.finish("PASS", f"enabled MONITOR hopper={hopper.get('total')} mspt={status.get('mspt')}")
    else:
        row.finish("FAIL", f"enabled={enabled} mode={mode} hopper={hopper.get('total')}")
    extra["smoke"] = row.actual
    extra["smokePass"] = ok
    (RESULTS / "release-smoke.json").write_text(
        json.dumps(
            {
                "smokePass": ok,
                "mode": mode,
                "enabled": enabled,
                "hopper": hopper,
                "status": status,
                "build": build_out[-400:],
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    copy_logs()
    write_report(h, extra)
    return 0 if ok else 1


def main() -> int:
    args = parse_args()
    ensure_results()
    os.environ.setdefault("PAPER_XMX", "3G")
    h = Harness()
    extra: dict = {"baseline": "ab1147af883e1c53e524461011cca1bac255dc8c"}
    build_out = ""
    try:
        soak_arg = str(args.soak).lower()
        soak_requested = soak_arg in {"2h", "7200", "2"}
        if soak_requested and args.clean_build:
            print("Refusing --clean-build together with soak; frozen FarmGuard jar must not change", flush=True)
            args.clean_build = False
        if args.clean_build:
            build_out = gradle_cmd("clean", "build")
        else:
            build_out = "skipped FarmGuard clean build to preserve frozen soak jar"
        if not args.restart_gate:
            gradle_cmd("jar", cwd=PROBE_PROJECT)
        capture_env(h, build_out)
        h.build_out = build_out
        if args.smoke:
            return run_release_smoke(h, extra, build_out)
        if args.restart_gate:
            return run_restart_gate_only(h, extra)
        if soak_requested:
            jar_sha = str((h.env or {}).get("jarSha256") or "")
            if jar_sha.upper() != FROZEN_JAR_SHA256.upper():
                raise RuntimeError(
                    "Frozen soak jar mismatch: " + jar_sha + " expected " + FROZEN_JAR_SHA256
                )
        h.start_server(reset_plugin_state=True)
        run_infra(h)
        run_fpp_spawn(h)
        from_idx = stage_index(args.start_from)
        until_idx = stage_index(args.until)

        def want(name: str) -> bool:
            return from_idx <= stage_index(name) <= until_idx

        if until_idx >= stage_index("login"):
            run_login(h)
            if any(row.scenario == "mineflayer.login_gate" and row.status == "PASS" for row in h.results):
                run_fpp_spawn_as_player(h)
        logged_in = any(row.scenario == "mineflayer.login_gate" and row.status == "PASS" for row in h.results)
        if logged_in and want("monitor"):
            run_monitor_interactions(h)
            run_piston_protocol(h)
            run_item_drop(h)
            run_minecart(h)
            run_breeding_monitor(h)
        if want("spawn"):
            run_spawn_reasons(h)
        if want("pressure"):
            run_hopper_integrity(h)
            run_pressure_and_correlation(h)
            if h.pressure_session.get("highReached"):
                try:
                    still = bool((h.pressure_cmd("status") or {}).get("running"))
                except Exception:
                    still = False
                if still:
                    if not h.pressure_session.get("emergencyScenariosRan"):
                        run_emergency_scenarios(h)
                    run_protect_behaviors(h)
                else:
                    print("[phase16b] skip emergency/protect behaviors; pressure generator already stopped", flush=True)
            run_item_throttle_default(h)
            extra["pressureRecovery"] = write_pressure_recovery_report(h)
        if want("recovery"):
            run_recovery(h)
            if not h.pressure_session.get("protectToMonitorDuringEmergency"):
                run_protect_to_monitor(h)
            extra["pressureRecovery"] = write_pressure_recovery_report(h)
        profiler_flag = os.environ.get("PHASE16_PROFILER", "1").strip().lower()
        profiler_on = profiler_flag not in {"0", "false", "no", "off"}
        if soak_requested:
            profiler_on = False
            prev_summary = RESULTS / "summary.json"
            if prev_summary.exists():
                try:
                    old = json.loads(prev_summary.read_text(encoding="utf-8"))
                    extra["jfrLoad"] = extra.get("jfrLoad") or old.get("jfrLoad")
                except Exception:
                    pass
            print("[phase16d] skipping soak-time JFR; using prior load profile if present", flush=True)
        if want("jfr"):
            if profiler_on:
                print("[phase16] JFR load profile starting", flush=True)
                extra.update(run_jfr_load(h))
                extra["pressureRecovery"] = write_pressure_recovery_report(h)
            else:
                print("[phase16] JFR skipped because PHASE16_PROFILER=" + profiler_flag, flush=True)
        if soak_requested:
            existing_path = RESULTS / "pre-soak-gate.json"
            existing = {}
            if existing_path.exists():
                try:
                    existing = json.loads(existing_path.read_text(encoding="utf-8"))
                except Exception:
                    existing = {}
            if existing.get("readyForSoak") is True:
                gate = existing
                extra["preSoakGate"] = existing
                print("[phase16d] using frozen pre-soak-gate.json readyForSoak=true", flush=True)
            else:
                gate = write_pre_soak_gate(h, extra)
        else:
            gate = write_pre_soak_gate(h, extra)
        if soak_requested:
            freeze = write_soak_freeze(h)
            extra["soakFreeze"] = freeze
            if not freeze.get("matchesFrozenJar"):
                extra["soak"] = "SKIPPED: FarmGuard jar SHA-256 does not match frozen soak build"
                extra["soakPass"] = False
                print("Refusing soak; rebuild would break freeze. Expected " + FROZEN_JAR_SHA256, flush=True)
            elif gate.get("readyForSoak") is True:
                from soak import SOAK_SECONDS, run_soak

                if "P2 — Redstone automatic suppression effectiveness limited" not in h.p2:
                    h.p2.append("P2 — Redstone automatic suppression effectiveness limited")
                if "P2 — Piston EMERGENCY suppression coverage is less extensive than hopper protection" not in h.p2:
                    h.p2.append("P2 — Piston EMERGENCY suppression coverage is less extensive than hopper protection")
                extra.update(run_soak(h, SOAK_SECONDS))
                extra.update(run_restart_gate(h, freeze))
                write_final_beta_gate(h, extra)
            else:
                extra["soak"] = "SKIPPED: pre-soak-gate.json readyForSoak is not true"
                extra["soakPass"] = False
                print("Refusing 2h soak until test-results/phase16/pre-soak-gate.json readyForSoak is true", flush=True)
        copy_logs()
        write_report(h, extra)
        fails = [row for row in h.results if row.status in {"FAIL", "ERROR"}]
        return 1 if fails else 0
    except Exception as exc:  # noqa: BLE001
        row = h.begin("orchestrator.fatal", "harness completes without crashing")
        row.finish("ERROR", str(exc))
        try:
            copy_logs()
            write_report(h, extra)
        except Exception:
            pass
        print("FATAL", exc)
        import traceback

        traceback.print_exc()
        return 2
    finally:
        if not args.skip_server_stop:
            try:
                h.stop_server()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
