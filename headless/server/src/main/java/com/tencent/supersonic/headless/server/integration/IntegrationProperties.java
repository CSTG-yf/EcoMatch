package com.tencent.supersonic.headless.server.integration;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "s2.integration")
public class IntegrationProperties {

    private boolean enabled;
    private boolean allowHttpLocal;
    private int connectTimeoutSeconds = 10;
    private int requestTimeoutSeconds = 30;
    private int maximumResponseBytes = 2 * 1024 * 1024;
    private int signatureSkewSeconds = 300;
    private int replayMaximumEntries = 100_000;
    private int idempotencyMaximumEntries = 100_000;
    private int idempotencyTtlSeconds = 86_400;
    private int rateLimitCapacity = 100;
    private double rateLimitRefillPerSecond = 20;
    private Map<String, SystemProperties> systems = new LinkedHashMap<>();

    @Data
    @ToString(exclude = "signingSecret")
    public static class SystemProperties {
        private String endpoint;
        private String signingSecret;
        private String organizationId;
        private Set<String> operations = new LinkedHashSet<>();
    }
}
