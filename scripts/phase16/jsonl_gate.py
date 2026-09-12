#!/usr/bin/env python3
"""Validate FarmGuard debug.log JSON Lines. Reads the raw file, not Markdown."""

from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path


def validate_file(path: Path) -> dict:
    result = {
        "path": str(path),
        "exists": path.exists(),
        "lines": 0,
        "emptyLines": 0,
        "objects": 0,
        "invalid": [],
        "types": {},
        "schema": None,
        "sessions": set(),
        "endsWithNewline": False,
        "lastLineComplete": True,
    }
    if not path.exists():
        result["invalid"].append({"line": 0, "error": "file missing"})
        return result
    raw = path.read_bytes()
    result["endsWithNewline"] = raw.endswith(b"\n") or raw.endswith(b"\r\n")
    text = raw.decode("utf-8")
    lines = text.splitlines()
    result["lines"] = len(lines)
    if text and not result["endsWithNewline"] and lines:
        last = lines[-1].strip()
        if last:
            try:
                json.loads(last)
            except json.JSONDecodeError as exc:
                result["lastLineComplete"] = False
                result["invalid"].append({"line": len(lines), "error": f"incomplete last line: {exc}"})
    for index, line in enumerate(lines, start=1):
        if not line.strip():
            result["emptyLines"] += 1
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError as exc:
            result["invalid"].append({"line": index, "error": str(exc), "preview": line[:180]})
            continue
        if not isinstance(obj, dict):
            result["invalid"].append({"line": index, "error": "not a JSON object"})
            continue
        schema = obj.get("schema")
        session = obj.get("session")
        ts = obj.get("ts")
        typ = obj.get("type")
        if schema != 1:
            result["invalid"].append({"line": index, "error": f"schema={schema!r}"})
        if not session or not isinstance(session, str):
            result["invalid"].append({"line": index, "error": "session empty"})
        else:
            result["sessions"].add(session)
        if not typ or not isinstance(typ, str):
            result["invalid"].append({"line": index, "error": "type empty"})
        else:
            result["types"][typ] = result["types"].get(typ, 0) + 1
        if not isinstance(ts, str):
            result["invalid"].append({"line": index, "error": "ts missing"})
        else:
            try:
                datetime.fromisoformat(ts)
            except ValueError as exc:
                result["invalid"].append({"line": index, "error": f"ts not ISO-8601: {exc}"})
            if "T" not in ts or not any(ch in ts for ch in ("+", "Z")):
                result["invalid"].append({"line": index, "error": f"ts missing offset: {ts}"})
        result["objects"] += 1
        result["schema"] = 1 if schema == 1 else schema
    result["sessions"] = sorted(result["sessions"])
    result["ok"] = not result["invalid"] and result["objects"] > 0
    return result


def validate_dir(logs_dir: Path) -> dict:
    files = []
    if logs_dir.exists():
        files.extend(sorted(logs_dir.glob("debug.log")))
        files.extend(sorted(logs_dir.glob("debug.[0-9]*.log")))
    reports = [validate_file(path) for path in files]
    return {
        "ok": all(item.get("ok") for item in reports) if reports else False,
        "files": reports,
    }


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: jsonl_gate.py <debug.log|logs-dir>")
        return 2
    target = Path(sys.argv[1])
    payload = validate_dir(target) if target.is_dir() else {"ok": False, "files": [validate_file(target)]}
    if target.is_file():
        payload["ok"] = payload["files"][0]["ok"]
    print(json.dumps(payload, indent=2, ensure_ascii=False))
    return 0 if payload["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
