#!/usr/bin/env python3
"""Contract tests for the SuperSonic end-to-end bank NL2SQL evaluator."""

from __future__ import annotations

import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from evaluation_policy import EvaluationAccessError, load_evaluation_records, record_final_test_run  # noqa: E402
from run_supersonic_eval import (  # noqa: E402
    SuperSonicEvaluationError,
    _latency_distribution,
    _load_resumable_items,
    run_supersonic_evaluation,
)


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
    def test_reports_nearest_rank_latency_percentiles(self) -> None:
        self.assertEqual(
            _latency_distribution([1, 2, 3, 4, 100, None]),
            {
                "count": 5,
                "average": 22.0,
                "p50": 3.0,
                "p95": 100.0,
                "p99": 100.0,
                "max": 100.0,
            },
        )
        self.assertEqual(_latency_distribution([])["count"], 0)
        self.assertIsNone(_latency_distribution([])["p95"])

    def test_runs_the_frontend_conversation_chain_without_sending_gold_fields(self) -> None:
        requests: list[tuple[str, dict]] = []

        def post_json(path: str, payload: dict) -> dict:
            requests.append((path, payload))
            if path.startswith("/openapi/chat/manage/save?"):
                return {"code": 200, "data": 501}
            if path == "/openapi/chat/query/parse":
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
            if path == "/openapi/chat/query/execute":
                return {
                    "code": 200,
                    "data": {
                        "queryState": "SUCCESS",
                        "querySql": "SELECT metric_value FROM bank_metric_daily",
                        "queryColumns": [{"name": "metric_value", "bizName": "metric_value"}],
                        "queryResults": [{"metric_value": 42.02}],
                    },
                }
            if path == "/openapi/chat/query/getExecuteSummary":
                return {
                    "code": 200,
                    "data": {
                        "queryMode": "METRIC",
                        "textSummary": "A bank deposit balance is 42.02",
                    },
                }
            if path == "/openapi/chat/manage/delete?chatId=501":
                return {"code": 200, "data": True}
            raise AssertionError(f"Unexpected path: {path}")

        records = [
            {
                "id": "DEV-01",
                "question": "Query bank A deposit balance",
                "sql": "SELECT secret_gold_sql",
                "expected": {
                    "columns": ["metric_value"],
                    "rows": [[42.02]],
                    "numericTolerance": 0.000001,
                    "orderSensitive": True,
                },
                "difficulty": "simple",
                "sqlFeatures": ["POINT_QUERY"],
            }
        ]

        report = run_supersonic_evaluation(records, agent_id=7, post_json=post_json)

        self.assertEqual(
            [path.split("?")[0] for path, _ in requests],
            [
                "/openapi/chat/manage/save",
                "/openapi/chat/query/parse",
                "/openapi/chat/query/execute",
                "/openapi/chat/query/getExecuteSummary",
                "/openapi/chat/manage/delete",
            ],
        )
        self.assertEqual(
            requests[1][1],
            {
                "queryText": "Query bank A deposit balance",
                "agentId": 7,
                "chatId": 501,
            },
        )
        self.assertEqual(
            requests[2][1],
            {
                "queryId": 101,
                "parseId": 1,
                "queryText": "Query bank A deposit balance",
                "agentId": 7,
                "chatId": 501,
                "streamingResult": True,
            },
        )
        request_text = json.dumps(requests, ensure_ascii=False)
        self.assertNotIn("secret_gold_sql", request_text)
        self.assertNotIn('"expected"', request_text)

        self.assertEqual(
            report["metrics"],
            {
                "parseSuccessRate": 1.0,
                "executionSuccessRate": 1.0,
                "resultAccuracy": 1.0,
            },
        )
        self.assertEqual(report["items"][0]["s2sql"], "SELECT metric_value FROM semantic_dataset")
        self.assertEqual(report["items"][0]["physicalSql"], "SELECT metric_value FROM bank_metric_daily")
        self.assertEqual(report["items"][0]["summaryState"], "SUCCESS")
        self.assertEqual(report["items"][0]["textSummary"], "A bank deposit balance is 42.02")
        self.assertGreaterEqual(report["items"][0]["endToEndMs"], 0)
        self.assertEqual(
            report["timingDistributionsMs"]["successfulEndToEnd"]["count"],
            1,
        )
        self.assertTrue(report["items"][0]["conversationCleaned"])
        self.assertNotIn("rows", report["items"][0])

    def test_keeps_result_mismatch_conversation_for_diagnosis(self) -> None:
        requests: list[str] = []

        def post_json(path: str, payload: dict) -> dict:
            requests.append(path)
            if path.startswith("/openapi/chat/manage/save?"):
                return {"code": 200, "data": 503}
            if path.endswith("/parse"):
                return {"code": 200, "data": {"queryId": 103, "selectedParses": [{"id": 1}]}}
            if path.endswith("/execute"):
                return {
                    "code": 200,
                    "data": {
                        "queryState": "SUCCESS",
                        "queryColumns": [{"nameEn": "metric_value"}],
                        "queryResults": [{"metric_value": 1}],
                    },
                }
            if path.endswith("/getExecuteSummary"):
                return {"code": 200, "data": {"queryMode": "METRIC", "textSummary": "done"}}
            raise AssertionError(f"Unexpected path: {path}")

        report = run_supersonic_evaluation(
            [
                {
                    "id": "DEV-MISMATCH",
                    "question": "mismatch",
                    "expected": {"columns": ["metric_value"], "rows": [[2]]},
                }
            ],
            agent_id=7,
            post_json=post_json,
        )

        self.assertEqual(report["items"][0]["errorCategory"], "RESULT_MISMATCH")
        self.assertFalse(report["items"][0]["conversationCleaned"])
        self.assertFalse(any("/delete?" in path for path in requests))

    def test_backend_error_message_is_an_execution_failure_even_when_state_is_success(self) -> None:
        def post_json(path: str, payload: dict) -> dict:
            if path.startswith("/openapi/chat/manage/save?"):
                return {"code": 200, "data": 504}
            if path.endswith("/parse"):
                return {"code": 200, "data": {"queryId": 104, "selectedParses": [{"id": 1}]}}
            if path.endswith("/execute"):
                return {
                    "code": 200,
                    "data": {
                        "queryState": "SUCCESS",
                        "queryColumns": [],
                        "queryResults": [],
                        "errorMsg": "StatementCallback; bad SQL grammar",
                    },
                }
            raise AssertionError(f"Unexpected path: {path}")

        report = run_supersonic_evaluation(
            [{"id": "DEV-SQL-ERROR", "question": "broken SQL", "expected": {"rows": []}}],
            agent_id=7,
            post_json=post_json,
        )

        self.assertEqual(report["items"][0]["errorCategory"], "EXECUTION_ERROR")
        self.assertFalse(report["items"][0]["execute"])
        self.assertEqual(
            report["items"][0]["backendError"],
            "StatementCallback; bad SQL grammar",
        )

    def test_runs_records_concurrently_but_keeps_each_record_chain_ordered(self) -> None:
        requests: dict[int, list[str]] = {}
        lock = threading.Lock()
        active_parses = 0
        maximum_active_parses = 0

        def post_json(path: str, payload: dict) -> dict:
            nonlocal active_parses, maximum_active_parses
            if path.startswith("/openapi/chat/manage/save?"):
                chat_id = 601 if "DEV-A" in path else 602
                with lock:
                    requests[chat_id] = ["save"]
                return {"code": 200, "data": chat_id}
            if path.endswith("/parse"):
                chat_id = payload["chatId"]
                with lock:
                    requests[chat_id].append("parse")
                    active_parses += 1
                    maximum_active_parses = max(maximum_active_parses, active_parses)
                time.sleep(0.03)
                with lock:
                    active_parses -= 1
                return {
                    "code": 200,
                    "data": {"queryId": chat_id + 1000, "selectedParses": [{"id": 1}]},
                }
            if path.endswith("/execute"):
                chat_id = payload["chatId"]
                with lock:
                    requests[chat_id].append("execute")
                return {
                    "code": 200,
                    "data": {"queryState": "SUCCESS", "queryColumns": [], "queryResults": []},
                }
            if path.endswith("/getExecuteSummary"):
                chat_id = payload["queryId"] - 1000
                with lock:
                    requests[chat_id].append("summary")
                return {"code": 200, "data": {"queryMode": "METRIC", "textSummary": "done"}}
            if path.startswith("/openapi/chat/manage/delete?"):
                chat_id = int(path.rsplit("=", 1)[1])
                with lock:
                    requests[chat_id].append("delete")
                return {"code": 200, "data": True}
            raise AssertionError(f"Unexpected path: {path}")

        report = run_supersonic_evaluation(
            [
                {"id": "DEV-A", "question": "first", "expected": {"columns": [], "rows": []}},
                {"id": "DEV-B", "question": "second", "expected": {"columns": [], "rows": []}},
            ],
            agent_id=7,
            post_json=post_json,
            concurrency=2,
        )

        self.assertGreaterEqual(maximum_active_parses, 2)
        self.assertEqual(requests[601], ["save", "parse", "execute", "summary", "delete"])
        self.assertEqual(requests[602], ["save", "parse", "execute", "summary", "delete"])
        self.assertEqual([item["id"] for item in report["items"]], ["DEV-A", "DEV-B"])

    def test_resume_checkpoint_requires_the_same_split_agent_and_runner(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            checkpoint = Path(temp_dir) / "checkpoint.json"
            checkpoint.write_text(
                json.dumps(
                    {
                        "run": {
                            "split": "train",
                            "agentId": 33,
                            "captureMethod": "concurrent-openapi-frontend-chain",
                        },
                        "items": [{"id": "TRAIN-01", "match": True}],
                    }
                ),
                encoding="utf-8",
            )

            self.assertEqual(
                _load_resumable_items(checkpoint, split="train", agent_id=33)[0]["id"],
                "TRAIN-01",
            )
            with self.assertRaises(SuperSonicEvaluationError):
                _load_resumable_items(checkpoint, split="dev", agent_id=33)


if __name__ == "__main__":
    unittest.main()
