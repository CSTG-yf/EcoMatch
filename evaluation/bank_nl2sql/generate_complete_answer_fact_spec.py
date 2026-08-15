#!/usr/bin/env python3
"""Generate a fail-closed typed answer-fact spec for every official record."""

from __future__ import annotations

import argparse
import copy
import json
import math
import numbers
import re
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any

from answer_facts import validate_answer_facts
from fact_contract_v3 import FactDraft, build_fact_contract


class CompleteAnswerFactSpecError(ValueError):
    pass


_ORG = re.compile(r"\bORG\d{3}\b")
_METRIC = re.compile(r"(?:\bZB\d{3}\b|\bDERIVED_[A-Z0-9_]+\b)")
_DATE = re.compile(r"\b20\d{2}-\d{2}-\d{2}\b")
_IDENTITY_COLUMNS = ("org_code", "metric_code", "data_date", "comparison_type")
_COLUMN_PRIORITY = {
    "RANK": ("rank_position",),
    "DELTA_ABS": ("rank_change", "absolute_change", "value_difference"),
    "SUM": ("combined_rate", "aggregate_value"),
    "TOTAL_COUNT": (
        "days_above_province_average",
        "days_above_average",
        "observation_count",
        "total_days",
    ),
    "PERCENT": (
        "combined_rate",
        "value_difference",
        "provincial_average",
        "percent_change",
        "ratio_percent",
        "above_ratio_percent",
        "gap_value",
        "absolute_gap",
        "metric_value",
        "aggregate_value",
        "current_value",
    ),
    "NUMBER": (
        "absolute_change",
        "value_difference",
        "absolute_gap",
        "gap_value",
        "metric_value",
        "aggregate_value",
        "current_value",
        "baseline_value",
        "daily_average",
        "per_capita_profit",
    ),
}
_INTENTS_TO_COMPARISON = {
    "CHANGE": "CHANGE",
    "THRESHOLD": "COUNT",
    "AGGREGATION": "MEAN",
    "RANKING": "POINT",
}


def _scale(raw: str) -> int:
    token = str(raw).strip()
    return len(token.rsplit(".", 1)[1]) if "." in token else 0


def _rounded(value: float, scale: int) -> float:
    quantum = Decimal(1).scaleb(-scale)
    return float(Decimal(str(value)).quantize(quantum, rounding=ROUND_HALF_UP))


def _matches_display(fact: FactDraft, value: float) -> bool:
    rounded = _rounded(value, _scale(fact.raw))
    return math.isclose(abs(rounded), abs(float(fact.value)), rel_tol=0.0, abs_tol=1e-12)


def _codes(record: dict[str, Any], columns: list[str], rows: list[list[Any]]) -> tuple[list[str], list[str], list[str]]:
    intent = record.get("normalizedIntent") if isinstance(record.get("normalizedIntent"), dict) else {}
    sql = str(record.get("sql") or record.get("s2sql") or "")
    organizations = {
        str(item.get("code"))
        for item in intent.get("organizations", [])
        if isinstance(item, dict) and _ORG.fullmatch(str(item.get("code") or ""))
    }
    metrics = {
        str(item.get("code"))
        for item in intent.get("metrics", [])
        if isinstance(item, dict) and _METRIC.fullmatch(str(item.get("code") or ""))
    }
    dates = set(_DATE.findall(sql))
    for name, target, pattern in (
        ("org_code", organizations, _ORG),
        ("metric_code", metrics, _METRIC),
        ("data_date", dates, _DATE),
    ):
        if name not in columns:
            continue
        index = columns.index(name)
        target.update(
            str(row[index])
            for row in rows
            if isinstance(row, (list, tuple))
            and index < len(row)
            and pattern.fullmatch(str(row[index]))
        )
    organizations.update(_ORG.findall(sql))
    metrics.update(_METRIC.findall(sql))
    return sorted(organizations), sorted(metrics), sorted(dates)


def _binding(record: dict[str, Any], columns: list[str], rows: list[list[Any]]) -> dict[str, Any]:
    organizations, metrics, dates = _codes(record, columns, rows)
    intent = record.get("normalizedIntent") if isinstance(record.get("normalizedIntent"), dict) else {}
    comparison = _INTENTS_TO_COMPARISON.get(str(intent.get("intent") or ""), "POINT")
    if not organizations or not metrics or not dates:
        raise CompleteAnswerFactSpecError(
            f"{record.get('id')}: identity binding is incomplete"
        )
    return {
        "organizationCodes": organizations,
        "metricCodes": metrics,
        "dates": dates,
        "comparisonType": comparison,
    }


def _candidate_formula(
    fact: FactDraft,
    columns: list[str],
    rows: list[list[Any]],
) -> tuple[float, dict[str, Any]] | None:
    candidates: list[tuple[int, int, str, float, dict[str, Any]]] = []
    preferred = _COLUMN_PRIORITY.get(fact.kind, ())
    for row_index, row in enumerate(rows):
        if not isinstance(row, (list, tuple)):
            continue
        for column_index, cell in enumerate(row):
            if (
                column_index >= len(columns)
                or not isinstance(cell, numbers.Real)
                or isinstance(cell, bool)
                or not math.isfinite(float(cell))
                or not _matches_display(fact, float(cell))
            ):
                continue
            column = columns[column_index]
            where = {
                name: row[columns.index(name)]
                for name in _IDENTITY_COLUMNS
                if name in columns and columns.index(name) < len(row)
            }
            priority = preferred.index(column) if column in preferred else len(preferred) + column_index
            candidates.append((priority, row_index, column, float(cell), where))
    if not candidates:
        return None
    candidates.sort(key=lambda item: (item[0], item[1], item[2]))
    _, _, column, value, where = candidates[0]
    direct: dict[str, Any] = {
        "operation": "DIRECT",
        "operands": [{"column": column, "where": where}],
    }
    scale = _scale(fact.raw)
    rounded = _rounded(value, scale)
    formula = (
        direct
        if math.isclose(value, rounded, rel_tol=0.0, abs_tol=1e-12)
        else {"operation": "ROUND", "scale": scale, "operands": [{"formula": direct}]}
    )
    return rounded, formula


def _generated_facts(record: dict[str, Any]) -> list[dict[str, Any]]:
    expected = record.get("expected") if isinstance(record.get("expected"), dict) else {}
    columns = [str(column) for column in expected.get("columns", [])]
    rows = expected.get("rows") if isinstance(expected.get("rows"), list) else []
    existing = expected.get("answerFacts")
    retained: list[dict[str, Any]] = []
    if isinstance(existing, list) and existing:
        validated, errors = validate_answer_facts(
            existing,
            expected,
            default_tolerance=1e-6,
            require_result_match=True,
        )
        if not errors and len(validated) == len(existing):
            retained = copy.deepcopy(existing)
    binding = _binding(record, columns, rows)
    # Rebuild stale contracts from the canonical answer and the newly
    # materialized result.  Valid contracts may contain reviewed formulas that
    # cannot be inferred from a flat result table and are retained byte-for-byte.
    source_record = copy.deepcopy(record)
    source_record["expected"].pop("answerFacts", None)
    contract = build_fact_contract(source_record)
    generated: list[dict[str, Any]] = retained
    retained_count = len(retained)

    claimed_existing: set[int] = set()
    seen_source_values: list[FactDraft] = []

    def claim_existing(fact: FactDraft) -> bool:
        for index, item in enumerate(generated[:retained_count]):
            if index in claimed_existing:
                continue
            kind = str(item.get("kind") or "")
            compatible_kind = kind == fact.kind or {kind, fact.kind} == {
                "NUMBER",
                "TOTAL_COUNT",
            }
            value = item.get("value")
            if (
                compatible_kind
                and isinstance(value, numbers.Real)
                and not isinstance(value, bool)
                and _matches_display(fact, float(value))
            ):
                claimed_existing.add(index)
                return True
        return False

    next_index = len(generated) + 1
    for fact in (item for item in contract.facts if item.required):
        # The source answer may repeat the same displayed value (for example a
        # component sum and its equal total).  Scoring one proven numeric fact
        # is sufficient; distinct values may never be dropped.
        if any(
            previous.kind == fact.kind
            and math.isclose(
                previous.value,
                fact.value,
                rel_tol=0.0,
                abs_tol=1e-12,
            )
            for previous in seen_source_values
        ):
            continue
        seen_source_values.append(fact)
        if claim_existing(fact):
            continue
        if fact.derivation == "COUNT_TRUE" and "meets_condition" in columns:
            value = float(fact.value)
            formula = {"operation": "SUM", "operands": [{"column": "meets_condition"}]}
            kind = "TOTAL_COUNT"
        else:
            candidate = _candidate_formula(fact, columns, rows)
            if candidate is None:
                raise CompleteAnswerFactSpecError(
                    f"{record.get('id')}: required fact {fact.raw} has no deterministic result formula"
                )
            value, formula = candidate
            kind = fact.kind
        generated.append(
            {
                "id": f"answer_{next_index:03d}",
                "value": value,
                "kind": kind,
                "binding": binding,
                "formula": formula,
            }
        )
        next_index += 1
    if not generated:
        raise CompleteAnswerFactSpecError(f"{record.get('id')}: no required answer facts")
    validated, errors = validate_answer_facts(
        generated,
        expected,
        default_tolerance=1e-6,
        require_result_match=True,
    )
    if errors or len(validated) != len(generated):
        raise CompleteAnswerFactSpecError(f"{record.get('id')}: invalid generated facts {errors}")
    return generated


def build_complete_spec(dataset: Path, output: Path, *, parent_version: str, target_version: str) -> dict[str, Any]:
    if output.exists():
        raise CompleteAnswerFactSpecError(f"output already exists: {output}")
    contracts: list[dict[str, Any]] = []
    failures: list[str] = []
    for split in ("train", "dev", "test"):
        for line in (dataset / f"{split}.jsonl").read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            record = json.loads(line)
            try:
                facts = _generated_facts(record)
            except CompleteAnswerFactSpecError as exc:
                failures.append(str(exc))
                continue
            contracts.append(
                {
                    "id": record["id"],
                    "reason": "由唯一事实源的结构化结果生成完整答案事实合同",
                    "answerFacts": facts,
                }
            )
    if failures:
        raise CompleteAnswerFactSpecError(
            f"complete contract generation failed for {len(failures)} records: "
            + "; ".join(failures)
        )
    result = {
        "schemaVersion": "1.0",
        "parentVersion": parent_version,
        "targetVersion": target_version,
        "coverageMode": "FULL_OFFICIAL",
        "contracts": contracts,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--parent-version", required=True)
    parser.add_argument("--target-version", required=True)
    args = parser.parse_args()
    result = build_complete_spec(
        args.dataset,
        args.output,
        parent_version=args.parent_version,
        target_version=args.target_version,
    )
    print(json.dumps({"contractCount": len(result["contracts"]), "version": result["targetVersion"]}))


if __name__ == "__main__":
    main()
