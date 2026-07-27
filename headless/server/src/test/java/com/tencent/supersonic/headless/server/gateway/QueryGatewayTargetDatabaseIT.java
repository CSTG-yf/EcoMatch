package com.tencent.supersonic.headless.server.gateway;

import com.alibaba.druid.pool.DruidDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.core.gateway.QueryExecutionGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Explicit QA-03 target database acceptance test. It is excluded from the default Surefire class
 * pattern and requires QA03_JDBC_URL to avoid accidental external database access.
 */
@EnabledIfEnvironmentVariable(named = "QA03_JDBC_URL", matches = ".+")
class QueryGatewayTargetDatabaseIT {

    private static final int MIN_STABILITY_SECONDS = 300;
    private static final Pattern SAFE_SCENARIO = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Test
    void verifiesTargetDatabaseLatencyStabilityAndCancellation() throws Exception {
        Qa03Config config = Qa03Config.fromEnvironment();
        QueryExecutionGateway gateway =
                new QueryExecutionGateway(config.concurrency(), config.acquireTimeoutMs(), 100_000);

        try (DruidDataSource dataSource = createDataSource(config)) {
            DatabaseIdentity identity = databaseIdentity(dataSource);
            warmUp(dataSource, gateway, config);
            LatencyStats latency = measureLatency(dataSource, gateway, config);
            StabilityStats stability = runStabilityTest(dataSource, gateway, config);
            CancellationStats cancellation = verifyDriverCancellation(dataSource, config);
            QueryExecutionGateway.QueryGatewayStats gatewayStats = gateway.snapshot();

            Path report =
                    writeReport(identity, config, latency, stability, cancellation, gatewayStats);
            System.out.printf(
                    "QA-03 report=%s samples=%d avg=%.2fms p95=%.2fms p99=%.2fms "
                            + "stabilitySuccess=%d stabilityFailure=%d cancellation=%dms%n",
                    report, latency.samples(), latency.averageMs(), latency.p95Ms(),
                    latency.p99Ms(), stability.successes(), stability.failures(),
                    cancellation.elapsedMs());

            assertTrue(latency.averageMs() <= config.maxAverageMs(),
                    "QA-03 average query latency exceeded the configured threshold");
            assertEquals(0, stability.failures(), "QA-03 stability run contained failed queries");
            assertTrue(stability.successes() > 0, "QA-03 stability run did not execute any query");
            assertEquals(0, gatewayStats.activeQueries(),
                    "query gateway retained active queries after the stability run");
            assertEquals(gatewayStats.maxConcurrency(), gatewayStats.availablePermits(),
                    "query gateway did not release every execution permit");
            assertEquals(0, gatewayStats.rejectedQueries(),
                    "query gateway rejected work within configured QA-03 concurrency");
        }
    }

    private DruidDataSource createDataSource(Qa03Config config) throws SQLException {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(config.jdbcUrl());
        dataSource.setUsername(config.jdbcUser());
        dataSource.setPassword(config.jdbcPassword());
        dataSource.setInitialSize(config.concurrency());
        dataSource.setMinIdle(config.concurrency());
        dataSource.setMaxActive(config.concurrency() + 2);
        dataSource.setMaxWait(config.acquireTimeoutMs());
        dataSource.setValidationQuery(config.healthSql());
        dataSource.setTestOnBorrow(true);
        dataSource.init();
        return dataSource;
    }

    private DatabaseIdentity databaseIdentity(DruidDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            return new DatabaseIdentity(metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion(), metadata.getDriverName(),
                    metadata.getDriverVersion());
        }
    }

    private void warmUp(DruidDataSource dataSource, QueryExecutionGateway gateway,
            Qa03Config config) {
        for (int i = 0; i < config.warmupQueries(); i++) {
            executeBenchmark(dataSource, gateway, config);
        }
    }

    private LatencyStats measureLatency(DruidDataSource dataSource, QueryExecutionGateway gateway,
            Qa03Config config) {
        List<Long> samples = new ArrayList<>(config.latencySamples());
        for (int i = 0; i < config.latencySamples(); i++) {
            long start = System.nanoTime();
            executeBenchmark(dataSource, gateway, config);
            samples.add(System.nanoTime() - start);
        }
        Collections.sort(samples);
        double averageMs =
                samples.stream().mapToLong(Long::longValue).average().orElseThrow() / 1_000_000.0;
        return new LatencyStats(samples.size(), averageMs, percentileMs(samples, 0.50),
                percentileMs(samples, 0.95), percentileMs(samples, 0.99),
                samples.get(samples.size() - 1) / 1_000_000.0);
    }

    private StabilityStats runStabilityTest(DruidDataSource dataSource,
            QueryExecutionGateway gateway, Qa03Config config) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(config.concurrency());
        ResourceTracker resourceTracker = new ResourceTracker();
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong successes = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        AtomicReference<String> firstFailureType = new AtomicReference<>();
        AtomicReference<ResourceStats> resources = new AtomicReference<>();
        long durationNanos = TimeUnit.SECONDS.toNanos(config.stabilitySeconds());
        List<Future<?>> workers = new ArrayList<>();
        try {
            resourceTracker.start();
            for (int i = 0; i < config.concurrency(); i++) {
                workers.add(executor.submit(() -> {
                    start.await();
                    long deadline = System.nanoTime() + durationNanos;
                    while (System.nanoTime() < deadline) {
                        try {
                            executeBenchmark(dataSource, gateway, config);
                            successes.incrementAndGet();
                        } catch (RuntimeException | Error error) {
                            failures.incrementAndGet();
                            firstFailureType.compareAndSet(null, error.getClass().getSimpleName());
                            break;
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(config.stabilitySeconds() + config.cancelGraceSeconds(),
                        TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            boolean terminated;
            try {
                terminated =
                        executor.awaitTermination(config.cancelGraceSeconds(), TimeUnit.SECONDS);
            } finally {
                resources.set(resourceTracker.stop());
            }
            assertTrue(terminated, "QA-03 stability workers did not terminate");
        }
        return new StabilityStats(config.stabilitySeconds(), successes.get(), failures.get(),
                firstFailureType.get(), resources.get());
    }

    private CancellationStats verifyDriverCancellation(DruidDataSource dataSource,
            Qa03Config config) throws SQLException {
        long start = System.nanoTime();
        String exceptionType = null;
        String sqlState = null;
        int vendorCode = 0;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(config.timeoutSql())) {
            statement.setQueryTimeout(config.cancelTimeoutSeconds());
            statement.execute();
            fail("QA-03 timeout query completed instead of being cancelled by the JDBC driver");
        } catch (SQLException exception) {
            exceptionType = exception.getClass().getSimpleName();
            sqlState = exception.getSQLState();
            vendorCode = exception.getErrorCode();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        long minimumElapsedMs =
                Math.min(1_000, TimeUnit.SECONDS.toMillis(config.cancelTimeoutSeconds()) / 2);
        long maximumElapsedMs = TimeUnit.SECONDS
                .toMillis(config.cancelTimeoutSeconds() + config.cancelGraceSeconds());
        assertTrue(elapsedMs >= minimumElapsedMs,
                "QA-03 timeout query failed before the configured timeout could cancel it");
        assertTrue(elapsedMs <= maximumElapsedMs,
                "QA-03 JDBC driver did not cancel the query within the grace period");

        executeHealthCheck(dataSource, config);
        long remainingStatements = querySingleLong(dataSource, config.cancellationProbeSql(),
                config.queryTimeoutSeconds());
        assertEquals(config.cancellationProbeExpected(), remainingStatements,
                "QA-03 database-side cancellation probe found a running timeout statement");
        return new CancellationStats(elapsedMs, exceptionType, sqlState, vendorCode,
                remainingStatements);
    }

    private void executeHealthCheck(DruidDataSource dataSource, Qa03Config config)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(config.healthSql())) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            statement.execute();
        }
    }

    private long querySingleLong(DruidDataSource dataSource, String sql, int timeoutSeconds)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "QA-03 cancellation probe did not return a result");
                long value = resultSet.getLong(1);
                assertTrue(!resultSet.wasNull(), "QA-03 cancellation probe returned null");
                return value;
            }
        }
    }

    private int executeBenchmark(DruidDataSource dataSource, QueryExecutionGateway gateway,
            Qa03Config config) {
        return gateway.execute(config.benchmarkSql(), () -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(config.benchmarkSql())) {
                statement.setQueryTimeout(config.queryTimeoutSeconds());
                statement.setMaxRows(config.maxResultRows());
                try (ResultSet resultSet = statement.executeQuery()) {
                    int rows = 0;
                    while (resultSet.next()) {
                        rows++;
                        if (rows > config.maxResultRows()) {
                            throw new Qa03QueryException(
                                    "QA-03 benchmark result exceeded the configured row limit");
                        }
                    }
                    return rows;
                }
            } catch (SQLException exception) {
                throw new Qa03QueryException("QA-03 benchmark query failed with "
                        + exception.getClass().getSimpleName());
            }
        });
    }

    private Path writeReport(DatabaseIdentity identity, Qa03Config config, LatencyStats latency,
            StabilityStats stability, CancellationStats cancellation,
            QueryExecutionGateway.QueryGatewayStats gatewayStats) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("scenario", config.scenario());
        report.put("database", identity);
        report.put("configuration", Map.of("warmupQueries", config.warmupQueries(),
                "latencySamples", config.latencySamples(), "concurrency", config.concurrency(),
                "stabilitySeconds", config.stabilitySeconds(), "queryTimeoutSeconds",
                config.queryTimeoutSeconds(), "cancelTimeoutSeconds", config.cancelTimeoutSeconds(),
                "maxResultRows", config.maxResultRows(), "maxAverageMs", config.maxAverageMs()));
        report.put("latency", latency);
        report.put("stability", stability);
        report.put("cancellation", cancellation);
        report.put("gateway", gatewayStats);

        Path reportPath = Path.of("target", "qa03-" + config.scenario() + "-report.json");
        Files.createDirectories(reportPath.getParent());
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
                .writeValue(reportPath.toFile(), report);
        return reportPath.toAbsolutePath().normalize();
    }

    private double percentileMs(List<Long> sortedNanos, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sortedNanos.size()) - 1);
        return sortedNanos.get(index) / 1_000_000.0;
    }

    private record Qa03Config(String scenario, String jdbcUrl, String jdbcUser,
            String jdbcPassword, String benchmarkSql, String timeoutSql,
            String cancellationProbeSql, long cancellationProbeExpected, String healthSql,
            int warmupQueries, int latencySamples, int concurrency, int stabilitySeconds,
            int queryTimeoutSeconds, int cancelTimeoutSeconds, int cancelGraceSeconds,
            int maxResultRows, long acquireTimeoutMs, double maxAverageMs) {

        private static Qa03Config fromEnvironment() {
            Qa03Config config = new Qa03Config(optional("QA03_SCENARIO", "benchmark"),
                    required("QA03_JDBC_URL"), optional("QA03_JDBC_USER", ""),
                    optional("QA03_JDBC_PASSWORD", ""), required("QA03_BENCHMARK_SQL"),
                    required("QA03_TIMEOUT_SQL"), required("QA03_CANCELLATION_PROBE_SQL"),
                    longValue("QA03_CANCELLATION_PROBE_EXPECTED", 0),
                    optional("QA03_HEALTH_SQL", "SELECT 1"),
                    intValue("QA03_WARMUP_QUERIES", 20),
                    intValue("QA03_LATENCY_SAMPLES", 200),
                    intValue("QA03_CONCURRENCY", 8),
                    intValue("QA03_STABILITY_SECONDS", 1800),
                    intValue("QA03_QUERY_TIMEOUT_SECONDS", 30),
                    intValue("QA03_CANCEL_TIMEOUT_SECONDS", 1),
                    intValue("QA03_CANCEL_GRACE_SECONDS", 10),
                    intValue("QA03_MAX_RESULT_ROWS", 10_000),
                    longValue("QA03_ACQUIRE_TIMEOUT_MS", 5_000),
                    doubleValue("QA03_MAX_AVERAGE_MS", 3_000));
            config.validate();
            return config;
        }

        private void validate() {
            if (!SAFE_SCENARIO.matcher(scenario).matches()) {
                throw new IllegalArgumentException(
                        "QA03_SCENARIO must contain only letters, digits, underscore or hyphen");
            }
            positive(warmupQueries, "QA03_WARMUP_QUERIES");
            positive(latencySamples, "QA03_LATENCY_SAMPLES");
            positive(concurrency, "QA03_CONCURRENCY");
            positive(queryTimeoutSeconds, "QA03_QUERY_TIMEOUT_SECONDS");
            positive(cancelTimeoutSeconds, "QA03_CANCEL_TIMEOUT_SECONDS");
            positive(cancelGraceSeconds, "QA03_CANCEL_GRACE_SECONDS");
            positive(maxResultRows, "QA03_MAX_RESULT_ROWS");
            positive(acquireTimeoutMs, "QA03_ACQUIRE_TIMEOUT_MS");
            positive(maxAverageMs, "QA03_MAX_AVERAGE_MS");
            if (stabilitySeconds < MIN_STABILITY_SECONDS) {
                throw new IllegalArgumentException(
                        "QA03_STABILITY_SECONDS must be at least " + MIN_STABILITY_SECONDS);
            }
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }

        private static String optional(String name, String defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? defaultValue : value;
        }

        private static int intValue(String name, int defaultValue) {
            return Math.toIntExact(longValue(name, defaultValue));
        }

        private static long longValue(String name, long defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " must be an integer");
            }
        }

        private static double doubleValue(String name, double defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " must be numeric");
            }
        }

        private static void positive(double value, String name) {
            if (!Double.isFinite(value) || value <= 0) {
                throw new IllegalArgumentException(name + " must be greater than zero");
            }
        }
    }

    private record DatabaseIdentity(String productName, String productVersion, String driverName,
            String driverVersion) {}

    private record LatencyStats(int samples, double averageMs, double p50Ms, double p95Ms,
            double p99Ms, double maxMs) {}

    private record StabilityStats(int durationSeconds, long successes, long failures,
            String firstFailureType, ResourceStats resources) {}

    private record CancellationStats(long elapsedMs, String exceptionType, String sqlState,
            int vendorCode, long remainingStatements) {}

    private record ResourceStats(long initialHeapBytes, long finalHeapBytes, long maxHeapBytes,
            int initialThreadCount, int finalThreadCount, int maxThreadCount,
            double processCpuPercent) {}

    private static final class ResourceTracker {

        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        private final com.sun.management.OperatingSystemMXBean operatingSystem =
                ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        private final ScheduledExecutorService sampler =
                Executors.newSingleThreadScheduledExecutor();
        private final AtomicLong maxHeapBytes = new AtomicLong();
        private final AtomicLong maxThreadCount = new AtomicLong();
        private long initialHeapBytes;
        private int initialThreadCount;
        private long initialCpuNanos;
        private long startNanos;

        private void start() {
            initialHeapBytes = memory.getHeapMemoryUsage().getUsed();
            initialThreadCount = threads.getThreadCount();
            initialCpuNanos = operatingSystem == null ? -1 : operatingSystem.getProcessCpuTime();
            startNanos = System.nanoTime();
            sample();
            sampler.scheduleAtFixedRate(this::sample, 1, 1, TimeUnit.SECONDS);
        }

        private ResourceStats stop() {
            sampler.shutdownNow();
            sample();
            long finalHeapBytes = memory.getHeapMemoryUsage().getUsed();
            int finalThreadCount = threads.getThreadCount();
            double cpuPercent = processCpuPercent(System.nanoTime() - startNanos);
            return new ResourceStats(initialHeapBytes, finalHeapBytes, maxHeapBytes.get(),
                    initialThreadCount, finalThreadCount, Math.toIntExact(maxThreadCount.get()),
                    cpuPercent);
        }

        private void sample() {
            maxHeapBytes.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
            maxThreadCount.accumulateAndGet(threads.getThreadCount(), Math::max);
        }

        private double processCpuPercent(long elapsedNanos) {
            if (operatingSystem == null || initialCpuNanos < 0 || elapsedNanos <= 0) {
                return -1;
            }
            long usedCpuNanos = operatingSystem.getProcessCpuTime() - initialCpuNanos;
            int processors = Math.max(1, operatingSystem.getAvailableProcessors());
            return Math.max(0, usedCpuNanos * 100.0 / elapsedNanos / processors);
        }
    }

    private static final class Qa03QueryException extends RuntimeException {

        private Qa03QueryException(String message) {
            super(message);
        }
    }
}
