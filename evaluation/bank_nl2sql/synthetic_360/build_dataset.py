#!/usr/bin/env python3
"""Build one executable point query for each synthetic catalog metric."""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from contextlib import closing
from pathlib import Path
from typing import Any


def _json_bytes(value: Any, *, indent: int | None = None) -> bytes:
    text = json.dumps(value, ensure_ascii=False, sort_keys=True, indent=indent, separators=None if indent else (",", ":"))
    return (text + "\n").encode("utf-8")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _split_for_code(code: str) -> str:
    remainder = int(code.removeprefix("CNB")) % 5
    return "test" if remainder == 0 else "dev" if remainder == 1 else "train"


def _opaque_id(code: str) -> str:
    return "SYNQ-" + hashlib.sha256(f"synthetic-360-point-v1|{code}".encode("utf-8")).hexdigest()[:20]


def _sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def build_dataset(release_dir: Path) -> dict[str, Any]:
    release_dir = release_dir.resolve()
    metrics = [json.loads(line) for line in (release_dir / "metrics.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    organizations = json.loads((release_dir / "organizations.json").read_text(encoding="utf-8"))
    output_dir = release_dir
    database_path = release_dir / "bank.sqlite"
    records: list[dict[str, Any]] = []
    expected_records: list[dict[str, Any]] = []
    with closing(sqlite3.connect(database_path)) as connection:
        for index, metric in enumerate(metrics):
            code = metric["code"]
            organization = organizations[index % len(organizations)]
            data_date = "2025-01-31" if index % 17 == 0 else f"2025-{(index % 12) + 1:02d}-" + ("31" if (index % 12) in {0, 2, 4, 6, 7, 9, 11} else "30")
            if data_date == "2025-02-30":
                data_date = "2025-02-28"
            term = metric["name"] if index % 2 == 0 else metric["aliases"][0]
            question = f"查询{organization['orgName']}在{data_date}的{term}是多少？"
            gold_sql = (
                "SELECT org_code, metric_code, data_date, metric_value "
                "FROM bank_metric_daily WHERE org_code = " + _sql_literal(organization["orgCode"]) +
                " AND data_date = " + _sql_literal(data_date) +
                " AND metric_code = " + _sql_literal(code) +
                " ORDER BY org_code, metric_code, data_date"
            )
            cursor = connection.execute(gold_sql)
            columns = [column[0] for column in cursor.description]
            rows = [list(row) for row in cursor.fetchall()]
            if len(rows) != 1:
                raise ValueError(f"gold query did not return one row: {code}")
            record = {
                "id": _opaque_id(code),
                "split": _split_for_code(code),
                "metricCode": code,
                "question": question,
                "queryType": "POINT_QUERY",
                "dataOrigin": "SYNTHETIC",
                "expected": {"columns": columns, "rows": rows},
                "goldSql": gold_sql,
            }
            records.append(record)
            expected_records.append({"id": record["id"], "split": record["split"], "expected": record["expected"], "metricCode": code, "dataOrigin": "SYNTHETIC"})
    records_payload = b"".join(_json_bytes(record) for record in records)
    blind_payload = b"".join(_json_bytes({"id": record["id"], "question": record["question"]}) for record in records)
    expected_payload = b"".join(_json_bytes(record) for record in expected_records)
    (output_dir / "questions.jsonl").write_bytes(records_payload)
    (output_dir / "questions_blind.jsonl").write_bytes(blind_payload)
    (output_dir / "expected.jsonl").write_bytes(expected_payload)
    manifest = {
        "version": "0.1.0-synthetic-point-v1",
        "status": "SYNTHETIC_CANDIDATE",
        "dataOrigin": "SYNTHETIC",
        "queryTypes": ["POINT_QUERY"],
        "questionCount": len(records),
        "splitCounts": {split: sum(record["split"] == split for record in records) for split in ("train", "dev", "test")},
        "metricCoverage": len({record["metricCode"] for record in records}),
        "officialEligible": False,
        "officialInputs": [],
        "sourceFactRelease": "0.1.0-synthetic",
        "files": {
            name: {"sha256": _sha256(output_dir / name), "bytes": (output_dir / name).stat().st_size}
            for name in ("questions.jsonl", "questions_blind.jsonl", "expected.jsonl")
        },
    }
    (output_dir / "dataset-manifest.json").write_bytes(_json_bytes(manifest, indent=2))
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = build_dataset(args.release_dir)
    except (OSError, ValueError, json.JSONDecodeError, sqlite3.Error) as error:
        print(f"INVALID: {error}")
        return 1
    print(json.dumps({"status": "VALID", **{key: manifest[key] for key in ("questionCount", "metricCoverage", "splitCounts")}}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
