#!/usr/bin/env python3
"""Evaluate the real SuperSonic parse-and-execute pipeline on DATA-02.

The evaluator sends only natural-language questions and runtime identifiers to
SuperSonic.  Gold SQL and expected rows are kept locally for scoring, never
added to parse or execute requests.  Development defaults to the dev split;
the frozen test split requires an explicit acknowledgement and local run
registry entry.
"""

from __future__ import annotations

import argparse
import json
import math
import multiprocessing
import queue
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from collections.abc import Callable, Iterable
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

from evaluate_predictions import _equal_value, _json_value, _matches_expected
from evaluation_policy import EvaluationAccessError, load_evaluation_records, record_final_test_run


DEFAULT_QUERY_API_PREFIX = "/openapi/chat/query"
DEFAULT_MANAGE_API_PREFIX = "/openapi/chat/manage"
DATA_CONTRACT_ISSUE_CODE = "STRUCTURED_GOLD_INCOMPLETE"


class SuperSonicEvaluationError(RuntimeError):
    """The runtime response did not satisfy the evaluator's API contract."""


class SuperSonicTransportError(SuperSonicEvaluationError):
    """An HTTP or transport failure observed by the runner."""

    def __init__(self, message: str, *, retry_count: int, retry_exhausted: bool) -> None:
        super().__init__(message)
        self.retry_count = retry_count
        self.retry_exhausted = retry_exhausted


class SuperSonicServiceError(SuperSonicEvaluationError):
    """The API returned an unsuccessful service envelope without a business result."""


_STAGE_ERROR_CATEGORIES = {
    "save": "CONVERSATION_ERROR",
    "parse": "PARSE_ERROR",
    "execute": "EXECUTION_ERROR",
    "summary": "SUMMARY_ERROR",
}
_RETRYABLE_CHECKPOINT_CATEGORIES = {
    "HTTP_RETRY_EXHAUSTED",
    "HTTP_RECORD_TIMEOUT",
    "HTTP_RECORD_WORKER_FAILURE",
    "SERVICE_ERROR",
    "CONVERSATION_ERROR",
    "SUMMARY_ERROR",
}
_BANK_TELEMETRY_ALLOWED_VALUES = {
    "generator": {"BANK_CONSTRAINED_PLAN"},
    "planIntent": {
        "POINT_QUERY",
        "COMPARISON",
        "RANKING",
        "TREND",
        "CHANGE",
        "RATIO",
        "THRESHOLD",
        "AGGREGATION",
    },
    "timeComparison": {"NONE", "YEAR_OVER_YEAR", "PERIOD_OVER_PERIOD", "START_OF_YEAR", "MOM_AND_YOY"},
    "calculationType": {"DIRECT", "CHANGE", "RATIO"},
    "route": {"STRUCT", "S2SQL_TEMPLATE"},
    "templateCategory": {"STRUCT", "CHANGE", "MONTH_AND_YEAR_CHANGE", "OTHER_S2SQL_TEMPLATE"},
}
_BANK_ROUTING_SQL_GEN_TYPES = {
    "ONE_PASS_SELF_CONSISTENCY",
    "BANK_CONSTRAINED_PLAN",
}
_BANK_CANDIDATE_REJECTION_STATES = {
    "NO_RESPONSE",
    "PLAN_EXCEPTION",
    "COMPILER_EXCEPTION",
    "VALIDATION_REJECTED",
    "NO_CANDIDATE",
}
_SQL_ERROR_TYPES = {
    "MAPPING_ERROR",
    "DEFINITION_ERROR",
    "JOIN_ERROR",
    "FILTER_ERROR",
    "SYNTAX_ERROR",
    "EXECUTION_ERROR",
}
_BANK_CANDIDATE_COMPILER_REASONS = {
    "INVALID_PLAN",
    "CLARIFICATION_REQUIRED",
    "SCHEMA_REQUIRED",
    "DATASET_REQUIRED",
    "METRIC_UNAVAILABLE",
    "DIMENSION_UNAVAILABLE",
    "ORGANIZATION_DIMENSION_UNAVAILABLE",
    "TIME_DIMENSION_UNAVAILABLE",
    "OUTPUT_ORDER_MISMATCH",
    "ORDER_FIELD_NOT_SELECTED",
    "UNSUPPORTED_FILTER",
    "UNSUPPORTED_CALCULATION",
    "S2SQL_RENDER_FAILED",
}
_LLM_INTERNAL_CANDIDATE_TYPE = "internal"
_EXECUTED_CANDIDATE_ORIGIN_LLM_INTERNAL = "LLM_INTERNAL"
_EXECUTED_CANDIDATE_ORIGIN_NON_LLM = "NON_LLM_OR_UNSPECIFIED"
_EXECUTION_FAILURE_LAYERS = {
    "SQL_SAFETY_POLICY",
    "QUERY_GATEWAY",
    "JDBC_GRAMMAR",
    "JDBC_DATA_ACCESS",
    "JDBC_OTHER",
}


def _new_stage_telemetry() -> dict[str, dict[str, Any]]:
    return {
        stage: {
            "state": "NOT_STARTED",
            "durationMs": None,
            "retryCount": 0,
            "retryExhausted": False,
            "exceptionCategory": None,
        }
        for stage in _STAGE_ERROR_CATEGORIES
    }


def _request_retry_info(post_json: Callable[[str, dict[str, Any]], Any]) -> dict[str, int | bool]:
    reader = getattr(post_json, "get_last_request_retry_info", None)
    if not callable(reader):
        return {"retryCount": 0, "retryExhausted": False}
    value = reader()
    if not isinstance(value, dict):
        return {"retryCount": 0, "retryExhausted": False}
    retry_count = value.get("retryCount", 0)
    return {
        "retryCount": retry_count if isinstance(retry_count, int) and retry_count >= 0 else 0,
        "retryExhausted": value.get("retryExhausted") is True,
    }


def _stage_error_category(stage: str, error: Exception) -> str:
    if isinstance(error, SuperSonicTransportError):
        return "HTTP_RETRY_EXHAUSTED" if error.retry_exhausted else "HTTP_TRANSPORT_ERROR"
    if isinstance(error, SuperSonicServiceError):
        return "SERVICE_ERROR"
    return _STAGE_ERROR_CATEGORIES[stage]


def _finish_stage(
    item: dict[str, Any],
    *,
    stage: str,
    started: float,
    post_json: Callable[[str, dict[str, Any]], Any],
    state: str,
    error: Exception | None = None,
) -> str | None:
    telemetry = item["stages"][stage]
    retry_info = _request_retry_info(post_json)
    if isinstance(error, SuperSonicTransportError):
        retry_info["retryCount"] = max(retry_info["retryCount"], error.retry_count)
        retry_info["retryExhausted"] = error.retry_exhausted
    telemetry.update(
        {
            "state": state,
            "durationMs": round((time.perf_counter() - started) * 1000, 3),
            "retryCount": retry_info["retryCount"],
            "retryExhausted": retry_info["retryExhausted"],
            "exceptionCategory": _stage_error_category(stage, error) if error else None,
        }
    )
    return telemetry["exceptionCategory"]


def _checkpoint_item_is_resumable(item: dict[str, Any]) -> bool:
    explicit = item.get("resumeEligible")
    if isinstance(explicit, bool):
        return explicit
    category = item.get("errorCategory")
    if category in _RETRYABLE_CHECKPOINT_CATEGORIES or category == "HTTP_TRANSPORT_ERROR":
        return False
    return item.get("match") is True or category in {
        "RESULT_MISMATCH",
        "PARSE_ERROR",
        "EXECUTION_ERROR",
    }


def _resumable_checkpoint_items(items: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Return only terminal checkpoint items that may safely be skipped on resume."""

    return [item for item in items if _checkpoint_item_is_resumable(item)]


def _unwrap_api_value(response: Any) -> Any:
    """Accept the controller's standard ``{code, data}`` envelope or raw data."""

    if not isinstance(response, dict):
        return response
    if "code" not in response:
        return response
    if str(response.get("code")) != "200":
        raise SuperSonicServiceError("SuperSonic API did not report success")
    return response.get("data")


def _unwrap_api_response(response: Any) -> dict[str, Any]:
    data = _unwrap_api_value(response)
    if not isinstance(data, dict):
        raise SuperSonicEvaluationError("Successful SuperSonic API response did not contain object data")
    return data


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _latency_distribution(values: Iterable[float | int | None]) -> dict[str, float | int | None]:
    samples = sorted(float(value) for value in values if value is not None)
    if not samples:
        return {
            "count": 0,
            "average": None,
            "p50": None,
            "p95": None,
            "p99": None,
            "max": None,
        }

    def percentile(fraction: float) -> float:
        index = max(0, min(len(samples) - 1, math.ceil(fraction * len(samples)) - 1))
        return round(samples[index], 3)

    return {
        "count": len(samples),
        "average": round(sum(samples) / len(samples), 3),
        "p50": percentile(0.50),
        "p95": percentile(0.95),
        "p99": percentile(0.99),
        "max": round(samples[-1], 3),
    }


def _safe_column_names(execute_response: dict[str, Any]) -> list[str]:
    rows = execute_response.get("queryResults")
    if not isinstance(rows, list):
        raise SuperSonicEvaluationError("Execution response did not contain queryResults")
    if not rows:
        columns = execute_response.get("queryColumns") or []
        if not isinstance(columns, list):
            raise SuperSonicEvaluationError("Execution response queryColumns is invalid")
        return [
            str(column.get("nameEn") or column.get("bizName") or column.get("name"))
            for column in columns
            if isinstance(column, dict)
            and (column.get("nameEn") or column.get("bizName") or column.get("name"))
        ]
    if not all(isinstance(row, dict) for row in rows):
        raise SuperSonicEvaluationError("Execution response queryResults must contain objects")

    columns = execute_response.get("queryColumns") or []
    if isinstance(columns, list):
        for key in ("nameEn", "bizName", "name"):
            candidate = [str(column[key]) for column in columns if isinstance(column, dict) and column.get(key)]
            if candidate and all(all(column in row for column in candidate) for row in rows):
                return candidate
    return [str(column) for column in rows[0].keys()]


def _rows_from_response(execute_response: dict[str, Any]) -> tuple[list[str], list[list[Any]]]:
    columns = _safe_column_names(execute_response)
    rows = execute_response.get("queryResults") or []
    return columns, [[_json_value(row.get(column)) for column in columns] for row in rows]


def _mismatch_reason(
    expected: dict[str, Any], columns: list[str], rows: list[list[Any]]
) -> str | None:
    """Classify a strict mismatch without retaining query results in the report."""

    if _matches_expected(expected, columns, rows):
        return None
    if expected.get("columns") != columns:
        return "COLUMN_PROJECTION"

    expected_rows = expected.get("rows", [])
    if len(expected_rows) != len(rows):
        return "ROW_COUNT"

    if expected.get("orderSensitive", False):
        tolerance = float(expected.get("numericTolerance") or 0.0)
        ordered_expected = sorted(
            expected_rows,
            key=lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True),
        )
        ordered_actual = sorted(
            rows,
            key=lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True),
        )
        if all(
            len(expected_row) == len(actual_row)
            and all(
                _equal_value(expected_value, actual_value, tolerance)
                for expected_value, actual_value in zip(expected_row, actual_row)
            )
            for expected_row, actual_row in zip(ordered_expected, ordered_actual)
        ):
            return "ORDER_ONLY"
    return "ROW_VALUE"


def _allowlisted_bank_telemetry(properties: object) -> dict[str, str]:
    if not isinstance(properties, dict):
        return {}
    telemetry = properties.get("bankTelemetry")
    if not isinstance(telemetry, dict):
        return {}
    return {
        field: value
        for field, allowed_values in _BANK_TELEMETRY_ALLOWED_VALUES.items()
        if isinstance((value := telemetry.get(field)), str) and value in allowed_values
    }


def _allowlisted_bank_routing_telemetry(properties: object) -> dict[str, str | bool]:
    if not isinstance(properties, dict):
        return {}
    telemetry = properties.get("bankRoutingTelemetry")
    if not isinstance(telemetry, dict):
        return {}

    result: dict[str, str | bool] = {}
    for field in ("bankConstrainedPlanEnabled", "bankDatasetQualified"):
        if isinstance(telemetry.get(field), bool):
            result[field] = telemetry[field]
    selected_sql_gen_type = telemetry.get("selectedSqlGenType")
    if (
        isinstance(selected_sql_gen_type, str)
        and selected_sql_gen_type in _BANK_ROUTING_SQL_GEN_TYPES
    ):
        result["selectedSqlGenType"] = selected_sql_gen_type
    return result


def _allowlisted_bank_routing_attempt_telemetry(
    parse_response: dict[str, Any],
) -> dict[str, str | bool]:
    telemetry = parse_response.get("bankRoutingAttemptTelemetry")
    if not isinstance(telemetry, dict):
        return {}

    result: dict[str, str | bool] = {}
    for field in (
        "bankConstrainedPlanEnabled",
        "bankDatasetQualified",
        "llmCandidateCreated",
    ):
        if isinstance(telemetry.get(field), bool):
            result[field] = telemetry[field]
    selected_sql_gen_type = telemetry.get("selectedSqlGenType")
    if (
        isinstance(selected_sql_gen_type, str)
        and selected_sql_gen_type in _BANK_ROUTING_SQL_GEN_TYPES
    ):
        result["selectedSqlGenType"] = selected_sql_gen_type
    candidate_rejection_state = telemetry.get("candidateRejectionState")
    if (
        isinstance(candidate_rejection_state, str)
        and candidate_rejection_state in _BANK_CANDIDATE_REJECTION_STATES
    ):
        result["candidateRejectionState"] = candidate_rejection_state
        candidate_validation_error_type = telemetry.get("candidateValidationErrorType")
        if (
            candidate_rejection_state == "VALIDATION_REJECTED"
            and isinstance(candidate_validation_error_type, str)
            and candidate_validation_error_type in _SQL_ERROR_TYPES
        ):
            result["candidateValidationErrorType"] = candidate_validation_error_type
        candidate_compiler_reason = telemetry.get("candidateCompilerReason")
        if (
            candidate_rejection_state == "COMPILER_EXCEPTION"
            and isinstance(candidate_compiler_reason, str)
            and candidate_compiler_reason in _BANK_CANDIDATE_COMPILER_REASONS
        ):
            result["candidateCompilerReason"] = candidate_compiler_reason
    return result


def _candidate_observation(selected: list[Any]) -> dict[str, str | int]:
    first = selected[0]
    properties = first.get("properties") if isinstance(first, dict) else None
    first_type = properties.get("type") if isinstance(properties, dict) else None
    origin = (
        _EXECUTED_CANDIDATE_ORIGIN_LLM_INTERNAL
        if first_type == _LLM_INTERNAL_CANDIDATE_TYPE
        else _EXECUTED_CANDIDATE_ORIGIN_NON_LLM
    )
    routing_candidate_count = sum(
        len(_allowlisted_bank_routing_telemetry(candidate.get("properties"))) == 3
        for candidate in selected
        if isinstance(candidate, dict)
    )
    return {
        "executedCandidateOrigin": origin,
        "bankRoutingCandidateCount": routing_candidate_count,
        "selectedParseCount": len(selected),
    }


def _allowlisted_execution_telemetry(execute_response: dict[str, Any]) -> dict[str, str | bool]:
    context = execute_response.get("chatContext")
    properties = context.get("properties") if isinstance(context, dict) else None
    telemetry = properties.get("executionTelemetry") if isinstance(properties, dict) else None
    if not isinstance(telemetry, dict):
        return {}
    result: dict[str, str | bool] = {}
    failure_layer = telemetry.get("failureLayer")
    if isinstance(failure_layer, str) and failure_layer in _EXECUTION_FAILURE_LAYERS:
        result["failureLayer"] = failure_layer
    for field in ("repairAttempted", "repaired"):
        if isinstance(telemetry.get(field), bool):
            result[field] = telemetry[field]
    return result


def _selected_parse(
    parse_response: dict[str, Any],
) -> tuple[
    int,
    dict[str, str],
    dict[str, str | bool],
    dict[str, str | int],
    dict[str, str | bool],
]:
    if parse_response.get("state") != "COMPLETED":
        raise SuperSonicEvaluationError("Parse response did not reach COMPLETED state")
    selected = parse_response.get("selectedParses")
    if not isinstance(selected, list) or not selected or not isinstance(selected[0], dict):
        raise SuperSonicEvaluationError("Parse response did not contain selectedParses")
    parse_id = selected[0].get("id")
    if not isinstance(parse_id, int):
        raise SuperSonicEvaluationError("Selected parse did not contain an integer id")
    properties = selected[0].get("properties")
    return (
        parse_id,
        _allowlisted_bank_telemetry(properties),
        _allowlisted_bank_routing_telemetry(properties),
        _candidate_observation(selected),
        _allowlisted_bank_routing_attempt_telemetry(parse_response),
    )


def _record_group_metrics(records: list[dict[str, Any]], key: str) -> dict[str, dict[str, Any]]:
    grouped: dict[str, Counter[str]] = defaultdict(Counter)
    for item in records:
        if key == "sqlFeatures":
            values = item.get(key)
        elif key == "templateCategory":
            telemetry = item.get("bankTelemetry")
            values = [telemetry.get(key, "UNSPECIFIED") if isinstance(telemetry, dict) else "UNSPECIFIED"]
        elif key == "selectedSqlGenType":
            telemetry = item.get("bankRoutingTelemetry")
            values = [telemetry.get(key, "UNSPECIFIED") if isinstance(telemetry, dict) else "UNSPECIFIED"]
        elif key == "attemptSelectedSqlGenType":
            telemetry = item.get("bankRoutingAttemptTelemetry")
            values = [telemetry.get("selectedSqlGenType", "UNSPECIFIED")
                      if isinstance(telemetry, dict) else "UNSPECIFIED"]
        elif key == "attemptCandidateRejectionState":
            telemetry = item.get("bankRoutingAttemptTelemetry")
            values = [telemetry.get("candidateRejectionState", "NONE")
                      if isinstance(telemetry, dict) else "NONE"]
        elif key == "attemptCandidateValidationErrorType":
            telemetry = item.get("bankRoutingAttemptTelemetry")
            values = [telemetry.get("candidateValidationErrorType", "NONE")
                      if isinstance(telemetry, dict) else "NONE"]
        elif key == "attemptCandidateCompilerReason":
            telemetry = item.get("bankRoutingAttemptTelemetry")
            values = [telemetry.get("candidateCompilerReason", "NONE")
                      if isinstance(telemetry, dict) else "NONE"]
        elif key == "failureLayer":
            telemetry = item.get("executionTelemetry")
            values = [telemetry.get(key, "NONE") if isinstance(telemetry, dict) else "NONE"]
        else:
            values = [item.get(key, "UNSPECIFIED")]
        for value in values or ["UNSPECIFIED"]:
            grouped[str(value)]["count"] += 1
            grouped[str(value)]["parse"] += int(item["parse"])
            grouped[str(value)]["execute"] += int(item["execute"])
            grouped[str(value)]["match"] += int(item["match"])
    return {
        name: {
            "count": counter["count"],
            "parseSuccessRate": _rate(counter["parse"], counter["count"]),
            "executionSuccessRate": _rate(counter["execute"], counter["count"]),
            "resultAccuracy": _rate(counter["match"], counter["count"]),
        }
        for name, counter in sorted(grouped.items())
    }


def _create_conversation(
    *,
    post_json: Callable[[str, dict[str, Any]], Any],
    manage_api_prefix: str,
    sample_id: str,
    agent_id: int,
) -> int:
    query = urllib.parse.urlencode(
        {"chatName": f"evaluation-{sample_id}", "agentId": agent_id}
    )
    chat_id = _unwrap_api_value(post_json(f"{manage_api_prefix}/save?{query}", {}))
    if not isinstance(chat_id, int) or isinstance(chat_id, bool):
        raise SuperSonicEvaluationError("Conversation creation did not return an integer chatId")
    return chat_id


def _poll_execute_summary(
    *,
    post_json: Callable[[str, dict[str, Any]], Any],
    summary_endpoint: str,
    query_id: int,
    timeout_seconds: float,
    poll_interval_seconds: float,
) -> tuple[str, str | None, float, int]:
    started = time.perf_counter()
    deadline = started + timeout_seconds
    retry_count = 0
    while True:
        response = _unwrap_api_response(post_json(summary_endpoint, {"queryId": query_id}))
        retry_count += int(_request_retry_info(post_json)["retryCount"])
        if response.get("queryMode") is not None:
            summary = response.get("textSummary")
            return (
                "SUCCESS",
                str(summary) if isinstance(summary, str) else None,
                round((time.perf_counter() - started) * 1000, 3),
                retry_count,
            )
        if time.perf_counter() >= deadline:
            return "TIMEOUT", None, round((time.perf_counter() - started) * 1000, 3), retry_count
        time.sleep(poll_interval_seconds)


def _evaluate_record(
    record: dict[str, Any],
    *,
    agent_id: int,
    post_json: Callable[[str, dict[str, Any]], Any],
    query_api_prefix: str,
    manage_api_prefix: str,
    summary_timeout_seconds: float,
    summary_poll_interval_seconds: float,
    cleanup_conversations: bool,
) -> dict[str, Any]:
    sample_id = record["id"]
    question = record["question"].strip()
    item = {
        "id": sample_id,
        "difficulty": str(record.get("difficulty", "UNSPECIFIED")),
        "sqlFeatures": list(record.get("sqlFeatures") or ["UNSPECIFIED"]),
        "parse": False,
        "execute": False,
        "match": False,
        "parseMs": None,
        "executeMs": None,
        "summaryMs": None,
        "endToEndMs": None,
        "summaryState": None,
        "errorCategory": None,
        "chatId": None,
        "queryId": None,
        "conversationCleaned": False,
        "resumeEligible": None,
        "stages": _new_stage_telemetry(),
        "bankTelemetry": {},
        "bankRoutingTelemetry": {},
        "bankRoutingAttemptTelemetry": {},
        "executedCandidateOrigin": _EXECUTED_CANDIDATE_ORIGIN_NON_LLM,
        "bankRoutingCandidateCount": 0,
        "selectedParseCount": 0,
        "executionTelemetry": {},
    }

    def finish_item() -> dict[str, Any]:
        item["resumeEligible"] = _checkpoint_item_is_resumable(item)
        return item

    started = time.perf_counter()
    try:
        chat_id = _create_conversation(
            post_json=post_json,
            manage_api_prefix=manage_api_prefix,
            sample_id=sample_id,
            agent_id=agent_id,
        )
        item["chatId"] = chat_id
        _finish_stage(
            item,
            stage="save",
            started=started,
            post_json=post_json,
            state="SUCCESS",
        )
    except Exception as error:
        item["errorCategory"] = _finish_stage(
            item,
            stage="save",
            started=started,
            post_json=post_json,
            state="ERROR",
            error=error,
        )
        return finish_item()

    query_started = time.perf_counter()

    def finish_query_timing() -> dict[str, Any]:
        item["endToEndMs"] = round((time.perf_counter() - query_started) * 1000, 3)
        return finish_item()

    parse_payload = {
        "queryText": question,
        "agentId": agent_id,
        "chatId": chat_id,
    }
    try:
        started = time.perf_counter()
        parse_response = _unwrap_api_response(
            post_json(f"{query_api_prefix}/parse", parse_payload)
        )
        item["parseMs"] = round((time.perf_counter() - started) * 1000, 3)
        (
            parse_id,
            item["bankTelemetry"],
            item["bankRoutingTelemetry"],
            candidate_observation,
            item["bankRoutingAttemptTelemetry"],
        ) = _selected_parse(parse_response)
        item.update(candidate_observation)
        query_id = parse_response.get("queryId")
        if not isinstance(query_id, int):
            raise SuperSonicEvaluationError("Parse response did not contain an integer queryId")
        item["parse"] = True
        item["queryId"] = query_id
        _finish_stage(
            item,
            stage="parse",
            started=started,
            post_json=post_json,
            state="SUCCESS",
        )
    except Exception as error:
        item["errorCategory"] = _finish_stage(
            item,
            stage="parse",
            started=started,
            post_json=post_json,
            state="ERROR",
            error=error,
        )
        return finish_query_timing()

    execute_payload = {
        "queryId": query_id,
        "parseId": parse_id,
        "queryText": question,
        "agentId": agent_id,
        "chatId": chat_id,
        "streamingResult": True,
    }
    try:
        started = time.perf_counter()
        execute_response = _unwrap_api_response(
            post_json(f"{query_api_prefix}/execute", execute_payload)
        )
        item["executeMs"] = round((time.perf_counter() - started) * 1000, 3)
        item["executionTelemetry"] = _allowlisted_execution_telemetry(execute_response)
        backend_error = execute_response.get("errorMsg")
        if isinstance(backend_error, str) and backend_error.strip():
            raise SuperSonicEvaluationError(
                "SuperSonic execution returned an error message"
            )
        if str(execute_response.get("queryState", "")).upper() != "SUCCESS":
            raise SuperSonicEvaluationError("SuperSonic execution did not report SUCCESS")
        columns, rows = _rows_from_response(execute_response)
        item["execute"] = True
        item["match"] = _matches_expected(record.get("expected", {}), columns, rows)
        if item["match"]:
            item["errorCategory"] = None
        else:
            item["errorCategory"] = "RESULT_MISMATCH"
            item["mismatchReason"] = _mismatch_reason(
                record.get("expected", {}), columns, rows
            )
        _finish_stage(
            item,
            stage="execute",
            started=started,
            post_json=post_json,
            state="SUCCESS",
        )
    except Exception as error:
        item["errorCategory"] = _finish_stage(
            item,
            stage="execute",
            started=started,
            post_json=post_json,
            state="ERROR",
            error=error,
        )
        return finish_query_timing()

    started = time.perf_counter()
    try:
        summary_state, _text_summary, summary_ms, summary_retry_count = _poll_execute_summary(
            post_json=post_json,
            summary_endpoint=f"{query_api_prefix}/getExecuteSummary",
            query_id=query_id,
            timeout_seconds=summary_timeout_seconds,
            poll_interval_seconds=summary_poll_interval_seconds,
        )
        item["summaryState"] = summary_state
        item["summaryMs"] = summary_ms
        if summary_state == "SUCCESS":
            _finish_stage(
                item,
                stage="summary",
                started=started,
                post_json=post_json,
                state="SUCCESS",
            )
        else:
            item["errorCategory"] = _finish_stage(
                item,
                stage="summary",
                started=started,
                post_json=post_json,
                state=summary_state,
                error=SuperSonicEvaluationError("Summary did not complete"),
            )
        item["stages"]["summary"]["retryCount"] = max(
            item["stages"]["summary"]["retryCount"], summary_retry_count
        )
    except Exception as error:
        item["summaryState"] = "ERROR"
        item["errorCategory"] = _finish_stage(
            item,
            stage="summary",
            started=started,
            post_json=post_json,
            state="ERROR",
            error=error,
        )

    finish_query_timing()
    if cleanup_conversations and item["match"]:
        try:
            cleaned = _unwrap_api_value(
                post_json(f"{manage_api_prefix}/delete?chatId={chat_id}", {})
            )
            item["conversationCleaned"] = cleaned is not False
        except Exception as error:
            item["cleanupErrorType"] = type(error).__name__
    return finish_item()


def _load_data_contract_pending(path: Path | str) -> dict[str, Any]:
    """Load the train-only structured-gold contract exception policy."""

    list_path = Path(path)
    try:
        payload = json.loads(list_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SuperSonicEvaluationError(
            f"Data-contract pending list is not readable JSON: {list_path}"
        ) from error
    if not isinstance(payload, dict):
        raise SuperSonicEvaluationError(
            f"Data-contract pending list must be a JSON object: {list_path}"
        )
    if payload.get("issueCode") != DATA_CONTRACT_ISSUE_CODE:
        raise SuperSonicEvaluationError(
            f"Data-contract pending list must use issueCode {DATA_CONTRACT_ISSUE_CODE}"
        )
    record_ids = payload.get("recordIds")
    if not isinstance(record_ids, list):
        raise SuperSonicEvaluationError(
            "Data-contract pending list must contain a recordIds list"
        )
    for record_id in record_ids:
        if not isinstance(record_id, str) or not record_id.strip():
            raise SuperSonicEvaluationError(
                "Data-contract pending list contains an invalid record id"
            )
        if record_id.upper().startswith("TEST"):
            raise SuperSonicEvaluationError(
                f"Data-contract pending list must not reference test records: {record_id}"
            )
        if not re.fullmatch(r"TRAIN-[A-Z0-9-]+", record_id):
            raise SuperSonicEvaluationError(
                f"Data-contract pending list contains an unsupported record id: {record_id}"
            )
    return {"issueCode": DATA_CONTRACT_ISSUE_CODE, "recordIds": list(record_ids)}


def _build_report(
    items: list[dict[str, Any]],
    *,
    data_contract_pending: dict[str, Any] | None = None,
) -> dict[str, Any]:
    pending_by_id = (
        {
            record_id: data_contract_pending["issueCode"]
            for record_id in data_contract_pending.get("recordIds") or []
        }
        if isinstance(data_contract_pending, dict)
        else {}
    )
    for item in items:
        record_id = item.get("id")
        item["scoringEligible"] = record_id not in pending_by_id
        if record_id in pending_by_id:
            item["dataContractIssueCode"] = pending_by_id[record_id]
    pending_hit_ids = [
        item["id"]
        for item in items
        if isinstance(item.get("id"), str) and item["id"] in pending_by_id
    ]
    eligible_items = [item for item in items if item["scoringEligible"]]
    error_categories: Counter[str] = Counter(
        item["errorCategory"] or "NONE" for item in items
    )
    eligible_error_categories: Counter[str] = Counter(
        item["errorCategory"] or "NONE" for item in eligible_items
    )
    parse_latencies = [item["parseMs"] for item in items if item["parseMs"] is not None]
    execute_latencies = [item["executeMs"] for item in items if item["executeMs"] is not None]
    summary_latencies = [item["summaryMs"] for item in items if item["summaryMs"] is not None]
    end_to_end_latencies = [
        item["endToEndMs"] for item in items if item.get("endToEndMs") is not None
    ]
    successful_end_to_end_latencies = [
        item["endToEndMs"]
        for item in items
        if item.get("endToEndMs") is not None
        and item.get("execute") is True
        and item.get("summaryState") == "SUCCESS"
    ]
    count = len(items)
    parsed = sum(int(item["parse"]) for item in items)
    executed = sum(int(item["execute"]) for item in items)
    matched = sum(int(item["match"]) for item in items)
    eligible_count = len(eligible_items)
    eligible_parsed = sum(int(item["parse"]) for item in eligible_items)
    eligible_executed = sum(int(item["execute"]) for item in eligible_items)
    eligible_matched = sum(int(item["match"]) for item in eligible_items)
    return {
        "recordCount": count,
        "metrics": {
            "parseSuccessRate": _rate(parsed, count),
            "executionSuccessRate": _rate(executed, count),
            "resultAccuracy": _rate(matched, count),
        },
        "scoreEligibleRecordCount": eligible_count,
        "scoreEligibleMetrics": {
            "parseSuccessRate": _rate(eligible_parsed, eligible_count),
            "executionSuccessRate": _rate(eligible_executed, eligible_count),
            "resultAccuracy": _rate(eligible_matched, eligible_count),
        },
        "scoreEligibleErrorCategories": dict(sorted(eligible_error_categories.items())),
        "dataContractPending": {
            "recordCount": len(pending_hit_ids),
            "issueCode": pending_by_id[pending_hit_ids[0]] if pending_hit_ids else None,
            "recordIds": pending_hit_ids,
        },
        "timingMs": {
            "averageParseMs": round(sum(parse_latencies) / len(parse_latencies), 3) if parse_latencies else None,
            "averageExecuteMs": round(sum(execute_latencies) / len(execute_latencies), 3) if execute_latencies else None,
            "averageSummaryMs": round(sum(summary_latencies) / len(summary_latencies), 3)
            if summary_latencies
            else None,
        },
        "timingDistributionsMs": {
            "parse": _latency_distribution(parse_latencies),
            "execute": _latency_distribution(execute_latencies),
            "summary": _latency_distribution(summary_latencies),
            "endToEnd": _latency_distribution(end_to_end_latencies),
            "successfulEndToEnd": _latency_distribution(
                successful_end_to_end_latencies
            ),
        },
        "byDifficulty": _record_group_metrics(items, "difficulty"),
        "bySqlFeature": _record_group_metrics(items, "sqlFeatures"),
        "byTemplateCategory": _record_group_metrics(items, "templateCategory"),
        "bySelectedSqlGenType": _record_group_metrics(items, "selectedSqlGenType"),
        "byAttemptSelectedSqlGenType": _record_group_metrics(
            items, "attemptSelectedSqlGenType"
        ),
        "byAttemptCandidateRejectionState": _record_group_metrics(
            items, "attemptCandidateRejectionState"
        ),
        "byAttemptCandidateValidationErrorType": _record_group_metrics(
            items, "attemptCandidateValidationErrorType"
        ),
        "byAttemptCandidateCompilerReason": _record_group_metrics(
            items, "attemptCandidateCompilerReason"
        ),
        "byExecutedCandidateOrigin": _record_group_metrics(items, "executedCandidateOrigin"),
        "byFailureLayer": _record_group_metrics(items, "failureLayer"),
        "errorCategories": dict(sorted(error_categories.items())),
        "items": items,
    }


def _record_transport_failure_item(
    record: dict[str, Any], *, category: str, elapsed_ms: float | None
) -> dict[str, Any]:
    """Build a resumable transport failure without copying sensitive record fields."""

    return {
        "id": record["id"],
        "difficulty": str(record.get("difficulty", "UNSPECIFIED")),
        "sqlFeatures": list(record.get("sqlFeatures") or ["UNSPECIFIED"]),
        "parse": False,
        "execute": False,
        "match": False,
        "parseMs": None,
        "executeMs": None,
        "summaryMs": None,
        "endToEndMs": elapsed_ms,
        "summaryState": None,
        "errorCategory": category,
        "chatId": None,
        "queryId": None,
        "conversationCleaned": False,
        "resumeEligible": False,
        "stages": _new_stage_telemetry(),
        "bankTelemetry": {},
        "bankRoutingTelemetry": {},
        "bankRoutingAttemptTelemetry": {},
        "executedCandidateOrigin": _EXECUTED_CANDIDATE_ORIGIN_NON_LLM,
        "bankRoutingCandidateCount": 0,
        "selectedParseCount": 0,
        "executionTelemetry": {},
    }


def _isolated_record_worker_entry(
    result_queue: Any,
    record: dict[str, Any],
    worker: Callable[..., dict[str, Any]],
    worker_kwargs: dict[str, Any],
) -> None:
    try:
        item = worker(record, **worker_kwargs)
        if not isinstance(item, dict) or item.get("id") != record["id"]:
            raise SuperSonicEvaluationError("Isolated worker returned an invalid evaluation item")
        result_queue.put({"item": item})
    except Exception:
        # Process boundaries must not copy server failures or record content into the report.
        result_queue.put({"workerFailure": True})


def _stop_isolated_record_worker(
    process: Any, result_queue: Any, *, terminate: bool
) -> None:
    try:
        if terminate and process.is_alive():
            process.terminate()
        process.join(timeout=1)
        if process.is_alive():
            process.kill()
            process.join(timeout=1)
    finally:
        result_queue.close()
        result_queue.join_thread()


def _run_isolated_record_evaluation(
    records: Iterable[dict[str, Any]],
    *,
    concurrency: int,
    record_timeout_seconds: float,
    worker: Callable[..., dict[str, Any]],
    worker_kwargs: dict[str, Any],
    on_item_complete: Callable[[dict[str, Any], int, int], None] | None = None,
) -> list[dict[str, Any]]:
    """Evaluate one record per process so a hard timeout can release a worker slot."""

    if concurrency < 1:
        raise SuperSonicEvaluationError("concurrency must be at least 1")
    if record_timeout_seconds <= 0:
        raise SuperSonicEvaluationError("record_timeout_seconds must be greater than zero")

    record_list = list(records)
    context = multiprocessing.get_context("spawn")
    active: dict[int, dict[str, Any]] = {}
    items_by_index: dict[int, dict[str, Any]] = {}
    next_index = 0
    completed = 0

    def complete(index: int, item: dict[str, Any], state: dict[str, Any], *, terminate: bool) -> None:
        nonlocal completed
        _stop_isolated_record_worker(state["process"], state["resultQueue"], terminate=terminate)
        active.pop(index, None)
        items_by_index[index] = item
        completed += 1
        if on_item_complete is not None:
            on_item_complete(item, completed, len(record_list))

    try:
        while next_index < len(record_list) or active:
            while next_index < len(record_list) and len(active) < concurrency:
                record = record_list[next_index]
                result_queue = context.Queue(maxsize=1)
                process = context.Process(
                    target=_isolated_record_worker_entry,
                    args=(result_queue, record, worker, worker_kwargs),
                )
                process.start()
                active[next_index] = {
                    "process": process,
                    "resultQueue": result_queue,
                    "startedAt": time.monotonic(),
                }
                next_index += 1

            now = time.monotonic()
            for index, state in list(active.items()):
                elapsed_seconds = now - state["startedAt"]
                if elapsed_seconds >= record_timeout_seconds:
                    complete(
                        index,
                        _record_transport_failure_item(
                            record_list[index],
                            category="HTTP_RECORD_TIMEOUT",
                            elapsed_ms=round(elapsed_seconds * 1000, 3),
                        ),
                        state,
                        terminate=True,
                    )
                    continue

                try:
                    message = state["resultQueue"].get_nowait()
                except queue.Empty:
                    message = None
                if isinstance(message, dict) and isinstance(message.get("item"), dict):
                    complete(index, message["item"], state, terminate=False)
                    continue

                if not state["process"].is_alive():
                    try:
                        message = state["resultQueue"].get(timeout=0.02)
                    except queue.Empty:
                        message = None
                    if isinstance(message, dict) and isinstance(message.get("item"), dict):
                        complete(index, message["item"], state, terminate=False)
                    else:
                        complete(
                            index,
                            _record_transport_failure_item(
                                record_list[index],
                                category="HTTP_RECORD_WORKER_FAILURE",
                                elapsed_ms=round(elapsed_seconds * 1000, 3),
                            ),
                            state,
                            terminate=False,
                        )

            if active:
                next_deadline = min(
                    state["startedAt"] + record_timeout_seconds for state in active.values()
                )
                time.sleep(min(0.01, max(0.001, next_deadline - time.monotonic())))
    finally:
        for state in active.values():
            _stop_isolated_record_worker(state["process"], state["resultQueue"], terminate=True)

    return [items_by_index[index] for index in range(len(record_list))]


def _evaluate_record_with_http_transport(
    record: dict[str, Any],
    *,
    agent_id: int,
    base_url: str,
    timeout_seconds: int,
    network_retries: int,
    retry_backoff_seconds: float,
    query_api_prefix: str,
    manage_api_prefix: str,
    summary_timeout_seconds: float,
    summary_poll_interval_seconds: float,
    cleanup_conversations: bool,
) -> dict[str, Any]:
    post_json = _http_post_json(
        base_url=base_url,
        authorization_token=None,
        cookie=None,
        timeout_seconds=timeout_seconds,
        network_retries=network_retries,
        retry_backoff_seconds=retry_backoff_seconds,
    )
    return _evaluate_record(
        record,
        agent_id=agent_id,
        post_json=post_json,
        query_api_prefix=query_api_prefix,
        manage_api_prefix=manage_api_prefix,
        summary_timeout_seconds=summary_timeout_seconds,
        summary_poll_interval_seconds=summary_poll_interval_seconds,
        cleanup_conversations=cleanup_conversations,
    )


def run_supersonic_evaluation(
    records: Iterable[dict[str, Any]],
    *,
    agent_id: int,
    post_json: Callable[[str, dict[str, Any]], Any],
    query_api_prefix: str = DEFAULT_QUERY_API_PREFIX,
    manage_api_prefix: str = DEFAULT_MANAGE_API_PREFIX,
    concurrency: int = 4,
    summary_timeout_seconds: float = 120,
    summary_poll_interval_seconds: float = 0.5,
    cleanup_conversations: bool = True,
    on_item_complete: Callable[[dict[str, Any], int, int], None] | None = None,
    data_contract_pending: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Run the frontend conversation API chain concurrently and score results locally."""

    if concurrency < 1:
        raise SuperSonicEvaluationError("concurrency must be at least 1")
    query_api_prefix = "/" + query_api_prefix.strip("/")
    manage_api_prefix = "/" + manage_api_prefix.strip("/")
    record_list = list(records)
    for record in record_list:
        sample_id = record.get("id")
        question = record.get("question")
        if not isinstance(sample_id, str) or not isinstance(question, str) or not question.strip():
            raise SuperSonicEvaluationError("Every evaluation record needs non-empty id and question")

    items_by_index: dict[int, dict[str, Any]] = {}
    with ThreadPoolExecutor(max_workers=min(concurrency, max(1, len(record_list)))) as executor:
        futures = {
            executor.submit(
                _evaluate_record,
                record,
                agent_id=agent_id,
                post_json=post_json,
                query_api_prefix=query_api_prefix,
                manage_api_prefix=manage_api_prefix,
                summary_timeout_seconds=summary_timeout_seconds,
                summary_poll_interval_seconds=summary_poll_interval_seconds,
                cleanup_conversations=cleanup_conversations,
            ): index
            for index, record in enumerate(record_list)
        }
        completed = 0
        for future in as_completed(futures):
            index = futures[future]
            item = future.result()
            items_by_index[index] = item
            completed += 1
            if on_item_complete is not None:
                on_item_complete(item, completed, len(record_list))

    return _build_report(
        [items_by_index[index] for index in range(len(record_list))],
        data_contract_pending=data_contract_pending,
    )


def _http_post_json(
    *,
    base_url: str,
    authorization_token: str | None,
    cookie: str | None,
    timeout_seconds: int,
    network_retries: int,
    retry_backoff_seconds: float,
) -> Callable[[str, dict[str, Any]], Any]:
    headers = {"Content-Type": "application/json"}
    request_state = threading.local()
    if authorization_token:
        headers["Authorization"] = f"Bearer {authorization_token}"
    if cookie:
        headers["Cookie"] = cookie

    def set_retry_info(*, retry_count: int, retry_exhausted: bool) -> None:
        request_state.retry_info = {
            "retryCount": retry_count,
            "retryExhausted": retry_exhausted,
        }

    def get_last_request_retry_info() -> dict[str, int | bool]:
        value = getattr(request_state, "retry_info", None)
        if not isinstance(value, dict):
            return {"retryCount": 0, "retryExhausted": False}
        return dict(value)

    def post_json(path: str, payload: dict[str, Any]) -> Any:
        request = urllib.request.Request(
            base_url.rstrip("/") + path,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        for attempt in range(network_retries + 1):
            try:
                with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                    result = json.loads(response.read().decode("utf-8"))
                    set_retry_info(retry_count=attempt, retry_exhausted=False)
                    return result
            except urllib.error.HTTPError as error:
                retriable = error.code == 429 or error.code >= 500
                if not retriable or attempt >= network_retries:
                    set_retry_info(retry_count=attempt, retry_exhausted=retriable)
                    raise SuperSonicTransportError(
                        "SuperSonic HTTP request failed",
                        retry_count=attempt,
                        retry_exhausted=retriable,
                    ) from error
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
                if attempt >= network_retries:
                    set_retry_info(retry_count=attempt, retry_exhausted=True)
                    raise SuperSonicTransportError(
                        "SuperSonic transport request failed",
                        retry_count=attempt,
                        retry_exhausted=True,
                    ) from error
            time.sleep(retry_backoff_seconds * (2**attempt))
        raise SuperSonicEvaluationError("SuperSonic HTTP request failed")

    post_json.get_last_request_retry_info = get_last_request_retry_info  # type: ignore[attr-defined]
    return post_json


def _write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _load_resumable_items(
    path: Path,
    *,
    split: str,
    agent_id: int,
) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SuperSonicEvaluationError("Existing checkpoint is not valid JSON") from error
    run = report.get("run") if isinstance(report, dict) else None
    items = report.get("items") if isinstance(report, dict) else None
    if (
        not isinstance(run, dict)
        or run.get("split") != split
        or run.get("agentId") != agent_id
        or run.get("captureMethod") != "concurrent-openapi-frontend-chain"
        or not isinstance(items, list)
    ):
        raise SuperSonicEvaluationError(
            "Existing checkpoint does not match this split, agent, or API runner"
        )
    if not all(isinstance(item, dict) and isinstance(item.get("id"), str) for item in items):
        raise SuperSonicEvaluationError("Existing checkpoint contains invalid items")
    return items


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="Frozen bank_nl2sql directory")
    parser.add_argument("--split", choices=("train", "dev", "test"), default="dev")
    parser.add_argument(
        "--base-url",
        required=True,
        help="SuperSonic backend endpoint, for example http://127.0.0.1:9080",
    )
    parser.add_argument(
        "--query-api-prefix",
        default=DEFAULT_QUERY_API_PREFIX,
        help="Frontend query endpoint prefix",
    )
    parser.add_argument(
        "--manage-api-prefix",
        default=DEFAULT_MANAGE_API_PREFIX,
        help="Frontend conversation endpoint prefix",
    )
    parser.add_argument("--agent-id", required=True, type=int)
    parser.add_argument("--timeout-seconds", type=int, default=120)
    parser.add_argument("--summary-timeout-seconds", type=float, default=120)
    parser.add_argument("--summary-poll-interval-seconds", type=float, default=0.5)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument(
        "--record-timeout-seconds",
        type=float,
        help="Enable per-record process isolation and terminate a record that exceeds this limit",
    )
    parser.add_argument("--network-retries", type=int, default=2)
    parser.add_argument("--retry-backoff-seconds", type=float, default=0.5)
    parser.add_argument("--max-records", type=int)
    parser.add_argument("--record-id")
    parser.add_argument("--keep-conversations", action="store_true")
    parser.add_argument(
        "--resume",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Resume completed records from the output checkpoint",
    )
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--acknowledge-final-test", action="store_true")
    parser.add_argument("--run-registry", type=Path)
    parser.add_argument(
        "--data-contract-pending",
        type=Path,
        default=None,
        help="Data-contract pending policy list (default: <dataset>/data_contract_pending.json)",
    )
    args = parser.parse_args()

    if args.concurrency < 1:
        parser.error("--concurrency must be at least 1")
    if args.record_timeout_seconds is not None and args.record_timeout_seconds <= 0:
        parser.error("--record-timeout-seconds must be greater than zero")
    if args.network_retries < 0:
        parser.error("--network-retries cannot be negative")
    if args.max_records is not None and args.max_records < 1:
        parser.error("--max-records must be at least 1")
    if args.split == "test" and args.run_registry is None:
        parser.error("--split test requires --run-registry to audit the final evaluation")
    try:
        records = load_evaluation_records(
            args.dataset,
            split=args.split,
            acknowledge_final_test=args.acknowledge_final_test,
        )
    except EvaluationAccessError as error:
        parser.error(str(error))

    try:
        data_contract_pending = _load_data_contract_pending(
            args.data_contract_pending
            if args.data_contract_pending is not None
            else args.dataset / "data_contract_pending.json"
        )
    except SuperSonicEvaluationError as error:
        parser.error(str(error))

    if args.record_id is not None:
        records = [record for record in records if record.get("id") == args.record_id]
        if len(records) != 1:
            parser.error(f"--record-id did not select exactly one record: {args.record_id}")
    if args.max_records is not None:
        records = records[: args.max_records]

    try:
        resumed_items = (
            _load_resumable_items(
                args.output,
                split=args.split,
                agent_id=args.agent_id,
            )
            if args.resume
            else []
        )
    except SuperSonicEvaluationError as error:
        parser.error(str(error))
    resumed_by_id = {
        item["id"]: item for item in _resumable_checkpoint_items(resumed_items)
    }
    record_ids = {record["id"] for record in records}
    resumed_by_id = {
        sample_id: item
        for sample_id, item in resumed_by_id.items()
        if sample_id in record_ids
    }
    pending_records = [record for record in records if record["id"] not in resumed_by_id]

    started_at = time.time()
    completed_by_id = dict(resumed_by_id)

    def run_metadata(*, status: str) -> dict[str, Any]:
        return {
            "split": args.split,
            "agentId": args.agent_id,
            "baseUrl": args.base_url,
            "queryApiPrefix": args.query_api_prefix,
            "manageApiPrefix": args.manage_api_prefix,
            "captureMethod": "concurrent-openapi-frontend-chain",
            "authentication": "bypassed-openapi",
            "concurrency": args.concurrency,
            "recordTimeoutSeconds": args.record_timeout_seconds,
            "requestedCount": len(records),
            "completedCount": len(completed_by_id),
            "resumedCount": len(resumed_by_id),
            "status": status,
            "durationSeconds": round(time.time() - started_at, 3),
        }

    def ordered_completed_items() -> list[dict[str, Any]]:
        return [
            completed_by_id[record["id"]]
            for record in records
            if record["id"] in completed_by_id
        ]

    def checkpoint(item: dict[str, Any], completed: int, pending_count: int) -> None:
        completed_by_id[item["id"]] = item
        partial_report = _build_report(
            ordered_completed_items(),
            data_contract_pending=data_contract_pending,
        )
        partial_report["run"] = run_metadata(status="RUNNING")
        _write_report(args.output, partial_report)
        print(
            json.dumps(
                {
                    "completed": len(completed_by_id),
                    "total": len(records),
                    "id": item["id"],
                    "state": item["errorCategory"] or "SUCCESS",
                },
                ensure_ascii=False,
            ),
            flush=True,
        )

    if args.record_timeout_seconds is None:
        run_supersonic_evaluation(
            pending_records,
            agent_id=args.agent_id,
            query_api_prefix=args.query_api_prefix,
            manage_api_prefix=args.manage_api_prefix,
            concurrency=args.concurrency,
            summary_timeout_seconds=args.summary_timeout_seconds,
            summary_poll_interval_seconds=args.summary_poll_interval_seconds,
            cleanup_conversations=not args.keep_conversations,
            on_item_complete=checkpoint,
            data_contract_pending=data_contract_pending,
            post_json=_http_post_json(
                base_url=args.base_url,
                authorization_token=None,
                cookie=None,
                timeout_seconds=args.timeout_seconds,
                network_retries=args.network_retries,
                retry_backoff_seconds=args.retry_backoff_seconds,
            ),
        )
    else:
        _run_isolated_record_evaluation(
            pending_records,
            concurrency=args.concurrency,
            record_timeout_seconds=args.record_timeout_seconds,
            worker=_evaluate_record_with_http_transport,
            worker_kwargs={
                "agent_id": args.agent_id,
                "base_url": args.base_url,
                "timeout_seconds": args.timeout_seconds,
                "network_retries": args.network_retries,
                "retry_backoff_seconds": args.retry_backoff_seconds,
                "query_api_prefix": args.query_api_prefix,
                "manage_api_prefix": args.manage_api_prefix,
                "summary_timeout_seconds": args.summary_timeout_seconds,
                "summary_poll_interval_seconds": args.summary_poll_interval_seconds,
                "cleanup_conversations": not args.keep_conversations,
            },
            on_item_complete=checkpoint,
        )
    report = _build_report(
        ordered_completed_items(),
        data_contract_pending=data_contract_pending,
    )
    report["run"] = run_metadata(status="COMPLETED")
    if args.split == "test":
        run_entry = record_final_test_run(
            args.run_registry,
            run_metadata={
                "split": "test",
                "agentId": args.agent_id,
                "baseUrl": args.base_url,
                "timestamp": int(started_at),
                "metrics": report["metrics"],
            },
        )
        report["run"]["finalTestRunNumber"] = run_entry["runNumber"]
    _write_report(args.output, report)
    print(json.dumps({"recordCount": report["recordCount"], "metrics": report["metrics"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
