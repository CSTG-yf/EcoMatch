#!/usr/bin/env python3
"""Purpose-scoped readers for frozen DATA-02 files.

The default training scope exposes only train/dev annotations.  A caller must
explicitly acknowledge that it is performing blind evaluation before it may
read held-out test gold records.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class DatasetAccessError(PermissionError):
    """A caller requested a dataset split outside its allowed purpose."""


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise DatasetAccessError(f"Dataset file does not exist: {path.name}")
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_records(
    dataset_path: Path | str,
    *,
    purpose: str,
    allow_test_gold: bool = False,
) -> list[dict[str, Any]]:
    """Load only splits authorized for a concrete purpose.

    ``training`` gets train/dev only. ``evaluation`` requires an explicit
    ``allow_test_gold=True`` acknowledgement and gets test only.  Augmentation
    records intentionally remain outside both default scopes.
    """

    dataset_path = Path(dataset_path).resolve()
    if purpose == "training":
        return _read_jsonl(dataset_path / "train.jsonl") + _read_jsonl(dataset_path / "dev.jsonl")
    if purpose == "evaluation":
        if not allow_test_gold:
            raise DatasetAccessError("Reading test gold requires allow_test_gold=True")
        return _read_jsonl(dataset_path / "test.jsonl")
    raise DatasetAccessError(f"Unsupported dataset access purpose: {purpose}")
