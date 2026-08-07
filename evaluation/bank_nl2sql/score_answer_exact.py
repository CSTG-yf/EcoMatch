#!/usr/bin/env python3
"""Score SuperSonic (or offline) evaluation items with answerExact.

Primary official metric: all required answerText numeric slots appear in the
prediction result table (sign-insensitive, numeric tolerance). Items whose gold
contract is not GOLD_OK are excluded from the official denominator.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from answer_contract import (
    DEFAULT_ABS_TOL,
    DEFAULT_REL_TOL,
    assess_gold_contract,
    equal_table,
    score_answer_exact,
)
from evaluation_policy import EvaluationAccessError, load_evaluation_records


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def score_report_against_dataset(
    report: dict[str, Any],
    records: list[dict[str, Any]],
    *,
    require_gold_ok: bool = True,
) -> dict[str, Any]:
    gold_by_id = {str(record["id"]): record for record in records}
    items_in = report.get("items")
    if not isinstance(items_in, list):
        raise ValueError("report must contain items[]")

    scored_items: list[dict[str, Any]] = []
    official_denom = 0
    official_hits = 0
    table_denom = 0
    table_hits = 0
    skipped = 0

    for raw in items_in:
        if not isinstance(raw, dict) or not isinstance(raw.get("id"), str):
            continue
        sample_id = raw["id"]
        gold = gold_by_id.get(sample_id)
        if gold is None:
            scored_items.append({**raw, "answerScore": {"scored": False, "reason": "missing_gold"}})
            skipped += 1
            continue

        contract = assess_gold_contract(gold)
        columns = raw.get("resultColumns")
        rows = raw.get("resultRows")
        has_table = isinstance(columns, list) and isinstance(rows, list)

        if not has_table and not raw.get("textSummary"):
            # Legacy reports without captured rows: cannot compute answerExact.
            answer_score = {
                "answerExact": False,
                "slotRecall": None,
                "requiredSlotCount": contract.requiredSlotCount,
                "hitCount": 0,
                "missedSlots": [],
                "goldGrade": contract.grade,
                "scored": False,
                "reason": "missing_prediction_table",
            }
            table_ex = None
        else:
            score = score_answer_exact(
                gold,
                columns=columns if isinstance(columns, list) else None,
                rows=rows if isinstance(rows, list) else None,
                text_summary=str(raw["textSummary"]) if isinstance(raw.get("textSummary"), str) else None,
                require_gold_ok=require_gold_ok,
            )
            answer_score = score.to_dict()
            if has_table and isinstance(gold.get("expected"), dict):
                table_ex = equal_table(gold["expected"], [str(c) for c in columns], rows)
            else:
                table_ex = None

        if answer_score.get("scored"):
            official_denom += 1
            official_hits += int(bool(answer_score.get("answerExact")))
        else:
            skipped += 1

        if table_ex is not None and contract.grade == "GOLD_OK":
            table_denom += 1
            table_hits += int(table_ex)

        scored_items.append(
            {
                **raw,
                "goldGrade": contract.grade,
                "answerScore": answer_score,
                "tableEX": table_ex,
            }
        )

    return {
        "recordCount": len(scored_items),
        "metrics": {
            "answerExact": _rate(official_hits, official_denom),
            "answerExactHits": official_hits,
            "answerExactDenominator": official_denom,
            "tableEX": _rate(table_hits, table_denom),
            "tableEXHits": table_hits,
            "tableEXDenominator": table_denom,
            "skippedUnscored": skipped,
            "legacyResultAccuracy": report.get("metrics", {}).get("resultAccuracy")
            if isinstance(report.get("metrics"), dict)
            else None,
        },
        "policy": {
            "officialMetric": "answerExact",
            "officialDenominator": "GOLD_OK items with capturable prediction results",
            "sqlStructureScored": False,
            "requireGoldOk": require_gold_ok,
        },
        "run": report.get("run"),
        "items": scored_items,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("report", type=Path, help="run_supersonic_eval JSON report")
    parser.add_argument("--split", choices=("train", "dev", "test"), default="train")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--acknowledge-final-test", action="store_true")
    parser.add_argument(
        "--allow-incomplete-gold",
        action="store_true",
        help="Score answerExact even when gold contract is not GOLD_OK (debug only)",
    )
    args = parser.parse_args(argv)

    try:
        records = load_evaluation_records(
            args.dataset,
            split=args.split,
            acknowledge_final_test=args.acknowledge_final_test,
        )
    except EvaluationAccessError as error:
        parser.error(str(error))

    report = _read_json(args.report)
    scored = score_report_against_dataset(
        report,
        records,
        require_gold_ok=not args.allow_incomplete_gold,
    )
    _write_json(args.output, scored)
    print(json.dumps({"metrics": scored["metrics"], "policy": scored["policy"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
