package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankQueryPlanValidatorTest {

    private final BankQueryPlanValidator validator = new BankQueryPlanValidator();

    @Test
    void acceptsAnExecutionPlanThatExactlyCoversTheModelRequirementsContract() {
        assertTrue(validator.validate(validPlan(), requirements()).isValid());
    }

    @Test
    void rejectsAMissingMetricWithThePreciseRepairSignal() {
        BankQueryPlan plan = validPlan();
        plan.setMetrics(List.of(plan.getMetrics().get(0)));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, requirements());

        assertFalse(result.isValid());
        assertTrue(result.summary().contains("required_metrics_missing: ZB002"));
    }

    @Test
    void rejectsAnExtraMetricInsteadOfSilentlyAddingItToTheQuestionMeaning() {
        BankQueryPlan plan = validPlan();
        List<BankQueryPlan.Metric> metrics = new ArrayList<>(plan.getMetrics());
        metrics.add(BankQueryPlan.Metric.builder().bizName("ZB003")
                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build());
        plan.setMetrics(metrics);
        plan.getOutput().setColumns(List.of("bank_organization", "ZB001", "ZB002", "ZB003"));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, requirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("UNEXPECTED_METRIC"));
    }

    @Test
    void rejectsLowercaseMetricCodesInsteadOfCanonicalizingThem() {
        BankQueryPlan plan = validPlan();
        plan.getMetrics().get(0).setBizName("zb001");

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, requirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("UNKNOWN_METRIC"));
        assertTrue(result.summary().contains("required_metrics_missing: ZB001"));
    }

    @Test
    void rejectsIntentTimeOrganizationAndFilterDriftFromTheRequirementsContract() {
        BankQueryPlan plan = validPlan();
        plan.setIntent(BankIntentType.POINT_QUERY);
        plan.getTime().setEndDate(LocalDate.of(2025, 8, 31));
        plan.setOrganizations(List.of(BankQueryPlan.Organization.builder().code("ORG003").build()));
        plan.setFilters(List.of());

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, requirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("INTENT_MISMATCH"));
        assertTrue(result.codes().contains("TIME_RANGE_MISMATCH"));
        assertTrue(result.summary().contains("required_organizations_missing: ORG004"));
        assertTrue(result.codes().contains("MISSING_REQUIRED_FILTER"));
    }

    @Test
    void rejectsProvinceAverageOutsideTheExactBenchmarkPlanContract() {
        BankQueryPlan plan = validPlan();
        plan.setFilters(List.of(BankQueryPlan.Filter.builder().field("metric_value")
                .operator("COMPARE").value("PROVINCE_AVERAGE").values(new ArrayList<>()).build()));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, requirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED"));
    }

    @Test
    void rejectsAnyPlanWithoutTheExplicitExecuteAction() {
        BankQueryPlan plan = validPlan();
        plan.setAction(null);

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, requirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("PLAN_ACTION_REQUIRED"));
    }

    @Test
    void rejectsDateGroupingForAChangePlanInsteadOfCompilingSeparateCurrentAndBaselineRows() {
        BankQueryPlan plan = BankQueryPlan.builder().version("1.0")
                .action(BankQueryPlan.PlanAction.EXECUTE).intent(BankIntentType.CHANGE)
                .metrics(new ArrayList<>(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build())))
                .derivedMetrics(new ArrayList<>())
                .dimensions(new ArrayList<>(List.of("bank_data_date")))
                .organizations(new ArrayList<>(
                        List.of(BankQueryPlan.Organization.builder().code("ORG004").build())))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 3, 31))
                        .endDate(LocalDate.of(2025, 3, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD)
                        .baselineStartDate(LocalDate.of(2024, 12, 31))
                        .baselineEndDate(LocalDate.of(2024, 12, 31)).build())
                .filters(new ArrayList<>())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.CHANGE).build())
                .orderBy(new ArrayList<>()).limit(null)
                .output(BankQueryPlan.Output.builder()
                        .columns(new ArrayList<>(List.of("bank_data_date", "ZB001")))
                        .orderSensitive(false).build())
                .build();
        SemanticIntentHints changeRequirements = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.CHANGE).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2025, 3, 31))
                .requiredEndDate(LocalDate.of(2025, 3, 31)).build();

        BankQueryPlanValidator.ValidationResult result =
                validator.validate(plan, changeRequirements);

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("CHANGE_DATE_DIMENSION_FORBIDDEN"));
    }

    @Test
    void rejectsCompilerOwnedOrderingAndMissingOrganizationDimensionForProvinceChangeTopN() {
        BankQueryPlan plan = provinceWideChangeTopNPlan();
        plan.setOrderBy(List.of(BankQueryPlan.OrderBy.builder().field("percent_change")
                .direction(BankQueryPlan.SortDirection.DESC).build()));

        BankQueryPlanValidator.ValidationResult ordered =
                validator.validate(plan, provinceWideChangeTopNRequirements());

        assertFalse(ordered.isValid());
        assertTrue(ordered.codes().contains("CHANGE_RESULT_ORDER_FORBIDDEN"));
        assertTrue(ordered.codes().contains("CHANGE_TOPN_ORGANIZATION_DIMENSION_REQUIRED"));

        plan.setOrderBy(List.of());
        plan.setDimensions(List.of("bank_organization"));
        plan.getOutput().setColumns(List.of("bank_organization", "ZB001"));

        assertTrue(validator.validate(plan, provinceWideChangeTopNRequirements()).isValid());
    }

    @Test
    void allowsDerivedMetricRankingToUseTheCompilerOwnedPerMetricOrder() {
        BankQueryPlan plan = derivedMetricRankingPlan();

        assertTrue(validator.validate(plan, derivedMetricRankingRequirements()).isValid());
    }

    @Test
    void rejectsCurrentYearFirstDayAsTheStartOfYearPlanBaseline() {
        BankQueryPlan plan = provinceWideChangeTopNPlan();
        plan.setDimensions(List.of("bank_organization"));
        plan.getOutput().setColumns(List.of("bank_organization", "ZB001"));
        plan.getTime().setComparison(BankQueryPlan.TimeComparison.START_OF_YEAR);
        plan.getTime().setBaselineStartDate(LocalDate.of(2026, 1, 1));
        plan.getTime().setBaselineEndDate(LocalDate.of(2026, 1, 1));

        BankQueryPlanValidator.ValidationResult invalid =
                validator.validate(plan, provinceWideChangeTopNRequirements());

        assertFalse(invalid.isValid());
        assertTrue(invalid.codes().contains("START_OF_YEAR_BASELINE_INVALID"));

        plan.getTime().setBaselineStartDate(LocalDate.of(2025, 12, 31));
        plan.getTime().setBaselineEndDate(LocalDate.of(2025, 12, 31));

        assertTrue(validator.validate(plan, provinceWideChangeTopNRequirements()).isValid());
    }

    @Test
    void rejectsDirectCalculationWhenPlanDeclaresATimeComparison() {
        BankQueryPlan plan = provinceWideChangeTopNPlan();
        plan.getTime().setComparison(BankQueryPlan.TimeComparison.START_OF_YEAR);
        plan.getTime().setBaselineStartDate(LocalDate.of(2025, 12, 31));
        plan.getTime().setBaselineEndDate(LocalDate.of(2025, 12, 31));
        plan.getCalculation().setType(BankQueryPlan.CalculationType.DIRECT);

        BankQueryPlanValidator.ValidationResult result =
                validator.validate(plan, provinceWideChangeTopNRequirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("COMPARISON_CALCULATION_REQUIRED"));
    }

    @Test
    void rejectsAPlanThatChangesTheModelOwnedComparisonContract() {
        BankQueryPlan plan = provinceWideChangeTopNPlan();
        plan.getTime().setComparison(BankQueryPlan.TimeComparison.START_OF_YEAR);
        plan.getTime().setBaselineStartDate(LocalDate.of(2025, 12, 31));
        plan.getTime().setBaselineEndDate(LocalDate.of(2025, 12, 31));
        SemanticIntentHints requirements = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.CHANGE).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of())
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31))
                .requiredTimeComparison(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD)
                .requiredBaselineStartDate(LocalDate.of(2024, 12, 31))
                .requiredBaselineEndDate(LocalDate.of(2024, 12, 31)).build();

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, requirements);

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("TIME_COMPARISON_MISMATCH"));
        assertTrue(result.codes().contains("COMPARISON_BASELINE_MISMATCH"));
    }

    @Test
    void rejectsAnAbsoluteThresholdWithoutTheCompilerRequiredOrganizationDimension() {
        BankQueryPlan plan = absoluteThresholdPlan();
        plan.setDimensions(List.of());
        plan.getOutput().setColumns(List.of("ZB015"));

        BankQueryPlanValidator.ValidationResult result =
                validator.validate(plan, absoluteThresholdRequirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("ABSOLUTE_THRESHOLD_ORGANIZATION_DIMENSION_REQUIRED"));
    }

    @Test
    void acceptsTheExactAbsoluteThresholdCompilerContract() {
        assertTrue(validator.validate(absoluteThresholdPlan(), absoluteThresholdRequirements())
                .isValid());
    }

    @Test
    void rejectsMalformedBottomRankingSliceBeforeTheCompiler() {
        BankQueryPlan plan = derivedMetricRankingPlan();
        plan.setDerivedMetrics(new ArrayList<>());
        plan.setMetrics(new ArrayList<>(List.of(BankQueryPlan.Metric.builder()
                .bizName("ZB011").aggregation(BankQueryPlan.Aggregation.DEFAULT).build())));
        plan.setOrganizations(new ArrayList<>());
        plan.setTime(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 8, 31))
                .endDate(LocalDate.of(2025, 8, 31))
                .granularity(BankQueryPlan.TimeGranularity.DAY)
                .comparison(BankQueryPlan.TimeComparison.NONE).build());
        plan.setFilters(new ArrayList<>(List.of(BankQueryPlan.Filter.builder()
                .field("rank_from_bottom").operator("GTE").value("1")
                .values(new ArrayList<>()).build())));
        plan.setOrderBy(new ArrayList<>(List.of(BankQueryPlan.OrderBy.builder().field("ZB011")
                .direction(BankQueryPlan.SortDirection.DESC).build())));
        plan.setLimit(1);
        plan.getOutput().setColumns(new ArrayList<>(List.of("bank_organization", "ZB011")));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan,
                bottomRankingRequirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("RANK_FILTER_CONTRACT_INVALID"));
        assertTrue(result.summary().contains("operator=LTE"));
        assertTrue(result.summary().contains("positive integer value"));
        assertTrue(result.summary().contains("values=[]"));
    }

    @Test
    void acceptsTheExactBottomRankingSliceContract() {
        BankQueryPlan plan = derivedMetricRankingPlan();
        plan.setDerivedMetrics(new ArrayList<>());
        plan.setMetrics(new ArrayList<>(List.of(BankQueryPlan.Metric.builder()
                .bizName("ZB011").aggregation(BankQueryPlan.Aggregation.DEFAULT).build())));
        plan.setOrganizations(new ArrayList<>());
        plan.setTime(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 8, 31))
                .endDate(LocalDate.of(2025, 8, 31))
                .granularity(BankQueryPlan.TimeGranularity.DAY)
                .comparison(BankQueryPlan.TimeComparison.NONE).build());
        plan.setFilters(new ArrayList<>(List.of(BankQueryPlan.Filter.builder()
                .field("rank_from_bottom").operator("LTE").value("1")
                .values(new ArrayList<>()).build())));
        plan.setOrderBy(new ArrayList<>(List.of(BankQueryPlan.OrderBy.builder().field("ZB011")
                .direction(BankQueryPlan.SortDirection.DESC).build())));
        plan.setLimit(1);
        plan.getOutput().setColumns(new ArrayList<>(List.of("bank_organization", "ZB011")));

        assertTrue(validator.validate(plan, bottomRankingRequirements()).isValid());
    }

    private SemanticIntentHints requirements() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.COMPARISON)
                .allowedMetrics(Set.of("ZB001", "ZB002", "ZB003"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001", "ZB002"))
                .requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2025, 7, 31))
                .requiredEndDate(LocalDate.of(2025, 7, 31))
                .requiredFilters(List.of(new SemanticIntentHints.RequiredFilter("benchmark",
                        "COMPARE", "PROVINCE_AVERAGE")))
                .build();
    }

    private SemanticIntentHints absoluteThresholdRequirements() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.THRESHOLD)
                .allowedMetrics(Set.of("ZB015"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB015")).requiredOrganizationCodes(Set.of("ORG002"))
                .requiredStartDate(LocalDate.of(2025, 12, 31))
                .requiredEndDate(LocalDate.of(2025, 12, 31))
                .requiredFilters(List
                        .of(new SemanticIntentHints.RequiredFilter("metric_value", "GT", "150%")))
                .build();
    }

    private SemanticIntentHints bottomRankingRequirements() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB011"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB011")).requiredOrganizationCodes(Set.of())
                .requiredStartDate(LocalDate.of(2025, 8, 31))
                .requiredEndDate(LocalDate.of(2025, 8, 31)).requiredLimit(1).maxLimit(100)
                .build();
    }

    private BankQueryPlan absoluteThresholdPlan() {
        return BankQueryPlan.builder().version("1.0").action(BankQueryPlan.PlanAction.EXECUTE)
                .intent(BankIntentType.THRESHOLD)
                .metrics(new ArrayList<>(List.of(BankQueryPlan.Metric.builder().bizName("ZB015")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build())))
                .derivedMetrics(new ArrayList<>())
                .dimensions(new ArrayList<>(List.of("bank_organization")))
                .organizations(new ArrayList<>(
                        List.of(BankQueryPlan.Organization.builder().code("ORG002").build())))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 12, 31))
                        .endDate(LocalDate.of(2025, 12, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .filters(
                        new ArrayList<>(List.of(BankQueryPlan.Filter.builder().field("metric_value")
                                .operator("GT").value("150%").values(new ArrayList<>()).build())))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(new ArrayList<>()).limit(null)
                .output(BankQueryPlan.Output.builder()
                        .columns(new ArrayList<>(List.of("bank_organization", "ZB015")))
                        .orderSensitive(false).build())
                .build();
    }

    private SemanticIntentHints provinceWideChangeTopNRequirements() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.CHANGE)
                .allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of())
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(3).build();
    }

    private BankQueryPlan provinceWideChangeTopNPlan() {
        return BankQueryPlan.builder().version("1.0").action(BankQueryPlan.PlanAction.EXECUTE)
                .intent(BankIntentType.CHANGE)
                .metrics(new ArrayList<>(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build())))
                .derivedMetrics(new ArrayList<>()).dimensions(new ArrayList<>())
                .organizations(new ArrayList<>())
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2026, 3, 31))
                        .endDate(LocalDate.of(2026, 3, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD)
                        .baselineStartDate(LocalDate.of(2024, 12, 31))
                        .baselineEndDate(LocalDate.of(2024, 12, 31)).build())
                .filters(new ArrayList<>())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.CHANGE).build())
                .orderBy(new ArrayList<>()).limit(3).output(BankQueryPlan.Output.builder()
                        .columns(new ArrayList<>(List.of("ZB001"))).orderSensitive(false).build())
                .build();
    }

    private SemanticIntentHints derivedMetricRankingRequirements() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001", "ZB002"))
                .requiredOrganizationCodes(Set.of("ORG004"))
                .requiredDerivedMetrics(List.of(new SemanticIntentHints.DerivedMetricSpec(
                        "DERIVED_ZB002_DIV_ZB001", "ZB002", "ZB001", "存贷比")))
                .requiredStartDate(LocalDate.of(2025, 7, 31))
                .requiredEndDate(LocalDate.of(2025, 7, 31)).build();
    }

    private BankQueryPlan derivedMetricRankingPlan() {
        return BankQueryPlan.builder().version("1.0").action(BankQueryPlan.PlanAction.EXECUTE)
                .intent(BankIntentType.RANKING)
                .metrics(new ArrayList<>(List.of(
                        BankQueryPlan.Metric.builder().bizName("ZB001")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build(),
                        BankQueryPlan.Metric.builder().bizName("ZB002")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build())))
                .derivedMetrics(new ArrayList<>(List.of(
                        BankQueryPlan.DerivedMetric.builder().metricCode("DERIVED_ZB002_DIV_ZB001")
                                .numerator("ZB002").denominator("ZB001").name("存贷比").build())))
                .dimensions(new ArrayList<>(List.of("bank_organization")))
                .organizations(new ArrayList<>(
                        List.of(BankQueryPlan.Organization.builder().code("ORG004").build())))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 7, 31))
                        .endDate(LocalDate.of(2025, 7, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .filters(new ArrayList<>(List.of(
                        BankQueryPlan.Filter.builder().field("rank").operator("LTE").value("3")
                                .values(new ArrayList<>()).build(),
                        BankQueryPlan.Filter.builder().field("rank_from_bottom").operator("LTE")
                                .value("4").values(new ArrayList<>()).build())))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(new ArrayList<>()).limit(7)
                .output(BankQueryPlan.Output.builder()
                        .columns(new ArrayList<>(List.of("bank_organization", "ZB001", "ZB002")))
                        .orderSensitive(false).build())
                .build();
    }

    private BankQueryPlan validPlan() {
        return BankQueryPlan.builder().version("1.0").action(BankQueryPlan.PlanAction.EXECUTE)
                .intent(BankIntentType.COMPARISON)
                .metrics(new ArrayList<>(List.of(
                        BankQueryPlan.Metric.builder().bizName("ZB001")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build(),
                        BankQueryPlan.Metric.builder().bizName("ZB002")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build())))
                .derivedMetrics(new ArrayList<>())
                .dimensions(new ArrayList<>(List.of("bank_organization")))
                .organizations(new ArrayList<>(
                        List.of(BankQueryPlan.Organization.builder().code("ORG004").build())))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 7, 31))
                        .endDate(LocalDate.of(2025, 7, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .filters(new ArrayList<>(List
                        .of(BankQueryPlan.Filter.builder().field("benchmark").operator("COMPARE")
                                .value("PROVINCE_AVERAGE").values(new ArrayList<>()).build())))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(new ArrayList<>()).limit(null)
                .output(BankQueryPlan.Output.builder()
                        .columns(new ArrayList<>(List.of("bank_organization", "ZB001", "ZB002")))
                        .orderSensitive(false).build())
                .build();
    }
}
