package com.tencent.supersonic.headless.server.integration;

import java.util.Map;

public record IntegrationRequest(String systemId, String operation, String organizationId,
        String idempotencyKey, String traceId, Map<String, Object> payload) {
}
