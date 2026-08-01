#!/usr/bin/env python3
"""Run the QA-02C repository-wide security release gate."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from run_qa02a import (
    REPO_ROOT,
    _parse_test_reports,
    _read_json,
    _report_directories,
    _run_maven,
    _sanitize,
    build_report as build_java_report,
)


DEFAULT_MANIFEST = REPO_ROOT / "evaluation" / "qa02c_manifest.json"
DEFAULT_OUTPUT = REPO_ROOT / ".local-dev" / "bank-evaluation" / "qa02c-report.json"
TASK = "QA-02C"


class FullChainGateError(RuntimeError):
    """The QA-02C manifest or evidence is invalid."""


def validate_manifest(value: dict[str, Any]) -> dict[str, Any]:
    if value.get("task") != TASK:
        raise FullChainGateError(f"manifest.task must be {TASK}")
    evidence = value.get("evidence")
    controls = value.get("controls")
    if not isinstance(evidence, list) or not evidence:
        raise FullChainGateError("manifest.evidence must be a non-empty array")
    if not isinstance(controls, list) or not controls:
        raise FullChainGateError("manifest.controls must be a non-empty array")

    evidence_ids: set[str] = set()
    java_classes: set[str] = set()
    for index, item in enumerate(evidence):
        if not isinstance(item, dict):
            raise FullChainGateError(f"manifest.evidence[{index}] must be an object")
        evidence_id = item.get("id")
        evidence_type = item.get("type")
        if not isinstance(evidence_id, str) or not evidence_id or evidence_id in evidence_ids:
            raise FullChainGateError(f"invalid or duplicate evidence id: {evidence_id!r}")
        if evidence_type not in {"gate", "java", "command"}:
            raise FullChainGateError(f"unsupported evidence type: {evidence_type!r}")
        if evidence_type == "gate":
            if not all(isinstance(item.get(key), str) and item.get(key) for key in ("script", "reportTask")):
                raise FullChainGateError(f"gate evidence {evidence_id} requires script and reportTask")
        elif evidence_type == "java":
            if not all(isinstance(item.get(key), str) and item.get(key) for key in ("module", "className")):
                raise FullChainGateError(f"java evidence {evidence_id} requires module and className")
            if item["className"] in java_classes:
                raise FullChainGateError(f"duplicate Java class: {item['className']}")
            java_classes.add(item["className"])
        else:
            command = item.get("command")
            if not isinstance(command, list) or not command or not all(isinstance(token, str) and token for token in command):
                raise FullChainGateError(f"command evidence {evidence_id} requires command tokens")
            cwd = item.get("cwd", ".")
            if not isinstance(cwd, str) or not cwd:
                raise FullChainGateError(f"command evidence {evidence_id} has invalid cwd")
        evidence_ids.add(evidence_id)

    control_ids: set[str] = set()
    referenced: set[str] = set()
    for index, control in enumerate(controls):
        if not isinstance(control, dict):
            raise FullChainGateError(f"manifest.controls[{index}] must be an object")
        control_id = control.get("id")
        references = control.get("evidence")
        if not isinstance(control_id, str) or not control_id or control_id in control_ids:
            raise FullChainGateError(f"invalid or duplicate control id: {control_id!r}")
        if not isinstance(references, list) or not references:
            raise FullChainGateError(f"control {control_id} must reference evidence")
        unknown = [reference for reference in references if reference not in evidence_ids]
        if unknown:
            raise FullChainGateError(f"control {control_id} references unknown evidence: {unknown}")
        control_ids.add(control_id)
        referenced.update(references)
    unreferenced = sorted(evidence_ids - referenced)
    if unreferenced:
        raise FullChainGateError(f"evidence is not assigned to a control: {unreferenced}")
    return value


def _prepare_command(command: list[str]) -> list[str]:
    resolved = shutil.which(command[0]) or command[0]
    prepared = [resolved, *command[1:]]
    if os.name == "nt" and Path(resolved).suffix.lower() in {".bat", ".cmd"}:
        return [
            os.environ.get("COMSPEC", "cmd.exe"),
            "/d",
            "/s",
            "/c",
            subprocess.list2cmdline(prepared),
        ]
    return prepared


def run_command(command: list[str], cwd: Path) -> tuple[int, int, str]:
    started = time.perf_counter()
    try:
        completed = subprocess.run(
            _prepare_command(command),
            cwd=cwd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        output = completed.stderr or completed.stdout
        return completed.returncode, round((time.perf_counter() - started) * 1000), _sanitize(output)
    except OSError as error:
        return 127, round((time.perf_counter() - started) * 1000), _sanitize(error)


def _gate_evidence(item: dict[str, Any], output_dir: Path) -> dict[str, Any]:
    report_path = output_dir / f"{item['id']}.json"
    script_path = (REPO_ROOT / item["script"]).resolve()
    try:
        script_path.relative_to(REPO_ROOT)
    except ValueError as error:
        raise FullChainGateError(f"gate script leaves repository: {item['id']}") from error
    exit_code, duration_ms, diagnostic = run_command(
        [sys.executable, str(script_path), "--output", str(report_path)], REPO_ROOT
    )
    report: dict[str, Any] = {}
    try:
        report = _read_json(report_path)
    except Exception as error:
        diagnostic = _sanitize(f"{diagnostic} {error}")
    valid_report = report.get("task") == item["reportTask"] and report.get("status") == "PASS"
    status = "PASS" if exit_code == 0 and valid_report else "FAIL"
    result = {
        "id": item["id"],
        "type": "gate",
        "task": item["reportTask"],
        "status": status,
        "durationMs": duration_ms,
        "summary": report.get("summary", {}),
    }
    if status == "FAIL":
        result["message"] = diagnostic or "prerequisite gate failed or produced an invalid report"
    return result


def _command_evidence(item: dict[str, Any]) -> dict[str, Any]:
    cwd = (REPO_ROOT / item.get("cwd", ".")).resolve()
    try:
        cwd.relative_to(REPO_ROOT)
    except ValueError as error:
        raise FullChainGateError(f"command cwd leaves repository: {item['id']}") from error
    exit_code, duration_ms, diagnostic = run_command(item["command"], cwd)
    result = {
        "id": item["id"],
        "type": "command",
        "status": "PASS" if exit_code == 0 else "FAIL",
        "durationMs": duration_ms,
        "exitCode": exit_code,
    }
    if exit_code != 0:
        result["message"] = diagnostic or f"command exited with {exit_code}"
    return result


def java_results_from_report(report: dict[str, Any], diagnostic: str = "") -> list[dict[str, Any]]:
    global_failure = report.get("status") != "PASS"
    results = []
    for test in report.get("tests", []):
        result = {**test, "type": "java"}
        if global_failure:
            result["status"] = "FAIL"
        if result["status"] != "PASS":
            matching = [
                failure["message"]
                for failure in report.get("failures", [])
                if failure["subject"].startswith(test["id"])
            ]
            result["message"] = matching[0] if matching else diagnostic or "Java security evidence failed"
        results.append(result)
    return results


def _java_evidence(items: list[dict[str, Any]], maven: str) -> list[dict[str, Any]]:
    if not items:
        return []
    manifest = {
        "schemaVersion": "1.0",
        "task": TASK,
        "tests": [
            {"id": item["id"], "module": item["module"], "className": item["className"]}
            for item in items
        ],
        "controls": [
            {
                "id": "java-cross-chain",
                "description": "QA-02C Java cross-chain evidence",
                "tests": [item["id"] for item in items],
            }
        ],
    }
    started_at = time.time()
    exit_code, duration_ms, diagnostic = _run_maven(manifest, maven)
    cases = _parse_test_reports(_report_directories(manifest), newer_than=started_at)
    report = build_java_report(
        manifest,
        cases,
        command_exit_code=exit_code,
        command_duration_ms=duration_ms,
        diagnostic=diagnostic,
        task=TASK,
    )
    return java_results_from_report(report, diagnostic)


def build_report(manifest: dict[str, Any], evidence: list[dict[str, Any]]) -> dict[str, Any]:
    manifest = validate_manifest(manifest)
    by_id = {item["id"]: item for item in evidence}
    failures: list[dict[str, str]] = []
    for declared in manifest["evidence"]:
        result = by_id.get(declared["id"])
        if result is None:
            failures.append({"category": "EVIDENCE_MISSING", "subject": declared["id"], "message": "declared evidence did not produce a result"})
        elif result.get("status") != "PASS":
            failures.append({"category": "EVIDENCE_FAILURE", "subject": declared["id"], "message": _sanitize(result.get("message", "evidence failed"))})

    controls = []
    for control in manifest["controls"]:
        results = [by_id.get(reference) for reference in control["evidence"]]
        status = "PASS" if all(result and result.get("status") == "PASS" for result in results) else "FAIL"
        controls.append({
            "id": control["id"],
            "description": control.get("description"),
            "evidenceCount": len(results),
            "status": status,
        })
    status = "PASS" if not failures and all(control["status"] == "PASS" for control in controls) else "FAIL"
    return {
        "schemaVersion": "1.0",
        "task": TASK,
        "scope": "REPOSITORY",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "environmentGateRequired": True,
        "summary": {
            "controlCount": len(controls),
            "passedControlCount": sum(control["status"] == "PASS" for control in controls),
            "evidenceCount": len(manifest["evidence"]),
            "passedEvidenceCount": sum(item.get("status") == "PASS" for item in evidence),
            "javaCaseCount": sum(item.get("caseCount", 0) for item in evidence if item.get("type") == "java"),
            "failureCount": len(failures),
        },
        "controls": controls,
        "evidence": evidence,
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
    evidence_results: list[dict[str, Any]] = []
    try:
        manifest = validate_manifest(_read_json(args.manifest.resolve()))
        java_items = [item for item in manifest["evidence"] if item["type"] == "java"]
        prerequisite_dir = REPO_ROOT / ".local-dev" / "bank-evaluation" / "qa02c-evidence"
        prerequisite_dir.mkdir(parents=True, exist_ok=True)
        for item in manifest["evidence"]:
            if item["type"] == "gate":
                evidence_results.append(_gate_evidence(item, prerequisite_dir))
        evidence_results.extend(_java_evidence(java_items, args.maven))
        for item in manifest["evidence"]:
            if item["type"] == "command":
                evidence_results.append(_command_evidence(item))
        report = build_report(manifest, evidence_results)
        process_exit_code = 0 if report["status"] == "PASS" else 1
    except Exception as error:
        report = {
            "schemaVersion": "1.0",
            "task": TASK,
            "scope": "REPOSITORY",
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "status": "FAIL",
            "environmentGateRequired": True,
            "summary": {"failureCount": 1},
            "failures": [{"category": "RUNNER_FAILURE", "subject": "runner", "message": _sanitize(f"{type(error).__name__}: {error}")}],
        }
        process_exit_code = 2
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"task": TASK, "status": report["status"], "scope": report.get("scope"), "report": str(args.output), "summary": report.get("summary")}, ensure_ascii=False))
    return process_exit_code


if __name__ == "__main__":
    raise SystemExit(main())
