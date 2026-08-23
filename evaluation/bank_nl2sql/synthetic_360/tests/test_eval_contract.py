from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from evaluation.bank_nl2sql.synthetic_360.evaluate_results import evaluate_capture
from evaluation.bank_nl2sql.synthetic_360.run_eval import (
    SyntheticRunError,
    build_blind_split,
    verify_runtime_receipt,
)


RELEASE = Path(__file__).resolve().parents[1] / "releases" / "0.1.0-synthetic"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Synthetic360EvalContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.gold = [
            {
                "id": "SYNQ-001",
                "split": "dev",
                "metricCode": "CNB001",
                "queryType": "POINT_QUERY",
                "question": "查询合成机构01在2025-01-31的各项存款余额是多少？",
                "expected": {
                    "columns": ["org_code", "metric_code", "data_date", "metric_value"],
                    "rows": [["SYN-ORG-001", "CNB001", "2025-01-31", 100.0]],
                },
            },
            {
                "id": "SYNQ-002",
                "split": "test",
                "metricCode": "CNB002",
                "question": "查询合成机构02在2025-01-31的对公存款是多少？",
                "expected": {
                    "columns": ["org_code", "metric_code", "data_date", "metric_value"],
                    "rows": [["SYN-ORG-002", "CNB002", "2025-01-31", 80.0]],
                },
            },
        ]

    def test_blind_split_sends_only_id_and_question(self) -> None:
        blind = [
            {"id": "SYNQ-001", "question": self.gold[0]["question"]},
            {"id": "SYNQ-002", "question": self.gold[1]["question"]},
        ]
        selected = build_blind_split(self.gold, blind, split="dev")
        self.assertEqual([{"id": "SYNQ-001", "question": self.gold[0]["question"]}], selected)

    def test_structured_result_scores_without_answer_text_equality(self) -> None:
        capture = {
            "items": [
                {
                    "id": "SYNQ-001",
                    "parse": True,
                    "execute": True,
                    "resultColumns": ["org_code", "metric_code", "data_date", "metric_value"],
                    "resultRows": [["SYN-ORG-001", "CNB001", "2025-01-31", 100.0]],
                    "textSummary": "文案可以不同，不参与主评分。",
                    "endToEndMs": 1200,
                    "errorCategory": None,
                }
            ]
        }
        report = evaluate_capture(self.gold[:1], capture)
        self.assertEqual(1.0, report["metricRecognitionAccuracy"])
        self.assertEqual(1.0, report["sqlExecutionSuccessRate"])
        self.assertEqual(1.0, report["resultAccuracy"])
        self.assertEqual(1200.0, report["latencyMs"]["mean"])

    def test_runtime_receipt_binds_agent_to_exact_release(self) -> None:
        manifest = json.loads((RELEASE / "manifest.json").read_text(encoding="utf-8"))
        receipt = {
            "receiptSchemaVersion": "1.0",
            "dataOrigin": "SYNTHETIC",
            "datasetVersion": manifest["version"],
            "manifestSha256": _sha256(RELEASE / "manifest.json"),
            "agentId": 33,
            "dataSetId": 101,
            "modelId": 202,
            "databaseId": 303,
            "modelBinding": {"modelId": 202, "databaseId": 303},
            "semanticImport": {"organizations": 13, "indicators": 360, "factsValidated": 79560},
            "packageFiles": {
                name: {
                    "bytes": manifest["files"][name]["bytes"],
                    "sha256": manifest["files"][name]["sha256"],
                }
                for name in ("bank.sqlite", "bank-h2.sql")
            },
            "counts": {"metrics": 360, "organizations": 13, "dates": 17, "facts": 79560},
            "physicalDatabaseLoad": "PRELOADED_BY_CALLER",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            receipt_path = Path(temp_dir) / "receipt.json"
            receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
            verified = verify_runtime_receipt(RELEASE, receipt_path, agent_id=33)
        self.assertEqual(303, verified["databaseId"])

    def test_runtime_receipt_rejects_wrong_agent(self) -> None:
        manifest = json.loads((RELEASE / "manifest.json").read_text(encoding="utf-8"))
        receipt = {
            "receiptSchemaVersion": "1.0",
            "dataOrigin": "SYNTHETIC",
            "datasetVersion": manifest["version"],
            "manifestSha256": _sha256(RELEASE / "manifest.json"),
            "agentId": 33,
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            receipt_path = Path(temp_dir) / "receipt.json"
            receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
            with self.assertRaises(SyntheticRunError):
                verify_runtime_receipt(RELEASE, receipt_path, agent_id=34)

    def test_wrong_metric_and_result_are_classified(self) -> None:
        capture = {
            "items": [
                {
                    "id": "SYNQ-001",
                    "parse": True,
                    "execute": True,
                    "resultColumns": ["org_code", "metric_code", "data_date", "metric_value"],
                    "resultRows": [["SYN-ORG-001", "CNB002", "2025-01-31", 99.0]],
                    "endToEndMs": 800,
                    "errorCategory": None,
                }
            ]
        }
        report = evaluate_capture(self.gold[:1], capture)
        self.assertEqual(0.0, report["metricRecognitionAccuracy"])
        self.assertEqual(1.0, report["sqlExecutionSuccessRate"])
        self.assertEqual(0.0, report["resultAccuracy"])
        self.assertEqual({"METRIC_MISMATCH": 1}, report["errorCategories"])

    def test_point_query_accepts_semantic_metric_aggregate_result(self) -> None:
        capture = {
            "items": [
                {
                    "id": "SYNQ-001",
                    "parse": True,
                    "execute": True,
                    "physicalSql": (
                        "SELECT SUM(CASE WHEN metric_code = 'CNB001' "
                        "THEN metric_value ELSE 0 END) AS cnb001 "
                        "FROM bank_metric_daily WHERE data_date = '2025-01-31' "
                        "AND org_code = 'SYN-ORG-001'"
                    ),
                    "resultColumns": ["cnb001"],
                    "resultRows": [[100.0]],
                    "textSummary": None,
                    "endToEndMs": 1200,
                    "errorCategory": None,
                }
            ]
        }
        report = evaluate_capture(self.gold[:1], capture)
        self.assertEqual(1.0, report["metricRecognitionAccuracy"])
        self.assertEqual(1.0, report["sqlExecutionSuccessRate"])
        self.assertEqual(1.0, report["resultAccuracy"])


if __name__ == "__main__":
    unittest.main()
