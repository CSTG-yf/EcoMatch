#!/usr/bin/env python3
"""Contract tests for gold SQL execution and timing validation."""

from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from validate_gold import GoldValidationError, validate_gold_dataset  # noqa: E402


class ValidateGoldTest(unittest.TestCase):
    def test_reports_result_matches_and_execution_latency(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            database = root / "benchmark.sqlite"
            connection = sqlite3.connect(database)
            connection.execute("CREATE TABLE metric (value INTEGER)")
            connection.execute("INSERT INTO metric VALUES (42)")
            connection.commit()
            connection.close()
            record = {
                "id": "CASE-01",
                "sql": "SELECT value FROM metric",
                "s2sql": "SELECT value FROM metric",
                "expected": {"columns": ["value"], "rows": [[42]]},
            }
            for split in ("train", "dev", "test"):
                item = {**record, "id": f"{split}-01", "split": split}
                (root / f"{split}.jsonl").write_text(
                    json.dumps(item) + "\n", encoding="utf-8"
                )

            report = validate_gold_dataset(root, database)

        self.assertEqual(report["officialCount"], 3)
        self.assertEqual(report["sqlExecutionCount"], 3)
        self.assertEqual(report["resultMatchCount"], 3)
        self.assertEqual(report["timingMs"]["count"], 3)
        self.assertGreaterEqual(report["timingMs"]["average"], 0)

    def test_identifies_the_sample_when_result_does_not_match(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            database = root / "benchmark.sqlite"
            connection = sqlite3.connect(database)
            connection.execute("CREATE TABLE metric (value INTEGER)")
            connection.execute("INSERT INTO metric VALUES (42)")
            connection.commit()
            connection.close()
            for split in ("train", "dev", "test"):
                item = {
                    "id": f"{split}-mismatch",
                    "split": split,
                    "sql": "SELECT value FROM metric",
                    "s2sql": "SELECT value FROM metric",
                    "expected": {"columns": ["value"], "rows": [[1]]},
                }
                (root / f"{split}.jsonl").write_text(
                    json.dumps(item) + "\n", encoding="utf-8"
                )

            with self.assertRaisesRegex(GoldValidationError, "train-mismatch"):
                validate_gold_dataset(root, database)


if __name__ == "__main__":
    unittest.main()
