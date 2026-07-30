package com.tencent.supersonic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class H2DistributionConfigTest {

    @Test
    void shouldInitializeFileDatabaseOnFirstStartup() throws IOException {
        List<PropertySource<?>> propertySources = new YamlPropertySourceLoader()
                .load("application-h2", new ClassPathResource("application-h2.yaml"));

        assertThat(propertySources).hasSize(1);
        PropertySource<?> properties = propertySources.get(0);
        assertThat(properties.getProperty("spring.sql.init.mode")).isEqualTo("always");
        assertThat(properties.getProperty("spring.sql.init.continue-on-error")).isEqualTo(true);
        assertThat(properties.getProperty("spring.sql.init.schema-locations")).asString()
                .contains("schema-h2.sql");
        assertThat(properties.getProperty("spring.sql.init.data-locations")).asString()
                .contains("data-h2.sql");
    }

    @Test
    void shouldPersistAgentModelConfigLargerThanLegacyLimit() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:h2-distribution-config;DATABASE_TO_UPPER=false", "root", "semantic")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema-h2.sql"));

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO s2_agent (name, chat_model_config) VALUES (?, ?)")) {
                statement.setString(1, "config-size-regression");
                statement.setString(2, "x".repeat(7_000));
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        }
    }
}
