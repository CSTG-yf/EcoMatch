#!/usr/bin/env python3
"""Regression tests for immutable answer-fact official releases."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from amend_official_answer_facts import (  # noqa: E402
    OfficialAmendmentError,
    amend_official_answer_facts,
)
from amend_official_ground_truth import sha256_file  # noqa: E402
from build_dataset import _load_official_manifest  # noqa: E402
from test_amend_official_ground_truth import _make_parent, _write_json  # noqa: E402


def _fact_spec(path: Path, *, sample_id: str = "SYN-T001") -> Path:
    _write_json(
        path,
        {
            "schemaVersion": "1.0",
            "parentVersion": "1.0.0",
            "targetVersion": "1.0.1",
            "contracts": [
                {
                    "id": sample_id,
                    "reason": "答案中的派生数值需要可执行事实公式",
                    "answerFacts": [
                        {
                            "id": "total",
                            "value": 42.0,
                            "kind": "NUMBER",
                            "binding": {
                                "organizationCodes": ["ORG001"],
                                "metricCodes": ["ZB001"],
                                "dates": ["2025-12-31"],
                                "comparisonType": "POINT",
                            },
                            "formula": {
                                "operation": "DIRECT",
                                "operands": [{"column": "metric_value"}],
                            },
                        }
                    ],
                }
            ],
        },
    )
    return path


def _full_fact_spec(path: Path) -> Path:
    payload = json.loads(_fact_spec(path).read_text(encoding="utf-8"))
    payload["coverageMode"] = "FULL_OFFICIAL"
    template = payload["contracts"][0]
    payload["contracts"] = []
    for sample_id, value in (("SYN-T001", 42.0), ("SYN-V001", 7.0), ("SYN-S001", 9.0)):
        contract = json.loads(json.dumps(template))
        contract["id"] = sample_id
        contract["answerFacts"][0]["value"] = value
        payload["contracts"].append(contract)
    _write_json(path, payload)
    return path


class AmendOfficialAnswerFactsTest(unittest.TestCase):
    def test_builds_child_without_changing_workbook_or_source_ledger(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            parent = _make_parent(root)
            output = root / "1.0.1"

            manifest = amend_official_answer_facts(
                parent, _fact_spec(root / "facts.json"), output, update_current=True
            )

            self.assertEqual(manifest["releaseMode"], "INCREMENTAL_ANSWER_FACT_CONTRACT")
            self.assertEqual(manifest["answerFactCount"], 1)
            self.assertEqual(
                sha256_file(parent / "bank-nl2sql-ground-truth-v1.0.0.xlsx"),
                sha256_file(output / manifest["groundTruthWorkbook"]),
            )
            self.assertEqual(
                sha256_file(parent / "contract-change-ledger.json"),
                sha256_file(output / manifest["changeLedger"]),
            )
            loaded = _load_official_manifest(output / "official-manifest.json")
            self.assertEqual(loaded["answerFactCount"], 1)
            current = json.loads((root / "CURRENT.json").read_text(encoding="utf-8"))
            self.assertEqual(current["currentVersion"], "1.0.1")
            for name in (
                manifest["groundTruthWorkbook"],
                manifest["changeLedger"],
                manifest["answerFactLedger"],
                manifest["finalAuditSummary"],
                "official-manifest.json",
            ):
                target = output / name
                self.assertEqual(
                    target.with_name(target.name + ".sha256").read_text(encoding="ascii").split()[0],
                    sha256_file(target),
                )

    def test_rejects_test_contract_without_creating_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            parent = _make_parent(root)
            output = root / "1.0.1"

            with self.assertRaisesRegex(OfficialAmendmentError, "禁止修改 test"):
                amend_official_answer_facts(
                    parent,
                    _fact_spec(root / "facts.json", sample_id="SYN-S001"),
                    output,
                )

            self.assertFalse(output.exists())

    def test_full_official_contract_must_cover_and_may_type_test(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            parent = _make_parent(root)
            output = root / "1.0.1"

            manifest = amend_official_answer_facts(
                parent, _full_fact_spec(root / "facts.json"), output
            )

            self.assertEqual(manifest["releaseMode"], "FULL_OFFICIAL_ANSWER_FACT_CONTRACT")
            self.assertEqual(manifest["answerFactCoverageMode"], "FULL_OFFICIAL")
            self.assertEqual(manifest["answerFactCount"], 3)

    def test_full_official_contract_rejects_partial_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            parent = _make_parent(root)
            spec = json.loads(_fact_spec(root / "facts.json").read_text(encoding="utf-8"))
            spec["coverageMode"] = "FULL_OFFICIAL"
            _write_json(root / "facts.json", spec)

            with self.assertRaisesRegex(OfficialAmendmentError, "必须完整覆盖"):
                amend_official_answer_facts(parent, root / "facts.json", root / "1.0.1")

    def test_full_official_contract_rejects_fact_not_in_workbook_answer(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            parent = _make_parent(root)
            spec_path = _full_fact_spec(root / "facts.json")
            spec = json.loads(spec_path.read_text(encoding="utf-8"))
            spec["contracts"][0]["answerFacts"][0]["value"] = 999.0
            _write_json(spec_path, spec)

            with self.assertRaisesRegex(OfficialAmendmentError, "未与父工作簿答案对齐"):
                amend_official_answer_facts(parent, spec_path, root / "1.0.1")


if __name__ == "__main__":
    unittest.main()
