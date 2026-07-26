#!/usr/bin/env python3
"""Materialise deterministic gold SQL and its SQLite execution result in DATA-02."""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from pathlib import Path
from typing import Any

from gold_sql import build_gold_sql


SPLITS = ("train", "dev", "test")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _json_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def _write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n" for record in records), encoding="utf-8"
    )


def build_gold_dataset(
    dataset_path: Path | str,
    database_path: Path | str,
    *,
    splits: tuple[str, ...] = SPLITS,
    write_gold_manifest: bool = True,
) -> dict[str, Any]:
    """Populate selected official DATA-02 records with gold SQL and exact result rows."""

    dataset_path = Path(dataset_path).resolve()
    database_path = Path(database_path).resolve()
    if not database_path.is_file():
        raise FileNotFoundError(f"Benchmark database does not exist: {database_path}")
    selected_splits = tuple(dict.fromkeys(splits))
    invalid_splits = set(selected_splits) - set(SPLITS)
    if not selected_splits or invalid_splits:
        raise ValueError(f"Unsupported DATA-02 splits: {sorted(invalid_splits) or 'none'}")
    if write_gold_manifest and set(selected_splits) != set(SPLITS):
        raise ValueError("Partial materialisation must not overwrite gold_manifest.json")
    records_by_split = {split: _read_jsonl(dataset_path / f"{split}.jsonl") for split in selected_splits}

    connection = sqlite3.connect(database_path)
    try:
        for records in records_by_split.values():
            for record in records:
                spec = build_gold_sql(record)
                cursor = connection.execute(spec.sql)
                columns = [column[0] for column in cursor.description]
                rows = [[_json_value(value) for value in row] for row in cursor.fetchall()]
                if not rows:
                    raise ValueError(f"Gold SQL returned no rows for {record.get('id')}")
                record["s2sql"] = spec.s2sql
                record["sql"] = spec.sql
                record["sqlFeatures"] = spec.features
                expected = record.setdefault("expected", {})
                expected["columns"] = columns
                expected["rows"] = rows
                expected["numericTolerance"] = 0.000001
                expected["orderSensitive"] = True
    finally:
        connection.close()

    for split, records in records_by_split.items():
        _write_jsonl(dataset_path / f"{split}.jsonl", records)
    report = {
        "version": "0.1.0",
        "databaseSha256": _sha256(database_path),
        "officialCount": sum(len(records) for records in records_by_split.values()),
        "splitCounts": {split: len(records) for split, records in records_by_split.items()},
        "sqlExecution": "PASS",
    }
    if write_gold_manifest:
        (dataset_path / "gold_manifest.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="DATA-02 directory containing train/dev/test JSONL")
    parser.add_argument("database", type=Path, help="SQLite benchmark database")
    parser.add_argument("--splits", nargs="+", choices=SPLITS, default=SPLITS)
    parser.add_argument("--no-gold-manifest", action="store_true")
    args = parser.parse_args()
    print(
        json.dumps(
            build_gold_dataset(
                args.dataset,
                args.database,
                splits=tuple(args.splits),
                write_gold_manifest=not args.no_gold_manifest,
            ),
            ensure_ascii=False,
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
