#!/usr/bin/env python3
"""Phase 16 Paper + bot + TestProbe helpers. Test infrastructure only."""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))

import phase15_common as p15  # noqa: E402

SERVER = p15.SERVER
PLUGIN_DIR = SERVER / "plugins"
PROBE_PROJECT = ROOT / "test-support" / "FarmGuardTestProbe"
PROBE_JAR = PROBE_PROJECT / "build" / "libs" / "FarmGuardTestProbe-0.1.0-test.jar"
FPP_JAR = PLUGIN_DIR / "fake-player-plugin-2.0.6.jar"
TEST_BOT = ROOT / "test-bot"
RESULTS = ROOT / "test-results" / "phase16"
GRADLE = os.environ.get("GRADLE", r"C:\Environment\gradle-8.14\bin\gradle.bat")
FPP_SHA256 = "CBBB15F467C22B7EEA7E0C3E5E07ED16285D2C84DF69CAB8305A38F4E31FFD67"
MINEFLAYER_VERSION = "4.39.0"
EMERGENCY_MSPT_LIMIT = float(os.environ.get("PHASE16_EMERGENCY_MSPT", "90"))
PRESSURE_ENTITY_CAP = int(os.environ.get("PHASE16_ENTITY_CAP", "220"))

ZONES = {
    "monitor_redstone": (0, 70, 0),
    "monitor_hopper": (256, 70, 0),
    "breeding": (512, 70, 0),
    "natural": (640, 70, 0),
    "spawner": (768, 70, 0),
    "farm_a": (1024, 70, 0),
    "farm_b": (1536, 70, 0),
    "emergency_redstone": (2048, 70, 0),
    "piston": (2304, 70, 0),
    "item_drop": (96, 70, 0),
    "minecart": (160, 70, 0),
    # Near spawn so the column is loaded; away from hopper rings at 256 and farms.
    "integrity": (40, 70, 40),
}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def git_rev() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=str(ROOT),
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except Exception:
        return "NO_GIT_REPOSITORY"


def ensure_results() -> Path:
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "profiler").mkdir(exist_ok=True)
    (RESULTS / "soak").mkdir(exist_ok=True)
    (RESULTS / "integrity").mkdir(exist_ok=True)
    return RESULTS


def parse_fgtest(text: str) -> dict[str, Any]:
    plain = p15.strip_color(text or "")
    idx = plain.find("FGTEST ")
    if idx < 0:
        raise RuntimeError("no FGTEST payload: " + plain[:400])
    blob = plain[idx + 7 :].strip()
    brace = blob.find("{")
    if brace > 0:
        blob = blob[brace:]
    return json.loads(blob)


def gradle_cmd(*args: str, cwd: Path | None = None) -> str:
    cmd = [GRADLE, *args]
    proc = subprocess.run(cmd, cwd=str(cwd or ROOT), capture_output=True, text=True)
    out = (proc.stdout or "") + "\n" + (proc.stderr or "")
    if proc.returncode != 0:
        raise RuntimeError("gradle failed: " + out[-4000:])
    return out


def install_runtime_plugins() -> dict[str, str]:
    PLUGIN_DIR.mkdir(parents=True, exist_ok=True)
    if not p15.PLUGIN_JAR_SRC.exists():
        raise RuntimeError("FarmGuard jar missing: " + str(p15.PLUGIN_JAR_SRC))
    if not PROBE_JAR.exists():
        raise RuntimeError("TestProbe jar missing: " + str(PROBE_JAR))
    if not FPP_JAR.exists():
        raise RuntimeError("FPP jar missing: " + str(FPP_JAR))
    shutil.copy2(p15.PLUGIN_JAR_SRC, PLUGIN_DIR / p15.PLUGIN_JAR_SRC.name)
    shutil.copy2(PROBE_JAR, PLUGIN_DIR / PROBE_JAR.name)
    props = SERVER / "server.properties"
    if props.exists():
        text = props.read_text(encoding="utf-8")
        text = re.sub(r"max-players=\d+", "max-players=30", text)
        text = re.sub(r"simulation-distance=\d+", "simulation-distance=8", text)
        text = re.sub(r"view-distance=\d+", "view-distance=8", text)
        text = re.sub(r"motd=.*", "motd=FarmGuard Phase 16", text)
        text = re.sub(r"spawn-protection=\d+", "spawn-protection=0", text)
        props.write_text(text, encoding="utf-8")
    return {
        "farmguard": str(PLUGIN_DIR / p15.PLUGIN_JAR_SRC.name),
        "probe": str(PLUGIN_DIR / PROBE_JAR.name),
        "fpp": str(FPP_JAR),
        "farmguardSha256": p15.sha256_file(p15.PLUGIN_JAR_SRC),
        "probeSha256": p15.sha256_file(PROBE_JAR),
        "fppSha256": p15.sha256_file(FPP_JAR),
    }


@dataclass
class ScenarioResult:
    scenario: str
    status: str
    start: str
    end: str = ""
    expected: str = ""
    actual: str = ""
    tps: float | None = None
    mspt: float | None = None
    mode: str | None = None
    risk: str | None = None
    protection: str | None = None
    correlation: str | None = None
    log_excerpt: str = ""
    extra: dict[str, Any] = field(default_factory=dict)

    def finish(self, status: str, actual: str, **kwargs: Any) -> "ScenarioResult":
        self.status = status
        self.actual = actual
        self.end = now_iso()
        for key, value in kwargs.items():
            if hasattr(self, key):
                setattr(self, key, value)
            else:
                self.extra[key] = value
        return self

    def to_dict(self) -> dict[str, Any]:
        return {
            "scenario": self.scenario,
            "status": self.status,
            "start": self.start,
            "end": self.end,
            "expected": self.expected,
            "actual": self.actual,
            "tps": self.tps,
            "mspt": self.mspt,
            "mode": self.mode,
            "risk": self.risk,
            "protection": self.protection,
            "correlation": self.correlation,
            "logExcerpt": self.log_excerpt[-2000:],
        }
        for key, value in self.extra.items():
            if key in out:
                out["extra_" + key] = value
            else:
                out[key] = value
        return out


class BotAgent:
    def __init__(self, name: str, log_path: Path):
        self.name = name
        self.log_path = log_path
        self.proc: subprocess.Popen[str] | None = None
        self._lock = threading.Lock()
        self._req = 0
        self._pending: dict[int, dict[str, Any]] = {}
        self._events: list[dict[str, Any]] = []
        self._reader: threading.Thread | None = None
        self._stderr_thread: threading.Thread | None = None

    def start(self, timeout: float = 40.0) -> dict[str, Any]:
        env = os.environ.copy()
        env["FG_BOT_HOST"] = "127.0.0.1"
        env["FG_BOT_PORT"] = "25570"
        env["FG_BOT_NAME"] = self.name
        env["FG_BOT_VERSION"] = "1.21.8"
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self.proc = subprocess.Popen(
            ["node", "src/agent.js"],
            cwd=str(TEST_BOT),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=env,
            bufsize=1,
        )
        self._reader = threading.Thread(target=self._read_stdout, daemon=True)
        self._reader.start()
        self._stderr_thread = threading.Thread(target=self._read_stderr, daemon=True)
        self._stderr_thread.start()
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(self.name + " exited before spawn")
            spawned = [row for row in self._events if row.get("event") == "spawn"]
            if spawned:
                last = spawned[-1]
                break
            time.sleep(0.2)
        if last is None:
            raise RuntimeError(self.name + " did not spawn: " + self._tail_log())
        return last

    def _tail_log(self) -> str:
        if not self.log_path.exists():
            return ""
        return self.log_path.read_text(encoding="utf-8", errors="replace")[-1500:]

    def _read_stdout(self) -> None:
        assert self.proc and self.proc.stdout
        for line in self.proc.stdout:
            line = line.strip()
            if not line:
                continue
            with self.log_path.open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            if "id" in payload:
                with self._lock:
                    self._pending[int(payload["id"])] = payload
            else:
                with self._lock:
                    self._events.append(payload)

    def _read_stderr(self) -> None:
        assert self.proc and self.proc.stderr
        for line in self.proc.stderr:
            with self.log_path.open("a", encoding="utf-8") as handle:
                handle.write("[stderr] " + line)

    def call(self, op: str, timeout: float = 20.0, **kwargs: Any) -> dict[str, Any]:
        if self.proc is None or self.proc.stdin is None:
            raise RuntimeError(self.name + " is not running")
        with self._lock:
            self._req += 1
            req_id = self._req
        payload = {"id": req_id, "op": op, **kwargs}
        self.proc.stdin.write(json.dumps(payload) + "\n")
        self.proc.stdin.flush()
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self._lock:
                if req_id in self._pending:
                    return self._pending.pop(req_id)
            if self.proc.poll() is not None:
                raise RuntimeError(self.name + " exited during " + op)
            time.sleep(0.05)
        raise TimeoutError(self.name + " timeout on " + op)

    def stop(self) -> None:
        try:
            if self.proc and self.proc.poll() is None:
                try:
                    self.call("quit", timeout=5)
                except Exception:
                    pass
                self.proc.terminate()
                try:
                    self.proc.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
        finally:
            self.proc = None


class Harness:
    def __init__(self) -> None:
        self.server = p15.PaperServer()
        self.bots: dict[str, BotAgent] = {}
        self.results: list[ScenarioResult] = []
        self.fpp_help: str = ""
        self.plugins_raw: str = ""
        self.p0: list[str] = []
        self.p1: list[str] = []
        self.p2: list[str] = []
        self.fixes: list[str] = []
        self.limitations: list[str] = []
        self.env: dict[str, Any] = {}
        self.build_out: str = ""
        self.last_rcon_latency: float = 0.0
        self.pressure_session: dict[str, Any] = {}

    def begin(self, name: str, expected: str) -> ScenarioResult:
        row = ScenarioResult(scenario=name, status="INCOMPLETE", start=now_iso(), expected=expected)
        self.results.append(row)
        return row

    def attach_status(self, row: ScenarioResult, inspect: dict[str, Any] | None = None) -> None:
        try:
            status = self.status()
        except Exception as exc:  # noqa: BLE001
            row.log_excerpt = str(exc)
            return
        row.tps = status.get("tps")
        row.mspt = status.get("mspt")
        row.mode = status.get("mode")
        if inspect:
            row.risk = inspect.get("risk")
            row.protection = inspect.get("protection")
            row.correlation = inspect.get("correlation")

    def status(self) -> dict[str, Any]:
        return p15.parse_status(self.cmd("fg status"))

    def cmd(self, command: str) -> str:
        t0 = time.perf_counter()
        try:
            out = self.server.cmd(command)
        except Exception:
            self._reconnect_rcon()
            out = self.server.cmd(command)
        self.last_rcon_latency = time.perf_counter() - t0
        return out

    def _reconnect_rcon(self) -> None:
        try:
            if self.server.rcon:
                self.server.rcon.close()
        except Exception:
            pass
        self.server.rcon = p15.Rcon(p15.RCON_HOST, p15.RCON_PORT, p15.RCON_PASSWORD, timeout=90.0)

    def inspect_block(self, x: int, z: int) -> dict[str, Any]:
        """FarmGuard /fg inspect uses chunk coordinates, not block coordinates."""
        text = self.cmd(f"fg inspect world {x >> 4} {z >> 4}")
        parsed = p15.parse_inspect(text)
        parsed["chunkX"] = x >> 4
        parsed["chunkZ"] = z >> 4
        parsed["blockX"] = x
        parsed["blockZ"] = z
        rates: dict[str, float] = {}
        blob = parsed.get("raw") or text
        for match in re.finditer(r"([A-Z_]+):\s*([\d.]+)", blob):
            key = match.group(1)
            if key not in {"MSPT"}:
                rates[key] = float(match.group(2))
        parsed["rates"] = rates
        return parsed

    def pressure_cmd(self, *parts: str) -> dict[str, Any]:
        return self.fgtest("pressure", *parts)

    def pressure_stop(self, reason: str = "command") -> dict[str, Any]:
        try:
            return self.fgtest("pressure", "stop")
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": str(exc), "reason": reason}

    def fgtest(self, *parts: str) -> dict[str, Any]:
        return parse_fgtest(self.cmd("fgtest " + " ".join(str(p) for p in parts)))

    def start_server(self, reset_plugin_state: bool = True) -> float:
        install_runtime_plugins()
        elapsed = self.server.start(reset_plugin_state=reset_plugin_state)
        self.cmd("gamerule sendCommandFeedback false")
        self.cmd("gamerule doDaylightCycle false")
        self.cmd("gamerule doWeatherCycle false")
        self.cmd("gamerule mobGriefing false")
        self.cmd("gamerule fallDamage false")
        self.cmd("gamerule keepInventory true")
        self.cmd("gamerule maxEntityCramming 0")
        self.cmd("time set noon")
        self.cmd("forceload add -2 -2 8 8")
        return elapsed

    def stop_server(self) -> None:
        try:
            self.emergency_stop_pressure()
        except Exception:
            pass
        for bot in list(self.bots.values()):
            bot.stop()
        self.bots.clear()
        self.server.stop()

    def bot(self, name: str = "FarmGuardBot01") -> BotAgent:
        return self.bots[name]

    def prepare_platform(self, x: int, y: int, z: int, radius: int = 6, dark: bool = False) -> None:
        self.cmd(f"execute in minecraft:overworld run fill {x-radius} {y-2} {z-radius} {x+radius} {y+6} {z+radius} air")
        self.cmd(
            f"execute in minecraft:overworld run fill {x-radius} {y-1} {z-radius} {x+radius} {y-1} {z+radius} minecraft:gray_concrete"
        )
        self.cmd(f"execute in minecraft:overworld run setblock {x} {y-1} {z} minecraft:barrier")
        if dark:
            self.cmd(
                f"execute in minecraft:overworld run fill {x-radius} {y+4} {z-radius} {x+radius} {y+4} {z+radius} stone"
            )
            self.cmd(
                f"execute in minecraft:overworld run fill {x-radius} {y} {z-radius} {x-radius} {y+3} {z+radius} stone"
            )
            self.cmd(
                f"execute in minecraft:overworld run fill {x+radius} {y} {z-radius} {x+radius} {y+3} {z+radius} stone"
            )
            self.cmd(
                f"execute in minecraft:overworld run fill {x-radius} {y} {z-radius} {x+radius} {y+3} {z-radius} stone"
            )
            self.cmd(
                f"execute in minecraft:overworld run fill {x-radius} {y} {z+radius} {x+radius} {y+3} {z+radius} stone"
            )
        else:
            self.cmd(
                f"execute in minecraft:overworld run setblock {x-radius} {y} {z-radius} glowstone"
            )
            self.cmd(
                f"execute in minecraft:overworld run setblock {x+radius} {y} {z+radius} glowstone"
            )

    def tp_bot(self, name: str, x: float, y: float, z: float, wait: float = 2.0) -> None:
        self.cmd(f"tp {name} {x} {y} {z}")
        self.cmd(f"gamemode survival {name}")
        self.cmd(f"effect give {name} minecraft:slow_falling 8 0 true")
        time.sleep(wait)

    def wait_chunk(self, x: int, z: int, timeout: float = 15.0) -> dict[str, Any]:
        deadline = time.time() + timeout
        last: dict[str, Any] = {}
        while time.time() < deadline:
            last = self.fgtest("chunk", "world", x // 16, z // 16)
            if last.get("loaded"):
                return last
            time.sleep(0.5)
        return last

    def kill_test_entities(self, tag: str = "FarmGuardTest") -> None:
        self.cmd(f"execute in minecraft:overworld run kill @e[tag={tag}]")
        self.cmd("execute in minecraft:overworld run kill @e[type=minecraft:item,tag=FarmGuardTest]")

    def emergency_stop_pressure(self) -> None:
        try:
            self.pressure_stop("emergency")
        except Exception:
            pass
        self.cmd("execute in minecraft:overworld run kill @e[tag=FarmGuardTest]")
        self.cmd("execute in minecraft:overworld run kill @e[tag=FarmGuardTestFarmB]")
        for key, (x, y, z) in ZONES.items():
            if key in {"farm_b", "emergency_redstone", "piston"}:
                self.cmd(
                    f"execute in minecraft:overworld run fill {x-12} {y} {z-12} {x+12} {y+4} {z+12} air replace repeater"
                )
                self.cmd(
                    f"execute in minecraft:overworld run fill {x-12} {y} {z-12} {x+12} {y+4} {z+12} air replace observer"
                )
                self.cmd(
                    f"execute in minecraft:overworld run fill {x-12} {y} {z-12} {x+12} {y+4} {z+12} air replace redstone_block"
                )

    def plugin_enabled(self, name: str) -> bool:
        raw = self.plugins_raw or self.cmd("plugins")
        self.plugins_raw = raw
        plain = p15.strip_color(raw)
        return bool(re.search(rf"\b{re.escape(name)}\b", plain, re.IGNORECASE))

    def discover_fpp(self) -> dict[str, Any]:
        texts = []
        for command in ("fpp help", "fpp help 2", "fpp help 3", "fpp help 4", "fpp help 5", "fpp", "help fpp"):
            try:
                texts.append(command + " => " + self.cmd(command))
            except Exception as exc:  # noqa: BLE001
                texts.append(command + " => ERR " + str(exc))
        self.fpp_help = "\n\n".join(texts)
        (RESULTS / "fpp-help.txt").write_text(self.fpp_help, encoding="utf-8")
        help_text = p15.strip_color(self.fpp_help).lower()
        return {
            "raw": self.fpp_help[:12000],
            "hasSpawn": "spawn" in help_text,
            "hasDespawn": "despawn" in help_text or "delete" in help_text,
            "hasMove": "/fpp move" in help_text or "pathfind" in help_text,
            "hasLook": "look" in help_text,
            "hasRightClick": "right-click" in help_text or "rightclick" in help_text,
            "hasLeftClick": "left-click" in help_text or "leftclick" in help_text,
            "hasUse": "/fpp use" in help_text or "useitem" in help_text,
            "hasRepeat": "--repeat" in help_text or "repeat" in help_text,
        }

    def try_fpp_spawn(self, name: str, x: int, y: int, z: int) -> str:
        attempts = [
            f"fpp spawn --name {name} --location {x} {y} {z} world",
            f"fpp spawn --name {name} --location world {x} {y} {z}",
            f"fpp spawn --name {name}",
        ]
        outputs = []
        for command in attempts:
            out = self.cmd(command)
            outputs.append(command + " => " + out)
            if re.search(r"spawned|created|success", out, re.IGNORECASE) and "unknown" not in out.lower() and "only a player" not in p15.strip_color(out).lower():
                return "\n".join(outputs)
        return "\n".join(outputs)

    def wait_client_block(self, bot_name: str, x: int, y: int, z: int, timeout: float = 12.0) -> dict:
        deadline = time.time() + timeout
        last: dict = {}
        while time.time() < deadline:
            last = self.bot(bot_name).call("blockAt", x=x, y=y, z=z)
            name = str(last.get("name") or "air")
            if last.get("ok") and name not in {"air", "cave_air", "void_air", "None"}:
                return last
            time.sleep(0.3)
        return last

    def start_bot(self, name: str) -> dict[str, Any]:
        agent = BotAgent(name, RESULTS / "bot.log")
        spawn = agent.start()
        self.bots[name] = agent
        try:
            self.cmd("op " + name)
        except Exception:
            pass
        return spawn

    def hopper_totals_probe(self, x: int, y: int, z: int) -> dict[str, Any]:
        dest = self.fgtest("container", "world", x, y, z)
        hopper = self.fgtest("container", "world", x, y + 1, z)
        src = self.fgtest("container", "world", x, y + 2, z)
        region = self.fgtest("region", "world", x - 2, y - 1, z - 2, x + 2, y + 3, z + 2)
        total = int(src.get("total") or 0) + int(hopper.get("total") or 0) + int(dest.get("total") or 0)
        return {
            "source": src,
            "hopper": hopper,
            "dest": dest,
            "region": region,
            "total": total,
            "groundItems": region.get("items", 0),
        }

    def isolated_hopper_conservation(self, expected: int = 64) -> dict[str, Any]:
        """64-item transfer on a clean column. Does not count leftover soak-ring items."""
        x, y, z = ZONES["integrity"]
        cx, cz = x >> 4, z >> 4
        self.cmd(f"forceload add {cx} {cz}")
        if "FarmGuardBot01" in self.bots:
            try:
                self.tp_bot("FarmGuardBot01", x + 0.5, y, z + 0.5, wait=1.2)
            except Exception:
                pass
        self.prepare_platform(x, y, z, radius=3)
        self.cmd(
            f"execute in minecraft:overworld run kill @e[type=minecraft:item,x={x},y={y},z={z},distance=..12]"
        )
        time.sleep(0.8)
        self.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z} chest")
        self.cmd(f"execute in minecraft:overworld run setblock {x} {y + 1} {z} hopper[facing=down]")
        self.cmd(f"execute in minecraft:overworld run setblock {x} {y + 2} {z} chest")
        placed = None
        for _ in range(12):
            placed = self.fgtest("container", "world", x, y + 2, z)
            if placed.get("ok") and str(placed.get("material") or "").upper() != "AIR":
                break
            time.sleep(0.4)
        self.cmd(f"item replace block {x} {y + 2} {z} container.0 with minecraft:cobblestone {expected}")
        time.sleep(8)
        dest = self.fgtest("container", "world", x, y, z)
        hopper = self.fgtest("container", "world", x, y + 1, z)
        src = self.fgtest("container", "world", x, y + 2, z)
        total = int(src.get("total") or 0) + int(hopper.get("total") or 0) + int(dest.get("total") or 0)
        return {
            "ok": total == expected,
            "expected": expected,
            "total": total,
            "source": src,
            "hopper": hopper,
            "dest": dest,
            "placed": placed,
            "x": x,
            "y": y,
            "z": z,
        }

    def write_partial(self) -> None:
        ensure_results()
        path = RESULTS / "scenarios.jsonl"
        with path.open("w", encoding="utf-8") as handle:
            for row in self.results:
                handle.write(json.dumps(row.to_dict(), ensure_ascii=False) + "\n")
