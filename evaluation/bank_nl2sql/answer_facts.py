#!/usr/bin/env python3
"""Typed answer-fact validation and deterministic result evaluation."""

from __future__ import annotations

import math
import numbers
import re
from decimal import Decimal, ROUND_HALF_UP
from typing import Any


METRIC_CODE = re.compile(r"^(?:ZB\d{3}|DERIVED_[A-Z0-9_]+)$")
ORGANIZATION_CODE = re.compile(r"^ORG\d{3}$")
FACT_KINDS = {"NUMBER", "PERCENT", "RANK", "TOTAL_COUNT"}
COMPARISON_TYPES = {
    "POINT",
    "SUM",
    "DIFFERENCE",
    "RATIO",
    "MEAN",
    "COUNT",
    "CHANGE",
    "PROVINCE_COMPARISON",
}
OPERATIONS = {
    "DIRECT",
    "SUM",
    "DIFFERENCE",
    "ABS_DIFFERENCE",
    "RATIO_PERCENT",
    "MEAN",
    "COUNT",
    "ROUND",
}


def _numeric(value: Any) -> bool:
    return isinstance(value, numbers.Real) and not isinstance(value, bool) and math.isfinite(float(value))


def _close(left: float, right: float, tolerance: float) -> bool:
    return math.isclose(left, right, rel_tol=1e-6, abs_tol=tolerance)


def _operand_values(
    operand: dict[str, Any], columns: list[str], rows: list[list[Any]]
) -> list[float]:
    nested = operand.get("formula")
    if isinstance(nested, dict):
        value = evaluate_formula(nested, columns, rows)
        return [] if value is None else [value]
    column = operand.get("column")
    where = operand.get("where", {})
    if not isinstance(column, str) or column not in columns or not isinstance(where, dict):
        return []
    value_index = columns.index(column)
    filters: list[tuple[int, Any]] = []
    for name, expected_value in where.items():
        if not isinstance(name, str) or name not in columns:
            return []
        filters.append((columns.index(name), expected_value))
    values: list[float] = []
    for row in rows:
        if not isinstance(row, (list, tuple)) or value_index >= len(row):
            continue
        if any(index >= len(row) or row[index] != expected_value for index, expected_value in filters):
            continue
        value = row[value_index]
        if _numeric(value):
            values.append(float(value))
    return values


def evaluate_formula(
    formula: dict[str, Any], columns: list[str], rows: list[list[Any]]
) -> float | None:
    operation = formula.get("operation")
    operands = formula.get("operands")
    if operation not in OPERATIONS or not isinstance(operands, list) or not operands:
        return None
    operand_values = [
        _operand_values(operand, columns, rows) if isinstance(operand, dict) else []
        for operand in operands
    ]
    if any(not values for values in operand_values):
        return None
    if operation == "DIRECT":
        return operand_values[0][0] if len(operand_values) == 1 and len(operand_values[0]) == 1 else None
    if operation == "ROUND":
        if len(operand_values) != 1 or len(operand_values[0]) != 1:
            return None
        scale = formula.get("scale")
        if not isinstance(scale, int) or isinstance(scale, bool) or not 0 <= scale <= 12:
            return None
        quantum = Decimal(1).scaleb(-scale)
        return float(Decimal(str(operand_values[0][0])).quantize(quantum, rounding=ROUND_HALF_UP))
    if operation == "SUM":
        return sum(value for values in operand_values for value in values)
    if operation == "COUNT":
        return float(sum(len(values) for values in operand_values))
    if operation == "MEAN":
        values = [value for values in operand_values for value in values]
        return sum(values) / len(values)
    if len(operand_values) != 2 or any(len(values) != 1 for values in operand_values):
        return None
    left, right = operand_values[0][0], operand_values[1][0]
    if operation == "DIFFERENCE":
        return left - right
    if operation == "ABS_DIFFERENCE":
        return abs(left - right)
    if operation == "RATIO_PERCENT":
        return None if right == 0 else left / right * 100.0
    return None


def _validate_formula(
    formula: Any,
    organizations: list[str],
    metrics: list[str],
    dates: list[str],
    *,
    depth: int = 0,
) -> str | None:
    if not isinstance(formula, dict) or depth > 8:
        return "INVALID_FORMULA"
    operation = formula.get("operation")
    operands = formula.get("operands")
    if operation not in OPERATIONS or not isinstance(operands, list) or not operands:
        return "INVALID_FORMULA"
    scale = formula.get("scale")
    if operation == "ROUND":
        if (
            not isinstance(scale, int)
            or isinstance(scale, bool)
            or not 0 <= scale <= 12
            or len(operands) != 1
        ):
            return "INVALID_FORMULA"
    elif "scale" in formula:
        return "INVALID_FORMULA"
    for operand in operands:
        if not isinstance(operand, dict):
            return "INVALID_FORMULA"
        if "formula" in operand:
            if set(operand) != {"formula"}:
                return "INVALID_FORMULA"
            nested_error = _validate_formula(
                operand["formula"], organizations, metrics, dates, depth=depth + 1
            )
            if nested_error:
                return nested_error
            continue
        column = operand.get("column")
        where = operand.get("where", {})
        if not isinstance(column, str) or not column.strip() or not isinstance(where, dict):
            return "INVALID_FORMULA"
        if (
            ("org_code" in where and where["org_code"] not in organizations)
            or ("metric_code" in where and where["metric_code"] not in metrics)
            or ("data_date" in where and where["data_date"] not in dates)
        ):
            return "FORMULA_OUTSIDE_BINDING"
    return None


def _formula_result_within_binding(
    formula: dict[str, Any],
    columns: list[str],
    rows: list[list[Any]],
    organizations: list[str],
    metrics: list[str],
    dates: list[str],
) -> bool:
    """Reject captured rows that contradict an answer fact's identity binding.

    Some result contracts intentionally omit one or more identity columns.  An
    omitted identity remains outside this row-level proof, but every identity
    column that is present must agree with the binding for every row consumed
    by the formula operand.  This prevents a numerically equal value from a
    different organization, metric, or date from satisfying the fact.
    """

    allowed_by_column = {
        "org_code": set(organizations),
        "metric_code": set(metrics),
        "data_date": set(dates),
    }
    for operand in formula.get("operands", []):
        nested = operand.get("formula") if isinstance(operand, dict) else None
        if isinstance(nested, dict):
            if not _formula_result_within_binding(
                nested, columns, rows, organizations, metrics, dates
            ):
                return False
            continue
        if not isinstance(operand, dict):
            continue
        where = operand.get("where", {})
        if not isinstance(where, dict) or any(name not in columns for name in where):
            continue
        filters = [(columns.index(name), value) for name, value in where.items()]
        matched_rows = [
            row
            for row in rows
            if isinstance(row, (list, tuple))
            and all(index < len(row) and row[index] == value for index, value in filters)
        ]
        for identity_column, allowed_values in allowed_by_column.items():
            if identity_column not in columns:
                continue
            identity_index = columns.index(identity_column)
            if any(
                identity_index >= len(row) or row[identity_index] not in allowed_values
                for row in matched_rows
            ):
                return False
    return True


def validate_answer_facts(
    raw_facts: Any,
    expected: dict[str, Any],
    *,
    default_tolerance: float,
    require_result_match: bool = True,
) -> tuple[list[dict[str, Any]], list[str]]:
    """Validate identity bindings and prove every declared value from result rows."""

    if not isinstance(raw_facts, list) or not raw_facts:
        return [], ["INVALID_TYPED_ANSWER_FACTS"]
    columns = [str(column) for column in expected.get("columns", [])] if isinstance(expected.get("columns"), list) else []
    rows = expected.get("rows") if isinstance(expected.get("rows"), list) else []
    tolerance_raw = expected.get("numericTolerance")
    tolerance = float(tolerance_raw) if _numeric(tolerance_raw) else default_tolerance
    facts: list[dict[str, Any]] = []
    errors: list[str] = []
    seen_ids: set[str] = set()
    for index, raw in enumerate(raw_facts):
        prefix = f"ANSWER_FACT_{index}"
        if not isinstance(raw, dict):
            errors.append(f"{prefix}_INVALID")
            continue
        fact_id = raw.get("id")
        value = raw.get("value")
        kind = raw.get("kind")
        binding = raw.get("binding")
        formula = raw.get("formula")
        if not isinstance(fact_id, str) or not fact_id.strip() or fact_id in seen_ids:
            errors.append(f"{prefix}_INVALID_ID")
        else:
            seen_ids.add(fact_id)
        if not _numeric(value) or kind not in FACT_KINDS:
            errors.append(f"{prefix}_INVALID_VALUE_OR_KIND")
            continue
        if not isinstance(binding, dict):
            errors.append(f"{prefix}_INVALID_BINDING")
            continue
        organizations = binding.get("organizationCodes")
        metrics = binding.get("metricCodes")
        dates = binding.get("dates")
        comparison_type = binding.get("comparisonType")
        if (
            not isinstance(organizations, list)
            or not organizations
            or not all(isinstance(code, str) and ORGANIZATION_CODE.fullmatch(code) for code in organizations)
            or len(set(organizations)) != len(organizations)
            or not isinstance(metrics, list)
            or not metrics
            or not all(isinstance(code, str) and METRIC_CODE.fullmatch(code) for code in metrics)
            or len(set(metrics)) != len(metrics)
            or not isinstance(dates, list)
            or not dates
            or not all(isinstance(date, str) and date.strip() for date in dates)
            or len(set(dates)) != len(dates)
            or comparison_type not in COMPARISON_TYPES
        ):
            errors.append(f"{prefix}_INVALID_BINDING")
            continue
        formula_error = _validate_formula(formula, organizations, metrics, dates)
        if formula_error:
            errors.append(f"{prefix}_{formula_error}")
            continue
        if require_result_match and not _formula_result_within_binding(
            formula, columns, rows, organizations, metrics, dates
        ):
            errors.append(f"{prefix}_FORMULA_RESULT_OUTSIDE_BINDING")
            continue
        calculated = evaluate_formula(formula, columns, rows)
        support = "TYPED_RESULT"
        if require_result_match and (
            calculated is None or not _close(float(value), calculated, tolerance)
        ):
            errors.append(f"{prefix}_FORMULA_MISMATCH")
            support = "MISSING"
        facts.append(
            {
                "value": float(value),
                "kind": str(kind),
                "raw": str(raw.get("raw") or value),
                "required": bool(raw.get("required", True)),
                "support": support,
                "derivation": str(formula.get("operation") or ""),
                "evidence": {"id": fact_id, "binding": binding, "formula": formula},
            }
        )
    return facts, errors
