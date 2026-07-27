#!/usr/bin/env python3
"""Contract tests for DATA-03 workbook integration."""

from __future__ import annotations

import importlib.util
import unittest
from decimal import Decimal
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("bank_chart_build_dataset", ROOT / "build_dataset.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Unable to load DATA-03 builder")
BUILD_DATASET = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD_DATASET)


class BuildDatasetTest(unittest.TestCase):
    def test_creates_legacy_query_database_from_shared_workbook_rows(self) -> None:
        connection = BUILD_DATASET.create_database(
            [("ORG001", "Bank A")],
            [("ZB001", "Deposits", "Deposit balance", "CNY")],
            [("2026-04-30", "ORG001", "ZB001", Decimal("12.345678"))],
        )
        try:
            row = connection.execute(
                "SELECT organization_code, metric_code, metric_value FROM bank_indicator_fact"
            ).fetchone()
            self.assertEqual(row, ("ORG001", "ZB001", 12.345678))
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
