package com.tencent.supersonic.headless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalIntegrationGatewayContractTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HmacIntegrationSigner signer =
            new HmacIntegrationSigner(clock, Duration.ofMinutes(5));

    @Test
    void integratesTwoMockSystemsWithSignedVersionedContractsAndAudit() throws Exception {
        Map<String, IntegrationSystemDefinition> systems = systems();
        AtomicInteger transportCalls = new AtomicInteger();
        IntegrationTransport transport = (endpoint, headers, body, timeout) -> {
            transportCalls.incrementAndGet();
            IntegrationSystemDefinition system =
                    systems.get(headers.get(HmacIntegrationSigner.HEADER_SYSTEM));
            String path = endpoint.getRawPath();
            signer.verify(system, "POST", path, headers, body);
            IntegrationEnvelope envelope;
            try {
                envelope = objectMapper.readValue(body, IntegrationEnvelope.class);
                return new IntegrationTransport.TransportResponse(200,
                        objectMapper.writeValueAsBytes(new IntegrationResponse("v1",
                                IntegrationErrorCode.SUCCESS, "accepted", envelope.traceId(), false,
                                Map.of("operation", envelope.operation()))));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        AuditEventPublisher audit = mock(AuditEventPublisher.class);
        when(audit.publishRequired(any(), any())).thenReturn("audit-id");
        ExternalIntegrationGateway gateway = gateway(systems, transport, audit);

        IntegrationRequest fetch = request("DATA_PLATFORM", "FETCH_METRICS", "request-data-001",
                new LinkedHashMap<>(Map.of("metric", "loan_balance", "period", "2026-07")));
        IntegrationResponse first = gateway.send(fetch);
        Map<String, Object> reorderedPayload = new LinkedHashMap<>();
        reorderedPayload.put("period", "2026-07");
        reorderedPayload.put("metric", "loan_balance");
        IntegrationResponse duplicate = gateway.send(
                request("DATA_PLATFORM", "FETCH_METRICS", "request-data-001", reorderedPayload));
        IntegrationResponse marketing = gateway.send(request("MARKETING_PLATFORM", "PUSH_SEGMENT",
                "request-marketing-001", Map.of("segmentId", "segment-a")));

        assertTrue(first.successful());
        assertEquals(first, duplicate);
        assertTrue(marketing.successful());
        assertEquals(2, transportCalls.get());
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(4)).publishRequired(events.capture(), any());
        List<AuditEventType> types =
                events.getAllValues().stream().map(AuditEvent::getEventType).toList();
        assertEquals(2, types.stream()
                .filter(type -> type == AuditEventType.INTEGRATION_OUTBOUND_STARTED).count());
        assertEquals(2, types.stream()
                .filter(type -> type == AuditEventType.INTEGRATION_OUTBOUND_SUCCEEDED).count());
        String auditJson = objectMapper.writeValueAsString(events.getAllValues());
        assertFalse(auditJson.contains("loan_balance"));
        assertFalse(auditJson.contains("segment-a"));
    }

    @Test
    void rejectsOrganizationOperationIdempotencyAndResponseTraceViolations() throws Exception {
        Map<String, IntegrationSystemDefinition> systems = systems();
        AuditEventPublisher audit = mock(AuditEventPublisher.class);
        when(audit.publishRequired(any(), any())).thenReturn("audit-id");
        IntegrationTransport wrongTrace = (endpoint, headers, body, timeout) -> {
            try {
                return new IntegrationTransport.TransportResponse(200,
                        objectMapper.writeValueAsBytes(
                                new IntegrationResponse("v1", IntegrationErrorCode.SUCCESS,
                                        "accepted", "wrong-trace", false, Map.of())));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        ExternalIntegrationGateway gateway = gateway(systems, wrongTrace, audit);

        assertEquals(IntegrationErrorCode.INVALID_REQUEST,
                assertThrows(IntegrationException.class,
                        () -> gateway.send(new IntegrationRequest("DATA_PLATFORM", "FETCH_METRICS",
                                "org-b", "request-data-002", "trace-data-002", Map.of())))
                                        .getCode());
        assertEquals(IntegrationErrorCode.UNSUPPORTED_OPERATION,
                assertThrows(IntegrationException.class, () -> gateway
                        .send(request("DATA_PLATFORM", "DELETE_ALL", "request-data-003", Map.of())))
                                .getCode());

        IntegrationRequest original = request("DATA_PLATFORM", "FETCH_METRICS", "request-data-004",
                Map.of("metric", "a"));
        assertEquals(IntegrationErrorCode.RESPONSE_INVALID,
                assertThrows(IntegrationException.class, () -> gateway.send(original)).getCode());

        IntegrationTransport successful = successfulTransport();
        ExternalIntegrationGateway idempotent = gateway(systems, successful, audit);
        idempotent.send(original);
        assertEquals(IntegrationErrorCode.IDEMPOTENCY_CONFLICT,
                assertThrows(IntegrationException.class,
                        () -> idempotent.send(request("DATA_PLATFORM", "FETCH_METRICS",
                                "request-data-004", Map.of("metric", "b")))).getCode());
    }

    private ExternalIntegrationGateway gateway(Map<String, IntegrationSystemDefinition> systems,
            IntegrationTransport transport, AuditEventPublisher audit) {
        return new ExternalIntegrationGateway(systems, transport, objectMapper, signer,
                new IntegrationRateLimiter(20, 20, clock),
                new IntegrationIdempotencyStore(100, Duration.ofHours(1), clock), audit, clock,
                Duration.ofSeconds(5));
    }

    private IntegrationTransport successfulTransport() {
        return (endpoint, headers, body, timeout) -> {
            try {
                IntegrationEnvelope envelope =
                        objectMapper.readValue(body, IntegrationEnvelope.class);
                return new IntegrationTransport.TransportResponse(200,
                        objectMapper.writeValueAsBytes(
                                new IntegrationResponse("v1", IntegrationErrorCode.SUCCESS,
                                        "accepted", envelope.traceId(), false, Map.of())));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
    }

    private Map<String, IntegrationSystemDefinition> systems() {
        Map<String, IntegrationSystemDefinition> systems = new LinkedHashMap<>();
        systems.put("DATA_PLATFORM",
                new IntegrationSystemDefinition("DATA_PLATFORM",
                        URI.create("http://127.0.0.1:19080/data-callback"), SECRET, "org-a",
                        Set.of("FETCH_METRICS"), true));
        systems.put("MARKETING_PLATFORM",
                new IntegrationSystemDefinition("MARKETING_PLATFORM",
                        URI.create("http://127.0.0.1:19081/marketing-callback"), SECRET, "org-a",
                        Set.of("PUSH_SEGMENT"), true));
        return Map.copyOf(systems);
    }

    private IntegrationRequest request(String system, String operation, String idempotencyKey,
            Map<String, Object> payload) {
        return new IntegrationRequest(system, operation, "org-a", idempotencyKey, "trace-12345678",
                payload);
    }
}
