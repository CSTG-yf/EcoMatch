from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class SyntheticSchemaTest(unittest.TestCase):
    def test_synthetic_records_require_origin_and_metric_code(self) -> None:
        schema = json.loads((ROOT / "schema.json").read_text(encoding="utf-8"))
        required = {
            "dataDate",
            "orgCode",
            "metricCode",
            "metricValue",
            "dataOrigin",
            "generatorVersion",
        }
        self.assertTrue(required <= set(schema["required"]))
        self.assertEqual("SYNTHETIC", schema["properties"]["dataOrigin"]["const"])

    def test_metric_and_organization_ranges_are_isolated(self) -> None:
        schema = json.loads((ROOT / "schema.json").read_text(encoding="utf-8"))
        self.assertEqual("synthetic-360-v1", schema["properties"]["generatorVersion"]["const"])
        self.assertIn("CNB", schema["properties"]["metricCode"]["pattern"])
        self.assertIn("SYN-ORG", schema["properties"]["orgCode"]["pattern"])
