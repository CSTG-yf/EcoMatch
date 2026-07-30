#!/usr/bin/env python3
"""Run the QA-02A permission and masking backend security gate."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = REPO_ROOT / "evaluation" / "qa02a_manifest.json"
DEFAULT_OUTPUT = REPO_ROOT / ".local-dev" / "bank-evaluation" / "qa02a-report.json"


class SecurityGateError(RuntimeError):
    """The QA-02A manifest or test output is invalid."""


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise SecurityGateError(f"manifest does not exist: {path}") from error
    except json.JSONDecodeError as error:
        raise SecurityGateError(f"manifest is not valid JSON: {path}") from error
    if not isinstance(value, dict):
        raise SecurityGateError("manifest must contain a JSON object")
    return value


def _validate_manifest(value: dict[str, Any]) -> dict[str, Any]:
    tests = value.get("tests")
    controls = value.get("controls")
    if value.get("task") != "QA-02A":
        raise SecurityGateError("manifest.task must be QA-02A")
    if not isinstance(tests, list) or not tests:
        raise SecurityGateError("manifest.tests must be a non-empty array")
    if not isinstance(controls, list) or not controls:
        raise SecurityGateError("manifest.controls must be a non-empty array")
    test_ids: set[str] = set()
    class_names: set[str] = set()
    for index, test in enumerate(tests):
        if not isinstance(test, dict):
            raise SecurityGateError(f"manifest.tests[{index}] must be an object")
        test_id = test.get("id")
        module = test.get("module")
        class_name = test.get("className")
        if not all(isinstance(item, str) and item for item in (test_id, module, class_name)):
            raise SecurityGateError(
                f"manifest.tests[{index}] requires id, module and className"
            )
        if test_id in test_ids or class_name in class_names:
            raise SecurityGateError(f"duplicate test id or class: {test_id}")
        test_ids.add(test_id)
        class_names.add(class_name)
    control_ids: set[str] = set()
    referenced: set[str] = set()
    for index, control in enumerate(controls):
        if not isinstance(control, dict):
            raise SecurityGateError(f"manifest.controls[{index}] must be an object")
        control_id = control.get("id")
        references = control.get("tests")
        if not isinstance(control_id, str) or not control_id or control_id in control_ids:
            raise SecurityGateError(f"invalid or duplicate control id: {control_id!r}")
        if not isinstance(references, list) or not references:
            raise SecurityGateError(f"control {control_id} must reference tests")
        unknown = [item for item in references if item not in test_ids]
        if unknown:
            raise SecurityGateError(
                f"control {control_id} references unknown tests: {unknown}"
            )
        control_ids.add(control_id)
        referenced.update(references)
    unreferenced = sorted(test_ids - referenced)
    if unreferenced:
        raise SecurityGateError(f"tests are not assigned to a control: {unreferenced}")
    return value


def _sanitize(value: Any) -> str:
    text = str(value).replace(str(REPO_ROOT), "<repo>")
    text = re.sub(
        r"(?i)\b[a-z]:[\\/](?:[^\s\"']+[\\/])*[^\s\"']*",
        "<path>",
        text,
    )
    text = re.sub(r"(https?://)[^/@\s]+:[^/@\s]+@", r"\1<redacted>@", text)
    text = re.sub(
        r"(?i)(password|token|secret|authorization)\s*[=:]\s*[^\s,;]+",
        r"\1=<redacted>",
        text,
    )
    return text[:1000]


def _run_maven(
    manifest: dict[str, Any],
    executable: str,
) -> tuple[int, int, str]:
    modules = sorted({test["module"] for test in manifest["tests"]})
    class_names = sorted(
        {test["className"].rsplit(".", 1)[-1] for test in manifest["tests"]}
    )
    command = [
        executable,
        "-q",
        "-pl",
        ",".join(modules),
        "-am",
        "test",
        f"-Dtest={','.join(class_names)}",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dspotless.skip=true",
    ]
    resolved = shutil.which(command[0]) or command[0]
    prepared = [resolved, *command[1:]]
    if os.name == "nt" and Path(resolved).suffix.lower() in {".bat", ".cmd"}:
        prepared = [
            os.environ.get("COMSPEC", "cmd.exe"),
            "/d",
            "/s",
            "/c",
            subprocess.list2cmdline([resolved, *command[1:]]),
        ]
    started = time.perf_counter()
    try:
        completed = subprocess.run(
            prepared,
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        diagnostic = _sanitize(completed.stderr or completed.stdout)
        return (
            completed.returncode,
            round((time.perf_counter() - started) * 1000),
            diagnostic,
        )
    except OSError as error:
        return 127, round((time.perf_counter() - started) * 1000), _sanitize(error)


def _report_directories(manifest: dict[str, Any]) -> list[Path]:
    return sorted(
        {
            REPO_ROOT / test["module"] / "target" / "surefire-reports"
            for test in manifest["tests"]
        }
    )


def _parse_test_reports(
    directories: list[Path],
    *,
    newer_than: float | None = None,
) -> dict[str, list[dict[str, Any]]]:
    cases: dict[str, list[dict[str, Any]]] = {}
    paths = sorted(
        path
        for directory in directories
        for path in directory.glob("TEST-*.xml")
        if newer_than is None or path.stat().st_mtime >= newer_than - 2
    )
    for path in paths:
        root = ET.parse(path).getroot()
        for node in root.findall("testcase"):
            class_name = node.get("classname")
            if not class_name:
                continue
            failure = node.find("failure")
            error = node.find("error")
            skipped = node.find("skipped")
            status = "PASS"
            message = None
            if failure is not None or error is not None:
                status = "FAIL"
                detail = failure if failure is not None else error
                message = _sanitize(detail.get("message") or detail.text or "test failed")
            elif skipped is not None:
                status = "SKIP"
            cases.setdefault(class_name, []).append(
                {
                    "name": node.get("name"),
                    "status": status,
                    "durationMs": round(float(node.get("time", "0")) * 1000),
                    **({"message": message} if message else {}),
                }
            )
    return cases


def build_report(
    manifest: dict[str, Any],
    cases_by_class: dict[str, list[dict[str, Any]]],
    *,
    command_exit_code: int,
    command_duration_ms: int,
    diagnostic: str = "",
) -> dict[str, Any]:
    manifest = _validate_manifest(manifest)
    tests: list[dict[str, Any]] = []
    results_by_id: dict[str, dict[str, Any]] = {}
    failures: list[dict[str, str]] = []
    for declared in manifest["tests"]:
        cases = cases_by_class.get(declared["className"], [])
        passed = sum(case["status"] == "PASS" for case in cases)
        failed = sum(case["status"] == "FAIL" for case in cases)
        skipped = sum(case["status"] == "SKIP" for case in cases)
        status = "PASS" if cases and failed == 0 and skipped == 0 else "FAIL"
        result = {
            "id": declared["id"],
            "module": declared["module"],
            "className": declared["className"],
            "caseCount": len(cases),
            "passed": passed,
            "failed": failed,
            "skipped": skipped,
            "durationMs": sum(case["durationMs"] for case in cases),
            "status": status,
        }
        tests.append(result)
        results_by_id[declared["id"]] = result
        if not cases:
            failures.append(
                {
                    "category": "TEST_CLASS_MISSING",
                    "subject": declared["id"],
                    "message": "declared test class did not produce a Surefire report",
                }
            )
        for case in cases:
            if case["status"] != "PASS":
                failures.append(
                    {
                        "category": (
                            "TEST_FAILURE"
                            if case["status"] == "FAIL"
                            else "TEST_SKIPPED"
                        ),
                        "subject": f"{declared['id']}#{case.get('name')}",
                        "message": case.get("message", case["status"]),
                    }
                )

    controls: list[dict[str, Any]] = []
    for control in manifest["controls"]:
        referenced = [results_by_id[test_id] for test_id in control["tests"]]
        status = (
            "PASS"
            if referenced and all(item["status"] == "PASS" for item in referenced)
            else "FAIL"
        )
        controls.append(
            {
                "id": control["id"],
                "description": control.get("description"),
                "testCount": len(referenced),
                "caseCount": sum(item["caseCount"] for item in referenced),
                "status": status,
            }
        )
    if command_exit_code != 0:
        failures.append(
            {
                "category": "MAVEN_FAILURE",
                "subject": "maven",
                "message": diagnostic or f"Maven exited with {command_exit_code}",
            }
        )
    status = (
        "PASS"
        if command_exit_code == 0
        and not failures
        and all(control["status"] == "PASS" for control in controls)
        else "FAIL"
    )
    return {
        "schemaVersion": "1.0",
        "task": "QA-02A",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "summary": {
            "controlCount": len(controls),
            "passedControlCount": sum(
                control["status"] == "PASS" for control in controls
            ),
            "testClassCount": len(tests),
            "passedTestClassCount": sum(test["status"] == "PASS" for test in tests),
            "caseCount": sum(test["caseCount"] for test in tests),
            "failureCount": len(failures),
        },
        "command": {
            "exitCode": command_exit_code,
            "durationMs": command_duration_ms,
        },
        "controls": controls,
        "tests": tests,
        "failures": failures,
    }


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
        manifest = _validate_manifest(_read_json(args.manifest.resolve()))
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
        )
        process_exit_code = 0 if report["status"] == "PASS" else 1
    except Exception as error:
        report = {
            "schemaVersion": "1.0",
            "task": "QA-02A",
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
                "task": "QA-02A",
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
