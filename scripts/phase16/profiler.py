#!/usr/bin/env python3
"""Spark + JFR profiler helpers for Phase 16. Sample share, not exact CPU%."""

from __future__ import annotations

import json
import os
import re
import subprocess
import time
from pathlib import Path

from common import RESULTS, Harness, now_iso


def spark_help(h: Harness) -> str:
    texts = []
    for command in ("spark help", "spark profiler --help", "spark"):
        try:
            texts.append(command + " => " + h.cmd(command))
        except Exception as exc:  # noqa: BLE001
            texts.append(command + " => ERR " + str(exc))
    path = RESULTS / "profiler" / "spark-help.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n\n".join(texts), encoding="utf-8")
    return "\n\n".join(texts)


def _jcmd_pid(line: str) -> int | None:
    try:
        return int(line.split()[0])
    except (ValueError, IndexError):
        return None


def resolve_java_pid(h: Harness) -> int | None:
    """Never attach JFR to the unrelated paper-1.21.8.jar process."""
    direct = h.server.proc.pid if h.server.proc else None
    try:
        listing = subprocess.check_output(["jcmd", "-l"], text=True, encoding="utf-8", errors="replace")
    except Exception:
        return direct
    lines = listing.splitlines()
    if direct:
        for line in lines:
            if _jcmd_pid(line) == direct and "paper-1.21.8.jar" not in line.lower():
                return direct
    for line in lines:
        lower = line.lower()
        if "paper-1.21.8.jar" in lower:
            continue
        if "paper.jar" in lower or "test-server" in lower:
            pid = _jcmd_pid(line)
            if pid:
                return pid
    return direct


def _profile_jfc() -> str:
    java_home = Path(os.environ.get("JAVA_HOME") or r"C:\Environment\Java\jdk-21.0.8")
    candidate = java_home / "lib" / "jfr" / "profile.jfc"
    return str(candidate) if candidate.exists() else "profile"


def run_jfr(h: Harness, name: str, seconds: int) -> dict:
    pid = resolve_java_pid(h)
    out = RESULTS / "profiler" / f"{name}.jfr"
    out.parent.mkdir(parents=True, exist_ok=True)
    if not pid:
        return {"ok": False, "error": "no-java-pid"}
    settings = _profile_jfc()
    start = subprocess.run(
        [
            "jcmd",
            str(pid),
            "JFR.start",
            f"name={name}",
            f"filename={out}",
            f"duration={seconds}s",
            f"settings={settings}",
            "disk=true",
            "dumponexit=true",
        ],
        capture_output=True,
        text=True,
    )
    time.sleep(seconds + 3)
    dump = subprocess.run(
        ["jcmd", str(pid), "JFR.dump", f"name={name}", f"filename={out}"],
        capture_output=True,
        text=True,
    )
    parsed = parse_jfr(out)
    cmd_line = subprocess.run(
        ["jcmd", str(pid), "VM.command_line"],
        capture_output=True,
        text=True,
    )
    parsed.update({
        "startOut": (start.stdout or "") + (start.stderr or ""),
        "dumpOut": (dump.stdout or "") + (dump.stderr or ""),
        "file": str(out),
        "seconds": seconds,
        "javaPid": pid,
        "settings": settings,
        "commandLine": (cmd_line.stdout or "")[-1500:],
    })
    return parsed


def parse_jfr(path: Path) -> dict:
    if not path.exists() or path.stat().st_size < 32:
        return {"ok": False, "error": "jfr-missing"}
    proc = subprocess.run(
        ["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64", str(path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    text = proc.stdout or ""
    (RESULTS / "profiler" / (path.stem + "-execution.txt")).write_text(text[:4_000_000], encoding="utf-8")
    blocks = text.split("jdk.ExecutionSample")
    samples = max(0, len(blocks) - 1)
    server_blocks = [block for block in blocks[1:] if 'sampledThread = "Server thread"' in block]
    server_samples = len(server_blocks)

    def count_blocks(needle: str, source: list[str] | None = None) -> int:
        hay = source if source is not None else blocks[1:]
        return sum(1 for block in hay if needle in block)

    probe_samples = count_blocks("dev.farmguard.testprobe", server_blocks)
    product_samples = sum(
        1
        for block in server_blocks
        if "dev.farmguard." in block and "dev.farmguard.testprobe" not in block
    )
    paper_samples = count_blocks("io.papermc", server_blocks) + count_blocks("ca.spottedleaf", server_blocks)
    minecraft_samples = count_blocks("net.minecraft", server_blocks)
    methods: dict[str, int] = {}
    for match in re.finditer(r"(dev\.farmguard\.(?!testprobe)[A-Za-z0-9_$.]+)", text):
        methods[match.group(1)] = methods.get(match.group(1), 0) + 1
    probe_methods: dict[str, int] = {}
    for match in re.finditer(r"(dev\.farmguard\.testprobe\.[A-Za-z0-9_$.]+)", text):
        probe_methods[match.group(1)] = probe_methods.get(match.group(1), 0) + 1
    top = sorted(methods.items(), key=lambda item: item[1], reverse=True)[:20]
    top_probe = sorted(probe_methods.items(), key=lambda item: item[1], reverse=True)[:10]
    denom = server_samples or samples
    share = (product_samples / denom) if denom else 0.0
    probe_share = (probe_samples / denom) if denom else 0.0
    alloc_proc = subprocess.run(
        ["jfr", "print", "--events", "jdk.ObjectAllocationSample", "--stack-depth", "32", str(path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    alloc_text = alloc_proc.stdout or ""
    alloc_fg = len(re.findall(r"dev\.farmguard\.(?!testprobe)", alloc_text))
    alloc_probe = len(re.findall(r"dev\.farmguard\.testprobe", alloc_text))
    return {
        "ok": True,
        "executionSamples": samples,
        "serverThreadExecutionSamples": server_samples,
        "farmGuardExecutionSamples": product_samples,
        "farmGuardSampleShare": share,
        "farmGuardProductSampleShare": share,
        "testProbeExecutionSamples": probe_samples,
        "testProbeSampleShare": probe_share,
        "paperLikeServerSamples": paper_samples,
        "minecraftServerSamples": minecraft_samples,
        "topFarmGuardMethods": top,
        "topTestProbeMethods": top_probe,
        "allocationFarmGuardSamples": alloc_fg,
        "allocationTestProbeSamples": alloc_probe,
        "shareDenominator": "server-thread ExecutionSample count (not exact CPU%)",
        "note": "sample share, not exact CPU%. TestProbe burner is excluded from FarmGuard product share. Default jfr print stack-depth is 5; this parser uses 64.",
    }


def run_profiler(h: Harness, seconds: int = 60) -> dict:
    row = h.begin("profiler.jfr_idle", f"JFR {seconds}s idle profile; report sample share not exact CPU%")
    help_text = spark_help(h)
    spark_url = None
    try:
        h.cmd("spark profiler start --timeout " + str(seconds))
    except Exception:
        pass
    idle = run_jfr(h, "fg-idle", seconds)
    h.attach_status(row)
    row.extra.update({"sparkHelp": help_text[-1500:], "jfr": idle})
    share = float(idle.get("farmGuardSampleShare") or 0)
    if share > 0.25:
        h.p1.append("FarmGuard idle JFR sample share > 25%")
        row.finish("FAIL", f"idle sample share {share:.4f}")
    elif idle.get("ok"):
        row.finish("PASS", f"idle FarmGuard sample share={share:.4f} samples={idle.get('farmGuardExecutionSamples')}")
    else:
        row.finish("INCOMPLETE", str(idle.get("error")))
    (RESULTS / "profiler" / "jfr-idle.json").write_text(json.dumps(idle, indent=2), encoding="utf-8")
    return {"jfr": idle, "sparkHelpCaptured": True, "spark": spark_url}
