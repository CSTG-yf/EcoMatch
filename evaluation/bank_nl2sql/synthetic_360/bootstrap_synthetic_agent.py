#!/usr/bin/env python3
"""Bind the synthetic-360 semantic workbook to an isolated SuperSonic Agent.

The physical H2 database is intentionally not copied by this command.  The
caller must first load ``bank-h2.sql`` into the database selected by
``--database-id``.  This command then verifies that ``--model-id`` points to
that database, imports the generated semantic workbook, upserts a dedicated
Agent, and writes a secret-free runtime receipt for ``run_eval.py``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any

try:
    from evaluation.bank_nl2sql.bootstrap_bank_agent import (
        ApiClient,
        BankAgentBootstrapError,
        _find_unique,
        _positive_id,
        build_agent_payload,
        resolve_auth_token,
    )
    from evaluation.bank_nl2sql.synthetic_360.build_import_workbook import build_workbook
    from evaluation.bank_nl2sql.synthetic_360.validate_synthetic_facts import validate_release
except ModuleNotFoundError:  # pragma: no cover - direct script execution
    ROOT = Path(__file__).resolve().parents[3]
    if str(ROOT) not in sys.path:
        sys.path.insert(0, str(ROOT))
    BANK_ROOT = ROOT / "evaluation" / "bank_nl2sql"
    if str(BANK_ROOT) not in sys.path:
        sys.path.insert(0, str(BANK_ROOT))
    from evaluation.bank_nl2sql.bootstrap_bank_agent import (  # type: ignore[no-redef]
        ApiClient,
        BankAgentBootstrapError,
        _find_unique,
        _positive_id,
        build_agent_payload,
        resolve_auth_token,
    )
    from evaluation.bank_nl2sql.synthetic_360.build_import_workbook import build_workbook  # type: ignore[no-redef]
    from evaluation.bank_nl2sql.synthetic_360.validate_synthetic_facts import validate_release  # type: ignore[no-redef]


DEFAULT_BASE_URL = "http://127.0.0.1:9080"
DEFAULT_TOKEN_ENV = "ECOMATCH_AUTH_TOKEN"
DEFAULT_ADMIN_PASSWORD_ENV = "ECOMATCH_ADMIN_PASSWORD"
DEFAULT_ADMIN_NAME = "admin"
DEFAULT_ADMIN_PASSWORD = "123456"
DEFAULT_AGENT_NAME = "银行问数-SYNTHETIC-360"
DEFAULT_DATASET_NAME = "银行问数合成360指标评测数据集"
DEFAULT_DATASET_BIZ_NAME = "bank_synthetic_360_dataset"
DEFAULT_DATE_FIELD = "data_date"
DEFAULT_ORGANIZATION_FIELD = "org_code"
DEFAULT_INDICATOR_CODE_FIELD = "metric_code"
DEFAULT_INDICATOR_VALUE_FIELD = "metric_value"
RECEIPT_SCHEMA_VERSION = "1.0"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _read_manifest(release_dir: Path) -> dict[str, Any]:
    try:
        value = json.loads((release_dir / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BankAgentBootstrapError(f"cannot read synthetic manifest: {release_dir}") from error
    if not isinstance(value, dict):
        raise BankAgentBootstrapError("synthetic manifest must be a JSON object")
    return value


def _verify_model_database(client: ApiClient, model_id: int, database_id: int) -> dict[str, Any]:
    model = client.json("GET", f"/api/semantic/model/getModel/{model_id}")
    if not isinstance(model, dict):
        raise BankAgentBootstrapError("model lookup did not return an object")
    actual_database_id = model.get("databaseId")
    if actual_database_id != database_id:
        raise BankAgentBootstrapError(
            f"model {model_id} is bound to database {actual_database_id}, expected {database_id}"
        )
    return {
        "modelId": model_id,
        "databaseId": database_id,
        "modelName": model.get("name"),
        "modelBizName": model.get("bizName"),
    }


def _validate_import_report(import_report: Any) -> dict[str, int]:
    if not isinstance(import_report, dict) or import_report.get("success") is not True:
        raise BankAgentBootstrapError(f"synthetic semantic import failed: {import_report}")
    expected = {
        "organizations": import_report.get("organizationCount"),
        "indicators": import_report.get("indicatorCount"),
        "factsValidated": import_report.get("factCount"),
    }
    wanted = {"organizations": 13, "indicators": 360, "factsValidated": 79_560}
    if expected != wanted:
        raise BankAgentBootstrapError(f"synthetic import counts mismatch: {expected}; expected {wanted}")
    return {key: int(value) for key, value in expected.items()}


def bootstrap(
    release_dir: Path,
    *,
    base_url: str,
    token: str,
    model_id: int,
    database_id: int,
    chat_model_id: int | None,
    agent_name: str,
    workbook: Path | None,
) -> dict[str, Any]:
    report = validate_release(release_dir)
    manifest = _read_manifest(release_dir)
    model_binding: dict[str, Any]
    client = ApiClient(base_url, token)
    model_binding = _verify_model_database(client, model_id, database_id)

    temporary_workbook: tempfile.TemporaryDirectory[str] | None = None
    try:
        if workbook is None:
            temporary_workbook = tempfile.TemporaryDirectory(prefix="ecomatch-synthetic-360-")
            workbook = Path(temporary_workbook.name) / "synthetic-360.xlsx"
        else:
            workbook.parent.mkdir(parents=True, exist_ok=True)
        build_workbook(release_dir, workbook)

        import_report = client.multipart(
            "/api/semantic/bank/resources/import",
            {
                "modelId": str(model_id),
                "dataSetName": DEFAULT_DATASET_NAME,
                "dataSetBizName": DEFAULT_DATASET_BIZ_NAME,
                "dateField": DEFAULT_DATE_FIELD,
                "organizationField": DEFAULT_ORGANIZATION_FIELD,
                "indicatorCodeField": DEFAULT_INDICATOR_CODE_FIELD,
                "indicatorValueField": DEFAULT_INDICATOR_VALUE_FIELD,
            },
            workbook,
        )
        semantic_import = _validate_import_report(import_report)
        data_set_id = _positive_id(import_report.get("dataSetId"), "dataSetId")

        agents = client.json("GET", "/api/chat/agent/getAgentList?authType=ADMIN")
        existing_agent = _find_unique(agents, "name", agent_name, "Agent")
        agent_payload = build_agent_payload(
            data_set_id,
            chat_model_id,
            existing_agent=existing_agent,
            agent_name=agent_name,
        )
        agent_payload["description"] = "360 项合成指标候选评测 Agent（非官方成绩）"
        agent = client.json("PUT" if existing_agent else "POST", "/api/chat/agent", agent_payload)
        agent_id = _positive_id(agent.get("id") if isinstance(agent, dict) else None, "agent id")

        files = {
            name: {
                "bytes": (release_dir / name).stat().st_size,
                "sha256": _sha256(release_dir / name),
            }
            for name in ("bank.sqlite", "bank-h2.sql")
        }
        return {
            "receiptSchemaVersion": RECEIPT_SCHEMA_VERSION,
            "dataOrigin": "SYNTHETIC",
            "datasetVersion": manifest.get("version"),
            "manifestSha256": _sha256(release_dir / "manifest.json"),
            "agentId": agent_id,
            "agentName": agent_name,
            "dataSetId": data_set_id,
            "modelId": model_id,
            "databaseId": database_id,
            "chatModelId": chat_model_id,
            "modelBinding": model_binding,
            "semanticImport": semantic_import,
            "packageFiles": files,
            "counts": report["counts"],
            "createdAgent": existing_agent is None,
            "physicalDatabaseLoad": "PRELOADED_BY_CALLER",
        }
    finally:
        if temporary_workbook is not None:
            temporary_workbook.cleanup()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("release_dir", type=Path)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--model-id", type=int, required=True)
    parser.add_argument("--database-id", type=int, required=True)
    parser.add_argument("--chat-model-id", type=int)
    parser.add_argument("--agent-name", default=DEFAULT_AGENT_NAME)
    parser.add_argument("--workbook", type=Path, help="Optional persistent workbook path")
    parser.add_argument("--token-env", default=DEFAULT_TOKEN_ENV)
    parser.add_argument("--admin-username", default=DEFAULT_ADMIN_NAME)
    parser.add_argument("--admin-password-env", default=DEFAULT_ADMIN_PASSWORD_ENV)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--output", type=Path, help="Secret-free runtime receipt JSON path")
    args = parser.parse_args(argv)
    release_dir = args.release_dir.resolve()
    try:
        report = validate_release(release_dir)
        manifest = _read_manifest(release_dir)
        if args.dry_run:
            output: dict[str, Any] = {
                "dryRun": True,
                "receiptSchemaVersion": RECEIPT_SCHEMA_VERSION,
                "dataOrigin": "SYNTHETIC",
                "datasetVersion": manifest.get("version"),
                "manifestSha256": _sha256(release_dir / "manifest.json"),
                "modelId": args.model_id,
                "databaseId": args.database_id,
                "agentName": args.agent_name,
                "counts": report["counts"],
                "physicalDatabaseLoad": "PRELOADED_BY_CALLER",
                "networkWrites": 0,
            }
        else:
            token = resolve_auth_token(
                args.base_url,
                token_env=args.token_env,
                admin_username=args.admin_username,
                admin_password=os.environ.get(args.admin_password_env, DEFAULT_ADMIN_PASSWORD),
            )
            output = bootstrap(
                release_dir,
                base_url=args.base_url,
                token=token,
                model_id=args.model_id,
                database_id=args.database_id,
                chat_model_id=args.chat_model_id,
                agent_name=args.agent_name,
                workbook=args.workbook.resolve() if args.workbook else None,
            )
    except (BankAgentBootstrapError, OSError, ValueError, json.JSONDecodeError) as error:
        parser.error(str(error))
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
