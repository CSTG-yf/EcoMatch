#!/usr/bin/env python3
"""Contract tests for the QA-03 semantic cache acceptance runner."""

from __future__ import annotations

import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from run_qa03_cache_eval import (  # noqa: E402
    Qa03CacheEvaluationError,
    _http_json_clients,
    _prepare_payload,
    run_cache_evaluation,
)


def monitor_snapshot(*, hits: int, misses: int, completed: int) -> dict:
    return {
        "code": 200,
        "data": {
            "cache": {
                "hits": hits,
                "misses": misses,
                "requests": hits + misses,
                "hotMetricHits": 0,
                "hotMetricMisses": 0,
                "hotMetricRequests": 0,
            },
            "gateway": {
                "acceptedQueries": completed,
                "rejectedQueries": 0,
                "completedQueries": completed,
                "failedQueries": 0,
            },
            "stages": {
                "execute": {
                    "count": completed,
                    "totalTimeMs": completed * 5.0,
                }
            },
        },
    }


class Qa03CacheEvaluationTest(unittest.TestCase):
    def test_http_client_keeps_credentials_in_headers_and_discards_error_body(
        self,
    ) -> None:
        captured_headers: dict[str, str] = {}
        secret_token = "qa03-secret-token"
        secret_cookie = "qa03-secret-cookie"
        secret_error = "response contains customer account 6222000011112222"

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                captured_headers["authorization"] = self.headers.get("Authorization", "")
                captured_headers["cookie"] = self.headers.get("Cookie", "")
                if self.path == "/ok":
                    body = b'{"ok":true}'
                    self.send_response(200)
                elif self.path == "/redirect":
                    body = b"redirect"
                    self.send_response(302)
                    self.send_header("Location", "/ok")
                else:
                    body = secret_error.encode("utf-8")
                    self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, _format: str, *_args: object) -> None:
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            _, get_json = _http_json_clients(
                base_url=f"http://127.0.0.1:{server.server_port}",
                authorization_token=secret_token,
                cookie=secret_cookie,
                timeout_seconds=2,
            )

            self.assertEqual(get_json("/ok"), {"ok": True})
            self.assertEqual(
                captured_headers["authorization"],
                f"Bearer {secret_token}",
            )
            self.assertEqual(captured_headers["cookie"], secret_cookie)
            with self.assertRaises(Qa03CacheEvaluationError) as caught:
                get_json("/error")
            self.assertNotIn(secret_error, str(caught.exception))
            self.assertNotIn(secret_token, str(caught.exception))
            self.assertNotIn(secret_cookie, str(caught.exception))
            with self.assertRaisesRegex(Qa03CacheEvaluationError, "status 302"):
                get_json("/redirect")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)

    def test_verifies_unique_cold_request_and_repeated_warm_hits_without_data_leakage(
        self,
    ) -> None:
        requests: list[dict] = []
        monitor_responses = iter(
            [
                monitor_snapshot(hits=10, misses=5, completed=20),
                monitor_snapshot(hits=14, misses=6, completed=21),
            ]
        )

        def get_json(path: str) -> dict:
            self.assertEqual(path, "/api/semantic/query/gateway/stats")
            return next(monitor_responses)

        def post_json(path: str, payload: dict) -> dict:
            self.assertEqual(path, "/api/semantic/query/sql")
            requests.append(payload)
            return {
                "code": 200,
                "data": {
                    "useCache": len(requests) > 1,
                    "resultList": [{"account_no": "6222000012345678"}],
                },
            }

        report = run_cache_evaluation(
            {
                "sql": "SELECT account_no FROM bank_account WHERE customer_name = 'Alice'",
                "modelIds": [7],
                "needAuth": False,
                "innerLayerNative": True,
            },
            scenario="account-query",
            post_json=post_json,
            get_json=get_json,
            warm_samples=3,
        )

        self.assertEqual(len(requests), 5)
        self.assertTrue(all(request["cacheInfo"] == {"cache": True} for request in requests))
        self.assertTrue(all("qa03-cache-account-query-" in request["sql"] for request in requests))
        self.assertEqual(len({request["sql"] for request in requests}), 1)
        self.assertTrue(all("needAuth" not in request for request in requests))
        self.assertTrue(all("innerLayerNative" not in request for request in requests))
        self.assertEqual(report["warm"]["verifiedHits"], 3)
        self.assertEqual(report["monitorDelta"]["cache"]["hits"], 4)
        self.assertEqual(report["monitorDelta"]["cache"]["misses"], 1)
        self.assertEqual(report["monitorDelta"]["gateway"]["completedQueries"], 1)

        serialized = json.dumps(report)
        self.assertNotIn("account_no", serialized)
        self.assertNotIn("Alice", serialized)
        self.assertNotIn("6222000012345678", serialized)

    def test_rejects_invalid_scenario_and_unexpected_cold_hit(self) -> None:
        with self.assertRaises(Qa03CacheEvaluationError):
            _prepare_payload({"sql": "SELECT 1"}, "../escape")

        snapshots = iter(
            [
                monitor_snapshot(hits=0, misses=0, completed=0),
                monitor_snapshot(hits=1, misses=0, completed=0),
            ]
        )
        with self.assertRaisesRegex(Qa03CacheEvaluationError, "unexpectedly hit"):
            run_cache_evaluation(
                {"sql": "SELECT 1"},
                scenario="cold-hit",
                post_json=lambda _path, _payload: {
                    "code": 200,
                    "data": {"useCache": True},
                },
                get_json=lambda _path: next(snapshots),
                warm_samples=1,
            )


if __name__ == "__main__":
    unittest.main()
