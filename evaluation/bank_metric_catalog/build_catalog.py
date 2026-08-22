#!/usr/bin/env python3
"""Deterministically build the candidate banking metric catalog release."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any

try:
    from .catalog_source import (
        CLEANUP_POLICY_VERSION,
        DOMAIN_QUOTAS,
        SCENE_QUOTAS,
        SOURCES,
        VERSION,
        build_cleanup_report,
        build_metric_records,
    )
except ImportError:  # direct script execution from repository root
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from evaluation.bank_metric_catalog.catalog_source import (  # type: ignore[no-redef]
        CLEANUP_POLICY_VERSION,
        DOMAIN_QUOTAS,
        SCENE_QUOTAS,
        SOURCES,
        VERSION,
        build_cleanup_report,
        build_metric_records,
    )


PACKAGE_DIR = Path(__file__).resolve().parent
SCHEMA_PATH = PACKAGE_DIR / "schema.json"


def _json_bytes(value: Any, *, pretty: bool = False) -> bytes:
    if pretty:
        text = json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2)
    else:
        text = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return (text + "\n").encode("utf-8")


def _jsonl_bytes(records: list[dict[str, Any]]) -> bytes:
    return b"".join(_json_bytes(record) for record in records)


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _review_csv_bytes(metrics: list[dict[str, Any]]) -> bytes:
    columns = [
        "code",
        "name",
        "legacyCodes",
        "scene",
        "domain",
        "metricType",
        "unit",
        "aggregation",
        "direction",
        "definition",
        "formula",
        "sourceIds",
        "sourceLocators",
        "provenanceLevel",
        "reviewStatus",
        "reviewDecision",
        "reviewComment",
    ]
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=columns, lineterminator="\n")
    writer.writeheader()
    for metric in metrics:
        writer.writerow(
            {
                "code": metric["code"],
                "name": metric["name"],
                "legacyCodes": "|".join(metric["legacyCodes"]),
                "scene": metric["scene"],
                "domain": metric["domain"],
                "metricType": metric["metricType"],
                "unit": metric["unit"],
                "aggregation": metric["aggregation"],
                "direction": metric["direction"],
                "definition": metric["definition"],
                "formula": json.dumps(metric["formula"], ensure_ascii=False, sort_keys=True) if metric["formula"] else "",
                "sourceIds": "|".join(ref["sourceId"] for ref in metric["sourceRefs"]),
                "sourceLocators": "|".join(ref["locator"] for ref in metric["sourceRefs"]),
                "provenanceLevel": metric["provenanceLevel"],
                "reviewStatus": metric["reviewStatus"],
                "reviewDecision": "",
                "reviewComment": "",
            }
        )
    return ("\ufeff" + stream.getvalue()).encode("utf-8")


def build_release(output_dir: Path) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    metrics = build_metric_records()
    sources = sorted(SOURCES, key=lambda item: item["sourceId"])
    metrics_payload = _jsonl_bytes(metrics)
    sources_payload = _json_bytes(sources, pretty=True)
    review_payload = _review_csv_bytes(metrics)
    cleanup_report = build_cleanup_report(metrics)
    cleanup_report_payload = _json_bytes(cleanup_report, pretty=True)
    schema_payload = SCHEMA_PATH.read_bytes()

    (output_dir / "metrics.jsonl").write_bytes(metrics_payload)
    (output_dir / "sources.json").write_bytes(sources_payload)
    (output_dir / "review.csv").write_bytes(review_payload)
    (output_dir / "metric_cleanup_report.json").write_bytes(cleanup_report_payload)

    manifest = {
        "version": VERSION,
        "status": "CANDIDATE",
        "schemaVersion": "1.1.0",
        "cleanupPolicyVersion": CLEANUP_POLICY_VERSION,
        "metricCount": len(metrics),
        "legacyMetricCount": sum(len(item["legacyCodes"]) for item in metrics),
        "sourceCount": len(sources),
        "sceneCounts": dict(sorted(Counter(item["scene"] for item in metrics).items())),
        "domainCounts": dict(sorted(Counter(item["domain"] for item in metrics).items())),
        "expectedSceneQuotas": dict(sorted(SCENE_QUOTAS.items())),
        "expectedDomainQuotas": dict(sorted(DOMAIN_QUOTAS.items())),
        "reviewStatus": "CANDIDATE",
        "factDataIncluded": False,
        "official21MetricEvaluationModified": False,
        "artifacts": {
            "metrics.jsonl": {"sha256": _sha256(metrics_payload), "bytes": len(metrics_payload)},
            "sources.json": {"sha256": _sha256(sources_payload), "bytes": len(sources_payload)},
            "review.csv": {"sha256": _sha256(review_payload), "bytes": len(review_payload)},
            "metric_cleanup_report.json": {
                "sha256": _sha256(cleanup_report_payload),
                "bytes": len(cleanup_report_payload),
            },
            "../schema.json": {"sha256": _sha256(schema_payload), "bytes": len(schema_payload)},
        },
    }
    manifest_payload = _json_bytes(manifest, pretty=True)
    (output_dir / "manifest.json").write_bytes(manifest_payload)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    manifest = build_release(args.output_dir)
    print(
        json.dumps(
            {
                "version": manifest["version"],
                "metricCount": manifest["metricCount"],
                "sceneCounts": manifest["sceneCounts"],
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
