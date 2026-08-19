#!/usr/bin/env python3
"""Fail-closed validation for the candidate banking metric catalog."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

try:
    from .catalog_source import DOMAIN_QUOTAS, LEGACY_TARGET_NAMES, SCENE_QUOTAS
except ImportError:  # direct script execution from repository root
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from evaluation.bank_metric_catalog.catalog_source import (  # type: ignore[no-redef]
        DOMAIN_QUOTAS,
        LEGACY_TARGET_NAMES,
        SCENE_QUOTAS,
    )


class CatalogValidationError(ValueError):
    pass


REQUIRED_METRIC_FIELDS = {
    "code",
    "name",
    "aliases",
    "legacyCodes",
    "semanticKey",
    "scene",
    "domain",
    "metricType",
    "valueType",
    "unit",
    "aggregation",
    "direction",
    "definition",
    "dimensions",
    "formula",
    "sourceRefs",
    "provenanceLevel",
    "reviewStatus",
    "valuePolicy",
}
RAW_FACT_FIELDS = {"value", "values", "rows", "facts", "factRows", "sql", "goldSql", "answer"}
ALLOWED_SOURCE_TYPES = {"OFFICIAL_STANDARD", "OFFICIAL_REGULATION", "OFFICIAL_DISCLOSURE"}
ALLOWED_HOSTS = {
    "cfstc.pbc.gov.cn",
    "std.samr.gov.cn",
    "jrs.mof.gov.cn",
    "www.nfra.gov.cn",
    "hbba.sacinfo.org.cn",
    "image2.ccb.com",
    "www.icbc-ltd.com",
}
ALLOWED_AGGREGATIONS = {"SNAPSHOT", "SUM", "COUNT", "AVG", "RATIO"}
ALLOWED_DIRECTIONS = {"HIGHER_IS_BETTER", "LOWER_IS_BETTER", "CONTEXT_DEPENDENT"}
ALLOWED_VALUE_TYPES = {"INTEGER", "DECIMAL"}
LEGACY_CODE_PATTERN = re.compile(r"^ZB\d{3}$")


def _fail(message: str) -> None:
    raise CatalogValidationError(message)


def _normalize(text: str) -> str:
    return re.sub(r"[\s（）()_\-/]+", "", text).casefold()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_release(release_dir: Path) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    metrics_path = release_dir / "metrics.jsonl"
    sources_path = release_dir / "sources.json"
    manifest_path = release_dir / "manifest.json"
    for path in (metrics_path, sources_path, manifest_path):
        if not path.is_file():
            _fail(f"missing release artifact: {path.name}")
    metrics = [json.loads(line) for line in metrics_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    sources = json.loads(sources_path.read_text(encoding="utf-8"))
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    return metrics, sources, manifest


def _validate_sources(sources: list[dict[str, Any]]) -> set[str]:
    if not isinstance(sources, list) or not sources:
        _fail("source ledger must be a non-empty list")
    source_ids: set[str] = set()
    required = {
        "sourceId",
        "authority",
        "title",
        "identifier",
        "url",
        "sourceType",
        "role",
        "retrievedAt",
        "reusePolicy",
        "notes",
    }
    for source in sources:
        if set(source) != required:
            _fail(f"source fields mismatch for {source.get('sourceId')}")
        source_id = source["sourceId"]
        if not isinstance(source_id, str) or not source_id or source_id in source_ids:
            _fail(f"duplicate or invalid sourceId: {source_id}")
        source_ids.add(source_id)
        if source["sourceType"] not in ALLOWED_SOURCE_TYPES:
            _fail(f"non-official source type: {source_id}")
        if source["reusePolicy"] != "REFERENCE_ONLY":
            _fail(f"source reuse policy must be REFERENCE_ONLY: {source_id}")
        if source["retrievedAt"] != "2026-08-18":
            _fail(f"unexpected retrieval date: {source_id}")
        parsed = urlparse(source["url"])
        if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS:
            _fail(f"non-official or non-HTTPS source URL: {source_id}")
        for key in required - {"sourceId", "sourceType", "reusePolicy", "retrievedAt", "url"}:
            if not isinstance(source[key], str) or not source[key].strip():
                _fail(f"empty source field {key}: {source_id}")
    return source_ids


def _validate_no_cycles(metrics: list[dict[str, Any]], by_code: dict[str, dict[str, Any]]) -> None:
    edges = {
        metric["code"]: list((metric.get("formula") or {}).get("operands", []))
        for metric in metrics
        if metric["metricType"] == "DERIVED"
    }
    state: dict[str, int] = {}

    def visit(code: str) -> None:
        if state.get(code) == 1:
            _fail(f"derived formula cycle detected at {code}")
        if state.get(code) == 2:
            return
        state[code] = 1
        for operand in edges.get(code, []):
            if operand in edges:
                visit(operand)
        state[code] = 2

    for code in edges:
        visit(code)


def validate_records(metrics: list[dict[str, Any]], sources: list[dict[str, Any]]) -> dict[str, Any]:
    source_ids = _validate_sources(sources)
    if len(metrics) != 360:
        _fail(f"metric count must be 360, got {len(metrics)}")

    codes: set[str] = set()
    names: dict[str, str] = {}
    semantic_keys: set[str] = set()
    aliases: dict[str, str] = {}
    legacy_codes: dict[str, str] = {}
    expected_codes = {f"CNB{index:03d}" for index in range(1, 361)}

    for metric in metrics:
        missing = REQUIRED_METRIC_FIELDS - set(metric)
        if missing:
            _fail(f"missing required fields for {metric.get('code')}: {sorted(missing)}")
        leaked = RAW_FACT_FIELDS & set(metric)
        if leaked:
            _fail(f"raw fact fields are forbidden for {metric.get('code')}: {sorted(leaked)}")
        code = metric["code"]
        if not isinstance(code, str) or not re.fullmatch(r"CNB\d{3}", code) or code in codes:
            _fail(f"duplicate or invalid metric code: {code}")
        codes.add(code)
        name = metric["name"]
        if not isinstance(name, str) or not name.strip():
            _fail(f"empty metric name: {code}")
        normalized_name = _normalize(name)
        if normalized_name in names:
            _fail(f"duplicate normalized name: {name} / {names[normalized_name]}")
        names[normalized_name] = name
        semantic_key = metric["semanticKey"]
        if not isinstance(semantic_key, str) or not semantic_key.strip() or semantic_key in semantic_keys:
            _fail(f"duplicate or invalid semanticKey: {semantic_key}")
        semantic_keys.add(semantic_key)
        if not isinstance(metric["aliases"], list) or len(metric["aliases"]) < 2:
            _fail(f"at least two aliases are required: {code}")
        for alias in metric["aliases"]:
            if not isinstance(alias, str) or not alias.strip():
                _fail(f"empty alias: {code}")
            normalized_alias = _normalize(alias)
            if normalized_alias in aliases:
                _fail(f"duplicate alias: {alias}")
            aliases[normalized_alias] = code
        metric_legacy_codes = metric["legacyCodes"]
        if not isinstance(metric_legacy_codes, list):
            _fail(f"legacyCodes must be a list: {code}")
        for legacy_code in metric_legacy_codes:
            if not isinstance(legacy_code, str) or not LEGACY_CODE_PATTERN.fullmatch(legacy_code):
                _fail(f"invalid legacy code: {code} -> {legacy_code}")
            if legacy_code in legacy_codes:
                _fail(f"duplicate legacy code: {legacy_code}")
            legacy_codes[legacy_code] = name
        if metric["scene"] not in SCENE_QUOTAS or metric["domain"] not in DOMAIN_QUOTAS:
            _fail(f"invalid scene/domain: {code}")
        if metric["metricType"] not in {"BASE", "DERIVED"}:
            _fail(f"invalid metricType: {code}")
        if metric["valueType"] not in ALLOWED_VALUE_TYPES:
            _fail(f"invalid valueType: {code}")
        if metric["aggregation"] not in ALLOWED_AGGREGATIONS:
            _fail(f"invalid aggregation: {code}")
        if metric["direction"] not in ALLOWED_DIRECTIONS:
            _fail(f"invalid direction: {code}")
        if not isinstance(metric["unit"], str) or not metric["unit"].strip():
            _fail(f"empty unit: {code}")
        if metric["unit"] == "%" and metric["aggregation"] == "SUM":
            _fail(f"percentage metric cannot use SUM: {code}")
        if metric["unit"] == "万元" and metric["aggregation"] == "COUNT":
            _fail(f"currency metric cannot use COUNT aggregation: {code}")
        if metric["aggregation"] == "RATIO" and metric["unit"] != "%":
            _fail(f"RATIO metric must use percent unit: {code}")
        if name.endswith("额") and metric["unit"] in {"户", "笔", "件", "次", "个"}:
            _fail(f"amount metric cannot use count unit: {code}")
        if not isinstance(metric["definition"], str) or len(metric["definition"].strip()) < 20:
            _fail(f"definition is too short: {code}")
        if not isinstance(metric["dimensions"], list) or not metric["dimensions"]:
            _fail(f"dimensions must be non-empty: {code}")
        if metric["reviewStatus"] != "CANDIDATE":
            _fail(f"reviewStatus must remain CANDIDATE: {code}")
        if metric["valuePolicy"] != "SYNTHETIC_OR_DESENSITIZED_ONLY":
            _fail(f"invalid value policy: {code}")
        if metric["provenanceLevel"] != "NEEDS_HUMAN_VERIFICATION":
            _fail(f"candidate provenance must require human verification: {code}")
        refs = metric["sourceRefs"]
        if not isinstance(refs, list) or not refs:
            _fail(f"sourceRefs must be non-empty: {code}")
        for ref in refs:
            if set(ref) != {"sourceId", "locator"}:
                _fail(f"sourceRef fields mismatch: {code}")
            if ref["sourceId"] not in source_ids:
                _fail(f"unknown source {ref['sourceId']} for {code}")
            if not isinstance(ref["locator"], str) or "待人工核验" not in ref["locator"]:
                _fail(f"candidate locator must explicitly require human verification: {code}")

    if codes != expected_codes:
        _fail("metric codes must be the complete CNB001..CNB360 range")
    if set(legacy_codes) != set(LEGACY_TARGET_NAMES):
        _fail("legacy metric codes must be exactly ZB001..ZB021")
    for legacy_code, expected_name in LEGACY_TARGET_NAMES.items():
        if legacy_codes[legacy_code] != expected_name:
            _fail(
                f"legacy target mismatch: {legacy_code} -> {legacy_codes[legacy_code]}, "
                f"expected {expected_name}"
            )
    for normalized_alias, code in aliases.items():
        if normalized_alias in names and names[normalized_alias] != next(m["name"] for m in metrics if m["code"] == code):
            _fail(f"alias collides with canonical name: {code}")

    by_code = {metric["code"]: metric for metric in metrics}
    for metric in metrics:
        formula = metric["formula"]
        if metric["metricType"] == "BASE":
            if formula is not None:
                _fail(f"base metric must not contain formula: {metric['code']}")
            continue
        if not isinstance(formula, dict) or set(formula) != {"operation", "expression", "operands"}:
            _fail(f"derived formula fields mismatch: {metric['code']}")
        if formula["operation"] != "DIVIDE_PERCENT" or not isinstance(formula["expression"], str) or not formula["expression"].strip():
            _fail(f"invalid derived formula: {metric['code']}")
        operands = formula["operands"]
        if not isinstance(operands, list) or not operands:
            _fail(f"derived operands must be non-empty: {metric['code']}")
        for operand in operands:
            if operand not in by_code:
                _fail(f"unknown operand {operand} for {metric['code']}")
            if operand == metric["code"]:
                _fail(f"derived metric cannot reference itself: {metric['code']}")
    _validate_no_cycles(metrics, by_code)

    scene_counts = dict(sorted(Counter(metric["scene"] for metric in metrics).items()))
    domain_counts = dict(sorted(Counter(metric["domain"] for metric in metrics).items()))
    if scene_counts != dict(sorted(SCENE_QUOTAS.items())):
        _fail(f"scene quota mismatch: {scene_counts}")
    if domain_counts != dict(sorted(DOMAIN_QUOTAS.items())):
        _fail(f"domain quota mismatch: {domain_counts}")
    return {
        "metricCount": len(metrics),
        "sourceCount": len(sources),
        "derivedMetricCount": sum(metric["metricType"] == "DERIVED" for metric in metrics),
        "legacyMetricCount": len(legacy_codes),
        "sceneCounts": scene_counts,
        "domainCounts": domain_counts,
    }


def validate_release(release_dir: Path) -> dict[str, Any]:
    metrics, sources, manifest = load_release(release_dir)
    report = validate_records(metrics, sources)
    if manifest.get("version") != "0.1.0-candidate" or manifest.get("status") != "CANDIDATE":
        _fail("manifest version/status mismatch")
    if manifest.get("schemaVersion") != "1.1.0":
        _fail("manifest schema version mismatch")
    if manifest.get("metricCount") != 360 or manifest.get("factDataIncluded") is not False:
        _fail("manifest metric/fact contract mismatch")
    if manifest.get("legacyMetricCount") != report["legacyMetricCount"]:
        _fail("manifest legacy metric count mismatch")
    if manifest.get("official21MetricEvaluationModified") is not False:
        _fail("manifest must preserve the official 21-metric evaluation")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, dict):
        _fail("manifest artifacts missing")
    artifact_paths = {
        "metrics.jsonl": release_dir / "metrics.jsonl",
        "sources.json": release_dir / "sources.json",
        "review.csv": release_dir / "review.csv",
        "../schema.json": Path(__file__).resolve().parent / "schema.json",
    }
    for name, path in artifact_paths.items():
        entry = artifacts.get(name)
        if not isinstance(entry, dict) or entry.get("sha256") != _sha256(path):
            _fail(f"sha256 mismatch: {name}")
        if entry.get("bytes") != path.stat().st_size:
            _fail(f"byte size mismatch: {name}")
    if manifest.get("sceneCounts") != report["sceneCounts"] or manifest.get("domainCounts") != report["domainCounts"]:
        _fail("manifest count summary mismatch")
    review_rows = list(csv.DictReader(io.StringIO((release_dir / "review.csv").read_text(encoding="utf-8-sig"))))
    if [row.get("code") for row in review_rows] != [metric["code"] for metric in metrics]:
        _fail("review.csv must contain the same 360 metrics in code order")
    if [row.get("legacyCodes") for row in review_rows] != [
        "|".join(metric["legacyCodes"]) for metric in metrics
    ]:
        _fail("review.csv legacy code mapping mismatch")
    if any(row.get("reviewDecision") or row.get("reviewComment") for row in review_rows):
        _fail("generated review.csv decisions/comments must remain empty")
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = validate_release(args.catalog_dir)
    except (CatalogValidationError, OSError, json.JSONDecodeError) as exc:
        print(f"INVALID: {exc}")
        return 1
    print(json.dumps({"status": "VALID", **report}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
