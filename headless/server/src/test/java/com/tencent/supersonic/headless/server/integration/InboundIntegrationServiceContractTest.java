package com.tencent.supersonic.headless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundIntegrationServiceContractTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String TRACE = "trace-12345678";
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HmacIntegrationSigner signer =
            new HmacIntegrationSigner(clock, Duration.ofMinutes(5));

    @Test
    void acceptsTwoMockSystemsAndDeduplicatesInboundCallbacks() throws Exception {
        AtomicInteger dataCalls = new AtomicInteger();
        AtomicInteger marketingCalls = new AtomicInteger();
        InboundIntegrationHandler data = handler("DATA_PLATFORM", "METRIC_CHANGED", dataCalls);
        InboundIntegrationHandler marketing =
                handler("MARKETING_PLATFORM", "CAMPAIGN_RESULT", marketingCalls);
        AuditEventPublisher audit = audit();
        InboundIntegrationService service = service(List.of(data, marketing), audit, 20);

        SignedCallback dataCallback = callback("DATA_PLATFORM", "METRIC_CHANGED",
                "callback-data-001", "nonce-data-123456", Map.of("metricId", "metric-a"));
        IntegrationResponse first = service.receive("DATA_PLATFORM", "METRIC_CHANGED",
                dataCallback.path(), dataCallback.headers(), dataCallback.body());
        SignedCallback duplicate = callback("DATA_PLATFORM", "METRIC_CHANGED", "callback-data-001",
                "nonce-data-123457", Map.of("metricId", "metric-a"));
        IntegrationResponse second = service.receive("DATA_PLATFORM", "METRIC_CHANGED",
                duplicate.path(), duplicate.headers(), duplicate.body());
        SignedCallback marketingCallback = callback("MARKETING_PLATFORM", "CAMPAIGN_RESULT",
                "callback-marketing-001", "nonce-market-123456", Map.of("campaignId", "c-a"));
        IntegrationResponse third = service.receive("MARKETING_PLATFORM", "CAMPAIGN_RESULT",
                marketingCallback.path(), marketingCallback.headers(), marketingCallback.body());

        assertEquals(first, second);
        assertEquals(IntegrationErrorCode.SUCCESS, third.code());
        assertEquals(1, dataCalls.get());
        assertEquals(1, marketingCalls.get());
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(4)).publishRequired(events.capture(), any());
        assertEquals(2,
                events.getAllValues().stream().filter(
                        event -> event.getEventType() == AuditEventType.INTEGRATION_INBOUND_STARTED)
                        .count());
        assertEquals(2, events.getAllValues().stream().filter(
                event -> event.getEventType() == AuditEventType.INTEGRATION_INBOUND_SUCCEEDED)
                .count());
    }

    @Test
    void rejectsTamperingReplayOrganizationAndIdempotencyConflicts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AuditEventPublisher audit = audit();
        InboundIntegrationService service =
                service(List.of(handler("DATA_PLATFORM", "METRIC_CHANGED", calls)), audit, 20);
        SignedCallback valid = callback("DATA_PLATFORM", "METRIC_CHANGED", "callback-data-002",
                "nonce-data-223456", Map.of("metricId", "metric-a"));

        byte[] tampered = objectMapper.writeValueAsBytes(new IntegrationEnvelope("v1", "request-x",
                TRACE, "METRIC_CHANGED", "org-a", clock.instant(), Map.of("metricId", "metric-b")));
        assertEquals(IntegrationErrorCode.AUTHENTICATION_FAILED,
                assertThrows(IntegrationException.class, () -> service.receive("DATA_PLATFORM",
                        "METRIC_CHANGED", valid.path(), valid.headers(), tampered)).getCode());

        service.receive("DATA_PLATFORM", "METRIC_CHANGED", valid.path(), valid.headers(),
                valid.body());
        assertEquals(IntegrationErrorCode.REPLAY_DETECTED,
                assertThrows(IntegrationException.class, () -> service.receive("DATA_PLATFORM",
                        "METRIC_CHANGED", valid.path(), valid.headers(), valid.body())).getCode());

        SignedCallback conflicting = callback("DATA_PLATFORM", "METRIC_CHANGED",
                "callback-data-002", "nonce-data-223457", Map.of("metricId", "metric-c"));
        assertEquals(IntegrationErrorCode.IDEMPOTENCY_CONFLICT,
                assertThrows(IntegrationException.class,
                        () -> service.receive("DATA_PLATFORM", "METRIC_CHANGED", conflicting.path(),
                                conflicting.headers(), conflicting.body())).getCode());

        SignedCallback wrongOrg = callbackWithOrganization("DATA_PLATFORM", "METRIC_CHANGED",
                "callback-data-003", "nonce-data-223458", "org-b", Map.of());
        assertEquals(IntegrationErrorCode.INVALID_REQUEST,
                assertThrows(IntegrationException.class, () -> service.receive("DATA_PLATFORM",
                        "METRIC_CHANGED", wrongOrg.path(), wrongOrg.headers(), wrongOrg.body()))
                                .getCode());
        assertEquals(1, calls.get());
    }

    @Test
    void controllerReturnsStableErrorWithoutInternalDetails() {
        InboundIntegrationService service = mock(InboundIntegrationService.class);
        when(service.receive(any(), any(), any(), any(), any())).thenThrow(new IntegrationException(
                IntegrationErrorCode.AUTHENTICATION_FAILED, "secret internal reason", false));
        IntegrationCallbackController controller = new IntegrationCallbackController(service);
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/semantic/integration/v1/callbacks/DATA_PLATFORM/METRIC_CHANGED");
        request.addHeader(HmacIntegrationSigner.HEADER_TRACE, TRACE);

        var response =
                controller.callback("DATA_PLATFORM", "METRIC_CHANGED", new byte[] {1}, request);

        assertEquals(401, response.getStatusCode().value());
        assertEquals("authentication rejected", response.getBody().message());
        assertEquals(TRACE, response.getBody().traceId());

        reset(service);
        doThrow(new IllegalStateException("database password=secret")).when(service).receive(any(),
                any(), any(), any(), any());
        var internal =
                controller.callback("DATA_PLATFORM", "METRIC_CHANGED", new byte[] {1}, request);
        assertEquals(500, internal.getStatusCode().value());
        assertEquals("integration failed", internal.getBody().message());
    }

    private InboundIntegrationService service(List<InboundIntegrationHandler> handlers,
            AuditEventPublisher audit, int rateCapacity) {
        return new InboundIntegrationService(systems(), handlers, signer,
                new IntegrationReplayGuard(100, Duration.ofMinutes(5)),
                new IntegrationIdempotencyStore(100, Duration.ofHours(1), clock),
                new IntegrationRateLimiter(rateCapacity, rateCapacity, clock), audit, objectMapper,
                Duration.ofSeconds(5));
    }

    private AuditEventPublisher audit() {
        AuditEventPublisher audit = mock(AuditEventPublisher.class);
        when(audit.publishRequired(any(), any())).thenReturn("audit-id");
        return audit;
    }

    private InboundIntegrationHandler handler(String systemId, String operation,
            AtomicInteger calls) {
        return new InboundIntegrationHandler() {
            @Override
            public String systemId() {
                return systemId;
            }

            @Override
            public String operation() {
                return operation;
            }

            @Override
            public Map<String, Object> handle(IntegrationEnvelope envelope) {
                calls.incrementAndGet();
                return Map.of("handled", true);
            }
        };
    }

    private SignedCallback callback(String systemId, String operation, String idempotencyKey,
            String nonce, Map<String, Object> payload) throws Exception {
        return callbackWithOrganization(systemId, operation, idempotencyKey, nonce, "org-a",
                payload);
    }

    private SignedCallback callbackWithOrganization(String systemId, String operation,
            String idempotencyKey, String nonce, String organizationId, Map<String, Object> payload)
            throws Exception {
        String path = "/api/semantic/integration/v1/callbacks/" + systemId + '/' + operation;
        IntegrationEnvelope envelope = new IntegrationEnvelope("v1", "request-" + idempotencyKey,
                TRACE, operation, organizationId, clock.instant(), payload);
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        IntegrationSystemDefinition system = systems().get(systemId);
        long timestamp = clock.millis();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HmacIntegrationSigner.HEADER_SYSTEM, systemId);
        headers.put(HmacIntegrationSigner.HEADER_TIMESTAMP, Long.toString(timestamp));
        headers.put(HmacIntegrationSigner.HEADER_NONCE, nonce);
        headers.put(HmacIntegrationSigner.HEADER_IDEMPOTENCY, idempotencyKey);
        headers.put(HmacIntegrationSigner.HEADER_TRACE, TRACE);
        headers.put(HmacIntegrationSigner.HEADER_SIGNATURE,
                signer.sign(system, "POST", path, timestamp, nonce, idempotencyKey, body));
        return new SignedCallback(path, Map.copyOf(headers), body);
    }

    private Map<String, IntegrationSystemDefinition> systems() {
        return Map
                .of("DATA_PLATFORM",
                        new IntegrationSystemDefinition(
                                "DATA_PLATFORM", URI.create("http://127.0.0.1:19080/callback"),
                                SECRET, "org-a", Set.of("METRIC_CHANGED"), true),
                        "MARKETING_PLATFORM",
                        new IntegrationSystemDefinition("MARKETING_PLATFORM",
                                URI.create("http://127.0.0.1:19081/callback"), SECRET, "org-a",
                                Set.of("CAMPAIGN_RESULT"), true));
    }

    private record SignedCallback(String path, Map<String, String> headers, byte[] body) {}
}
