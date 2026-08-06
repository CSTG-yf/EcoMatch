package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.enums.AggOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.QueryType;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaValueMap;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankQueryPlanCompilerTest {

    private final BankQueryPlanCompiler compiler = new BankQueryPlanCompiler();

    @Test
    void shouldCompileRankingPlanToStableStructRequest() {
        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(rankingPlan(), rankingHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.STRUCT, compiled.getRoute());
        assertEquals(QueryType.AGGREGATE, compiled.getStructReq().getQueryType());
        assertEquals(List.of("bank_organization"), compiled.getStructReq().getGroups());
        assertEquals("ZB001", compiled.getStructReq().getAggregators().get(0).getColumn());
        assertEquals(AggOperatorEnum.SUM,
                compiled.getStructReq().getAggregators().get(0).getFunc());
        assertEquals("ZB001", compiled.getStructReq().getAggregators().get(0).getAlias());
        assertTrue(compiled.getStructReq().getDimensionFilters().isEmpty());
        assertEquals("数据日期", compiled.getStructReq().getDateInfo().getDateField());
        assertEquals("2026-03-31", compiled.getStructReq().getDateInfo().getStartDate());
        assertEquals("2026-03-31", compiled.getStructReq().getDateInfo().getEndDate());
        assertEquals(List.of("ZB001", "bank_organization"), compiled.getStructReq().getOrders()
                .stream().map(order -> order.getColumn()).toList());
        assertEquals(List.of("DESC", "ASC"), compiled.getStructReq().getOrders().stream()
                .map(order -> order.getDirection()).toList());
        assertEquals(SemanticIntentHints.DEFAULT_MAX_LIMIT, compiled.getStructReq().getLimit());
        assertEquals(List.of("bank_organization", "ZB001"), compiled.getOutputColumns());
        assertEquals(BankResultProjector.ProjectionType.RANKED_LONG_FORM,
                compiled.getResultContract().getType());
        assertEquals("bank_organization", compiled.getResultContract().getOrganizationColumn());
        assertEquals(List.of("ORG004"),
                compiled.getResultContract().getSelectedOrganizationCodes());
    }

    @Test
    void shouldCompileCombinedGoodAndPoorPerformanceRankHints() {
        BankQueryPlan plan = rankingPlan();
        plan.setFilters(List.of(
                BankQueryPlan.Filter.builder().field("rank").operator("LTE").value("3").build(),
                BankQueryPlan.Filter.builder().field("rank_from_bottom").operator("LTE").value("4")
                        .build()));

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, rankingHints(), schema());

        assertTrue(compiled.getStructReq().getMetricFilters().isEmpty());
        assertEquals(SemanticIntentHints.DEFAULT_MAX_LIMIT, compiled.getStructReq().getLimit());
    }

    @Test
    void shouldRankWithinAnExplicitOrganizationSubset() {
        BankQueryPlan plan = rankingPlan();
        plan.setOrganizations(
                List.of(organization("ORG001"), organization("ORG005"), organization("ORG009")));
        plan.setFilters(List.of(
                BankQueryPlan.Filter.builder().field("rank").operator("LTE").value("1").build()));
        plan.setLimit(1);
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001"))
                .requiredOrganizationCodes(Set.of("ORG001", "ORG005", "ORG009"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31))
                .requiredFilters(
                        List.of(new SemanticIntentHints.RequiredFilter("rank", "LTE", "1")))
                .requiredLimit(1).maxLimit(100).build();

        BankQueryPlanCompiler.CompiledQuery compiled = compiler.compile(plan, hints, schema());

        assertTrue(compiled.getStructReq().getDimensionFilters().isEmpty());
        assertEquals(SemanticIntentHints.DEFAULT_MAX_LIMIT, compiled.getStructReq().getLimit());
        assertEquals(Integer.valueOf(1), compiled.getResultContract().getTopRankLimit());
    }

    @Test
    void shouldLoadTheFullRankingInputForABottomRankSlice() {
        BankQueryPlan plan = rankingPlan();
        plan.setOrganizations(List.of());
        plan.setFilters(List.of(BankQueryPlan.Filter.builder().field("rank_from_bottom")
                .operator("LTE").value("1").build()));
        plan.setLimit(1);
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31))
                .requiredFilters(List
                        .of(new SemanticIntentHints.RequiredFilter("rank_from_bottom", "LTE", "1")))
                .requiredLimit(1).maxLimit(100).build();

        BankQueryPlanCompiler.CompiledQuery compiled = compiler.compile(plan, hints, schema());

        assertEquals(SemanticIntentHints.DEFAULT_MAX_LIMIT, compiled.getStructReq().getLimit());
        assertEquals(Integer.valueOf(1), compiled.getResultContract().getBottomRankLimit());
    }

    @Test
    void shouldCompileAnnualAverageTopAndBottomRankingToAControlledTemplate() {
        BankQueryPlan plan = rankingPlan();
        plan.setOrganizations(List.of());
        plan.setMetrics(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                .aggregation(BankQueryPlan.Aggregation.AVG).build()));
        plan.setTime(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 1, 1))
                .endDate(LocalDate.of(2025, 12, 31)).granularity(BankQueryPlan.TimeGranularity.DAY)
                .comparison(BankQueryPlan.TimeComparison.NONE).build());
        plan.setFilters(List.of(
                BankQueryPlan.Filter.builder().field("rank").operator("LTE").value("3").build(),
                BankQueryPlan.Filter.builder().field("rank_from_bottom").operator("LTE").value("3")
                        .build()));
        plan.setLimit(6);

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, annualAverageRankingHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.STRUCT, compiled.getRoute());
        assertEquals(List.of("bank_organization", "bank_data_date"),
                compiled.getStructReq().getGroups());
        assertEquals("ZB001", compiled.getStructReq().getAggregators().get(0).getColumn());
        assertEquals(10_000, compiled.getStructReq().getLimit());
        assertEquals(List.of("bank_organization", "数据日期", "ZB001"), compiled.getOutputColumns());
        assertEquals(BankResultProjector.ProjectionType.DAILY_AVERAGE_RANKING,
                compiled.getResultContract().getType());
        assertEquals(Integer.valueOf(3), compiled.getResultContract().getTopRankLimit());
        assertEquals(Integer.valueOf(3), compiled.getResultContract().getBottomRankLimit());
    }

    @Test
    void shouldAttachTheStableLongFormContractToAnOrganizationComparison() {
        BankQueryPlan plan = rankingPlan();
        plan.setIntent(BankIntentType.COMPARISON);
        plan.setOrganizations(List.of(organization("ORG004"), organization("ORG005")));
        plan.setOrderBy(List.of(BankQueryPlan.OrderBy.builder().field("ZB001")
                .direction(BankQueryPlan.SortDirection.ASC).build()));
        plan.setLimit(null);

        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.COMPARISON).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001"))
                .requiredOrganizationCodes(Set.of("ORG004", "ORG005"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();

        BankQueryPlanCompiler.CompiledQuery compiled = compiler.compile(plan, hints, schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertTrue(compiled.getS2sql().contains("WITH bank_comparison AS"));
        assertTrue(compiled.getS2sql()
                .contains("SELECT bank_organization, SUM(ZB001) AS metric_value"));
        assertTrue(compiled.getS2sql()
                .contains("FROM bank_comparison\nWHERE bank_organization IN ('ORG004', 'ORG005')"));
        assertTrue(compiled.getS2sql().contains("ORDER BY metric_value ASC"));
        assertEquals(BankResultProjector.ProjectionType.COMPARISON,
                compiled.getResultContract().getType());
        assertEquals("metric_value",
                compiled.getResultContract().getMetrics().get(0).getSemanticColumn());
        assertEquals(List.of("ORG004", "ORG005"),
                compiled.getResultContract().getSelectedOrganizationCodes());
    }

    @Test
    void shouldCompileComparisonPlanToControlledSemanticS2Sql() {
        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(changePlan(), changeHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        String sql = compiled.getS2sql();
        assertTrue(sql.contains("WITH bank_current AS"));
        assertTrue(sql.contains("SUM(ZB001) AS current_value"));
        assertTrue(sql.contains("FROM 银行指标数据集"));
        assertTrue(sql.contains("bank_organization = 'ORG004'"));
        assertTrue(sql.contains("CASE WHEN baseline_value = 0 THEN NULL"));
        assertFalse(sql.contains("bank_daily_metrics"));
        assertFalse(sql.contains("metric_value"));
        assertFalse(sql.contains("org_code"));
        assertEquals(
                List.of("current_value", "baseline_value", "absolute_change", "percent_change"),
                compiled.getOutputColumns());
    }

    @Test
    void shouldCompileMultipleMetricChangeAsStableLongForm() {
        BankQueryPlan plan = changePlan();
        plan.setMetrics(List.of(metric("ZB001"), metric("ZB002")));
        plan.getOutput().setColumns(List.of("ZB001", "ZB002"));

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, changeHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertTrue(compiled.getS2sql().contains("'ZB001' AS metric_code"));
        assertTrue(compiled.getS2sql().contains("'ZB002' AS metric_code"));
        assertTrue(compiled.getS2sql().contains("UNION ALL"));
        assertTrue(compiled.getS2sql().contains("ORDER BY metric_code ASC"));
        assertEquals(List.of("metric_code", "current_value", "baseline_value", "absolute_change",
                "percent_change"), compiled.getOutputColumns());
        assertEquals(BankResultProjector.ProjectionType.MULTI_METRIC_CHANGE,
                compiled.getResultContract().getType());
    }

    @Test
    void shouldPreserveOrganizationCodeToDisplayNameForMultipleMetricChange() {
        LLMReq.LLMSchema schema = schema();
        SchemaValueMap organization = new SchemaValueMap();
        organization.setTechName("ORG004");
        organization.setBizName("江苏省D市农商行");
        schema.getDimensions().get(0).setSchemaValueMaps(List.of(organization));

        BankQueryPlan plan = changePlan();
        plan.setMetrics(List.of(metric("ZB001"), metric("ZB002")));
        plan.getOutput().setColumns(List.of("ZB001", "ZB002"));
        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, changeHints(), schema);

        assertEquals(Map.of("ORG004", "江苏省D市农商行"),
                compiled.getResultContract().getOrganizationNames());
    }

    @Test
    void shouldCompileStartOfYearChangeAsAnEndOfPeriodSnapshot() {
        BankQueryPlan plan = changePlan();
        plan.getTime().setStartDate(LocalDate.of(2025, 1, 1));
        plan.getTime().setEndDate(LocalDate.of(2025, 4, 30));
        plan.getTime().setComparison(BankQueryPlan.TimeComparison.START_OF_YEAR);
        plan.getTime().setBaselineStartDate(LocalDate.of(2024, 12, 31));
        plan.getTime().setBaselineEndDate(LocalDate.of(2024, 12, 31));
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.CHANGE).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2025, 1, 1))
                .requiredEndDate(LocalDate.of(2025, 4, 30)).maxLimit(100).build();

        BankQueryPlanCompiler.CompiledQuery compiled = compiler.compile(plan, hints, schema());

        assertTrue(compiled.getS2sql().contains("\u6570\u636e\u65e5\u671f >= '2025-04-30'"));
        assertTrue(compiled.getS2sql().contains("\u6570\u636e\u65e5\u671f <= '2025-04-30'"));
        assertFalse(compiled.getS2sql().contains("\u6570\u636e\u65e5\u671f >= '2025-01-01'"));
        assertTrue(compiled.getS2sql().contains("\u6570\u636e\u65e5\u671f >= '2024-12-31'"));
    }

    @Test
    void shouldCompileMonthAndYearComparisonAsTwoOrderedBaselines() {
        BankQueryPlan plan = changePlan();
        plan.getTime().setStartDate(LocalDate.of(2026, 4, 30));
        plan.getTime().setEndDate(LocalDate.of(2026, 4, 30));
        plan.getTime().setComparison(BankQueryPlan.TimeComparison.MOM_AND_YOY);
        plan.getTime().setBaselineStartDate(null);
        plan.getTime().setBaselineEndDate(null);
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.CHANGE).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 4, 30))
                .requiredEndDate(LocalDate.of(2026, 4, 30)).maxLimit(100).build();

        BankQueryPlanCompiler.CompiledQuery compiled = compiler.compile(plan, hints, schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertFalse(compiled.getS2sql().contains("UNION ALL"));
        assertTrue(compiled.getS2sql().contains("'2026-03-31'"));
        assertTrue(compiled.getS2sql().contains("'2025-04-30'"));
        assertTrue(compiled.getS2sql().contains("mom_baseline_value"));
        assertTrue(compiled.getS2sql().contains("yoy_baseline_value"));
        assertEquals(
                List.of("current_value", "baseline_value", "absolute_change", "percent_change"),
                compiled.getOutputColumns());
        assertEquals(BankResultProjector.ProjectionType.MOM_YOY_CHANGE,
                compiled.getResultContract().getType());
    }

    @Test
    void shouldCompileGroupedChangePlanWithStableDimensionJoin() {
        BankQueryPlan plan = changePlan();
        plan.setDimensions(List.of("bank_organization"));
        plan.setOrganizations(List.of());
        plan.getOutput().setColumns(List.of("bank_organization", "ZB001"));

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, groupedChangeHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        String sql = compiled.getS2sql();
        assertTrue(sql.contains("SELECT bank_organization, SUM(ZB001) AS current_value"));
        assertTrue(sql.contains("GROUP BY bank_organization"));
        assertTrue(sql.contains("FROM bank_current INNER JOIN bank_baseline"));
        assertTrue(sql
                .contains("ON bank_current.bank_organization = bank_baseline.bank_organization"));
        assertTrue(sql.contains("ORDER BY bank_current.bank_organization ASC"));
        assertEquals(List.of("bank_organization", "current_value", "baseline_value",
                "absolute_change", "percent_change"), compiled.getOutputColumns());
    }

    @Test
    void shouldCompileRatioPlanWithStableNumeratorDenominatorAndPercentageColumns() {
        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(ratioPlan(), ratioHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertTrue(compiled.getS2sql().contains("WITH bank_ratio AS"));
        assertTrue(compiled.getS2sql().contains("SUM(ZB001) AS numerator_value"));
        assertTrue(compiled.getS2sql().contains("SUM(ZB002) AS denominator_value"));
        assertTrue(compiled.getS2sql().contains("CASE WHEN denominator_value = 0 THEN NULL"));
        assertTrue(compiled.getS2sql()
                .contains("ELSE numerator_value * 100.0 / denominator_value END AS ratio_percent"));
        assertTrue(compiled.getS2sql().contains("AS ratio_percent"));
        assertEquals(List.of("numerator_value", "denominator_value", "ratio_percent"),
                compiled.getOutputColumns());
    }

    @Test
    void shouldCompileGroupedRatioPlanWithStableDimensionOrder() {
        BankQueryPlan plan = ratioPlan();
        plan.setDimensions(List.of("bank_organization"));
        plan.getOutput().setColumns(List.of("bank_organization", "ZB001", "ZB002"));

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, ratioHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertTrue(compiled.getS2sql().contains("WITH bank_ratio AS"));
        assertTrue(compiled.getS2sql()
                .contains("SELECT bank_organization, numerator_value, denominator_value"));
        assertTrue(compiled.getS2sql().contains(
                "SELECT bank_organization, SUM(ZB001) AS numerator_value, SUM(ZB002) AS denominator_value"));
        assertTrue(compiled.getS2sql().contains("GROUP BY bank_organization"));
        assertTrue(compiled.getS2sql().contains("ORDER BY bank_organization ASC"));
        assertEquals(List.of("bank_organization", "numerator_value", "denominator_value",
                "ratio_percent"), compiled.getOutputColumns());
    }

    @Test
    void shouldCompileQuarterlyTrendToDateGroupedStableResultContract() {
        BankQueryPlan plan = BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.TREND).metrics(List.of(metric("ZB001")))
                .dimensions(List.of("bank_data_date"))
                .organizations(List.of(organization("ORG004")))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 3, 31))
                        .endDate(LocalDate.of(2026, 3, 31))
                        .granularity(BankQueryPlan.TimeGranularity.QUARTER)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(List.of()).output(BankQueryPlan.Output.builder()
                        .columns(List.of("bank_data_date", "ZB001")).orderSensitive(true).build())
                .build();
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.TREND).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2025, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();

        BankQueryPlanCompiler.CompiledQuery compiled = compiler.compile(plan, hints, schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.STRUCT, compiled.getRoute());
        assertEquals(List.of("bank_data_date"), compiled.getStructReq().getGroups());
        assertEquals(1, compiled.getStructReq().getDimensionFilters().size());
        assertEquals(com.tencent.supersonic.common.pojo.DateConf.DateMode.BETWEEN,
                compiled.getStructReq().getDateInfo().getDateMode());
        assertEquals("2025-03-31", compiled.getStructReq().getDateInfo().getStartDate());
        assertEquals("2026-03-31", compiled.getStructReq().getDateInfo().getEndDate());
        assertEquals(List.of("bank_data_date"), compiled.getStructReq().getOrders().stream()
                .map(order -> order.getColumn()).toList());
        assertEquals(List.of("ASC"), compiled.getStructReq().getOrders().stream()
                .map(order -> order.getDirection()).toList());
        assertEquals(List.of("bank_data_date", "ZB001"), compiled.getOutputColumns());
        assertEquals(BankResultProjector.ProjectionType.TREND,
                compiled.getResultContract().getType());
        assertEquals("bank_data_date", compiled.getResultContract().getTimeColumn());
        assertEquals(List.of("2025-03-31", "2025-06-30", "2025-09-30", "2025-12-31", "2026-03-31"),
                compiled.getResultContract().getSelectedDates());
    }

    @Test
    void shouldRejectCompilationWhenTheOrganizationSemanticDimensionIsUnavailable() {
        LLMReq.LLMSchema schema = schema();
        schema.setDimensions(List.of());
        BankQueryPlan plan = rankingPlan();
        plan.setIntent(BankIntentType.AGGREGATION);
        plan.setDimensions(List.of());
        plan.setOrderBy(List.of());
        plan.setLimit(null);
        plan.getOutput().setColumns(List.of("ZB001"));
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.AGGREGATION).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_data_date")).requiredMetrics(Set.of("ZB001"))
                .requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();

        BankPlanCompilationException exception = assertThrows(BankPlanCompilationException.class,
                () -> compiler.compile(plan, hints, schema));

        assertEquals(BankPlanCompilationException.Reason.ORGANIZATION_DIMENSION_UNAVAILABLE,
                exception.getReason());
    }

    @Test
    void shouldCanonicalizeSemanticDisplayNamesBeforeCheckingOutputOrder() {
        BankQueryPlan plan = rankingPlan();
        plan.setDimensions(List.of("机构"));
        plan.getOutput().setColumns(List.of("机构", "ZB001"));
        plan.setOrderBy(List.of(BankQueryPlan.OrderBy.builder().field("各项存款余额")
                .direction(BankQueryPlan.SortDirection.DESC).build()));
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001", "ZB002", "各项存款余额"))
                .allowedDimensions(Set.of("机构", "bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(3).maxLimit(100).build();

        BankQueryPlanCompiler.CompiledQuery compiled = compiler.compile(plan, hints, schema());

        assertEquals(List.of("bank_organization", "ZB001"), compiled.getOutputColumns());
        assertEquals(List.of("bank_organization"), compiled.getStructReq().getGroups());
        assertEquals("ZB001", compiled.getStructReq().getOrders().get(0).getColumn());
    }

    @Test
    void shouldPreserveAnInFilterWhenThePlannerProvidesOneScalarValue() {
        BankQueryPlan plan = thresholdPlan();
        plan.setFilters(List.of(BankQueryPlan.Filter.builder().field("bank_organization")
                .operator("IN").value("ORG004").build()));

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, thresholdHints(), schema());

        assertEquals(FilterOperatorEnum.IN,
                compiled.getStructReq().getDimensionFilters().get(0).getOperator());
        assertEquals(List.of("ORG004"),
                compiled.getStructReq().getDimensionFilters().get(0).getValue());
    }

    @Test
    void shouldCompileProvinceAverageThresholdToControlledSemanticS2Sql() {
        BankQueryPlan plan = thresholdPlan();
        plan.setFilters(List.of(provinceAverageBenchmark()));

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, provinceAverageThresholdHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertTrue(compiled.getS2sql().contains("WITH bank_values AS"));
        assertTrue(compiled.getS2sql().contains("AVG(metric_value) AS provincial_average"));
        assertTrue(compiled.getS2sql()
                .contains("CASE WHEN metric_value > provincial_average THEN 1 ELSE 0 END"));
        assertTrue(compiled.getS2sql().contains("ORDER BY bank_organization ASC"));
        assertEquals(List.of("bank_organization", "metric_value", "provincial_average",
                "meets_condition"), compiled.getOutputColumns());
        assertEquals(BankResultProjector.ProjectionType.PROVINCIAL_AVERAGE_THRESHOLD,
                compiled.getResultContract().getType());
    }

    @Test
    void shouldCompileAnAbsoluteThresholdToAStableConditionContract() {
        BankQueryPlan plan = thresholdPlan();
        plan.setDimensions(List.of("bank_organization"));
        plan.setOrganizations(List.of(organization("ORG004")));
        plan.setFilters(List.of(BankQueryPlan.Filter.builder().field("metric_value").operator("GTE")
                .value("10.5%").build()));
        plan.getOutput().setColumns(List.of("bank_organization", "ZB001"));
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.THRESHOLD).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31))
                .requiredFilters(List
                        .of(new SemanticIntentHints.RequiredFilter("metric_value", "GTE", "10.5%")))
                .maxLimit(100).build();

        BankQueryPlanCompiler.CompiledQuery compiled = compiler.compile(plan, hints, schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertTrue(compiled.getS2sql().contains("SUM(ZB001) AS metric_value"));
        assertTrue(compiled.getS2sql()
                .contains("CASE WHEN metric_value >= 10.5 THEN 1 ELSE 0 END AS meets_condition"));
        assertTrue(compiled.getS2sql().contains("WHERE bank_organization = 'ORG004'"));
        assertEquals(BankResultProjector.ProjectionType.ABSOLUTE_THRESHOLD,
                compiled.getResultContract().getType());
    }

    @Test
    void shouldCompileProvinceAverageAggregationToAStableSummaryContract() {
        BankQueryPlan plan = thresholdPlan();
        plan.setIntent(BankIntentType.AGGREGATION);
        plan.setOrganizations(List.of(organization("ORG004")));
        plan.setFilters(List.of(provinceAverageBenchmark()));

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, provinceAverageAggregationHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertTrue(compiled.getS2sql().contains("WITH bank_daily_values AS"));
        assertTrue(compiled.getS2sql().contains("SUM(ZB001) AS metric_value"));
        assertTrue(compiled.getS2sql().contains("GROUP BY bank_organization, aggregation_date"));
        assertTrue(compiled.getS2sql().contains("AVG(metric_value) AS aggregate_value"));
        assertTrue(compiled.getS2sql().contains("MIN(metric_value) AS min_value"));
        assertTrue(compiled.getS2sql().contains("MAX(metric_value) AS max_value"));
        assertTrue(compiled.getS2sql().contains("COUNT(metric_value) AS observation_count"));
        assertEquals(List.of("bank_organization", "aggregate_value", "min_value", "max_value",
                "observation_count"), compiled.getOutputColumns());
        assertEquals(BankResultProjector.ProjectionType.AGGREGATION_SUMMARY,
                compiled.getResultContract().getType());
    }

    @Test
    void shouldCompileMultipleMetricProvinceAverageAggregationToStableLongSummary() {
        BankQueryPlan plan = thresholdPlan();
        plan.setIntent(BankIntentType.AGGREGATION);
        plan.setMetrics(List.of(metric("ZB002"), metric("ZB001")));
        plan.getOutput().setColumns(List.of("ZB002", "ZB001"));
        plan.setOrganizations(List.of(organization("ORG004")));
        plan.setFilters(List.of(provinceAverageBenchmark()));

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, provinceAverageAggregationHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        String s2sql = compiled.getS2sql();
        assertTrue(s2sql.contains("bank_daily_values_0 AS ("));
        assertTrue(s2sql.contains("bank_aggregation_0 AS ("));
        assertTrue(s2sql.contains("bank_daily_values_1 AS ("));
        assertTrue(s2sql.contains("bank_aggregation_1 AS ("));
        int firstOuterProjection =
                s2sql.indexOf("SELECT bank_organization, 'ZB001' AS metric_code");
        int secondOuterProjection =
                s2sql.indexOf("SELECT bank_organization, 'ZB002' AS metric_code");
        assertTrue(firstOuterProjection > s2sql.indexOf("bank_aggregation_1 AS ("));
        assertTrue(secondOuterProjection > s2sql.indexOf("bank_aggregation_1 AS ("));
        assertFalse(s2sql.contains("GROUP BY bank_organization, metric_code"));
        assertTrue(s2sql.contains("UNION ALL"));
        assertTrue(s2sql
                .contains("ORDER BY metric_code ASC, aggregate_value DESC, bank_organization ASC"));
        assertEquals(List.of("bank_organization", "metric_code", "aggregate_value", "min_value",
                "max_value", "observation_count"), compiled.getOutputColumns());
        assertEquals(List.of("ZB001", "ZB002"), compiled.getResultContract().getMetrics().stream()
                .map(BankResultProjector.MetricBinding::getMetricCode).toList());
        assertEquals(BankResultProjector.ProjectionType.AGGREGATION_SUMMARY,
                compiled.getResultContract().getType());
    }

    @Test
    void shouldCompileAnnualDailyAggregationToAStableSummaryContract() {
        BankQueryPlan plan = rankingPlan();
        plan.setIntent(BankIntentType.AGGREGATION);
        plan.setMetrics(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                .aggregation(BankQueryPlan.Aggregation.AVG).build()));
        plan.setTime(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 1, 1))
                .endDate(LocalDate.of(2025, 12, 31)).granularity(BankQueryPlan.TimeGranularity.DAY)
                .comparison(BankQueryPlan.TimeComparison.NONE).build());
        plan.setOrderBy(List.of());
        plan.setLimit(null);

        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(plan, annualDailyAggregationHints(), schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        assertTrue(compiled.getS2sql().contains("WITH bank_daily_values AS"));
        assertTrue(compiled.getS2sql().contains("AVG(metric_value) AS aggregate_value"));
        assertTrue(compiled.getS2sql().contains("MIN(metric_value) AS min_value"));
        assertTrue(compiled.getS2sql().contains("MAX(metric_value) AS max_value"));
        assertTrue(compiled.getS2sql().contains("COUNT(metric_value) AS observation_count"));
        assertEquals(BankResultProjector.ProjectionType.AGGREGATION_SUMMARY,
                compiled.getResultContract().getType());
    }

    @Test
    void shouldCompileDaysAboveProvinceAverageToPerDayComparisonSql() {
        BankQueryPlanCompiler.CompiledQuery compiled =
                compiler.compile(daysAboveProvinceAveragePlan(), daysAboveProvinceAverageHints(),
                        schema());

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, compiled.getRoute());
        String sql = compiled.getS2sql();
        assertTrue(sql.contains("WITH bank_daily_values AS"));
        assertTrue(sql.contains(
                "SELECT bank_organization, bank_data_date AS aggregation_date, SUM(ZB001) AS metric_value"));
        assertTrue(sql.contains("GROUP BY bank_organization, aggregation_date"));
        assertTrue(sql.contains("SELECT aggregation_date, AVG(metric_value) AS daily_average"));
        assertTrue(sql.contains("GROUP BY aggregation_date"));
        assertTrue(sql.contains(
                "SUM(CASE WHEN metric_value > daily_average THEN 1 ELSE 0 END)\n         AS days_above_province_average"));
        assertTrue(sql.contains("COUNT(metric_value) AS observation_count"));
        assertTrue(sql.contains("AS above_ratio_percent"));
        // 目标机构过滤必须发生在每日省均之后,而不是在每日聚合之前。
        assertTrue(sql.indexOf("WHERE bank_organization = 'ORG004'") > sql
                .indexOf("GROUP BY aggregation_date"));
        // 不得先按机构跨期 SUM 后比较:机构过滤绝不能出现在逐日聚合 CTE 之前。
        assertFalse(sql.contains("FROM 银行指标数据集\n  WHERE bank_organization = 'ORG004'"));
        assertEquals(List.of("bank_organization", "days_above_province_average",
                "observation_count", "above_ratio_percent"), compiled.getOutputColumns());
        assertEquals(BankResultProjector.ProjectionType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE,
                compiled.getResultContract().getType());
        assertEquals("ZB001",
                compiled.getResultContract().getMetrics().get(0).getMetricCode());
        assertEquals(List.of("ORG004"),
                compiled.getResultContract().getSelectedOrganizationCodes());
    }

    @Test
    void shouldRejectDaysAboveProvinceAverageWithoutAnOrganization() {
        BankQueryPlan plan = daysAboveProvinceAveragePlan();
        plan.setOrganizations(List.of());

        BankPlanCompilationException exception = assertThrows(BankPlanCompilationException.class,
                () -> compiler.compile(plan, daysAboveProvinceAverageHints(), schema()));

        assertEquals(BankPlanCompilationException.Reason.INVALID_PLAN, exception.getReason());
        assertTrue(exception.getMessage()
                .contains("DAYS_ABOVE_PROVINCE_AVERAGE_SINGLE_ORGANIZATION_REQUIRED"));
    }

    @Test
    void shouldRejectDaysAboveProvinceAverageWithoutTheProvinceAverageBenchmark() {
        BankQueryPlan plan = daysAboveProvinceAveragePlan();
        plan.setFilters(List.of());

        BankPlanCompilationException exception = assertThrows(BankPlanCompilationException.class,
                () -> compiler.compile(plan, daysAboveProvinceAverageHints(), schema()));

        assertEquals(BankPlanCompilationException.Reason.INVALID_PLAN, exception.getReason());
        assertTrue(exception.getMessage()
                .contains("DAYS_ABOVE_PROVINCE_AVERAGE_BENCHMARK_REQUIRED"));
    }

    @Test
    void shouldRejectDaysAboveProvinceAverageWithTheWrongIntent() {
        BankQueryPlan plan = daysAboveProvinceAveragePlan();
        plan.setIntent(BankIntentType.THRESHOLD);

        BankPlanCompilationException exception = assertThrows(BankPlanCompilationException.class,
                () -> compiler.compile(plan, daysAboveProvinceAverageHints(), schema()));

        assertEquals(BankPlanCompilationException.Reason.INVALID_PLAN, exception.getReason());
        assertTrue(exception.getMessage()
                .contains("DAYS_ABOVE_PROVINCE_AVERAGE_INTENT_REQUIRED"));
    }

    @Test
    void shouldRejectDaysAboveProvinceAverageWithTheWrongDimension() {
        BankQueryPlan plan = daysAboveProvinceAveragePlan();
        plan.setDimensions(List.of("bank_data_date"));
        plan.getOutput().setColumns(List.of("bank_data_date", "ZB001"));

        BankPlanCompilationException exception = assertThrows(BankPlanCompilationException.class,
                () -> compiler.compile(plan, daysAboveProvinceAverageHints(), schema()));

        assertEquals(BankPlanCompilationException.Reason.INVALID_PLAN, exception.getReason());
        assertTrue(exception.getMessage()
                .contains("DAYS_ABOVE_PROVINCE_AVERAGE_ORGANIZATION_DIMENSION_REQUIRED"));
    }

    @Test
    void shouldRejectDaysAboveProvinceAverageCarryingAnAbsoluteMetricThreshold() {
        BankQueryPlan plan = daysAboveProvinceAveragePlan();
        plan.setFilters(List.of(provinceAverageBenchmark(),
                BankQueryPlan.Filter.builder().field("metric_value").operator("GT").value("100")
                        .build()));

        BankPlanCompilationException exception = assertThrows(BankPlanCompilationException.class,
                () -> compiler.compile(plan, daysAboveProvinceAverageHints(), schema()));

        assertEquals(BankPlanCompilationException.Reason.INVALID_PLAN, exception.getReason());
        assertTrue(exception.getMessage()
                .contains("DAYS_ABOVE_PROVINCE_AVERAGE_METRIC_FILTER_FORBIDDEN"));
    }

    private BankQueryPlan rankingPlan() {
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.RANKING).metrics(List.of(metric("ZB001")))
                .dimensions(List.of("bank_organization"))
                .organizations(List.of(organization("ORG004")))
                .time(time(BankQueryPlan.TimeComparison.NONE, null, null))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(List.of(BankQueryPlan.OrderBy.builder().field("ZB001")
                        .direction(BankQueryPlan.SortDirection.DESC).build()))
                .limit(3)
                .output(BankQueryPlan.Output.builder()
                        .columns(List.of("bank_organization", "ZB001")).orderSensitive(true)
                        .build())
                .build();
    }

    private BankQueryPlan changePlan() {
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.CHANGE).metrics(List.of(metric("ZB001")))
                .dimensions(List.of()).organizations(List.of(organization("ORG004")))
                .time(time(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                        LocalDate.of(2026, 2, 28), LocalDate.of(2026, 2, 28)))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.CHANGE).build())
                .orderBy(List.of()).output(BankQueryPlan.Output.builder().columns(List.of("ZB001"))
                        .orderSensitive(true).build())
                .build();
    }

    private BankQueryPlan ratioPlan() {
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.RATIO).metrics(List.of(metric("ZB001"), metric("ZB002")))
                .dimensions(List.of()).organizations(List.of())
                .time(time(BankQueryPlan.TimeComparison.NONE, null, null))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.RATIO).baseline("ZB002").build())
                .orderBy(List.of()).output(BankQueryPlan.Output.builder()
                        .columns(List.of("ZB001", "ZB002")).orderSensitive(true).build())
                .build();
    }

    private BankQueryPlan thresholdPlan() {
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.THRESHOLD).metrics(List.of(metric("ZB001")))
                .dimensions(List.of()).organizations(List.of())
                .time(time(BankQueryPlan.TimeComparison.NONE, null, null))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(List.of()).output(BankQueryPlan.Output.builder().columns(List.of("ZB001"))
                        .orderSensitive(true).build())
                .build();
    }

    private BankQueryPlan.Metric metric(String bizName) {
        return BankQueryPlan.Metric.builder().bizName(bizName)
                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build();
    }

    private BankQueryPlan.Organization organization(String code) {
        return BankQueryPlan.Organization.builder().code(code).build();
    }

    private BankQueryPlan.TimeRange time(BankQueryPlan.TimeComparison comparison,
            LocalDate baselineStart, LocalDate baselineEnd) {
        return BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2026, 3, 31))
                .endDate(LocalDate.of(2026, 3, 31)).granularity(BankQueryPlan.TimeGranularity.DAY)
                .comparison(comparison).baselineStartDate(baselineStart)
                .baselineEndDate(baselineEnd).build();
    }

    private SemanticIntentHints rankingHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(3).maxLimit(100).build();
    }

    private SemanticIntentHints annualAverageRankingHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredStartDate(LocalDate.of(2025, 1, 1))
                .requiredEndDate(LocalDate.of(2025, 12, 31)).requiredLimit(6).maxLimit(100).build();
    }

    private SemanticIntentHints changeHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.CHANGE)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();
    }

    private SemanticIntentHints groupedChangeHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.CHANGE)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();
    }

    private SemanticIntentHints ratioHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RATIO)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001", "ZB002"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();
    }

    private SemanticIntentHints thresholdHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.THRESHOLD)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();
    }

    private SemanticIntentHints provinceAverageThresholdHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.THRESHOLD)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31))
                .requiredFilters(List.of(new SemanticIntentHints.RequiredFilter("benchmark",
                        "COMPARE", "PROVINCE_AVERAGE")))
                .maxLimit(100).build();
    }

    private SemanticIntentHints provinceAverageAggregationHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.AGGREGATION)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31))
                .requiredFilters(List.of(new SemanticIntentHints.RequiredFilter("benchmark",
                        "COMPARE", "PROVINCE_AVERAGE")))
                .maxLimit(100).build();
    }

    private SemanticIntentHints annualDailyAggregationHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.AGGREGATION)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2025, 1, 1))
                .requiredEndDate(LocalDate.of(2025, 12, 31)).maxLimit(100).build();
    }

    private BankQueryPlan.Filter provinceAverageBenchmark() {
        return BankQueryPlan.Filter.builder().field("benchmark").operator("COMPARE")
                .value("PROVINCE_AVERAGE").build();
    }

    private BankQueryPlan daysAboveProvinceAveragePlan() {
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.AGGREGATION)
                .metrics(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()))
                .dimensions(List.of("bank_organization"))
                .organizations(List.of(organization("ORG004")))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 1, 1))
                        .endDate(LocalDate.of(2025, 12, 31))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .filters(List.of(provinceAverageBenchmark()))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE)
                        .build())
                .orderBy(List.of())
                .limit(null)
                .output(BankQueryPlan.Output.builder()
                        .columns(List.of("bank_organization", "ZB001")).orderSensitive(true)
                        .build())
                .build();
    }

    private SemanticIntentHints daysAboveProvinceAverageHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.AGGREGATION)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2025, 1, 1))
                .requiredEndDate(LocalDate.of(2025, 12, 31))
                .requiredFilters(List.of(new SemanticIntentHints.RequiredFilter("benchmark",
                        "COMPARE", "PROVINCE_AVERAGE")))
                .maxLimit(100).build();
    }

    private LLMReq.LLMSchema schema() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(12L);
        schema.setDataSetName("银行指标数据集");
        schema.setMetrics(List.of(
                SchemaElement.builder().name("各项存款余额").bizName("ZB001").defaultAgg("SUM").build(),
                SchemaElement.builder().name("各项贷款余额").bizName("ZB002").defaultAgg("SUM").build()));
        schema.setDimensions(
                List.of(SchemaElement.builder().name("机构").bizName("bank_organization").build()));
        schema.setPartitionTime(
                SchemaElement.builder().name("数据日期").bizName("bank_data_date").build());
        return schema;
    }
}
