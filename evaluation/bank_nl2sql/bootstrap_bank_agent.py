#!/usr/bin/env python3
"""Import the official bank semantic dataset and upsert a portable bank Agent.

The script intentionally does not copy a runtime H2 database.  It uses stable
HTTP contracts so generated IDs on a teammate's installation are discovered
and wired into the Agent payload.  Authentication is read only from an
environment variable and is never persisted in the repository.
"""

from __future__ import annotations

import argparse
import base64
import copy
import hashlib
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
DEFAULT_ADMIN_PASSWORD_ENV = "ECOMATCH_ADMIN_PASSWORD"
DEFAULT_ADMIN_NAME = "admin"
DEFAULT_ADMIN_PASSWORD = "123456"
LOGIN_AES_KEY = b"supersonic@2024"
DEFAULT_AGENT_NAME = "银行问数"
DEFAULT_DOMAIN_NAME = "银行问数"
DEFAULT_DOMAIN_BIZ_NAME = "bank_question"
DEFAULT_MODEL_NAME = "银行指标日度事实"
DEFAULT_MODEL_BIZ_NAME = "bank_metric_daily"
DEFAULT_DATASET_NAME = "银行业智能问数数据集"
DEFAULT_DATASET_BIZ_NAME = "bank_indicator_dataset"


class BankAgentBootstrapError(RuntimeError):
    """The portable runtime package could not be validated or imported."""


def _gf_multiply(left: int, right: int) -> int:
    result = 0
    for _ in range(8):
        if right & 1:
            result ^= left
        left = ((left << 1) ^ (0x11B if left & 0x80 else 0)) & 0xFF
        right >>= 1
    return result


def _gf_power(value: int, exponent: int) -> int:
    result = 1
    while exponent:
        if exponent & 1:
            result = _gf_multiply(result, value)
        value = _gf_multiply(value, value)
        exponent >>= 1
    return result


def _rotate_byte(value: int, amount: int) -> int:
    return ((value << amount) | (value >> (8 - amount))) & 0xFF


def _build_aes_sbox() -> tuple[int, ...]:
    values = []
    for value in range(256):
        inverse = 0 if value == 0 else _gf_power(value, 254)
        values.append(
            inverse
            ^ _rotate_byte(inverse, 1)
            ^ _rotate_byte(inverse, 2)
            ^ _rotate_byte(inverse, 3)
            ^ _rotate_byte(inverse, 4)
            ^ 0x63
        )
    return tuple(values)


_AES_SBOX = _build_aes_sbox()
_AES_RCON = (0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B)


def _sub_word(word: int) -> int:
    return (
        (_AES_SBOX[(word >> 24) & 0xFF] << 24)
        | (_AES_SBOX[(word >> 16) & 0xFF] << 16)
        | (_AES_SBOX[(word >> 8) & 0xFF] << 8)
        | _AES_SBOX[word & 0xFF]
    )


def _rotate_word(word: int) -> int:
    return ((word << 8) | (word >> 24)) & 0xFFFFFFFF


def _cryptojs_login_key_schedule() -> dict[int, int]:
    padded_key = LOGIN_AES_KEY + b"\x00"
    key_words = tuple(
        int.from_bytes(padded_key[offset : offset + 4], "big")
        for offset in range(0, len(padded_key), 4)
    )
    # CryptoJS derives keySize from sigBytes, so this existing 15-byte key
    # produces the historical 3.75-word schedule instead of standard AES.
    key_size = len(LOGIN_AES_KEY) / 4
    key_schedule: dict[int, int] = {}
    for row in range(int((key_size + 6 + 1) * 4)):
        if row < key_size:
            key_schedule[row] = key_words[row]
            continue
        value = key_schedule[row - 1]
        if row % key_size == 0:
            value = _sub_word(_rotate_word(value)) ^ (_AES_RCON[int(row / key_size)] << 24)
        key_schedule[row] = key_schedule.get(row - key_size, 0) ^ value
    return key_schedule


_LOGIN_KEY_SCHEDULE = _cryptojs_login_key_schedule()


def _add_round_key(state: bytearray, key_schedule: dict[int, int], start: int) -> None:
    for column in range(4):
        word = key_schedule.get(start + column, 0)
        for row in range(4):
            state[column * 4 + row] ^= (word >> (24 - row * 8)) & 0xFF


def _encrypt_cryptojs_login_block(block: bytes) -> bytes:
    state = bytearray(block)
    _add_round_key(state, _LOGIN_KEY_SCHEDULE, 0)
    for _ in range(1, 10):
        state = bytearray(_AES_SBOX[value] for value in state)
        shifted = bytearray(16)
        for column in range(4):
            for row in range(4):
                shifted[column * 4 + row] = state[((column + row) % 4) * 4 + row]
        state = shifted
        for column in range(4):
            offset = column * 4
            a0, a1, a2, a3 = state[offset : offset + 4]
            state[offset] = _gf_multiply(a0, 2) ^ _gf_multiply(a1, 3) ^ a2 ^ a3
            state[offset + 1] = a0 ^ _gf_multiply(a1, 2) ^ _gf_multiply(a2, 3) ^ a3
            state[offset + 2] = a0 ^ a1 ^ _gf_multiply(a2, 2) ^ _gf_multiply(a3, 3)
            state[offset + 3] = _gf_multiply(a0, 3) ^ a1 ^ a2 ^ _gf_multiply(a3, 2)
        _add_round_key(state, _LOGIN_KEY_SCHEDULE, 4 * _)

    state = bytearray(_AES_SBOX[value] for value in state)
    shifted = bytearray(16)
    for column in range(4):
        for row in range(4):
            shifted[column * 4 + row] = state[((column + row) % 4) * 4 + row]
    _add_round_key(shifted, _LOGIN_KEY_SCHEDULE, 40)
    return bytes(shifted)


def encrypt_login_password(password: str) -> str:
    """Mirror the frontend's historical CryptoJS AES-ECB login transformation."""
    raw = password.encode("utf-8")
    padding = 16 - (len(raw) % 16)
    padded = raw + bytes([padding]) * padding
    encrypted = b"".join(
        _encrypt_cryptojs_login_block(padded[offset : offset + 16])
        for offset in range(0, len(padded), 16)
    )
    return base64.b64encode(encrypted).decode("ascii")


def _canonical_sha256(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def unwrap_api_response(payload: Any) -> Any:
    if not isinstance(payload, dict):
        raise BankAgentBootstrapError("API response must be a JSON object")
    if "code" not in payload:
        return payload
    if payload.get("code") != 200:
        raise BankAgentBootstrapError(str(payload.get("msg") or "API request failed"))
    return payload.get("data")


def login_for_token(base_url: str, username: str, password: str) -> str:
    """Log in through the public auth endpoint and return a bearer token."""
    if not username.strip():
        raise BankAgentBootstrapError("administrator username is empty")
    if not password:
        raise BankAgentBootstrapError("administrator password is empty")
    encrypted_password = encrypt_login_password(password)
    body = json.dumps(
        {"name": username, "password": encrypted_password},
        ensure_ascii=False,
    ).encode("utf-8")
    req = request.Request(
        base_url.rstrip("/") + "/api/auth/user/login",
        data=body,
        headers={"Accept": "application/json", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=30) as response:
            raw = response.read().decode("utf-8", errors="replace").strip()
    except error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise BankAgentBootstrapError(f"administrator login failed: HTTP {exc.code}: {detail[:500]}") from exc
    except error.URLError as exc:
        raise BankAgentBootstrapError(f"administrator login failed: {exc.reason}") from exc
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        token = raw.strip('"')
    else:
        if isinstance(parsed, str):
            token = parsed
        elif isinstance(parsed, dict):
            data = unwrap_api_response(parsed)
            token = data if isinstance(data, str) else ""
        else:
            token = ""
    if not token:
        raise BankAgentBootstrapError("administrator login did not return a token")
    return token


def resolve_auth_token(
    base_url: str,
    *,
    token_env: str,
    admin_username: str,
    admin_password: str,
) -> str:
    token = os.environ.get(token_env, "").strip()
    if token:
        return token
    return login_for_token(base_url, admin_username, admin_password)


def _positive_id(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise BankAgentBootstrapError(f"{label} is invalid")
    return value


def _find_unique(items: Any, field: str, value: Any, label: str) -> dict[str, Any] | None:
    if not isinstance(items, list):
        raise BankAgentBootstrapError(f"{label} list response must be a list")
    matches = [item for item in items if isinstance(item, dict) and item.get(field) == value]
    if len(matches) > 1:
        raise BankAgentBootstrapError(f"multiple {label} entries have {field}={value}")
    return matches[0] if matches else None


def build_domain_payload(admin_name: str) -> dict[str, Any]:
    return {
        "name": DEFAULT_DOMAIN_NAME,
        "bizName": DEFAULT_DOMAIN_BIZ_NAME,
        "description": "银行业智能问数专用主题域",
        "status": 1,
        "sensitiveLevel": 0,
        "parentId": 0,
        "isOpen": 1,
        "viewers": [],
        "viewOrgs": [],
        "admins": [admin_name],
        "adminOrgs": [],
    }


def ensure_domain(client: "ApiClient", admin_name: str) -> tuple[int, bool]:
    domains = client.json("GET", "/api/semantic/domain/getDomainList")
    domain = _find_unique(domains, "bizName", DEFAULT_DOMAIN_BIZ_NAME, "domain")
    if domain is None:
        domain = _find_unique(domains, "name", DEFAULT_DOMAIN_NAME, "domain")
    if domain is not None:
        return _positive_id(domain.get("id"), "domain id"), False
    created = client.json("POST", "/api/semantic/domain/createDomain", build_domain_payload(admin_name))
    if isinstance(created, dict) and isinstance(created.get("id"), int):
        return _positive_id(created.get("id"), "domain id"), True
    domains = client.json("GET", "/api/semantic/domain/getDomainList")
    domain = _find_unique(domains, "bizName", DEFAULT_DOMAIN_BIZ_NAME, "domain")
    if domain is None:
        raise BankAgentBootstrapError("domain creation did not return a usable domain")
    return _positive_id(domain.get("id"), "domain id"), True


def select_database(client: "ApiClient", database_id: int | None = None) -> int:
    databases = client.json("GET", "/api/semantic/database/getDatabaseList")
    if not isinstance(databases, list):
        raise BankAgentBootstrapError("database list response must be a list")
    if database_id is not None:
        database = _find_unique(databases, "id", database_id, "database")
        if database is None:
            raise BankAgentBootstrapError(f"database id {database_id} was not found")
        return database_id
    h2_databases = [
        item
        for item in databases
        if isinstance(item, dict)
        and (
            str(item.get("type") or "").upper() == "H2"
            or str(item.get("url") or "").lower().startswith("jdbc:h2:")
        )
    ]
    candidates = h2_databases or [item for item in databases if isinstance(item, dict)]
    if len(candidates) == 1:
        return _positive_id(candidates[0].get("id"), "database id")
    preferred = [
        item
        for item in candidates
        if item.get("name") in {"S2数据库DEMO", "银行问数数据库"}
    ]
    if len(preferred) == 1:
        return _positive_id(preferred[0].get("id"), "database id")
    choices = ", ".join(
        f"{item.get('id')}:{item.get('name')}" for item in candidates[:10]
    )
    raise BankAgentBootstrapError(
        "cannot uniquely select a database; pass --database-id. Candidates: " + choices
    )


def build_bank_model_payload(
    *,
    database_id: int,
    domain_id: int,
    admin_name: str,
    date_field: str,
    organization_field: str,
    indicator_code_field: str,
    indicator_value_field: str,
) -> dict[str, Any]:
    fields = [
        {"fieldName": date_field, "dataType": "DATE"},
        {"fieldName": organization_field, "dataType": "VARCHAR"},
        {"fieldName": indicator_code_field, "dataType": "VARCHAR"},
        {"fieldName": indicator_value_field, "dataType": "DECIMAL"},
    ]
    return {
        "name": DEFAULT_MODEL_NAME,
        "bizName": DEFAULT_MODEL_BIZ_NAME,
        "description": "银行机构、指标与日期组成的日度事实模型",
        "status": 1,
        "sensitiveLevel": 0,
        "databaseId": database_id,
        "domainId": domain_id,
        "isOpen": 1,
        "admins": [admin_name],
        "adminOrgs": [],
        "viewers": [],
        "viewOrgs": [],
        "modelDetail": {
            "queryType": "sql_query",
            "sqlQuery": (
                f"SELECT {date_field}, {organization_field}, {indicator_code_field}, "
                f"{indicator_value_field} FROM {DEFAULT_MODEL_BIZ_NAME}"
            ),
            "identifiers": [],
            "dimensions": [
                {
                    "name": "数据日期",
                    "type": "partition_time",
                    "expr": date_field,
                    "dateFormat": "yyyy-MM-dd",
                    "dataType": "DATE",
                    "typeParams": {"isPrimary": "true", "timeGranularity": "day"},
                    "isCreateDimension": 1,
                    "bizName": "bank_data_date",
                    "description": "银行指标数据日期",
                },
                {
                    "name": "机构",
                    "type": "categorical",
                    "expr": organization_field,
                    "dataType": "VARCHAR",
                    "isCreateDimension": 1,
                    "bizName": "bank_organization",
                    "description": "农商行机构编码及名称",
                },
                {
                    "name": "指标",
                    "type": "categorical",
                    "expr": indicator_code_field,
                    "dataType": "VARCHAR",
                    "isCreateDimension": 1,
                    "bizName": "bank_indicator",
                    "description": "银行指标编码及名称",
                },
            ],
            "measures": [],
            "fields": fields,
            "sqlVariables": [],
        },
    }


def _model_field_names(model: dict[str, Any]) -> set[str]:
    detail = model.get("modelDetail")
    if not isinstance(detail, dict):
        return set()
    fields = detail.get("fields")
    if not isinstance(fields, list):
        return set()
    return {
        str(item.get("fieldName"))
        for item in fields
        if isinstance(item, dict) and item.get("fieldName")
    }


def ensure_semantic_model(
    client: "ApiClient",
    *,
    database_id: int,
    domain_id: int,
    admin_name: str,
    date_field: str,
    organization_field: str,
    indicator_code_field: str,
    indicator_value_field: str,
) -> tuple[int, bool]:
    required_fields = {
        date_field,
        organization_field,
        indicator_code_field,
        indicator_value_field,
    }
    models = client.json("GET", f"/api/semantic/model/getModelList/{domain_id}")
    model = _find_unique(models, "bizName", DEFAULT_MODEL_BIZ_NAME, "model")
    if model is None:
        model = _find_unique(models, "name", DEFAULT_MODEL_NAME, "model")
    if model is not None:
        missing = required_fields - _model_field_names(model)
        if missing:
            model_id = _positive_id(model.get("id"), "model id")
            detail = client.json("GET", f"/api/semantic/model/getModel/{model_id}")
            if isinstance(detail, dict):
                model = detail
                missing = required_fields - _model_field_names(model)
            if missing:
                raise BankAgentBootstrapError(
                    "existing bank model is missing physical fields: " + ", ".join(sorted(missing))
                )
        return _positive_id(model.get("id"), "model id"), False
    payload = build_bank_model_payload(
        database_id=database_id,
        domain_id=domain_id,
        admin_name=admin_name,
        date_field=date_field,
        organization_field=organization_field,
        indicator_code_field=indicator_code_field,
        indicator_value_field=indicator_value_field,
    )
    if client.json("POST", "/api/semantic/model/createModel", payload) is not True:
        raise BankAgentBootstrapError("bank semantic model creation was not acknowledged")
    models = client.json("GET", f"/api/semantic/model/getModelList/{domain_id}")
    model = _find_unique(models, "bizName", DEFAULT_MODEL_BIZ_NAME, "model")
    if model is None:
        raise BankAgentBootstrapError("bank semantic model was not found after creation")
    return _positive_id(model.get("id"), "model id"), True


def select_chat_model(
    client: "ApiClient",
    *,
    chat_model_id: int | None = None,
    chat_model_name: str | None = None,
) -> int | None:
    models = client.json("GET", "/api/chat/model/getModelList")
    if not isinstance(models, list):
        raise BankAgentBootstrapError("chat model list response must be a list")
    if chat_model_id is not None:
        model = _find_unique(models, "id", chat_model_id, "chat model")
        if model is None:
            raise BankAgentBootstrapError(f"chat model id {chat_model_id} was not found")
        return chat_model_id
    if chat_model_name:
        model = _find_unique(models, "name", chat_model_name, "chat model")
        if model is None:
            raise BankAgentBootstrapError(f"chat model named {chat_model_name} was not found")
        return _positive_id(model.get("id"), "chat model id")

    # The default startup flow leaves model binding to the administrator.
    return None

def ensure_runtime_resources(
    client: "ApiClient",
    *,
    database_id: int | None,
    model_id: int | None,
    chat_model_id: int | None,
    chat_model_name: str | None,
    admin_name: str,
    date_field: str,
    organization_field: str,
    indicator_code_field: str,
    indicator_value_field: str,
) -> dict[str, Any]:
    domain_id: int | None = None
    resolved_database_id: int | None = None
    created_domain = False
    created_model = False
    if model_id is None:
        domain_id, created_domain = ensure_domain(client, admin_name)
        resolved_database_id = select_database(client, database_id)
        model_id, created_model = ensure_semantic_model(
            client,
            database_id=resolved_database_id,
            domain_id=domain_id,
            admin_name=admin_name,
            date_field=date_field,
            organization_field=organization_field,
            indicator_code_field=indicator_code_field,
            indicator_value_field=indicator_value_field,
        )
    else:
        _positive_id(model_id, "model id")
    resolved_chat_model_id = None
    if chat_model_id is not None or chat_model_name:
        resolved_chat_model_id = select_chat_model(
            client,
            chat_model_id=chat_model_id,
            chat_model_name=chat_model_name,
        )
    return {
        "domainId": domain_id,
        "databaseId": resolved_database_id,
        "modelId": model_id,
        "chatModelId": resolved_chat_model_id,
        "createdDomain": created_domain,
        "createdModel": created_model,
    }


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
    chat_model_id: int | None,
    *,
    existing_agent: dict[str, Any] | None = None,
    agent_name: str = DEFAULT_AGENT_NAME,
) -> dict[str, Any]:
    if data_set_id <= 0:
        raise BankAgentBootstrapError("dataSetId must be positive")
    if chat_model_id is not None and chat_model_id <= 0:
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
    final_answer_prompt = (
        "#Role: 你是银行问数 Agent 的最终回答器。\n"
        "#Task: 根据 Question、已验证的 BankQueryPlan 和数据库 Result 直接回答用户。\n"
        "#Rules:\n"
        "1. 只回答问题明确询问的事实，不复述问题，不解释查询过程。\n"
        "2. 禁止输出记录数、数据范围、首末记录、额外最大最小值、免责声明、SQL、字段分析或推理过程。\n"
        "3. 每个数字必须来自 Result；日期和题面阈值可以来自 Question，不得编造额外数字。\n"
        "4. 百分比和业务数值通常四舍五入到两位小数；根据正负号明确回答增长/上升或下降。\n"
        "5. percent_change=变化率，absolute_change=变化额，ratio_percent=占比，rank_position=名次；"
        "current_value/baseline_value 未被询问时不要输出。\n"
        "6. 问增幅/变化百分比只回答 percent_change；只问增加/减少/变动多少时只回答 absolute_change，"
        "不要附带 current_value、baseline_value 或 percent_change；"
        "问占比/比重回答 ratio_percent。\n"
        "7. 同时询问环比和同比时分别回答；排名、趋势、多机构或多指标按 Result 行身份逐项回答。"
        "对逐期序列，必须根据首末值明确写出整体上升、下降或持平；不能只罗列数值。\n"
        "8. 输出一至三句纯文本，不要 Markdown、JSON、标签或前后缀。\n"
        "#Question: {{question}}\n#BankQueryPlan: {{plan}}\n#Result: {{data}}\n"
        "#Metric catalog (code -> business meaning / unit / ranking direction): {{metric_catalog}}\n"
        "#Previous answer: {{previous_answer}}\n#validation_feedback: {{validation_feedback}}\n"
        "#Direct answer:"
    )
    chat_apps = {
        "REWRITE_MULTI_TURN": {
            "name": "多轮对话改写",
            "description": "使用结构化会话历史改写当前问题",
            "prompt": rewrite_prompt,
            "enable": True,
        },
        "BANK_CONSTRAINED_PLAN": {
            "name": "银行受约束查询计划",
            "description": "通过大模型生成经过白名单约束的银行查询计划",
            "enable": True,
        },
        "BANK_FINAL_ANSWER": {
            "name": "银行问数直接回答",
            "description": "基于已验证计划和查询结果生成简洁、可校验的最终答案",
            "prompt": final_answer_prompt,
            "enable": True,
        },
        "S2SQL_PARSER": {
            "name": "语义 SQL 解析",
            "description": "通过大模型把自然语言翻译为 S2SQL",
            "prompt": s2sql_prompt,
            "enable": True,
        },
        "EXECUTION_SQL_CORRECTOR": {
            "name": "执行 SQL 修复",
            "description": "将数据库执行错误回灌模型并进行一次受控修复",
            "enable": True,
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
        },
    }
    if chat_model_id is not None:
        for app_key in (
            "REWRITE_MULTI_TURN",
            "BANK_CONSTRAINED_PLAN",
            "BANK_FINAL_ANSWER",
            "S2SQL_PARSER",
            "EXECUTION_SQL_CORRECTOR",
            "REWRITE_ERROR_MESSAGE",
        ):
            chat_apps[app_key]["chatModelId"] = chat_model_id
    elif existing_agent is not None:
        existing_apps = existing_agent.get("chatAppConfig")
        if isinstance(existing_apps, dict):
            for app_key, existing_app in existing_apps.items():
                if not isinstance(existing_app, dict):
                    continue
                existing_model_id = existing_app.get("chatModelId")
                if isinstance(existing_model_id, int) and existing_model_id > 0:
                    if app_key not in chat_apps:
                        chat_apps[app_key] = {
                            key: copy.deepcopy(value)
                            for key, value in existing_app.items()
                            if key != "chatModelConfig"
                        }
                    else:
                        chat_apps[app_key]["chatModelId"] = existing_model_id
    preserved_lists: dict[str, list[Any]] = {}
    for field in ("examples", "admins", "viewers", "adminOrgs", "viewOrgs"):
        value = existing_agent.get(field) if existing_agent is not None else None
        if value is not None and not isinstance(value, list):
            raise BankAgentBootstrapError(f"existing Agent {field} must be a list")
        preserved_lists[field] = copy.deepcopy(value) if isinstance(value, list) else []

    payload: dict[str, Any] = {
        "name": agent_name,
        "description": "银行业智能问数正式评估 Agent",
        "status": 1,
        "examples": preserved_lists["examples"],
        "enableSearch": 1,
        "enableFeedback": 0,
        "toolConfig": json.dumps(tool_config, ensure_ascii=False, separators=(",", ":")),
        "chatAppConfig": chat_apps,
        "visualConfig": copy.deepcopy(existing_agent.get("visualConfig"))
        if existing_agent is not None
        else None,
        "admins": preserved_lists["admins"],
        "viewers": preserved_lists["viewers"],
        "adminOrgs": preserved_lists["adminOrgs"],
        "viewOrgs": preserved_lists["viewOrgs"],
        "isOpen": 1,
    }
    if existing_agent is not None:
        existing_agent_id = existing_agent.get("id")
        if not isinstance(existing_agent_id, int) or existing_agent_id <= 0:
            raise BankAgentBootstrapError("existing Agent id is invalid")
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


def _find_existing_agent(agents: Any, agent_name: str) -> dict[str, Any] | None:
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
    return matches[0]


def bootstrap(
    dataset_dir: Path,
    *,
    base_url: str,
    token: str,
    database_id: int | None,
    model_id: int | None,
    chat_model_id: int | None,
    chat_model_name: str | None,
    admin_name: str,
    agent_name: str,
    date_field: str,
    organization_field: str,
    indicator_code_field: str,
    indicator_value_field: str,
) -> dict[str, Any]:
    manifest, workbook, manifest_sha = resolve_official_release(dataset_dir)
    client = ApiClient(base_url, token)
    resources = ensure_runtime_resources(
        client,
        database_id=database_id,
        model_id=model_id,
        chat_model_id=chat_model_id,
        chat_model_name=chat_model_name,
        admin_name=admin_name,
        date_field=date_field,
        organization_field=organization_field,
        indicator_code_field=indicator_code_field,
        indicator_value_field=indicator_value_field,
    )
    resolved_model_id = _positive_id(resources["modelId"], "model id")
    resolved_chat_model_id = resources["chatModelId"]
    import_report = client.multipart(
        "/api/semantic/bank/resources/import",
        {
            "modelId": str(resolved_model_id),
            "dataSetName": DEFAULT_DATASET_NAME,
            "dataSetBizName": DEFAULT_DATASET_BIZ_NAME,
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
    existing_agent = _find_existing_agent(agents, agent_name)
    agent_payload = build_agent_payload(
        data_set_id,
        resolved_chat_model_id,
        existing_agent=existing_agent,
        agent_name=agent_name,
    )
    agent = client.json("PUT" if existing_agent else "POST", "/api/chat/agent", agent_payload)
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
        "receiptSchemaVersion": "1.0",
        "officialVersion": manifest["datasetVersion"],
        "officialManifestSha256": manifest_sha,
        "modelId": resolved_model_id,
        "dataSetId": data_set_id,
        "agentId": agent["id"],
        "agentName": agent_name,
        "chatModelId": resolved_chat_model_id,
        "createdAgent": existing_agent is None,
        "runtimeResources": resources,
        "semanticImport": {
            "organizations": import_report.get("organizationCount"),
            "indicators": import_report.get("indicatorCount"),
            "factsValidated": import_report.get("factCount"),
        },
        "systemParameterCount": len(wanted),
        "agentProfileSha256": _canonical_sha256(
            {
                "toolConfig": agent_payload["toolConfig"],
                "chatAppConfig": agent_payload["chatAppConfig"],
            }
        ),
        "systemParametersSha256": _canonical_sha256(wanted),
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
    parser.add_argument("--database-id", type=int)
    parser.add_argument("--model-id", type=int)
    parser.add_argument("--chat-model-id", type=int)
    parser.add_argument("--chat-model-name")
    parser.add_argument("--agent-name", default=DEFAULT_AGENT_NAME)
    parser.add_argument("--token-env", default=DEFAULT_TOKEN_ENV)
    parser.add_argument("--admin-username", default=DEFAULT_ADMIN_NAME)
    parser.add_argument("--admin-password-env", default=DEFAULT_ADMIN_PASSWORD_ENV)
    parser.add_argument("--date-field", default="data_date")
    parser.add_argument("--organization-field", default="org_code")
    parser.add_argument("--indicator-code-field", default="metric_code")
    parser.add_argument("--indicator-value-field", default="metric_value")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--output",
        type=Path,
        help="Write the successful, secret-free bootstrap receipt to this local JSON path",
    )
    args = parser.parse_args(argv)
    try:
        manifest, workbook, manifest_sha = resolve_official_release(args.dataset.resolve())
        if args.dry_run:
            output = {
                "dryRun": True,
                "officialVersion": manifest["datasetVersion"],
                "officialManifestSha256": manifest_sha,
                "workbook": str(workbook),
                "databaseId": args.database_id,
                "modelId": args.model_id,
                "chatModelId": args.chat_model_id,
                "chatModelName": args.chat_model_name,
                "agentName": args.agent_name,
                "networkWrites": 0,
            }
        else:
            admin_password = os.environ.get(
                args.admin_password_env,
                DEFAULT_ADMIN_PASSWORD,
            )
            token = resolve_auth_token(
                args.base_url,
                token_env=args.token_env,
                admin_username=args.admin_username,
                admin_password=admin_password,
            )
            output = bootstrap(
                args.dataset.resolve(),
                base_url=args.base_url,
                token=token,
                database_id=args.database_id,
                model_id=args.model_id,
                chat_model_id=args.chat_model_id,
                chat_model_name=args.chat_model_name,
                admin_name=args.admin_username,
                agent_name=args.agent_name,
                date_field=args.date_field,
                organization_field=args.organization_field,
                indicator_code_field=args.indicator_code_field,
                indicator_value_field=args.indicator_value_field,
            )
    except BankAgentBootstrapError as exc:
        parser.error(str(exc))
    if args.output is not None:
        _write_json(args.output, output)
    print(json.dumps(output, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
