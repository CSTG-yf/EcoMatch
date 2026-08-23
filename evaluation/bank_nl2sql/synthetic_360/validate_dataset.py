#!/usr/bin/env python3
"""Validate synthetic point-query gold SQL and blind-data isolation."""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from collections import defaultdict
from contextlib import closing
from pathlib import Path
from typing import Any


class DatasetValidationError(ValueError):
    pass


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_report(path: Path, report: dict[str, Any]) -> None:
    """Write canonical LF JSON bytes on every supported platform."""
    path.write_bytes((json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


def validate_dataset(release_dir: Path) -> dict[str, Any]:
    release_dir = release_dir.resolve()
    manifest = json.loads((release_dir / "dataset-manifest.json").read_text(encoding="utf-8"))
    if manifest.get("status") != "SYNTHETIC_CANDIDATE" or manifest.get("dataOrigin") != "SYNTHETIC" or manifest.get("officialInputs") != []:
        raise DatasetValidationError("dataset must remain explicitly synthetic and non-official")
    records = _jsonl(release_dir / "questions.jsonl")
    blind = _jsonl(release_dir / "questions_blind.jsonl")
    expected = _jsonl(release_dir / "expected.jsonl")
    if len(records) != 360 or len(blind) != len(records) or len(expected) != len(records):
        raise DatasetValidationError("question/expected counts must all equal 360")
    if len({record["id"] for record in records}) != 360 or len({record["metricCode"] for record in records}) != 360:
        raise DatasetValidationError("question ids and metric coverage must be unique")
    record_by_id = {record["id"]: record for record in records}
    expected_by_id = {record["id"]: record for record in expected}
    if set(expected_by_id) != set(record_by_id):
        raise DatasetValidationError("expected ids do not align with questions")
    for record in records:
        if record["dataOrigin"] != "SYNTHETIC" or record["queryType"] != "POINT_QUERY" or not record["goldSql"].lstrip().upper().startswith("SELECT "):
            raise DatasetValidationError(f"question contract mismatch: {record['id']}")
        if record["expected"] != expected_by_id[record["id"]]["expected"]:
            raise DatasetValidationError(f"expected row mismatch: {record['id']}")
    for record in blind:
        if set(record) != {"id", "question"} or record["id"] not in record_by_id or record["question"] != record_by_id[record["id"]]["question"]:
            raise DatasetValidationError("blind file must contain only aligned id/question pairs")
    metric_splits: dict[str, set[str]] = defaultdict(set)
    with closing(sqlite3.connect(release_dir / "bank.sqlite")) as connection:
        for record in records:
            metric_splits[record["metricCode"]].add(record["split"])
            cursor = connection.execute(record["goldSql"])
            actual = {"columns": [column[0] for column in cursor.description], "rows": [list(row) for row in cursor.fetchall()]}
            if actual != record["expected"]:
                raise DatasetValidationError(f"gold SQL result mismatch: {record['id']}")
    if any(len(splits) != 1 for splits in metric_splits.values()):
        raise DatasetValidationError("one metric must not cross train/dev/test")
    split_counts = {split: sum(record["split"] == split for record in records) for split in ("train", "dev", "test")}
    if split_counts != {"train": 216, "dev": 72, "test": 72}:
        raise DatasetValidationError(f"split counts mismatch: {split_counts}")
    for name in ("questions.jsonl", "questions_blind.jsonl", "expected.jsonl"):
        entry = manifest.get("files", {}).get(name, {})
        path = release_dir / name
        if entry.get("sha256") != _sha256(path) or entry.get("bytes") != path.stat().st_size:
            raise DatasetValidationError(f"manifest hash mismatch: {name}")
    return {
        "status": "VALID",
        "dataOrigin": "SYNTHETIC",
        "questionCount": 360,
        "metricCoverage": 360,
        "splitCounts": split_counts,
        "goldSqlExecutable": 360,
        "blindFields": ["id", "question"],
        "officialInputs": [],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = validate_dataset(args.release_dir)
    except (OSError, ValueError, json.JSONDecodeError, sqlite3.Error) as error:
        print(f"INVALID: {error}")
        return 1
    report_path = args.report or (args.release_dir / "dataset-validation-report.json")
    _write_report(report_path, report)
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
