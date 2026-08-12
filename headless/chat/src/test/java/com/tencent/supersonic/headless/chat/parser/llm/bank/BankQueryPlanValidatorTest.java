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
                .derivedMetrics(new ArrayList<>()).dimensions(new ArrayList<>(List.of("bank_data_date")))
                .organizations(new ArrayList<>(List.of(BankQueryPlan.Organization.builder()
                        .code("ORG004").build())))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 3, 31))
                        .endDate(LocalDate.of(2025, 3, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD)
                        .baselineStartDate(LocalDate.of(2024, 12, 31))
                        .baselineEndDate(LocalDate.of(2024, 12, 31)).build())
                .filters(new ArrayList<>()).calculation(BankQueryPlan.Calculation.builder()
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

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, changeRequirements);

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("CHANGE_DATE_DIMENSION_FORBIDDEN"));
    }

    @Test
    void rejectsCompilerOwnedOrderingAndMissingOrganizationDimensionForProvinceChangeTopN() {
        BankQueryPlan plan = provinceWideChangeTopNPlan();
        plan.setOrderBy(List.of(BankQueryPlan.OrderBy.builder().field("percent_change")
                .direction(BankQueryPlan.SortDirection.DESC).build()));

        BankQueryPlanValidator.ValidationResult ordered = validator.validate(plan,
                provinceWideChangeTopNRequirements());

        assertFalse(ordered.isValid());
        assertTrue(ordered.codes().contains("CHANGE_RESULT_ORDER_FORBIDDEN"));
        assertTrue(ordered.codes().contains("CHANGE_TOPN_ORGANIZATION_DIMENSION_REQUIRED"));

        plan.setOrderBy(List.of());
        plan.setDimensions(List.of("bank_organization"));
        plan.getOutput().setColumns(List.of("bank_organization", "ZB001"));

        assertTrue(validator.validate(plan, provinceWideChangeTopNRequirements()).isValid());
    }

    @Test
    void rejectsCurrentYearFirstDayAsTheStartOfYearPlanBaseline() {
        BankQueryPlan plan = provinceWideChangeTopNPlan();
        plan.setDimensions(List.of("bank_organization"));
        plan.getOutput().setColumns(List.of("bank_organization", "ZB001"));
        plan.getTime().setComparison(BankQueryPlan.TimeComparison.START_OF_YEAR);
        plan.getTime().setBaselineStartDate(LocalDate.of(2026, 1, 1));
        plan.getTime().setBaselineEndDate(LocalDate.of(2026, 1, 1));

        BankQueryPlanValidator.ValidationResult invalid = validator.validate(plan,
                provinceWideChangeTopNRequirements());

        assertFalse(invalid.isValid());
        assertTrue(invalid.codes().contains("START_OF_YEAR_BASELINE_INVALID"));

        plan.getTime().setBaselineStartDate(LocalDate.of(2025, 12, 31));
        plan.getTime().setBaselineEndDate(LocalDate.of(2025, 12, 31));

        assertTrue(validator.validate(plan, provinceWideChangeTopNRequirements()).isValid());
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
                .filters(new ArrayList<>()).calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.CHANGE).build())
                .orderBy(new ArrayList<>()).limit(3)
                .output(BankQueryPlan.Output.builder().columns(new ArrayList<>(List.of("ZB001")))
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
                .derivedMetrics(new ArrayList<>()).dimensions(new ArrayList<>(List.of("bank_organization")))
                .organizations(new ArrayList<>(List.of(BankQueryPlan.Organization.builder()
                        .code("ORG004").build())))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 7, 31))
                        .endDate(LocalDate.of(2025, 7, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .filters(new ArrayList<>(List.of(BankQueryPlan.Filter.builder().field("benchmark")
                        .operator("COMPARE").value("PROVINCE_AVERAGE").values(new ArrayList<>())
                        .build())))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(new ArrayList<>()).limit(null)
                .output(BankQueryPlan.Output.builder().columns(new ArrayList<>(
                        List.of("bank_organization", "ZB001", "ZB002"))).orderSensitive(false)
                        .build())
                .build();
    }
}
