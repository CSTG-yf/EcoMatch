#!/usr/bin/env python3
"""Validate the synthetic 360-metric release without touching official data."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sqlite3
import sys
from collections import defaultdict
from contextlib import closing
from pathlib import Path
from typing import Any

try:
    from .build_synthetic_facts import DATES, DATA_ORIGIN, GENERATOR_VERSION, ORGANIZATIONS
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
    from evaluation.bank_nl2sql.synthetic_360.build_synthetic_facts import DATES, DATA_ORIGIN, GENERATOR_VERSION, ORGANIZATIONS  # type: ignore


class SyntheticValidationError(ValueError):
    pass


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    except (OSError, json.JSONDecodeError) as error:
        raise SyntheticValidationError(f"invalid JSONL: {path}: {error}") from error


def _write_report(path: Path, report: dict[str, Any]) -> None:
    """Write canonical LF JSON bytes on every supported platform."""
    path.write_bytes((json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


def validate_release(release_dir: Path) -> dict[str, Any]:
    release_dir = release_dir.resolve()
    manifest = json.loads((release_dir / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("status") != "SYNTHETIC_CANDIDATE" or manifest.get("dataOrigin") != DATA_ORIGIN:
        raise SyntheticValidationError("release must remain explicitly synthetic")
    if manifest.get("generatorVersion") != GENERATOR_VERSION or manifest.get("officialInputs") != []:
        raise SyntheticValidationError("synthetic release boundary mismatch")
    metrics = _read_jsonl(release_dir / "metrics.jsonl")
    facts = _read_jsonl(release_dir / "facts.jsonl")
    if len(metrics) != 360 or len({metric.get("code") for metric in metrics}) != 360:
        raise SyntheticValidationError("metric count or uniqueness mismatch")
    metric_by_code = {metric["code"]: metric for metric in metrics}
    organization_codes = {organization["orgCode"] for organization in ORGANIZATIONS}
    date_set = set(DATES)
    values: dict[tuple[str, str], dict[str, float]] = defaultdict(dict)
    keys: set[tuple[str, str, str]] = set()
    for fact in facts:
        required = {"dataDate", "orgCode", "metricCode", "metricValue", "dataOrigin", "generatorVersion"}
        if set(fact) != required or fact["dataOrigin"] != DATA_ORIGIN or fact["generatorVersion"] != GENERATOR_VERSION:
            raise SyntheticValidationError(f"fact contract mismatch: {fact}")
        key = (fact["dataDate"], fact["orgCode"], fact["metricCode"])
        if key in keys:
            raise SyntheticValidationError(f"duplicate fact key: {key}")
        keys.add(key)
        if fact["dataDate"] not in date_set or fact["orgCode"] not in organization_codes or fact["metricCode"] not in metric_by_code:
            raise SyntheticValidationError(f"fact dimension is outside release: {key}")
        if not isinstance(fact["metricValue"], (int, float)) or not math.isfinite(float(fact["metricValue"])):
            raise SyntheticValidationError(f"invalid finite value: {key}")
        values[(fact["dataDate"], fact["orgCode"])][fact["metricCode"]] = float(fact["metricValue"])
    expected_count = 360 * len(ORGANIZATIONS) * len(DATES)
    if len(facts) != expected_count:
        raise SyntheticValidationError(f"fact count mismatch: {len(facts)} != {expected_count}")
    missing_base_cells = [cell for cell, row in values.items() if len(row) != 360]
    if missing_base_cells:
        raise SyntheticValidationError(f"missing fact cells: {missing_base_cells[:3]}")
    for cell, row in values.items():
        if not math.isclose(row["CNB001"], row["CNB002"] + row["CNB003"], rel_tol=0, abs_tol=1e-5):
            raise SyntheticValidationError(f"deposit balance relation mismatch: {cell}")
        if row["CNB016"] < row["CNB156"] or row["CNB159"] > row["CNB016"] or row["CNB161"] > row["CNB156"]:
            raise SyntheticValidationError(f"loan quality relation mismatch: {cell}")
        if row["CNB352"] > row["CNB351"] or row["CNB354"] > row["CNB352"]:
            raise SyntheticValidationError(f"complaint relation mismatch: {cell}")
        for metric in metrics:
            if metric["metricType"] != "DERIVED":
                continue
            numerator, denominator = metric["formula"]["operands"]
            expected = round(row[numerator] / row[denominator] * 100, 6)
            if not math.isclose(row[metric["code"]], expected, rel_tol=0, abs_tol=1e-6):
                raise SyntheticValidationError(f"derived formula mismatch: {cell} {metric['code']}")
    with closing(sqlite3.connect(release_dir / "bank.sqlite")) as connection:
        table_counts = {
            "organizations": connection.execute("SELECT COUNT(*) FROM bank_organization").fetchone()[0],
            "metrics": connection.execute("SELECT COUNT(*) FROM bank_metric_definition").fetchone()[0],
            "facts": connection.execute("SELECT COUNT(*) FROM bank_metric_daily").fetchone()[0],
        }
        duplicate_count = connection.execute(
            "SELECT COUNT(*) FROM (SELECT data_date, org_code, metric_code FROM bank_metric_daily GROUP BY data_date, org_code, metric_code HAVING COUNT(*) > 1)"
        ).fetchone()[0]
    if table_counts != {"organizations": 13, "metrics": 360, "facts": expected_count} or duplicate_count:
        raise SyntheticValidationError(f"SQLite counts or duplicate keys mismatch: {table_counts}, {duplicate_count}")
    for name, entry in manifest.get("files", {}).items():
        path = release_dir / name
        if entry.get("sha256") != _sha256(path) or entry.get("bytes") != path.stat().st_size:
            raise SyntheticValidationError(f"manifest hash mismatch: {name}")
    report = {
        "status": "VALID",
        "dataOrigin": DATA_ORIGIN,
        "generatorVersion": GENERATOR_VERSION,
        "counts": {"metrics": 360, "organizations": 13, "dates": 17, "facts": expected_count},
        "duplicateKeys": [],
        "missingBaseCells": [],
        "derivedFormulaErrors": 0,
        "officialInputs": [],
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = validate_release(args.release_dir)
    except (OSError, ValueError, json.JSONDecodeError, sqlite3.Error) as error:
        print(f"INVALID: {error}")
        return 1
    report_path = args.report or (args.release_dir / "validation_report.json")
    _write_report(report_path, report)
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
