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
    build_bank_model_payload,
    build_domain_payload,
    encrypt_login_password,
    ensure_runtime_resources,
    patch_system_config,
    select_chat_model,
    select_database,
    unwrap_api_response,
)


class BootstrapBankAgentTest(unittest.TestCase):
    def test_login_password_matches_frontend_cryptojs_contract(self) -> None:
        self.assertEqual(encrypt_login_password("123456"), "e6+jQ26AESREiBBuKM1u1A==")

    def test_domain_and_model_payloads_use_bank_stable_business_keys(self) -> None:
        domain = build_domain_payload("admin")
        self.assertEqual(domain["name"], "银行问数")
        self.assertEqual(domain["bizName"], "bank_question")
        self.assertEqual(domain["admins"], ["admin"])

        model = build_bank_model_payload(
            database_id=3,
            domain_id=4,
            admin_name="admin",
            date_field="data_date",
            organization_field="org_code",
            indicator_code_field="metric_code",
            indicator_value_field="metric_value",
        )
        self.assertEqual(model["bizName"], "bank_metric_daily")
        self.assertEqual(model["databaseId"], 3)
        self.assertEqual(model["domainId"], 4)
        self.assertIn("bank_metric_daily", model["modelDetail"]["sqlQuery"])
        self.assertEqual(
            {field["fieldName"] for field in model["modelDetail"]["fields"]},
            {"data_date", "org_code", "metric_code", "metric_value"},
        )

    def test_resource_bootstrap_discovers_database_and_chat_model_without_ids(self) -> None:
        class FakeClient:
            def __init__(self) -> None:
                self.calls = []
                self.model_list_calls = 0

            def json(self, method, path, payload=None):
                self.calls.append((method, path, payload))
                responses = {
                    "GET /api/semantic/domain/getDomainList": [],
                    "POST /api/semantic/domain/createDomain": {"id": 11},
                    "GET /api/semantic/database/getDatabaseList": [
                        {"id": 12, "name": "银行问数数据库", "type": "H2"},
                    ],
                    "GET /api/chat/model/getModelList": [
                        {"id": 13, "name": "OpenAI模型DEMO"},
                    ],
                    "POST /api/semantic/model/createModel": True,
                }
                if path == "/api/semantic/model/getModelList/11":
                    self.model_list_calls += 1
                    return [] if self.model_list_calls == 1 else [
                        {
                            "id": 14,
                            "bizName": "bank_metric_daily",
                            "modelDetail": {
                                "fields": [
                                    {"fieldName": "data_date"},
                                    {"fieldName": "org_code"},
                                    {"fieldName": "metric_code"},
                                    {"fieldName": "metric_value"},
                                ]
                            },
                        }
                    ]
                return responses[f"{method} {path}"]

        client = FakeClient()
        resources = ensure_runtime_resources(
            client,
            database_id=None,
            model_id=None,
            chat_model_id=None,
            chat_model_name=None,
            admin_name="admin",
            date_field="data_date",
            organization_field="org_code",
            indicator_code_field="metric_code",
            indicator_value_field="metric_value",
        )

        self.assertEqual(resources["databaseId"], 12)
        self.assertEqual(resources["modelId"], 14)
        self.assertIsNone(resources["chatModelId"])
        self.assertTrue(resources["createdDomain"])
        self.assertTrue(resources["createdModel"])
        self.assertIn(("GET", "/api/semantic/database/getDatabaseList", None), client.calls)

    def test_resource_selection_rejects_ambiguous_database(self) -> None:
        class FakeClient:
            def json(self, method, path, payload=None):
                return [
                    {"id": 1, "name": "one", "type": "H2"},
                    {"id": 2, "name": "two", "type": "H2"},
                ]

        with self.assertRaisesRegex(BankAgentBootstrapError, "cannot uniquely select a database"):
            select_database(FakeClient())

    def test_chat_model_selection_is_only_used_when_explicitly_requested(self) -> None:
        class FakeClient:
            def json(self, method, path, payload=None):
                return [
                    {"id": 1, "name": "demo", "config": {"baseUrl": "https://api.openai.com/v1", "apiKey": "demo"}},
                    {"id": 2, "name": "local", "config": {"baseUrl": "http://llm:8080/v1", "apiKey": "local-no-key"}},
                ]

        self.assertIsNone(select_chat_model(FakeClient()))
        self.assertEqual(select_chat_model(FakeClient(), chat_model_id=2), 2)

    def test_default_agent_payload_leaves_model_binding_for_admin(self) -> None:
        payload = build_agent_payload(data_set_id=77, chat_model_id=None)

        self.assertTrue(payload["chatAppConfig"]["BANK_CONSTRAINED_PLAN"]["enable"])
        self.assertNotIn("chatModelId", payload["chatAppConfig"]["S2SQL_PARSER"])
        self.assertNotIn("chatModelId", payload["chatAppConfig"]["BANK_FINAL_ANSWER"])

    def test_default_update_preserves_an_admin_selected_model(self) -> None:
        payload = build_agent_payload(
            data_set_id=77,
            chat_model_id=None,
            existing_agent={
                "id": 41,
                "chatAppConfig": {
                    "S2SQL_PARSER": {
                        "enable": True,
                        "chatModelId": 9,
                        "chatModelConfig": {"apiKey": "must-not-be-copied"},
                    }
                },
            },
        )

        self.assertEqual(payload["chatAppConfig"]["S2SQL_PARSER"]["chatModelId"], 9)
        self.assertNotIn("chatModelConfig", payload["chatAppConfig"]["S2SQL_PARSER"])

    def test_agent_payload_uses_imported_dataset_and_selected_chat_model(self) -> None:
        payload = build_agent_payload(data_set_id=77, chat_model_id=5)

        self.assertEqual(payload["name"], "银行问数")
        self.assertEqual(payload["examples"], [])
        self.assertEqual(payload["isOpen"], 1)
        tool_config = json.loads(payload["toolConfig"])
        self.assertEqual(tool_config["tools"][0]["dataSetIds"], [77])
        self.assertTrue(payload["chatAppConfig"]["BANK_CONSTRAINED_PLAN"]["enable"])
        final_answer = payload["chatAppConfig"]["BANK_FINAL_ANSWER"]
        self.assertTrue(final_answer["enable"])
        self.assertEqual(final_answer["chatModelId"], 5)
        self.assertIn("validation_feedback", final_answer["prompt"])
        self.assertIn("metric_catalog", final_answer["prompt"])
        self.assertIn("整体上升、下降或持平", final_answer["prompt"])
        self.assertIn("禁止输出记录数", final_answer["prompt"])
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
        self.assertIn("--output", launcher)
        self.assertIn("bootstrap-receipt.json", launcher)
        self.assertNotIn("local-no-key", launcher)

    def test_single_start_entry_orchestrates_existing_interfaces(self) -> None:
        launcher = (ROOT / "Start-BankSystem.ps1").read_text(encoding="utf-8")
        wrapper = (ROOT / "Start-BankSystem.cmd").read_text(encoding="utf-8")
        standalone_config = (
            ROOT.parent.parent
            / "launchers"
            / "standalone"
            / "src"
            / "main"
            / "resources"
            / "s2-config.yaml"
        ).read_text(encoding="utf-8")

        self.assertIn("supersonic-build.bat", launcher)
        self.assertIn("supersonic-daemon.bat", launcher)
        self.assertIn("Import-OfficialBankData.ps1", launcher)
        self.assertIn("bootstrap_bank_agent.py", launcher)
        self.assertIn("--base-url", launcher)
        self.assertIn("--output", launcher)
        self.assertIn("S2_METADATA_DB_PATH", launcher)
        self.assertIn("-m venv", launcher)
        self.assertIn("-m pip install -r", launcher)
        self.assertIn("Test-MetadataDatabasePresent", launcher)
        self.assertNotRegex(launcher, r"java\s+-jar")
        self.assertNotIn("org.h2", launcher)
        self.assertNotIn("INSERT INTO", launcher)
        self.assertNotIn(r"\.mv.db", launcher)
        self.assertIn("Start-BankSystem.ps1", wrapper)
        self.assertIn('Join-Path $PSScriptRoot "..\\.."', launcher)
        self.assertIn("names: []", standalone_config)


if __name__ == "__main__":
    unittest.main()
