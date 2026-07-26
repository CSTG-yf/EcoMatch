#!/usr/bin/env python3
"""Verify every stored physical gold SQL statement against the H2 benchmark."""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path


SPLITS = ("train", "dev", "test")


def validate_gold_h2(
    dataset_path: Path | str,
    java_path: Path | str,
    h2_jar_path: Path | str,
    jdbc_url: str,
    user: str = "root",
    password: str = "semantic",
) -> dict[str, object]:
    dataset_path = Path(dataset_path).resolve()
    java_path = Path(java_path).resolve()
    h2_jar_path = Path(h2_jar_path).resolve()
    if not java_path.is_file() or not h2_jar_path.is_file():
        raise FileNotFoundError("Java executable or H2 jar is unavailable")
    statements: list[str] = []
    for split in SPLITS:
        for line in (dataset_path / f"{split}.jsonl").read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            record = json.loads(line)
            sql = record.get("sql")
            if not isinstance(sql, str) or not sql.strip():
                raise ValueError(f"{record.get('id')}: missing physical SQL")
            statements.append(sql.rstrip().rstrip(";") + ";")
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".sql", delete=False) as script:
        script_path = Path(script.name)
        script.write("\n".join(statements) + "\n")
    try:
        completed = subprocess.run(
            [
                str(java_path),
                "-cp",
                str(h2_jar_path),
                "org.h2.tools.RunScript",
                "-url",
                jdbc_url,
                "-user",
                user,
                "-password",
                password,
                "-script",
                str(script_path),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if completed.returncode:
            raise RuntimeError(completed.stderr.strip() or completed.stdout.strip())
    finally:
        script_path.unlink(missing_ok=True)
    return {"result": "PASS", "h2SqlExecutionCount": len(statements), "jdbcUrl": jdbc_url}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--java-path", type=Path, required=True)
    parser.add_argument("--h2-jar-path", type=Path, required=True)
    parser.add_argument("--jdbc-url", required=True)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="semantic")
    args = parser.parse_args()
    result = validate_gold_h2(
        args.dataset, args.java_path, args.h2_jar_path, args.jdbc_url, args.user, args.password
    )
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
