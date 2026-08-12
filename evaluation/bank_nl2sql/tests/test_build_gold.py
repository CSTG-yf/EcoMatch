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
    def test_materialises_versioned_gold_sql_override(self) -> None:
        record = {
            "id": "CASE-OVERRIDE-01",
            "question": "和全省均值相比如何？",
            "normalizedIntent": {"intent": "AGGREGATION", "metrics": [], "time": {}, "organizations": []},
            "expected": {"answerText": "低1", "columns": [], "rows": [], "unit": None, "numericTolerance": None, "orderSensitive": False},
            "goldSqlOverride": "SELECT 41.0 AS organization_value, 42.0 AS provincial_average, 1.0 AS absolute_difference",
            "goldSqlFeatures": ["PROVINCIAL_AVERAGE", "ANSWER_FACT_CONTRACT"],
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "train.jsonl").write_text(json.dumps(record) + "\n", encoding="utf-8")
            (root / "dev.jsonl").write_text("", encoding="utf-8")
            (root / "test.jsonl").write_text("", encoding="utf-8")
            database_path = root / "benchmark.sqlite"
            sqlite3.connect(database_path).close()

            build_gold_dataset(root, database_path)
            populated = json.loads((root / "train.jsonl").read_text(encoding="utf-8"))

            self.assertEqual(populated["sql"], record["goldSqlOverride"])
            self.assertEqual(populated["sqlFeatures"], record["goldSqlFeatures"])
            self.assertNotIn("goldSqlOverride", populated)
            self.assertNotIn("goldSqlFeatures", populated)
            self.assertEqual(
                populated["expected"]["rows"], [[41.0, 42.0, 1.0]]
            )

    def test_rejects_write_statement_disguised_as_cte_override(self) -> None:
        record = {
            "id": "CASE-OVERRIDE-WRITE",
            "question": "恶意覆盖不得修改评估库",
            "normalizedIntent": {
                "intent": "POINT_QUERY",
                "metrics": [],
                "time": {},
                "organizations": [],
            },
            "expected": {
                "answerText": "1",
                "columns": [],
                "rows": [],
                "unit": None,
                "numericTolerance": None,
                "orderSensitive": False,
            },
            "goldSqlOverride": (
                "WITH marker AS (SELECT 1) "
                "DELETE FROM guarded_fact RETURNING value"
            ),
            "goldSqlFeatures": ["ANSWER_FACT_CONTRACT"],
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "train.jsonl").write_text(
                json.dumps(record) + "\n", encoding="utf-8"
            )
            (root / "dev.jsonl").write_text("", encoding="utf-8")
            (root / "test.jsonl").write_text("", encoding="utf-8")
            database_path = root / "benchmark.sqlite"
            connection = sqlite3.connect(database_path)
            connection.executescript(
                "CREATE TABLE guarded_fact (value INTEGER);"
                "INSERT INTO guarded_fact VALUES (1);"
            )
            connection.commit()
            connection.close()

            with self.assertRaises(sqlite3.OperationalError):
                build_gold_dataset(root, database_path)

            connection = sqlite3.connect(database_path)
            try:
                self.assertEqual(
                    connection.execute("SELECT value FROM guarded_fact").fetchall(),
                    [(1,)],
                )
            finally:
                connection.close()

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


    def test_can_materialise_only_the_development_split(self) -> None:
        record = {
            "id": "CASE-DEV-01",
            "question": "2025-06-30",
            "normalizedIntent": {
                "intent": "POINT_QUERY",
                "metrics": [{"code": "ZB001"}],
                "time": {"expressions": ["2025-06-30"]},
                "organizations": [{"code": "ORG001"}],
            },
            "expected": {"answerText": "42.02", "columns": [], "rows": [], "unit": None, "numericTolerance": None, "orderSensitive": False},
            "sql": None,
            "s2sql": None,
            "sqlFeatures": [],
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "train.jsonl").write_text(json.dumps({**record, "id": "CASE-TRAIN-01"}) + "\n", encoding="utf-8")
            (root / "dev.jsonl").write_text(json.dumps(record) + "\n", encoding="utf-8")
            (root / "test.jsonl").write_text("", encoding="utf-8")
            database_path = root / "benchmark.sqlite"
            connection = sqlite3.connect(database_path)
            connection.executescript(
                """
                CREATE TABLE bank_organization (org_code TEXT PRIMARY KEY, org_name TEXT NOT NULL);
                CREATE TABLE bank_metric_daily (data_date TEXT, org_code TEXT, metric_code TEXT, metric_value NUMERIC);
                INSERT INTO bank_organization VALUES ('ORG001', 'Example bank');
                INSERT INTO bank_metric_daily VALUES ('2025-06-30', 'ORG001', 'ZB001', 42.02);
                """
            )
            connection.commit()
            connection.close()

            report = build_gold_dataset(root, database_path, splits=("dev",), write_gold_manifest=False)
            train_record = json.loads((root / "train.jsonl").read_text(encoding="utf-8").strip())
            dev_record = json.loads((root / "dev.jsonl").read_text(encoding="utf-8").strip())

            self.assertEqual(report["officialCount"], 1)
            self.assertIsNone(train_record["sql"])
            self.assertIn("metric_code = 'ZB001'", dev_record["sql"])
            self.assertFalse((root / "gold_manifest.json").exists())

    def test_version_inherited_from_dataset_manifest_falls_back_to_legacy(self) -> None:
        """gold_manifest 版本必须继承 manifest.json；无 manifest 保持旧版 0.1.0。"""
        record = {
            "id": "CASE-VER-01",
            "question": "2025-06-30",
            "normalizedIntent": {
                "intent": "POINT_QUERY",
                "metrics": [{"code": "ZB001"}],
                "time": {"expressions": ["2025-06-30"]},
                "organizations": [{"code": "ORG001"}],
            },
            "expected": {"answerText": "42.02", "columns": [], "rows": [], "unit": None, "numericTolerance": None, "orderSensitive": False},
            "sql": None,
            "s2sql": None,
            "sqlFeatures": [],
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "train.jsonl").write_text(json.dumps(record) + "\n", encoding="utf-8")
            (root / "dev.jsonl").write_text("", encoding="utf-8")
            (root / "test.jsonl").write_text("", encoding="utf-8")
            database_path = root / "benchmark.sqlite"
            connection = sqlite3.connect(database_path)
            connection.executescript(
                """
                CREATE TABLE bank_organization (org_code TEXT PRIMARY KEY, org_name TEXT NOT NULL);
                CREATE TABLE bank_metric_daily (data_date TEXT, org_code TEXT, metric_code TEXT, metric_value NUMERIC);
                INSERT INTO bank_organization VALUES ('ORG001', 'Example bank');
                INSERT INTO bank_metric_daily VALUES ('2025-06-30', 'ORG001', 'ZB001', 42.02);
                """
            )
            connection.commit()
            connection.close()

            # 无 manifest：旧兼容路径保持 0.1.0
            legacy = build_gold_dataset(root, database_path)
            self.assertEqual(legacy["version"], "0.1.0")

            # 有 manifest：版本从数据集 manifest 继承
            (root / "manifest.json").write_text(
                json.dumps(
                    {
                        "version": "2.0.1",
                        "sourceSha256": "A" * 64,
                        "parentVersion": "2.0.0",
                        "answerAmendment": {"ledgerSha256": "B" * 64},
                    },
                    ensure_ascii=False,
                    indent=2,
                    sort_keys=True,
                )
                + "\n",
                encoding="utf-8",
            )
            inherited = build_gold_dataset(root, database_path)
            self.assertEqual(inherited["version"], "2.0.1")
            self.assertEqual(inherited["parentVersion"], "2.0.0")
            self.assertEqual(inherited["answerAmendmentLedgerSha256"], "B" * 64)
            gold_manifest = json.loads((root / "gold_manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(gold_manifest["version"], "2.0.1")
            self.assertEqual(gold_manifest["parentVersion"], "2.0.0")
            self.assertEqual(gold_manifest["answerAmendmentLedgerSha256"], "B" * 64)

            manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
            manifest.pop("answerAmendment")
            manifest["answerFactContract"] = {"ledgerSha256": "C" * 64}
            (root / "manifest.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            answer_facts = build_gold_dataset(root, database_path)
            self.assertEqual(answer_facts["answerFactLedgerSha256"], "C" * 64)


if __name__ == "__main__":
    unittest.main()
