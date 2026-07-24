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
import os
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any

from evaluate_predictions import _json_value, _matches_expected
from evaluation_policy import EvaluationAccessError, load_evaluation_records, record_final_test_run


DEFAULT_QUERY_API_PREFIX = "/api/chat/query"


class SuperSonicEvaluationError(RuntimeError):
    """The runtime response did not satisfy the evaluator's API contract."""


def _unwrap_api_response(response: Any) -> dict[str, Any]:
    """Accept the controller's standard ``{code, data}`` envelope or raw data."""

    if not isinstance(response, dict):
        raise SuperSonicEvaluationError("SuperSonic response is not an object")
    if "code" not in response:
        return response
    if str(response.get("code")) != "200":
        raise SuperSonicEvaluationError("SuperSonic API did not report success")
    data = response.get("data")
    if not isinstance(data, dict):
        raise SuperSonicEvaluationError("Successful SuperSonic API response did not contain object data")
    return data


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


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


def run_supersonic_evaluation(
    records: Iterable[dict[str, Any]],
    *,
    agent_id: int,
    post_json: Callable[[str, dict[str, Any]], dict[str, Any]],
    chat_id: int = 0,
    query_api_prefix: str = DEFAULT_QUERY_API_PREFIX,
) -> dict[str, Any]:
    """Run parse then execute for every record and score returned results locally."""

    query_api_prefix = "/" + query_api_prefix.strip("/")
    parse_endpoint = f"{query_api_prefix}/parse"
    execute_endpoint = f"{query_api_prefix}/execute"
    items: list[dict[str, Any]] = []
    error_categories: Counter[str] = Counter()
    parse_latencies: list[float] = []
    execute_latencies: list[float] = []
    for record in records:
        sample_id = record.get("id")
        question = record.get("question")
        if not isinstance(sample_id, str) or not isinstance(question, str) or not question.strip():
            raise SuperSonicEvaluationError("Every evaluation record needs non-empty id and question")
        item = {
            "id": sample_id,
            "difficulty": str(record.get("difficulty", "UNSPECIFIED")),
            "sqlFeatures": list(record.get("sqlFeatures") or ["UNSPECIFIED"]),
            "parse": False,
            "execute": False,
            "match": False,
            "parseMs": None,
            "executeMs": None,
            "errorCategory": None,
            "s2sql": None,
            "physicalSql": None,
        }
        parse_payload = {
            "queryText": question.strip(),
            "agentId": agent_id,
            "chatId": chat_id,
            "saveAnswer": False,
        }
        try:
            started = time.perf_counter()
            parse_response = _unwrap_api_response(post_json(parse_endpoint, parse_payload))
            item["parseMs"] = round((time.perf_counter() - started) * 1000, 3)
            parse_latencies.append(item["parseMs"])
            parse_id, s2sql = _selected_parse(parse_response)
            query_id = parse_response.get("queryId")
            if not isinstance(query_id, int):
                raise SuperSonicEvaluationError("Parse response did not contain an integer queryId")
            item["parse"] = True
            item["s2sql"] = s2sql
        except Exception as error:
            item["errorCategory"] = "PARSE_ERROR"
            item["errorType"] = type(error).__name__
            error_categories[item["errorCategory"]] += 1
            items.append(item)
            continue

        execute_payload = {
            "queryId": query_id,
            "parseId": parse_id,
            "queryText": question.strip(),
            "agentId": agent_id,
            "chatId": chat_id,
            "saveAnswer": False,
            "streamingResult": False,
        }
        try:
            started = time.perf_counter()
            execute_response = _unwrap_api_response(post_json(execute_endpoint, execute_payload))
            item["executeMs"] = round((time.perf_counter() - started) * 1000, 3)
            execute_latencies.append(item["executeMs"])
            if str(execute_response.get("queryState", "")).upper() != "SUCCESS":
                raise SuperSonicEvaluationError("SuperSonic execution did not report SUCCESS")
            columns, rows = _rows_from_response(execute_response)
            item["execute"] = True
            item["physicalSql"] = execute_response.get("querySql") if isinstance(execute_response.get("querySql"), str) else None
            item["match"] = _matches_expected(record.get("expected", {}), columns, rows)
            item["errorCategory"] = None if item["match"] else "RESULT_MISMATCH"
        except Exception as error:
            item["errorCategory"] = "EXECUTION_ERROR"
            item["errorType"] = type(error).__name__
        error_categories[item["errorCategory"] or "NONE"] += 1
        items.append(item)

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
        },
        "byDifficulty": _record_group_metrics(items, "difficulty"),
        "bySqlFeature": _record_group_metrics(items, "sqlFeatures"),
        "errorCategories": dict(sorted(error_categories.items())),
        "items": items,
    }


def _http_post_json(
    *,
    base_url: str,
    authorization_token: str | None,
    cookie: str | None,
    timeout_seconds: int,
) -> Callable[[str, dict[str, Any]], dict[str, Any]]:
    headers = {"Content-Type": "application/json"}
    if authorization_token:
        headers["Authorization"] = f"Bearer {authorization_token}"
    if cookie:
        headers["Cookie"] = cookie

    def post_json(path: str, payload: dict[str, Any]) -> dict[str, Any]:
        request = urllib.request.Request(
            base_url.rstrip("/") + path,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                body = json.loads(response.read().decode("utf-8"))
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            raise SuperSonicEvaluationError("SuperSonic HTTP request failed") from error
        if not isinstance(body, dict):
            raise SuperSonicEvaluationError("SuperSonic response is not a JSON object")
        return body

    return post_json


def _write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="Frozen bank_nl2sql directory")
    parser.add_argument("--split", choices=("train", "dev", "test"), default="dev")
    parser.add_argument("--base-url", required=True, help="SuperSonic web endpoint, for example http://127.0.0.1:9000")
    parser.add_argument(
        "--query-api-prefix",
        default=DEFAULT_QUERY_API_PREFIX,
        help="Parse/execute endpoint prefix, for example /openapi/chat/query",
    )
    parser.add_argument("--agent-id", required=True, type=int)
    parser.add_argument("--chat-id", type=int, default=0)
    parser.add_argument("--authorization-token", default=os.getenv("SUPSERSONIC_AUTH_TOKEN"))
    parser.add_argument("--cookie", default=os.getenv("SUPSERSONIC_COOKIE"))
    parser.add_argument("--timeout-seconds", type=int, default=120)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--acknowledge-final-test", action="store_true")
    parser.add_argument("--run-registry", type=Path)
    args = parser.parse_args()

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

    started_at = time.time()
    report = run_supersonic_evaluation(
        records,
        agent_id=args.agent_id,
        chat_id=args.chat_id,
        query_api_prefix=args.query_api_prefix,
        post_json=_http_post_json(
            base_url=args.base_url,
            authorization_token=args.authorization_token,
            cookie=args.cookie,
            timeout_seconds=args.timeout_seconds,
        ),
    )
    report["run"] = {
        "split": args.split,
        "agentId": args.agent_id,
        "chatId": args.chat_id,
        "baseUrl": args.base_url,
        "queryApiPrefix": args.query_api_prefix,
        "durationSeconds": round(time.time() - started_at, 3),
    }
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
