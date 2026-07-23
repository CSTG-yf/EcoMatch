#!/usr/bin/env python3
"""Contract tests for the real-model blind evaluation runner."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from run_model_blind_eval import build_prompt_records, extract_sql, generate_predictions  # noqa: E402


class RunModelBlindEvalTest(unittest.TestCase):
    def test_build_prompt_records_exposes_only_id_and_question(self) -> None:
        records = [
            {
                "id": "TEST-01",
                "question": "查询A行存款余额",
                "sql": "SELECT secret_gold_sql",
                "expected": {"rows": [[42.02]]},
                "normalizedIntent": {"metrics": ["gold-only"]},
            }
        ]

        self.assertEqual(build_prompt_records(records), [{"id": "TEST-01", "question": "查询A行存款余额"}])

    def test_extract_sql_unwraps_markdown_and_rejects_non_query_text(self) -> None:
        self.assertEqual(extract_sql("```sql\nSELECT 1 AS value;\n```"), "SELECT 1 AS value;")
        self.assertIsNone(extract_sql("我无法生成 SQL"))

    def test_generate_predictions_sends_question_without_hidden_gold(self) -> None:
        captured_messages = []

        def fake_completion(messages: list[dict[str, str]]) -> str:
            captured_messages.extend(messages)
            return "SELECT 42.02 AS value"

        predictions = generate_predictions(
            [{"id": "TEST-01", "question": "查询A行存款余额"}],
            schema_context="schema only",
            completion=fake_completion,
        )

        self.assertEqual(predictions, [{"id": "TEST-01", "sql": "SELECT 42.02 AS value"}])
        prompt_text = "\n".join(message["content"] for message in captured_messages)
        self.assertIn("查询A行存款余额", prompt_text)
        self.assertNotIn("secret_gold_sql", prompt_text)
        self.assertNotIn("42.02", prompt_text)


if __name__ == "__main__":
    unittest.main()
