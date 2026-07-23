#!/usr/bin/env python3
"""Focused contract tests for deterministic gold SQL generation."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from gold_sql import GoldSqlError, build_gold_sql  # noqa: E402


def record(question: str, intent: str, metrics: list[str], expressions: list[str], organizations: list[str]) -> dict:
    return {
        "id": "CASE-01",
        "question": question,
        "normalizedIntent": {
            "intent": intent,
            "metrics": [{"code": code} for code in metrics],
            "time": {"expressions": expressions},
            "organizations": [{"code": code} for code in organizations],
        },
    }


class GoldSqlTest(unittest.TestCase):
    def test_point_query_uses_metric_org_and_resolved_month_end(self) -> None:
        spec = build_gold_sql(
            record(
                "江苏省A市农商行2025年6月末的各项存款余额是多少？",
                "POINT_QUERY",
                ["ZB001"],
                ["2025年6月末"],
                ["ORG001"],
            )
        )
        self.assertIn("metric_code = 'ZB001'", spec.sql)
        self.assertIn("org_code = 'ORG001'", spec.sql)
        self.assertIn("data_date = '2025-06-30'", spec.sql)
        self.assertEqual(spec.features, ["POINT_QUERY"])

    def test_ranking_query_uses_window_function_and_npl_ascending(self) -> None:
        spec = build_gold_sql(
            record(
                "2026年3月末，哪家农商行的不良贷款率最低？",
                "RANKING",
                ["ZB013"],
                ["2026年3月末"],
                [],
            )
        )
        self.assertIn("ROW_NUMBER() OVER (ORDER BY metric_value ASC)", spec.sql)
        self.assertIn("rank_position = 1", spec.sql)
        self.assertEqual(spec.features, ["RANKING", "WINDOW_RANK"])

    def test_year_end_change_uses_explicit_baseline(self) -> None:
        spec = build_gold_sql(
            record(
                "江苏省A市农商行的各项存款余额截至2025-03-31，和2024年末相比变化了多少？",
                "CHANGE",
                ["ZB001"],
                ["2025-03-31", "2024年末"],
                ["ORG001"],
            )
        )
        self.assertIn("'2025-03-31'", spec.sql)
        self.assertIn("'2024-12-31'", spec.sql)
        self.assertIn("current_value - baseline_value", spec.sql)
        self.assertEqual(spec.features, ["CHANGE", "BASELINE_COMPARISON"])

    def test_unsupported_intent_is_rejected(self) -> None:
        with self.assertRaises(GoldSqlError):
            build_gold_sql(record("未知问题", "UNKNOWN", ["ZB001"], ["2025-01-01"], ["ORG001"]))


if __name__ == "__main__":
    unittest.main()
