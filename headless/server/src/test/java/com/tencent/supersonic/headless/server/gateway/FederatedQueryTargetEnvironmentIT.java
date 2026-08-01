package com.tencent.supersonic.headless.server.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.core.gateway.QueryExecutionGateway;
import com.tencent.supersonic.headless.core.gateway.SqlSafetyPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Explicit BE-07 acceptance against a provisioned Trino or Presto federation environment. */
class FederatedQueryTargetEnvironmentIT {

    private static final Path REPORT_PATH = Path.of("target", "be07-federated-report.json");

    @Test
    void verifiesCrossCatalogQuerySemanticsLimitsAndPermissionIsolation() throws Exception {
        Be07Config config = Be07Config.from(System.getenv());
        QueryExecutionGateway gateway = new QueryExecutionGateway(2, 5_000, 100_000);
        Map<String, Object> engine;
        QueryResult federatedResult;

        try (Connection connection =
                connect(config.jdbcUrl(), config.jdbcUser(), config.jdbcPassword())) {
            engine = verifyEngine(connection);
            verifyExplain(connection, config);
            federatedResult = gateway.execute(config.federatedSql(),
                    () -> executeBounded(connection, config.federatedSql(), config));
            assertTrue(federatedResult.rows() >= config.expectedMinRows(),
                    "BE-07 federated query returned fewer rows than expected");
            assertEquals(config.expectedColumnCount(), federatedResult.columns(),
                    "BE-07 federated query returned an unexpected column contract");
            assertEquals(0,
                    gateway.execute(config.validationSql(),
                            () -> executeValidation(connection, config)),
                    "BE-07 semantic validation found inconsistent federated results");
            verifyResourceLimit(connection, config, gateway);
        }

        verifyDeniedAccount(config, gateway);
        QueryExecutionGateway.QueryGatewayStats gatewayStats = gateway.snapshot();
        assertEquals(0, gatewayStats.activeQueries(),
                "BE-07 physical gateway retained an active query");
        assertEquals(gatewayStats.maxConcurrency(), gatewayStats.availablePermits(),
                "BE-07 physical gateway did not release every query permit");
        writeReport(config, engine, federatedResult, gatewayStats);
    }

    private Connection connect(String url, String user, String password) throws SQLException {
        Properties properties = new Properties();
        if (!user.isBlank()) {
            properties.setProperty("user", user);
        }
        if (!password.isBlank()) {
            properties.setProperty("password", password);
        }
        return DriverManager.getConnection(url, properties);
    }

    private Map<String, Object> verifyEngine(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        String normalized = product.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("trino") || normalized.contains("presto"),
                "BE-07 target must identify as Trino or Presto");
        return Map.of("product", product, "productVersion", metadata.getDatabaseProductVersion(),
                "driver", metadata.getDriverName(), "driverVersion", metadata.getDriverVersion());
    }

    private void verifyExplain(Connection connection, Be07Config config) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("EXPLAIN " + config.federatedSql())) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "BE-07 federated EXPLAIN returned no plan");
            }
        }
    }

    private QueryResult executeBounded(Connection connection, String sql, Be07Config config) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            statement.setMaxRows(config.maxResultRows() + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columns = metadata.getColumnCount();
                int rows = 0;
                while (resultSet.next()) {
                    rows++;
                    if (rows > config.maxResultRows()) {
                        fail("BE-07 federated query exceeded the configured result limit");
                    }
                }
                return new QueryResult(rows, columns);
            }
        } catch (SQLException exception) {
            throw new Be07ExecutionException(exception);
        }
    }

    private long executeValidation(Connection connection, Be07Config config) {
        try (PreparedStatement statement = connection.prepareStatement(config.validationSql())) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            statement.setMaxRows(2);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "BE-07 validation SQL returned no result");
                assertEquals(1, resultSet.getMetaData().getColumnCount(),
                        "BE-07 validation SQL must return one numeric column");
                long violations = resultSet.getLong(1);
                assertTrue(!resultSet.wasNull(), "BE-07 validation SQL returned null");
                assertTrue(!resultSet.next(), "BE-07 validation SQL must return exactly one row");
                return violations;
            }
        } catch (SQLException exception) {
            throw new Be07ExecutionException(exception);
        }
    }

    private void verifyDeniedAccount(Be07Config config, QueryExecutionGateway gateway)
            throws SQLException {
        try (Connection connection = connect(config.deniedJdbcUrl(), config.deniedJdbcUser(),
                config.deniedJdbcPassword())) {
            gateway.execute(config.healthSql(), () -> executeStatement(connection,
                    config.healthSql(), config.queryTimeoutSeconds(), 1));
            try {
                gateway.execute(config.deniedSql(), () -> executeStatement(connection,
                        config.deniedSql(), config.queryTimeoutSeconds(), 1));
                fail("BE-07 restricted account accessed a protected federated source");
            } catch (Be07ExecutionException expected) {
                assertExpectedRejection(expected, config.deniedErrorFragments(),
                        "BE-07 restricted-account rejection did not match the permission rule");
                assertTrue(connection.isValid(config.queryTimeoutSeconds()),
                        "BE-07 denied-account failure was a broken connection, not permission isolation");
            }
        }
    }

    private void verifyResourceLimit(Connection connection, Be07Config config,
            QueryExecutionGateway gateway) throws SQLException {
        try {
            gateway.execute(config.resourceLimitSql(),
                    () -> executeStatement(connection, config.resourceLimitSql(),
                            config.resourceLimitTimeoutSeconds(), config.maxResultRows() + 1));
            fail("BE-07 resource-limit probe completed instead of being rejected");
        } catch (Be07ExecutionException expected) {
            assertExpectedRejection(expected, config.resourceLimitErrorFragments(),
                    "BE-07 rejection did not match the configured resource-group limit");
            assertTrue(connection.isValid(config.queryTimeoutSeconds()),
                    "BE-07 resource-limit rejection left the federation connection unhealthy");
        }
    }

    private Void executeStatement(Connection connection, String sql, int timeoutSeconds,
            int maxRows) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows);
            statement.execute();
            return null;
        } catch (SQLException exception) {
            throw new Be07ExecutionException(exception);
        }
    }

    private void assertExpectedRejection(Be07ExecutionException exception, List<String> expected,
            String message) {
        SQLException cause = exception.sqlException();
        String evidence =
                String.valueOf(cause.getSQLState()) + " " + String.valueOf(cause.getMessage());
        String normalized = evidence.toLowerCase(Locale.ROOT);
        assertTrue(expected.stream().anyMatch(normalized::contains), message);
    }

    private void writeReport(Be07Config config, Map<String, Object> engine, QueryResult result,
            QueryExecutionGateway.QueryGatewayStats gatewayStats) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "1.0");
        report.put("task", "BE-07");
        report.put("scope", "ENVIRONMENT");
        report.put("generatedAt", Instant.now().toString());
        report.put("status", "PASS");
        report.put("engine", engine);
        report.put("configuration",
                Map.of("sourceCount", 2, "queryTimeoutSeconds", config.queryTimeoutSeconds(),
                        "maxResultRows", config.maxResultRows(), "expectedMinRows",
                        config.expectedMinRows(), "expectedColumnCount",
                        config.expectedColumnCount()));
        report.put("result", result);
        report.put("gateway", gatewayStats);
        report.put("controls",
                List.of(control("federation-engine"), control("cross-catalog-plan-and-query"),
                        control("supersonic-physical-query-gateway"), control("bounded-result"),
                        control("semantic-consistency"), control("server-resource-group-limit"),
                        control("restricted-account-isolation")));
        Files.createDirectories(REPORT_PATH.getParent());
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
                .writeValue(REPORT_PATH.toFile(), report);
    }

    private Map<String, Object> control(String id) {
        return Map.of("id", id, "status", "PASS");
    }

    record QueryResult(int rows, int columns) {}

    private static class Be07ExecutionException extends RuntimeException {

        private final SQLException sqlException;

        private Be07ExecutionException(SQLException sqlException) {
            super("BE-07 JDBC execution failed");
            this.sqlException = sqlException;
        }

        private SQLException sqlException() {
            return sqlException;
        }
    }

    record Be07Config(String jdbcUrl, String jdbcUser, String jdbcPassword, String sourceA,
            String sourceB, String federatedSql, String validationSql, String deniedJdbcUrl,
            String deniedJdbcUser, String deniedJdbcPassword, String deniedSql,
            List<String> deniedErrorFragments, String resourceLimitSql,
            List<String> resourceLimitErrorFragments, String healthSql, int queryTimeoutSeconds,
            int resourceLimitTimeoutSeconds, int maxResultRows, int expectedMinRows,
            int expectedColumnCount) {

        private static final Pattern SOURCE =
                Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*\\.[A-Za-z_][A-Za-z0-9_$]*");

        static Be07Config from(Map<String, String> env) {
            String jdbcUrl = required(env, "BE07_JDBC_URL");
            Be07Config config = new Be07Config(jdbcUrl, optional(env, "BE07_JDBC_USER", ""),
                    optional(env, "BE07_JDBC_PASSWORD", ""), required(env, "BE07_SOURCE_A"),
                    required(env, "BE07_SOURCE_B"), required(env, "BE07_FEDERATED_SQL"),
                    required(env, "BE07_VALIDATION_SQL"),
                    optional(env, "BE07_DENIED_JDBC_URL", jdbcUrl),
                    required(env, "BE07_DENIED_JDBC_USER"),
                    optional(env, "BE07_DENIED_JDBC_PASSWORD", ""),
                    required(env, "BE07_DENIED_SQL"),
                    rejectionFragments(env, "BE07_DENIED_ERROR_CONTAINS"),
                    required(env, "BE07_RESOURCE_LIMIT_SQL"),
                    rejectionFragments(env, "BE07_RESOURCE_LIMIT_ERROR_CONTAINS"),
                    optional(env, "BE07_HEALTH_SQL", "SELECT 1"),
                    boundedInt(env, "BE07_QUERY_TIMEOUT_SECONDS", 60, 1, 3_600),
                    boundedInt(env, "BE07_RESOURCE_LIMIT_TIMEOUT_SECONDS", 120, 1, 3_600),
                    boundedInt(env, "BE07_MAX_RESULT_ROWS", 10_000, 1, 1_000_000),
                    boundedInt(env, "BE07_EXPECTED_MIN_ROWS", 1, 1, 1_000_000),
                    requiredBoundedInt(env, "BE07_EXPECTED_COLUMN_COUNT", 1, 1_024));
            config.validate();
            return config;
        }

        private void validate() {
            if (!jdbcUrl.startsWith("jdbc:trino:") && !jdbcUrl.startsWith("jdbc:presto:")) {
                throw new IllegalArgumentException("BE07_JDBC_URL must use Trino or Presto JDBC");
            }
            if (!deniedJdbcUrl.startsWith("jdbc:trino:")
                    && !deniedJdbcUrl.startsWith("jdbc:presto:")) {
                throw new IllegalArgumentException(
                        "BE07_DENIED_JDBC_URL must use Trino or Presto JDBC");
            }
            if (!SOURCE.matcher(sourceA).matches() || !SOURCE.matcher(sourceB).matches()) {
                throw new IllegalArgumentException(
                        "BE07_SOURCE_A and BE07_SOURCE_B must use catalog.schema format");
            }
            String catalogA = sourceA.substring(0, sourceA.indexOf('.'));
            String catalogB = sourceB.substring(0, sourceB.indexOf('.'));
            if (catalogA.equalsIgnoreCase(catalogB)) {
                throw new IllegalArgumentException("BE-07 sources must use different catalogs");
            }
            if (expectedMinRows > maxResultRows) {
                throw new IllegalArgumentException(
                        "BE07_EXPECTED_MIN_ROWS must not exceed BE07_MAX_RESULT_ROWS");
            }
            SqlSafetyPolicy policy = new SqlSafetyPolicy(100_000);
            policy.validate(federatedSql);
            policy.validate(validationSql);
            policy.validate(deniedSql);
            policy.validate(resourceLimitSql);
            policy.validate(healthSql);
            requireSource(federatedSql, sourceA, "BE07_FEDERATED_SQL");
            requireSource(federatedSql, sourceB, "BE07_FEDERATED_SQL");
            requireSource(validationSql, sourceA, "BE07_VALIDATION_SQL");
            requireSource(validationSql, sourceB, "BE07_VALIDATION_SQL");
            requireSource(resourceLimitSql, sourceA, "BE07_RESOURCE_LIMIT_SQL");
            requireSource(resourceLimitSql, sourceB, "BE07_RESOURCE_LIMIT_SQL");
            if (!containsSource(deniedSql, sourceA) && !containsSource(deniedSql, sourceB)) {
                throw new IllegalArgumentException(
                        "BE07_DENIED_SQL must reference a configured federated source");
            }
        }

        private static void requireSource(String sql, String source, String variable) {
            if (!containsSource(sql, source)) {
                throw new IllegalArgumentException(variable + " must reference " + source);
            }
        }

        private static boolean containsSource(String sql, String source) {
            String[] parts = source.split("\\.");
            Pattern qualified = Pattern.compile("(?i)(?<![A-Za-z0-9_$])\\\"?"
                    + Pattern.quote(parts[0]) + "\\\"?\\s*\\.\\s*\\\"?"
                    + Pattern.quote(parts[1]) + "\\\"?\\s*\\.");
            return qualified.matcher(sql).find();
        }

        private static String required(Map<String, String> env, String name) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("required environment variable is missing: " + name);
            }
            return value;
        }

        private static String optional(Map<String, String> env, String name, String fallback) {
            String value = env.get(name);
            return value == null || value.isBlank() ? fallback : value;
        }

        private static int boundedInt(Map<String, String> env, String name, int fallback, int min,
                int max) {
            int value = Integer.parseInt(optional(env, name, String.valueOf(fallback)));
            return requireRange(name, value, min, max);
        }

        private static int requiredBoundedInt(Map<String, String> env, String name, int min,
                int max) {
            int value = Integer.parseInt(required(env, name));
            return requireRange(name, value, min, max);
        }

        private static int requireRange(String name, int value, int min, int max) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(
                        name + " must be between " + min + " and " + max);
            }
            return value;
        }

        private static List<String> rejectionFragments(Map<String, String> env, String name) {
            String configured = required(env, name);
            List<String> fragments = java.util.Arrays.stream(configured.split("\\|"))
                    .map(String::trim).filter(value -> !value.isEmpty())
                    .map(value -> value.toLowerCase(Locale.ROOT)).toList();
            if (fragments.isEmpty() || fragments.size() > 8
                    || fragments.stream().anyMatch(value -> value.length() > 128)) {
                throw new IllegalArgumentException(
                        name + " must contain 1 to 8 literal alternatives of at most 128 characters");
            }
            return fragments;
        }
    }
}
