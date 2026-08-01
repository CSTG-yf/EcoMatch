package com.tencent.supersonic.headless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.TraceIdUtil;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class InboundIntegrationService {

    private static final int MAXIMUM_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{7,127}");

    private final Map<String, IntegrationSystemDefinition> systems;
    private final Map<String, InboundIntegrationHandler> handlers;
    private final HmacIntegrationSigner signer;
    private final IntegrationReplayGuard replayGuard;
    private final IntegrationIdempotencyStore idempotencyStore;
    private final IntegrationRateLimiter rateLimiter;
    private final AuditEventPublisher auditPublisher;
    private final ObjectMapper objectMapper;
    private final Duration waitTimeout;

    public InboundIntegrationService(Map<String, IntegrationSystemDefinition> systems,
            List<InboundIntegrationHandler> handlers, HmacIntegrationSigner signer,
            IntegrationReplayGuard replayGuard, IntegrationIdempotencyStore idempotencyStore,
            IntegrationRateLimiter rateLimiter, AuditEventPublisher auditPublisher,
            ObjectMapper objectMapper, Duration waitTimeout) {
        if (systems == null || systems.isEmpty() || handlers == null || signer == null
                || replayGuard == null || idempotencyStore == null || rateLimiter == null
                || auditPublisher == null || objectMapper == null || waitTimeout == null
                || waitTimeout.isZero() || waitTimeout.isNegative()) {
            throw new IllegalArgumentException("inbound integration configuration is invalid");
        }
        this.systems = normalizeSystems(systems);
        this.handlers = normalizeHandlers(handlers);
        this.signer = signer;
        this.replayGuard = replayGuard;
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.auditPublisher = auditPublisher;
        this.objectMapper = objectMapper;
        this.waitTimeout = waitTimeout;
    }

    public IntegrationResponse receive(String systemId, String operation, String path,
            Map<String, String> headers, byte[] body) {
        String normalizedSystem = normalize(systemId);
        String normalizedOperation = normalize(operation);
        IntegrationSystemDefinition system = systems.get(normalizedSystem);
        if (system == null) {
            throw new IntegrationException(IntegrationErrorCode.AUTHENTICATION_FAILED,
                    "integration system authentication failed", false);
        }
        if (body == null || body.length == 0 || body.length > MAXIMUM_REQUEST_BYTES) {
            throw invalid("integration callback body size is invalid");
        }
        if (headers == null) {
            throw invalid("integration callback headers are required");
        }
        User serviceAccount = serviceAccount(system);
        try {
            rateLimiter.acquire(system.systemId());
            String expectedPath = "/api/semantic/integration/v1/callbacks/" + normalizedSystem + '/'
                    + normalizedOperation;
            if (!expectedPath.equals(path)) {
                throw invalid("integration callback path is invalid");
            }
            signer.verify(system, "POST", path, headers, body);
            if (!system.supports(normalizedOperation)) {
                throw new IntegrationException(IntegrationErrorCode.UNSUPPORTED_OPERATION,
                        "integration callback is not configured", false);
            }
            String nonce = headers.get(HmacIntegrationSigner.HEADER_NONCE);
            replayGuard.requireFresh(system.systemId(), nonce);
            IntegrationEnvelope envelope = parseEnvelope(body);
            validateEnvelope(system, normalizedOperation, headers, envelope);
            String idempotencyKey = headers.get(HmacIntegrationSigner.HEADER_IDEMPOTENCY);
            String fingerprint = signer.bodyDigest(body);
            return idempotencyStore.execute("INBOUND:" + system.systemId(), idempotencyKey,
                    fingerprint, waitTimeout,
                    () -> handleOnce(system, normalizedOperation, envelope, serviceAccount));
        } catch (RuntimeException failure) {
            publish(AuditEventType.INTEGRATION_INBOUND_FAILED, AuditOutcome.FAILURE,
                    reasonCode(failure), system, normalizedOperation,
                    headers.get(HmacIntegrationSigner.HEADER_TRACE), serviceAccount);
            throw failure;
        }
    }

    private IntegrationResponse handleOnce(IntegrationSystemDefinition system, String operation,
            IntegrationEnvelope envelope, User serviceAccount) {
        InboundIntegrationHandler handler = handlers.get(key(system.systemId(), operation));
        if (handler == null) {
            throw new IntegrationException(IntegrationErrorCode.UNSUPPORTED_OPERATION,
                    "integration callback handler is not installed", false);
        }
        publish(AuditEventType.INTEGRATION_INBOUND_STARTED, AuditOutcome.UNKNOWN,
                "INTEGRATION_STARTED", system, operation, envelope.traceId(), serviceAccount);
        Map<String, Object> data = handler.handle(envelope);
        IntegrationResponse response = new IntegrationResponse("v1", IntegrationErrorCode.SUCCESS,
                "accepted", envelope.traceId(), false, data == null ? Map.of() : Map.copyOf(data));
        publish(AuditEventType.INTEGRATION_INBOUND_SUCCEEDED, AuditOutcome.SUCCESS,
                IntegrationErrorCode.SUCCESS.name(), system, operation, envelope.traceId(),
                serviceAccount);
        return response;
    }

    private IntegrationEnvelope parseEnvelope(byte[] body) {
        try {
            return objectMapper.readValue(body, IntegrationEnvelope.class);
        } catch (IOException exception) {
            throw new IntegrationException(IntegrationErrorCode.INVALID_REQUEST,
                    "integration callback is not valid JSON", false, exception);
        }
    }

    private void validateEnvelope(IntegrationSystemDefinition system, String operation,
            Map<String, String> headers, IntegrationEnvelope envelope) {
        if (envelope == null || !"v1".equals(envelope.apiVersion())
                || !REQUEST_ID.matcher(envelope.requestId() == null ? "" : envelope.requestId())
                        .matches()
                || !operation.equals(normalize(envelope.operation()))
                || !system.organizationId().equals(envelope.organizationId())
                || envelope.payload() == null || envelope.occurredAt() == null
                || !TraceIdUtil.isValidTraceId(envelope.traceId())
                || !envelope.traceId().equals(headers.get(HmacIntegrationSigner.HEADER_TRACE))) {
            throw invalid("integration callback contract is invalid");
        }
    }

    private Map<String, IntegrationSystemDefinition> normalizeSystems(
            Map<String, IntegrationSystemDefinition> configured) {
        Map<String, IntegrationSystemDefinition> result = new LinkedHashMap<>();
        configured.values().forEach(system -> result.put(system.systemId(), system));
        if (result.size() != configured.size()) {
            throw new IllegalArgumentException("integration system identifiers must be unique");
        }
        return Map.copyOf(result);
    }

    private Map<String, InboundIntegrationHandler> normalizeHandlers(
            List<InboundIntegrationHandler> configured) {
        Map<String, InboundIntegrationHandler> result = new LinkedHashMap<>();
        for (InboundIntegrationHandler handler : configured) {
            String key = key(normalize(handler.systemId()), normalize(handler.operation()));
            if (result.putIfAbsent(key, handler) != null) {
                throw new IllegalArgumentException("duplicate inbound integration handler: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private void publish(AuditEventType type, AuditOutcome outcome, String reason,
            IntegrationSystemDefinition system, String operation, String traceId, User user) {
        auditPublisher.publishRequired(AuditEvent.builder().eventType(type).outcome(outcome)
                .traceId(traceId).resourceType("EXTERNAL_SYSTEM").resourceId(system.systemId())
                .organizationId(system.organizationId()).reasonCode(reason)
                .metadata(Map.of("operation", operation)).build(), user);
    }

    private User serviceAccount(IntegrationSystemDefinition system) {
        User user = User.get(0L, "integration_" + system.systemId().toLowerCase(Locale.ROOT));
        user.getAttributes().put("organizationId", system.organizationId());
        user.getRoles().add("INTEGRATION_SERVICE");
        return user;
    }

    private String reasonCode(RuntimeException failure) {
        return failure instanceof IntegrationException integration ? integration.getCode().name()
                : IntegrationErrorCode.INTERNAL_ERROR.name();
    }

    private String key(String systemId, String operation) {
        return systemId + ':' + operation;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private IntegrationException invalid(String message) {
        return new IntegrationException(IntegrationErrorCode.INVALID_REQUEST, message, false);
    }
}
