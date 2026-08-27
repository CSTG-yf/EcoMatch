#!/usr/bin/env python3
"""Dataset reader shared by the Bank NL2SQL evaluation scripts."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class EvaluationAccessError(PermissionError):
    """An evaluation run attempted to use a split outside its authorization."""


_SUPPORTED_SPLITS = {"train", "dev", "test"}


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise EvaluationAccessError(f"Dataset file does not exist: {path.name}")
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_evaluation_records(
    dataset_path: Path | str,
    *,
    split: str,
) -> list[dict[str, Any]]:
    """Read one named evaluation split."""

    normalized_split = split.strip().lower()
    dataset_root = Path(dataset_path).resolve()
    if normalized_split in _SUPPORTED_SPLITS:
        return _read_jsonl(dataset_root / f"{normalized_split}.jsonl")
    raise EvaluationAccessError(f"Unsupported evaluation split: {split}")
