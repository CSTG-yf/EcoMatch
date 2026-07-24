#!/usr/bin/env python3
"""Access policy and audit registry for DATA-02 evaluation runs."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class EvaluationAccessError(PermissionError):
    """An evaluation run attempted to use a split outside its authorization."""


_DEVELOPMENT_SPLITS = {"train", "dev"}
_FINAL_SPLIT = "test"


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise EvaluationAccessError(f"Dataset file does not exist: {path.name}")
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_evaluation_records(
    dataset_path: Path | str,
    *,
    split: str,
    acknowledge_final_test: bool = False,
) -> list[dict[str, Any]]:
    """Read one approved split for a concrete evaluation run.

    Development runs can read only train or dev.  Reading the frozen test set
    requires an explicit final-evaluation acknowledgement; callers must also
    register that run with :func:`record_final_test_run`.
    """

    normalized_split = split.strip().lower()
    dataset_root = Path(dataset_path).resolve()
    if normalized_split in _DEVELOPMENT_SPLITS:
        return _read_jsonl(dataset_root / f"{normalized_split}.jsonl")
    if normalized_split == _FINAL_SPLIT:
        if not acknowledge_final_test:
            raise EvaluationAccessError(
                "Reading frozen test gold requires acknowledge_final_test=True"
            )
        return _read_jsonl(dataset_root / "test.jsonl")
    raise EvaluationAccessError(f"Unsupported evaluation split: {split}")


def record_final_test_run(
    registry_path: Path | str,
    *,
    run_metadata: dict[str, Any],
) -> dict[str, Any]:
    """Append a final-test run to a local audit registry and return its entry."""

    path = Path(registry_path).resolve()
    if path.exists():
        try:
            registry = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise EvaluationAccessError(f"Invalid final-test run registry: {path}") from error
    else:
        registry = {"runs": []}
    if not isinstance(registry, dict) or not isinstance(registry.get("runs"), list):
        raise EvaluationAccessError(f"Invalid final-test run registry structure: {path}")

    entry = dict(run_metadata)
    entry["runNumber"] = len(registry["runs"]) + 1
    registry["runs"].append(entry)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(registry, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return entry
