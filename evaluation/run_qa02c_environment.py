#!/usr/bin/env python3
"""Run QA-02C against a configured target environment without persisting secrets."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from run_qa02a import REPO_ROOT, _read_json, _sanitize


TASK = "QA-02C-ENVIRONMENT"
DEFAULT_CONFIG = REPO_ROOT / "evaluation" / "qa02c_environment.template.json"
DEFAULT_REPOSITORY_REPORT = REPO_ROOT / "task" / "QA-02C_REPOSITORY_ACCEPTANCE_REPORT.json"
DEFAULT_OUTPUT = REPO_ROOT / ".local-dev" / "bank-evaluation" / "qa02c-environment-report.json"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_LOG_BYTES = 50 * 1024 * 1024
DENY_STATUSES = {401, 403, 404}
REQUIRED_REQUIREMENTS = {
    "identity-attributes": {"identity-allow"},
    "production-permission-rules": {"resource-allow", "resource-deny"},
    "dynamic-masking": {"masking-allow", "masking-deny"},
    "audit-alert-organization": {"audit-allow", "audit-deny"},
    "controlled-export": {"export-owner", "export-deny"},
    "controlled-sharing": {"share-allow", "share-deny"},
    "history-model-input": {"history-deny", "model-input-deny"},
    "sensitive-data-egress": {"log-scan"},
}


class EnvironmentGateError(RuntimeError):
    """The target environment or its evidence is not ready for QA-02C."""


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        raise EnvironmentGateError("HTTP redirects are not allowed during the security gate")


def validate_config(value: dict[str, Any]) -> dict[str, Any]:
    if value.get("task") != TASK:
        raise EnvironmentGateError(f"config.task must be {TASK}")
    if not isinstance(value.get("baseUrlEnv"), str) or not value["baseUrlEnv"]:
        raise EnvironmentGateError("config.baseUrlEnv is required")
    actors = value.get("actors")
    scenarios = value.get("scenarios")
    if not isinstance(actors, dict) or not actors:
        raise EnvironmentGateError("config.actors must be a non-empty object")
    if not isinstance(scenarios, list) or not scenarios:
        raise EnvironmentGateError("config.scenarios must be a non-empty array")
    for name, actor in actors.items():
        if not isinstance(name, str) or not name or not isinstance(actor, dict):
            raise EnvironmentGateError("actor names and definitions must be valid")
        if not isinstance(actor.get("tokenEnv"), str) or not actor["tokenEnv"]:
            raise EnvironmentGateError(f"actor {name} requires tokenEnv")

    scenario_ids: set[str] = set()
    covered: dict[str, set[str]] = {control: set() for control in REQUIRED_REQUIREMENTS}
    for index, scenario in enumerate(scenarios):
        if not isinstance(scenario, dict):
            raise EnvironmentGateError(f"scenarios[{index}] must be an object")
        scenario_id = scenario.get("id")
        control = scenario.get("control")
        requirement = scenario.get("requirement")
        scenario_type = scenario.get("type")
        if not isinstance(scenario_id, str) or not scenario_id or scenario_id in scenario_ids:
            raise EnvironmentGateError(f"invalid or duplicate scenario id: {scenario_id!r}")
        if control not in REQUIRED_REQUIREMENTS:
            raise EnvironmentGateError(f"scenario {scenario_id} has unknown control")
        if requirement not in REQUIRED_REQUIREMENTS[control]:
            raise EnvironmentGateError(f"scenario {scenario_id} has invalid requirement")
        if scenario_type == "http":
            _validate_http_scenario(scenario, actors)
        elif scenario_type == "log":
            if requirement != "log-scan":
                raise EnvironmentGateError("only log-scan requirements may use log evidence")
            if not isinstance(scenario.get("fileEnv"), str) or not scenario["fileEnv"]:
                raise EnvironmentGateError(f"log scenario {scenario_id} requires fileEnv")
            forbidden = scenario.get("forbiddenEnvs")
            if not isinstance(forbidden, list) or not forbidden or not all(isinstance(name, str) and name for name in forbidden):
                raise EnvironmentGateError(f"log scenario {scenario_id} requires forbiddenEnvs")
        else:
            raise EnvironmentGateError(f"scenario {scenario_id} has unsupported type")
        scenario_ids.add(scenario_id)
        covered[control].add(requirement)
    missing = {
        control: sorted(requirements - covered[control])
        for control, requirements in REQUIRED_REQUIREMENTS.items()
        if requirements - covered[control]
    }
    if missing:
        raise EnvironmentGateError(f"required environment evidence is missing: {missing}")
    return value


def _validate_http_scenario(scenario: dict[str, Any], actors: dict[str, Any]) -> None:
    request = scenario.get("request")
    expect = scenario.get("expect")
    if not isinstance(request, dict) or not isinstance(expect, dict):
        raise EnvironmentGateError(f"HTTP scenario {scenario['id']} requires request and expect")
    if request.get("method") not in {"GET", "POST", "DELETE"}:
        raise EnvironmentGateError(f"HTTP scenario {scenario['id']} has unsupported method")
    path = request.get("path")
    if not isinstance(path, str) or not path.startswith("/") or ".." in path or "://" in path:
        raise EnvironmentGateError(f"HTTP scenario {scenario['id']} has unsafe path")
    if request.get("actor") not in actors:
        raise EnvironmentGateError(f"HTTP scenario {scenario['id']} references unknown actor")
    statuses = expect.get("statuses")
    if not isinstance(statuses, list) or not statuses or not all(isinstance(status, int) for status in statuses):
        raise EnvironmentGateError(f"HTTP scenario {scenario['id']} requires statuses")
    if scenario["requirement"].endswith("deny") and not set(statuses).issubset(DENY_STATUSES):
        raise EnvironmentGateError(f"deny scenario {scenario['id']} must require 401, 403 or 404")
    assertions = expect.get("jsonAssertions", [])
    if not isinstance(assertions, list):
        raise EnvironmentGateError(f"HTTP scenario {scenario['id']} has invalid assertions")
    for assertion in assertions:
        if not isinstance(assertion, dict) or assertion.get("operator") not in {
            "equals", "equalsEnv", "contains", "containsEnv", "exists", "truthy", "notEqualsEnv"
        } or not isinstance(assertion.get("path"), str):
            raise EnvironmentGateError(f"HTTP scenario {scenario['id']} has invalid JSON assertion")
        if assertion["operator"].endswith("Env") and not isinstance(assertion.get("env"), str):
            raise EnvironmentGateError(f"HTTP scenario {scenario['id']} assertion requires env")
        if assertion["operator"] in {"equals", "contains"} and "value" not in assertion:
            raise EnvironmentGateError(f"HTTP scenario {scenario['id']} assertion requires value")
    forbidden = expect.get("bodyNotContainsEnvs", [])
    if not isinstance(forbidden, list) or not all(isinstance(name, str) and name for name in forbidden):
        raise EnvironmentGateError(f"HTTP scenario {scenario['id']} has invalid protected values")


ENV_PATTERN = re.compile(r"\$\{([A-Z][A-Z0-9_]*)\}")


def expand_env(value: Any, environ: dict[str, str]) -> Any:
    if isinstance(value, str):
        def replace(match: re.Match[str]) -> str:
            name = match.group(1)
            secret = environ.get(name)
            if not secret:
                raise EnvironmentGateError(f"required environment variable is missing: {name}")
            return secret
        return ENV_PATTERN.sub(replace, value)
    if isinstance(value, list):
        return [expand_env(item, environ) for item in value]
    if isinstance(value, dict):
        return {key: expand_env(item, environ) for key, item in value.items()}
    return value


def required_env(name: str, environ: dict[str, str]) -> str:
    value = environ.get(name)
    if not value:
        raise EnvironmentGateError(f"required environment variable is missing: {name}")
    return value


def json_path(value: Any, path: str) -> tuple[bool, Any]:
    if path == "$":
        return True, value
    if not path.startswith("$."):
        return False, None
    current = value
    for token in path[2:].split("."):
        match = re.fullmatch(r"([^\[]+)(?:\[(\d+)\])?", token)
        if not match or not isinstance(current, dict) or match.group(1) not in current:
            return False, None
        current = current[match.group(1)]
        if match.group(2) is not None:
            index = int(match.group(2))
            if not isinstance(current, list) or index >= len(current):
                return False, None
            current = current[index]
    return True, current


def assert_json(payload: Any, assertion: dict[str, Any], environ: dict[str, str]) -> None:
    exists, actual = json_path(payload, assertion["path"])
    operator = assertion["operator"]
    if operator == "exists":
        if not exists:
            raise EnvironmentGateError(f"JSON assertion failed at {assertion['path']}: missing")
        return
    if not exists:
        raise EnvironmentGateError(f"JSON assertion failed at {assertion['path']}: missing")
    expected = assertion.get("value")
    if operator.endswith("Env"):
        expected = required_env(assertion.get("env", ""), environ)
    passed = {
        "equals": actual == expected,
        "equalsEnv": str(actual) == expected,
        "contains": isinstance(actual, (str, list, dict)) and expected in actual,
        "containsEnv": isinstance(actual, (str, list, dict)) and expected in actual,
        "truthy": bool(actual),
        "notEqualsEnv": str(actual) != expected,
    }.get(operator, False)
    if not passed:
        raise EnvironmentGateError(f"JSON assertion failed at {assertion['path']} ({operator})")


def actor_tokens(config: dict[str, Any], environ: dict[str, str]) -> dict[str, str]:
    return {name: required_env(actor["tokenEnv"], environ) for name, actor in config["actors"].items()}


def run_http_scenario(
    scenario: dict[str, Any], base_url: str, tokens: dict[str, str], environ: dict[str, str],
    sensitive_envs: list[str],
) -> dict[str, Any]:
    started = time.perf_counter()
    try:
        request_config = expand_env(scenario["request"], environ)
        token = tokens[request_config["actor"]]
        expanded_path = request_config["path"]
        parsed_path = urllib.parse.urlsplit(expanded_path)
        if parsed_path.scheme or parsed_path.netloc or not parsed_path.path.startswith("/") or ".." in parsed_path.path or "\r" in expanded_path or "\n" in expanded_path:
            raise EnvironmentGateError(f"expanded request path is unsafe: {scenario['id']}")
        url = base_url.rstrip("/") + expanded_path
        headers = {"Accept": "application/json", "Authorization": f"Bearer {token}", "auth": f"Bearer {token}"}
        data = None
        if "jsonBody" in request_config:
            data = json.dumps(request_config["jsonBody"], ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers, method=request_config["method"])
        opener = urllib.request.build_opener(NoRedirectHandler(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
        try:
            with opener.open(request, timeout=15) as response:
                status = response.status
                body = response.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            try:
                status = error.code
                body = error.read(MAX_RESPONSE_BYTES + 1)
            finally:
                error.close()
        if len(body) > MAX_RESPONSE_BYTES:
            raise EnvironmentGateError("response exceeds 2 MB security gate limit")
        if status not in scenario["expect"]["statuses"]:
            raise EnvironmentGateError(f"unexpected HTTP status: {status}")
        text = body.decode("utf-8", errors="replace")
        forbidden_envs = set(sensitive_envs) | set(scenario["expect"].get("bodyNotContainsEnvs", []))
        forbidden_values = [required_env(name, environ) for name in forbidden_envs]
        forbidden_values.extend(tokens.values())
        if any(value and value in text for value in forbidden_values):
            raise EnvironmentGateError("response contains a protected environment value")
        assertions = scenario["expect"].get("jsonAssertions", [])
        if assertions:
            try:
                payload = json.loads(text)
            except json.JSONDecodeError as error:
                raise EnvironmentGateError("response is not valid JSON") from error
            for assertion in assertions:
                assert_json(payload, assertion, environ)
        return {"id": scenario["id"], "control": scenario["control"], "requirement": scenario["requirement"], "type": "http", "status": "PASS", "httpStatus": status, "durationMs": round((time.perf_counter() - started) * 1000)}
    except Exception as error:
        return {"id": scenario["id"], "control": scenario["control"], "requirement": scenario["requirement"], "type": "http", "status": "FAIL", "durationMs": round((time.perf_counter() - started) * 1000), "message": _sanitize(f"{type(error).__name__}: {error}")}


def run_log_scenario(
    scenario: dict[str, Any], tokens: dict[str, str], environ: dict[str, str],
    sensitive_envs: list[str],
) -> dict[str, Any]:
    started = time.perf_counter()
    try:
        path = Path(required_env(scenario["fileEnv"], environ)).resolve()
        size = path.stat().st_size
        if size > MAX_LOG_BYTES:
            raise EnvironmentGateError("log evidence exceeds 50 MB limit")
        content = path.read_text(encoding="utf-8", errors="replace")
        forbidden_names = set(sensitive_envs) | set(scenario["forbiddenEnvs"])
        values = list(tokens.values()) + [required_env(name, environ) for name in forbidden_names]
        if any(value and value in content for value in values):
            raise EnvironmentGateError("log evidence contains a protected environment value")
        return {"id": scenario["id"], "control": scenario["control"], "requirement": scenario["requirement"], "type": "log", "status": "PASS", "bytesScanned": size, "durationMs": round((time.perf_counter() - started) * 1000)}
    except Exception as error:
        return {"id": scenario["id"], "control": scenario["control"], "requirement": scenario["requirement"], "type": "log", "status": "FAIL", "durationMs": round((time.perf_counter() - started) * 1000), "message": _sanitize(f"{type(error).__name__}: {error}")}


def build_report(config: dict[str, Any], results: list[dict[str, Any]], repository_report: dict[str, Any], repository_hash: str, transport: str) -> dict[str, Any]:
    by_id = {result["id"]: result for result in results}
    failures = []
    for scenario in config["scenarios"]:
        result = by_id.get(scenario["id"])
        if not result:
            failures.append({"category": "EVIDENCE_MISSING", "subject": scenario["id"], "message": "scenario did not produce a result"})
        elif result["status"] != "PASS":
            failures.append({"category": "EVIDENCE_FAILURE", "subject": scenario["id"], "message": result.get("message", "scenario failed")})
    controls = []
    for control, requirements in REQUIRED_REQUIREMENTS.items():
        matching = [result for result in results if result["control"] == control]
        covered = {result["requirement"] for result in matching if result["status"] == "PASS"}
        controls.append({"id": control, "requiredEvidenceCount": len(requirements), "evidenceCount": len(matching), "status": "PASS" if requirements.issubset(covered) else "FAIL"})
    status = "PASS" if not failures and all(control["status"] == "PASS" for control in controls) else "FAIL"
    return {
        "schemaVersion": "1.0",
        "task": TASK,
        "scope": "ENVIRONMENT",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "transport": transport,
        "repositoryGate": {"task": repository_report["task"], "generatedAt": repository_report["generatedAt"], "sha256": repository_hash},
        "summary": {"controlCount": len(controls), "passedControlCount": sum(control["status"] == "PASS" for control in controls), "evidenceCount": len(config["scenarios"]), "passedEvidenceCount": sum(result["status"] == "PASS" for result in results), "failureCount": len(failures)},
        "controls": controls,
        "evidence": results,
        "failures": failures,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--repository-report", type=Path, default=DEFAULT_REPOSITORY_REPORT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--allow-http", action="store_true", help="Only for isolated local test servers")
    return parser


def main(argv: list[str] | None = None, *, environ: dict[str, str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    env = dict(os.environ if environ is None else environ)
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        config = validate_config(_read_json(args.config.resolve()))
        repository_bytes = args.repository_report.resolve().read_bytes()
        repository_report = json.loads(repository_bytes.decode("utf-8"))
        if (
            repository_report.get("task") != "QA-02C"
            or repository_report.get("scope") != "REPOSITORY"
            or repository_report.get("status") != "PASS"
            or repository_report.get("environmentGateRequired") is not True
        ):
            raise EnvironmentGateError("a passing QA-02C repository report is required")
        base_url = required_env(config["baseUrlEnv"], env).rstrip("/")
        if not base_url.startswith("https://"):
            if not args.allow_http or not re.fullmatch(r"http://(?:localhost|127\.0\.0\.1)(?::\d+)?", base_url):
                raise EnvironmentGateError("target base URL must use HTTPS")
            transport = "HTTP_LOCAL_TEST"
        else:
            transport = "HTTPS"
        tokens = actor_tokens(config, env)
        sensitive_envs = config.get("sensitiveValueEnvs", [])
        if not isinstance(sensitive_envs, list) or not all(isinstance(name, str) and name for name in sensitive_envs):
            raise EnvironmentGateError("config.sensitiveValueEnvs must contain environment variable names")
        results = []
        for scenario in config["scenarios"]:
            if scenario["type"] == "http":
                results.append(run_http_scenario(scenario, base_url, tokens, env, sensitive_envs))
            else:
                results.append(run_log_scenario(scenario, tokens, env, sensitive_envs))
        report = build_report(config, results, repository_report, hashlib.sha256(repository_bytes).hexdigest(), transport)
        exit_code = 0 if report["status"] == "PASS" else 1
    except Exception as error:
        report = {"schemaVersion": "1.0", "task": TASK, "scope": "ENVIRONMENT", "generatedAt": datetime.now(timezone.utc).isoformat(), "status": "FAIL", "summary": {"failureCount": 1}, "failures": [{"category": "RUNNER_FAILURE", "subject": "runner", "message": _sanitize(f"{type(error).__name__}: {error}")}]}
        exit_code = 2
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"task": TASK, "status": report["status"], "scope": report.get("scope"), "report": str(args.output), "summary": report.get("summary")}, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
