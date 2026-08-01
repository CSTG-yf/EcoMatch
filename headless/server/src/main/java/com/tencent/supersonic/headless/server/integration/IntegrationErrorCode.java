package com.tencent.supersonic.headless.server.integration;

public enum IntegrationErrorCode {
    SUCCESS,
    INVALID_REQUEST,
    AUTHENTICATION_FAILED,
    REPLAY_DETECTED,
    IDEMPOTENCY_CONFLICT,
    RATE_LIMITED,
    UNSUPPORTED_OPERATION,
    UPSTREAM_TIMEOUT,
    UPSTREAM_REJECTED,
    RESPONSE_INVALID,
    INTERNAL_ERROR
}
