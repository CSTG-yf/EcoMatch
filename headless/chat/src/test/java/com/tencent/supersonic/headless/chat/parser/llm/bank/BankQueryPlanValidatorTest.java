package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankQueryPlanValidatorTest {

    private final BankQueryPlanValidator validator = new BankQueryPlanValidator();

    @Test
    void shouldAcceptCompleteRankingPlanThatPreservesUserConstraints() {
        BankQueryPlan plan = completeRankingPlan();

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertTrue(result.isValid(), result::summary);
    }

    @Test
    void shouldRejectPlanThatDropsRecognizedMetricOrTimeRange() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setMetrics(List.of(BankQueryPlan.Metric.builder().bizName("ZB002").build()));
        plan.getTime().setEndDate(LocalDate.of(2026, 2, 28));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("MISSING_REQUIRED_METRIC"));
        assertTrue(result.codes().contains("TIME_RANGE_MISMATCH"));
    }

    @Test
    void shouldRejectPhysicalSqlAndUnknownOrganizationInsteadOfExecutingThem() {
        BankQueryPlan plan = completeRankingPlan();
        plan.getMetrics().get(0).setBizName("sum(deposit_balance)");
        plan.setOrganizations(List.of(BankQueryPlan.Organization.builder().code("ORG999").build()));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("FORBIDDEN_SQL_TOKEN"));
        assertTrue(result.codes().contains("UNKNOWN_ORGANIZATION"));
    }

    @Test
    void shouldRejectRankingPlanWithoutStableOrderAndRequestedTopN() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setOrderBy(List.of());
        plan.setLimit(10);

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("RANKING_ORDER_REQUIRED"));
        assertTrue(result.codes().contains("LIMIT_MISMATCH"));
    }

    @Test
    void shouldAcceptSelectedOrganizationRankWithoutTopN() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setLimit(null);
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("机构", "数据日期")).requiredMetrics(Set.of("ZB001"))
                .requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints);

        assertTrue(result.isValid(), result::summary);
    }

    @Test
    void shouldKeepTopNRequiredForUnboundedRanking() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setOrganizations(List.of());
        plan.setLimit(null);
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("机构", "数据日期")).requiredMetrics(Set.of("ZB001"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints);

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("RANKING_LIMIT_REQUIRED"));
    }

    @Test
    void shouldRejectRankingPlanThatGroupsByDateInsteadOfOrganization() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setDimensions(List.of("数据日期"));
        plan.getOutput().setColumns(List.of("数据日期", "ZB001"));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("RANKING_ORGANIZATION_DIMENSION_REQUIRED"));
    }

    @Test
    void shouldRejectFilterOnPhysicalColumnEvenWhenItLooksLikeAValidCondition() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setFilters(List.of(BankQueryPlan.Filter.builder().field("org_code").operator("EQ")
                .value("ORG004").build()));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("UNKNOWN_FILTER_FIELD"));
    }

    @Test
    void shouldCreateImmutableHintsFromFinancialIntentAndSemanticSchema() {
        BankIntentResult intent = new BankIntentResult();
        intent.setIntent(BankIntentType.RANKING);
        intent.setMetrics(
                List.of(BankIntentResult.MetricCandidate.builder().code("ZB001").build()));
        intent.setOrganizations(
                List.of(BankIntentResult.OrganizationSlot.builder().code("ORG004").build()));
        intent.setTime(BankIntentResult.TimeSlot.builder().startDate(LocalDate.of(2026, 3, 31))
                .endDate(LocalDate.of(2026, 3, 31)).build());
        intent.setFilters(List.of(BankIntentResult.FilterSlot.builder().field("rank")
                .operator("LTE").value("3").build()));
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setMetrics(List.of(SchemaElement.builder().bizName("ZB001").name("各项存款余额").build()));
        schema.setDimensions(List.of(SchemaElement.builder().bizName("机构").name("机构").build()));
        schema.setPartitionTime(SchemaElement.builder().bizName("数据日期").name("数据日期").build());

        SemanticIntentHints hints = SemanticIntentHints.from(intent, schema);

        assertEquals(BankIntentType.RANKING, hints.getExpectedIntent());
        assertEquals(Set.of("ZB001"), hints.getRequiredMetrics());
        assertEquals(Set.of("ORG004"), hints.getRequiredOrganizationCodes());
        assertEquals(3, hints.getRequiredLimit());
        assertTrue(hints.getAllowedMetrics().contains("各项存款余额"));
        assertTrue(hints.getAllowedDimensions().contains("数据日期"));
        assertThrows(UnsupportedOperationException.class,
                () -> hints.getRequiredMetrics().add("ZB999"));
    }

    @Test
    void shouldRejectComparisonWithoutExplicitGranularityAndEarlierBaseline() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setIntent(BankIntentType.CHANGE);
        plan.setOrganizations(List.of());
        plan.setCalculation(BankQueryPlan.Calculation.builder()
                .type(BankQueryPlan.CalculationType.CHANGE).build());
        plan.getTime().setGranularity(null);
        plan.getTime().setComparison(BankQueryPlan.TimeComparison.YEAR_OVER_YEAR);
        plan.getTime().setBaselineStartDate(LocalDate.of(2026, 3, 31));
        plan.getTime().setBaselineEndDate(LocalDate.of(2026, 3, 31));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, changeHints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("TIME_GRANULARITY_REQUIRED"));
        assertTrue(result.codes().contains("COMPARISON_BASELINE_INVALID"));
    }

    @Test
    void shouldRejectRatioPlanWithoutAnExplicitSecondMetricAsDenominator() {
        BankQueryPlan plan = BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.RATIO)
                .metrics(List.of(
                        BankQueryPlan.Metric.builder().bizName("ZB005")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build(),
                        BankQueryPlan.Metric.builder().bizName("ZB002")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()))
                .dimensions(List.of()).organizations(List.of())
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2026, 3, 31))
                        .endDate(LocalDate.of(2026, 3, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.RATIO).build())
                .orderBy(List.of()).output(BankQueryPlan.Output.builder()
                        .columns(List.of("ZB005", "ZB002")).orderSensitive(true).build())
                .build();
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RATIO).allowedMetrics(Set.of("ZB005", "ZB002"))
                .allowedDimensions(Set.of("机构", "数据日期")).requiredMetrics(Set.of("ZB005", "ZB002"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints);

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("RATIO_DENOMINATOR_REQUIRED"));
    }

    @Test
    void shouldRejectMultiRatioWhenTheSharedDenominatorIsNotTheFinalMetric() {
        BankQueryPlan plan = BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.RATIO)
                .metrics(List.of(
                        BankQueryPlan.Metric.builder().bizName("ZB003")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build(),
                        BankQueryPlan.Metric.builder().bizName("ZB001")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build(),
                        BankQueryPlan.Metric.builder().bizName("ZB004")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()))
                .dimensions(List.of()).organizations(List.of())
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2026, 3, 31))
                        .endDate(LocalDate.of(2026, 3, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.MULTI_RATIO).baseline("ZB001").build())
                .orderBy(List.of()).output(BankQueryPlan.Output.builder()
                        .columns(List.of("ZB003", "ZB001", "ZB004")).orderSensitive(true).build())
                .build();
        SemanticIntentHints hints =
                SemanticIntentHints.builder().expectedIntent(BankIntentType.RATIO)
                        .allowedMetrics(Set.of("ZB001", "ZB003", "ZB004"))
                        .allowedDimensions(Set.of("机构", "数据日期"))
                        .requiredMetrics(Set.of("ZB001", "ZB003", "ZB004"))
                        .requiredStartDate(LocalDate.of(2026, 3, 31))
                        .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints);

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("MULTI_RATIO_DENOMINATOR_MISMATCH"));
    }

    @Test
    void shouldRejectTrendWithoutTheSemanticDateDimension() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setIntent(BankIntentType.TREND);
        plan.setDimensions(List.of());
        plan.setOrderBy(List.of());
        plan.setLimit(null);
        plan.getOutput().setColumns(List.of("ZB001"));
        plan.getTime().setStartDate(LocalDate.of(2025, 3, 31));
        plan.getTime().setEndDate(LocalDate.of(2026, 3, 31));
        plan.getTime().setGranularity(BankQueryPlan.TimeGranularity.QUARTER);

        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.TREND).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2025, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints);

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("TREND_TIME_DIMENSION_REQUIRED"));
    }

    @Test
    void shouldRejectOutputThatDropsASelectedDimension() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setDimensions(List.of("机构", "数据日期"));
        plan.getOutput().setColumns(List.of("机构", "ZB001"));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("OUTPUT_MISSING_DIMENSION"));
        assertFalse(result.codes().contains("OUTPUT_EXTRA_COLUMN"));
        assertFalse(result.codes().contains("OUTPUT_MISSING_METRIC"));
        assertFalse(result.codes().contains("UNKNOWN_OUTPUT_COLUMN"));
    }

    @Test
    void shouldRejectOutputContainingAValidButUnselectedField() {
        BankQueryPlan plan = completeRankingPlan();
        plan.getOutput().setColumns(List.of("机构", "ZB001", "ZB002"));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("OUTPUT_EXTRA_COLUMN"));
        assertFalse(result.codes().contains("OUTPUT_MISSING_DIMENSION"));
        assertFalse(result.codes().contains("OUTPUT_MISSING_METRIC"));
        assertFalse(result.codes().contains("UNKNOWN_OUTPUT_COLUMN"));
    }

    @Test
    void shouldRejectOutputWithDuplicateColumns() {
        BankQueryPlan plan = completeRankingPlan();
        plan.getOutput().setColumns(List.of("机构", "ZB001", "机构"));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("OUTPUT_EXTRA_COLUMN"));
        assertFalse(result.codes().contains("OUTPUT_MISSING_DIMENSION"));
        assertFalse(result.codes().contains("OUTPUT_MISSING_METRIC"));
    }

    private SemanticIntentHints hints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001", "ZB002")).allowedDimensions(Set.of("机构", "数据日期"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(3).maxLimit(100).build();
    }

    private SemanticIntentHints changeHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.CHANGE)
                .allowedMetrics(Set.of("ZB001", "ZB002")).allowedDimensions(Set.of("机构", "数据日期"))
                .requiredMetrics(Set.of("ZB001")).requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();
    }

    private BankQueryPlan completeRankingPlan() {
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.RANKING)
                .metrics(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).alias("各项存款余额").build()))
                .dimensions(List.of("机构"))
                .organizations(List.of(BankQueryPlan.Organization.builder().code("ORG004").build()))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2026, 3, 31))
                        .endDate(LocalDate.of(2026, 3, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(List.of(BankQueryPlan.OrderBy.builder().field("ZB001")
                        .direction(BankQueryPlan.SortDirection.DESC).build()))
                .limit(3).output(BankQueryPlan.Output.builder().columns(List.of("机构", "ZB001"))
                        .orderSensitive(true).build())
                .build();
    }

    @Test
    void shouldAcceptRankingPlanPreservingTheDerivedMetricContract() {
        BankQueryPlan plan = derivedRankingPlan();

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, derivedHints());

        assertTrue(result.isValid(), result::summary);
    }

    @Test
    void shouldRejectPlanThatDropsTheRecognizedDerivedMetric() {
        BankQueryPlan plan = derivedRankingPlan();
        plan.setDerivedMetrics(List.of());

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, derivedHints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("DERIVED_METRIC_MISSING"));
    }

    @Test
    void shouldRejectIllegalDerivedMetricCodeAndOperands() {
        BankQueryPlan codeOperandConflict = derivedRankingPlan();
        codeOperandConflict.setDerivedMetrics(
                List.of(BankQueryPlan.DerivedMetric.builder().metricCode("DERIVED_ZB002_DIV_ZB001")
                        .numerator("ZB001").denominator("ZB001").name("存贷比").build()));
        assertTrue(validator.validate(codeOperandConflict, derivedHints()).codes()
                .contains("DERIVED_METRIC_INVALID"));

        BankQueryPlan sameOperands = derivedRankingPlan();
        sameOperands.setDerivedMetrics(
                List.of(BankQueryPlan.DerivedMetric.builder().metricCode("DERIVED_ZB001_DIV_ZB001")
                        .numerator("ZB001").denominator("ZB001").name("存贷比").build()));
        assertTrue(validator.validate(sameOperands, derivedHints()).codes()
                .contains("DERIVED_METRIC_INVALID"));

        BankQueryPlan nonBaseOperand = derivedRankingPlan();
        nonBaseOperand.setDerivedMetrics(
                List.of(BankQueryPlan.DerivedMetric.builder().metricCode("DERIVED_ZB002_DIV_ZB001")
                        .numerator("贷款余额").denominator("ZB001").name("存贷比").build()));
        assertTrue(validator.validate(nonBaseOperand, derivedHints()).codes()
                .contains("DERIVED_METRIC_INVALID"));
    }

    @Test
    void shouldRejectDuplicateDerivedMetricEntries() {
        BankQueryPlan plan = derivedRankingPlan();
        BankQueryPlan.DerivedMetric derived = plan.getDerivedMetrics().get(0);
        plan.setDerivedMetrics(List.of(derived, derived));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, derivedHints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("DERIVED_METRIC_DUPLICATE"));
    }

    @Test
    void shouldRejectDerivedMetricThatDoesNotMatchMapperEvidence() {
        BankQueryPlan plan = derivedRankingPlan();
        plan.setDerivedMetrics(
                List.of(BankQueryPlan.DerivedMetric.builder().metricCode("DERIVED_ZB013_DIV_ZB001")
                        .numerator("ZB013").denominator("ZB001").name("不良贷款率").build()));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, derivedHints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("DERIVED_METRIC_MISMATCH"));
    }

    @Test
    void shouldRejectDerivedMetricsReorderedAgainstMapperEvidence() {
        BankQueryPlan plan = derivedRankingPlan();
        plan.setDerivedMetrics(List.of(
                BankQueryPlan.DerivedMetric.builder().metricCode("DERIVED_ZB001_DIV_ZB002")
                        .numerator("ZB001").denominator("ZB002").name("存贷比倒数").build(),
                BankQueryPlan.DerivedMetric.builder().metricCode("DERIVED_ZB002_DIV_ZB001")
                        .numerator("ZB002").denominator("ZB001").name("存贷比").build()));

        BankQueryPlanValidator.ValidationResult result =
                validator.validate(plan, twoDerivedHints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("DERIVED_METRIC_MISMATCH"));
    }

    @Test
    void shouldRejectUnexpectedDerivedMetricWithoutMapperEvidence() {
        BankQueryPlan plan = derivedRankingPlan();

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, hints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("DERIVED_METRIC_UNEXPECTED"));
    }

    @Test
    void shouldRejectDerivedMetricWhoseOperandsAreNotDirectMetrics() {
        BankQueryPlan plan = derivedRankingPlan();
        plan.setMetrics(List.of(BankQueryPlan.Metric.builder().bizName("ZB002")
                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, derivedHints());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("DERIVED_METRIC_OPERAND_REQUIRED"));
    }

    @Test
    void shouldRejectDerivedMetricOutsideTheRankingDirectContract() {
        BankQueryPlan nonDirectCalculation = derivedRankingPlan();
        nonDirectCalculation.setCalculation(BankQueryPlan.Calculation.builder()
                .type(BankQueryPlan.CalculationType.RATIO).build());
        assertTrue(validator.validate(nonDirectCalculation, derivedHints()).codes()
                .contains("DERIVED_METRIC_CALCULATION_REQUIRED"));

        BankQueryPlan nonRankingIntent = derivedRankingPlan();
        nonRankingIntent.setIntent(BankIntentType.POINT_QUERY);
        assertTrue(validator.validate(nonRankingIntent, derivedHints()).codes()
                .contains("DERIVED_METRIC_INTENT_REQUIRED"));
    }

    private SemanticIntentHints derivedHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001", "ZB002")).allowedDimensions(Set.of("机构", "数据日期"))
                .requiredMetrics(Set.of("ZB001", "ZB002"))
                .requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(3).maxLimit(100)
                .requiredDerivedMetrics(List.of(new SemanticIntentHints.DerivedMetricSpec(
                        "DERIVED_ZB002_DIV_ZB001", "ZB002", "ZB001", "存贷比")))
                .build();
    }

    private SemanticIntentHints twoDerivedHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001", "ZB002")).allowedDimensions(Set.of("机构", "数据日期"))
                .requiredMetrics(Set.of("ZB001", "ZB002"))
                .requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(3).maxLimit(100)
                .requiredDerivedMetrics(List.of(
                        new SemanticIntentHints.DerivedMetricSpec("DERIVED_ZB002_DIV_ZB001",
                                "ZB002", "ZB001", "存贷比"),
                        new SemanticIntentHints.DerivedMetricSpec("DERIVED_ZB001_DIV_ZB002",
                                "ZB001", "ZB002", "存贷比倒数")))
                .build();
    }

    private BankQueryPlan derivedRankingPlan() {
        BankQueryPlan plan = completeRankingPlan();
        plan.setMetrics(List.of(
                BankQueryPlan.Metric.builder().bizName("ZB002")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build(),
                BankQueryPlan.Metric.builder().bizName("ZB001")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()));
        plan.setDerivedMetrics(
                List.of(BankQueryPlan.DerivedMetric.builder().metricCode("DERIVED_ZB002_DIV_ZB001")
                        .numerator("ZB002").denominator("ZB001").name("存贷比").build()));
        plan.getOutput().setColumns(List.of("机构", "ZB002", "ZB001"));
        return plan;
    }
}
