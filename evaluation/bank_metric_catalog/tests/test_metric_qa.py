from __future__ import annotations

import json
import re
import tempfile
import unittest
from collections import Counter, defaultdict
from pathlib import Path

from evaluation.bank_metric_catalog.build_catalog import build_release
from evaluation.bank_metric_catalog.evaluate_metric_qa import (
    LEXICON_BASELINE_WARNING,
    MetricQaEvaluationError,
    build_lexicon_predictions,
    evaluate_predictions,
    select_prediction_subset,
)
from evaluation.bank_metric_catalog.generate_metric_qa import (
    QA_CASE_TYPES,
    build_qa_release,
    load_qa_release,
    validate_qa_release,
)
from evaluation.bank_metric_catalog.validate_catalog import load_release


class BankMetricQaTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.catalog_dir = Path(self.tmp.name) / "0.1.0-candidate"
        build_release(self.catalog_dir)
        build_qa_release(self.catalog_dir)
        self.metrics, _, _ = load_release(self.catalog_dir)
        self.records, self.manifest = load_qa_release(self.catalog_dir)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_generated_release_covers_every_metric_with_three_case_types(self) -> None:
        report = validate_qa_release(self.catalog_dir)
        self.assertEqual(1080, report["qaCaseCount"])
        self.assertEqual(360, report["metricCount"])
        self.assertEqual(
            {"CANONICAL_QUERY": 360, "ALIAS_QUERY": 360, "GOVERNANCE_QA": 360},
            report["caseTypeCounts"],
        )

        cases_by_metric: dict[str, list[dict[str, object]]] = defaultdict(list)
        for record in self.records:
            cases_by_metric[record["expected"]["metricCode"]].append(record)
        self.assertEqual({metric["code"] for metric in self.metrics}, set(cases_by_metric))
        self.assertTrue(
            all({case["caseType"] for case in cases} == set(QA_CASE_TYPES) for cases in cases_by_metric.values())
        )

    def test_splits_are_metric_isolated_and_balanced(self) -> None:
        splits_by_metric: dict[str, set[str]] = defaultdict(set)
        for record in self.records:
            splits_by_metric[record["expected"]["metricCode"]].add(record["split"])
        self.assertTrue(all(len(splits) == 1 for splits in splits_by_metric.values()))
        self.assertEqual(
            {"train": 648, "dev": 216, "test": 216},
            dict(Counter(record["split"] for record in self.records)),
        )

    def test_ids_are_opaque_and_do_not_encode_gold(self) -> None:
        ids = [record["id"] for record in self.records]
        self.assertEqual(len(ids), len(set(ids)))
        for record in self.records:
            with self.subTest(id=record["id"]):
                self.assertRegex(record["id"], r"^BMQ-[0-9a-f]{20}$")
                self.assertNotIn(record["expected"]["metricCode"], record["id"])
                self.assertNotIn(record["split"].upper(), record["id"])
                self.assertFalse(
                    any(token in record["id"] for token in ("CANONICAL", "ALIAS", "GOVERNANCE"))
                )

    def test_blind_input_contains_only_id_and_question(self) -> None:
        blind_path = self.catalog_dir / "metric_qa_blind.jsonl"
        blind = [
            json.loads(line)
            for line in blind_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        self.assertEqual(len(self.records), len(blind))
        self.assertEqual(
            [{"id": record["id"], "question": record["question"]} for record in self.records],
            blind,
        )

    def test_qa_records_do_not_claim_fact_values_or_official_eligibility(self) -> None:
        forbidden_keys = {"answerText", "facts", "goldSql", "rows", "s2sql", "sql", "value", "values"}

        def all_keys(value: object) -> set[str]:
            if isinstance(value, dict):
                return set(value) | set().union(*(all_keys(item) for item in value.values()))
            if isinstance(value, list):
                return set().union(*(all_keys(item) for item in value))
            return set()

        for record in self.records:
            with self.subTest(id=record["id"]):
                self.assertFalse(record["officialEligible"])
                self.assertFalse(record["factDataIncluded"])
                self.assertEqual("SYNTHETIC_OR_DESENSITIZED_ONLY", record["valuePolicy"])
                self.assertFalse(forbidden_keys & all_keys(record))

    def test_generation_is_deterministic(self) -> None:
        other_dir = Path(self.tmp.name) / "other" / "0.1.0-candidate"
        build_release(other_dir)
        build_qa_release(other_dir)
        self.assertEqual(
            (self.catalog_dir / "metric_qa.jsonl").read_bytes(),
            (other_dir / "metric_qa.jsonl").read_bytes(),
        )
        self.assertEqual(self.manifest, load_qa_release(other_dir)[1])

    def test_checked_in_qa_release_is_valid(self) -> None:
        release_dir = Path(__file__).resolve().parents[1] / "releases" / "0.1.0-candidate"
        report = validate_qa_release(release_dir)
        self.assertEqual(1080, report["qaCaseCount"])
        self.assertEqual(360, report["metricCount"])

    def test_lexicon_baseline_exercises_the_full_evaluator(self) -> None:
        predictions = build_lexicon_predictions(self.records, self.metrics)
        report = evaluate_predictions(self.records, predictions, self.metrics)
        self.assertEqual(1080, report["goldCount"])
        self.assertEqual(1.0, report["metrics"]["metricCodeAccuracy"])
        self.assertEqual(1.0, report["metrics"]["actionAccuracy"])
        self.assertEqual(1.0, report["metrics"]["metadataAccuracy"])
        self.assertEqual(1.0, report["metrics"]["definitionAccuracy"])
        self.assertEqual(1.0, report["metrics"]["caseAccuracy"])
        self.assertEqual(1.0, report["metrics"]["completeMetricCoverageRate"])
        self.assertEqual({}, report["errorCategories"])

    def test_prediction_subset_uses_smoke_denominator(self) -> None:
        subset = self.records[:5]
        predictions = build_lexicon_predictions(subset, self.metrics)
        for latency, prediction in zip((100, 200, 300, 400, 500), predictions, strict=True):
            prediction["latencyMs"] = latency
        scored_records = select_prediction_subset(self.records, predictions)
        report = evaluate_predictions(scored_records, predictions, self.metrics)
        self.assertEqual(5, report["goldCount"])
        self.assertEqual(5, report["predictionCount"])
        self.assertEqual(1.0, report["metrics"]["caseAccuracy"])
        self.assertEqual(
            {"count": 5, "mean": 300.0, "p50": 300, "p95": 500, "p99": 500},
            report["latencyMs"],
        )

    def test_checked_in_baseline_report_is_current(self) -> None:
        release_dir = Path(__file__).resolve().parents[1] / "releases" / "0.1.0-candidate"
        records, _ = load_qa_release(release_dir)
        metrics, _, _ = load_release(release_dir)
        expected = {
            "evaluationMode": "LEXICON_BASELINE",
            "warning": LEXICON_BASELINE_WARNING,
            **evaluate_predictions(records, build_lexicon_predictions(records, metrics), metrics),
        }
        actual = json.loads(
            (release_dir / "metric_qa_baseline_report.json").read_text(encoding="utf-8")
        )
        self.assertEqual(expected, actual)

    def test_metric_code_and_action_only_cannot_pass(self) -> None:
        predictions = [
            {
                "id": record["id"],
                "metricCode": record["expected"]["metricCode"],
                "action": record["expected"]["action"],
            }
            for record in self.records
        ]
        report = evaluate_predictions(self.records, predictions, self.metrics)
        self.assertEqual(0.0, report["metrics"]["caseAccuracy"])
        self.assertEqual(0.0, report["metrics"]["metadataAccuracy"])
        self.assertEqual(1080, report["errorCategories"]["INVALID_PREDICTION_CONTRACT"])

    def test_evaluator_scores_metadata_and_governance_definition(self) -> None:
        predictions = build_lexicon_predictions(self.records, self.metrics)
        predictions[0]["metricName"] = "错误指标名"
        predictions[1]["scene"] = "RISK" if predictions[1]["scene"] != "RISK" else "OPERATIONS"
        predictions[5]["domain"] = "wrong_domain"
        predictions[3]["unit"] = "错误单位"
        predictions[4]["aggregation"] = (
            "SUM" if predictions[4]["aggregation"] != "SUM" else "COUNT"
        )
        governance_index = next(
            index
            for index, record in enumerate(self.records)
            if record["caseType"] == "GOVERNANCE_QA"
        )
        predictions[governance_index]["definition"] = "错误定义"

        report = evaluate_predictions(self.records, predictions, self.metrics)
        self.assertLess(report["metrics"]["metadataAccuracy"], 1.0)
        self.assertLess(report["metrics"]["definitionAccuracy"], 1.0)
        self.assertEqual(1, report["errorCategories"]["METRIC_NAME_MISMATCH"])
        self.assertEqual(1, report["errorCategories"]["SCENE_MISMATCH"])
        self.assertEqual(1, report["errorCategories"]["DOMAIN_MISMATCH"])
        self.assertEqual(1, report["errorCategories"]["UNIT_MISMATCH"])
        self.assertEqual(1, report["errorCategories"]["AGGREGATION_MISMATCH"])
        self.assertEqual(1, report["errorCategories"]["DEFINITION_MISMATCH"])

    def test_evaluator_reports_missing_wrong_unknown_and_extra_predictions(self) -> None:
        predictions = build_lexicon_predictions(self.records, self.metrics)
        predictions.pop()
        predictions[0]["metricCode"] = self.metrics[1]["code"]
        predictions[1]["metricCode"] = "CNB999"
        extra = dict(predictions[2])
        extra["id"] = "NOT-IN-GOLD"
        predictions.append(extra)
        report = evaluate_predictions(self.records, predictions, self.metrics)
        self.assertEqual(1, report["errorCategories"]["MISSING_PREDICTION"])
        self.assertEqual(1, report["errorCategories"]["METRIC_MISMATCH"])
        self.assertEqual(1, report["errorCategories"]["UNKNOWN_METRIC_CODE"])
        self.assertEqual(["NOT-IN-GOLD"], report["unmatchedPredictionIds"])
        self.assertLess(report["metrics"]["caseAccuracy"], 1.0)

    def test_duplicate_prediction_and_tampered_release_are_rejected(self) -> None:
        predictions = build_lexicon_predictions(self.records, self.metrics)
        predictions.append(dict(predictions[0]))
        with self.assertRaisesRegex(MetricQaEvaluationError, "Duplicate prediction id"):
            evaluate_predictions(self.records, predictions, self.metrics)

        qa_path = self.catalog_dir / "metric_qa.jsonl"
        qa_path.write_text(qa_path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "sha256"):
            validate_qa_release(self.catalog_dir)

    def test_schema_matches_generated_record_contract(self) -> None:
        schema_path = Path(__file__).resolve().parents[1] / "qa_schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual(set(schema["required"]), set(self.records[0]))


if __name__ == "__main__":
    unittest.main()
