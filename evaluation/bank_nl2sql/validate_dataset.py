#!/usr/bin/env python3
"""Validate structural and split-integrity invariants for DATA-02."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any


EVALUATION_SPLITS = ("train", "dev", "test")
REQUIRED_FIELDS = {
    "id",
    "source",
    "sourceSplit",
    "split",
    "splitReason",
    "difficulty",
    "question",
    "normalizedIntent",
    "templateGroup",
    "expectedAction",
    "s2sql",
    "sql",
    "sqlFeatures",
    "expected",
    "errorCategory",
}
EXPECTED_FIELDS = {"answerText", "columns", "rows", "unit", "numericTolerance", "orderSensitive"}


class DatasetValidationError(ValueError):
    """Raised when DATA-02 output violates a required invariant."""


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise DatasetValidationError(f"Missing dataset file: {path.name}")
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise DatasetValidationError(f"Invalid JSON: {path.name}:{line_number}") from error
        if not isinstance(record, dict):
            raise DatasetValidationError(f"Record must be an object: {path.name}:{line_number}")
        records.append(record)
    return records


def _validate_record(record: dict[str, Any], filename: str, index: int, official: bool) -> None:
    missing = REQUIRED_FIELDS - set(record)
    if missing:
        raise DatasetValidationError(f"{filename}:{index} missing fields: {sorted(missing)}")
    if not isinstance(record["id"], str) or not record["id"]:
        raise DatasetValidationError(f"{filename}:{index} has invalid id")
    if record["split"] not in EVALUATION_SPLITS:
        raise DatasetValidationError(f"{filename}:{index} has invalid split")
    if record["expectedAction"] not in {"EXECUTE", "CLARIFY", "REFUSE"}:
        raise DatasetValidationError(f"{filename}:{index} has invalid expectedAction")
    if not isinstance(record["expected"], dict) or EXPECTED_FIELDS - set(record["expected"]):
        raise DatasetValidationError(f"{filename}:{index} has invalid expected object")
    if official:
        if record["sourceSplit"] not in EVALUATION_SPLITS:
            raise DatasetValidationError(f"{filename}:{index} official record has invalid sourceSplit")
        if record["expectedAction"] != "EXECUTE":
            raise DatasetValidationError(f"{filename}:{index} official record must execute")
        if record["splitReason"] not in {"source_assignment", "template_isolation"}:
            raise DatasetValidationError(f"{filename}:{index} has invalid official split reason")
        moved = record["split"] != record["sourceSplit"]
        if moved != (record["splitReason"] == "template_isolation"):
            raise DatasetValidationError(f"{filename}:{index} split reason contradicts source split")
    elif record["sourceSplit"] is not None or record["splitReason"] != "augmentation":
        raise DatasetValidationError(f"{filename}:{index} augmentation is not isolated")


def _template_overlap(records_by_split: dict[str, list[dict[str, Any]]]) -> dict[str, list[str]]:
    groups = {
        split: {record["templateGroup"] for record in records if record.get("templateGroup")}
        for split, records in records_by_split.items()
    }
    return {
        "trainDev": sorted(groups["train"] & groups["dev"]),
        "trainTest": sorted(groups["train"] & groups["test"]),
        "devTest": sorted(groups["dev"] & groups["test"]),
    }


def validate_dataset(output_path: Path | str) -> dict[str, Any]:
    """Validate output files and return a compact PASS report."""

    output_path = Path(output_path).resolve()
    manifest_path = output_path / "manifest.json"
    schema_path = output_path / "schema.json"
    if not manifest_path.is_file() or not schema_path.is_file():
        raise DatasetValidationError("manifest.json and schema.json are required")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    if schema.get("title") != "EcoMatch bank NL2SQL annotated sample":
        raise DatasetValidationError("Unexpected schema title")

    records_by_split = {split: _read_jsonl(output_path / f"{split}.jsonl") for split in EVALUATION_SPLITS}
    augmentation_records = _read_jsonl(output_path / "augmentation.jsonl")
    ids: set[str] = set()
    source_split_counts: Counter[str] = Counter()
    for split, records in records_by_split.items():
        for index, record in enumerate(records, start=1):
            _validate_record(record, f"{split}.jsonl", index, official=True)
            if record["split"] != split:
                raise DatasetValidationError(f"{split}.jsonl:{index} split does not match containing file")
            if record["id"] in ids:
                raise DatasetValidationError(f"Duplicate official id: {record['id']}")
            ids.add(record["id"])
            source_split_counts[record["sourceSplit"]] += 1
    for index, record in enumerate(augmentation_records, start=1):
        _validate_record(record, "augmentation.jsonl", index, official=False)
        if record["id"] in ids:
            raise DatasetValidationError(f"Augmentation reuses official id: {record['id']}")
        ids.add(record["id"])

    evaluation_counts = {split: len(records_by_split[split]) for split in EVALUATION_SPLITS}
    expected_source_counts = {split: source_split_counts[split] for split in EVALUATION_SPLITS}
    overlap = _template_overlap(records_by_split)
    if any(overlap.values()):
        raise DatasetValidationError(f"Template leakage across official splits: {overlap}")
    if manifest.get("officialCount") != sum(evaluation_counts.values()):
        raise DatasetValidationError("Manifest officialCount does not match JSONL files")
    if manifest.get("augmentationCount") != len(augmentation_records):
        raise DatasetValidationError("Manifest augmentationCount does not match JSONL file")
    if manifest.get("sourceSplitCounts") != expected_source_counts:
        raise DatasetValidationError("Manifest sourceSplitCounts does not match official records")
    if manifest.get("evaluationSplitCounts") != evaluation_counts:
        raise DatasetValidationError("Manifest evaluationSplitCounts does not match official records")
    if manifest.get("templateOverlap") != overlap:
        raise DatasetValidationError("Manifest templateOverlap does not match official records")

    return {
        "result": "PASS",
        "officialCount": sum(evaluation_counts.values()),
        "augmentationCount": len(augmentation_records),
        "sourceSplitCounts": expected_source_counts,
        "evaluationSplitCounts": evaluation_counts,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate the DATA-02 bank NL2SQL dataset")
    parser.add_argument("output", type=Path, help="Directory containing DATA-02 JSONL output")
    args = parser.parse_args()
    print(json.dumps(validate_dataset(args.output), ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
