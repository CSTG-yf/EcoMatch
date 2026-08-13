#!/usr/bin/env python3
"""Build a complete answer-fact spec from a reviewed replacement patch."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


class AnswerFactSpecBuildError(ValueError):
    pass


def _object(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise AnswerFactSpecBuildError(f"{label} cannot be read: {exc}") from exc
    if not isinstance(value, dict):
        raise AnswerFactSpecBuildError(f"{label} must be an object")
    return value


def build_answer_fact_spec(base_path: Path, patch_path: Path, output_path: Path) -> dict[str, Any]:
    base = _object(base_path, "base spec")
    patch = _object(patch_path, "replacement patch")
    if output_path.exists():
        raise AnswerFactSpecBuildError(f"output already exists: {output_path}")
    if patch.get("schemaVersion") != "1.0" or patch.get("baseTargetVersion") != base.get(
        "targetVersion"
    ):
        raise AnswerFactSpecBuildError("replacement patch does not match the base spec")
    replacements = patch.get("replacements")
    contracts = base.get("contracts")
    if not isinstance(replacements, list) or not replacements or not isinstance(contracts, list):
        raise AnswerFactSpecBuildError("contracts and replacements must be non-empty lists")
    replacement_by_id: dict[str, dict[str, Any]] = {}
    for replacement in replacements:
        sample_id = replacement.get("id") if isinstance(replacement, dict) else None
        if not isinstance(sample_id, str) or not sample_id or sample_id in replacement_by_id:
            raise AnswerFactSpecBuildError("replacement IDs must be unique non-empty strings")
        replacement_by_id[sample_id] = replacement
    base_ids = {contract.get("id") for contract in contracts if isinstance(contract, dict)}
    unknown = set(replacement_by_id) - base_ids
    if unknown:
        raise AnswerFactSpecBuildError(f"replacement IDs are absent from base: {sorted(unknown)}")
    merged = [replacement_by_id.get(contract.get("id"), contract) for contract in contracts]
    result = {
        "schemaVersion": "1.0",
        "parentVersion": patch.get("parentVersion"),
        "targetVersion": patch.get("targetVersion"),
        "contracts": merged,
    }
    if not all(isinstance(result[name], str) and result[name] for name in ("parentVersion", "targetVersion")):
        raise AnswerFactSpecBuildError("parentVersion and targetVersion are required")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--patch", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = build_answer_fact_spec(args.base, args.patch, args.output)
    print(json.dumps({"contractCount": len(result["contracts"]), "version": result["targetVersion"]}))


if __name__ == "__main__":
    main()
