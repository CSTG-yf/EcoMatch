package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable satisfiability matrix for the model-owned bank plan contract.
 *
 * <p>The matrix deliberately contains legal combinations only.  A family which has a compiler
 * owned comparison form (for example MOM_AND_YOY) is represented by its legal CHANGE row rather
 * than by manufacturing an invalid cross product.  This keeps the test useful as an admission
 * contract: every advertised legal shape must pass both validation and compilation.</p>
 */
class BankQueryPlanContractSatisfiabilityTest {

    private final BankQueryPlanValidator validator = new BankQueryPlanValidator();
    private final BankQueryPlanCompiler compiler = new BankQueryPlanCompiler();

    @TestFactory
    Stream<DynamicTest> everyLegalQueryFamilyAndComparisonCompiles() {
        return legalMatrix().stream().map(candidate -> DynamicTest.dynamicTest(
                candidate.name() + " [intent=" + candidate.intent() + ", comparison="
                        + candidate.comparison() + "]", () -> {
                    BankQueryPlan plan = candidate.plan();
                    SemanticIntentHints hints = candidate.hints();
                    BankQueryPlanValidator.ValidationResult validation =
                            validator.validate(plan, hints);
                    assertTrue(validation.isValid(), candidate.name() + " rejected: "
                            + validation.summary());
                    BankQueryPlanCompiler.CompiledQuery compiled = assertDoesNotThrow(
                            () -> compiler.compile(plan, hints, candidate.schema()),
                            candidate.name() + " must be compiler-satisfiable");
                    assertNotNull(compiled.getOutputColumns(), candidate.name()
                            + " must expose a deterministic output contract");
                }));
    }

    /** H-18 is the ranked-change/rank-filter shape that previously had no executable contract. */
    @org.junit.jupiter.api.Test
    void matrixIncludesH18RankedChangeWithAdvisoryRankFilter() {
        Candidate candidate = legalMatrix().stream()
                .filter(item -> item.name().equals("ranking-change-h18"))
                .findFirst().orElseThrow();

        assertTrue(validator.validate(candidate.plan(), candidate.hints()).isValid());
        assertDoesNotThrow(() -> compiler.compile(candidate.plan(), candidate.hints(),
                candidate.schema()));
    }

    private List<Candidate> legalMatrix() {
        return List.of(
                candidate("aggregation-none", BankIntentType.AGGREGATION,
                        BankQueryPlan.TimeComparison.NONE, this::aggregationNone),
                candidate("ratio-none", BankIntentType.RATIO, BankQueryPlan.TimeComparison.NONE,
                        this::ratioNone),
                candidate("change-period-over-period", BankIntentType.CHANGE,
                        BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD, this::changePeriod),
                candidate("change-mom-and-yoy", BankIntentType.CHANGE,
                        BankQueryPlan.TimeComparison.MOM_AND_YOY, this::changeMomAndYoy),
                candidate("change-start-of-year", BankIntentType.CHANGE,
                        BankQueryPlan.TimeComparison.START_OF_YEAR, this::changeStartOfYear),
                candidate("ranking-none", BankIntentType.RANKING,
                        BankQueryPlan.TimeComparison.NONE, this::rankingNone),
                candidate("ranking-change-h18", BankIntentType.CHANGE,
                        BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD, this::rankingChangeH18),
                candidate("trend-none", BankIntentType.TREND, BankQueryPlan.TimeComparison.NONE,
                        this::trendNone),
                candidate("threshold-none", BankIntentType.THRESHOLD,
                        BankQueryPlan.TimeComparison.NONE, this::thresholdNone),
                candidate("comparison-none", BankIntentType.COMPARISON,
                        BankQueryPlan.TimeComparison.NONE, this::comparisonNone),
                candidate("province-average-none", BankIntentType.THRESHOLD,
                        BankQueryPlan.TimeComparison.NONE, this::provinceAverageNone),
                candidate("structure-share-none", BankIntentType.POINT_QUERY,
                        BankQueryPlan.TimeComparison.NONE, this::structureShareNone),
                candidate("derived-none", BankIntentType.RANKING,
                        BankQueryPlan.TimeComparison.NONE, this::derivedNone),
                candidate("multi-metric-none", BankIntentType.POINT_QUERY,
                        BankQueryPlan.TimeComparison.NONE, this::multiMetricNone),
                candidate("multi-metric-change", BankIntentType.CHANGE,
                        BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD, this::multiMetricChange));
    }

    private Candidate candidate(String name, BankIntentType intent,
            BankQueryPlan.TimeComparison comparison, Supplier<PlanAndHints> factory) {
        PlanAndHints value = factory.get();
        return new Candidate(name, intent, comparison, value.plan(), value.hints(), schema());
    }

    private PlanAndHints aggregationNone() {
        BankQueryPlan plan = basePlan(BankIntentType.AGGREGATION, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT, List.of(), List.of(), null,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.AGGREGATION, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints ratioNone() {
        BankQueryPlan plan = basePlan(BankIntentType.RATIO, List.of("ZB001", "ZB002"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.RATIO, List.of(), List.of(), null,
                List.of("bank_organization", "ZB001", "ZB002"));
        plan.getCalculation().setBaseline("ZB002");
        return value(plan, BankIntentType.RATIO, List.of("ZB001", "ZB002"), List.of("ORG004"));
    }

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

    private PlanAndHints rankingNone() {
        BankQueryPlan plan = basePlan(BankIntentType.RANKING, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT, List.of(),
                List.of(order("ZB001", BankQueryPlan.SortDirection.DESC)), 3,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.RANKING, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints rankingChangeH18() {
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

    private PlanAndHints trendNone() {
        BankQueryPlan plan = basePlan(BankIntentType.TREND, List.of("ZB001"),
                List.of("bank_data_date"), List.of("ORG004"),
                time(LocalDate.of(2025, 3, 31), LocalDate.of(2026, 3, 31),
                        BankQueryPlan.TimeComparison.NONE, null, null),
                BankQueryPlan.CalculationType.DIRECT, List.of(), List.of(), null,
                List.of("bank_data_date", "ZB001"));
        plan.getTime().setGranularity(BankQueryPlan.TimeGranularity.QUARTER);
        return value(plan, BankIntentType.TREND, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints thresholdNone() {
        BankQueryPlan plan = basePlan(BankIntentType.THRESHOLD, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT,
                List.of(filter("metric_value", "GT", "100")), List.of(), null,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.THRESHOLD, List.of("ZB001"), List.of("ORG004"));
    }

    private PlanAndHints comparisonNone() {
        BankQueryPlan plan = basePlan(BankIntentType.COMPARISON, List.of("ZB001"),
                List.of("bank_organization"), List.of("ORG004", "ORG005"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT, List.of(),
                List.of(order("ZB001", BankQueryPlan.SortDirection.ASC)), null,
                List.of("bank_organization", "ZB001"));
        return value(plan, BankIntentType.COMPARISON, List.of("ZB001"),
                List.of("ORG004", "ORG005"));
    }

    private PlanAndHints provinceAverageNone() {
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

    private PlanAndHints structureShareNone() {
        BankQueryPlan plan = basePlan(BankIntentType.POINT_QUERY,
                List.of("ZB001", "ZB003", "ZB004"), List.of("bank_organization"),
                List.of("ORG004"), dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT, List.of(), List.of(), null,
                List.of("bank_organization", "ZB001", "ZB003", "ZB004"));
        plan.getOutput().setOrderSensitive(true);
        return value(plan, BankIntentType.POINT_QUERY,
                List.of("ZB001", "ZB003", "ZB004"), List.of("ORG004"));
    }

    private PlanAndHints derivedNone() {
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

    private PlanAndHints multiMetricNone() {
        BankQueryPlan plan = basePlan(BankIntentType.POINT_QUERY, List.of("ZB001", "ZB002"),
                List.of("bank_organization"), List.of("ORG004"),
                dayTime(BankQueryPlan.TimeComparison.NONE),
                BankQueryPlan.CalculationType.DIRECT, List.of(), List.of(), null,
                List.of("bank_organization", "ZB001", "ZB002"));
        return value(plan, BankIntentType.POINT_QUERY, List.of("ZB001", "ZB002"),
                List.of("ORG004"));
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

    private BankQueryPlan.Organization organization(String code) {
        return BankQueryPlan.Organization.builder().code(code).build();
    }

    private BankQueryPlan.Filter filter(String field, String operator, String value) {
        return BankQueryPlan.Filter.builder().field(field).operator(operator).value(value).build();
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

    private record PlanAndHints(BankQueryPlan plan, SemanticIntentHints hints) {}

    private record Candidate(String name, BankIntentType intent,
            BankQueryPlan.TimeComparison comparison, BankQueryPlan plan,
            SemanticIntentHints hints, LLMReq.LLMSchema schema) {}
}
