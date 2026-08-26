from __future__ import annotations

import json
import unittest
from pathlib import Path

from evaluation.bank_nl2sql.public_disclosure.qwen_blind_eval import (
    build_blind_prompts,
    build_sqlite_connection,
    evaluate_prediction,
    rescore_saved_details,
    schema_context,
)


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT.parent.parent / "bank_metric_catalog" / "releases" / "0.1.0-candidate"


def load_queries() -> list[dict[str, object]]:
    return [
        json.loads(line)
        for line in (ROOT / "queries.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


class QwenBlindEvalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.queries = load_queries()

    def test_blind_prompts_exclude_gold_sql_and_expected_rows(self) -> None:
        prompts = build_blind_prompts(self.queries)

        self.assertEqual(
            [{"id": query["id"], "question": query["question"]} for query in self.queries],
            prompts,
        )
        self.assertTrue(all(set(prompt) == {"id", "question"} for prompt in prompts))

    def test_schema_context_contains_metadata_but_not_public_fact_values(self) -> None:
        connection = build_sqlite_connection(ROOT, catalog_dir=CATALOG)
        try:
            context = schema_context(connection)
        finally:
            connection.close()

        self.assertIn("public_metric_fact", context)
        self.assertIn("营业收入", context)
        self.assertIn("中国建设银行集团（公开披露）", context)
        self.assertNotIn("4057114900", context)
        self.assertNotIn("75015100", context)

    def test_read_only_sql_is_executed_and_compared_with_local_gold(self) -> None:
        query = self.queries[0]
        connection = build_sqlite_connection(ROOT, catalog_dir=CATALOG)
        try:
            result = evaluate_prediction(connection, query, query["sql"])
        finally:
            connection.close()

        self.assertTrue(result["parseSuccess"])
        self.assertTrue(result["executionSuccess"])
        self.assertTrue(result["resultCorrect"])
        self.assertTrue(result["sourceTraceable"])

    def test_wide_projection_with_the_same_metric_facts_is_result_correct(self) -> None:
        query = self.queries[0]
        generated_sql = """
            SELECT a.metric_value AS total_assets, b.metric_value AS operating_revenue,
                   c.metric_value AS net_interest_income, d.metric_value AS net_fee_income,
                   e.metric_value AS operating_profit, f.metric_value AS total_profit,
                   g.metric_value AS net_profit, h.metric_value AS attributable_net_profit
            FROM public_metric_fact a
            JOIN public_metric_fact b ON a.data_date = b.data_date AND a.org_code = b.org_code
            JOIN public_metric_fact c ON a.data_date = c.data_date AND a.org_code = c.org_code
            JOIN public_metric_fact d ON a.data_date = d.data_date AND a.org_code = d.org_code
            JOIN public_metric_fact e ON a.data_date = e.data_date AND a.org_code = e.org_code
            JOIN public_metric_fact f ON a.data_date = f.data_date AND a.org_code = f.org_code
            JOIN public_metric_fact g ON a.data_date = g.data_date AND a.org_code = g.org_code
            JOIN public_metric_fact h ON a.data_date = h.data_date AND a.org_code = h.org_code
            WHERE a.data_date = '2024-12-31' AND a.org_code = 'PUB-CCB-GROUP'
              AND a.metric_code = 'CNB043' AND b.metric_code = 'CNB046'
              AND c.metric_code = 'CNB049' AND d.metric_code = 'CNB052'
              AND e.metric_code = 'CNB063' AND f.metric_code = 'CNB064'
              AND g.metric_code = 'CNB065' AND h.metric_code = 'CNB066'
        """
        connection = build_sqlite_connection(ROOT, catalog_dir=CATALOG)
        try:
            result = evaluate_prediction(connection, query, generated_sql)
        finally:
            connection.close()

        self.assertTrue(result["executionSuccess"])
        self.assertFalse(result["structuralResultCorrect"])
        self.assertTrue(result["resultCorrect"])
        self.assertTrue(result["sourceTraceable"])

    def test_saved_model_sql_can_be_rescored_without_another_model_request(self) -> None:
        query = self.queries[1]
        saved_details = [{"id": query["id"], "generatedSql": query["sql"], "latencyMs": 1234}]
        connection = build_sqlite_connection(ROOT, catalog_dir=CATALOG)
        try:
            details = rescore_saved_details(connection, {query["id"]: query}, saved_details)
        finally:
            connection.close()

        self.assertEqual(1, len(details))
        self.assertEqual(1234, details[0]["latencyMs"])
        self.assertTrue(details[0]["resultCorrect"])
        self.assertTrue(details[0]["sourceTraceable"])

    def test_mutating_sql_is_rejected_before_execution(self) -> None:
        query = self.queries[0]
        connection = build_sqlite_connection(ROOT, catalog_dir=CATALOG)
        try:
            result = evaluate_prediction(connection, query, "DELETE FROM public_metric_fact")
        finally:
            connection.close()

        self.assertFalse(result["parseSuccess"])
        self.assertFalse(result["executionSuccess"])
        self.assertFalse(result["resultCorrect"])


if __name__ == "__main__":
    unittest.main()
