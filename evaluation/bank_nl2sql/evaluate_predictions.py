#!/usr/bin/env python3
"""Blindly score predicted SQL against the held-out DATA-02 gold results."""

from __future__ import annotations

import argparse
import json
import numbers
import sqlite3
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import sqlparse


class PredictionEvaluationError(ValueError):
    """The prediction file cannot be evaluated safely or unambiguously."""


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise PredictionEvaluationError(f"Missing JSONL file: {path}")
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _is_read_only_query(sql: Any) -> bool:
    if not isinstance(sql, str) or not sql.strip():
        return False
    statements = [statement for statement in sqlparse.parse(sql) if str(statement).strip()]
    if len(statements) != 1:
        return False
    statement = statements[0]
    first_token = next((token for token in statement.flatten() if not token.is_whitespace), None)
    if first_token is None or first_token.normalized.upper() not in {"SELECT", "WITH"}:
        return False
    forbidden = {"INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "ATTACH", "PRAGMA", "REPLACE"}
    return not any(token.normalized.upper() in forbidden for token in statement.flatten())


def _json_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def _equal_value(expected: Any, actual: Any, tolerance: float) -> bool:
    if isinstance(expected, numbers.Real) and not isinstance(expected, bool) and isinstance(actual, numbers.Real) and not isinstance(actual, bool):
        return abs(float(expected) - float(actual)) <= tolerance
    return expected == actual


def _matches_expected(expected: dict[str, Any], columns: list[str], rows: list[list[Any]]) -> bool:
    if expected.get("columns") != columns:
        return False
    expected_rows = expected.get("rows", [])
    if len(expected_rows) != len(rows):
        return False
    tolerance = float(expected.get("numericTolerance") or 0.0)
    if not expected.get("orderSensitive", False):
        expected_rows = sorted(expected_rows, key=lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True))
        rows = sorted(rows, key=lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True))
    return all(
        len(expected_row) == len(actual_row)
        and all(_equal_value(expected_value, actual_value, tolerance) for expected_value, actual_value in zip(expected_row, actual_row))
        for expected_row, actual_row in zip(expected_rows, rows)
    )


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def evaluate_prediction_file(
    dataset_path: Path | str, prediction_path: Path | str, database_path: Path | str
) -> dict[str, Any]:
    """Evaluate a prediction JSONL of ``{"id", "sql"}`` records against test gold.

    Gold is loaded only from ``test.jsonl`` inside this evaluator.  The
    prediction input is never enriched with gold SQL, rows, or answer text.
    """

    dataset_path = Path(dataset_path).resolve()
    prediction_path = Path(prediction_path).resolve()
    database_path = Path(database_path).resolve()
    gold_records = _read_jsonl(dataset_path / "test.jsonl")
    gold_by_id = {record["id"]: record for record in gold_records}
    if len(gold_by_id) != len(gold_records):
        raise PredictionEvaluationError("Duplicate IDs in held-out gold")
    predictions = _read_jsonl(prediction_path)
    prediction_by_id: dict[str, dict[str, Any]] = {}
    for prediction in predictions:
        sample_id = prediction.get("id")
        if not isinstance(sample_id, str) or not sample_id:
            raise PredictionEvaluationError("Each prediction must have a non-empty id")
        if sample_id in prediction_by_id:
            raise PredictionEvaluationError(f"Duplicate prediction id: {sample_id}")
        prediction_by_id[sample_id] = prediction

    per_item: list[dict[str, Any]] = []
    counters = Counter()
    by_difficulty: dict[str, Counter[str]] = defaultdict(Counter)
    by_feature: dict[str, Counter[str]] = defaultdict(Counter)
    connection = sqlite3.connect(database_path)
    try:
        for gold in gold_records:
            sample_id = gold["id"]
            prediction = prediction_by_id.get(sample_id)
            difficulty = str(gold.get("difficulty", "UNSPECIFIED"))
            features = gold.get("sqlFeatures", []) or ["UNSPECIFIED"]
            outcome = {"id": sample_id, "parse": False, "execute": False, "match": False, "latencyMs": None, "errorCategory": None}
            if prediction is None:
                outcome["errorCategory"] = "MISSING_PREDICTION"
            elif not _is_read_only_query(prediction.get("sql")):
                outcome["errorCategory"] = "PARSE_OR_SAFETY_ERROR"
            else:
                outcome["parse"] = True
                started = time.perf_counter()
                try:
                    cursor = connection.execute(prediction["sql"])
                    columns = [column[0] for column in cursor.description]
                    rows = [[_json_value(value) for value in row] for row in cursor.fetchall()]
                    outcome["execute"] = True
                    outcome["match"] = _matches_expected(gold.get("expected", {}), columns, rows)
                    outcome["errorCategory"] = None if outcome["match"] else "RESULT_MISMATCH"
                except sqlite3.Error:
                    outcome["errorCategory"] = "EXECUTION_ERROR"
                finally:
                    outcome["latencyMs"] = round((time.perf_counter() - started) * 1000, 3)
            counters["gold"] += 1
            counters["parse"] += int(outcome["parse"])
            counters["execute"] += int(outcome["execute"])
            counters["match"] += int(outcome["match"])
            counters[f"error:{outcome['errorCategory'] or 'NONE'}"] += 1
            by_difficulty[difficulty]["gold"] += 1
            by_difficulty[difficulty]["match"] += int(outcome["match"])
            for feature in features:
                by_feature[str(feature)]["gold"] += 1
                by_feature[str(feature)]["match"] += int(outcome["match"])
            per_item.append(outcome)
    finally:
        connection.close()

    unmatched = sorted(set(prediction_by_id) - set(gold_by_id))
    return {
        "goldCount": counters["gold"],
        "predictionCount": len(predictions),
        "matchedGoldCount": counters["match"],
        "metrics": {
            "parseSuccessRate": _rate(counters["parse"], counters["gold"]),
            "executionSuccessRate": _rate(counters["execute"], counters["gold"]),
            "resultAccuracy": _rate(counters["match"], counters["gold"]),
        },
        "byDifficulty": {
            name: {"count": value["gold"], "resultAccuracy": _rate(value["match"], value["gold"])}
            for name, value in sorted(by_difficulty.items())
        },
        "bySqlFeature": {
            name: {"count": value["gold"], "resultAccuracy": _rate(value["match"], value["gold"])}
            for name, value in sorted(by_feature.items())
        },
        "errorCategories": {
            name.removeprefix("error:"): count for name, count in sorted(counters.items()) if name.startswith("error:")
        },
        "unmatchedPredictionIds": unmatched,
        "items": per_item,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("predictions", type=Path, help="JSONL records containing id and predicted sql")
    parser.add_argument("database", type=Path)
    parser.add_argument("--report", type=Path, help="Optional JSON report output")
    args = parser.parse_args()
    report = evaluate_prediction_file(args.dataset, args.predictions, args.database)
    if args.report:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
