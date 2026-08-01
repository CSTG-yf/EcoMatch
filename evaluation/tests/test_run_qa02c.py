#!/usr/bin/env python3
"""Unit tests for the QA-02C repository-wide security gate."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from run_qa02c import (  # noqa: E402
    FullChainGateError,
    build_report,
    java_results_from_report,
    validate_manifest,
)


class Qa02cSecurityGateTest(unittest.TestCase):
    def test_repository_manifest_covers_required_full_chain_controls(self) -> None:
        manifest = json.loads((ROOT / "qa02c_manifest.json").read_text(encoding="utf-8"))
        validated = validate_manifest(manifest)
        control_ids = {control["id"] for control in validated["controls"]}
        self.assertEqual(len(validated["evidence"]), 14)
        self.assertEqual(len(control_ids), 7)
        self.assertIn("controlled-export", control_ids)
        self.assertIn("controlled-sharing", control_ids)
        self.assertIn("history-and-model-input-boundary", control_ids)
        self.assertIn("sensitive-data-egress", control_ids)
        self.assertIn("security-client-release-artifact", control_ids)
        frontend = next(item for item in validated["evidence"] if item["id"] == "frontend-security-regression")
        self.assertEqual(frontend["command"], ["pnpm", "test:contest"])

    def test_manifest_rejects_unreferenced_evidence(self) -> None:
        manifest = self._manifest()
        manifest["evidence"].append({"id": "orphan", "type": "command", "command": ["true"]})
        with self.assertRaisesRegex(FullChainGateError, "not assigned"):
            validate_manifest(manifest)

    def test_manifest_rejects_unknown_control_reference(self) -> None:
        manifest = self._manifest()
        manifest["controls"][0]["evidence"] = ["missing"]
        with self.assertRaisesRegex(FullChainGateError, "unknown evidence"):
            validate_manifest(manifest)

    def test_report_passes_only_when_all_evidence_passes(self) -> None:
        report = build_report(
            self._manifest(),
            [
                {"id": "backend", "type": "java", "status": "PASS", "caseCount": 3},
                {"id": "frontend", "type": "command", "status": "PASS"},
            ],
        )
        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["summary"]["javaCaseCount"], 3)
        self.assertTrue(report["environmentGateRequired"])

    def test_report_fails_closed_for_missing_evidence(self) -> None:
        report = build_report(
            self._manifest(),
            [{"id": "backend", "type": "java", "status": "PASS", "caseCount": 1}],
        )
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["failures"][0]["category"], "EVIDENCE_MISSING")

    def test_report_fails_closed_for_failed_evidence(self) -> None:
        report = build_report(
            self._manifest(),
            [
                {"id": "backend", "type": "java", "status": "FAIL", "message": "denied"},
                {"id": "frontend", "type": "command", "status": "PASS"},
            ],
        )
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["controls"][0]["status"], "FAIL")

    def test_global_maven_failure_overrides_green_test_cases(self) -> None:
        results = java_results_from_report(
            {
                "status": "FAIL",
                "tests": [{"id": "backend", "status": "PASS", "caseCount": 3}],
                "failures": [{"category": "MAVEN_FAILURE", "subject": "maven", "message": "compile failed"}],
            },
            "compile failed",
        )
        self.assertEqual(results[0]["status"], "FAIL")
        self.assertEqual(results[0]["message"], "compile failed")

    @staticmethod
    def _manifest() -> dict:
        return {
            "schemaVersion": "1.0",
            "task": "QA-02C",
            "evidence": [
                {"id": "backend", "type": "java", "module": "common", "className": "example.SecurityTest"},
                {"id": "frontend", "type": "command", "command": ["pnpm", "test"]},
            ],
            "controls": [
                {"id": "full-chain", "description": "full chain", "evidence": ["backend", "frontend"]}
            ],
        }


if __name__ == "__main__":
    unittest.main()
