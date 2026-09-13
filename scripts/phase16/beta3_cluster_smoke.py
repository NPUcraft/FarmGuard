#!/usr/bin/env python3
"""Short Paper check: multi-chunk hopper cluster appears once in /fg top."""

from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from common import RESULTS, Harness, ensure_results, now_iso  # noqa: E402
from pressure import build_hopper_ring, clear_observers  # noqa: E402


def log(msg: str) -> None:
    print("[cluster-smoke] " + msg, flush=True)


def ranks(text: str) -> list[str]:
    return re.findall(r"^#\d+.*", text, re.MULTILINE)


def run() -> dict:
    ensure_results()
    out: dict = {"startedAt": now_iso(), "ok": False}
    h = Harness()
    try:
        h.start_server(reset_plugin_state=True)
        clear_observers(h, 0, 70, 0, pairs=10)
        clear_observers(h, 48, 70, 48, pairs=10)
        x, y, z = 15, 70, 0  # blocks 15/16 span chunk 0 and 1 (already forceloaded)
        h.prepare_platform(x, y, z, radius=10)
        h.cmd("forceload add 0 0")
        h.cmd("forceload add 1 0")
        for zoff in (0, 2, 4, 6, 8):
            build_hopper_ring(h, x, y, z + zoff)
        try:
            h.start_bot("FarmGuardBot01")
            h.tp_bot("FarmGuardBot01", x + 0.5, y, z + 0.5, wait=1.0)
        except Exception as exc:  # noqa: BLE001
            out["botError"] = str(exc)
        time.sleep(40)
        top = h.cmd("fg top")
        inspect_a = h.cmd("fg inspect world 0 0")
        inspect_b = h.cmd("fg inspect world 1 0")
        out["top"] = top
        out["inspect00"] = inspect_a
        out["inspect10"] = inspect_b
        out["ranks"] = ranks(top)
        member = any(token in top for token in ("2 个区块", "3 个区块", "4 个区块", "5 个区块"))
        storage = "存储" in top or "Storage" in top
        out["memberCountShown"] = member
        out["storageShown"] = storage
        out["ok"] = bool(member)
    finally:
        try:
            h.stop_server()
        except Exception:
            pass
        out["endedAt"] = now_iso()
        path = RESULTS / "beta3-cluster-smoke.json"
        path.write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
        log("wrote " + str(path) + " ok=" + str(out.get("ok")))
    return out


if __name__ == "__main__":
    result = run()
    sys.exit(0 if result.get("ok") else 1)
