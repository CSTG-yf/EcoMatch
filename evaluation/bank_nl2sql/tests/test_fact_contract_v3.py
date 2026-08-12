#!/usr/bin/env python3
"""Regression tests for the full-denominator fact-contract v3 dry-run."""

from __future__ import annotations

import sys
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from fact_contract_v3 import (  # noqa: E402
    build_fact_contract,
    build_fact_contract_report,
    score_fact_contract_report,
)
from build_fact_contract_v3 import main as build_main  # noqa: E402
from score_fact_contract_v3 import main as score_main  # noqa: E402


def _record(
    sample_id: str,
    *,
    question: str,
    answer_text: str,
    columns: list[str],
    rows: list[list[object]],
) -> dict[str, object]:
    return {
        "id": sample_id,
        "question": question,
        "expected": {
            "answerText": answer_text,
            "columns": columns,
            "rows": rows,
            "numericTolerance": 1e-6,
            "orderSensitive": True,
            "unit": None,
        },
    }


class FactContractV3Test(unittest.TestCase):
    def test_question_threshold_is_context_not_required_result_fact(self) -> None:
        record = _record(
            "TRAIN-S-13",
            question="拨备覆盖率有没有超过150%的监管要求？",
            answer_text="达标，拨备覆盖率191.44%，高于150%",
            columns=["metric_value"],
            rows=[[191.44]],
        )

        contract = build_fact_contract(record)

        facts = {fact.value: fact for fact in contract.facts}
        self.assertTrue(facts[191.44].required)
        self.assertEqual(facts[191.44].support, "DIRECT_RESULT")
        self.assertFalse(facts[150.0].required)
        self.assertEqual(facts[150.0].support, "QUESTION_CONTEXT")
        self.assertEqual(contract.status, "READY")
        self.assertNotIn("MISSING_RESULT_SUPPORT", contract.reasons)

    def test_sum_fact_without_explicit_result_column_is_not_auto_derived(self) -> None:
        record = _record(
            "TRAIN-M-58",
            question="净利息收入和中间业务收入合计多少？",
            answer_text="合计463.61万元（净利息64.1万+中间业务399.51万）",
            columns=["metric_code", "metric_value"],
            rows=[["ZB020", 64.1], ["ZB021", 399.51]],
        )

        contract = build_fact_contract(record)

        total = next(fact for fact in contract.facts if fact.value == 463.61)
        self.assertTrue(total.required)
        self.assertEqual(total.support, "MISSING")
        self.assertIsNone(total.derivation)
        self.assertEqual(contract.status, "REVIEW_REQUIRED")
        self.assertIn("MISSING_RESULT_SUPPORT", contract.reasons)

    def test_typed_sum_fact_is_ready_when_formula_matches_rows(self) -> None:
        record = _record(
            "SUM-TYPED",
            question="两项收入合计多少？",
            answer_text="合计463.61万元",
            columns=["metric_code", "metric_value"],
            rows=[["ZB008", 64.1], ["ZB007", 399.51]],
        )
        record["expected"]["answerFacts"] = [
            {
                "id": "total_income",
                "value": 463.61,
                "kind": "NUMBER",
                "required": True,
                "binding": {
                    "organizationCodes": ["ORG003"],
                    "metricCodes": ["ZB008", "ZB007"],
                    "dates": ["2026-04-30"],
                    "comparisonType": "SUM",
                },
                "formula": {
                    "operation": "SUM",
                    "operands": [
                        {"column": "metric_value", "where": {"metric_code": "ZB008"}},
                        {"column": "metric_value", "where": {"metric_code": "ZB007"}},
                    ],
                },
            }
        ]

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "READY")
        self.assertEqual(contract.facts[0].support, "TYPED_RESULT")
        self.assertEqual(contract.facts[0].derivation, "SUM")

    def test_typed_nested_formula_can_compare_a_sum_with_a_total(self) -> None:
        record = _record(
            "SUM-DIFFERENCE-TYPED",
            question="分项之和与总额相差多少？",
            answer_text="相差0.01亿元",
            columns=["metric_code", "metric_value"],
            rows=[["ZB003", 35.52], ["ZB004", 64.48], ["ZB001", 99.99]],
        )
        record["expected"]["answerFacts"] = [
            {
                "id": "composition_gap",
                "value": 0.01,
                "kind": "NUMBER",
                "binding": {
                    "organizationCodes": ["ORG003"],
                    "metricCodes": ["ZB003", "ZB004", "ZB001"],
                    "dates": ["2025-06-30"],
                    "comparisonType": "DIFFERENCE",
                },
                "formula": {
                    "operation": "DIFFERENCE",
                    "operands": [
                        {
                            "formula": {
                                "operation": "SUM",
                                "operands": [
                                    {"column": "metric_value", "where": {"metric_code": "ZB003"}},
                                    {"column": "metric_value", "where": {"metric_code": "ZB004"}},
                                ],
                            }
                        },
                        {"column": "metric_value", "where": {"metric_code": "ZB001"}},
                    ],
                },
            }
        ]

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "READY")
        self.assertEqual(contract.facts[0].support, "TYPED_RESULT")

    def test_typed_fact_can_add_identity_to_an_existing_result_value(self) -> None:
        record = _record(
            "PROVINCE-TYPED",
            question="与全省均值相比差多少？",
            answer_text="低于全省均值18.11亿元",
            columns=["metric_code", "rounded_difference"],
            rows=[["ZB001", 18.11]],
        )
        record["expected"]["answerFacts"] = [
            {
                "id": "deposit_difference",
                "value": 18.11,
                "kind": "NUMBER",
                "binding": {
                    "organizationCodes": ["ORG004"],
                    "metricCodes": ["ZB001"],
                    "dates": ["2025-07-31"],
                    "comparisonType": "PROVINCE_COMPARISON",
                },
                "formula": {
                    "operation": "DIRECT",
                    "operands": [
                        {"column": "rounded_difference", "where": {"metric_code": "ZB001"}}
                    ],
                },
            }
        ]

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "READY")
        self.assertEqual(contract.facts[0].support, "TYPED_RESULT")

    def test_typed_fact_can_round_runtime_province_gap_with_financial_rounding(self) -> None:
        record = _record(
            "PROVINCE-ROUND-TYPED",
            question="与全省均值相比差多少？",
            answer_text="低于全省均值18.11亿元",
            columns=["org_code", "metric_code", "absolute_gap"],
            rows=[["ORG004", "ZB001", 18.10846153846154]],
        )
        record["expected"]["answerFacts"] = [
            {
                "id": "deposit_difference",
                "value": 18.11,
                "kind": "NUMBER",
                "binding": {
                    "organizationCodes": ["ORG004"],
                    "metricCodes": ["ZB001"],
                    "dates": ["2025-07-31"],
                    "comparisonType": "PROVINCE_COMPARISON",
                },
                "formula": {
                    "operation": "ROUND",
                    "scale": 2,
                    "operands": [
                        {"column": "absolute_gap", "where": {"metric_code": "ZB001"}}
                    ],
                },
            }
        ]

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "READY")
        self.assertEqual(contract.facts[0].support, "TYPED_RESULT")

    def test_typed_round_formula_rejects_invalid_scale(self) -> None:
        record = _record(
            "PROVINCE-ROUND-INVALID",
            question="与全省均值相比差多少？",
            answer_text="低于全省均值18.11亿元",
            columns=["metric_code", "absolute_gap"],
            rows=[["ZB001", 18.10846153846154]],
        )
        record["expected"]["answerFacts"] = [
            {
                "id": "deposit_difference",
                "value": 18.11,
                "kind": "NUMBER",
                "binding": {
                    "organizationCodes": ["ORG004"],
                    "metricCodes": ["ZB001"],
                    "dates": ["2025-07-31"],
                    "comparisonType": "PROVINCE_COMPARISON",
                },
                "formula": {
                    "operation": "ROUND",
                    "scale": "2",
                    "operands": [{"column": "absolute_gap"}],
                },
            }
        ]

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "REVIEW_REQUIRED")
        self.assertIn("ANSWER_FACT_0_INVALID_FORMULA", contract.reasons)

    def test_distinct_typed_facts_can_share_the_same_numeric_rank(self) -> None:
        record = _record(
            "TIED-RANKS-TYPED",
            question="列出两项指标的排名。",
            answer_text="各项存款余额第11名，各项贷款余额第11名。",
            columns=["metric_code", "rank_position"],
            rows=[["ZB001", 11], ["ZB002", 11]],
        )
        record["expected"]["answerFacts"] = [
            {
                "id": f"{metric}_rank",
                "value": 11,
                "kind": "RANK",
                "binding": {
                    "organizationCodes": ["ORG011"],
                    "metricCodes": [metric],
                    "dates": ["2025-12-31"],
                    "comparisonType": "POINT",
                },
                "formula": {
                    "operation": "DIRECT",
                    "operands": [
                        {"column": "rank_position", "where": {"metric_code": metric}}
                    ],
                },
            }
            for metric in ("ZB001", "ZB002")
        ]

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "READY")
        self.assertEqual(
            [fact.evidence["id"] for fact in contract.facts if fact.kind == "RANK"],
            ["ZB001_rank", "ZB002_rank"],
        )

    def test_typed_fact_fails_closed_when_declared_value_disagrees_with_formula(self) -> None:
        record = _record(
            "SUM-TYPED-BAD",
            question="两项收入合计多少？",
            answer_text="合计999万元",
            columns=["metric_code", "metric_value"],
            rows=[["ZB008", 64.1], ["ZB007", 399.51]],
        )
        record["expected"]["answerFacts"] = [
            {
                "id": "total_income",
                "value": 999.0,
                "kind": "NUMBER",
                "binding": {
                    "organizationCodes": ["ORG003"],
                    "metricCodes": ["ZB008", "ZB007"],
                    "dates": ["2026-04-30"],
                    "comparisonType": "SUM",
                },
                "formula": {
                    "operation": "SUM",
                    "operands": [
                        {"column": "metric_value", "where": {"metric_code": "ZB008"}},
                        {"column": "metric_value", "where": {"metric_code": "ZB007"}},
                    ],
                },
            }
        ]

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "REVIEW_REQUIRED")
        self.assertIn("ANSWER_FACT_0_FORMULA_MISMATCH", contract.reasons)

    def test_typed_fact_requires_identity_and_formula_contract(self) -> None:
        record = _record(
            "TYPED-INVALID",
            question="合计多少？",
            answer_text="合计3万元",
            columns=["metric_value"],
            rows=[[3.0]],
        )
        record["expected"]["answerFacts"] = [
            {"id": "total", "value": 3.0, "kind": "NUMBER", "binding": {}}
        ]

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "REVIEW_REQUIRED")
        self.assertIn("ANSWER_FACT_0_INVALID_BINDING", contract.reasons)

    def test_typed_fact_rejects_result_rows_outside_declared_binding(self) -> None:
        record = _record(
            "TYPED-WRONG-IDENTITY",
            question="江苏省D市农商行2025-07-31的存款是多少？",
            answer_text="9亿元",
            columns=["org_code", "metric_code", "data_date", "metric_value"],
            rows=[["ORG999", "ZB999", "2099-01-01", 9.0]],
        )
        record["expected"]["answerFacts"] = [
            {
                "id": "deposit",
                "value": 9.0,
                "kind": "NUMBER",
                "binding": {
                    "organizationCodes": ["ORG004"],
                    "metricCodes": ["ZB001"],
                    "dates": ["2025-07-31"],
                    "comparisonType": "POINT",
                },
                "formula": {
                    "operation": "DIRECT",
                    "operands": [{"column": "metric_value"}],
                },
            }
        ]

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "REVIEW_REQUIRED")
        self.assertIn(
            "ANSWER_FACT_0_FORMULA_RESULT_OUTSIDE_BINDING", contract.reasons
        )

    def test_missing_legacy_table_support_is_scorable_from_answer_fact(self) -> None:
        record = _record(
            "VAL-M-20",
            question="2025年12月末的人均利润是多少？",
            answer_text="1.02万元/人",
            columns=["metric_code", "metric_value"],
            rows=[["ZB011", 183.02]],
        )

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "REVIEW_REQUIRED")
        self.assertIn("MISSING_RESULT_SUPPORT", contract.reasons)
        self.assertEqual(contract.facts[0].support, "MISSING")

    def test_negative_value_after_higher_wording_is_source_semantic_risk(self) -> None:
        record = _record(
            "TRAIN-M-46",
            question="逾期贷款率比不良贷款率高多少？",
            answer_text="逾期贷款率1.15%，不良贷款率1.35%，高出-0.2个百分点",
            columns=["overdue_rate", "npl_rate"],
            rows=[[1.15, 1.35]],
        )

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "REVIEW_REQUIRED")
        self.assertIn("DIRECTION_SIGN_CONFLICT", contract.sourceRisks)

    def test_mean_comparison_binding_is_reported_without_excluding_the_case(self) -> None:
        record = _record(
            "VAL-M-18",
            question="不良率和全省均值比怎么样？",
            answer_text="高于均值0.0个百分点（本机构1.14%，全省1.143076923076923%）",
            columns=[
                "aggregate_value",
                "min_value",
                "max_value",
                "observation_count",
            ],
            rows=[[1.14, 1.14, 1.14, 1]],
        )

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "REVIEW_REQUIRED")
        self.assertIn("MISSING_RESULT_SUPPORT", contract.reasons)
        self.assertIn("SEMANTIC_BINDING_DIAGNOSTIC", contract.warnings)

    def test_equality_gap_without_explicit_result_column_is_not_auto_derived(self) -> None:
        record = _record(
            "TRAIN-S-22",
            question="对公存款加个人存款是不是等于各项存款？差额多少？",
            answer_text="对公42.32+个人74.66=116.98，各项116.98，差额0.0亿",
            columns=["metric_code", "metric_value"],
            rows=[["ZB003", 42.32], ["ZB004", 74.66], ["ZB001", 116.98]],
        )

        contract = build_fact_contract(record)

        zero = next(fact for fact in contract.facts if fact.value == 0.0)
        self.assertEqual(zero.support, "MISSING")
        self.assertIsNone(zero.derivation)
        self.assertEqual(contract.status, "REVIEW_REQUIRED")
        self.assertIn("MISSING_RESULT_SUPPORT", contract.reasons)

    def test_condition_count_is_derived_from_meets_condition_column(self) -> None:
        record = _record(
            "COUNT-TRUE-1",
            question="有多少家机构满足条件？",
            answer_text="2家",
            columns=["org_code", "meets_condition"],
            rows=[["ORG001", 1], ["ORG002", 0], ["ORG003", 1]],
        )

        contract = build_fact_contract(record)
        scored = score_fact_contract_report(
            {
                "items": [
                    {
                        "id": "COUNT-TRUE-1",
                        "resultColumns": record["expected"]["columns"],
                        "resultRows": record["expected"]["rows"],
                        "textSummary": "2家",
                    }
                ]
            },
            [record],
        )

        count_fact = next(fact for fact in contract.facts if fact.required)
        self.assertEqual(count_fact.support, "DERIVED_RESULT")
        self.assertEqual(count_fact.derivation, "COUNT_TRUE")
        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertTrue(scored["items"][0]["casePass"])

    def test_incidental_binary_flag_does_not_satisfy_an_unasked_count(self) -> None:
        record = _record(
            "INCIDENTAL-BINARY-FLAG-1",
            question="请列出满足条件的机构。",
            answer_text="共有2家机构满足条件。",
            columns=["org_code", "meets_condition"],
            rows=[["ORG001", 1], ["ORG002", 1], ["ORG003", 0]],
        )
        report = {
            "items": [
                {
                    "id": "INCIDENTAL-BINARY-FLAG-1",
                    "resultColumns": ["org_code", "meets_condition"],
                    "resultRows": [["ORG001", 1], ["ORG002", 1], ["ORG003", 0]],
                    "textSummary": None,
                }
            ]
        }

        contract = build_fact_contract(record)
        scored = score_fact_contract_report(report, [record], score_mode="result_only")

        count_fact = next(fact for fact in contract.facts if fact.value == 2.0)
        self.assertEqual(count_fact.support, "MISSING")
        self.assertIsNone(count_fact.derivation)
        self.assertFalse(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_result_only_score_does_not_gate_on_final_answer_text(self) -> None:
        record = _record(
            "RESULT-ONLY-1",
            question="余额是多少？",
            answer_text="42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )

        scored = score_fact_contract_report(
            {
                "items": [
                    {
                        "id": "RESULT-ONLY-1",
                        "resultColumns": record["expected"]["columns"],
                        "resultRows": record["expected"]["rows"],
                        "textSummary": "无法回答",
                    }
                ]
            },
            [record],
            score_mode="result_only",
        )

        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertTrue(scored["items"][0]["casePass"])
        self.assertNotIn("finalFactsExact", scored["items"][0])
        self.assertNotIn("finalFactAccuracy", scored["metrics"])
        self.assertNotIn("finalFactsExactHits", scored["metrics"])

    def test_result_only_rejects_cross_metric_arithmetic_coincidence(self) -> None:
        record = _record(
            "CROSS-METRIC-COINCIDENCE-1",
            question="请评估净利润较年初变化。",
            answer_text="净利润较年初增长21.25万元。",
            columns=[
                "org_code",
                "metric_code",
                "current_value",
                "baseline_value",
                "absolute_change",
            ],
            rows=[["ORG011", "ZB011", 122.43, 105.86, 16.57]],
        )
        report = {
            "items": [
                {
                    "id": "CROSS-METRIC-COINCIDENCE-1",
                    "resultColumns": [
                        "org_code",
                        "metric_code",
                        "current_value",
                        "baseline_value",
                        "absolute_change",
                    ],
                    "resultRows": [
                        ["ORG011", "ZB007", 172.05, 184.48, -12.43],
                        ["ORG011", "ZB008", 51.72, 8.71, 43.01],
                        ["ORG011", "ZB011", 122.43, 105.86, 16.57],
                        ["ORG011", "ZB012", 34.86, 34.77, 0.09],
                    ],
                    "textSummary": None,
                }
            ]
        }

        scored = score_fact_contract_report(report, [record], score_mode="result_only")

        self.assertFalse(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_result_only_rejects_unbound_runtime_number_for_missing_gold_fact(self) -> None:
        record = _record(
            "UNBOUND-RUNTIME-NUMBER-1",
            question="请评估净利润较年初变化。",
            answer_text="净利润较年初增长21.25万元。",
            columns=[
                "org_code",
                "metric_code",
                "current_value",
                "baseline_value",
                "absolute_change",
            ],
            rows=[["ORG011", "ZB011", 122.43, 105.86, 16.57]],
        )
        report = {
            "items": [
                {
                    "id": "UNBOUND-RUNTIME-NUMBER-1",
                    "resultColumns": [
                        "org_code",
                        "metric_code",
                        "current_value",
                        "baseline_value",
                        "absolute_change",
                    ],
                    "resultRows": [
                        ["ORG011", "ZB011", 122.43, 105.86, 16.57],
                        ["ORG011", "ZB007", 21.25, 0.0, 21.25],
                    ],
                    "textSummary": None,
                }
            ]
        }

        scored = score_fact_contract_report(report, [record], score_mode="result_only")

        self.assertFalse(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_report_keeps_every_record_in_denominator(self) -> None:
        ready = _record(
            "READY-1",
            question="余额是多少？",
            answer_text="42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )
        review = _record(
            "REVIEW-1",
            question="逾期贷款率比不良贷款率高多少？",
            answer_text="逾期贷款率1.15%，不良贷款率1.35%，高出-0.2个百分点",
            columns=["overdue_rate", "npl_rate"],
            rows=[[1.15, 1.35]],
        )

        report = build_fact_contract_report({"train": [ready], "dev": [review]})

        self.assertEqual(report["summary"]["recordCount"], 2)
        self.assertEqual(report["summary"]["readyCount"], 1)
        self.assertEqual(report["summary"]["reviewRequiredCount"], 1)
        self.assertEqual(report["summary"]["excludedCount"], 0)


class BuildFactContractV3CliTest(unittest.TestCase):
    def test_cli_reads_only_train_and_dev_and_writes_legacy_incomplete_dry_run(self) -> None:
        ready = _record(
            "READY-1",
            question="余额是多少？",
            answer_text="42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )
        incomplete = _record(
            "REVIEW-1",
            question="人均利润是多少？",
            answer_text="1.02万元/人",
            columns=["metric_value"],
            rows=[[183.02]],
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "train.jsonl").write_text(
                json.dumps(ready, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            (root / "dev.jsonl").write_text(
                json.dumps(incomplete, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            # A malformed test file proves the command never attempts to read it.
            (root / "test.jsonl").write_text("not-json\n", encoding="utf-8")
            output = root / "dry-run.json"

            code = build_main(
                [
                    str(root),
                    "--split",
                    "both",
                    "--legacy-incomplete-only",
                    "--output",
                    str(output),
                ]
            )

            self.assertEqual(code, 0)
            payload = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(payload["summary"]["inputRecordCount"], 2)
            self.assertEqual(payload["summary"]["recordCount"], 1)
            self.assertEqual(payload["items"][0]["sampleId"], "REVIEW-1")
            self.assertFalse(payload["policy"]["testRead"])


class ScoreFactContractV3Test(unittest.TestCase):
    def test_typed_sum_fact_is_recomputed_from_captured_rows(self) -> None:
        record = _record(
            "SUM-TYPED-SCORE",
            question="两项收入合计多少？",
            answer_text="合计463.61万元",
            columns=["metric_code", "metric_value"],
            rows=[["ZB008", 64.1], ["ZB007", 399.51]],
        )
        record["expected"]["answerFacts"] = [
            {
                "id": "total_income",
                "value": 463.61,
                "kind": "NUMBER",
                "binding": {
                    "organizationCodes": ["ORG003"],
                    "metricCodes": ["ZB008", "ZB007"],
                    "dates": ["2026-04-30"],
                    "comparisonType": "SUM",
                },
                "formula": {
                    "operation": "SUM",
                    "operands": [
                        {"column": "metric_value", "where": {"metric_code": "ZB008"}},
                        {"column": "metric_value", "where": {"metric_code": "ZB007"}},
                    ],
                },
            }
        ]
        report = {
            "items": [
                {
                    "id": record["id"],
                    "resultColumns": record["expected"]["columns"],
                    "resultRows": record["expected"]["rows"],
                }
            ]
        }

        scored = score_fact_contract_report(report, [record], score_mode="result_only")

        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertTrue(scored["items"][0]["casePass"])

    def test_score_emits_only_fact_v3_score_fields(self) -> None:
        record = _record(
            "V3-ONLY-1",
            question="余额是多少？",
            answer_text="余额42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )
        scored = score_fact_contract_report(
            {
                "items": [
                    {
                        "id": "V3-ONLY-1",
                        "resultColumns": ["metric_value"],
                        "resultRows": [[42.02]],
                        "textSummary": "余额42.02亿元",
                    }
                ]
            },
            [record],
        )

        for key in ("resultAccuracy", "tableExactAccuracy", "tableExactHits"):
            self.assertNotIn(key, scored["metrics"])
        self.assertNotIn("tableExact", scored["items"][0])

    def test_result_fact_match_fails_closed_when_projection_cannot_be_bound(self) -> None:
        record = _record(
            "STRUCTURE-1",
            question="余额是多少？",
            answer_text="余额42.02亿元",
            columns=["metric_code", "metric_value"],
            rows=[["ZB001", 42.02]],
        )
        report = {
            "items": [
                {
                    "id": "STRUCTURE-1",
                    "resultColumns": ["指标名称", "查询结果"],
                    "resultRows": [["各项存款", 42.02]],
                    "textSummary": "余额42.02亿元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertFalse(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_result_fact_match_rejects_wrong_date_binding_with_correct_values(self) -> None:
        record = _record(
            "ROW-BINDING-1",
            question="请给出两期余额及总体趋势。",
            answer_text="两期余额分别为10亿元和20亿元，整体上升",
            columns=["data_date", "metric_value"],
            rows=[["2025-01-31", 10.0], ["2025-02-28", 20.0]],
        )
        report = {
            "items": [
                {
                    "id": "ROW-BINDING-1",
                    "resultColumns": ["data_date", "metric_value"],
                    "resultRows": [["2099-01-01", 10.0], ["2099-01-01", 20.0]],
                    "textSummary": "两期余额分别为10亿元和20亿元，整体上升",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertFalse(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_standard_provincial_average_projection_binds_complete_legacy_identity(self) -> None:
        record = _record(
            "PROVINCIAL-AVERAGE-1",
            question="对江苏省D市农商行的四项指标与全省均值逐一对比。",
            answer_text=(
                "存款54.65亿元（低于全省均值18.11亿元）；贷款44.25亿元"
                "（低于全省均值14.83亿元）；不良率1.55%（高于全省均值0.41%）；"
                "净利润105.54万元（低于全省均值54.9万元）"
            ),
            columns=[
                "org_code",
                "org_name",
                "metric_code",
                "aggregate_value",
                "min_value",
                "max_value",
                "observation_count",
            ],
            rows=[
                ["ORG004", "江苏省D市农商行", "ZB001", 54.65, 54.65, 54.65, 1],
                ["ORG004", "江苏省D市农商行", "ZB002", 44.25, 44.25, 44.25, 1],
                ["ORG004", "江苏省D市农商行", "ZB011", 105.54, 105.54, 105.54, 1],
                ["ORG004", "江苏省D市农商行", "ZB013", 1.55, 1.55, 1.55, 1],
            ],
        )
        report = {
            "items": [
                {
                    "id": "PROVINCIAL-AVERAGE-1",
                    "resultColumns": [
                        "org_code",
                        "org_name",
                        "metric_code",
                        "metric_value",
                        "provincial_average",
                        "gap_value",
                        "absolute_gap",
                    ],
                    "resultRows": [
                        ["ORG004", "江苏省D市农商行", "ZB001", 54.65, 72.758, -18.108, 18.108],
                        ["ORG004", "江苏省D市农商行", "ZB002", 44.25, 59.076, -14.826, 14.826],
                        ["ORG004", "江苏省D市农商行", "ZB011", 105.54, 160.438, -54.898, 54.898],
                        ["ORG004", "江苏省D市农商行", "ZB013", 1.55, 1.142, 0.408, 0.408],
                    ],
                    "textSummary": (
                        "存款54.65亿元（低于全省均值18.11亿元）；贷款44.25亿元"
                        "（低于全省均值14.83亿元）；不良率1.55%（高于全省均值0.41%）；"
                        "净利润105.54万元（低于全省均值54.9万元）"
                    ),
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertTrue(scored["items"][0]["finalFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])
        self.assertEqual(scored["items"][0]["reason"], "contract_review_required")

    def test_complete_gold_rejects_extra_result_row(self) -> None:
        record = _record(
            "EXTRA-ROW-1",
            question="请给出两期余额。",
            answer_text="两期余额分别为10亿元和20亿元",
            columns=["data_date", "metric_value"],
            rows=[["2025-01-31", 10.0], ["2025-02-28", 20.0]],
        )
        report = {
            "items": [
                {
                    "id": "EXTRA-ROW-1",
                    "resultColumns": ["data_date", "metric_value"],
                    "resultRows": [
                        ["2025-01-31", 10.0],
                        ["2025-02-28", 20.0],
                        ["2099-12-31", 999999.0],
                    ],
                    "textSummary": "两期余额分别为10亿元和20亿元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertEqual(build_fact_contract(record).legacyGoldGrade, "GOLD_OK")
        self.assertFalse(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_final_fact_match_rejects_replaced_organization_entity(self) -> None:
        record = _record(
            "TEXT-ENTITY-1",
            question="请给出江苏省I市农商行的不良贷款率。",
            answer_text="江苏省I市农商行的不良贷款率为1.48%",
            columns=["org_code", "metric_code", "metric_value"],
            rows=[["ORG009", "ZB013", 1.48]],
        )
        record["normalizedIntent"] = {
            "organizations": [
                {
                    "code": "ORG009",
                    "name": "江苏省I市农商行",
                    "matchedText": "江苏省I市农商行",
                }
            ],
            "metrics": [
                {
                    "code": "ZB013",
                    "name": "不良贷款率",
                    "matchedText": "不良贷款率",
                }
            ],
        }
        report = {
            "items": [
                {
                    "id": "TEXT-ENTITY-1",
                    "resultColumns": ["org_code", "metric_code", "metric_value"],
                    "resultRows": [["ORG009", "ZB013", 1.48]],
                    "textSummary": "江苏省A市农商行的不良贷款率为1.48%",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["finalFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_final_fact_match_rejects_extra_number_and_contradictory_semantics(self) -> None:
        record = _record(
            "TEXT-EXACT-1",
            question="余额趋势如何？",
            answer_text="余额为20亿元，整体上升",
            columns=["metric_value"],
            rows=[[20.0]],
        )
        report = {
            "items": [
                {
                    "id": "TEXT-EXACT-1",
                    "resultColumns": ["metric_value"],
                    "resultRows": [[20.0]],
                    "textSummary": "余额为20亿元，整体上升，同时下降，另有999999亿元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["finalFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_final_fact_match_allows_complete_question_dates_in_direct_answer(self) -> None:
        cases = (
            (
                "DATE-CN-1",
                "江苏省A市农商行在2025年6月15日的各项存款余额是多少？",
                "42.02亿元",
                "江苏省A市农商行在2025年6月15日的各项存款余额为42.02亿元。",
                42.02,
            ),
            (
                "DATE-ISO-1",
                "截至2025-03-31，较2024年末变化了多少？",
                "增加0.2亿元",
                "截至2025-03-31，较2024年末增加0.2亿元。",
                0.2,
            ),
        )
        for sample_id, question, answer_text, text_summary, value in cases:
            with self.subTest(sample_id=sample_id):
                record = _record(
                    sample_id,
                    question=question,
                    answer_text=answer_text,
                    columns=["metric_value"],
                    rows=[[value]],
                )
                scored = score_fact_contract_report(
                    {
                        "items": [
                            {
                                "id": sample_id,
                                "resultColumns": ["metric_value"],
                                "resultRows": [[value]],
                                "textSummary": text_summary,
                            }
                        ]
                    },
                    [record],
                )

                self.assertTrue(scored["items"][0]["resultFactsExact"])
                self.assertTrue(scored["items"][0]["finalFactsExact"])
                self.assertTrue(scored["items"][0]["casePass"])

    def test_final_fact_match_accepts_equivalent_quarter_end_month_label(self) -> None:
        record = _record(
            "DATE-QUARTER-ALIAS-1",
            question="请分析江苏省I市农商行不良贷款率从2025年一季度末到2026年一季度末的逐季变化。",
            answer_text=(
                "江苏省I市农商行2025-03-31不良贷款率1.48%；"
                "江苏省I市农商行2025-06-30不良贷款率1.52%"
            ),
            columns=["data_date", "metric_value"],
            rows=[["2025-03-31", 1.48], ["2025-06-30", 1.52]],
        )
        report = {
            "items": [
                {
                    "id": "DATE-QUARTER-ALIAS-1",
                    "resultColumns": ["data_date", "metric_value"],
                    "resultRows": [["2025-03-31", 1.48], ["2025-06-30", 1.52]],
                    "textSummary": "江苏省I市农商行2025-03不良贷款率1.48%；2025-06不良贷款率1.52%",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertTrue(scored["items"][0]["finalFactsExact"])
        self.assertTrue(scored["items"][0]["casePass"])

    def test_final_fact_match_does_not_alias_arbitrary_daily_dates(self) -> None:
        record = _record(
            "DATE-DAILY-ALIAS-1",
            question="2025年6月15日的余额是多少？",
            answer_text="2025-06-15余额42.02亿元",
            columns=["data_date", "metric_value"],
            rows=[["2025-06-15", 42.02]],
        )
        report = {
            "items": [
                {
                    "id": "DATE-DAILY-ALIAS-1",
                    "resultColumns": ["data_date", "metric_value"],
                    "resultRows": [["2025-06-15", 42.02]],
                    "textSummary": "2025-06余额42.02亿元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertFalse(scored["items"][0]["finalFactsExact"])

    def test_question_date_parts_do_not_downgrade_same_numeric_answer_fact(self) -> None:
        record = _record(
            "DATE-PART-1",
            question="2025年6月15日的余额是多少？",
            answer_text="15亿元",
            columns=["metric_value"],
            rows=[[15.0]],
        )

        contract = build_fact_contract(record)
        fact = next(fact for fact in contract.facts if fact.value == 15.0)

        self.assertTrue(fact.required)
        self.assertEqual(fact.support, "DIRECT_RESULT")

    def test_final_fact_match_rejects_opposite_extreme_semantics(self) -> None:
        record = _record(
            "TEXT-EXTREME-1",
            question="哪个月余额最高？",
            answer_text="最高余额为20亿元",
            columns=["metric_value"],
            rows=[[20.0]],
        )
        report = {
            "items": [
                {
                    "id": "TEXT-EXTREME-1",
                    "resultColumns": ["metric_value"],
                    "resultRows": [[20.0]],
                    "textSummary": "最低余额为20亿元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["finalFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_result_table_cannot_replace_missing_final_answer(self) -> None:
        record = _record(
            "NO-TEXT-1",
            question="余额是多少？",
            answer_text="余额42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )
        report = {
            "items": [
                {
                    "id": "NO-TEXT-1",
                    "resultColumns": ["metric_value"],
                    "resultRows": [[42.02]],
                    "textSummary": None,
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["finalFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_captured_result_cannot_supply_untyped_derivation_missing_from_legacy_table(self) -> None:
        record = _record(
            "DERIVE-1",
            question="两项合计多少？",
            answer_text="合计10万元",
            columns=["legacy_partial_value"],
            rows=[[2.0]],
        )
        report = {
            "items": [
                {
                    "id": "DERIVE-1",
                    "resultColumns": ["first", "second"],
                    "resultRows": [[4.0, 6.0]],
                    "textSummary": "两项合计10万元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertEqual(build_fact_contract(record).facts[0].support, "MISSING")
        self.assertFalse(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_days_above_projection_binds_reviewed_legacy_column_aliases(self) -> None:
        record = _record(
            "DAYS-ABOVE-ALIASES-1",
            question="2025年全年，该机构的不良贷款率有多少天高于全省均值？",
            answer_text="高于全省均值的天数：0天；总天数：365天；占比：0%",
            columns=[
                "org_code",
                "org_name",
                "days_above_province_average",
                "observation_count",
                "above_ratio_percent",
            ],
            rows=[["ORG002", "江苏省B市农商行", 0, 365, 0.0]],
        )
        report = {
            "items": [
                {
                    "id": record["id"],
                    "resultColumns": [
                        "org_code",
                        "org_name",
                        "metric_code",
                        "days_above_average",
                        "total_days",
                        "ratio_percent",
                    ],
                    "resultRows": [
                        ["ORG002", "江苏省B市农商行", "ZB013", 0, 365, 0.0]
                    ],
                    "textSummary": None,
                }
            ]
        }

        scored = score_fact_contract_report(report, [record], score_mode="result_only")

        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertTrue(scored["items"][0]["casePass"])

    def test_days_above_projection_rejects_unreviewed_column_aliases(self) -> None:
        record = _record(
            "DAYS-ABOVE-ALIASES-2",
            question="有多少天高于全省均值？",
            answer_text="高于全省均值0天；总天数365天；占比0%",
            columns=[
                "days_above_province_average",
                "observation_count",
                "above_ratio_percent",
            ],
            rows=[[0, 365, 0.0]],
        )
        report = {
            "items": [
                {
                    "id": record["id"],
                    "resultColumns": ["days", "count", "percent"],
                    "resultRows": [[0, 365, 0.0]],
                    "textSummary": None,
                }
            ]
        }

        scored = score_fact_contract_report(report, [record], score_mode="result_only")

        self.assertFalse(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_legacy_incomplete_result_still_requires_available_identity_binding(self) -> None:
        record = _record(
            "DERIVE-IDENTITY-1",
            question="人均值是多少？",
            answer_text="人均值1.02万元",
            columns=["metric_code", "metric_value"],
            rows=[["ZB011", 183.02]],
        )
        report = {
            "items": [
                {
                    "id": "DERIVE-IDENTITY-1",
                    "resultColumns": ["first", "second"],
                    "resultRows": [[0.5, 0.52]],
                    "textSummary": "人均值1.02万元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertEqual(build_fact_contract(record).legacyGoldGrade, "GOLD_BAD")
        self.assertFalse(scored["items"][0]["resultFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_every_record_stays_in_denominator_and_review_contract_fails_closed(self) -> None:
        ready = _record(
            "READY-1",
            question="余额是多少？",
            answer_text="42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )
        review = _record(
            "REVIEW-1",
            question="逾期贷款率比不良贷款率高多少？",
            answer_text="逾期贷款率1.15%，不良贷款率1.35%，高出-0.2个百分点",
            columns=["overdue_rate", "npl_rate"],
            rows=[[1.15, 1.35]],
        )
        report = {
            "items": [
                {
                    "id": "READY-1",
                    "resultColumns": ["metric_value"],
                    "resultRows": [[42.02]],
                    "textSummary": "余额为42.02亿元",
                    "physicalSql": "THIS TEXT IS NOT SCORED",
                },
                {
                    "id": "REVIEW-1",
                    "resultColumns": ["overdue_rate", "npl_rate"],
                    "resultRows": [[1.15, 1.35]],
                    "textSummary": "逾期贷款率1.15%，不良贷款率1.35%，高出-0.2个百分点",
                },
            ]
        }

        scored = score_fact_contract_report(report, [ready, review])

        self.assertEqual(scored["metrics"]["caseDenominator"], 2)
        self.assertEqual(scored["metrics"]["casePassHits"], 1)
        self.assertEqual(scored["metrics"]["caseAccuracy"], 0.5)
        review_item = next(item for item in scored["items"] if item["id"] == "REVIEW-1")
        self.assertFalse(review_item["casePass"])
        self.assertFalse(review_item["resultExact"])
        self.assertEqual(review_item["reason"], "contract_review_required")

    def test_correct_summary_cannot_hide_wrong_result_table(self) -> None:
        record = _record(
            "X",
            question="余额是多少？",
            answer_text="42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )
        report = {
            "items": [
                {
                    "id": "X",
                    "resultColumns": ["metric_value"],
                    "resultRows": [[99.0]],
                    "textSummary": "余额为42.02亿元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertFalse(scored["items"][0]["resultExact"])
        self.assertTrue(scored["items"][0]["finalFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_known_organization_name_is_not_prefixed_by_question_verbs(self) -> None:
        record = _record(
            "ORG-PREFIX-1",
            question="请分析江苏省A市农商行的余额是多少？",
            answer_text="余额42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )
        record["normalizedIntent"] = {
            "organizations": [{"code": "ORG001", "name": "江苏省A市农商行"}],
            "metrics": [],
        }
        report = {
            "items": [
                {
                    "id": "ORG-PREFIX-1",
                    "resultColumns": ["metric_value"],
                    "resultRows": [[42.02]],
                    "textSummary": "江苏省A市农商行余额42.02亿元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertTrue(scored["items"][0]["finalFactsExact"])
        self.assertTrue(scored["items"][0]["casePass"])

    def test_ranking_prose_does_not_prefix_organization_names(self) -> None:
        record = _record(
            "ORG-RANKING-PREFIX-1",
            question="各项存款余额排名前三和后三分别是哪几家？",
            answer_text=(
                "前3名：江苏省C市农商行(116.12亿元)、江苏省G市农商行(110.5亿元)。"
                "后3名：江苏省H市农商行(38.5亿元)。"
            ),
            columns=["org_code", "org_name", "metric_code", "metric_value", "rank_position"],
            rows=[
                ["ORG003", "江苏省C市农商行", "ZB001", 116.12, 1],
                ["ORG007", "江苏省G市农商行", "ZB001", 110.5, 2],
                ["ORG008", "江苏省H市农商行", "ZB001", 38.5, 13],
            ],
        )
        report = {
            "items": [
                {
                    "id": "ORG-RANKING-PREFIX-1",
                    "resultColumns": ["org_code", "org_name", "metric_code", "metric_value", "rank_position"],
                    "resultRows": [
                        ["ORG003", "江苏省C市农商行", "ZB001", 116.12, 1],
                        ["ORG007", "江苏省G市农商行", "ZB001", 110.5, 2],
                        ["ORG008", "江苏省H市农商行", "ZB001", 38.5, 13],
                    ],
                    "textSummary": (
                        "排名前三的分别是江苏省C市农商行（116.12亿元）、江苏省G市农商行（110.50亿元）；"
                        "排名后三（第13名）的分别是江苏省H市农商行（38.50亿元）。"
                    ),
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertTrue(scored["items"][0]["finalFactsExact"])
        self.assertTrue(scored["items"][0]["casePass"])

    def test_sum_projection_without_typed_total_cannot_ground_final_fact(self) -> None:
        record = _record(
            "SUM-1",
            question="两项收入合计多少？",
            answer_text="合计463.61万元（64.1+399.51）",
            columns=["metric_code", "metric_value"],
            rows=[["A", 64.1], ["B", 399.51]],
        )
        report = {
            "items": [
                {
                    "id": "SUM-1",
                    "resultColumns": ["metric_code", "metric_value"],
                    "resultRows": [["A", 64.1], ["B", 399.51]],
                    "textSummary": "两项收入合计463.61万元（64.1+399.51）",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertFalse(scored["items"][0]["resultExact"])
        self.assertTrue(scored["items"][0]["finalFactsExact"])
        self.assertFalse(scored["items"][0]["casePass"])

    def test_missing_captured_rows_fails_closed(self) -> None:
        record = _record(
            "LEGACY-1",
            question="余额是多少？",
            answer_text="42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )
        report = {
            "items": [
                {
                    "id": "LEGACY-1",
                    "textSummary": "余额为42.02亿元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertFalse(scored["items"][0]["resultExact"])
        self.assertFalse(scored["items"][0]["casePass"])
        self.assertEqual(scored["items"][0]["resultEvidence"], "MISSING")

    def test_cli_writes_full_denominator_score(self) -> None:
        record = _record(
            "X",
            question="余额是多少？",
            answer_text="42.02亿元",
            columns=["metric_value"],
            rows=[[42.02]],
        )
        report = {
            "items": [
                {
                    "id": "X",
                    "resultColumns": ["metric_value"],
                    "resultRows": [[42.02]],
                    "textSummary": "余额为42.02亿元",
                }
            ]
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "train.jsonl").write_text(
                json.dumps(record, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            report_path = root / "run.json"
            report_path.write_text(json.dumps(report), encoding="utf-8")
            output = root / "score.json"

            code = score_main(
                [
                    str(root),
                    str(report_path),
                    "--split",
                    "train",
                    "--output",
                    str(output),
                ]
            )

            self.assertEqual(code, 0)
            scored = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(scored["metrics"]["caseDenominator"], 1)
            self.assertEqual(scored["metrics"]["caseAccuracy"], 1.0)


if __name__ == "__main__":
    unittest.main()
