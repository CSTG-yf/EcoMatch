#!/usr/bin/env python3
"""Tests for bank-on / bank-off ablation comparison."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from compare_runtime_ablation import AblationCompareError, compare_runtime_ablation  # noqa: E402
from run_supersonic_eval import (  # noqa: E402
    SuperSonicEvaluationError,
    _bank_routing_telemetry,
    _filter_records_by_ids,
    _load_record_ids_file,
)


class RuntimeAblationHelpersTest(unittest.TestCase):
    def test_extracts_bank_routing_telemetry(self) -> None:
        telemetry = _bank_routing_telemetry(
            {
                "bankRoutingAttemptTelemetry": {
                    "bankConstrainedPlanEnabled": True,
                    "bankDatasetQualified": True,
                    "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                    "llmCandidateCreated": True,
                    "raw": "should-not-leak",
                }
            }
        )
        self.assertEqual(
            telemetry,
            {
                "bankConstrainedPlanEnabled": True,
                "bankDatasetQualified": True,
                "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                "llmCandidateCreated": True,
            },
        )

    def test_filters_records_in_requested_order(self) -> None:
        records = [
            {"id": "TRAIN-S-01", "question": "a"},
            {"id": "TRAIN-H-01", "question": "b"},
            {"id": "TRAIN-M-01", "question": "c"},
        ]
        filtered = _filter_records_by_ids(records, ["TRAIN-M-01", "TRAIN-S-01"])
        self.assertEqual([item["id"] for item in filtered], ["TRAIN-M-01", "TRAIN-S-01"])

    def test_rejects_unknown_record_ids(self) -> None:
        with self.assertRaises(SuperSonicEvaluationError):
            _filter_records_by_ids([{"id": "TRAIN-S-01"}], ["MISSING"])

    def test_loads_manifest_style_ids_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "ids.json"
            path.write_text(
                json.dumps({"recordIds": ["TRAIN-S-01", "TRAIN-M-01"]}),
                encoding="utf-8",
            )
            self.assertEqual(_load_record_ids_file(path), ["TRAIN-S-01", "TRAIN-M-01"])
            nested = Path(temp_dir) / "manifest.json"
            nested.write_text(
                json.dumps({"smoke": {"recordIds": ["TRAIN-H-01", "TRAIN-S-01"]}}),
                encoding="utf-8",
            )
            self.assertEqual(_load_record_ids_file(nested), ["TRAIN-H-01", "TRAIN-S-01"])


class CompareRuntimeAblationTest(unittest.TestCase):
    def test_compares_shared_ids_and_recommends_without_deletion(self) -> None:
        left = {
            "run": {
                "runtimeMode": "bank-off",
                "status": "COMPLETED",
                "protocolSchemaVersion": "3.0.0",
                "datasetVersion": "2.0.1",
                "agentId": 33,
                "modelLabel": "test-model",
                "endpointFingerprint": "a" * 64,
                "protocolProfileSha256": "b" * 64,
                "sourceRevision": "c" * 40,
                "captureMethod": "openapi-frontend-conversation-chain",
                "concurrency": 1,
                "mode": "smoke",
                "split": "train",
                "selectedRecordIds": ["A", "B"],
                "setupReceipt": {
                    "agentId": 33,
                    "modelId": 1,
                    "chatModelId": 1,
                    "dataSetId": 17,
                    "officialManifestSha256": "d" * 64,
                    "agentProfileSha256": "e" * 64,
                    "systemParametersSha256": "f" * 64,
                },
            },
            "metrics": {"caseAccuracy": 0.5, "parseSuccessRate": 1.0, "executionSuccessRate": 1.0},
            "items": [
                {
                    "id": "A",
                    "parse": True,
                    "execute": True,
                    "casePass": True,
                    "errorCategory": None,
                    "bankRouting": {
                        "bankConstrainedPlanEnabled": False,
                        "bankDatasetQualified": True,
                        "selectedSqlGenType": "ONE_PASS_SELF_CONSISTENCY",
                        "llmCandidateCreated": True,
                    },
                },
                {
                    "id": "B",
                    "parse": True,
                    "execute": True,
                    "casePass": False,
                    "errorCategory": "RESULT_FACT_MISMATCH",
                    "bankRouting": {
                        "bankConstrainedPlanEnabled": False,
                        "bankDatasetQualified": True,
                        "selectedSqlGenType": "ONE_PASS_SELF_CONSISTENCY",
                        "llmCandidateCreated": True,
                    },
                },
            ],
        }
        right = {
            "run": {
                "runtimeMode": "bank-on",
                "status": "COMPLETED",
                "protocolSchemaVersion": "3.0.0",
                "datasetVersion": "2.0.1",
                "agentId": 33,
                "modelLabel": "test-model",
                "endpointFingerprint": "a" * 64,
                "protocolProfileSha256": "b" * 64,
                "sourceRevision": "c" * 40,
                "captureMethod": "openapi-frontend-conversation-chain",
                "concurrency": 1,
                "mode": "smoke",
                "split": "train",
                "selectedRecordIds": ["A", "B"],
                "setupReceipt": {
                    "agentId": 33,
                    "modelId": 1,
                    "chatModelId": 1,
                    "dataSetId": 17,
                    "officialManifestSha256": "d" * 64,
                    "agentProfileSha256": "e" * 64,
                    "systemParametersSha256": "f" * 64,
                },
            },
            "metrics": {"caseAccuracy": 1.0, "parseSuccessRate": 1.0, "executionSuccessRate": 1.0},
            "items": [
                {
                    "id": "A",
                    "parse": True,
                    "execute": True,
                    "casePass": True,
                    "errorCategory": None,
                    "bankRouting": {
                        "bankConstrainedPlanEnabled": True,
                        "bankDatasetQualified": True,
                        "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                        "llmCandidateCreated": True,
                    },
                },
                {
                    "id": "B",
                    "parse": True,
                    "execute": True,
                    "casePass": True,
                    "errorCategory": None,
                    "bankRouting": {
                        "bankConstrainedPlanEnabled": True,
                        "bankDatasetQualified": True,
                        "selectedSqlGenType": "BANK_CONSTRAINED_PLAN",
                        "llmCandidateCreated": True,
                    },
                },
            ],
        }

        comparison = compare_runtime_ablation(left, right)
        self.assertEqual(comparison["deltas"]["caseAccuracy"], 0.5)
        self.assertEqual(comparison["deltas"]["onlyRightCasePass"], 1)
        self.assertTrue(comparison["comparability"]["verified"])
        self.assertTrue(comparison["left"]["routing"]["routingLooksConsistent"])
        self.assertTrue(comparison["right"]["routing"]["routingLooksConsistent"])
        self.assertEqual(comparison["recommendation"]["decision"], "prefer-bank-on-for-now")
        self.assertTrue(comparison["recommendation"]["doNotDeleteCode"])

    def test_rejects_mismatched_id_sets(self) -> None:
        with self.assertRaises(AblationCompareError):
            compare_runtime_ablation(
                {
                    "metrics": {"caseAccuracy": 0.0},
                    "items": [{"id": "A", "parse": False, "execute": False, "casePass": False}],
                },
                {
                    "metrics": {"caseAccuracy": 0.0},
                    "items": [{"id": "B", "parse": False, "execute": False, "casePass": False}],
                },
            )

    def test_rejects_different_official_runtime_contracts(self) -> None:
        base_run = {
            "status": "COMPLETED",
            "protocolSchemaVersion": "3.0.0",
            "datasetVersion": "2.0.1",
            "agentId": 33,
            "modelLabel": "test-model",
            "endpointFingerprint": "a" * 64,
            "protocolProfileSha256": "b" * 64,
            "sourceRevision": "c" * 40,
            "captureMethod": "openapi-frontend-conversation-chain",
            "concurrency": 1,
            "mode": "smoke",
            "split": "train",
            "selectedRecordIds": ["A"],
            "setupReceipt": {
                "agentId": 33,
                "modelId": 1,
                "chatModelId": 1,
                "dataSetId": 17,
                "officialManifestSha256": "d" * 64,
                "agentProfileSha256": "e" * 64,
                "systemParametersSha256": "f" * 64,
            },
        }
        left = {
            "run": {**base_run, "runtimeMode": "bank-off"},
            "metrics": {"caseAccuracy": 1.0},
            "items": [{"id": "A", "casePass": True}],
        }
        right = {
            "run": {**base_run, "runtimeMode": "bank-on", "modelLabel": "another-model"},
            "metrics": {"caseAccuracy": 1.0},
            "items": [{"id": "A", "casePass": True}],
        }

        with self.assertRaises(AblationCompareError):
            compare_runtime_ablation(left, right)

    def test_rejects_a_running_checkpoint(self) -> None:
        run = {
            "status": "RUNNING",
            "protocolSchemaVersion": "3.0.0",
            "datasetVersion": "2.0.1",
            "agentId": 33,
            "modelLabel": "test-model",
            "endpointFingerprint": "a" * 64,
            "protocolProfileSha256": "b" * 64,
            "sourceRevision": "c" * 40,
            "captureMethod": "openapi-frontend-conversation-chain",
            "concurrency": 1,
            "mode": "smoke",
            "split": "train",
            "selectedRecordIds": ["A"],
            "setupReceipt": {
                "agentId": 33,
                "modelId": 1,
                "chatModelId": 1,
                "dataSetId": 17,
                "officialManifestSha256": "d" * 64,
                "agentProfileSha256": "e" * 64,
                "systemParametersSha256": "f" * 64,
            },
        }
        report = {
            "run": run,
            "metrics": {"caseAccuracy": 1.0},
            "items": [{"id": "A", "casePass": True}],
        }

        with self.assertRaises(AblationCompareError):
            compare_runtime_ablation(report, report)


if __name__ == "__main__":
    unittest.main()
