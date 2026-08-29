package com.tencent.supersonic.headless.chat.parser.llm.bank.difftest;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanCompiler;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanCompiler.CompiledQuery;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanCompiler.CompilationRoute;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankSemanticRegistry;
import com.tencent.supersonic.headless.chat.parser.llm.bank.difftest.BankDiffAssert.Comparison;
import com.tencent.supersonic.headless.chat.parser.llm.bank.difftest.BankDiffOracle.Variant;
import com.tencent.supersonic.headless.chat.parser.llm.bank.difftest.BankDiffPlanGenerator.Family;
import com.tencent.supersonic.headless.chat.parser.llm.bank.difftest.BankDiffPlanGenerator.Generated;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Differential property tests for the bank query families
 * (AGGREGATION_SUMMARY / RATIO / CHANGE / PROVINCE_AVERAGE / DERIVED_RANKING /
 * ABSOLUTE_THRESHOLD).
 *
 * <p>Per plan the test compiles the family SQL through the real
 * {@code BankQueryPlanCompiler + BankS2SqlTemplateFactory}, mirrors the two Chinese identifiers
 * exactly like the runtime engine boundary, executes the SQL against a fixed-seed synthetic
 * in-memory Calcite table, and compares the result as a multiset (numeric tolerance 1e-6, row
 * order ignored) against a naive pure-Java oracle that re-implements the family semantics without
 * SQL. Every generated plan must pass {@code BankQueryPlanValidator} before it is used; rejected
 * candidates are regenerated and the discard rate is printed. On mismatch the plan, the SQL and
 * both row sets are dumped (truncated) in the assertion message.
 *
 * <p>DERIVED_RANKING plans additionally pin the rank-slice contract: when a plan carries
 * rank/rank_from_bottom filters, the committed contract limits are applied to the executed rows
 * exactly like {@code BankResultProjector#sliceDerivedRankingRows} and the resulting rank
 * ordinals per metric must equal the requested top/bottom rank set exactly — a property over the
 * ROW_NUMBER ordinals, not just the value multiset. Limit-only plans (a limit without any rank
 * filter) are deliberately never generated: their slice semantics sit on the
 * compiler/projector boundary under active change. ABSOLUTE_THRESHOLD plans never carry a
 * province-average benchmark or direction object (that family variant is owned elsewhere).
 *
 * <p>This test only adds test-scope fixtures; no main code is modified.
 */
class BankFamilyDifferentialPropertyTest {

    private static final int AGGREGATION_PLANS = 48;
    private static final int RATIO_PLANS = 44;
    private static final int CHANGE_PLANS = 60;
    private static final int PROVINCE_AVERAGE_PLANS = 48;
    private static final int DERIVED_RANKING_PLANS = 48;
    private static final int ABSOLUTE_THRESHOLD_PLANS = 44;
    private static final int MAX_DIAGNOSTIC_ROWS = 5;
    private static final int MAX_SQL_DIAGNOSTIC_CHARS = 2400;

    private static final BankDiffDataset DATASET = BankDiffDataset.build();
    private static final BankDiffOracle ORACLE = new BankDiffOracle(DATASET);

    private static BankDiffExecutor executor;

    @BeforeAll
    static void startExecutor() {
        executor = new BankDiffExecutor(DATASET);
    }

    @AfterAll
    static void stopExecutor() throws SQLException {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void aggregationSummaryFamilyMatchesNaiveOracleOnRandomPlans() {
        runFamily(Family.AGGREGATION_SUMMARY, 202608271L, AGGREGATION_PLANS);
    }

    @Test
    void ratioFamilyMatchesNaiveOracleOnRandomPlans() {
        runFamily(Family.RATIO, 202608272L, RATIO_PLANS);
    }

    @Test
    void changeFamilyMatchesNaiveOracleOnRandomPlans() {
        runFamily(Family.CHANGE, 202608273L, CHANGE_PLANS);
    }

    @Test
    void provinceAverageFamilyMatchesNaiveOracleOnRandomPlans() {
        runFamily(Family.PROVINCE_AVERAGE, 202608274L, PROVINCE_AVERAGE_PLANS);
    }

    @Test
    void derivedRankingFamilyMatchesNaiveOracleOnRandomPlans() {
        runDerivedRankingFamily(202608275L, DERIVED_RANKING_PLANS);
    }

    @Test
    void absoluteThresholdFamilyMatchesNaiveOracleOnRandomPlans() {
        runFamily(Family.ABSOLUTE_THRESHOLD, 202608276L, ABSOLUTE_THRESHOLD_PLANS,
                List.of("bank_organization", "metric_value", "meets_condition"));
    }

    private void runFamily(Family family, long seed, int planCount) {
        runFamily(family, seed, planCount, null);
    }

    private void runFamily(Family family, long seed, int planCount,
            List<String> expectedOutputColumns) {
        List<Generated> plans = new BankDiffPlanGenerator(family, seed).generate(planCount);
        assertEquals(planCount, plans.size(), () -> "generator produced too few plans");
        BankQueryPlanCompiler compiler = new BankQueryPlanCompiler();
        List<String> failures = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            Generated generated = plans.get(index);
            String header = "family=%s seed=%d planIndex=%d variant=%s".formatted(family, seed,
                    index, generated.variant());
            try {
                CompiledQuery compiled = compiler.compile(generated.plan(), generated.hints(),
                        bankSchema());
                assertEquals(CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute(),
                        header + ": family plan must compile through the S2SQL template route");
                if (expectedOutputColumns != null) {
                    assertEquals(expectedOutputColumns, compiled.getOutputColumns(),
                            header + ": output column contract drifted");
                }
                String sql = compiled.getS2sql();
                List<Object[]> actual = executor.execute(BankDiffDataset.mirror(sql));
                List<List<Object>> expected = ORACLE.evaluate(generated.plan(),
                        generated.variant());
                Comparison comparison = BankDiffAssert.compareMultiset(actual, expected,
                        BankDiffAssert.TOLERANCE);
                if (!comparison.matches()) {
                    failures.add(diagnostic(header, generated, sql, compiled, comparison));
                }
            } catch (RuntimeException | AssertionError exception) {
                java.io.StringWriter stack = new java.io.StringWriter();
                exception.printStackTrace(new java.io.PrintWriter(stack));
                failures.add(header + " raised " + exception + "\nplan: "
                        + BankDiffAssert.truncate(generated.plan().toString(), 800) + "\n"
                        + BankDiffAssert.truncate(stack.toString(), 1500) + "\n");
            }
        }
        if (!failures.isEmpty()) {
            fail("%d/%d %s plans diverged from the naive oracle:%n%s".formatted(failures.size(),
                    planCount, family, String.join("\n---\n", failures)));
        }
    }

    /**
     * DERIVED_RANKING differential with the rank-slice property: rows are compared after applying
     * the committed contract slice (top/bottom rank limits) to both sides, and sliced plans must
     * additionally emit exactly the requested rank ordinals per metric.
     */
    private void runDerivedRankingFamily(long seed, int planCount) {
        List<Generated> plans = new BankDiffPlanGenerator(Family.DERIVED_RANKING, seed)
                .generate(planCount);
        assertEquals(planCount, plans.size(), () -> "generator produced too few plans");
        BankQueryPlanCompiler compiler = new BankQueryPlanCompiler();
        List<String> failures = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            Generated generated = plans.get(index);
            String header = "family=%s seed=%d planIndex=%d variant=%s".formatted(
                    Family.DERIVED_RANKING, seed, index, generated.variant());
            try {
                CompiledQuery compiled = compiler.compile(generated.plan(), generated.hints(),
                        bankSchema());
                assertEquals(CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute(),
                        header + ": ranking plan must compile through the S2SQL template route");
                assertEquals(List.of("metric_code", "bank_organization", "metric_value",
                        "rank_position"), compiled.getOutputColumns(),
                        header + ": derived-ranking output column contract drifted");
                Integer topRankLimit = compiled.getResultContract() == null
                        ? null : compiled.getResultContract().getTopRankLimit();
                Integer bottomRankLimit = compiled.getResultContract() == null
                        ? null : compiled.getResultContract().getBottomRankLimit();
                assertEquals(rankFilterValue(generated.plan(), "rank"), topRankLimit,
                        header + ": contract topRankLimit must mirror the plan rank filter");
                assertEquals(rankFilterValue(generated.plan(), "rank_from_bottom"),
                        bottomRankLimit,
                        header + ": contract bottomRankLimit must mirror the plan rank filter");
                String sql = compiled.getS2sql();
                List<Object[]> actual = executor.execute(BankDiffDataset.mirror(sql));
                List<List<Object>> expected = ORACLE.evaluate(generated.plan(),
                        generated.variant());
                List<List<Object>> slicedActual = sliceRankedRows(rowLists(actual), topRankLimit,
                        bottomRankLimit);
                List<List<Object>> slicedExpected = sliceRankedRows(expected, topRankLimit,
                        bottomRankLimit);
                String sliceFailure = rankSlicePropertyFailure(header, expected, slicedActual,
                        topRankLimit, bottomRankLimit);
                if (sliceFailure != null) {
                    failures.add(sliceFailure);
                    continue;
                }
                Comparison comparison = BankDiffAssert.compareRowMultiset(slicedActual,
                        slicedExpected, BankDiffAssert.TOLERANCE);
                if (!comparison.matches()) {
                    failures.add(diagnostic(header, generated, sql, compiled, comparison));
                }
            } catch (RuntimeException | AssertionError exception) {
                java.io.StringWriter stack = new java.io.StringWriter();
                exception.printStackTrace(new java.io.PrintWriter(stack));
                failures.add(header + " raised " + exception + "\nplan: "
                        + BankDiffAssert.truncate(generated.plan().toString(), 800) + "\n"
                        + BankDiffAssert.truncate(stack.toString(), 1500) + "\n");
            }
        }
        if (!failures.isEmpty()) {
            fail("%d/%d %s plans diverged from the naive oracle:%n%s".formatted(failures.size(),
                    planCount, Family.DERIVED_RANKING, String.join("\n---\n", failures)));
        }
    }

    private static List<List<Object>> rowLists(List<Object[]> rows) {
        List<List<Object>> lists = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            // Arrays.asList (not List.of): result rows legitimately contain NULL cells.
            lists.add(Arrays.asList(row));
        }
        return lists;
    }

    /**
     * Applies the committed projector rank-slice contract to ranked long-form rows
     * (metric_code, bank_organization, metric_value, rank_position), mirroring
     * {@code BankResultProjector#sliceDerivedRankingRows}: per metric code the population is the
     * highest returned rank_position, top slices keep rank &lt;= topRankLimit, bottom slices keep
     * rank &gt; population - bottomRankLimit. Slicing both sides here stays idempotent should the
     * template later move the cut into the SQL itself.
     */
    private static List<List<Object>> sliceRankedRows(List<List<Object>> rows,
            Integer topRankLimit, Integer bottomRankLimit) {
        if (topRankLimit == null && bottomRankLimit == null) {
            return rows;
        }
        Map<String, Integer> populationByMetric = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            populationByMetric.merge(metricCode(row), rankPosition(row), Math::max);
        }
        List<List<Object>> sliced = new ArrayList<>();
        for (String metric : populationByMetric.keySet()) {
            for (List<Object> row : rows) {
                if (!metricCode(row).equals(metric)) {
                    continue;
                }
                if (isRequestedRankSlice(topRankLimit, bottomRankLimit, rankPosition(row),
                        populationByMetric.get(metric))) {
                    sliced.add(row);
                }
            }
        }
        return sliced;
    }

    private static boolean isRequestedRankSlice(Integer topRankLimit, Integer bottomRankLimit,
            int rank, int population) {
        if (topRankLimit != null && rank <= topRankLimit) {
            return true;
        }
        return bottomRankLimit != null && rank > population - bottomRankLimit;
    }

    /**
     * Rank-slice property: for plans carrying rank/rank_from_bottom filters, the emitted rows per
     * metric must carry exactly the requested rank ordinals {1..top} / {population-bottom+1 ..
     * population} / their union, where the population is the metric's full ranked row count from
     * the oracle. This pins the ROW_NUMBER ordinals themselves — not just the value multiset.
     * Returns null when the property holds (or no slice was requested).
     */
    private String rankSlicePropertyFailure(String header, List<List<Object>> fullOracleRows,
            List<List<Object>> slicedActual, Integer topRankLimit, Integer bottomRankLimit) {
        if (topRankLimit == null && bottomRankLimit == null) {
            return null;
        }
        Map<String, Integer> populationByMetric = new LinkedHashMap<>();
        for (List<Object> row : fullOracleRows) {
            populationByMetric.merge(metricCode(row), rankPosition(row), Math::max);
        }
        Map<String, TreeSet<Integer>> actualRanksByMetric = new LinkedHashMap<>();
        for (List<Object> row : slicedActual) {
            actualRanksByMetric.computeIfAbsent(metricCode(row), ignored -> new TreeSet<>())
                    .add(rankPosition(row));
        }
        for (Map.Entry<String, Integer> population : populationByMetric.entrySet()) {
            TreeSet<Integer> expectedRanks = new TreeSet<>();
            for (int rank = 1; rank <= population.getValue(); rank++) {
                if (isRequestedRankSlice(topRankLimit, bottomRankLimit, rank,
                        population.getValue())) {
                    expectedRanks.add(rank);
                }
            }
            TreeSet<Integer> actualRanks = actualRanksByMetric.getOrDefault(population.getKey(),
                    new TreeSet<>());
            if (!expectedRanks.equals(actualRanks)) {
                return header + ": metric " + population.getKey()
                        + " rank-slice set mismatch — expected ranks " + expectedRanks
                        + " but SQL rows carry " + actualRanks;
            }
        }
        return null;
    }

    private static String metricCode(List<Object> rankingRow) {
        return String.valueOf(rankingRow.get(0));
    }

    private static int rankPosition(List<Object> rankingRow) {
        return ((Number) rankingRow.get(3)).intValue();
    }

    private static Integer rankFilterValue(BankQueryPlan plan, String field) {
        return plan.getFilters().stream().filter(filter -> field.equals(filter.getField()))
                .map(BankQueryPlan.Filter::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst().map(Integer::valueOf).orElse(null);
    }

    private String diagnostic(String header, Generated generated, String sql,
            CompiledQuery compiled, Comparison comparison) {
        StringBuilder builder = new StringBuilder();
        builder.append(header).append(" mismatch\n");
        builder.append("plan: ").append(BankDiffAssert.truncate(generated.plan().toString(), 1500))
                .append('\n');
        builder.append("sql: ").append(BankDiffAssert.truncate(sql, MAX_SQL_DIAGNOSTIC_CHARS))
                .append('\n');
        builder.append("columns: ").append(compiled.getOutputColumns()).append('\n');
        builder.append("rows: sql=%d oracle=%d%n".formatted(comparison.actualRowCount(),
                comparison.expectedRowCount()));
        builder.append("unmatched SQL rows (up to %d):%n".formatted(MAX_DIAGNOSTIC_ROWS));
        appendRows(builder, comparison.unmatchedActual());
        builder.append("unmatched oracle rows (up to %d):%n".formatted(MAX_DIAGNOSTIC_ROWS));
        appendRows(builder, comparison.unmatchedExpected());
        return builder.toString();
    }

    private void appendRows(StringBuilder builder, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            builder.append("  (none)\n");
            return;
        }
        rows.stream().limit(MAX_DIAGNOSTIC_ROWS)
                .forEach(row -> builder.append("  ").append(BankDiffAssert.render(row))
                        .append(row.size() > MAX_DIAGNOSTIC_ROWS ? " ..." : "").append('\n'));
        if (rows.size() > MAX_DIAGNOSTIC_ROWS) {
            builder.append("  ... and ").append(rows.size() - MAX_DIAGNOSTIC_ROWS)
                    .append(" more\n");
        }
    }

    /** Semantic schema mirroring the runtime bank dataset (zb001..zb019, org + date dims). */
    private static LLMReq.LLMSchema bankSchema() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(97L);
        schema.setDataSetName(BankDiffDataset.DATA_SET_NAME);
        List<SchemaElement> metrics = new ArrayList<>();
        for (int metric = 1; metric <= BankDiffDataset.METRIC_COUNT; metric++) {
            String code = String.format(Locale.ROOT, "ZB%03d", metric);
            metrics.add(SchemaElement.builder()
                    .name(BankSemanticRegistry.metrics().get(code).name())
                    .bizName(code.toLowerCase(Locale.ROOT)).defaultAgg("SUM").build());
        }
        schema.setMetrics(metrics);
        schema.setDimensions(List.of(
                SchemaElement.builder().name("机构").bizName("bank_organization").build()));
        schema.setPartitionTime(SchemaElement.builder().name(BankDiffDataset.DATE_FIELD_NAME)
                .bizName("bank_data_date").build());
        return schema;
    }
}
