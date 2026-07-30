#!/usr/bin/env python3
"""Unit tests for the QA-02B audit and alert gate."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from run_qa02a import _validate_manifest, build_report  # noqa: E402


class Qa02bSecurityGateTest(unittest.TestCase):
    def test_repository_manifest_is_valid_and_covers_required_controls(self) -> None:
        manifest = json.loads(
            (ROOT / "qa02b_manifest.json").read_text(encoding="utf-8")
        )
        validated = _validate_manifest(manifest, "QA-02B")
        control_ids = {control["id"] for control in validated["controls"]}
        self.assertEqual(len(validated["tests"]), 10)
        self.assertIn("hash-chain-integrity", control_ids)
        self.assertIn("anomaly-rule-triggering", control_ids)
        self.assertIn("alert-deduplication-and-evidence", control_ids)
        self.assertIn("organization-isolation", control_ids)
        self.assertIn("alert-disposition", control_ids)

    def test_shared_report_builder_emits_qa02b_result(self) -> None:
        manifest = {
            "schemaVersion": "1.0",
            "task": "QA-02B",
            "tests": [
                {
                    "id": "audit",
                    "module": "headless/server",
                    "className": "example.AuditTest",
                }
            ],
            "controls": [
                {
                    "id": "integrity",
                    "description": "integrity",
                    "tests": ["audit"],
                }
            ],
        }
        report = build_report(
            manifest,
            {
                "example.AuditTest": [
                    {"name": "detectsTamper", "status": "PASS", "durationMs": 1}
                ]
            },
            command_exit_code=0,
            command_duration_ms=2,
            task="QA-02B",
        )
        self.assertEqual(report["task"], "QA-02B")
        self.assertEqual(report["status"], "PASS")


if __name__ == "__main__":
    unittest.main()
