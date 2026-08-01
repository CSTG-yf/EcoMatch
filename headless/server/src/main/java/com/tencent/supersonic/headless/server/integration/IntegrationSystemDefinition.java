package com.tencent.supersonic.headless.server.integration;

import javax.crypto.spec.SecretKeySpec;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class IntegrationSystemDefinition {

    private static final Pattern SYSTEM_ID = Pattern.compile("[A-Z][A-Z0-9_-]{1,63}");
    private static final Pattern OPERATION = Pattern.compile("[A-Z][A-Z0-9_.-]{1,63}");

    private final String systemId;
    private final URI endpoint;
    private final SecretKeySpec signingKey;
    private final String organizationId;
    private final Set<String> operations;

    public IntegrationSystemDefinition(String systemId, URI endpoint, String signingSecret,
            String organizationId, Set<String> operations, boolean allowHttpLocal) {
        this.systemId = normalize(systemId, SYSTEM_ID, "systemId");
        this.endpoint = validateEndpoint(endpoint, allowHttpLocal);
        if (signingSecret == null || signingSecret.length() < 32) {
            throw new IllegalArgumentException(
                    "integration signing secret must be at least 32 characters");
        }
        this.signingKey =
                new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        if (organizationId == null || organizationId.isBlank() || organizationId.length() > 128) {
            throw new IllegalArgumentException("integration organizationId is required");
        }
        this.organizationId = organizationId;
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("integration operations must not be empty");
        }
        this.operations = operations.stream().map(value -> normalize(value, OPERATION, "operation"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public String systemId() {
        return systemId;
    }

    public URI endpoint() {
        return endpoint;
    }

    SecretKeySpec signingKey() {
        return signingKey;
    }

    public String organizationId() {
        return organizationId;
    }

    public boolean supports(String operation) {
        return operations.contains(operation);
    }

    private static String normalize(String value, Pattern pattern, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid integration " + field);
        }
        return normalized;
    }

    private static URI validateEndpoint(URI endpoint, boolean allowHttpLocal) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null || endpoint.getRawQuery() != null
                || endpoint.getRawPath() == null || !endpoint.getRawPath().startsWith("/")) {
            throw new IllegalArgumentException("integration endpoint is invalid");
        }
        if ("https".equalsIgnoreCase(endpoint.getScheme())) {
            return endpoint;
        }
        String host = endpoint.getHost();
        if (allowHttpLocal && "http".equalsIgnoreCase(endpoint.getScheme())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) {
            return endpoint;
        }
        throw new IllegalArgumentException("integration endpoint must use HTTPS");
    }

    @Override
    public String toString() {
        return "IntegrationSystemDefinition{systemId='" + systemId + "'}";
    }
}
