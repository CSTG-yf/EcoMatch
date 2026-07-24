#!/usr/bin/env python3
"""Contract tests for the SuperSonic end-to-end bank NL2SQL evaluator."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from evaluation_policy import EvaluationAccessError, load_evaluation_records, record_final_test_run  # noqa: E402
from run_supersonic_eval import run_supersonic_evaluation  # noqa: E402


class SuperSonicEvaluationPolicyTest(unittest.TestCase):
    def test_dev_is_available_but_test_requires_final_acknowledgement_and_is_registered(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "dev.jsonl").write_text(json.dumps({"id": "DEV-01"}) + "\n", encoding="utf-8")
            (root / "test.jsonl").write_text(json.dumps({"id": "TEST-01"}) + "\n", encoding="utf-8")

            self.assertEqual(
                [record["id"] for record in load_evaluation_records(root, split="dev")], ["DEV-01"]
            )
            with self.assertRaises(EvaluationAccessError):
                load_evaluation_records(root, split="test")
            self.assertEqual(
                [
                    record["id"]
                    for record in load_evaluation_records(
                        root, split="test", acknowledge_final_test=True
                    )
                ],
                ["TEST-01"],
            )

            registry = root / "final-test-runs.json"
            first = record_final_test_run(registry, run_metadata={"model": "local-qwen"})
            second = record_final_test_run(registry, run_metadata={"model": "local-qwen"})
            self.assertEqual(first["runNumber"], 1)
            self.assertEqual(second["runNumber"], 2)


class RunSuperSonicEvalTest(unittest.TestCase):
    def test_runs_parse_then_execute_without_sending_gold_fields(self) -> None:
        requests: list[tuple[str, dict]] = []

        def post_json(path: str, payload: dict) -> dict:
            requests.append((path, payload))
            if path == "/api/chat/query/parse":
                return {
                    "code": 200,
                    "data": {
                        "queryId": 101,
                        "state": "COMPLETED",
                        "selectedParses": [
                            {
                                "id": 1,
                                "sqlInfo": {"correctedS2SQL": "SELECT metric_value FROM semantic_dataset"},
                            }
                        ],
                    },
                }
            if path == "/api/chat/query/execute":
                return {
                    "code": 200,
                    "data": {
                        "queryState": "SUCCESS",
                        "querySql": "SELECT metric_value FROM bank_metric_daily",
                        "queryColumns": [{"name": "metric_value", "bizName": "metric_value"}],
                        "queryResults": [{"metric_value": 42.02}],
                    },
                }
            raise AssertionError(f"Unexpected path: {path}")

        records = [
            {
                "id": "DEV-01",
                "question": "查询A行存款余额",
                "sql": "SELECT secret_gold_sql",
                "expected": {
                    "columns": ["metric_value"],
                    "rows": [[42.02]],
                    "numericTolerance": 0.000001,
                    "orderSensitive": True,
                },
                "difficulty": "简单",
                "sqlFeatures": ["POINT_QUERY"],
            }
        ]

        report = run_supersonic_evaluation(records, agent_id=7, post_json=post_json)

        self.assertEqual([path for path, _ in requests], ["/api/chat/query/parse", "/api/chat/query/execute"])
        self.assertEqual(
            requests[0][1],
            {"queryText": "查询A行存款余额", "agentId": 7, "chatId": 0, "saveAnswer": False},
        )
        self.assertEqual(
            requests[1][1],
            {
                "queryId": 101,
                "parseId": 1,
                "queryText": "查询A行存款余额",
                "agentId": 7,
                "chatId": 0,
                "saveAnswer": False,
                "streamingResult": False,
            },
        )
        request_text = json.dumps(requests, ensure_ascii=False)
        self.assertNotIn("secret_gold_sql", request_text)
        self.assertNotIn('"expected"', request_text)

        self.assertEqual(report["metrics"], {
            "parseSuccessRate": 1.0,
            "executionSuccessRate": 1.0,
            "resultAccuracy": 1.0,
        })
        self.assertEqual(report["items"][0]["s2sql"], "SELECT metric_value FROM semantic_dataset")
        self.assertEqual(report["items"][0]["physicalSql"], "SELECT metric_value FROM bank_metric_daily")
        self.assertNotIn("rows", report["items"][0])

    def test_supports_an_openapi_query_endpoint_prefix(self) -> None:
        requests: list[str] = []

        def post_json(path: str, _payload: dict) -> dict:
            requests.append(path)
            if path.endswith("/parse"):
                return {"code": 200, "data": {"queryId": 101, "selectedParses": [{"id": 1}]}}
            return {
                "code": 200,
                "data": {"queryState": "SUCCESS", "queryColumns": [], "queryResults": []},
            }

        run_supersonic_evaluation(
            [{"id": "DEV-01", "question": "test", "expected": {"columns": [], "rows": []}}],
            agent_id=7,
            post_json=post_json,
            query_api_prefix="/openapi/chat/query/",
        )

        self.assertEqual(requests, ["/openapi/chat/query/parse", "/openapi/chat/query/execute"])


if __name__ == "__main__":
    unittest.main()
