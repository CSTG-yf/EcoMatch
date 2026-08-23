#!/usr/bin/env python3
"""Build a deterministic 360-metric recognition and governance QA package."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

try:
    from .catalog_source import VERSION
    from .validate_catalog import load_release, validate_release
except ImportError:  # direct script execution from repository root
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from evaluation.bank_metric_catalog.catalog_source import VERSION  # type: ignore[no-redef]
    from evaluation.bank_metric_catalog.validate_catalog import (  # type: ignore[no-redef]
        load_release,
        validate_release,
    )


QA_SCHEMA_VERSION = "1.1.0"
QA_VERSION = "1.1.0-candidate"
QA_CASE_TYPES = ("CANONICAL_QUERY", "ALIAS_QUERY", "GOVERNANCE_QA")
QA_FILENAME = "metric_qa.jsonl"
QA_BLIND_FILENAME = "metric_qa_blind.jsonl"
QA_MANIFEST_FILENAME = "metric_qa_manifest.json"
PACKAGE_DIR = Path(__file__).resolve().parent
QA_SCHEMA_PATH = PACKAGE_DIR / "qa_schema.json"

SCENE_LABELS = {
    "OPERATIONS": "经营分析",
    "RISK": "风险管理",
    "CUSTOMER_MARKETING": "客户营销",
}
DOMAIN_LABELS = {
    "assets_liabilities_deposits_loans": "资产负债与存贷款",
    "income_profit_cost_efficiency": "收入利润与成本效率",
    "payments_accounts_transactions": "支付账户与交易",
    "product_channel_operations": "产品渠道运营",
    "credit_asset_quality": "信贷资产质量",
    "capital_solvency": "资本与偿付能力",
    "liquidity_alm": "流动性与资产负债管理",
    "market_interest_fx_risk": "市场利率与汇率风险",
    "concentration_operational_compliance": "集中度运营与合规",
    "customer_base_structure": "客户基础与结构",
    "acquisition_activation_retention": "获客激活与留存",
    "penetration_cross_sell_aum": "渗透交叉销售与资产管理规模",
    "digital_channel_service_usage": "数字渠道与服务使用",
    "complaint_satisfaction_service_quality": "投诉满意度与服务质量",
}
FORBIDDEN_FACT_KEYS = {"answerText", "facts", "goldSql", "rows", "s2sql", "sql", "value", "values"}


class MetricQaValidationError(ValueError):
    """The generated metric QA package violates its frozen contract."""


def _json_bytes(value: Any, *, pretty: bool = False) -> bytes:
    if pretty:
        text = json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2)
    else:
        text = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return (text + "\n").encode("utf-8")


def _jsonl_bytes(records: list[dict[str, Any]]) -> bytes:
    return b"".join(_json_bytes(record) for record in records)


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _sha256_path(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _split_for_code(code: str) -> str:
    remainder = int(code.removeprefix("CNB")) % 5
    if remainder == 0:
        return "test"
    if remainder == 1:
        return "dev"
    return "train"


def _opaque_id(question: str) -> str:
    digest = hashlib.sha256(f"ecomatch-bank-metric-qa-v1\n{question}".encode("utf-8")).hexdigest()
    return f"BMQ-{digest[:20]}"


def _build_case(
    metric: dict[str, Any],
    *,
    split: str,
    case_type: str,
    question: str,
    matched_text: str,
    query_features: list[str],
) -> dict[str, Any]:
    action = "EXPLAIN_METRIC" if case_type == "GOVERNANCE_QA" else "ROUTE_TO_DATA_QUERY"
    return {
        "id": _opaque_id(question),
        "split": split,
        "caseType": case_type,
        "question": question,
        "queryFeatures": query_features,
        "expected": {
            "action": action,
            "metricCode": metric["code"],
            "metricName": metric["name"],
            "matchedText": matched_text,
            "scene": metric["scene"],
            "domain": metric["domain"],
            "unit": metric["unit"],
            "aggregation": metric["aggregation"],
            "definition": metric["definition"] if case_type == "GOVERNANCE_QA" else None,
        },
        "source": {
            "kind": "bank_metric_catalog_generalization",
            "catalogVersion": VERSION,
            "metricCode": metric["code"],
            "metricReviewStatus": metric["reviewStatus"],
        },
        "officialEligible": False,
        "factDataIncluded": False,
        "valuePolicy": metric["valuePolicy"],
    }


def build_qa_records(metrics: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Create three metric-isolated QA cases for every catalog metric."""

    organizations = ("江苏省A市示例农商行", "江苏省B市示例农商行", "江苏省C市示例农商行")
    times = ("2026年6月末", "2026年上半年", "2026年第二季度")
    records: list[dict[str, Any]] = []
    for index, metric in enumerate(metrics):
        split = _split_for_code(metric["code"])
        organization = organizations[index % len(organizations)]
        time_expression = times[index % len(times)]
        scene_label = SCENE_LABELS[metric["scene"]]
        domain_label = DOMAIN_LABELS[metric["domain"]]
        canonical = metric["name"]
        alias = metric["aliases"][0]
        governance_alias = metric["aliases"][1]
        records.extend(
            (
                _build_case(
                    metric,
                    split=split,
                    case_type="CANONICAL_QUERY",
                    question=f"查询{organization}{time_expression}的{canonical}。",
                    matched_text=canonical,
                    query_features=["CANONICAL_NAME", "ORGANIZATION", "TIME"],
                ),
                _build_case(
                    metric,
                    split=split,
                    case_type="ALIAS_QUERY",
                    question=(
                        f"做{scene_label}中的{domain_label}分析时，请查看{organization}"
                        f"{time_expression}的{alias}变化。"
                    ),
                    matched_text=alias,
                    query_features=["ALIAS", "BUSINESS_SCENE", "DOMAIN", "ORGANIZATION", "TIME"],
                ),
                _build_case(
                    metric,
                    split=split,
                    case_type="GOVERNANCE_QA",
                    question=f"{governance_alias}是什么指标？请说明其定义、单位和汇总方式。",
                    matched_text=governance_alias,
                    query_features=["ALIAS", "METRIC_DEFINITION", "UNIT", "AGGREGATION"],
                ),
            )
        )
    return records


def _all_keys(value: Any) -> set[str]:
    if isinstance(value, dict):
        nested = set().union(*(_all_keys(item) for item in value.values())) if value else set()
        return set(value) | nested
    if isinstance(value, list):
        return set().union(*(_all_keys(item) for item in value)) if value else set()
    return set()


def validate_qa_records(records: list[dict[str, Any]], metrics: list[dict[str, Any]]) -> dict[str, Any]:
    if len(metrics) != 360:
        raise MetricQaValidationError(f"expected 360 catalog metrics, got {len(metrics)}")
    if len(records) != len(metrics) * len(QA_CASE_TYPES):
        raise MetricQaValidationError(f"expected 1080 QA cases, got {len(records)}")

    metric_by_code = {metric["code"]: metric for metric in metrics}
    if len(metric_by_code) != len(metrics):
        raise MetricQaValidationError("catalog metric codes must be unique")
    required_fields = {
        "id",
        "split",
        "caseType",
        "question",
        "queryFeatures",
        "expected",
        "source",
        "officialEligible",
        "factDataIncluded",
        "valuePolicy",
    }
    expected_fields = {
        "action",
        "metricCode",
        "metricName",
        "matchedText",
        "scene",
        "domain",
        "unit",
        "aggregation",
        "definition",
    }
    source_fields = {"kind", "catalogVersion", "metricCode", "metricReviewStatus"}
    ids: set[str] = set()
    questions: set[str] = set()
    cases_by_metric: dict[str, set[str]] = defaultdict(set)
    splits_by_metric: dict[str, set[str]] = defaultdict(set)

    for record in records:
        sample_id = record.get("id")
        if set(record) != required_fields:
            raise MetricQaValidationError(f"QA fields mismatch: {sample_id}")
        if not isinstance(sample_id, str) or not sample_id or sample_id in ids:
            raise MetricQaValidationError(f"duplicate or invalid QA id: {sample_id}")
        if re.fullmatch(r"BMQ-[0-9a-f]{20}", sample_id) is None:
            raise MetricQaValidationError(f"QA id must be opaque: {sample_id}")
        ids.add(sample_id)
        question = record.get("question")
        if not isinstance(question, str) or len(question) < 8 or question in questions:
            raise MetricQaValidationError(f"duplicate or invalid question: {sample_id}")
        questions.add(question)
        if record.get("caseType") not in QA_CASE_TYPES:
            raise MetricQaValidationError(f"invalid caseType: {sample_id}")
        if record.get("split") not in {"train", "dev", "test"}:
            raise MetricQaValidationError(f"invalid split: {sample_id}")
        if record.get("officialEligible") is not False or record.get("factDataIncluded") is not False:
            raise MetricQaValidationError(f"QA case must remain non-official and fact-free: {sample_id}")
        if FORBIDDEN_FACT_KEYS & _all_keys(record):
            raise MetricQaValidationError(f"raw fact or SQL payload is forbidden: {sample_id}")

        expected = record.get("expected")
        source = record.get("source")
        if not isinstance(expected, dict) or set(expected) != expected_fields:
            raise MetricQaValidationError(f"expected fields mismatch: {sample_id}")
        if not isinstance(source, dict) or set(source) != source_fields:
            raise MetricQaValidationError(f"source fields mismatch: {sample_id}")
        code = expected.get("metricCode")
        metric = metric_by_code.get(code)
        if metric is None:
            raise MetricQaValidationError(f"unknown expected metric code: {sample_id}")
        expected_projection = {
            "metricName": metric["name"],
            "scene": metric["scene"],
            "domain": metric["domain"],
            "unit": metric["unit"],
            "aggregation": metric["aggregation"],
        }
        if any(expected.get(field) != value for field, value in expected_projection.items()):
            raise MetricQaValidationError(f"expected metric metadata drift: {sample_id}")
        if expected.get("matchedText") not in question:
            raise MetricQaValidationError(f"matchedText must occur in question: {sample_id}")
        is_governance = record["caseType"] == "GOVERNANCE_QA"
        wanted_action = "EXPLAIN_METRIC" if is_governance else "ROUTE_TO_DATA_QUERY"
        wanted_definition = metric["definition"] if is_governance else None
        if expected.get("action") != wanted_action or expected.get("definition") != wanted_definition:
            raise MetricQaValidationError(f"case action/definition mismatch: {sample_id}")
        if source != {
            "kind": "bank_metric_catalog_generalization",
            "catalogVersion": VERSION,
            "metricCode": code,
            "metricReviewStatus": "CANDIDATE",
        }:
            raise MetricQaValidationError(f"source contract mismatch: {sample_id}")
        if record.get("valuePolicy") != "SYNTHETIC_OR_DESENSITIZED_ONLY":
            raise MetricQaValidationError(f"value policy mismatch: {sample_id}")
        cases_by_metric[code].add(record["caseType"])
        splits_by_metric[code].add(record["split"])

    if set(cases_by_metric) != set(metric_by_code):
        raise MetricQaValidationError("QA metric coverage mismatch")
    if any(case_types != set(QA_CASE_TYPES) for case_types in cases_by_metric.values()):
        raise MetricQaValidationError("every metric must have exactly three case types")
    if any(len(splits) != 1 for splits in splits_by_metric.values()):
        raise MetricQaValidationError("all cases for a metric must remain in one split")

    split_counts = dict(sorted(Counter(record["split"] for record in records).items()))
    if split_counts != {"dev": 216, "test": 216, "train": 648}:
        raise MetricQaValidationError(f"split count mismatch: {split_counts}")
    return {
        "qaCaseCount": len(records),
        "metricCount": len(cases_by_metric),
        "caseTypeCounts": dict(sorted(Counter(record["caseType"] for record in records).items())),
        "splitCounts": split_counts,
        "sceneCounts": dict(sorted(Counter(record["expected"]["scene"] for record in records).items())),
        "domainCounts": dict(sorted(Counter(record["expected"]["domain"] for record in records).items())),
    }


def build_qa_release(catalog_dir: Path) -> dict[str, Any]:
    catalog_dir = catalog_dir.resolve()
    validate_release(catalog_dir)
    metrics, _, catalog_manifest = load_release(catalog_dir)
    records = build_qa_records(metrics)
    report = validate_qa_records(records, metrics)
    qa_payload = _jsonl_bytes(records)
    blind_payload = _jsonl_bytes(
        [{"id": record["id"], "question": record["question"]} for record in records]
    )
    schema_payload = QA_SCHEMA_PATH.read_bytes()
    (catalog_dir / QA_FILENAME).write_bytes(qa_payload)
    (catalog_dir / QA_BLIND_FILENAME).write_bytes(blind_payload)

    manifest = {
        "version": QA_VERSION,
        "status": "CANDIDATE",
        "schemaVersion": QA_SCHEMA_VERSION,
        "catalogVersion": catalog_manifest["version"],
        **report,
        "casesPerMetric": len(QA_CASE_TYPES),
        "splitPolicy": "metric-code-mod-5; remainder 0=test, 1=dev, otherwise=train",
        "evaluationScope": "METRIC_RECOGNITION_AND_GOVERNANCE",
        "officialEligible": False,
        "factDataIncluded": False,
        "official21MetricEvaluationModified": False,
        "valuePolicy": "SYNTHETIC_OR_DESENSITIZED_ONLY",
        "catalogMetricsSha256": _sha256_path(catalog_dir / "metrics.jsonl"),
        "artifacts": {
            QA_FILENAME: {"sha256": _sha256_bytes(qa_payload), "bytes": len(qa_payload)},
            QA_BLIND_FILENAME: {
                "sha256": _sha256_bytes(blind_payload),
                "bytes": len(blind_payload),
            },
            "../../qa_schema.json": {"sha256": _sha256_bytes(schema_payload), "bytes": len(schema_payload)},
        },
    }
    (catalog_dir / QA_MANIFEST_FILENAME).write_bytes(_json_bytes(manifest, pretty=True))
    return manifest


def load_qa_release(catalog_dir: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    qa_path = catalog_dir / QA_FILENAME
    manifest_path = catalog_dir / QA_MANIFEST_FILENAME
    for path in (qa_path, manifest_path):
        if not path.is_file():
            raise MetricQaValidationError(f"missing QA release artifact: {path.name}")
    records = [json.loads(line) for line in qa_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    return records, manifest


def validate_qa_release(catalog_dir: Path) -> dict[str, Any]:
    catalog_dir = catalog_dir.resolve()
    validate_release(catalog_dir)
    metrics, _, catalog_manifest = load_release(catalog_dir)
    records, manifest = load_qa_release(catalog_dir)
    report = validate_qa_records(records, metrics)
    if manifest.get("version") != QA_VERSION or manifest.get("status") != "CANDIDATE":
        raise MetricQaValidationError("QA manifest version/status mismatch")
    if manifest.get("schemaVersion") != QA_SCHEMA_VERSION:
        raise MetricQaValidationError("QA manifest schema version mismatch")
    if manifest.get("catalogVersion") != catalog_manifest.get("version"):
        raise MetricQaValidationError("QA manifest catalog version mismatch")
    for key, value in report.items():
        if manifest.get(key) != value:
            raise MetricQaValidationError(f"QA manifest summary mismatch: {key}")
    if manifest.get("casesPerMetric") != 3 or manifest.get("evaluationScope") != "METRIC_RECOGNITION_AND_GOVERNANCE":
        raise MetricQaValidationError("QA manifest evaluation contract mismatch")
    if (
        manifest.get("officialEligible") is not False
        or manifest.get("factDataIncluded") is not False
        or manifest.get("official21MetricEvaluationModified") is not False
    ):
        raise MetricQaValidationError("QA manifest must preserve the non-official fact-free boundary")
    if manifest.get("valuePolicy") != "SYNTHETIC_OR_DESENSITIZED_ONLY":
        raise MetricQaValidationError("QA manifest value policy mismatch")
    if manifest.get("catalogMetricsSha256") != _sha256_path(catalog_dir / "metrics.jsonl"):
        raise MetricQaValidationError("catalog metrics sha256 mismatch")
    artifact_paths = {
        QA_FILENAME: catalog_dir / QA_FILENAME,
        QA_BLIND_FILENAME: catalog_dir / QA_BLIND_FILENAME,
        "../../qa_schema.json": QA_SCHEMA_PATH,
    }
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, dict):
        raise MetricQaValidationError("QA manifest artifacts missing")
    for name, path in artifact_paths.items():
        entry = artifacts.get(name)
        if not isinstance(entry, dict) or entry.get("sha256") != _sha256_path(path):
            raise MetricQaValidationError(f"sha256 mismatch: {name}")
        if entry.get("bytes") != path.stat().st_size:
            raise MetricQaValidationError(f"byte size mismatch: {name}")
    blind_records = [
        json.loads(line)
        for line in (catalog_dir / QA_BLIND_FILENAME).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    expected_blind = [{"id": record["id"], "question": record["question"]} for record in records]
    if blind_records != expected_blind:
        raise MetricQaValidationError("blind input must contain only aligned id/question pairs")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = build_qa_release(args.catalog_dir)
    except (MetricQaValidationError, OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"INVALID: {exc}")
        return 1
    print(
        json.dumps(
            {
                "status": "VALID",
                "qaCaseCount": manifest["qaCaseCount"],
                "metricCount": manifest["metricCount"],
                "splitCounts": manifest["splitCounts"],
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
