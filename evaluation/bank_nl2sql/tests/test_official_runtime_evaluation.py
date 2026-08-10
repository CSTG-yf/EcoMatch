#!/usr/bin/env python3
"""Contract tests for the single official Bank NL2SQL runtime report."""

from __future__ import annotations

import sys
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from official_runtime_evaluation import (  # noqa: E402
    OFFICIAL_RUNTIME_SCHEMA_VERSION,
    OfficialRuntimeEvaluationError,
    build_official_runtime_report,
    load_official_runtime_profile,
    verify_bootstrap_receipt,
    verify_official_runtime_release,
)


def _record(sample_id: str = "TRAIN-S-01") -> dict:
    return {
        "id": sample_id,
        "question": "2025年末，江苏省A市农商行存款余额是多少？",
        "expected": {
            "answerText": "2025年末，江苏省A市农商行存款余额42.02亿元。",
            "columns": ["metric_value"],
            "rows": [[42.02]],
            "numericTolerance": 0.000001,
            "orderSensitive": True,
            "unit": None,
        },
        "difficulty": "simple",
        "sqlFeatures": ["POINT_QUERY"],
    }


def _capture(sample_id: str = "TRAIN-S-01") -> dict:
    return {
        "run": {
            "captureMethod": "concurrent-openapi-frontend-chain",
            "split": "train",
            "agentId": 33,
        },
        "timingMs": {"averageParseMs": 1.0, "averageExecuteMs": 2.0},
        "timingDistributionsMs": {"endToEnd": {"count": 1, "p95": 4.0}},
        "items": [
            {
                "id": sample_id,
                "difficulty": "simple",
                "sqlFeatures": ["POINT_QUERY"],
                "parse": True,
                "execute": True,
                "summaryState": "SUCCESS",
                "textSummary": "2025年末，江苏省A市农商行存款余额42.02亿元。",
                "resultColumns": ["metric_value"],
                "resultRows": [[42.02]],
                "chatId": 501,
                "queryId": 101,
                "s2sql": "SELECT metric_value FROM semantic_dataset",
                "physicalSql": "SELECT metric_value FROM bank_metric_daily",
                "bankRouting": {"bankConstrainedPlanEnabled": True},
                "errorCategory": None,
            }
        ],
    }


class OfficialRuntimeEvaluationTest(unittest.TestCase):
    def test_emits_only_full_denominator_fact_v3_metrics(self) -> None:
        report = build_official_runtime_report(_capture(), [_record()])

        self.assertEqual(report["schemaVersion"], OFFICIAL_RUNTIME_SCHEMA_VERSION)
        self.assertEqual(
            set(report["metrics"]),
            {
                "caseAccuracy",
                "casePassHits",
                "caseDenominator",
                "resultFactAccuracy",
                "resultFactsExactHits",
                "finalFactAccuracy",
                "finalFactsExactHits",
                "contractReadyRate",
                "contractReadyCount",
            },
        )
        self.assertEqual(report["metrics"]["caseAccuracy"], 1.0)
        self.assertTrue(report["items"][0]["casePass"])
        self.assertEqual(report["items"][0]["errorCategory"], None)
        self.assertEqual(report["runtimeDiagnostics"]["parseSuccessRate"], 1.0)
        self.assertEqual(report["runtimeDiagnostics"]["executionSuccessRate"], 1.0)
        for legacy_key in ("answerExact", "answerScore", "goldGrade", "match", "tableEX", "tableExact"):
            self.assertNotIn(legacy_key, report["items"][0])

    def test_rejects_a_capture_that_omits_or_adds_records(self) -> None:
        with self.assertRaises(OfficialRuntimeEvaluationError):
            build_official_runtime_report(_capture("UNEXPECTED"), [_record()])

    def test_bootstrap_receipt_binds_the_agent_to_the_official_dataset(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "bootstrap-receipt.json"
            path.write_text(
                json.dumps(
                    {
                        "receiptSchemaVersion": "1.0",
                        "officialVersion": "2.0.1",
                        "officialManifestSha256": "c" * 64,
                        "agentId": 33,
                        "modelId": 1,
                        "chatModelId": 1,
                        "dataSetId": 17,
                        "semanticImport": {
                            "organizations": 13,
                            "indicators": 21,
                            "factsValidated": 132678,
                        },
                        "agentProfileSha256": "a" * 64,
                        "systemParametersSha256": "b" * 64,
                    }
                ),
                encoding="utf-8",
            )
            receipt = verify_bootstrap_receipt(
                path,
                dataset_version="2.0.1",
                agent_id=33,
                official_manifest_sha256="c" * 64,
                database_counts={"organizations": 13, "metrics": 21, "facts": 132678},
            )

            self.assertEqual(receipt["agentId"], 33)
            self.assertEqual(receipt["agentProfileSha256"], "a" * 64)
            with self.assertRaises(OfficialRuntimeEvaluationError):
                verify_bootstrap_receipt(
                    path,
                    dataset_version="2.0.1",
                    agent_id=34,
                    official_manifest_sha256="c" * 64,
                    database_counts={"organizations": 13, "metrics": 21, "facts": 132678},
                )

    def test_windows_launcher_exposes_only_the_official_runner(self) -> None:
        launcher = (ROOT / "Run-OfficialBankEvaluation.ps1").read_text(encoding="utf-8")

        self.assertIn("run_official_runtime_eval.py", launcher)
        self.assertNotIn("run_supersonic_eval.py", launcher)
        self.assertNotIn("--concurrency", launcher)

    def test_all_public_scoring_commands_converge_or_fail_closed(self) -> None:
        official = subprocess.run(
            [sys.executable, str(ROOT / "run_supersonic_eval.py"), "--help"],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(official.returncode, 0)
        self.assertIn("--mode", official.stdout)
        self.assertNotIn("--concurrency", official.stdout)

        for legacy_name in (
            "score_answer_exact.py",
            "evaluate_predictions.py",
            "evaluate_ui_capture.py",
        ):
            legacy = subprocess.run(
                [sys.executable, str(ROOT / legacy_name)],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(legacy.returncode, 2, legacy_name)
            self.assertIn("retired", legacy.stderr)
            self.assertIn("caseAccuracy", legacy.stderr)

    def test_public_readme_has_no_alternate_score(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")

        self.assertIn("## 官方运行时评测（唯一入口）", readme)
        self.assertIn("caseAccuracy", readme)
        public_guides = (
            ROOT / "README.md",
            ROOT / "RUNTIME_ABLATION.md",
            ROOT / "repro" / "BEST_BANK_ON.md",
            ROOT / "LLM_AGENT_SCORE_IMPROVEMENT_FACT_PLAN.md",
            ROOT.parents[1] / "AGENTS.md",
        )
        legacy_names = ("answerExact", "tableEX", "resultAccuracy")
        for guide in public_guides:
            content = guide.read_text(encoding="utf-8")
            self.assertIn("caseAccuracy", content, str(guide))
            for legacy_name in legacy_names:
                self.assertNotIn(legacy_name, content, str(guide))

    def test_profile_verifies_the_selected_split_without_reading_test_gold(self) -> None:
        profile, profile_sha256 = load_official_runtime_profile(ROOT)
        release = verify_official_runtime_release(ROOT, profile=profile, split="train")

        self.assertEqual(profile["datasetVersion"], "2.0.1")
        self.assertEqual(len(profile_sha256), 64)
        self.assertEqual(release["recordCount"], 119)
        self.assertIn("train.jsonl", release["checkedAssets"])
        self.assertNotIn("test.jsonl", release["checkedAssets"])
        self.assertEqual(
            release["databaseCounts"],
            {"facts": 132678, "metrics": 21, "organizations": 13},
        )
        self.assertEqual(len(release["officialManifestSha256"]), 64)
        self.assertTrue(release["officialManifestSha256"].isupper())

    def test_profile_rejects_any_relaxed_score_contract(self) -> None:
        profile_path = ROOT / "official_runtime_evaluation_v3.json"
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            altered = json.loads(profile_path.read_text(encoding="utf-8"))
            altered["capture"]["concurrency"] = 2
            (root / "official_runtime_evaluation_v3.json").write_text(
                json.dumps(altered), encoding="utf-8"
            )
            with self.assertRaises(OfficialRuntimeEvaluationError):
                load_official_runtime_profile(root)


if __name__ == "__main__":
    unittest.main()
