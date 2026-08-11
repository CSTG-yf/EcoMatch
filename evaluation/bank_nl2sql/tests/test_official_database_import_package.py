#!/usr/bin/env python3
"""Contract tests for the immutable official database import package (v2.0.2).

The package lives in evaluation/bank_nl2sql/db/releases/2.0.2/ and is a frozen,
Git-tracked companion for the official Bank NL2SQL v2.0.2 workbook. It is NOT a
runtime semantic.mv.db: the runtime database is produced on demand by
db/Import-OfficialBankData.ps1, which verifies the manifest and artifact hashes
before touching the target and applies only the packaged bank_* benchmark
tables/views.

The live H2 end-to-end subtest runs against an isolated disposable H2 target
when an H2 jar is available (ECOMATCH_H2_JAR is the primary signal; project-local
discovery in <repo>/.local-dev or the user Maven repository is the fallback).
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

BANK_NL2SQL_DIR = Path(__file__).resolve().parents[1]
DB_DIR = BANK_NL2SQL_DIR / "db"
REPO_ROOT = Path(__file__).resolve().parents[3]
RELEASE_DIR = DB_DIR / "releases" / "2.0.2"
IMPORTER = DB_DIR / "Import-OfficialBankData.ps1"
CMD_WRAPPER = DB_DIR / "Import-OfficialBankData.cmd"
WORKBOOK = (
    REPO_ROOT
    / "evaluation/bank_nl2sql/official/2.0.2/bank-nl2sql-ground-truth-v2.0.2.xlsx"
)

EXPECTED_SCHEMA_VERSION = "1.0"
EXPECTED_OFFICIAL_VERSION = "2.0.2"
EXPECTED_SOURCE_RELATIVE_PATH = (
    "evaluation/bank_nl2sql/official/2.0.2/bank-nl2sql-ground-truth-v2.0.2.xlsx"
)
EXPECTED_COUNTS = {"organizations": 13, "metrics": 21, "facts": 132678}
EXPECTED_ARTIFACTS = ("bank.sqlite", "bank-h2.sql")
H2_COUNT_SCRIPT = """\
SELECT 'ORG_COUNT=' || COUNT(*) FROM bank_organization;
SELECT 'METRIC_COUNT=' || COUNT(*) FROM bank_metric_definition;
SELECT 'FACT_COUNT=' || COUNT(*) FROM bank_metric_daily;
"""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _executable_powershell(text: str) -> str:
    """Return only the executable PowerShell lines of the importer.

    Comment lines (both ``#`` line comments and the ``<# ... #>`` help block)
    are excluded so that forbidden-command contract checks inspect what the
    importer actually executes, not prose that explains what the importer must
    never do.
    """
    lines: list[str] = []
    in_block_comment = False
    for line in text.splitlines():
        stripped = line.lstrip()
        if in_block_comment:
            if "#>" in stripped:
                in_block_comment = False
            continue
        if stripped.startswith("<#"):
            if "#>" not in stripped:
                in_block_comment = True
            continue
        if stripped.startswith("#"):
            continue
        lines.append(line)
    return "\n".join(lines)


def _read_manifest() -> dict[str, Any]:
    manifest_path = RELEASE_DIR / "database-manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"database-manifest.json missing: {manifest_path}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError(f"database-manifest.json is not valid JSON: {error}") from error
    if not isinstance(manifest, dict):
        raise ValueError("database-manifest.json root must be an object")
    return manifest


def _expected_manifest() -> dict[str, Any]:
    """Build the manifest the release must declare, computed from the files."""
    artifacts: dict[str, dict[str, Any]] = {}
    for name in EXPECTED_ARTIFACTS:
        path = RELEASE_DIR / name
        if not path.is_file():
            raise FileNotFoundError(f"release artifact missing: {path}")
        artifacts[name] = {"sha256": _sha256(path), "bytes": path.stat().st_size}
    connection = sqlite3.connect(RELEASE_DIR / "bank.sqlite")
    try:
        date_min = connection.execute("SELECT MIN(data_date) FROM bank_metric_daily").fetchone()[0]
        date_max = connection.execute("SELECT MAX(data_date) FROM bank_metric_daily").fetchone()[0]
    finally:
        connection.close()
    return {
        "schemaVersion": EXPECTED_SCHEMA_VERSION,
        "packageName": "bank-nl2sql-official-db-import-package",
        "officialVersion": EXPECTED_OFFICIAL_VERSION,
        "source": {
            "officialVersion": EXPECTED_OFFICIAL_VERSION,
            "path": EXPECTED_SOURCE_RELATIVE_PATH,
            "sha256": _sha256(WORKBOOK),
            "dateRange": {"min": date_min, "max": date_max},
        },
        "artifacts": artifacts,
        "counts": dict(EXPECTED_COUNTS),
    }


class OfficialDatabaseImportPackageTest(unittest.TestCase):
    def test_release_assets_exist(self) -> None:
        for name in (*EXPECTED_ARTIFACTS, "database-manifest.json"):
            self.assertTrue(
                (RELEASE_DIR / name).is_file(),
                f"missing release asset: {RELEASE_DIR / name}",
            )
        self.assertTrue(IMPORTER.is_file(), f"missing importer: {IMPORTER}")
        self.assertTrue(CMD_WRAPPER.is_file(), f"missing cmd wrapper: {CMD_WRAPPER}")
        self.assertEqual(
            {path.name for path in RELEASE_DIR.iterdir()},
            set(EXPECTED_ARTIFACTS) | {"database-manifest.json"},
            "release directory must contain exactly the immutable package files",
        )

    def test_manifest_declares_source_artifacts_and_counts(self) -> None:
        try:
            expected = _expected_manifest()
        except FileNotFoundError as error:
            self.fail(f"cannot verify manifest before release assets exist: {error}")
        try:
            manifest = _read_manifest()
        except (FileNotFoundError, ValueError) as error:
            self.fail(
                f"{error}\nExpected manifest (computed from release files):\n"
                f"{json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=True)}"
            )
        self.assertEqual(manifest["schemaVersion"], EXPECTED_SCHEMA_VERSION)
        self.assertEqual(manifest["officialVersion"], EXPECTED_OFFICIAL_VERSION)
        self.assertEqual(manifest["source"]["officialVersion"], EXPECTED_OFFICIAL_VERSION)
        self.assertEqual(manifest["source"]["path"], EXPECTED_SOURCE_RELATIVE_PATH)
        self.assertEqual(manifest["source"]["sha256"].lower(), _sha256(WORKBOOK))
        self.assertEqual(manifest["counts"], EXPECTED_COUNTS)

    def test_artifact_hashes_match_manifest(self) -> None:
        manifest = _read_manifest()
        self.assertEqual(set(manifest["artifacts"]), set(EXPECTED_ARTIFACTS))
        for name in EXPECTED_ARTIFACTS:
            path = RELEASE_DIR / name
            self.assertTrue(path.is_file(), f"missing release artifact: {path}")
            declared = manifest["artifacts"][name]
            self.assertEqual(
                declared["sha256"].lower(),
                _sha256(path),
                f"manifest SHA-256 mismatch for {name}",
            )
            self.assertEqual(declared["bytes"], path.stat().st_size, f"byte count mismatch for {name}")

    def test_sqlite_row_counts_and_date_range(self) -> None:
        manifest = _read_manifest()
        database_path = RELEASE_DIR / "bank.sqlite"
        connection = sqlite3.connect(database_path)
        try:
            actual_counts = {
                "organizations": connection.execute("SELECT COUNT(*) FROM bank_organization").fetchone()[0],
                "metrics": connection.execute("SELECT COUNT(*) FROM bank_metric_definition").fetchone()[0],
                "facts": connection.execute("SELECT COUNT(*) FROM bank_metric_daily").fetchone()[0],
            }
            date_min = connection.execute("SELECT MIN(data_date) FROM bank_metric_daily").fetchone()[0]
            date_max = connection.execute("SELECT MAX(data_date) FROM bank_metric_daily").fetchone()[0]
            date_count = int(
                connection.execute("SELECT COUNT(DISTINCT data_date) FROM bank_metric_daily").fetchone()[0]
            )
        finally:
            connection.close()
        self.assertEqual(actual_counts, EXPECTED_COUNTS)
        self.assertEqual(manifest["source"]["dateRange"], {"min": date_min, "max": date_max})
        self.assertEqual(
            actual_counts["facts"],
            actual_counts["organizations"] * actual_counts["metrics"] * date_count,
            "facts must form a complete organization x metric cube for every date",
        )

    def test_h2_script_schema_and_views(self) -> None:
        script = (RELEASE_DIR / "bank-h2.sql").read_text(encoding="utf-8")
        self.assertTrue(script.startswith("BEGIN;\n"))
        self.assertTrue(script.endswith("COMMIT;\n"))
        self.assertIn("CREATE SCHEMA IF NOT EXISTS bank_benchmark;", script)
        self.assertIn("DROP VIEW IF EXISTS bank_benchmark.bank_metric_daily;", script)
        self.assertIn("DROP VIEW IF EXISTS bank_benchmark.bank_metric_definition;", script)
        self.assertIn("DROP VIEW IF EXISTS bank_benchmark.bank_organization;", script)
        self.assertEqual(script.count("DROP TABLE IF EXISTS bank_"), 3)
        self.assertEqual(script.count("CREATE TABLE bank_"), 3)
        self.assertIn(
            "CREATE VIEW bank_benchmark.bank_organization AS SELECT * FROM PUBLIC.bank_organization;",
            script,
        )
        self.assertIn(
            "CREATE VIEW bank_benchmark.bank_metric_definition AS SELECT * FROM PUBLIC.bank_metric_definition;",
            script,
        )
        self.assertIn(
            "CREATE VIEW bank_benchmark.bank_metric_daily AS SELECT * FROM PUBLIC.bank_metric_daily;",
            script,
        )
        self.assertEqual(script.count("INSERT INTO bank_organization("), 13)
        self.assertEqual(script.count("INSERT INTO bank_metric_definition("), 21)
        self.assertEqual(script.count("INSERT INTO bank_metric_daily("), 132678)

    def test_importer_safety_markers(self) -> None:
        text = IMPORTER.read_text(encoding="utf-8")
        # Forbidden-command checks inspect the executable PowerShell code, not
        # the comments (comments may legitimately describe what the importer
        # must never do without ever executing it).
        code = _executable_powershell(text)
        # Safety markers are explicit, greppable contract points.
        self.assertIn("# SAFETY 1:", text)
        self.assertIn("# SAFETY 2:", text)
        self.assertIn("# SAFETY 3:", text)
        self.assertIn("# SAFETY 4:", text)
        # Never manages processes.
        self.assertNotIn("Stop-Process", code)
        self.assertNotIn("taskkill", code.lower())
        # Never deletes files (not even temporary database files).
        self.assertNotIn("Remove-Item", code)
        # Refuses an active/locked target database instead of touching it.
        self.assertIn("lock.db", code)
        # Defaults to the repository-local semantic H2 base path.
        self.assertIn('".local-dev\\state\\semantic"', code)
        # Explicit parameter surface.
        self.assertIn("TargetDatabase", code)
        self.assertIn("JavaPath", code)
        self.assertIn("H2JarPath", code)
        # Applies only the packaged bank benchmark script via RunScript with the
        # project-standard root/semantic credentials.
        self.assertIn("org.h2.tools.RunScript", code)
        self.assertIn('"-user"', code)
        self.assertIn("root", code)
        self.assertIn('"-password"', code)
        self.assertIn("semantic", code)
        # Verifies the three exact row counts after the import.
        self.assertIn("132678", code)
        self.assertIn("21", code)
        # Project-local toolchain discovery when parameters are omitted.
        self.assertIn("ECOMATCH_H2_JAR", code)
        self.assertIn(".local-dev\\jdk", code)
        # Hash verification uses the pure .NET streaming SHA-256, not the
        # Get-FileHash cmdlet.
        self.assertNotIn("Get-FileHash", code)
        self.assertIn("System.Security.Cryptography.SHA256", code)
        # The Java/H2 toolchain is resolved into the outer script scope before
        # Invoke-RunScript (function-local resolution would be invisible to it).
        self.assertIn("$toolchain = Resolve-Toolchain", code)
        self.assertIn("$JavaPath = $toolchain.JavaPath", code)
        self.assertIn("$H2JarPath = $toolchain.H2JarPath", code)
        # Row counts are read back from H2 (org.h2.tools.Shell) and compared in
        # PowerShell; no CASE/CAST verification that H2 eagerly compiles even
        # when the counts are correct.
        self.assertIn("org.h2.tools.Shell", code)
        self.assertNotIn("CASE WHEN", code)
        self.assertNotIn("CAST(", code)

        wrapper = CMD_WRAPPER.read_text(encoding="utf-8")
        self.assertIn("Import-OfficialBankData.ps1", wrapper)

    def test_importer_refuses_locked_target(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            target = temp_path / "semantic"
            (temp_path / "semantic.lock.db").write_bytes(b"locked")
            completed = self._run_importer(["-TargetDatabase", str(target)])
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("lock", completed.stdout.lower() + completed.stderr.lower())
            self.assertFalse(
                (temp_path / "semantic.mv.db").exists(),
                "importer must not create/overwrite a locked target database",
            )
            self.assertEqual(
                {path.name for path in temp_path.iterdir()},
                {"semantic.lock.db"},
                "importer must leave a refused target completely untouched",
            )

    def test_importer_imports_into_isolated_h2_target(self) -> None:
        h2_jar = self._resolve_h2_jar()
        java = self._resolve_java()
        if h2_jar is None or java is None:
            self.skipTest(
                "isolated H2 import check requires an H2 jar and a Java executable; "
                f"jar={h2_jar}, java={java} (set ECOMATCH_H2_JAR / JAVA_HOME or provide "
                "a project-local JDK under <repo>/.local-dev)"
            )
        os.environ["ECOMATCH_H2_JAR"] = str(h2_jar)
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            target = temp_path / "semantic"
            # Import twice: the importer must be idempotent for bank_* objects.
            for attempt in (1, 2):
                completed = self._run_importer(["-TargetDatabase", str(target)])
                self.assertEqual(
                    completed.returncode,
                    0,
                    f"import attempt {attempt} failed:\n{completed.stdout}\n{completed.stderr}",
                )
                output = completed.stdout + completed.stderr
                self.assertIn("organizations=13", output)
                self.assertIn("metrics=21", output)
                self.assertIn("facts=132678", output)
                self.assertIn("PASS", output.upper())
            self.assertTrue((temp_path / "semantic.mv.db").is_file())
            counts = self._query_h2_counts(java, h2_jar, target)
            self.assertEqual(counts, {"ORG_COUNT": 13, "METRIC_COUNT": 21, "FACT_COUNT": 132678})

    def _run_importer(self, arguments: list[str]) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(IMPORTER),
                *arguments,
            ],
            capture_output=True,
            text=True,
            timeout=900,
        )

    def _query_h2_counts(
        self, java: Path, h2_jar: Path, target: Path
    ) -> dict[str, int]:
        with tempfile.TemporaryDirectory() as temp_dir:
            script_path = Path(temp_dir) / "counts.sql"
            script_path.write_text(H2_COUNT_SCRIPT, encoding="utf-8")
            jdbc_url = "jdbc:h2:file:" + target.resolve().as_posix() + ";DATABASE_TO_UPPER=false"
            completed = subprocess.run(
                [
                    str(java),
                    "-cp",
                    str(h2_jar),
                    "org.h2.tools.RunScript",
                    "-url",
                    jdbc_url,
                    "-user",
                    "root",
                    "-password",
                    "semantic",
                    "-script",
                    str(script_path),
                    "-showResults",
                ],
                capture_output=True,
                text=True,
                timeout=900,
            )
        self.assertEqual(
            completed.returncode,
            0,
            f"H2 count query failed:\n{completed.stdout}\n{completed.stderr}",
        )
        output = completed.stdout + completed.stderr
        counts = {
            name: int(match.group(1))
            for name, token in (
                ("ORG_COUNT", "ORG_COUNT"),
                ("METRIC_COUNT", "METRIC_COUNT"),
                ("FACT_COUNT", "FACT_COUNT"),
            )
            for match in [re.search(re.escape(token) + r"=(\d+)", output)]
            if match
        }
        self.assertEqual(
            set(counts),
            {"ORG_COUNT", "METRIC_COUNT", "FACT_COUNT"},
            f"could not parse all H2 count tokens from RunScript output:\n{output}",
        )
        return counts

    def _resolve_h2_jar(self) -> Path | None:
        env_jar = os.environ.get("ECOMATCH_H2_JAR")
        if env_jar and Path(env_jar).is_file():
            return Path(env_jar).resolve()
        candidates: list[Path] = []
        candidates.extend(
            sorted(
                (REPO_ROOT / ".local-dev/m2-repository/com/h2database/h2").glob("*/h2-*.jar")
            )
        )
        candidates.extend(sorted((REPO_ROOT / ".local-dev/h2").glob("h2-*.jar")))
        candidates.extend(
            sorted(Path.home().glob(".m2/repository/com/h2database/h2/*/h2-*.jar"))
        )
        return candidates[-1] if candidates else None

    def _resolve_java(self) -> Path | None:
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            candidate = Path(java_home) / "bin/java.exe"
            if candidate.is_file():
                return candidate.resolve()
        candidates = sorted((REPO_ROOT / ".local-dev/jdk").glob("*/bin/java.exe"))
        if candidates:
            return candidates[-1].resolve()
        on_path = subprocess.run(
            ["where.exe", "java"], capture_output=True, text=True, timeout=30
        )
        if on_path.returncode == 0 and on_path.stdout.strip():
            return Path(on_path.stdout.splitlines()[0].strip()).resolve()
        return None


if __name__ == "__main__":
    sys.exit(unittest.main())
