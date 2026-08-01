package com.tencent.supersonic.headless.server.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.TraceIdUtil;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class ExternalIntegrationGateway {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,128}");
    private static final Pattern ORGANIZATION_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final int MAXIMUM_REQUEST_BYTES = 2 * 1024 * 1024;

    private final Map<String, IntegrationSystemDefinition> systems;
    private final IntegrationTransport transport;
    private final ObjectMapper objectMapper;
    private final ObjectWriter canonicalWriter;
    private final HmacIntegrationSigner signer;
    private final IntegrationRateLimiter rateLimiter;
    private final IntegrationIdempotencyStore idempotencyStore;
    private final AuditEventPublisher auditPublisher;
    private final Clock clock;
    private final Duration requestTimeout;

    public ExternalIntegrationGateway(Map<String, IntegrationSystemDefinition> systems,
            IntegrationTransport transport, ObjectMapper objectMapper, HmacIntegrationSigner signer,
            IntegrationRateLimiter rateLimiter, IntegrationIdempotencyStore idempotencyStore,
            AuditEventPublisher auditPublisher, Clock clock, Duration requestTimeout) {
        if (systems == null || systems.isEmpty() || systems.size() > 32 || transport == null
                || objectMapper == null || signer == null || rateLimiter == null
                || idempotencyStore == null || auditPublisher == null || clock == null
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "external integration gateway configuration is invalid");
        }
        Map<String, IntegrationSystemDefinition> normalized = new LinkedHashMap<>();
        systems.values().forEach(system -> normalized.put(system.systemId(), system));
        if (normalized.size() != systems.size()) {
            throw new IllegalArgumentException("integration system identifiers must be unique");
        }
        this.systems = Map.copyOf(normalized);
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.canonicalWriter =
                objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.signer = signer;
        this.rateLimiter = rateLimiter;
        this.idempotencyStore = idempotencyStore;
        this.auditPublisher = auditPublisher;
        this.clock = clock;
        this.requestTimeout = requestTimeout;
    }

    public IntegrationResponse send(IntegrationRequest request) {
        ValidatedRequest validated = validate(request);
        byte[] fingerprintBody = serializeCanonical(Map.of("systemId",
                validated.system().systemId(), "operation", validated.operation(), "organizationId",
                validated.organizationId(), "payload", validated.payload()));
        if (fingerprintBody.length > MAXIMUM_REQUEST_BYTES) {
            throw new IntegrationException(IntegrationErrorCode.INVALID_REQUEST,
                    "integration request exceeded the size limit", false);
        }
        String fingerprint = signer.bodyDigest(fingerprintBody);
        return idempotencyStore.execute("OUTBOUND:" + validated.system().systemId(),
                request.idempotencyKey(), fingerprint, requestTimeout, () -> {
                    IntegrationEnvelope envelope = new IntegrationEnvelope("v1",
                            UUID.randomUUID().toString(), validated.traceId(),
                            validated.operation(), validated.organizationId(), clock.instant(),
                            validated.payload());
                    byte[] body = serialize(envelope);
                    if (body.length > MAXIMUM_REQUEST_BYTES) {
                        throw new IntegrationException(IntegrationErrorCode.INVALID_REQUEST,
                                "integration request exceeded the size limit", false);
                    }
                    return sendOnce(validated, envelope, request.idempotencyKey(), body);
                });
    }

    private IntegrationResponse sendOnce(ValidatedRequest request, IntegrationEnvelope envelope,
            String idempotencyKey, byte[] body) {
        rateLimiter.acquire(request.system().systemId());
        User serviceAccount = serviceAccount(request.system());
        publish(AuditEventType.INTEGRATION_OUTBOUND_STARTED, AuditOutcome.UNKNOWN,
                "INTEGRATION_STARTED", request, serviceAccount, null);
        long started = System.nanoTime();
        try {
            URI endpoint = request.system().endpoint();
            String path = endpoint.getRawPath();
            if (endpoint.getRawQuery() != null) {
                path += '?' + endpoint.getRawQuery();
            }
            long timestamp = clock.millis();
            String nonce = UUID.randomUUID().toString().replace("-", "");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put(HmacIntegrationSigner.HEADER_SYSTEM, request.system().systemId());
            headers.put(HmacIntegrationSigner.HEADER_TIMESTAMP, Long.toString(timestamp));
            headers.put(HmacIntegrationSigner.HEADER_NONCE, nonce);
            headers.put(HmacIntegrationSigner.HEADER_IDEMPOTENCY, idempotencyKey);
            headers.put(HmacIntegrationSigner.HEADER_TRACE, envelope.traceId());
            headers.put(HmacIntegrationSigner.HEADER_SIGNATURE, signer.sign(request.system(),
                    "POST", path, timestamp, nonce, idempotencyKey, body));
            IntegrationTransport.TransportResponse transportResponse =
                    transport.post(endpoint, Map.copyOf(headers), body, requestTimeout);
            if (transportResponse.status() < 200 || transportResponse.status() >= 300) {
                throw new IntegrationException(IntegrationErrorCode.UPSTREAM_REJECTED,
                        "integration upstream returned a non-success status", false);
            }
            IntegrationResponse response = deserialize(transportResponse.body());
            if (!envelope.traceId().equals(response.traceId())) {
                throw new IntegrationException(IntegrationErrorCode.RESPONSE_INVALID,
                        "integration response trace does not match the request", false);
            }
            publish(response.successful() ? AuditEventType.INTEGRATION_OUTBOUND_SUCCEEDED
                    : AuditEventType.INTEGRATION_OUTBOUND_FAILED,
                    response.successful() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                    response.code().name(), request, serviceAccount, elapsedMs(started));
            return response;
        } catch (RuntimeException failure) {
            publish(AuditEventType.INTEGRATION_OUTBOUND_FAILED, AuditOutcome.FAILURE,
                    reasonCode(failure), request, serviceAccount, elapsedMs(started));
            throw failure;
        }
    }

    private ValidatedRequest validate(IntegrationRequest request) {
        if (request == null) {
            throw invalid("integration request is required");
        }
        String systemId = normalize(request.systemId());
        String operation = normalize(request.operation());
        IntegrationSystemDefinition system = systems.get(systemId);
        if (system == null) {
            throw new IntegrationException(IntegrationErrorCode.UNSUPPORTED_OPERATION,
                    "integration system is not configured", false);
        }
        if (!system.supports(operation)) {
            throw new IntegrationException(IntegrationErrorCode.UNSUPPORTED_OPERATION,
                    "integration operation is not supported", false);
        }
        if (!ORGANIZATION_ID.matcher(nullToEmpty(request.organizationId())).matches()
                || !system.organizationId().equals(request.organizationId())) {
            throw invalid("integration organization boundary is invalid");
        }
        if (!IDEMPOTENCY_KEY.matcher(nullToEmpty(request.idempotencyKey())).matches()) {
            throw invalid("integration idempotency key is invalid");
        }
        if (request.payload() == null) {
            throw invalid("integration payload is required");
        }
        String traceId = TraceIdUtil.resolveTraceId(request.traceId());
        return new ValidatedRequest(system, operation, request.organizationId(), traceId,
                snapshotPayload(request.payload()));
    }

    private Map<String, Object> snapshotPayload(Map<String, Object> payload) {
        byte[] serialized = serialize(payload);
        if (serialized.length > MAXIMUM_REQUEST_BYTES) {
            throw invalid("integration request exceeded the size limit");
        }
        try {
            return Map.copyOf(objectMapper.readValue(serialized, new TypeReference<>() {}));
        } catch (IOException | RuntimeException exception) {
            throw new IntegrationException(IntegrationErrorCode.INVALID_REQUEST,
                    "integration payload cannot be normalized", false, exception);
        }
    }

    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IntegrationException(IntegrationErrorCode.INVALID_REQUEST,
                    "integration payload cannot be serialized", false, exception);
        }
    }

    private byte[] serializeCanonical(Object value) {
        try {
            return canonicalWriter.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IntegrationException(IntegrationErrorCode.INVALID_REQUEST,
                    "integration payload cannot be serialized", false, exception);
        }
    }

    private IntegrationResponse deserialize(byte[] body) {
        try {
            IntegrationResponse response = objectMapper.readValue(body, IntegrationResponse.class);
            if (response.apiVersion() == null || !"v1".equals(response.apiVersion())
                    || response.code() == null || response.traceId() == null
                    || response.message() == null || response.message().length() > 512) {
                throw new IntegrationException(IntegrationErrorCode.RESPONSE_INVALID,
                        "integration response contract is invalid", false);
            }
            return response;
        } catch (IOException exception) {
            throw new IntegrationException(IntegrationErrorCode.RESPONSE_INVALID,
                    "integration response is not valid JSON", false, exception);
        }
    }

    private void publish(AuditEventType type, AuditOutcome outcome, String reason,
            ValidatedRequest request, User user, Long durationMs) {
        auditPublisher.publishRequired(
                AuditEvent.builder().eventType(type).outcome(outcome).traceId(request.traceId())
                        .organizationId(user.getAttributes().get("organizationId"))
                        .resourceType("EXTERNAL_SYSTEM").resourceId(request.system().systemId())
                        .reasonCode(reason).durationMs(durationMs)
                        .metadata(Map.of("operation", request.operation())).build(),
                user);
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

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private String normalize(String value) {
        return nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private IntegrationException invalid(String message) {
        return new IntegrationException(IntegrationErrorCode.INVALID_REQUEST, message, false);
    }

    private record ValidatedRequest(IntegrationSystemDefinition system, String operation,
            String organizationId, String traceId, Map<String, Object> payload) {}
}
