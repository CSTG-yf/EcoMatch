#!/usr/bin/env python3
"""Contract tests for scoring results captured from the visible chat page."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from evaluate_ui_capture import UiCaptureEvaluationError, evaluate_ui_capture  # noqa: E402


class UiCaptureEvaluationTest(unittest.TestCase):

    def test_scores_visible_rows_together_with_following_pages(self) -> None:
        records = [
            {
                "id": "DEV-01",
                "expected": {
                    "columns": ["metric_value"],
                    "rows": [[10], [20]],
                    "numericTolerance": 0.0,
                    "orderSensitive": True,
                },
            }
        ]
        capture = {
            "items": [
                {
                    "id": "DEV-01",
                    "state": "done",
                    "headers": ["metric_value"],
                    "rows": [["10"]],
                }
            ],
            "paginationSupplements": {"DEV-01": [["20"]]},
        }

        report = evaluate_ui_capture(records, capture)

        self.assertEqual(report["metrics"]["resultAccuracy"], 1.0)
        self.assertEqual(report["errorCategories"], {"NONE": 1})
        self.assertEqual(report["items"][0]["visibleRowCount"], 2)

    def test_records_a_non_terminal_page_answer_as_a_failure(self) -> None:
        records = [
            {
                "id": "DEV-01",
                "expected": {"columns": ["metric_value"], "rows": [[10]]},
            }
        ]
        capture = {
            "items": [
                {
                    "id": "DEV-01",
                    "state": "model_response_timeout",
                    "headers": [],
                    "rows": [],
                }
            ]
        }

        report = evaluate_ui_capture(records, capture)

        self.assertEqual(report["metrics"]["executionSuccessRate"], 0.0)
        self.assertEqual(report["errorCategories"], {"UI_TERMINAL_FAILURE": 1})

    def test_normalizes_visible_thousand_separators(self) -> None:
        records = [
            {
                "id": "DEV-01",
                "expected": {"columns": ["metric_value"], "rows": [[1609]]},
            }
        ]
        capture = {
            "items": [
                {
                    "id": "DEV-01",
                    "state": "done",
                    "headers": ["metric_value"],
                    "rows": [["1,609"]],
                }
            ]
        }

        report = evaluate_ui_capture(records, capture)

        self.assertEqual(report["metrics"]["resultAccuracy"], 1.0)

    def test_can_score_only_captured_records_for_a_smoke_check(self) -> None:
        records = [
            {"id": "DEV-01", "expected": {"columns": ["metric_value"], "rows": [[10]]}},
            {"id": "DEV-02", "expected": {"columns": ["metric_value"], "rows": [[20]]}},
        ]
        capture = {
            "items": [
                {"id": "DEV-01", "state": "done", "headers": ["metric_value"], "rows": [["10"]]}
            ]
        }

        report = evaluate_ui_capture(records, capture, captured_only=True)

        self.assertEqual(report["recordCount"], 1)
        self.assertEqual(report["metrics"]["resultAccuracy"], 1.0)
        self.assertEqual(report["errorCategories"], {"NONE": 1})

    def test_rejects_duplicate_capture_ids(self) -> None:
        records = [{"id": "DEV-01", "expected": {"columns": [], "rows": []}}]
        capture = {
            "items": [
                {"id": "DEV-01", "state": "done", "headers": [], "rows": []},
                {"id": "DEV-01", "state": "done", "headers": [], "rows": []},
            ]
        }

        with self.assertRaises(UiCaptureEvaluationError):
            evaluate_ui_capture(records, capture)


if __name__ == "__main__":
    unittest.main()
