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
import java.sql.ResultSet;
import java.sql.Statement;
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
        assertThat(properties.getProperty("spring.sql.init.data-locations")).asString()
                .contains("chat-agent-binding-backfill.sql");
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

    @Test
    void shouldUpgradeLegacyAuthGroupsSchemaWithoutLosingPolicies() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:h2-auth-groups-upgrade;DATABASE_TO_UPPER=false", "root",
                "semantic"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE s2_auth_groups (group_id INT PRIMARY KEY, config LONGVARCHAR)");
            statement.execute("INSERT INTO s2_auth_groups (group_id, config) VALUES (1, 'legacy-policy')");

            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema-h2.sql"));

            try (ResultSet result = statement.executeQuery(
                    "SELECT config, model_id, policy_code, enabled, policy_version, valid_from, "
                            + "valid_to, updated_at, updated_by FROM s2_auth_groups WHERE group_id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("config")).isEqualTo("legacy-policy");
                assertThat(result.getObject("model_id")).isNull();
                assertThat(result.getObject("policy_code")).isNull();
                assertThat(result.getInt("enabled")).isEqualTo(1);
                assertThat(result.getLong("policy_version")).isEqualTo(1L);
                assertThat(result.getObject("valid_from")).isNull();
                assertThat(result.getObject("valid_to")).isNull();
                assertThat(result.getObject("updated_at")).isNull();
                assertThat(result.getObject("updated_by")).isNull();
            }
        }
    }

    @Test
    void shouldBackfillOnlyLegacyChatsWithOneHistoricalAgent() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:chat-agent-backfill;DATABASE_TO_UPPER=false", "root", "semantic");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE s2_chat (chat_id BIGINT PRIMARY KEY, agent_id INT)");
            statement.execute("CREATE TABLE s2_chat_query (chat_id BIGINT, agent_id INT)");
            statement.execute("INSERT INTO s2_chat VALUES (1, NULL), (2, NULL), (3, NULL), (4, 9)");
            statement.execute("INSERT INTO s2_chat_query VALUES (1, 7), (1, 7), (2, 7), (2, 8)");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/chat-agent-binding-backfill.sql"));

            try (ResultSet result = statement.executeQuery(
                    "SELECT chat_id, agent_id FROM s2_chat ORDER BY chat_id")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("chat_id")).isEqualTo(1L);
                assertThat(result.getInt("agent_id")).isEqualTo(7);
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("chat_id")).isEqualTo(2L);
                assertThat(result.getObject("agent_id")).isNull();
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("chat_id")).isEqualTo(3L);
                assertThat(result.getObject("agent_id")).isNull();
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("chat_id")).isEqualTo(4L);
                assertThat(result.getInt("agent_id")).isEqualTo(9);
            }
        }
    }
}
