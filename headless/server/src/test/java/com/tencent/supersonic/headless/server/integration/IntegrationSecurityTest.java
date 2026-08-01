package com.tencent.supersonic.headless.server.integration;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegrationSecurityTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final IntegrationSystemDefinition system = new IntegrationSystemDefinition(
            "DATA_PLATFORM", URI.create("http://127.0.0.1:19080/callback"),
            "0123456789abcdef0123456789abcdef", "org-a", Set.of("FETCH_METRICS"), true);
    private final HmacIntegrationSigner signer =
            new HmacIntegrationSigner(clock, Duration.ofMinutes(5));

    @Test
    void hmacBindsBodyPathTimestampNonceAndIdempotencyKey() {
        byte[] body = "{\"value\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> headers =
                signedHeaders(body, NOW.toEpochMilli(), "nonce-1234567890", "request-12345678");

        signer.verify(system, "POST", "/callback", headers, body);
        IntegrationException tampered = assertThrows(IntegrationException.class,
                () -> signer.verify(system, "POST", "/callback", headers,
                        "{\"value\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(IntegrationErrorCode.AUTHENTICATION_FAILED, tampered.getCode());

        Map<String, String> stale =
                signedHeaders(body, NOW.minus(Duration.ofMinutes(6)).toEpochMilli(),
                        "nonce-1234567891", "request-12345679");
        assertThrows(IntegrationException.class,
                () -> signer.verify(system, "POST", "/callback", stale, body));
    }

    @Test
    void replayRateLimitAndIdempotencyFailClosed() {
        IntegrationReplayGuard replay = new IntegrationReplayGuard(100, Duration.ofMinutes(5));
        replay.requireFresh("DATA_PLATFORM", "nonce-1234567890");
        assertEquals(IntegrationErrorCode.REPLAY_DETECTED, assertThrows(IntegrationException.class,
                () -> replay.requireFresh("DATA_PLATFORM", "nonce-1234567890")).getCode());

        IntegrationRateLimiter limiter = new IntegrationRateLimiter(1, 1, clock);
        limiter.acquire("DATA_PLATFORM");
        assertEquals(IntegrationErrorCode.RATE_LIMITED,
                assertThrows(IntegrationException.class, () -> limiter.acquire("DATA_PLATFORM"))
                        .getCode());

        IntegrationIdempotencyStore store =
                new IntegrationIdempotencyStore(100, Duration.ofHours(1), clock);
        AtomicInteger calls = new AtomicInteger();
        IntegrationResponse expected = response("trace-12345678");
        IntegrationResponse first = store.execute("OUTBOUND:DATA_PLATFORM", "request-12345678",
                "fingerprint-a", Duration.ofSeconds(1), () -> {
                    calls.incrementAndGet();
                    return expected;
                });
        IntegrationResponse second = store.execute("OUTBOUND:DATA_PLATFORM", "request-12345678",
                "fingerprint-a", Duration.ofSeconds(1), () -> {
                    calls.incrementAndGet();
                    return expected;
                });
        assertEquals(first, second);
        assertEquals(1, calls.get());
        assertEquals(IntegrationErrorCode.IDEMPOTENCY_CONFLICT,
                assertThrows(IntegrationException.class,
                        () -> store.execute("OUTBOUND:DATA_PLATFORM", "request-12345678",
                                "fingerprint-b", Duration.ofSeconds(1), () -> expected)).getCode());

        IntegrationIdempotencyStore capacity =
                new IntegrationIdempotencyStore(1, Duration.ofHours(1), clock);
        capacity.execute("OUTBOUND:DATA_PLATFORM", "request-capacity-1", "same",
                Duration.ofSeconds(1), () -> expected);
        assertEquals(expected, capacity.execute("OUTBOUND:DATA_PLATFORM", "request-capacity-1",
                "same", Duration.ofSeconds(1), () -> response("unused")));
        assertEquals(IntegrationErrorCode.RATE_LIMITED,
                assertThrows(IntegrationException.class,
                        () -> capacity.execute("OUTBOUND:DATA_PLATFORM", "request-capacity-2",
                                "other", Duration.ofSeconds(1), () -> expected)).getCode());
    }

    private Map<String, String> signedHeaders(byte[] body, long timestamp, String nonce,
            String idempotencyKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HmacIntegrationSigner.HEADER_SYSTEM, system.systemId());
        headers.put(HmacIntegrationSigner.HEADER_TIMESTAMP, Long.toString(timestamp));
        headers.put(HmacIntegrationSigner.HEADER_NONCE, nonce);
        headers.put(HmacIntegrationSigner.HEADER_IDEMPOTENCY, idempotencyKey);
        headers.put(HmacIntegrationSigner.HEADER_TRACE, "trace-12345678");
        headers.put(HmacIntegrationSigner.HEADER_SIGNATURE,
                signer.sign(system, "POST", "/callback", timestamp, nonce, idempotencyKey, body));
        return headers;
    }

    private IntegrationResponse response(String traceId) {
        return new IntegrationResponse("v1", IntegrationErrorCode.SUCCESS, "accepted", traceId,
                false, Map.of());
    }
}
