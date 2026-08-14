#!/usr/bin/env python3
"""Create an immutable answer-fact child release from an official package."""

from __future__ import annotations

import argparse
import json
import shutil
import tempfile
from pathlib import Path
from typing import Any

from amend_official_ground_truth import (
    MANIFEST_NAME,
    OfficialAmendmentError,
    _read_json,
    _validate_parent,
    _write_json,
    _write_sidecar,
    sha256_file,
)
from answer_facts import validate_answer_facts
from build_dataset import _load_workbook_questions
from fact_contract_v3 import validate_typed_facts_against_answer


GENERATOR = {"name": "amend_official_answer_facts", "version": "1.0.0"}
LEDGER_NAME = "answer-fact-ledger.json"
AUDIT_NAME = "answer-fact-audit-summary.json"


def _load_spec(
    spec_path: Path,
    *,
    parent_version: str,
    workbook_path: Path,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    spec = _read_json(spec_path, "答案事实清单")
    if spec.get("schemaVersion") != "1.0":
        raise OfficialAmendmentError("答案事实清单 schemaVersion 必须为 1.0")
    if spec.get("parentVersion") != parent_version:
        raise OfficialAmendmentError("答案事实清单 parentVersion 与父正式包不一致")
    target_version = spec.get("targetVersion")
    if not isinstance(target_version, str) or not target_version or target_version == parent_version:
        raise OfficialAmendmentError("答案事实清单 targetVersion 非法")
    contracts = spec.get("contracts")
    if not isinstance(contracts, list) or not contracts:
        raise OfficialAmendmentError("答案事实清单 contracts 必须为非空列表")
    questions = {item["id"]: item for item in _load_workbook_questions(workbook_path)}
    coverage_mode = spec.get("coverageMode", "INCREMENTAL")
    if coverage_mode not in {"INCREMENTAL", "FULL_OFFICIAL"}:
        raise OfficialAmendmentError("答案事实清单 coverageMode 非法")
    seen: set[str] = set()
    normalized: list[dict[str, Any]] = []
    for contract in contracts:
        if not isinstance(contract, dict):
            raise OfficialAmendmentError("答案事实清单 contract 必须为对象")
        sample_id = contract.get("id")
        if not isinstance(sample_id, str) or not sample_id or sample_id in seen:
            raise OfficialAmendmentError("答案事实清单 ID 非法或重复")
        seen.add(sample_id)
        question = questions.get(sample_id)
        if question is None:
            raise OfficialAmendmentError(f"答案事实 ID 不在父工作簿: {sample_id}")
        if coverage_mode != "FULL_OFFICIAL" and question["sourceSplit"] not in {"train", "dev"}:
            raise OfficialAmendmentError(f"答案事实禁止修改 test: {sample_id}")
        reason = contract.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            raise OfficialAmendmentError(f"答案事实 reason 非法: {sample_id}")
        answer_facts = contract.get("answerFacts")
        _, errors = validate_answer_facts(
            answer_facts,
            {"columns": [], "rows": [], "numericTolerance": 1e-6},
            default_tolerance=1e-6,
            require_result_match=False,
        )
        if errors:
            raise OfficialAmendmentError(f"答案事实结构非法 {sample_id}: {errors}")
        if coverage_mode == "FULL_OFFICIAL":
            source_errors = validate_typed_facts_against_answer(
                question["question"], question["answerText"], answer_facts
            )
            if source_errors:
                raise OfficialAmendmentError(
                    f"答案事实未与父工作簿答案对齐 {sample_id}: {source_errors}"
                )
        entry = {
            "id": sample_id,
            "split": question["sourceSplit"],
            "reason": reason.strip(),
            "answerFacts": answer_facts,
        }
        gold_sql = contract.get("goldSql")
        if gold_sql is not None:
            sql = gold_sql.strip() if isinstance(gold_sql, str) else ""
            features = contract.get("sqlFeatures")
            if (
                not sql
                or not sql.upper().startswith(("SELECT ", "WITH "))
                or ";" in sql
                or not isinstance(features, list)
                or not features
                or not all(isinstance(feature, str) and feature.strip() for feature in features)
            ):
                raise OfficialAmendmentError(f"答案事实 goldSql/sqlFeatures 非法: {sample_id}")
            entry["goldSql"] = sql
            entry["sqlFeatures"] = features
        normalized.append(entry)
    if coverage_mode == "FULL_OFFICIAL" and seen != set(questions):
        missing = sorted(set(questions) - seen)
        extra = sorted(seen - set(questions))
        raise OfficialAmendmentError(
            f"FULL_OFFICIAL 必须完整覆盖父工作簿: missing={missing}, extra={extra}"
        )
    normalized.sort(key=lambda item: item["id"])
    return spec, normalized


def amend_official_answer_facts(
    parent_dir: Path | str,
    spec_path: Path | str,
    output_dir: Path | str,
    *,
    update_current: bool = False,
) -> dict[str, Any]:
    parent_dir = Path(parent_dir).resolve()
    spec_path = Path(spec_path).resolve()
    output_dir = Path(output_dir).resolve()
    if output_dir.exists():
        raise OfficialAmendmentError(f"输出目录已存在，拒绝覆盖: {output_dir}")
    parent_manifest, parent_workbook, parent_ledger, parent_audit, parent_manifest_sha = (
        _validate_parent(parent_dir)
    )
    spec, contracts = _load_spec(
        spec_path,
        parent_version=str(parent_manifest["datasetVersion"]),
        workbook_path=parent_workbook,
    )
    target_version = str(spec["targetVersion"])
    if output_dir.name != target_version:
        raise OfficialAmendmentError("输出目录名必须等于 targetVersion")
    output_dir.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="official-answer-facts-", dir=output_dir.parent) as temp_dir:
        stage = Path(temp_dir) / target_version
        stage.mkdir()
        # The workbook is inherited byte-for-byte; preserve its name so frozen
        # test records also retain identical source metadata across the child release.
        workbook = stage / parent_workbook.name
        ledger = stage / parent_ledger.name
        audit = stage / AUDIT_NAME
        for source, destination in (
            (parent_workbook, workbook),
            (parent_ledger, ledger),
        ):
            shutil.copy2(source, destination)
            _write_sidecar(destination)

        fact_ledger = stage / LEDGER_NAME
        _write_json(
            fact_ledger,
            {
                "schemaVersion": "1.0",
                "generator": GENERATOR,
                "parentDatasetVersion": parent_manifest["datasetVersion"],
                "parentOfficialManifestSha256": parent_manifest_sha,
                "targetDatasetVersion": target_version,
                "coverageMode": spec.get("coverageMode", "INCREMENTAL"),
                "specSha256": sha256_file(spec_path),
                "count": len(contracts),
                "entries": contracts,
            },
        )
        _write_sidecar(fact_ledger)
        _write_json(
            audit,
            {
                "schemaVersion": "1.0",
                "datasetVersion": target_version,
                "parentDatasetVersion": parent_manifest["datasetVersion"],
                "answerFactCount": len(contracts),
                "answerFactCoverageMode": spec.get("coverageMode", "INCREMENTAL"),
                "verifiedIds": [contract["id"] for contract in contracts],
                "parentWorkbookSha256": sha256_file(parent_workbook),
                "candidateWorkbookSha256": sha256_file(workbook),
                "workbookChanged": False,
                "testSplitChanged": False,
                "canonicalReady": True,
                "auditErrors": 0,
            },
        )
        _write_sidecar(audit)
        manifest = {
            "schemaVersion": "2.2",
            "releaseMode": (
                "FULL_OFFICIAL_ANSWER_FACT_CONTRACT"
                if spec.get("coverageMode") == "FULL_OFFICIAL"
                else "INCREMENTAL_ANSWER_FACT_CONTRACT"
            ),
            "datasetVersion": target_version,
            "parent": {
                "datasetVersion": parent_manifest["datasetVersion"],
                "officialManifestSha256": parent_manifest_sha,
                "groundTruthWorkbookSha256": sha256_file(parent_workbook),
                "finalAuditSummarySha256": sha256_file(parent_audit),
            },
            "canonicalReady": True,
            "officialCount": parent_manifest["officialCount"],
            "sourceSplitCounts": parent_manifest["sourceSplitCounts"],
            "removedIds": parent_manifest["removedIds"],
            "sourceSha256": parent_manifest.get("sourceSha256"),
            "factRegionSha256": parent_manifest["factRegionSha256"],
            "generator": parent_manifest["generator"],
            "changeLedger": ledger.name,
            "changeCounts": parent_manifest["changeCounts"],
            "answerFactLedger": fact_ledger.name,
            "answerFactCount": len(contracts),
            "answerFactCoverageMode": spec.get("coverageMode", "INCREMENTAL"),
            "answerFactGenerator": GENERATOR,
            "groundTruthWorkbook": workbook.name,
            "finalAuditSummary": audit.name,
            "artifactSha256": {
                "groundTruthWorkbook": sha256_file(workbook),
                "changeLedger": sha256_file(ledger),
                "answerFactLedger": sha256_file(fact_ledger),
                "finalAuditSummary": sha256_file(audit),
            },
            "auditStatus": {
                "auditErrors": 0,
                "candidateReady": True,
                "workbookChanged": False,
                "testSplitChanged": False,
            },
        }
        manifest_path = stage / MANIFEST_NAME
        _write_json(manifest_path, manifest)
        _write_sidecar(manifest_path)
        current_temp = Path(temp_dir) / "CURRENT.json"
        if update_current:
            _write_json(
                current_temp,
                {
                    "currentVersion": target_version,
                    "directory": target_version,
                    "groundTruthWorkbook": f"{target_version}/{workbook.name}",
                    "officialManifest": f"{target_version}/{MANIFEST_NAME}",
                },
            )
        stage.replace(output_dir)
        if update_current:
            current_temp.replace(output_dir.parent / "CURRENT.json")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Create an immutable answer-fact child release")
    parser.add_argument("--parent", type=Path, required=True)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--update-current", action="store_true")
    args = parser.parse_args()
    print(
        json.dumps(
            amend_official_answer_facts(
                args.parent, args.spec, args.output, update_current=args.update_current
            ),
            ensure_ascii=False,
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
