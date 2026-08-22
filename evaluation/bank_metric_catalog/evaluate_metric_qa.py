#!/usr/bin/env python3
"""Evaluate 360-metric QA predictions or run the reproducible lexicon baseline."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

try:
    from .generate_metric_qa import load_qa_release, validate_qa_release
    from .validate_catalog import load_release
except ImportError:  # direct script execution from repository root
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from evaluation.bank_metric_catalog.generate_metric_qa import (  # type: ignore[no-redef]
        load_qa_release,
        validate_qa_release,
    )
    from evaluation.bank_metric_catalog.validate_catalog import load_release  # type: ignore[no-redef]


class MetricQaEvaluationError(ValueError):
    """Predictions cannot be scored safely or unambiguously."""


LEXICON_BASELINE_WARNING = "词典基线仅用于验证360指标映射和评测链路，不代表大模型或正式赛题成绩。"
MODEL_EVALUATION_WARNING = "该结果只评测候选指标识别与治理问答，不属于现有21指标Fact v3正式成绩。"
PREDICTION_REQUIRED_FIELDS = {
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
METADATA_FIELDS = ("metricName", "matchedText", "scene", "domain", "unit", "aggregation")


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _normalize(text: str) -> str:
    return re.sub(r"[\s，。！？、；：,.!?;:（）()《》“”\"'_/\-]+", "", text).casefold()


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise MetricQaEvaluationError(f"missing JSONL file: {path}")
    try:
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    except json.JSONDecodeError as exc:
        raise MetricQaEvaluationError(f"invalid prediction JSONL: {exc}") from exc


def select_prediction_subset(
    qa_records: list[dict[str, Any]], predictions: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Select gold records matching a smoke prediction file by opaque ID."""

    gold_by_id = {record["id"]: record for record in qa_records}
    selected_ids: list[str] = []
    seen: set[str] = set()
    for prediction in predictions:
        sample_id = prediction.get("id")
        if not isinstance(sample_id, str) or not sample_id:
            raise MetricQaEvaluationError("subset prediction must have a non-empty id")
        if sample_id in seen:
            raise MetricQaEvaluationError(f"Duplicate prediction id: {sample_id}")
        if sample_id not in gold_by_id:
            raise MetricQaEvaluationError(f"Prediction id is not in gold: {sample_id}")
        seen.add(sample_id)
        selected_ids.append(sample_id)
    if not selected_ids:
        raise MetricQaEvaluationError("cannot score an empty prediction subset")
    return [gold_by_id[sample_id] for sample_id in selected_ids]


def build_lexicon_predictions(
    qa_records: list[dict[str, Any]], metrics: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Build a transparent exact-term baseline for evaluator smoke testing.

    This is not a model score. It only verifies that canonical names and aliases
    in the catalog can flow through the complete prediction/evaluation contract.
    """

    terms: list[tuple[str, str]] = []
    for metric in metrics:
        for term in (metric["name"], *metric["aliases"]):
            normalized = _normalize(term)
            if normalized:
                terms.append((normalized, metric["code"]))
    predictions: list[dict[str, Any]] = []
    for record in qa_records:
        question = _normalize(record["question"])
        matches = [(len(term), code) for term, code in terms if term in question]
        predicted_code: str | None = None
        if matches:
            longest = max(length for length, _ in matches)
            candidates = {code for length, code in matches if length == longest}
            if len(candidates) == 1:
                predicted_code = next(iter(candidates))
        metric = next(metric for metric in metrics if metric["code"] == predicted_code)
        action = (
            "EXPLAIN_METRIC"
            if "请说明其定义、单位和汇总方式" in record["question"]
            else "ROUTE_TO_DATA_QUERY"
        )
        expected = record["expected"]
        predictions.append(
            {
                "id": record["id"],
                "metricCode": predicted_code,
                "action": action,
                "metricName": metric["name"],
                "matchedText": expected["matchedText"],
                "scene": metric["scene"],
                "domain": metric["domain"],
                "unit": metric["unit"],
                "aggregation": metric["aggregation"],
                "definition": metric["definition"] if action == "EXPLAIN_METRIC" else None,
            }
        )
    return predictions


def _breakdown(counter: Counter[str]) -> dict[str, Any]:
    count = counter["count"]
    return {
        "count": count,
        "metricCodeAccuracy": _rate(counter["metric"], count),
        "actionAccuracy": _rate(counter["action"], count),
        "metadataAccuracy": _rate(counter["metadata"], count),
        "definitionAccuracy": _rate(counter["definition"], count),
        "caseAccuracy": _rate(counter["case"], count),
    }


def _latency_summary(predictions: list[dict[str, Any]]) -> dict[str, Any] | None:
    values = [
        prediction["latencyMs"]
        for prediction in predictions
        if isinstance(prediction.get("latencyMs"), (int, float))
        and not isinstance(prediction.get("latencyMs"), bool)
    ]
    if not values:
        return None
    ordered = sorted(float(value) for value in values)

    def percentile(percent: float) -> int:
        index = min(len(ordered) - 1, round((len(ordered) - 1) * percent))
        return round(ordered[index])

    return {
        "count": len(ordered),
        "mean": round(sum(ordered) / len(ordered), 3),
        "p50": percentile(0.50),
        "p95": percentile(0.95),
        "p99": percentile(0.99),
    }


def evaluate_predictions(
    qa_records: list[dict[str, Any]],
    predictions: list[dict[str, Any]],
    metrics: list[dict[str, Any]],
) -> dict[str, Any]:
    gold_by_id = {record["id"]: record for record in qa_records}
    if len(gold_by_id) != len(qa_records):
        raise MetricQaEvaluationError("duplicate QA ids in gold")
    known_codes = {metric["code"] for metric in metrics}
    prediction_by_id: dict[str, dict[str, Any]] = {}
    for prediction in predictions:
        sample_id = prediction.get("id")
        if not isinstance(sample_id, str) or not sample_id:
            raise MetricQaEvaluationError("each prediction must have a non-empty id")
        if sample_id in prediction_by_id:
            raise MetricQaEvaluationError(f"Duplicate prediction id: {sample_id}")
        prediction_by_id[sample_id] = prediction

    totals = Counter()
    errors = Counter()
    breakdowns: dict[str, dict[str, Counter[str]]] = {
        "split": defaultdict(Counter),
        "caseType": defaultdict(Counter),
        "scene": defaultdict(Counter),
        "domain": defaultdict(Counter),
    }
    metric_case_counts = Counter(record["expected"]["metricCode"] for record in qa_records)
    metric_correct_counts = Counter()
    failures: list[dict[str, Any]] = []

    for gold in qa_records:
        sample_id = gold["id"]
        expected = gold["expected"]
        prediction = prediction_by_id.get(sample_id)
        metric_correct = False
        action_correct = False
        error_category: str | None = None
        if prediction is None:
            error_category = "MISSING_PREDICTION"
        else:
            missing_fields = sorted(PREDICTION_REQUIRED_FIELDS - set(prediction))
            if missing_fields:
                error_category = "INVALID_PREDICTION_CONTRACT"
            else:
                predicted_code = prediction.get("metricCode")
                if not isinstance(predicted_code, str) or not predicted_code:
                    error_category = "INVALID_METRIC_CODE"
                elif predicted_code not in known_codes:
                    error_category = "UNKNOWN_METRIC_CODE"
                elif predicted_code != expected["metricCode"]:
                    error_category = "METRIC_MISMATCH"
                else:
                    metric_correct = True
                action_correct = prediction.get("action") == expected["action"]

        field_matches: dict[str, bool] = {}
        if error_category != "INVALID_PREDICTION_CONTRACT" and prediction is not None:
            field_matches = {
                field: prediction.get(field) == expected[field]
                for field in METADATA_FIELDS
            }
            definition_expected = expected["definition"]
            definition_actual = prediction.get("definition")
            field_matches["definition"] = (
                definition_actual == definition_expected
                if definition_expected is None
                else _normalize(str(definition_actual or "")) == _normalize(definition_expected)
            )
            if error_category is None:
                if not action_correct:
                    error_category = "ACTION_MISMATCH"
                else:
                    mismatch_categories = {
                        "metricName": "METRIC_NAME_MISMATCH",
                        "matchedText": "MATCHED_TEXT_MISMATCH",
                        "scene": "SCENE_MISMATCH",
                        "domain": "DOMAIN_MISMATCH",
                        "unit": "UNIT_MISMATCH",
                        "aggregation": "AGGREGATION_MISMATCH",
                        "definition": "DEFINITION_MISMATCH",
                    }
                    for field, category in mismatch_categories.items():
                        if not field_matches[field]:
                            error_category = category
                            break
        metadata_correct = bool(field_matches) and all(field_matches[field] for field in METADATA_FIELDS)
        definition_correct = bool(field_matches) and field_matches.get("definition", False)
        case_correct = metric_correct and action_correct and metadata_correct and definition_correct
        totals["count"] += 1
        totals["metric"] += int(metric_correct)
        totals["action"] += int(action_correct)
        totals["metadata"] += int(metadata_correct)
        totals["definition"] += int(definition_correct)
        totals["case"] += int(case_correct)
        if metric_correct:
            metric_correct_counts[expected["metricCode"]] += 1
        if error_category is not None:
            errors[error_category] += 1
            failures.append(
                {
                    "id": sample_id,
                    "errorCategory": error_category,
                    "expectedMetricCode": expected["metricCode"],
                    "predictedMetricCode": prediction.get("metricCode") if prediction else None,
                    "expectedAction": expected["action"],
                    "predictedAction": prediction.get("action") if prediction else None,
                    "mismatchedFields": [field for field, matched in field_matches.items() if not matched],
                }
            )
        labels = {
            "split": gold["split"],
            "caseType": gold["caseType"],
            "scene": expected["scene"],
            "domain": expected["domain"],
        }
        for group, label in labels.items():
            counter = breakdowns[group][label]
            counter["count"] += 1
            counter["metric"] += int(metric_correct)
            counter["action"] += int(action_correct)
            counter["metadata"] += int(metadata_correct)
            counter["definition"] += int(definition_correct)
            counter["case"] += int(case_correct)

    complete_metrics = sum(
        metric_correct_counts[code] == count for code, count in metric_case_counts.items()
    )
    recognized_metrics = sum(metric_correct_counts[code] > 0 for code in metric_case_counts)
    unmatched = sorted(set(prediction_by_id) - set(gold_by_id))
    report = {
        "goldCount": totals["count"],
        "predictionCount": len(predictions),
        "knownMetricCount": len(known_codes),
        "metrics": {
            "metricCodeAccuracy": _rate(totals["metric"], totals["count"]),
            "actionAccuracy": _rate(totals["action"], totals["count"]),
            "metadataAccuracy": _rate(totals["metadata"], totals["count"]),
            "definitionAccuracy": _rate(totals["definition"], totals["count"]),
            "caseAccuracy": _rate(totals["case"], totals["count"]),
            "recognizedMetricCoverageRate": _rate(recognized_metrics, len(metric_case_counts)),
            "completeMetricCoverageRate": _rate(complete_metrics, len(metric_case_counts)),
        },
        "counts": {
            "metricCodeCorrect": totals["metric"],
            "actionCorrect": totals["action"],
            "caseCorrect": totals["case"],
            "recognizedMetrics": recognized_metrics,
            "completeMetrics": complete_metrics,
        },
        "bySplit": {name: _breakdown(value) for name, value in sorted(breakdowns["split"].items())},
        "byCaseType": {
            name: _breakdown(value) for name, value in sorted(breakdowns["caseType"].items())
        },
        "byScene": {name: _breakdown(value) for name, value in sorted(breakdowns["scene"].items())},
        "byDomain": {name: _breakdown(value) for name, value in sorted(breakdowns["domain"].items())},
        "errorCategories": dict(sorted(errors.items())),
        "unmatchedPredictionIds": unmatched,
        "failures": failures,
    }
    latency = _latency_summary(predictions)
    if latency is not None:
        report["latencyMs"] = latency
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qa-dir", type=Path, required=True)
    parser.add_argument("--catalog-dir", type=Path)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--predictions", type=Path)
    source.add_argument("--lexicon-baseline", action="store_true")
    parser.add_argument(
        "--subset-predictions",
        action="store_true",
        help="score only gold records whose IDs occur in --predictions (for smoke runs)",
    )
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    catalog_dir = (args.catalog_dir or args.qa_dir).resolve()
    qa_dir = args.qa_dir.resolve()
    try:
        validate_qa_release(qa_dir)
        qa_records, _ = load_qa_release(qa_dir)
        metrics, _, _ = load_release(catalog_dir)
        if args.lexicon_baseline:
            predictions = build_lexicon_predictions(qa_records, metrics)
            mode = "LEXICON_BASELINE"
            warning = LEXICON_BASELINE_WARNING
        else:
            predictions = _read_jsonl(args.predictions)
            mode = "MODEL_PREDICTIONS"
            warning = MODEL_EVALUATION_WARNING
        scored_qa_records = qa_records
        if args.subset_predictions:
            if args.lexicon_baseline:
                raise MetricQaEvaluationError("--subset-predictions requires --predictions")
            scored_qa_records = select_prediction_subset(qa_records, predictions)
        report = {
            "evaluationMode": mode,
            "evaluationScope": "PREDICTION_ID_SUBSET" if args.subset_predictions else "FULL_RELEASE",
            "warning": warning,
            **evaluate_predictions(scored_qa_records, predictions, metrics),
        }
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
    except (MetricQaEvaluationError, OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"INVALID: {exc}")
        return 1
    print(
        json.dumps(
            {
                "status": "VALID",
                "evaluationMode": report["evaluationMode"],
                "goldCount": report["goldCount"],
                "metrics": report["metrics"],
                "report": str(args.report),
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
