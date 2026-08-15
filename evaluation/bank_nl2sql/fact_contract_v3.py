#!/usr/bin/env python3
"""Fact-contract v3 draft builder for bank NL2SQL evaluation.

The v2 ``answerExact`` gate only scores records whose structured table already
contains every number printed in ``answerText``.  That excludes valid derived
answers and can inflate accuracy by shrinking the denominator.  This module
builds a reviewable, full-denominator draft without modifying frozen datasets.

It is intentionally a *dry-run contract builder*.  ``REVIEW_REQUIRED`` records
must not be silently promoted into the official evaluator.
"""

from __future__ import annotations

import math
import numbers
import re
from dataclasses import asdict, dataclass
from typing import Any, Iterable

from answer_contract import (
    DEFAULT_ABS_TOL,
    DEFAULT_REL_TOL,
    assess_gold_contract,
    extract_answer_slots,
    values_close,
)
from answer_facts import evaluate_formula, validate_answer_facts


SCHEMA_VERSION = "3.2-dry-run"

_DATE_TOKEN = re.compile(r"\d{4}-\d{1,2}-\d{1,2}")
_RANK_OUTPUT = re.compile(r"第\s*([1-9]\d*)\s*名")
_TOTAL_COUNT = re.compile(r"共\s*([1-9]\d*)\s*家")
_PROVINCE_MEAN = re.compile(r"(?:全省|省)均值[（(]?\s*(-?\d+(?:\.\d+)?)")
_HIGHER_NEGATIVE = re.compile(r"(?:高出|高于|上升|增长)[^。；]{0,16}-\s*\d+(?:\.\d+)?")
_GOOD_SEGMENT = re.compile(r"表现较好(?:指标)?[:：](.*?)(?:表现较差|$)")
_RANK_REQUEST = re.compile(r"排第几|排名|表现较好|表现较差")
_MEAN_REQUEST = re.compile(r"均值|平均值|对比|比较|比.*(?:全省|省)均")
_COUNT_REQUEST = re.compile(r"多少家|有几家|几家机构")
_ISO_DATE_FACT = re.compile(r"\d{4}[-/]\d{1,2}(?:[-/]\d{1,2})?")
_CHINESE_DATE_FACT = re.compile(
    r"(?P<year>\d{4})年(?:"
    r"(?P<month>\d{1,2})月(?:(?P<day>\d{1,2})日|末)?"
    r"|(?P<quarter>[一二三四1-4])季度末"
    r"|(?P<year_end>末)"
    r")"
)
_ORGANIZATION_NAME = re.compile(r"(?:[\u4e00-\u9fff]{2,8}省)?[A-Za-z]市农商行")
_ORGANIZATION_CODE = re.compile(r"\bORG\d{3}\b", re.IGNORECASE)
_METRIC_CODE = re.compile(r"\bZB\d{3}\b", re.IGNORECASE)

# The constrained bank compiler exposes stable projector names that are more
# concise than a few legacy workbook aliases.  Keep this mapping deliberately
# small and directional: it may reconcile a reviewed gold column with its
# canonical runtime projection, but it must never match arbitrary columns by
# position or by coincidentally equal numeric values.
_RESULT_COLUMN_ALIASES: dict[str, tuple[str, ...]] = {
    "days_above_province_average": ("days_above_average",),
    "observation_count": ("total_days",),
    "above_ratio_percent": ("ratio_percent",),
}

_FACT_RESULT_COLUMN_ALIASES: dict[str, tuple[str, ...]] = {
    **_RESULT_COLUMN_ALIASES,
    "org_name": ("bank_organization",),
    "data_date": ("bank_data_date",),
    "bank_data_date": ("data_date",),
    "metric_value": ("aggregate_value", "current_value"),
    "aggregate_value": ("metric_value", "current_value"),
    "value_difference": ("absolute_gap", "gap_value", "absolute_change"),
}
_FACT_IDENTITY_COLUMNS = {
    "org_code",
    "org_name",
    "metric_code",
    "data_date",
    "bank_data_date",
    "comparison_type",
}
_DIFFERENCE_REQUEST = re.compile(r"差多少|相差|差额")


@dataclass(frozen=True)
class FactDraft:
    value: float
    kind: str
    raw: str
    required: bool
    support: str
    derivation: str | None = None
    evidence: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class RecordFactContract:
    sampleId: str
    status: str
    legacyGoldGrade: str
    facts: list[FactDraft]
    semanticFacts: list[str]
    sourceRisks: list[str]
    warnings: list[str]
    reasons: list[str]

    def to_dict(self) -> dict[str, Any]:
        return {
            "sampleId": self.sampleId,
            "status": self.status,
            "legacyGoldGrade": self.legacyGoldGrade,
            "facts": [fact.to_dict() for fact in self.facts],
            "semanticFacts": list(self.semanticFacts),
            "sourceRisks": list(self.sourceRisks),
            "warnings": list(self.warnings),
            "reasons": list(self.reasons),
        }


def _numeric_table_values(expected: dict[str, Any]) -> list[float]:
    """Return actual numeric cells, never digits embedded in ORG/ZB identifiers."""

    rows = expected.get("rows") if isinstance(expected.get("rows"), list) else []
    values: list[float] = []
    for row in rows:
        if not isinstance(row, (list, tuple)):
            continue
        for cell in row:
            if cell is None or isinstance(cell, bool):
                continue
            if isinstance(cell, numbers.Real):
                values.append(float(cell))
                continue
            if isinstance(cell, str):
                text = cell.strip().replace(",", "")
                if _DATE_TOKEN.fullmatch(text):
                    continue
                try:
                    values.append(float(text))
                except ValueError:
                    continue
    unique: list[float] = []
    for value in values:
        if not any(math.isclose(value, existing, abs_tol=1e-12, rel_tol=1e-12) for existing in unique):
            unique.append(value)
    return unique


def _condition_true_count(
    columns: list[str] | None,
    rows: list[list[Any]] | None,
) -> float | None:
    if not isinstance(columns, list) or "meets_condition" not in columns:
        return None
    index = columns.index("meets_condition")
    total = 0.0
    for row in rows or []:
        if not isinstance(row, (list, tuple)) or index >= len(row):
            return None
        value = row[index]
        if isinstance(value, bool):
            total += float(value)
        elif isinstance(value, numbers.Real) and float(value) in {0.0, 1.0}:
            total += float(value)
        elif isinstance(value, str) and value.strip() in {"0", "1"}:
            total += float(value.strip())
        else:
            return None
    return total


def _close(left: float, right: float, *, kind: str, tolerance: float) -> bool:
    fact_tolerance = max(tolerance, 0.02) if kind in {"PERCENT", "NUMBER"} else tolerance
    return values_close(left, right, abs_tol=fact_tolerance, rel_tol=DEFAULT_REL_TOL)


def _reviewed_difference_formula(
    question: str,
    expected: dict[str, Any],
    *,
    fact_value: float,
    kind: str,
    tolerance: float,
) -> dict[str, Any] | None:
    """Recover a difference formula only from an explicit structured-gold column.

    This is not free-form arithmetic inference.  The source table must already
    publish ``value_difference`` and two identity-bound operand values whose
    absolute difference equals that reviewed value.
    """

    if not _DIFFERENCE_REQUEST.search(question):
        return None
    columns = expected.get("columns")
    rows = expected.get("rows")
    if not isinstance(columns, list) or not isinstance(rows, list):
        return None
    names = [str(column) for column in columns]
    if "value_difference" not in names:
        return None
    difference_index = names.index("value_difference")
    if not any(
        isinstance(row, (list, tuple))
        and difference_index < len(row)
        and _is_numeric_cell(row[difference_index])
        and _close(
            fact_value,
            float(row[difference_index]),
            kind=kind,
            tolerance=tolerance,
        )
        for row in rows
    ):
        return None

    value_column = next(
        (name for name in ("metric_value", "aggregate_value", "current_value") if name in names),
        None,
    )
    identity_column = next(
        (name for name in ("org_code", "org_name") if name in names),
        None,
    )
    if value_column is None or identity_column is None:
        return None
    value_index = names.index(value_column)
    identity_index = names.index(identity_column)
    operands: list[tuple[Any, float]] = []
    for row in rows:
        if (
            not isinstance(row, (list, tuple))
            or max(value_index, identity_index) >= len(row)
            or row[identity_index] is None
            or not _is_numeric_cell(row[value_index])
        ):
            continue
        candidate = (row[identity_index], float(row[value_index]))
        if candidate not in operands:
            operands.append(candidate)
    if len(operands) != 2 or not _close(
        fact_value,
        abs(operands[0][1] - operands[1][1]),
        kind=kind,
        tolerance=tolerance,
    ):
        return None
    return {
        "operation": "ABS_DIFFERENCE",
        "operands": [
            {"column": value_column, "where": {identity_column: identity}}
            for identity, _ in operands
        ],
    }


def _non_date_slots(text: str) -> list[Any]:
    """Extract numeric answer slots without interpreting date components as facts.

    ``extract_answer_slots`` correctly recognizes the year portion of a date, but
    month/day tokens in Chinese dates (for example ``2025年6月15日``) are otherwise
    ordinary quantities.  Dates are identity entities, not answer values, and are
    checked separately by ``_normalized_date_facts``.
    """

    without_dates = _CHINESE_DATE_FACT.sub(" ", _ISO_DATE_FACT.sub(" ", text))
    return [slot for slot in extract_answer_slots(without_dates) if slot.kind != "year"]


def _question_values(question: str) -> list[float]:
    return [slot.value for slot in _non_date_slots(question)]


def _semantic_facts(answer_text: str) -> list[str]:
    facts: list[str] = []
    checks = (
        ("FAIL", r"不达标|不满足"),
        ("PASS", r"(?<!不)达标|(?<!不)满足"),
        ("HIGHER", r"高于|高出"),
        ("LOWER", r"低于"),
        ("UP", r"上升|增长|提高"),
        ("DOWN", r"下降|减少|降低"),
        ("EQUAL", r"等于|相等"),
        ("MAXIMUM", r"最高|最大|居首|首位"),
        ("MINIMUM", r"最低|最小|末位"),
        ("MOM", r"环比"),
        ("YOY", r"同比|较去年同期"),
        ("PROVINCE_MEAN", r"全省均值|省均值|全省平均(?:值)?"),
        ("YEAR_START", r"年初"),
    )
    for name, pattern in checks:
        if re.search(pattern, answer_text):
            facts.append(name)
    return facts


def _source_risks(question: str, answer_text: str) -> list[str]:
    risks: list[str] = []
    if _HIGHER_NEGATIVE.search(answer_text):
        risks.append("DIRECTION_SIGN_CONFLICT")

    if re.search(r"前三|表现较好=全省排名前三", question):
        segment = _GOOD_SEGMENT.search(answer_text)
        if segment and any(int(rank) > 3 for rank in _RANK_OUTPUT.findall(segment.group(1))):
            risks.append("TOP3_LABEL_CONFLICT")
    return risks


def _typed_answer_facts(
    raw_facts: Any, expected: dict[str, Any]
) -> tuple[list[FactDraft], list[str]]:
    validated, errors = validate_answer_facts(
        raw_facts, expected, default_tolerance=DEFAULT_ABS_TOL
    )
    return [FactDraft(**fact) for fact in validated], errors


def validate_typed_facts_against_answer(
    question: str, answer_text: str, raw_facts: Any
) -> list[str]:
    """Prove that a typed scoring contract preserves the workbook answer facts."""

    source = build_fact_contract(
        {
            "id": "ANSWER-FACT-SOURCE-CHECK",
            "question": question,
            "expected": {
                "answerText": answer_text,
                "columns": [],
                "rows": [],
                "numericTolerance": DEFAULT_ABS_TOL,
                "orderSensitive": False,
                "unit": None,
            },
        }
    )
    required: list[FactDraft] = []
    for fact in (item for item in source.facts if item.required):
        if any(
            existing.kind == fact.kind
            and math.isclose(
                existing.value,
                fact.value,
                rel_tol=0.0,
                abs_tol=1e-12,
            )
            for existing in required
        ):
            continue
        required.append(fact)
    typed = raw_facts if isinstance(raw_facts, list) else []

    def matches(source_fact: FactDraft, typed_fact: dict[str, Any]) -> bool:
        typed_kind = typed_fact.get("kind")
        compatible_kind = typed_kind == source_fact.kind or {
            str(typed_kind), source_fact.kind
        } == {"NUMBER", "TOTAL_COUNT"}
        value = typed_fact.get("value")
        fact_tolerance = (
            max(DEFAULT_ABS_TOL, 0.02)
            if source_fact.kind in {"PERCENT", "NUMBER"}
            else DEFAULT_ABS_TOL
        )
        value_matches = (
            isinstance(value, numbers.Real)
            and not isinstance(value, bool)
            and math.isclose(
                source_fact.value,
                float(value),
                rel_tol=DEFAULT_REL_TOL,
                abs_tol=fact_tolerance,
            )
        )
        if (
            not value_matches
            and isinstance(value, numbers.Real)
            and not isinstance(value, bool)
            and float(value) < 0 <= source_fact.value
            and (
                re.search(r"(?:下降|减少|降低|下滑|降幅|负增长)", question)
                or re.search(
                    rf"(?:下降|减少|降低|下滑|低于|降幅|负增长)[^，。；]{{0,12}}{re.escape(source_fact.raw)}",
                    answer_text,
                )
            )
        ):
            # Chinese answers normally write the magnitude after a directional
            # word ("下降0.4%"), while executable result columns correctly carry
            # the signed value (-0.4).  The direction word is therefore part of
            # the source fact and must be present before sign normalization.
            value_matches = math.isclose(
                source_fact.value,
                abs(float(value)),
                rel_tol=DEFAULT_REL_TOL,
                abs_tol=fact_tolerance,
            )
        return (
            compatible_kind
            and value_matches
        )

    errors: list[str] = []
    for index, typed_fact in enumerate(typed):
        if not isinstance(typed_fact, dict) or not any(
            matches(source_fact, typed_fact) for source_fact in required
        ):
            errors.append(f"ANSWER_FACT_{index}_NOT_IN_WORKBOOK_ANSWER")
    assigned_source_by_typed: dict[int, int] = {}

    def assign(source_index: int, seen_typed: set[int]) -> bool:
        for typed_index, typed_fact in enumerate(typed):
            if (
                typed_index in seen_typed
                or not isinstance(typed_fact, dict)
                or not matches(required[source_index], typed_fact)
            ):
                continue
            seen_typed.add(typed_index)
            previous_source = assigned_source_by_typed.get(typed_index)
            if previous_source is None or assign(previous_source, seen_typed):
                assigned_source_by_typed[typed_index] = source_index
                return True
        return False

    for index in range(len(required)):
        if not assign(index, set()):
            errors.append(f"WORKBOOK_ANSWER_FACT_{index}_NOT_TYPED")
    return errors


def build_fact_contract(record: dict[str, Any]) -> RecordFactContract:
    sample_id = str(record.get("id") or "")
    question = str(record.get("question") or "")
    expected = record.get("expected") if isinstance(record.get("expected"), dict) else {}
    answer_text = str(expected.get("answerText") or "")
    tolerance_raw = expected.get("numericTolerance")
    tolerance = float(tolerance_raw) if isinstance(tolerance_raw, numbers.Real) else DEFAULT_ABS_TOL
    table_values = _numeric_table_values(expected)
    question_values = _question_values(question)
    rank_values = {float(value) for value in _RANK_OUTPUT.findall(answer_text)}
    total_counts = {float(value) for value in _TOTAL_COUNT.findall(answer_text)}
    province_means = {float(value) for value in _PROVINCE_MEAN.findall(answer_text)}
    asks_for_rank = bool(_RANK_REQUEST.search(question))
    asks_for_mean = bool(_MEAN_REQUEST.search(question))
    asks_for_count = bool(_COUNT_REQUEST.search(question))
    condition_true_count = _condition_true_count(
        [str(column) for column in expected.get("columns", [])]
        if isinstance(expected.get("columns"), list)
        else None,
        expected.get("rows") if isinstance(expected.get("rows"), list) else None,
    )

    facts: list[FactDraft] = []
    seen: set[tuple[float, str, str | None]] = set()
    for slot in _non_date_slots(answer_text):

        value = float(slot.value)
        if value in rank_values:
            kind = "RANK"
            required = asks_for_rank
        elif value in total_counts:
            kind = "TOTAL_COUNT"
            required = False
        else:
            kind = "PERCENT" if slot.kind == "percent" else "NUMBER"
            required = bool(slot.required)

        if value in province_means and not asks_for_mean:
            required = False

        # A binary ``meets_condition`` column proves a count only when the
        # question explicitly asks for one.  A prose total in an otherwise
        # list-oriented answer must not invent a count-based scoring path.
        candidate_derivation = "COUNT_TRUE" if asks_for_count else None
        key = (value, kind, candidate_derivation)
        if key in seen:
            continue
        seen.add(key)

        in_question = any(_close(value, candidate, kind=kind, tolerance=tolerance) for candidate in question_values)
        derivation = candidate_derivation
        difference_formula = _reviewed_difference_formula(
            question,
            expected,
            fact_value=value,
            kind=kind,
            tolerance=tolerance,
        )
        if in_question:
            support = "QUESTION_CONTEXT"
            derivation = None
            required = False
        elif difference_formula is not None:
            support = "DERIVED_RESULT"
            derivation = "ABS_DIFFERENCE"
        elif any(_close(value, candidate, kind=kind, tolerance=tolerance) for candidate in table_values):
            support = "DIRECT_RESULT"
            derivation = None
        else:
            if (
                derivation == "COUNT_TRUE"
                and condition_true_count is not None
                and _close(value, condition_true_count, kind=kind, tolerance=tolerance)
            ):
                support = "DERIVED_RESULT"
            else:
                derivation = None
                support = "MISSING"

        facts.append(
            FactDraft(
                value=value,
                kind=kind,
                raw=slot.raw,
                required=required,
                support=support,
                derivation=derivation,
                evidence={"formula": difference_formula}
                if difference_formula is not None
                else None,
            )
        )

    typed_errors: list[str] = []
    typed_facts = expected.get("answerFacts")
    typed_authoritative = expected.get("answerFactsAuthoritative") is True
    if typed_facts is not None:
        typed_drafts, typed_errors = _typed_answer_facts(typed_facts, expected)
        if typed_authoritative and not typed_errors and typed_drafts:
            # A validated typed contract is the authoritative result-scoring
            # surface.  Legacy prose extraction remains useful while creating
            # the contract, but must not make an identity-bound fact fail merely
            # because the same number cannot be mapped back to an anonymous text
            # slot (for example tied ranks or a reviewed derived projection).
            facts = typed_drafts
        elif not typed_authoritative:
            # Incremental contracts are annotations over the legacy answer
            # extraction.  They may add identity/formulas, but cannot silently
            # redefine which numeric facts the workbook answer requires.
            legacy_facts = list(facts)
            typed_targets: set[int] = set()
            for typed in typed_drafts:
                target = next(
                    (
                        index
                        for index, fact in enumerate(legacy_facts)
                        if fact.required
                        and fact.support in {"MISSING", "DIRECT_RESULT"}
                        and fact.kind == typed.kind
                        and _close(
                            fact.value,
                            typed.value,
                            kind=fact.kind,
                            tolerance=tolerance,
                        )
                    ),
                    None,
                )
                if target is None:
                    typed_errors.append("TYPED_ANSWER_FACT_NOT_BOUND_TO_ANSWER_FACT")
                elif target in typed_targets:
                    # Legacy extraction de-duplicates equal tokens, while a
                    # typed contract can bind tied values to distinct metrics.
                    facts.append(typed)
                else:
                    typed_targets.add(target)
                    facts[target] = typed

    risks = _source_risks(question, answer_text)
    warnings: list[str] = []
    missing_required_support = any(
        fact.required and fact.support == "MISSING" for fact in facts
    )
    if missing_required_support:
        warnings.append("LEGACY_TABLE_MISSING_ANSWER_FACT")
    if asks_for_mean or re.search(r"哪些表现较好|哪些表现较差", question):
        warnings.append("SEMANTIC_BINDING_DIAGNOSTIC")

    reasons: list[str] = []
    if risks and not (
        typed_authoritative and typed_facts is not None and not typed_errors and typed_drafts
    ):
        reasons.append("SOURCE_SEMANTIC_RISK")
    if missing_required_support:
        reasons.append("MISSING_RESULT_SUPPORT")
    reasons.extend(typed_errors)
    if not any(fact.required for fact in facts):
        reasons.append("NO_REQUIRED_FACTS")

    return RecordFactContract(
        sampleId=sample_id,
        status="REVIEW_REQUIRED" if reasons else "READY",
        legacyGoldGrade=assess_gold_contract(record).grade,
        facts=facts,
        semanticFacts=_semantic_facts(answer_text),
        sourceRisks=risks,
        warnings=warnings,
        reasons=list(dict.fromkeys(reasons)),
    )


def build_fact_contract_report(
    records_by_split: dict[str, Iterable[dict[str, Any]]],
    *,
    legacy_incomplete_only: bool = False,
) -> dict[str, Any]:
    items: list[dict[str, Any]] = []
    input_count = 0
    legacy_incomplete_count = 0
    by_split: dict[str, dict[str, int]] = {}

    for split, records in records_by_split.items():
        split_counts = {"recordCount": 0, "READY": 0, "REVIEW_REQUIRED": 0}
        for record in records:
            input_count += 1
            contract = build_fact_contract(record)
            legacy_incomplete = contract.legacyGoldGrade in {
                "GOLD_PARTIAL",
                "GOLD_BAD",
                "GOLD_EMPTY_TABLE",
            }
            legacy_incomplete_count += int(legacy_incomplete)
            if legacy_incomplete_only and not legacy_incomplete:
                continue
            split_counts["recordCount"] += 1
            split_counts[contract.status] += 1
            item = contract.to_dict()
            item["split"] = split
            item["question"] = str(record.get("question") or "")
            item["answerText"] = str(
                record.get("expected", {}).get("answerText")
                if isinstance(record.get("expected"), dict)
                else ""
            )
            items.append(item)
        by_split[split] = split_counts

    ready_count = sum(1 for item in items if item["status"] == "READY")
    review_count = len(items) - ready_count
    return {
        "schemaVersion": SCHEMA_VERSION,
        "policy": {
            "denominator": "ALL_SELECTED_RECORDS",
            "sqlTextScored": False,
            "frozenDatasetModified": False,
            "testRead": False,
        },
        "summary": {
            "inputRecordCount": input_count,
            "recordCount": len(items),
            "readyCount": ready_count,
            "reviewRequiredCount": review_count,
            "excludedCount": 0,
            "legacyIncompleteCount": legacy_incomplete_count,
        },
        "bySplit": by_split,
        "items": items,
    }


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _prediction_values(
    columns: list[str] | None,
    rows: list[list[Any]] | None,
    text_summary: str | None,
) -> tuple[list[float], list[float]]:
    table_values = _numeric_table_values({"columns": columns or [], "rows": rows or []})
    text_values = [slot.value for slot in _non_date_slots(text_summary or "")]
    return table_values, text_values


def _is_numeric_cell(value: Any) -> bool:
    return isinstance(value, numbers.Real) and not isinstance(value, bool)


_PROVINCIAL_AVERAGE_LEGACY_COLUMNS = (
    "org_code",
    "org_name",
    "metric_code",
    "aggregate_value",
    "min_value",
    "max_value",
    "observation_count",
)
_PROVINCIAL_AVERAGE_RESULT_COLUMNS = {
    "org_code",
    "org_name",
    "metric_code",
    "metric_value",
    "provincial_average",
    "gap_value",
    "absolute_gap",
}


def _standard_provincial_average_projection_binds(
    expected: dict[str, Any],
    *,
    expected_names: list[str],
    expected_rows: list[Any],
    columns: list[str],
    rows: list[list[Any]],
    require_exact_rows: bool,
) -> bool | None:
    """Bind the published provincial-average result contract to legacy aggregate rows.

    This is deliberately narrow: the runtime projection keeps the same organization and metric
    identity, exposes the current value as ``metric_value``, and additionally exposes the
    provincial mean/gaps required by the user-visible answer. The legacy snapshot rows encode
    aggregate/min/max as the same single-date target value and observation count one.
    """

    if tuple(expected_names) != _PROVINCIAL_AVERAGE_LEGACY_COLUMNS:
        return None
    if not _PROVINCIAL_AVERAGE_RESULT_COLUMNS.issubset(columns):
        return None
    if any(
        not isinstance(row, (list, tuple)) or len(row) != len(expected_names)
        for row in expected_rows
    ):
        return False

    indexes = {name: columns.index(name) for name in _PROVINCIAL_AVERAGE_RESULT_COLUMNS}
    tolerance_raw = expected.get("numericTolerance")
    tolerance = float(tolerance_raw) if isinstance(tolerance_raw, numbers.Real) else 0.0

    def cells_equal(left: Any, right: Any) -> bool:
        if _is_numeric_cell(left) and _is_numeric_cell(right):
            return abs(float(left) - float(right)) <= tolerance
        return left == right

    projected_rows: list[list[Any]] = []
    for row in rows:
        if not isinstance(row, (list, tuple)) or any(
            index >= len(row) for index in indexes.values()
        ):
            return False
        value = row[indexes["metric_value"]]
        projected_rows.append(
            [
                row[indexes["org_code"]],
                row[indexes["org_name"]],
                row[indexes["metric_code"]],
                value,
                value,
                value,
                1,
            ]
        )

    def rows_equal(left: list[Any] | tuple[Any, ...], right: list[Any]) -> bool:
        return len(left) == len(right) and all(
            cells_equal(expected_value, actual_value)
            for expected_value, actual_value in zip(left, right)
        )

    if require_exact_rows and expected.get("orderSensitive", False):
        return len(expected_rows) == len(projected_rows) and all(
            rows_equal(expected_row, actual_row)
            for expected_row, actual_row in zip(expected_rows, projected_rows)
        )

    unmatched = list(range(len(projected_rows)))
    for expected_row in expected_rows:
        matched_index = next(
            (
                candidate
                for candidate in unmatched
                if rows_equal(expected_row, projected_rows[candidate])
            ),
            None,
        )
        if matched_index is None:
            return False
        unmatched.remove(matched_index)
    return not unmatched if require_exact_rows else True


def _standard_provincial_average_semantic_values(
    expected: dict[str, Any],
    *,
    columns: list[str],
    rows: list[list[Any]],
    require_exact_rows: bool,
) -> list[float]:
    """Return only a named comparison gap after the narrow projection is bound.

    The old aggregate snapshot records the target metric value but not the
    province mean or gap printed in the answer.  The approved runtime projection
    names its absolute gap explicitly.  Do not expose it unless its organisation
    and metric identity has first been proven against the legacy rows.
    """

    expected_columns = expected.get("columns")
    expected_rows = expected.get("rows")
    if not isinstance(expected_columns, list) or not isinstance(expected_rows, list):
        return []
    if not _standard_provincial_average_projection_binds(
        expected,
        expected_names=[str(column) for column in expected_columns],
        expected_rows=expected_rows,
        columns=columns,
        rows=rows,
        require_exact_rows=require_exact_rows,
    ):
        return []

    values: list[float] = []
    for name in ("absolute_gap",):
        index = columns.index(name)
        for row in rows:
            if isinstance(row, (list, tuple)) and index < len(row) and _is_numeric_cell(row[index]):
                values.append(float(row[index]))
    return values


def _expected_rows_are_bound(
    expected: dict[str, Any],
    *,
    columns: list[str],
    rows: list[list[Any]],
    require_exact_rows: bool,
) -> bool:
    """Prove row identity for complete structured gold without requiring table shape.

    Extra result columns and provenance rows are allowed, but every expected row
    must be recoverable through the official column names.  This prevents a bag
    of correct numbers attached to wrong dates, organizations or metrics from
    being scored as an exact result.
    """

    expected_columns = expected.get("columns")
    expected_rows = expected.get("rows")
    if not isinstance(expected_columns, list) or not isinstance(expected_rows, list):
        return False
    expected_names = [str(column) for column in expected_columns]
    if not expected_names or len(set(columns)) != len(columns):
        return False

    provincial_average_binding = _standard_provincial_average_projection_binds(
        expected,
        expected_names=expected_names,
        expected_rows=expected_rows,
        columns=columns,
        rows=rows,
        require_exact_rows=require_exact_rows,
    )
    if provincial_average_binding is not None:
        return provincial_average_binding

    has_identity = any(
        cell is not None and not _is_numeric_cell(cell)
        for row in expected_rows
        if isinstance(row, (list, tuple))
        for cell in row
    )
    if not has_identity and not require_exact_rows:
        return True
    resolved_names = [
        next(
            (
                candidate
                for candidate in (name, *_RESULT_COLUMN_ALIASES.get(name, ()))
                if candidate in columns
            ),
            None,
        )
        for name in expected_names
    ]
    if any(name is None for name in resolved_names):
        return False

    indexes = [columns.index(name) for name in resolved_names if name is not None]
    projected_rows: list[list[Any]] = []
    for row in rows:
        if not isinstance(row, (list, tuple)) or any(index >= len(row) for index in indexes):
            return False
        projected_rows.append([row[index] for index in indexes])

    tolerance_raw = expected.get("numericTolerance")
    tolerance = float(tolerance_raw) if isinstance(tolerance_raw, numbers.Real) else 0.0

    def cells_equal(left: Any, right: Any) -> bool:
        if _is_numeric_cell(left) and _is_numeric_cell(right):
            return abs(float(left) - float(right)) <= tolerance
        return left == right

    def rows_equal(left: list[Any] | tuple[Any, ...], right: list[Any]) -> bool:
        return len(left) == len(right) and all(
            cells_equal(expected_value, actual_value)
            for expected_value, actual_value in zip(left, right)
        )

    if require_exact_rows and expected.get("orderSensitive", False):
        return len(expected_rows) == len(projected_rows) and all(
            isinstance(expected_row, (list, tuple))
            and rows_equal(expected_row, actual_row)
            for expected_row, actual_row in zip(expected_rows, projected_rows)
        )

    unmatched = list(range(len(projected_rows)))
    for expected_row in expected_rows:
        if not isinstance(expected_row, (list, tuple)) or len(expected_row) != len(expected_names):
            return False
        matched_index = next(
            (
                candidate
                for candidate in unmatched
                if rows_equal(expected_row, projected_rows[candidate])
            ),
            None,
        )
        if matched_index is None:
            return False
        unmatched.remove(matched_index)
    return not unmatched if require_exact_rows else True


def _resolve_fact_result_column(name: str, columns: list[str]) -> str | None:
    by_folded = {column.casefold(): column for column in columns}
    for candidate in (name, *_FACT_RESULT_COLUMN_ALIASES.get(name, ())):
        resolved = by_folded.get(candidate.casefold())
        if resolved is not None:
            return resolved
    return None


def _resolved_identity_group(
    expected_by_name: dict[str, Any],
    columns: list[str],
    names: tuple[str, ...],
) -> tuple[bool, list[tuple[int, Any]]]:
    expected_values = [
        (name, expected_by_name[name])
        for name in names
        if name in expected_by_name and expected_by_name[name] is not None
    ]
    pairs: list[tuple[int, Any]] = []
    for name, value in expected_values:
        resolved = _resolve_fact_result_column(name, columns)
        if resolved is not None:
            pairs.append((columns.index(resolved), value))
    return bool(expected_values), pairs


def _metric_pivot_result_column(metric_code: Any, columns: list[str]) -> str | None:
    if not isinstance(metric_code, str) or not _METRIC_CODE.fullmatch(metric_code):
        return None
    expression = re.compile(
        rf"\bmetric_code\s*=\s*['\"]{re.escape(metric_code)}['\"]",
        re.IGNORECASE,
    )
    for column in columns:
        if column.casefold() == metric_code.casefold() or expression.search(column):
            return column
    return None


def _legacy_direct_fact_is_bound(
    fact: FactDraft,
    expected: dict[str, Any],
    *,
    columns: list[str],
    rows: list[list[Any]],
) -> bool:
    """Bind one required value to its reviewed entity without enforcing table shape."""

    expected_columns = expected.get("columns")
    expected_rows = expected.get("rows")
    if not isinstance(expected_columns, list) or not isinstance(expected_rows, list):
        return False
    expected_names = [str(column) for column in expected_columns]
    tolerance_raw = expected.get("numericTolerance")
    tolerance = float(tolerance_raw) if isinstance(tolerance_raw, numbers.Real) else DEFAULT_ABS_TOL

    for expected_row in expected_rows:
        if not isinstance(expected_row, (list, tuple)) or len(expected_row) != len(expected_names):
            continue
        fact_columns = [
            name
            for name, cell in zip(expected_names, expected_row)
            if _is_numeric_cell(cell)
            and _close(fact.value, float(cell), kind=fact.kind, tolerance=tolerance)
            and name not in _FACT_IDENTITY_COLUMNS
        ]
        if not fact_columns:
            continue
        expected_by_name = dict(zip(expected_names, expected_row))
        implicit_metric_column = None
        expected_metric_code = expected_by_name.get("metric_code")
        if expected_metric_code is not None and _resolve_fact_result_column(
            "metric_code", columns
        ) is None:
            implicit_metric_column = _metric_pivot_result_column(
                expected_metric_code, columns
            )
            if implicit_metric_column is None:
                # A matching number and organization cannot prove which metric
                # produced the answer.  Fail closed unless the result exposes
                # metric_code or a metric-specific pivot column.
                continue
        identity_pairs: list[tuple[int, Any]] = []
        missing_identity_group = False
        for group in (
            ("org_code", "org_name"),
            ("data_date", "bank_data_date"),
            ("comparison_type",),
        ):
            expected_group, group_pairs = _resolved_identity_group(
                expected_by_name, columns, group
            )
            if expected_group and not group_pairs:
                missing_identity_group = True
                break
            identity_pairs.extend(group_pairs)
        if missing_identity_group:
            continue
        resolved_metric_code = _resolve_fact_result_column("metric_code", columns)
        if expected_metric_code is not None and resolved_metric_code is not None:
            identity_pairs.append(
                (columns.index(resolved_metric_code), expected_metric_code)
            )

        actual_value_columns: list[str] = []
        for name in fact_columns:
            resolved = (
                implicit_metric_column
                if implicit_metric_column is not None
                and name in {"metric_value", "aggregate_value", "current_value"}
                else _resolve_fact_result_column(name, columns)
            )
            if resolved is None:
                resolved = _metric_pivot_result_column(
                    expected_by_name.get("metric_code"), columns
                )
            if resolved is not None and resolved not in actual_value_columns:
                actual_value_columns.append(resolved)
        for row in rows:
            if not isinstance(row, (list, tuple)):
                continue
            if any(index >= len(row) or row[index] != value for index, value in identity_pairs):
                continue
            if any(
                columns.index(name) < len(row)
                and _is_numeric_cell(row[columns.index(name)])
                and _close(
                    fact.value,
                    float(row[columns.index(name)]),
                    kind=fact.kind,
                    tolerance=tolerance,
                )
                for name in actual_value_columns
            ):
                return True
    return False


def _required_facts_are_bound(
    required_facts: list[FactDraft],
    expected: dict[str, Any],
    *,
    columns: list[str],
    rows: list[list[Any]],
) -> bool:
    """Bind answer facts, while treating all other rows as non-scored evidence."""

    direct_facts = [fact for fact in required_facts if fact.support == "DIRECT_RESULT"]
    if not all(
        _legacy_direct_fact_is_bound(fact, expected, columns=columns, rows=rows)
        for fact in direct_facts
    ):
        return False
    missing_facts = [fact for fact in required_facts if fact.support == "MISSING"]
    if missing_facts:
        return _expected_rows_are_bound(
            expected,
            columns=columns,
            rows=rows,
            require_exact_rows=False,
        )
    return True


def _fact_is_grounded_in_result(
    fact: FactDraft,
    *,
    table_values: list[float],
    columns: list[str] | None = None,
    rows: list[list[Any]] | None = None,
    explicit_semantic_values: Iterable[float] = (),
) -> bool:
    """Accept only evidence already typed by the structured gold contract.

    A captured result table can contain additional metrics or incidental values.
    It must not turn an answer fact that is absent from ``expected.rows`` into a
    passing fact merely because the same number appears somewhere else.  The
    only non-cell derivation retained here is the named ``meets_condition``
    count, whose column gives the derivation an explicit semantic contract.
    """

    tolerance = DEFAULT_ABS_TOL
    if fact.support == "DIRECT_RESULT":
        return any(
            _close(fact.value, value, kind=fact.kind, tolerance=tolerance)
            for value in table_values
        )
    if fact.support == "TYPED_RESULT" and isinstance(fact.evidence, dict):
        formula = fact.evidence.get("formula")
        calculated = (
            evaluate_formula(formula, columns or [], rows or [])
            if isinstance(formula, dict)
            else None
        )
        return calculated is not None and _close(
            fact.value,
            calculated,
            kind=fact.kind,
            tolerance=tolerance,
        )
    if fact.support == "DERIVED_RESULT" and isinstance(fact.evidence, dict):
        formula = fact.evidence.get("formula")
        calculated = (
            evaluate_formula(formula, columns or [], rows or [])
            if isinstance(formula, dict)
            else None
        )
        if calculated is not None:
            return _close(
                fact.value,
                calculated,
                kind=fact.kind,
                tolerance=DEFAULT_ABS_TOL,
            )
    if fact.derivation == "COUNT_TRUE" and fact.support == "DERIVED_RESULT":
        condition_true_count = _condition_true_count(columns, rows)
        return condition_true_count is not None and _close(
            fact.value,
            condition_true_count,
            kind=fact.kind,
            tolerance=DEFAULT_ABS_TOL,
        )
    if fact.support == "MISSING":
        return any(
            _close(fact.value, value, kind=fact.kind, tolerance=tolerance)
            for value in explicit_semantic_values
        )
    return False


def _fact_is_grounded_in_text(fact: FactDraft, *, text_values: list[float]) -> bool:
    tolerance = DEFAULT_ABS_TOL
    return any(
        _close(fact.value, value, kind=fact.kind, tolerance=tolerance)
        for value in text_values
    )


def _text_has_only_allowed_facts(
    contract: RecordFactContract,
    *,
    text_values: list[float],
    additional_values: Iterable[float] = (),
) -> bool:
    allowed_facts = list(contract.facts)
    allowed_values = list(additional_values)
    return all(
        any(
            _close(fact.value, value, kind=fact.kind, tolerance=DEFAULT_ABS_TOL)
            for fact in allowed_facts
        ) or any(
            _close(allowed_value, value, kind="RANK", tolerance=DEFAULT_ABS_TOL)
            for allowed_value in allowed_values
        )
        for value in text_values
    )


def _result_rank_values(
    columns: list[str] | None,
    rows: list[list[Any]] | None,
) -> list[float]:
    if not isinstance(columns, list) or "rank_position" not in columns:
        return []
    index = columns.index("rank_position")
    values: list[float] = []
    for row in rows or []:
        if not isinstance(row, (list, tuple)) or index >= len(row):
            continue
        value = row[index]
        if _is_numeric_cell(value):
            values.append(float(value))
    return values


def _build_entity_alias_catalog(
    records: list[dict[str, Any]],
    field: str,
) -> tuple[tuple[str, str], ...]:
    aliases: dict[str, set[str]] = {}
    for record in records:
        intent = record.get("normalizedIntent")
        entities = intent.get(field) if isinstance(intent, dict) else None
        if not isinstance(entities, list):
            continue
        for entity in entities:
            if not isinstance(entity, dict):
                continue
            code = entity.get("code")
            if not isinstance(code, str) or not code.strip():
                continue
            for key in ("code", "name", "matchedText"):
                value = entity.get(key)
                if not isinstance(value, str) or len(value.strip()) < 2:
                    continue
                alias = value.strip().casefold()
                aliases.setdefault(alias, set()).add(code.strip().upper())
    return tuple(
        sorted(
            (
                (alias, next(iter(codes)))
                for alias, codes in aliases.items()
                if len(codes) == 1
            ),
            key=lambda item: (-len(item[0]), item[0]),
        )
    )


def _entity_codes_in_text(
    text: str,
    catalog: tuple[tuple[str, str], ...],
) -> set[str]:
    normalized = text.casefold()
    occupied = [False] * len(normalized)
    codes: set[str] = set()
    for alias, code in catalog:
        start = 0
        while True:
            index = normalized.find(alias, start)
            if index < 0:
                break
            end = index + len(alias)
            if not any(occupied[index:end]):
                codes.add(code)
                occupied[index:end] = [True] * len(alias)
            start = index + 1
    return codes


def _normalized_date_facts(text: str) -> set[str]:
    """Normalize equivalent period labels without weakening daily-date binding.

    The source answer list mixes ``YYYY-MM`` and quarter-end ``YYYY-MM-DD`` labels
    for the same quarter-end fact. Treat those two spellings as aliases only for
    quarter-end months; an arbitrary daily date such as 2025-06-15 remains exact.
    """

    dates: set[str] = set()
    quarter_day = {3: 31, 6: 30, 9: 30, 12: 31}

    def add_period(year: int, month: int, day: int | None = None) -> None:
        month_text = f"{year:04d}-{month:02d}"
        if day is None:
            dates.add(month_text)
            if month in quarter_day:
                dates.add(f"{month_text}-{quarter_day[month]:02d}")
            return
        full_text = f"{month_text}-{day:02d}"
        dates.add(full_text)
        if month in quarter_day and day == quarter_day[month]:
            dates.add(month_text)

    for token in _ISO_DATE_FACT.findall(text):
        parts = re.split(r"[-/]", token)
        add_period(
            int(parts[0]),
            int(parts[1]),
            int(parts[2]) if len(parts) == 3 else None,
        )
    quarter_month = {"一": 3, "1": 3, "二": 6, "2": 6, "三": 9, "3": 9, "四": 12, "4": 12}
    for match in _CHINESE_DATE_FACT.finditer(text):
        year = int(match.group("year"))
        if match.group("year_end"):
            add_period(year, 12, 31)
        elif match.group("quarter"):
            month = quarter_month[match.group("quarter")]
            add_period(year, month, quarter_day[month])
        else:
            month = int(match.group("month"))
            day = match.group("day")
            add_period(year, month, int(day) if day else None)
    return dates


def _organization_names(
    text: str,
    catalog: tuple[tuple[str, str], ...] = (),
) -> set[str]:
    """Return only unrecognised explicit bank names.

    The catalog already binds every known organisation to its code. Mask those
    exact aliases before applying the broad fallback regex so a question prefix
    such as ``请分析江苏省A市农商行`` is not incorrectly captured as a different
    organisation named ``请分析江苏省A市农商行``. Any genuinely unknown explicit
    bank name remains visible and therefore still fails closed.
    """

    normalized = text.casefold()
    for alias, _ in catalog:
        if "农商行" in alias:
            normalized = normalized.replace(alias, " " * len(alias))
    names: set[str] = set()
    for match in _ORGANIZATION_NAME.finditer(normalized):
        value = match.group(0)
        # The broad prefix is intentionally permissive for province names, but
        # answer prose can place Chinese connective words immediately before
        # “江苏省X市农商行”. Keep the province suffix and bank name only.
        province_end = value.rfind("省")
        if province_end >= 2:
            value = value[province_end - 2 :]
        names.add(value.casefold())
    return names


def _literal_codes(text: str, pattern: re.Pattern[str]) -> set[str]:
    return {match.group(0).upper() for match in pattern.finditer(text)}


def _text_entities_are_exact(
    record: dict[str, Any],
    text_summary: str,
    *,
    organization_catalog: tuple[tuple[str, str], ...],
    metric_catalog: tuple[tuple[str, str], ...],
) -> bool:
    expected = record.get("expected") if isinstance(record.get("expected"), dict) else {}
    answer_text = str(expected.get("answerText") or "")
    question = str(record.get("question") or "")

    def required_and_allowed(
        extractor: Any,
    ) -> tuple[set[str], set[str], set[str]]:
        required = set(extractor(answer_text))
        allowed = required | set(extractor(question))
        predicted = set(extractor(text_summary))
        return required, allowed, predicted

    extractors = (
        lambda text: _entity_codes_in_text(text, organization_catalog),
        lambda text: _entity_codes_in_text(text, metric_catalog),
        lambda text: _organization_names(text, organization_catalog),
        _normalized_date_facts,
        lambda text: _literal_codes(text, _ORGANIZATION_CODE),
        lambda text: _literal_codes(text, _METRIC_CODE),
    )
    for extractor in extractors:
        required, allowed, predicted = required_and_allowed(extractor)
        if not required.issubset(predicted) or not predicted.issubset(allowed):
            return False
    return True


def score_fact_contract_report(
    report: dict[str, Any],
    records: list[dict[str, Any]],
    *,
    score_mode: str = "legacy",
) -> dict[str, Any]:
    """Score every selected record; unresolved contracts fail closed, never skip.

    ``result_only`` is the official runtime mode: structured result facts are
    the score, while the model's natural-language answer remains a UI output
    and does not gate ``caseAccuracy``.  ``legacy`` is retained only for the
    focused historical contract tests and migration diagnostics.
    """

    if score_mode not in {"legacy", "result_only"}:
        raise ValueError(f"unsupported score_mode: {score_mode}")
    score_final_answer = score_mode == "legacy"

    report_items = report.get("items") if isinstance(report.get("items"), list) else []
    prediction_by_id = {
        str(item.get("id")): item
        for item in report_items
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }

    organization_catalog = _build_entity_alias_catalog(records, "organizations")
    metric_catalog = _build_entity_alias_catalog(records, "metrics")
    scored_items: list[dict[str, Any]] = []
    case_hits = 0
    result_hits = 0
    final_fact_hits = 0
    ready_count = 0

    for record in records:
        sample_id = str(record.get("id") or "")
        contract = build_fact_contract(record)
        prediction = prediction_by_id.get(sample_id)
        result_facts_exact = False
        final_facts_exact = False
        result_evidence = "MISSING"
        reason = "ok"
        if contract.status == "READY":
            ready_count += 1

        if prediction is None:
            reason = "missing_prediction"
        else:
            columns = prediction.get("resultColumns")
            rows = prediction.get("resultRows")
            has_table = isinstance(columns, list) and isinstance(rows, list)
            expected = record.get("expected") if isinstance(record.get("expected"), dict) else {}
            if has_table:
                result_evidence = "CAPTURED_ROWS"

            summary = prediction.get("textSummary")
            text_summary = str(summary) if isinstance(summary, str) else None
            table_values, text_values = _prediction_values(
                [str(column) for column in columns] if isinstance(columns, list) else None,
                rows if isinstance(rows, list) else None,
                text_summary,
            )
            required_facts = [fact for fact in contract.facts if fact.required]
            if has_table:
                require_exact_rows = (
                    score_final_answer and contract.legacyGoldGrade == "GOLD_OK"
                )
                explicit_semantic_values = _standard_provincial_average_semantic_values(
                    expected,
                    columns=[str(column) for column in columns],
                    rows=rows,
                    require_exact_rows=require_exact_rows,
                )
                facts_grounded = bool(required_facts) and all(
                    _fact_is_grounded_in_result(
                        fact,
                        table_values=table_values,
                        columns=[str(column) for column in columns],
                        rows=rows,
                        explicit_semantic_values=explicit_semantic_values,
                    )
                    for fact in required_facts
                )
                if score_final_answer:
                    row_binding_ok = _expected_rows_are_bound(
                        expected,
                        columns=[str(column) for column in columns],
                        rows=rows,
                        require_exact_rows=require_exact_rows,
                    )
                else:
                    row_binding_ok = _required_facts_are_bound(
                        required_facts,
                        expected,
                        columns=[str(column) for column in columns],
                        rows=rows,
                    )
                result_facts_exact = facts_grounded and row_binding_ok
            if score_final_answer:
                final_numeric_ok = bool(text_summary) and bool(required_facts) and all(
                    _fact_is_grounded_in_text(
                        fact,
                        text_values=text_values,
                    )
                    for fact in required_facts
                )
                final_numeric_ok = final_numeric_ok and _text_has_only_allowed_facts(
                    contract,
                    text_values=text_values,
                    additional_values=_result_rank_values(
                        [str(column) for column in columns] if isinstance(columns, list) else None,
                        rows if isinstance(rows, list) else None,
                    ),
                )
                predicted_semantics = set(_semantic_facts(text_summary or ""))
                semantic_ok = set(contract.semanticFacts) == predicted_semantics
                entity_ok = bool(text_summary) and _text_entities_are_exact(
                    record,
                    text_summary,
                    organization_catalog=organization_catalog,
                    metric_catalog=metric_catalog,
                )
                final_facts_exact = final_numeric_ok and semantic_ok and entity_ok

            if contract.status != "READY":
                reason = "contract_review_required"
            else:
                if not result_facts_exact:
                    reason = "result_mismatch"
                elif score_final_answer and not final_facts_exact:
                    reason = "final_fact_mismatch"

        case_pass = bool(
            result_facts_exact
            and contract.status == "READY"
            and (not score_final_answer or final_facts_exact)
        )
        case_hits += int(case_pass)
        result_hits += int(result_facts_exact)
        if score_final_answer:
            final_fact_hits += int(final_facts_exact)
        scored_item = {
            "id": sample_id,
            "contractStatus": contract.status,
            "contractReasons": list(contract.reasons),
            "resultExact": result_facts_exact,
            "resultFactsExact": result_facts_exact,
            "resultEvidence": result_evidence,
            "casePass": case_pass,
            "reason": reason,
        }
        if score_final_answer:
            scored_item["finalFactsExact"] = final_facts_exact
        scored_items.append(
            scored_item
        )

    denominator = len(records)
    metrics = {
        "caseAccuracy": _rate(case_hits, denominator),
        "casePassHits": case_hits,
        "caseDenominator": denominator,
        "resultExactHits": result_hits,
        "resultFactAccuracy": _rate(result_hits, denominator),
        "resultFactsExactHits": result_hits,
        "contractReadyRate": _rate(ready_count, denominator),
        "contractReadyCount": ready_count,
        "excludedCount": 0,
    }
    policy = {
        "primaryMetric": "caseAccuracy",
        "casePass": "resultExact" if not score_final_answer else "resultExact AND finalFactsExact",
        "resultExact": (
            "required answer facts must be directly typed by structured gold, or by an "
            "identity-bound named projection, and present in captured SQL result"
        ),
        "denominator": "ALL_SELECTED_RECORDS",
        "sqlTextScored": False,
        "reviewRequiredBehavior": "FAIL_CLOSED",
    }
    if score_final_answer:
        metrics.update(
            {
                "finalFactAccuracy": _rate(final_fact_hits, denominator),
                "finalFactsExactHits": final_fact_hits,
            }
        )
        policy["finalFactsExact"] = (
            "all required answer facts and answer entities present with no extra numeric, "
            "out-of-context entity or contradictory semantic facts"
        )
    else:
        policy["finalAnswerScored"] = False

    return {
        "schemaVersion": SCHEMA_VERSION,
        "recordCount": denominator,
        "scoreMode": score_mode,
        "metrics": metrics,
        "policy": policy,
        "run": report.get("run"),
        "items": scored_items,
    }
