#!/usr/bin/env python3
"""Apply an official answer-fact child release without rewriting frozen test rows."""

from __future__ import annotations

import argparse
import json
import shutil
import tempfile
from pathlib import Path
from typing import Any

from amend_official_ground_truth import _read_json, _validate_parent, sha256_file
from build_dataset import _load_answer_fact_contracts


def _records(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def _write_records(path: Path, records: list[dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n" for record in records),
        encoding="utf-8",
        newline="\n",
    )


def amend_derived_answer_facts(
    parent_dataset_dir: Path | str,
    child_official_dir: Path | str,
    output_dir: Path | str,
) -> dict[str, Any]:
    parent_dataset_dir = Path(parent_dataset_dir).resolve()
    child_official_dir = Path(child_official_dir).resolve()
    output_dir = Path(output_dir).resolve()
    if output_dir.exists():
        raise ValueError(f"output already exists: {output_dir}")
    parent_manifest = _read_json(parent_dataset_dir / "manifest.json", "parent dataset manifest")
    child_manifest, child_workbook, _, _, child_manifest_sha = _validate_parent(child_official_dir)
    if child_manifest.get("releaseMode") != "INCREMENTAL_ANSWER_FACT_CONTRACT":
        raise ValueError("child official package is not an answer-fact release")
    if parent_manifest.get("version") != child_manifest.get("parent", {}).get("datasetVersion"):
        raise ValueError("derived parent version does not match official parent")
    contracts = _load_answer_fact_contracts(child_manifest, child_official_dir)
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="derived-answer-facts-", dir=output_dir.parent) as temp_dir:
        stage = Path(temp_dir) / "dataset"
        stage.mkdir()
        seen: set[str] = set()
        for split in ("train", "dev"):
            records = _records(parent_dataset_dir / f"{split}.jsonl")
            for record in records:
                contract = contracts.get(record.get("id"))
                if contract is None:
                    continue
                seen.add(str(record["id"]))
                record["expected"]["answerFacts"] = contract["answerFacts"]
                if "goldSql" in contract:
                    record["goldSqlOverride"] = contract["goldSql"]
                    record["goldSqlFeatures"] = contract["sqlFeatures"]
            _write_records(stage / f"{split}.jsonl", records)
        if seen != set(contracts):
            raise ValueError(f"answer-fact contracts absent from train/dev: {sorted(set(contracts) - seen)}")
        for name in ("test.jsonl", "augmentation.jsonl", "schema.json", "gold_manifest.json"):
            shutil.copy2(parent_dataset_dir / name, stage / name)
        manifest = json.loads(json.dumps(parent_manifest))
        manifest["version"] = child_manifest["datasetVersion"]
        manifest["parentVersion"] = child_manifest["parent"]["datasetVersion"]
        manifest["answerFactContract"] = {
            "count": child_manifest["answerFactCount"],
            "officialManifestSha256": child_manifest_sha,
            "ledgerSha256": child_manifest["artifactSha256"]["answerFactLedger"],
            "canonicalWorkbook": child_manifest["groundTruthWorkbook"],
            "canonicalWorkbookSha256": sha256_file(child_workbook),
        }
        (stage / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        stage.replace(output_dir)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--parent-dataset", type=Path, required=True)
    parser.add_argument("--child-official", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = amend_derived_answer_facts(
        args.parent_dataset, args.child_official, args.output
    )
    print(json.dumps({"version": manifest["version"], "answerFactCount": manifest["answerFactContract"]["count"]}))


if __name__ == "__main__":
    main()
