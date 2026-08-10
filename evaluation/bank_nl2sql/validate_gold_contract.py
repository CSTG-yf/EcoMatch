#!/usr/bin/env python3
"""Audit historical structured-answer support (L2 ⊇ L1).

This is a data-audit gate, not a model scorer.  It does not produce an official
runtime score; use the Fact v3 protocol for that purpose.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from answer_contract import DEFAULT_ABS_TOL, DEFAULT_REL_TOL, scan_dataset_records
from evaluation_policy import EvaluationAccessError, load_evaluation_records


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="bank_nl2sql directory containing train/dev/test.jsonl")
    parser.add_argument(
        "--split",
        choices=("train", "dev", "test", "all"),
        default="all",
        help="Split to scan (default: all official splits)",
    )
    parser.add_argument("--output", type=Path, help="Write full JSON report")
    parser.add_argument(
        "--fail-on-incomplete",
        action="store_true",
        help="Exit 1 when any GOLD_PARTIAL/GOLD_BAD/GOLD_EMPTY_TABLE is present",
    )
    parser.add_argument(
        "--min-official-scorable-rate",
        type=float,
        default=None,
        help="Exit 1 when GOLD_OK rate is below this threshold (0-1)",
    )
    parser.add_argument("--abs-tol", type=float, default=DEFAULT_ABS_TOL)
    parser.add_argument("--rel-tol", type=float, default=DEFAULT_REL_TOL)
    parser.add_argument(
        "--acknowledge-final-test",
        action="store_true",
        help="Required when --split test or all (includes test)",
    )
    args = parser.parse_args(argv)

    splits = ("train", "dev", "test") if args.split == "all" else (args.split,)
    if "test" in splits and not args.acknowledge_final_test:
        parser.error("scanning test requires --acknowledge-final-test")

    reports: dict[str, Any] = {"splits": {}, "policy": {
        "kind": "historical-structured-contract-audit",
        "modelScoreProduced": False,
        "supersededBy": "official Fact v3 caseAccuracy runtime protocol",
    }}
    incomplete = 0
    total = 0
    ok = 0
    for split in splits:
        try:
            records = load_evaluation_records(
                args.dataset,
                split=split,
                acknowledge_final_test=args.acknowledge_final_test,
            )
        except EvaluationAccessError as error:
            parser.error(str(error))
        scan = scan_dataset_records(records, abs_tol=args.abs_tol, rel_tol=args.rel_tol)
        reports["splits"][split] = scan
        total += scan["recordCount"]
        ok += scan["officialScorableCount"]
        by_grade = scan["byGrade"]
        incomplete += (
            by_grade.get("GOLD_PARTIAL", 0)
            + by_grade.get("GOLD_BAD", 0)
            + by_grade.get("GOLD_EMPTY_TABLE", 0)
        )

    reports["totals"] = {
        "recordCount": total,
        "officialScorableCount": ok,
        "officialScorableRate": round(ok / total, 6) if total else 0.0,
        "incompleteContractCount": incomplete,
    }

    summary = {
        "totals": reports["totals"],
        "bySplit": {
            name: {
                "recordCount": payload["recordCount"],
                "byGrade": payload["byGrade"],
                "officialScorableRate": payload["officialScorableRate"],
            }
            for name, payload in reports["splits"].items()
        },
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    if args.output is not None:
        _write_json(args.output, reports)

    if args.fail_on_incomplete and incomplete:
        return 1
    if args.min_official_scorable_rate is not None:
        if reports["totals"]["officialScorableRate"] + 1e-12 < args.min_official_scorable_rate:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
