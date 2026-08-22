#!/usr/bin/env python3
"""Run the existing SuperSonic Agent chain on the isolated synthetic-360 set."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from run_supersonic_eval import _http_post_json, run_supersonic_evaluation  # noqa: E402


class SyntheticRunError(ValueError):
    """Synthetic evaluation inputs are invalid."""


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def build_blind_split(
    gold_records: list[dict[str, Any]],
    blind_records: list[dict[str, Any]],
    *,
    split: str,
) -> list[dict[str, str]]:
    if split not in {"train", "dev", "test"}:
        raise SyntheticRunError("split must be train, dev, or test")
    selected_ids = [record["id"] for record in gold_records if record.get("split") == split]
    blind_by_id: dict[str, dict[str, Any]] = {}
    for record in blind_records:
        if set(record) != {"id", "question"}:
            raise SyntheticRunError("blind records must contain only id and question")
        if record["id"] in blind_by_id:
            raise SyntheticRunError(f"duplicate blind id: {record['id']}")
        blind_by_id[record["id"]] = record
    missing = [sample_id for sample_id in selected_ids if sample_id not in blind_by_id]
    if missing:
        raise SyntheticRunError(f"blind file is missing IDs: {', '.join(missing[:5])}")
    return [
        {"id": sample_id, "question": str(blind_by_id[sample_id]["question"])}
        for sample_id in selected_ids
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    parser.add_argument("--split", choices=("train", "dev", "test"), default="dev")
    parser.add_argument("--max-records", type=int)
    parser.add_argument("--base-url", default="http://127.0.0.1:9080")
    parser.add_argument("--agent-id", type=int, required=True)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=int, default=120)
    parser.add_argument("--summary-timeout-seconds", type=float, default=120)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    if args.max_records is not None and args.max_records < 1:
        parser.error("--max-records must be positive")
    if not args.dry_run and args.output is None:
        parser.error("--output is required unless --dry-run is used")
    gold = _read_jsonl(args.release_dir / "questions.jsonl")
    blind = _read_jsonl(args.release_dir / "questions_blind.jsonl")
    records = build_blind_split(gold, blind, split=args.split)
    if args.max_records is not None:
        records = records[: args.max_records]
    if args.dry_run:
        print(json.dumps({"status": "DRY_RUN", "split": args.split, "count": len(records), "fields": sorted(records[0]) if records else []}, ensure_ascii=False))
        return 0

    report = run_supersonic_evaluation(
        records,
        agent_id=args.agent_id,
        post_json=_http_post_json(
            base_url=args.base_url,
            authorization_token=None,
            cookie=None,
            timeout_seconds=args.timeout_seconds,
            network_retries=1,
            retry_backoff_seconds=0.5,
        ),
        concurrency=args.concurrency,
        summary_timeout_seconds=args.summary_timeout_seconds,
        result_only=True,
    )
    report["run"] = {
        "dataset": "synthetic_360",
        "dataOrigin": "SYNTHETIC",
        "split": args.split,
        "agentId": args.agent_id,
        "baseUrl": args.base_url,
        "requestedCount": len(records),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"recordCount": report["recordCount"], "metrics": report["metrics"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
