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
}
