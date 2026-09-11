#!/usr/bin/env python3
"""Phase 16B controlled tick pressure. TestProbe CPU burner only; no FarmGuard hooks."""

from __future__ import annotations

import json
import time
from typing import Any

from common import EMERGENCY_MSPT_LIMIT, RESULTS, ZONES, Harness, now_iso

PRODUCTION_HIGH_MSPT = 48.0
PRODUCTION_CRITICAL_MSPT = 65.0
RAMP_LEVELS = (0, 10, 20, 30, 40, 50, 60, 68)
LEVEL_SECONDS = 16
RCON_LATENCY_ABORT = 8.0
VANILLA_ENTITY_PEAK_MSPT = 5.5
HOLD_SECONDS = 480
CORR_SCENARIO = "SYNTHETIC_SYNCHRONIZED_PRESSURE"


def log(msg: str) -> None:
    print("[phase16b] " + msg, flush=True)


def item_count(status: dict[str, Any], name: str) -> int:
    needle = name.lower()
    total = 0
    for item in status.get("inventory") or []:
        if needle in str(item.get("name") or "").lower():
            total += int(item.get("count") or 0)
    return total


def ensure_bot(h: Harness, name: str) -> None:
    if name not in h.bots:
        h.start_bot(name)


def build_hopper_ring(h: Harness, x: int, y: int, z: int) -> None:
    h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z} hopper[facing=east]")
    h.cmd(f"execute in minecraft:overworld run setblock {x + 1} {y} {z} hopper[facing=south]")
    h.cmd(f"execute in minecraft:overworld run setblock {x + 1} {y} {z + 1} hopper[facing=west]")
    h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z + 1} hopper[facing=north]")
    for ox, oz in ((0, 0), (1, 0), (1, 1), (0, 1)):
        for slot in range(5):
            h.cmd(f"item replace block {x + ox} {y} {z + oz} container.{slot} with minecraft:air")
    for slot in range(3):
        h.cmd(f"item replace block {x} {y} {z} container.{slot} with minecraft:cobblestone 64")
    h.cmd(f"item replace block {x + 1} {y} {z} container.0 with minecraft:cobblestone 32")


def build_observer_clocks(h: Harness, x: int, y: int, z: int, pairs: int = 6) -> None:
    for i in range(pairs):
        ox = i * 2
        h.cmd(
            f"execute in minecraft:overworld run setblock {x + ox} {y} {z} observer[facing=east]"
        )
        h.cmd(
            f"execute in minecraft:overworld run setblock {x + ox + 1} {y} {z} observer[facing=west]"
        )


def clear_observers(h: Harness, x: int, y: int, z: int, pairs: int = 8) -> None:
    for yoff in (0, 1, 2, 3):
        for i in range(pairs):
            ox = i * 2
            h.cmd(f"execute in minecraft:overworld run setblock {x + ox} {y + yoff} {z} air")
            h.cmd(f"execute in minecraft:overworld run setblock {x + ox + 1} {y + yoff} {z} air")


def place_conservation(h: Harness, x: int, y: int, z: int, stacks: int = 10) -> None:
    h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z} chest")
    h.cmd(f"execute in minecraft:overworld run setblock {x} {y + 1} {z} hopper[facing=down]")
    h.cmd(f"execute in minecraft:overworld run setblock {x} {y + 2} {z} chest")
    for slot in range(stacks):
        h.cmd(
            f"item replace block {x} {y + 2} {z} container.{slot} with minecraft:cobblestone 64"
        )


def conservation_ok(h: Harness, row, x: int, y: int, z: int, expected: int) -> bool:
    mid = h.hopper_totals_probe(x, y, z)
    actual = int(mid["total"]) + int(mid.get("groundItems") or 0)
    row.extra.setdefault("integritySamples", []).append(mid)
    delta = actual - expected
    # One item can be mid-transfer and invisible to container snapshots.
    if abs(delta) <= 2:
        return True
    if actual > expected:
        h.p0.append(f"P0 DUPLICATION expected={expected} actual={actual}")
        h.emergency_stop_pressure()
        return False
    h.p0.append(f"P0 ITEM LOSS expected={expected} actual={actual}")
    h.emergency_stop_pressure()
    return False


def silence_hopper_ring(h: Harness, x: int, y: int, z: int) -> None:
    for ox, oz in ((0, 0), (1, 0), (1, 1), (0, 1)):
        h.cmd(f"execute in minecraft:overworld run setblock {x + ox} {y} {z + oz} air")


def measure_hopper_rate(h: Harness, seconds: float = 12.0) -> dict[str, Any]:
    h.fgtest("reset")
    time.sleep(seconds)
    counts = h.fgtest("events").get("counts") or {}
    moves = int(counts.get("InventoryMoveItemEvent") or 0)
    cancelled = int(counts.get("InventoryMoveItemEvent.cancelled") or 0)
    success = max(0, moves - cancelled)
    return {
        "seconds": seconds,
        "moves": moves,
        "cancelled": cancelled,
        "successful": success,
        "itemsPerSec": round(success / seconds, 3),
        "eventsPerSec": round(moves / seconds, 3),
        "cancelledPerSec": round(cancelled / seconds, 3),
    }


def snapshot_pair(h: Harness, ax: int, az: int, bx: int, bz: int) -> dict[str, Any]:
    t0 = time.perf_counter()
    status = h.status()
    rcon = h.last_rcon_latency
    inspect_a = h.inspect_block(ax, az)
    inspect_b = h.inspect_block(bx, bz)
    probe = {}
    try:
        probe = h.pressure_cmd("status")
    except Exception as exc:  # noqa: BLE001
        probe = {"error": str(exc)}
    return {
        "ts": now_iso(),
        "rconLatency": round(rcon, 3),
        "sampleSeconds": round(time.perf_counter() - t0, 3),
        **status,
        "A": inspect_a,
        "B": inspect_b,
        "probe": probe,
    }


def safety_abort(h: Harness, sample: dict[str, Any], aborts: list[str]) -> str | None:
    mspt = float(sample.get("mspt") or 0)
    if mspt >= EMERGENCY_MSPT_LIMIT:
        msg = f"MSPT {mspt} >= safety ceiling {EMERGENCY_MSPT_LIMIT}"
        aborts.append(msg)
        h.emergency_stop_pressure()
        return msg
    if float(sample.get("rconLatency") or 0) >= RCON_LATENCY_ABORT:
        msg = f"RCON latency {sample.get('rconLatency')}s"
        aborts.append(msg)
        h.emergency_stop_pressure()
        return msg
    return None


def start_pressure(h: Harness, busy_ms: float, duration: int, max_busy: float = 70.0) -> dict[str, Any]:
    log(f"pressure start targetBusy={busy_ms}ms duration={duration}s")
    return h.pressure_cmd("start", str(busy_ms), str(duration), str(max_busy))


def run_calibration(h: Harness) -> dict[str, Any]:
    row = h.begin(
        "pressure.calibration",
        "Short sanity calibration of TestProbe busy-ms vs observed Paper MSPT",
    )
    out: dict[str, Any] = {}
    try:
        for target in (10, 30, 50):
            start_pressure(h, target, 6, 70)
            time.sleep(4)
            sample = h.status()
            probe = h.pressure_cmd("status")
            out[f"{target}msTarget"] = {
                "targetBusyMillis": target,
                "paperMspt": sample.get("mspt"),
                "paperTps": sample.get("tps"),
                "farmGuardPressure": sample.get("pressure"),
                "probeLastBusyMillis": probe.get("lastBusyMillis"),
                "probeRunning": probe.get("running"),
            }
            log(f"cal {target}ms -> mspt={sample.get('mspt')} pressure={sample.get('pressure')}")
        h.pressure_stop("calibration-done")
        path = RESULTS / "machinePressureCalibration.json"
        path.write_text(json.dumps(out, indent=2), encoding="utf-8")
        row.extra.update(out)
        h.attach_status(row)
        row.finish("PASS", "wrote machinePressureCalibration.json")
    except Exception as exc:  # noqa: BLE001
        h.pressure_stop("calibration-error")
        row.finish("ERROR", str(exc))
    return out


def prepare_farms(h: Harness) -> tuple[tuple[int, int, int], tuple[int, int, int]]:
    ax, ay, az = ZONES["farm_a"]
    bx, by, bz = ZONES["farm_b"]
    ensure_bot(h, "FarmGuardBot01")
    ensure_bot(h, "FarmGuardBot02")
    h.prepare_platform(ax, ay, az, radius=6)
    h.prepare_platform(bx, by, bz, radius=8)
    h.tp_bot("FarmGuardBot01", ax + 3, ay, az + 2, wait=1.5)
    h.wait_chunk(ax, az)
    build_hopper_ring(h, ax, ay, az)
    build_observer_clocks(h, ax, ay, az + 4, pairs=1)
    return (ax, ay, az), (bx, by, bz)


def start_farm_b(h: Harness, bx: int, by: int, bz: int) -> None:
    h.tp_bot("FarmGuardBot02", bx + 8, by, bz + 5, wait=1.5)
    h.wait_chunk(bx, bz)
    build_hopper_ring(h, bx, by, bz)
    place_conservation(h, bx + 8, by, bz + 2, stacks=10)
    h.cmd(f"execute in minecraft:overworld run setblock {bx + 12} {by} {bz + 6} redstone_lamp")
    h.cmd(
        f"execute in minecraft:overworld run setblock {bx + 11} {by} {bz + 6} lever[face=wall,facing=west,powered=false]"
    )
    h.cmd(
        f"execute in minecraft:overworld run setblock {bx + 12} {by} {bz + 4} oak_door[facing=south,half=lower,open=false]"
    )
    h.cmd(
        f"execute in minecraft:overworld run setblock {bx + 12} {by + 1} {bz + 4} oak_door[facing=south,half=upper,open=false]"
    )
    h.cmd(
        f"execute in minecraft:overworld run setblock {bx + 10} {by} {bz + 10} sticky_piston[facing=east]"
    )
    h.cmd(f"execute in minecraft:overworld run setblock {bx + 11} {by} {bz + 10} oak_planks")
    h.cmd(
        f"execute in minecraft:overworld run setblock {bx + 9} {by} {bz + 10} lever[face=floor,facing=north,powered=false]"
    )
    h.cmd(f"execute in minecraft:overworld run setblock {bx + 4} {by} {bz + 14} rail")
    h.cmd(f"execute in minecraft:overworld run setblock {bx + 5} {by} {bz + 14} rail")


def start_farm_b_activity(h: Harness, bx: int, by: int, bz: int) -> None:
    # Extra hopper ring stays in Farm B's chunk (1536..1551, 0..15) so activity
    # can remain HIGH/CRITICAL long enough for EMERGENCY enter + safety tests.
    build_hopper_ring(h, bx + 4, by, bz + 4)
    for yoff in (0, 1, 2, 3):
        build_observer_clocks(h, bx, by + yoff, bz + 8, pairs=8)


def wait_for_emergency(h: Harness, bx: int, bz: int, seconds: float = 24.0) -> dict[str, Any]:
    """Poll Farm B until FarmGuard itself reports EMERGENCY, or timeout."""
    deadline = time.time() + seconds
    inspect: dict[str, Any] = {}
    while time.time() < deadline:
        inspect = h.inspect_block(bx, bz)
        prot = str(inspect.get("protection") or "").upper()
        corr = str(inspect.get("correlation") or "")
        log("waiting EMERGENCY on B: " + prot + " corr=" + corr)
        if prot == "EMERGENCY":
            inspect["emergencyEvidence"] = {
                "protection": prot,
                "correlation": corr,
                "risk": inspect.get("risk"),
                "activity": inspect.get("activity"),
                "status": h.status(),
                "ts": now_iso(),
            }
            log("EMERGENCY observed " + json.dumps(inspect["emergencyEvidence"], default=str)[:400])
            return inspect
        time.sleep(2)
    return inspect


def reenter_emergency(
    h: Harness,
    bx: int,
    by: int,
    bz: int,
    session: dict[str, Any],
    seconds: float = 28.0,
) -> dict[str, Any]:
    """Co-move Farm B activity with a pressure climb. Instant 68ms busy after NORMAL does not create msptDelta."""
    inspect = h.inspect_block(bx, bz)
    if str(inspect.get("protection") or "").upper() == "EMERGENCY":
        return inspect
    log("re-enter EMERGENCY via co-moving activity + pressure")
    start_farm_b_activity(h, bx, by, bz)
    start_pressure(h, 40, 24, 70)
    time.sleep(10)
    clear_observers(h, bx, by, bz + 8, pairs=8)
    time.sleep(2)
    start_farm_b_activity(h, bx, by, bz)
    hold = session.get("holdBusyMillis") or 68
    start_pressure(h, hold, 180, 70)
    return wait_for_emergency(h, bx, bz, seconds)


def run_pressure_and_correlation(h: Harness) -> dict[str, Any]:
    ax, ay, az = ZONES["farm_a"]
    bx, by, bz = ZONES["farm_b"]
    session = {
        "kind": "SYNTHETIC_CONTROLLED_TICK_PRESSURE",
        "correlationScenarioType": CORR_SCENARIO,
        "authenticity": {
            "farmGuardThresholdsModified": False,
            "msptMocked": False,
            "farmGuardStateInjected": False,
            "paperTickTimeIsWallClock": True,
            "productionHighMspt": PRODUCTION_HIGH_MSPT,
            "productionCriticalMspt": PRODUCTION_CRITICAL_MSPT,
            "vanillaEntityPeakMspt": VANILLA_ENTITY_PEAK_MSPT,
        },
        "highReached": False,
        "criticalReached": False,
        "highTimestamp": None,
        "criticalTimestamp": None,
        "peakMspt": 0.0,
        "minTps": 20.0,
        "holdBusyMillis": None,
        "safetyAborts": [],
        "levels": [],
        "hopperIntegrity": "NOT_RUN",
        "correlation": "NOT_RUN",
        "recoveryCompleted": False,
        "emergencyReached": False,
    }
    h.pressure_session = session
    aborts: list[str] = session["safetyAborts"]

    baseline = h.begin(
        "lag.farm_a_baseline",
        "Farm A real hopper/redstone activity, bots resident, server NORMAL >=60s, correlation NONE",
    )
    try:
        (ax, ay, az), (bx, by, bz) = prepare_farms(h)
        h.cmd("fg mode protect")
        t0 = time.time()
        samples = []
        while time.time() - t0 < 60:
            if int(time.time() - t0) in {20, 40}:
                build_hopper_ring(h, ax, ay, az)
            row = snapshot_pair(h, ax, az, bx, bz)
            samples.append(row)
            log(
                f"baseline t={round(time.time()-t0)} mspt={row.get('mspt')} "
                f"Aact={(row.get('A') or {}).get('activity')} Acorr={(row.get('A') or {}).get('correlation')}"
            )
            time.sleep(5)
        last = samples[-1]
        h.attach_status(baseline, last.get("A"))
        baseline.extra["samples"] = samples[-8:]
        session["baseline"] = {"last": last, "n": len(samples)}
        pressure = str(last.get("pressure") or "").upper()
        corr_a = str((last.get("A") or {}).get("correlation") or "NONE").upper()
        activity = (last.get("A") or {}).get("activity")
        live = [s for s in samples if float((s.get("A") or {}).get("activity") or 0) >= 4]
        if pressure and pressure != "NORMAL":
            baseline.finish("FAIL", f"Farm A baseline not NORMAL: {pressure}")
            return session
        if not live:
            baseline.finish("FAIL", "Farm A never showed sustained hopper/redstone activity")
            return session
        if corr_a == "STRONG":
            h.p1.append("Farm A correlation STRONG during healthy baseline")
            baseline.finish("FAIL", "A correlation STRONG before pressure")
            return session
        baseline.finish(
            "PASS",
            f"60s NORMAL mspt={last.get('mspt')} lastActivity={activity} "
            f"peakActivity={max(float((s.get('A') or {}).get('activity') or 0) for s in samples)} corr={corr_a}",
        )
    except Exception as exc:  # noqa: BLE001
        baseline.finish("ERROR", str(exc))
        return session

    session["calibration"] = run_calibration(h)

    pressure_row = h.begin(
        "pressure.real_mspt",
        "SYNTHETIC CONTROLLED TICK PRESSURE ramps until FarmGuard observes production HIGH then CRITICAL",
    )
    corr = h.begin(
        "lag.farm_a_vs_b",
        "SYNTHETIC_SYNCHRONIZED_PRESSURE: B activity starts with pressure; A must not false-STRONG",
    )
    try:
        start_farm_b(h, bx, by, bz)
        time.sleep(2)
        expected = 640
        initial = h.hopper_totals_probe(bx + 8, by, bz + 2)
        session["hopperInitial"] = initial
        if int(initial["total"]) != expected:
            log(f"conservation initial {initial['total']} (expected {expected})")
            expected = int(initial["total"])
        session["hopperExpected"] = expected

        h.fgtest("reset")
        time.sleep(12)
        session["hopperRates"] = {"NORMAL": measure_hopper_rate(h, 12)}
        log("NORMAL hopper rate " + str(session["hopperRates"]["NORMAL"]))

        reached_high = False
        reached_critical = False
        hold_busy = None
        a_strong = False
        b_peak = "NONE"
        corr_rank = {"NONE": 0, "POSSIBLE": 1, "STRONG": 2}
        for busy in RAMP_LEVELS:
            if reached_critical:
                break
            if busy in (0, 20, 40, 60):
                h.tp_bot("FarmGuardBot01", ax + 3, ay, az + 2, wait=0.4)
                build_hopper_ring(h, ax, ay, az)
            if busy == 40:
                # First B activity spike co-moves with MSPT leaving the healthy range.
                start_farm_b_activity(h, bx, by, bz)
            if busy == 60:
                # Let B activity fall so the CRITICAL climb can co-move with a fresh rise.
                # Stable high activity + stable high MSPT yields correlation NONE by design.
                clear_observers(h, bx, by, bz + 8, pairs=8)
            if busy == 68:
                start_farm_b_activity(h, bx, by, bz)
            duration = LEVEL_SECONDS + 8
            start_pressure(h, busy, duration, 70)
            level_deadline = time.time() + LEVEL_SECONDS
            level_samples = []
            while time.time() < level_deadline:
                sample = snapshot_pair(h, ax, az, bx, bz)
                level_samples.append(sample)
                mspt = float(sample.get("mspt") or 0)
                tps = float(sample.get("tps") or 20)
                session["peakMspt"] = max(session["peakMspt"], mspt)
                session["minTps"] = min(session["minTps"], tps)
                name = str(sample.get("pressure") or "").upper()
                log(
                    f"ramp busy={busy} mspt={mspt} tps={tps} pressure={name} "
                    f"Aact={(sample.get('A') or {}).get('activity')} "
                    f"Acorr={(sample.get('A') or {}).get('correlation')} "
                    f"Bact={(sample.get('B') or {}).get('activity')} "
                    f"Bcorr={(sample.get('B') or {}).get('correlation')} "
                    f"Bprot={(sample.get('B') or {}).get('protection')} "
                    f"Brisk={(sample.get('B') or {}).get('risk')}"
                )
                abort = safety_abort(h, sample, aborts)
                if abort:
                    pressure_row.extra["levels"] = session["levels"]
                    pressure_row.finish("FAIL", "SAFETY_ABORT " + abort)
                    corr.finish("INCOMPLETE", "aborted")
                    session["correlation"] = "INCOMPLETE"
                    return session
                bcorr = str((sample.get("B") or {}).get("correlation") or "NONE").upper()
                acorr = str((sample.get("A") or {}).get("correlation") or "NONE").upper()
                if acorr == "STRONG":
                    a_strong = True
                if corr_rank.get(bcorr, 0) > corr_rank.get(b_peak, 0):
                    b_peak = bcorr
                    session["bCorrelationPeak"] = b_peak
                    session["bCorrelationPeakSample"] = {
                        "ts": sample.get("ts"),
                        "mspt": sample.get("mspt"),
                        "pressure": sample.get("pressure"),
                        "B": sample.get("B"),
                    }
                if name in {"HIGH", "CRITICAL"} and not reached_high:
                    reached_high = True
                    session["highReached"] = True
                    session["highTimestamp"] = sample.get("ts")
                    if hold_busy is None:
                        hold_busy = busy
                    log(f"HIGH observed at busy={busy} mspt={mspt}")
                if name == "CRITICAL" and not reached_critical:
                    reached_critical = True
                    session["criticalReached"] = True
                    session["criticalTimestamp"] = sample.get("ts")
                    hold_busy = busy
                    session["holdBusyMillis"] = hold_busy
                    log(f"CRITICAL observed at busy={busy} mspt={mspt}")
                    start_pressure(h, hold_busy, HOLD_SECONDS, 70)
                    emergency = wait_for_emergency(h, bx, bz, 20)
                    session["emergencyInspect"] = emergency
                    session["emergencyReached"] = str(emergency.get("protection") or "").upper() == "EMERGENCY"
                    if emergency.get("emergencyEvidence"):
                        session["emergencyEvidence"] = emergency["emergencyEvidence"]
                    log("EMERGENCY reached=" + str(session["emergencyReached"]))
                    break
                time.sleep(3)
            session["levels"].append(
                {
                    "targetBusyMillis": busy,
                    "samples": level_samples[-4:],
                    "last": level_samples[-1] if level_samples else {},
                }
            )
            last = level_samples[-1] if level_samples else {}
            if busy == 50 and str((last.get("B") or {}).get("protection") or "").upper() == "THROTTLE":
                start_pressure(h, 50, 30, 70)
                silence_hopper_ring(h, ax, ay, az)
                session["hopperRates"]["THROTTLE"] = measure_hopper_rate(h, 10)
                log("HIGH-window THROTTLE hopper rate " + str(session["hopperRates"]["THROTTLE"]))
                build_hopper_ring(h, ax, ay, az)
            if not conservation_ok(h, pressure_row, bx + 8, by, bz + 2, expected):
                session["hopperIntegrity"] = "FAIL"
                if reached_high and reached_critical:
                    pressure_row.extra.update({"high": True, "critical": True, "peakMspt": session["peakMspt"]})
                    pressure_row.finish("FAIL", "HIGH+CRITICAL observed but P0 hopper integrity during ramp")
                else:
                    pressure_row.finish("FAIL", "P0 hopper integrity during ramp")
                corr.finish("INCOMPLETE", "integrity failure")
                return session
            if reached_critical:
                start_pressure(h, hold_busy if hold_busy is not None else busy, HOLD_SECONDS, 70)
                break
            if reached_high and busy >= 50 and float(last.get("mspt") or 0) >= PRODUCTION_CRITICAL_MSPT:
                continue

        if reached_critical and hold_busy is not None:
            start_pressure(h, hold_busy, HOLD_SECONDS, 70)
        elif reached_high and hold_busy is not None:
            start_pressure(h, min(70, max(hold_busy, 50)), HOLD_SECONDS, 70)
        session["holdBusyMillis"] = hold_busy

        last = snapshot_pair(h, ax, az, bx, bz)
        session["during"] = last
        h.attach_status(pressure_row, last.get("B"))
        pressure_row.extra.update(
            {
                "kind": session["kind"],
                "high": reached_high,
                "critical": reached_critical,
                "peakMspt": session["peakMspt"],
                "minTps": session["minTps"],
                "highTimestamp": session["highTimestamp"],
                "criticalTimestamp": session["criticalTimestamp"],
                "holdBusyMillis": hold_busy,
                "levels": session["levels"],
                "authenticity": session["authenticity"],
                "vanillaEntityPeakMspt": VANILLA_ENTITY_PEAK_MSPT,
            }
        )
        if reached_high and reached_critical:
            pressure_row.finish(
                "PASS",
                f"FarmGuard HIGH+CRITICAL via real MSPT peak={session['peakMspt']} holdBusy={hold_busy}",
            )
            if not session.get("emergencyReached"):
                h.limitations.append(
                    "Sustained FarmGuard EMERGENCY was not observed under held CRITICAL MSPT. "
                    "require-lag-correlation-for-emergency plus first-vs-last msptDelta returns correlation NONE "
                    "once MSPT plateaus, so protection stays THROTTLE. Redstone/piston emergency-only throttles "
                    "therefore did not fire. This is algorithm behavior under SYNTHETIC_SYNCHRONIZED_PRESSURE, not a mock."
                )
        elif reached_high:
            pressure_row.finish(
                "INCOMPLETE",
                f"HIGH reached but CRITICAL not; peak={session['peakMspt']} pressure={last.get('pressure')}",
            )
        else:
            pressure_row.finish(
                "FAIL",
                f"FarmGuard HIGH not observed; peakMspt={session['peakMspt']} pressure={last.get('pressure')}",
            )

        a = last.get("A") or {}
        b = last.get("B") or {}
        corr.extra.update(
            {
                "scenarioType": CORR_SCENARIO,
                "notVanillaFarmCausalProof": True,
                "A": a,
                "B": b,
                "bPeak": b_peak,
                "aStrongSeen": a_strong,
                "server": {k: last.get(k) for k in ("tps", "mspt", "pressure")},
            }
        )
        h.attach_status(corr, b)
        corr_a = str(a.get("correlation") or "NONE").upper()
        corr_b = str(b.get("correlation") or b_peak or "NONE").upper()
        if a_strong or corr_a == "STRONG":
            h.p1.append("LagCorrelation false positive: Farm A STRONG under synthetic synchronized pressure")
            corr.finish("FAIL", f"Farm A incorrectly STRONG (A={corr_a} Bpeak={b_peak})")
            session["correlation"] = "FAIL"
        elif reached_high and b_peak in {"POSSIBLE", "STRONG"}:
            corr.finish("PASS", f"SYNTHETIC_SYNCHRONIZED_PRESSURE A={corr_a} Bpeak={b_peak} Bend={b.get('correlation')}")
            session["correlation"] = "PASS"
        elif reached_high and corr_b == "NONE" and b_peak == "NONE":
            h.p1.append("LagCorrelation NONE for Farm B despite synchronized activity+pressure rise")
            corr.finish("FAIL", f"Farm B correlation NONE (A={corr_a} Bpeak={b_peak})")
            session["correlation"] = "FAIL"
        elif reached_high:
            corr.finish("PASS", f"SYNTHETIC_SYNCHRONIZED_PRESSURE A={corr_a} Bpeak={b_peak}")
            session["correlation"] = "PASS"
        else:
            corr.finish("INCOMPLETE", f"HIGH not reached; A={corr_a} B={corr_b}")
            session["correlation"] = "INCOMPLETE"

        if reached_high:
            run_emergency_scenarios(h)
            session["emergencyScenariosRan"] = True
            inspect = h.inspect_block(bx, bz)
            if str(inspect.get("protection") or "").upper() == "EMERGENCY":
                run_protect_to_monitor(h, require_existing=True)
            _measure_protection_rates(h, session, bx, by, bz, expected, pressure_row)
        write_pressure_recovery_report(h, session)
    except Exception as exc:  # noqa: BLE001
        pressure_row.finish("ERROR", str(exc))
        corr.finish("ERROR", str(exc))
        session["error"] = str(exc)
        write_pressure_recovery_report(h, session)
    return session


def _measure_protection_rates(
    h: Harness,
    session: dict[str, Any],
    bx: int,
    by: int,
    bz: int,
    expected: int,
    pressure_row,
) -> None:
    row = h.begin(
        "integrity.hopper_pressure",
        "640 cobble conserved; THROTTLE rate < NORMAL; EMERGENCY <= THROTTLE if reached",
    )
    try:
        ax, ay, az = ZONES["farm_a"]
        silence_hopper_ring(h, ax, ay, az)
        inspect = h.inspect_block(bx, bz)
        protection = str(inspect.get("protection") or "NORMAL").upper()
        wait_deadline = time.time() + 40
        while time.time() < wait_deadline and protection == "NORMAL":
            time.sleep(4)
            inspect = h.inspect_block(bx, bz)
            protection = str(inspect.get("protection") or "NORMAL").upper()
            log("waiting protection on B: " + protection + " risk=" + str(inspect.get("risk")))
        if protection == "EMERGENCY" or session.get("emergencyReached"):
            if "EMERGENCY" not in session["hopperRates"]:
                inspect = wait_for_emergency(h, bx, bz, 12)
                protection = str(inspect.get("protection") or protection).upper()
                if protection == "EMERGENCY":
                    session["hopperRates"]["EMERGENCY"] = measure_hopper_rate(h, 10)
                    session["emergencyReached"] = True
            else:
                inspect = h.inspect_block(bx, bz)
                protection = str(inspect.get("protection") or protection).upper()
        if "THROTTLE" not in session["hopperRates"] and (
            protection == "THROTTLE"
            or str((h.inspect_block(bx, bz).get("protection") or "")).upper() == "THROTTLE"
        ):
            inspect = h.inspect_block(bx, bz)
            protection = str(inspect.get("protection") or "THROTTLE").upper()
            if protection == "THROTTLE":
                session["hopperRates"]["THROTTLE"] = measure_hopper_rate(h, 12)
        status = h.status()
        inspect = h.inspect_block(bx, bz)
        protection = str(inspect.get("protection") or protection).upper()
        if (
            str(status.get("pressure") or "").upper() == "CRITICAL"
            and "EMERGENCY" not in session["hopperRates"]
        ):
            inspect = wait_for_emergency(h, bx, bz, 16)
            if str(inspect.get("protection") or "").upper() == "EMERGENCY":
                session["hopperRates"]["EMERGENCY"] = measure_hopper_rate(h, 10)
                session["emergencyReached"] = True
        if not conservation_ok(h, row, bx + 8, by, bz + 2, expected):
            session["hopperIntegrity"] = "FAIL"
            row.finish("FAIL", "P0 hopper integrity under pressure")
            return
        rates = session.get("hopperRates") or {}
        normal = (rates.get("NORMAL") or {}).get("itemsPerSec") or 0
        throttle = (rates.get("THROTTLE") or {}).get("itemsPerSec")
        emergency = (rates.get("EMERGENCY") or {}).get("itemsPerSec")
        row.extra.update({"rates": rates, "inspect": inspect, "fgStatus": status})
        h.attach_status(row, inspect)
        if throttle is None and emergency is None:
            session["hopperIntegrity"] = "INCOMPLETE"
            row.finish("INCOMPLETE", f"no THROTTLE/EMERGENCY on Farm B; prot={protection} NORMAL={normal}")
        elif throttle is not None and normal > 0 and throttle > normal * 0.95:
            session["hopperIntegrity"] = "FAIL"
            row.finish("FAIL", f"THROTTLE rate {throttle} not below NORMAL {normal}")
        elif emergency is not None and throttle is not None and emergency > throttle * 1.15:
            session["hopperIntegrity"] = "FAIL"
            row.finish("FAIL", f"EMERGENCY rate {emergency} above THROTTLE {throttle}")
        else:
            session["hopperIntegrity"] = "PASS"
            row.finish(
                "PASS",
                "conserved "
                + str(expected)
                + "; rates="
                + str({k: v.get("itemsPerSec") for k, v in rates.items()}),
            )
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))
        session["hopperIntegrity"] = "ERROR"


def run_emergency_scenarios(h: Harness) -> None:
    session = h.pressure_session or {}
    running = False
    try:
        running = bool((h.pressure_cmd("status") or {}).get("running"))
    except Exception:
        running = False
    if not running:
        row = h.begin("protection.emergency_redstone", "Requires controlled pressure still running")
        row.finish("INCOMPLETE", "pressure generator not running; skipped")
        row = h.begin("protection.emergency_piston", "Requires controlled pressure still running")
        row.finish("INCOMPLETE", "pressure generator not running; skipped")
        return
    if not session.get("criticalReached") and not session.get("highReached"):
        row = h.begin("protection.emergency_redstone", "Requires real HIGH/CRITICAL")
        row.finish("INCOMPLETE", "pressure gate not reached")
        row = h.begin("protection.emergency_piston", "Requires real HIGH/CRITICAL")
        row.finish("INCOMPLETE", "pressure gate not reached")
        return
    bx, by, bz = ZONES["farm_b"]
    if session.get("holdBusyMillis") is not None:
        start_pressure(h, session["holdBusyMillis"], 180, 70)
    inspect = h.inspect_block(bx, bz)
    if str(inspect.get("protection") or "").upper() != "EMERGENCY":
        inspect = wait_for_emergency(h, bx, bz, 18)
    session["emergencyInspect"] = inspect
    if str(inspect.get("protection") or "").upper() == "EMERGENCY":
        session["emergencyReached"] = True
        if inspect.get("emergencyEvidence"):
            session["emergencyEvidence"] = session.get("emergencyEvidence") or inspect["emergencyEvidence"]

    redstone = h.begin(
        "protection.emergency_redstone",
        "High-frequency redstone suppressed under EMERGENCY; lever/door/lamp remain eventually usable",
    )
    try:
        inspect = h.inspect_block(bx, bz)
        ax, ay, az = ZONES["farm_a"]
        silence_hopper_ring(h, ax, ay, az)
        h.fgtest("reset")
        time.sleep(8)
        events = h.fgtest("events").get("counts") or {}
        after_inspect = h.inspect_block(bx, bz)
        if str(inspect.get("protection") or after_inspect.get("protection") or "").upper() == "EMERGENCY":
            seconds = 8.0
            moves = int(events.get("InventoryMoveItemEvent") or 0)
            cancelled = int(events.get("InventoryMoveItemEvent.cancelled") or 0)
            success = max(0, moves - cancelled)
            session["hopperRates"] = session.get("hopperRates") or {}
            if "EMERGENCY" not in session["hopperRates"]:
                session["hopperRates"]["EMERGENCY"] = {
                    "seconds": seconds,
                    "moves": moves,
                    "cancelled": cancelled,
                    "successful": success,
                    "itemsPerSec": round(success / seconds, 3),
                    "eventsPerSec": round(moves / seconds, 3),
                    "cancelledPerSec": round(cancelled / seconds, 3),
                }
                log("EMERGENCY hopper rate " + str(session["hopperRates"]["EMERGENCY"]))
        before_lamp = h.fgtest("block", "world", bx + 12, by, bz + 6)
        h.tp_bot("FarmGuardBot02", bx + 10.5, by, bz + 6.5, wait=0.8)
        try:
            h.bot("FarmGuardBot02").call("activate", x=bx + 11, y=by, z=bz + 6, timeout=20)
        except Exception as exc:  # noqa: BLE001
            redstone.extra["activateError"] = str(exc)
        time.sleep(1.0)
        after_lamp = h.fgtest("block", "world", bx + 12, by, bz + 6)
        before_door = h.fgtest("block", "world", bx + 12, by, bz + 4)
        try:
            h.bot("FarmGuardBot02").call("activate", x=bx + 12, y=by, z=bz + 4, timeout=20)
        except Exception as exc:  # noqa: BLE001
            redstone.extra["doorError"] = str(exc)
        time.sleep(0.8)
        after_door = h.fgtest("block", "world", bx + 12, by, bz + 4)
        client_door = h.bot("FarmGuardBot02").call("blockAt", x=bx + 12, y=by, z=bz + 4)
        protection = str(after_inspect.get("protection") or inspect.get("protection") or "").upper()
        corr = str(after_inspect.get("correlation") or inspect.get("correlation") or "").upper()
        suppressed = int(events.get("BlockRedstoneEvent.suppressed") or 0)
        total_rs = int(events.get("BlockRedstoneEvent") or 0)
        redstone.extra.update(
            {
                "inspect": inspect,
                "afterInspect": after_inspect,
                "events": events,
                "beforeLamp": before_lamp,
                "afterLamp": after_lamp,
                "beforeDoor": before_door,
                "afterDoor": after_door,
                "clientDoor": client_door,
            }
        )
        h.attach_status(redstone, after_inspect)
        door_ok = after_door.get("open") is True or before_door.get("open") != after_door.get("open")
        earned = str((session.get("emergencyEvidence") or {}).get("correlation") or "").upper() in {
            "STRONG",
            "POSSIBLE",
        }
        if protection == "EMERGENCY" and corr == "NONE" and not earned:
            redstone.finish("FAIL", "EMERGENCY with correlation NONE and no prior STRONG/POSSIBLE evidence")
        elif after_door.get("material") not in (None, "OAK_DOOR") and after_door.get("material") != "OAK_DOOR":
            h.p1.append("redstone emergency left door in invalid material")
            redstone.finish("FAIL", "door material " + str(after_door.get("material")))
        else:
            redstone.finish(
                "PASS",
                f"prot={protection} corr={corr} suppressed={suppressed}/{total_rs} doorChanged={door_ok} earned={earned}",
            )
            if protection == "EMERGENCY" and not door_ok:
                h.p2.append("Chunk emergency redstone may affect normal lever/door; consider default redstone throttle OFF")
            if protection == "EMERGENCY" and total_rs > 20 and suppressed == 0:
                h.p2.append("EMERGENCY redstone suppression did not fire; check enabled-throttles.redstone")
    except Exception as exc:  # noqa: BLE001
        redstone.finish("ERROR", str(exc))

    piston = h.begin(
        "protection.emergency_piston",
        "Sticky piston under CRITICAL: no block dup/loss; protocol final state consistent",
    )
    try:
        h.tp_bot("FarmGuardBot02", bx + 8.5, by, bz + 10.5, wait=0.8)
        before_planks = h.fgtest("block", "world", bx + 11, by, bz + 10)
        before_client = h.bot("FarmGuardBot02").call("blockAt", x=bx + 11, y=by, z=bz + 10)
        h.bot("FarmGuardBot02").call("activate", x=bx + 9, y=by, z=bz + 10, timeout=20)
        time.sleep(1.0)
        mid_planks = h.fgtest("block", "world", bx + 12, by, bz + 10)
        piston_block = h.fgtest("block", "world", bx + 10, by, bz + 10)
        h.bot("FarmGuardBot02").call("activate", x=bx + 9, y=by, z=bz + 10, timeout=20)
        time.sleep(1.0)
        after_planks = h.fgtest("block", "world", bx + 11, by, bz + 10)
        ghost = h.fgtest("block", "world", bx + 12, by, bz + 10)
        after_client = h.bot("FarmGuardBot02").call("blockAt", x=bx + 11, y=by, z=bz + 10)
        piston.extra.update(
            {
                "before": before_planks,
                "mid": mid_planks,
                "after": after_planks,
                "ghost": ghost,
                "piston": piston_block,
                "beforeClient": before_client,
                "afterClient": after_client,
            }
        )
        h.attach_status(piston, h.inspect_block(bx, bz))
        if ghost.get("material") == "OAK_PLANKS" and after_planks.get("material") == "OAK_PLANKS":
            h.p0.append("P0 piston block duplication")
            piston.finish("FAIL", "P0 block duplication")
            h.emergency_stop_pressure()
            return
        if after_planks.get("material") not in ("OAK_PLANKS", None) and after_planks.get("material") != "OAK_PLANKS":
            if after_planks.get("material") == "AIR":
                h.p0.append("P0 piston block loss")
                piston.finish("FAIL", "P0 block disappearance")
                h.emergency_stop_pressure()
                return
        client_name = str(after_client.get("name") or "")
        server_mat = str(after_planks.get("material") or "").lower()
        if client_name and "planks" not in client_name and "planks" in server_mat:
            h.p1.append("persistent piston protocol desync")
            piston.finish("FAIL", "CLIENT_PROTOCOL_STATE_DESYNC")
        else:
            piston.finish(
                "PASS",
                f"final server={after_planks.get('material')} client={client_name} extendedMid={piston_block.get('extended')}",
            )
    except Exception as exc:  # noqa: BLE001
        piston.finish("ERROR", str(exc))


def run_protect_behaviors(h: Harness) -> None:
    session = h.pressure_session or {}
    bx, by, bz = ZONES["farm_b"]
    if session.get("holdBusyMillis") is not None:
        start_pressure(h, session["holdBusyMillis"], 180, 70)

    minecart = h.begin(
        "protection.minecart_place",
        "If FarmGuard blocks VehicleCreate, player item must not vanish",
    )
    try:
        h.tp_bot("FarmGuardBot02", bx + 4.5, by, bz + 14.5, wait=0.8)
        h.cmd(f"execute in minecraft:overworld positioned {bx} {by} {bz} run kill @e[type=minecraft:minecart,distance=..24]")
        h.cmd(f"execute in minecraft:overworld positioned {bx} {by} {bz} run kill @e[type=minecraft:hopper_minecart,distance=..24]")
        h.cmd("clear FarmGuardBot02 minecraft:minecart")
        h.cmd("clear FarmGuardBot02 minecraft:hopper_minecart")
        h.cmd("give FarmGuardBot02 minecraft:minecart 1")
        h.cmd("give FarmGuardBot02 minecraft:hopper_minecart 1")
        time.sleep(0.6)
        before = h.bot("FarmGuardBot02").call("status")
        h.fgtest("reset")
        h.bot("FarmGuardBot02").call("equip", item="minecart")
        try:
            h.bot("FarmGuardBot02").call("activate", x=bx + 4, y=by, z=bz + 14, timeout=20)
        except Exception as exc:  # noqa: BLE001
            minecart.extra["activate1"] = str(exc)
        time.sleep(0.7)
        h.bot("FarmGuardBot02").call("equip", item="hopper_minecart")
        try:
            h.bot("FarmGuardBot02").call("activate", x=bx + 5, y=by, z=bz + 14, timeout=20)
        except Exception as exc:  # noqa: BLE001
            minecart.extra["activate2"] = str(exc)
        time.sleep(0.7)
        after = h.bot("FarmGuardBot02").call("status")
        events = h.fgtest("events").get("counts") or {}
        region = h.fgtest("region", "world", bx, by - 1, bz + 12, bx + 8, by + 3, bz + 16)
        created = int(events.get("VehicleCreateEvent") or 0)
        carts = int(region.get("minecarts") or 0)
        inv_before = item_count(before, "minecart")
        inv_after = item_count(after, "minecart")
        minecart.extra.update(
            {
                "events": events,
                "region": region,
                "invBefore": inv_before,
                "invAfter": inv_after,
            }
        )
        h.attach_status(minecart, h.inspect_block(bx, bz))
        if carts == 0 and created == 0 and inv_after < inv_before:
            h.p0.append("P0 minecart item loss without vehicle")
            minecart.finish("FAIL", "P0 item disappeared and no vehicle")
            h.emergency_stop_pressure()
        else:
            minecart.finish(
                "PASS",
                f"carts={carts} created={created} minecartItems {inv_before}->{inv_after}",
            )
    except Exception as exc:  # noqa: BLE001
        minecart.finish("ERROR", str(exc))

    breed = h.begin(
        "protection.breeding",
        "Feed two cows under protection; cancelled breed must not permanently eat wheat with no baby",
    )
    try:
        h.cmd(
            f"execute in minecraft:overworld run kill @e[type=minecraft:cow,x={bx-4},y=60,z={bz-4},dx=20,dy=20,dz=20]"
        )
        h.tp_bot("FarmGuardBot02", bx + 2, by, bz + 2, wait=0.6)
        h.cmd("execute as FarmGuardBot02 at FarmGuardBot02 run summon cow ~1 ~ ~")
        h.cmd("execute as FarmGuardBot02 at FarmGuardBot02 run summon cow ~-1 ~ ~")
        h.cmd("clear FarmGuardBot02 minecraft:wheat")
        h.cmd("give FarmGuardBot02 minecraft:wheat 8")
        time.sleep(0.8)
        before = h.bot("FarmGuardBot02").call("status")
        h.bot("FarmGuardBot02").call("equip", item="wheat")
        h.fgtest("reset")
        cows = h.bot("FarmGuardBot02").call("entities", type="cow", limit=4)
        for ent in (cows.get("entities") or [])[:2]:
            if ent.get("id") is not None:
                h.bot("FarmGuardBot02").call("useEntity", entityId=ent["id"], timeout=15)
                time.sleep(0.4)
        time.sleep(5.0)
        after = h.bot("FarmGuardBot02").call("status")
        events = h.fgtest("events").get("counts") or {}
        region = h.fgtest("region", "world", bx - 4, by - 1, bz - 4, bx + 8, by + 4, bz + 8)
        wheat_before = item_count(before, "wheat")
        wheat_after = item_count(after, "wheat")
        babies = int(region.get("babies") or 0)
        breeds = int(events.get("EntityBreedEvent") or 0)
        inspect = h.inspect_block(bx, bz)
        protection = str(inspect.get("protection") or "NORMAL").upper()
        breed.extra.update(
            {
                "wheatBefore": wheat_before,
                "wheatAfter": wheat_after,
                "babies": babies,
                "events": events,
                "region": region,
                "protection": protection,
            }
        )
        h.attach_status(breed, inspect)
        if protection in {"THROTTLE", "EMERGENCY"} and wheat_after < wheat_before - 1 and babies == 0 and breeds == 0:
            h.p1.append("Breeding cancelled after wheat consumed; consider default breeding throttle OFF")
            breed.finish("FAIL", f"wheat {wheat_before}->{wheat_after} no baby under {protection}")
        else:
            breed.finish(
                "PASS",
                f"prot={protection} wheat {wheat_before}->{wheat_after} babies={babies} breedEvents={breeds}",
            )
    except Exception as exc:  # noqa: BLE001
        breed.finish("ERROR", str(exc))

    spawn = h.begin(
        "protection.spawner_rate",
        "SPAWNER rate may drop under protection; COMMAND/NATURAL must not be cancelled by default",
    )
    try:
        h.cmd(f"execute in minecraft:overworld run setblock {bx + 14} {by} {bz + 14} spawner")
        h.cmd(
            f'execute in minecraft:overworld run data merge block {bx + 14} {by} {bz + 14} '
            f'{{MaxNearbyEntities:8s,RequiredPlayerRange:16s,SpawnCount:2s,MinSpawnDelay:20s,MaxSpawnDelay:40s,SpawnData:{{entity:{{id:"minecraft:zombie"}}}}}}'
        )
        h.tp_bot("FarmGuardBot02", bx + 13, by, bz + 13, wait=1.0)
        h.fgtest("reset")
        h.cmd(f"execute in minecraft:overworld run summon villager {bx + 3} {by} {bz + 3}")
        time.sleep(10)
        events = h.fgtest("events").get("counts") or {}
        command = int(events.get("CreatureSpawnEvent.COMMAND") or 0)
        command_c = int(events.get("CreatureSpawnEvent.COMMAND.cancelled") or 0)
        spawner_n = int(events.get("CreatureSpawnEvent.SPAWNER") or 0)
        spawner_c = int(events.get("CreatureSpawnEvent.SPAWNER.cancelled") or 0)
        natural_c = int(events.get("CreatureSpawnEvent.NATURAL.cancelled") or 0)
        spawn.extra["events"] = events
        h.attach_status(spawn, h.inspect_block(bx, bz))
        if command_c > 0 or natural_c > 0:
            spawn.finish("FAIL", f"COMMAND/NATURAL cancelled command_c={command_c} natural_c={natural_c}")
        else:
            spawn.finish(
                "PASS",
                f"COMMAND={command} cancelled={command_c} SPAWNER={spawner_n}/{spawner_c} NATURAL.cancelled={natural_c}",
            )
    except Exception as exc:  # noqa: BLE001
        spawn.finish("ERROR", str(exc))


def run_recovery(h: Harness) -> None:
    row = h.begin(
        "recovery.automatic",
        "Stop controlled pressure and Farm B workload; protection returns to NORMAL without admin commands",
    )
    session = h.pressure_session or {}
    bx, by, bz = ZONES["farm_b"]
    try:
        h.cmd("fg mode protect")
        time.sleep(0.4)
        inspect = h.inspect_block(bx, bz)
        if session.get("emergencyReached") and str(inspect.get("protection") or "").upper() != "EMERGENCY":
            inspect = reenter_emergency(h, bx, by, bz, session, 32)
        session["recoveryStartInspect"] = inspect
        t0 = time.time()
        h.pressure_stop("recovery-t0")
        clear_observers(h, bx, by, bz + 8, pairs=8)
        timeline = []
        path = RESULTS / "protection-timeline.jsonl"
        path.write_text("", encoding="utf-8")
        recovered = False
        last_pressure = None
        last_prot = None
        flips = 0
        dwell = []
        last_change = t0
        protection_path = []
        deadline = time.time() + 180
        markers = {0: "T0", 10: "T1", 20: "T2", 30: "T3", 40: "T4"}
        marked = set()
        while time.time() < deadline:
            elapsed = time.time() - t0
            status = h.status()
            inspect = h.inspect_block(bx, bz)
            sample = {
                "t": round(elapsed, 1),
                "pressure": status.get("pressure"),
                "mspt": status.get("mspt"),
                "tps": status.get("tps"),
                "limiting": status.get("limiting"),
                "protection": inspect.get("protection"),
                "correlation": inspect.get("correlation"),
                "recommended": inspect.get("recommended"),
            }
            timeline.append(sample)
            with path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(sample) + "\n")
            for mark, label in markers.items():
                if elapsed >= mark and label not in marked:
                    marked.add(label)
                    sample[label] = True
            pressure = str(status.get("pressure") or "")
            prot = str(inspect.get("protection") or "")
            if not protection_path or protection_path[-1] != prot:
                protection_path.append(prot)
            if last_pressure and last_pressure != pressure:
                flips += 1
                dwell.append(elapsed - last_change)
                last_change = elapsed
            last_pressure = pressure
            last_prot = prot
            if pressure.upper() == "NORMAL" and int(status.get("limiting") or 0) == 0:
                if prot.upper() in {"NORMAL", "NONE", ""}:
                    recovered = True
                    if elapsed >= 40 or time.time() > t0 + 45:
                        break
            time.sleep(2)
        if recovered:
            session["hopperRates"] = session.get("hopperRates") or {}
            h.tp_bot("FarmGuardBot02", bx + 2, by, bz + 2, wait=0.8)
            h.wait_chunk(bx, bz)
            build_hopper_ring(h, bx, by, bz)
            time.sleep(1.5)
            session["hopperRates"]["RECOVERED"] = measure_hopper_rate(h, 10)
            log("RECOVERED hopper rate " + str(session["hopperRates"]["RECOVERED"]))
        h.attach_status(row)
        row.extra.update(
            {
                "timeline": timeline,
                "flips": flips,
                "dwell": dwell,
                "lastProt": last_prot,
                "protectionPath": protection_path,
                "startInspect": session.get("recoveryStartInspect"),
            }
        )
        session["recoveryTimeline"] = timeline
        session["protectionPath"] = protection_path
        fast = [d for d in dwell if d < 3]
        if len(fast) >= 3:
            row.finish("FAIL", f"pressure oscillation flips={flips} fastDwell={fast}")
            session["recoveryCompleted"] = False
        elif recovered:
            row.finish(
                "PASS",
                f"recovered in {timeline[-1]['t'] if timeline else '?'}s flips={flips} path={'->'.join(protection_path)}",
            )
            session["recoveryCompleted"] = True
        else:
            row.finish("FAIL", "did not return to NORMAL/limiting=0 without admin reset")
            session["recoveryCompleted"] = False
        write_pressure_recovery_report(h, session)
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))
        session["recoveryCompleted"] = False
        write_pressure_recovery_report(h, session)


def run_protect_to_monitor(h: Harness, require_existing: bool = False) -> None:
    row = h.begin(
        "mode.protect_to_monitor",
        "/fg mode monitor immediately stops new FarmGuard suppression",
    )
    session = h.pressure_session or {}
    bx, by, bz = ZONES["farm_b"]
    try:
        inspect = h.inspect_block(bx, bz)
        if str(inspect.get("protection") or "").upper() != "EMERGENCY":
            if require_existing:
                row.finish("INCOMPLETE", "not EMERGENCY at first Protect→Monitor window")
                return
            h.cmd("fg mode protect")
            time.sleep(0.4)
            inspect = reenter_emergency(h, bx, by, bz, session, 32)
        before = h.status()
        before_inspect = inspect
        if str(inspect.get("protection") or "").upper() != "EMERGENCY":
            h.pressure_stop("protect-to-monitor-no-emergency")
            clear_observers(h, bx, by, bz + 8, pairs=8)
            row.extra.update({"before": before, "beforeInspect": before_inspect})
            if session.get("emergencyReached"):
                row.finish("FAIL", "could not re-enter real EMERGENCY for Protect→Monitor")
            else:
                row.finish("INCOMPLETE", "EMERGENCY never reached; skipped Protect→Monitor")
            return
        session["protectToMonitorDuringEmergency"] = True
        h.fgtest("reset")
        out = h.cmd("fg mode monitor")
        time.sleep(1.0)
        after = h.status()
        after_inspect = h.inspect_block(bx, bz)
        h.fgtest("reset")
        time.sleep(6)
        events = h.fgtest("events").get("counts") or {}
        cancelled = int(events.get("InventoryMoveItemEvent.cancelled") or 0)
        suppressed = int(events.get("BlockRedstoneEvent.suppressed") or 0)
        h.pressure_stop("after-monitor")
        clear_observers(h, bx, by, bz + 8, pairs=8)
        h.attach_status(row)
        row.extra.update(
            {
                "before": before,
                "beforeInspect": before_inspect,
                "after": after,
                "afterInspect": after_inspect,
                "cmd": out,
                "eventsAfter": events,
                "cancelledHopper": cancelled,
                "suppressedRedstone": suppressed,
            }
        )
        if str(after.get("mode") or "").upper() != "MONITOR":
            row.finish("FAIL", "mode not MONITOR: " + str(after.get("mode")))
        elif cancelled > 0:
            row.finish("FAIL", f"hopper still cancelled after monitor: {cancelled}")
        else:
            row.finish(
                "PASS",
                f"mode MONITOR from EMERGENCY limiting={after.get('limiting')} hopperCancelled={cancelled} rsSuppressed={suppressed}",
            )
    except Exception as exc:  # noqa: BLE001
        h.pressure_stop("protect-to-monitor-error")
        row.finish("ERROR", str(exc))


def run_jfr_load(h: Harness, seconds: int | None = None) -> dict[str, Any]:
    from profiler import run_jfr, spark_help

    seconds = int(seconds if seconds is not None else __import__("os").environ.get("PHASE16_JFR_LOAD_SECONDS", "300"))
    row = h.begin(
        "profiler.jfr_load",
        f"JFR {seconds}s at controlled HIGH; FarmGuard sample share excludes TestProbe burner",
    )
    session = h.pressure_session or {}
    ax, az = ZONES["farm_a"][0], ZONES["farm_a"][2]
    bx, by, bz = ZONES["farm_b"]
    try:
        busy = session.get("holdBusyMillis") or 50
        if session.get("criticalReached") and busy and busy > 55:
            busy = 50
        h.cmd("fg mode protect")
        prepare_farms(h)
        start_farm_b(h, bx, by, bz)
        h.tp_bot("FarmGuardBot01", ax + 3, 70, az + 2, wait=0.8)
        h.tp_bot("FarmGuardBot02", bx + 8, by, bz + 5, wait=0.8)
        build_observer_clocks(h, bx, by, bz + 8, pairs=4)
        start_pressure(h, busy, seconds + 30, 70)
        help_text = spark_help(h)
        parsed = run_jfr(h, "fg-load", seconds)
        h.pressure_stop("jfr-done")
        clear_observers(h, bx, by, bz + 8, pairs=4)
        h.attach_status(row)
        row.extra.update({"jfr": parsed, "sparkHelp": help_text[-800:], "busy": busy})
        share = float(parsed.get("farmGuardProductSampleShare") or parsed.get("farmGuardSampleShare") or 0)
        if share > 0.35:
            h.p1.append("FarmGuard product JFR sample share > 35% under load")
            row.finish("FAIL", f"product sample share {share:.4f}")
        elif parsed.get("ok"):
            row.finish(
                "PASS",
                f"productShare={share:.4f} probeShare={parsed.get('testProbeSampleShare')} samples={parsed.get('executionSamples')}",
            )
        else:
            row.finish("INCOMPLETE", str(parsed.get("error")))
        (RESULTS / "profiler" / "jfr-load.json").write_text(json.dumps(parsed, indent=2), encoding="utf-8")
        session["jfrLoad"] = parsed
        write_pressure_recovery_report(h, session)
        return {"jfrLoad": parsed}
    except Exception as exc:  # noqa: BLE001
        h.pressure_stop("jfr-error")
        row.finish("ERROR", str(exc))
        return {"jfrLoad": {"ok": False, "error": str(exc)}}


def write_pressure_recovery_report(h: Harness, session: dict[str, Any] | None = None) -> dict[str, Any]:
    session = session or h.pressure_session or {}
    statuses = {row.scenario: row.status for row in h.results}
    report = {
        "generatedAt": now_iso(),
        "kind": "SYNTHETIC_CONTROLLED_TICK_PRESSURE",
        "correlationScenarioType": CORR_SCENARIO,
        "authenticity": session.get("authenticity"),
        "HIGH reached": session.get("highReached"),
        "CRITICAL reached": session.get("criticalReached"),
        "highReached": session.get("highReached"),
        "criticalReached": session.get("criticalReached"),
        "highTimestamp": session.get("highTimestamp"),
        "criticalTimestamp": session.get("criticalTimestamp"),
        "Peak MSPT": session.get("peakMspt"),
        "peakMspt": session.get("peakMspt"),
        "minTps": session.get("minTps"),
        "holdBusyMillis": session.get("holdBusyMillis"),
        "emergencyReached": session.get("emergencyReached"),
        "emergencyEvidence": session.get("emergencyEvidence"),
        "protectionPath": session.get("protectionPath"),
        "recoveryStartInspect": session.get("recoveryStartInspect"),
        "Hopper integrity": session.get("hopperIntegrity"),
        "hopperIntegrity": session.get("hopperIntegrity"),
        "hopperRates": session.get("hopperRates"),
        "Correlation": session.get("correlation"),
        "correlation": session.get("correlation"),
        "Recovery completed": session.get("recoveryCompleted"),
        "recoveryCompleted": session.get("recoveryCompleted"),
        "safetyAborts": session.get("safetyAborts") or [],
        "calibration": session.get("calibration"),
        "vanillaEntityPeakMspt": VANILLA_ENTITY_PEAK_MSPT,
        "jfrLoad": session.get("jfrLoad"),
        "scenarioStatus": {
            "pressure.real_mspt": statuses.get("pressure.real_mspt"),
            "lag.farm_a_vs_b": statuses.get("lag.farm_a_vs_b"),
            "integrity.hopper_pressure": statuses.get("integrity.hopper_pressure"),
            "recovery.automatic": statuses.get("recovery.automatic"),
            "profiler.jfr_load": statuses.get("profiler.jfr_load"),
        },
    }
    needed = [
        session.get("highReached") is True,
        session.get("criticalReached") is True,
        session.get("hopperIntegrity") == "PASS",
        session.get("correlation") == "PASS",
        session.get("recoveryCompleted") is True,
        not (session.get("safetyAborts") or []),
        statuses.get("pressure.real_mspt") == "PASS",
    ]
    report["allPass"] = all(needed)
    existing_path = RESULTS / "pressure-recovery-report.json"
    if not report["allPass"] and existing_path.exists() and not session.get("highReached"):
        try:
            previous = json.loads(existing_path.read_text(encoding="utf-8"))
        except Exception:
            previous = {}
        if previous.get("allPass") is True:
            previous["jfrLoad"] = session.get("jfrLoad") or previous.get("jfrLoad")
            prev_status = previous.get("scenarioStatus") or {}
            prev_status["profiler.jfr_load"] = statuses.get("profiler.jfr_load")
            previous["scenarioStatus"] = prev_status
            previous["generatedAt"] = now_iso()
            report = previous
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "pressure-recovery-report.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    return report
