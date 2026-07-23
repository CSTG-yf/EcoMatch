#!/usr/bin/env python3
"""Contract test for materialising executable gold results into JSONL."""

from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from build_gold import build_gold_dataset  # noqa: E402


class BuildGoldTest(unittest.TestCase):
    def test_populates_sql_and_structured_result_from_sqlite(self) -> None:
        record = {
            "id": "CASE-01",
            "question": "江苏省A市农商行2025年6月末的各项存款余额是多少？",
            "normalizedIntent": {
                "intent": "POINT_QUERY",
                "metrics": [{"code": "ZB001"}],
                "time": {"expressions": ["2025年6月末"]},
                "organizations": [{"code": "ORG001"}],
            },
            "expected": {"answerText": "42.02亿元", "columns": [], "rows": [], "unit": None, "numericTolerance": None, "orderSensitive": False},
            "sql": None,
            "s2sql": None,
            "sqlFeatures": [],
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "train.jsonl").write_text(json.dumps(record, ensure_ascii=False) + "\n", encoding="utf-8")
            (root / "dev.jsonl").write_text("", encoding="utf-8")
            (root / "test.jsonl").write_text("", encoding="utf-8")
            database_path = root / "benchmark.sqlite"
            connection = sqlite3.connect(database_path)
            connection.executescript(
                """
                CREATE TABLE bank_organization (org_code TEXT PRIMARY KEY, org_name TEXT NOT NULL);
                CREATE TABLE bank_metric_daily (data_date TEXT, org_code TEXT, metric_code TEXT, metric_value NUMERIC);
                INSERT INTO bank_organization VALUES ('ORG001', '江苏省A市农商行');
                INSERT INTO bank_metric_daily VALUES ('2025-06-30', 'ORG001', 'ZB001', 42.02);
                """
            )
            connection.commit()
            connection.close()

            report = build_gold_dataset(root, database_path)
            populated = json.loads((root / "train.jsonl").read_text(encoding="utf-8").strip())

            self.assertEqual(report["officialCount"], 1)
            self.assertEqual(populated["sqlFeatures"], ["POINT_QUERY"])
            self.assertIn("metric_code = 'ZB001'", populated["sql"])
            self.assertEqual(populated["expected"]["columns"], ["org_code", "org_name", "metric_code", "metric_value"])
            self.assertEqual(populated["expected"]["rows"], [["ORG001", "江苏省A市农商行", "ZB001", 42.02]])


if __name__ == "__main__":
    unittest.main()
