#!/usr/bin/env python3
"""Fail-closed validation for the separately maintained public disclosure facts."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Any


DATA_ORIGIN = "PUBLIC_DISCLOSURE"
REVIEW_STATUS = "PUBLIC_SOURCE_EXTRACTED_PENDING_BUSINESS_REVIEW"
EXPECTED_CODES = (
    "CNB001", "CNB016", "CNB043", "CNB046", "CNB049", "CNB052", "CNB063", "CNB064",
    "CNB065", "CNB066", "CNB071", "CNB079", "CNB157", "CNB169", "CNB170", "CNB191",
    "CNB192", "CNB193",
)
AMOUNT_CODES = {"CNB001", "CNB016", "CNB043", "CNB046", "CNB049", "CNB052", "CNB063", "CNB064", "CNB065", "CNB066"}
PENDING_MAPPING_CODES = {"CNB001", "CNB016"}
FACT_FIELDS = {
    "dataDate", "orgCode", "orgName", "metricCode", "metricName", "metricValue", "unit", "sourceId",
    "sourcePage", "sourcePdfPage", "sourceLocator", "sourceField", "sourceValue", "sourceUnit", "conversion",
    "dataOrigin", "reviewStatus", "mappingStatus",
}


class PublicFactsValidationError(ValueError):
    """The public disclosure release is not safe to use."""


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PublicFactsValidationError(f"invalid JSON: {path}: {error}") from error


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    except (OSError, json.JSONDecodeError) as error:
        raise PublicFactsValidationError(f"invalid JSONL: {path}: {error}") from error


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _catalog_dir_default() -> Path:
    return Path(__file__).resolve().parents[2] / "bank_metric_catalog" / "releases" / "0.1.0-candidate"


def _load_catalog(catalog_dir: Path) -> dict[str, dict[str, Any]]:
    path = catalog_dir / "metrics.jsonl"
    metrics = _read_jsonl(path)
    by_code = {metric.get("code"): metric for metric in metrics}
    if len(by_code) != len(metrics):
        raise PublicFactsValidationError("candidate catalog contains duplicate metric codes")
    missing = sorted(set(EXPECTED_CODES) - set(by_code))
    if missing:
        raise PublicFactsValidationError(f"public facts reference missing catalog metrics: {missing}")
    return by_code


def _validate_sources(sources: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(sources, list) or not sources:
        raise PublicFactsValidationError("sources.json must be a non-empty list")
    required = {
        "sourceId", "title", "authority", "url", "reportYear", "retrievedAt", "sourceType", "reusePolicy", "notes",
    }
    result: dict[str, dict[str, Any]] = {}
    for source in sources:
        if not isinstance(source, dict) or set(source) != required:
            raise PublicFactsValidationError(f"source fields mismatch: {source}")
        source_id = source.get("sourceId")
        url = source.get("url", "")
        if not isinstance(source_id, str) or not source_id or source_id in result:
            raise PublicFactsValidationError(f"duplicate or empty sourceId: {source_id}")
        if not isinstance(url, str) or not url.startswith("https://"):
            raise PublicFactsValidationError(f"source URL must be HTTPS: {source_id}")
        if source.get("sourceType") != "OFFICIAL_PUBLIC_DISCLOSURE":
            raise PublicFactsValidationError(f"source must be an official public disclosure: {source_id}")
        if source.get("reusePolicy") != "EXTRACTED_FACTS_WITH_CITATION_ONLY":
            raise PublicFactsValidationError(f"source reuse policy mismatch: {source_id}")
        if not isinstance(source.get("reportYear"), int) or not isinstance(source.get("retrievedAt"), str):
            raise PublicFactsValidationError(f"source metadata type mismatch: {source_id}")
        result[source_id] = source
    return result


def _validate_facts(facts: list[dict[str, Any]], sources: dict[str, dict[str, Any]], catalog: dict[str, dict[str, Any]]) -> dict[str, Any]:
    if len(facts) != len(EXPECTED_CODES) or {fact.get("metricCode") for fact in facts} != set(EXPECTED_CODES):
        raise PublicFactsValidationError("public facts must cover the 18 selected catalog metrics exactly once")
    keys: set[tuple[str, str, str]] = set()
    organizations: set[str] = set()
    dates: set[str] = set()
    pending = 0
    for fact in facts:
        if set(fact) != FACT_FIELDS:
            raise PublicFactsValidationError(f"fact fields mismatch for {fact.get('metricCode')}")
        code = fact["metricCode"]
        metric = catalog.get(code)
        if metric is None:
            raise PublicFactsValidationError(f"unknown metric code: {code}")
        if fact["dataOrigin"] != DATA_ORIGIN or fact["reviewStatus"] != REVIEW_STATUS:
            raise PublicFactsValidationError(f"fact boundary mismatch: {code}")
        if fact["metricName"] != metric["name"] or fact["unit"] != metric["unit"]:
            raise PublicFactsValidationError(f"catalog name/unit mismatch: {code}")
        if fact["sourceId"] not in sources or not isinstance(fact["sourceLocator"], str) or not fact["sourceLocator"]:
            raise PublicFactsValidationError(f"missing source traceability: {code}")
        if fact["mappingStatus"] not in {"DIRECT_PUBLIC_FIELD", "PUBLIC_FIELD_ALIAS", "CANDIDATE_MAPPING_PENDING_BUSINESS_REVIEW"}:
            raise PublicFactsValidationError(f"invalid mapping status: {code}")
        if code in PENDING_MAPPING_CODES and fact["mappingStatus"] != "CANDIDATE_MAPPING_PENDING_BUSINESS_REVIEW":
            raise PublicFactsValidationError(f"candidate mapping must remain pending review: {code}")
        if code not in PENDING_MAPPING_CODES and fact["mappingStatus"] == "CANDIDATE_MAPPING_PENDING_BUSINESS_REVIEW":
            raise PublicFactsValidationError(f"unexpected pending mapping: {code}")
        if not isinstance(fact["sourcePage"], int) or fact["sourcePage"] < 1 or not isinstance(fact["sourcePdfPage"], int) or fact["sourcePdfPage"] < 1:
            raise PublicFactsValidationError(f"invalid source page: {code}")
        if not isinstance(fact["sourceValue"], (int, float)) or isinstance(fact["sourceValue"], bool) or not math.isfinite(float(fact["sourceValue"])):
            raise PublicFactsValidationError(f"invalid source value: {code}")
        if not isinstance(fact["metricValue"], (int, float)) or isinstance(fact["metricValue"], bool) or not math.isfinite(float(fact["metricValue"])):
            raise PublicFactsValidationError(f"invalid metric value: {code}")
        expected_value = float(fact["sourceValue"]) * 100 if code in AMOUNT_CODES else float(fact["sourceValue"])
        if fact["conversion"] != ("sourceValue * 100" if code in AMOUNT_CODES else "sourceValue"):
            raise PublicFactsValidationError(f"conversion rule mismatch: {code}")
        if not math.isclose(float(fact["metricValue"]), expected_value, rel_tol=0, abs_tol=1e-9):
            raise PublicFactsValidationError(f"converted value mismatch: {code}")
        expected_source_unit = "人民币百万元" if code in AMOUNT_CODES else "百分比"
        if fact["sourceUnit"] != expected_source_unit:
            raise PublicFactsValidationError(f"source unit mismatch: {code}")
        key = (fact["dataDate"], fact["orgCode"], code)
        if key in keys:
            raise PublicFactsValidationError(f"duplicate fact key: {key}")
        keys.add(key)
        organizations.add(fact["orgCode"])
        dates.add(fact["dataDate"])
        pending += int(fact["mappingStatus"] == "CANDIDATE_MAPPING_PENDING_BUSINESS_REVIEW")
    return {
        "facts": len(facts),
        "metrics": len({fact["metricCode"] for fact in facts}),
        "organizations": len(organizations),
        "dates": len(dates),
        "sourceLinkedFacts": len(facts),
        "pendingBusinessReviewFacts": pending,
    }


def _manifest_for(release_dir: Path, counts: dict[str, Any]) -> dict[str, Any]:
    return {
        "version": "0.1.0-public-disclosure",
        "status": "PUBLIC_DISCLOSURE_CANDIDATE",
        "schemaVersion": "1.0.0",
        "dataOrigin": DATA_ORIGIN,
        "catalogVersion": "0.1.0-candidate",
        "officialInputs": [],
        "official21MetricEvaluationModified": False,
        "reviewStatus": REVIEW_STATUS,
        "counts": {key: counts[key] for key in ("facts", "metrics", "organizations", "dates")},
        "files": {
            name: {"sha256": _sha256(release_dir / name), "bytes": (release_dir / name).stat().st_size}
            for name in ("facts.jsonl", "queries.jsonl", "sources.json")
        },
    }


def validate_release(release_dir: Path, *, catalog_dir: Path | None = None) -> dict[str, Any]:
    release_dir = release_dir.resolve()
    catalog_dir = (catalog_dir or _catalog_dir_default()).resolve()
    required_files = ("manifest.json", "facts.jsonl", "queries.jsonl", "sources.json")
    missing = [name for name in required_files if not (release_dir / name).is_file()]
    if missing:
        raise PublicFactsValidationError(f"missing public release files: {missing}")
    manifest = _read_json(release_dir / "manifest.json")
    if manifest.get("status") != "PUBLIC_DISCLOSURE_CANDIDATE" or manifest.get("dataOrigin") != DATA_ORIGIN:
        raise PublicFactsValidationError("public disclosure boundary mismatch")
    if manifest.get("officialInputs") != [] or manifest.get("official21MetricEvaluationModified") is not False:
        raise PublicFactsValidationError("public release must not modify or consume official 21-metric inputs")
    sources = _validate_sources(_read_json(release_dir / "sources.json"))
    facts = _read_jsonl(release_dir / "facts.jsonl")
    counts = _validate_facts(facts, sources, _load_catalog(catalog_dir))
    queries = _read_jsonl(release_dir / "queries.jsonl")
    if len(queries) != 3 or len({query.get("id") for query in queries}) != 3:
        raise PublicFactsValidationError("public release must contain three distinct validation queries")
    fact_codes = set(EXPECTED_CODES)
    covered_codes: set[str] = set()
    for query in queries:
        if set(query) != {"id", "question", "sql", "expected", "metricCodes"} or not query["sql"].lstrip().upper().startswith("SELECT "):
            raise PublicFactsValidationError(f"invalid public query contract: {query.get('id')}")
        if not set(query["metricCodes"]) <= fact_codes:
            raise PublicFactsValidationError(f"query references an unknown public metric: {query['id']}")
        covered_codes.update(query["metricCodes"])
    if covered_codes != fact_codes:
        raise PublicFactsValidationError("validation queries do not cover every public fact")
    if manifest.get("counts") != {key: counts[key] for key in ("facts", "metrics", "organizations", "dates")}:
        raise PublicFactsValidationError("manifest count summary mismatch")
    for name in ("facts.jsonl", "queries.jsonl", "sources.json"):
        entry = manifest.get("files", {}).get(name)
        path = release_dir / name
        if not isinstance(entry, dict) or entry.get("sha256") != _sha256(path) or entry.get("bytes") != path.stat().st_size:
            raise PublicFactsValidationError(f"manifest hash mismatch: {name}")
    return {
        "status": "VALID",
        "dataOrigin": DATA_ORIGIN,
        "counts": {key: counts[key] for key in ("facts", "metrics", "organizations", "dates")},
        **counts,
        "queryCount": len(queries),
        "coveredMetricCodes": sorted(covered_codes),
    }


def write_manifest(release_dir: Path, *, catalog_dir: Path | None = None) -> dict[str, Any]:
    release_dir = release_dir.resolve()
    sources = _validate_sources(_read_json(release_dir / "sources.json"))
    facts = _read_jsonl(release_dir / "facts.jsonl")
    counts = _validate_facts(facts, sources, _load_catalog((catalog_dir or _catalog_dir_default()).resolve()))
    manifest = _manifest_for(release_dir, counts)
    (release_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    parser.add_argument("--catalog-dir", type=Path)
    parser.add_argument("--write-manifest", action="store_true")
    args = parser.parse_args()
    try:
        if args.write_manifest:
            write_manifest(args.release_dir, catalog_dir=args.catalog_dir)
        print(json.dumps(validate_release(args.release_dir, catalog_dir=args.catalog_dir), ensure_ascii=False, sort_keys=True))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"INVALID: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
