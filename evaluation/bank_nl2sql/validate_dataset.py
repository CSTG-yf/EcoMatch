#!/usr/bin/env python3
"""Validate DATA-02 schema, coverage, template isolation, and SQL execution."""

from __future__ import annotations

import argparse
import json
import sqlite3
from collections import Counter
from pathlib import Path

from build_dataset import ERROR_TYPES, FEATURES, OUTPUT_DIR, SPLITS, create_database, execute, find_workbook, workbook_rows

REQUIRED_FIELDS = {
    "id", "source", "split", "templateGroup", "difficulty", "question", "normalizedIntent",
    "s2sql", "physicalSql", "sqlDialect", "complexity", "expectedResultSummary", "expectedResult",
}


def read_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def assert_negative_behavior(connection: sqlite3.Connection, error: dict, positive_hash: str) -> None:
    error_type = error["errorType"]
    try:
        result = execute(connection, error["faultySql"])
    except sqlite3.Error:
        if error_type not in {"SYNTAX_ERROR", "EXECUTION_ERROR"}:
            raise AssertionError(f"{error['id']} unexpectedly failed to execute")
        return
    if error_type in {"SYNTAX_ERROR", "EXECUTION_ERROR"}:
        raise AssertionError(f"{error['id']} should fail to execute")
    if result["sha256"] == positive_hash:
        raise AssertionError(f"{error['id']} did not change the expected result")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workbook", type=Path)
    args = parser.parse_args()
    workbook = args.workbook or find_workbook()
    organizations, metrics, facts = workbook_rows(workbook)
    connection = create_database(organizations, metrics, facts)
    cases = [case for split in SPLITS for case in read_jsonl(OUTPUT_DIR / f"{split}.jsonl")]
    errors = read_jsonl(OUTPUT_DIR / "error_cases.jsonl")
    ids: set[str] = set()
    templates = {split: set() for split in SPLITS}
    feature_counts: Counter[str] = Counter()
    positive_hashes: dict[str, str] = {}
    for case in cases:
        missing = REQUIRED_FIELDS - case.keys()
        assert not missing, f"{case.get('id')} missing fields: {sorted(missing)}"
        assert case["id"] not in ids, f"duplicate id: {case['id']}"
        ids.add(case["id"])
        assert case["split"] in SPLITS
        assert case["sqlDialect"] == "sqlite"
        assert case["s2sql"].lstrip().upper().startswith(("SELECT", "WITH"))
        assert "bank_indicator_dataset" in case["s2sql"]
        assert case["question"].strip() and case["expectedResultSummary"].strip()
        templates[case["split"]].add(case["templateGroup"])
        feature_counts.update(case["complexity"])
        actual = execute(connection, case["physicalSql"])
        assert actual["sha256"] == case["expectedResult"]["sha256"], f"result mismatch: {case['id']}"
        assert actual["rowCount"] == case["expectedResult"]["rowCount"], f"row count mismatch: {case['id']}"
        positive_hashes[case["id"]] = actual["sha256"]
    assert not templates["train"] & templates["dev"]
    assert not templates["train"] & templates["test"]
    assert not templates["dev"] & templates["test"]
    for feature in FEATURES:
        assert feature_counts[feature] > 0, f"missing feature coverage: {feature}"
    error_counts = Counter(error["errorType"] for error in errors)
    assert set(error_counts) == set(ERROR_TYPES)
    assert len(set(error_counts.values())) == 1, "error taxonomy must be balanced"
    assert len(errors) == len(cases)
    for error in errors:
        assert error["sourceId"] in positive_hashes
        assert error["expectedDiagnosis"].strip()
        assert_negative_behavior(connection, error, positive_hashes[error["sourceId"]])
    report = {
        "status": "passed", "positiveSqlExecuted": len(cases), "negativeCasesValidated": len(errors),
        "featureCounts": dict(feature_counts), "errorTypeCounts": dict(error_counts), "templateOverlap": 0,
    }
    (OUTPUT_DIR / "validation_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    connection.close()
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
