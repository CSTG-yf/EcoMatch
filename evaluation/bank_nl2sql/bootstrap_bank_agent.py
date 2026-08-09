#!/usr/bin/env python3
"""Import the official bank semantic dataset and upsert a portable bank Agent.

The script intentionally does not copy a runtime H2 database.  It uses stable
HTTP contracts so generated IDs on a teammate's installation are discovered
and wired into the Agent payload.  Authentication is read only from an
environment variable and is never persisted in the repository.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import sys
import uuid
from pathlib import Path
from typing import Any
from urllib import error, request

from amend_official_ground_truth import _validate_parent


DEFAULT_BASE_URL = "http://127.0.0.1:9080"
DEFAULT_TOKEN_ENV = "ECOMATCH_AUTH_TOKEN"
DEFAULT_AGENT_NAME = "银行问数"


class BankAgentBootstrapError(RuntimeError):
    """The portable runtime package could not be validated or imported."""


def unwrap_api_response(payload: Any) -> Any:
    if not isinstance(payload, dict):
        raise BankAgentBootstrapError("API response must be a JSON object")
    if "code" not in payload:
        return payload
    if payload.get("code") != 200:
        raise BankAgentBootstrapError(str(payload.get("msg") or "API request failed"))
    return payload.get("data")


def patch_system_config(current: dict[str, Any], wanted: dict[str, str]) -> dict[str, Any]:
    if not isinstance(current, dict):
        raise BankAgentBootstrapError("system configuration must be an object")
    patched = copy.deepcopy(current)
    parameters = patched.get("parameters")
    if not isinstance(parameters, list):
        raise BankAgentBootstrapError("system configuration parameters must be a list")
    by_name = {
        item.get("name"): item
        for item in parameters
        if isinstance(item, dict) and isinstance(item.get("name"), str)
    }
    for name, value in wanted.items():
        if name in by_name:
            by_name[name]["value"] = str(value)
        else:
            item = {
                "name": name,
                "value": str(value),
                "comment": "portable bank Agent bootstrap",
            }
            parameters.append(item)
            by_name[name] = item
    return patched


def build_agent_payload(
    data_set_id: int,
    chat_model_id: int,
    *,
    existing_agent_id: int | None = None,
    agent_name: str = DEFAULT_AGENT_NAME,
) -> dict[str, Any]:
    if data_set_id <= 0:
        raise BankAgentBootstrapError("dataSetId must be positive")
    if chat_model_id <= 0:
        raise BankAgentBootstrapError("chatModelId must be positive")
    tool_config = {
        "simpleMode": False,
        "debugMode": True,
        "tools": [
            {
                "id": "bank-evaluation-dataset",
                "type": "DATASET",
                "name": "银行 NL2SQL",
                "dataSetIds": [data_set_id],
                "exampleQuestions": [],
                "metricOptions": [],
            }
        ],
    }
    rewrite_prompt = (
        "#Role: Rewrite the current question into a complete standalone question using "
        "the structured history from this chat session. #Rules: 1. Process "
        "history_context in chronological order. 2. APPEND inherits unspecified metrics, "
        "dimensions, filters, date ranges, sorting and granularity. 3. REPLACE overwrites "
        "only items explicitly changed. 4. REMOVE excludes only explicitly cancelled items. "
        "5. DRILL_DOWN preserves filters and changes granularity. 6. Never invent conditions "
        "or inherit information outside history_context. 7. ONLY respond with the rewritten "
        "question. #Current Question: {{current_question}} #Current Mapped Schema: "
        "{{current_schema}} #Context Operation: {{context_operation}} #History Context: "
        "{{history_context}} #Rewritten Question:"
    )
    s2sql_prompt = (
        "#Role: You are a data analyst experienced in SQL languages.\n"
        "#Task: Convert the natural-language question into one executable S2SQL statement.\n"
        "#Rules:\n1. Use only columns and values present in Schema; do not hallucinate.\n"
        "2. Preserve explicitly requested institutions, metrics, dates, comparison types, "
        "ranking direction and limits.\n3. Use explicit date bounds; do not calculate date "
        "ranges with SQL functions.\n4. Use WITH when nested aggregation is required.\n"
        "5. Output only S2SQL, without Markdown or explanation.\n"
        "#Query: Question:{{question}},Schema:{{schema}},SideInfo:{{information}}"
    )
    chat_apps = {
        "REWRITE_MULTI_TURN": {
            "name": "多轮对话改写",
            "description": "使用结构化会话历史改写当前问题",
            "prompt": rewrite_prompt,
            "enable": True,
            "chatModelId": chat_model_id,
        },
        "BANK_CONSTRAINED_PLAN": {
            "name": "银行受约束查询计划",
            "description": "通过大模型生成经过白名单约束的银行查询计划",
            "enable": True,
        },
        "S2SQL_PARSER": {
            "name": "语义 SQL 解析",
            "description": "通过大模型把自然语言翻译为 S2SQL",
            "prompt": s2sql_prompt,
            "enable": True,
            "chatModelId": chat_model_id,
        },
        "EXECUTION_SQL_CORRECTOR": {
            "name": "执行 SQL 修复",
            "description": "将数据库执行错误回灌模型并进行一次受控修复",
            "enable": True,
            "chatModelId": chat_model_id,
        },
        "REWRITE_ERROR_MESSAGE": {
            "name": "异常提示改写",
            "description": "把终态错误改写为用户可见的失败原因",
            "prompt": (
                "#Role: You are a data business partner. #Task: Explain the failed "
                "validation or execution result and tell the user how to correct the request. "
                "#Rules: 1. Use the same language as Input. 2. Do not claim success. "
                "3. Do not invent data or hide the failure stage. #Input: {{user_question}} "
                "#Output: {{system_message}} #Response:"
            ),
            "enable": True,
            "chatModelId": chat_model_id,
        },
    }
    payload: dict[str, Any] = {
        "name": agent_name,
        "description": "银行业智能问数正式评估 Agent",
        "status": 1,
        "examples": [],
        "enableSearch": 1,
        "enableFeedback": 0,
        "toolConfig": json.dumps(tool_config, ensure_ascii=False, separators=(",", ":")),
        "chatAppConfig": chat_apps,
        "visualConfig": None,
        "admins": [],
        "viewers": [],
        "adminOrgs": [],
        "viewOrgs": [],
        "isOpen": 1,
    }
    if existing_agent_id is not None:
        payload["id"] = existing_agent_id
    return payload


class ApiClient:
    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self.token = token.strip()
        if not self.token:
            raise BankAgentBootstrapError("authentication token is empty")

    def _headers(self, content_type: str | None = None) -> dict[str, str]:
        authorization = self.token
        if not authorization.lower().startswith("bearer "):
            authorization = f"Bearer {authorization}"
        headers = {"Accept": "application/json", "Authorization": authorization}
        if content_type:
            headers["Content-Type"] = content_type
        return headers

    def _open(self, req: request.Request) -> Any:
        try:
            with request.urlopen(req, timeout=180) as response:
                raw = response.read()
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise BankAgentBootstrapError(f"HTTP {exc.code}: {detail[:500]}") from exc
        except error.URLError as exc:
            raise BankAgentBootstrapError(f"request failed: {exc.reason}") from exc
        try:
            return unwrap_api_response(json.loads(raw.decode("utf-8")))
        except json.JSONDecodeError as exc:
            raise BankAgentBootstrapError("API response is not valid JSON") from exc

    def json(self, method: str, path: str, payload: Any | None = None) -> Any:
        body = None
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = request.Request(
            self.base_url + path,
            data=body,
            headers=self._headers("application/json" if body is not None else None),
            method=method,
        )
        return self._open(req)

    def multipart(self, path: str, fields: dict[str, str], file_path: Path) -> Any:
        boundary = "----EcoMatch" + uuid.uuid4().hex
        chunks: list[bytes] = []
        for name, value in fields.items():
            chunks.extend(
                [
                    f"--{boundary}\r\n".encode("ascii"),
                    f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("ascii"),
                    str(value).encode("utf-8"),
                    b"\r\n",
                ]
            )
        chunks.extend(
            [
                f"--{boundary}\r\n".encode("ascii"),
                (
                    f'Content-Disposition: form-data; name="file"; '
                    f'filename="{file_path.name}"\r\n'
                ).encode("utf-8"),
                b"Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n",
                file_path.read_bytes(),
                b"\r\n",
                f"--{boundary}--\r\n".encode("ascii"),
            ]
        )
        req = request.Request(
            self.base_url + path,
            data=b"".join(chunks),
            headers=self._headers(f"multipart/form-data; boundary={boundary}"),
            method="POST",
        )
        return self._open(req)


def resolve_official_release(dataset_dir: Path) -> tuple[dict[str, Any], Path, str]:
    current_path = dataset_dir / "official" / "CURRENT.json"
    try:
        current = json.loads(current_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise BankAgentBootstrapError(f"cannot read official CURRENT.json: {exc}") from exc
    directory = current.get("directory")
    if not isinstance(directory, str) or not directory or Path(directory).name != directory:
        raise BankAgentBootstrapError("official CURRENT.json directory is invalid")
    official_dir = dataset_dir / "official" / directory
    try:
        manifest, workbook, _, _, manifest_sha = _validate_parent(official_dir)
    except Exception as exc:  # validation code supplies the precise artifact error
        raise BankAgentBootstrapError(f"official release validation failed: {exc}") from exc
    if current.get("currentVersion") != manifest.get("datasetVersion"):
        raise BankAgentBootstrapError("CURRENT.json version does not match official manifest")
    expected_workbook = f"{directory}/{workbook.name}"
    if current.get("groundTruthWorkbook") != expected_workbook:
        raise BankAgentBootstrapError("CURRENT.json workbook does not match official manifest")
    return manifest, workbook, manifest_sha


def _find_existing_agent(agents: Any, agent_name: str) -> int | None:
    if not isinstance(agents, list):
        raise BankAgentBootstrapError("Agent list response must be a list")
    matches = [item for item in agents if isinstance(item, dict) and item.get("name") == agent_name]
    if len(matches) > 1:
        raise BankAgentBootstrapError(f"multiple Agents named {agent_name}")
    if not matches:
        return None
    agent_id = matches[0].get("id")
    if not isinstance(agent_id, int) or agent_id <= 0:
        raise BankAgentBootstrapError("existing Agent id is invalid")
    return agent_id


def bootstrap(
    dataset_dir: Path,
    *,
    base_url: str,
    token: str,
    model_id: int,
    chat_model_id: int,
    agent_name: str,
    date_field: str,
    organization_field: str,
    indicator_code_field: str,
    indicator_value_field: str,
) -> dict[str, Any]:
    manifest, workbook, manifest_sha = resolve_official_release(dataset_dir)
    client = ApiClient(base_url, token)
    import_report = client.multipart(
        "/api/semantic/bank/resources/import",
        {
            "modelId": str(model_id),
            "dataSetName": "银行业智能问数数据集",
            "dataSetBizName": "bank_indicator_dataset",
            "dateField": date_field,
            "organizationField": organization_field,
            "indicatorCodeField": indicator_code_field,
            "indicatorValueField": indicator_value_field,
        },
        workbook,
    )
    if not isinstance(import_report, dict) or import_report.get("success") is not True:
        raise BankAgentBootstrapError(f"semantic import failed: {import_report}")
    data_set_id = import_report.get("dataSetId")
    if not isinstance(data_set_id, int) or data_set_id <= 0:
        raise BankAgentBootstrapError("semantic import did not return a valid dataSetId")

    agents = client.json("GET", "/api/chat/agent/getAgentList?authType=ADMIN")
    existing_id = _find_existing_agent(agents, agent_name)
    agent_payload = build_agent_payload(
        data_set_id,
        chat_model_id,
        existing_agent_id=existing_id,
        agent_name=agent_name,
    )
    agent = client.json("PUT" if existing_id else "POST", "/api/chat/agent", agent_payload)
    if not isinstance(agent, dict) or not isinstance(agent.get("id"), int):
        raise BankAgentBootstrapError("Agent upsert did not return a valid id")

    best = json.loads((dataset_dir / "repro" / "best_bank_on.json").read_text(encoding="utf-8"))
    wanted = best.get("systemParameters")
    if not isinstance(wanted, dict):
        raise BankAgentBootstrapError("best_bank_on systemParameters are invalid")
    current_system = client.json("GET", "/api/semantic/parameter")
    patched_system = patch_system_config(current_system, {str(k): str(v) for k, v in wanted.items()})
    if client.json("POST", "/api/semantic/parameter", patched_system) is not True:
        raise BankAgentBootstrapError("system parameter update was not acknowledged")

    return {
        "officialVersion": manifest["datasetVersion"],
        "officialManifestSha256": manifest_sha,
        "modelId": model_id,
        "dataSetId": data_set_id,
        "agentId": agent["id"],
        "agentName": agent_name,
        "chatModelId": chat_model_id,
        "createdAgent": existing_id is None,
        "semanticImport": {
            "organizations": import_report.get("organizationCount"),
            "indicators": import_report.get("indicatorCount"),
            "factsValidated": import_report.get("factCount"),
        },
        "systemParameterCount": len(wanted),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "dataset",
        nargs="?",
        type=Path,
        default=Path(__file__).resolve().parent,
        help="bank_nl2sql dataset directory",
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--model-id", type=int, required=True)
    parser.add_argument("--chat-model-id", type=int, default=1)
    parser.add_argument("--agent-name", default=DEFAULT_AGENT_NAME)
    parser.add_argument("--token-env", default=DEFAULT_TOKEN_ENV)
    parser.add_argument("--date-field", default="data_date")
    parser.add_argument("--organization-field", default="org_code")
    parser.add_argument("--indicator-code-field", default="metric_code")
    parser.add_argument("--indicator-value-field", default="metric_value")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        manifest, workbook, manifest_sha = resolve_official_release(args.dataset.resolve())
        if args.dry_run:
            output = {
                "dryRun": True,
                "officialVersion": manifest["datasetVersion"],
                "officialManifestSha256": manifest_sha,
                "workbook": str(workbook),
                "modelId": args.model_id,
                "chatModelId": args.chat_model_id,
                "agentName": args.agent_name,
                "networkWrites": 0,
            }
        else:
            token = os.environ.get(args.token_env, "")
            if not token:
                raise BankAgentBootstrapError(
                    f"set {args.token_env} to an administrator token before importing"
                )
            output = bootstrap(
                args.dataset.resolve(),
                base_url=args.base_url,
                token=token,
                model_id=args.model_id,
                chat_model_id=args.chat_model_id,
                agent_name=args.agent_name,
                date_field=args.date_field,
                organization_field=args.organization_field,
                indicator_code_field=args.indicator_code_field,
                indicator_value_field=args.indicator_value_field,
            )
    except BankAgentBootstrapError as exc:
        parser.error(str(exc))
    print(json.dumps(output, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
