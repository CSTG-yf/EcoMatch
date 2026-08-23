#!/usr/bin/env python3
"""Execute the public disclosure queries against an isolated SQLite fact store."""

from __future__ import annotations

import argparse
import json
import math
import sqlite3
import sys
from pathlib import Path
from typing import Any

try:
    from .validate_public_facts import validate_release
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
    from evaluation.bank_nl2sql.public_disclosure.validate_public_facts import validate_release  # type: ignore


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _same_value(expected: Any, actual: Any) -> bool:
    if isinstance(expected, (int, float)) and not isinstance(expected, bool) and isinstance(actual, (int, float)) and not isinstance(actual, bool):
        return math.isclose(float(expected), float(actual), rel_tol=0, abs_tol=1e-9)
    return expected == actual


def _matches(expected: dict[str, Any], actual_columns: list[str], actual_rows: list[list[Any]]) -> bool:
    if expected.get("columns") != actual_columns or len(expected.get("rows", [])) != len(actual_rows):
        return False
    return all(
        len(expected_row) == len(actual_row) and all(_same_value(left, right) for left, right in zip(expected_row, actual_row))
        for expected_row, actual_row in zip(expected["rows"], actual_rows)
    )


def run_tests(release_dir: Path, *, catalog_dir: Path | None = None) -> dict[str, Any]:
    release_dir = release_dir.resolve()
    validation = validate_release(release_dir, catalog_dir=catalog_dir)
    facts = _read_jsonl(release_dir / "facts.jsonl")
    queries = _read_jsonl(release_dir / "queries.jsonl")
    connection = sqlite3.connect(":memory:")
    try:
        connection.executescript(
            """
            CREATE TABLE public_organization (
                org_code TEXT PRIMARY KEY,
                org_name TEXT NOT NULL,
                data_origin TEXT NOT NULL CHECK (data_origin = 'PUBLIC_DISCLOSURE')
            );
            CREATE TABLE public_metric_definition (
                metric_code TEXT PRIMARY KEY,
                metric_name TEXT NOT NULL,
                unit TEXT NOT NULL,
                aggregation TEXT NOT NULL,
                catalog_status TEXT NOT NULL CHECK (catalog_status = 'CANDIDATE')
            );
            CREATE TABLE public_metric_fact (
                data_date TEXT NOT NULL,
                org_code TEXT NOT NULL,
                metric_code TEXT NOT NULL,
                metric_value REAL NOT NULL,
                unit TEXT NOT NULL,
                source_id TEXT NOT NULL,
                source_page INTEGER NOT NULL,
                source_locator TEXT NOT NULL,
                source_value REAL NOT NULL,
                source_unit TEXT NOT NULL,
                conversion TEXT NOT NULL,
                data_origin TEXT NOT NULL CHECK (data_origin = 'PUBLIC_DISCLOSURE'),
                mapping_status TEXT NOT NULL,
                PRIMARY KEY (data_date, org_code, metric_code),
                FOREIGN KEY (org_code) REFERENCES public_organization(org_code),
                FOREIGN KEY (metric_code) REFERENCES public_metric_definition(metric_code)
            );
            """
        )
        organizations = {(fact["orgCode"], fact["orgName"]) for fact in facts}
        connection.executemany("INSERT INTO public_organization VALUES (?, ?, 'PUBLIC_DISCLOSURE')", [(code, name) for code, name in organizations])
        catalog_path = (catalog_dir or release_dir.parents[1] / "bank_metric_catalog" / "releases" / "0.1.0-candidate") / "metrics.jsonl"
        catalog = {metric["code"]: metric for metric in _read_jsonl(catalog_path)}
        connection.executemany(
            "INSERT INTO public_metric_definition VALUES (?, ?, ?, ?, 'CANDIDATE')",
            [(code, catalog[code]["name"], catalog[code]["unit"], catalog[code]["aggregation"]) for code in sorted({fact["metricCode"] for fact in facts})],
        )
        connection.executemany(
            "INSERT INTO public_metric_fact VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [(
                fact["dataDate"], fact["orgCode"], fact["metricCode"], fact["metricValue"], fact["unit"], fact["sourceId"],
                fact["sourcePage"], fact["sourceLocator"], fact["sourceValue"], fact["sourceUnit"], fact["conversion"],
                fact["dataOrigin"], fact["mappingStatus"],
            ) for fact in facts],
        )
        details: list[dict[str, Any]] = []
        for query in queries:
            query_result = {"id": query["id"], "parse": False, "execute": False, "resultCorrect": False, "sourceTraceable": False}
            sql = query["sql"].strip()
            query_result["parse"] = sql.upper().startswith("SELECT ") and sql.count(";") <= 1
            if query_result["parse"]:
                columns = [description[0] for description in connection.execute(sql).description or []]
                rows = [list(row) for row in connection.execute(sql).fetchall()]
                query_result["execute"] = True
                query_result["resultCorrect"] = _matches(query["expected"], columns, rows)
                returned_codes = {str(row[0]) for row in rows if row}
                query_result["sourceTraceable"] = returned_codes == set(query["metricCodes"]) and all(
                    fact["sourceId"] and fact["sourceLocator"] for fact in facts if fact["metricCode"] in returned_codes
                )
            details.append(query_result)
        parse_count = sum(item["parse"] for item in details)
        execute_count = sum(item["execute"] for item in details)
        result_count = sum(item["resultCorrect"] for item in details)
        source_count = sum(item["sourceTraceable"] for item in details)
        report = {
            "status": "VALID" if result_count == len(queries) and source_count == len(queries) else "INVALID",
            "dataOrigin": "PUBLIC_DISCLOSURE",
            "dataset": "public_disclosure",
            "queryCount": len(queries),
            "parseSuccessCount": parse_count,
            "executionSuccessCount": execute_count,
            "resultCorrectCount": result_count,
            "sourceTraceableCount": source_count,
            "factsLoaded": len(facts),
            "metricCount": validation["metrics"],
            "details": details,
            "warning": "这是公开披露事实的 SQLite 链路验证，不是官方 21 项成绩，也不是模型准确率或生产库验收。",
        }
        return report
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    parser.add_argument("--catalog-dir", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = run_tests(args.release_dir, catalog_dir=args.catalog_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("status", "queryCount", "parseSuccessCount", "executionSuccessCount", "resultCorrectCount", "sourceTraceableCount")}, ensure_ascii=False))
    return 0 if report["status"] == "VALID" else 1


if __name__ == "__main__":
    raise SystemExit(main())
