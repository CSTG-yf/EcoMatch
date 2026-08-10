#!/usr/bin/env python3
"""Score a bank NL2SQL report with fact-contract v3 and a full denominator."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from evaluation_policy import EvaluationAccessError, load_evaluation_records
from fact_contract_v3 import score_fact_contract_report


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--split", choices=("train", "dev", "test"), default="train")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--acknowledge-final-test", action="store_true")
    args = parser.parse_args(argv)

    try:
        records = load_evaluation_records(
            args.dataset,
            split=args.split,
            acknowledge_final_test=args.acknowledge_final_test,
        )
    except EvaluationAccessError as error:
        parser.error(str(error))

    scored = score_fact_contract_report(_read_json(args.report), records)
    _write_json(args.output, scored)
    print(json.dumps(scored["metrics"], ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
