#!/usr/bin/env python3
"""Repair structured gold for known still-fail incomplete contracts.

Only rewrites ``expected.columns/rows`` and diagnostic ``sql/s2sql`` so that
structured results can prove official ``answerText``. Official answer text is
never modified.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path
from typing import Any


DB_DEFAULT = Path("evaluation/bank_nl2sql/db/releases/2.0.0/bank.sqlite")
TRAIN_DEFAULT = Path("evaluation/bank_nl2sql/train.jsonl")

TARGET_IDS = {
    "TRAIN-H-11",
    "TRAIN-H-16",
    "TRAIN-H-17",
    "TRAIN-H-18",
    "TRAIN-H-31",
    "TRAIN-H-32",
    "TRAIN-H-33",
    "TRAIN-M-31",
    "TRAIN-M-32",
    "TRAIN-M-33",
    "TRAIN-S-24",
}


def _org_map(conn: sqlite3.Connection) -> dict[str, str]:
    return {
        str(code): str(name)
        for code, name in conn.execute("SELECT org_code, org_name FROM bank_organization")
    }


def _mean_ranking(
    conn: sqlite3.Connection,
    *,
    metric_code: str,
    start: str,
    end: str,
    ascending: bool,
) -> list[list[Any]]:
    order = "ASC" if ascending else "DESC"
    rows = conn.execute(
        f"""
        SELECT o.org_code, o.org_name, d.metric_code, AVG(d.metric_value) AS metric_value
        FROM bank_metric_daily d
        JOIN bank_organization o ON o.org_code = d.org_code
        WHERE d.data_date BETWEEN ? AND ?
          AND d.metric_code = ?
        GROUP BY o.org_code, o.org_name, d.metric_code
        ORDER BY metric_value {order}, o.org_code
        """,
        (start, end, metric_code),
    ).fetchall()
    ranked = [
        [code, name, metric, float(value), index]
        for index, (code, name, metric, value) in enumerate(rows, start=1)
    ]
    # Keep top3 + bottom3 like original gold shape.
    if len(ranked) <= 6:
        return ranked
    return ranked[:3] + ranked[-3:]


def _growth_table(
    conn: sqlite3.Connection,
    *,
    metric_code: str,
    baseline_date: str,
    current_date: str,
    top_k: int | None = None,
) -> list[list[Any]]:
    rows = conn.execute(
        """
        WITH baseline AS (
          SELECT org_code, metric_value AS baseline_value
          FROM bank_metric_daily
          WHERE data_date = ? AND metric_code = ?
        ), current AS (
          SELECT org_code, metric_value AS current_value
          FROM bank_metric_daily
          WHERE data_date = ? AND metric_code = ?
        )
        SELECT o.org_code, o.org_name, ?,
               c.current_value, b.baseline_value,
               c.current_value - b.baseline_value AS absolute_change,
               CASE WHEN b.baseline_value = 0 THEN NULL
                    ELSE (c.current_value - b.baseline_value) * 100.0 / b.baseline_value
               END AS percent_change
        FROM baseline b
        JOIN current c ON c.org_code = b.org_code
        JOIN bank_organization o ON o.org_code = b.org_code
        ORDER BY percent_change DESC, o.org_code
        """,
        (baseline_date, metric_code, current_date, metric_code, metric_code),
    ).fetchall()
    table = [
        [
            code,
            name,
            metric,
            float(current),
            float(baseline),
            float(abs_chg),
            None if pct is None else float(pct),
        ]
        for code, name, metric, current, baseline, abs_chg, pct in rows
    ]
    if top_k is not None:
        return table[:top_k]
    return table


def _days_above_province(
    conn: sqlite3.Connection,
    *,
    org_code: str,
    metric_code: str,
    start: str,
    end: str,
) -> list[list[Any]]:
    orgs = _org_map(conn)
    days_above, total_days = conn.execute(
        """
        WITH daily AS (
          SELECT data_date, org_code, metric_value
          FROM bank_metric_daily
          WHERE metric_code = ? AND data_date BETWEEN ? AND ?
        ), provincial AS (
          SELECT data_date, AVG(metric_value) AS provincial_average
          FROM daily
          GROUP BY data_date
        )
        SELECT SUM(CASE WHEN d.metric_value > p.provincial_average THEN 1 ELSE 0 END) AS days_above,
               COUNT(*) AS total_days
        FROM daily d
        JOIN provincial p ON p.data_date = d.data_date
        WHERE d.org_code = ?
        """,
        (metric_code, start, end, org_code),
    ).fetchone()
    days_above = int(days_above or 0)
    total_days = int(total_days or 0)
    ratio = 0.0 if total_days == 0 else days_above * 100.0 / total_days
    return [[
        org_code,
        orgs[org_code],
        metric_code,
        days_above,
        total_days,
        ratio,
    ]]


def _deposit_mix(
    conn: sqlite3.Connection,
    *,
    org_code: str,
    data_date: str,
) -> list[list[Any]]:
    orgs = _org_map(conn)
    values = {
        code: float(value)
        for code, value in conn.execute(
            """
            SELECT metric_code, metric_value
            FROM bank_metric_daily
            WHERE org_code = ? AND data_date = ?
              AND metric_code IN ('ZB001', 'ZB003', 'ZB004')
            """,
            (org_code, data_date),
        )
    }
    total = values["ZB001"]
    rows = []
    for code in ("ZB003", "ZB004", "ZB001"):
        value = values[code]
        ratio = 100.0 if code == "ZB001" else (0.0 if total == 0 else value * 100.0 / total)
        rows.append([org_code, orgs[org_code], code, value, ratio])
    return rows


def _loan_mix(
    conn: sqlite3.Connection,
    *,
    org_code: str,
    data_date: str,
) -> list[list[Any]]:
    orgs = _org_map(conn)
    values = {
        code: float(value)
        for code, value in conn.execute(
            """
            SELECT metric_code, metric_value
            FROM bank_metric_daily
            WHERE org_code = ? AND data_date = ?
              AND metric_code IN ('ZB002', 'ZB005', 'ZB006')
            """,
            (org_code, data_date),
        )
    }
    total = values["ZB002"]
    rows = []
    # Personal first then corporate to mirror answer text order.
    for code, role in (("ZB006", "personal"), ("ZB005", "corporate"), ("ZB002", "total")):
        value = values[code]
        ratio = 100.0 if code == "ZB002" else (0.0 if total == 0 else value * 100.0 / total)
        rows.append([org_code, orgs[org_code], code, role, value, total, ratio])
    return rows


def repair_record(record: dict[str, Any], conn: sqlite3.Connection) -> dict[str, Any]:
    sample_id = record["id"]
    expected = dict(record["expected"])
    if sample_id == "TRAIN-H-11":
        expected["columns"] = [
            "org_code", "org_name", "metric_code", "metric_value", "rank_position"
        ]
        # Lower NPL rate is better: ascending mean ranking.
        expected["rows"] = _mean_ranking(
            conn, metric_code="ZB013", start="2025-01-01", end="2025-12-31", ascending=True
        )
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-H-16":
        expected["columns"] = [
            "org_code",
            "org_name",
            "metric_code",
            "current_value",
            "baseline_value",
            "absolute_change",
            "percent_change",
        ]
        expected["rows"] = _growth_table(
            conn,
            metric_code="ZB001",
            baseline_date="2024-12-31",
            current_date="2026-03-31",
        )
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-H-17":
        expected["columns"] = [
            "org_code",
            "org_name",
            "metric_code",
            "current_value",
            "baseline_value",
            "absolute_change",
            "percent_change",
        ]
        expected["rows"] = _growth_table(
            conn,
            metric_code="ZB002",
            baseline_date="2024-12-31",
            current_date="2026-04-30",
        )
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-H-18":
        expected["columns"] = [
            "org_code",
            "org_name",
            "metric_code",
            "current_value",
            "baseline_value",
            "absolute_change",
            "percent_change",
        ]
        expected["rows"] = _growth_table(
            conn,
            metric_code="ZB011",
            baseline_date="2024-12-31",
            current_date="2026-04-30",
        )
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-H-31":
        expected["columns"] = [
            "org_code",
            "org_name",
            "metric_code",
            "days_above_average",
            "total_days",
            "ratio_percent",
        ]
        expected["rows"] = _days_above_province(
            conn, org_code="ORG002", metric_code="ZB013", start="2025-01-01", end="2025-12-31"
        )
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-H-32":
        expected["columns"] = [
            "org_code",
            "org_name",
            "metric_code",
            "days_above_average",
            "total_days",
            "ratio_percent",
        ]
        expected["rows"] = _days_above_province(
            conn, org_code="ORG007", metric_code="ZB012", start="2025-01-01", end="2025-12-31"
        )
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-H-33":
        expected["columns"] = [
            "org_code",
            "org_name",
            "metric_code",
            "days_above_average",
            "total_days",
            "ratio_percent",
        ]
        expected["rows"] = _days_above_province(
            conn, org_code="ORG012", metric_code="ZB001", start="2025-01-01", end="2025-12-31"
        )
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-M-31":
        expected["columns"] = [
            "org_code", "org_name", "metric_code", "metric_value", "ratio_percent"
        ]
        expected["rows"] = _deposit_mix(conn, org_code="ORG002", data_date="2025-06-30")
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-M-32":
        expected["columns"] = [
            "org_code", "org_name", "metric_code", "metric_value", "ratio_percent"
        ]
        expected["rows"] = _deposit_mix(conn, org_code="ORG007", data_date="2025-07-31")
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-M-33":
        expected["columns"] = [
            "org_code", "org_name", "metric_code", "metric_value", "ratio_percent"
        ]
        expected["rows"] = _deposit_mix(conn, org_code="ORG012", data_date="2025-08-31")
        expected["orderSensitive"] = True
    elif sample_id == "TRAIN-S-24":
        expected["columns"] = [
            "org_code",
            "org_name",
            "metric_code",
            "metric_role",
            "numerator_value",
            "denominator_value",
            "ratio_percent",
        ]
        expected["rows"] = _loan_mix(conn, org_code="ORG007", data_date="2026-03-31")
        expected["orderSensitive"] = True
    else:
        return record

    repaired = dict(record)
    repaired["expected"] = expected
    # Keep answerText identical.
    assert repaired["expected"]["answerText"] == record["expected"]["answerText"]
    return repaired


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-file", type=Path, default=TRAIN_DEFAULT)
    parser.add_argument("--db", type=Path, default=DB_DEFAULT)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    records = [
        json.loads(line)
        for line in args.dataset_file.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    conn = sqlite3.connect(args.db)
    changed = []
    try:
        out_records = []
        for record in records:
            if record.get("id") in TARGET_IDS:
                repaired = repair_record(record, conn)
                if repaired != record:
                    changed.append(record["id"])
                out_records.append(repaired)
            else:
                out_records.append(record)
    finally:
        conn.close()

    if args.dry_run:
        print(json.dumps({"wouldChange": changed, "count": len(changed)}, ensure_ascii=False))
        return

    args.dataset_file.write_text(
        "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in out_records),
        encoding="utf-8",
    )
    print(json.dumps({"changed": changed, "count": len(changed), "path": str(args.dataset_file)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
