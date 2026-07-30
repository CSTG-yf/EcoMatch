#!/usr/bin/env python3
"""Re-execute DATA-02 gold SQL and prove it matches stored structured results."""

from __future__ import annotations

import argparse
import json
import math
import sqlite3
import time
from pathlib import Path
from typing import Any


SPLITS = ("train", "dev", "test")


class GoldValidationError(ValueError):
    """A stored gold SQL query or result is no longer reproducible."""


def _json_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def validate_gold_dataset(dataset_path: Path | str, database_path: Path | str) -> dict[str, Any]:
    dataset_path = Path(dataset_path).resolve()
    database_path = Path(database_path).resolve()
    records = []
    for split in SPLITS:
        path = dataset_path / f"{split}.jsonl"
        records.extend((split, json.loads(line)) for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    connection = sqlite3.connect(database_path)
    latencies_ms: list[float] = []
    try:
        for split, record in records:
            sample_id = record.get("id")
            sql = record.get("sql")
            s2sql = record.get("s2sql")
            expected = record.get("expected", {})
            if not isinstance(sql, str) or not sql.strip() or not isinstance(s2sql, str) or not s2sql.strip():
                raise GoldValidationError(f"{sample_id}: missing gold SQL")
            started = time.perf_counter()
            try:
                cursor = connection.execute(sql)
                columns = [column[0] for column in cursor.description]
                rows = [[_json_value(value) for value in row] for row in cursor.fetchall()]
            finally:
                latencies_ms.append((time.perf_counter() - started) * 1000)
            if not rows:
                raise GoldValidationError(f"{sample_id}: gold SQL returned no rows")
            if expected.get("columns") != columns or expected.get("rows") != rows:
                raise GoldValidationError(f"{sample_id}: stored result differs from SQL result")
            if record.get("split") != split:
                raise GoldValidationError(f"{sample_id}: split does not match containing JSONL")
    finally:
        connection.close()
    ordered = sorted(latencies_ms)

    def percentile(fraction: float) -> float:
        index = max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))
        return round(ordered[index], 3)

    return {
        "result": "PASS",
        "officialCount": len(records),
        "sqlExecutionCount": len(records),
        "resultMatchCount": len(records),
        "timingMs": {
            "count": len(ordered),
            "average": round(sum(ordered) / len(ordered), 3),
            "p50": percentile(0.50),
            "p95": percentile(0.95),
            "p99": percentile(0.99),
            "max": round(ordered[-1], 3),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("database", type=Path)
    args = parser.parse_args()
    print(json.dumps(validate_gold_dataset(args.dataset, args.database), ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
