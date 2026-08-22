from __future__ import annotations

import json
import unittest
from pathlib import Path

from evaluation.bank_nl2sql.synthetic_360.validate_dataset import validate_dataset


ROOT = Path(__file__).resolve().parents[1]
RELEASE = ROOT / "releases" / "0.1.0-synthetic"


class SyntheticDatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.report = validate_dataset(RELEASE)

    def test_every_metric_has_one_executable_point_query(self) -> None:
        self.assertEqual(360, self.report["questionCount"])
        self.assertEqual(360, self.report["metricCoverage"])
        self.assertEqual(360, self.report["goldSqlExecutable"])
        self.assertEqual({"train": 216, "dev": 72, "test": 72}, self.report["splitCounts"])

    def test_blind_file_contains_no_gold_fields(self) -> None:
        blind = [json.loads(line) for line in (RELEASE / "questions_blind.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        self.assertTrue(blind)
        self.assertTrue(all(set(record) == {"id", "question"} for record in blind))

    def test_dataset_is_not_official(self) -> None:
        manifest = json.loads((RELEASE / "dataset-manifest.json").read_text(encoding="utf-8"))
        self.assertFalse(manifest["officialEligible"])
        self.assertEqual([], manifest["officialInputs"])
