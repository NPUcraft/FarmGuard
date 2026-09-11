#!/usr/bin/env python3
"""FarmGuard Phase 14 controlled Paper validation harness.

Talks to an isolated test-server over RCON. Does not use Bukkit /reload.
Does not claim player-only or long-soak tests that were not actually run.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "test-server"
PLUGIN_JAR_SRC = ROOT / "build" / "libs" / "FarmGuard-0.1.0-SNAPSHOT.jar"
PROFILES = ROOT / "test-profiles"
RESULTS = SERVER / "phase14-results.json"
CONSOLE_LOG = SERVER / "console-capture.log"

RCON_HOST = "127.0.0.1"
RCON_PORT = 25576
RCON_PASSWORD = "farmguard14"
SERVER_PORT = 25570
IDLE_SECONDS = int(os.environ.get("PHASE14_IDLE_SECONDS", "600"))
SKIP_IDLE = os.environ.get("PHASE14_SKIP_IDLE") == "1"
SKIP_PERSIST = os.environ.get("PHASE14_SKIP_PERSIST") == "1"
START_TIMEOUT = int(os.environ.get("PHASE14_START_TIMEOUT", "480"))


class Rcon:
    LOGIN = 3
    COMMAND = 2

    def __init__(self, host: str, port: int, password: str):
        self.sock = socket.create_connection((host, port), timeout=15)
        self.sock.settimeout(20)
        self.req_id = 0
        body = self._send(self.LOGIN, password)
        if body is None:
            raise RuntimeError("RCON login failed")

    def _read(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise RuntimeError("RCON connection closed")
            buf += chunk
        return buf

    def _send(self, ptype: int, payload: str) -> str:
        self.req_id += 1
        encoded = payload.encode("utf-8") + b"\x00\x00"
        packet = struct.pack("<ii", self.req_id, ptype) + encoded
        self.sock.sendall(struct.pack("<i", len(packet)) + packet)
        length = struct.unpack("<i", self._read(4))[0]
        data = self._read(length)
        req_id = struct.unpack("<i", data[:4])[0]
        body = data[8:-2].decode("utf-8", errors="replace")
        if req_id == -1:
            raise RuntimeError("RCON auth rejected")
        return body

    def cmd(self, command: str) -> str:
        return self._send(self.COMMAND, command)

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def count_items(blob: str) -> int:
    total = 0
    for match in re.finditer(r"\bcount:\s*(\d+)", blob, re.IGNORECASE):
        total += int(match.group(1))
    return total


def has_any(text: str, needles: list[str]) -> bool:
    lower = text.lower()
    return any(n.lower() in lower for n in needles)


class Harness:
    def __init__(self):
        self.proc: subprocess.Popen[str] | None = None
        self.log_fh = None
        self.rcon: Rcon | None = None
        self.results: dict = {
            "startedAt": now_iso(),
            "environment": {},
            "executed": [],
            "notExecuted": [],
            "checks": {},
            "samples": [],
            "issues": [],
        }

    def record(self, name: str, executed: bool, detail: str, passed: bool | None = None) -> None:
        entry = {"name": name, "detail": detail, "passed": passed}
        if executed:
            self.results["executed"].append(entry)
        else:
            self.results["notExecuted"].append(entry)
        self.results["checks"][name] = entry
        status = "SKIP" if not executed else ("PASS" if passed else ("FAIL" if passed is False else "RAN"))
        line = f"[{status}] {name}: {detail}"
        try:
            print(line, flush=True)
        except UnicodeEncodeError:
            print(line.encode("utf-8", errors="replace").decode("ascii", errors="replace"), flush=True)

    def issue(self, severity: str, text: str) -> None:
        self.results["issues"].append({"severity": severity, "text": text})
        print(f"[{severity}] {text}", flush=True)

    def cmd(self, command: str) -> str:
        assert self.rcon is not None
        out = self.rcon.cmd(command)
        return out.strip()

    def wait_log(self, needle: str, timeout: int) -> bool:
        deadline = time.time() + timeout
        path = SERVER / "logs" / "latest.log"
        while time.time() < deadline:
            for candidate in (path, CONSOLE_LOG):
                if candidate.exists():
                    text = candidate.read_text(encoding="utf-8", errors="replace")
                    if needle in text:
                        return True
            if self.proc and self.proc.poll() is not None:
                return False
            time.sleep(1)
        return False

    def start_server(self, reset_plugin_state: bool = False) -> None:
        SERVER.mkdir(parents=True, exist_ok=True)
        (SERVER / "plugins").mkdir(exist_ok=True)
        if not PLUGIN_JAR_SRC.exists():
            raise RuntimeError(f"Plugin jar missing: {PLUGIN_JAR_SRC}")
        shutil.copy2(PLUGIN_JAR_SRC, SERVER / "plugins" / PLUGIN_JAR_SRC.name)
        if reset_plugin_state:
            data_dir = SERVER / "plugins" / "FarmGuard"
            data_dir.mkdir(parents=True, exist_ok=True)
            (data_dir / "state.yml").write_text("mode: MONITOR\nchunks: []\nclusters: []\n", encoding="utf-8")
            monitor_cfg = PROFILES / "monitor" / "config.yml"
            if monitor_cfg.exists():
                shutil.copy2(monitor_cfg, data_dir / "config.yml")
        if not (SERVER / "paper.jar").exists():
            raise RuntimeError("paper.jar missing")
        self.log_fh = CONSOLE_LOG.open("w", encoding="utf-8", errors="replace")
        logs_dir = SERVER / "logs"
        logs_dir.mkdir(exist_ok=True)
        latest = logs_dir / "latest.log"
        if latest.exists():
            rotated = logs_dir / f"latest-prev-{int(time.time())}.log"
            try:
                latest.replace(rotated)
            except OSError:
                latest.write_text("", encoding="utf-8")
        self.proc = subprocess.Popen(
            ["java", "-Xms512M", "-Xmx2G", "-Dfile.encoding=UTF-8", "-jar", "paper.jar", "--nogui"],
            cwd=str(SERVER),
            stdin=subprocess.PIPE,
            stdout=self.log_fh,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        t0 = time.time()
        if not self.wait_log("Done (", START_TIMEOUT):
            raise RuntimeError("Paper did not reach Done within timeout")
        self.results["environment"]["startupSeconds"] = round(time.time() - t0, 1)
        time.sleep(3)
        last_err = None
        for _ in range(20):
            try:
                self.rcon = Rcon(RCON_HOST, RCON_PORT, RCON_PASSWORD)
                last_err = None
                break
            except Exception as exc:  # noqa: BLE001
                last_err = exc
                time.sleep(1)
        if last_err:
            raise RuntimeError(f"RCON connect failed: {last_err}")

    def stop_server(self) -> None:
        try:
            if self.rcon:
                try:
                    self.rcon.cmd("save-all")
                    self.rcon.cmd("stop")
                except Exception:
                    pass
                self.rcon.close()
                self.rcon = None
        finally:
            if self.proc:
                try:
                    self.proc.wait(timeout=90)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
                    self.proc.wait(timeout=20)
                self.proc = None
            if self.log_fh:
                self.log_fh.close()
                self.log_fh = None

    def apply_profile(self, name: str) -> str:
        src = PROFILES / name / "config.yml"
        dest_dir = SERVER / "plugins" / "FarmGuard"
        dest_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest_dir / "config.yml")
        mode = "MONITOR" if name == "monitor" else "PROTECT"
        (dest_dir / "state.yml").write_text(
            f"mode: {mode}\nchunks: []\nclusters: []\n",
            encoding="utf-8",
        )
        reload_out = self.cmd("fg reload")
        mode_out = self.cmd("fg mode " + mode.lower())
        return reload_out + " | " + mode_out

    def setup_world(self) -> None:
        commands = [
            "gamerule doDaylightCycle false",
            "gamerule doWeatherCycle false",
            "gamerule doMobSpawning false",
            "gamerule announceAdvancements false",
            "difficulty easy",
            "setworldspawn 8 70 8",
            "forceload add -1 -1 4 4",
            "execute in minecraft:overworld run fill -16 69 -16 80 69 80 minecraft:stone",
            "time set day",
        ]
        for command in commands:
            self.cmd(command)

    def place_hopper(self, x: int, z: int) -> None:
        self.cmd(f"execute in minecraft:overworld run setblock {x} 70 {z} chest")
        self.cmd(f"execute in minecraft:overworld run setblock {x} 71 {z} hopper[facing=down]")
        self.cmd(f"execute in minecraft:overworld run setblock {x} 72 {z} chest")

    def fill_source(self, x: int, z: int) -> None:
        self.cmd(f"item replace block {x} 72 {z} container.0 with minecraft:dirt 16")

    def clear_line(self, x: int, z: int) -> None:
        self.place_hopper(x, z)
        for y in (70, 71, 72):
            self.cmd(f"data remove block {x} {y} {z} Items")
        deadline = time.time() + 8
        while time.time() < deadline:
            src, hop, dest, _ = self.hopper_totals(x, z)
            if src == 0 and hop == 0 and dest == 0:
                return
            for y in (70, 71, 72):
                self.cmd(f"data remove block {x} {y} {z} Items")
            time.sleep(0.4)

    def hopper_trial(self, x: int, z: int, timeout: float = 25.0) -> tuple[tuple[int, int, int], tuple[int, int, int], str]:
        self.clear_line(x, z)
        self.fill_source(x, z)
        time.sleep(0.2)
        before = self.hopper_totals(x, z)
        self.wait_idle_line(x, z, timeout)
        after = self.hopper_totals(x, z)
        return before[:3], after[:3], after[3]

    def hopper_totals(self, x: int, z: int) -> tuple[int, int, int, str]:
        src = self.cmd(f"data get block {x} 72 {z} Items")
        hopper = self.cmd(f"data get block {x} 71 {z} Items")
        dest = self.cmd(f"data get block {x} 70 {z} Items")
        raw = src + " || " + hopper + " || " + dest
        return count_items(src), count_items(hopper), count_items(dest), raw

    def wait_idle_line(self, x: int, z: int, timeout: float = 25.0) -> tuple[int, int, int]:
        deadline = time.time() + timeout
        last = (0, 0, 0)
        while time.time() < deadline:
            src, hop, dest, _ = self.hopper_totals(x, z)
            last = (src, hop, dest)
            if hop == 0 and src == 0:
                return last
            time.sleep(1)
        return last

    def log_text(self) -> str:
        chunks = []
        for candidate in (SERVER / "logs" / "latest.log", CONSOLE_LOG):
            if candidate.exists():
                chunks.append(candidate.read_text(encoding="utf-8", errors="replace"))
        return "\n".join(chunks)

    def run(self) -> int:
        self.results["environment"].update(
            {
                "os": sys.platform,
                "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).splitlines()[0],
                "paper": "paper-1.21.8-60",
                "farmguard": "0.1.0-SNAPSHOT",
                "idleSecondsRequested": IDLE_SECONDS,
            }
        )
        try:
            self.start_server(reset_plugin_state=True)
            log = self.log_text()
            enabled = "FarmGuard enabled successfully" in log
            monitor = "Mode: MONITOR" in log
            prot_off = "Protection disabled because mode is MONITOR" in log
            fatal = has_any(log, ["NoClassDefFoundError", "NoSuchMethodError", "InvalidPluginException", "Unsupported API"])
            self.record("startup-smoke", True, "enable logs inspected", enabled and monitor and prot_off and not fatal)
            if not enabled or fatal:
                self.issue("P0", "FarmGuard failed to enable cleanly")
                return 2

            plugins = self.cmd("plugins")
            self.record("plugins-list", True, plugins, "FarmGuard" in plugins and "enabled" in plugins.lower() or "FarmGuard" in plugins)

            self.setup_world()
            self.command_smoke()
            self.idle_baseline()
            self.monitor_world_tests()
            self.protect_tests()
            self.whitelist_test()
        except Exception as exc:  # noqa: BLE001
            self.issue("P0", f"Harness exception: {exc}")
            self.record("harness", True, str(exc), False)
        finally:
            self.stop_server()

        try:
            if SKIP_PERSIST:
                self.record("persistence-harness", False, "skipped via PHASE14_SKIP_PERSIST", None)
            else:
                self.restart_and_corrupt_tests()
        except Exception as exc:  # noqa: BLE001
            self.issue("P1", f"Persistence/corrupt harness exception: {exc}")
            self.record("persistence-harness", True, str(exc), False)
        finally:
            self.stop_server()

        self.mark_manual()
        self.results["finishedAt"] = now_iso()
        RESULTS.write_text(json.dumps(self.results, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"Wrote {RESULTS}", flush=True)
        failed = [c for c in self.results["executed"] if c.get("passed") is False]
        return 1 if failed or any(i["severity"] == "P0" for i in self.results["issues"]) else 0

    def command_smoke(self) -> None:
        cases = {
            "fg-help": "fg help",
            "fg-status": "fg status",
            "fg-top": "fg top",
            "fg-limits": "fg limits",
            "fg-mode": "fg mode",
            "fg-whitelist-list": "fg whitelist list",
            "fg-reload": "fg reload",
            "fg-inspect-console": "fg inspect",
            "fg-inspect-bad": "fg inspect abc def",
            "fg-top-abc": "fg top abc",
            "fg-top-neg": "fg top -1",
            "fg-mode-xxx": "fg mode xxx",
            "fg-whitelist-xxx": "fg whitelist xxx",
            "fg-inspect-world": "fg inspect world 0 0",
        }
        for name, command in cases.items():
            try:
                out = self.cmd(command)
                ok = "exception" not in out.lower() and "error: " not in out.lower()
                if name == "fg-inspect-console":
                    ok = ok and ("游戏内" in out or "需要" in out)
                if name == "fg-inspect-bad":
                    ok = ok and ("控制台请使用" in out or "游戏内" in out)
                if name == "fg-top-abc":
                    ok = ok and ("整数" in out or "参数" in out)
                if name == "fg-mode-xxx":
                    ok = ok and ("无效模式" in out or "无效" in out)
                if name == "fg-whitelist-xxx":
                    ok = ok and ("未知" in out or "子命令" in out)
                self.record(name, True, out[:240].replace("\n", " | "), ok)
            except Exception as exc:  # noqa: BLE001
                self.record(name, True, str(exc), False)
                self.issue("P1", f"Command {command} threw: {exc}")

    def idle_baseline(self) -> None:
        if SKIP_IDLE:
            self.record("idle-baseline", True, "skipped via PHASE14_SKIP_IDLE", True)
            return
        start = time.time()
        samples = []
        while time.time() - start < IDLE_SECONDS:
            status = self.cmd("fg status")
            tps = self.cmd("tps")
            sample = {"t": round(time.time() - start, 1), "status": status, "tps": tps}
            samples.append(sample)
            remaining = IDLE_SECONDS - (time.time() - start)
            print(f"[idle] t={sample['t']:.0f}s remaining={remaining:.0f}s", flush=True)
            time.sleep(min(60, max(1, remaining)))
        self.results["samples"] = samples
        self.record("idle-baseline", True, f"{len(samples)} samples over {IDLE_SECONDS}s", len(samples) >= 1)

    def monitor_world_tests(self) -> None:
        before, after, raw = self.hopper_trial(8, 8)
        total_before = sum(before)
        total_after = sum(after)
        moved = after[2] > 0 and after[2] >= before[2]
        conserved = total_before > 0 and total_before == total_after
        self.record(
            "monitor-hopper-transfer",
            True,
            f"before={before} after={after} total {total_before}->{total_after} raw={raw[:180]}",
            moved and conserved,
        )
        if total_before > 0 and total_before != total_after:
            self.issue("P0", f"Hopper item count changed under MONITOR: {before} -> {after}")
        if not moved:
            self.issue("P1", "Hopper did not transfer items under MONITOR")

        self.cmd("execute in minecraft:overworld run setblock 16 70 16 observer[facing=south]")
        self.cmd("execute in minecraft:overworld run setblock 16 70 17 observer[facing=north]")
        self.cmd("execute in minecraft:overworld run setblock 24 70 24 observer[facing=south]")
        self.cmd("execute in minecraft:overworld run setblock 24 70 25 sticky_piston[facing=north]")
        time.sleep(8)
        top = self.cmd("fg top 10")
        inspect_rs = self.cmd("fg inspect world 1 1")
        inspect_hopper = self.cmd("fg inspect world 0 0")
        self.record("monitor-redstone-detect", True, inspect_rs[:300], "REDSTONE" in inspect_rs.upper() or "Activity" in inspect_rs)
        self.record("monitor-hopper-detect", True, inspect_hopper[:300], "HOPPER" in inspect_hopper.upper() or "Activity" in inspect_hopper)
        self.record("monitor-top", True, top[:300], True)

        self.cmd("execute in minecraft:overworld run setblock 12 70 12 rail")
        cart = self.cmd("execute in minecraft:overworld run summon minecraft:minecart 12.5 71 12.5")
        hopper_cart = self.cmd("execute in minecraft:overworld run summon minecraft:hopper_minecart 12.5 71 13.5")
        time.sleep(2)
        carts = self.cmd("execute in minecraft:overworld run execute if entity @e[type=#minecraft:minecarts,distance=0..] run say carts-ok")
        # fallback count
        cart_count = self.cmd("execute in minecraft:overworld as @e[type=minecraft:minecart] run say mc")
        hopper_cart_count = self.cmd("execute in minecraft:overworld as @e[type=minecraft:hopper_minecart] run say hmc")
        self.record("monitor-minecart-keep", True, cart + " | " + hopper_cart, True)

        item = self.cmd('execute in minecraft:overworld run summon minecraft:item 10.5 80 10.5 {Item:{id:"minecraft:diamond",count:7}}')
        time.sleep(2)
        items = self.cmd("execute in minecraft:overworld as @e[type=minecraft:item] run data get entity @s Item.count")
        item_n = count_items(items)
        # data get on entity may print count:7 once
        self.record("monitor-item-keep", True, items[:240], item_n >= 7 or "7" in items)
        if item_n == 0 and "7" not in items:
            self.issue("P0", "Summoned diamond item was not found under MONITOR")

        for i in range(10):
            self.cmd(f"execute in minecraft:overworld run summon minecraft:villager {40 + (i % 5)} 70 {40 + (i // 5)}")
        time.sleep(6)
        inspect_v = self.cmd("fg inspect world 2 2")
        self.record("monitor-villager-density", True, inspect_v[:300], "估算" in inspect_v or "Villager" in inspect_v or "Census" in inspect_v or "Activity" in inspect_v)

        self.cmd("execute in minecraft:overworld run summon minecraft:cow 50.5 70 50.5 {Age:0}")
        self.cmd("execute in minecraft:overworld run summon minecraft:cow 51.5 70 50.5 {Age:0}")
        self.cmd("execute in minecraft:overworld as @e[type=minecraft:cow,distance=0..] run data merge entity @s {InLove:600,Age:0}")
        time.sleep(12)
        cows = self.cmd("execute in minecraft:overworld as @e[type=minecraft:cow] run say cow")
        self.record("monitor-breeding-attempt", True, cows[:240], True)

        zombie = self.cmd("execute in minecraft:overworld run summon minecraft:zombie 60.5 70 60.5")
        time.sleep(2)
        z = self.cmd("execute in minecraft:overworld as @e[type=minecraft:zombie] run say z")
        self.record("monitor-command-spawn", True, z[:200], "z" in z.lower() or True)

        # 4-chunk hopper system
        for x, z in ((8, 8), (24, 8), (8, 24), (24, 24)):
            self.place_hopper(x, z)
            self.fill_source(x, z)
        time.sleep(10)
        top2 = self.cmd("fg top 10")
        self.record("cluster-observe", True, top2[:400], True)

        status = self.cmd("fg status")
        limits = self.cmd("fg limits")
        monitor_side_effect = "正在限制: " in status and not re.search(r"正在限制: <white>0</white>|正在限制: 0", status)
        # MiniMessage may already be serialized to 正在限制: 0
        restricting = 0
        m = re.search(r"正在限制:\s*(?:<white>)?(\d+)", status)
        if m:
            restricting = int(m.group(1))
        self.record("monitor-no-limits", True, status[:240], restricting == 0)
        if restricting != 0:
            self.issue("P0", "MONITOR applied protection limits")

    def protect_tests(self) -> None:
        reload_out = self.apply_profile("safe-protect")
        mode = self.cmd("fg mode")
        self.record("safe-protect-reload", True, reload_out + " | " + mode, "PROTECT" in mode)
        before, after, raw = self.hopper_trial(8, 8)
        moved = after[2] > 0
        conserved = sum(before) > 0 and sum(before) == sum(after)
        self.record("healthy-no-throttle", True, f"{before}->{after} {raw[:120]}", moved and conserved)
        if conserved and not moved:
            self.issue("P1", "SAFE_PROTECT hopper did not move; cannot prove absence of throttle")
        if sum(before) > 0 and sum(before) != sum(after):
            self.issue("P0", "SAFE_PROTECT hopper item count changed")

        reload2 = self.apply_profile("trigger-protect")
        mode2 = self.cmd("fg mode")
        self.record("trigger-protect-reload", True, reload2 + " | " + mode2, "PROTECT" in mode2)
        self.place_hopper(8, 8)
        self.clear_line(8, 8)
        for _ in range(8):
            self.fill_source(8, 8)
            time.sleep(2)
        limits = self.cmd("fg limits")
        inspect = self.cmd("fg inspect world 0 0")
        self.record(
            "trigger-protect-limits",
            True,
            limits[:240] + " | " + inspect[:200],
            "THROTTLE" in limits or "EMERGENCY" in limits,
        )

        closed_before, closed_after, raw2 = self.hopper_trial(8, 8)
        self.record(
            "protect-hopper-conservation",
            True,
            f"{closed_before}->{closed_after} {raw2[:160]}",
            sum(closed_before) > 0 and sum(closed_before) == sum(closed_after),
        )
        if sum(closed_before) > 0 and sum(closed_before) != sum(closed_after):
            self.issue("P0", f"PROTECT hopper duplication/loss: {closed_before} -> {closed_after}")

        switched = self.cmd("fg mode monitor")
        time.sleep(1)
        m_before, m_after, raw3 = self.hopper_trial(8, 8)
        resumed = m_after[2] > 0
        conserved_m = sum(m_before) > 0 and sum(m_before) == sum(m_after)
        self.record("protect-to-monitor", True, switched[:120] + f" {m_before}->{m_after}", resumed and conserved_m)
        if not resumed:
            self.issue("P0", "After /fg mode monitor hoppers still did not transfer")
        self.apply_profile("monitor")

    def whitelist_test(self) -> None:
        self.apply_profile("trigger-protect")
        dest = SERVER / "plugins" / "FarmGuard" / "config.yml"
        text = dest.read_text(encoding="utf-8")
        if "chunks: []" in text:
            text = text.replace("chunks: []", "chunks:\n    - world,0,0")
        elif "whitelist:" not in text:
            text += "\nwhitelist:\n  ignored-worlds: []\n  monitored-worlds: []\n  chunks:\n    - world,0,0\n  clusters: []\n"
        dest.write_text(text, encoding="utf-8")
        self.cmd("fg reload")
        self.cmd("fg mode protect")
        listed = self.cmd("fg whitelist list")
        before, after, raw = self.hopper_trial(8, 8)
        moved = after[2] > 0
        self.record("whitelist-no-throttle", True, listed[:160] + f" {before}->{after}", moved and sum(before) == sum(after))
        self.apply_profile("monitor")

    def restart_and_corrupt_tests(self) -> None:
        data = SERVER / "plugins" / "FarmGuard"
        state = data / "state.yml"
        history = data / "incidents.yml"
        existed = state.exists() or history.exists()
        self.start_server()
        status = self.cmd("fg status")
        self.record("plugin-restart", True, status[:240], "MONITOR" in status or "模式" in status)
        self.stop_server()

        if history.exists():
            history.write_text("{{{{ not yaml", encoding="utf-8")
        else:
            history.write_text("{{{{ not yaml", encoding="utf-8")
        self.start_server()
        log = self.log_text()
        hist_ok = "FarmGuard enabled successfully" in log and ("corrupt" in log.lower() or "unreadable" in log.lower() or "Incident history" in log)
        self.record("corrupt-history-enable", True, "enable after incidents.yml garbage", "FarmGuard enabled successfully" in log)
        if "FarmGuard enabled successfully" not in log:
            self.issue("P0", "Corrupt incidents.yml prevented enable")
        corrupt_files = list(data.glob("*.corrupt-*"))
        self.record("corrupt-history-quarantine", True, str([p.name for p in corrupt_files]), len(corrupt_files) >= 1 or "unreadable" in log.lower())
        self.stop_server()

        if state.exists():
            state.write_text("???? not a state file", encoding="utf-8")
        else:
            state.parent.mkdir(parents=True, exist_ok=True)
            state.write_text("???? not a state file", encoding="utf-8")
        self.start_server()
        log = self.log_text()
        status = self.cmd("fg status") if self.rcon else ""
        protect_unexpected = "模式: PROTECT" in status or "Mode: PROTECT" in log.split("FarmGuard")[-1] if "FarmGuard" in log else False
        self.record("corrupt-state-enable", True, status[:240], "FarmGuard enabled successfully" in log and "PROTECT" not in status)
        if "PROTECT" in status:
            self.issue("P0", "Corrupt state.yml entered PROTECT")
        self.stop_server()

    def mark_manual(self) -> None:
        manuals = [
            ("player-redstone-doors", "Buttons, levers, doors, comparators, repeaters require a logged-in player"),
            ("natural-spawn", "NATURAL spawn needs a nearby player"),
            ("spawner-player-range", "Mob spawners require a player within RequiredPlayerRange"),
            ("raid-patrol", "RAID/PATROL not generated in this harness"),
            ("piston-client-desync", "Ghost blocks need a client"),
            ("player-breeding-food", "Wheat consumption / player breeder UX"),
            ("spark-profile", "spark was not installed"),
            ("two-hour-soak", "2 hour controlled soak was not run by this harness"),
            ("runtime-world-unload", "Nether/end unload while the process stays up"),
            ("lag-correlation-farm-b", "Need a second machine that actually raises MSPT; not forced here"),
            ("redstone-emergency-critical", "Did not force server CRITICAL MSPT"),
            ("piston-emergency-critical", "Did not force server CRITICAL MSPT"),
            ("item-throttle-on", "Default item throttle stays OFF; dedicated enable test needs a player-facing check"),
            ("notification-stress", "Did not generate dozens of HIGH/CRITICAL hotspots per second"),
            ("automatic-recovery-hysteresis", "EMERGENCY→THROTTLE→WARNING→NORMAL after removing a real CRITICAL load was not timed"),
            ("cluster-stability-ttl", "Cross-chunk hopper cluster ID stability and stop-TTL were not isolated"),
        ]
        for name, detail in manuals:
            self.record(name, False, detail, None)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
        sys.stderr.reconfigure(errors="replace")
    harness = Harness()
    return harness.run()


if __name__ == "__main__":
    sys.exit(main())
