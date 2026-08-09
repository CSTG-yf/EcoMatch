#!/usr/bin/env python3
"""Tests for the portable bank Agent bootstrap package."""

from __future__ import annotations

import sys
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from bootstrap_bank_agent import (  # noqa: E402
    BankAgentBootstrapError,
    build_agent_payload,
    patch_system_config,
    unwrap_api_response,
)


class BootstrapBankAgentTest(unittest.TestCase):
    def test_agent_payload_uses_imported_dataset_and_selected_chat_model(self) -> None:
        payload = build_agent_payload(data_set_id=77, chat_model_id=5)

        self.assertEqual(payload["name"], "银行问数")
        self.assertEqual(payload["examples"], [])
        self.assertEqual(payload["isOpen"], 1)
        tool_config = json.loads(payload["toolConfig"])
        self.assertEqual(tool_config["tools"][0]["dataSetIds"], [77])
        self.assertTrue(payload["chatAppConfig"]["BANK_CONSTRAINED_PLAN"]["enable"])
        self.assertEqual(payload["chatAppConfig"]["S2SQL_PARSER"]["chatModelId"], 5)
        self.assertEqual(payload["chatAppConfig"]["EXECUTION_SQL_CORRECTOR"]["chatModelId"], 5)

    def test_existing_agent_id_is_preserved_for_idempotent_update(self) -> None:
        existing = {
            "id": 41,
            "examples": ["保留示例"],
            "admins": ["alice"],
            "viewers": ["bob"],
            "adminOrgs": ["org-admin"],
            "viewOrgs": ["org-view"],
            "visualConfig": {"type": "TABLE"},
        }

        payload = build_agent_payload(
            data_set_id=88,
            chat_model_id=3,
            existing_agent=existing,
        )

        self.assertEqual(payload["id"], 41)
        self.assertEqual(json.loads(payload["toolConfig"])["tools"][0]["dataSetIds"], [88])
        self.assertEqual(payload["examples"], ["保留示例"])
        self.assertEqual(payload["admins"], ["alice"])
        self.assertEqual(payload["viewers"], ["bob"])
        self.assertEqual(payload["adminOrgs"], ["org-admin"])
        self.assertEqual(payload["viewOrgs"], ["org-view"])
        self.assertEqual(payload["visualConfig"], {"type": "TABLE"})

    def test_system_config_patch_updates_and_appends_without_dropping_other_values(self) -> None:
        current = {
            "id": 1,
            "admins": ["admin"],
            "parameters": [
                {"name": "unrelated", "value": "keep", "comment": "unchanged"},
                {"name": "s2.parser.bank.max-candidates", "value": "9"},
            ],
        }
        wanted = {
            "s2.parser.bank.max-candidates": "1",
            "s2.parser.bank.constrained-plan.enable": "true",
        }

        patched = patch_system_config(current, wanted)

        by_name = {item["name"]: item for item in patched["parameters"]}
        self.assertEqual(by_name["unrelated"]["value"], "keep")
        self.assertEqual(by_name["s2.parser.bank.max-candidates"]["value"], "1")
        self.assertEqual(by_name["s2.parser.bank.constrained-plan.enable"]["value"], "true")

    def test_api_wrapper_fails_closed_on_application_error(self) -> None:
        with self.assertRaisesRegex(BankAgentBootstrapError, "authentication failed"):
            unwrap_api_response({"code": 403, "msg": "authentication failed", "data": None})

        self.assertEqual(
            unwrap_api_response({"code": 200, "msg": "success", "data": {"id": 7}}),
            {"id": 7},
        )

    def test_windows_launcher_uses_project_venv_and_clears_token(self) -> None:
        launcher = (ROOT / "Bootstrap-BankAgent.cmd").read_text(encoding="utf-8")

        self.assertIn(r"evaluation\.venv\Scripts\python.exe", launcher)
        self.assertIn('set "ECOMATCH_AUTH_TOKEN="', launcher)
        self.assertNotIn("local-no-key", launcher)


if __name__ == "__main__":
    unittest.main()
