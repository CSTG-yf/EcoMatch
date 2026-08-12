#!/usr/bin/env python3
"""Contract tests for the DATA-02 JSONL dataset builder."""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

from openpyxl import Workbook


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from build_dataset import DatasetBuildError, build_dataset  # noqa: E402
from validate_dataset import DatasetValidationError, validate_dataset  # noqa: E402


class BuildDatasetTest(unittest.TestCase):
    def create_question_workbook(self, path: Path) -> None:
        workbook = Workbook()
        sheet = workbook.active
        sheet.title = "问题答案清单"
        sheet.append(["问题编号", "问题类型", "问题难度", "问题描述", "问题结果"])
        sheet.append(["TRAIN-S-01", "训练集", "简单", "A行存款余额是多少？", "41.76亿元"])
        sheet.append(["VAL-S-01", "验证集", "普通", "B行不良贷款率是多少？", "1.10%"])
        sheet.append(["TEST-S-01", "测试集", "复杂", "哪家存款余额最高？", "江苏省A市农商行"])
        workbook.save(path)

    @staticmethod
    def intent_record(sample_id: str, source_split: str | None, split: str, source: str = "competition_workbook") -> dict:
        return {
            "id": sample_id,
            "source": source,
            "sourceSplit": source_split,
            "split": split,
            "difficulty": "简单",
            "question": "placeholder",
            "answer": "placeholder",
            "scene": "OPERATION_ANALYSIS",
            "intent": "POINT_QUERY",
            "metrics": [{"code": "ZB001", "name": "各项存款余额", "matchedText": "存款余额"}],
            "dimensions": ["bank_data_date", "bank_organization"],
            "time": {"expressions": ["2024年12月31日"]},
            "organizations": [],
            "filters": [],
            "linguisticFeatures": ["STANDARD"],
            "clarificationExpected": False,
            "templateGroup": f"template-{sample_id}",
            "referenceDate": "2026-07-23",
        }

    def write_intents(self, root: Path) -> None:
        root.mkdir()
        entries = {
            "train": [self.intent_record("TRAIN-S-01", "train", "train")],
            "dev": [self.intent_record("VAL-S-01", "dev", "test")],
            "test": [
                self.intent_record("TEST-S-01", "test", "test"),
                self.intent_record("AUG-01", None, "test", "curated_augmentation"),
            ],
        }
        for split, records in entries.items():
            (root / f"{split}.jsonl").write_text(
                "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
                encoding="utf-8",
            )

    def test_freezes_official_split_to_workbook_source_split(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path = temp_path / "questions.xlsx"
            intent_root = temp_path / "bank_intent"
            output_path = temp_path / "bank_nl2sql"
            self.create_question_workbook(workbook_path)
            self.write_intents(intent_root)

            report = build_dataset(workbook_path, intent_root, output_path)
            validation = validate_dataset(output_path)

            self.assertEqual(report["officialCount"], 3)
            self.assertEqual(report["augmentationCount"], 1)
            self.assertEqual(report["sourceSplitCounts"], {"train": 1, "dev": 1, "test": 1})
            self.assertEqual(report["evaluationSplitCounts"], {"train": 1, "dev": 1, "test": 1})
            self.assertEqual(report["reassignedForTemplateIsolation"], [])
            self.assertEqual(report["templateOverlap"], {"trainDev": [], "trainTest": [], "devTest": []})
            self.assertEqual(report["version"], "0.1.0")
            self.assertEqual(validation["result"], "PASS")

            # VAL-S-01 的 intent split 为 "test"，但 workbook sourceSplit 是唯一事实：
            # 官方记录必须冻结在 dev，不得发生 template 迁移。
            dev_records = [
                json.loads(line)
                for line in (output_path / "dev.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            kept = next(record for record in dev_records if record["id"] == "VAL-S-01")
            self.assertEqual(kept["sourceSplit"], "dev")
            self.assertEqual(kept["split"], "dev")
            self.assertEqual(kept["splitReason"], "source_assignment")
            self.assertEqual(kept["expectedAction"], "EXECUTE")
            self.assertIsNone(kept["s2sql"])
            self.assertEqual(kept["expected"]["answerText"], "1.10%")
            self.assertEqual(len((output_path / "augmentation.jsonl").read_text(encoding="utf-8").splitlines()), 1)


class OfficialSplitContractTest(BuildDatasetTest):
    """DATA-02 官方切分不可变契约。

    对 competition_workbook 官方记录，workbook sourceSplit 是唯一事实：
    输出恒为 split == sourceSplit、splitReason == source_assignment。
    模板重叠只如实写入 manifest.templateOverlap，不触发跨切分迁移，
    也不令 validate_dataset 失败。
    """

    def write_overlap_intents(self, root: Path) -> None:
        """TRAIN-S-01 与 VAL-S-01 共享 templateGroup，制造真实模板重叠。"""
        root.mkdir()
        train = self.intent_record("TRAIN-S-01", "train", "train")
        train["templateGroup"] = "template-shared"
        dev = self.intent_record("VAL-S-01", "dev", "test")
        dev["templateGroup"] = "template-shared"
        entries = {
            "train": [train],
            "dev": [dev],
            "test": [self.intent_record("TEST-S-01", "test", "test")],
        }
        for split, records in entries.items():
            (root / f"{split}.jsonl").write_text(
                "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
                encoding="utf-8",
            )

    def test_template_overlap_passes_without_migration(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path = temp_path / "questions.xlsx"
            intent_root = temp_path / "bank_intent"
            output_path = temp_path / "bank_nl2sql"
            self.create_question_workbook(workbook_path)
            self.write_overlap_intents(intent_root)

            report = build_dataset(workbook_path, intent_root, output_path)

            self.assertEqual(
                report["templateOverlap"], {"trainDev": ["template-shared"], "trainTest": [], "devTest": []}
            )
            self.assertEqual(report["reassignedForTemplateIsolation"], [])
            records = [
                json.loads(line)
                for split in ("train", "dev", "test")
                for line in (output_path / f"{split}.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            for record in records:
                self.assertEqual(record["split"], record["sourceSplit"])
                self.assertEqual(record["splitReason"], "source_assignment")
            # 非空模板重叠本身必须允许通过
            self.assertEqual(validate_dataset(output_path)["result"], "PASS")

    def test_official_split_mismatch_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path = temp_path / "questions.xlsx"
            intent_root = temp_path / "bank_intent"
            output_path = temp_path / "bank_nl2sql"
            self.create_question_workbook(workbook_path)
            self.write_intents(intent_root)
            build_dataset(workbook_path, intent_root, output_path)

            # 篡改官方记录 split，使其与 workbook sourceSplit 不一致
            dev_lines = (output_path / "dev.jsonl").read_text(encoding="utf-8").splitlines()
            tampered = json.loads(dev_lines[0])
            tampered["split"] = "test"
            (output_path / "dev.jsonl").write_text(
                json.dumps(tampered, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(DatasetValidationError, "split must equal sourceSplit"):
                validate_dataset(output_path)

    def test_nonempty_reassigned_for_template_isolation_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path = temp_path / "questions.xlsx"
            intent_root = temp_path / "bank_intent"
            output_path = temp_path / "bank_nl2sql"
            self.create_question_workbook(workbook_path)
            self.write_intents(intent_root)
            build_dataset(workbook_path, intent_root, output_path)

            manifest = json.loads((output_path / "manifest.json").read_text(encoding="utf-8"))
            manifest["reassignedForTemplateIsolation"] = [
                {"id": "VAL-S-01", "sourceSplit": "dev", "split": "test"}
            ]
            (output_path / "manifest.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(DatasetValidationError, "reassignedForTemplateIsolation"):
                validate_dataset(output_path)


class OfficialManifestTest(BuildDatasetTest):
    """官方 manifest 只能授权账本声明的删除 ID；无 manifest 保持严格行为。

    复用 BuildDatasetTest 的共享 workbook/intent fixture
    （create_question_workbook / intent_record / write_intents），
    全部为合成数据，不依赖真实竞赛题目或真实题号。
    """

    def create_workbook_and_intents(
        self, temp_path: Path, include_unknown: bool = False, include_removed_intent: bool = True
    ) -> tuple[Path, Path]:
        workbook_path = temp_path / "questions.xlsx"
        intent_root = temp_path / "bank_intent"
        self.create_question_workbook(workbook_path)
        intent_root.mkdir()
        removed = self.intent_record("TRAIN-S-02", "train", "train")
        train_entries = [self.intent_record("TRAIN-S-01", "train", "train")]
        if include_removed_intent:
            train_entries.append(removed)
        entries = {
            "train": train_entries,
            "dev": [self.intent_record("VAL-S-01", "dev", "test")],
            "test": [
                self.intent_record("TEST-S-01", "test", "test"),
                self.intent_record("AUG-01", None, "test", "curated_augmentation"),
            ],
        }
        if include_unknown:
            entries["test"].append(self.intent_record("VAL-S-99", "dev", "dev"))
        for split, records in entries.items():
            (intent_root / f"{split}.jsonl").write_text(
                "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
                encoding="utf-8",
            )
        return workbook_path, intent_root

    @staticmethod
    def official_manifest(workbook_path: Path, removed_ids: list[str], **overrides) -> dict:
        manifest = {
            "datasetVersion": "2.0.0",
            "canonicalReady": True,
            "officialCount": 3,
            "sourceSplitCounts": {"train": 1, "dev": 1, "test": 1},
            "removedIds": removed_ids,
            "groundTruthWorkbook": workbook_path.name,
            "generator": {"name": "clarify_ground_truth_contracts", "version": "2.0.0"},
            "changeLedger": "contract-change-ledger.json",
            "artifactSha256": {
                "groundTruthWorkbook": hashlib.sha256(workbook_path.read_bytes()).hexdigest().upper(),
                "changeLedger": "A" * 64,
            },
            "changeCounts": {"answerChanges": 0, "questionClarifications": 0, "questionRemovals": len(removed_ids), "contractErrors": 0},
            "auditStatus": {"candidateReady": True, "evidenceComplete": 3, "evidenceErrors": 0, "auditErrors": 0},
        }
        manifest.update(overrides)
        return manifest

    def write_manifest(self, path: Path, manifest: dict) -> None:
        path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    def write_ledger(
        self,
        directory: Path,
        manifest: dict,
        clarifications: list[dict] | None = None,
        removed_ids: list[str] | None = None,
        answer_changes: list[str] | None = None,
    ) -> Path:
        """Write the change ledger referenced by ``manifest`` and anchor its hash.

        ``manifest["changeLedger"]`` stays the relative file name; the ledger
        lives next to the manifest file.  ``changeCounts`` is recomputed from
        the written entries so the fixture stays self-consistent.
        """
        entries: list[dict] = []
        for entry_id in answer_changes or []:
            entries.append(
                {
                    "id": entry_id,
                    "changeType": "ANSWER_CORRECTION",
                    "category": None,
                    "split": "train",
                    "difficulty": "简单",
                    "oldTextSha256": "A" * 64,
                    "newTextSha256": "B" * 64,
                    "metricCodes": None,
                    "dimensionMapping": None,
                }
            )
        for contract in clarifications or []:
            entries.append(
                {
                    "id": contract["id"],
                    "changeType": "QUESTION_CLARIFICATION",
                    "category": "PERFORMANCE",
                    "split": "train",
                    "difficulty": "复杂",
                    "oldTextSha256": "C" * 64,
                    "newTextSha256": "D" * 64,
                    "metricCodes": contract["metricCodes"],
                    "dimensionMapping": None,
                }
            )
        for entry_id in removed_ids or []:
            entries.append(
                {
                    "id": entry_id,
                    "changeType": "QUESTION_REMOVAL",
                    "category": None,
                    "split": "train",
                    "difficulty": "复杂",
                    "oldTextSha256": "E" * 64,
                    "newTextSha256": None,
                    "removedAnswerSha256": "F" * 64,
                    "metricCodes": None,
                    "dimensionMapping": None,
                }
            )
        ledger = {
            "generatorName": "clarify_ground_truth_contracts",
            "generatorVersion": "2.0.0",
            "count": len(entries),
            "entries": entries,
            "contractErrors": [],
        }
        ledger_path = directory / manifest["changeLedger"]
        ledger_path.write_text(json.dumps(ledger, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        manifest["artifactSha256"]["changeLedger"] = hashlib.sha256(ledger_path.read_bytes()).hexdigest().upper()
        manifest["changeCounts"] = {
            "answerChanges": len(answer_changes or []),
            "questionClarifications": len(clarifications or []),
            "questionRemovals": len(removed_ids or []),
            "contractErrors": 0,
        }
        return ledger_path

    def write_answer_fact_ledger(
        self, directory: Path, manifest: dict, entries: list[dict]
    ) -> Path:
        parent_manifest_sha = "B" * 64
        generator = {"name": "amend_official_answer_facts", "version": "1.0.0"}
        ledger = {
            "schemaVersion": "1.0",
            "generator": generator,
            "parentDatasetVersion": "2.0.0",
            "parentOfficialManifestSha256": parent_manifest_sha,
            "targetDatasetVersion": "2.0.1",
            "count": len(entries),
            "entries": entries,
        }
        ledger_path = directory / "answer-fact-ledger.json"
        ledger_path.write_text(
            json.dumps(ledger, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        manifest.update(
            {
                "releaseMode": "INCREMENTAL_ANSWER_FACT_CONTRACT",
                "datasetVersion": "2.0.1",
                "parent": {
                    "datasetVersion": "2.0.0",
                    "officialManifestSha256": parent_manifest_sha,
                },
                "answerFactLedger": ledger_path.name,
                "answerFactCount": len(entries),
                "answerFactGenerator": generator,
            }
        )
        manifest["artifactSha256"]["answerFactLedger"] = hashlib.sha256(
            ledger_path.read_bytes()
        ).hexdigest().upper()
        return ledger_path

    def test_manifest_projects_typed_answer_fact_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["TRAIN-S-02"])
            answer_facts = [
                {
                    "id": "deposit_value",
                    "value": 41.76,
                    "kind": "NUMBER",
                    "binding": {
                        "organizationCodes": ["ORG001"],
                        "metricCodes": ["ZB001"],
                        "dates": ["2025-12-31"],
                        "comparisonType": "POINT",
                    },
                    "formula": {
                        "operation": "DIRECT",
                        "operands": [
                            {"column": "metric_value", "where": {"metric_code": "ZB001"}}
                        ],
                    },
                }
            ]
            self.write_ledger(
                temp_path,
                manifest,
                removed_ids=["TRAIN-S-02"],
            )
            self.write_answer_fact_ledger(
                temp_path,
                manifest,
                [
                    {
                        "id": "TRAIN-S-01",
                        "split": "train",
                        "reason": "derived answer fact",
                        "answerFacts": answer_facts,
                        "goldSql": "SELECT 41.76 AS metric_value",
                        "sqlFeatures": ["ANSWER_FACT_CONTRACT"],
                    }
                ],
            )
            self.write_manifest(manifest_path, manifest)
            output_path = temp_path / "output"

            build_dataset(
                workbook_path,
                intent_root,
                output_path,
                official_manifest_path=manifest_path,
            )

            record = json.loads((output_path / "train.jsonl").read_text(encoding="utf-8").splitlines()[0])
            self.assertEqual(record["expected"]["answerFacts"], answer_facts)
            self.assertEqual(record["goldSqlOverride"], "SELECT 41.76 AS metric_value")
            self.assertEqual(record["goldSqlFeatures"], ["ANSWER_FACT_CONTRACT"])
            schema = json.loads((output_path / "schema.json").read_text(encoding="utf-8"))
            self.assertEqual(
                schema["properties"]["expected"]["properties"]["answerFacts"]["type"],
                "array",
            )

    def create_clarification_fixture(self, temp_path: Path) -> tuple[Path, Path]:
        """Workbook + intents with one clarify-style RANKING question.

        The clarified record (``TRAIN-C-01``) starts with an empty metric
        annotation (the pre-projection state) and must be overridden solely by
        the ledger contract; ``TRAIN-S-01`` is an ordinary record.
        """
        workbook_path = temp_path / "questions.xlsx"
        workbook = Workbook()
        sheet = workbook.active
        sheet.title = "问题答案清单"
        sheet.append(["问题编号", "问题类型", "问题难度", "问题描述", "问题结果"])
        sheet.append(["TRAIN-C-01", "训练集", "复杂", "请列出江苏省G市农商行在2025-11-30的主要经营指标及排名？", "存贷比81.21%，不良贷款率0.96%。"])
        sheet.append(["TRAIN-S-01", "训练集", "简单", "A行存款余额是多少？", "41.76亿元"])
        workbook.save(workbook_path)
        intent_root = temp_path / "bank_intent"
        intent_root.mkdir()
        clarified = self.intent_record("TRAIN-C-01", "train", "train")
        clarified["metrics"] = []
        clarified["intent"] = "RANKING"
        clarified["question"] = "请列出江苏省G市农商行在2025-11-30的主要经营指标及排名？"
        plain = self.intent_record("TRAIN-S-01", "train", "train")
        for split, records in (("train", [clarified, plain]), ("dev", []), ("test", [])):
            (intent_root / f"{split}.jsonl").write_text(
                "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
                encoding="utf-8",
            )
        return workbook_path, intent_root

    def test_manifest_authorizes_only_ledger_declared_removal(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["TRAIN-S-02"])
            self.write_ledger(temp_path, manifest, removed_ids=["TRAIN-S-02"])
            self.write_manifest(manifest_path, manifest)
            output_path = temp_path / "bank_nl2sql"

            report = build_dataset(workbook_path, intent_root, output_path, manifest_path)

            self.assertEqual(report["officialCount"], 3)
            self.assertEqual(report["version"], "2.0.0")
            self.assertEqual(report["sourceSplitCounts"], {"train": 1, "dev": 1, "test": 1})
            self.assertEqual(report["evaluationSplitCounts"], {"train": 1, "dev": 1, "test": 1})
            ids = {
                record["id"]
                for split in ("train", "dev", "test")
                for record in (
                    json.loads(line)
                    for line in (output_path / f"{split}.jsonl").read_text(encoding="utf-8").splitlines()
                )
            }
            self.assertEqual(ids, {"TRAIN-S-01", "VAL-S-01", "TEST-S-01"})
            self.assertNotIn("TRAIN-S-02", ids)
            self.assertEqual(validate_dataset(output_path)["result"], "PASS")

    def test_without_manifest_strict_unknown_intent_behavior_remains(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            output_path = temp_path / "bank_nl2sql"
            with self.assertRaisesRegex(DatasetBuildError, "Official intents absent from workbook"):
                build_dataset(workbook_path, intent_root, output_path)

    def test_manifest_workbook_hash_mismatch_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["TRAIN-S-02"])
            self.write_ledger(temp_path, manifest, removed_ids=["TRAIN-S-02"])
            manifest["artifactSha256"]["groundTruthWorkbook"] = "B" * 64
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "SHA-256 does not match"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_does_not_authorize_other_unknown_intent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path, include_unknown=True)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["TRAIN-S-02"])
            self.write_ledger(temp_path, manifest, removed_ids=["TRAIN-S-02"])
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "VAL-S-99"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_removed_id_requires_intent_annotation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["TRAIN-S-99"])
            self.write_ledger(temp_path, manifest, removed_ids=["TRAIN-S-99"])
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "absent from intents"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_removed_id_still_in_workbook_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["VAL-S-01"])
            self.write_ledger(temp_path, manifest, removed_ids=["VAL-S-01"])
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "still present in workbook"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_official_count_mismatch_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            # Manifest stays schema-self-consistent (officialCount == sum of
            # sourceSplitCounts == 4) so _load_official_manifest accepts it,
            # while the workbook holds only 3 questions; the dedicated
            # officialCount check in build_dataset then rejects it before the
            # sourceSplitCounts comparison is ever reached.
            self.write_manifest(
                manifest_path,
                self.official_manifest(
                    workbook_path,
                    ["TRAIN-S-02"],
                    officialCount=4,
                    sourceSplitCounts={"train": 2, "dev": 1, "test": 1},
                ),
            )
            with self.assertRaisesRegex(DatasetBuildError, "officialCount"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_clarification_projects_ledger_metric_contract(self) -> None:
        """最小合成 RED：账本声明的 5 个基础指标 + 1 个 derived 完整投影。"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_clarification_fixture(temp_path)
            manifest = self.official_manifest(
                workbook_path, [], officialCount=2, sourceSplitCounts={"train": 2, "dev": 0, "test": 0}
            )
            manifest_path = temp_path / "official-manifest.json"
            self.write_ledger(
                temp_path,
                manifest,
                clarifications=[
                    {
                        "id": "TRAIN-C-01",
                        "metricCodes": [
                            "ZB001",
                            "ZB002",
                            "ZB011",
                            "ZB012",
                            "ZB013",
                            {"derived": {"numerator": "ZB002", "denominator": "ZB001"}, "name": "存贷比"},
                        ],
                    }
                ],
            )
            self.write_manifest(manifest_path, manifest)
            output_path = temp_path / "bank_nl2sql"

            report = build_dataset(workbook_path, intent_root, output_path, manifest_path)

            self.assertEqual(report["officialCount"], 2)
            records = [
                json.loads(line)
                for line in (output_path / "train.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            clarified = next(record for record in records if record["id"] == "TRAIN-C-01")
            # 基础 metricCodes 按账本顺序投影为 metrics，只含账本声明
            self.assertEqual(
                clarified["normalizedIntent"]["metrics"],
                [{"code": "ZB001"}, {"code": "ZB002"}, {"code": "ZB011"}, {"code": "ZB012"}, {"code": "ZB013"}],
            )
            # derived 规格按账本顺序写入 derivedMetrics，保留完整规格
            self.assertEqual(
                clarified["normalizedIntent"]["derivedMetrics"],
                [
                    {
                        "metricCode": "DERIVED_ZB002_DIV_ZB001",
                        "numerator": "ZB002",
                        "denominator": "ZB001",
                        "name": "存贷比",
                    }
                ],
            )
            # 未声明的默认指标（_status_metrics 中的 ZB015/ZB016/ZB017 等）不得出现
            declared = {metric["code"] for metric in clarified["normalizedIntent"]["metrics"]}
            self.assertNotIn("ZB015", declared)
            self.assertNotIn("ZB016", declared)
            self.assertNotIn("ZB017", declared)
            self.assertEqual(clarified["expectedAction"], "EXECUTE")
            # 普通记录（无 ledger 契约）normalizedIntent 不变
            plain = next(record for record in records if record["id"] == "TRAIN-S-01")
            self.assertEqual(
                plain["normalizedIntent"]["metrics"],
                [{"code": "ZB001", "name": "各项存款余额", "matchedText": "存款余额"}],
            )
            self.assertNotIn("derivedMetrics", plain["normalizedIntent"])
            self.assertEqual(validate_dataset(output_path)["result"], "PASS")

    def test_answer_correction_entry_does_not_override_normalized_intent(self) -> None:
        """ANSWER_CORRECTION 只改答案，不得覆盖任何记录的指标契约。"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_clarification_fixture(temp_path)
            manifest = self.official_manifest(
                workbook_path, [], officialCount=2, sourceSplitCounts={"train": 2, "dev": 0, "test": 0}
            )
            manifest_path = temp_path / "official-manifest.json"
            self.write_ledger(
                temp_path,
                manifest,
                clarifications=[
                    {
                        "id": "TRAIN-C-01",
                        "metricCodes": [
                            "ZB001",
                            "ZB002",
                            "ZB011",
                            "ZB012",
                            "ZB013",
                            {"derived": {"numerator": "ZB002", "denominator": "ZB001"}, "name": "存贷比"},
                        ],
                    }
                ],
                answer_changes=["TRAIN-S-01"],
            )
            self.write_manifest(manifest_path, manifest)
            output_path = temp_path / "bank_nl2sql"

            build_dataset(workbook_path, intent_root, output_path, manifest_path)

            records = [
                json.loads(line)
                for line in (output_path / "train.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            corrected = next(record for record in records if record["id"] == "TRAIN-S-01")
            self.assertEqual(
                corrected["normalizedIntent"]["metrics"],
                [{"code": "ZB001", "name": "各项存款余额", "matchedText": "存款余额"}],
            )
            self.assertNotIn("derivedMetrics", corrected["normalizedIntent"])
            clarified = next(record for record in records if record["id"] == "TRAIN-C-01")
            self.assertEqual(len(clarified["normalizedIntent"]["metrics"]), 5)
            self.assertEqual(len(clarified["normalizedIntent"]["derivedMetrics"]), 1)

    def test_manifest_missing_ledger_file_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            self.write_manifest(manifest_path, self.official_manifest(workbook_path, ["TRAIN-S-02"]))
            with self.assertRaisesRegex(DatasetBuildError, "Change ledger does not exist"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_hash_mismatch_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["TRAIN-S-02"])
            ledger_path = self.write_ledger(temp_path, manifest, removed_ids=["TRAIN-S-02"])
            # 篡改账本字节但保持 manifest 锚定的旧哈希
            ledger_path.write_text(ledger_path.read_text(encoding="utf-8") + " ", encoding="utf-8")
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "Change ledger SHA-256 does not match"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_generator_mismatch_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["TRAIN-S-02"])
            ledger_path = self.write_ledger(temp_path, manifest, removed_ids=["TRAIN-S-02"])
            ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
            ledger["generatorName"] = "other_generator"
            ledger_path.write_text(json.dumps(ledger, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            # 同步重算账本哈希：内容契约（generator）仍必须拒绝
            manifest["artifactSha256"]["changeLedger"] = hashlib.sha256(ledger_path.read_bytes()).hexdigest().upper()
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "ledger.generatorName"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_change_counts_mismatch_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["TRAIN-S-02"])
            self.write_ledger(temp_path, manifest, removed_ids=["TRAIN-S-02"])
            manifest["changeCounts"]["questionClarifications"] = 1
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "QUESTION_CLARIFICATION=0 != manifest.changeCounts"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_removed_ids_mismatch_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path, include_unknown=True)
            manifest_path = temp_path / "official-manifest.json"
            manifest = self.official_manifest(workbook_path, ["TRAIN-S-02"])
            self.write_ledger(temp_path, manifest, removed_ids=["TRAIN-S-02"])
            # VAL-S-99 有 intent 注解且不在工作簿，可被账本授权删除；
            # 但 ledger 的 QUESTION_REMOVAL 条目只声明了 TRAIN-S-02。
            manifest["removedIds"] = ["VAL-S-99"]
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "QUESTION_REMOVAL IDs"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_clarification_id_absent_from_workbook_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path, include_removed_intent=False)
            manifest = self.official_manifest(workbook_path, [])
            manifest_path = temp_path / "official-manifest.json"
            self.write_ledger(
                temp_path,
                manifest,
                clarifications=[{"id": "VAL-S-99", "metricCodes": ["ZB001"]}],
            )
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "Ledger clarification IDs absent from workbook"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_clarification_empty_metric_codes_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path, include_removed_intent=False)
            manifest = self.official_manifest(workbook_path, [])
            manifest_path = temp_path / "official-manifest.json"
            self.write_ledger(
                temp_path,
                manifest,
                clarifications=[{"id": "TRAIN-S-01", "metricCodes": []}],
            )
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "metricCodes 非法"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_clarification_derived_operands_need_not_be_output_base_metrics(self) -> None:
        """derived 操作数是底层取数指标，无需出现在输出基础 metrics 集合中。

        账本 string metricCodes 只投影为基础 metrics；ZB001/ZB002 仅作为
        derived 操作数保留在 derivedMetrics，不得作为基础行输出。
        """
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path, include_removed_intent=False)
            manifest = self.official_manifest(workbook_path, [])
            manifest_path = temp_path / "official-manifest.json"
            self.write_ledger(
                temp_path,
                manifest,
                clarifications=[
                    {
                        "id": "TRAIN-S-01",
                        "metricCodes": [
                            "ZB011",
                            {"derived": {"numerator": "ZB002", "denominator": "ZB001"}, "name": "存贷比"},
                        ],
                    }
                ],
            )
            self.write_manifest(manifest_path, manifest)
            output_path = temp_path / "bank_nl2sql"

            build_dataset(workbook_path, intent_root, output_path, manifest_path)

            records = [
                json.loads(line)
                for line in (output_path / "train.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            clarified = next(record for record in records if record["id"] == "TRAIN-S-01")
            # 基础 metrics 只含账本 string 项，ZB001/ZB002 不进入输出基础集合
            self.assertEqual(clarified["normalizedIntent"]["metrics"], [{"code": "ZB011"}])
            self.assertNotIn("ZB001", [metric["code"] for metric in clarified["normalizedIntent"]["metrics"]])
            self.assertNotIn("ZB002", [metric["code"] for metric in clarified["normalizedIntent"]["metrics"]])
            # derived 规格单独保留操作数
            self.assertEqual(
                clarified["normalizedIntent"]["derivedMetrics"],
                [
                    {
                        "metricCode": "DERIVED_ZB002_DIV_ZB001",
                        "numerator": "ZB002",
                        "denominator": "ZB001",
                        "name": "存贷比",
                    }
                ],
            )
            self.assertEqual(validate_dataset(output_path)["result"], "PASS")

    def test_manifest_ledger_clarification_derived_invalid_operands_rejected(self) -> None:
        """非法指标代码与分子等于分母的 derived 契约必须 fail closed。"""
        invalid_cases = [
            (
                ["ZB001", {"derived": {"numerator": "NOT_A_CODE", "denominator": "ZB001"}, "name": "伪造比率"}],
                "derived numerator 'NOT_A_CODE' 不是合法指标代码",
            ),
            (
                ["ZB001", {"derived": {"numerator": "ZB002", "denominator": "NOT_A_CODE"}, "name": "伪造比率"}],
                "derived denominator 'NOT_A_CODE' 不是合法指标代码",
            ),
            (
                ["ZB001", {"derived": {"numerator": "ZB001", "denominator": "ZB001"}, "name": "自比"}],
                "derived numerator 与 denominator 必须不同",
            ),
        ]
        for metric_codes, expected_error in invalid_cases:
            with self.subTest(metric_codes=metric_codes), tempfile.TemporaryDirectory() as temp_dir:
                temp_path = Path(temp_dir)
                workbook_path, intent_root = self.create_workbook_and_intents(temp_path, include_removed_intent=False)
                manifest = self.official_manifest(workbook_path, [])
                manifest_path = temp_path / "official-manifest.json"
                self.write_ledger(
                    temp_path,
                    manifest,
                    clarifications=[{"id": "TRAIN-S-01", "metricCodes": metric_codes}],
                )
                self.write_manifest(manifest_path, manifest)
                with self.assertRaisesRegex(DatasetBuildError, expected_error):
                    build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_clarification_invalid_base_code_rejected(self) -> None:
        """string base metricCodes 必须为合法 ZB### 代码：空串与非法格式 fail closed。"""
        for metric_code in ("", "ZB01", "ZB0011", "zb001", "ZB001 ", "存款余额"):
            with self.subTest(metric_code=metric_code), tempfile.TemporaryDirectory() as temp_dir:
                temp_path = Path(temp_dir)
                workbook_path, intent_root = self.create_workbook_and_intents(temp_path, include_removed_intent=False)
                manifest = self.official_manifest(workbook_path, [])
                manifest_path = temp_path / "official-manifest.json"
                self.write_ledger(
                    temp_path,
                    manifest,
                    clarifications=[{"id": "TRAIN-S-01", "metricCodes": [metric_code]}],
                )
                self.write_manifest(manifest_path, manifest)
                with self.assertRaisesRegex(DatasetBuildError, "不是合法指标代码"):
                    build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_clarification_duplicate_base_codes_rejected(self) -> None:
        """重复的 string base metricCodes 必须 fail closed。"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path, include_removed_intent=False)
            manifest = self.official_manifest(workbook_path, [])
            manifest_path = temp_path / "official-manifest.json"
            self.write_ledger(
                temp_path,
                manifest,
                clarifications=[{"id": "TRAIN-S-01", "metricCodes": ["ZB001", "ZB001"]}],
            )
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "基础指标重复"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)

    def test_manifest_ledger_clarification_derived_only_rejected(self) -> None:
        """仅 derived 无 string base 的契约必须 fail closed（至少一个 base code）。"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            workbook_path, intent_root = self.create_workbook_and_intents(temp_path, include_removed_intent=False)
            manifest = self.official_manifest(workbook_path, [])
            manifest_path = temp_path / "official-manifest.json"
            self.write_ledger(
                temp_path,
                manifest,
                clarifications=[
                    {
                        "id": "TRAIN-S-01",
                        "metricCodes": [
                            {"derived": {"numerator": "ZB002", "denominator": "ZB001"}, "name": "存贷比"}
                        ],
                    }
                ],
            )
            self.write_manifest(manifest_path, manifest)
            with self.assertRaisesRegex(DatasetBuildError, "未声明任何基础指标"):
                build_dataset(workbook_path, intent_root, temp_path / "bank_nl2sql", manifest_path)


class OfficialV201RebuildMetadataTest(unittest.TestCase):
    """The deterministic rebuild must retain the v2.0.1 amendment provenance."""

    def test_incremental_release_projects_answer_amendment_metadata(self) -> None:
        repository = ROOT.parents[1]
        official_dir = ROOT / "official" / "2.0.1"
        workbook = official_dir / "bank-nl2sql-ground-truth-v2.0.1.xlsx"
        official_manifest = official_dir / "official-manifest.json"
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "dataset"
            report = build_dataset(
                workbook,
                repository / "evaluation" / "bank_intent",
                output,
                official_manifest,
            )
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))

        self.assertEqual(report["version"], "2.0.1")
        self.assertEqual(manifest["parentVersion"], "2.0.0")
        self.assertEqual(manifest["answerAmendment"]["count"], 6)
        self.assertEqual(
            manifest["answerAmendment"]["canonicalWorkbook"],
            "bank-nl2sql-ground-truth-v2.0.1.xlsx",
        )
        self.assertEqual(
            manifest["answerAmendment"]["canonicalWorkbookSha256"],
            "B19F2DF98CE4CD7A4D7B16B37C220CDB85047D24EB30360D08A37F59105B6706",
        )


if __name__ == "__main__":
    unittest.main()
