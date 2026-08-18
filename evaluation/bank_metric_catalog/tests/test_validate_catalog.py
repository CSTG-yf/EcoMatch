from __future__ import annotations

import copy
import csv
import json
import tempfile
import unittest
from pathlib import Path

from evaluation.bank_metric_catalog.build_catalog import build_release
from evaluation.bank_metric_catalog.validate_catalog import (
    CatalogValidationError,
    load_release,
    validate_records,
    validate_release,
)


class BankMetricCatalogValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.release_dir = Path(self.tmp.name) / "0.1.0-candidate"
        build_release(self.release_dir)
        self.metrics, self.sources, self.manifest = load_release(self.release_dir)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_generated_candidate_release_is_valid(self) -> None:
        report = validate_release(self.release_dir)
        self.assertEqual(360, report["metricCount"])
        self.assertEqual(
            {"OPERATIONS": 150, "RISK": 120, "CUSTOMER_MARKETING": 90},
            report["sceneCounts"],
        )
        self.assertGreaterEqual(report["derivedMetricCount"], 10)
        self.assertEqual(21, report["legacyMetricCount"])
        self.assertTrue(all(m["reviewStatus"] == "CANDIDATE" for m in self.metrics))
        self.assertTrue(
            all(m["valuePolicy"] == "SYNTHETIC_OR_DESENSITIZED_ONLY" for m in self.metrics)
        )
        self.assertTrue(all(len(m["aliases"]) >= 2 for m in self.metrics))
        self.assertEqual(
            {f"ZB{index:03d}" for index in range(1, 22)},
            {code for metric in self.metrics for code in metric["legacyCodes"]},
        )

    def test_quota_drift_is_rejected(self) -> None:
        metrics = copy.deepcopy(self.metrics)
        metrics[0]["scene"] = "RISK"
        with self.assertRaisesRegex(CatalogValidationError, "scene quota"):
            validate_records(metrics, self.sources)

    def test_duplicate_semantic_key_and_alias_are_rejected(self) -> None:
        metrics = copy.deepcopy(self.metrics)
        metrics[1]["semanticKey"] = metrics[0]["semanticKey"]
        with self.assertRaisesRegex(CatalogValidationError, "semanticKey"):
            validate_records(metrics, self.sources)

        metrics = copy.deepcopy(self.metrics)
        metrics[1]["aliases"][0] = metrics[0]["aliases"][0]
        with self.assertRaisesRegex(CatalogValidationError, "alias"):
            validate_records(metrics, self.sources)

        metrics = copy.deepcopy(self.metrics)
        metrics[0]["aliases"] = [metrics[0]["name"] + "口径"]
        with self.assertRaisesRegex(CatalogValidationError, "at least two aliases"):
            validate_records(metrics, self.sources)

    def test_invalid_percentage_aggregation_is_rejected(self) -> None:
        metrics = copy.deepcopy(self.metrics)
        target = next(metric for metric in metrics if metric["name"] == "净息差")
        target["aggregation"] = "SUM"
        with self.assertRaisesRegex(CatalogValidationError, "percentage metric cannot use SUM"):
            validate_records(metrics, self.sources)

    def test_known_amount_formats_are_preserved(self) -> None:
        by_name = {metric["name"]: metric for metric in self.metrics}
        expected = {
            "新生成不良贷款额": ("万元", "SUM"),
            "线上贷款放款额": ("万元", "SUM"),
            "未来30日现金净流出量": ("万元", "SNAPSHOT"),
            "数字渠道交易金额": ("万元", "SUM"),
            "杠杆率暴露总额": ("万元", "SNAPSHOT"),
            "利率敏感性缺口": ("万元", "SNAPSHOT"),
            "累计利率敏感性缺口": ("万元", "SNAPSHOT"),
            "交易账簿利率风险资本": ("万元", "SNAPSHOT"),
            "汇率风险资本": ("万元", "SNAPSHOT"),
        }
        for name, (unit, aggregation) in expected.items():
            with self.subTest(name=name):
                self.assertEqual(unit, by_name[name]["unit"])
                self.assertEqual(aggregation, by_name[name]["aggregation"])

    def test_invalid_amount_formats_are_rejected(self) -> None:
        metrics = copy.deepcopy(self.metrics)
        target = next(metric for metric in metrics if metric["unit"] == "万元")
        target["aggregation"] = "COUNT"
        with self.assertRaisesRegex(CatalogValidationError, "currency metric cannot use COUNT"):
            validate_records(metrics, self.sources)

        metrics = copy.deepcopy(self.metrics)
        target = next(metric for metric in metrics if metric["unit"] == "万元")
        target["aggregation"] = "RATIO"
        with self.assertRaisesRegex(CatalogValidationError, "RATIO metric must use percent unit"):
            validate_records(metrics, self.sources)

        metrics = copy.deepcopy(self.metrics)
        target = next(metric for metric in metrics if metric["name"] == "新生成不良贷款额")
        target["unit"] = "个"
        with self.assertRaisesRegex(CatalogValidationError, "amount metric cannot use count unit"):
            validate_records(metrics, self.sources)

    def test_legacy_code_coverage_and_target_are_fail_closed(self) -> None:
        metrics = copy.deepcopy(self.metrics)
        target = next(metric for metric in metrics if "ZB001" in metric["legacyCodes"])
        target["legacyCodes"] = []
        with self.assertRaisesRegex(CatalogValidationError, "legacy metric codes"):
            validate_records(metrics, self.sources)

        metrics = copy.deepcopy(self.metrics)
        deposit = next(metric for metric in metrics if "ZB001" in metric["legacyCodes"])
        loan = next(metric for metric in metrics if "ZB002" in metric["legacyCodes"])
        loan["legacyCodes"] = ["ZB001"]
        with self.assertRaisesRegex(CatalogValidationError, "duplicate legacy code"):
            validate_records(metrics, self.sources)

        metrics = copy.deepcopy(self.metrics)
        deposit = next(metric for metric in metrics if "ZB001" in metric["legacyCodes"])
        loan = next(metric for metric in metrics if "ZB002" in metric["legacyCodes"])
        deposit["legacyCodes"], loan["legacyCodes"] = loan["legacyCodes"], deposit["legacyCodes"]
        with self.assertRaisesRegex(CatalogValidationError, "legacy target mismatch"):
            validate_records(metrics, self.sources)

    def test_unknown_source_is_rejected(self) -> None:
        metrics = copy.deepcopy(self.metrics)
        metrics[0]["sourceRefs"][0]["sourceId"] = "UNKNOWN"
        with self.assertRaisesRegex(CatalogValidationError, "unknown source"):
            validate_records(metrics, self.sources)

    def test_broken_and_cyclic_derived_formula_are_rejected(self) -> None:
        derived = [i for i, metric in enumerate(self.metrics) if metric["metricType"] == "DERIVED"]
        self.assertGreaterEqual(len(derived), 2)

        metrics = copy.deepcopy(self.metrics)
        metrics[derived[0]]["formula"]["operands"][0] = "CNB999"
        with self.assertRaisesRegex(CatalogValidationError, "unknown operand"):
            validate_records(metrics, self.sources)

        metrics = copy.deepcopy(self.metrics)
        left, right = derived[:2]
        metrics[left]["formula"]["operands"] = [metrics[right]["code"]]
        metrics[right]["formula"]["operands"] = [metrics[left]["code"]]
        with self.assertRaisesRegex(CatalogValidationError, "cycle"):
            validate_records(metrics, self.sources)

    def test_manifest_hash_tampering_is_rejected(self) -> None:
        metrics_path = self.release_dir / "metrics.jsonl"
        metrics_path.write_text(metrics_path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        with self.assertRaisesRegex(CatalogValidationError, "sha256"):
            validate_release(self.release_dir)

    def test_raw_fact_payload_is_rejected(self) -> None:
        metrics = copy.deepcopy(self.metrics)
        metrics[0]["value"] = 123
        with self.assertRaisesRegex(CatalogValidationError, "raw fact"):
            validate_records(metrics, self.sources)

    def test_manifest_is_machine_readable(self) -> None:
        manifest = json.loads((self.release_dir / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("0.1.0-candidate", manifest["version"])
        self.assertEqual("1.1.0", manifest["schemaVersion"])
        self.assertEqual(360, manifest["metricCount"])
        self.assertEqual(21, manifest["legacyMetricCount"])

    def test_schema_required_fields_match_generated_records(self) -> None:
        schema_path = Path(__file__).resolve().parents[1] / "schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual(set(schema["required"]), set(self.metrics[0]))

    def test_reviewed_unit_and_direction_examples_are_preserved(self) -> None:
        by_name = {metric["name"]: metric for metric in self.metrics}
        self.assertEqual("%", by_name["存贷比"]["unit"])
        self.assertEqual("%", by_name["净息差"]["unit"])
        self.assertEqual("万元", by_name["经济增加值"]["unit"])
        self.assertEqual("万元", by_name["外汇敞口头寸"]["unit"])
        self.assertEqual("HIGHER_IS_BETTER", by_name["人工成本利润率"]["direction"])
        self.assertEqual("HIGHER_IS_BETTER", by_name["投诉办结率"]["direction"])
        self.assertEqual("LOWER_IS_BETTER", by_name["外汇风险限额使用率"]["direction"])
        self.assertEqual(
            ["organization", "date", "employee_type"],
            by_name["员工人数"]["dimensions"],
        )

    def test_review_csv_has_one_blank_review_row_per_metric(self) -> None:
        with (self.release_dir / "review.csv").open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual([metric["code"] for metric in self.metrics], [row["code"] for row in rows])
        self.assertEqual(
            ["|".join(metric["legacyCodes"]) for metric in self.metrics],
            [row["legacyCodes"] for row in rows],
        )
        self.assertTrue(all(not row["reviewDecision"] and not row["reviewComment"] for row in rows))


if __name__ == "__main__":
    unittest.main()
