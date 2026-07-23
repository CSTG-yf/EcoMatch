#!/usr/bin/env python3
"""Validate DATA-01 structure, IDs and template isolation."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).parent
REQUIRED = {
    "id", "split", "question", "scene", "intent", "metrics", "dimensions", "time",
    "organizations", "filters", "linguisticFeatures", "clarificationExpected", "templateGroup",
}


def main() -> None:
    records = {}
    templates = {}
    errors = []
    for split in ("train", "dev", "test"):
        records[split] = []
        templates[split] = set()
        for line_number, line in enumerate((ROOT / f"{split}.jsonl").read_text(encoding="utf-8").splitlines(), 1):
            item = json.loads(line)
            missing = REQUIRED - item.keys()
            if missing:
                errors.append(f"{split}:{line_number} missing={sorted(missing)}")
            if item.get("split") != split:
                errors.append(f"{split}:{line_number} split mismatch")
            records[split].append(item)
            templates[split].add(item.get("templateGroup"))

    ids = [item["id"] for values in records.values() for item in values]
    if len(ids) != len(set(ids)):
        errors.append("duplicate sample id")
    for left, right in (("train", "dev"), ("train", "test"), ("dev", "test")):
        overlap = templates[left] & templates[right]
        if overlap:
            errors.append(f"template leakage {left}/{right}: {sorted(overlap)}")
    if len(records["test"]) < 40:
        errors.append("frozen test set contains fewer than 40 cases")
    if errors:
        raise SystemExit("\n".join(errors))
    print(json.dumps({"result": "PASS", "counts": {key: len(value) for key, value in records.items()},
                      "templateOverlap": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
