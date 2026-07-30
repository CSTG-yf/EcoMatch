#!/usr/bin/env python3
"""Run the QA-02B audit and security alert backend gate."""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from run_qa02a import (
    REPO_ROOT,
    _parse_test_reports,
    _read_json,
    _report_directories,
    _run_maven,
    _sanitize,
    _validate_manifest,
    build_report,
)


DEFAULT_MANIFEST = REPO_ROOT / "evaluation" / "qa02b_manifest.json"
DEFAULT_OUTPUT = REPO_ROOT / ".local-dev" / "bank-evaluation" / "qa02b-report.json"
TASK = "QA-02B"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--maven", default="mvn")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        manifest = _validate_manifest(_read_json(args.manifest.resolve()), TASK)
        started_at = time.time()
        exit_code, duration_ms, diagnostic = _run_maven(manifest, args.maven)
        cases = _parse_test_reports(
            _report_directories(manifest),
            newer_than=started_at,
        )
        report = build_report(
            manifest,
            cases,
            command_exit_code=exit_code,
            command_duration_ms=duration_ms,
            diagnostic=diagnostic,
            task=TASK,
        )
        process_exit_code = 0 if report["status"] == "PASS" else 1
    except Exception as error:
        report = {
            "schemaVersion": "1.0",
            "task": TASK,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "status": "FAIL",
            "summary": {"failureCount": 1},
            "failures": [
                {
                    "category": "RUNNER_FAILURE",
                    "subject": "runner",
                    "message": _sanitize(f"{type(error).__name__}: {error}"),
                }
            ],
        }
        process_exit_code = 2
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "task": TASK,
                "status": report["status"],
                "report": str(args.output),
                "summary": report.get("summary"),
            },
            ensure_ascii=False,
        )
    )
    return process_exit_code


if __name__ == "__main__":
    raise SystemExit(main())
