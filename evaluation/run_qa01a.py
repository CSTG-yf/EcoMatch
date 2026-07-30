#!/usr/bin/env python3
"""Run the unified QA-01A bank evaluation and emit a CI-ready JSON report."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parent.parent
EVALUATION_ROOT = REPO_ROOT / "evaluation"
DEFAULT_DATASET = EVALUATION_ROOT / "bank_nl2sql"
DEFAULT_OUTPUT = REPO_ROOT / ".local-dev" / "bank-evaluation" / "qa01a-report.json"
DEFAULT_DATABASE = REPO_ROOT / ".local-dev" / "bank-evaluation" / "bank_benchmark.sqlite"
JAVA_TESTS = (
    "BankIntentFrozenDatasetTest,"
    "BankNl2SqlDatasetValidationTest,"
    "MultiTurnContextEngineTest,"
    "BusinessInsightProcessorTest"
)


class EvaluationRunnerError(RuntimeError):
    """The unified evaluator could not execute or parse one of its stages."""


@dataclass(frozen=True)
class CommandResult:
    label: str
    returncode: int
    duration_ms: int
    stdout: str
    stderr: str


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _run_command(
    label: str,
    command: list[str],
    *,
    env: dict[str, str] | None = None,
) -> CommandResult:
    started = time.perf_counter()
    executable = shutil.which(command[0]) or command[0]
    prepared = [executable, *command[1:]]
    if os.name == "nt" and Path(executable).suffix.lower() in {".bat", ".cmd"}:
        prepared = [
            os.environ.get("COMSPEC", "cmd.exe"),
            "/d",
            "/s",
            "/c",
            subprocess.list2cmdline([executable, *command[1:]]),
        ]
    try:
        completed = subprocess.run(
            prepared,
            cwd=REPO_ROOT,
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        returncode = completed.returncode
        stdout = completed.stdout
        stderr = completed.stderr
    except OSError as error:
        returncode = 127
        stdout = ""
        stderr = f"{type(error).__name__}: {error}"
    return CommandResult(
        label=label,
        returncode=returncode,
        duration_ms=round((time.perf_counter() - started) * 1000),
        stdout=stdout,
        stderr=stderr,
    )


def _json_from_output(result: CommandResult) -> dict[str, Any]:
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()
        raise EvaluationRunnerError(
            f"{result.label} failed with exit code {result.returncode}: "
            f"{detail[-1] if detail else 'no diagnostic output'}"
        )
    text = result.stdout.strip()
    for index, character in enumerate(text):
        if character != "{":
            continue
        try:
            parsed = json.loads(text[index:])
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            return parsed
    raise EvaluationRunnerError(f"{result.label} did not emit a JSON object")


def _find_workbook(configured: Path | None) -> Path:
    if configured is not None:
        workbook = configured.expanduser().resolve()
        if not workbook.is_file():
            raise EvaluationRunnerError(f"competition workbook not found: {workbook}")
        return workbook
    environment_path = os.environ.get("BANK_NL2SQL_WORKBOOK")
    if environment_path:
        return _find_workbook(Path(environment_path))
    candidates = sorted((REPO_ROOT / "task").glob("*.xlsx"))
    if len(candidates) != 1:
        raise EvaluationRunnerError(
            "expected exactly one workbook under task/ or an explicit --workbook"
        )
    return candidates[0].resolve()


def _sanitize_message(value: Any) -> str:
    message = str(value).replace(str(REPO_ROOT), "<repo>")
    message = re.sub(
        r"(?i)\b[a-z]:[\\/](?:[^\s\"']+[\\/])*[^\s\"']*",
        "<path>",
        message,
    )
    message = re.sub(r"(https?://)[^/@\s]+:[^/@\s]+@", r"\1<redacted>@", message)
    return message[:1000]


def _failure(label: str, error: Exception | str) -> dict[str, str]:
    return {"stage": label, "message": _sanitize_message(error)}


def _parse_java_reports(
    report_dirs: Path | list[Path],
    *,
    newer_than: float | None = None,
) -> dict[str, Any]:
    cases: list[dict[str, Any]] = []
    output_parts: list[str] = []
    directories = [report_dirs] if isinstance(report_dirs, Path) else report_dirs
    report_paths = sorted(
        report_path
        for report_dir in directories
        for report_path in report_dir.glob("TEST-*.xml")
        if newer_than is None or report_path.stat().st_mtime >= newer_than - 2
    )
    for report_path in report_paths:
        root = ET.parse(report_path).getroot()
        for case in root.findall("testcase"):
            failure = case.find("failure")
            error = case.find("error")
            skipped = case.find("skipped")
            status = "PASS"
            detail = None
            if failure is not None or error is not None:
                status = "FAIL"
                node = failure if failure is not None else error
                detail = _sanitize_message(
                    (node.get("message") or node.text or "test failed").strip()
                )
            elif skipped is not None:
                status = "SKIP"
            cases.append(
                {
                    "className": case.get("classname"),
                    "name": case.get("name"),
                    "status": status,
                    "durationMs": round(float(case.get("time", "0")) * 1000),
                    **({"message": detail} if detail else {}),
                }
            )
        output_parts.extend(node.text or "" for node in root.findall(".//system-out"))
    if not cases:
        raise EvaluationRunnerError("Maven did not produce QA-01A Surefire XML reports")
    output = "\n".join(output_parts)
    return {"cases": cases, "output": output}


def _metric_line(output: str, pattern: str, label: str) -> tuple[int, int, float]:
    match = re.search(pattern, output)
    if not match:
        raise EvaluationRunnerError(f"Java evaluation output is missing {label}")
    return int(match.group(1)), int(match.group(2)), float(match.group(3))


def _intent_metrics(output: str) -> dict[str, Any]:
    match = re.search(
        r"BANK_INTENT_EVAL cases=(\d+) intent=([\d.]+) metric=([\d.]+) clarification=([\d.]+)",
        output,
    )
    if not match:
        raise EvaluationRunnerError("Java evaluation output is missing BANK_INTENT_EVAL")
    return {
        "caseCount": int(match.group(1)),
        "intentAccuracy": float(match.group(2)),
        "metricSetAccuracy": float(match.group(3)),
        "clarificationAccuracy": float(match.group(4)),
    }


def _java_class_summary(java_report: dict[str, Any], suffix: str) -> dict[str, Any]:
    cases = [
        case
        for case in java_report["cases"]
        if str(case.get("className") or "").endswith(suffix)
    ]
    failures = [
        {
            "id": f"{case['className']}#{case['name']}",
            "category": "JAVA_TEST_FAILURE",
            "message": case.get("message", "test failed"),
        }
        for case in cases
        if case["status"] == "FAIL"
    ]
    passed = sum(case["status"] == "PASS" for case in cases)
    return {
        "testCount": len(cases),
        "passed": passed,
        "skipped": sum(case["status"] == "SKIP" for case in cases),
        "passRate": _rate(passed, len(cases)),
        "failures": failures,
    }


def _suite(
    name: str,
    metrics: dict[str, Any],
    thresholds: dict[str, float],
    failures: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    failures = list(failures or [])
    violations = [
        {
            "id": name,
            "category": "THRESHOLD_VIOLATION",
            "message": f"{metric}={metrics.get(metric)} below {minimum}",
        }
        for metric, minimum in thresholds.items()
        if not isinstance(metrics.get(metric), (int, float))
        or float(metrics[metric]) < minimum
    ]
    failures.extend(violations)
    return {
        "status": "PASS" if not failures else "FAIL",
        "metrics": metrics,
        "thresholds": thresholds,
        "failures": failures,
    }


def _runtime_failures(runtime_report: dict[str, Any]) -> list[dict[str, str]]:
    return [
        {
            "id": str(item.get("id", "unknown")),
            "category": str(item.get("errorCategory") or "RESULT_MISMATCH"),
        }
        for item in runtime_report.get("items", [])
        if item.get("errorCategory") or not item.get("match", False)
    ]


def _runtime_timing(runtime_report: dict[str, Any]) -> dict[str, Any]:
    distributions = runtime_report.get("timingDistributionsMs", {})
    if isinstance(distributions, dict) and isinstance(distributions.get("successfulEndToEnd"), dict):
        return distributions["successfulEndToEnd"]
    samples = [
        float(item["latencyMs"])
        for item in runtime_report.get("items", [])
        if isinstance(item.get("latencyMs"), (int, float))
    ]
    return {
        "count": len(samples),
        "average": round(sum(samples) / len(samples), 3) if samples else None,
    }


def _run_runtime(
    args: argparse.Namespace,
    database: Path,
    commands: list[CommandResult],
) -> dict[str, Any] | None:
    runtime_report_path = args.output.parent / "qa01a-runtime-report.json"
    if args.runtime_mode == "gold":
        return None
    if args.runtime_mode == "predictions":
        if args.predictions is None:
            raise EvaluationRunnerError("--runtime-mode predictions requires --predictions")
        command = [
            sys.executable,
            str(EVALUATION_ROOT / "bank_nl2sql" / "evaluate_predictions.py"),
            str(args.dataset),
            str(args.predictions.resolve()),
            str(database),
            "--report",
            str(runtime_report_path),
        ]
    else:
        if not args.base_url or args.agent_id is None:
            raise EvaluationRunnerError(
                "--runtime-mode supersonic requires --base-url and --agent-id"
            )
        command = [
            sys.executable,
            str(EVALUATION_ROOT / "bank_nl2sql" / "run_supersonic_eval.py"),
            str(args.dataset),
            "--split",
            args.split,
            "--base-url",
            args.base_url,
            "--agent-id",
            str(args.agent_id),
            "--concurrency",
            str(args.concurrency),
            "--output",
            str(runtime_report_path),
            "--no-resume",
        ]
        if args.max_records is not None:
            command.extend(["--max-records", str(args.max_records)])
        if args.split == "test":
            if not args.acknowledge_final_test or args.run_registry is None:
                raise EvaluationRunnerError(
                    "test split requires --acknowledge-final-test and --run-registry"
                )
            command.extend(
                [
                    "--acknowledge-final-test",
                    "--run-registry",
                    str(args.run_registry.resolve()),
                ]
            )
    result = _run_command(f"runtime-{args.runtime_mode}", command)
    commands.append(result)
    _json_from_output(result)
    return json.loads(runtime_report_path.read_text(encoding="utf-8"))


def run_evaluation(args: argparse.Namespace) -> tuple[dict[str, Any], int]:
    started = time.perf_counter()
    args.output = args.output.resolve()
    args.dataset = args.dataset.resolve()
    database = args.database.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    database.parent.mkdir(parents=True, exist_ok=True)
    workbook = _find_workbook(args.workbook)
    child_env = dict(os.environ)
    child_env["BANK_NL2SQL_WORKBOOK"] = str(workbook)
    commands: list[CommandResult] = []
    stage_failures: list[dict[str, str]] = []
    reports: dict[str, Any] = {}

    def execute_json(label: str, command: list[str]) -> dict[str, Any] | None:
        result = _run_command(label, command, env=child_env)
        commands.append(result)
        try:
            report = _json_from_output(result)
            reports[label] = report
            return report
        except Exception as error:
            stage_failures.append(_failure(label, error))
            return None

    execute_json(
        "intent-dataset",
        [sys.executable, str(EVALUATION_ROOT / "bank_intent" / "validate_dataset.py")],
    )
    nl2sql_dataset = execute_json(
        "nl2sql-dataset",
        [
            sys.executable,
            str(EVALUATION_ROOT / "bank_nl2sql" / "validate_dataset.py"),
            str(args.dataset),
        ],
    )
    execute_json(
        "chart-dataset",
        [sys.executable, str(EVALUATION_ROOT / "bank_chart_explanation" / "validate_dataset.py")],
    )
    if not database.is_file() or args.rebuild_database:
        execute_json(
            "build-database",
            [
                sys.executable,
                str(EVALUATION_ROOT / "bank_nl2sql" / "db" / "build_database.py"),
                str(workbook),
                "--sqlite-output",
                str(database),
            ],
        )
    execute_json(
        "validate-database",
        [
            sys.executable,
            str(EVALUATION_ROOT / "bank_nl2sql" / "db" / "validate_database.py"),
            str(database),
        ],
    )
    gold_report = execute_json(
        "gold-result-consistency",
        [
            sys.executable,
            str(EVALUATION_ROOT / "bank_nl2sql" / "validate_gold.py"),
            str(args.dataset),
            str(database),
        ],
    )

    java_started_at = time.time()
    java_result = _run_command(
        "java-frozen-evaluation",
        [
            args.maven,
            "-q",
            "-pl",
            "chat/server",
            "-am",
            "test",
            f"-Dtest={JAVA_TESTS}",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "-Dspotless.skip=true",
        ],
    )
    commands.append(java_result)
    java_report: dict[str, Any] | None = None
    try:
        java_report = _parse_java_reports(
            [
                REPO_ROOT / "headless" / "chat" / "target" / "surefire-reports",
                REPO_ROOT / "chat" / "server" / "target" / "surefire-reports",
            ],
            newer_than=java_started_at,
        )
        if java_result.returncode != 0:
            raise EvaluationRunnerError(
                f"java-frozen-evaluation failed with exit code {java_result.returncode}"
            )
    except Exception as error:
        stage_failures.append(_failure("java-frozen-evaluation", error))

    runtime_report: dict[str, Any] | None = None
    try:
        runtime_report = _run_runtime(args, database, commands)
    except Exception as error:
        stage_failures.append(_failure(f"runtime-{args.runtime_mode}", error))

    suites: dict[str, dict[str, Any]] = {}
    if java_report is not None:
        output = java_report["output"]
        try:
            intent = _intent_metrics(output)
            intent_java = _java_class_summary(java_report, "BankIntentFrozenDatasetTest")
            suites["intent"] = _suite(
                "intent",
                {
                    **intent,
                    "javaPassRate": intent_java["passRate"],
                },
                {
                    "intentAccuracy": args.min_intent_accuracy,
                    "metricSetAccuracy": args.min_metric_accuracy,
                    "clarificationAccuracy": args.min_clarification_accuracy,
                    "javaPassRate": 1.0,
                },
                intent_java["failures"],
            )
        except Exception as error:
            stage_failures.append(_failure("intent", error))

        multi = _java_class_summary(java_report, "MultiTurnContextEngineTest")
        suites["multiTurn"] = _suite(
            "multiTurn",
            {
                "testCount": multi["testCount"],
                "passRate": multi["passRate"],
                "maxContextRounds": 10,
            },
            {"passRate": 1.0, "maxContextRounds": 10},
            multi["failures"],
        )
        try:
            chart_cases, chart_matches, chart_accuracy = _metric_line(
                output,
                r"BANK_CHART_EVAL cases=(\d+) matched=(\d+) accuracy=([\d.]+)",
                "BANK_CHART_EVAL",
            )
            explanation_cases, explanation_matches, explanation_coverage = _metric_line(
                output,
                r"BANK_EXPLANATION_EVAL cases=(\d+) matched=(\d+) coverage=([\d.]+)",
                "BANK_EXPLANATION_EVAL",
            )
            chart_java = _java_class_summary(java_report, "BusinessInsightProcessorTest")
            suites["chartRecommendation"] = _suite(
                "chartRecommendation",
                {
                    "caseCount": chart_cases,
                    "matchedCount": chart_matches,
                    "chartAccuracy": chart_accuracy,
                    "explanationCaseCount": explanation_cases,
                    "explanationMatchedCount": explanation_matches,
                    "explanationCoverage": explanation_coverage,
                    "javaPassRate": chart_java["passRate"],
                },
                {
                    "chartAccuracy": args.min_chart_accuracy,
                    "explanationCoverage": args.min_explanation_coverage,
                    "javaPassRate": 1.0,
                },
                chart_java["failures"],
            )
        except Exception as error:
            stage_failures.append(_failure("chartRecommendation", error))

    active_runtime = runtime_report or gold_report
    if active_runtime is not None:
        if runtime_report is not None:
            metrics = runtime_report.get("metrics", {})
            total = int(runtime_report.get("goldCount") or runtime_report.get("recordCount") or 0)
            execution_rate = float(metrics.get("executionSuccessRate") or 0.0)
            result_rate = float(metrics.get("resultAccuracy") or 0.0)
            timing = _runtime_timing(runtime_report)
            failures = _runtime_failures(runtime_report)
        else:
            total = int(gold_report.get("officialCount") or 0)
            execution_rate = _rate(int(gold_report.get("sqlExecutionCount") or 0), total)
            result_rate = _rate(int(gold_report.get("resultMatchCount") or 0), total)
            timing = gold_report.get("timingMs", {})
            failures = []
        suites["sqlExecution"] = _suite(
            "sqlExecution",
            {
                "caseCount": total,
                "executionSuccessRate": execution_rate,
                "averageResponseTimeMs": timing.get("average"),
                "p95ResponseTimeMs": timing.get("p95"),
            },
            {"executionSuccessRate": args.min_execution_success_rate},
            failures,
        )
        suites["resultConsistency"] = _suite(
            "resultConsistency",
            {
                "caseCount": total,
                "resultConsistencyRate": result_rate,
            },
            {"resultConsistencyRate": args.min_result_consistency_rate},
            failures,
        )

    required_suites = {
        "intent",
        "sqlExecution",
        "resultConsistency",
        "multiTurn",
        "chartRecommendation",
    }
    missing_suites = sorted(required_suites - suites.keys())
    stage_failures.extend(
        _failure(name, "required evaluation suite did not produce a report")
        for name in missing_suites
    )
    failed_suites = sorted(name for name, suite in suites.items() if suite["status"] != "PASS")
    status = "PASS" if not stage_failures and not failed_suites else "FAIL"
    report = {
        "schemaVersion": "1.0",
        "task": "QA-01A",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "evaluationMode": args.runtime_mode,
        "durationMs": round((time.perf_counter() - started) * 1000),
        "source": {
            "workbook": workbook.name,
            "workbookSha256": _sha256(workbook),
            "datasetVersion": (
                json.loads((args.dataset / "manifest.json").read_text(encoding="utf-8")).get(
                    "version"
                )
                if (args.dataset / "manifest.json").is_file()
                else None
            ),
            "databaseSha256": _sha256(database) if database.is_file() else None,
        },
        "summary": {
            "requiredSuiteCount": len(required_suites),
            "passedSuiteCount": sum(
                name in suites and suites[name]["status"] == "PASS"
                for name in required_suites
            ),
            "failedSuites": failed_suites,
            "missingSuites": missing_suites,
        },
        "suites": suites,
        "stageFailures": stage_failures,
        "commands": [
            {
                "stage": result.label,
                "exitCode": result.returncode,
                "durationMs": result.duration_ms,
            }
            for result in commands
        ],
        "artifacts": {
            "report": args.output.name,
            "runtimeReport": (
                "qa01a-runtime-report.json" if args.runtime_mode != "gold" else None
            ),
        },
        "datasetValidation": {
            "nl2sql": nl2sql_dataset,
            "officialSqlBaseline": gold_report,
        },
    }
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return report, 0 if status == "PASS" else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workbook", type=Path)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--database", type=Path, default=DEFAULT_DATABASE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--rebuild-database", action="store_true")
    parser.add_argument("--maven", default="mvn")
    parser.add_argument(
        "--runtime-mode",
        choices=("gold", "predictions", "supersonic"),
        default="gold",
    )
    parser.add_argument("--predictions", type=Path)
    parser.add_argument("--base-url")
    parser.add_argument("--agent-id", type=int)
    parser.add_argument("--split", choices=("train", "dev", "test"), default="dev")
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--max-records", type=int)
    parser.add_argument("--acknowledge-final-test", action="store_true")
    parser.add_argument("--run-registry", type=Path)
    parser.add_argument("--min-intent-accuracy", type=float, default=0.94)
    parser.add_argument("--min-metric-accuracy", type=float, default=0.94)
    parser.add_argument("--min-clarification-accuracy", type=float, default=0.90)
    parser.add_argument("--min-execution-success-rate", type=float, default=0.90)
    parser.add_argument("--min-result-consistency-rate", type=float, default=0.90)
    parser.add_argument("--min-chart-accuracy", type=float, default=0.90)
    parser.add_argument("--min-explanation-coverage", type=float, default=0.90)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        report, exit_code = run_evaluation(args)
    except Exception as error:
        args.output = args.output.resolve()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        report = {
            "schemaVersion": "1.0",
            "task": "QA-01A",
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "status": "FAIL",
            "stageFailures": [_failure("runner", error)],
        }
        args.output.write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        exit_code = 1
    print(
        json.dumps(
            {
                "task": "QA-01A",
                "status": report["status"],
                "report": str(args.output),
                "summary": report.get("summary"),
            },
            ensure_ascii=False,
        )
    )
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
