#!/usr/bin/env python3
"""Create an auditable DATA-02 release manifest after all gates pass."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from validate_dataset import validate_dataset
from validate_gold import validate_gold_dataset


RELEASE_FILES = (
    "schema.json",
    "manifest.json",
    "gold_manifest.json",
    "train.jsonl",
    "dev.jsonl",
    "test.jsonl",
    "augmentation.jsonl",
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def freeze_dataset(dataset_path: Path | str, database_path: Path | str) -> dict[str, Any]:
    """Validate DATA-02 and write its immutable-content release manifest."""

    dataset_path = Path(dataset_path).resolve()
    database_path = Path(database_path).resolve()
    dataset_report = validate_dataset(dataset_path)
    gold_report = validate_gold_dataset(dataset_path, database_path)
    content_hashes = {filename: _sha256(dataset_path / filename) for filename in RELEASE_FILES}
    source_manifest = json.loads((dataset_path / "manifest.json").read_text(encoding="utf-8"))
    release = {
        "version": "0.1.0",
        "sourceSha256": source_manifest["sourceSha256"],
        "officialCount": dataset_report["officialCount"],
        "augmentationCount": dataset_report["augmentationCount"],
        "sourceSplitCounts": dataset_report["sourceSplitCounts"],
        "evaluationSplitCounts": dataset_report["evaluationSplitCounts"],
        "goldValidation": gold_report,
        "contentSha256": content_hashes,
        "accessPolicy": {
            "training": ["train.jsonl", "dev.jsonl"],
            "heldOutGold": ["test.jsonl"],
            "excludedByDefault": ["test.jsonl", "augmentation.jsonl"],
        },
    }
    (dataset_path / "release_manifest.json").write_text(
        json.dumps(release, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return release


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("database", type=Path)
    args = parser.parse_args()
    print(json.dumps(freeze_dataset(args.dataset, args.database), ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
