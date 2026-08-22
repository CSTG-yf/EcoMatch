from __future__ import annotations

import json
import sqlite3
import unittest
from contextlib import closing
from pathlib import Path

from evaluation.bank_nl2sql.synthetic_360.validate_synthetic_facts import validate_release


ROOT = Path(__file__).resolve().parents[1]
RELEASE = ROOT / "releases" / "0.1.0-synthetic"


class SyntheticFactsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.report = validate_release(RELEASE)

    def test_generated_release_has_360_metrics_13_orgs_and_17_months(self) -> None:
        self.assertEqual({"metrics": 360, "organizations": 13, "dates": 17, "facts": 79560}, self.report["counts"])
        self.assertEqual("SYNTHETIC", self.report["dataOrigin"])

    def test_fact_cube_has_no_duplicate_keys_and_no_missing_base_cells(self) -> None:
        self.assertEqual([], self.report["duplicateKeys"])
        self.assertEqual([], self.report["missingBaseCells"])
        self.assertEqual(0, self.report["derivedFormulaErrors"])

    def test_derived_metric_is_calculated_from_operands(self) -> None:
        with closing(sqlite3.connect(RELEASE / "bank.sqlite")) as connection:
            values = dict(connection.execute("SELECT metric_code, metric_value FROM bank_metric_daily WHERE data_date = '2025-01-31' AND org_code = 'SYN-ORG-001'"))
        self.assertAlmostEqual(values["CNB016"] / values["CNB001"] * 100, values["CNB045"], places=6)

    def test_manifest_is_explicitly_non_official(self) -> None:
        manifest = json.loads((RELEASE / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual([], manifest["officialInputs"])
        self.assertEqual("SYNTHETIC_CANDIDATE", manifest["status"])
