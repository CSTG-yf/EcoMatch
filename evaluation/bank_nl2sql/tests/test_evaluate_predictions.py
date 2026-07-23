#!/usr/bin/env python3
"""QA-01 contract test: evaluate prediction SQL without exposing test gold as input."""

from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from evaluate_predictions import evaluate_prediction_file  # noqa: E402


class EvaluatePredictionsTest(unittest.TestCase):
    def test_reports_parse_execution_and_result_metrics(self) -> None:
        gold = {
            "id": "TEST-01",
            "difficulty": "简单",
            "sqlFeatures": ["POINT_QUERY"],
            "expected": {"columns": ["value"], "rows": [[42.02]], "numericTolerance": 0.000001, "orderSensitive": True},
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "test.jsonl").write_text(json.dumps(gold, ensure_ascii=False) + "\n", encoding="utf-8")
            prediction_path = root / "predictions.jsonl"
            prediction_path.write_text(
                "\n".join(
                    [
                        json.dumps({"id": "TEST-01", "sql": "SELECT 42.02 AS value"}),
                        json.dumps({"id": "UNKNOWN", "sql": "SELECT 1"}),
                    ]
                )
                + "\n",
                encoding="utf-8",
            )
            database_path = root / "benchmark.sqlite"
            sqlite3.connect(database_path).close()

            report = evaluate_prediction_file(root, prediction_path, database_path)

            self.assertEqual(report["goldCount"], 1)
            self.assertEqual(report["predictionCount"], 2)
            self.assertEqual(report["matchedGoldCount"], 1)
            self.assertEqual(report["metrics"]["parseSuccessRate"], 1.0)
            self.assertEqual(report["metrics"]["executionSuccessRate"], 1.0)
            self.assertEqual(report["metrics"]["resultAccuracy"], 1.0)
            self.assertEqual(report["unmatchedPredictionIds"], ["UNKNOWN"])


if __name__ == "__main__":
    unittest.main()
