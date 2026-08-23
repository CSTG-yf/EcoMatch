#!/usr/bin/env python3
"""Score synthetic-360 Agent captures by structured facts, not answer wording."""

from __future__ import annotations

import argparse
import json
import math
import numbers
import re
from collections import Counter
from pathlib import Path
from typing import Any


class SyntheticEvaluationError(ValueError):
    """The synthetic capture cannot be scored safely."""


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _equal_value(expected: Any, actual: Any, tolerance: float = 0.000001) -> bool:
    if (
        isinstance(expected, numbers.Real)
        and not isinstance(expected, bool)
        and isinstance(actual, numbers.Real)
        and not isinstance(actual, bool)
    ):
        return abs(float(expected) - float(actual)) <= tolerance
    return expected == actual


def _matches_expected(expected: dict[str, Any], columns: Any, rows: Any) -> bool:
    if expected.get("columns") != columns or not isinstance(rows, list):
        return False
    expected_rows = expected.get("rows")
    if not isinstance(expected_rows, list) or len(expected_rows) != len(rows):
        return False
    normalized_expected = sorted(expected_rows, key=lambda row: json.dumps(row, ensure_ascii=False))
    normalized_actual = sorted(rows, key=lambda row: json.dumps(row, ensure_ascii=False))
    return all(
        isinstance(actual_row, list)
        and len(expected_row) == len(actual_row)
        and all(_equal_value(expected_value, actual_value) for expected_value, actual_value in zip(expected_row, actual_row))
        for expected_row, actual_row in zip(normalized_expected, normalized_actual)
    )


def _recognized_metric(item: dict[str, Any], expected_code: str) -> bool:
    columns = item.get("resultColumns")
    rows = item.get("resultRows")
    if isinstance(columns, list) and isinstance(rows, list):
        normalized = [str(column).casefold() for column in columns]
        if "metric_code" in normalized:
            index = normalized.index("metric_code")
            values = [
                str(row[index]).casefold()
                for row in rows
                if isinstance(row, list) and len(row) > index
            ]
            return bool(values) and all(value == expected_code.casefold() for value in values)

    # Semantic models commonly compile a point metric to a conditional aggregate,
    # returning an alias such as `cnb001` instead of the raw metric_code column.
    sql = item.get("physicalSql")
    if not isinstance(sql, str):
        return False
    codes = [value.casefold() for value in re.findall(
        r"\bmetric_code\s*=\s*['\"]([A-Za-z0-9_-]+)['\"]", sql, flags=re.IGNORECASE
    )]
    return bool(codes) and all(value == expected_code.casefold() for value in codes)


def _sql_has_literal(sql: str, column: str, value: Any) -> bool:
    escaped_value = re.escape(str(value))
    pattern = rf"\b{re.escape(column)}\s*=\s*['\"]{escaped_value}['\"]"
    return re.search(pattern, sql, flags=re.IGNORECASE) is not None


def _matches_point_query(expected: dict[str, Any], item: dict[str, Any]) -> bool:
    """Match a point query whose semantic SQL returns one aggregate value."""

    if expected.get("queryType") != "POINT_QUERY":
        return False
    expected_rows = expected.get("expected", {}).get("rows")
    expected_columns = expected.get("expected", {}).get("columns")
    if not isinstance(expected_rows, list) or len(expected_rows) != 1:
        return False
    if not isinstance(expected_columns, list) or not isinstance(expected_rows[0], list):
        return False
    expected_row = expected_rows[0]
    try:
        value_index = [str(column).casefold() for column in expected_columns].index(
            "metric_value"
        )
        org_index = [str(column).casefold() for column in expected_columns].index("org_code")
        date_index = [str(column).casefold() for column in expected_columns].index("data_date")
    except ValueError:
        return False
    if max(value_index, org_index, date_index) >= len(expected_row):
        return False

    actual_rows = item.get("resultRows")
    if not isinstance(actual_rows, list) or len(actual_rows) != 1:
        return False
    actual_row = actual_rows[0]
    if not isinstance(actual_row, list) or len(actual_row) != 1:
        return False
    if not _equal_value(expected_row[value_index], actual_row[0]):
        return False

    physical_sql = item.get("physicalSql")
    if not isinstance(physical_sql, str):
        return False
    if not _sql_has_literal(physical_sql, "data_date", expected_row[date_index]):
        return False
    if not _sql_has_literal(physical_sql, "org_code", expected_row[org_index]):
        return False
    return _recognized_metric(item, str(expected.get("metricCode", "")))


def _latency_summary(values: list[float]) -> dict[str, Any]:
    samples = sorted(value for value in values if math.isfinite(value) and value >= 0)
    if not samples:
        return {"count": 0, "mean": None, "p50": None, "p95": None, "p99": None}

    def percentile(fraction: float) -> float:
        index = max(0, min(len(samples) - 1, math.ceil(fraction * len(samples)) - 1))
        return round(samples[index], 3)

    return {
        "count": len(samples),
        "mean": round(sum(samples) / len(samples), 3),
        "p50": percentile(0.50),
        "p95": percentile(0.95),
        "p99": percentile(0.99),
    }


def evaluate_capture(
    gold_records: list[dict[str, Any]], capture_report: dict[str, Any]
) -> dict[str, Any]:
    gold_by_id = {record["id"]: record for record in gold_records}
    if len(gold_by_id) != len(gold_records):
        raise SyntheticEvaluationError("duplicate gold IDs")
    items = capture_report.get("items")
    if not isinstance(items, list):
        raise SyntheticEvaluationError("capture report must contain items")
    item_by_id: dict[str, dict[str, Any]] = {}
    for item in items:
        sample_id = item.get("id") if isinstance(item, dict) else None
        if not isinstance(sample_id, str) or not sample_id:
            raise SyntheticEvaluationError("capture item must have a non-empty id")
        if sample_id in item_by_id:
            raise SyntheticEvaluationError(f"duplicate capture id: {sample_id}")
        item_by_id[sample_id] = item

    counts = Counter()
    errors = Counter()
    latencies: list[float] = []
    details: list[dict[str, Any]] = []
    for gold in gold_records:
        counts["gold"] += 1
        item = item_by_id.get(gold["id"])
        metric_ok = False
        execute_ok = False
        result_ok = False
        if item is None:
            category = "MISSING_CAPTURE"
        else:
            execute_ok = item.get("execute") is True
            metric_ok = _recognized_metric(item, gold["metricCode"])
            result_ok = execute_ok and (
                _matches_expected(
                    gold["expected"], item.get("resultColumns"), item.get("resultRows")
                )
                or _matches_point_query(gold, item)
            )
            latency = item.get("endToEndMs")
            if isinstance(latency, (int, float)) and not isinstance(latency, bool):
                latencies.append(float(latency))
            if not item.get("parse"):
                category = str(item.get("errorCategory") or "PARSE_ERROR")
            elif not execute_ok:
                category = str(item.get("errorCategory") or "EXECUTION_ERROR")
            elif not metric_ok:
                category = "METRIC_MISMATCH"
            elif not result_ok:
                category = "RESULT_MISMATCH"
            else:
                category = "NONE"
        counts["metric"] += int(metric_ok)
        counts["execute"] += int(execute_ok)
        counts["result"] += int(result_ok)
        errors[category] += 1
        details.append(
            {
                "id": gold["id"],
                "metricCode": gold["metricCode"],
                "metricRecognized": metric_ok,
                "execute": execute_ok,
                "resultCorrect": result_ok,
                "errorCategory": None if category == "NONE" else category,
            }
        )

    unmatched = sorted(set(item_by_id) - set(gold_by_id))
    return {
        "dataset": "synthetic_360",
        "dataOrigin": "SYNTHETIC",
        "metricCount": len({record["metricCode"] for record in gold_records}),
        "questionCount": counts["gold"],
        "metricRecognitionAccuracy": _rate(counts["metric"], counts["gold"]),
        "sqlExecutionSuccessRate": _rate(counts["execute"], counts["gold"]),
        "resultAccuracy": _rate(counts["result"], counts["gold"]),
        "latencyMs": _latency_summary(latencies),
        "errorCategories": {
            name: value for name, value in sorted(errors.items()) if name != "NONE"
        },
        "unmatchedCaptureIds": unmatched,
        "items": details,
        "warning": "合成数据成绩不属于官方21项Fact v3成绩，也不代表真实银行生产数据。",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--split", choices=("train", "dev", "test"))
    parser.add_argument("--max-records", type=int)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.max_records is not None and args.max_records < 1:
        parser.error("--max-records must be positive")
    gold = _read_jsonl(args.gold)
    if args.split:
        gold = [record for record in gold if record.get("split") == args.split]
    if args.max_records is not None:
        gold = gold[: args.max_records]
    capture = json.loads(args.capture.read_text(encoding="utf-8"))
    report = evaluate_capture(gold, capture)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("questionCount", "metricRecognitionAccuracy", "sqlExecutionSuccessRate", "resultAccuracy")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
