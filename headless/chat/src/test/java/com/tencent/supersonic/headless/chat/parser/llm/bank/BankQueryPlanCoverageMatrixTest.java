package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanCompiler.CompiledQuery;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanCompiler.CompilationRoute;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic coverage-matrix tests for the compiler routing table. Every declared family keeps
 * compiling through its original route after the table refactor (branch equivalence for the dev
 * shapes), and the one undeclared shape (a ranking intent executed through the CHANGE family)
 * now fails loudly with UNSUPPORTED_QUERY_SHAPE instead of silently using a near-miss template.
 */
class BankQueryPlanCoverageMatrixTest {

    private final BankQueryPlanValidator validator = new BankQueryPlanValidator();
    private final BankQueryPlanCompiler compiler = new BankQueryPlanCompiler();

    @Test
    void singleMetricPeriodOverPeriodChangeKeepsChangeFamily() {
        PlanAndHints candidate = changePeriod();
        CompiledQuery compiled = assertCompiles(candidate);
        assertEquals(CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertTrue(compiled.getOutputColumns().contains("absolute_change"));
    }

    @Test
    void momAndYoyChangeKeepsChangeFamilyWithMomYoyContract() {
        CompiledQuery compiled = assertCompiles(changeMomAndYoy());
        assertEquals(BankResultProjector.ProjectionType.MOM_YOY_CHANGE,
                compiled.getResultContract().getType());
    }

    @Test
    void startOfYearChangeKeepsChangeFamily() {
        CompiledQuery compiled = assertCompiles(changeStartOfYear());
        assertTrue(compiled.getOutputColumns().containsAll(
                List.of("current_value", "baseline_value", "absolute_change")));
    }

    @Test
    void multiMetricChangeKeepsChangeFamily() {
        CompiledQuery compiled = assertCompiles(multiMetricChange());
        assertEquals(BankResultProjector.ProjectionType.MULTI_METRIC_CHANGE,
                compiled.getResultContract().getType());
    }

    @Test
    void rankedChangeWithAdvisoryRankFilterKeepsChangeFamily() {
        assertCompiles(rankedChangeH18());
    }

    @Test
    void ratioKeepsRatioFamily() {
        CompiledQuery compiled = assertCompiles(ratio());
        assertEquals(CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertEquals(BankResultProjector.ProjectionType.RATIO,
                compiled.getResultContract().getType());
    }

    @Test
    void topRankingKeepsDirectFamily() {
        assertCompiles(rankingTop());
    }

    @Test
    void bottomRankingKeepsDirectFamily() {
        assertCompiles(rankingFromBottom());
    }

    @Test
    void provinceAverageThresholdKeepsProvinceAverageFamily() {
        CompiledQuery compiled = assertCompiles(provinceAverageThreshold());
        assertEquals(BankResultProjector.ProjectionType.PROVINCIAL_AVERAGE_THRESHOLD,
                compiled.getResultContract().getType());
    }

    @Test
    void multiMetricProvinceAverageKeepsProvinceAverageFamily() {
        CompiledQuery compiled = assertCompiles(multiMetricProvinceAverageAggregation());
        assertEquals(BankResultProjector.ProjectionType.MULTI_METRIC_PROVINCIAL_AVERAGE,
                compiled.getResultContract().getType());
    }

    /**
     * Synthetic multi-metric threshold + province-average benchmark (W4a defect 2). A threshold
     * intent asks "which organizations satisfy the benchmark", so the shape must compile through
     * the threshold template whose rows carry an explicit meets_condition fact; the long-form gap
     * contract answers the comparison intent instead.
     */
    @Test
    void multiMetricThresholdRoutesToTheWideThresholdContract() {
        CompiledQuery compiled = assertCompiles(multiMetricProvinceAverageThreshold());
        assertEquals(
                BankResultProjector.ProjectionType.MULTI_METRIC_PROVINCIAL_AVERAGE_THRESHOLD,
                compiled.getResultContract().getType());
        assertEquals(List.of("bank_organization", "metric_code", "metric_value",
                "provincial_average", "gap_value", "meets_condition"),
                compiled.getOutputColumns());
        String sql = compiled.getS2sql();
        assertTrue(sql.contains("WITH bank_org AS"));
        assertTrue(sql.contains("AS metric_code"));
        assertTrue(sql.contains("provincial_average"));
        assertTrue(sql.contains("meets_condition"));
        assertTrue(sql.contains("ORDER BY metric_code ASC, bank_organization ASC"));
        // Direction-aware satisfaction (test r6 TST-H-10 lesson): a lower-better metric meets
        // below the average, a higher-better one above it — never a direction-blind sign flag.
        String directionSql = assertCompiles(
                multiMetricProvinceAverageThreshold("ZB013", "ZB015")).getS2sql();
        assertTrue(directionSql.contains("(bank_values.metric_code = 'ZB013'"
                + " AND bank_values.metric_value < province_average.provincial_average)"),
                () -> directionSql);
        assertTrue(directionSql.contains("(bank_values.metric_code = 'ZB015'"
                + " AND bank_values.metric_value > province_average.provincial_average)"),
                () -> directionSql);
    }

    /** Comparison intent keeps the long-form aggregation summary + gap contract. */
    @Test
    void multiMetricComparisonKeepsTheLongFormGapContract() {
        CompiledQuery compiled = assertCompiles(multiMetricProvinceAverageComparison());
        assertEquals(BankResultProjector.ProjectionType.MULTI_METRIC_PROVINCIAL_AVERAGE,
                compiled.getResultContract().getType());
        assertEquals(List.of("bank_organization", "metric_code", "aggregate_value", "min_value",
                "max_value", "observation_count"), compiled.getOutputColumns());
        String sql = compiled.getS2sql();
        assertTrue(sql.contains("bank_daily_values_0 AS"));
        assertTrue(sql.contains("UNION ALL"));
        assertFalse(sql.contains("meets_condition"));
    }

    @Test
    void absoluteThresholdKeepsAbsoluteThresholdFamily() {
        CompiledQuery compiled = assertCompiles(absoluteThreshold());
        assertEquals(BankResultProjector.ProjectionType.ABSOLUTE_THRESHOLD,
                compiled.getResultContract().getType());
    }

    @Test
    void dailyAggregationSummaryKeepsAggregationSummaryFamily() {
        CompiledQuery compiled = assertCompiles(dailyAggregationSummary());
        assertEquals(BankResultProjector.ProjectionType.AGGREGATION_SUMMARY,
                compiled.getResultContract().getType());
    }

    @Test
    void daysAboveProvinceAverageKeepsCountDaysFamily() {
        CompiledQuery compiled = assertCompiles(daysAboveProvinceAverage());
        assertEquals(BankResultProjector.ProjectionType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE,
                compiled.getResultContract().getType());
    }

    @Test
    void derivedRankingKeepsDerivedRankingFamily() {
        CompiledQuery compiled = assertCompiles(derivedRanking());
        assertEquals(BankResultProjector.ProjectionType.DERIVED_RANKING,
                compiled.getResultContract().getType());
    }

    /**
     * Synthetic multi-metric ranking + top/bottom rank slice (W4a defect 1). The ranked template
     * family must own this shape so the SQL carries metric_code + rank_position identity; the
     * bare long-form struct route loses that identity and the in-memory rank slice collapses to
     * an empty result. The end-to-end half feeds the template-shaped rows back through the
     * projector and requires a non-empty slice with contiguous per-metric ranks.
     */
    @Test
    void multiMetricRankingWithRankSliceKeepsRankedTemplateFamily() {
        CompiledQuery compiled = assertCompiles(multiMetricRankingWithRankSlice());
        assertEquals(CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertEquals(BankResultProjector.ProjectionType.DERIVED_RANKING,
                compiled.getResultContract().getType());
        assertEquals(Integer.valueOf(3), compiled.getResultContract().getTopRankLimit());
        assertEquals(Integer.valueOf(3), compiled.getResultContract().getBottomRankLimit());
        String sql = compiled.getS2sql();
        assertTrue(sql.contains("WITH bank_metric_0 AS"));
        assertTrue(sql.contains("ROW_NUMBER() OVER (ORDER BY"));
        assertTrue(sql.contains("AS rank_position"));
        assertFalse(sql.contains("RANK()"));

        List<String> codes = List.of("ZB001", "ZB002", "ZB003", "ZB004", "ZB011");
        List<Map<String, Object>> sourceRows = new java.util.ArrayList<>();
        for (String code : codes) {
            for (int rank = 1; rank <= 13; rank++) {
                sourceRows.add(Map.of("metric_code", code,
                        "bank_organization", String.format("ORG%03d", rank),
                        "metric_value", new java.math.BigDecimal(200 - rank),
                        "rank_position", rank));
            }
        }
        BankResultProjector.Projection projection = new BankResultProjector()
                .project(compiled.getResultContract(), sourceRows);
        assertTrue(projection.isApplied());
        assertEquals(
                List.of("metric_code", "org_code", "org_name", "metric_value", "rank_position"),
                projection.getColumns());
        assertEquals(30, projection.getRows().size());
        for (String code : codes) {
            List<Object> ranks = projection.getRows().stream()
                    .filter(row -> code.equals(row.get("metric_code")))
                    .map(row -> row.get("rank_position")).toList();
            assertEquals(List.of(1, 2, 3, 11, 12, 13), ranks, code);
        }
    }

    @Test
    void structureShareKeepsGenericDirectFamily() {
        CompiledQuery compiled = assertCompiles(structureShare());
        assertEquals(List.of("bank_organization", "ZB001", "ZB003", "ZB004"),
                compiled.getOutputColumns());
    }

    @Test
    void trendKeepsGenericDirectFamilyWithTrendContract() {
        CompiledQuery compiled = assertCompiles(trend());
        assertEquals(BankResultProjector.ProjectionType.TREND,
                compiled.getResultContract().getType());
    }

    @Test
    void organizationComparisonKeepsComparisonFamily() {
        assertCompiles(organizationComparison());
    }

    @Test
    void rankingIntentThroughChangeFamilyIsAnUnsupportedQueryShape() {
        PlanAndHints candidate = rankingIntentChange();
        BankQueryPlanValidator.ValidationResult validation =
                validator.validate(candidate.plan(), candidate.hints());
        assertTrue(validation.isValid(), validation.summary());

        BankPlanCompilationException exception = assertThrows(BankPlanCompilationException.class,
                () -> compiler.compile(candidate.plan(), candidate.hints(), schema()));
        assertEquals(BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE,
                exception.getReason());
        assertTrue(exception.getMessage().contains("查询形状未申报"));
        assertTrue(exception.getMessage().contains("已支持的查询族"));
        assertTrue(exception.getMessage().contains("CHANGE"));
        assertTrue(exception.getMessage().contains("RANKING"));
    }

    /**
     * Dedicated family: "rank change across periods" (机构/指标排名在两个时期间的位次变化) routes
     * through calculation.type=RANK_CHANGE. The strategy gate enforces family↔question coherence
     * in both directions — the shape must declare RANK_CHANGE (a near-miss CHANGE plan is a
     * repairable failure, not a silent wrong-family success) and RANK_CHANGE may not appear on
     * questions outside the shape — while single-period rankings, plain value changes,
     * share/composition questions and magnitude rankings all stay outside the trigger.
     */
    @Test
    void rankChangeAcrossPeriodsRoutesIntoTheDedicatedFamily() {
        assertTrue(BankPlanGenStrategy.isRankChangeAcrossPeriodsQuestion(
                "从2024年末到2026年4月末，存款、贷款、不良率、净利润的排名分别变化了多少？"));
        assertTrue(BankPlanGenStrategy.isRankChangeAcrossPeriodsQuestion(
                "各行不良贷款率较年初的排名变动情况如何？"));

        // The near-miss CHANGE plan a model would first emit for the shape is rejected by the
        // plan gate as a repairable failure that tells the model to declare RANK_CHANGE.
        BankQueryPlanParseException required = assertThrows(BankQueryPlanParseException.class,
                () -> BankPlanGenStrategy.validateRankChangePlanContract(
                        "从2024年末到2026年4月末，存款、贷款的排名分别变化了多少？",
                        rankChangeNearMissPlan().plan()));
        assertTrue(required.getMessage().contains("rank_change_plan_contract_required"));

        // A RANK_CHANGE plan for a question without the shape is equally rejected.
        BankQueryPlanParseException notApplicable = assertThrows(BankQueryPlanParseException.class,
                () -> BankPlanGenStrategy.validateRankChangePlanContract(
                        "某农商行2026年3月末各项存款余额是多少？",
                        rankChangePlan().plan()));
        assertTrue(notApplicable.getMessage().contains("rank_change_plan_not_applicable"));

        // Negative space that must stay supported (never routed into the RANK_CHANGE family).
        assertFalse(BankPlanGenStrategy.isRankChangeAcrossPeriodsQuestion(
                "某农商行从2024年末到2026年4月末，存款和贷款的余额分别变化了多少？"));
        assertFalse(BankPlanGenStrategy.isRankChangeAcrossPeriodsQuestion(
                "2026年4月末不良贷款率较年初变化了多少？"));
        assertFalse(BankPlanGenStrategy.isRankChangeAcrossPeriodsQuestion(
                "2024年末存款余额排名前三的机构有哪些？"));
        assertFalse(BankPlanGenStrategy.isRankChangeAcrossPeriodsQuestion(
                "2024年末存款余额排名后三的机构有哪些？"));
        assertFalse(BankPlanGenStrategy.isRankChangeAcrossPeriodsQuestion(
                "2026年2月末对公存款和个人存款占存款总额的占比是多少？"));
        assertFalse(BankPlanGenStrategy.isRankChangeAcrossPeriodsQuestion(
                "较年初各家农商行净利润增幅排名前三的是哪些？"));
    }

    /**
     * End-to-end rank-change family: plan → SQL asserts the two period windows, per-window
     * ROW_NUMBER ranks and the baseline-current join; then the projector passes the SQL-computed
     * rank facts through with organization identity (delta positive = moved up).
     */
    @Test
    void rankChangeAcrossPeriodsCompilesTheRankChangeTemplate() {
        PlanAndHints candidate = rankChangePlan();
        CompiledQuery compiled = assertCompiles(candidate);
        assertEquals(CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertEquals(BankResultProjector.ProjectionType.RANK_CHANGE_LONG_FORM,
                compiled.getResultContract().getType());
        assertEquals(List.of("metric_code", "bank_organization", "baseline_rank", "current_rank",
                "rank_change"), compiled.getOutputColumns());
        String sql = compiled.getS2sql();
        assertTrue(sql.contains("bank_current_values_0 AS"));
        assertTrue(sql.contains("bank_baseline_values_0 AS"));
        assertTrue(sql.contains("bank_current_values_1 AS"));
        // ZB001 and ZB002 are both higher-is-better, so both windows rank DESC.
        assertTrue(sql.contains("ROW_NUMBER() OVER (ORDER BY metric_value DESC, bank_organization ASC) AS current_rank"));
        assertTrue(sql.contains("ROW_NUMBER() OVER (ORDER BY metric_value DESC, bank_organization ASC) AS baseline_rank"));
        assertTrue(sql.contains("INNER JOIN"));
        assertTrue(sql.contains("baseline_rank - bank_current_rank_0.current_rank AS rank_change"));
        assertTrue(sql.contains("UNION ALL"));
        assertTrue(sql.contains("ORDER BY metric_code ASC, bank_organization ASC"));

        List<Map<String, Object>> sourceRows = new java.util.ArrayList<>(List.of(
                Map.of("metric_code", "ZB001", "bank_organization", "ORG002",
                        "baseline_rank", 1L, "current_rank", 2L, "rank_change", -1L),
                Map.of("metric_code", "ZB001", "bank_organization", "ORG001",
                        "baseline_rank", 2L, "current_rank", 1L, "rank_change", 1L),
                Map.of("metric_code", "ZB002", "bank_organization", "ORG001",
                        "baseline_rank", 3L, "current_rank", 3L, "rank_change", 0L),
                Map.of("metric_code", "ZB002", "bank_organization", "ORG002",
                        "baseline_rank", 2L, "current_rank", 1L, "rank_change", 1L)));
        BankResultProjector.Projection projection = new BankResultProjector()
                .project(compiled.getResultContract(), sourceRows);
        assertTrue(projection.isApplied());
        assertEquals(List.of("metric_code", "org_code", "org_name", "baseline_rank",
                "current_rank", "rank_change"), projection.getColumns());
        assertEquals(4, projection.getRows().size());
        assertEquals(List.of("ZB001", "ORG001", 2L, 1L, 1L),
                projection.getRows().stream().filter(row -> "ORG001".equals(row.get("org_code"))
                        && "ZB001".equals(row.get("metric_code")))
                        .map(row -> List.of(row.get("metric_code"), row.get("org_code"),
                                row.get("baseline_rank"), row.get("current_rank"),
                                row.get("rank_change"))).findFirst().orElse(List.of()));
    }

    /**
     * Shape contract (test r5 JDBC_GRAMMAR root cause): the semantic field registration drops
     * every field name that also appears as an alias in the S2SQL, so aliasing a semantic
     * dimension ({@code ... AS bank_organization}) silently removes org_code from the physical
     * field set and H2 rejects the executed SQL with column-not-found. The rank-change UNION and
     * the threshold values-vs-average join must therefore carry no dimension alias anywhere.
     */
    @Test
    void familyTemplatesKeepTheOuterQueryASingleUnqualifiedSelect() {
        assertNoDimensionAliasAndSingleUnqualifiedOuterSelect(
                assertCompiles(rankChangePlan()).getS2sql(),
                "bank_rank_change_output",
                "metric_code, bank_organization, baseline_rank, current_rank, rank_change");
        assertNoDimensionAliasAndSingleUnqualifiedOuterSelect(
                assertCompiles(multiMetricProvinceAverageThreshold()).getS2sql(),
                "bank_gap",
                "bank_organization, metric_code, metric_value, provincial_average,\n"
                        + "       gap_value, meets_condition");
    }

    private static void assertNoDimensionAliasAndSingleUnqualifiedOuterSelect(
            String sql, String outputCte, String outerSelectList) {
        assertFalse(sql.matches("(?is).*\\bAS\\s+`?bank_organization`?\\b.*"),
                () -> "semantic dimension must never be aliased in S2SQL:\n" + sql);
        String trimmed = sql.trim();
        assertTrue(trimmed.startsWith("WITH "), () -> sql);
        String marker = ")\nSELECT " + outerSelectList + "\nFROM " + outputCte;
        int outer = trimmed.indexOf(marker);
        assertTrue(outer >= 0,
                () -> "outer single SELECT over " + outputCte + " missing:\n" + sql);
        String tail = trimmed.substring(outer);
        assertFalse(tail.contains("UNION ALL"), () -> sql);
        assertFalse(tail.matches("(?s).*[a-zA-Z_][a-zA-Z_0-9]*\\.[a-zA-Z_][a-zA-Z_0-9]+.*"),
                () -> "outer query must not use qualified references:\n" + tail);
    }

    /** The value CHANGE plan closest to a rank-change question (correct facts, wrong semantics). */
    private PlanAndHints rankChangeNearMissPlan() {
        BankQueryPlan plan = basePlan(BankIntentType.CHANGE, List.of("ZB001", "ZB002"),
                List.of(), List.of("ORG004"),
                time(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 30),
                        BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                        LocalDate.of(2024, 12, 31), LocalDate.of(2024, 12, 31)),
                BankQueryPlan.CalculationType.CHANGE, List.of(), List.of(), null,
                List.of("ZB001", "ZB002"));
        return value(plan, BankIntentType.CHANGE, List.of("ZB001", "ZB002"), List.of("ORG004"));
    }

    /** Synthetic cross-period rank-change plan: two metrics, both period windows, province-wide. */
    private PlanAndHints rankChangePlan() {
        BankQueryPlan plan = basePlan(BankIntentType.CHANGE, List.of("ZB001", "ZB002"),
                List.of("bank_organization"), List.of(),
                time(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31),
                        BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                        LocalDate.of(2024, 12, 31), LocalDate.of(2024, 12, 31)),
                BankQueryPlan.CalculationType.RANK_CHANGE, List.of(), List.of(), null,
                List.of("bank_organization", "ZB001", "ZB002"));
        return value(plan, BankIntentType.CHANGE, List.of("ZB001", "ZB002"), List.of());
    }

    /**
     * Composite-numerator ratio (numerator = ZB003 + ZB004 over denominator ZB001). The plan
     * declares one composite derived metric; the template sums the operands as the numerator and
     * every other part of the ratio template stays unchanged.
     */
    @Test
    void compositeNumeratorRatioCompilesTheSumNumeratorTemplate() {
        PlanAndHints candidate = compositeNumeratorRatio();
        CompiledQuery compiled = assertCompiles(candidate);
        assertEquals(BankResultProjector.ProjectionType.RATIO,
                compiled.getResultContract().getType());
        String sql = compiled.getS2sql();
        assertTrue(sql.contains("(SUM(ZB003) + SUM(ZB004)) AS numerator_value"));
        assertTrue(sql.contains("SUM(ZB001) AS denominator_value"));
        assertTrue(sql.contains("numerator_value * 100.0 / denominator_value"));
    }

    private PlanAndHints compositeNumeratorRatio() {
        BankQueryPlan plan = basePlan(BankIntentType.RATIO, List.of("ZB003", "ZB004", "ZB001"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.RATIO, List.of(), List.of(), null,
                List.of("bank_organization", "ZB003", "ZB004", "ZB001"));
        plan.getCalculation().setBaseline("ZB001");
        plan.setDerivedMetrics(List.of(BankQueryPlan.DerivedMetric.builder()
                .metricCode("DERIVED_SUM_ZB003_AND_ZB004_DIV_ZB001").numerator("ZB003")
                .numeratorOperands(List.of("ZB003", "ZB004")).denominator("ZB001")
                .name("对公与个人存款合计占各项存款比例").build()));
        return value(plan, BankIntentType.RATIO, List.of("ZB003", "ZB004", "ZB001"),
                List.of("ORG004"));
    }

    private CompiledQuery assertCompiles(PlanAndHints candidate) {
        BankQueryPlanValidator.ValidationResult validation =
                validator.validate(candidate.plan(), candidate.hints());
        assertTrue(validation.isValid(), validation.summary());
        CompiledQuery compiled =
                assertDoesNotThrow(() -> compiler.compile(candidate.plan(), candidate.hints(),
                        schema()));
        assertNotNull(compiled.getOutputColumns());
        return compiled;
    }

    private record PlanAndHints(BankQueryPlan plan, SemanticIntentHints hints) {}

    private PlanAndHints changePeriod() {
        BankQueryPlan plan = basePlan(BankIntentType.CHANGE, List.of("ZB001"), List.of(),
                List.of("ORG004"),
                time(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31),
                        BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                        LocalDate.of(2026, 2, 28), LocalDate.of(2026, 2, 28)),
                BankQueryPlan.CalculationType.CHANGE, List.of(), List.of(), null,
                List.of("ZB001"));
        return value(plan, BankIntentType.CHANGE, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints changeMomAndYoy() {
        BankQueryPlan plan = basePlan(BankIntentType.CHANGE, List.of("ZB001"), List.of(),
                List.of("ORG004"),
                time(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 30),
                        BankQueryPlan.TimeComparison.MOM_AND_YOY, null, null),
                BankQueryPlan.CalculationType.CHANGE, List.of(), List.of(), null,
                List.of("ZB001"));
        return value(plan, BankIntentType.CHANGE, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints changeStartOfYear() {
        BankQueryPlan plan = basePlan(BankIntentType.CHANGE, List.of("ZB001"), List.of(),
                List.of("ORG004"),
                time(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 30),
                        BankQueryPlan.TimeComparison.START_OF_YEAR,
                        LocalDate.of(2024, 12, 31), LocalDate.of(2024, 12, 31)),
                BankQueryPlan.CalculationType.CHANGE, List.of(), List.of(), null,
                List.of("ZB001"));
        return value(plan, BankIntentType.CHANGE, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints multiMetricChange() {
        BankQueryPlan plan = basePlan(BankIntentType.CHANGE, List.of("ZB001", "ZB002"),
                List.of(), List.of("ORG004"),
                time(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31),
                        BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                        LocalDate.of(2026, 2, 28), LocalDate.of(2026, 2, 28)),
                BankQueryPlan.CalculationType.CHANGE, List.of(), List.of(), null,
                List.of("ZB001", "ZB002"));
        return value(plan, BankIntentType.CHANGE, List.of("ZB001", "ZB002"), List.of("ORG004"));
    }

    private PlanAndHints rankedChangeH18() {
        BankQueryPlan plan = basePlan(BankIntentType.CHANGE, List.of("ZB011"),
                List.of("bank_organization"), List.of(),
                time(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 30),
                        BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                        LocalDate.of(2024, 12, 31), LocalDate.of(2024, 12, 31)),
                BankQueryPlan.CalculationType.CHANGE,
                List.of(filter("rank", "LTE", "3")), List.of(), null,
                List.of("bank_organization", "ZB011"));
        return value(plan, BankIntentType.CHANGE, List.of("ZB011"), List.of());
    }

    private PlanAndHints ratio() {
        BankQueryPlan plan = basePlan(BankIntentType.RATIO, List.of("ZB001", "ZB002"),
                List.of(), List.of("ORG004"), dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.RATIO, List.of(), List.of(), null,
                List.of("ZB001", "ZB002"));
        plan.getCalculation().setBaseline("ZB002");
        return value(plan, BankIntentType.RATIO, List.of("ZB001", "ZB002"), List.of("ORG004"));
    }

    private PlanAndHints rankingTop() {
        BankQueryPlan plan = basePlan(BankIntentType.RANKING, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT, List.of(),
                List.of(order("ZB001", BankQueryPlan.SortDirection.DESC)), 3,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.RANKING, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints rankingFromBottom() {
        BankQueryPlan plan = basePlan(BankIntentType.RANKING, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT,
                List.of(filter("rank_from_bottom", "LTE", "3")),
                List.of(order("ZB001", BankQueryPlan.SortDirection.ASC)), 3,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.RANKING, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints provinceAverageThreshold() {
        BankQueryPlan plan = basePlan(BankIntentType.THRESHOLD, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT,
                List.of(filter("benchmark", "COMPARE", "PROVINCE_AVERAGE")), List.of(), null,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.THRESHOLD, List.of("ZB001"), List.of("ORG004"),
                List.of(new SemanticIntentHints.RequiredFilter("benchmark", "COMPARE",
                        "PROVINCE_AVERAGE")));
    }

    private PlanAndHints multiMetricProvinceAverageAggregation() {
        BankQueryPlan plan = basePlan(BankIntentType.AGGREGATION, List.of("ZB001", "ZB002"),
                List.of("bank_organization"), List.of("ORG004"),
                time(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                        BankQueryPlan.TimeComparison.NONE, null, null),
                BankQueryPlan.CalculationType.DIRECT,
                List.of(filter("benchmark", "COMPARE", "PROVINCE_AVERAGE")), List.of(), null,
                List.of("bank_organization", "ZB001", "ZB002"));
        SemanticIntentHints hints = value(plan, BankIntentType.AGGREGATION,
                List.of("ZB001", "ZB002"), List.of("ORG004"),
                List.of(new SemanticIntentHints.RequiredFilter("benchmark", "COMPARE",
                        "PROVINCE_AVERAGE"))).hints();
        return new PlanAndHints(plan, hints);
    }

    /** Synthetic two-metric threshold against the province average, province-wide. */
    private PlanAndHints multiMetricProvinceAverageThreshold() {
        return multiMetricProvinceAverageThreshold("ZB001", "ZB002");
    }

    private PlanAndHints multiMetricProvinceAverageThreshold(String... metricCodes) {
        List<String> metrics = List.of(metricCodes);
        List<String> outputColumns = new java.util.ArrayList<>();
        outputColumns.add("bank_organization");
        outputColumns.addAll(metrics);
        BankQueryPlan plan = basePlan(BankIntentType.THRESHOLD, metrics,
                List.of("bank_organization"), List.of(),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT,
                List.of(filter("benchmark", "COMPARE", "PROVINCE_AVERAGE")), List.of(), null,
                outputColumns);
        SemanticIntentHints hints = value(plan, BankIntentType.THRESHOLD,
                metrics, List.of(),
                List.of(new SemanticIntentHints.RequiredFilter("benchmark", "COMPARE",
                        "PROVINCE_AVERAGE"))).hints();
        return new PlanAndHints(plan, hints);
    }

    /** Synthetic two-metric comparison against the province average, single organization. */
    private PlanAndHints multiMetricProvinceAverageComparison() {
        BankQueryPlan plan = basePlan(BankIntentType.COMPARISON, List.of("ZB001", "ZB002"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT,
                List.of(filter("benchmark", "COMPARE", "PROVINCE_AVERAGE")), List.of(), null,
                List.of("bank_organization", "ZB001", "ZB002"));
        SemanticIntentHints hints = value(plan, BankIntentType.COMPARISON,
                List.of("ZB001", "ZB002"), List.of("ORG004"),
                List.of(new SemanticIntentHints.RequiredFilter("benchmark", "COMPARE",
                        "PROVINCE_AVERAGE"))).hints();
        return new PlanAndHints(plan, hints);
    }

    private PlanAndHints absoluteThreshold() {
        BankQueryPlan plan = basePlan(BankIntentType.THRESHOLD, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT,
                List.of(filter("metric_value", "GT", "100")), List.of(), null,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.THRESHOLD, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints dailyAggregationSummary() {
        BankQueryPlan plan = basePlan(BankIntentType.AGGREGATION, List.of(),
                List.of("bank_organization"), List.of("ORG004"),
                time(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                        BankQueryPlan.TimeComparison.NONE, null, null),
                BankQueryPlan.CalculationType.DIRECT, List.of(), List.of(), null,
                List.of("bank_organization", "ZB001"));
        plan.setMetrics(List.of(avgMetric("ZB001")));
        return value(plan, BankIntentType.AGGREGATION, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints daysAboveProvinceAverage() {
        BankQueryPlan plan = basePlan(BankIntentType.AGGREGATION, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004"),
                time(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                        BankQueryPlan.TimeComparison.NONE, null, null),
                BankQueryPlan.CalculationType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE,
                List.of(filter("benchmark", "COMPARE", "PROVINCE_AVERAGE")), List.of(), null,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.AGGREGATION, List.of("ZB001"), List.of("ORG004"),
                List.of(new SemanticIntentHints.RequiredFilter("benchmark", "COMPARE",
                        "PROVINCE_AVERAGE")));
    }

    private PlanAndHints derivedRanking() {
        BankQueryPlan plan = basePlan(BankIntentType.RANKING, List.of("ZB001", "ZB002"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT, List.of(), List.of(), null,
                List.of("bank_organization", "ZB001", "ZB002"));
        plan.setDerivedMetrics(List.of(BankQueryPlan.DerivedMetric.builder()
                .metricCode("DERIVED_ZB002_DIV_ZB001").numerator("ZB002").denominator("ZB001")
                .name("存贷比").build()));
        return value(plan, BankIntentType.RANKING, List.of("ZB001", "ZB002"), List.of("ORG004"),
                List.of(), List.of(new SemanticIntentHints.DerivedMetricSpec(
                        "DERIVED_ZB002_DIV_ZB001", "ZB002", "ZB001", "存贷比")));
    }

    /** Synthetic five-metric point ranking with top-3/bottom-3 rank slices, province-wide. */
    private PlanAndHints multiMetricRankingWithRankSlice() {
        BankQueryPlan plan = basePlan(BankIntentType.RANKING,
                List.of("ZB001", "ZB002", "ZB003", "ZB004", "ZB011"),
                List.of("bank_organization"), List.of(),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT,
                List.of(filter("rank", "LTE", "3"),
                        filter("rank_from_bottom", "LTE", "3")),
                List.of(order("ZB001", BankQueryPlan.SortDirection.DESC)), 6,
                List.of("bank_organization", "ZB001", "ZB002", "ZB003", "ZB004", "ZB011"));
        return value(plan, BankIntentType.RANKING,
                List.of("ZB001", "ZB002", "ZB003", "ZB004", "ZB011"), List.of(),
                List.of(new SemanticIntentHints.RequiredFilter("rank", "LTE", "3"),
                        new SemanticIntentHints.RequiredFilter("rank_from_bottom", "LTE", "3")));
    }

    private PlanAndHints structureShare() {
        BankQueryPlan plan = basePlan(BankIntentType.POINT_QUERY,
                List.of("ZB001", "ZB003", "ZB004"), List.of("bank_organization"),
                List.of("ORG004"), dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT, List.of(), List.of(), null,
                List.of("bank_organization", "ZB001", "ZB003", "ZB004"));
        plan.getOutput().setOrderSensitive(true);
        return value(plan, BankIntentType.POINT_QUERY, List.of("ZB001", "ZB003", "ZB004"),
                List.of("ORG004"));
    }

    private PlanAndHints trend() {
        BankQueryPlan plan = basePlan(BankIntentType.TREND, List.of("ZB001"),
                List.of("bank_data_date"), List.of("ORG004"),
                time(LocalDate.of(2025, 3, 31), LocalDate.of(2026, 3, 31),
                        BankQueryPlan.TimeComparison.NONE, null, null),
                BankQueryPlan.CalculationType.DIRECT, List.of(), List.of(), null,
                List.of("bank_data_date", "ZB001"));
        plan.getTime().setGranularity(BankQueryPlan.TimeGranularity.QUARTER);
        return value(plan, BankIntentType.TREND, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints organizationComparison() {
        BankQueryPlan plan = basePlan(BankIntentType.COMPARISON, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004", "ORG005"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT, List.of(),
                List.of(order("ZB001", BankQueryPlan.SortDirection.ASC)), null,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.COMPARISON, List.of("ZB001"),
                List.of("ORG004", "ORG005"));
    }

    /** Undeclared shape: a ranking intent executed through the CHANGE calculation family. */
    private PlanAndHints rankingIntentChange() {
        BankQueryPlan plan = basePlan(BankIntentType.RANKING, List.of("ZB011"),
                List.of("bank_organization"), List.of(),
                time(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 30),
                        BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                        LocalDate.of(2024, 12, 31), LocalDate.of(2024, 12, 31)),
                BankQueryPlan.CalculationType.CHANGE, List.of(),
                List.of(order("ZB011", BankQueryPlan.SortDirection.DESC)), 3,
                List.of("bank_organization", "ZB011"));
        return value(plan, BankIntentType.RANKING, List.of("ZB011"), List.of());
    }

    private BankQueryPlan basePlan(BankIntentType intent, List<String> metrics,
            List<String> dimensions, List<String> organizations, BankQueryPlan.TimeRange time,
            BankQueryPlan.CalculationType calculation, List<BankQueryPlan.Filter> filters,
            List<BankQueryPlan.OrderBy> orderBy, Integer limit, List<String> output) {
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .action(BankQueryPlan.PlanAction.EXECUTE).intent(intent)
                .metrics(metrics.stream().map(this::metric).toList()).dimensions(dimensions)
                .organizations(organizations.stream().map(this::organization).toList()).time(time)
                .filters(filters).calculation(BankQueryPlan.Calculation.builder().type(calculation)
                        .build()).orderBy(orderBy).limit(limit)
                .output(BankQueryPlan.Output.builder().columns(output).build()).build();
    }

    private PlanAndHints value(BankQueryPlan plan, BankIntentType intent,
            List<String> requiredMetrics, List<String> requiredOrganizations) {
        return value(plan, intent, requiredMetrics, requiredOrganizations, List.of(), List.of());
    }

    private PlanAndHints value(BankQueryPlan plan, BankIntentType intent,
            List<String> requiredMetrics, List<String> requiredOrganizations,
            List<SemanticIntentHints.RequiredFilter> requiredFilters) {
        return value(plan, intent, requiredMetrics, requiredOrganizations, requiredFilters,
                List.of());
    }

    private PlanAndHints value(BankQueryPlan plan, BankIntentType intent,
            List<String> requiredMetrics, List<String> requiredOrganizations,
            List<SemanticIntentHints.RequiredFilter> requiredFilters,
            List<SemanticIntentHints.DerivedMetricSpec> requiredDerivedMetrics) {
        BankQueryPlan.TimeRange time = plan.getTime();
        SemanticIntentHints hints = SemanticIntentHints.builder().expectedIntent(intent)
                .allowedMetrics(Set.of("ZB001", "ZB002", "ZB003", "ZB004", "ZB011", "ZB013",
                        "ZB015"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.copyOf(requiredMetrics))
                .requiredOrganizationCodes(Set.copyOf(requiredOrganizations))
                .requiredDerivedMetrics(requiredDerivedMetrics)
                .requiredStartDate(time.getStartDate()).requiredEndDate(time.getEndDate())
                .requiredTimeComparison(time.getComparison())
                .requiredBaselineStartDate(time.getBaselineStartDate())
                .requiredBaselineEndDate(time.getBaselineEndDate()).requiredFilters(requiredFilters)
                .maxLimit(100).build();
        return new PlanAndHints(plan, hints);
    }

    private BankQueryPlan.TimeRange dayTime(BankQueryPlan.TimeComparison comparison) {
        return time(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31), comparison, null,
                null);
    }

    private BankQueryPlan.TimeRange time(LocalDate start, LocalDate end,
            BankQueryPlan.TimeComparison comparison, LocalDate baselineStart,
            LocalDate baselineEnd) {
        return BankQueryPlan.TimeRange.builder().startDate(start).endDate(end)
                .granularity(BankQueryPlan.TimeGranularity.DAY).comparison(comparison)
                .baselineStartDate(baselineStart).baselineEndDate(baselineEnd).build();
    }

    private BankQueryPlan.Metric metric(String code) {
        return BankQueryPlan.Metric.builder().bizName(code)
                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build();
    }

    private BankQueryPlan.Metric avgMetric(String code) {
        return BankQueryPlan.Metric.builder().bizName(code)
                .aggregation(BankQueryPlan.Aggregation.AVG).build();
    }

    private BankQueryPlan.Organization organization(String code) {
        return BankQueryPlan.Organization.builder().code(code).build();
    }

    private BankQueryPlan.Filter filter(String field, String operator, String value) {
        return BankQueryPlan.Filter.builder().field(field).operator(operator).value(value)
                .build();
    }

    private BankQueryPlan.OrderBy order(String field, BankQueryPlan.SortDirection direction) {
        return BankQueryPlan.OrderBy.builder().field(field).direction(direction).build();
    }

    private LLMReq.LLMSchema schema() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(12L);
        schema.setDataSetName("银行指标数据集");
        schema.setMetrics(List.of(
                schemaMetric("各项存款余额", "ZB001"), schemaMetric("各项贷款余额", "ZB002"),
                schemaMetric("对公存款余额", "ZB003"), schemaMetric("个人存款余额", "ZB004"),
                schemaMetric("净利润", "ZB011"), schemaMetric("不良贷款率", "ZB013"),
                schemaMetric("拨备覆盖率", "ZB015")));
        schema.setDimensions(List.of(
                SchemaElement.builder().name("机构").bizName("bank_organization").build()));
        schema.setPartitionTime(
                SchemaElement.builder().name("数据日期").bizName("bank_data_date").build());
        return schema;
    }

    private SchemaElement schemaMetric(String name, String code) {
        return SchemaElement.builder().name(name).bizName(code).defaultAgg("SUM").build();
    }
}
