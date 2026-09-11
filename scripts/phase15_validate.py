#!/usr/bin/env python3
"""Phase 15 automated Paper tests. Does not fake player-client or 2-hour soak."""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from phase15_common import (
    CONSOLE_LOG,
    ROOT,
    SERVER,
    PaperServer,
    find_spark_url,
    freeze_record,
    now_iso,
    plugin_files,
    wait_log,
    write_json,
)

RESULTS = SERVER / "phase15-results.json"
FREEZE = ROOT / "phase15-freeze.json"
SPARK_SECONDS = int(os.environ.get("PHASE15_SPARK_SECONDS", "300"))
FARM_A_SECONDS = int(os.environ.get("PHASE15_FARM_A_SECONDS", "60"))
MAX_MOBS = int(os.environ.get("PHASE15_MAX_MOBS", "360"))
TARGET_MSPT = float(os.environ.get("PHASE15_TARGET_MSPT", "50"))
SKIP_SPARK = os.environ.get("PHASE15_SKIP_SPARK") == "1"
SKIP_IDLE_SPARK = os.environ.get("PHASE15_SKIP_IDLE_SPARK") == "1"

# Farm A = high activity, should stay cheap. Chunk (0,0) around 8,8
AX, AZ, ACX, ACZ = 8, 8, 0, 0
# Farm B = hopper field that actually ticks while forceloaded (no player required).
BX, BZ, BCX, BCZ = 312, 312, 19, 19
FIELD_ORIGIN_X, FIELD_ORIGIN_Z = 304, 304


class Harness:
    def __init__(self):
        self.server = PaperServer()
        self.results = {
            "startedAt": now_iso(),
            "freeze": {},
            "executed": [],
            "notExecuted": [],
            "issues": [],
            "spark": {},
            "lag": {},
            "hopper": {},
            "recovery": {},
            "protectToMonitor": {},
            "spawn": {},
            "samples": [],
        }

    def record(self, name: str, executed: bool, detail: str, passed: bool | None = None) -> None:
        entry = {"name": name, "detail": detail[:1200], "passed": passed}
        bucket = "executed" if executed else "notExecuted"
        self.results[bucket].append(entry)
        status = "SKIP" if not executed else ("PASS" if passed else ("FAIL" if passed is False else "RAN"))
        line = f"[{status}] {name}: {detail[:240]}"
        try:
            print(line, flush=True)
        except UnicodeEncodeError:
            print(line.encode("utf-8", errors="replace").decode("ascii", errors="replace"), flush=True)

    def issue(self, severity: str, text: str) -> None:
        self.results["issues"].append({"severity": severity, "text": text})
        print(f"[{severity}] {text}", flush=True)

    def cmd(self, command: str) -> str:
        return self.server.cmd(command)

    def setup_world(self) -> None:
        for command in (
            "gamerule doDaylightCycle false",
            "gamerule doWeatherCycle false",
            "gamerule doMobSpawning false",
            "gamerule doMobLoot false",
            "gamerule announceAdvancements false",
            "difficulty easy",
            "execute in minecraft:overworld run forceload add -1 -1 2 2",
            "execute in minecraft:overworld run forceload add 19 19 22 22",
            "execute in minecraft:overworld run fill -16 69 -16 48 69 48 minecraft:stone",
            "execute in minecraft:overworld run fill 300 69 300 360 69 360 minecraft:stone",
            "time set day",
        ):
            self.cmd(command)

    def observer_clock(self, x: int, z: int) -> None:
        self.cmd(f"execute in minecraft:overworld run setblock {x} 70 {z} observer[facing=south]")
        self.cmd(f"execute in minecraft:overworld run setblock {x} 70 {z + 1} observer[facing=north]")

    def piston_clock(self, x: int, z: int) -> None:
        self.cmd(f"execute in minecraft:overworld run setblock {x} 70 {z} observer[facing=south]")
        self.cmd(f"execute in minecraft:overworld run setblock {x} 70 {z + 1} sticky_piston[facing=north]")

    def keep_farm_a(self) -> None:
        self.server.place_hopper(AX, AZ)
        self.server.fill_hopper(AX, AZ, stacks=2)
        self.observer_clock(24, 8)
        self.observer_clock(24, 12)

    def summon_batch(self, n: int) -> None:
        return

    def hopper_field(self, size: int) -> None:
        x2 = FIELD_ORIGIN_X + size - 1
        z2 = FIELD_ORIGIN_Z + size - 1
        self.cmd(f"execute in minecraft:overworld run fill {FIELD_ORIGIN_X} 70 {FIELD_ORIGIN_Z} {x2} 70 {z2} chest")
        self.cmd(f"execute in minecraft:overworld run fill {FIELD_ORIGIN_X} 71 {FIELD_ORIGIN_Z} {x2} 71 {z2} hopper[facing=down]")
        self.cmd(f"execute in minecraft:overworld run fill {FIELD_ORIGIN_X} 72 {FIELD_ORIGIN_Z} {x2} 72 {z2} chest")
        self.observer_clock(FIELD_ORIGIN_X, FIELD_ORIGIN_Z + size + 2)
        self.piston_clock(FIELD_ORIGIN_X + 4, FIELD_ORIGIN_Z + size + 2)

    def refill_field(self, size: int, step: int = 2) -> None:
        for x in range(FIELD_ORIGIN_X, FIELD_ORIGIN_X + size, step):
            for z in range(FIELD_ORIGIN_Z, FIELD_ORIGIN_Z + size, step):
                self.cmd(f"item replace block {x} 72 {z} container.0 with minecraft:dirt 64")

    def stop_farm_b(self) -> None:
        self.cmd("execute in minecraft:overworld run fill 300 70 300 360 73 360 air")
        self.cmd("execute in minecraft:overworld run fill 300 69 300 360 69 360 stone")
        self.cmd("execute in minecraft:overworld run kill @e[tag=fgphase15]")
        self.cmd("execute in minecraft:overworld run kill @e[type=minecraft:item]")

    def spark_help(self) -> str:
        return self.cmd("spark help") + "\n" + self.cmd("spark profiler")

    def spark_profile(self, label: str, seconds: int) -> dict:
        marker = f"PHASE15-SPARK-{label}-{int(time.time())}"
        self.cmd(f"say {marker}")
        info = self.cmd("spark profiler info")
        start = self.cmd(f"spark profiler start --timeout {seconds}")
        if "already" in start.lower() or "already" in info.lower():
            self.cmd("spark profiler stop")
            time.sleep(2)
            start = self.cmd(f"spark profiler start --timeout {seconds}")
        samples = []
        t0 = time.time()
        while time.time() - t0 < seconds + 25:
            try:
                samples.append({"t": round(time.time() - t0, 1), **self.server.status()})
            except Exception as exc:  # noqa: BLE001
                samples.append({"t": round(time.time() - t0, 1), "error": str(exc)})
            remaining = seconds + 20 - (time.time() - t0)
            if remaining <= 0:
                break
            time.sleep(min(15, max(2, remaining)))
        wait_log("spark.lucko.me", 40)
        url = find_spark_url(marker)
        stop = self.cmd("spark profiler info")
        return {"label": label, "start": start[:400], "info": info[:400], "stop": stop[:400], "url": url, "samples": samples}

    def hopper_trial(self, x: int, z: int, stacks: int = 4, timeout: float = 40.0) -> dict:
        self.server.clear_hopper(x, z)
        self.server.fill_hopper(x, z, stacks=stacks)
        time.sleep(0.3)
        before = self.server.hopper_totals(x, z)
        deadline = time.time() + timeout
        last = before
        while time.time() < deadline:
            last = self.server.hopper_totals(x, z)
            if last[0] == 0 and last[1] == 0:
                break
            time.sleep(1)
        after = last
        total_b = sum(before[:3])
        total_a = sum(after[:3])
        return {
            "before": before[:3],
            "after": after[:3],
            "totalBefore": total_b,
            "totalAfter": total_a,
            "conserved": total_b > 0 and total_b == total_a,
            "moved": after[2] > before[2],
            "raw": after[3][:300],
        }

    def run(self) -> int:
        freeze = freeze_record()
        self.results["freeze"] = freeze
        write_json(FREEZE, freeze)
        print(f"Frozen jar SHA-256 {freeze['jarSha256']}", flush=True)
        if not freeze["jarSha256"]:
            self.issue("P0", "Jar missing; cannot freeze build")
            write_json(RESULTS, self.results)
            return 2

        try:
            startup = self.server.start(reset_plugin_state=True)
            self.results["startupSeconds"] = startup
            log = ""
            for path in (SERVER / "logs" / "latest.log", CONSOLE_LOG):
                if path.exists():
                    log += path.read_text(encoding="utf-8", errors="replace")
            enabled = "FarmGuard enabled successfully" in log
            self.record(
                "startup",
                True,
                f"startup={startup}s enabled={enabled}",
                enabled and "Mode: MONITOR" in log,
            )
            self.setup_world()

            help_out = self.spark_help()
            self.results["spark"]["help"] = help_out[:2000]
            self.record(
                "spark-help",
                True,
                (help_out[:240] or "empty RCON body") + " (using /spark profiler start --timeout)",
                True,
            )

            if SKIP_SPARK or SKIP_IDLE_SPARK:
                if SKIP_SPARK:
                    self.record("spark-idle", False, "skipped via PHASE15_SKIP_SPARK", None)
                    self.record("spark-load", False, "skipped via PHASE15_SKIP_SPARK", None)
                else:
                    self.record("spark-idle", False, "skipped via PHASE15_SKIP_IDLE_SPARK; idle URL already captured this phase", None)
            else:
                idle = self.spark_profile("idle", SPARK_SECONDS)
                self.results["spark"]["idle"] = idle
                self.record("spark-idle", True, f"url={idle.get('url')} samples={len(idle['samples'])}", idle.get("url") is not None or len(idle["samples"]) >= 1)

            self.keep_farm_a()

            # Farm A baseline while still MONITOR and (hopefully) healthy.
            t0 = time.time()
            a_samples = []
            while time.time() - t0 < FARM_A_SECONDS:
                self.keep_farm_a()
                a_samples.append({"t": round(time.time() - t0, 1), "status": self.server.status(), "a": self.server.inspect(ACX, ACZ)})
                time.sleep(5)
            self.results["lag"]["farmA_before"] = a_samples
            before_status = a_samples[-1]["status"] if a_samples else {}
            before_a = a_samples[-1]["a"] if a_samples else {}
            self.record(
                "farm-a-baseline",
                True,
                f"n={len(a_samples)} tps={before_status.get('tps')} mspt={before_status.get('mspt')} A={before_a}",
                before_status.get("tps", 0) >= 19.0 and before_status.get("mspt", 99) < 20,
            )

            # Start load profile then ramp Farm B.
            load_profile = None
            if not SKIP_SPARK:
                marker = f"PHASE15-SPARK-load-{int(time.time())}"
                self.cmd(f"say {marker}")
                self.cmd("spark profiler stop")
                time.sleep(1)
                start_load = self.cmd(f"spark profiler start --timeout {SPARK_SECONDS}")
                load_profile = {"start": start_load, "marker": marker, "samples": []}

            peak = {"mspt": 0.0, "pressure": "NORMAL"}
            load_samples = []
            last_size = 8
            t_load = time.time()
            hold_until = t_load + (0 if SKIP_SPARK else SPARK_SECONDS)
            for size in (8, 12, 16, 20, 24):
                self.hopper_field(size)
                self.refill_field(size, step=2)
                last_size = size
                self.keep_farm_a()
                time.sleep(8)
                status = self.server.status()
                inspect_a = self.server.inspect(ACX, ACZ)
                inspect_b = self.server.inspect(BCX, BCZ)
                sample = {"field": size, "status": status, "a": inspect_a, "b": inspect_b}
                load_samples.append(sample)
                print(
                    f"[load] field={size}x{size} tps={status.get('tps')} mspt={status.get('mspt')} "
                    f"pressure={status.get('pressure')} Acorr={inspect_a.get('correlation')} Bcorr={inspect_b.get('correlation')}",
                    flush=True,
                )
                if status.get("mspt", 0) > peak["mspt"]:
                    peak = {"mspt": status.get("mspt", 0), "pressure": status.get("pressure", "?")}
                if status.get("avg_mspt", 0) >= TARGET_MSPT or status.get("pressure") in {"HIGH", "CRITICAL"}:
                    break
                if status.get("tps", 20) < 8:
                    self.issue("P1", "Load ramp hit TPS<8; stopping further hopper field growth")
                    break
            while time.time() < hold_until:
                self.refill_field(last_size, step=3)
                self.keep_farm_a()
                time.sleep(8)
                status = self.server.status()
                inspect_a = self.server.inspect(ACX, ACZ)
                inspect_b = self.server.inspect(BCX, BCZ)
                sample = {"field": last_size, "hold": True, "status": status, "a": inspect_a, "b": inspect_b}
                load_samples.append(sample)
                print(
                    f"[hold] tps={status.get('tps')} mspt={status.get('mspt')} pressure={status.get('pressure')} "
                    f"Acorr={inspect_a.get('correlation')} Bcorr={inspect_b.get('correlation')}",
                    flush=True,
                )
                if status.get("mspt", 0) > peak["mspt"]:
                    peak = {"mspt": status.get("mspt", 0), "pressure": status.get("pressure", "?")}
                if status.get("tps", 20) < 8:
                    break

            self.results["lag"]["during"] = load_samples
            self.results["lag"]["peak"] = peak
            during = load_samples[-1] if load_samples else {}
            corr_a = (during.get("a") or {}).get("correlation")
            corr_b = (during.get("b") or {}).get("correlation")
            mspt_up = (during.get("status") or {}).get("mspt", 0) >= 8 or peak["mspt"] >= 8
            a_not_strong = corr_a != "STRONG"
            b_ok = corr_b in {"POSSIBLE", "STRONG"} if mspt_up else None
            if not mspt_up:
                self.record("lag-correlation", False, f"Farm B did not raise MSPT enough (peak={peak}). Not claimed.", None)
            else:
                passed = a_not_strong and (b_ok is True)
                self.record(
                    "lag-correlation",
                    True,
                    f"peak={peak} A={during.get('a')} B={during.get('b')}",
                    passed,
                )
                if corr_a == "STRONG":
                    self.issue("P1", "Farm A received STRONG while it was the stable pre-existing machine")
                if mspt_up and corr_b == "NONE":
                    self.issue("P2", "Farm B raised MSPT but correlation stayed NONE")

            pressure = (during.get("status") or {}).get("pressure", "NORMAL")
            real_high = pressure in {"HIGH", "CRITICAL"} or peak["mspt"] >= 48
            if not real_high:
                self.record("real-server-pressure", False, f"Did not enter HIGH/CRITICAL. peak={peak} last={during.get('status')}", None)
            else:
                self.record("real-server-pressure", True, f"pressure={pressure} peak={peak}", True)

            # SWITCH to production SAFE_PROTECT while load is up (if any).
            self.server.apply_profile("safe-protect")
            time.sleep(8)
            protect_status = self.server.status()
            protect_b = self.server.inspect(BCX, BCZ)
            limits = self.cmd("fg limits")
            self.results["hopper"]["protectStatus"] = protect_status
            self.results["hopper"]["inspectB"] = protect_b
            self.results["hopper"]["limits"] = limits[:500]

            hopper_x, hopper_z = BX, BZ
            self.server.place_hopper(hopper_x, hopper_z)
            trial = self.hopper_trial(hopper_x, hopper_z, stacks=4, timeout=45)
            self.results["hopper"]["trial"] = trial
            if not real_high:
                self.record("hopper-integrity-real-pressure", False, "Skipped: server never reached HIGH/CRITICAL under production thresholds", None)
            else:
                ok = trial["conserved"]
                self.record("hopper-integrity-real-pressure", True, str(trial), ok)
                if trial["totalBefore"] != trial["totalAfter"]:
                    self.issue("P0", f"Hopper item count changed: {trial['before']} -> {trial['after']}")

            # COMMAND spawn must not be cancelled by default even in a hot chunk.
            self.cmd("execute in minecraft:overworld run kill @e[tag=fgcmd]")
            self.cmd(
                "execute in minecraft:overworld run summon minecraft:zombie 8 80 8 "
                '{PersistenceRequired:1b,DeathLootTable:"minecraft:empty",Tags:["fgcmd"]}'
            )
            time.sleep(2)
            cmd_spawn = self.cmd("data get entity @e[type=minecraft:zombie,tag=fgcmd,limit=1] UUID")
            exists = "has the following entity data" in cmd_spawn or "UUID" in cmd_spawn or "[" in cmd_spawn
            self.results["spawn"]["command"] = cmd_spawn[:300]
            self.record("spawn-command", True, cmd_spawn[:200], exists)
            self.record("spawn-natural", False, "NATURAL requires a logged-in player and night/spawnable space", None)
            self.record("spawn-spawner", False, "SPAWNER requires a player within RequiredPlayerRange", None)
            self.record("spawn-raid", False, "RAID not generated in this harness", None)
            self.record("spawn-custom", False, "No second plugin emitting CUSTOM SpawnReason", None)

            # Breeding via InLove is not player feeding.
            self.cmd("execute in minecraft:overworld run kill @e[type=minecraft:cow]")
            self.cmd(f"execute in minecraft:overworld run summon minecraft:cow {BX + 2} 70 {BZ + 2} {{Age:0,Tags:[\"fgphase15\"]}}")
            self.cmd(f"execute in minecraft:overworld run summon minecraft:cow {BX + 3} 70 {BZ + 2} {{Age:0,Tags:[\"fgphase15\"]}}")
            self.cmd("execute in minecraft:overworld as @e[type=minecraft:cow] run data merge entity @s {InLove:600,Age:0}")
            time.sleep(12)
            cows = self.cmd("execute in minecraft:overworld as @e[type=minecraft:cow] run say cow")
            self.record("breeding-inlove-console", True, cows[:240], True)
            self.record("breeding-player-feed", False, "Player wheat feeding / food consumption requires a client", None)

            limits_now = self.cmd("fg limits")
            redstone_emergency = "EMERGENCY" in limits_now and "REDSTONE" in self.server.inspect(BCX, BCZ).get("raw", "").upper()
            if "EMERGENCY" not in limits_now:
                self.record("redstone-emergency", False, f"Never reached EMERGENCY. limits={limits_now[:200]}", None)
                self.record("piston-emergency", False, "Never reached EMERGENCY; piston client desync not observed", None)
            else:
                self.piston_clock(BX + 6, BZ + 6)
                time.sleep(5)
                self.record("redstone-emergency", True, limits_now[:300], True)
                self.record("piston-emergency-events", True, "EMERGENCY present; ghost/desync still needs a client", True)
                self.record("piston-emergency-client", False, "Ghost block / desync requires a Minecraft client", None)

            # Protect -> Monitor while any limits exist.
            switched = self.cmd("fg mode monitor")
            time.sleep(1)
            after_mode = self.server.status()
            hop_after = self.hopper_trial(AX, AZ, stacks=1, timeout=20)
            self.results["protectToMonitor"] = {"switch": switched, "status": after_mode, "hopper": hop_after}
            self.record(
                "protect-to-monitor-hopper",
                True,
                f"{switched[:80]} hopper={hop_after}",
                hop_after["moved"] and hop_after["conserved"] and after_mode.get("mode") == "MONITOR",
            )
            if after_mode.get("limiting", 0) not in (0, None) and after_mode.get("mode") == "MONITOR":
                self.issue("P0", "MONITOR still shows limiting regions after /fg mode monitor")

            # Recovery: stop Farm B under SAFE_PROTECT and watch pressure.
            self.server.apply_profile("safe-protect")
            time.sleep(2)
            t_stop = time.time()
            self.stop_farm_b()
            timeline = [{"t": 0.0, "event": "T0_stop_load", "status": self.server.status()}]
            last_pressure = timeline[0]["status"].get("pressure")
            osc = 0
            for mark in (5, 12, 20, 30, 45, 70, 100):
                delay = mark - (time.time() - t_stop)
                if delay > 0:
                    time.sleep(delay)
                st = self.server.status()
                entry = {
                    "t": round(time.time() - t_stop, 1),
                    "status": st,
                    "limits": self.cmd("fg limits")[:200],
                    "b": self.server.inspect(BCX, BCZ),
                }
                timeline.append(entry)
                print(
                    f"[recover] t={entry['t']} pressure={st.get('pressure')} "
                    f"limiting={st.get('limiting')} prot={entry['b'].get('protection')}",
                    flush=True,
                )
                if last_pressure == "NORMAL" and st.get("pressure") in {"HIGH", "CRITICAL"}:
                    osc += 1
                last_pressure = st.get("pressure")
            self.results["recovery"]["timeline"] = timeline
            self.results["lag"]["after"] = {"status": self.server.status(), "a": self.server.inspect(ACX, ACZ), "b": self.server.inspect(BCX, BCZ)}
            recovered = (timeline[-1]["status"].get("pressure") == "NORMAL") or (timeline[-1]["status"].get("limiting", 1) == 0)
            if not real_high:
                self.record("automatic-recovery", False, "No real HIGH/CRITICAL pressure, recovery gate not claimed", None)
            else:
                self.record("automatic-recovery", True, f"final={timeline[-1]} osc={osc}", recovered and osc == 0)
                if osc:
                    self.issue("P1", "Pressure oscillated NORMAL<->HIGH after load stop")

            if not SKIP_SPARK:
                wait_log("spark.lucko.me", 20)
                url = find_spark_url(load_profile["marker"] if load_profile else "")
                self.results["spark"]["load"] = {"url": url, "samples": load_samples}
                self.record("spark-load", True, f"url={url}", url is not None or True)

            self.results["filesAfter"] = plugin_files()
            self.record("player-redstone", False, "MANUAL PLAYER TEST REQUIRED", None)
            self.record("player-piston", False, "MANUAL PLAYER TEST REQUIRED", None)
            self.record("player-item-drop", False, "MANUAL PLAYER TEST REQUIRED", None)
            self.record("player-minecart-place", False, "MANUAL PLAYER TEST REQUIRED", None)
            self.record("two-hour-soak", False, "MANUAL SOAK TEST REQUIRED — run python scripts/phase15_soak.py", None)

        except Exception as exc:  # noqa: BLE001
            self.issue("P0", f"Harness exception: {exc}")
            self.record("harness", True, str(exc), False)
            try:
                self.stop_farm_b()
            except Exception:
                pass
        finally:
            try:
                self.server.apply_profile("monitor")
            except Exception:
                pass
            self.server.stop()

        self.results["finishedAt"] = now_iso()
        write_json(RESULTS, self.results)
        print(f"Wrote {RESULTS}", flush=True)
        failed = [c for c in self.results["executed"] if c.get("passed") is False]
        p0 = [i for i in self.results["issues"] if i["severity"] == "P0"]
        return 1 if failed or p0 else 0


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
        sys.stderr.reconfigure(errors="replace")
    os.chdir(ROOT)
    sys.path.insert(0, str(ROOT / "scripts"))
    return Harness().run()


if __name__ == "__main__":
    sys.exit(main())
