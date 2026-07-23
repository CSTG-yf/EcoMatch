#!/usr/bin/env python3
"""Generate held-out SQL predictions with an OpenAI-compatible model.

The model receives only each held-out sample's ``id`` and ``question`` plus
database schema metadata. Gold SQL and expected rows remain inside
``evaluate_predictions.py`` and are never included in a model request.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any


SYSTEM_PROMPT = """You generate one SQLite-compatible SQL query for each banking question.
Return only one read-only SELECT or WITH query: no Markdown, explanation, DDL, DML, PRAGMA, or multiple statements.
Use only the schema and metadata below. Join the organization or metric-definition table when a requested output needs names or units.
Dates must use YYYY-MM-DD text literals. Do not invent columns, organizations, metrics, or values.

Schema and allowed metadata:
{schema_context}
"""


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def build_prompt_records(records: Iterable[dict[str, Any]]) -> list[dict[str, str]]:
    """Keep only the fields the model is allowed to receive during blind evaluation."""

    prompts: list[dict[str, str]] = []
    seen_ids: set[str] = set()
    for record in records:
        sample_id = record.get("id")
        question = record.get("question")
        if not isinstance(sample_id, str) or not sample_id:
            raise ValueError("Each blind-evaluation sample needs a non-empty id")
        if not isinstance(question, str) or not question.strip():
            raise ValueError(f"Blind-evaluation sample {sample_id} needs a question")
        if sample_id in seen_ids:
            raise ValueError(f"Duplicate blind-evaluation sample id: {sample_id}")
        seen_ids.add(sample_id)
        prompts.append({"id": sample_id, "question": question.strip()})
    return prompts


def extract_sql(content: Any) -> str | None:
    """Extract one query from a model response without trying to repair it."""

    if not isinstance(content, str):
        return None
    response = content.strip()
    fenced = re.fullmatch(r"```(?:sql|sqlite)?\s*(.*?)\s*```", response, flags=re.IGNORECASE | re.DOTALL)
    candidate = fenced.group(1).strip() if fenced else response
    match = re.search(r"\b(?:SELECT|WITH)\b.*", candidate, flags=re.IGNORECASE | re.DOTALL)
    if not match:
        return None
    return match.group(0).strip()


def _schema_context(database_path: Path) -> str:
    with sqlite3.connect(database_path) as connection:
        tables = [
            str(row[0])
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'bank_%' ORDER BY name"
            )
        ]
        table_lines = []
        for table in tables:
            columns = [str(row[1]) for row in connection.execute(f"PRAGMA table_info({table})")]
            table_lines.append(f"{table}({', '.join(columns)})")

        organization_cursor = connection.execute("SELECT * FROM bank_organization ORDER BY 1")
        organization_columns = [column[0] for column in organization_cursor.description]
        organizations = [dict(zip(organization_columns, row)) for row in organization_cursor.fetchall()]

        metric_cursor = connection.execute("SELECT * FROM bank_metric_definition ORDER BY 1")
        metric_columns = [column[0] for column in metric_cursor.description]
        metrics = [dict(zip(metric_columns, row)) for row in metric_cursor.fetchall()]

    return "\n".join(
        [
            "Tables:",
            *[f"- {line}" for line in table_lines],
            "Organizations:",
            json.dumps(organizations, ensure_ascii=False, separators=(",", ":")),
            "Metric definitions:",
            json.dumps(metrics, ensure_ascii=False, separators=(",", ":")),
        ]
    )


def _messages(schema_context: str, question: str) -> list[dict[str, str]]:
    return [
        {"role": "system", "content": SYSTEM_PROMPT.format(schema_context=schema_context)},
        {"role": "user", "content": question},
    ]


def generate_predictions(
    prompt_records: Iterable[dict[str, str]],
    *,
    schema_context: str,
    completion: Callable[[list[dict[str, str]]], str],
    on_prediction: Callable[[list[dict[str, Any]]], None] | None = None,
) -> list[dict[str, Any]]:
    """Generate predictions while retaining no source fields beyond id/question."""

    predictions: list[dict[str, Any]] = []
    for record in prompt_records:
        prediction: dict[str, Any] = {"id": record["id"], "sql": ""}
        try:
            prediction["sql"] = extract_sql(completion(_messages(schema_context, record["question"]))) or ""
        except Exception as error:  # The scorer classifies this as a missing/invalid SQL prediction.
            prediction["error"] = f"{type(error).__name__}: {error}"
        predictions.append(prediction)
        if on_prediction:
            on_prediction(predictions)
    return predictions


def _openai_completion(
    *, base_url: str, model: str, api_key: str, temperature: float, timeout_seconds: int, max_retries: int
) -> Callable[[list[dict[str, str]]], str]:
    endpoint = base_url.rstrip("/") + "/chat/completions"

    def complete(messages: list[dict[str, str]]) -> str:
        payload = json.dumps(
            {"model": model, "messages": messages, "temperature": temperature, "stream": False},
            ensure_ascii=False,
        ).encode("utf-8")
        last_error: Exception | None = None
        for attempt in range(max_retries + 1):
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
                    raise ValueError("Chat completion did not contain text content")
                return content
            except (KeyError, ValueError, urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
                last_error = error
                if attempt == max_retries:
                    break
                time.sleep(min(2**attempt, 8))
        raise RuntimeError(f"Model request failed after {max_retries + 1} attempt(s): {last_error}")

    return complete


def _write_jsonl(path: Path, records: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n" for record in records),
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="Frozen bank_nl2sql directory")
    parser.add_argument("database", type=Path, help="SQLite benchmark database used only for schema metadata")
    parser.add_argument("--base-url", required=True, help="OpenAI-compatible base URL, including /v1")
    parser.add_argument("--model", required=True, help="Model identifier accepted by the endpoint")
    parser.add_argument("--api-key", default="local-no-key")
    parser.add_argument("--output", required=True, type=Path, help="Prediction JSONL output path")
    parser.add_argument("--metadata-output", type=Path, help="Optional run metadata JSON path")
    parser.add_argument("--temperature", type=float, default=0.0)
    parser.add_argument("--timeout-seconds", type=int, default=90)
    parser.add_argument("--max-retries", type=int, default=2)
    args = parser.parse_args()

    dataset_path = args.dataset.resolve()
    database_path = args.database.resolve()
    prompt_records = build_prompt_records(_read_jsonl(dataset_path / "test.jsonl"))
    schema_context = _schema_context(database_path)
    completion = _openai_completion(
        base_url=args.base_url,
        model=args.model,
        api_key=args.api_key,
        temperature=args.temperature,
        timeout_seconds=args.timeout_seconds,
        max_retries=args.max_retries,
    )
    started_at = time.time()

    def checkpoint(predictions: list[dict[str, Any]]) -> None:
        _write_jsonl(args.output, predictions)
        print(
            json.dumps(
                {"completed": len(predictions), "total": len(prompt_records), "lastId": predictions[-1]["id"]},
                ensure_ascii=False,
            ),
            flush=True,
        )

    predictions = generate_predictions(
        prompt_records, schema_context=schema_context, completion=completion, on_prediction=checkpoint
    )
    if args.metadata_output:
        metadata = {
            "model": args.model,
            "baseUrl": args.base_url,
            "temperature": args.temperature,
            "testCount": len(prompt_records),
            "predictionCount": len(predictions),
            "modelErrorCount": sum("error" in prediction for prediction in predictions),
            "schemaContextSha256": hashlib.sha256(schema_context.encode("utf-8")).hexdigest(),
            "durationSeconds": round(time.time() - started_at, 3),
        }
        args.metadata_output.parent.mkdir(parents=True, exist_ok=True)
        args.metadata_output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
