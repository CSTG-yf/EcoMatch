#!/usr/bin/env python3
"""Build a train/dev-only fact-contract v3 dry-run report.

This command never reads the frozen test split and never rewrites dataset or
manifest files.  It exists to review denominator and grounding gaps before a
new scoring protocol is promoted.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from evaluation_policy import EvaluationAccessError, load_evaluation_records
from fact_contract_v3 import build_fact_contract_report


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--split", choices=("train", "dev", "both"), default="both")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--legacy-incomplete-only",
        action="store_true",
        help="Emit only records excluded by the legacy GOLD_OK denominator",
    )
    args = parser.parse_args(argv)

    selected = ("train", "dev") if args.split == "both" else (args.split,)
    records_by_split: dict[str, list[dict[str, Any]]] = {}
    try:
        for split in selected:
            records_by_split[split] = load_evaluation_records(args.dataset, split=split)
    except EvaluationAccessError as error:
        parser.error(str(error))

    report = build_fact_contract_report(
        records_by_split,
        legacy_incomplete_only=args.legacy_incomplete_only,
    )
    _write_json(args.output, report)
    print(json.dumps(report["summary"], ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
