# -*- coding: utf-8 -*-
"""Apply best_bank_on.json to local H2 metadata (service must be stopped).

Usage (repo root):
  .local-dev/eval-venv/Scripts/python.exe evaluation/bank_nl2sql/repro/apply_best_bank_on.py
  .local-dev/eval-venv/Scripts/python.exe evaluation/bank_nl2sql/repro/apply_best_bank_on.py --dry-run
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
REPRO = Path(__file__).resolve().parent
BEST = REPRO / "best_bank_on.json"
JAVA = ROOT / ".local-dev/jdk/jdk-21.0.11+10/bin/java.exe"
DB = ROOT / ".local-dev/state/semantic"


def find_h2() -> Path:
    runtime = ROOT / ".local-dev/runtime"
    matches = list(runtime.rglob("h2-2.2.224.jar"))
    if not matches:
        raise FileNotFoundError("h2-2.2.224.jar not found under .local-dev/runtime")
    return matches[0]


def h2_sql(sql: str) -> str:
    if not JAVA.is_file():
        raise FileNotFoundError(f"java not found: {JAVA}")
    cmd = [
        str(JAVA),
        "-cp",
        str(find_h2()),
        "org.h2.tools.Shell",
        "-url",
        f"jdbc:h2:file:{DB.as_posix()};DATABASE_TO_UPPER=false;IFEXISTS=TRUE",
        "-user",
        "root",
        "-password",
        "semantic",
        "-sql",
        sql,
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        raise RuntimeError(r.stdout + "\n" + r.stderr)
    return r.stdout


def load_best() -> dict:
    return json.loads(BEST.read_text(encoding="utf-8"))


def patch_system_parameters(params: list[dict], wanted: dict[str, str]) -> tuple[list[dict], list[str]]:
    touched: list[str] = []
    by_name = {p.get("name"): p for p in params if isinstance(p, dict)}
    for name, value in wanted.items():
        if name in by_name:
            by_name[name]["value"] = str(value)
            touched.append(name)
        else:
            # Append if missing so new ablation knobs still land in H2.
            params.append(
                {
                    "name": name,
                    "value": str(value),
                    "comment": "best_bank_on repro",
                }
            )
            touched.append(name + " (appended)")
    return params, touched


def patch_agent_chat_apps(cfg: dict, apps: dict) -> list[str]:
    touched: list[str] = []
    for key, patch in apps.items():
        if not isinstance(patch, dict):
            continue
        entry = cfg.get(key)
        if not isinstance(entry, dict):
            # Create minimal app entry when absent.
            cfg[key] = {"enable": bool(patch.get("enable", False))}
            if "chatModelId" in patch:
                cfg[key]["chatModelId"] = patch["chatModelId"]
            touched.append(key + " (created)")
            continue
        if "enable" in patch:
            entry["enable"] = bool(patch["enable"])
            touched.append(f"{key}.enable={entry['enable']}")
        if "chatModelId" in patch and patch["chatModelId"] is not None:
            entry["chatModelId"] = patch["chatModelId"]
            touched.append(f"{key}.chatModelId={entry['chatModelId']}")
    return touched


def main() -> int:
    parser = argparse.ArgumentParser(description="Apply best bank-on runtime params to local H2")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--agent-id", type=int, default=None)
    args = parser.parse_args()

    best = load_best()
    wanted = best["systemParameters"]
    agent_id = args.agent_id or int(best.get("agent", {}).get("agentId", 33))
    jvm = best.get("jvmSystemProperties", {})

    print("=== best_bank_on ===")
    print("file:", BEST)
    print("asOf:", best.get("asOf"))
    print("agentId:", agent_id)
    print("systemParameters:")
    for k, v in wanted.items():
        print(f"  {k} = {v}")
    print("jvmSystemProperties:")
    for k, v in jvm.items():
        print(f"  -D{k}={v}")

    if args.dry_run:
        print("dry-run: no DB writes")
        return 0

    mv = Path(str(DB) + ".mv.db")
    if not mv.is_file():
        raise FileNotFoundError(f"missing H2 file: {mv}")

    # Export system parameters
    params_csv = ROOT / ".local-dev/state/best_bank_on_sysparams.csv"
    params_csv.parent.mkdir(parents=True, exist_ok=True)
    h2_sql(
        f"CALL CSVWRITE('{params_csv.as_posix()}', "
        f"'SELECT parameters FROM s2_system_config WHERE id=1', "
        f"'charset=UTF-8 fieldSeparator=| fieldDelimiter=');"
    )
    lines = params_csv.read_text(encoding="utf-8").splitlines()
    if len(lines) < 2:
        raise RuntimeError("empty s2_system_config parameters export")
    params_body = lines[1].replace('""', '"')
    params = json.loads(params_body)
    if not isinstance(params, list):
        raise RuntimeError("parameters JSON is not a list")
    params, sys_touched = patch_system_parameters(params, wanted)
    params_json = json.dumps(params, ensure_ascii=False, separators=(",", ":"))
    params_sql = params_json.replace("'", "''")

    # Agent chat apps
    agent_csv = ROOT / ".local-dev/state/best_bank_on_agent.csv"
    h2_sql(
        f"CALL CSVWRITE('{agent_csv.as_posix()}', "
        f"'SELECT chat_model_config FROM s2_agent WHERE id={agent_id}', "
        f"'charset=UTF-8 fieldSeparator=| fieldDelimiter=');"
    )
    agent_lines = agent_csv.read_text(encoding="utf-8").splitlines()
    agent_touched: list[str] = []
    agent_sql = None
    if len(agent_lines) >= 2 and agent_lines[1].strip():
        raw = agent_lines[1].replace('""', '"')
        try:
            cfg = json.loads(raw)
            if isinstance(cfg, dict):
                agent_touched = patch_agent_chat_apps(cfg, best.get("agentChatApps") or {})
                agent_sql = json.dumps(cfg, ensure_ascii=False, separators=(",", ":")).replace(
                    "'", "''"
                )
        except json.JSONDecodeError as exc:
            print("WARN: agent chat_model_config not JSON, skip agent patch:", exc)

    sql_parts = [
        f"UPDATE s2_system_config SET parameters='{params_sql}' WHERE id=1;",
    ]
    if agent_sql is not None:
        sql_parts.append(
            f"UPDATE s2_agent SET chat_model_config='{agent_sql}', "
            f"updated_at=CURRENT_TIMESTAMP, updated_by='best_bank_on' WHERE id={agent_id};"
        )
    sql_parts.append(f"SELECT id FROM s2_agent WHERE id={agent_id};")
    out = h2_sql("\n".join(sql_parts))
    print("H2 apply ok")
    print("system touched:", ", ".join(sys_touched))
    if agent_touched:
        print("agent touched:", ", ".join(agent_touched))
    else:
        print("agent touched: (none or skipped)")
    print(out.strip())

    print("\n# Restart standalone with JVM flags, then:")
    print(
        ".local-dev\\eval-venv\\Scripts\\python.exe evaluation/bank_nl2sql/run_supersonic_eval.py "
        "evaluation/bank_nl2sql --split train --base-url http://127.0.0.1:9080 "
        f"--agent-id {agent_id} --ids-file evaluation/bank_nl2sql/repro/ids-train-hard20.txt "
        "--runtime-mode bank-on --concurrency 1 --timeout-seconds 300 --no-resume "
        "--output .local-dev/bank-nl2sql/ablation/repro-hard20.json"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001 — CLI surface
        print("ERROR:", exc, file=sys.stderr)
        raise SystemExit(1)
