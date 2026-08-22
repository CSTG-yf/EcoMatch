#!/usr/bin/env python3
"""Build the deterministic, explicitly synthetic 360-metric fact cube."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sqlite3
import sys
from contextlib import closing
from datetime import date
from pathlib import Path
from typing import Any

try:
    from evaluation.bank_metric_catalog.validate_catalog import load_release, validate_release
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
    from evaluation.bank_metric_catalog.validate_catalog import load_release, validate_release  # type: ignore


GENERATOR_VERSION = "synthetic-360-v1"
DATA_ORIGIN = "SYNTHETIC"
DATES = tuple(
    [f"2025-{month:02d}-{day:02d}" for month, day in ((1, 31), (2, 28), (3, 31), (4, 30), (5, 31), (6, 30), (7, 31), (8, 31), (9, 30), (10, 31), (11, 30), (12, 31))]
    + [f"2026-{month:02d}-{day:02d}" for month, day in ((1, 31), (2, 28), (3, 31), (4, 30), (5, 31))]
)
ORGANIZATIONS = tuple(
    {"orgCode": f"SYN-ORG-{index:03d}", "orgName": f"合成机构{index:02d}"}
    for index in range(1, 14)
)


def _json_bytes(value: Any, *, indent: int | None = None) -> bytes:
    text = json.dumps(value, ensure_ascii=False, sort_keys=True, indent=indent, separators=None if indent else (",", ":"))
    return (text + "\n").encode("utf-8")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _stable_fraction(*parts: str) -> float:
    digest = hashlib.sha256("|".join((GENERATOR_VERSION, *parts)).encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") / float(2**64)


def _load_metrics(catalog_dir: Path) -> list[dict[str, Any]]:
    metrics, _, _ = load_release(catalog_dir.resolve())
    if len(metrics) != 360:
        raise ValueError(f"synthetic release requires 360 catalog metrics, got {len(metrics)}")
    return metrics


def _generic_value(metric: dict[str, Any], org_code: str, data_date: str) -> float | int:
    fraction = _stable_fraction(org_code, metric["code"], data_date)
    if metric["valueType"] == "INTEGER":
        return int(500 + fraction * 100_000)
    if metric["unit"] == "%":
        return round(1 + fraction * 75, 6)
    return round(500 + fraction * 150_000, 6)


def _set_relational_values(values: dict[str, float | int], org_code: str, data_date: str) -> None:
    fraction = _stable_fraction(org_code, data_date, "relations")
    # Balance and asset-quality relationships.
    values["CNB001"] = round(float(values["CNB002"]) + float(values["CNB003"]), 6)
    values["CNB016"] = round(float(values["CNB001"]) * (0.65 + fraction * 0.2), 6)
    values["CNB156"] = round(float(values["CNB016"]) * (0.01 + fraction * 0.04), 6)
    values["CNB152"] = round(float(values["CNB016"]) * (0.02 + fraction * 0.06), 6)
    values["CNB159"] = round(float(values["CNB016"]) * (0.01 + fraction * 0.04), 6)
    values["CNB161"] = round(float(values["CNB156"]) * (0.10 + fraction * 0.30), 6)
    values["CNB168"] = round(float(values["CNB156"]) * (1.0 + fraction * 1.2), 6)
    values["CNB171"] = round(float(values["CNB016"]) * (0.01 + fraction * 0.03), 6)

    # Capital and liquidity numerators stay positive and bounded by their bases.
    values["CNB194"] = max(float(values["CNB194"]), 50_000.0)
    values["CNB186"] = round(float(values["CNB194"]) * (0.08 + fraction * 0.04), 6)
    values["CNB188"] = round(float(values["CNB194"]) * (0.10 + fraction * 0.04), 6)
    values["CNB190"] = round(float(values["CNB194"]) * (0.12 + fraction * 0.05), 6)
    values["CNB211"] = max(float(values["CNB211"]), 50_000.0)
    values["CNB212"] = max(float(values["CNB212"]), 40_000.0)
    values["CNB214"] = round(float(values["CNB211"]) * (0.70 + fraction * 0.20), 6)
    values["CNB215"] = round(float(values["CNB212"]) * (0.50 + fraction * 0.20), 6)
    values["CNB217"] = round(float(values["CNB212"]) * (0.85 + fraction * 0.10), 6)
    values["CNB218"] = round(float(values["CNB212"]) * (0.70 + fraction * 0.10), 6)

    # Count relationships used by customer, marketing and complaint ratios.
    values["CNB271"] = max(int(values["CNB271"]), 10_000)
    values["CNB285"] = min(int(values["CNB285"]), int(values["CNB271"]))
    values["CNB303"] = min(int(values["CNB303"]), int(values["CNB271"]))
    values["CNB331"] = min(int(values["CNB331"]), int(values["CNB285"]))
    values["CNB296"] = max(int(values["CNB296"]), 1_000)
    values["CNB297"] = min(int(values["CNB297"]), int(values["CNB296"]))
    values["CNB299"] = min(int(values["CNB299"]), int(values["CNB297"]))
    values["CNB351"] = max(int(values["CNB351"]), 100)
    values["CNB352"] = min(int(values["CNB352"]), int(values["CNB351"]))
    values["CNB354"] = min(int(values["CNB354"]), int(values["CNB352"]))


def _calculate_values(metrics: list[dict[str, Any]], org_code: str, data_date: str) -> dict[str, float | int]:
    values = {metric["code"]: _generic_value(metric, org_code, data_date) for metric in metrics if metric["metricType"] == "BASE"}
    _set_relational_values(values, org_code, data_date)
    for metric in metrics:
        if metric["metricType"] != "DERIVED":
            continue
        operands = metric["formula"]["operands"]
        numerator = float(values[operands[0]])
        denominator = float(values[operands[1]])
        if denominator <= 0:
            raise ValueError(f"non-positive denominator for {metric['code']}")
        values[metric["code"]] = round(numerator / denominator * 100, 6)
    return values


def _sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _write_sqlite(path: Path, metrics: list[dict[str, Any]], facts: list[dict[str, Any]]) -> None:
    if path.exists():
        path.unlink()
    with closing(sqlite3.connect(path)) as connection:
        connection.executescript(
            """
            CREATE TABLE bank_organization (
                org_code TEXT PRIMARY KEY,
                org_name TEXT NOT NULL UNIQUE
            );
            CREATE TABLE bank_metric_definition (
                metric_code TEXT PRIMARY KEY,
                metric_name TEXT NOT NULL UNIQUE,
                metric_meaning TEXT NOT NULL,
                metric_unit TEXT NOT NULL,
                aggregation TEXT NOT NULL,
                metric_type TEXT NOT NULL,
                data_origin TEXT NOT NULL CHECK (data_origin = 'CANDIDATE_CATALOG')
            );
            CREATE TABLE bank_metric_daily (
                data_date TEXT NOT NULL,
                org_code TEXT NOT NULL,
                metric_code TEXT NOT NULL,
                metric_value REAL NOT NULL,
                data_origin TEXT NOT NULL CHECK (data_origin = 'SYNTHETIC'),
                generator_version TEXT NOT NULL,
                PRIMARY KEY (data_date, org_code, metric_code)
            );
            """
        )
        connection.executemany("INSERT INTO bank_organization VALUES (?, ?)", [(x["orgCode"], x["orgName"]) for x in ORGANIZATIONS])
        connection.executemany(
            "INSERT INTO bank_metric_definition VALUES (?, ?, ?, ?, ?, ?, ?)",
            [(m["code"], m["name"], m["definition"], m["unit"], m["aggregation"], m["metricType"], "CANDIDATE_CATALOG") for m in metrics],
        )
        connection.executemany(
            "INSERT INTO bank_metric_daily VALUES (?, ?, ?, ?, ?, ?)",
            [(x["dataDate"], x["orgCode"], x["metricCode"], x["metricValue"], x["dataOrigin"], x["generatorVersion"]) for x in facts],
        )
        connection.commit()


def _write_h2(path: Path, metrics: list[dict[str, Any]], facts: list[dict[str, Any]]) -> None:
    lines = [
        "DROP TABLE IF EXISTS bank_metric_daily;",
        "DROP TABLE IF EXISTS bank_metric_definition;",
        "DROP TABLE IF EXISTS bank_organization;",
        "CREATE TABLE bank_organization (org_code VARCHAR(32) PRIMARY KEY, org_name VARCHAR(128) NOT NULL UNIQUE);",
        "CREATE TABLE bank_metric_definition (metric_code VARCHAR(16) PRIMARY KEY, metric_name VARCHAR(128) NOT NULL UNIQUE, metric_meaning VARCHAR(2000) NOT NULL, metric_unit VARCHAR(32) NOT NULL, aggregation VARCHAR(32) NOT NULL, metric_type VARCHAR(16) NOT NULL, data_origin VARCHAR(32) NOT NULL);",
        "CREATE TABLE bank_metric_daily (data_date DATE NOT NULL, org_code VARCHAR(32) NOT NULL, metric_code VARCHAR(16) NOT NULL, metric_value DECIMAL(24,6) NOT NULL, data_origin VARCHAR(32) NOT NULL, generator_version VARCHAR(64) NOT NULL, PRIMARY KEY (data_date, org_code, metric_code));",
    ]
    lines.extend(f"INSERT INTO bank_organization VALUES ({_sql_quote(x['orgCode'])}, {_sql_quote(x['orgName'])});" for x in ORGANIZATIONS)
    lines.extend(
        f"INSERT INTO bank_metric_definition VALUES ({_sql_quote(m['code'])}, {_sql_quote(m['name'])}, {_sql_quote(m['definition'])}, {_sql_quote(m['unit'])}, {_sql_quote(m['aggregation'])}, {_sql_quote(m['metricType'])}, 'CANDIDATE_CATALOG');"
        for m in metrics
    )
    lines.extend(
        f"INSERT INTO bank_metric_daily VALUES ({_sql_quote(x['dataDate'])}, {_sql_quote(x['orgCode'])}, {_sql_quote(x['metricCode'])}, {x['metricValue']}, 'SYNTHETIC', 'synthetic-360-v1');"
        for x in facts
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_release(catalog_dir: Path, output_dir: Path) -> dict[str, Any]:
    catalog_dir = catalog_dir.resolve()
    output_dir = output_dir.resolve()
    validate_release(catalog_dir)
    metrics = _load_metrics(catalog_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    facts: list[dict[str, Any]] = []
    for organization in ORGANIZATIONS:
        for data_date in DATES:
            values = _calculate_values(metrics, organization["orgCode"], data_date)
            for metric in metrics:
                value = values[metric["code"]]
                if not isinstance(value, (int, float)) or not math.isfinite(float(value)):
                    raise ValueError(f"invalid generated value: {metric['code']}")
                facts.append(
                    {
                        "dataDate": data_date,
                        "orgCode": organization["orgCode"],
                        "metricCode": metric["code"],
                        "metricValue": value,
                        "dataOrigin": DATA_ORIGIN,
                        "generatorVersion": GENERATOR_VERSION,
                    }
                )

    (output_dir / "organizations.json").write_bytes(_json_bytes(list(ORGANIZATIONS), indent=2))
    (output_dir / "metrics.jsonl").write_bytes(b"".join(_json_bytes(metric) for metric in metrics))
    (output_dir / "facts.jsonl").write_bytes(b"".join(_json_bytes(fact) for fact in facts))
    _write_sqlite(output_dir / "bank.sqlite", metrics, facts)
    _write_h2(output_dir / "bank-h2.sql", metrics, facts)
    files = {name: {"sha256": _sha256(output_dir / name), "bytes": (output_dir / name).stat().st_size} for name in ("organizations.json", "metrics.jsonl", "facts.jsonl", "bank.sqlite", "bank-h2.sql")}
    manifest = {
        "version": "0.1.0-synthetic",
        "status": "SYNTHETIC_CANDIDATE",
        "schemaVersion": "1.0.0",
        "dataOrigin": DATA_ORIGIN,
        "generatorVersion": GENERATOR_VERSION,
        "catalogVersion": "0.1.0-candidate",
        "officialInputs": [],
        "dateRange": {"start": DATES[0], "end": DATES[-1]},
        "counts": {
            "metrics": len(metrics),
            "baseMetrics": sum(metric["metricType"] == "BASE" for metric in metrics),
            "derivedMetrics": sum(metric["metricType"] == "DERIVED" for metric in metrics),
            "organizations": len(ORGANIZATIONS),
            "dates": len(DATES),
            "facts": len(facts),
        },
        "files": files,
    }
    (output_dir / "manifest.json").write_bytes(_json_bytes(manifest, indent=2))
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = build_release(args.catalog_dir, args.output_dir)
    except (OSError, ValueError, json.JSONDecodeError, sqlite3.Error) as error:
        print(f"INVALID: {error}")
        return 1
    print(json.dumps({"status": "VALID", **manifest["counts"], "release": str(args.output_dir)}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
