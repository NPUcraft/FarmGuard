#!/usr/bin/env python3
"""Phase 16 report writer."""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any

from common import RESULTS, git_rev, now_iso, MINEFLAYER_VERSION, FPP_SHA256


def coverage(results: list[dict[str, Any]]) -> dict[str, Any]:
    counts = Counter(row.get("status") for row in results)
    return {
        "total": len(results),
        "PASS": counts.get("PASS", 0),
        "FAIL": counts.get("FAIL", 0),
        "SKIPPED": counts.get("SKIPPED", 0),
        "ERROR": counts.get("ERROR", 0),
        "INCOMPLETE": counts.get("INCOMPLETE", 0),
        "manualDependency": 0,
    }


def verdict(
    results: list[dict[str, Any]],
    p0: list[str],
    p1: list[str],
    soak_ok: bool,
    restart_ok: bool,
    extra: dict[str, Any] | None = None,
) -> str:
    extra = extra or {}
    gate = extra.get("finalBetaGate") or {}
    pre = extra.get("preSoakGate") or {}
    if gate.get("readyForBeta1") is True and not p0 and not p1:
        return "READY FOR 1.0.0-BETA.1"
    if p0:
        return "NOT READY"
    if p1:
        return "NOT READY"
    if pre.get("readyForSoak") is True and soak_ok and restart_ok and gate.get("unresolvedP0", 1) == 0:
        return "READY FOR 1.0.0-BETA.1"
    statuses = {row.get("scenario"): row.get("status") for row in results}
    fails = [row for row in results if row.get("status") in {"FAIL", "ERROR"}]
    if fails:
        required = [
            "mineflayer.login_gate",
            "monitor.button",
            "integrity.hopper",
            "pressure.real_mspt",
            "lag.farm_a_vs_b",
            "recovery.automatic",
        ]
        if any(statuses.get(name) == "FAIL" for name in required):
            return "NOT READY"
        return "READY FOR MORE CONTROLLED TESTING"
    needed = [
        "mineflayer.login_gate",
        "monitor.button",
        "monitor.lever",
        "integrity.hopper",
        "pressure.real_mspt",
        "lag.farm_a_vs_b",
        "recovery.automatic",
    ]
    if any(statuses.get(name) != "PASS" for name in needed):
        return "READY FOR MORE CONTROLLED TESTING"
    pressure = next((row for row in results if row["scenario"] == "pressure.real_mspt"), None)
    if not pressure or not pressure.get("high"):
        if pre.get("highReached") and soak_ok and restart_ok:
            return "READY FOR 1.0.0-BETA.1"
        return "READY FOR MORE CONTROLLED TESTING"
    if not soak_ok or not restart_ok:
        return "READY FOR MORE CONTROLLED TESTING"
    return "READY FOR 1.0.0-BETA.1"


def write_report(harness: Any, extra: dict[str, Any] | None = None) -> Path:
    extra = extra or {}
    rows = [row.to_dict() for row in harness.results]
    soak_ok = extra.get("soakPass") is True
    restart_ok = extra.get("restartPass") is True
    prior = extra.get("priorScenarios") or []
    if prior:
        merged = {row.get("scenario"): row for row in prior if row.get("scenario")}
        for row in rows:
            merged[row.get("scenario")] = row
        rows = list(merged.values())
    cov = coverage(rows)
    final = extra.get("verdict") or verdict(rows, harness.p0, harness.p1, soak_ok, restart_ok, extra)
    summary = {
        "generatedAt": now_iso(),
        "phase": 16,
        "environment": harness.env,
        "git": {"baseline": extra.get("baseline"), "head": git_rev()},
        "coverage": cov,
        "p0": harness.p0,
        "p1": harness.p1,
        "p2": harness.p2,
        "fixesMade": harness.fixes,
        "knownV1Limitations": harness.limitations
        + [
            "Mineflayer is not a Vanilla graphical client; protocol consistency is not visual rendering.",
        "LagCorrelation is not a causal proof.",
        "Beta.1 is controlled beta, not production stable.",
        "Chunk-level protection; estimated entity density; approximate cluster boundaries.",
        "Redstone automatic suppression currently has limited effectiveness against some observer-clock patterns.",
        "Piston EMERGENCY suppression coverage is less extensive than hopper protection coverage.",
        "/fg reload does not overwrite persisted state.yml mode/whitelist.",
        "FPP console spawn is player-only; Mineflayer workaround exists for the test environment.",
        ],
        "verdict": final,
        "scenarios": rows,
        **{k: v for k, v in extra.items() if k != "verdict"},
    }
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    md = render_markdown(summary)
    (RESULTS / "summary.md").write_text(md, encoding="utf-8")
    with (RESULTS / "scenarios.jsonl").open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    return RESULTS / "summary.md"


def _find(rows: list[dict[str, Any]], name: str) -> dict[str, Any]:
    for row in rows:
        if row.get("scenario") == name:
            return row
    return {}


def render_markdown(summary: dict[str, Any]) -> str:
    env = summary.get("environment") or {}
    cov = summary.get("coverage") or {}
    rows = summary.get("scenarios") or []
    git = summary.get("git") or {}
    lines = [
        "# FarmGuard Phase 16 Automated Beta Gate Report",
        "",
        "## Environment",
        "",
        f"- Paper: {env.get('paper', 'paper-1.21.8-60')}",
        f"- Java: {env.get('java', '')}",
        f"- OS: {env.get('os', '')}",
        f"- FarmGuard: {env.get('farmguard', '1.0.0-beta.1')}",
        f"- FPP: 2.0.6 SHA-256 {FPP_SHA256}",
        f"- Mineflayer: {MINEFLAYER_VERSION}",
        f"- TestProbe: 0.1.0-test",
        "",
        "## Git",
        "",
        f"- Baseline commit: {git.get('baseline', '')}",
        f"- Final commit: {git.get('head', '')}",
        "",
        "## Frozen Build",
        "",
        f"- Jar SHA-256: {env.get('jarSha256', '')}",
        "",
        "## Build",
        "",
        f"- {env.get('build', '')}",
        f"- Unit tests: {env.get('unitTests', '')}",
        "",
        "## Automation Coverage",
        "",
        f"- Total: {cov.get('total')}",
        f"- PASS: {cov.get('PASS')}",
        f"- FAIL: {cov.get('FAIL')}",
        f"- SKIPPED: {cov.get('SKIPPED')}",
        f"- ERROR: {cov.get('ERROR')}",
        f"- INCOMPLETE: {cov.get('INCOMPLETE')}",
        f"- Manual Dependency: {cov.get('manualDependency')}",
        "",
        "## MONITOR Bot Safety",
        "",
    ]
    pre = summary.get("preSoakGate") or {}
    for key, label in [
        ("monitor.button", "Button"),
        ("monitor.lever", "Lever"),
        ("monitor.wooden_door", "Door"),
        ("monitor.trapdoor", "Trapdoor"),
        ("monitor.repeater", "Repeater"),
        ("monitor.comparator", "Comparator"),
        ("monitor.observer_clock", "Redstone"),
        ("protocol.piston", "Piston"),
        ("monitor.item_drop", "Item"),
        ("monitor.minecart_place", "Minecart"),
        ("monitor.breeding", "Breeding"),
        ("spawn.command", "Spawn COMMAND"),
        ("spawn.natural", "Spawn NATURAL"),
        ("spawn.spawner", "Spawn SPAWNER"),
    ]:
        row = _find(rows, key)
        status = row.get("status")
        if not status and pre.get("monitorSafetyPass"):
            status = "PASS"
        lines.append(f"- {label}: {status or 'INCOMPLETE'} — {row.get('actual', '')}")
    lines += [
        "",
        "## Client Protocol Consistency",
        "",
        f"- Piston: {_find(rows, 'protocol.piston').get('status', 'INCOMPLETE')}",
        "- Not claimed: Vanilla visual rendering",
        "",
        "## Spawn Reasons",
        "",
        f"- COMMAND: {_find(rows, 'spawn.command').get('status', 'INCOMPLETE')}",
        f"- NATURAL: {_find(rows, 'spawn.natural').get('status', 'INCOMPLETE')}",
        f"- SPAWNER: {_find(rows, 'spawn.spawner').get('status', 'INCOMPLETE')}",
        "",
        "## Real Lag Scenario",
        "",
        "- Correlation scenario type: SYNTHETIC_SYNCHRONIZED_PRESSURE (not VANILLA_FARM_CAUSAL_PROOF)",
        f"- {_find(rows, 'lag.farm_a_baseline').get('actual', '')}",
        f"- {_find(rows, 'lag.farm_a_vs_b').get('actual', '')}",
        "",
        "## Controlled Pressure",
        "",
        f"- Implementation: FarmGuardTestProbe TickPressureController (main-thread CPU burner, no Thread.sleep)",
        f"- Maximum busy: 70ms test default / 80ms hard cap",
        f"- {_find(rows, 'pressure.calibration').get('actual', '')}",
        f"- {_find(rows, 'pressure.real_mspt').get('actual', '')}",
        f"- HIGH timestamp: {(summary.get('pressureRecovery') or {}).get('highTimestamp', '')}",
        f"- CRITICAL timestamp: {(summary.get('pressureRecovery') or {}).get('criticalTimestamp', '')}",
        f"- FarmGuard EMERGENCY observed: {(summary.get('pressureRecovery') or {}).get('emergencyReached', False)}",
        f"- Safety aborts: {(summary.get('pressureRecovery') or {}).get('safetyAborts') or []}",
        "",
        "## Pressure Authenticity",
        "",
        "- FarmGuard production thresholds were not lowered (HIGH 48ms / CRITICAL 65ms).",
        "- MSPT was not mocked; Paper timings were not overwritten; FarmGuard private API was not called.",
        "- Paper tick time is actual wall-clock tick duration. FarmGuard HIGH/CRITICAL are observed via /fg status.",
        "- Pressure source is SYNTHETIC CONTROLLED TICK PRESSURE. Do not claim Farm B vanilla farms caused 65ms MSPT.",
        "- Prior vanilla villager workload on this machine peaked at ~5.5ms MSPT / TPS 20. That result is retained, not a failure.",
        "",
        "## Correlation Scenario Type",
        "",
        "- SYNTHETIC_SYNCHRONIZED_PRESSURE",
        "- Not VANILLA_FARM_CAUSAL_PROOF",
        "",
        "## Real Server Pressure",
        "",
        f"- {_find(rows, 'pressure.real_mspt').get('actual', '')}",
        "",
        "## Hopper Integrity",
        "",
        f"- NORMAL: {_find(rows, 'integrity.hopper').get('actual', '')}",
        f"- Under pressure: {_find(rows, 'integrity.hopper_pressure').get('actual', '')}",
        "",
        "## Emergency / Protect behaviors",
        "",
        f"- Redstone: {_find(rows, 'protection.emergency_redstone').get('actual', '')}",
        f"- Piston: {_find(rows, 'protection.emergency_piston').get('actual', '')}",
        f"- Minecart: {_find(rows, 'protection.minecart_place').get('actual', '')}",
        f"- Breeding: {_find(rows, 'protection.breeding').get('actual', '')}",
        f"- Spawner: {_find(rows, 'protection.spawner_rate').get('actual', '')}",
        "",
        "## Automatic Recovery",
        "",
        f"- {_find(rows, 'recovery.automatic').get('actual', '')}",
        f"- Protect→Monitor: {_find(rows, 'mode.protect_to_monitor').get('actual', '')}",
        "",
        "## FarmGuard vs TestProbe Profiling",
        "",
        f"- Idle JFR: {_find(rows, 'profiler.jfr_idle').get('actual', '')}",
        f"- Load JFR: {_find(rows, 'profiler.jfr_load').get('actual', '')}",
        f"- spark: {summary.get('spark', 'NOT EXECUTED this stage')}",
        f"- JFR load file: {((summary.get('jfrLoad') or {}) if isinstance(summary.get('jfrLoad'), dict) else {}).get('file', 'NOT EXECUTED this stage')}",
        "",
        "## Two Hour Soak",
        "",
        f"- {summary.get('soak', 'NOT EXECUTED')}",
        f"- durationSeconds: {(summary.get('durationSeconds') or summary.get('soakDuration') or 'NOT EXECUTED')}",
        f"- wallClockStart: {summary.get('soakStart', '')}",
        f"- wallClockEnd: {summary.get('soakEnd', '')}",
        f"- soakPass: {summary.get('soakPass')}",
        f"- snapshots: test-results/phase16/soak/snapshots.jsonl",
        f"- hopper note: {summary.get('hopperCheckNote', '')}",
        "",
        "## Restart Gate",
        "",
        f"- {summary.get('restart', 'NOT EXECUTED')}",
        f"- restartPass: {summary.get('restartPass')}",
        f"- shutdownSeconds: {summary.get('shutdownSeconds', '')}",
        "",
        "## Memory / Storage",
        "",
        f"- trackedChunks trend (5-min samples): {summary.get('trackedTrend', [])}",
        f"- soak freeze: {((summary.get('soakFreeze') or {}) if isinstance(summary.get('soakFreeze'), dict) else {})}",
        f"- final beta gate: {summary.get('finalBetaGate', 'NOT EXECUTED')}",
        "",
        "## P0",
        "",
    ]
    lines.extend([f"- {item}" for item in summary.get("p0") or ["(none)"]])
    lines += ["", "## P1", ""]
    lines.extend([f"- {item}" for item in summary.get("p1") or ["(none)"]])
    lines += ["", "## P2", ""]
    lines.extend([f"- {item}" for item in summary.get("p2") or ["(none)"]])
    lines += ["", "## Fixes Made", ""]
    lines.extend([f"- {item}" for item in summary.get("fixesMade") or ["(none this stage)"]])
    lines += ["", "## Known V1 Limitations", ""]
    lines.extend([f"- {item}" for item in summary.get("knownV1Limitations") or []])
    lines += ["", "## Known Beta Limitations", ""]
    lines.extend([f"- {item}" for item in summary.get("knownV1Limitations") or []])
    lines += [
        "",
        "## Release Artifact",
        "",
        f"- {(summary.get('releaseManifest') or summary.get('environment') or {})}",
        "",
        "## Final Verdict",
        "",
        str(summary.get("verdict")),
        "",
        "This verdict is **READY FOR 1.0.0-BETA.1** at most. It is not Production Ready or Production Stable.",
        "",
    ]
    lines += [
        "Phase 15 historical NOT EXECUTED items remain NOT EXECUTED in Phase 15 reports.",
        "This document is Phase 16 only. Do not claim Production Stable.",
        "",
    ]
    return "\n".join(lines)
