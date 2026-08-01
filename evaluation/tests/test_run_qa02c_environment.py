#!/usr/bin/env python3
"""Tests for the QA-02C target-environment security gate."""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from run_qa02c_environment import (  # noqa: E402
    EnvironmentGateError,
    assert_json,
    expand_env,
    json_path,
    main,
    validate_config,
)


class GateHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        self._respond()

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        if length:
            self.rfile.read(length)
        self._respond()

    def _respond(self) -> None:
        if self.path.startswith("/deny"):
            self.send_response(403)
            body = b'{"code":403}'
        elif self.path.startswith("/redirect"):
            self.send_response(302)
            self.send_header("Location", "/allow")
            body = b""
        elif self.path.startswith("/identity"):
            self.send_response(200)
            body = b'{"name":"expected","attributes":{"organizationId":"org-a"}}'
        else:
            self.send_response(200)
            body = b'{"code":200,"dataMasked":true}'
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args) -> None:  # noqa: A002
        return


class Qa02cEnvironmentGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), GateHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def test_repository_template_is_complete_and_contains_no_secret_values(self) -> None:
        template_path = ROOT / "qa02c_environment.template.json"
        text = template_path.read_text(encoding="utf-8")
        config = validate_config(json.loads(text))
        self.assertEqual(len(config["scenarios"]), 14)
        self.assertNotIn("Bearer ", text)
        self.assertNotRegex(text, r'"token"\s*:\s*"(?!\$\{)')

    def test_environment_expansion_and_json_assertions_fail_closed(self) -> None:
        self.assertEqual(expand_env("/items/${RESOURCE_ID}", {"RESOURCE_ID": "7"}), "/items/7")
        with self.assertRaisesRegex(EnvironmentGateError, "RESOURCE_ID"):
            expand_env("${RESOURCE_ID}", {})
        payload = {"data": {"roles": ["AUDITOR"], "organizationId": "org-a"}}
        self.assertEqual(json_path(payload, "$.data.organizationId"), (True, "org-a"))
        assert_json(payload, {"path": "$.data.roles", "operator": "contains", "value": "AUDITOR"}, {})
        with self.assertRaises(EnvironmentGateError):
            assert_json(payload, {"path": "$.data.organizationId", "operator": "equalsEnv", "env": "ORG"}, {"ORG": "org-b"})

    def test_complete_local_environment_gate_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path, report_path, output_path, env = self._fixture(root)
            exit_code = main(
                ["--config", str(config_path), "--repository-report", str(report_path), "--output", str(output_path), "--allow-http"],
                environ=env,
            )
            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(exit_code, 0)
            self.assertEqual(report["status"], "PASS")
            self.assertEqual(report["summary"]["passedControlCount"], 8)
            self.assertEqual(report["summary"]["passedEvidenceCount"], 14)
            serialized = output_path.read_text(encoding="utf-8")
            for value in [env["BANK_QA_ORG_A_OWNER_TOKEN"], env["BANK_QA_RAW_SENSITIVE_VALUE"]]:
                self.assertNotIn(value, serialized)

    def test_log_leakage_blocks_environment_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path, report_path, output_path, env = self._fixture(root, leaking_log=True)
            exit_code = main(
                ["--config", str(config_path), "--repository-report", str(report_path), "--output", str(output_path), "--allow-http"],
                environ=env,
            )
            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(exit_code, 1)
            self.assertEqual(report["status"], "FAIL")
            self.assertEqual(report["failures"][0]["subject"], "application-log-leakage")
            self.assertNotIn(env["BANK_QA_RAW_SENSITIVE_VALUE"], output_path.read_text(encoding="utf-8"))

    def test_redirect_is_not_accepted_as_success(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path, report_path, output_path, env = self._fixture(root)
            config = json.loads(config_path.read_text(encoding="utf-8"))
            scenario = next(item for item in config["scenarios"] if item["requirement"] == "resource-allow")
            scenario["request"]["path"] = "/redirect"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            exit_code = main(
                ["--config", str(config_path), "--repository-report", str(report_path), "--output", str(output_path), "--allow-http"],
                environ=env,
            )
            self.assertEqual(exit_code, 1)
            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "FAIL")

    def test_expanded_path_cannot_escape_the_configured_origin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path, report_path, output_path, env = self._fixture(root)
            config = json.loads(config_path.read_text(encoding="utf-8"))
            scenario = next(item for item in config["scenarios"] if item["requirement"] == "resource-allow")
            scenario["request"]["path"] = "/allow/${UNSAFE_RESOURCE_ID}"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            env["UNSAFE_RESOURCE_ID"] = "../admin"
            exit_code = main(
                ["--config", str(config_path), "--repository-report", str(report_path), "--output", str(output_path), "--allow-http"],
                environ=env,
            )
            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(exit_code, 1)
            self.assertEqual(report["status"], "FAIL")
            self.assertEqual(report["failures"][0]["subject"], scenario["id"])

    def _fixture(self, root: Path, *, leaking_log: bool = False):
        config = json.loads((ROOT / "qa02c_environment.template.json").read_text(encoding="utf-8"))
        for scenario in config["scenarios"]:
            if scenario["type"] != "http":
                continue
            if scenario["requirement"] == "identity-allow":
                scenario["request"]["path"] = "/identity"
            elif scenario["requirement"].endswith("deny"):
                scenario["request"]["path"] = "/deny"
            else:
                scenario["request"]["path"] = "/allow"
            scenario["request"].pop("jsonBody", None)
        config_path = root / "config.json"
        config_path.write_text(json.dumps(config), encoding="utf-8")
        repository_report = {
            "schemaVersion": "1.0",
            "task": "QA-02C",
            "scope": "REPOSITORY",
            "status": "PASS",
            "environmentGateRequired": True,
            "generatedAt": "2026-08-01T00:00:00+00:00",
        }
        report_path = root / "repository.json"
        report_path.write_text(json.dumps(repository_report), encoding="utf-8")
        log_path = root / "application.log"
        raw_value = "raw-sensitive-123456"
        log_path.write_text(f"safe log {raw_value if leaking_log else 'redacted'}", encoding="utf-8")
        env_names = set(re.findall(r"\$\{([A-Z][A-Z0-9_]*)\}", json.dumps(config)))
        env_names.update(config.get("sensitiveValueEnvs", []))
        env_names.update(actor["tokenEnv"] for actor in config["actors"].values())
        env_names.add("BANK_QA_APPLICATION_LOG")
        env = {name: f"value-{name.lower()}" for name in env_names}
        env.update(
            {
                "BANK_QA_BASE_URL": f"http://127.0.0.1:{self.server.server_port}",
                "BANK_QA_ORG_A_OWNER_NAME": "expected",
                "BANK_QA_ORG_A_ID": "org-a",
                "BANK_QA_RAW_SENSITIVE_VALUE": raw_value,
                "BANK_QA_TEST_ACCOUNT_NO": "6222000012345678",
                "BANK_QA_APPLICATION_LOG": str(log_path),
            }
        )
        return config_path, report_path, root / "output.json", env


if __name__ == "__main__":
    unittest.main()
