package com.tencent.supersonic.headless.server.integration;

import javax.crypto.Mac;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class HmacIntegrationSigner {

    public static final String HEADER_SYSTEM = "X-S2-System";
    public static final String HEADER_TIMESTAMP = "X-S2-Timestamp";
    public static final String HEADER_NONCE = "X-S2-Nonce";
    public static final String HEADER_IDEMPOTENCY = "X-S2-Idempotency-Key";
    public static final String HEADER_TRACE = "X-S2-Trace-Id";
    public static final String HEADER_SIGNATURE = "X-S2-Signature";

    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,128}");
    private static final Pattern SIGNATURE = Pattern.compile("[a-f0-9]{64}");
    private final Clock clock;
    private final Duration allowedSkew;

    public HmacIntegrationSigner(Clock clock, Duration allowedSkew) {
        if (clock == null || allowedSkew == null || allowedSkew.isNegative() || allowedSkew.isZero()
                || allowedSkew.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("integration signature clock skew is invalid");
        }
        this.clock = clock;
        this.allowedSkew = allowedSkew;
    }

    public String sign(IntegrationSystemDefinition system, String method, String path,
            long timestamp, String nonce, String idempotencyKey, byte[] body) {
        validateRequestFields(method, path, nonce, idempotencyKey, body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(system.signingKey());
            return HexFormat.of().formatHex(mac.doFinal(canonical(system.systemId(), method, path,
                    timestamp, nonce, idempotencyKey, body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("integration HMAC initialization failed", exception);
        }
    }

    public void verify(IntegrationSystemDefinition system, String method, String path,
            Map<String, String> headers, byte[] body) {
        String headerSystem = required(headers, HEADER_SYSTEM).toUpperCase(Locale.ROOT);
        if (!system.systemId().equals(headerSystem)) {
            throw authFailure("integration system identity mismatch");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(required(headers, HEADER_TIMESTAMP));
        } catch (NumberFormatException exception) {
            throw authFailure("integration timestamp is invalid");
        }
        Instant requestTime;
        try {
            requestTime = Instant.ofEpochMilli(timestamp);
        } catch (RuntimeException exception) {
            throw authFailure("integration timestamp is invalid");
        }
        if (Duration.between(requestTime, clock.instant()).abs().compareTo(allowedSkew) > 0) {
            throw authFailure("integration timestamp is outside the allowed window");
        }
        String nonce = required(headers, HEADER_NONCE);
        String idempotencyKey = required(headers, HEADER_IDEMPOTENCY);
        String supplied = required(headers, HEADER_SIGNATURE).toLowerCase(Locale.ROOT);
        if (!SIGNATURE.matcher(supplied).matches()) {
            throw authFailure("integration signature is invalid");
        }
        String expected = sign(system, method, path, timestamp, nonce, idempotencyKey, body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII))) {
            throw authFailure("integration signature is invalid");
        }
    }

    public String bodyDigest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String canonical(String systemId, String method, String path, long timestamp,
            String nonce, String idempotencyKey, byte[] body) {
        return "v1\n" + method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + systemId + "\n"
                + timestamp + "\n" + nonce + "\n" + idempotencyKey + "\n" + bodyDigest(body);
    }

    private void validateRequestFields(String method, String path, String nonce,
            String idempotencyKey, byte[] body) {
        if (method == null || !method.matches("[A-Z]{3,8}") || path == null || !path.startsWith("/")
                || path.contains("\r") || path.contains("\n")
                || !NONCE.matcher(nonce == null ? "" : nonce).matches()
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey == null ? "" : idempotencyKey).matches()
                || body == null) {
            throw new IntegrationException(IntegrationErrorCode.INVALID_REQUEST,
                    "integration signing input is invalid", false);
        }
    }

    private String required(Map<String, String> headers, String name) {
        String value = headers.get(name);
        if (value == null || value.isBlank()) {
            throw authFailure("required integration authentication header is missing");
        }
        return value;
    }

    private IntegrationException authFailure(String message) {
        return new IntegrationException(IntegrationErrorCode.AUTHENTICATION_FAILED, message, false);
    }
}
