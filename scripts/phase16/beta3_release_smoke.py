#!/usr/bin/env python3
"""Final Beta.3 jar smoke: startup, zh/en language, top/inspect, debug, hopper 64, stop."""

from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from common import RESULTS, SERVER, Harness, ensure_results, now_iso  # noqa: E402
from debug_log_smoke import DEBUG_LOG, set_debug_enabled  # noqa: E402
from jsonl_gate import validate_file  # noqa: E402
import phase15_common as p15  # noqa: E402

LOGS_DIR = SERVER / "plugins" / "FarmGuard" / "logs"
LATEST = SERVER / "logs" / "latest.log"
ENUM_LEAK = re.compile(
    r"\b(STORAGE|SUSTAINED_LOAD|EXCESSIVE_HOPPERS|POSSIBLE|STRONG|"
    r"REDSTONE_MACHINE|UNKNOWN_AUTOMATION)\b"
)


def log(msg: str) -> None:
    print("[beta3-smoke] " + msg, flush=True)


def leaks(text: str) -> list[str]:
    return sorted(set(ENUM_LEAK.findall(p15.strip_color(text or ""))))


def run() -> dict:
    ensure_results()
    props = SERVER / "server.properties"
    if props.exists():
        text = props.read_text(encoding="utf-8")
        text = re.sub(r"rcon.port=\d+", f"rcon.port={p15.RCON_PORT}", text)
        props.write_text(text, encoding="utf-8")

    out: dict = {
        "startedAt": now_iso(),
        "ok": False,
        "jar": str(p15.PLUGIN_JAR_SRC),
        "rconPort": p15.RCON_PORT,
    }
    if not p15.PLUGIN_JAR_SRC.exists():
        raise RuntimeError("Missing release jar: " + str(p15.PLUGIN_JAR_SRC))
    out["jarSha256"] = p15.sha256_file(p15.PLUGIN_JAR_SRC)

    h = Harness()
    try:
        h.start_server(reset_plugin_state=True)
        latest = LATEST.read_text(encoding="utf-8", errors="replace") if LATEST.exists() else ""
        out["bannerVersion"] = "v1.0.0-beta.3" in latest
        status_zh = h.cmd("fg status")
        top_zh = h.cmd("fg top")
        inspect_zh = h.cmd("fg inspect world 0 0")
        lang_zh = h.cmd("fg language")
        out["startupStatus"] = status_zh
        out["topZh"] = top_zh
        out["inspectZh"] = inspect_zh
        out["languageZh"] = lang_zh
        out["defaultChinese"] = "模式" in status_zh and "仅监控" in status_zh and "简体中文" in lang_zh
        out["debugOff"] = "诊断日志: 关闭" in status_zh

        switched = h.cmd("fg language en_US")
        status_en = h.cmd("fg status")
        top_en = h.cmd("fg top")
        inspect_en = h.cmd("fg inspect world 0 0")
        out["switchedEn"] = switched
        out["statusEn"] = status_en
        out["topEn"] = top_en
        out["inspectEn"] = inspect_en
        out["englishAfterSwitch"] = "Mode:" in status_en and "English" in switched
        back = h.cmd("fg language zh_CN")
        out["switchedZh"] = back
        out["backToChinese"] = "简体中文" in back

        set_debug_enabled(True, 5)
        out["reloadOn"] = h.cmd("fg reload")
        time.sleep(8)
        status_on = h.cmd("fg status")
        out["statusOn"] = status_on
        out["debugOn"] = "诊断日志: 开启" in status_on

        snapshot_ok = False
        deadline = time.time() + 20
        while time.time() < deadline:
            if DEBUG_LOG.exists():
                report = validate_file(DEBUG_LOG)
                types = report.get("types") or {}
                if report.get("ok") and types.get("server_snapshot", 0) >= 1:
                    out["jsonl"] = report
                    snapshot_ok = True
                    break
            time.sleep(2)
        if not snapshot_ok and DEBUG_LOG.exists():
            out["jsonl"] = validate_file(DEBUG_LOG)
        out["snapshot"] = snapshot_ok

        hopper = h.isolated_hopper_conservation(64)
        out["hopper64"] = hopper

        set_debug_enabled(False, 5)
        h.cmd("fg reload")
        time.sleep(1.5)
        status_off = h.cmd("fg status")
        out["statusFinalOff"] = status_off
        out["debugFinalOff"] = "诊断日志: 关闭" in status_off
        leak_hits = leaks("\n".join([status_zh, top_zh, inspect_zh, status_en, top_en, inspect_en]))
        out["enumLeaks"] = leak_hits
        out["ok"] = bool(
            out["bannerVersion"]
            and out["defaultChinese"]
            and out["englishAfterSwitch"]
            and out["backToChinese"]
            and out["debugOff"]
            and out["debugOn"]
            and snapshot_ok
            and hopper.get("ok")
            and out["debugFinalOff"]
            and not leak_hits
        )
    finally:
        try:
            h.stop_server()
        except Exception:
            pass
        out["endedAt"] = now_iso()
        path = RESULTS / "beta3-release-smoke.json"
        path.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=list), encoding="utf-8")
        log("wrote " + str(path) + " ok=" + str(out.get("ok")))
    return out


if __name__ == "__main__":
    result = run()
    sys.exit(0 if result.get("ok") else 1)
