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

import itertools
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


@dataclass(frozen=True)
class FactDraft:
    value: float
    kind: str
    raw: str
    required: bool
    support: str
    derivation: str | None = None

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


def _derive(value: float, candidates: list[float], *, kind: str, tolerance: float) -> str | None:
    for left, right in itertools.combinations(candidates, 2):
        operations = (
            ("SUM", left + right),
            ("DIFFERENCE", left - right),
            ("DIFFERENCE", right - left),
        )
        if not math.isclose(right, 0.0, abs_tol=1e-12):
            operations += (("RATIO_PERCENT", left * 100.0 / right),)
        if not math.isclose(left, 0.0, abs_tol=1e-12):
            operations += (("RATIO_PERCENT", right * 100.0 / left),)
        for operation, derived in operations:
            if _close(value, derived, kind=kind, tolerance=tolerance):
                return operation
    for first, second, total in itertools.permutations(candidates, 3):
        if _close(
            value,
            first + second - total,
            kind=kind,
            tolerance=tolerance,
        ):
            return "SUM_DIFFERENCE"
    return None


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
    seen: set[tuple[float, str]] = set()
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

        key = (value, kind)
        if key in seen:
            continue
        seen.add(key)

        in_question = any(_close(value, candidate, kind=kind, tolerance=tolerance) for candidate in question_values)
        if in_question:
            support = "QUESTION_CONTEXT"
            derivation = None
            required = False
        elif any(_close(value, candidate, kind=kind, tolerance=tolerance) for candidate in table_values):
            support = "DIRECT_RESULT"
            derivation = None
        else:
            derivation = _derive(value, table_values, kind=kind, tolerance=tolerance)
            if (
                derivation is None
                and asks_for_count
                and condition_true_count is not None
                and _close(value, condition_true_count, kind=kind, tolerance=tolerance)
            ):
                derivation = "COUNT_TRUE"
            support = "DERIVED_RESULT" if derivation else "MISSING"

        facts.append(
            FactDraft(
                value=value,
                kind=kind,
                raw=slot.raw,
                required=required,
                support=support,
                derivation=derivation,
            )
        )

    risks = _source_risks(question, answer_text)
    warnings: list[str] = []
    if any(fact.required and fact.support == "MISSING" for fact in facts):
        warnings.append("LEGACY_TABLE_MISSING_ANSWER_FACT")
    if asks_for_mean or re.search(r"哪些表现较好|哪些表现较差", question):
        warnings.append("SEMANTIC_BINDING_DIAGNOSTIC")

    reasons: list[str] = []
    if risks:
        reasons.append("SOURCE_SEMANTIC_RISK")
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
        reasons=reasons,
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
    if any(name not in columns for name in expected_names):
        return False

    indexes = [columns.index(name) for name in expected_names]
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


def _fact_is_grounded_in_result(
    fact: FactDraft,
    *,
    table_values: list[float],
    columns: list[str] | None = None,
    rows: list[list[Any]] | None = None,
) -> bool:
    tolerance = DEFAULT_ABS_TOL
    if any(_close(fact.value, value, kind=fact.kind, tolerance=tolerance) for value in table_values):
        return True
    if fact.kind in {"NUMBER", "PERCENT"}:
        if fact.derivation == "COUNT_TRUE":
            condition_true_count = _condition_true_count(columns, rows)
            return condition_true_count is not None and _close(
                fact.value,
                condition_true_count,
                kind=fact.kind,
                tolerance=DEFAULT_ABS_TOL,
            )
        return _derive(fact.value, table_values, kind=fact.kind, tolerance=tolerance) is not None
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
) -> bool:
    allowed_facts = list(contract.facts)
    return all(
        any(
            _close(fact.value, value, kind=fact.kind, tolerance=DEFAULT_ABS_TOL)
            for fact in allowed_facts
        )
        for value in text_values
    )


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
    return {match.group(0).casefold() for match in _ORGANIZATION_NAME.finditer(normalized)}


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
) -> dict[str, Any]:
    """Score every selected record; unresolved contracts fail closed, never skip."""

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
                facts_grounded = bool(required_facts) and all(
                    _fact_is_grounded_in_result(
                        fact,
                        table_values=table_values,
                        columns=[str(column) for column in columns],
                        rows=rows,
                    )
                    for fact in required_facts
                )
                row_binding_ok = _expected_rows_are_bound(
                    expected,
                    columns=[str(column) for column in columns],
                    rows=rows,
                    require_exact_rows=contract.legacyGoldGrade == "GOLD_OK",
                )
                result_facts_exact = facts_grounded and row_binding_ok
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
                elif not final_facts_exact:
                    reason = "final_fact_mismatch"

        case_pass = bool(result_facts_exact and final_facts_exact and contract.status == "READY")
        case_hits += int(case_pass)
        result_hits += int(result_facts_exact)
        final_fact_hits += int(final_facts_exact)
        scored_items.append(
            {
                "id": sample_id,
                "contractStatus": contract.status,
                "contractReasons": list(contract.reasons),
                "resultExact": result_facts_exact,
                "resultFactsExact": result_facts_exact,
                "resultEvidence": result_evidence,
                "finalFactsExact": final_facts_exact,
                "casePass": case_pass,
                "reason": reason,
            }
        )

    denominator = len(records)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "recordCount": denominator,
        "metrics": {
            "caseAccuracy": _rate(case_hits, denominator),
            "casePassHits": case_hits,
            "caseDenominator": denominator,
            "resultExactHits": result_hits,
            "resultFactAccuracy": _rate(result_hits, denominator),
            "resultFactsExactHits": result_hits,
            "finalFactAccuracy": _rate(final_fact_hits, denominator),
            "finalFactsExactHits": final_fact_hits,
            "contractReadyRate": _rate(ready_count, denominator),
            "contractReadyCount": ready_count,
            "excludedCount": 0,
        },
        "policy": {
            "primaryMetric": "caseAccuracy",
            "casePass": "resultExact AND finalFactsExact",
            "resultExact": (
                "required answer facts grounded in captured SQL result; complete structured "
                "gold has exact projected rows and incomplete gold preserves available identities"
            ),
            "finalFactsExact": (
                "all required answer facts and answer entities present with no extra numeric, "
                "out-of-context entity or contradictory semantic facts"
            ),
            "denominator": "ALL_SELECTED_RECORDS",
            "sqlTextScored": False,
            "reviewRequiredBehavior": "FAIL_CLOSED",
        },
        "run": report.get("run"),
        "items": scored_items,
    }
