#!/usr/bin/env python3
"""Unit tests for the QA-02A backend security gate."""

from __future__ import annotations

import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from run_qa02a import (  # noqa: E402
    SecurityGateError,
    _sanitize,
    _validate_manifest,
    build_report,
)


def _manifest() -> dict:
    return {
        "schemaVersion": "1.0",
        "task": "QA-02A",
        "tests": [
            {
                "id": "access",
                "module": "module-a",
                "className": "example.AccessTest",
            },
            {
                "id": "mask",
                "module": "module-b",
                "className": "example.MaskTest",
            },
        ],
        "controls": [
            {
                "id": "security",
                "description": "test control",
                "tests": ["access", "mask"],
            }
        ],
    }


def _cases() -> dict:
    return {
        "example.AccessTest": [
            {"name": "denies", "status": "PASS", "durationMs": 1}
        ],
        "example.MaskTest": [
            {"name": "masks", "status": "PASS", "durationMs": 2}
        ],
    }


class Qa02aSecurityGateTest(unittest.TestCase):
    def test_repository_manifest_is_valid(self) -> None:
        value = json.loads(
            (ROOT / "qa02a_manifest.json").read_text(encoding="utf-8")
        )
        validated = _validate_manifest(value)
        self.assertEqual(len(validated["controls"]), 7)
        self.assertGreaterEqual(len(validated["tests"]), 20)

    def test_all_declared_tests_must_run_and_pass(self) -> None:
        report = build_report(
            _manifest(),
            _cases(),
            command_exit_code=0,
            command_duration_ms=10,
        )
        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["summary"]["caseCount"], 2)

        missing = deepcopy(_cases())
        del missing["example.MaskTest"]
        report = build_report(
            _manifest(),
            missing,
            command_exit_code=0,
            command_duration_ms=10,
        )
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["failures"][0]["category"], "TEST_CLASS_MISSING")

    def test_failure_and_skip_block_the_control(self) -> None:
        cases = deepcopy(_cases())
        cases["example.AccessTest"][0] = {
            "name": "denies",
            "status": "FAIL",
            "durationMs": 1,
            "message": "expected denial",
        }
        cases["example.MaskTest"][0]["status"] = "SKIP"
        report = build_report(
            _manifest(),
            cases,
            command_exit_code=0,
            command_duration_ms=10,
        )
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["controls"][0]["status"], "FAIL")
        self.assertEqual(report["summary"]["failureCount"], 2)

    def test_maven_failure_blocks_even_when_reports_pass(self) -> None:
        report = build_report(
            _manifest(),
            _cases(),
            command_exit_code=1,
            command_duration_ms=10,
            diagnostic="build failed",
        )
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["failures"][-1]["category"], "MAVEN_FAILURE")

    def test_manifest_rejects_unassigned_or_unknown_tests(self) -> None:
        manifest = _manifest()
        manifest["controls"][0]["tests"] = ["unknown"]
        with self.assertRaises(SecurityGateError):
            _validate_manifest(manifest)

        manifest = _manifest()
        manifest["controls"][0]["tests"] = ["access"]
        with self.assertRaises(SecurityGateError):
            _validate_manifest(manifest)

    def test_sanitizes_paths_credentials_and_tokens(self) -> None:
        value = _sanitize(
            r"F:\secret\file password=hunter2 "
            "https://admin:password@example.test token=abc"
        )
        self.assertNotIn("hunter2", value)
        self.assertNotIn("admin:password", value)
        self.assertNotIn("token=abc", value)


if __name__ == "__main__":
    unittest.main()
