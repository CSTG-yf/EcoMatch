#!/usr/bin/env python3
"""Historical structured UI-capture comparison helpers.

The browser runner is responsible for creating a real conversation, submitting
each question and collecting every visible result page.  This module deliberately
does not call the chat APIs.  It is retained for focused UI compatibility tests;
its CLI is retired because it cannot score the final answer under the Fact v3
runtime contract.
"""

from __future__ import annotations

import argparse
import json
import numbers
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from evaluate_predictions import _matches_expected
class UiCaptureEvaluationError(ValueError):
    """A UI capture cannot be safely compared with the evaluation gold."""


_SUCCESS_STATES = {"done", "success"}


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _require_rows(value: Any, *, field: str) -> list[list[Any]]:
    if not isinstance(value, list) or not all(isinstance(row, list) for row in value):
        raise UiCaptureEvaluationError(f"{field} must be a list of rows")
    return value


def _capture_by_id(capture: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], dict[str, list[list[Any]]]]:
    items = capture.get("items")
    if not isinstance(items, list):
        raise UiCaptureEvaluationError("UI capture needs an items list")

    item_by_id: dict[str, dict[str, Any]] = {}
    for item in items:
        if not isinstance(item, dict):
            raise UiCaptureEvaluationError("Every UI capture item must be an object")
        sample_id = item.get("id")
        if not isinstance(sample_id, str) or not sample_id:
            raise UiCaptureEvaluationError("Every UI capture item needs a non-empty id")
        if sample_id in item_by_id:
            raise UiCaptureEvaluationError(f"Duplicate UI capture id: {sample_id}")
        headers = item.get("headers")
        if not isinstance(headers, list) or not all(isinstance(header, str) for header in headers):
            raise UiCaptureEvaluationError(f"UI capture {sample_id} needs string headers")
        _require_rows(item.get("rows"), field=f"UI capture {sample_id} rows")
        item_by_id[sample_id] = item

    supplements = capture.get("paginationSupplements", {})
    if not isinstance(supplements, dict):
        raise UiCaptureEvaluationError("paginationSupplements must be an object")
    normalized_supplements: dict[str, list[list[Any]]] = {}
    for sample_id, rows in supplements.items():
        if not isinstance(sample_id, str) or not sample_id:
            raise UiCaptureEvaluationError("pagination supplement ids must be non-empty strings")
        normalized_supplements[sample_id] = _require_rows(
            rows, field=f"pagination supplement {sample_id}"
        )
    return item_by_id, normalized_supplements


def _coerce_display_value(expected: Any, actual: Any) -> Any:
    if expected is None:
        return None if actual is None or actual == "" else actual
    if isinstance(expected, bool):
        if isinstance(actual, str) and actual.lower() in {"true", "false"}:
            return actual.lower() == "true"
        return actual
    if isinstance(expected, numbers.Real) and not isinstance(expected, bool):
        if isinstance(actual, str):
            try:
                return float(actual.replace(",", "").replace("−", "-"))
            except ValueError:
                return actual
    return actual


def _coerce_display_rows(expected: dict[str, Any], rows: list[list[Any]]) -> list[list[Any]]:
    expected_rows = expected.get("rows")
    if not isinstance(expected_rows, list) or len(expected_rows) != len(rows):
        return rows
    normalized: list[list[Any]] = []
    for expected_row, actual_row in zip(expected_rows, rows):
        if not isinstance(expected_row, list) or len(expected_row) != len(actual_row):
            return rows
        normalized.append(
            [_coerce_display_value(expected_value, actual_value) for expected_value, actual_value in zip(expected_row, actual_row)]
        )
    return normalized


def _group_metrics(items: Iterable[dict[str, Any]], key: str) -> dict[str, dict[str, Any]]:
    grouped: dict[str, Counter[str]] = defaultdict(Counter)
    for item in items:
        values = item.get(key) if key == "sqlFeatures" else [item.get(key, "UNSPECIFIED")]
        for value in values or ["UNSPECIFIED"]:
            counter = grouped[str(value)]
            counter["count"] += 1
            counter["parse"] += int(item["parse"])
            counter["execute"] += int(item["execute"])
            counter["match"] += int(item["match"])
    return {
        name: {
            "count": counter["count"],
            "parseSuccessRate": _rate(counter["parse"], counter["count"]),
            "executionSuccessRate": _rate(counter["execute"], counter["count"]),
            "resultAccuracy": _rate(counter["match"], counter["count"]),
        }
        for name, counter in sorted(grouped.items())
    }


def evaluate_ui_capture(
    records: Iterable[dict[str, Any]], capture: dict[str, Any], *, captured_only: bool = False
) -> dict[str, Any]:
    """Score page-visible results and terminal states against a split's gold rows."""

    if not isinstance(capture, dict):
        raise UiCaptureEvaluationError("UI capture must be a JSON object")
    captured, supplements = _capture_by_id(capture)
    gold_records = list(records)
    if captured_only:
        gold_records = [record for record in gold_records if record.get("id") in captured]
    gold_ids: set[str] = set()
    items: list[dict[str, Any]] = []
    errors: Counter[str] = Counter()

    for record in gold_records:
        sample_id = record.get("id")
        if not isinstance(sample_id, str) or not sample_id:
            raise UiCaptureEvaluationError("Every evaluation record needs a non-empty id")
        if sample_id in gold_ids:
            raise UiCaptureEvaluationError(f"Duplicate evaluation record id: {sample_id}")
        gold_ids.add(sample_id)
        outcome = {
            "id": sample_id,
            "difficulty": str(record.get("difficulty", "UNSPECIFIED")),
            "sqlFeatures": list(record.get("sqlFeatures") or ["UNSPECIFIED"]),
            "pageState": None,
            "parse": False,
            "execute": False,
            "match": False,
            "visibleRowCount": 0,
            "errorCategory": None,
        }
        item = captured.get(sample_id)
        if item is None:
            outcome["errorCategory"] = "MISSING_UI_CAPTURE"
        else:
            state = str(item.get("state", "")).strip().lower()
            outcome["pageState"] = state or None
            rows = list(item["rows"]) + list(supplements.get(sample_id, []))
            outcome["visibleRowCount"] = len(rows)
            if state not in _SUCCESS_STATES:
                outcome["errorCategory"] = "UI_TERMINAL_FAILURE"
            else:
                outcome["parse"] = True
                outcome["execute"] = True
                expected = record.get("expected")
                if not isinstance(expected, dict):
                    raise UiCaptureEvaluationError(f"Evaluation record {sample_id} has invalid expected result")
                normalized_rows = _coerce_display_rows(expected, rows)
                outcome["match"] = _matches_expected(expected, item["headers"], normalized_rows)
                outcome["errorCategory"] = None if outcome["match"] else "RESULT_MISMATCH"
        errors[outcome["errorCategory"] or "NONE"] += 1
        items.append(outcome)

    count = len(items)
    return {
        "capture": {
            "method": str((capture.get("run") or {}).get("captureMethod") or "authenticated-ui"),
            "sourceRecordCount": len(captured),
        },
        "recordCount": count,
        "metrics": {
            "parseSuccessRate": _rate(sum(int(item["parse"]) for item in items), count),
            "executionSuccessRate": _rate(sum(int(item["execute"]) for item in items), count),
            "resultAccuracy": _rate(sum(int(item["match"]) for item in items), count),
        },
        "byDifficulty": _group_metrics(items, "difficulty"),
        "bySqlFeature": _group_metrics(items, "sqlFeatures"),
        "errorCategories": dict(sorted(errors.items())),
        "unmatchedCaptureIds": sorted(set(captured) - gold_ids),
        "items": items,
    }


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise UiCaptureEvaluationError(f"Cannot read UI capture: {path}") from error
    if not isinstance(value, dict):
        raise UiCaptureEvaluationError("UI capture root must be an object")
    return value


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.error(
        "Structured UI-capture scoring is retired. "
        "Use evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1 for Fact v3 caseAccuracy."
    )


if __name__ == "__main__":
    main()
