#!/usr/bin/env python3
"""Answer-contract evaluation for bank NL2SQL.

Implements two complementary checks used by a correct bank QA auto-evaluator:

1. **Gold contract (L2 ⊇ L1)** — structured ``expected.rows`` must cover the
   business numbers stated in official ``answerText``.
2. **answerExact** — a prediction result table (and optional text summary) must
   hit every required answer slot extracted from ``answerText``.

SQL text / AST / plan shape are intentionally out of scope.
"""

from __future__ import annotations

import json
import math
import numbers
import re
from dataclasses import asdict, dataclass
from typing import Any, Iterable


YEAR_MIN = 2020
YEAR_MAX = 2030
DEFAULT_ABS_TOL = 1e-3
DEFAULT_REL_TOL = 1e-3

# "前3名" / "后3名" rank cardinals are rhetorical; do not require them in tables.
_RANK_CARDINAL = re.compile(r"[前后]([1-9]\d*)名")
# Strip calendar tokens so "2025-06-30" / "2025-03" do not yield years/months as slots.
_DATE_TOKEN = re.compile(r"\d{4}-\d{1,2}-\d{1,2}")
_YEAR_MONTH_TOKEN = re.compile(r"\d{4}-\d{1,2}(?!\d)")
_NUMBER_TOKEN = re.compile(r"-?\d+(?:\.\d+)?")
_PERCENT_CONTEXT = re.compile(
    r"(-?\d+(?:\.\d+)?)\s*%"
    r"|(-?\d+(?:\.\d+)?)\s*％"
)


@dataclass(frozen=True)
class AnswerSlot:
    """One required numeric fact from the human-facing answer text."""

    value: float
    kind: str  # percent | quantity | rank_cardinal | year
    required: bool
    raw: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class ContractAssessment:
    sample_id: str
    grade: str  # GOLD_OK | GOLD_PARTIAL | GOLD_BAD | GOLD_NON_NUMERIC | GOLD_EMPTY_TABLE
    requiredSlotCount: int
    coveredRequiredCount: int
    coverageRate: float | None
    uncoveredRequired: list[float]
    optionalOnly: list[float]
    answerText: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class AnswerExactScore:
    answerExact: bool
    slotRecall: float | None
    requiredSlotCount: int
    hitCount: int
    missedSlots: list[float]
    goldGrade: str
    scored: bool
    reason: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def _is_year(value: float) -> bool:
    return value.is_integer() and YEAR_MIN <= int(value) <= YEAR_MAX


def extract_answer_slots(answer_text: str | None) -> list[AnswerSlot]:
    """Extract numeric slots from official answer text."""

    text = "" if answer_text is None else str(answer_text)
    if not text.strip():
        return []

    optional_ranks: set[float] = set()
    for match in _RANK_CARDINAL.finditer(text):
        optional_ranks.add(float(match.group(1)))

    percent_values: set[float] = set()
    for match in _PERCENT_CONTEXT.finditer(text):
        token = match.group(1) or match.group(2)
        if token is not None:
            percent_values.add(float(token))

    scrubbed = _DATE_TOKEN.sub(" ", text)
    scrubbed = _YEAR_MONTH_TOKEN.sub(" ", scrubbed)
    slots: list[AnswerSlot] = []
    seen: set[tuple[float, str]] = set()

    for match in _NUMBER_TOKEN.finditer(scrubbed):
        raw = match.group(0)
        value = float(raw)
        if _is_year(value):
            kind = "year"
            required = False
        elif value in optional_ranks and value.is_integer() and 1 <= int(value) <= 20:
            kind = "rank_cardinal"
            required = False
        elif value in percent_values:
            kind = "percent"
            required = True
        else:
            kind = "quantity"
            required = True
        key = (value, kind)
        if key in seen:
            continue
        seen.add(key)
        slots.append(AnswerSlot(value=value, kind=kind, required=required, raw=raw))
    return slots


def flatten_table_numbers(
    columns: list[str] | None,
    rows: list[list[Any]] | Iterable[Iterable[Any]] | None,
    *,
    include_column_names: bool = False,
) -> list[float]:
    """Collect numeric cells from a result table (absolute values kept signed)."""

    values: list[float] = []
    if include_column_names:
        for column in columns or []:
            values.extend(_numbers_from_text(str(column)))
    for row in rows or []:
        for cell in row:
            if cell is None or isinstance(cell, bool):
                continue
            if isinstance(cell, numbers.Real):
                values.append(float(cell))
                continue
            text = str(cell).strip()
            if _DATE_TOKEN.fullmatch(text):
                continue
            values.extend(_numbers_from_text(text))
    return values


def flatten_expected_numbers(expected: dict[str, Any] | None) -> list[float]:
    if not isinstance(expected, dict):
        return []
    columns = expected.get("columns") if isinstance(expected.get("columns"), list) else []
    rows = expected.get("rows") if isinstance(expected.get("rows"), list) else []
    return flatten_table_numbers([str(c) for c in columns], rows)


def _numbers_from_text(text: str) -> list[float]:
    scrubbed = _DATE_TOKEN.sub(" ", text.replace(",", ""))
    scrubbed = _YEAR_MONTH_TOKEN.sub(" ", scrubbed)
    out: list[float] = []
    for match in _NUMBER_TOKEN.finditer(scrubbed):
        try:
            out.append(float(match.group(0)))
        except ValueError:
            continue
    return out


def values_close(
    left: float,
    right: float,
    *,
    abs_tol: float = DEFAULT_ABS_TOL,
    rel_tol: float = DEFAULT_REL_TOL,
) -> bool:
    if math.isclose(left, right, abs_tol=abs_tol, rel_tol=rel_tol):
        return True
    # Sign-insensitive for wording like "下降11.69" vs absolute_change=-11.69
    if math.isclose(abs(left), abs(right), abs_tol=abs_tol, rel_tol=rel_tol):
        return True
    return False


def coverage_of_slots(
    slots: list[AnswerSlot],
    candidate_values: list[float],
    *,
    abs_tol: float = DEFAULT_ABS_TOL,
    rel_tol: float = DEFAULT_REL_TOL,
) -> tuple[list[AnswerSlot], list[AnswerSlot]]:
    """Return (covered_required_or_optional_hits_tracking, missed_required)."""

    missed: list[AnswerSlot] = []
    covered: list[AnswerSlot] = []
    for slot in slots:
        # Official answer text often rounds percents/means to 0-2 decimals
        # (e.g. 3% vs 3.0137, 0.77% vs 0.774).
        slot_abs = max(abs_tol, 0.02) if slot.kind == "percent" else abs_tol
        hit = any(
            values_close(slot.value, candidate, abs_tol=slot_abs, rel_tol=rel_tol)
            for candidate in candidate_values
        )
        if hit:
            covered.append(slot)
        elif slot.required:
            missed.append(slot)
    return covered, missed


def assess_gold_contract(
    record: dict[str, Any],
    *,
    abs_tol: float = DEFAULT_ABS_TOL,
    rel_tol: float = DEFAULT_REL_TOL,
) -> ContractAssessment:
    """Classify whether structured gold can prove the official answer text."""

    sample_id = str(record.get("id") or "")
    expected = record.get("expected") if isinstance(record.get("expected"), dict) else {}
    answer_text = str(expected.get("answerText") or "")
    slots = extract_answer_slots(answer_text)
    required = [slot for slot in slots if slot.required]
    optional = [slot for slot in slots if not slot.required]
    table_values = flatten_expected_numbers(expected)
    rows = expected.get("rows") if isinstance(expected.get("rows"), list) else []

    if not required:
        if rows:
            grade = "GOLD_NON_NUMERIC"
            rate: float | None = None
        else:
            grade = "GOLD_EMPTY_TABLE"
            rate = None
        return ContractAssessment(
            sample_id=sample_id,
            grade=grade,
            requiredSlotCount=0,
            coveredRequiredCount=0,
            coverageRate=rate,
            uncoveredRequired=[],
            optionalOnly=[slot.value for slot in optional],
            answerText=answer_text,
        )

    if not rows:
        return ContractAssessment(
            sample_id=sample_id,
            grade="GOLD_EMPTY_TABLE",
            requiredSlotCount=len(required),
            coveredRequiredCount=0,
            coverageRate=0.0,
            uncoveredRequired=[slot.value for slot in required],
            optionalOnly=[slot.value for slot in optional],
            answerText=answer_text,
        )

    _, missed = coverage_of_slots(required, table_values, abs_tol=abs_tol, rel_tol=rel_tol)
    covered_count = len(required) - len(missed)
    rate = covered_count / len(required)
    if rate >= 1.0 - 1e-12:
        grade = "GOLD_OK"
    elif covered_count == 0:
        grade = "GOLD_BAD"
    else:
        grade = "GOLD_PARTIAL"

    return ContractAssessment(
        sample_id=sample_id,
        grade=grade,
        requiredSlotCount=len(required),
        coveredRequiredCount=covered_count,
        coverageRate=round(rate, 6),
        uncoveredRequired=[slot.value for slot in missed],
        optionalOnly=[slot.value for slot in optional],
        answerText=answer_text,
    )


def score_answer_exact(
    record: dict[str, Any],
    *,
    columns: list[str] | None,
    rows: list[list[Any]] | None,
    text_summary: str | None = None,
    abs_tol: float | None = None,
    rel_tol: float = DEFAULT_REL_TOL,
    require_gold_ok: bool = True,
) -> AnswerExactScore:
    """Score whether prediction results satisfy the official answer slots.

    When ``require_gold_ok`` is true, items whose structured gold cannot prove
    the answer text are marked unscored for official accuracy.
    """

    expected = record.get("expected") if isinstance(record.get("expected"), dict) else {}
    gold = assess_gold_contract(record)
    tolerance = abs_tol
    if tolerance is None:
        raw_tol = expected.get("numericTolerance")
        tolerance = float(raw_tol) if isinstance(raw_tol, numbers.Real) else DEFAULT_ABS_TOL

    if require_gold_ok and gold.grade != "GOLD_OK":
        return AnswerExactScore(
            answerExact=False,
            slotRecall=None,
            requiredSlotCount=gold.requiredSlotCount,
            hitCount=0,
            missedSlots=list(gold.uncoveredRequired),
            goldGrade=gold.grade,
            scored=False,
            reason=f"gold_contract_{gold.grade.lower()}",
        )

    slots = [slot for slot in extract_answer_slots(str(expected.get("answerText") or "")) if slot.required]
    if not slots:
        # Fall back: non-numeric answers are not answerExact-scored in v1.
        return AnswerExactScore(
            answerExact=False,
            slotRecall=None,
            requiredSlotCount=0,
            hitCount=0,
            missedSlots=[],
            goldGrade=gold.grade,
            scored=False,
            reason="no_required_slots",
        )

    candidates = flatten_table_numbers(columns, rows)
    if text_summary:
        candidates.extend(_numbers_from_text(text_summary))

    _, missed = coverage_of_slots(slots, candidates, abs_tol=tolerance, rel_tol=rel_tol)
    hit = len(slots) - len(missed)
    recall = hit / len(slots)
    has_evidence = bool(text_summary) or (isinstance(rows, list) and len(rows) > 0)
    exact = len(missed) == 0 and has_evidence
    return AnswerExactScore(
        answerExact=exact,
        slotRecall=round(recall, 6),
        requiredSlotCount=len(slots),
        hitCount=hit,
        missedSlots=[slot.value for slot in missed],
        goldGrade=gold.grade,
        scored=True,
        reason="ok" if exact else "slot_miss",
    )


def scan_dataset_records(
    records: Iterable[dict[str, Any]],
    *,
    abs_tol: float = DEFAULT_ABS_TOL,
    rel_tol: float = DEFAULT_REL_TOL,
) -> dict[str, Any]:
    """Scan a split for gold-contract grades and aggregate rates."""

    items = [assess_gold_contract(record, abs_tol=abs_tol, rel_tol=rel_tol) for record in records]
    by_grade: dict[str, int] = {}
    for item in items:
        by_grade[item.grade] = by_grade.get(item.grade, 0) + 1
    official_scorable = by_grade.get("GOLD_OK", 0)
    return {
        "recordCount": len(items),
        "byGrade": dict(sorted(by_grade.items())),
        "officialScorableCount": official_scorable,
        "officialScorableRate": round(official_scorable / len(items), 6) if items else 0.0,
        "items": [item.to_dict() for item in items],
    }


def equal_table(
    expected: dict[str, Any],
    columns: list[str],
    rows: list[list[Any]],
) -> bool:
    """Strict table equality used as auxiliary tableEX (legacy-compatible)."""

    if expected.get("columns") != columns:
        return False
    expected_rows = expected.get("rows", [])
    if not isinstance(expected_rows, list) or len(expected_rows) != len(rows):
        return False
    tolerance = float(expected.get("numericTolerance") or 0.0)

    def close(left: Any, right: Any) -> bool:
        if isinstance(left, numbers.Real) and not isinstance(left, bool):
            if isinstance(right, numbers.Real) and not isinstance(right, bool):
                return abs(float(left) - float(right)) <= tolerance
        return left == right

    ordered_expected = list(expected_rows)
    ordered_actual = list(rows)
    if not expected.get("orderSensitive", False):
        ordered_expected = sorted(
            ordered_expected, key=lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True)
        )
        ordered_actual = sorted(
            ordered_actual, key=lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True)
        )
    return all(
        len(exp_row) == len(act_row)
        and all(close(e, a) for e, a in zip(exp_row, act_row))
        for exp_row, act_row in zip(ordered_expected, ordered_actual)
    )
