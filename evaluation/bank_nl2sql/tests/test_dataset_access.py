#!/usr/bin/env python3
"""The training loader must never receive held-out test gold records."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from dataset_access import DatasetAccessError, load_records  # noqa: E402


class DatasetAccessTest(unittest.TestCase):
    def test_training_scope_excludes_test_and_augmentation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for split, sample_id in (("train", "TRAIN-01"), ("dev", "DEV-01"), ("test", "TEST-01"), ("augmentation", "AUG-01")):
                (root / f"{split}.jsonl").write_text(json.dumps({"id": sample_id}) + "\n", encoding="utf-8")
            records = load_records(root, purpose="training")
            self.assertEqual([record["id"] for record in records], ["TRAIN-01", "DEV-01"])
            with self.assertRaises(DatasetAccessError):
                load_records(root, purpose="evaluation")
            self.assertEqual(
                [record["id"] for record in load_records(root, purpose="evaluation", allow_test_gold=True)], ["TEST-01"]
            )


if __name__ == "__main__":
    unittest.main()
