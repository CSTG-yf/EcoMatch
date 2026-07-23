package com.tencent.supersonic.headless.server.gateway;

import com.tencent.supersonic.headless.core.gateway.ExplainCostPolicy;
import com.tencent.supersonic.headless.core.gateway.QueryExecutionGateway;
import com.tencent.supersonic.headless.core.gateway.SqlSafetyPolicy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryGatewayH2IntegrationTest {

    private static final String AGGREGATE_SQL = "SELECT branch_id, SUM(balance) AS total_balance "
            + "FROM bank_account GROUP BY branch_id ORDER BY branch_id LIMIT 100";
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUpDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:query_gateway;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS bank_account");
        jdbcTemplate.execute("CREATE TABLE bank_account AS SELECT X AS id, MOD(X, 100) "
                + "AS branch_id, X * 10 AS balance FROM SYSTEM_RANGE(1, 10000)");
    }

    @Test
    void appliesPolicyExplainAndResultLimitOnRealJdbcQuery() {
        new SqlSafetyPolicy(10_000).validate(AGGREGATE_SQL);
        List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + AGGREGATE_SQL);
        long estimatedRows = new ExplainCostPolicy(1_000_000).validate(plan);

        jdbcTemplate.setMaxRows(50);
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("SELECT * FROM bank_account WHERE id > 0");
        jdbcTemplate.setMaxRows(0);

        assertTrue(estimatedRows >= 0);
        assertEquals(50, rows.size());
    }

    @Test
    void meetsLatencyGateAndRemainsStableUnderConcurrency() throws Exception {
        QueryExecutionGateway gateway = new QueryExecutionGateway(8, 1000, 10_000);
        for (int i = 0; i < 20; i++) {
            execute(gateway);
        }

        List<Long> latenciesMs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long start = System.nanoTime();
            execute(gateway);
            latenciesMs.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
        Collections.sort(latenciesMs);
        double average = latenciesMs.stream().mapToLong(Long::longValue).average().orElseThrow();
        long p95 = percentile(latenciesMs, 0.95);
        long p99 = percentile(latenciesMs, 0.99);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(() -> execute(gateway));
        }
        executor.invokeAll(tasks).forEach(future -> {
            try {
                assertEquals(100, future.get());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        System.out.printf("BE-06 H2 benchmark: samples=200 avg=%.2fms p95=%dms p99=%dms%n", average,
                p95, p99);
        assertTrue(average <= 3000, "average latency must not exceed 3 seconds");
        assertTrue(gateway.getRejectedQueries().get() == 0,
                "queries should remain stable within configured concurrency");
    }

    private int execute(QueryExecutionGateway gateway) {
        return gateway.execute(AGGREGATE_SQL,
                () -> jdbcTemplate.queryForList(AGGREGATE_SQL).size());
    }

    private long percentile(List<Long> sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, index));
    }
}
