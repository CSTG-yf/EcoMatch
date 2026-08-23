#!/usr/bin/env python3
"""Run the existing SuperSonic Agent chain on the isolated synthetic-360 set."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from run_supersonic_eval import _http_post_json, run_supersonic_evaluation  # noqa: E402

try:
    from .validate_synthetic_facts import validate_release  # type: ignore[import-not-found]
except ImportError:  # pragma: no cover - direct script execution
    from validate_synthetic_facts import validate_release  # type: ignore[no-redef]


class SyntheticRunError(ValueError):
    """Synthetic evaluation inputs are invalid."""


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_runtime_receipt(
    release_dir: Path | str,
    receipt_path: Path | str,
    *,
    agent_id: int,
) -> dict[str, Any]:
    """Fail closed unless the Agent receipt matches this exact package.

    The receipt is secret-free evidence produced by
    ``bootstrap_synthetic_agent.py``.  It proves the selected model/database
    binding and semantic import counts; it does not claim an official score.
    """

    release = Path(release_dir).resolve()
    receipt_file = Path(receipt_path).resolve()
    try:
        manifest = json.loads((release / "manifest.json").read_text(encoding="utf-8"))
        receipt = json.loads(receipt_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SyntheticRunError(f"cannot read synthetic runtime receipt: {receipt_file}") from error
    if not isinstance(manifest, dict) or not isinstance(receipt, dict):
        raise SyntheticRunError("synthetic manifest and runtime receipt must be JSON objects")

    report = validate_release(release)
    if receipt.get("receiptSchemaVersion") != "1.0":
        raise SyntheticRunError("runtime receipt schemaVersion is invalid")
    if receipt.get("dataOrigin") != "SYNTHETIC":
        raise SyntheticRunError("runtime receipt dataOrigin is invalid")
    if receipt.get("datasetVersion") != manifest.get("version"):
        raise SyntheticRunError("runtime receipt datasetVersion does not match release")
    if receipt.get("manifestSha256") != _sha256(release / "manifest.json"):
        raise SyntheticRunError("runtime receipt manifestSha256 does not match release")
    if receipt.get("agentId") != agent_id:
        raise SyntheticRunError("runtime receipt agentId does not match --agent-id")
    if receipt.get("physicalDatabaseLoad") != "PRELOADED_BY_CALLER":
        raise SyntheticRunError("runtime receipt physicalDatabaseLoad is invalid")
    for key in ("dataSetId", "modelId", "databaseId"):
        value = receipt.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise SyntheticRunError(f"runtime receipt {key} is invalid")
    model_binding = receipt.get("modelBinding")
    if not isinstance(model_binding, dict) or model_binding.get("modelId") != receipt["modelId"]:
        raise SyntheticRunError("runtime receipt modelBinding is invalid")
    if model_binding.get("databaseId") != receipt["databaseId"]:
        raise SyntheticRunError("runtime receipt model/database binding is inconsistent")

    expected_counts = {"organizations": 13, "indicators": 360, "factsValidated": 79_560}
    if receipt.get("semanticImport") != expected_counts:
        raise SyntheticRunError("runtime receipt semanticImport counts are invalid")
    if receipt.get("counts") != report["counts"]:
        raise SyntheticRunError("runtime receipt release counts do not match package")
    package_files = receipt.get("packageFiles")
    manifest_files = manifest.get("files")
    if not isinstance(package_files, dict) or not isinstance(manifest_files, dict):
        raise SyntheticRunError("runtime receipt packageFiles are invalid")
    for name in ("bank.sqlite", "bank-h2.sql"):
        entry = package_files.get(name)
        manifest_entry = manifest_files.get(name)
        path = release / name
        if (
            not isinstance(entry, dict)
            or not isinstance(manifest_entry, dict)
            or entry.get("bytes") != manifest_entry.get("bytes")
            or entry.get("sha256") != manifest_entry.get("sha256")
            or entry.get("bytes") != path.stat().st_size
            or entry.get("sha256") != _sha256(path)
        ):
            raise SyntheticRunError(f"runtime receipt package file mismatch: {name}")

    return {
        "receiptSchemaVersion": receipt["receiptSchemaVersion"],
        "datasetVersion": receipt["datasetVersion"],
        "manifestSha256": receipt["manifestSha256"],
        "agentId": agent_id,
        "dataSetId": receipt["dataSetId"],
        "modelId": receipt["modelId"],
        "databaseId": receipt["databaseId"],
        "semanticImport": expected_counts,
        "physicalDatabaseLoad": receipt["physicalDatabaseLoad"],
    }


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
    parser.add_argument(
        "--runtime-receipt",
        type=Path,
        help="Secret-free receipt from bootstrap_synthetic_agent.py; required for non-dry runs",
    )
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    if args.max_records is not None and args.max_records < 1:
        parser.error("--max-records must be positive")
    if not args.dry_run and args.output is None:
        parser.error("--output is required unless --dry-run is used")
    if not args.dry_run and args.runtime_receipt is None:
        parser.error("--runtime-receipt is required unless --dry-run is used")
    runtime_receipt = (
        verify_runtime_receipt(args.release_dir, args.runtime_receipt, agent_id=args.agent_id)
        if args.runtime_receipt is not None
        else None
    )
    gold = _read_jsonl(args.release_dir / "questions.jsonl")
    blind = _read_jsonl(args.release_dir / "questions_blind.jsonl")
    records = build_blind_split(gold, blind, split=args.split)
    if args.max_records is not None:
        records = records[: args.max_records]
    if args.dry_run:
        output = {
            "status": "DRY_RUN",
            "split": args.split,
            "count": len(records),
            "fields": sorted(records[0]) if records else [],
        }
        if runtime_receipt is not None:
            output["runtimeReceipt"] = runtime_receipt
        print(json.dumps(output, ensure_ascii=False))
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
        "runtimeReceipt": runtime_receipt,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"recordCount": report["recordCount"], "metrics": report["metrics"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
