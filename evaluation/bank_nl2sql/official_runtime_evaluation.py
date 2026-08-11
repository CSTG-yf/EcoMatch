#!/usr/bin/env python3
"""Build the single public Bank NL2SQL runtime evaluation report.

The transport runner captures the same independent-conversation API sequence as
the web client.  This module is the sole scoring boundary: it converts that
capture into a full-denominator Fact v3 report and deliberately drops every
legacy score and table-shape field from the published result.
"""

from __future__ import annotations

from collections import Counter
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

from fact_contract_v3 import score_fact_contract_report


OFFICIAL_RUNTIME_SCHEMA_VERSION = "3.1.0"
OFFICIAL_RUNTIME_PROFILE = "official_runtime_evaluation_v3.json"
OFFICIAL_DATASET_VERSION = "2.0.2"
OFFICIAL_MINIMUM_SOURCE_COMMIT = "565bc74ed313acff1b192aef4ab9a974893e1a53"
OFFICIAL_SMOKE_IDS = (
    "TRAIN-S-01",
    "TRAIN-M-01",
    "TRAIN-H-01",
    "TRAIN-H-04",
    "TRAIN-H-07",
)
OFFICIAL_SPLIT_COUNTS = {"train": 119, "dev": 40, "test": 40}

_METRIC_KEYS = (
    "caseAccuracy",
    "casePassHits",
    "caseDenominator",
    "resultFactAccuracy",
    "resultFactsExactHits",
    "contractReadyRate",
    "contractReadyCount",
)

_RUNTIME_ITEM_KEYS = (
    "id",
    "difficulty",
    "sqlFeatures",
    "parse",
    "execute",
    "parseMs",
    "executeMs",
    "summaryMs",
    "endToEndMs",
    "summaryState",
    "textSummary",
    "finalAnswerTrace",
    "errorType",
    "backendError",
    "summaryErrorType",
    "s2sql",
    "physicalSql",
    "bankRouting",
    "resultColumns",
    "resultRows",
    "chatId",
    "queryId",
    "conversationCleaned",
    "cleanupErrorType",
)

_SCORE_ITEM_KEYS = (
    "contractStatus",
    "contractReasons",
    "resultExact",
    "resultFactsExact",
    "resultEvidence",
    "casePass",
    "reason",
)

_FACT_FAILURE_CATEGORIES = {
    "missing_prediction": "MISSING_PREDICTION",
    "contract_review_required": "CONTRACT_REVIEW_REQUIRED",
    "result_mismatch": "RESULT_FACT_MISMATCH",
}


class OfficialRuntimeEvaluationError(ValueError):
    """The capture cannot be promoted into a comparable official report."""


def _read_json(path: Path, *, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise OfficialRuntimeEvaluationError(f"cannot read {label}: {path}") from error
    if not isinstance(value, dict):
        raise OfficialRuntimeEvaluationError(f"{label} must be a JSON object")
    return value


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_official_runtime_profile(dataset_dir: Path | str) -> tuple[dict[str, Any], str]:
    """Load the fixed protocol profile and reject a substituted release."""

    dataset_root = Path(dataset_dir).resolve()
    profile_path = dataset_root / OFFICIAL_RUNTIME_PROFILE
    profile = _read_json(profile_path, label="official runtime profile")
    if profile.get("schemaVersion") != "1.0":
        raise OfficialRuntimeEvaluationError("unsupported official runtime profile schemaVersion")
    if profile.get("protocolVersion") != OFFICIAL_RUNTIME_SCHEMA_VERSION:
        raise OfficialRuntimeEvaluationError("official runtime profile protocolVersion is invalid")
    dataset_version = profile.get("datasetVersion")
    if dataset_version != OFFICIAL_DATASET_VERSION:
        raise OfficialRuntimeEvaluationError("official runtime profile datasetVersion is invalid")
    minimum_commit = profile.get("minimumSourceCommit")
    if minimum_commit != OFFICIAL_MINIMUM_SOURCE_COMMIT:
        raise OfficialRuntimeEvaluationError("official runtime profile minimumSourceCommit is invalid")
    capture = profile.get("capture")
    if (
        not isinstance(capture, dict)
        or capture.get("method") != "openapi-frontend-conversation-chain"
        or capture.get("perRecordConversation") != "isolated"
        or capture.get("concurrency") != 1
    ):
        raise OfficialRuntimeEvaluationError("official runtime profile must require serial execution")
    for key in ("timeoutSeconds", "summaryTimeoutSeconds", "networkRetries"):
        if not isinstance(capture.get(key), int) or capture[key] < 1:
            raise OfficialRuntimeEvaluationError(f"official runtime profile {key} is invalid")
    for key in ("summaryPollIntervalSeconds", "retryBackoffSeconds"):
        if not isinstance(capture.get(key), (int, float)) or capture[key] <= 0:
            raise OfficialRuntimeEvaluationError(f"official runtime profile {key} is invalid")
    score = profile.get("score")
    if score != {
        "primaryMetric": "caseAccuracy",
        "casePass": "resultExact",
        "denominator": "ALL_SELECTED_RECORDS",
        "sqlTextScored": False,
        "finalAnswerScored": False,
    }:
        raise OfficialRuntimeEvaluationError("official runtime profile score contract is invalid")
    smoke = profile.get("smoke")
    if (
        not isinstance(smoke, dict)
        or smoke.get("split") != "train"
        or smoke.get("recordIds") != list(OFFICIAL_SMOKE_IDS)
        or smoke.get("requiredCaseAccuracy") != 1.0
    ):
        raise OfficialRuntimeEvaluationError("official runtime profile smoke contract is invalid")
    splits = profile.get("splits")
    if splits != OFFICIAL_SPLIT_COUNTS:
        raise OfficialRuntimeEvaluationError("official runtime profile split counts are invalid")
    return profile, _sha256_file(profile_path)


def verify_bootstrap_receipt(
    receipt_path: Path | str,
    *,
    dataset_version: str,
    agent_id: int,
    official_manifest_sha256: str,
    database_counts: dict[str, int],
) -> dict[str, Any]:
    """Require evidence that this Agent was bootstrapped for the same release.

    The receipt contains IDs and configuration fingerprints only.  It never
    stores a bearer token, a model endpoint, or a model secret.
    """

    receipt = _read_json(Path(receipt_path), label="bootstrap receipt")
    if receipt.get("receiptSchemaVersion") != "1.0":
        raise OfficialRuntimeEvaluationError("bootstrap receipt schemaVersion is invalid")
    if receipt.get("officialVersion") != dataset_version:
        raise OfficialRuntimeEvaluationError("bootstrap receipt dataset version does not match")
    if receipt.get("officialManifestSha256") != official_manifest_sha256:
        raise OfficialRuntimeEvaluationError("bootstrap receipt official manifest does not match")
    if receipt.get("agentId") != agent_id:
        raise OfficialRuntimeEvaluationError("bootstrap receipt agentId does not match")
    for key in ("modelId", "chatModelId", "dataSetId"):
        if not isinstance(receipt.get(key), int) or receipt[key] <= 0:
            raise OfficialRuntimeEvaluationError(f"bootstrap receipt {key} is invalid")
    for key in ("agentProfileSha256", "systemParametersSha256"):
        value = receipt.get(key)
        if not isinstance(value, str) or len(value) != 64:
            raise OfficialRuntimeEvaluationError(f"bootstrap receipt {key} is invalid")
    semantic_import = receipt.get("semanticImport")
    if not isinstance(semantic_import, dict):
        raise OfficialRuntimeEvaluationError("bootstrap receipt semanticImport is invalid")
    expected_import_counts = {
        "organizations": database_counts.get("organizations"),
        "indicators": database_counts.get("metrics"),
        "factsValidated": database_counts.get("facts"),
    }
    for key, expected in expected_import_counts.items():
        if not isinstance(expected, int) or expected < 1:
            raise OfficialRuntimeEvaluationError("database package counts are invalid")
        if semantic_import.get(key) != expected:
            raise OfficialRuntimeEvaluationError(
                f"bootstrap receipt semanticImport {key} does not match the official database package"
            )
    return {
        "agentId": agent_id,
        "modelId": receipt["modelId"],
        "chatModelId": receipt["chatModelId"],
        "dataSetId": receipt["dataSetId"],
        "officialManifestSha256": official_manifest_sha256,
        "semanticImport": {key: semantic_import[key] for key in expected_import_counts},
        "agentProfileSha256": receipt["agentProfileSha256"],
        "systemParametersSha256": receipt["systemParametersSha256"],
    }


def verify_official_runtime_release(
    dataset_dir: Path | str,
    *,
    profile: dict[str, Any],
    split: str,
) -> dict[str, Any]:
    """Verify only the selected split plus public package integrity metadata.

    Train/dev runs never read the frozen test JSONL merely to prepare an
    evaluation.  The database import artifacts are checked by hash because
    they are part of the reproducible runtime package, not prediction gold.
    """

    dataset_root = Path(dataset_dir).resolve()
    normalized_split = split.strip().lower()
    if normalized_split not in {"train", "dev", "test"}:
        raise OfficialRuntimeEvaluationError(f"unsupported official split: {split}")
    current = _read_json(dataset_root / "official" / "CURRENT.json", label="official CURRENT.json")
    release = _read_json(dataset_root / "release_manifest.json", label="release manifest")
    expected_version = profile["datasetVersion"]
    if current.get("currentVersion") != expected_version or release.get("version") != expected_version:
        raise OfficialRuntimeEvaluationError("official dataset version does not match the runtime profile")
    official_directory = current.get("directory")
    if official_directory != expected_version:
        raise OfficialRuntimeEvaluationError("official CURRENT directory does not match the runtime profile")
    official_manifest_path = dataset_root / "official" / expected_version / "official-manifest.json"
    if not official_manifest_path.is_file():
        raise OfficialRuntimeEvaluationError("official manifest is missing from the current release")
    # Official-release sidecars use upper-case SHA-256, and bootstrap receipts
    # record that canonical release identity rather than a checkout-specific
    # formatting variant.
    official_manifest_sha256 = _sha256_file(official_manifest_path).upper()

    declared_hashes = release.get("contentSha256")
    if not isinstance(declared_hashes, dict):
        raise OfficialRuntimeEvaluationError("release manifest contentSha256 is invalid")
    required_assets = ("manifest.json", "schema.json", "gold_manifest.json", f"{normalized_split}.jsonl")
    checked_assets: dict[str, str] = {}
    for name in required_assets:
        path = dataset_root / name
        expected_hash = declared_hashes.get(name)
        if not isinstance(expected_hash, str) or not path.is_file():
            raise OfficialRuntimeEvaluationError(f"release asset is missing from the verified manifest: {name}")
        actual_hash = _sha256_file(path)
        if actual_hash != expected_hash:
            raise OfficialRuntimeEvaluationError(f"release asset SHA-256 mismatch: {name}")
        checked_assets[name] = actual_hash

    actual_count = sum(1 for line in (dataset_root / f"{normalized_split}.jsonl").read_text(encoding="utf-8").splitlines() if line.strip())
    expected_count = profile["splits"][normalized_split]
    if actual_count != expected_count:
        raise OfficialRuntimeEvaluationError(
            f"selected split count mismatch: expected {expected_count}, got {actual_count}"
        )

    database_dir = dataset_root / "db" / "releases" / expected_version
    database_manifest = _read_json(database_dir / "database-manifest.json", label="database manifest")
    if database_manifest.get("officialVersion") != expected_version:
        raise OfficialRuntimeEvaluationError("database package version does not match the runtime profile")
    artifacts = database_manifest.get("artifacts")
    if not isinstance(artifacts, dict):
        raise OfficialRuntimeEvaluationError("database package artifacts are invalid")
    database_hashes: dict[str, str] = {}
    for name in ("bank.sqlite", "bank-h2.sql"):
        declared = artifacts.get(name)
        path = database_dir / name
        if not isinstance(declared, dict) or not path.is_file():
            raise OfficialRuntimeEvaluationError(f"database package artifact is missing: {name}")
        actual_hash = _sha256_file(path)
        if actual_hash != declared.get("sha256"):
            raise OfficialRuntimeEvaluationError(f"database package SHA-256 mismatch: {name}")
        if path.stat().st_size != declared.get("bytes"):
            raise OfficialRuntimeEvaluationError(f"database package size mismatch: {name}")
        database_hashes[name] = actual_hash
    database_counts = database_manifest.get("counts")
    if (
        not isinstance(database_counts, dict)
        or any(
            not isinstance(database_counts.get(key), int) or database_counts[key] < 1
            for key in ("organizations", "metrics", "facts")
        )
    ):
        raise OfficialRuntimeEvaluationError("database package counts are invalid")

    return {
        "datasetVersion": expected_version,
        "officialManifestSha256": official_manifest_sha256,
        "split": normalized_split,
        "recordCount": actual_count,
        "checkedAssets": checked_assets,
        "databaseArtifacts": database_hashes,
        "databaseCounts": database_counts,
    }


def verify_source_checkout(dataset_dir: Path | str, *, profile: dict[str, Any]) -> dict[str, str]:
    """Require a clean checkout descended from the protocol's release base."""

    repo_root = Path(dataset_dir).resolve().parents[1]

    def run_git(*args: str) -> str:
        completed = subprocess.run(
            ["git", "-C", str(repo_root), *args],
            check=False,
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            raise OfficialRuntimeEvaluationError(f"Git preflight failed: {' '.join(args)}")
        return completed.stdout.strip()

    if run_git("status", "--porcelain"):
        raise OfficialRuntimeEvaluationError("official evaluation requires a clean Git worktree")
    revision = run_git("rev-parse", "HEAD")
    required_base = str(profile["minimumSourceCommit"])
    completed = subprocess.run(
        ["git", "-C", str(repo_root), "merge-base", "--is-ancestor", required_base, revision],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise OfficialRuntimeEvaluationError("source revision does not include the official evaluation base")
    return {"sourceRevision": revision, "requiredBaseRevision": required_base}


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _validated_capture_items(
    capture_report: dict[str, Any], records: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[str]]:
    raw_items = capture_report.get("items")
    if not isinstance(raw_items, list):
        raise OfficialRuntimeEvaluationError("capture report must contain items[]")
    if not all(isinstance(item, dict) and isinstance(item.get("id"), str) for item in raw_items):
        raise OfficialRuntimeEvaluationError("every capture item must contain a string id")

    expected_ids = [str(record.get("id") or "") for record in records]
    if not all(expected_ids) or len(set(expected_ids)) != len(expected_ids):
        raise OfficialRuntimeEvaluationError("evaluation records must have unique non-empty ids")
    captured_ids = [str(item["id"]) for item in raw_items]
    if captured_ids != expected_ids:
        raise OfficialRuntimeEvaluationError(
            "capture ids must exactly match the selected evaluation records in dataset order"
        )
    return raw_items, expected_ids


def _runtime_diagnostics(
    capture_report: dict[str, Any], items: list[dict[str, Any]]
) -> dict[str, Any]:
    count = len(items)
    errors = Counter(
        str(item.get("errorCategory") or "NONE")
        for item in items
    )
    final_answer_states = Counter(
        str((item.get("finalAnswerTrace") or {}).get("status") or "MISSING")
        for item in items
    )
    return {
        "parseSuccessRate": _rate(sum(bool(item.get("parse")) for item in items), count),
        "executionSuccessRate": _rate(sum(bool(item.get("execute")) for item in items), count),
        "summarySuccessRate": _rate(
            sum(item.get("summaryState") == "SUCCESS" for item in items), count
        ),
        "finalAnswerProcessorSuccessRate": _rate(
            sum(
                isinstance(item.get("finalAnswerTrace"), dict)
                and item["finalAnswerTrace"].get("status") == "SUCCEEDED"
                for item in items
            ),
            count,
        ),
        "finalAnswerProcessorStates": dict(sorted(final_answer_states.items())),
        "errorCategories": dict(sorted(errors.items())),
        "timingMs": capture_report.get("timingMs"),
        "timingDistributionsMs": capture_report.get("timingDistributionsMs"),
    }


def _public_item(runtime_item: dict[str, Any], scored_item: dict[str, Any]) -> dict[str, Any]:
    item = {
        key: runtime_item[key]
        for key in _RUNTIME_ITEM_KEYS
        if key in runtime_item
    }
    item.update(
        {
            key: scored_item[key]
            for key in _SCORE_ITEM_KEYS
            if key in scored_item
        }
    )
    runtime_error = runtime_item.get("errorCategory")
    if runtime_error in {"RESULT_MISMATCH", "ANSWER_SLOT_MISS"}:
        runtime_error = None
    if isinstance(runtime_error, str) and runtime_error:
        item["errorCategory"] = runtime_error
    else:
        item["errorCategory"] = _FACT_FAILURE_CATEGORIES.get(scored_item.get("reason"))
    return item


def _validate_final_answer_attestation(capture_items: list[dict[str, Any]]) -> None:
    for item in capture_items:
        trace = item.get("finalAnswerTrace")
        if not isinstance(trace, dict):
            raise OfficialRuntimeEvaluationError(
                "runtime capture lacks final-answer stage attestation; deploy the current backend "
                "and bootstrap the Agent before evaluation"
            )
        if (
            not isinstance(trace.get("status"), str)
            or not isinstance(trace.get("attempts"), int)
            or not isinstance(trace.get("errors"), list)
            or not all(isinstance(error, str) for error in trace["errors"])
        ):
            raise OfficialRuntimeEvaluationError("runtime capture final-answer stage attestation is invalid")


def build_official_runtime_report(
    capture_report: dict[str, Any], records: list[dict[str, Any]]
) -> dict[str, Any]:
    """Promote a complete frontend-chain capture to the sole Fact v3 report.

    A partial, reordered, or duplicated capture has no stable denominator and
    therefore cannot be published as an official result.
    """

    capture_items, expected_ids = _validated_capture_items(capture_report, records)
    fact_report = score_fact_contract_report(
        capture_report,
        records,
        score_mode="result_only",
    )
    scored_items = fact_report.get("items")
    if not isinstance(scored_items, list):
        raise OfficialRuntimeEvaluationError("Fact v3 scorer did not return items[]")
    scored_by_id = {
        str(item.get("id")): item
        for item in scored_items
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }
    if list(scored_by_id) != expected_ids:
        raise OfficialRuntimeEvaluationError("Fact v3 scorer did not preserve the selected denominator")

    metrics_in = fact_report.get("metrics")
    if not isinstance(metrics_in, dict) or any(key not in metrics_in for key in _METRIC_KEYS):
        raise OfficialRuntimeEvaluationError("Fact v3 scorer did not return the official metric set")

    public_items = [
        _public_item(runtime_item, scored_by_id[sample_id])
        for runtime_item, sample_id in zip(capture_items, expected_ids)
    ]
    return {
        "schemaVersion": OFFICIAL_RUNTIME_SCHEMA_VERSION,
        "protocol": {
            "name": "official-bank-runtime-evaluation",
            "scoreVersion": "fact-contract-v3",
            "captureMethod": "openapi-frontend-conversation-chain",
            "perRecordConversation": "isolated",
        },
        "metrics": {key: metrics_in[key] for key in _METRIC_KEYS},
        "policy": {
            "primaryMetric": "caseAccuracy",
            "casePass": "resultExact",
            "denominator": "ALL_SELECTED_RECORDS",
            "sqlTextScored": False,
            "finalAnswerScored": False,
        },
        "run": capture_report.get("run"),
        "runtimeDiagnostics": _runtime_diagnostics(capture_report, public_items),
        "items": public_items,
    }
