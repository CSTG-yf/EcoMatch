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
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import run_supersonic_eval as evaluator  # noqa: E402
from evaluation_policy import EvaluationAccessError, load_evaluation_records, record_final_test_run  # noqa: E402
from run_supersonic_eval import (  # noqa: E402
    SuperSonicEvaluationError,
    SuperSonicServiceError,
    SuperSonicTransportError,
    _build_report,
    _latency_distribution,
    _load_data_contract_pending,
    _load_resumable_items,
    _mismatch_reason,
    _resumable_checkpoint_items,
    _selected_parse,
    main,
    run_supersonic_evaluation,
)


def _synthetic_top_level_record_worker(
    record: dict[str, object], *, sleep_seconds: float
) -> dict[str, object]:
    if record["id"] == "SYNTHETIC-SLOW":
        time.sleep(sleep_seconds)
    return {
        "id": record["id"],
        "errorCategory": None,
        "resumeEligible": True,
    }


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
    def test_process_isolation_times_out_one_record_and_schedules_the_next(self) -> None:
        started = time.monotonic()
        items = evaluator._run_isolated_record_evaluation(
            [
                {
                    "id": "SYNTHETIC-SLOW",
                    "question": "question must not enter timeout result",
                    "answerText": "answer must not enter timeout result",
                    "expected": {
                        "sql": "SQL must not enter timeout result",
                        "rows": [["row must not enter timeout result"]],
                    },
                },
                {"id": "SYNTHETIC-FAST", "question": "subsequent record"},
            ],
            concurrency=1,
            record_timeout_seconds=0.5,
            worker=_synthetic_top_level_record_worker,
            worker_kwargs={"sleep_seconds": 5.0},
        )
        elapsed = time.monotonic() - started

        self.assertLess(elapsed, 2.5)
        self.assertEqual([item["id"] for item in items], ["SYNTHETIC-SLOW", "SYNTHETIC-FAST"])
        timed_out = items[0]
        self.assertEqual(timed_out["errorCategory"], "HTTP_RECORD_TIMEOUT")
        self.assertFalse(timed_out["resumeEligible"])
        self.assertFalse(evaluator._checkpoint_item_is_resumable(timed_out))
        self.assertFalse(
            evaluator._checkpoint_item_is_resumable({"errorCategory": "HTTP_RECORD_TIMEOUT"})
        )
        self.assertTrue(
            {
                "question",
                "answer",
                "answerText",
                "sql",
                "rows",
                "raw",
                "error",
                "rawError",
                "errorMessage",
            }.isdisjoint(timed_out)
        )
        serialized_timeout = json.dumps(timed_out, ensure_ascii=False)
        for forbidden_value in (
            "question must not enter timeout result",
            "answer must not enter timeout result",
            "SQL must not enter timeout result",
            "row must not enter timeout result",
        ):
            self.assertNotIn(forbidden_value, serialized_timeout)
        self.assertTrue(items[1]["resumeEligible"])

    def test_extracts_only_allowlisted_bank_telemetry_from_parse_properties(self) -> None:
        (
            parse_id,
            telemetry,
            routing_telemetry,
            candidate_observation,
            attempt_telemetry,
        ) = _selected_parse(
            {
                "state": "COMPLETED",
                "bankRoutingAttemptTelemetry": {
                    "bankConstrainedPlanEnabled": True,
                    "bankDatasetQualified": False,
                    "selectedSqlGenType": "ONE_PASS_SELF_CONSISTENCY",
                    "llmCandidateCreated": True,
                    "raw": "opaque-details",
                },
                "selectedParses": [
                    {
                        "id": 7,
                        "properties": {
                            "type": "internal",
                            "bankTelemetry": {
                                "generator": "BANK_CONSTRAINED_PLAN",
                                "planIntent": "CHANGE",
                                "timeComparison": "MOM_AND_YOY",
                                "calculationType": "CHANGE",
                                "route": "S2SQL_TEMPLATE",
                                "templateCategory": "MONTH_AND_YEAR_CHANGE",
                                "raw": "opaque-details",
                            },
                            "bankRoutingTelemetry": {
                                "bankConstrainedPlanEnabled": True,
                                "bankDatasetQualified": False,
                                "selectedSqlGenType": "ONE_PASS_SELF_CONSISTENCY",
                                "raw": "opaque-details",
                            },
                            "rawProperties": "opaque-details",
                        },
                        "sqlInfo": {"correctedS2SQL": "opaque-details"},
                    }
                ],
            }
        )

        self.assertEqual(parse_id, 7)
        self.assertEqual(
            telemetry,
            {
                "generator": "BANK_CONSTRAINED_PLAN",
                "planIntent": "CHANGE",
                "timeComparison": "MOM_AND_YOY",
                "calculationType": "CHANGE",
                "route": "S2SQL_TEMPLATE",
                "templateCategory": "MONTH_AND_YEAR_CHANGE",
            },
        )
        self.assertEqual(
            routing_telemetry,
            {
                "bankConstrainedPlanEnabled": True,
                "bankDatasetQualified": False,
                "selectedSqlGenType": "ONE_PASS_SELF_CONSISTENCY",
            },
        )
        self.assertEqual(
            candidate_observation,
            {
                "executedCandidateOrigin": "LLM_INTERNAL",
                "bankRoutingCandidateCount": 1,
                "selectedParseCount": 1,
            },
        )
        self.assertEqual(
            attempt_telemetry,
            {
                "bankConstrainedPlanEnabled": True,
                "bankDatasetQualified": False,
                "selectedSqlGenType": "ONE_PASS_SELF_CONSISTENCY",
                "llmCandidateCreated": True,
            },
        )
        self.assertNotIn(
            "opaque-details",
            json.dumps(
                {
                    "bank": telemetry,
                    "routing": routing_telemetry,
                    "candidate": candidate_observation,
                    "attempt": attempt_telemetry,
                },
                ensure_ascii=False,
            ),
        )

    def test_observes_non_llm_selected_candidate_and_later_llm_without_leakage(self) -> None:
        _, _, _, candidate_observation, _ = _selected_parse(
            {
                "state": "COMPLETED",
                "selectedParses": [
                    {
                        "id": 7,
                        "properties": {
                            "type": "external",
                            "raw": "opaque-details",
                        },
                        "sqlInfo": {"correctedS2SQL": "opaque-details"},
                    },
                    {
                        "id": 8,
                        "properties": {
                            "type": "internal",
                            "bankRoutingTelemetry": {
                                "bankConstrainedPlanEnabled": True,
                                "bankDatasetQualified": True,
                                "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                                "raw": "opaque-details",
                            },
                            "question": "opaque-details",
                        },
                    },
                ],
            }
        )

        self.assertEqual(
            candidate_observation,
            {
                "executedCandidateOrigin": "NON_LLM_OR_UNSPECIFIED",
                "bankRoutingCandidateCount": 1,
                "selectedParseCount": 2,
            },
        )
        self.assertNotIn("opaque-details", json.dumps(candidate_observation, ensure_ascii=False))

    def test_observes_routing_attempt_when_no_llm_candidate_is_created(self) -> None:
        _, _, _, candidate_observation, attempt_telemetry = _selected_parse(
            {
                "state": "COMPLETED",
                "bankRoutingAttemptTelemetry": {
                    "bankConstrainedPlanEnabled": True,
                    "bankDatasetQualified": True,
                    "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                    "llmCandidateCreated": False,
                    "candidateRejectionState": "VALIDATION_REJECTED",
                    "candidateValidationErrorType": "JOIN_ERROR",
                    "validateMsg": "opaque-details",
                    "raw": "opaque-details",
                },
                "selectedParses": [
                    {
                        "id": 7,
                        "properties": {
                            "type": "external",
                            "raw": "opaque-details",
                        },
                    }
                ],
            }
        )

        self.assertEqual(
            candidate_observation,
            {
                "executedCandidateOrigin": "NON_LLM_OR_UNSPECIFIED",
                "bankRoutingCandidateCount": 0,
                "selectedParseCount": 1,
            },
        )
        self.assertEqual(
            attempt_telemetry,
            {
                "bankConstrainedPlanEnabled": True,
                "bankDatasetQualified": True,
                "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                "llmCandidateCreated": False,
                "candidateRejectionState": "VALIDATION_REJECTED",
                "candidateValidationErrorType": "JOIN_ERROR",
            },
        )
        self.assertNotIn(
            "opaque-details",
            json.dumps(
                {"candidate": candidate_observation, "attempt": attempt_telemetry},
                ensure_ascii=False,
            ),
        )

    def test_filters_unknown_bank_candidate_rejection_telemetry(self) -> None:
        telemetry = evaluator._allowlisted_bank_routing_attempt_telemetry(
            {
                "bankRoutingAttemptTelemetry": {
                    "bankConstrainedPlanEnabled": True,
                    "bankDatasetQualified": True,
                    "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                    "llmCandidateCreated": False,
                    "candidateRejectionState": "UNTRUSTED_REASON",
                    "candidateValidationErrorType": "UNTRUSTED_ERROR",
                    "candidateCompilerReason": "UNTRUSTED_COMPILER_REASON",
                    "validateMsg": "opaque-details",
                    "sql": "opaque-details",
                }
            }
        )

        self.assertEqual(
            telemetry,
            {
                "bankConstrainedPlanEnabled": True,
                "bankDatasetQualified": True,
                "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                "llmCandidateCreated": False,
            },
        )

    def test_observes_allowlisted_compiler_reason_without_raw_details(self) -> None:
        telemetry = evaluator._allowlisted_bank_routing_attempt_telemetry(
            {
                "bankRoutingAttemptTelemetry": {
                    "bankConstrainedPlanEnabled": True,
                    "bankDatasetQualified": True,
                    "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                    "llmCandidateCreated": False,
                    "candidateRejectionState": "COMPILER_EXCEPTION",
                    "candidateCompilerReason": "S2SQL_RENDER_FAILED",
                    "message": "opaque-details",
                    "sql": "opaque-details",
                }
            }
        )

        self.assertEqual(
            telemetry,
            {
                "bankConstrainedPlanEnabled": True,
                "bankDatasetQualified": True,
                "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                "llmCandidateCreated": False,
                "candidateRejectionState": "COMPILER_EXCEPTION",
                "candidateCompilerReason": "S2SQL_RENDER_FAILED",
            },
        )
        self.assertNotIn("opaque-details", json.dumps(telemetry, ensure_ascii=False))

    def test_groups_selected_sql_generation_type_without_retaining_routing_extras(self) -> None:
        def item(routing_telemetry, attempt_telemetry, executed_candidate_origin):
            return {
                "errorCategory": None,
                "parseMs": None,
                "executeMs": None,
                "summaryMs": None,
                "endToEndMs": None,
                "summaryState": None,
                "parse": True,
                "execute": True,
                "match": True,
                "difficulty": "普通",
                "sqlFeatures": [],
                "bankTelemetry": {},
                "executionTelemetry": {},
                "bankRoutingTelemetry": routing_telemetry,
                "bankRoutingAttemptTelemetry": attempt_telemetry,
                "executedCandidateOrigin": executed_candidate_origin,
            }

        report = _build_report(
            [
                item(
                    {
                        "bankConstrainedPlanEnabled": True,
                        "bankDatasetQualified": False,
                        "selectedSqlGenType": "ONE_PASS_SELF_CONSISTENCY",
                        "raw": "opaque-details",
                    },
                    {
                        "bankConstrainedPlanEnabled": True,
                        "bankDatasetQualified": False,
                        "selectedSqlGenType": "ONE_PASS_SELF_CONSISTENCY",
                        "llmCandidateCreated": True,
                        "candidateRejectionState": "VALIDATION_REJECTED",
                        "candidateValidationErrorType": "JOIN_ERROR",
                        "raw": "opaque-details",
                    },
                    "NON_LLM_OR_UNSPECIFIED",
                ),
                item(
                    {},
                    {
                        "bankConstrainedPlanEnabled": True,
                        "bankDatasetQualified": True,
                        "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                        "llmCandidateCreated": False,
                        "candidateRejectionState": "NO_CANDIDATE",
                    },
                    "LLM_INTERNAL",
                ),
                item(
                    {},
                    {
                        "bankConstrainedPlanEnabled": True,
                        "bankDatasetQualified": True,
                        "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                        "llmCandidateCreated": False,
                        "candidateRejectionState": "COMPILER_EXCEPTION",
                        "candidateCompilerReason": "OUTPUT_ORDER_MISMATCH",
                    },
                    "NON_LLM_OR_UNSPECIFIED",
                ),
            ]
        )

        self.assertEqual(
            report["bySelectedSqlGenType"]["ONE_PASS_SELF_CONSISTENCY"]["count"],
            1,
        )
        self.assertEqual(report["bySelectedSqlGenType"]["UNSPECIFIED"]["count"], 2)
        self.assertEqual(
            report["byAttemptSelectedSqlGenType"]["ONE_PASS_SELF_CONSISTENCY"]["count"],
            1,
        )
        self.assertEqual(
            report["byAttemptSelectedSqlGenType"]["BANK_CONSTRAINED_PLAN"]["count"],
            2,
        )
        self.assertEqual(
            report["byAttemptCandidateRejectionState"]["VALIDATION_REJECTED"]["count"],
            1,
        )
        self.assertEqual(
            report["byAttemptCandidateRejectionState"]["NO_CANDIDATE"]["count"],
            1,
        )
        self.assertEqual(
            report["byAttemptCandidateRejectionState"]["COMPILER_EXCEPTION"]["count"],
            1,
        )
        self.assertEqual(
            report["byAttemptCandidateValidationErrorType"]["JOIN_ERROR"]["count"],
            1,
        )
        self.assertEqual(
            report["byAttemptCandidateValidationErrorType"]["NONE"]["count"],
            2,
        )
        self.assertEqual(
            report["byAttemptCandidateCompilerReason"]["OUTPUT_ORDER_MISMATCH"]["count"],
            1,
        )
        self.assertEqual(
            report["byAttemptCandidateCompilerReason"]["NONE"]["count"],
            2,
        )
        self.assertEqual(
            report["byExecutedCandidateOrigin"]["NON_LLM_OR_UNSPECIFIED"]["count"],
            2,
        )
        self.assertEqual(report["byExecutedCandidateOrigin"]["LLM_INTERNAL"]["count"], 1)

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

    def test_classifies_strict_mismatch_reasons_without_relaxing_matching(self) -> None:
        cases = [
            (
                "COLUMN_PROJECTION",
                {"columns": ["expected"], "rows": [[1]]},
                ["actual"],
                [[1]],
            ),
            (
                "ROW_COUNT",
                {"columns": ["metric"], "rows": [[1]]},
                ["metric"],
                [[1], [2]],
            ),
            (
                "ORDER_ONLY",
                {"columns": ["metric"], "rows": [[1], [2]], "orderSensitive": True},
                ["metric"],
                [[2], [1]],
            ),
            (
                "ROW_VALUE",
                {"columns": ["metric"], "rows": [[1]], "numericTolerance": 0.001},
                ["metric"],
                [[2]],
            ),
        ]

        for expected_reason, expected, columns, rows in cases:
            with self.subTest(reason=expected_reason):
                self.assertEqual(_mismatch_reason(expected, columns, rows), expected_reason)

        self.assertIsNone(
            _mismatch_reason(
                {"columns": ["metric"], "rows": [[1], [2]], "orderSensitive": False},
                ["metric"],
                [[2], [1]],
            )
        )

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
        self.assertEqual(report["items"][0]["summaryState"], "SUCCESS")
        self.assertNotIn("mismatchReason", report["items"][0])
        self.assertNotIn("s2sql", report["items"][0])
        self.assertNotIn("physicalSql", report["items"][0])
        self.assertNotIn("textSummary", report["items"][0])
        self.assertGreaterEqual(report["items"][0]["endToEndMs"], 0)
        self.assertEqual(
            report["timingDistributionsMs"]["successfulEndToEnd"]["count"],
            1,
        )
        self.assertTrue(report["items"][0]["conversationCleaned"])
        self.assertNotIn("rows", report["items"][0])
        report_text = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("Query bank A deposit balance", report_text)
        self.assertNotIn("SELECT secret_gold_sql", report_text)
        self.assertNotIn("SELECT metric_value FROM semantic_dataset", report_text)
        self.assertNotIn("SELECT metric_value FROM bank_metric_daily", report_text)
        self.assertNotIn("A bank deposit balance is 42.02", report_text)

    def test_keeps_result_mismatch_conversation_for_diagnosis(self) -> None:
        requests: list[str] = []

        def post_json(path: str, payload: dict) -> dict:
            requests.append(path)
            if path.startswith("/openapi/chat/manage/save?"):
                return {"code": 200, "data": 503}
            if path.endswith("/parse"):
                return {
                    "code": 200,
                    "data": {"queryId": 103, "state": "COMPLETED", "selectedParses": [{"id": 1}]},
                }
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
        self.assertEqual(report["items"][0]["mismatchReason"], "ROW_VALUE")
        self.assertFalse(report["items"][0]["conversationCleaned"])
        self.assertTrue(report["items"][0]["resumeEligible"])
        self.assertFalse(any("/delete?" in path for path in requests))

    def test_does_not_execute_selected_parse_before_translation_completes(self) -> None:
        requests: list[str] = []

        def post_json(path: str, payload: dict) -> dict:
            requests.append(path)
            if path.startswith("/openapi/chat/manage/save?"):
                return {"code": 200, "data": 505}
            if path.endswith("/parse"):
                return {
                    "code": 200,
                    "data": {
                        "queryId": 105,
                        "state": "PENDING",
                        "selectedParses": [{"id": 1}],
                    },
                }
            if path.endswith("/execute"):
                raise AssertionError("execute must not run before parse completion")
            raise AssertionError(f"Unexpected path: {path}")

        item = run_supersonic_evaluation(
            [{"id": "DEV-PENDING-PARSE", "question": "pending", "expected": {"rows": []}}],
            agent_id=7,
            post_json=post_json,
        )["items"][0]

        self.assertEqual(item["errorCategory"], "PARSE_ERROR")
        self.assertFalse(item["parse"])
        self.assertFalse(item["execute"])
        self.assertEqual(item["stages"]["parse"]["state"], "ERROR")
        self.assertEqual(item["stages"]["parse"]["exceptionCategory"], "PARSE_ERROR")
        self.assertFalse(any(path.endswith("/execute") for path in requests))

    def test_backend_error_message_is_an_execution_failure_even_when_state_is_success(self) -> None:
        def post_json(path: str, payload: dict) -> dict:
            if path.startswith("/openapi/chat/manage/save?"):
                return {"code": 200, "data": 504}
            if path.endswith("/parse"):
                return {
                    "code": 200,
                    "data": {"queryId": 104, "state": "COMPLETED", "selectedParses": [{"id": 1}]},
                }
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
            report["items"][0]["stages"]["execute"]["exceptionCategory"],
            "EXECUTION_ERROR",
        )
        self.assertNotIn("backendError", report["items"][0])

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
                    "data": {
                        "queryId": chat_id + 1000,
                        "state": "COMPLETED",
                        "selectedParses": [{"id": 1}],
                    },
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

    def test_resume_reruns_retryable_checkpoint_failures_but_keeps_terminal_results(self) -> None:
        items = [
            {"id": "TRAIN-HTTP", "errorCategory": "HTTP_RETRY_EXHAUSTED", "resumeEligible": False},
            {"id": "TRAIN-SERVICE", "errorCategory": "SERVICE_ERROR", "resumeEligible": False},
            {"id": "TRAIN-CONVERSATION", "errorCategory": "CONVERSATION_ERROR", "resumeEligible": False},
            {"id": "TRAIN-SUMMARY", "errorCategory": "SUMMARY_ERROR", "resumeEligible": False},
            {"id": "TRAIN-PARSE", "errorCategory": "PARSE_ERROR", "resumeEligible": True},
            {"id": "TRAIN-EXECUTION", "errorCategory": "EXECUTION_ERROR", "resumeEligible": True},
            {"id": "TRAIN-MISMATCH", "errorCategory": "RESULT_MISMATCH", "resumeEligible": True},
            {"id": "TRAIN-SUCCESS", "errorCategory": None, "match": True, "resumeEligible": True},
        ]

        self.assertEqual(
            [item["id"] for item in _resumable_checkpoint_items(items)],
            ["TRAIN-PARSE", "TRAIN-EXECUTION", "TRAIN-MISMATCH", "TRAIN-SUCCESS"],
        )

    def test_transport_exhaustion_is_safe_to_report_but_not_safe_to_resume(self) -> None:
        def post_json(path: str, payload: dict) -> dict:
            raise SuperSonicTransportError(
                "network details must not enter the report",
                retry_count=2,
                retry_exhausted=True,
            )

        item = run_supersonic_evaluation(
            [{"id": "DEV-TRANSPORT", "question": "safe", "expected": {"rows": []}}],
            agent_id=7,
            post_json=post_json,
        )["items"][0]

        self.assertEqual(item["errorCategory"], "HTTP_RETRY_EXHAUSTED")
        self.assertEqual(item["stages"]["save"], {
            "state": "ERROR",
            "durationMs": item["stages"]["save"]["durationMs"],
            "retryCount": 2,
            "retryExhausted": True,
            "exceptionCategory": "HTTP_RETRY_EXHAUSTED",
        })
        self.assertFalse(item["resumeEligible"])
        self.assertNotIn("network details", json.dumps(item, ensure_ascii=False))

    def test_unsuccessful_service_envelope_is_not_a_terminal_business_result(self) -> None:
        def post_json(path: str, payload: dict) -> dict:
            return {"code": 503, "data": {"message": "details must stay private"}}

        item = run_supersonic_evaluation(
            [{"id": "DEV-SERVICE", "question": "safe", "expected": {"rows": []}}],
            agent_id=7,
            post_json=post_json,
        )["items"][0]

        self.assertEqual(item["errorCategory"], "SERVICE_ERROR")
        self.assertEqual(item["stages"]["save"]["exceptionCategory"], "SERVICE_ERROR")
        self.assertFalse(item["resumeEligible"])
        self.assertNotIn("details must stay private", json.dumps(item, ensure_ascii=False))

    def test_main_reruns_only_retryable_checkpoint_records(self) -> None:
        def checkpoint_item(sample_id: str, *, category: str | None, resume_eligible: bool) -> dict:
            return {
                "id": sample_id,
                "difficulty": "simple",
                "sqlFeatures": ["POINT_QUERY"],
                "parse": True,
                "execute": True,
                "match": category is None,
                "parseMs": 1,
                "executeMs": 1,
                "summaryMs": 1,
                "endToEndMs": 3,
                "summaryState": "SUCCESS",
                "errorCategory": category,
                "resumeEligible": resume_eligible,
            }

        calls: list[str] = []

        def post_json(path: str, payload: dict) -> dict:
            calls.append(path)
            if path.startswith("/openapi/chat/manage/save?"):
                return {"code": 200, "data": 801}
            if path.endswith("/parse"):
                return {
                    "code": 200,
                    "data": {"queryId": 1801, "state": "COMPLETED", "selectedParses": [{"id": 1}]},
                }
            if path.endswith("/execute"):
                return {"code": 200, "data": {"queryState": "SUCCESS", "queryColumns": [], "queryResults": []}}
            if path.endswith("/getExecuteSummary"):
                return {"code": 200, "data": {"queryMode": "METRIC", "textSummary": "done"}}
            if path.startswith("/openapi/chat/manage/delete?"):
                return {"code": 200, "data": True}
            raise AssertionError(f"Unexpected path: {path}")

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "data_contract_pending.json").write_text(
                json.dumps(
                    {
                        "issueCode": "STRUCTURED_GOLD_INCOMPLETE",
                        "recordIds": ["TRAIN-M-31", "TRAIN-M-32", "TRAIN-M-33"],
                    }
                ),
                encoding="utf-8",
            )
            (root / "train.jsonl").write_text(
                "\n".join(
                    json.dumps(record)
                    for record in (
                        {"id": "TRAIN-RETRY", "question": "retry", "expected": {"rows": []}},
                        {"id": "TRAIN-SUCCESS", "question": "skip", "expected": {"rows": []}},
                    )
                )
                + "\n",
                encoding="utf-8",
            )
            output = root / "checkpoint.json"
            output.write_text(
                json.dumps(
                    {
                        "run": {
                            "split": "train",
                            "agentId": 7,
                            "captureMethod": "concurrent-openapi-frontend-chain",
                        },
                        "items": [
                            checkpoint_item(
                                "TRAIN-RETRY",
                                category="HTTP_RETRY_EXHAUSTED",
                                resume_eligible=False,
                            ),
                            checkpoint_item(
                                "TRAIN-SUCCESS",
                                category=None,
                                resume_eligible=True,
                            ),
                        ],
                    }
                ),
                encoding="utf-8",
            )
            with patch("run_supersonic_eval._http_post_json", return_value=post_json), patch.object(
                sys,
                "argv",
                [
                    "run_supersonic_eval.py",
                    str(root),
                    "--split",
                    "train",
                    "--base-url",
                    "http://evaluation.invalid",
                    "--agent-id",
                    "7",
                    "--output",
                    str(output),
                ],
            ):
                main()

        self.assertTrue(any("evaluation-TRAIN-RETRY" in call for call in calls))
        self.assertFalse(any("evaluation-TRAIN-SUCCESS" in call for call in calls))

    def test_records_safe_terminal_telemetry_for_each_frontend_api_stage(self) -> None:
        def post_json(path: str, payload: dict) -> dict:
            if path.startswith("/openapi/chat/manage/save?"):
                return {"code": 200, "data": 701}
            if path.endswith("/parse"):
                return {
                    "code": 200,
                    "data": {"queryId": 1701, "state": "COMPLETED", "selectedParses": [{"id": 1}]},
                }
            if path.endswith("/execute"):
                return {
                    "code": 200,
                    "data": {
                        "queryState": "SUCCESS",
                        "queryColumns": [{"name": "metric_value"}],
                        "queryResults": [{"metric_value": 42}],
                    },
                }
            if path.endswith("/getExecuteSummary"):
                return {"code": 200, "data": {"queryMode": "METRIC", "textSummary": "42"}}
            if path.startswith("/openapi/chat/manage/delete?"):
                return {"code": 200, "data": True}
            raise AssertionError(f"Unexpected path: {path}")

        item = run_supersonic_evaluation(
            [{"id": "DEV-TELEMETRY", "question": "safe", "expected": {"columns": ["metric_value"], "rows": [[42]]}}],
            agent_id=7,
            post_json=post_json,
        )["items"][0]

        self.assertEqual(
            {name: item["stages"][name]["state"] for name in ("save", "parse", "execute", "summary")},
            {"save": "SUCCESS", "parse": "SUCCESS", "execute": "SUCCESS", "summary": "SUCCESS"},
        )
        for name in ("save", "parse", "execute", "summary"):
            self.assertGreaterEqual(item["stages"][name]["durationMs"], 0)
            self.assertEqual(item["stages"][name]["retryCount"], 0)
            self.assertIsNone(item["stages"][name]["exceptionCategory"])
        self.assertNotIn("question", item)
        self.assertNotIn("expected", item)
        self.assertNotIn("textSummary", item)

class DataContractPendingTest(unittest.TestCase):
    @staticmethod
    def _item(
        record_id: str, *, execute: bool, match: bool, error_category: str | None
    ) -> dict[str, object]:
        return {
            "id": record_id,
            "errorCategory": error_category,
            "parseMs": None,
            "executeMs": None,
            "summaryMs": None,
            "endToEndMs": None,
            "summaryState": "SUCCESS" if execute else "ERROR",
            "parse": True,
            "execute": execute,
            "match": match,
            "difficulty": "NORMAL",
            "sqlFeatures": [],
            "bankTelemetry": {},
            "executionTelemetry": {},
            "bankRoutingTelemetry": {},
            "bankRoutingAttemptTelemetry": {},
            "executedCandidateOrigin": "NON_LLM_OR_UNSPECIFIED",
        }

    def test_keeps_observed_metrics_and_adds_score_eligible_metrics(self) -> None:
        items = [
            self._item(
                "TRAIN-M-31",
                execute=False,
                match=False,
                error_category="EXECUTION_ERROR",
            ),
            self._item("DEV-ELIGIBLE", execute=True, match=True, error_category=None),
        ]
        report = _build_report(
            items,
            data_contract_pending={
                "issueCode": "STRUCTURED_GOLD_INCOMPLETE",
                "recordIds": ["TRAIN-M-31", "TRAIN-M-32", "TRAIN-M-33"],
            },
        )

        self.assertEqual(report["recordCount"], 2)
        self.assertEqual(report["metrics"]["resultAccuracy"], 0.5)
        self.assertEqual(report["scoreEligibleRecordCount"], 1)
        self.assertEqual(report["scoreEligibleMetrics"]["resultAccuracy"], 1.0)
        self.assertEqual(report["errorCategories"], {"EXECUTION_ERROR": 1, "NONE": 1})
        self.assertEqual(report["scoreEligibleErrorCategories"], {"NONE": 1})
        self.assertEqual(
            report["dataContractPending"],
            {
                "recordCount": 1,
                "issueCode": "STRUCTURED_GOLD_INCOMPLETE",
                "recordIds": ["TRAIN-M-31"],
            },
        )
        self.assertFalse(items[0]["scoringEligible"])
        self.assertEqual(items[0]["dataContractIssueCode"], "STRUCTURED_GOLD_INCOMPLETE")
        self.assertTrue(items[1]["scoringEligible"])
        self.assertNotIn("dataContractIssueCode", items[1])

    def test_zero_eligible_records_has_zero_rates(self) -> None:
        report = _build_report(
            [self._item("TRAIN-M-31", execute=True, match=True, error_category=None)],
            data_contract_pending={
                "issueCode": "STRUCTURED_GOLD_INCOMPLETE",
                "recordIds": ["TRAIN-M-31"],
            },
        )

        self.assertEqual(report["scoreEligibleRecordCount"], 0)
        self.assertEqual(
            report["scoreEligibleMetrics"],
            {
                "parseSuccessRate": 0.0,
                "executionSuccessRate": 0.0,
                "resultAccuracy": 0.0,
            },
        )
        self.assertEqual(report["scoreEligibleErrorCategories"], {})

    def test_policy_loader_rejects_invalid_or_test_records(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            policy = Path(temp_dir) / "pending.json"
            policy.write_text(
                json.dumps(
                    {
                        "issueCode": "STRUCTURED_GOLD_INCOMPLETE",
                        "recordIds": ["TEST-01"],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(SuperSonicEvaluationError):
                _load_data_contract_pending(policy)

            policy.write_text(
                json.dumps({"issueCode": "OTHER", "recordIds": ["TRAIN-M-31"]}),
                encoding="utf-8",
            )
            with self.assertRaises(SuperSonicEvaluationError):
                _load_data_contract_pending(policy)

    def test_default_policy_contains_only_the_approved_train_records(self) -> None:
        policy = _load_data_contract_pending(ROOT / "data_contract_pending.json")
        self.assertEqual(policy["issueCode"], "STRUCTURED_GOLD_INCOMPLETE")
        self.assertEqual(
            policy["recordIds"], ["TRAIN-M-31", "TRAIN-M-32", "TRAIN-M-33"]
        )


if __name__ == "__main__":
    unittest.main()
