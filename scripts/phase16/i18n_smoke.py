#!/usr/bin/env python3
"""Paper smoke for FarmGuard i18n + admin UX. Not a 2h soak."""

from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from common import RESULTS, SERVER, Harness, ensure_results, now_iso  # noqa: E402
from debug_log_smoke import DEBUG_LOG, set_debug_enabled  # noqa: E402
from jsonl_gate import validate_file  # noqa: E402
import shutil

CONFIG = SERVER / "plugins" / "FarmGuard" / "config.yml"
LATEST = SERVER / "logs" / "latest.log"


def log(msg: str) -> None:
    print("[i18n-smoke] " + msg, flush=True)


def language_in_config() -> str:
    if not CONFIG.exists():
        return ""
    match = re.search(r"^language:\s*(\S+)", CONFIG.read_text(encoding="utf-8"), re.MULTILINE)
    return match.group(1) if match else ""


def run() -> dict:
    ensure_results()
    import phase15_common as p15

    props = SERVER / "server.properties"
    if props.exists():
        text = props.read_text(encoding="utf-8")
        text = re.sub(r"rcon.port=\d+", f"rcon.port={p15.RCON_PORT}", text)
        props.write_text(text, encoding="utf-8")

    out: dict = {"startedAt": now_iso(), "ok": False, "rconPort": p15.RCON_PORT}
    lang_dir = SERVER / "plugins" / "FarmGuard" / "lang"
    if lang_dir.exists():
        shutil.rmtree(lang_dir)
    h = Harness()
    try:
        h.start_server(reset_plugin_state=True)
        status_zh = h.cmd("fg status")
        top_zh = h.cmd("fg top")
        inspect_zh = h.cmd("fg inspect world 0 0")
        lang_zh = h.cmd("fg language")
        out["statusZh"] = status_zh
        out["topZh"] = top_zh
        out["inspectZh"] = inspect_zh
        out["languageZh"] = lang_zh
        out["defaultChinese"] = "模式" in status_zh and "简体中文" in lang_zh
        out["topUsesFacilities"] = "设施" in top_zh or "活跃" in top_zh
        out["inspectUsesDiagnosis"] = "区块诊断" in inspect_zh

        set_debug_enabled(True, 5)
        h.cmd("fg reload")
        switched = h.cmd("fg language en_US")
        status_en = h.cmd("fg status")
        top_en = h.cmd("fg top")
        inspect_en = h.cmd("fg inspect world 0 0")
        out["switched"] = switched
        out["statusEn"] = status_en
        out["topEn"] = top_en
        out["inspectEn"] = inspect_en
        out["englishAfterSwitch"] = "Mode:" in status_en and "English" in switched
        out["noChineseAfterSwitch"] = "模式" not in status_en
        out["englishTop"] = "Hotspots" in top_en and "Activity" in top_en
        out["englishInspect"] = "Chunk Inspection" in inspect_en and "Activity Score" in inspect_en
        out["configAfterEn"] = language_in_config()

        jsonl = validate_file(DEBUG_LOG) if DEBUG_LOG.exists() else {"ok": False, "exists": False}
        out["jsonl"] = jsonl
        types = jsonl.get("types") or {}
        out["debugLogNeutral"] = jsonl.get("ok") is True and "server_snapshot" in types

        h.stop_server()
        h.start_server(reset_plugin_state=False)
        status_en2 = h.cmd("fg status")
        lang_en2 = h.cmd("fg language")
        out["statusEnRestart"] = status_en2
        out["languageEnRestart"] = lang_en2
        out["englishPersisted"] = "Mode:" in status_en2 and "English" in lang_en2

        h.cmd("fg language zh_CN")
        h.stop_server()
        h.start_server(reset_plugin_state=False)
        status_zh2 = h.cmd("fg status")
        lang_zh2 = h.cmd("fg language")
        out["statusZhRestart"] = status_zh2
        out["languageZhRestart"] = lang_zh2
        out["chinesePersisted"] = "模式" in status_zh2 and "简体中文" in lang_zh2
        out["configFinal"] = language_in_config()

        out["ok"] = bool(
            out.get("defaultChinese")
            and out.get("englishAfterSwitch")
            and out.get("noChineseAfterSwitch")
            and out.get("englishPersisted")
            and out.get("chinesePersisted")
            and out.get("debugLogNeutral")
            and language_in_config() == "zh_CN"
        )
    finally:
        try:
            h.stop_server()
        except Exception:
            pass
        out["endedAt"] = now_iso()
        path = RESULTS / "i18n-smoke.json"
        path.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=list), encoding="utf-8")
        log("wrote " + str(path) + " ok=" + str(out.get("ok")))
    return out


if __name__ == "__main__":
    result = run()
    sys.exit(0 if result.get("ok") else 1)
