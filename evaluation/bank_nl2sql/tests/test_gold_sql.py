#!/usr/bin/env python3
"""Focused contract tests for deterministic gold SQL generation."""

from __future__ import annotations

import sqlite3
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from gold_sql import GoldSqlError, build_gold_sql  # noqa: E402


def record(
    question: str,
    intent: str,
    metrics: list[str],
    expressions: list[str],
    organizations: list[str],
    derived_metrics: list[dict] | None = None,
) -> dict:
    normalized = {
        "intent": intent,
        "metrics": [{"code": code} for code in metrics],
        "time": {"expressions": expressions},
        "organizations": [{"code": code} for code in organizations],
    }
    if derived_metrics is not None:
        normalized["derivedMetrics"] = derived_metrics
    return {
        "id": "CASE-01",
        "question": question,
        "normalizedIntent": normalized,
    }


BANK_SCHEMA = """
CREATE TABLE bank_organization (
  org_code TEXT PRIMARY KEY,
  org_name TEXT NOT NULL
);
CREATE TABLE bank_metric_daily (
  data_date TEXT NOT NULL,
  org_code TEXT NOT NULL,
  metric_code TEXT NOT NULL,
  metric_value REAL NOT NULL
);
"""

BANK_ORGANIZATIONS = [
    ("ORG001", "江苏省A市农商行"),
    ("ORG002", "江苏省B市农商行"),
    ("ORG003", "江苏省C市农商行"),
    ("ORG004", "江苏省D市农商行"),
]


def execute_gold_sql(sql: str, daily: list[tuple[str, str, str, float]]) -> list[tuple]:
    """Run gold SQL against a minimal in-memory SQLite bank schema."""
    connection = sqlite3.connect(":memory:")
    try:
        connection.executescript(BANK_SCHEMA)
        connection.executemany(
            "INSERT INTO bank_organization (org_code, org_name) VALUES (?, ?)", BANK_ORGANIZATIONS
        )
        connection.executemany(
            "INSERT INTO bank_metric_daily (data_date, org_code, metric_code, metric_value)"
            " VALUES (?, ?, ?, ?)",
            daily,
        )
        return connection.execute(sql).fetchall()
    finally:
        connection.close()


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
        self.assertIn("ROW_NUMBER() OVER (ORDER BY metric_value ASC, o.org_code)", spec.sql)
        self.assertIn("rank_position = 1", spec.sql)
        self.assertEqual(spec.features, ["RANKING", "WINDOW_RANK"])

    def test_ranking_query_accepts_chinese_top_number(self) -> None:
        spec = build_gold_sql(
            record(
                "\u4e0d\u826f\u8d37\u6b3e\u7387\u6392\u540d\u524d\u4e09",
                "RANKING",
                ["ZB013"],
                ["2025-09-30"],
                [],
            )
        )
        self.assertIn("rank_position <= 3", spec.sql)

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

    def test_selected_organization_ranking_keeps_the_global_rank(self) -> None:
        spec = build_gold_sql(
            record(
                "2025-11-30\u67d0\u94f6\u884c\u6392\u7b2c\u51e0",
                "RANKING",
                ["ZB012"],
                ["2025-11-30"],
                ["ORG007"],
            )
        )
        self.assertIn("WHERE org_code IN ('ORG007')", spec.sql)
        self.assertNotIn("rank_position = 1", spec.sql)

    def test_last_rank_keeps_the_normal_ranking_direction_and_selects_the_tail(self) -> None:
        spec = build_gold_sql(
            record(
                "2025年8月末，全省净利润排最后一名的是哪家？",
                "RANKING",
                ["ZB011"],
                ["2025年8月末"],
                [],
            )
        )
        self.assertIn("ORDER BY metric_value DESC", spec.sql)
        self.assertIn("rank_position > total_count - 1", spec.sql)
        self.assertIn("ORDER BY rank_position DESC", spec.sql)

    def test_annual_average_ranking_returns_both_top_and_bottom_groups(self) -> None:
        spec = build_gold_sql(
            record(
                "2025\u5e74\u5168\u5e74\u51c0\u5229\u6da6\u5747\u503c\u6392\u540d\u524d3\u548c\u540e3",
                "RANKING",
                ["ZB011"],
                ["2025\u5e74\u5168\u5e74"],
                [],
            )
        )
        self.assertIn("AVG(d.metric_value) AS metric_value", spec.sql)
        self.assertIn("rank_position > total_count - 3", spec.sql)
        self.assertEqual(spec.features, ["RANKING", "WINDOW_RANK", "DATE_RANGE", "AVERAGE", "TOP_BOTTOM"])

    def test_annual_average_ranking_accepts_chinese_top_and_bottom_numbers(self) -> None:
        spec = build_gold_sql(
            record(
                "2025年全年，各项存款余额的均值排名前三和后三的分别是哪几家？",
                "RANKING",
                ["ZB001"],
                ["2025年全年"],
                [],
            )
        )
        self.assertIn("AVG(d.metric_value) AS metric_value", spec.sql)
        self.assertIn("rank_position <= 3", spec.sql)
        self.assertIn("rank_position > total_count - 3", spec.sql)

    def test_annual_daily_extrema_returns_numeric_maximum_then_minimum(self) -> None:
        spec = build_gold_sql(
            record(
                "2025年全年，各项贷款余额的单日最高值出现在哪家？单日最低值在哪家？",
                "RANKING",
                ["ZB002"],
                ["2025年全年"],
                [],
            )
        )
        self.assertIn("BETWEEN '2025-01-01' AND '2025-12-31'", spec.sql)
        self.assertIn("ORDER BY d.metric_value DESC", spec.sql)
        self.assertIn("ORDER BY d.metric_value ASC", spec.sql)
        self.assertIn("UNION ALL", spec.sql)

    def test_annual_daily_statistics_for_one_organization_use_the_full_range(self) -> None:
        spec = build_gold_sql(
            record(
                "江苏省J市农商行2025年全年的各项存款余额日均值是多少？最高日和最低日分别出现在什么水平？",
                "RANKING",
                ["ZB001"],
                ["2025年全年"],
                ["ORG010"],
            )
        )
        self.assertIn("AVG(d.metric_value) AS aggregate_value", spec.sql)
        self.assertIn("MAX(d.metric_value) AS max_value", spec.sql)
        self.assertIn("MIN(d.metric_value) AS min_value", spec.sql)
        self.assertIn("d.org_code = 'ORG010'", spec.sql)
        self.assertEqual(spec.features, ["AGGREGATION", "DATE_RANGE", "AVERAGE", "EXTREMA"])

    def test_subset_winner_filters_before_ranking_and_returns_one_row(self) -> None:
        spec = build_gold_sql(
            record(
                "2025年底，江苏省A市农商行、江苏省E市农商行、江苏省I市农商行三家谁存款最多？",
                "RANKING",
                ["ZB001"],
                ["2025年底"],
                ["ORG001", "ORG005", "ORG009"],
            )
        )
        ranking_sql, result_sql = spec.sql.split(")\nSELECT", 1)
        self.assertIn("d.org_code IN ('ORG001', 'ORG005', 'ORG009')", ranking_sql)
        self.assertIn("rank_position = 1", result_sql)
        self.assertNotIn("org_code IN", result_sql)

    def test_change_query_returns_month_over_month_and_year_over_year(self) -> None:
        spec = build_gold_sql(
            record(
                "2026-04-30\u51c0\u5229\u6da6\u73af\u6bd4\u548c\u540c\u6bd4\u5206\u522b\u53d8\u52a8",
                "CHANGE",
                ["ZB011"],
                ["2026-04-30"],
                ["ORG010"],
            )
        )
        self.assertIn("UNION ALL", spec.sql)
        self.assertIn("'2026-03-31'", spec.sql)
        self.assertIn("'2025-04-30'", spec.sql)
        self.assertEqual(spec.features, ["CHANGE", "BASELINE_COMPARISON", "MOM_YOY"])

    def test_unsupported_intent_is_rejected(self) -> None:
        with self.assertRaises(GoldSqlError):
            build_gold_sql(record("未知问题", "UNKNOWN", ["ZB001"], ["2025-01-01"], ["ORG001"]))

    def test_invalid_metric_code_is_rejected(self) -> None:
        for code in ("ZB01", "ZB0011", "zb001", "ZB001 ", "存款余额"):
            with self.subTest(code=code):
                with self.assertRaises(GoldSqlError):
                    build_gold_sql(
                        record("江苏省A市农商行各项存款余额是多少？", "POINT_QUERY", [code], ["2025-01-31"], ["ORG001"])
                    )

    def test_duplicate_metric_codes_are_rejected(self) -> None:
        with self.assertRaises(GoldSqlError):
            build_gold_sql(
                record("江苏省A市农商行各项存款余额是多少？", "POINT_QUERY", ["ZB001", "ZB001"], ["2025-01-31"], ["ORG001"])
            )

    def test_ranking_does_not_swallow_invalid_or_duplicate_metric_codes(self) -> None:
        """RANKING 的 _status_metrics 回退只适用于空 metrics 推断失败，直接输入的非法/重复 base 必须 fail closed。"""
        for metrics in (["ZB01"], ["ZB001", "ZB001"]):
            with self.subTest(metrics=metrics):
                with self.assertRaises(GoldSqlError):
                    build_gold_sql(
                        record("哪家农商行的不良贷款率最低？", "RANKING", metrics, ["2026年3月末"], [])
                    )

    def test_malformed_metrics_are_rejected(self) -> None:
        non_list = record("江苏省A市农商行各项存款余额是多少？", "POINT_QUERY", [], ["2025-01-31"], ["ORG001"])
        non_list["normalizedIntent"]["metrics"] = "ZB001"
        with self.assertRaises(GoldSqlError):
            build_gold_sql(non_list)
        non_dict_entry = record("江苏省A市农商行各项存款余额是多少？", "POINT_QUERY", [], ["2025-01-31"], ["ORG001"])
        non_dict_entry["normalizedIntent"]["metrics"] = [["ZB001"]]
        with self.assertRaises(GoldSqlError):
            build_gold_sql(non_dict_entry)

    def test_empty_metrics_keeps_question_text_inference(self) -> None:
        spec = build_gold_sql(
            record("江苏省A市农商行各项存款余额是多少？", "POINT_QUERY", [], ["2025-01-31"], ["ORG001"])
        )
        self.assertIn("metric_code = 'ZB001'", spec.sql)
        self.assertEqual(spec.features, ["POINT_QUERY"])

    def test_explicit_metrics_with_empty_code_are_rejected(self) -> None:
        """显式 metrics 含空 code 必须 fail closed，不得回退到题干推断。"""
        malformed = record(
            "江苏省A市农商行各项存款余额是多少？", "POINT_QUERY", [], ["2025-01-31"], ["ORG001"]
        )
        malformed["normalizedIntent"]["metrics"] = [{"code": ""}]
        with self.assertRaises(GoldSqlError):
            build_gold_sql(malformed)

    def test_explicit_metrics_without_code_are_rejected(self) -> None:
        """显式 metrics 含缺失 code 的对象必须 fail closed，不得回退到题干推断。"""
        malformed = record(
            "江苏省A市农商行各项存款余额是多少？", "POINT_QUERY", [], ["2025-01-31"], ["ORG001"]
        )
        malformed["normalizedIntent"]["metrics"] = [{}]
        with self.assertRaises(GoldSqlError):
            build_gold_sql(malformed)

    def test_explicit_metrics_with_non_string_code_are_rejected(self) -> None:
        """显式 metrics 中 code 非字符串必须 fail closed，不得强转后放行。"""
        malformed = record(
            "江苏省A市农商行各项存款余额是多少？", "POINT_QUERY", [], ["2025-01-31"], ["ORG001"]
        )
        malformed["normalizedIntent"]["metrics"] = [{"code": 123}]
        with self.assertRaises(GoldSqlError):
            build_gold_sql(malformed)

    def test_invalid_derived_metrics_are_rejected(self) -> None:
        invalid_derived = [
            {"numerator": "NOT_A_CODE", "denominator": "ZB001"},
            {"numerator": "ZB002", "denominator": "NOT_A_CODE"},
            {"numerator": "ZB002", "denominator": "ZB002"},
        ]
        for derived in invalid_derived:
            with self.subTest(derived=derived):
                with self.assertRaises(GoldSqlError):
                    build_gold_sql(
                        record(
                            "请列出江苏省G市农商行在2025-11-30的主要经营指标及排名？",
                            "RANKING",
                            ["ZB001", "ZB002"],
                            ["2025-11-30"],
                            [],
                            derived_metrics=[derived],
                        )
                    )

    def test_non_list_derived_metrics_are_rejected(self) -> None:
        malformed = record(
            "请列出江苏省G市农商行在2025-11-30的主要经营指标及排名？",
            "RANKING",
            ["ZB001", "ZB002"],
            ["2025-11-30"],
            [],
        )
        malformed["normalizedIntent"]["derivedMetrics"] = {"numerator": "ZB002", "denominator": "ZB001"}
        with self.assertRaises(GoldSqlError):
            build_gold_sql(malformed)

    def test_derived_ratio_rank_sqlite_base_plus_derived_returns_six_rows(self) -> None:
        """5 个声明基础指标 + 1 个派生比率 = 6 行，派生值 = ZB002/ZB001*100。"""
        daily = [
            ("2025-11-30", "ORG001", "ZB001", 100.0),
            ("2025-11-30", "ORG001", "ZB002", 90.0),
            ("2025-11-30", "ORG001", "ZB011", 10.0),
            ("2025-11-30", "ORG001", "ZB012", 5.0),
            ("2025-11-30", "ORG001", "ZB013", 2.0),
        ]
        spec = build_gold_sql(
            record(
                "请列出江苏省G市农商行在2025-11-30的主要经营指标及排名？",
                "RANKING",
                ["ZB001", "ZB002", "ZB011", "ZB012", "ZB013"],
                ["2025-11-30"],
                [],
                derived_metrics=[
                    {
                        "metricCode": "DERIVED_ZB002_DIV_ZB001",
                        "numerator": "ZB002",
                        "denominator": "ZB001",
                        "name": "存贷比",
                    }
                ],
            )
        )
        result = execute_gold_sql(spec.sql, daily)

        self.assertEqual(len(result), 6)
        # 输出只含 5 个声明基础指标 + 1 个派生指标，无未声明默认指标
        self.assertEqual(
            [row[0] for row in result],
            ["DERIVED_ZB002_DIV_ZB001", "ZB001", "ZB002", "ZB011", "ZB012", "ZB013"],
        )
        derived = result[0]
        self.assertEqual(derived[1], "ORG001")
        self.assertEqual(derived[2], "江苏省A市农商行")
        self.assertAlmostEqual(derived[3], 90.0, places=6)
        self.assertEqual(derived[4], 1)

    def test_derived_ratio_rank_sqlite_without_derived_returns_five_rows(self) -> None:
        """无派生契约时输出 5 行，且不含未声明的默认基础指标。"""
        daily = [
            ("2025-11-30", "ORG001", "ZB001", 100.0),
            ("2025-11-30", "ORG001", "ZB002", 90.0),
            ("2025-11-30", "ORG001", "ZB011", 10.0),
            ("2025-11-30", "ORG001", "ZB012", 5.0),
            ("2025-11-30", "ORG001", "ZB013", 2.0),
        ]
        spec = build_gold_sql(
            record(
                "请列出江苏省G市农商行在2025-11-30的主要经营指标及排名？",
                "RANKING",
                ["ZB001", "ZB002", "ZB011", "ZB012", "ZB013"],
                ["2025-11-30"],
                [],
            )
        )
        result = execute_gold_sql(spec.sql, daily)

        self.assertEqual(len(result), 5)
        self.assertEqual([row[0] for row in result], ["ZB001", "ZB002", "ZB011", "ZB012", "ZB013"])

    def test_derived_ratio_rank_sqlite_orders_desc_stable_and_excludes_zero_denominator(self) -> None:
        """派生排名按 ratio DESC、org_code 稳定；分母为 0 的机构不产生派生行。

        无有效比率的机构在排名前被过滤，NULL 永不排到有效值之前；请求机构
        无有效比率时派生查询返回无行。
        """
        daily = [
            ("2025-11-30", "ORG001", "ZB001", 100.0),
            ("2025-11-30", "ORG001", "ZB002", 90.0),
            ("2025-11-30", "ORG002", "ZB001", 200.0),
            ("2025-11-30", "ORG002", "ZB002", 140.0),
            ("2025-11-30", "ORG003", "ZB001", 0.0),
            ("2025-11-30", "ORG003", "ZB002", 60.0),
            ("2025-11-30", "ORG004", "ZB001", 100.0),
            ("2025-11-30", "ORG004", "ZB002", 70.0),
        ]
        derived = [{"metricCode": "DERIVED_ZB002_DIV_ZB001", "numerator": "ZB002", "denominator": "ZB001", "name": "存贷比"}]
        spec = build_gold_sql(
            record(
                "请列出各农商行在2025-11-30的存贷比排名？",
                "RANKING",
                ["ZB002", "ZB001"],
                ["2025-11-30"],
                [],
                derived_metrics=derived,
            )
        )
        result = execute_gold_sql(spec.sql, daily)
        derived_rows = [row for row in result if row[0] == "DERIVED_ZB002_DIV_ZB001"]

        # ratio: ORG001=90, ORG002=70, ORG004=70（tie 按 org_code 稳定），ORG003 分母 0 被排除
        self.assertEqual([row[1] for row in derived_rows], ["ORG001", "ORG002", "ORG004"])
        self.assertEqual([row[4] for row in derived_rows], [1, 2, 3])
        self.assertAlmostEqual(derived_rows[0][3], 90.0, places=6)
        self.assertAlmostEqual(derived_rows[1][3], 70.0, places=6)
        self.assertAlmostEqual(derived_rows[2][3], 70.0, places=6)
        self.assertTrue(all(row[3] is not None for row in derived_rows))

        # 请求机构无有效比率（分母为 0）时，派生查询返回无行
        org_spec = build_gold_sql(
            record(
                "江苏省C市农商行在2025-11-30的存贷比排名？",
                "RANKING",
                ["ZB002", "ZB001"],
                ["2025-11-30"],
                ["ORG003"],
                derived_metrics=derived,
            )
        )
        org_result = execute_gold_sql(org_spec.sql, daily)
        self.assertEqual([row for row in org_result if row[0] == "DERIVED_ZB002_DIV_ZB001"], [])


if __name__ == "__main__":
    unittest.main()
