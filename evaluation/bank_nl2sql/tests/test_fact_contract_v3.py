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

    def test_sum_fact_is_supported_by_deterministic_projection(self) -> None:
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
        self.assertEqual(total.support, "DERIVED_RESULT")
        self.assertEqual(total.derivation, "SUM")
        self.assertEqual(contract.status, "READY")

    def test_missing_legacy_table_support_is_scorable_from_answer_fact(self) -> None:
        record = _record(
            "VAL-M-20",
            question="2025年12月末的人均利润是多少？",
            answer_text="1.02万元/人",
            columns=["metric_code", "metric_value"],
            rows=[["ZB011", 183.02]],
        )

        contract = build_fact_contract(record)

        self.assertEqual(contract.status, "READY")
        self.assertNotIn("MISSING_RESULT_SUPPORT", contract.reasons)
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

        self.assertEqual(contract.status, "READY")
        self.assertIn("SEMANTIC_BINDING_DIAGNOSTIC", contract.warnings)

    def test_equality_gap_can_be_derived_from_two_parts_and_total(self) -> None:
        record = _record(
            "TRAIN-S-22",
            question="对公存款加个人存款是不是等于各项存款？差额多少？",
            answer_text="对公42.32+个人74.66=116.98，各项116.98，差额0.0亿",
            columns=["metric_code", "metric_value"],
            rows=[["ZB003", 42.32], ["ZB004", 74.66], ["ZB001", 116.98]],
        )

        contract = build_fact_contract(record)

        zero = next(fact for fact in contract.facts if fact.value == 0.0)
        self.assertEqual(zero.support, "DERIVED_RESULT")
        self.assertEqual(zero.derivation, "SUM_DIFFERENCE")
        self.assertEqual(contract.status, "READY")

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
        self.assertFalse(scored["items"][0]["tableExact"])
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

    def test_captured_result_can_supply_approved_derivation_missing_from_legacy_table(self) -> None:
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
        self.assertTrue(scored["items"][0]["resultFactsExact"])
        self.assertTrue(scored["items"][0]["casePass"])

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
        self.assertTrue(review_item["resultExact"])
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

    def test_approved_sum_projection_can_ground_final_fact(self) -> None:
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

        self.assertTrue(scored["items"][0]["resultExact"])
        self.assertTrue(scored["items"][0]["finalFactsExact"])
        self.assertTrue(scored["items"][0]["casePass"])

    def test_legacy_match_is_explicit_fallback_when_rows_were_not_captured(self) -> None:
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
                    "match": True,
                    "textSummary": "余额为42.02亿元",
                }
            ]
        }

        scored = score_fact_contract_report(report, [record])

        self.assertTrue(scored["items"][0]["casePass"])
        self.assertEqual(scored["items"][0]["resultEvidence"], "LEGACY_MATCH")

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
