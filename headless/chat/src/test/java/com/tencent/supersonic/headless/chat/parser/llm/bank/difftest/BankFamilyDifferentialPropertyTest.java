package com.tencent.supersonic.headless.chat.parser.llm.bank.difftest;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
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
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Differential property tests for the four target bank query families
 * (AGGREGATION_SUMMARY / RATIO / CHANGE / PROVINCE_AVERAGE).
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
 * <p>This test only adds test-scope fixtures; no main code is modified.
 */
class BankFamilyDifferentialPropertyTest {

    private static final int AGGREGATION_PLANS = 48;
    private static final int RATIO_PLANS = 44;
    private static final int CHANGE_PLANS = 60;
    private static final int PROVINCE_AVERAGE_PLANS = 48;
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

    private void runFamily(Family family, long seed, int planCount) {
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
