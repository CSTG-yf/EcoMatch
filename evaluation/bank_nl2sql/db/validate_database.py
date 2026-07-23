#!/usr/bin/env python3
"""Validate the generated SQLite bank NL2SQL benchmark database."""

from __future__ import annotations

import argparse
import json
import sqlite3
from datetime import date
from pathlib import Path
from typing import Any


def _scalar(connection: sqlite3.Connection, sql: str) -> int | str | None:
    return connection.execute(sql).fetchone()[0]


def validate_database(database_path: Path) -> dict[str, Any]:
    database_path = Path(database_path)
    if not database_path.is_file():
        raise FileNotFoundError(f"SQLite benchmark database not found: {database_path}")

    connection = sqlite3.connect(database_path)
    try:
        counts = {
            "organizations": int(_scalar(connection, "SELECT COUNT(*) FROM bank_organization")),
            "metrics": int(_scalar(connection, "SELECT COUNT(*) FROM bank_metric_definition")),
            "facts": int(_scalar(connection, "SELECT COUNT(*) FROM bank_metric_daily")),
        }
        date_min = _scalar(connection, "SELECT MIN(data_date) FROM bank_metric_daily")
        date_max = _scalar(connection, "SELECT MAX(data_date) FROM bank_metric_daily")
        date_count = int(_scalar(connection, "SELECT COUNT(DISTINCT data_date) FROM bank_metric_daily"))
        expected_facts_per_date = counts["organizations"] * counts["metrics"]
        errors: list[str] = []

        if not all(counts.values()):
            errors.append(f"empty benchmark table: {counts}")
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            errors.append("foreign key violations found")
        duplicate_count = int(
            _scalar(
                connection,
                "SELECT COUNT(*) FROM ("
                "SELECT data_date, org_code, metric_code, COUNT(*) AS c "
                "FROM bank_metric_daily GROUP BY data_date, org_code, metric_code HAVING c > 1"
                ")",
            )
        )
        if duplicate_count:
            errors.append(f"duplicate fact keys: {duplicate_count}")
        missing_fact_dates = int(
            _scalar(
                connection,
                "SELECT COUNT(*) FROM ("
                "SELECT data_date, COUNT(*) AS c FROM bank_metric_daily GROUP BY data_date "
                f"HAVING c <> {expected_facts_per_date}"
                ")",
            )
        )
        if missing_fact_dates:
            errors.append(f"dates with incomplete fact cube: {missing_fact_dates}")
        if date_min and date_max:
            inclusive_days = (date.fromisoformat(str(date_max)) - date.fromisoformat(str(date_min))).days + 1
            if date_count != inclusive_days:
                errors.append(
                    f"non-contiguous dates: expected {inclusive_days} distinct dates, found {date_count}"
                )
        else:
            errors.append("missing date range")
        expected_fact_count = date_count * expected_facts_per_date
        if counts["facts"] != expected_fact_count:
            errors.append(f"fact count mismatch: expected {expected_fact_count}, found {counts['facts']}")

        if errors:
            raise ValueError("; ".join(errors))
        return {
            "result": "PASS",
            "counts": counts,
            "dateRange": {"min": date_min, "max": date_max},
            "dateCount": date_count,
            "factsPerDate": expected_facts_per_date,
        }
    finally:
        connection.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    args = parser.parse_args()
    print(json.dumps(validate_database(args.database), ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
