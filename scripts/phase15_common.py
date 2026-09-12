#!/usr/bin/env python3
"""Shared Paper RCON helpers for FarmGuard Phase 15. Not a product feature."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "test-server"
PLUGIN_JAR_SRC = ROOT / "build" / "libs" / "FarmGuard-1.0.0-beta.2.jar"
PROFILES = ROOT / "test-profiles"
CONSOLE_LOG = SERVER / "console-capture.log"

RCON_HOST = "127.0.0.1"
RCON_PORT = int(os.environ.get("FARMGUARD_RCON_PORT", "25576"))
RCON_PASSWORD = os.environ.get("FARMGUARD_RCON_PASSWORD", "farmguard14")
START_TIMEOUT = int(os.environ.get("PHASE15_START_TIMEOUT", "480"))


class Rcon:
    LOGIN = 3
    COMMAND = 2

    def __init__(self, host: str, port: int, password: str, timeout: float = 60.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.req_id = 0
        if self._send(self.LOGIN, password) is None:
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


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def count_items(blob: str) -> int:
    total = 0
    for match in re.finditer(r"\bcount:\s*(\d+)", blob, re.IGNORECASE):
        total += int(match.group(1))
    return total


def strip_color(text: str) -> str:
    return re.sub(r"§[0-9a-fk-orx]|§x(§[0-9a-f]){6}", "", text, flags=re.IGNORECASE)


def parse_status(text: str) -> dict:
    plain = strip_color(text)
    out: dict = {"raw": plain[:500]}
    m = re.search(r"模式:\s*(\w+)", plain)
    if m:
        out["mode"] = m.group(1)
    m = re.search(r"TPS:\s*([\d.]+)", plain)
    if m:
        out["tps"] = float(m.group(1))
    m = re.search(r"MSPT:\s*([\d.]+)", plain)
    if m:
        out["mspt"] = float(m.group(1))
    m = re.search(r"平均MSPT:\s*([\d.]+)", plain)
    if m:
        out["avg_mspt"] = float(m.group(1))
    m = re.search(r"服务器压力:\s*(\w+)", plain)
    if m:
        out["pressure"] = m.group(1)
    m = re.search(r"正在限制:\s*(\d+)", plain)
    if m:
        out["limiting"] = int(m.group(1))
    m = re.search(r"跟踪区块:\s*(\d+)", plain)
    if m:
        out["tracked"] = int(m.group(1))
    m = re.search(r"活跃区块:\s*(\d+)", plain)
    if m:
        out["active"] = int(m.group(1))
    m = re.search(r"高风险区块:\s*(\d+)", plain)
    if m:
        out["high_risk"] = int(m.group(1))
    return out


def parse_inspect(text: str) -> dict:
    plain = strip_color(text)
    out: dict = {"raw": plain[:800]}
    m = re.search(r"Activity:\s*([\d.]+)", plain)
    if m:
        out["activity"] = float(m.group(1))
    m = re.search(r"Risk:\s*([\d.]+)\s*/\s*(\w+)", plain)
    if m:
        out["risk_score"] = float(m.group(1))
        out["risk"] = m.group(2)
    m = re.search(r"Lag correlation:\s*(\w+)", plain)
    if m:
        out["correlation"] = m.group(1)
    m = re.search(r"Protection:\s*(\w+)", plain)
    if m:
        out["protection"] = m.group(1)
    m = re.search(r"recommended:\s*(\w+)", plain)
    if m:
        out["recommended"] = m.group(1)
    return out


def wait_log(needle: str, timeout: int, extra: Path | None = None) -> bool:
    deadline = time.time() + timeout
    path = SERVER / "logs" / "latest.log"
    while time.time() < deadline:
        for candidate in (path, CONSOLE_LOG, extra):
            if candidate is None or not candidate.exists():
                continue
            text = candidate.read_text(encoding="utf-8", errors="replace")
            if needle in text:
                return True
        time.sleep(1)
    return False


def find_spark_url(since_text: str = "") -> str | None:
    blobs = []
    for candidate in (SERVER / "logs" / "latest.log", CONSOLE_LOG):
        if candidate.exists():
            blobs.append(candidate.read_text(encoding="utf-8", errors="replace"))
    text = "\n".join(blobs)
    if since_text and since_text in text:
        text = text.split(since_text, 1)[-1]
    matches = re.findall(r"https://spark\.lucko\.me/\S+", text)
    if not matches:
        return None
    return matches[-1].rstrip(").,]").replace("\x1b[0m", "")


def plugin_files() -> dict:
    folder = SERVER / "plugins" / "FarmGuard"
    out = {}
    if not folder.exists():
        return out
    for path in folder.iterdir():
        if path.is_file():
            out[path.name] = path.stat().st_size
    return out


class PaperServer:
    def __init__(self):
        self.proc: subprocess.Popen[str] | None = None
        self.log_fh = None
        self.rcon: Rcon | None = None

    def cmd(self, command: str) -> str:
        assert self.rcon is not None
        return self.rcon.cmd(command).strip()

    def start(self, reset_plugin_state: bool = False) -> float:
        SERVER.mkdir(parents=True, exist_ok=True)
        (SERVER / "plugins").mkdir(exist_ok=True)
        if not PLUGIN_JAR_SRC.exists():
            raise RuntimeError(f"Plugin jar missing: {PLUGIN_JAR_SRC}")
        for old in (SERVER / "plugins").glob("FarmGuard-*.jar"):
            old.unlink(missing_ok=True)
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
            ["java", "-Xms512M", f"-Xmx{os.environ.get('PAPER_XMX', '2G')}", "-Dfile.encoding=UTF-8", "-jar", "paper.jar", "--nogui"],
            cwd=str(SERVER),
            stdin=subprocess.PIPE,
            stdout=self.log_fh,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        t0 = time.time()
        if not wait_log("Done (", START_TIMEOUT):
            raise RuntimeError("Paper did not reach Done within timeout")
        if not wait_log("RCON running on", 30):
            self.stop()
            raise RuntimeError("Paper RCON did not bind; check rcon.port is free")
        elapsed = round(time.time() - t0, 1)
        time.sleep(3)
        last_err = None
        for _ in range(25):
            try:
                self.rcon = Rcon(RCON_HOST, RCON_PORT, RCON_PASSWORD)
                last_err = None
                break
            except Exception as exc:  # noqa: BLE001
                last_err = exc
                time.sleep(1)
        if last_err:
            self.stop()
            raise RuntimeError(f"RCON connect failed: {last_err}")
        return elapsed

    def stop(self) -> None:
        try:
            if self.rcon:
                try:
                    self.rcon.cmd("save-all")
                    self.rcon.cmd("stop")
                except Exception:
                    pass
                self.rcon.close()
                self.rcon = None
            elif self.proc and self.proc.stdin and self.proc.poll() is None:
                try:
                    self.proc.stdin.write("stop\n")
                    self.proc.stdin.flush()
                except Exception:
                    pass
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

    def status(self) -> dict:
        return parse_status(self.cmd("fg status"))

    def inspect(self, x: int, z: int) -> dict:
        return parse_inspect(self.cmd(f"fg inspect world {x} {z}"))

    def place_hopper(self, x: int, z: int) -> None:
        self.cmd(f"execute in minecraft:overworld run setblock {x} 70 {z} chest")
        self.cmd(f"execute in minecraft:overworld run setblock {x} 71 {z} hopper[facing=down]")
        self.cmd(f"execute in minecraft:overworld run setblock {x} 72 {z} chest")

    def hopper_totals(self, x: int, z: int) -> tuple[int, int, int, str]:
        src = self.cmd(f"data get block {x} 72 {z} Items")
        hopper = self.cmd(f"data get block {x} 71 {z} Items")
        dest = self.cmd(f"data get block {x} 70 {z} Items")
        raw = src + " || " + hopper + " || " + dest
        return count_items(src), count_items(hopper), count_items(dest), raw

    def clear_hopper(self, x: int, z: int) -> None:
        self.place_hopper(x, z)
        deadline = time.time() + 8
        while time.time() < deadline:
            for y in (70, 71, 72):
                self.cmd(f"data remove block {x} {y} {z} Items")
            src, hop, dest, _ = self.hopper_totals(x, z)
            if src == 0 and hop == 0 and dest == 0:
                return
            time.sleep(0.4)

    def fill_hopper(self, x: int, z: int, stacks: int = 4) -> None:
        for slot in range(stacks):
            self.cmd(f"item replace block {x} 72 {z} container.{slot} with minecraft:dirt 64")

    def kill_load(self) -> None:
        self.cmd("execute in minecraft:overworld run kill @e[tag=fgphase15]")
        self.cmd("execute in minecraft:overworld run kill @e[type=minecraft:item]")


def freeze_record() -> dict:
    jar = PLUGIN_JAR_SRC
    return {
        "frozenAt": now_iso(),
        "gitCommit": "NO_GIT_REPOSITORY",
        "jar": str(jar),
        "jarSha256": sha256_file(jar) if jar.exists() else None,
        "configYmlSha256": sha256_file(ROOT / "src" / "main" / "resources" / "config.yml"),
        "messagesYmlSha256": sha256_file(ROOT / "src" / "main" / "resources" / "messages.yml"),
        "pluginYmlSha256": sha256_file(ROOT / "src" / "main" / "resources" / "plugin.yml"),
        "buildGradleSha256": sha256_file(ROOT / "build.gradle.kts"),
        "farmGuardVersion": "1.0.0-beta.2",
        "gradle": "8.14",
        "paper": "paper-1.21.8-60",
        "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).splitlines()[0],
        "os": os.name,
    }


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
