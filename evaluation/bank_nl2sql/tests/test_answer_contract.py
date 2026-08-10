#!/usr/bin/env python3
"""Tests for gold-contract gate and answerExact scoring."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from answer_contract import (  # noqa: E402
    assess_gold_contract,
    extract_answer_slots,
    score_answer_exact,
    values_close,
)
from score_answer_exact import score_report_against_dataset  # noqa: E402
from validate_gold_contract import main as validate_main  # noqa: E402


class AnswerSlotExtractionTest(unittest.TestCase):
    def test_extracts_percent_and_quantity_and_skips_years_and_rank_cardinals(self) -> None:
        slots = extract_answer_slots(
            "前3名：江苏省H市农商行(2.58%)、江苏省D市农商行(2.36%)。2025年数据。"
        )
        required = {(slot.value, slot.kind) for slot in slots if slot.required}
        optional = {(slot.value, slot.kind) for slot in slots if not slot.required}
        self.assertIn((2.58, "percent"), required)
        self.assertIn((2.36, "percent"), required)
        self.assertIn((3.0, "rank_cardinal"), optional)
        self.assertIn((2025.0, "year"), optional)

    def test_skips_year_month_tokens_in_trend_answers(self) -> None:
        slots = extract_answer_slots(
            "各季度末：2025-03(41.96亿元)、2025-06(41.78亿元)。整体呈上升趋势。"
        )
        required_values = {slot.value for slot in slots if slot.required}
        self.assertEqual(required_values, {41.96, 41.78})

    def test_sign_insensitive_close(self) -> None:
        self.assertTrue(values_close(11.69, -11.69))


class GoldContractTest(unittest.TestCase):
    def test_gold_ok_when_rows_cover_answer_numbers(self) -> None:
        record = {
            "id": "TRAIN-M-11",
            "expected": {
                "answerText": "下降11.69万元",
                "columns": ["current_value", "baseline_value", "absolute_change", "percent_change"],
                "rows": [[78.6, 90.29, -11.69, -12.95]],
                "numericTolerance": 1e-6,
                "orderSensitive": True,
                "unit": None,
            },
        }
        assessment = assess_gold_contract(record)
        self.assertEqual(assessment.grade, "GOLD_OK")
        self.assertEqual(assessment.coverageRate, 1.0)

    def test_gold_bad_when_ratio_answer_missing_from_rows(self) -> None:
        record = {
            "id": "TRAIN-M-31",
            "expected": {
                "answerText": "对公存款占比35.52%，个人存款占比64.48%",
                "columns": ["org_code", "org_name", "metric_code", "metric_value"],
                "rows": [["ORG002", "江苏省B市农商行", "ZB001", 52.11]],
                "numericTolerance": 1e-6,
                "orderSensitive": True,
                "unit": None,
            },
        }
        assessment = assess_gold_contract(record)
        self.assertEqual(assessment.grade, "GOLD_BAD")
        self.assertEqual(set(assessment.uncoveredRequired), {35.52, 64.48})

    def test_gold_partial_when_only_some_answer_numbers_present(self) -> None:
        record = {
            "id": "TRAIN-S-24",
            "expected": {
                "answerText": "个人贷款占比46.46%，对公贷款占比53.54%",
                "columns": ["org_code", "ratio_percent"],
                "rows": [["ORG007", 53.543219490488376]],
                "numericTolerance": 1e-6,
                "orderSensitive": False,
                "unit": None,
            },
        }
        assessment = assess_gold_contract(record)
        self.assertEqual(assessment.grade, "GOLD_PARTIAL")
        self.assertIn(46.46, assessment.uncoveredRequired)


class AnswerExactScoreTest(unittest.TestCase):
    def test_answer_exact_hits_all_required_slots(self) -> None:
        record = {
            "id": "X",
            "expected": {
                "answerText": "对公存款占比35.52%，个人存款占比64.48%",
                "columns": ["role", "ratio_percent"],
                "rows": [["corp", 35.52], ["retail", 64.48]],
                "numericTolerance": 0.01,
                "orderSensitive": False,
                "unit": None,
            },
        }
        # Gold itself OK
        self.assertEqual(assess_gold_contract(record).grade, "GOLD_OK")
        score = score_answer_exact(
            record,
            columns=["role", "ratio_percent"],
            rows=[["corp", 35.52], ["retail", 64.48]],
        )
        self.assertTrue(score.scored)
        self.assertTrue(score.answerExact)
        self.assertEqual(score.slotRecall, 1.0)

    def test_unscored_when_gold_contract_incomplete(self) -> None:
        record = {
            "id": "TRAIN-M-31",
            "expected": {
                "answerText": "对公存款占比35.52%，个人存款占比64.48%",
                "columns": ["metric_value"],
                "rows": [[52.11]],
                "numericTolerance": 1e-6,
                "orderSensitive": True,
                "unit": None,
            },
        }
        score = score_answer_exact(
            record,
            columns=["metric_value"],
            rows=[[52.11]],
            require_gold_ok=True,
        )
        self.assertFalse(score.scored)
        self.assertFalse(score.answerExact)
        self.assertEqual(score.goldGrade, "GOLD_BAD")

    def test_report_scorer_excludes_incomplete_gold_from_denominator(self) -> None:
        records = [
            {
                "id": "OK-1",
                "expected": {
                    "answerText": "42.02亿元",
                    "columns": ["metric_value"],
                    "rows": [[42.02]],
                    "numericTolerance": 1e-6,
                    "orderSensitive": True,
                    "unit": None,
                },
            },
            {
                "id": "BAD-1",
                "expected": {
                    "answerText": "对公存款占比35.52%，个人存款占比64.48%",
                    "columns": ["metric_value"],
                    "rows": [[52.11]],
                    "numericTolerance": 1e-6,
                    "orderSensitive": True,
                    "unit": None,
                },
            },
        ]
        report = {
            "metrics": {"resultAccuracy": 1.0},
            "items": [
                {
                    "id": "OK-1",
                    "resultColumns": ["metric_value"],
                    "resultRows": [[42.02]],
                    "match": True,
                },
                {
                    "id": "BAD-1",
                    "resultColumns": ["metric_value"],
                    "resultRows": [[52.11]],
                    "match": True,
                },
            ],
        }
        scored = score_report_against_dataset(report, records, require_gold_ok=True)
        self.assertEqual(scored["metrics"]["answerExactDenominator"], 1)
        self.assertEqual(scored["metrics"]["answerExactHits"], 1)
        self.assertEqual(scored["metrics"]["answerExact"], 1.0)


class ValidateGoldContractCliTest(unittest.TestCase):
    def test_cli_scans_split_and_writes_report(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "train.jsonl").write_text(
                json.dumps(
                    {
                        "id": "TRAIN-S-01",
                        "expected": {
                            "answerText": "42.02亿元",
                            "columns": ["metric_value"],
                            "rows": [[42.02]],
                            "numericTolerance": 1e-6,
                            "orderSensitive": True,
                            "unit": None,
                        },
                    },
                    ensure_ascii=False,
                )
                + "\n",
                encoding="utf-8",
            )
            output = root / "report.json"
            code = validate_main(
                [
                    str(root),
                    "--split",
                    "train",
                    "--output",
                    str(output),
                ]
            )
            self.assertEqual(code, 0)
            payload = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(payload["splits"]["train"]["byGrade"]["GOLD_OK"], 1)


if __name__ == "__main__":
    unittest.main()
