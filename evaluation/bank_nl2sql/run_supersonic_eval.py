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
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from collections.abc import Callable, Iterable
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

from evaluate_predictions import _json_value, _matches_expected
from evaluation_policy import EvaluationAccessError, load_evaluation_records, record_final_test_run


DEFAULT_QUERY_API_PREFIX = "/openapi/chat/query"
DEFAULT_MANAGE_API_PREFIX = "/openapi/chat/manage"


class SuperSonicEvaluationError(RuntimeError):
    """The runtime response did not satisfy the evaluator's API contract."""


def _unwrap_api_value(response: Any) -> Any:
    """Accept the controller's standard ``{code, data}`` envelope or raw data."""

    if not isinstance(response, dict):
        return response
    if "code" not in response:
        return response
    if str(response.get("code")) != "200":
        raise SuperSonicEvaluationError("SuperSonic API did not report success")
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


def _selected_parse(parse_response: dict[str, Any]) -> tuple[int, str | None]:
    selected = parse_response.get("selectedParses")
    if not isinstance(selected, list) or not selected or not isinstance(selected[0], dict):
        raise SuperSonicEvaluationError("Parse response did not contain selectedParses")
    parse_id = selected[0].get("id")
    if not isinstance(parse_id, int):
        raise SuperSonicEvaluationError("Selected parse did not contain an integer id")
    sql_info = selected[0].get("sqlInfo")
    s2sql = sql_info.get("correctedS2SQL") if isinstance(sql_info, dict) else None
    return parse_id, str(s2sql) if isinstance(s2sql, str) else None


def _record_group_metrics(records: list[dict[str, Any]], key: str) -> dict[str, dict[str, Any]]:
    grouped: dict[str, Counter[str]] = defaultdict(Counter)
    for item in records:
        values = item.get(key) if key == "sqlFeatures" else [item.get(key, "UNSPECIFIED")]
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
) -> tuple[str, str | None, float]:
    started = time.perf_counter()
    deadline = started + timeout_seconds
    while True:
        response = _unwrap_api_response(post_json(summary_endpoint, {"queryId": query_id}))
        if response.get("queryMode") is not None:
            summary = response.get("textSummary")
            return (
                "SUCCESS",
                str(summary) if isinstance(summary, str) else None,
                round((time.perf_counter() - started) * 1000, 3),
            )
        if time.perf_counter() >= deadline:
            return "TIMEOUT", None, round((time.perf_counter() - started) * 1000, 3)
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
        "textSummary": None,
        "errorCategory": None,
        "s2sql": None,
        "physicalSql": None,
        "chatId": None,
        "queryId": None,
        "conversationCleaned": False,
    }

    try:
        chat_id = _create_conversation(
            post_json=post_json,
            manage_api_prefix=manage_api_prefix,
            sample_id=sample_id,
            agent_id=agent_id,
        )
        item["chatId"] = chat_id
    except Exception as error:
        item["errorCategory"] = "CONVERSATION_ERROR"
        item["errorType"] = type(error).__name__
        return item

    query_started = time.perf_counter()

    def finish_query_timing() -> dict[str, Any]:
        item["endToEndMs"] = round((time.perf_counter() - query_started) * 1000, 3)
        return item

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
        parse_id, s2sql = _selected_parse(parse_response)
        query_id = parse_response.get("queryId")
        if not isinstance(query_id, int):
            raise SuperSonicEvaluationError("Parse response did not contain an integer queryId")
        item["parse"] = True
        item["s2sql"] = s2sql
        item["queryId"] = query_id
    except Exception as error:
        item["errorCategory"] = "PARSE_ERROR"
        item["errorType"] = type(error).__name__
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
        backend_error = execute_response.get("errorMsg")
        if isinstance(backend_error, str) and backend_error.strip():
            item["backendError"] = backend_error.strip()
            raise SuperSonicEvaluationError(
                "SuperSonic execution returned an error message"
            )
        if str(execute_response.get("queryState", "")).upper() != "SUCCESS":
            raise SuperSonicEvaluationError("SuperSonic execution did not report SUCCESS")
        columns, rows = _rows_from_response(execute_response)
        item["execute"] = True
        item["physicalSql"] = (
            execute_response.get("querySql")
            if isinstance(execute_response.get("querySql"), str)
            else None
        )
        item["match"] = _matches_expected(record.get("expected", {}), columns, rows)
        item["errorCategory"] = None if item["match"] else "RESULT_MISMATCH"
    except Exception as error:
        item["errorCategory"] = "EXECUTION_ERROR"
        item["errorType"] = type(error).__name__
        return finish_query_timing()

    try:
        summary_state, text_summary, summary_ms = _poll_execute_summary(
            post_json=post_json,
            summary_endpoint=f"{query_api_prefix}/getExecuteSummary",
            query_id=query_id,
            timeout_seconds=summary_timeout_seconds,
            poll_interval_seconds=summary_poll_interval_seconds,
        )
        item["summaryState"] = summary_state
        item["textSummary"] = text_summary
        item["summaryMs"] = summary_ms
    except Exception as error:
        item["summaryState"] = "ERROR"
        item["summaryErrorType"] = type(error).__name__

    finish_query_timing()
    if cleanup_conversations and item["match"]:
        try:
            cleaned = _unwrap_api_value(
                post_json(f"{manage_api_prefix}/delete?chatId={chat_id}", {})
            )
            item["conversationCleaned"] = cleaned is not False
        except Exception as error:
            item["cleanupErrorType"] = type(error).__name__
    return item


def _build_report(items: list[dict[str, Any]]) -> dict[str, Any]:
    error_categories: Counter[str] = Counter(
        item["errorCategory"] or "NONE" for item in items
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
    return {
        "recordCount": count,
        "metrics": {
            "parseSuccessRate": _rate(parsed, count),
            "executionSuccessRate": _rate(executed, count),
            "resultAccuracy": _rate(matched, count),
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
        "errorCategories": dict(sorted(error_categories.items())),
        "items": items,
    }


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

    return _build_report([items_by_index[index] for index in range(len(record_list))])


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
    if authorization_token:
        headers["Authorization"] = f"Bearer {authorization_token}"
    if cookie:
        headers["Cookie"] = cookie

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
                    return json.loads(response.read().decode("utf-8"))
            except urllib.error.HTTPError as error:
                retriable = error.code == 429 or error.code >= 500
                if not retriable or attempt >= network_retries:
                    raise SuperSonicEvaluationError(
                        f"SuperSonic HTTP request failed with status {error.code}"
                    ) from error
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
                if attempt >= network_retries:
                    raise SuperSonicEvaluationError("SuperSonic HTTP request failed") from error
            time.sleep(retry_backoff_seconds * (2**attempt))
        raise SuperSonicEvaluationError("SuperSonic HTTP request failed")

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
    args = parser.parse_args()

    if args.concurrency < 1:
        parser.error("--concurrency must be at least 1")
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
    resumed_by_id = {item["id"]: item for item in resumed_items}
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
        partial_report = _build_report(ordered_completed_items())
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
        post_json=_http_post_json(
            base_url=args.base_url,
            authorization_token=None,
            cookie=None,
            timeout_seconds=args.timeout_seconds,
            network_retries=args.network_retries,
            retry_backoff_seconds=args.retry_backoff_seconds,
        ),
    )
    report = _build_report(ordered_completed_items())
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
