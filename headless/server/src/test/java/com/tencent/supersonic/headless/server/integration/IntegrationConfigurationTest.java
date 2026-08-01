package com.tencent.supersonic.headless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class IntegrationConfigurationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void featureIsDisabledByDefaultAndCreatesValidatedBeansWhenEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(IntegrationConfiguration.class,
                        IntegrationCallbackController.class)
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(AuditEventPublisher.class, () -> mock(AuditEventPublisher.class));

        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ExternalIntegrationGateway.class);
            assertThat(context).doesNotHaveBean(InboundIntegrationService.class);
            assertThat(context).doesNotHaveBean(IntegrationCallbackController.class);
        });

        runner.withPropertyValues("s2.integration.enabled=true",
                "s2.integration.allow-http-local=true",
                "s2.integration.systems.data-platform.endpoint=http://127.0.0.1:19080/callback",
                "s2.integration.systems.data-platform.signing-secret=" + SECRET,
                "s2.integration.systems.data-platform.organization-id=org-a",
                "s2.integration.systems.data-platform.operations[0]=FETCH_METRICS").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ExternalIntegrationGateway.class);
                    assertThat(context).hasSingleBean(InboundIntegrationService.class);
                    assertThat(context).hasSingleBean(IntegrationCallbackController.class);
                });
    }

    @Test
    void secretsAreNotRenderedAndUnsafeEndpointsAreRejected() {
        IntegrationProperties.SystemProperties properties =
                new IntegrationProperties.SystemProperties();
        properties.setSigningSecret(SECRET);
        properties.setEndpoint("https://example.test/callback");
        assertFalse(properties.toString().contains(SECRET));

        assertThrows(IllegalArgumentException.class,
                () -> new IntegrationSystemDefinition("DATA_PLATFORM",
                        URI.create("https://user:password@example.test/callback"), SECRET, "org-a",
                        Set.of("FETCH_METRICS"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrationSystemDefinition("DATA_PLATFORM",
                        URI.create("https://example.test/callback?token=secret"), SECRET, "org-a",
                        Set.of("FETCH_METRICS"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrationSystemDefinition("DATA_PLATFORM",
                        URI.create("http://example.test/callback"), SECRET, "org-a",
                        Set.of("FETCH_METRICS"), false));
    }
}
