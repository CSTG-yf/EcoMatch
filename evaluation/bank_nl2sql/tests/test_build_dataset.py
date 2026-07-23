#!/usr/bin/env python3
"""Contract tests for the DATA-02 JSONL dataset builder."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

from openpyxl import Workbook


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from build_dataset import build_dataset  # noqa: E402
from validate_dataset import validate_dataset  # noqa: E402


class BuildDatasetTest(unittest.TestCase):
    def create_question_workbook(self, path: Path) -> None:
        workbook = Workbook()
        sheet = workbook.active
        sheet.title = "问题答案清单"
        sheet.append(["问题编号", "问题类型", "问题难度", "问题描述", "问题结果"])
        sheet.append(["TRAIN-S-01", "训练集", "简单", "A行存款余额是多少？", "41.76亿元"])
        sheet.append(["VAL-S-01", "验证集", "普通", "B行不良贷款率是多少？", "1.10%"])
        sheet.append(["TEST-S-01", "测试集", "复杂", "哪家存款余额最高？", "江苏省A市农商行"])
        workbook.save(path)

    @staticmethod
    def intent_record(sample_id: str, source_split: str | None, split: str, source: str = "competition_workbook") -> dict:
        return {
            "id": sample_id,
            "source": source,
            "sourceSplit": source_split,
            "split": split,
            "difficulty": "简单",
            "question": "placeholder",
            "answer": "placeholder",
            "scene": "OPERATION_ANALYSIS",
            "intent": "POINT_QUERY",
            "metrics": [{"code": "ZB001", "name": "各项存款余额", "matchedText": "存款余额"}],
            "dimensions": ["bank_data_date", "bank_organization"],
            "time": {"expressions": ["2024年12月31日"]},
            "organizations": [],
            "filters": [],
            "linguisticFeatures": ["STANDARD"],
            "clarificationExpected": False,
            "templateGroup": f"template-{sample_id}",
            "referenceDate": "2026-07-23",
        }

    def write_intents(self, root: Path) -> None:
        root.mkdir()
        entries = {
            "train": [self.intent_record("TRAIN-S-01", "train", "train")],
            "dev": [self.intent_record("VAL-S-01", "dev", "test")],
            "test": [
                self.intent_record("TEST-S-01", "test", "test"),
                self.intent_record("AUG-01", None, "test", "curated_augmentation"),
            ],
        }
        for split, records in entries.items():
            (root / f"{split}.jsonl").write_text(
                "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
                encoding="utf-8",
            )

    def test_freezes_source_split_and_isolates_augmentations(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path = temp_path / "questions.xlsx"
            intent_root = temp_path / "bank_intent"
            output_path = temp_path / "bank_nl2sql"
            self.create_question_workbook(workbook_path)
            self.write_intents(intent_root)

            report = build_dataset(workbook_path, intent_root, output_path)
            validation = validate_dataset(output_path)

            self.assertEqual(report["officialCount"], 3)
            self.assertEqual(report["augmentationCount"], 1)
            self.assertEqual(report["sourceSplitCounts"], {"train": 1, "dev": 1, "test": 1})
            self.assertEqual(validation["result"], "PASS")

            test_records = [
                json.loads(line)
                for line in (output_path / "test.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            moved = next(record for record in test_records if record["id"] == "VAL-S-01")
            self.assertEqual(moved["sourceSplit"], "dev")
            self.assertEqual(moved["split"], "test")
            self.assertEqual(moved["splitReason"], "template_isolation")
            self.assertEqual(moved["expectedAction"], "EXECUTE")
            self.assertIsNone(moved["s2sql"])
            self.assertEqual(moved["expected"]["answerText"], "1.10%")
            self.assertEqual(len((output_path / "augmentation.jsonl").read_text(encoding="utf-8").splitlines()), 1)


if __name__ == "__main__":
    unittest.main()
