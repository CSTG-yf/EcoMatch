package com.tencent.supersonic.headless.server.integration;

import java.time.Instant;
import java.util.Map;

public record IntegrationEnvelope(String apiVersion, String requestId, String traceId,
        String operation, String organizationId, Instant occurredAt, Map<String, Object> payload) {
}
