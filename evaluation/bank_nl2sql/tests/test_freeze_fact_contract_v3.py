#!/usr/bin/env python3
"""Release-gate tests for fact-contract v3 datasets."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from freeze_dataset import freeze_dataset  # noqa: E402


def _write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")


class FreezeFactContractV3Test(unittest.TestCase):
    def test_answer_fact_release_keeps_fact_contract_and_records_gold_integrity(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            record = {
                "id": "SYN-T001",
                "question": "余额是多少？",
                "expected": {
                    "answerText": "余额42.02亿元",
                    "columns": ["metric_value"],
                    "rows": [[42.02]],
                    "numericTolerance": 1e-6,
                    "orderSensitive": True,
                    "unit": None,
                },
            }
            _write_json(root / "train.jsonl", record)
            (root / "dev.jsonl").write_text("", encoding="utf-8")
            (root / "test.jsonl").write_text("opaque-held-out\n", encoding="utf-8")
            (root / "augmentation.jsonl").write_text("", encoding="utf-8")
            _write_json(root / "schema.json", {"type": "object"})
            _write_json(
                root / "manifest.json",
                {
                    "version": "2.0.1",
                    "sourceSha256": "A" * 64,
                    "answerFactContract": {"count": 1},
                },
            )
            _write_json(root / "gold_manifest.json", {"version": "2.0.1"})
            dataset_report = {
                "officialCount": 1,
                "augmentationCount": 0,
                "sourceSplitCounts": {"train": 1, "dev": 0, "test": 0},
                "evaluationSplitCounts": {"train": 1, "dev": 0, "test": 0},
            }

            gold_report = {
                "result": "PASS",
                "officialCount": 1,
                "sqlExecutionCount": 1,
                "resultMatchCount": 1,
            }
            with patch("freeze_dataset.validate_dataset", return_value=dataset_report), patch(
                "freeze_dataset.validate_gold_dataset", return_value=gold_report
            ) as validate_gold:
                release = freeze_dataset(root, root / "unused.sqlite")

            validate_gold.assert_called_once_with(root.resolve(), (root / "unused.sqlite").resolve())
            self.assertEqual(release["answerContractValidation"]["readyCount"], 1)
            self.assertEqual(release["answerContractValidation"]["reviewRequiredCount"], 0)
            self.assertEqual(release["answerContractValidation"]["excludedCount"], 0)
            self.assertEqual(release["goldValidation"], gold_report)


if __name__ == "__main__":
    unittest.main()
