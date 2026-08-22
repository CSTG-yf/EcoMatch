from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from evaluation.bank_metric_catalog.run_metric_qa_model import (
    REQUIRED_OUTPUT_FIELDS,
    build_model_request,
    filter_records_by_ids,
    generate_predictions,
    load_id_filter,
    load_split_ids,
    parse_model_prediction,
    select_pilot_ids,
    select_candidate_metrics,
)


class MetricQaModelRunnerTest(unittest.TestCase):
    def test_id_filter_selects_requested_blind_records_in_file_order(self) -> None:
        records = [
            {"id": "BMQ-001", "question": "问题1"},
            {"id": "BMQ-002", "question": "问题2"},
            {"id": "BMQ-003", "question": "问题3"},
        ]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ids.txt"
            path.write_text("BMQ-003\nBMQ-001\n", encoding="utf-8")
            ids = load_id_filter(path)
        self.assertEqual(["BMQ-003", "BMQ-001"], ids)
        self.assertEqual([records[2], records[0]], filter_records_by_ids(records, ids))

    def test_split_filter_reads_only_ids_and_split_from_local_gold(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "gold.jsonl"
            path.write_text(
                "\n".join(
                    [
                        json.dumps({"id": "BMQ-001", "split": "dev", "expected": {"metricCode": "CNB001"}}),
                        json.dumps({"id": "BMQ-002", "split": "test", "expected": {"metricCode": "CNB002"}}),
                    ]
                ),
                encoding="utf-8",
            )
            self.assertEqual(["BMQ-001"], load_split_ids(path, "dev"))

    def test_pilot_ids_cover_scenes_and_keep_all_cases_for_each_metric(self) -> None:
        rows = []
        metric_specs = [
            ("CNB001", "OPERATIONS", "deposits"),
            ("CNB006", "OPERATIONS", "profit"),
            ("CNB151", "RISK", "credit"),
            ("CNB186", "RISK", "capital"),
            ("CNB271", "CUSTOMER_MARKETING", "customers"),
            ("CNB291", "CUSTOMER_MARKETING", "retention"),
        ]
        for code, scene, domain in metric_specs:
            for case_type in ("CANONICAL_QUERY", "ALIAS_QUERY", "GOVERNANCE_QA"):
                rows.append(
                    {
                        "id": f"{code}-{case_type}",
                        "split": "dev",
                        "caseType": case_type,
                        "expected": {"metricCode": code, "scene": scene, "domain": domain},
                    }
                )
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "gold.jsonl"
            path.write_text(
                "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
                encoding="utf-8",
            )
            ids = select_pilot_ids(path, "dev", metric_count=3)

        self.assertEqual(9, len(ids))
        selected_codes = {sample_id.split("-")[0] for sample_id in ids}
        self.assertEqual({"CNB001", "CNB151", "CNB271"}, selected_codes)
        self.assertEqual(
            {"CANONICAL_QUERY", "ALIAS_QUERY", "GOVERNANCE_QA"},
            {sample_id.removeprefix(f"{code}-") for code in selected_codes for sample_id in ids if sample_id.startswith(code)},
        )

    def test_model_request_contains_only_opaque_id_and_question(self) -> None:
        candidates = [
            {
                "code": "CNB001",
                "name": "各项存款余额",
                "aliases": ["存款余额"],
                "scene": "OPERATIONS",
                "domain": "assets_liabilities_deposits_loans",
                "unit": "万元",
                "aggregation": "SNAPSHOT",
                "definition": "候选定义",
            }
        ]
        request = build_model_request({"id": "BMQ-abc", "question": "查询存款余额。"}, candidates=candidates)
        serialized = json.dumps(request["input"], ensure_ascii=False)
        for forbidden in ("expected", "metricCode", "definition", "split", "goldSql", "answerText"):
            self.assertNotIn(forbidden, serialized)
        self.assertEqual({"id", "question"}, set(request["input"]))
        self.assertIn("CNB001", request["system"])

    def test_candidate_retrieval_prefers_the_longest_catalog_term(self) -> None:
        metrics = [
            {
                "code": "CNB001",
                "name": "各项存款余额",
                "aliases": ["存款余额"],
                "scene": "OPERATIONS",
                "domain": "assets_liabilities_deposits_loans",
                "unit": "万元",
                "aggregation": "SNAPSHOT",
                "definition": "定义1",
            },
            {
                "code": "CNB002",
                "name": "对公存款余额",
                "aliases": ["对公存款"],
                "scene": "OPERATIONS",
                "domain": "assets_liabilities_deposits_loans",
                "unit": "万元",
                "aggregation": "SNAPSHOT",
                "definition": "定义2",
            },
        ]
        candidates = select_candidate_metrics("查询对公存款余额。", metrics, limit=2)
        self.assertEqual("CNB002", candidates[0]["code"])

    def test_fenced_json_is_parsed_into_complete_prediction(self) -> None:
        content = "```json\n" + json.dumps(
            {
                "metricCode": "CNB001",
                "action": "ROUTE_TO_DATA_QUERY",
                "metricName": "各项存款余额",
                "matchedText": "存款余额",
                "scene": "OPERATIONS",
                "domain": "assets_liabilities_deposits_loans",
                "unit": "万元",
                "aggregation": "SNAPSHOT",
                "definition": None,
            },
            ensure_ascii=False,
        ) + "\n```"
        prediction = parse_model_prediction(content, sample_id="BMQ-abc")
        self.assertEqual(REQUIRED_OUTPUT_FIELDS, set(prediction))
        self.assertEqual("BMQ-abc", prediction["id"])
        self.assertEqual("CNB001", prediction["metricCode"])

    def test_runner_writes_checkpoint_and_does_not_need_network_for_fake_completion(self) -> None:
        records = [
            {"id": "BMQ-001", "question": "查询存款余额。"},
            {"id": "BMQ-002", "question": "什么是存贷比？"},
        ]

        def complete(messages: list[dict[str, str]]) -> str:
            self.assertEqual({"id", "question"}, set(json.loads(messages[-1]["content"])))
            return json.dumps(
                {
                    "metricCode": "CNB001",
                    "action": "ROUTE_TO_DATA_QUERY",
                    "metricName": "各项存款余额",
                    "matchedText": "存款余额",
                    "scene": "OPERATIONS",
                    "domain": "assets_liabilities_deposits_loans",
                    "unit": "万元",
                    "aggregation": "SNAPSHOT",
                    "definition": None,
                },
                ensure_ascii=False,
            )

        checkpoints: list[int] = []
        metrics = [
            {
                "code": "CNB001",
                "name": "各项存款余额",
                "aliases": ["存款余额"],
                "scene": "OPERATIONS",
                "domain": "assets_liabilities_deposits_loans",
                "unit": "万元",
                "aggregation": "SNAPSHOT",
                "definition": "候选定义",
            }
        ]
        predictions = generate_predictions(records, completion=complete, metrics=metrics, on_checkpoint=lambda rows: checkpoints.append(len(rows)))
        self.assertEqual(2, len(predictions))
        self.assertEqual([1, 2], checkpoints)
        self.assertTrue(all(isinstance(row["latencyMs"], int) for row in predictions))

    def test_runner_retries_existing_error_rows(self) -> None:
        records = [{"id": "BMQ-001", "question": "查询存款余额。"}]
        calls = 0

        def complete(_: list[dict[str, str]]) -> str:
            nonlocal calls
            calls += 1
            return json.dumps(
                {
                    "metricCode": "CNB001",
                    "action": "ROUTE_TO_DATA_QUERY",
                    "metricName": "各项存款余额",
                    "matchedText": "存款余额",
                    "scene": "OPERATIONS",
                    "domain": "assets_liabilities_deposits_loans",
                    "unit": "万元",
                    "aggregation": "SNAPSHOT",
                    "definition": None,
                },
                ensure_ascii=False,
            )

        metrics = [
            {
                "code": "CNB001",
                "name": "各项存款余额",
                "aliases": ["存款余额"],
                "scene": "OPERATIONS",
                "domain": "assets_liabilities_deposits_loans",
                "unit": "万元",
                "aggregation": "SNAPSHOT",
                "definition": "候选定义",
            }
        ]
        predictions = generate_predictions(
            records,
            completion=complete,
            metrics=metrics,
            existing=[{"id": "BMQ-001", "error": "old timeout", "latencyMs": 60000}],
        )
        self.assertEqual(1, calls)
        self.assertEqual(1, len(predictions))
        self.assertNotIn("error", predictions[0])

    def test_invalid_json_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            parse_model_prediction("not json", sample_id="BMQ-abc")
