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
            return prompt.contains("ZB001") && prompt.contains("\"bizName\"")
                    && prompt.contains("\"aggregation\"") && prompt.contains("意图必须精确填写：RANKING")
                    && prompt.contains("必填过滤条件（必须原样填写）：[]")
                    && prompt.contains("可填写值目录（只能从下列内容中选择）：")
                    && prompt.contains("/metrics/*/bizName: [ZB001]")
                    && prompt.contains("\"output\":{\"columns\":[\"bank_organization\",\"ZB001\"]")
                    && prompt.contains("/dimensions, /output/columns: [");
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
                        && repairPrompt.contains("INTENT_REQUIRED")
                        && repairPrompt.contains("/intent 必须精确填写：RANKING")
                        && repairPrompt.contains("/time/startDate 必须精确填写：2026-03-31")));
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
                .contains("RATIO 的 metrics 第一个指标是分子")
                && prompt.contains("/calculation/baseline: [ZB002]")
                && prompt.contains(
                        "\"metrics\":[{\"bizName\":\"ZB005\",\"aggregation\":\"DEFAULT\"},{\"bizName\":\"ZB002\",\"aggregation\":\"DEFAULT\"}]")
                && prompt.contains("\"calculation\":{\"type\":\"RATIO\",\"baseline\":\"ZB002\"}")));
    }

    @Test
    void shouldProvideAnAbsoluteStartOfYearBaselineForChangePlans() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validChangePlanJson());
        BankPlanGenStrategy strategy = new TestBankPlanGenStrategy(model);

        LLMResp response = strategy.generate(changeRequest());

        assertEquals(BankQueryPlan.TimeComparison.START_OF_YEAR,
                response.getBankQueryPlan().getTime().getComparison());
        assertEquals(LocalDate.of(2024, 12, 31),
                response.getBankQueryPlan().getTime().getBaselineStartDate());
        assertEquals(LocalDate.of(2024, 12, 31),
                response.getBankQueryPlan().getTime().getBaselineEndDate());
        verify(model).generate(org.mockito.ArgumentMatchers
                .<String>argThat(prompt -> prompt.contains("\"comparison\":\"START_OF_YEAR\"")
                        && prompt.contains("\"baselineStartDate\":\"2024-12-31\"")
                        && prompt.contains("\"baselineEndDate\":\"2024-12-31\"")));
    }

    @Test
    void shouldNormalizeACombinedMonthAndYearQuestionToTheDualBaselinePlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validChangePlanJson()
                .replace("\"dimensions\":[]", "\"dimensions\":[\"bank_organization\"]"));
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
                .<String>argThat(prompt -> prompt.contains("\"dimensions\":[\"bank_data_date\"]")
                        && prompt.contains("\"granularity\":\"QUARTER\"") && prompt.contains(
                                "\"output\":{\"columns\":[\"bank_data_date\",\"ZB001\"]")));
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
