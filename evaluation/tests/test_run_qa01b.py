#!/usr/bin/env python3
"""Unit tests for the QA-01B version comparison and release gate."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from copy import deepcopy
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from run_qa01b import (  # noqa: E402
    ReleaseGateError,
    create_baseline,
    evaluate_release,
    main,
)


def _report() -> dict:
    return {
        "schemaVersion": "1.0",
        "task": "QA-01A",
        "status": "PASS",
        "evaluationMode": "predictions",
        "source": {
            "datasetVersion": "0.1.0",
            "workbookSha256": "workbook-hash",
        },
        "commands": [
            {"stage": "intent", "durationMs": 100},
            {"stage": "runtime-predictions", "durationMs": 200},
        ],
        "suites": {
            "intent": {
                "status": "PASS",
                "intentAccuracy": 0.98,
                "metricSetAccuracy": 0.97,
                "clarificationAccuracy": 0.95,
                "failures": [],
            },
            "sqlExecution": {
                "status": "PASS",
                "executionSuccessRate": 0.96,
                "averageResponseTimeMs": 100,
                "p95ResponseTimeMs": 200,
                "failures": [],
            },
            "resultConsistency": {
                "status": "PASS",
                "resultConsistencyRate": 0.95,
                "failures": [],
            },
            "multiTurn": {
                "status": "PASS",
                "passRate": 1.0,
                "failures": [],
            },
            "chartRecommendation": {
                "status": "PASS",
                "chartAccuracy": 0.95,
                "explanationCoverage": 0.96,
                "failures": [],
            },
        },
        "stageFailures": [],
    }


def _policy() -> dict:
    policy_path = ROOT / "qa01b_policy.json"
    return json.loads(policy_path.read_text(encoding="utf-8"))


class Qa01bReleaseGateTest(unittest.TestCase):
    def test_identical_reports_pass_and_include_stage_timings(self) -> None:
        report = _report()
        result = evaluate_release(
            report,
            deepcopy(report),
            _policy(),
            baseline_version="v1",
            current_version="v2",
        )
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["releaseDecision"], "ALLOW")
        self.assertEqual(result["summary"]["passedMetricCount"], 10)
        self.assertEqual(result["stageTimingComparison"][0]["deltaMs"], 0.0)

    def test_accuracy_regression_blocks_release(self) -> None:
        baseline = _report()
        current = deepcopy(baseline)
        current["suites"]["intent"]["intentAccuracy"] = 0.97
        result = evaluate_release(
            baseline,
            current,
            _policy(),
            baseline_version="v1",
            current_version="v2",
        )
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["releaseDecision"], "BLOCK")
        self.assertTrue(
            any(
                item["category"] == "REGRESSION"
                and item["subject"] == "intent.intentAccuracy"
                for item in result["violations"]
            )
        )

    def test_latency_regression_over_twenty_percent_blocks_release(self) -> None:
        baseline = _report()
        current = deepcopy(baseline)
        current["suites"]["sqlExecution"]["p95ResponseTimeMs"] = 241
        result = evaluate_release(
            baseline,
            current,
            _policy(),
            baseline_version="v1",
            current_version="v2",
        )
        self.assertTrue(
            any(
                item["category"] == "REGRESSION"
                and item["subject"] == "sqlExecution.p95ResponseTimeMs"
                for item in result["violations"]
            )
        )

    def test_source_mismatch_blocks_release(self) -> None:
        baseline = _report()
        current = deepcopy(baseline)
        current["source"]["datasetVersion"] = "0.2.0"
        result = evaluate_release(
            baseline,
            current,
            _policy(),
            baseline_version="v1",
            current_version="v2",
        )
        self.assertTrue(
            any(
                item["category"] == "SOURCE_MISMATCH"
                and item["subject"] == "datasetVersion"
                for item in result["violations"]
            )
        )

    def test_current_failure_and_error_case_are_reported(self) -> None:
        baseline = _report()
        current = deepcopy(baseline)
        current["status"] = "FAIL"
        current["suites"]["intent"]["status"] = "FAIL"
        current["suites"]["intent"]["failures"] = [
            {"id": "case-7", "category": "WRONG_INTENT", "message": "mismatch"}
        ]
        result = evaluate_release(
            baseline,
            current,
            _policy(),
            baseline_version="v1",
            current_version="v2",
        )
        self.assertEqual(result["releaseDecision"], "BLOCK")
        self.assertEqual(result["errorCases"][0]["id"], "case-7")
        self.assertTrue(
            any(
                item["category"] == "CURRENT_REPORT_FAILED"
                for item in result["violations"]
            )
        )

    def test_only_passing_report_can_be_saved_as_baseline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "report.json"
            report = _report()
            source.write_text(json.dumps(report), encoding="utf-8")
            baseline = create_baseline(report, source_path=source, version="v1")
            self.assertEqual(baseline["task"], "QA-01B-BASELINE")
            self.assertEqual(baseline["version"], "v1")
            tampered = deepcopy(baseline)
            tampered["report"]["suites"]["intent"]["intentAccuracy"] = 1.0
            with self.assertRaises(ReleaseGateError):
                evaluate_release(
                    tampered,
                    _report(),
                    _policy(),
                    baseline_version="v1",
                    current_version="v2",
                )
            report["status"] = "FAIL"
            with self.assertRaises(ReleaseGateError):
                create_baseline(report, source_path=source, version="bad")

    def test_cli_returns_one_and_writes_block_report_for_regression(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            baseline_path = root / "baseline.json"
            current_path = root / "current.json"
            output_path = root / "gate.json"
            policy_path = ROOT / "qa01b_policy.json"
            baseline = _report()
            current = deepcopy(baseline)
            current["suites"]["resultConsistency"]["resultConsistencyRate"] = 0.94
            baseline_path.write_text(json.dumps(baseline), encoding="utf-8")
            current_path.write_text(json.dumps(current), encoding="utf-8")
            with redirect_stdout(StringIO()):
                exit_code = main(
                    [
                        "compare",
                        "--baseline",
                        str(baseline_path),
                        "--current",
                        str(current_path),
                        "--policy",
                        str(policy_path),
                        "--current-version",
                        "v2",
                        "--output",
                        str(output_path),
                    ]
                )
            self.assertEqual(exit_code, 1)
            self.assertEqual(
                json.loads(output_path.read_text(encoding="utf-8"))["releaseDecision"],
                "BLOCK",
            )


if __name__ == "__main__":
    unittest.main()
