#!/usr/bin/env python3
"""Run blind metric-recognition QA against an OpenAI-compatible chat model.

This runner is deliberately separate from the official 21-metric NL2SQL
evaluation. It never reads or sends QA gold fields and writes only opaque
prediction records plus endpoint fingerprints in optional run metadata.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Iterable
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any


SYSTEM_PROMPT = """你是银行指标知识库路由器。根据用户问题和候选指标识别最匹配的一个业务指标，判断动作，并返回一个 JSON 对象。
只能返回以下字段，不要输出 Markdown、解释或额外字段：
metricCode, action, metricName, matchedText, scene, domain, unit, aggregation, definition。
action 只能是 ROUTE_TO_DATA_QUERY 或 EXPLAIN_METRIC；无法确定时仍返回最可能的指标，但不要编造事实数值。
metricCode 必须来自下面的候选指标；其余元数据必须复制候选指标中的对应值。matchedText 必须逐字复制用户问题中实际出现的指标名称或别名。只有 EXPLAIN_METRIC 返回候选定义，ROUTE_TO_DATA_QUERY 的 definition 返回 null。

候选指标（它们来自全局候选目录，不是该题金标）：
{candidate_context}
"""

REQUIRED_OUTPUT_FIELDS = {
    "id",
    "metricCode",
    "action",
    "metricName",
    "matchedText",
    "scene",
    "domain",
    "unit",
    "aggregation",
    "definition",
}
OUTPUT_FIELDS = REQUIRED_OUTPUT_FIELDS - {"id"}
FORBIDDEN_INPUT_FIELDS = {
    "expected",
    "metricCode",
    "definition",
    "split",
    "goldSql",
    "answerText",
    "value",
    "rows",
}


def load_blind_records(path: Path) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    seen: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        if set(record) != {"id", "question"}:
            raise ValueError(f"blind record must contain only id/question: {record.get('id')}")
        if not isinstance(record["id"], str) or not record["id"] or record["id"] in seen:
            raise ValueError("blind ids must be unique non-empty strings")
        if not isinstance(record["question"], str) or not record["question"].strip():
            raise ValueError(f"blind question is empty: {record['id']}")
        seen.add(record["id"])
        records.append({"id": record["id"], "question": record["question"].strip()})
    return records


def load_id_filter(path: Path) -> list[str]:
    ids = [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(ids) != len(set(ids)):
        raise ValueError("ID filter contains duplicate IDs")
    if not ids:
        raise ValueError("ID filter is empty")
    return ids


def load_split_ids(path: Path, split: str) -> list[str]:
    if split not in {"train", "dev", "test"}:
        raise ValueError("split must be train, dev, or test")
    ids: list[str] = []
    seen: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        sample_id = record.get("id")
        if record.get("split") != split or not isinstance(sample_id, str) or not sample_id:
            continue
        if sample_id in seen:
            raise ValueError(f"gold file contains duplicate ID: {sample_id}")
        seen.add(sample_id)
        ids.append(sample_id)
    if not ids:
        raise ValueError(f"gold file contains no records for split: {split}")
    return ids


def select_pilot_ids(path: Path, split: str, *, metric_count: int) -> list[str]:
    """Select complete three-case metric groups across scenes and domains."""

    if metric_count < 1:
        raise ValueError("pilot metric count must be positive")
    groups: dict[str, dict[str, Any]] = {}
    scene_order: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        if record.get("split") != split:
            continue
        expected = record.get("expected", {})
        code = expected.get("metricCode")
        scene = expected.get("scene")
        domain = expected.get("domain")
        sample_id = record.get("id")
        case_type = record.get("caseType")
        if not all(isinstance(value, str) and value for value in (code, scene, domain, sample_id, case_type)):
            raise ValueError("pilot selection requires id, caseType, metricCode, scene, and domain")
        if scene not in scene_order:
            scene_order.append(scene)
        group = groups.setdefault(code, {"scene": scene, "domain": domain, "ids": {}})
        if group["scene"] != scene or group["domain"] != domain:
            raise ValueError(f"inconsistent pilot metadata for metric: {code}")
        group["ids"][case_type] = sample_id

    required_cases = ("CANONICAL_QUERY", "ALIAS_QUERY", "GOVERNANCE_QA")
    eligible = {
        code: group
        for code, group in groups.items()
        if all(case_type in group["ids"] for case_type in required_cases)
    }
    queues: dict[str, list[str]] = {scene: [] for scene in scene_order}
    seen_scene_domains: dict[str, set[str]] = {scene: set() for scene in scene_order}
    for code, group in eligible.items():
        scene = group["scene"]
        domain = group["domain"]
        if domain not in seen_scene_domains[scene]:
            queues[scene].append(code)
            seen_scene_domains[scene].add(domain)

    selected_codes: list[str] = []
    while len(selected_codes) < metric_count and any(queues.values()):
        for scene in scene_order:
            if queues[scene] and len(selected_codes) < metric_count:
                selected_codes.append(queues[scene].pop(0))
    if len(selected_codes) < metric_count:
        for code in eligible:
            if code not in selected_codes:
                selected_codes.append(code)
                if len(selected_codes) == metric_count:
                    break
    if len(selected_codes) < metric_count:
        raise ValueError(f"split {split} contains only {len(selected_codes)} complete metric groups")
    return [eligible[code]["ids"][case_type] for code in selected_codes for case_type in required_cases]


def filter_records_by_ids(
    records: Iterable[dict[str, str]], selected_ids: Iterable[str]
) -> list[dict[str, str]]:
    records_by_id = {record["id"]: record for record in records}
    ids = list(selected_ids)
    unknown = [sample_id for sample_id in ids if sample_id not in records_by_id]
    if unknown:
        raise ValueError(f"ID filter contains unknown IDs: {', '.join(unknown[:5])}")
    return [records_by_id[sample_id] for sample_id in ids]


def load_catalog_metrics(path: Path) -> list[dict[str, Any]]:
    metrics = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    required = {"code", "name", "aliases", "scene", "domain", "unit", "aggregation", "definition"}
    if len(metrics) != 360 or len({metric.get("code") for metric in metrics}) != 360:
        raise ValueError("catalog context must contain 360 unique metrics")
    if any(not required <= set(metric) for metric in metrics):
        raise ValueError("catalog metric is missing model-context fields")
    return metrics


def _normalize(text: str) -> str:
    return re.sub(r"[\s，。！？、；：,.!?;:（）()《》“”\"'_\-/]+", "", text).casefold()


def select_candidate_metrics(question: str, metrics: Iterable[dict[str, Any]], *, limit: int = 8) -> list[dict[str, Any]]:
    """Retrieve a small catalog shortlist without reading QA gold fields."""

    if limit < 1:
        raise ValueError("candidate limit must be positive")
    normalized_question = _normalize(question)
    ranked: list[tuple[float, str, dict[str, Any]]] = []
    for metric in metrics:
        terms = [metric["name"], *metric["aliases"]]
        normalized_terms = [_normalize(str(term)) for term in terms if _normalize(str(term))]
        exact_lengths = [len(term) for term in normalized_terms if term in normalized_question]
        if exact_lengths:
            score = 10_000 + max(exact_lengths)
        else:
            score = max((SequenceMatcher(None, normalized_question, term).ratio() for term in normalized_terms), default=0.0)
        context = {
            "code": metric["code"],
            "name": metric["name"],
            "aliases": metric["aliases"],
            "scene": metric["scene"],
            "domain": metric["domain"],
            "unit": metric["unit"],
            "aggregation": metric["aggregation"],
            "definition": metric["definition"],
        }
        ranked.append((score, metric["code"], context))
    ranked.sort(key=lambda row: (-row[0], row[1]))
    return [row[2] for row in ranked[:limit]]


def build_model_request(record: dict[str, str], *, candidates: Iterable[dict[str, Any]] = ()) -> dict[str, Any]:
    if set(record) != {"id", "question"}:
        raise ValueError("model input must contain only id and question")
    if FORBIDDEN_INPUT_FIELDS & set(record):
        raise ValueError("gold fields are not allowed in a model request")
    candidate_context = json.dumps(list(candidates), ensure_ascii=False, separators=(",", ":"))
    return {
        "system": SYSTEM_PROMPT.format(candidate_context=candidate_context),
        "input": {"id": record["id"], "question": record["question"]},
    }


def _extract_json_object(content: str) -> dict[str, Any]:
    text = content.strip()
    fenced = re.fullmatch(r"```(?:json)?\s*(.*?)\s*```", text, flags=re.IGNORECASE | re.DOTALL)
    candidate = fenced.group(1).strip() if fenced else text
    decoder = json.JSONDecoder()
    for index, char in enumerate(candidate):
        if char != "{":
            continue
        try:
            value, _ = decoder.raw_decode(candidate[index:])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            return value
    raise ValueError("model response does not contain one JSON object")


def parse_model_prediction(content: str, *, sample_id: str) -> dict[str, Any]:
    value = _extract_json_object(content)
    missing = sorted(OUTPUT_FIELDS - set(value))
    if missing:
        raise ValueError(f"model prediction missing fields: {', '.join(missing)}")
    extra = set(value) - OUTPUT_FIELDS
    if extra:
        raise ValueError(f"model prediction has unsupported fields: {', '.join(sorted(extra))}")
    prediction = {"id": sample_id, **{field: value[field] for field in OUTPUT_FIELDS}}
    if prediction["action"] not in {"ROUTE_TO_DATA_QUERY", "EXPLAIN_METRIC"}:
        raise ValueError(f"unsupported action: {prediction['action']}")
    if not re.fullmatch(r"CNB(?:00[1-9]|0[1-9][0-9]|[12][0-9][0-9]|3[0-5][0-9]|360)", str(prediction["metricCode"])):
        raise ValueError(f"unsupported metric code: {prediction['metricCode']}")
    return prediction


def _messages(record: dict[str, str], metrics: Iterable[dict[str, Any]], candidate_limit: int) -> list[dict[str, str]]:
    candidates = select_candidate_metrics(record["question"], metrics, limit=candidate_limit)
    request = build_model_request(record, candidates=candidates)
    return [
        {"role": "system", "content": request["system"]},
        {"role": "user", "content": json.dumps(request["input"], ensure_ascii=False)},
    ]


def generate_predictions(
    records: Iterable[dict[str, str]],
    *,
    completion: Callable[[list[dict[str, str]]], str],
    metrics: Iterable[dict[str, Any]],
    candidate_limit: int = 8,
    existing: Iterable[dict[str, Any]] = (),
    on_checkpoint: Callable[[list[dict[str, Any]]], None] | None = None,
) -> list[dict[str, Any]]:
    # Failed rows are checkpoints for audit only; retry them on resume instead
    # of treating a timeout or malformed response as a completed prediction.
    predictions = [row for row in existing if "error" not in row]
    completed = {record.get("id") for record in predictions}
    for record in records:
        if record["id"] in completed:
            continue
        prediction: dict[str, Any]
        request_started = time.perf_counter()
        try:
            prediction = parse_model_prediction(
                completion(_messages(record, metrics, candidate_limit)), sample_id=record["id"]
            )
        except Exception as error:  # Keep a row so a failed request is auditable and resumable.
            prediction = {"id": record["id"], "error": f"{type(error).__name__}: {error}"}
        prediction["latencyMs"] = round((time.perf_counter() - request_started) * 1000)
        predictions.append(prediction)
        completed.add(record["id"])
        if on_checkpoint:
            on_checkpoint(predictions)
    return predictions


def _openai_completion(*, base_url: str, model: str, api_key: str, timeout: int, retries: int) -> Callable[[list[dict[str, str]]], str]:
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
                with urllib.request.urlopen(request, timeout=timeout) as response:
                    body = json.loads(response.read().decode("utf-8"))
                content = body["choices"][0]["message"]["content"]
                if not isinstance(content, str):
                    raise ValueError("chat completion content is not text")
                return content
            except (KeyError, TypeError, ValueError, json.JSONDecodeError, urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
                last_error = error
                if attempt < retries:
                    time.sleep(min(2**attempt, 8))
        raise RuntimeError(f"model request failed after {retries + 1} attempt(s): {last_error}")

    return complete


def _read_predictions(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_predictions(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--blind", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, help="360-metric catalog JSONL; defaults to metrics.jsonl beside --blind")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url")
    parser.add_argument("--model")
    parser.add_argument(
        "--api-key",
        default=os.environ.get("ECOMATCH_MODEL_API_KEY"),
        help="API key; defaults to ECOMATCH_MODEL_API_KEY so it is not exposed in process arguments",
    )
    parser.add_argument("--ids-file", type=Path, help="optional ordered list of blind IDs to run")
    parser.add_argument("--gold", type=Path, help="local gold JSONL used only to select a split; never sent to the model")
    parser.add_argument("--split", choices=("train", "dev", "test"), help="run only IDs from --gold in this split")
    parser.add_argument("--pilot-metrics", type=int, help="select this many complete metric groups across scenes/domains")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--candidate-limit", type=int, default=8)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--metadata-output", type=Path)
    args = parser.parse_args()

    records = load_blind_records(args.blind)
    if bool(args.gold) != bool(args.split):
        raise SystemExit("--gold and --split must be provided together")
    if args.gold:
        records = filter_records_by_ids(records, load_split_ids(args.gold, args.split))
    if args.pilot_metrics is not None:
        if not args.gold or not args.split:
            raise SystemExit("--pilot-metrics requires --gold and --split")
        records = filter_records_by_ids(records, select_pilot_ids(args.gold, args.split, metric_count=args.pilot_metrics))
    if args.ids_file:
        records = filter_records_by_ids(records, load_id_filter(args.ids_file))
    catalog_path = (args.catalog or (args.blind.parent / "metrics.jsonl")).resolve()
    metrics = load_catalog_metrics(catalog_path)
    if args.limit is not None:
        if args.limit < 1:
            raise SystemExit("--limit must be positive")
        records = records[: args.limit]
    if args.dry_run:
        first_request = None
        if records:
            first_request = build_model_request(
                records[0], candidates=select_candidate_metrics(records[0]["question"], metrics, limit=args.candidate_limit)
            )
        print(json.dumps({"status": "DRY_RUN", "count": len(records), "firstRequest": first_request}, ensure_ascii=False))
        return 0
    if not args.base_url or not args.model or not args.api_key:
        raise SystemExit("真实运行需要 --base-url、--model、--api-key；密钥不会写入报告")

    completion = _openai_completion(
        base_url=args.base_url,
        model=args.model,
        api_key=args.api_key,
        timeout=args.timeout,
        retries=args.retries,
    )
    started = time.time()
    existing = _read_predictions(args.output)

    def checkpoint(rows: list[dict[str, Any]]) -> None:
        _write_predictions(args.output, rows)
        print(json.dumps({"completed": len(rows), "total": len(records)}, ensure_ascii=False), flush=True)

    predictions = generate_predictions(
        records,
        completion=completion,
        metrics=metrics,
        candidate_limit=args.candidate_limit,
        existing=existing,
        on_checkpoint=checkpoint,
    )
    if args.metadata_output:
        metadata = {
            "status": "COMPLETE",
            "count": len(predictions),
            "errorCount": sum("error" in row for row in predictions),
            "endpointFingerprint": hashlib.sha256(args.base_url.encode("utf-8")).hexdigest()[:16],
            "modelFingerprint": hashlib.sha256(args.model.encode("utf-8")).hexdigest()[:16],
            "catalogSha256": hashlib.sha256(catalog_path.read_bytes()).hexdigest(),
            "candidateRetrieval": "catalog-term-shortlist-v1",
            "candidateLimit": args.candidate_limit,
            "durationSeconds": round(time.time() - started, 3),
        }
        args.metadata_output.parent.mkdir(parents=True, exist_ok=True)
        args.metadata_output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "COMPLETE", "count": len(predictions)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
