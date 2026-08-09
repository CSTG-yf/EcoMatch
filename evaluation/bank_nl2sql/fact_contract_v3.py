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
    equal_table,
    extract_answer_slots,
    values_close,
)


SCHEMA_VERSION = "3.0-dry-run"

_DATE_TOKEN = re.compile(r"\d{4}-\d{1,2}-\d{1,2}")
_RANK_OUTPUT = re.compile(r"第\s*([1-9]\d*)\s*名")
_TOTAL_COUNT = re.compile(r"共\s*([1-9]\d*)\s*家")
_PROVINCE_MEAN = re.compile(r"(?:全省|省)均值[（(]?\s*(-?\d+(?:\.\d+)?)")
_HIGHER_NEGATIVE = re.compile(r"(?:高出|高于|上升|增长)[^。；]{0,16}-\s*\d+(?:\.\d+)?")
_GOOD_SEGMENT = re.compile(r"表现较好(?:指标)?[:：](.*?)(?:表现较差|$)")
_RANK_REQUEST = re.compile(r"排第几|排名|表现较好|表现较差")
_MEAN_REQUEST = re.compile(r"均值|平均值|对比|比较|比.*(?:全省|省)均")


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


def _close(left: float, right: float, *, kind: str, tolerance: float) -> bool:
    fact_tolerance = max(tolerance, 0.02) if kind in {"PERCENT", "NUMBER"} else tolerance
    return values_close(left, right, abs_tol=fact_tolerance, rel_tol=DEFAULT_REL_TOL)


def _question_values(question: str) -> list[float]:
    return [slot.value for slot in extract_answer_slots(question) if slot.kind != "year"]


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

    facts: list[FactDraft] = []
    seen: set[tuple[float, str]] = set()
    for slot in extract_answer_slots(answer_text):
        if slot.kind == "year":
            continue

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
    text_values = [
        slot.value
        for slot in extract_answer_slots(text_summary or "")
        if slot.kind != "year"
    ]
    return table_values, text_values


def _is_numeric_cell(value: Any) -> bool:
    return isinstance(value, numbers.Real) and not isinstance(value, bool)


def _expected_rows_are_bound(
    expected: dict[str, Any],
    *,
    columns: list[str],
    rows: list[list[Any]],
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

    has_identity = any(
        cell is not None and not _is_numeric_cell(cell)
        for row in expected_rows
        if isinstance(row, (list, tuple))
        for cell in row
    )
    if not has_identity:
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

    unmatched = list(range(len(projected_rows)))
    for expected_row in expected_rows:
        if not isinstance(expected_row, (list, tuple)) or len(expected_row) != len(expected_names):
            return False
        matched_index = next(
            (
                candidate
                for candidate in unmatched
                if all(
                    cells_equal(expected_value, actual_value)
                    for expected_value, actual_value in zip(
                        expected_row, projected_rows[candidate]
                    )
                )
            ),
            None,
        )
        if matched_index is None:
            return False
        unmatched.remove(matched_index)
    return True


def _fact_is_grounded_in_result(
    fact: FactDraft,
    *,
    table_values: list[float],
) -> bool:
    tolerance = DEFAULT_ABS_TOL
    if any(_close(fact.value, value, kind=fact.kind, tolerance=tolerance) for value in table_values):
        return True
    if fact.kind in {"NUMBER", "PERCENT"}:
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

    scored_items: list[dict[str, Any]] = []
    case_hits = 0
    result_hits = 0
    final_fact_hits = 0
    ready_count = 0
    table_exact_hits = 0

    for record in records:
        sample_id = str(record.get("id") or "")
        contract = build_fact_contract(record)
        prediction = prediction_by_id.get(sample_id)
        result_facts_exact = False
        table_exact = False
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
                table_exact = bool(
                    equal_table(expected, [str(column) for column in columns], rows)
                )
                result_evidence = "CAPTURED_ROWS"
            elif isinstance(prediction.get("match"), bool):
                result_facts_exact = bool(prediction["match"])
                table_exact = bool(prediction["match"])
                result_evidence = "LEGACY_MATCH"

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
                    )
                    for fact in required_facts
                )
                row_binding_ok = _expected_rows_are_bound(
                    expected,
                    columns=[str(column) for column in columns],
                    rows=rows,
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
            final_facts_exact = final_numeric_ok and semantic_ok

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
        table_exact_hits += int(table_exact)
        scored_items.append(
            {
                "id": sample_id,
                "contractStatus": contract.status,
                "contractReasons": list(contract.reasons),
                "resultExact": result_facts_exact,
                "resultFactsExact": result_facts_exact,
                "tableExact": table_exact,
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
            "resultAccuracy": _rate(result_hits, denominator),
            "resultExactHits": result_hits,
            "resultFactAccuracy": _rate(result_hits, denominator),
            "resultFactsExactHits": result_hits,
            "tableExactAccuracy": _rate(table_exact_hits, denominator),
            "tableExactHits": table_exact_hits,
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
                "required answer facts grounded in captured SQL result and complete "
                "structured-gold row identities preserved"
            ),
            "finalFactsExact": (
                "all required answer facts present with no extra numeric or contradictory "
                "semantic facts"
            ),
            "tableExact": "diagnostic only; projection shape is not scored",
            "denominator": "ALL_SELECTED_RECORDS",
            "sqlTextScored": False,
            "reviewRequiredBehavior": "FAIL_CLOSED",
        },
        "run": report.get("run"),
        "items": scored_items,
    }
