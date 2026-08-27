#!/usr/bin/env python3
"""Run the only supported Bank NL2SQL runtime evaluation protocol.

The runner drives the public frontend API sequence with one independent
conversation per record, then publishes Fact v3 ``caseAccuracy`` only.  It
never sends frozen gold, SQL, rows, or answer text to the service.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Any

from bootstrap_bank_agent import (
    DEFAULT_ADMIN_NAME,
    DEFAULT_ADMIN_PASSWORD,
    DEFAULT_ADMIN_PASSWORD_ENV,
    DEFAULT_TOKEN_ENV,
    ApiClient,
    BankAgentBootstrapError,
    resolve_auth_token,
)
from evaluation_policy import EvaluationAccessError, load_evaluation_records
from official_runtime_evaluation import (
    OFFICIAL_RUNTIME_SCHEMA_VERSION,
    OfficialRuntimeEvaluationError,
    build_official_runtime_report,
    load_official_runtime_profile,
    verify_bootstrap_receipt,
    verify_official_runtime_release,
    verify_source_checkout,
)
from run_supersonic_eval import (
    DEFAULT_MANAGE_API_PREFIX,
    DEFAULT_QUERY_API_PREFIX,
    SuperSonicEvaluationError,
    _http_post_json,
    _latency_distribution,
    _unwrap_api_value,
    warm_up_runtime_prefix,
    run_supersonic_evaluation,
)


class OfficialRuntimeRunError(RuntimeError):
    """The standard execution gate could not produce a comparable result."""


_RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")
_MODES = ("smoke", "train", "dev", "test")
_BANK_RUNTIME_CHAT_APPS = (
    "REWRITE_MULTI_TURN",
    "BANK_CONSTRAINED_PLAN",
    "BANK_FINAL_ANSWER",
    "S2SQL_PARSER",
    "EXECUTION_SQL_CORRECTOR",
    "REWRITE_ERROR_MESSAGE",
)


def _read_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise OfficialRuntimeRunError(f"cannot read JSON report: {path}") from error
    if not isinstance(payload, dict):
        raise OfficialRuntimeRunError(f"JSON report must be an object: {path}")
    return payload


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _endpoint_fingerprint(base_url: str) -> str:
    return hashlib.sha256(base_url.rstrip("/").encode("utf-8")).hexdigest()


def _canonical_sha256(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _find_agent_by_id(agents: Any, agent_id: int) -> dict[str, Any]:
    if not isinstance(agents, list):
        raise OfficialRuntimeRunError("Agent list response must be a list")
    matches = [
        item for item in agents
        if isinstance(item, dict) and item.get("id") == agent_id
    ]
    if len(matches) != 1:
        raise OfficialRuntimeRunError(
            f"Agent id {agent_id} was not found uniquely in the manageable Agent list"
        )
    return matches[0]


def _find_chat_model_by_id(models: Any, chat_model_id: int) -> dict[str, Any]:
    if not isinstance(models, list):
        raise OfficialRuntimeRunError("chat model list response must be a list")
    matches = [
        item for item in models
        if isinstance(item, dict) and item.get("id") == chat_model_id
    ]
    if len(matches) != 1:
        raise OfficialRuntimeRunError(
            f"chat model id {chat_model_id} was not found uniquely"
        )
    return matches[0]


def _runtime_model_descriptor(model: dict[str, Any], chat_model_id: int) -> dict[str, Any]:
    config = model.get("config")
    if not isinstance(config, dict):
        raise OfficialRuntimeRunError(
            f"chat model id {chat_model_id} has no usable configuration"
        )
    provider = config.get("provider")
    model_name = config.get("modelName")
    base_url = config.get("baseUrl")
    if not isinstance(provider, str) or not provider.strip():
        raise OfficialRuntimeRunError(f"chat model id {chat_model_id} provider is blank")
    if not isinstance(model_name, str) or not model_name.strip():
        raise OfficialRuntimeRunError(f"chat model id {chat_model_id} modelName is blank")
    if not isinstance(base_url, str) or not base_url.strip():
        raise OfficialRuntimeRunError(f"chat model id {chat_model_id} baseUrl is blank")
    registry_name = model.get("name")
    return {
        "chatModelId": chat_model_id,
        "registryName": registry_name.strip()
        if isinstance(registry_name, str) and registry_name.strip()
        else None,
        "provider": provider.strip().upper(),
        "modelName": model_name.strip(),
        "modelEndpointFingerprint": _endpoint_fingerprint(base_url.strip()),
    }


def bind_runtime_chat_model(
    client: ApiClient,
    *,
    agent_id: int,
    chat_model_id: int,
) -> dict[str, Any]:
    """Bind and read back the real model used by every enabled bank ChatApp."""

    descriptor = _runtime_model_descriptor(
        _find_chat_model_by_id(
            client.json("GET", "/api/chat/model/getModelList"),
            chat_model_id,
        ),
        chat_model_id,
    )
    agent = _find_agent_by_id(
        client.json("GET", "/api/chat/agent/getAgentList?authType=ADMIN"),
        agent_id,
    )
    chat_apps = agent.get("chatAppConfig")
    if not isinstance(chat_apps, dict):
        raise OfficialRuntimeRunError(f"Agent id {agent_id} chatAppConfig is invalid")
    target_keys = [
        key
        for key in _BANK_RUNTIME_CHAT_APPS
        if isinstance(chat_apps.get(key), dict) and chat_apps[key].get("enable") is True
    ]
    if "BANK_CONSTRAINED_PLAN" not in target_keys:
        raise OfficialRuntimeRunError(
            f"Agent id {agent_id} does not enable BANK_CONSTRAINED_PLAN"
        )
    previous_ids = {key: chat_apps[key].get("chatModelId") for key in target_keys}
    needs_update = any(value != chat_model_id for value in previous_ids.values())

    if needs_update:
        payload = copy.deepcopy(agent)
        payload_apps = payload.get("chatAppConfig")
        if not isinstance(payload_apps, dict):
            raise OfficialRuntimeRunError(f"Agent id {agent_id} update payload is invalid")
        for app in payload_apps.values():
            if isinstance(app, dict):
                app.pop("chatModelConfig", None)
        for key in target_keys:
            payload_apps[key]["chatModelId"] = chat_model_id
        updated = client.json("PUT", "/api/chat/agent", payload)
        if not isinstance(updated, dict) or updated.get("id") != agent_id:
            raise OfficialRuntimeRunError("Agent model binding update was not acknowledged")

    read_back = _find_agent_by_id(
        client.json("GET", "/api/chat/agent/getAgentList?authType=ADMIN"),
        agent_id,
    )
    read_back_apps = read_back.get("chatAppConfig")
    if not isinstance(read_back_apps, dict):
        raise OfficialRuntimeRunError("Agent model binding read-back is invalid")
    read_back_ids: dict[str, int] = {}
    for key in target_keys:
        app = read_back_apps.get(key)
        if not isinstance(app, dict) or app.get("enable") is not True:
            raise OfficialRuntimeRunError(f"Agent ChatApp {key} changed during model binding")
        if app.get("chatModelId") != chat_model_id:
            raise OfficialRuntimeRunError(
                f"Agent ChatApp {key} did not bind chatModelId {chat_model_id}"
            )
        read_back_ids[key] = chat_model_id

    receipt = {
        "bindingSchemaVersion": "1.0",
        "agentId": agent_id,
        **descriptor,
        "targetChatApps": target_keys,
        "readBackChatModelIds": read_back_ids,
    }
    receipt["bindingSha256"] = _canonical_sha256(
        {
            "agentId": agent_id,
            "chatModelId": chat_model_id,
            "provider": descriptor["provider"],
            "modelName": descriptor["modelName"],
            "modelEndpointFingerprint": descriptor["modelEndpointFingerprint"],
            "readBackChatModelIds": read_back_ids,
        }
    )
    return receipt


def _validate_run_id(value: str) -> str:
    normalized = value.strip()
    if not _RUN_ID.fullmatch(normalized):
        raise OfficialRuntimeRunError(
            "run-id must contain only letters, digits, dot, underscore, or hyphen"
        )
    return normalized


def _records_for_mode(
    dataset_dir: Path,
    *,
    profile: dict[str, Any],
    mode: str,
) -> tuple[str, list[dict[str, Any]]]:
    split = "train" if mode == "smoke" else mode
    try:
        records = load_evaluation_records(
            dataset_dir,
            split=split,
        )
    except EvaluationAccessError as error:
        raise OfficialRuntimeRunError(str(error)) from error
    if mode != "smoke":
        return split, records

    expected_ids = profile["smoke"]["recordIds"]
    by_id = {str(record.get("id")): record for record in records}
    if len(by_id) != len(records) or any(record_id not in by_id for record_id in expected_ids):
        raise OfficialRuntimeRunError("official smoke IDs are missing from the frozen train split")
    return split, [by_id[record_id] for record_id in expected_ids]


def _capture_report(
    items: list[dict[str, Any]],
    run: dict[str, Any],
    *,
    warmup: dict[str, Any] | None = None,
) -> dict[str, Any]:
    def average(key: str) -> float | None:
        values = [item[key] for item in items if isinstance(item.get(key), (int, float))]
        return round(sum(values) / len(values), 3) if values else None

    error_categories = Counter(
        str(item.get("errorCategory") or "NONE")
        for item in items
    )
    return {
        "run": run,
        "warmup": warmup,
        "items": items,
        "timingMs": {
            "averageParseMs": average("parseMs"),
            "averageExecuteMs": average("executeMs"),
            "averageQueryTimeCostMs": average("queryTimeCostMs"),
            "averageExecutePostQueryMs": average("executePostQueryMs"),
            "averageSummaryMs": average("summaryMs"),
        },
        "timingDistributionsMs": {
            "parse": _latency_distribution([item.get("parseMs") for item in items]),
            "execute": _latency_distribution([item.get("executeMs") for item in items]),
            "queryTimeCost": _latency_distribution(
                [item.get("queryTimeCostMs") for item in items]
            ),
            "executePostQuery": _latency_distribution(
                [item.get("executePostQueryMs") for item in items]
            ),
            "summary": _latency_distribution([item.get("summaryMs") for item in items]),
            "endToEnd": _latency_distribution([item.get("endToEndMs") for item in items]),
        },
        "captureDiagnostics": {"errorCategories": dict(sorted(error_categories.items()))},
    }


def _base_run_metadata(
    *,
    profile_sha256: str,
    source: dict[str, str],
    release: dict[str, Any],
    semantic_receipt: dict[str, Any],
    runtime_model_binding: dict[str, Any],
    mode: str,
    split: str,
    run_id: str,
    agent_id: int,
    model_label: str,
    base_url: str,
    record_ids: list[str],
    max_failures: int | None,
) -> dict[str, Any]:
    metadata = {
        "runId": run_id,
        "mode": mode,
        "split": split,
        "agentId": agent_id,
        "modelLabel": model_label,
        "modelLabelAuthority": "DISPLAY_ONLY",
        "endpointFingerprint": _endpoint_fingerprint(base_url),
        "protocolProfileSha256": profile_sha256,
        "protocolSchemaVersion": OFFICIAL_RUNTIME_SCHEMA_VERSION,
        "sourceRevision": source["sourceRevision"],
        "requiredBaseRevision": source["requiredBaseRevision"],
        "datasetVersion": release["datasetVersion"],
        "databaseArtifacts": release["databaseArtifacts"],
        "setupReceipt": semantic_receipt,
        "runtimeModelBinding": runtime_model_binding,
        "captureMethod": "openapi-frontend-conversation-chain",
        "authentication": "bypassed-openapi",
        "concurrency": 1,
        "selectedRecordIds": record_ids,
        "requestedCount": len(record_ids),
    }
    if max_failures is not None:
        metadata["maxFailureCount"] = max_failures
    return metadata


def _run_metadata(
    base: dict[str, Any],
    *,
    status: str,
    completed_count: int,
    started_at: float,
    early_stop: dict[str, Any] | None = None,
) -> dict[str, Any]:
    metadata = {
        **base,
        "status": status,
        "completedCount": completed_count,
        "durationSeconds": round(time.time() - started_at, 3),
    }
    if early_stop is not None:
        metadata["earlyStop"] = early_stop
    return metadata


def _assert_same_run(
    run: dict[str, Any],
    expected: dict[str, Any],
    *,
    include_max_failures: bool = True,
) -> None:
    for key in (
        "runId",
        "mode",
        "split",
        "agentId",
        "modelLabelAuthority",
        "endpointFingerprint",
        "protocolProfileSha256",
        "protocolSchemaVersion",
        "sourceRevision",
        "requiredBaseRevision",
        "datasetVersion",
        "databaseArtifacts",
        "setupReceipt",
        "runtimeModelBinding",
        "captureMethod",
        "authentication",
        "concurrency",
        "selectedRecordIds",
        "requestedCount",
    ):
        if run.get(key) != expected.get(key):
            raise OfficialRuntimeRunError(f"existing report is not compatible with this run: {key}")
    if include_max_failures and run.get("maxFailureCount") != expected.get("maxFailureCount"):
        raise OfficialRuntimeRunError("existing report is not compatible with this run: maxFailureCount")


def _early_stop_state(
    *,
    max_failures: int | None,
    scored_items: list[dict[str, Any]],
) -> dict[str, Any] | None:
    """Return a non-promotable stop reason only after the budget is exceeded."""
    if max_failures is None:
        return None
    failed_ids = [str(item.get("id")) for item in scored_items if item.get("casePass") is False]
    if len(failed_ids) <= max_failures:
        return None
    return {
        "reason": "MAX_FAILURES_EXCEEDED",
        "maxFailures": max_failures,
        "observedFailures": len(failed_ids),
        "failedIds": failed_ids,
        "triggeredAfterId": str(scored_items[-1].get("id")) if scored_items else None,
        "promotable": False,
    }


def _build_stopped_early_report(
    capture: dict[str, Any],
    records: list[dict[str, Any]],
    *,
    early_stop: dict[str, Any],
) -> dict[str, Any]:
    """Mark a partial capture as diagnostic-only instead of a formal score."""
    report = build_official_runtime_report(capture, records)
    report["partialEvaluation"] = {
        "status": "STOPPED_EARLY",
        "promotable": False,
        "reason": "max-failures budget exceeded before all selected records completed",
        "requestedCount": capture["run"]["requestedCount"],
        "completedCount": len(records),
        "earlyStop": early_stop,
    }
    return report


def _mark_bounded_diagnostic_report(
    report: dict[str, Any], *, max_failures: int | None
) -> dict[str, Any]:
    """Keep a completed failure-budget run explicitly diagnostic-only."""
    if max_failures is None:
        return report
    run = report.get("run")
    if not isinstance(run, dict):
        raise OfficialRuntimeRunError("completed diagnostic report has no run metadata")
    report["diagnosticEvaluation"] = {
        "status": "COMPLETED_WITH_FAILURE_BUDGET",
        "promotable": False,
        "reason": "max-failures budget was configured",
        "maxFailures": max_failures,
        "completedCount": run.get("completedCount"),
        "requestedCount": run.get("requestedCount"),
    }
    return report


def _load_resumed_items(path: Path, *, expected_run: dict[str, Any], records: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    if not path.exists():
        return {}
    report = _read_json(path)
    if report.get("schemaVersion") != OFFICIAL_RUNTIME_SCHEMA_VERSION:
        raise OfficialRuntimeRunError("existing report is not an official runtime evaluation report")
    run = report.get("run")
    if not isinstance(run, dict):
        raise OfficialRuntimeRunError("existing report has no run metadata")
    _assert_same_run(run, expected_run)
    if run.get("status") == "STOPPED_EARLY":
        raise OfficialRuntimeRunError(
            "existing report stopped early and is diagnostic-only; repair the candidate and use a new run-id"
        )
    items = report.get("items")
    if not isinstance(items, list):
        raise OfficialRuntimeRunError("existing report has no items")
    allowed_ids = {str(record["id"]) for record in records}
    resumed: dict[str, dict[str, Any]] = {}
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get("id"), str):
            raise OfficialRuntimeRunError("existing report contains an invalid item")
        sample_id = item["id"]
        if sample_id not in allowed_ids or sample_id in resumed:
            raise OfficialRuntimeRunError("existing report contains unexpected or duplicate item IDs")
        resumed[sample_id] = item
    return resumed


def _assert_completed_gate(
    path: Path,
    *,
    expected: dict[str, Any],
    required_mode: str,
    require_green: bool,
    expected_record_ids: list[str] | None = None,
) -> None:
    if not path.is_file():
        raise OfficialRuntimeRunError(f"required {required_mode} report does not exist: {path}")
    report = _read_json(path)
    if report.get("schemaVersion") != OFFICIAL_RUNTIME_SCHEMA_VERSION:
        raise OfficialRuntimeRunError(f"required {required_mode} report schemaVersion is invalid")
    record_ids = expected_record_ids if expected_record_ids is not None else expected.get("selectedRecordIds")
    if (
        not isinstance(record_ids, list)
        or not record_ids
        or any(not isinstance(record_id, str) or not record_id for record_id in record_ids)
        or len(set(record_ids)) != len(record_ids)
    ):
        raise OfficialRuntimeRunError(f"required {required_mode} expected selectedRecordIds are invalid")
    run = report.get("run")
    if not isinstance(run, dict):
        raise OfficialRuntimeRunError(f"required {required_mode} report has no run metadata")
    expected_for_mode = {
        **expected,
        "mode": required_mode,
        "split": "train" if required_mode == "smoke" else required_mode,
        "selectedRecordIds": record_ids,
        "requestedCount": len(record_ids),
    }
    # maxFailureCount is a diagnostic stop budget for a selected train/dev
    # capture, not part of the common source/runtime identity required by an
    # earlier gate.  Keeping it here would make a normal completed smoke report
    # unusable as the prerequisite for a bounded train diagnostic.
    _assert_same_run(run, expected_for_mode, include_max_failures=False)
    if run.get("status") != "COMPLETED":
        raise OfficialRuntimeRunError(f"required {required_mode} report is not complete")
    diagnostic = report.get("diagnosticEvaluation")
    if isinstance(diagnostic, dict) and diagnostic.get("promotable") is False:
        raise OfficialRuntimeRunError(
            f"required {required_mode} report is diagnostic-only and cannot satisfy a formal gate"
        )
    items = report.get("items")
    if not isinstance(items, list):
        raise OfficialRuntimeRunError(f"required {required_mode} report items are invalid")
    item_ids = [item.get("id") for item in items if isinstance(item, dict)]
    if len(item_ids) != len(items) or item_ids != record_ids:
        raise OfficialRuntimeRunError(f"required {required_mode} report items do not match selectedRecordIds")
    if any(
        not isinstance(item.get("casePass"), bool)
        or not isinstance(item.get("resultExact"), bool)
        or item["casePass"] != item["resultExact"]
        for item in items
    ):
        raise OfficialRuntimeRunError(
            f"required {required_mode} report items violate casePass = resultExact"
        )
    metrics = report.get("metrics")
    pass_hits = sum(item["casePass"] for item in items)
    expected_accuracy = round(pass_hits / len(record_ids), 6)
    if (
        not isinstance(metrics, dict)
        or metrics.get("caseDenominator") != len(record_ids)
        or metrics.get("casePassHits") != pass_hits
        or metrics.get("caseAccuracy") != expected_accuracy
    ):
        raise OfficialRuntimeRunError(f"required {required_mode} report has an invalid denominator")
    if require_green and pass_hits != len(record_ids):
        raise OfficialRuntimeRunError(f"required {required_mode} report is not all green")


def _write_markdown(path: Path, report: dict[str, Any]) -> None:
    run = report["run"]
    metrics = report["metrics"]
    diagnostics = report["runtimeDiagnostics"]
    failed = [item for item in report["items"] if not item.get("casePass")]
    lines = [
        "# Official Bank Runtime Evaluation",
        "",
        f"- Run: `{run['runId']}` / `{run['mode']}` / `{run['status']}`",
        f"- Dataset: v{run['datasetVersion']} · source `{run['sourceRevision']}`",
        f"- Agent: {run['agentId']} · chatModelId: {run['runtimeModelBinding']['chatModelId']} "
        f"· provider/model: `{run['runtimeModelBinding']['provider']}` / "
        f"`{run['runtimeModelBinding']['modelName']}` · display label: `{run['modelLabel']}` "
        f"· concurrency: {run['concurrency']}",
        f"- caseAccuracy: {metrics['caseAccuracy']:.6f} ({metrics['casePassHits']}/{metrics['caseDenominator']})",
        f"- resultFactAccuracy: {metrics['resultFactAccuracy']:.6f}",
        "- final answer text: non-scoring presentation output",
        f"- parse/execution/summary: {diagnostics['parseSuccessRate']:.6f} / {diagnostics['executionSuccessRate']:.6f} / {diagnostics['summarySuccessRate']:.6f}",
        "",
    ]
    partial = report.get("partialEvaluation")
    if isinstance(partial, dict):
        early_stop = partial.get("earlyStop") if isinstance(partial.get("earlyStop"), dict) else {}
        lines.extend(
            [
                "## Stop status",
                "",
                "This is a partial diagnostic and is not a promotable official score.",
                f"- Reason: `{early_stop.get('reason', 'STOPPED_EARLY')}`",
                f"- Failures: {early_stop.get('observedFailures')} (budget: {early_stop.get('maxFailures')})",
                "",
            ]
        )
    diagnostic = report.get("diagnosticEvaluation")
    if isinstance(diagnostic, dict):
        lines.extend(
            [
                "## Qualification",
                "",
                "This completed run used a failure budget and is diagnostic-only, not a promotable official score.",
                f"- Failure budget: {diagnostic.get('maxFailures')}",
                "",
            ]
        )
    lines.extend(["## Failed cases", ""])
    if failed:
        lines.extend(["| ID | Stage | Reason |", "| --- | --- | --- |"])
        for item in failed:
            lines.append(
                f"| {item['id']} | {item.get('errorCategory') or 'UNKNOWN'} | {item.get('reason') or 'unknown'} |"
            )
    else:
        lines.append("None.")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _cleanup_successful_conversations(
    report: dict[str, Any],
    *,
    post_json: Any,
    manage_api_prefix: str,
) -> None:
    prefix = "/" + manage_api_prefix.strip("/")
    for item in report["items"]:
        if not item.get("casePass") or item.get("conversationCleaned") is True:
            continue
        chat_id = item.get("chatId")
        if not isinstance(chat_id, int):
            continue
        try:
            cleaned = _unwrap_api_value(post_json(f"{prefix}/delete?chatId={chat_id}", {}))
            item["conversationCleaned"] = cleaned is not False
        except Exception as error:  # keep scored success, but leave cleanup evidence visible
            item["cleanupErrorType"] = type(error).__name__


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="Frozen evaluation/bank_nl2sql directory")
    parser.add_argument("--mode", choices=_MODES, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--agent-id", type=int, required=True)
    parser.add_argument("--chat-model-id", type=int, required=True)
    parser.add_argument(
        "--model-label",
        help="Optional display-only label; real selection always uses --chat-model-id",
    )
    parser.add_argument("--bootstrap-receipt", type=Path, required=True)
    parser.add_argument("--token-env", default=DEFAULT_TOKEN_ENV)
    parser.add_argument("--admin-username", default=DEFAULT_ADMIN_NAME)
    parser.add_argument("--admin-password-env", default=DEFAULT_ADMIN_PASSWORD_ENV)
    parser.add_argument("--evidence-root", type=Path)
    parser.add_argument(
        "--max-failures",
        type=int,
        help="Stop a serial train/dev diagnostic after failures exceed this budget; output is non-promotable",
    )
    parser.add_argument(
        "--resume",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Resume only a compatible official report in the same run directory",
    )
    args = parser.parse_args(argv)

    try:
        run_id = _validate_run_id(args.run_id)
        dataset_dir = args.dataset.resolve()
        if args.agent_id <= 0:
            raise OfficialRuntimeRunError("agent-id must be positive")
        if args.chat_model_id <= 0:
            raise OfficialRuntimeRunError("chat-model-id must be positive")
        if args.max_failures is not None and args.max_failures < 0:
            raise OfficialRuntimeRunError("max-failures must be zero or greater")
        if args.max_failures is not None and args.mode not in {"train", "dev"}:
            raise OfficialRuntimeRunError("max-failures is only valid for train or dev mode")

        profile, profile_sha256 = load_official_runtime_profile(dataset_dir)
        split, records = _records_for_mode(
            dataset_dir,
            profile=profile,
            mode=args.mode,
        )
        release = verify_official_runtime_release(dataset_dir, profile=profile, split=split)
        source = verify_source_checkout(dataset_dir, profile=profile)
        semantic_receipt = verify_bootstrap_receipt(
            args.bootstrap_receipt,
            dataset_version=release["datasetVersion"],
            agent_id=args.agent_id,
            official_manifest_sha256=release["officialManifestSha256"],
            database_counts=release["databaseCounts"],
        )
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
        runtime_model_binding = bind_runtime_chat_model(
            ApiClient(args.base_url, token),
            agent_id=args.agent_id,
            chat_model_id=args.chat_model_id,
        )
        model_label = (
            args.model_label.strip()
            if isinstance(args.model_label, str) and args.model_label.strip()
            else runtime_model_binding["modelName"]
        )

        evidence_root = (
            args.evidence_root.resolve()
            if args.evidence_root is not None
            else dataset_dir.parents[1] / ".local-dev" / "bank-nl2sql" / "official-v3"
        )
        run_dir = evidence_root / run_id
        output_path = run_dir / f"{args.mode}.json"
        markdown_path = run_dir / f"{args.mode}.md"
        base_run = _base_run_metadata(
            profile_sha256=profile_sha256,
            source=source,
            release=release,
            semantic_receipt=semantic_receipt,
            runtime_model_binding=runtime_model_binding,
            mode=args.mode,
            split=split,
            run_id=run_id,
            agent_id=args.agent_id,
            model_label=model_label,
            base_url=args.base_url,
            record_ids=[str(record["id"]) for record in records],
            max_failures=args.max_failures,
        )

        resumed_by_id = _load_resumed_items(output_path, expected_run=base_run, records=records) if args.resume else {}
        pending_records = [record for record in records if record["id"] not in resumed_by_id]
        completed_by_id: dict[str, dict[str, Any]] = dict(resumed_by_id)
        early_stop: dict[str, Any] | None = None

        def ordered_items() -> list[dict[str, Any]]:
            return [completed_by_id[str(record["id"])] for record in records if str(record["id"]) in completed_by_id]

        def checkpoint(item: dict[str, Any], _: int, __: int) -> None:
            nonlocal early_stop
            completed_by_id[str(item["id"])] = item
            partial_records = [record for record in records if str(record["id"]) in completed_by_id]
            provisional_capture = _capture_report(
                ordered_items(),
                _run_metadata(
                    base_run,
                    status="RUNNING",
                    completed_count=len(partial_records),
                    started_at=started_at,
                ),
                warmup=warmup_info,
            )
            provisional_report = build_official_runtime_report(provisional_capture, partial_records)
            early_stop = _early_stop_state(
                max_failures=args.max_failures,
                scored_items=provisional_report["items"],
            )
            status = "STOPPED_EARLY" if early_stop is not None else "RUNNING"
            partial_capture = _capture_report(
                ordered_items(),
                _run_metadata(
                    base_run,
                    status=status,
                    completed_count=len(partial_records),
                    started_at=started_at,
                    early_stop=early_stop,
                ),
                warmup=warmup_info,
            )
            partial_report = (
                _build_stopped_early_report(partial_capture, partial_records, early_stop=early_stop)
                if early_stop is not None
                else build_official_runtime_report(partial_capture, partial_records)
            )
            _write_json(output_path, partial_report)
            if early_stop is not None:
                _write_markdown(markdown_path, partial_report)

        capture = profile["capture"]
        post_json = _http_post_json(
            base_url=args.base_url,
            authorization_token=None,
            cookie=None,
            timeout_seconds=capture["timeoutSeconds"],
            network_retries=capture["networkRetries"],
            retry_backoff_seconds=float(capture["retryBackoffSeconds"]),
        )
        # Prefix materialization is deliberately outside the official run clock.  The first
        # real record must never be charged for loading the fixed bank prompt into the model KV
        # cache; the separate evidence is retained in the report for auditability.
        warmup_info = None
        if pending_records:
            try:
                warmup_info = warm_up_runtime_prefix(
                    post_json=post_json,
                    agent_id=args.agent_id,
                    query_api_prefix=DEFAULT_QUERY_API_PREFIX,
                    manage_api_prefix=DEFAULT_MANAGE_API_PREFIX,
                )
            except SuperSonicEvaluationError as error:
                warmup_info = {
                    "status": "FAILED",
                    "errorType": type(error).__name__,
                    "error": str(error),
                }
        started_at = time.time()
        if pending_records:
            run_supersonic_evaluation(
                pending_records,
                agent_id=args.agent_id,
                post_json=post_json,
                query_api_prefix=DEFAULT_QUERY_API_PREFIX,
                manage_api_prefix=DEFAULT_MANAGE_API_PREFIX,
                concurrency=capture["concurrency"],
                summary_timeout_seconds=float(capture["summaryTimeoutSeconds"]),
                summary_poll_interval_seconds=float(capture["summaryPollIntervalSeconds"]),
                result_only=bool(capture["resultOnly"]),
                cleanup_conversations=False,
                on_item_complete=checkpoint,
                stop_predicate=lambda _item, _completed, _total: early_stop is not None,
            )

        if early_stop is not None:
            partial_records = [record for record in records if str(record["id"]) in completed_by_id]
            stopped_capture = _capture_report(
                ordered_items(),
                _run_metadata(
                    base_run,
                    status="STOPPED_EARLY",
                    completed_count=len(partial_records),
                    started_at=started_at,
                    early_stop=early_stop,
                ),
                warmup=warmup_info,
            )
            stopped_report = _build_stopped_early_report(
                stopped_capture,
                partial_records,
                early_stop=early_stop,
            )
            _cleanup_successful_conversations(
                stopped_report,
                post_json=post_json,
                manage_api_prefix=DEFAULT_MANAGE_API_PREFIX,
            )
            _write_json(output_path, stopped_report)
            _write_markdown(markdown_path, stopped_report)
            print(
                json.dumps(
                    {
                        "mode": args.mode,
                        "output": str(output_path),
                        "summary": str(markdown_path),
                        "metrics": stopped_report["metrics"],
                        "runtimeDiagnostics": stopped_report["runtimeDiagnostics"],
                        "partialEvaluation": stopped_report["partialEvaluation"],
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                )
            )
            return 3

        if len(completed_by_id) != len(records):
            raise OfficialRuntimeRunError("runtime capture completed with an inconsistent record count")
        final_capture = _capture_report(
            ordered_items(),
            _run_metadata(base_run, status="COMPLETED", completed_count=len(records), started_at=started_at),
            warmup=warmup_info,
        )
        final_report = build_official_runtime_report(final_capture, records)
        _mark_bounded_diagnostic_report(final_report, max_failures=args.max_failures)
        _cleanup_successful_conversations(
            final_report,
            post_json=post_json,
            manage_api_prefix=DEFAULT_MANAGE_API_PREFIX,
        )
        _write_json(output_path, final_report)
        _write_markdown(markdown_path, final_report)

        print(
            json.dumps(
                {
                    "mode": args.mode,
                    "output": str(output_path),
                    "summary": str(markdown_path),
                    "metrics": final_report["metrics"],
                    "runtimeDiagnostics": final_report["runtimeDiagnostics"],
                },
                ensure_ascii=False,
                sort_keys=True,
            )
        )
        if args.mode == "smoke" and final_report["metrics"]["caseAccuracy"] != profile["smoke"]["requiredCaseAccuracy"]:
            return 2
        return 0
    except (
        BankAgentBootstrapError,
        OfficialRuntimeEvaluationError,
        OfficialRuntimeRunError,
        SuperSonicEvaluationError,
    ) as error:
        parser.error(str(error))
    return 2


if __name__ == "__main__":
    sys.exit(main())
