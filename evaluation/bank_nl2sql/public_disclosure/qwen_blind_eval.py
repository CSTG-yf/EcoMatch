#!/usr/bin/env python3
"""Run an isolated, blind Qwen NL2SQL evaluation for public disclosure facts.

The model receives only a question plus schema and catalog metadata. Expected rows,
reference SQL, fact values, and source locations remain local to the evaluator.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sqlite3
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any

try:
    from .validate_public_facts import validate_release
except ImportError:
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
    from evaluation.bank_nl2sql.public_disclosure.validate_public_facts import validate_release  # type: ignore


ALLOWED_TABLES = {
    "public_organization",
    "public_metric_definition",
    "public_metric_fact",
}

SYSTEM_PROMPT = """你是银行公开披露数据查询 Agent。请把用户问题转换成一条 SQLite 只读 SQL。
只能返回一条 SELECT 或 WITH 查询，不要 Markdown、解释、DDL、DML、PRAGMA 或多条语句。
只能使用下面给出的表、机构、指标和日期；不得编造指标代码、机构代码、字段或数值。
金额单位已经统一为万元，比例单位为 %。需要指标名称或单位时可以连接指标定义表。

{schema}
"""


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _catalog_by_code(catalog_dir: Path) -> dict[str, dict[str, Any]]:
    return {item["code"]: item for item in _read_jsonl(catalog_dir / "metrics.jsonl")}


def build_sqlite_connection(release_dir: Path, *, catalog_dir: Path) -> sqlite3.Connection:
    """Build the isolated public disclosure SQLite store used by the blind Agent."""

    release_dir = release_dir.resolve()
    catalog_dir = catalog_dir.resolve()
    validate_release(release_dir, catalog_dir=catalog_dir)
    facts = _read_jsonl(release_dir / "facts.jsonl")
    catalog = _catalog_by_code(catalog_dir)
    connection = sqlite3.connect(":memory:")
    connection.executescript(
        """
        CREATE TABLE public_organization (
            org_code TEXT PRIMARY KEY,
            org_name TEXT NOT NULL,
            data_origin TEXT NOT NULL CHECK (data_origin = 'PUBLIC_DISCLOSURE')
        );
        CREATE TABLE public_metric_definition (
            metric_code TEXT PRIMARY KEY,
            metric_name TEXT NOT NULL,
            unit TEXT NOT NULL,
            aggregation TEXT NOT NULL,
            catalog_status TEXT NOT NULL CHECK (catalog_status = 'CANDIDATE')
        );
        CREATE TABLE public_metric_fact (
            data_date TEXT NOT NULL,
            org_code TEXT NOT NULL,
            metric_code TEXT NOT NULL,
            metric_value REAL NOT NULL,
            unit TEXT NOT NULL,
            source_id TEXT NOT NULL,
            source_page INTEGER NOT NULL,
            source_locator TEXT NOT NULL,
            source_value REAL NOT NULL,
            source_unit TEXT NOT NULL,
            conversion TEXT NOT NULL,
            data_origin TEXT NOT NULL CHECK (data_origin = 'PUBLIC_DISCLOSURE'),
            mapping_status TEXT NOT NULL,
            PRIMARY KEY (data_date, org_code, metric_code)
        );
        """
    )
    organizations = sorted({(fact["orgCode"], fact["orgName"]) for fact in facts})
    connection.executemany(
        "INSERT INTO public_organization VALUES (?, ?, 'PUBLIC_DISCLOSURE')", organizations
    )
    metric_codes = sorted({fact["metricCode"] for fact in facts})
    connection.executemany(
        "INSERT INTO public_metric_definition VALUES (?, ?, ?, ?, 'CANDIDATE')",
        [
            (code, catalog[code]["name"], catalog[code]["unit"], catalog[code]["aggregation"])
            for code in metric_codes
        ],
    )
    connection.executemany(
        "INSERT INTO public_metric_fact VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (
                fact["dataDate"],
                fact["orgCode"],
                fact["metricCode"],
                fact["metricValue"],
                fact["unit"],
                fact["sourceId"],
                fact["sourcePage"],
                fact["sourceLocator"],
                fact["sourceValue"],
                fact["sourceUnit"],
                fact["conversion"],
                fact["dataOrigin"],
                fact["mappingStatus"],
            )
            for fact in facts
        ],
    )
    return connection


def build_blind_prompts(queries: Iterable[dict[str, Any]]) -> list[dict[str, str]]:
    """Strip every gold field before a question can reach the model."""

    prompts: list[dict[str, str]] = []
    seen_ids: set[str] = set()
    for query in queries:
        query_id = query.get("id")
        question = query.get("question")
        if not isinstance(query_id, str) or not query_id or query_id in seen_ids:
            raise ValueError("blind query ids must be unique non-empty strings")
        if not isinstance(question, str) or not question.strip():
            raise ValueError(f"blind query {query_id} has an empty question")
        seen_ids.add(query_id)
        prompts.append({"id": query_id, "question": question.strip()})
    return prompts


def schema_context(connection: sqlite3.Connection) -> str:
    """Return schema and metadata without exposing source fact values to the model."""

    table_lines = []
    for table in sorted(ALLOWED_TABLES):
        columns = [str(row[1]) for row in connection.execute(f"PRAGMA table_info({table})")]
        table_lines.append(f"- {table}({', '.join(columns)})")
    organizations = [
        {"orgCode": row[0], "orgName": row[1]}
        for row in connection.execute("SELECT org_code, org_name FROM public_organization ORDER BY org_code")
    ]
    metrics = [
        {"metricCode": row[0], "metricName": row[1], "unit": row[2], "aggregation": row[3]}
        for row in connection.execute(
            "SELECT metric_code, metric_name, unit, aggregation FROM public_metric_definition ORDER BY metric_code"
        )
    ]
    dates = [row[0] for row in connection.execute("SELECT DISTINCT data_date FROM public_metric_fact ORDER BY data_date")]
    return "\n".join(
        [
            "可用表：",
            *table_lines,
            "可用机构：" + json.dumps(organizations, ensure_ascii=False, separators=(",", ":")),
            "可用指标：" + json.dumps(metrics, ensure_ascii=False, separators=(",", ":")),
            "可用日期：" + json.dumps(dates, ensure_ascii=False),
        ]
    )


def extract_sql(content: Any) -> str | None:
    if not isinstance(content, str):
        return None
    response = content.strip()
    fenced = re.fullmatch(r"```(?:sql|sqlite)?\s*(.*?)\s*```", response, flags=re.IGNORECASE | re.DOTALL)
    candidate = fenced.group(1).strip() if fenced else response
    match = re.search(r"\b(?:SELECT|WITH)\b.*", candidate, flags=re.IGNORECASE | re.DOTALL)
    return match.group(0).strip() if match else None


def _normalize_sql(sql: str) -> str | None:
    compact = sql.strip()
    if compact.endswith(";"):
        compact = compact[:-1].strip()
    if not compact or ";" in compact or not re.match(r"^(SELECT|WITH)\b", compact, flags=re.IGNORECASE):
        return None
    return compact


def _read_only_authorizer(action: int, parameter_1: str | None, _parameter_2: str | None, _database: str | None, _source: str | None) -> int:
    if action in {sqlite3.SQLITE_SELECT, sqlite3.SQLITE_FUNCTION}:
        return sqlite3.SQLITE_OK
    if action == sqlite3.SQLITE_READ:
        return sqlite3.SQLITE_OK if parameter_1 in ALLOWED_TABLES else sqlite3.SQLITE_DENY
    return sqlite3.SQLITE_DENY


def _same_value(expected: Any, actual: Any) -> bool:
    if isinstance(expected, (int, float)) and not isinstance(expected, bool) and isinstance(actual, (int, float)) and not isinstance(actual, bool):
        return math.isclose(float(expected), float(actual), rel_tol=0, abs_tol=1e-9)
    return expected == actual


def _matches(expected: dict[str, Any], columns: list[str], rows: list[list[Any]]) -> bool:
    expected_rows = expected.get("rows", [])
    if expected.get("columns") != columns or len(expected_rows) != len(rows):
        return False
    return all(
        len(left) == len(right) and all(_same_value(a, b) for a, b in zip(left, right))
        for left, right in zip(expected_rows, rows)
    )


def _referenced_metric_codes(sql: str) -> set[str]:
    return {code.upper() for code in re.findall(r"\bCNB\d{3}\b", sql, flags=re.IGNORECASE)}


def _numeric_cells(rows: Iterable[Iterable[Any]]) -> list[float]:
    return [
        float(cell)
        for row in rows
        for cell in row
        if isinstance(cell, (int, float)) and not isinstance(cell, bool)
    ]


def _same_numeric_multiset(expected: Iterable[Any], actual: Iterable[float]) -> bool:
    remaining = [float(value) for value in actual]
    expected_values = [float(value) for value in expected]
    if len(expected_values) != len(remaining):
        return False
    for expected_value in expected_values:
        for index, actual_value in enumerate(remaining):
            if math.isclose(expected_value, actual_value, rel_tol=0, abs_tol=1e-9):
                remaining.pop(index)
                break
        else:
            return False
    return not remaining


def _semantic_fact_match(query: dict[str, Any], generated_sql: str, rows: list[list[Any]]) -> bool:
    """Accept equivalent wide or long result layouts while keeping fact values strict."""

    expected_codes = {str(code).upper() for code in query["metricCodes"]}
    expected_values = [row[1] for row in query["expected"]["rows"]]
    return _referenced_metric_codes(generated_sql) == expected_codes and _same_numeric_multiset(
        expected_values, _numeric_cells(rows)
    )


def evaluate_prediction(connection: sqlite3.Connection, query: dict[str, Any], generated_sql: str | None) -> dict[str, Any]:
    """Execute a generated read-only SQL query and independently score its result."""

    outcome: dict[str, Any] = {
        "id": query["id"],
        "parseSuccess": False,
        "executionSuccess": False,
        "structuralResultCorrect": False,
        "resultCorrect": False,
        "sourceTraceable": False,
    }
    normalized_sql = _normalize_sql(generated_sql or "")
    if normalized_sql is None:
        outcome["error"] = "generated SQL is not one read-only SELECT or WITH statement"
        return outcome
    outcome["parseSuccess"] = True
    connection.set_authorizer(_read_only_authorizer)
    try:
        cursor = connection.execute(normalized_sql)
        columns = [description[0] for description in cursor.description or []]
        rows = [list(row) for row in cursor.fetchall()]
    except sqlite3.DatabaseError as error:
        outcome["error"] = f"SQLiteError: {error}"
        return outcome
    finally:
        connection.set_authorizer(None)
    outcome["executionSuccess"] = True
    outcome["structuralResultCorrect"] = _matches(query["expected"], columns, rows)
    outcome["resultCorrect"] = outcome["structuralResultCorrect"] or _semantic_fact_match(
        query, normalized_sql, rows
    )
    referenced_codes = _referenced_metric_codes(normalized_sql)
    expected_codes = {str(code).upper() for code in query["metricCodes"]}
    traceable_codes = {
        row[0]
        for row in connection.execute(
            "SELECT metric_code FROM public_metric_fact WHERE source_id <> '' AND source_locator <> ''"
        )
    }
    outcome["sourceTraceable"] = (
        outcome["resultCorrect"]
        and referenced_codes == expected_codes
        and referenced_codes <= traceable_codes
    )
    return outcome


def rescore_saved_details(
    connection: sqlite3.Connection,
    queries_by_id: dict[str, dict[str, Any]],
    saved_details: Iterable[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Re-evaluate saved model SQL locally without sending another model request."""

    rescored: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for saved in saved_details:
        query_id = saved.get("id")
        if not isinstance(query_id, str) or query_id not in queries_by_id or query_id in seen_ids:
            raise ValueError("saved details must contain each known query id exactly once")
        generated_sql = saved.get("generatedSql")
        if not isinstance(generated_sql, str):
            raise ValueError(f"saved detail {query_id} has no generated SQL")
        result = evaluate_prediction(connection, queries_by_id[query_id], generated_sql)
        result["generatedSql"] = generated_sql
        for key in ("latencyMs", "modelError"):
            if key in saved:
                result[key] = saved[key]
        rescored.append(result)
        seen_ids.add(query_id)
    if set(queries_by_id) != seen_ids:
        raise ValueError("saved details do not cover the complete query set")
    return rescored


def _openai_completion(*, base_url: str, model: str, api_key: str, timeout_seconds: int, retries: int) -> Callable[[list[dict[str, str]]], str]:
    endpoint = base_url.rstrip("/") + "/chat/completions"

    def complete(messages: list[dict[str, str]]) -> str:
        payload = json.dumps(
            {"model": model, "messages": messages, "temperature": 0, "stream": False},
            ensure_ascii=False,
        ).encode("utf-8")
        last_error: Exception | None = None
        for attempt in range(retries + 1):
            request = urllib.request.Request(
                endpoint,
                data=payload,
                headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                    body = json.loads(response.read().decode("utf-8"))
                content = body["choices"][0]["message"]["content"]
                if not isinstance(content, str):
                    raise ValueError("chat completion did not contain text content")
                return content
            except (KeyError, TypeError, ValueError, json.JSONDecodeError, urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
                last_error = error
                if attempt < retries:
                    time.sleep(min(2**attempt, 8))
        raise RuntimeError(f"model request failed after {retries + 1} attempt(s): {last_error}")

    return complete


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    parser.add_argument("--catalog-dir", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--api-key", default=os.environ.get("ECOMATCH_MODEL_API_KEY"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=90)
    parser.add_argument("--retries", type=int, default=1)
    args = parser.parse_args()
    if not args.api_key:
        raise SystemExit("Missing API key: set ECOMATCH_MODEL_API_KEY or pass --api-key.")

    release_dir = args.release_dir.resolve()
    catalog_dir = args.catalog_dir.resolve()
    queries = _read_jsonl(release_dir / "queries.jsonl")
    prompts = build_blind_prompts(queries)
    completion = _openai_completion(
        base_url=args.base_url,
        model=args.model,
        api_key=args.api_key,
        timeout_seconds=args.timeout_seconds,
        retries=args.retries,
    )
    started = time.perf_counter()
    details: list[dict[str, Any]] = []
    connection = build_sqlite_connection(release_dir, catalog_dir=catalog_dir)
    try:
        context = schema_context(connection)
        query_by_id = {query["id"]: query for query in queries}
        for prompt in prompts:
            request_started = time.perf_counter()
            model_sql = ""
            model_error: str | None = None
            try:
                content = completion(
                    [
                        {"role": "system", "content": SYSTEM_PROMPT.format(schema=context)},
                        {"role": "user", "content": prompt["question"]},
                    ]
                )
                model_sql = extract_sql(content) or ""
            except Exception as error:  # A request error is recorded rather than hidden by a fallback SQL.
                model_error = f"{type(error).__name__}: {error}"
            result = evaluate_prediction(connection, query_by_id[prompt["id"]], model_sql)
            result["generatedSql"] = model_sql
            result["latencyMs"] = round((time.perf_counter() - request_started) * 1000)
            if model_error:
                result["modelError"] = model_error
            details.append(result)
            print(json.dumps({"completed": len(details), "total": len(prompts), "id": prompt["id"]}, ensure_ascii=False), flush=True)
    finally:
        connection.close()

    report = {
        "status": "VALID" if all(item["resultCorrect"] and item["sourceTraceable"] for item in details) else "INVALID",
        "evaluationMode": "QWEN_PUBLIC_DISCLOSURE_BLIND_NL2SQL",
        "dataOrigin": "PUBLIC_DISCLOSURE",
        "model": args.model,
        "queryCount": len(details),
        "parseSuccessCount": sum(item["parseSuccess"] for item in details),
        "executionSuccessCount": sum(item["executionSuccess"] for item in details),
        "structuralResultCorrectCount": sum(item["structuralResultCorrect"] for item in details),
        "resultCorrectCount": sum(item["resultCorrect"] for item in details),
        "sourceTraceableCount": sum(item["sourceTraceable"] for item in details),
        "requestErrorCount": sum("modelError" in item for item in details),
        "meanLatencyMs": round(sum(item["latencyMs"] for item in details) / len(details), 3) if details else None,
        "schemaContextSha256": hashlib.sha256(context.encode("utf-8")).hexdigest(),
        "durationSeconds": round(time.perf_counter() - started, 3),
        "blindInputContract": "model receives only question, public schema, organization metadata, metric metadata, and dates; gold SQL, expected rows, fact values, and source locations remain local",
        "warning": "This is an isolated public-disclosure Qwen blind evaluation, not an official 21-metric score or production-bank acceptance.",
        "details": details,
    }
    _write_json(args.output, report)
    print(json.dumps({key: report[key] for key in ("status", "queryCount", "parseSuccessCount", "executionSuccessCount", "resultCorrectCount", "sourceTraceableCount", "requestErrorCount")}, ensure_ascii=False))
    return 0 if report["status"] == "VALID" else 1


if __name__ == "__main__":
    raise SystemExit(main())
