#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from build_answer_fact_spec import AnswerFactSpecBuildError, build_answer_fact_spec  # noqa: E402


class BuildAnswerFactSpecTest(unittest.TestCase):
    def test_replaces_only_declared_contracts_and_preserves_order(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            base = root / "base.json"
            patch = root / "patch.json"
            output = root / "out.json"
            base.write_text(json.dumps({"targetVersion":"1.0.0","contracts":[{"id":"A","v":1},{"id":"B","v":1}]}), encoding="utf-8")
            patch.write_text(json.dumps({"schemaVersion":"1.0","baseTargetVersion":"1.0.0","parentVersion":"1.0.1","targetVersion":"1.0.2","replacements":[{"id":"B","v":2}]}), encoding="utf-8")

            result = build_answer_fact_spec(base, patch, output)

            self.assertEqual([item["id"] for item in result["contracts"]], ["A", "B"])
            self.assertEqual(result["contracts"][1]["v"], 2)

    def test_rejects_unknown_replacement_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            base = root / "base.json"
            patch = root / "patch.json"
            output = root / "out.json"
            base.write_text(json.dumps({"targetVersion":"1.0.0","contracts":[{"id":"A"}]}), encoding="utf-8")
            patch.write_text(json.dumps({"schemaVersion":"1.0","baseTargetVersion":"1.0.0","parentVersion":"1.0.1","targetVersion":"1.0.2","replacements":[{"id":"B"}]}), encoding="utf-8")

            with self.assertRaises(AnswerFactSpecBuildError):
                build_answer_fact_spec(base, patch, output)

            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
