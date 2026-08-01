package com.tencent.supersonic.headless.server.integration;

import java.util.Map;

public record IntegrationResponse(String apiVersion, IntegrationErrorCode code, String message,
        String traceId, boolean retryable, Map<String, Object> data) {

    public boolean successful() {
        return code == IntegrationErrorCode.SUCCESS;
    }
}
