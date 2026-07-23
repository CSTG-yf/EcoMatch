#!/usr/bin/env python3
"""Build the DATA-02 NL2SQL annotated dataset from frozen source inputs.

The competition workbook remains the source of truth for the 200 official
questions, their source splits, and their expected answers.  The separately
curated intent data supplies semantic annotations and the template-isolated
evaluation split.  Curated augmentations are intentionally emitted to a
separate file, so they can never alter the official benchmark score.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

from openpyxl import load_workbook


QUESTION_SHEET = "问题答案清单"
QUESTION_HEADERS = ("问题编号", "问题类型", "问题难度", "问题描述", "问题结果")
SOURCE_SPLITS = {"训练集": "train", "验证集": "dev", "测试集": "test"}
EVALUATION_SPLITS = ("train", "dev", "test")
INTENT_FILES = ("train.jsonl", "dev.jsonl", "test.jsonl")

SCHEMA: dict[str, Any] = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "title": "EcoMatch bank NL2SQL annotated sample",
    "type": "object",
    "required": [
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
    ],
    "properties": {
        "id": {"type": "string", "minLength": 1},
        "sourceSplit": {"type": ["string", "null"], "enum": ["train", "dev", "test", None]},
        "split": {"type": "string", "enum": ["train", "dev", "test"]},
        "expectedAction": {"type": "string", "enum": ["EXECUTE", "CLARIFY", "REFUSE"]},
        "s2sql": {"type": ["string", "null"]},
        "sql": {"type": ["string", "null"]},
        "expected": {
            "type": "object",
            "required": ["answerText", "columns", "rows", "unit", "numericTolerance", "orderSensitive"],
        },
    },
}


class DatasetBuildError(ValueError):
    """Raised when a frozen DATA-02 input violates its contract."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _as_text(value: Any, field: str, row_number: int) -> str:
    if value is None or not str(value).strip():
        raise DatasetBuildError(f"{QUESTION_SHEET} row {row_number}: {field} must not be empty")
    return str(value).strip()


def _load_workbook_questions(workbook_path: Path) -> list[dict[str, str]]:
    workbook = load_workbook(workbook_path, read_only=True, data_only=True)
    try:
        if QUESTION_SHEET not in workbook.sheetnames:
            raise DatasetBuildError(f"Missing worksheet: {QUESTION_SHEET}")
        sheet = workbook[QUESTION_SHEET]
        rows = sheet.iter_rows(values_only=True)
        header = tuple(str(value).strip() if value is not None else "" for value in next(rows, ()))
        if header[: len(QUESTION_HEADERS)] != QUESTION_HEADERS:
            raise DatasetBuildError(f"Unexpected {QUESTION_SHEET} headers: {header}")

        questions: list[dict[str, str]] = []
        seen_ids: set[str] = set()
        for row_number, row in enumerate(rows, start=2):
            if all(value is None for value in row):
                continue
            if len(row) < len(QUESTION_HEADERS):
                raise DatasetBuildError(f"{QUESTION_SHEET} row {row_number}: incomplete row")
            sample_id = _as_text(row[0], "问题编号", row_number)
            if sample_id in seen_ids:
                raise DatasetBuildError(f"Duplicate workbook question id: {sample_id}")
            question_type = _as_text(row[1], "问题类型", row_number)
            if question_type not in SOURCE_SPLITS:
                raise DatasetBuildError(f"Unknown 问题类型 at row {row_number}: {question_type}")
            seen_ids.add(sample_id)
            questions.append(
                {
                    "id": sample_id,
                    "sourceSplit": SOURCE_SPLITS[question_type],
                    "difficulty": _as_text(row[2], "问题难度", row_number),
                    "question": _as_text(row[3], "问题描述", row_number),
                    "answerText": _as_text(row[4], "问题结果", row_number),
                    "rowNumber": str(row_number),
                }
            )
        if not questions:
            raise DatasetBuildError(f"{QUESTION_SHEET} contains no questions")
        return questions
    finally:
        workbook.close()


def _read_jsonl(path: Path) -> Iterable[dict[str, Any]]:
    if not path.is_file():
        raise DatasetBuildError(f"Missing intent input: {path}")
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise DatasetBuildError(f"Invalid JSON in {path}:{line_number}") from error
        if not isinstance(value, dict):
            raise DatasetBuildError(f"Intent sample must be an object: {path}:{line_number}")
        yield value


def _load_intents(intent_root: Path) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]]]:
    official: dict[str, dict[str, Any]] = {}
    augmentations: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for filename in INTENT_FILES:
        for record in _read_jsonl(intent_root / filename):
            sample_id = str(record.get("id", "")).strip()
            if not sample_id:
                raise DatasetBuildError(f"Intent sample in {filename} has no id")
            if sample_id in seen_ids:
                raise DatasetBuildError(f"Duplicate intent id: {sample_id}")
            seen_ids.add(sample_id)
            if record.get("source") == "competition_workbook":
                source_split = record.get("sourceSplit")
                split = record.get("split")
                if source_split not in EVALUATION_SPLITS:
                    raise DatasetBuildError(f"Official intent {sample_id} has invalid sourceSplit: {source_split}")
                if split not in EVALUATION_SPLITS:
                    raise DatasetBuildError(f"Official intent {sample_id} has invalid split: {split}")
                official[sample_id] = record
            else:
                augmentations.append(record)
    return official, augmentations


def _normalized_intent(record: dict[str, Any]) -> dict[str, Any]:
    return {
        "scene": record.get("scene"),
        "intent": record.get("intent"),
        "metrics": record.get("metrics", []),
        "dimensions": record.get("dimensions", []),
        "time": record.get("time", {}),
        "organizations": record.get("organizations", []),
        "filters": record.get("filters", []),
        "linguisticFeatures": record.get("linguisticFeatures", []),
        "clarificationExpected": bool(record.get("clarificationExpected", False)),
        "referenceDate": record.get("referenceDate"),
    }


def _expected(answer_text: str | None) -> dict[str, Any]:
    return {
        "answerText": answer_text,
        "columns": [],
        "rows": [],
        "unit": None,
        "numericTolerance": None,
        "orderSensitive": False,
    }


def _official_record(question: dict[str, str], intent: dict[str, Any], workbook_name: str) -> dict[str, Any]:
    sample_id = question["id"]
    if intent.get("sourceSplit") != question["sourceSplit"]:
        raise DatasetBuildError(
            f"{sample_id}: workbook source split {question['sourceSplit']} does not match intent sourceSplit {intent.get('sourceSplit')}"
        )
    split = intent["split"]
    return {
        "id": sample_id,
        "source": {
            "kind": "competition_workbook",
            "workbook": workbook_name,
            "rowNumber": int(question["rowNumber"]),
            "intentDataset": "bank_intent",
        },
        "sourceSplit": question["sourceSplit"],
        "split": split,
        "splitReason": "source_assignment" if split == question["sourceSplit"] else "template_isolation",
        "difficulty": question["difficulty"],
        "question": question["question"],
        "normalizedIntent": _normalized_intent(intent),
        "templateGroup": intent.get("templateGroup"),
        "expectedAction": "EXECUTE",
        "s2sql": None,
        "sql": None,
        "sqlFeatures": [],
        "expected": _expected(question["answerText"]),
        "errorCategory": None,
    }


def _augmentation_record(intent: dict[str, Any]) -> dict[str, Any]:
    sample_id = str(intent["id"])
    split = intent.get("split")
    if split not in EVALUATION_SPLITS:
        raise DatasetBuildError(f"Augmentation {sample_id} has invalid split: {split}")
    clarify = bool(intent.get("clarificationExpected", False))
    return {
        "id": sample_id,
        "source": {"kind": intent.get("source"), "intentDataset": "bank_intent"},
        "sourceSplit": None,
        "split": split,
        "splitReason": "augmentation",
        "difficulty": intent.get("difficulty"),
        "question": intent.get("question"),
        "normalizedIntent": _normalized_intent(intent),
        "templateGroup": intent.get("templateGroup"),
        "expectedAction": "CLARIFY" if clarify else "EXECUTE",
        "s2sql": None,
        "sql": None,
        "sqlFeatures": [],
        "expected": _expected(None if clarify else intent.get("answer")),
        "errorCategory": "AMBIGUOUS_REQUEST" if clarify else None,
    }


def _write_jsonl(path: Path, records: Iterable[dict[str, Any]]) -> None:
    payload = "".join(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n" for record in records)
    path.write_text(payload, encoding="utf-8")


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


def build_dataset(workbook_path: Path | str, intent_root: Path | str, output_path: Path | str) -> dict[str, Any]:
    """Create official and augmentation JSONL files and return their manifest."""

    workbook_path = Path(workbook_path).resolve()
    intent_root = Path(intent_root).resolve()
    output_path = Path(output_path).resolve()
    if not workbook_path.is_file():
        raise DatasetBuildError(f"Workbook does not exist: {workbook_path}")
    questions = _load_workbook_questions(workbook_path)
    official_intents, raw_augmentations = _load_intents(intent_root)

    question_ids = {question["id"] for question in questions}
    unknown_intents = sorted(set(official_intents) - question_ids)
    missing_intents = sorted(question_ids - set(official_intents))
    if unknown_intents:
        raise DatasetBuildError(f"Official intents absent from workbook: {unknown_intents}")
    if missing_intents:
        raise DatasetBuildError(f"Workbook questions absent from intents: {missing_intents}")

    records_by_split: dict[str, list[dict[str, Any]]] = {split: [] for split in EVALUATION_SPLITS}
    for question in questions:
        record = _official_record(question, official_intents[question["id"]], workbook_path.name)
        records_by_split[record["split"]].append(record)
    for records in records_by_split.values():
        records.sort(key=lambda item: item["id"])

    augmentations = sorted((_augmentation_record(record) for record in raw_augmentations), key=lambda item: item["id"])
    source_split_counts = Counter(question["sourceSplit"] for question in questions)
    evaluation_split_counts = {split: len(records_by_split[split]) for split in EVALUATION_SPLITS}
    reassigned = [
        {"id": record["id"], "sourceSplit": record["sourceSplit"], "split": record["split"]}
        for split_records in records_by_split.values()
        for record in split_records
        if record["splitReason"] == "template_isolation"
    ]
    manifest = {
        "version": "0.1.0",
        "sourceWorkbook": workbook_path.name,
        "sourceSha256": _sha256(workbook_path),
        "officialCount": len(questions),
        "augmentationCount": len(augmentations),
        "sourceSplitCounts": {split: source_split_counts[split] for split in EVALUATION_SPLITS},
        "evaluationSplitCounts": evaluation_split_counts,
        "reassignedForTemplateIsolation": sorted(reassigned, key=lambda item: item["id"]),
        "templateOverlap": _template_overlap(records_by_split),
    }

    output_path.mkdir(parents=True, exist_ok=True)
    for split in EVALUATION_SPLITS:
        _write_jsonl(output_path / f"{split}.jsonl", records_by_split[split])
    _write_jsonl(output_path / "augmentation.jsonl", augmentations)
    (output_path / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (output_path / "schema.json").write_text(
        json.dumps(SCHEMA, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the DATA-02 bank NL2SQL dataset")
    parser.add_argument("workbook", type=Path, help="Competition workbook containing 问题答案清单")
    parser.add_argument("--intent-root", type=Path, required=True, help="Directory containing bank_intent JSONL files")
    parser.add_argument("--output", type=Path, required=True, help="Output directory for the annotated dataset")
    args = parser.parse_args()
    print(json.dumps(build_dataset(args.workbook, args.intent_root, args.output), ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
