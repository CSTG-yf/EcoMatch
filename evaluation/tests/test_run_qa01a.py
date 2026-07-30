#!/usr/bin/env python3
"""Unit tests for the QA-01A unified evaluation runner."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from run_qa01a import (  # noqa: E402
    CommandResult,
    EvaluationRunnerError,
    _intent_metrics,
    _json_from_output,
    _parse_java_reports,
    _run_command,
    _sanitize_message,
    _suite,
)


class Qa01aRunnerTest(unittest.TestCase):
    def test_parses_pretty_json_after_non_json_output(self) -> None:
        result = CommandResult(
            label="validator",
            returncode=0,
            duration_ms=1,
            stdout='notice\n{\n  "result": "PASS",\n  "count": 3\n}\n',
            stderr="",
        )
        self.assertEqual(_json_from_output(result)["count"], 3)

    def test_rejects_failed_command_without_leaking_full_output(self) -> None:
        result = CommandResult(
            label="validator",
            returncode=1,
            duration_ms=1,
            stdout="",
            stderr="first diagnostic\nlast diagnostic",
        )
        with self.assertRaisesRegex(EvaluationRunnerError, "last diagnostic"):
            _json_from_output(result)

    def test_applies_thresholds_and_identifies_metric(self) -> None:
        passed = _suite("intent", {"accuracy": 0.95}, {"accuracy": 0.94})
        failed = _suite("intent", {"accuracy": 0.90}, {"accuracy": 0.94})
        self.assertEqual(passed["status"], "PASS")
        self.assertEqual(failed["status"], "FAIL")
        self.assertEqual(failed["failures"][0]["category"], "THRESHOLD_VIOLATION")

    def test_missing_executable_is_a_stage_result_instead_of_runner_crash(self) -> None:
        result = _run_command("missing", ["qa01a-command-that-does-not-exist"])
        self.assertEqual(result.returncode, 127)
        self.assertIn("FileNotFoundError", result.stderr)

    def test_sanitizes_paths_and_url_credentials_in_failure_reports(self) -> None:
        sanitized = _sanitize_message(
            r"failed at F:\secret\data.json via https://admin:password@example.test/api"
        )
        self.assertNotIn(r"F:\secret", sanitized)
        self.assertNotIn("admin:password", sanitized)
        self.assertIn("<path>", sanitized)
        self.assertIn("<redacted>", sanitized)

    def test_parses_intent_metrics_and_surefire_failures(self) -> None:
        self.assertEqual(
            _intent_metrics(
                "BANK_INTENT_EVAL cases=52 intent=0.9808 metric=1.0000 clarification=1.0000"
            )["intentAccuracy"],
            0.9808,
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            report_dir = Path(temp_dir)
            (report_dir / "TEST-example.xml").write_text(
                """<?xml version="1.0" encoding="UTF-8"?>
<testsuite tests="2" failures="1" errors="0" skipped="0">
  <testcase classname="example.PassingTest" name="passes" time="0.01">
    <system-out>BANK_INTENT_EVAL cases=52 intent=0.9808 metric=1.0000 clarification=1.0000</system-out>
  </testcase>
  <testcase classname="example.FailingTest" name="fails" time="0.02">
    <failure message="expected true">trace</failure>
  </testcase>
  <system-out>BANK_CHART_EVAL cases=30 matched=29 accuracy=0.9667</system-out>
</testsuite>
""",
                encoding="utf-8",
            )
            report = _parse_java_reports(report_dir)
        self.assertEqual(len(report["cases"]), 2)
        self.assertEqual(report["cases"][1]["status"], "FAIL")
        self.assertIn("BANK_INTENT_EVAL", report["output"])
        self.assertIn("BANK_CHART_EVAL", report["output"])


if __name__ == "__main__":
    unittest.main()
