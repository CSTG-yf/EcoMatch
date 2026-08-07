#!/usr/bin/env python3
"""Compare bank-on vs bank-off SuperSonic evaluation reports for runtime ablation."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any


class AblationCompareError(RuntimeError):
    """Two ablation reports cannot be compared under the experiment contract."""


def _load_report(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AblationCompareError(f"Invalid report JSON: {path}") from error
    if not isinstance(payload, dict):
        raise AblationCompareError(f"Report root must be an object: {path}")
    return payload


def _items_by_id(report: dict[str, Any]) -> dict[str, dict[str, Any]]:
    items = report.get("items")
    if not isinstance(items, list) or not items:
        raise AblationCompareError("Report must contain a non-empty items list")
    by_id: dict[str, dict[str, Any]] = {}
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get("id"), str):
            raise AblationCompareError("Every item must include a string id")
        if item["id"] in by_id:
            raise AblationCompareError(f"Duplicate item id in report: {item['id']}")
        by_id[item["id"]] = item
    return by_id


def _expected_selected_sql_gen(runtime_mode: str | None) -> str | None:
    if runtime_mode == "bank-on":
        return "BANK_CONSTRAINED_PLAN"
    if runtime_mode == "bank-off":
        return "ONE_PASS_SELF_CONSISTENCY"
    return None


def _routing_summary(items: list[dict[str, Any]], runtime_mode: str | None) -> dict[str, Any]:
    selected = Counter()
    enabled = Counter()
    qualified = Counter()
    missing = 0
    mismatches = 0
    expected = _expected_selected_sql_gen(runtime_mode)
    for item in items:
        routing = item.get("bankRouting")
        if not isinstance(routing, dict):
            missing += 1
            continue
        selected_type = routing.get("selectedSqlGenType")
        selected[str(selected_type)] += 1
        enabled[str(bool(routing.get("bankConstrainedPlanEnabled")))] += 1
        qualified[str(bool(routing.get("bankDatasetQualified")))] += 1
        if expected is not None and selected_type != expected:
            mismatches += 1
    return {
        "missingTelemetryCount": missing,
        "selectedSqlGenTypeCounts": dict(sorted(selected.items())),
        "bankConstrainedPlanEnabledCounts": dict(sorted(enabled.items())),
        "bankDatasetQualifiedCounts": dict(sorted(qualified.items())),
        "expectedSelectedSqlGenType": expected,
        "selectedSqlGenTypeMismatchCount": mismatches,
        "routingLooksConsistent": mismatches == 0 and missing == 0,
    }


def _pair_delta(left: bool, right: bool) -> str:
    if left == right:
        return "same"
    if left and not right:
        return "only-left"
    if right and not left:
        return "only-right"
    return "same"


def compare_runtime_ablation(
    left_report: dict[str, Any],
    right_report: dict[str, Any],
    *,
    left_label: str | None = None,
    right_label: str | None = None,
) -> dict[str, Any]:
    left_run = left_report.get("run") if isinstance(left_report.get("run"), dict) else {}
    right_run = right_report.get("run") if isinstance(right_report.get("run"), dict) else {}
    left_mode = left_label or left_run.get("runtimeMode")
    right_mode = right_label or right_run.get("runtimeMode")
    left_items = _items_by_id(left_report)
    right_items = _items_by_id(right_report)

    left_ids = set(left_items)
    right_ids = set(right_items)
    if left_ids != right_ids:
        raise AblationCompareError(
            "Compared reports must cover the same record ids; "
            f"only-left={sorted(left_ids - right_ids)}, only-right={sorted(right_ids - left_ids)}"
        )

    ordered_ids = left_run.get("selectedRecordIds")
    if not isinstance(ordered_ids, list) or set(ordered_ids) != left_ids:
        ordered_ids = sorted(left_ids)

    pairs = []
    both_match = 0
    only_left_match = 0
    only_right_match = 0
    neither_match = 0
    for sample_id in ordered_ids:
        left = left_items[sample_id]
        right = right_items[sample_id]
        left_match = bool(left.get("match"))
        right_match = bool(right.get("match"))
        if left_match and right_match:
            both_match += 1
        elif left_match:
            only_left_match += 1
        elif right_match:
            only_right_match += 1
        else:
            neither_match += 1
        pairs.append(
            {
                "id": sample_id,
                "left": {
                    "parse": bool(left.get("parse")),
                    "execute": bool(left.get("execute")),
                    "match": left_match,
                    "errorCategory": left.get("errorCategory"),
                    "bankRouting": left.get("bankRouting"),
                },
                "right": {
                    "parse": bool(right.get("parse")),
                    "execute": bool(right.get("execute")),
                    "match": right_match,
                    "errorCategory": right.get("errorCategory"),
                    "bankRouting": right.get("bankRouting"),
                },
                "resultDelta": _pair_delta(left_match, right_match),
            }
        )

    left_metrics = left_report.get("metrics") if isinstance(left_report.get("metrics"), dict) else {}
    right_metrics = right_report.get("metrics") if isinstance(right_report.get("metrics"), dict) else {}
    left_result = float(left_metrics.get("resultAccuracy") or 0.0)
    right_result = float(right_metrics.get("resultAccuracy") or 0.0)

    return {
        "recordCount": len(ordered_ids),
        "left": {
            "runtimeMode": left_mode,
            "metrics": left_metrics,
            "routing": _routing_summary(list(left_items.values()), left_mode if isinstance(left_mode, str) else None),
        },
        "right": {
            "runtimeMode": right_mode,
            "metrics": right_metrics,
            "routing": _routing_summary(
                list(right_items.values()), right_mode if isinstance(right_mode, str) else None
            ),
        },
        "deltas": {
            "resultAccuracy": round(right_result - left_result, 6),
            "bothMatch": both_match,
            "onlyLeftMatch": only_left_match,
            "onlyRightMatch": only_right_match,
            "neitherMatch": neither_match,
        },
        "recommendation": _recommendation(
            left_mode if isinstance(left_mode, str) else None,
            right_mode if isinstance(right_mode, str) else None,
            left_result,
            right_result,
            only_left_match,
            only_right_match,
        ),
        "items": pairs,
    }


def _recommendation(
    left_mode: str | None,
    right_mode: str | None,
    left_result: float,
    right_result: float,
    only_left_match: int,
    only_right_match: int,
) -> dict[str, Any]:
    modes = {left_mode, right_mode}
    if modes == {"bank-on", "bank-off"}:
        bank_on_is_right = right_mode == "bank-on"
        bank_on_score = right_result if bank_on_is_right else left_result
        bank_off_score = left_result if bank_on_is_right else right_result
        bank_only = only_right_match if bank_on_is_right else only_left_match
        generic_only = only_left_match if bank_on_is_right else only_right_match
        if bank_on_score > bank_off_score:
            decision = "prefer-bank-on-for-now"
            rationale = "Bank constrained-plan scored higher resultAccuracy on the shared sample."
        elif bank_off_score > bank_on_score:
            decision = "investigate-bank-on-regressions"
            rationale = (
                "Generic ONE_PASS scored higher; keep bank code, but inspect bank-only failures "
                "before any permanent switch."
            )
        else:
            decision = "tie-on-sample"
            rationale = "Result accuracy tied on this sample; expand sample or inspect latency/error mix."
        return {
            "decision": decision,
            "rationale": rationale,
            "bankOnResultAccuracy": bank_on_score,
            "bankOffResultAccuracy": bank_off_score,
            "bankOnlyMatches": bank_only,
            "genericOnlyMatches": generic_only,
            "doNotDeleteCode": True,
        }
    return {
        "decision": "manual-review",
        "rationale": "Runtime modes are not the bank-on/bank-off pair; inspect deltas manually.",
        "doNotDeleteCode": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left_report", type=Path, help="Usually bank-off report")
    parser.add_argument("right_report", type=Path, help="Usually bank-on report")
    parser.add_argument("--left-label", choices=("bank-on", "bank-off"))
    parser.add_argument("--right-label", choices=("bank-on", "bank-off"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    comparison = compare_runtime_ablation(
        _load_report(args.left_report),
        _load_report(args.right_report),
        left_label=args.left_label,
        right_label=args.right_label,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(comparison, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "recordCount": comparison["recordCount"],
                "deltas": comparison["deltas"],
                "recommendation": comparison["recommendation"],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
