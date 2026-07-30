#!/usr/bin/env python3
"""Create QA-01A baselines and enforce the QA-01B release regression gate."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CURRENT = REPO_ROOT / ".local-dev" / "bank-evaluation" / "qa01a-report.json"
DEFAULT_BASELINE = REPO_ROOT / ".local-dev" / "bank-evaluation" / "qa01b-baseline.json"
DEFAULT_OUTPUT = REPO_ROOT / ".local-dev" / "bank-evaluation" / "qa01b-report.json"
DEFAULT_POLICY = REPO_ROOT / "evaluation" / "qa01b_policy.json"


class ReleaseGateError(RuntimeError):
    """The release gate input or policy is invalid."""


def _read_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ReleaseGateError(f"{label} does not exist: {path}") from error
    except json.JSONDecodeError as error:
        raise ReleaseGateError(f"{label} is not valid JSON: {path}") from error
    if not isinstance(value, dict):
        raise ReleaseGateError(f"{label} must contain a JSON object")
    return value


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _json_sha256(value: dict[str, Any]) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _qa01a_report(value: dict[str, Any], label: str) -> dict[str, Any]:
    if value.get("task") == "QA-01B-BASELINE":
        report = value.get("report")
        if not isinstance(report, dict):
            raise ReleaseGateError(f"{label} does not contain an embedded report")
        expected_hash = value.get("reportSha256")
        if not isinstance(expected_hash, str) or expected_hash != _json_sha256(report):
            raise ReleaseGateError(f"{label} embedded report integrity check failed")
    else:
        report = value
    if not isinstance(report, dict) or report.get("task") != "QA-01A":
        raise ReleaseGateError(f"{label} is not a QA-01A report or QA-01B baseline")
    if not isinstance(report.get("suites"), dict):
        raise ReleaseGateError(f"{label} does not contain QA-01A suites")
    return report


def _number(value: Any, path: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ReleaseGateError(f"{path} must be numeric")
    result = float(value)
    if not math.isfinite(result):
        raise ReleaseGateError(f"{path} must be finite")
    return result


def _policy(value: dict[str, Any]) -> dict[str, Any]:
    required_suites = value.get("requiredSuites")
    metric_rules = value.get("metrics")
    source_fields = value.get("sourceIdentityFields")
    if not isinstance(required_suites, list) or not all(
        isinstance(item, str) and item for item in required_suites
    ):
        raise ReleaseGateError("policy.requiredSuites must be a non-empty string array")
    if not isinstance(source_fields, list) or not all(
        isinstance(item, str) and item for item in source_fields
    ):
        raise ReleaseGateError("policy.sourceIdentityFields must be a string array")
    if not isinstance(metric_rules, list) or not metric_rules:
        raise ReleaseGateError("policy.metrics must be a non-empty array")
    seen: set[str] = set()
    for index, rule in enumerate(metric_rules):
        path = f"policy.metrics[{index}]"
        if not isinstance(rule, dict):
            raise ReleaseGateError(f"{path} must be an object")
        suite = rule.get("suite")
        metric = rule.get("metric")
        direction = rule.get("direction")
        if not isinstance(suite, str) or not isinstance(metric, str):
            raise ReleaseGateError(f"{path} requires suite and metric")
        key = f"{suite}.{metric}"
        if key in seen:
            raise ReleaseGateError(f"policy contains duplicate metric: {key}")
        seen.add(key)
        if direction not in {"higher", "lower"}:
            raise ReleaseGateError(f"{path}.direction must be higher or lower")
        for field in ("minimum", "maximum", "maxRegression", "maxRegressionRatio"):
            if field in rule:
                if _number(rule[field], f"{path}.{field}") < 0:
                    raise ReleaseGateError(f"{path}.{field} cannot be negative")
    return value


def _violation(
    category: str,
    subject: str,
    message: str,
) -> dict[str, str]:
    return {"category": category, "subject": subject, "message": message}


def _source_comparison(
    baseline: dict[str, Any],
    current: dict[str, Any],
    fields: list[str],
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    baseline_source = baseline.get("source") or {}
    current_source = current.get("source") or {}
    comparisons: list[dict[str, Any]] = []
    violations: list[dict[str, str]] = []
    for field in fields:
        baseline_value = baseline_source.get(field)
        current_value = current_source.get(field)
        matches = (
            baseline_value is not None
            and current_value is not None
            and baseline_value == current_value
        )
        comparisons.append(
            {
                "field": field,
                "baseline": baseline_value,
                "current": current_value,
                "status": "PASS" if matches else "FAIL",
            }
        )
        if not matches:
            violations.append(
                _violation(
                    "SOURCE_MISMATCH",
                    field,
                    f"baseline={baseline_value!r}, current={current_value!r}",
                )
            )
    baseline_mode = baseline.get("evaluationMode")
    current_mode = current.get("evaluationMode")
    if baseline_mode is not None or current_mode is not None:
        matches = baseline_mode == current_mode
        comparisons.append(
            {
                "field": "evaluationMode",
                "baseline": baseline_mode,
                "current": current_mode,
                "status": "PASS" if matches else "FAIL",
            }
        )
        if not matches:
            violations.append(
                _violation(
                    "SOURCE_MISMATCH",
                    "evaluationMode",
                    f"baseline={baseline_mode!r}, current={current_mode!r}",
                )
            )
    return comparisons, violations


def _metric_comparison(
    baseline: dict[str, Any],
    current: dict[str, Any],
    rule: dict[str, Any],
) -> tuple[dict[str, Any], list[dict[str, str]]]:
    suite = rule["suite"]
    metric = rule["metric"]
    subject = f"{suite}.{metric}"
    baseline_suite = baseline["suites"].get(suite)
    current_suite = current["suites"].get(suite)
    violations: list[dict[str, str]] = []
    if not isinstance(baseline_suite, dict) or metric not in baseline_suite:
        violations.append(
            _violation("METRIC_MISSING", subject, "metric is missing from baseline")
        )
        return {"path": subject, "status": "FAIL"}, violations
    if not isinstance(current_suite, dict) or metric not in current_suite:
        violations.append(
            _violation("METRIC_MISSING", subject, "metric is missing from current report")
        )
        return {"path": subject, "status": "FAIL"}, violations

    baseline_value = _number(baseline_suite[metric], f"baseline.{subject}")
    current_value = _number(current_suite[metric], f"current.{subject}")
    delta = current_value - baseline_value
    relative_change = delta / abs(baseline_value) if baseline_value else None
    direction = rule["direction"]

    minimum = rule.get("minimum")
    maximum = rule.get("maximum")
    if minimum is not None and current_value < float(minimum):
        violations.append(
            _violation(
                "MINIMUM_NOT_MET",
                subject,
                f"current={current_value:g} below minimum={float(minimum):g}",
            )
        )
    if maximum is not None and current_value > float(maximum):
        violations.append(
            _violation(
                "MAXIMUM_EXCEEDED",
                subject,
                f"current={current_value:g} above maximum={float(maximum):g}",
            )
        )

    regression = baseline_value - current_value if direction == "higher" else delta
    max_regression = rule.get("maxRegression")
    if max_regression is not None and regression > float(max_regression):
        violations.append(
            _violation(
                "REGRESSION",
                subject,
                f"regression={regression:g} exceeds allowed={float(max_regression):g}",
            )
        )
    max_ratio = rule.get("maxRegressionRatio")
    regression_ratio = regression / abs(baseline_value) if baseline_value else None
    if (
        max_ratio is not None
        and (
            (regression_ratio is not None and regression_ratio > float(max_ratio))
            or (baseline_value == 0 and regression > 0)
        )
    ):
        ratio_message = (
            f"{regression_ratio:.6f}" if regression_ratio is not None else "infinite"
        )
        violations.append(
            _violation(
                "REGRESSION",
                subject,
                f"regressionRatio={ratio_message} exceeds allowed={float(max_ratio):g}",
            )
        )

    return (
        {
            "path": subject,
            "direction": direction,
            "baseline": baseline_value,
            "current": current_value,
            "delta": round(delta, 6),
            "relativeChange": (
                round(relative_change, 6) if relative_change is not None else None
            ),
            **({"minimum": minimum} if minimum is not None else {}),
            **({"maximum": maximum} if maximum is not None else {}),
            **(
                {"maxRegression": max_regression}
                if max_regression is not None
                else {}
            ),
            **(
                {"maxRegressionRatio": max_ratio}
                if max_ratio is not None
                else {}
            ),
            "status": "PASS" if not violations else "FAIL",
        },
        violations,
    )


def _stage_timings(report: dict[str, Any]) -> dict[str, float]:
    timings: dict[str, float] = {}
    for command in report.get("commands") or []:
        if not isinstance(command, dict) or not isinstance(command.get("stage"), str):
            continue
        duration = command.get("durationMs")
        if isinstance(duration, (int, float)) and not isinstance(duration, bool):
            timings[command["stage"]] = float(duration)
    return timings


def _compare_stage_timings(
    baseline: dict[str, Any],
    current: dict[str, Any],
) -> list[dict[str, Any]]:
    baseline_timings = _stage_timings(baseline)
    current_timings = _stage_timings(current)
    rows: list[dict[str, Any]] = []
    for stage in sorted(baseline_timings.keys() | current_timings.keys()):
        baseline_value = baseline_timings.get(stage)
        current_value = current_timings.get(stage)
        delta = (
            round(current_value - baseline_value, 3)
            if baseline_value is not None and current_value is not None
            else None
        )
        rows.append(
            {
                "stage": stage,
                "baselineDurationMs": baseline_value,
                "currentDurationMs": current_value,
                "deltaMs": delta,
            }
        )
    return rows


def _error_cases(report: dict[str, Any]) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for failure in report.get("stageFailures") or []:
        if isinstance(failure, dict):
            cases.append(
                {
                    "suite": failure.get("stage", "unknown"),
                    "id": failure.get("stage", "unknown"),
                    "category": "STAGE_FAILURE",
                    "message": str(failure.get("message", "stage failed"))[:1000],
                }
            )
    for suite_name, suite in (report.get("suites") or {}).items():
        if not isinstance(suite, dict):
            continue
        for failure in suite.get("failures") or []:
            if not isinstance(failure, dict):
                continue
            cases.append(
                {
                    "suite": suite_name,
                    "id": failure.get("id", suite_name),
                    "category": failure.get("category", "EVALUATION_FAILURE"),
                    "message": str(failure.get("message", "evaluation failed"))[:1000],
                }
            )
    return cases


def evaluate_release(
    baseline: dict[str, Any],
    current: dict[str, Any],
    policy: dict[str, Any],
    *,
    baseline_version: str,
    current_version: str,
) -> dict[str, Any]:
    baseline = _qa01a_report(baseline, "baseline")
    current = _qa01a_report(current, "current report")
    policy = _policy(policy)
    violations: list[dict[str, str]] = []

    if baseline.get("status") != "PASS":
        violations.append(
            _violation(
                "INVALID_BASELINE",
                "baseline.status",
                f"baseline status is {baseline.get('status')!r}, expected 'PASS'",
            )
        )
    if current.get("status") != "PASS":
        violations.append(
            _violation(
                "CURRENT_REPORT_FAILED",
                "current.status",
                f"current status is {current.get('status')!r}, expected 'PASS'",
            )
        )

    for suite_name in policy["requiredSuites"]:
        for label, report in (("baseline", baseline), ("current", current)):
            suite = report["suites"].get(suite_name)
            if not isinstance(suite, dict):
                violations.append(
                    _violation(
                        "SUITE_MISSING",
                        f"{label}.{suite_name}",
                        f"required suite {suite_name!r} is missing",
                    )
                )
            elif suite.get("status") != "PASS":
                violations.append(
                    _violation(
                        "SUITE_FAILED",
                        f"{label}.{suite_name}",
                        f"suite status is {suite.get('status')!r}, expected 'PASS'",
                    )
                )

    sources, source_violations = _source_comparison(
        baseline,
        current,
        policy["sourceIdentityFields"],
    )
    violations.extend(source_violations)
    metrics: list[dict[str, Any]] = []
    for rule in policy["metrics"]:
        comparison, metric_violations = _metric_comparison(
            baseline,
            current,
            rule,
        )
        metrics.append(comparison)
        violations.extend(metric_violations)

    status = "PASS" if not violations else "FAIL"
    return {
        "schemaVersion": "1.0",
        "task": "QA-01B",
        "generatedAt": _utc_now(),
        "status": status,
        "releaseDecision": "ALLOW" if status == "PASS" else "BLOCK",
        "versions": {
            "baseline": baseline_version,
            "current": current_version,
        },
        "summary": {
            "metricCount": len(metrics),
            "passedMetricCount": sum(row["status"] == "PASS" for row in metrics),
            "regressionCount": sum(
                violation["category"] == "REGRESSION" for violation in violations
            ),
            "violationCount": len(violations),
            "errorCaseCount": len(_error_cases(current)),
        },
        "sourceComparison": sources,
        "metricComparison": metrics,
        "stageTimingComparison": _compare_stage_timings(baseline, current),
        "violations": violations,
        "errorCases": _error_cases(current),
    }


def create_baseline(
    report: dict[str, Any],
    *,
    source_path: Path,
    version: str,
) -> dict[str, Any]:
    report = _qa01a_report(report, "current report")
    if report.get("status") != "PASS":
        raise ReleaseGateError("only a passing QA-01A report can become a baseline")
    return {
        "schemaVersion": "1.0",
        "task": "QA-01B-BASELINE",
        "savedAt": _utc_now(),
        "version": version,
        "sourceReportSha256": _sha256(source_path),
        "reportSha256": _json_sha256(report),
        "report": report,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    baseline = subparsers.add_parser("baseline", help="store a passing QA-01A report")
    baseline.add_argument("--current", type=Path, default=DEFAULT_CURRENT)
    baseline.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    baseline.add_argument("--version", required=True)

    compare = subparsers.add_parser("compare", help="compare current report to baseline")
    compare.add_argument("--current", type=Path, default=DEFAULT_CURRENT)
    compare.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    compare.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    compare.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    compare.add_argument("--baseline-version")
    compare.add_argument("--current-version", required=True)
    return parser


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "baseline":
            current = _read_json(args.current.resolve(), "current report")
            value = create_baseline(
                current,
                source_path=args.current.resolve(),
                version=args.version,
            )
            _write_json(args.baseline.resolve(), value)
            result = {
                "task": "QA-01B",
                "action": "baseline",
                "status": "PASS",
                "baseline": str(args.baseline.resolve()),
                "version": args.version,
            }
            exit_code = 0
        else:
            baseline_value = _read_json(args.baseline.resolve(), "baseline")
            current = _read_json(args.current.resolve(), "current report")
            policy = _read_json(args.policy.resolve(), "policy")
            baseline_version = args.baseline_version
            if baseline_version is None:
                baseline_version = str(
                    baseline_value.get("version") or args.baseline.stem
                )
            value = evaluate_release(
                baseline_value,
                current,
                policy,
                baseline_version=baseline_version,
                current_version=args.current_version,
            )
            _write_json(args.output.resolve(), value)
            result = {
                "task": "QA-01B",
                "action": "compare",
                "status": value["status"],
                "releaseDecision": value["releaseDecision"],
                "report": str(args.output.resolve()),
                "summary": value["summary"],
            }
            exit_code = 0 if value["status"] == "PASS" else 1
    except Exception as error:
        result = {
            "task": "QA-01B",
            "action": args.command,
            "status": "FAIL",
            "releaseDecision": "BLOCK",
            "error": f"{type(error).__name__}: {error}",
        }
        exit_code = 2
    print(json.dumps(result, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
