from __future__ import annotations

import json
import unittest
from pathlib import Path

from evaluation.bank_nl2sql.public_disclosure.run_public_fact_tests import run_tests
from evaluation.bank_nl2sql.public_disclosure.validate_public_facts import validate_release


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT.parent.parent / "bank_metric_catalog" / "releases" / "0.1.0-candidate"


class PublicDisclosureFactsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.validation = validate_release(ROOT, catalog_dir=CATALOG)
        cls.query_report = run_tests(ROOT, catalog_dir=CATALOG)

    def test_release_contains_cited_public_facts_and_pending_review_boundary(self) -> None:
        self.assertEqual("VALID", self.validation["status"])
        self.assertEqual("PUBLIC_DISCLOSURE", self.validation["dataOrigin"])
        self.assertEqual({"facts": 18, "metrics": 18, "organizations": 1, "dates": 1}, self.validation["counts"])
        self.assertEqual(18, self.validation["sourceLinkedFacts"])
        self.assertEqual(2, self.validation["pendingBusinessReviewFacts"])

    def test_amount_unit_conversion_is_explicit_and_exact(self) -> None:
        facts = [json.loads(line) for line in (ROOT / "facts.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        total_assets = next(fact for fact in facts if fact["metricCode"] == "CNB043")
        self.assertEqual(40571149, total_assets["sourceValue"])
        self.assertEqual("人民币百万元", total_assets["sourceUnit"])
        self.assertEqual("sourceValue * 100", total_assets["conversion"])
        self.assertEqual(4057114900, total_assets["metricValue"])

    def test_public_sql_queries_execute_and_match_facts(self) -> None:
        self.assertEqual(3, self.query_report["queryCount"])
        self.assertEqual(3, self.query_report["parseSuccessCount"])
        self.assertEqual(3, self.query_report["executionSuccessCount"])
        self.assertEqual(3, self.query_report["resultCorrectCount"])
        self.assertEqual("PUBLIC_DISCLOSURE", self.query_report["dataOrigin"])

    def test_manifest_keeps_official_evaluation_untouched(self) -> None:
        manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
        self.assertFalse(manifest["official21MetricEvaluationModified"])
        self.assertEqual([], manifest["officialInputs"])
        self.assertEqual("PUBLIC_DISCLOSURE_CANDIDATE", manifest["status"])


if __name__ == "__main__":
    unittest.main()
