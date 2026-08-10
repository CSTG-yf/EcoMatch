#!/usr/bin/env python3
"""Compare two official Fact v3 reports for a bank runtime ablation."""

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
        if not isinstance(item.get("casePass"), bool):
            raise AblationCompareError("Every item must include a Fact v3 boolean casePass")
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


def _require_comparable_official_runs(
    left_run: dict[str, Any],
    right_run: dict[str, Any],
) -> dict[str, Any]:
    """Fail closed unless both inputs are the same official protocol run shape.

    A bank-on/bank-off experiment deliberately changes a server switch.  It
    must not also quietly change its model, data release, source revision or
    selected denominator, otherwise its case-level delta has no meaning.
    """

    required_keys = (
        "protocolSchemaVersion",
        "datasetVersion",
        "agentId",
        "modelLabel",
        "endpointFingerprint",
        "protocolProfileSha256",
        "sourceRevision",
        "captureMethod",
        "concurrency",
        "mode",
        "split",
        "selectedRecordIds",
    )
    for key in required_keys:
        if key not in left_run or key not in right_run:
            raise AblationCompareError(
                f"Compared reports must be complete official Fact v3 runs: missing {key}"
            )
        if left_run[key] != right_run[key]:
            raise AblationCompareError(
                f"Compared reports differ on required runtime contract field: {key}"
            )
    for side, run in (("left", left_run), ("right", right_run)):
        if run.get("status") != "COMPLETED":
            raise AblationCompareError(
                f"Compared {side} report is not a completed official runtime evaluation"
            )
    if left_run["captureMethod"] != "openapi-frontend-conversation-chain":
        raise AblationCompareError("Compared reports do not use the official frontend capture method")
    if left_run["concurrency"] != 1:
        raise AblationCompareError("Compared reports do not use required serial execution")
    selected_ids = left_run["selectedRecordIds"]
    if (
        not isinstance(selected_ids, list)
        or not selected_ids
        or not all(isinstance(sample_id, str) and sample_id for sample_id in selected_ids)
        or len(selected_ids) != len(set(selected_ids))
    ):
        raise AblationCompareError("Compared reports have an invalid selectedRecordIds contract")
    left_receipt = left_run.get("setupReceipt")
    right_receipt = right_run.get("setupReceipt")
    if not isinstance(left_receipt, dict) or not isinstance(right_receipt, dict):
        raise AblationCompareError("Compared reports must contain an official bootstrap receipt")
    receipt_keys = (
        "agentId",
        "modelId",
        "chatModelId",
        "dataSetId",
        "officialManifestSha256",
        "agentProfileSha256",
    )
    for key in receipt_keys:
        if left_receipt.get(key) != right_receipt.get(key):
            raise AblationCompareError(
                f"Compared reports differ on required bootstrap receipt field: {key}"
            )
    return {
        **{key: left_run[key] for key in required_keys},
        "bootstrapReceipt": {key: left_receipt[key] for key in receipt_keys},
        "systemParametersSha256": {
            "left": left_receipt.get("systemParametersSha256"),
            "right": right_receipt.get("systemParametersSha256"),
        },
    }


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
    comparability = _require_comparable_official_runs(left_run, right_run)
    left_items = _items_by_id(left_report)
    right_items = _items_by_id(right_report)

    left_ids = set(left_items)
    right_ids = set(right_items)
    if left_ids != right_ids:
        raise AblationCompareError(
            "Compared reports must cover the same record ids; "
            f"only-left={sorted(left_ids - right_ids)}, only-right={sorted(right_ids - left_ids)}"
        )

    ordered_ids = comparability["selectedRecordIds"]
    if set(ordered_ids) != left_ids:
        raise AblationCompareError("Official run metadata does not match the report item denominator")

    pairs = []
    both_case_pass = 0
    only_left_case_pass = 0
    only_right_case_pass = 0
    neither_case_pass = 0
    for sample_id in ordered_ids:
        left = left_items[sample_id]
        right = right_items[sample_id]
        left_case_pass = left["casePass"]
        right_case_pass = right["casePass"]
        if left_case_pass and right_case_pass:
            both_case_pass += 1
        elif left_case_pass:
            only_left_case_pass += 1
        elif right_case_pass:
            only_right_case_pass += 1
        else:
            neither_case_pass += 1
        pairs.append(
            {
                "id": sample_id,
                "left": {
                    "parse": bool(left.get("parse")),
                    "execute": bool(left.get("execute")),
                    "casePass": left_case_pass,
                    "errorCategory": left.get("errorCategory"),
                    "bankRouting": left.get("bankRouting"),
                },
                "right": {
                    "parse": bool(right.get("parse")),
                    "execute": bool(right.get("execute")),
                    "casePass": right_case_pass,
                    "errorCategory": right.get("errorCategory"),
                    "bankRouting": right.get("bankRouting"),
                },
                "caseDelta": _pair_delta(left_case_pass, right_case_pass),
            }
        )

    left_metrics = left_report.get("metrics") if isinstance(left_report.get("metrics"), dict) else {}
    right_metrics = right_report.get("metrics") if isinstance(right_report.get("metrics"), dict) else {}
    if not isinstance(left_metrics.get("caseAccuracy"), (int, float)):
        raise AblationCompareError("Left report must contain Fact v3 caseAccuracy")
    if not isinstance(right_metrics.get("caseAccuracy"), (int, float)):
        raise AblationCompareError("Right report must contain Fact v3 caseAccuracy")
    left_case_accuracy = float(left_metrics["caseAccuracy"])
    right_case_accuracy = float(right_metrics["caseAccuracy"])
    if not 0.0 <= left_case_accuracy <= 1.0 or not 0.0 <= right_case_accuracy <= 1.0:
        raise AblationCompareError("Fact v3 caseAccuracy must be between 0 and 1")

    return {
        "recordCount": len(ordered_ids),
        "comparability": {"verified": True, **comparability},
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
            "caseAccuracy": round(right_case_accuracy - left_case_accuracy, 6),
            "bothCasePass": both_case_pass,
            "onlyLeftCasePass": only_left_case_pass,
            "onlyRightCasePass": only_right_case_pass,
            "neitherCasePass": neither_case_pass,
        },
        "recommendation": _recommendation(
            left_mode if isinstance(left_mode, str) else None,
            right_mode if isinstance(right_mode, str) else None,
            left_case_accuracy,
            right_case_accuracy,
            only_left_case_pass,
            only_right_case_pass,
        ),
        "items": pairs,
    }


def _recommendation(
    left_mode: str | None,
    right_mode: str | None,
    left_case_accuracy: float,
    right_case_accuracy: float,
    only_left_case_pass: int,
    only_right_case_pass: int,
) -> dict[str, Any]:
    modes = {left_mode, right_mode}
    if modes == {"bank-on", "bank-off"}:
        bank_on_is_right = right_mode == "bank-on"
        bank_on_score = right_case_accuracy if bank_on_is_right else left_case_accuracy
        bank_off_score = left_case_accuracy if bank_on_is_right else right_case_accuracy
        bank_only = only_right_case_pass if bank_on_is_right else only_left_case_pass
        generic_only = only_left_case_pass if bank_on_is_right else only_right_case_pass
        if bank_on_score > bank_off_score:
            decision = "prefer-bank-on-for-now"
            rationale = "Bank constrained-plan scored higher Fact v3 caseAccuracy on the shared sample."
        elif bank_off_score > bank_on_score:
            decision = "investigate-bank-on-regressions"
            rationale = (
                "Generic ONE_PASS scored higher; keep bank code, but inspect bank-only failures "
                "before any permanent switch."
            )
        else:
            decision = "tie-on-sample"
            rationale = "Fact v3 caseAccuracy tied on this sample; expand the standard split or inspect runtime diagnostics."
        return {
            "decision": decision,
            "rationale": rationale,
            "bankOnCaseAccuracy": bank_on_score,
            "bankOffCaseAccuracy": bank_off_score,
            "bankOnlyCasePasses": bank_only,
            "genericOnlyCasePasses": generic_only,
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
