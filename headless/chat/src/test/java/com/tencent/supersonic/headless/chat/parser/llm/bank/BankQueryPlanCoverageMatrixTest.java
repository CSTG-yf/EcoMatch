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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                .allowedMetrics(Set.of("ZB001", "ZB002", "ZB003", "ZB004", "ZB011"))
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
                schemaMetric("净利润", "ZB011")));
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
