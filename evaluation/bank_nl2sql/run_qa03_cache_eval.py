#!/usr/bin/env python3
"""Verify cold and warm semantic-query cache behavior on a deployed SuperSonic instance."""

from __future__ import annotations

import argparse
import copy
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from run_supersonic_eval import (
    SuperSonicEvaluationError,
    _latency_distribution,
    _unwrap_api_response,
)


DEFAULT_QUERY_ENDPOINT = "/api/semantic/query/sql"
DEFAULT_MONITOR_ENDPOINT = "/api/semantic/query/gateway/stats"
MAX_HTTP_BODY_BYTES = 5 * 1024 * 1024
MAX_QUERY_TEMPLATE_BYTES = 1024 * 1024
SAFE_SCENARIO = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")


class Qa03CacheEvaluationError(RuntimeError):
    """The cache acceptance run did not satisfy its contract."""


def _prepare_payload(template: dict[str, Any], scenario: str) -> tuple[dict[str, Any], str]:
    if not SAFE_SCENARIO.fullmatch(scenario):
        raise Qa03CacheEvaluationError(
            "scenario must contain only letters, digits, underscore or hyphen"
        )
    sql = template.get("sql")
    if not isinstance(sql, str) or not sql.strip():
        raise Qa03CacheEvaluationError("query template must contain non-empty sql")

    run_id = uuid.uuid4().hex
    payload = copy.deepcopy(template)
    payload.pop("needAuth", None)
    payload.pop("innerLayerNative", None)
    normalized_sql = sql.rstrip()
    if normalized_sql.endswith(";"):
        normalized_sql = normalized_sql[:-1].rstrip()
    payload["sql"] = f"{normalized_sql} /* qa03-cache-{scenario}-{run_id} */"
    payload["cacheInfo"] = {"cache": True}
    return payload, run_id


def _cache_snapshot(response: Any) -> dict[str, Any]:
    snapshot = _unwrap_api_response(response)
    cache = snapshot.get("cache")
    gateway = snapshot.get("gateway")
    stages = snapshot.get("stages")
    if not isinstance(cache, dict) or not isinstance(gateway, dict) or not isinstance(stages, dict):
        raise Qa03CacheEvaluationError("gateway monitor response is incomplete")
    for field in ("hits", "misses", "requests"):
        value = cache.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise Qa03CacheEvaluationError("gateway cache counters are invalid")
    return snapshot


def _query_once(
    post_json: Callable[[str, dict[str, Any]], Any],
    endpoint: str,
    payload: dict[str, Any],
) -> tuple[bool, float]:
    started = time.perf_counter()
    response = _unwrap_api_response(post_json(endpoint, payload))
    latency_ms = round((time.perf_counter() - started) * 1000, 3)
    error_message = response.get("errorMsg")
    if isinstance(error_message, str) and error_message.strip():
        raise Qa03CacheEvaluationError("semantic query returned an error")
    use_cache = response.get("useCache")
    if not isinstance(use_cache, bool):
        raise Qa03CacheEvaluationError("semantic query did not declare useCache")
    return use_cache, latency_ms


def _counter_delta(before: dict[str, Any], after: dict[str, Any]) -> dict[str, int]:
    delta: dict[str, int] = {}
    for field in (
        "hits",
        "misses",
        "requests",
        "hotMetricHits",
        "hotMetricMisses",
        "hotMetricRequests",
    ):
        old = before.get(field)
        new = after.get(field)
        if (
            isinstance(old, int)
            and not isinstance(old, bool)
            and isinstance(new, int)
            and not isinstance(new, bool)
        ):
            if new < old:
                raise Qa03CacheEvaluationError("gateway cache counters moved backwards")
            delta[field] = new - old
    return delta


def _stage_delta(
    before: dict[str, Any], after: dict[str, Any]
) -> dict[str, dict[str, float | int]]:
    delta: dict[str, dict[str, float | int]] = {}
    for stage, after_stats in after.items():
        before_stats = before.get(stage)
        if not isinstance(before_stats, dict) or not isinstance(after_stats, dict):
            continue
        stage_delta: dict[str, float | int] = {}
        for field in ("count", "totalTimeMs"):
            old = before_stats.get(field)
            new = after_stats.get(field)
            if isinstance(old, (int, float)) and not isinstance(old, bool) and isinstance(
                new, (int, float)
            ) and not isinstance(new, bool):
                if new < old:
                    raise Qa03CacheEvaluationError("gateway stage counters moved backwards")
                stage_delta[field] = round(new - old, 3)
        if stage_delta:
            delta[str(stage)] = stage_delta
    return delta


def _gateway_delta(before: dict[str, Any], after: dict[str, Any]) -> dict[str, int]:
    delta: dict[str, int] = {}
    for field in ("acceptedQueries", "rejectedQueries", "completedQueries", "failedQueries"):
        old = before.get(field)
        new = after.get(field)
        if (
            isinstance(old, int)
            and not isinstance(old, bool)
            and isinstance(new, int)
            and not isinstance(new, bool)
        ):
            if new < old:
                raise Qa03CacheEvaluationError("query gateway counters moved backwards")
            delta[field] = new - old
    return delta


def run_cache_evaluation(
    query_template: dict[str, Any],
    *,
    scenario: str,
    post_json: Callable[[str, dict[str, Any]], Any],
    get_json: Callable[[str], Any],
    query_endpoint: str = DEFAULT_QUERY_ENDPOINT,
    monitor_endpoint: str = DEFAULT_MONITOR_ENDPOINT,
    warm_samples: int = 100,
    materialization_timeout_seconds: float = 10,
    materialization_poll_seconds: float = 0.1,
    max_warm_average_ms: float = 3000,
) -> dict[str, Any]:
    if warm_samples < 1:
        raise Qa03CacheEvaluationError("warm_samples must be at least 1")
    if materialization_timeout_seconds <= 0 or materialization_poll_seconds <= 0:
        raise Qa03CacheEvaluationError("cache materialization timing must be positive")
    if max_warm_average_ms <= 0:
        raise Qa03CacheEvaluationError("max_warm_average_ms must be positive")

    payload, run_id = _prepare_payload(query_template, scenario)
    before = _cache_snapshot(get_json(monitor_endpoint))

    cold_hit, cold_latency_ms = _query_once(post_json, query_endpoint, payload)
    if cold_hit:
        raise Qa03CacheEvaluationError("unique cold query unexpectedly hit the cache")

    materialization_started = time.perf_counter()
    materialization_attempts = 0
    while True:
        materialization_attempts += 1
        warm_hit, _ = _query_once(post_json, query_endpoint, payload)
        if warm_hit:
            break
        if time.perf_counter() - materialization_started >= materialization_timeout_seconds:
            raise Qa03CacheEvaluationError("cache entry was not materialized before timeout")
        time.sleep(materialization_poll_seconds)

    warm_latencies: list[float] = []
    for _ in range(warm_samples):
        warm_hit, latency_ms = _query_once(post_json, query_endpoint, payload)
        if not warm_hit:
            raise Qa03CacheEvaluationError("materialized cache query returned a miss")
        warm_latencies.append(latency_ms)

    after = _cache_snapshot(get_json(monitor_endpoint))
    cache_delta = _counter_delta(before["cache"], after["cache"])
    if cache_delta.get("misses", 0) < 1:
        raise Qa03CacheEvaluationError("monitor did not record the cold cache miss")
    if cache_delta.get("hits", 0) < warm_samples + 1:
        raise Qa03CacheEvaluationError("monitor did not record every verified cache hit")

    warm_distribution = _latency_distribution(warm_latencies)
    average = warm_distribution["average"]
    if not isinstance(average, (int, float)) or average > max_warm_average_ms:
        raise Qa03CacheEvaluationError("warm cache average latency exceeded the threshold")

    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scenario": scenario,
        "runId": run_id,
        "configuration": {
            "warmSamples": warm_samples,
            "materializationTimeoutSeconds": materialization_timeout_seconds,
            "materializationPollSeconds": materialization_poll_seconds,
            "maxWarmAverageMs": max_warm_average_ms,
        },
        "cold": {"useCache": False, "latencyMs": cold_latency_ms},
        "materialization": {
            "attempts": materialization_attempts,
            "verifiedHit": True,
        },
        "warm": {
            "verifiedHits": warm_samples,
            "latencyMs": warm_distribution,
        },
        "monitorDelta": {
            "cache": cache_delta,
            "gateway": _gateway_delta(before["gateway"], after["gateway"]),
            "stages": _stage_delta(before["stages"], after["stages"]),
        },
    }


def _http_json_clients(
    *,
    base_url: str,
    authorization_token: str,
    cookie: str | None,
    timeout_seconds: float,
) -> tuple[
    Callable[[str, dict[str, Any]], Any],
    Callable[[str], Any],
]:
    if timeout_seconds <= 0:
        raise Qa03CacheEvaluationError("timeout_seconds must be positive")
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise Qa03CacheEvaluationError("base URL must be an absolute HTTP(S) URL")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise Qa03CacheEvaluationError("base URL must not contain credentials, query or fragment")
    normalized_base_url = urllib.parse.urlunsplit(
        (parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", "")
    )
    token = authorization_token.removeprefix("Bearer ").strip()
    if not token:
        raise Qa03CacheEvaluationError("QA03_AUTH_TOKEN is required")
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    if cookie:
        headers["Cookie"] = cookie

    class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
        def redirect_request(
            self,
            _request: urllib.request.Request,
            _file_pointer: Any,
            _code: int,
            _message: str,
            _headers: Any,
            _new_url: str,
        ) -> None:
            return None

    opener = urllib.request.build_opener(NoRedirectHandler())

    def request_json(path: str, payload: dict[str, Any] | None) -> Any:
        normalized_path = "/" + path.strip("/")
        request = urllib.request.Request(
            normalized_base_url + normalized_path,
            data=(
                json.dumps(payload, ensure_ascii=False).encode("utf-8")
                if payload is not None
                else None
            ),
            headers=headers,
            method="POST" if payload is not None else "GET",
        )
        try:
            with opener.open(request, timeout=timeout_seconds) as response:
                body = response.read(MAX_HTTP_BODY_BYTES + 1)
        except urllib.error.HTTPError as error:
            raise Qa03CacheEvaluationError(
                f"SuperSonic HTTP request failed with status {error.code}"
            ) from None
        except (urllib.error.URLError, TimeoutError):
            raise Qa03CacheEvaluationError("SuperSonic HTTP request failed") from None
        if len(body) > MAX_HTTP_BODY_BYTES:
            raise Qa03CacheEvaluationError("SuperSonic HTTP response exceeded the size limit")
        try:
            return json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise Qa03CacheEvaluationError("SuperSonic HTTP response was not valid JSON") from None

    return (
        lambda path, payload: request_json(path, payload),
        lambda path: request_json(path, None),
    )


def _load_query_template(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise Qa03CacheEvaluationError("query template does not exist")
    if path.stat().st_size > MAX_QUERY_TEMPLATE_BYTES:
        raise Qa03CacheEvaluationError("query template exceeded the size limit")
    try:
        template = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        raise Qa03CacheEvaluationError("query template was not valid UTF-8 JSON") from None
    if not isinstance(template, dict):
        raise Qa03CacheEvaluationError("query template must be a JSON object")
    return template


def _write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--query-template", required=True, type=Path)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--query-endpoint", default=DEFAULT_QUERY_ENDPOINT)
    parser.add_argument("--monitor-endpoint", default=DEFAULT_MONITOR_ENDPOINT)
    parser.add_argument("--warm-samples", type=int, default=100)
    parser.add_argument("--timeout-seconds", type=float, default=120)
    parser.add_argument("--materialization-timeout-seconds", type=float, default=10)
    parser.add_argument("--materialization-poll-seconds", type=float, default=0.1)
    parser.add_argument("--max-warm-average-ms", type=float, default=3000)
    args = parser.parse_args()

    authorization_token = os.environ.get("QA03_AUTH_TOKEN", "")
    cookie = os.environ.get("QA03_COOKIE")
    try:
        post_json, get_json = _http_json_clients(
            base_url=args.base_url,
            authorization_token=authorization_token,
            cookie=cookie,
            timeout_seconds=args.timeout_seconds,
        )
        report = run_cache_evaluation(
            _load_query_template(args.query_template),
            scenario=args.scenario,
            post_json=post_json,
            get_json=get_json,
            query_endpoint=args.query_endpoint,
            monitor_endpoint=args.monitor_endpoint,
            warm_samples=args.warm_samples,
            materialization_timeout_seconds=args.materialization_timeout_seconds,
            materialization_poll_seconds=args.materialization_poll_seconds,
            max_warm_average_ms=args.max_warm_average_ms,
        )
        _write_report(args.output, report)
    except (Qa03CacheEvaluationError, SuperSonicEvaluationError) as error:
        parser.error(str(error))
    print(
        json.dumps(
            {
                "scenario": report["scenario"],
                "coldMs": report["cold"]["latencyMs"],
                "warmLatencyMs": report["warm"]["latencyMs"],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
