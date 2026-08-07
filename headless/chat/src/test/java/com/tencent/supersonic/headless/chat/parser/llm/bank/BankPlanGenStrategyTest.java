package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankPlanGenStrategyTest {

    @Test
    void shouldGenerateValidatedPlanFromRawModelJsonWithoutExposingPhysicalSchema() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);
        LLMReq request = request();

        LLMResp response = strategy.generate(request);

        assertNotNull(response.getBankQueryPlan());
        assertEquals(BankIntentType.RANKING, response.getBankQueryPlan().getIntent());
        assertEquals("json_object", request.getChatAppConfig().get(BankPlanGenStrategy.APP_KEY)
                .getChatModelConfig().getJsonFormatType());
        assertEquals(0, request.getChatAppConfig().get(BankPlanGenStrategy.APP_KEY)
                .getChatModelConfig().getMaxRetries());
        verify(model).generate(org.mockito.ArgumentMatchers.<String>argThat(prompt -> {
            assertFalse(prompt.contains("bank_daily_metrics"));
            assertFalse(prompt.toUpperCase().contains("SELECT"));
            assertFalse(prompt.contains("FROZEN_TEST_EXEMPLAR"));
            return prompt.contains(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX)
                    && prompt.contains("\"bizName\"")
                    && prompt.contains(request.getQueryText());
        }));
    }

    @Test
    void shouldClassifyModelFailureWithoutFallingBackToUnconstrainedSql() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenThrow(new RuntimeException("connection timeout"));
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        BankNl2SqlError exception =
                assertThrows(BankNl2SqlError.class, () -> strategy.generate(request()));

        assertEquals(BankNl2SqlError.Category.MODEL_FAILURE, exception.getCategory());
        assertFalse(exception.isRetryable());
    }

    @Test
    void shouldRetryOnceWithValidationFeedbackForAnInvalidPlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString()))
                .thenReturn(validPlanJson().replace("\"RANKING\"", "\"UNKNOWN\""), validPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(request());

        assertNotNull(response.getBankQueryPlan());
        verify(model, org.mockito.Mockito.times(2)).generate(anyString());
        verify(model).generate(org.mockito.ArgumentMatchers
                .<String>argThat(repairPrompt -> repairPrompt.contains("\"intent\":\"UNKNOWN\"")
                        && repairPrompt.contains("<previous_candidate>")
                        && repairPrompt.contains(request().getQueryText())));
    }

    @Test
    void shouldMergeEquivalentValidPlansIntoOneSemanticCandidateDiagnostic() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);
        LLMReq request = request();
        request.setBankMaxCandidates(2);

        LLMResp response = strategy.generate(request);

        assertEquals(2, response.getBankCandidateDiagnostics().get("bank.nl2sql.candidateCount"));
        assertEquals(1,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.uniqueCandidateCount"));
        assertEquals(0,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.rejectedCandidateCount"));
        verify(model, org.mockito.Mockito.times(2)).generate(anyString());
    }

    @Test
    void shouldStopAfterOneStructuredRepairWhenThePlanRemainsInvalid() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString()))
                .thenReturn(validPlanJson().replace("\"RANKING\"", "\"UNKNOWN\""));
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        BankNl2SqlError exception =
                assertThrows(BankNl2SqlError.class, () -> strategy.generate(request()));

        assertFalse(exception.isRetryable());
        verify(model, org.mockito.Mockito.times(2)).generate(anyString());
    }

    @Test
    void shouldReuseTheAgentS2SqlModelWhenNoDedicatedBankModelIsConfigured() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);
        ChatModelConfig modelConfig = new ChatModelConfig();
        ChatApp app = ChatApp.builder().chatModelConfig(modelConfig).build();
        LLMReq request = request();
        request.setChatAppConfig(Map.of("S2SQL_PARSER", app));

        LLMResp response = strategy.generate(request);

        assertNotNull(response.getBankQueryPlan());
    }

    @Test
    void shouldProvideTheRatioNumeratorAndDenominatorInTheTemplate() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validRatioPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(ratioRequest());

        assertEquals("ZB002", response.getBankQueryPlan().getCalculation().getBaseline());
        verify(model).generate(org.mockito.ArgumentMatchers.<String>argThat(prompt -> prompt
                .contains(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX)
                && prompt.contains(ratioRequest().getQueryText())));
    }

    @Test
    void shouldProvideAnAbsoluteStartOfYearBaselineForChangePlans() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validChangePlanJson()
                .replace("\"dimensions\":[]", "\"dimensions\":[\"bank_organization\"]")
                .replace("\"output\":{\"columns\":[\"ZB001\"]",
                        "\"output\":{\"columns\":[\"bank_organization\",\"ZB001\"]"));
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(changeRequest());

        assertEquals(List.of(), response.getBankQueryPlan().getDimensions());
        assertEquals(BankQueryPlan.TimeComparison.START_OF_YEAR,
                response.getBankQueryPlan().getTime().getComparison());
        assertEquals(LocalDate.of(2024, 12, 31),
                response.getBankQueryPlan().getTime().getBaselineStartDate());
        assertEquals(LocalDate.of(2024, 12, 31),
                response.getBankQueryPlan().getTime().getBaselineEndDate());
        verify(model).generate(anyString());
    }

    @Test
    void shouldProvideAnExplicitPeriodBaselineForAChangeRange() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validPeriodChangePlanJson()
                .replace("\"dimensions\":[]", "\"dimensions\":[\"bank_organization\"]")
                .replace("\"output\":{\"columns\":[\"ZB001\"]",
                        "\"output\":{\"columns\":[\"bank_organization\",\"ZB001\"]"));
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(periodChangeRequest());

        assertEquals(List.of(), response.getBankQueryPlan().getDimensions());
        assertEquals(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                response.getBankQueryPlan().getTime().getComparison());
        assertEquals(LocalDate.of(2025, 12, 31),
                response.getBankQueryPlan().getTime().getStartDate());
        assertEquals(LocalDate.of(2025, 6, 30),
                response.getBankQueryPlan().getTime().getBaselineStartDate());
        verify(model).generate(anyString());
    }

    @Test
    void shouldNormalizeACombinedMonthAndYearQuestionToTheDualBaselinePlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validChangePlanJson()
                .replace("\"dimensions\":[]", "\"dimensions\":[\"bank_organization\"]")
                .replace("\"output\":{\"columns\":[\"ZB001\"]",
                        "\"output\":{\"columns\":[\"bank_organization\",\"ZB001\"]"));
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);
        LLMReq request = changeRequest();
        request.setQueryText(
                "\u73af\u6bd4\u548c\u540c\u6bd4\u5206\u522b\u53d8\u52a8\u4e86\u591a\u5c11");

        LLMResp response = strategy.generate(request);

        assertEquals(BankQueryPlan.TimeComparison.MOM_AND_YOY,
                response.getBankQueryPlan().getTime().getComparison());
        assertEquals(List.of(), response.getBankQueryPlan().getDimensions());
        assertEquals(null, response.getBankQueryPlan().getTime().getBaselineStartDate());
        assertEquals(null, response.getBankQueryPlan().getTime().getBaselineEndDate());
    }

    @Test
    void shouldNormalizeAnnualAverageTopAndBottomQuestionToBothRankFilters() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validAnnualAverageRankingPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(annualAverageRankingRequest());

        assertEquals(BankQueryPlan.Aggregation.AVG,
                response.getBankQueryPlan().getMetrics().get(0).getAggregation());
        assertEquals(List.of(
                BankQueryPlan.Filter.builder().field("rank").operator("LTE").value("3").build(),
                BankQueryPlan.Filter.builder().field("rank_from_bottom").operator("LTE").value("3")
                        .build()),
                response.getBankQueryPlan().getFilters());
        assertEquals(Integer.valueOf(6), response.getBankQueryPlan().getLimit());
    }

    @Test
    void shouldNormalizeAnAnnualDailyExtremaQuestionToAnAggregationSummaryPlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validAnnualDailySummaryPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(annualDailySummaryRequest());

        assertEquals(BankIntentType.AGGREGATION, response.getBankQueryPlan().getIntent());
        assertEquals(BankQueryPlan.Aggregation.AVG,
                response.getBankQueryPlan().getMetrics().get(0).getAggregation());
        assertEquals(List.of("bank_organization"), response.getBankQueryPlan().getDimensions());
        assertEquals(List.of("bank_organization", "ZB001"),
                response.getBankQueryPlan().getOutput().getColumns());
        assertEquals(List.of(), response.getBankQueryPlan().getOrderBy());
        assertEquals(null, response.getBankQueryPlan().getLimit());
    }

    @Test
    void shouldNormalizeAnAbsoluteThresholdToTheStableTemplatePlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validThresholdPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(thresholdRequest());

        BankQueryPlan plan = response.getBankQueryPlan();
        assertEquals(BankIntentType.THRESHOLD, plan.getIntent());
        assertEquals(List.of("bank_organization"), plan.getDimensions());
        assertEquals(List.of(BankQueryPlan.Filter.builder().field("metric_value").operator("GTE")
                .value("10.5%").build()), plan.getFilters());
        assertEquals(List.of("bank_organization", "ZB016"), plan.getOutput().getColumns());
        assertEquals(BankQueryPlan.TimeComparison.NONE, plan.getTime().getComparison());
        assertEquals(null, plan.getLimit());
    }

    @Test
    void shouldNormalizeASingleOrganizationRatioToTheStableTemplatePlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validRatioPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(ratioRequest());

        BankQueryPlan plan = response.getBankQueryPlan();
        assertEquals(BankIntentType.RATIO, plan.getIntent());
        assertEquals(List.of("ZB005", "ZB002"),
                plan.getMetrics().stream().map(BankQueryPlan.Metric::getBizName).toList());
        assertEquals(List.of(), plan.getDimensions());
        assertEquals("ZB002", plan.getCalculation().getBaseline());
        assertEquals(BankQueryPlan.TimeComparison.NONE, plan.getTime().getComparison());
        assertEquals(null, plan.getLimit());
    }

    @Test
    void shouldProvideTheDateDimensionAndQuarterGranularityForTrendPlans() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validTrendPlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(trendRequest());

        assertEquals(BankIntentType.TREND, response.getBankQueryPlan().getIntent());
        assertEquals(List.of("bank_data_date"), response.getBankQueryPlan().getDimensions());
        assertEquals(BankQueryPlan.TimeGranularity.QUARTER,
                response.getBankQueryPlan().getTime().getGranularity());
        verify(model).generate(org.mockito.ArgumentMatchers
                .<String>argThat(prompt -> prompt.contains(trendRequest().getQueryText())
                        && prompt.contains(BankPlanPromptComposer.FIXED_SYSTEM_PREFIX)));
    }

    @Test
    void shouldNormalizeACompleteReversedOutputToCanonicalPlanOrder() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validPlanJson()
                .replace("\"output\":{\"columns\":[\"bank_organization\",\"ZB001\"]",
                        "\"output\":{\"columns\":[\"ZB001\",\"bank_organization\"]"));
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(request());

        assertEquals(List.of("bank_organization", "ZB001"),
                response.getBankQueryPlan().getOutput().getColumns());
    }

    @Test
    void shouldBuildDeterministicAnnualDailyAveragePlanWithoutModelOrAppConfig() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(annualDailyAverageRequest());

        BankQueryPlan plan = response.getBankQueryPlan();
        assertEquals(BankIntentType.AGGREGATION, plan.getIntent());
        assertEquals(BankQueryPlan.Aggregation.AVG,
                plan.getMetrics().get(0).getAggregation());
        assertEquals(List.of("bank_organization"), plan.getDimensions());
        assertEquals(List.of("ORG010"), plan.getOrganizations().stream()
                .map(BankQueryPlan.Organization::getCode).toList());
        assertEquals(LocalDate.of(2025, 1, 1), plan.getTime().getStartDate());
        assertEquals(LocalDate.of(2025, 12, 31), plan.getTime().getEndDate());
        assertEquals(BankQueryPlan.TimeGranularity.DAY, plan.getTime().getGranularity());
        assertEquals(BankQueryPlan.TimeComparison.NONE, plan.getTime().getComparison());
        assertEquals(BankQueryPlan.CalculationType.DIRECT, plan.getCalculation().getType());
        assertEquals(List.of("bank_organization", "ZB001"), plan.getOutput().getColumns());
        assertEquals(List.of(), plan.getOrderBy());
        assertEquals(null, plan.getLimit());
        verify(model, org.mockito.Mockito.never()).generate(anyString());
    }

    @Test
    void shouldBuildDeterministicAnnualAverageTopAndBottomRankingPlanWithoutCallingTheModel() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(annualAverageRankingRequest());

        BankQueryPlan plan = response.getBankQueryPlan();
        assertEquals(BankIntentType.RANKING, plan.getIntent());
        assertEquals(BankQueryPlan.Aggregation.AVG,
                plan.getMetrics().get(0).getAggregation());
        assertEquals(List.of("bank_organization"), plan.getDimensions());
        assertEquals(List.of("bank_organization", "ZB002"), plan.getOutput().getColumns());
        assertEquals(List.of(
                BankQueryPlan.Filter.builder().field("rank").operator("LTE").value("3").build(),
                BankQueryPlan.Filter.builder().field("rank_from_bottom").operator("LTE").value("3")
                        .build()),
                plan.getFilters());
        assertEquals(List.of(BankQueryPlan.OrderBy.builder().field("ZB002")
                .direction(BankQueryPlan.SortDirection.DESC).build()), plan.getOrderBy());
        assertEquals(Integer.valueOf(6), plan.getLimit());
        assertEquals(BankQueryPlan.TimeGranularity.DAY, plan.getTime().getGranularity());
        assertEquals(BankQueryPlan.TimeComparison.NONE, plan.getTime().getComparison());
        assertEquals(BankQueryPlan.CalculationType.DIRECT, plan.getCalculation().getType());
        verify(model, org.mockito.Mockito.never()).generate(anyString());
    }

    @Test
    void shouldBuildChineseTopBottomNplRankingFromQuestionTextEvenWithSparseHints() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);
        LLMReq request = new LLMReq();
        request.setQueryText("2025年全年，不良贷款率的均值排名前三和后三的分别是哪几家？");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        // Sparse mapper evidence: no required metrics/dates — plan must recover from text.
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001", "ZB002", "ZB013"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());
        request.setChatAppConfig(Map.of());

        LLMResp response = strategy.generate(request);

        BankQueryPlan plan = response.getBankQueryPlan();
        assertEquals("ZB013", plan.getMetrics().get(0).getBizName());
        assertEquals(BankQueryPlan.Aggregation.AVG, plan.getMetrics().get(0).getAggregation());
        assertEquals(BankQueryPlan.SortDirection.ASC, plan.getOrderBy().get(0).getDirection());
        assertEquals(LocalDate.of(2025, 1, 1), plan.getTime().getStartDate());
        assertEquals(LocalDate.of(2025, 12, 31), plan.getTime().getEndDate());
        assertEquals(Integer.valueOf(6), plan.getLimit());
        verify(model, org.mockito.Mockito.never()).generate(anyString());
    }

    @Test
    void shouldBuildDeterministicAnnualDailyExtremaSummaryPlanBeforeModelCandidates() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(annualDailySummaryRequest());

        BankQueryPlan plan = response.getBankQueryPlan();
        assertEquals(BankIntentType.AGGREGATION, plan.getIntent());
        assertEquals(BankQueryPlan.Aggregation.AVG,
                plan.getMetrics().get(0).getAggregation());
        assertEquals(List.of("bank_organization"), plan.getDimensions());
        assertEquals(List.of("ORG010"), plan.getOrganizations().stream()
                .map(BankQueryPlan.Organization::getCode).toList());
        assertEquals(LocalDate.of(2025, 1, 1), plan.getTime().getStartDate());
        assertEquals(LocalDate.of(2025, 12, 31), plan.getTime().getEndDate());
        assertEquals(BankQueryPlan.TimeGranularity.DAY, plan.getTime().getGranularity());
        assertEquals(BankQueryPlan.TimeComparison.NONE, plan.getTime().getComparison());
        assertEquals(BankQueryPlan.CalculationType.DIRECT, plan.getCalculation().getType());
        assertEquals(List.of("bank_organization", "ZB001"), plan.getOutput().getColumns());
        assertEquals(List.of(), plan.getOrderBy());
        assertEquals(null, plan.getLimit());
        verify(model, org.mockito.Mockito.never()).generate(anyString());
    }

    @Test
    void shouldBuildDeterministicDaysAboveProvinceAveragePlanWithoutCallingTheModel() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(daysAboveProvinceAverageRequest());

        BankQueryPlan plan = response.getBankQueryPlan();
        assertEquals(BankIntentType.AGGREGATION, plan.getIntent());
        assertEquals(BankQueryPlan.CalculationType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE,
                plan.getCalculation().getType());
        assertEquals(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()), plan.getMetrics());
        assertEquals(List.of("bank_organization"), plan.getDimensions());
        assertEquals(List.of("ORG010"), plan.getOrganizations().stream()
                .map(BankQueryPlan.Organization::getCode).toList());
        assertEquals(List.of(BankQueryPlan.Filter.builder().field("benchmark")
                .operator("COMPARE").value("PROVINCE_AVERAGE").build()), plan.getFilters());
        assertEquals(LocalDate.of(2025, 1, 1), plan.getTime().getStartDate());
        assertEquals(LocalDate.of(2025, 12, 31), plan.getTime().getEndDate());
        assertEquals(BankQueryPlan.TimeGranularity.DAY, plan.getTime().getGranularity());
        assertEquals(BankQueryPlan.TimeComparison.NONE, plan.getTime().getComparison());
        assertEquals(List.of("bank_organization", "ZB001"), plan.getOutput().getColumns());
        assertEquals(List.of(), plan.getOrderBy());
        assertEquals(null, plan.getLimit());
        verify(model, org.mockito.Mockito.never()).generate(anyString());
    }

    @Test
    void shouldBuildDeterministicDerivedMetricRankingPlanWithoutCallingTheModel() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(derivedRankingRequest());

        BankQueryPlan plan = response.getBankQueryPlan();
        assertEquals(BankQueryPlan.PlanAction.EXECUTE, plan.getAction());
        assertEquals(BankIntentType.RANKING, plan.getIntent());
        assertEquals(List.of("ZB001", "ZB002"),
                plan.getMetrics().stream().map(BankQueryPlan.Metric::getBizName).toList());
        assertEquals(List.of(BankQueryPlan.DerivedMetric.builder()
                .metricCode("DERIVED_ZB002_DIV_ZB001").numerator("ZB002").denominator("ZB001")
                .name("存贷比").build()), plan.getDerivedMetrics());
        assertEquals(List.of("bank_organization"), plan.getDimensions());
        assertEquals(List.of("ORG004"),
                plan.getOrganizations().stream().map(BankQueryPlan.Organization::getCode).toList());
        assertEquals(LocalDate.of(2026, 3, 31), plan.getTime().getStartDate());
        assertEquals(LocalDate.of(2026, 3, 31), plan.getTime().getEndDate());
        assertEquals(BankQueryPlan.TimeGranularity.DAY, plan.getTime().getGranularity());
        assertEquals(BankQueryPlan.TimeComparison.NONE, plan.getTime().getComparison());
        assertEquals(BankQueryPlan.CalculationType.DIRECT, plan.getCalculation().getType());
        assertEquals(List.of(BankQueryPlan.OrderBy.builder().field("ZB001")
                .direction(BankQueryPlan.SortDirection.DESC).build()), plan.getOrderBy());
        assertEquals(null, plan.getLimit());
        assertEquals(List.of("bank_organization", "ZB001", "ZB002"),
                plan.getOutput().getColumns());
        verify(model, org.mockito.Mockito.never()).generate(anyString());
    }

    @Test
    void shouldKeepTheModelPathWhenDerivedHintsCarryMoreThanOneOrganization() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validDerivedPlanJson().replace(
                "\"organizations\":[{\"code\":\"ORG004\"}]",
                "\"organizations\":[{\"code\":\"ORG004\"},{\"code\":\"ORG005\"}]"));
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);
        LLMReq request = derivedRankingRequest();
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(new LinkedHashSet<>(List.of("ZB001", "ZB002")))
                .requiredOrganizationCodes(Set.of("ORG004", "ORG005"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31))
                .requiredDerivedMetrics(List.of(new SemanticIntentHints.DerivedMetricSpec(
                        "DERIVED_ZB002_DIV_ZB001", "ZB002", "ZB001", "存贷比")))
                .build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY,
                ChatApp.builder().chatModelConfig(new ChatModelConfig()).build()));

        LLMResp response = strategy.generate(request);

        assertNotNull(response.getBankQueryPlan());
        assertEquals(List.of("ORG004", "ORG005"), response.getBankQueryPlan().getOrganizations()
                .stream().map(BankQueryPlan.Organization::getCode).toList());
        verify(model, org.mockito.Mockito.times(1)).generate(anyString());
    }

    @Test
    void shouldUseThePreviousNaturalQuarterEndAsBaselineForLastQuarterEndChange() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validChangePlanJson()
                .replace("\"time\":{\"startDate\":\"2025-01-01\",\"endDate\":\"2025-04-30\",\"granularity\":\"DAY\",\"comparison\":\"START_OF_YEAR\",\"baselineStartDate\":\"2024-12-31\",\"baselineEndDate\":\"2024-12-31\"}",
                        "\"time\":{\"startDate\":\"2025-12-31\",\"endDate\":\"2025-12-31\",\"granularity\":\"DAY\",\"comparison\":\"START_OF_YEAR\",\"baselineStartDate\":\"2024-12-31\",\"baselineEndDate\":\"2024-12-31\"}"));
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);
        LLMReq request = changeRequest();
        request.setQueryText("2025年12月末各项存款余额较上季度末变化了多少？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.CHANGE).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG003"))
                .requiredStartDate(LocalDate.of(2025, 12, 31))
                .requiredEndDate(LocalDate.of(2025, 12, 31)).build());

        LLMResp response = strategy.generate(request);

        BankQueryPlan.TimeRange time = response.getBankQueryPlan().getTime();
        assertEquals(LocalDate.of(2025, 12, 31), time.getStartDate());
        assertEquals(LocalDate.of(2025, 12, 31), time.getEndDate());
        assertEquals(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD, time.getComparison());
        assertEquals(LocalDate.of(2025, 9, 30), time.getBaselineStartDate());
        assertEquals(LocalDate.of(2025, 9, 30), time.getBaselineEndDate());
        verify(model).generate(anyString());
    }

    @Test
    void shouldRejectAnAllowedButUnselectedOutputFieldInsteadOfSilentlyAlteringIt() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validTrendPlanJson()
                .replace("\"output\":{\"columns\":[\"bank_data_date\",\"ZB001\"]",
                        "\"output\":{\"columns\":[\"bank_data_date\",\"bank_organization\","
                                + "\"ZB001\"]"));
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        BankNl2SqlError exception = assertThrows(BankNl2SqlError.class,
                () -> strategy.generate(trendRequest()));

        assertEquals(BankNl2SqlError.Category.VALIDATION_FAILED, exception.getCategory());
        assertFalse(exception.isRetryable());
        verify(model, org.mockito.Mockito.times(2)).generate(anyString());
    }

    @Test
    void shouldBuildDeterministicPointQueryWithoutCallingModel() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);
        LLMReq request = new LLMReq();
        request.setQueryText("江苏省A市农商行在2025年6月15日，各项存款余额是多少？");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.POINT_QUERY).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG001"))
                .requiredStartDate(LocalDate.of(2025, 6, 15))
                .requiredEndDate(LocalDate.of(2025, 6, 15)).build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY,
                ChatApp.builder().chatModelConfig(new ChatModelConfig()).build()));

        LLMResp response = strategy.generate(request);

        assertNotNull(response.getBankQueryPlan());
        assertEquals(BankIntentType.POINT_QUERY, response.getBankQueryPlan().getIntent());
        assertEquals("ZB001", response.getBankQueryPlan().getMetrics().get(0).getBizName());
        assertEquals(List.of("ZB001"), response.getBankQueryPlan().getOutput().getColumns());
        assertEquals("ORG001", response.getBankQueryPlan().getOrganizations().get(0).getCode());
        verify(model, org.mockito.Mockito.never()).generate(anyString());
    }

    private LLMReq request() {
        ChatModelConfig modelConfig = new ChatModelConfig();
        ChatApp app = ChatApp.builder().chatModelConfig(modelConfig).build();
        LLMReq request = new LLMReq();
        request.setQueryText("查询江苏省D市农商行各项存款余额前3名");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setDynamicExemplars(List.of(Text2SQLExemplar.builder()
                .question("FROZEN_TEST_EXEMPLAR").sql("forbidden").build()));
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_data_date", "bank_organization"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(3).build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY, app));
        return request;
    }

    private String validPlanJson() {
        return """
                {"version":"1.0","intent":"RANKING",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT"}],
                "dimensions":["bank_organization"],"organizations":[{"code":"ORG004"}],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"NONE"},
                "filters":[],"calculation":{"type":"DIRECT"},
                "orderBy":[{"field":"ZB001","direction":"DESC"}],"limit":3,
                "output":{"columns":["bank_organization","ZB001"],"orderSensitive":true}}
                """;
    }

    private LLMReq ratioRequest() {
        ChatModelConfig modelConfig = new ChatModelConfig();
        ChatApp app = ChatApp.builder().chatModelConfig(modelConfig).build();
        LLMReq request = new LLMReq();
        request.setQueryText("查询机构的对公贷款占各项贷款比例");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RATIO).allowedMetrics(Set.of("ZB005", "ZB002"))
                .allowedDimensions(Set.of("bank_organization"))
                .requiredMetrics(new LinkedHashSet<>(List.of("ZB005", "ZB002")))
                .requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY, app));
        return request;
    }

    private String validRatioPlanJson() {
        return """
                {"version":"1.0","intent":"RATIO",
                "metrics":[{"bizName":"ZB005","aggregation":"DEFAULT"},{"bizName":"ZB002","aggregation":"DEFAULT"}],
                "dimensions":[],"organizations":[{"code":"ORG004"}],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"NONE"},
                "filters":[],"calculation":{"type":"RATIO","baseline":"ZB002"},
                "orderBy":[],"limit":null,
                "output":{"columns":["ZB005","ZB002"],"orderSensitive":true}}
                """;
    }

    private LLMReq changeRequest() {
        ChatModelConfig modelConfig = new ChatModelConfig();
        ChatApp app = ChatApp.builder().chatModelConfig(modelConfig).build();
        LLMReq request = new LLMReq();
        request.setQueryText("change from year start");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.CHANGE).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG003"))
                .requiredStartDate(LocalDate.of(2025, 1, 1))
                .requiredEndDate(LocalDate.of(2025, 4, 30)).build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY, app));
        return request;
    }

    private String validChangePlanJson() {
        return """
                {"version":"1.0","intent":"CHANGE",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT"}],
                "dimensions":[],"organizations":[{"code":"ORG003"}],
                "time":{"startDate":"2025-01-01","endDate":"2025-04-30","granularity":"DAY","comparison":"START_OF_YEAR","baselineStartDate":"2024-12-31","baselineEndDate":"2024-12-31"},
                "filters":[],"calculation":{"type":"CHANGE"},
                "orderBy":[],"limit":null,
                "output":{"columns":["ZB001"],"orderSensitive":true}}
                """;
    }

    private LLMReq periodChangeRequest() {
        LLMReq request = changeRequest();
        request.setQueryText("from half year end to year end");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.CHANGE).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG003"))
                .requiredStartDate(LocalDate.of(2025, 6, 30))
                .requiredEndDate(LocalDate.of(2025, 12, 31)).build());
        return request;
    }

    private String validPeriodChangePlanJson() {
        return """
                {"version":"1.0","intent":"CHANGE",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT"}],
                "dimensions":[],"organizations":[{"code":"ORG003"}],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"PERIOD_OVER_PERIOD","baselineStartDate":"2025-06-30","baselineEndDate":"2025-06-30"},
                "filters":[],"calculation":{"type":"CHANGE"},
                "orderBy":[],"limit":null,
                "output":{"columns":["ZB001"],"orderSensitive":true}}
                """;
    }

    private LLMReq annualAverageRankingRequest() {
        ChatModelConfig modelConfig = new ChatModelConfig();
        ChatApp app = ChatApp.builder().chatModelConfig(modelConfig).build();
        LLMReq request = new LLMReq();
        request.setQueryText(
                "2025\u5e74\u5168\u5e74\u5404\u9879\u8d37\u6b3e\u4f59\u989d\u5747\u503c\u6392\u540d\u524d3\u548c\u540e3\u7684\u519c\u5546\u884c\uff1f");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING).allowedMetrics(Set.of("ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB002")).requiredStartDate(LocalDate.of(2025, 1, 1))
                .requiredEndDate(LocalDate.of(2025, 12, 31)).requiredLimit(6).build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY, app));
        return request;
    }

    private String validAnnualAverageRankingPlanJson() {
        return """
                {"version":"1.0","intent":"RANKING",
                "metrics":[{"bizName":"ZB002","aggregation":"AVG"}],
                "dimensions":["bank_organization"],"organizations":[],
                "time":{"startDate":"2025-01-01","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE"},
                "filters":[],"calculation":{"type":"DIRECT"},
                "orderBy":[{"field":"ZB002","direction":"DESC"}],"limit":6,
                "output":{"columns":["bank_organization","ZB002"],"orderSensitive":true}}
                """;
    }

    private LLMReq annualDailyAverageRequest() {
        LLMReq request = new LLMReq();
        request.setQueryText("江苏省J市农商行2025年全年各项存款余额日均是多少？");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.AGGREGATION).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG010"))
                .requiredStartDate(LocalDate.of(2025, 1, 1))
                .requiredEndDate(LocalDate.of(2025, 12, 31)).build());
        // 故意不配置任何 chatApp,证明确定性路径不依赖模型配置。
        request.setChatAppConfig(Map.of());
        return request;
    }

    private LLMReq annualDailySummaryRequest() {
        ChatModelConfig modelConfig = new ChatModelConfig();
        ChatApp app = ChatApp.builder().chatModelConfig(modelConfig).build();
        LLMReq request = new LLMReq();
        request.setQueryText("江苏省J市农商行2025年全年的各项存款余额日均值是多少？最高日和最低日分别出现在什么水平？");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.AGGREGATION).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG010"))
                .requiredStartDate(LocalDate.of(2025, 1, 1))
                .requiredEndDate(LocalDate.of(2025, 12, 31)).build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY, app));
        return request;
    }

    private LLMReq daysAboveProvinceAverageRequest() {
        LLMReq request = new LLMReq();
        request.setQueryText("江苏省J市农商行2025年全年各项存款余额有多少天高于全省均值？");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.AGGREGATION).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG010"))
                .requiredStartDate(LocalDate.of(2025, 1, 1))
                .requiredEndDate(LocalDate.of(2025, 12, 31))
                .requiredFilters(List.of(new SemanticIntentHints.RequiredFilter("benchmark",
                        "COMPARE", "PROVINCE_AVERAGE")))
                .build());
        // 故意不配置任何 chatApp,证明确定性路径不依赖模型配置。
        request.setChatAppConfig(Map.of());
        return request;
    }

    private LLMReq derivedRankingRequest() {
        LLMReq request = new LLMReq();
        request.setQueryText("2026年3月末各农商行存贷比排名如何？");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.RANKING).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(new LinkedHashSet<>(List.of("ZB001", "ZB002")))
                .requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31))
                .requiredDerivedMetrics(List.of(new SemanticIntentHints.DerivedMetricSpec(
                        "DERIVED_ZB002_DIV_ZB001", "ZB002", "ZB001", "存贷比")))
                .build());
        // 故意不配置任何 chatApp,证明确定性路径不依赖模型配置。
        request.setChatAppConfig(Map.of());
        return request;
    }

    private String validDerivedPlanJson() {
        return """
                {"version":"1.0","intent":"RANKING",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT"},{"bizName":"ZB002","aggregation":"DEFAULT"}],
                "dimensions":["bank_organization"],"organizations":[{"code":"ORG004"}],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"NONE"},
                "filters":[],"calculation":{"type":"DIRECT"},
                "orderBy":[{"field":"ZB001","direction":"DESC"}],"limit":null,
                "derivedMetrics":[{"metricCode":"DERIVED_ZB002_DIV_ZB001","numerator":"ZB002","denominator":"ZB001","name":"存贷比"}],
                "output":{"columns":["bank_organization","ZB001","ZB002"],"orderSensitive":true}}
                """;
    }

    private String validAnnualDailySummaryPlanJson() {
        return """
                {"version":"1.0","intent":"AGGREGATION",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT"}],
                "dimensions":[],"organizations":[{"code":"ORG010"}],
                "time":{"startDate":"2025-01-01","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE"},
                "filters":[],"calculation":{"type":"DIRECT"},
                "orderBy":[],"limit":null,
                "output":{"columns":["ZB001"],"orderSensitive":true}}
                """;
    }

    private LLMReq thresholdRequest() {
        ChatModelConfig modelConfig = new ChatModelConfig();
        ChatApp app = ChatApp.builder().chatModelConfig(modelConfig).build();
        LLMReq request = new LLMReq();
        request.setQueryText("capital adequacy ratio meets the minimum requirement");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.THRESHOLD).allowedMetrics(Set.of("ZB016"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB016")).requiredOrganizationCodes(Set.of("ORG008"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31))
                .requiredFilters(List
                        .of(new SemanticIntentHints.RequiredFilter("metric_value", "GTE", "10.5%")))
                .build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY, app));
        return request;
    }

    private String validThresholdPlanJson() {
        return """
                {"version":"1.0","intent":"THRESHOLD",
                "metrics":[{"bizName":"ZB016","aggregation":"DEFAULT"}],
                "dimensions":["bank_data_date","bank_organization"],"organizations":[{"code":"ORG008"}],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"NONE"},
                "filters":[{"field":"metric_value","operator":"GTE","value":"10.5%"}],"calculation":{"type":"DIRECT"},
                "orderBy":[],"limit":null,
                "output":{"columns":["bank_data_date","bank_organization","ZB016"],"orderSensitive":true}}
                """;
    }

    private LLMReq trendRequest() {
        ChatModelConfig modelConfig = new ChatModelConfig();
        ChatApp app = ChatApp.builder().chatModelConfig(modelConfig).build();
        LLMReq request = new LLMReq();
        request.setQueryText("quarterly trend");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.TREND).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2025, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY, app));
        return request;
    }

    private String validTrendPlanJson() {
        return """
                {"version":"1.0","intent":"TREND",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT"}],
                "dimensions":["bank_data_date"],"organizations":[{"code":"ORG004"}],
                "time":{"startDate":"2025-03-31","endDate":"2026-03-31","granularity":"QUARTER","comparison":"NONE"},
                "filters":[],"calculation":{"type":"DIRECT"},
                "orderBy":[],"limit":null,
                "output":{"columns":["bank_data_date","ZB001"],"orderSensitive":true}}
                """;
    }

    private static class TestBankPlanGenStrategy extends BankPlanGenStrategy {
        private final ChatLanguageModel model;

        private TestBankPlanGenStrategy(ChatLanguageModel model) {
            this.model = model;
        }

        @Override
        protected ChatLanguageModel getChatLanguageModel(ChatModelConfig modelConfig) {
            return model;
        }
    }
}
