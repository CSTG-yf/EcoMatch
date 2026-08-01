package com.tencent.supersonic.headless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
@ConditionalOnProperty(prefix = "s2.integration", name = "enabled", havingValue = "true")
public class IntegrationConfiguration {

    @Bean
    public Clock integrationClock() {
        return Clock.systemUTC();
    }

    @Bean("integrationSystems")
    public Map<String, IntegrationSystemDefinition> integrationSystems(
            IntegrationProperties properties) {
        if (properties.getSystems() == null || properties.getSystems().isEmpty()
                || properties.getSystems().size() > 32) {
            throw new IllegalArgumentException(
                    "s2.integration.systems must configure between 1 and 32 systems");
        }
        Map<String, IntegrationSystemDefinition> systems = new LinkedHashMap<>();
        properties.getSystems().forEach((systemId, configured) -> {
            IntegrationSystemDefinition system =
                    new IntegrationSystemDefinition(systemId, URI.create(configured.getEndpoint()),
                            configured.getSigningSecret(), configured.getOrganizationId(),
                            configured.getOperations(), properties.isAllowHttpLocal());
            if (systems.putIfAbsent(system.systemId(), system) != null) {
                throw new IllegalArgumentException("duplicate integration system identifier");
            }
        });
        return Map.copyOf(systems);
    }

    @Bean
    public HmacIntegrationSigner hmacIntegrationSigner(IntegrationProperties properties,
            Clock integrationClock) {
        return new HmacIntegrationSigner(integrationClock,
                Duration.ofSeconds(properties.getSignatureSkewSeconds()));
    }

    @Bean
    public ExternalIntegrationGateway externalIntegrationGateway(
            @org.springframework.beans.factory.annotation.Qualifier("integrationSystems") Map<String, IntegrationSystemDefinition> systems,
            IntegrationProperties properties, ObjectMapper objectMapper,
            HmacIntegrationSigner signer, AuditEventPublisher auditPublisher,
            Clock integrationClock) {
        Duration requestTimeout = Duration.ofSeconds(properties.getRequestTimeoutSeconds());
        return new ExternalIntegrationGateway(systems,
                new JdkIntegrationTransport(
                        Duration.ofSeconds(properties.getConnectTimeoutSeconds()),
                        properties.getMaximumResponseBytes()),
                objectMapper, signer,
                new IntegrationRateLimiter(properties.getRateLimitCapacity(),
                        properties.getRateLimitRefillPerSecond(), integrationClock),
                new IntegrationIdempotencyStore(properties.getIdempotencyMaximumEntries(),
                        Duration.ofSeconds(properties.getIdempotencyTtlSeconds()),
                        integrationClock),
                auditPublisher, integrationClock, requestTimeout);
    }

    @Bean
    public InboundIntegrationService inboundIntegrationService(
            @org.springframework.beans.factory.annotation.Qualifier("integrationSystems") Map<String, IntegrationSystemDefinition> systems,
            IntegrationProperties properties, ObjectMapper objectMapper,
            HmacIntegrationSigner signer, AuditEventPublisher auditPublisher,
            Clock integrationClock, ObjectProvider<InboundIntegrationHandler> handlers) {
        List<InboundIntegrationHandler> registeredHandlers = handlers.orderedStream().toList();
        return new InboundIntegrationService(systems, registeredHandlers, signer,
                new IntegrationReplayGuard(properties.getReplayMaximumEntries(),
                        Duration.ofSeconds(properties.getSignatureSkewSeconds())),
                new IntegrationIdempotencyStore(properties.getIdempotencyMaximumEntries(),
                        Duration.ofSeconds(properties.getIdempotencyTtlSeconds()),
                        integrationClock),
                new IntegrationRateLimiter(properties.getRateLimitCapacity(),
                        properties.getRateLimitRefillPerSecond(), integrationClock),
                auditPublisher, objectMapper,
                Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
    }
}
