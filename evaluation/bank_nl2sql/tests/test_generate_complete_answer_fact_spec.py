#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from generate_complete_answer_fact_spec import (  # noqa: E402
    CompleteAnswerFactSpecError,
    build_complete_spec,
)


def _record(sample_id: str, value: float, answer: str) -> dict[str, object]:
    return {
        "id": sample_id,
        "question": "江苏省A市农商行在2025-12-31的余额是多少？",
        "normalizedIntent": {
            "intent": "POINT_QUERY",
            "organizations": [{"code": "ORG001"}],
            "metrics": [{"code": "ZB001"}],
        },
        "sql": "SELECT metric_value FROM t WHERE org_code='ORG001' AND metric_code='ZB001' AND data_date='2025-12-31'",
        "expected": {
            "answerText": answer,
            "columns": ["org_code", "metric_code", "metric_value"],
            "rows": [["ORG001", "ZB001", value]],
            "numericTolerance": 1e-6,
            "orderSensitive": True,
            "unit": None,
        },
    }


class GenerateCompleteAnswerFactSpecTest(unittest.TestCase):
    def test_generates_full_split_contracts_and_explicit_rounding(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for split, suffix in (("train", "T"), ("dev", "V"), ("test", "S")):
                record = _record(f"SYN-{suffix}001", 42.019, "42.02亿元")
                (root / f"{split}.jsonl").write_text(
                    json.dumps(record, ensure_ascii=False) + "\n", encoding="utf-8"
                )
            output = root / "facts.json"

            result = build_complete_spec(
                root, output, parent_version="2.0.5", target_version="2.0.6"
            )

            self.assertEqual(result["coverageMode"], "FULL_OFFICIAL")
            self.assertEqual(len(result["contracts"]), 3)
            fact = result["contracts"][0]["answerFacts"][0]
            self.assertEqual(fact["value"], 42.02)
            self.assertEqual(fact["formula"]["operation"], "ROUND")
            self.assertEqual(fact["formula"]["scale"], 2)

    def test_fails_without_writing_partial_spec_when_fact_is_not_provable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for split, suffix in (("train", "T"), ("dev", "V"), ("test", "S")):
                record = _record(f"SYN-{suffix}001", 42.0, "99亿元")
                (root / f"{split}.jsonl").write_text(
                    json.dumps(record, ensure_ascii=False) + "\n", encoding="utf-8"
                )
            output = root / "facts.json"

            with self.assertRaisesRegex(CompleteAnswerFactSpecError, "failed for 3 records"):
                build_complete_spec(
                    root, output, parent_version="2.0.5", target_version="2.0.6"
                )

            self.assertFalse(output.exists())

    def test_accepts_derived_metric_identity_from_result_rows(self) -> None:
        record = _record("SYN-T001", 81.2059, "81.21%")
        record["expected"]["rows"] = [["ORG001", "DERIVED_ZB002_DIV_ZB001", 81.2059]]

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for split, suffix in (("train", "T"), ("dev", "V"), ("test", "S")):
                payload = dict(record)
                payload["id"] = f"SYN-{suffix}001"
                (root / f"{split}.jsonl").write_text(
                    json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8"
                )

            result = build_complete_spec(
                root,
                root / "facts.json",
                parent_version="2.0.5",
                target_version="2.0.6",
            )

            self.assertIn(
                "DERIVED_ZB002_DIV_ZB001",
                result["contracts"][0]["answerFacts"][0]["binding"]["metricCodes"],
            )

    def test_rebuilds_stale_existing_answer_fact_against_current_result(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for split, suffix in (("train", "T"), ("dev", "V"), ("test", "S")):
                payload = _record(f"SYN-{suffix}001", 42.0, "42亿元")
                payload["expected"]["answerFacts"] = [{
                    "id": "stale",
                    "value": 99.0,
                    "kind": "NUMBER",
                    "binding": {
                        "organizationCodes": ["ORG001"],
                        "metricCodes": ["ZB001"],
                        "dates": ["2025-12-31"],
                        "comparisonType": "POINT",
                    },
                    "formula": {"operation": "DIRECT", "operands": [{"column": "metric_value"}]},
                }]
                (root / f"{split}.jsonl").write_text(
                    json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8"
                )

            result = build_complete_spec(
                root, root / "facts.json", parent_version="2.0.5", target_version="2.0.6"
            )

            self.assertEqual(result["contracts"][0]["answerFacts"][0]["value"], 42.0)

    def test_retains_reviewed_fact_and_adds_missing_source_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for split, suffix in (("train", "T"), ("dev", "V"), ("test", "S")):
                payload = _record(
                    f"SYN-{suffix}001",
                    3.0,
                    "对公客户1户，个人客户2户，合计3户",
                )
                payload["expected"]["rows"] = [
                    ["ORG001", "ZB001", 1.0],
                    ["ORG001", "ZB002", 2.0],
                    ["ORG001", "ZB003", 3.0],
                ]
                payload["expected"]["answerFacts"] = [{
                    "id": "reviewed_total",
                    "value": 3.0,
                    "kind": "NUMBER",
                    "binding": {
                        "organizationCodes": ["ORG001"],
                        "metricCodes": ["ZB001", "ZB002", "ZB003"],
                        "dates": ["2025-12-31"],
                        "comparisonType": "SUM",
                    },
                    "formula": {
                        "operation": "SUM",
                        "operands": [
                            {"column": "metric_value", "where": {"metric_code": "ZB001"}},
                            {"column": "metric_value", "where": {"metric_code": "ZB002"}},
                        ],
                    },
                }]
                (root / f"{split}.jsonl").write_text(
                    json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8"
                )

            result = build_complete_spec(
                root, root / "facts.json", parent_version="2.0.5", target_version="2.0.6"
            )

            facts = result["contracts"][0]["answerFacts"]
            self.assertEqual(facts[0]["id"], "reviewed_total")
            self.assertEqual(sorted(fact["value"] for fact in facts), [1.0, 2.0, 3.0])


if __name__ == "__main__":
    unittest.main()
