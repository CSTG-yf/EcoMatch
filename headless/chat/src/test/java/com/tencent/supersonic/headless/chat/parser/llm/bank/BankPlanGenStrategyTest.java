package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankPlanGenStrategyTest {

    @Test
    void modelGeneratesRequirementsThenAnExactPlanWithoutQuestionRuleRewriting() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(requirementsJson(), validPlanJson());

        LLMReq request = request();
        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals("MODEL", response.getBankCandidateDiagnostics().get("bank.nl2sql.planSource"));
        assertEquals(Set.of("ZB001", "ZB002"),
                response.getBankRequestContract().getMetricCodes().stream().collect(
                        java.util.stream.Collectors.toSet()));
        assertEquals(BankIntentType.COMPARISON, response.getBankQueryPlan().getIntent());
        assertEquals("ZB001", response.getBankQueryPlan().getMetrics().get(0).getBizName());
        assertEquals(Set.of("ZB001", "ZB002"), request.getSemanticIntentHints().getRequiredMetrics());
        verify(model, times(2)).generate(anyString());
        assertEquals("json_object", request.getChatAppConfig().get(BankPlanGenStrategy.APP_KEY)
                .getChatModelConfig().getJsonFormatType());
        assertEquals(0, request.getChatAppConfig().get(BankPlanGenStrategy.APP_KEY)
                .getChatModelConfig().getMaxRetries());
    }

    @Test
    void missingMetricIsReturnedToTheModelAsARepairableRequirementsError() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(requirementsJson(),
                validPlanJson().replace("{\"bizName\":\"ZB001\",\"aggregation\":\"DEFAULT\",\"alias\":null},", ""),
                validPlanJson());
        LLMReq request = request();

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertNotNull(response.getBankQueryPlan());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        String repair = prompts.getAllValues().get(2);
        assertTrue(repair.contains("required_metrics_missing: ZB001"));
        assertTrue(repair.contains("<requirements_contract>"));
        assertTrue(repair.contains("<stage>PLAN</stage>"));
        assertFalse(repair.contains("SELECT "));
    }

    @Test
    void invalidRequirementIdentifiersAreRepairedByTheModelInsteadOfCanonicalizedByBackend() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                requirementsJson().replace("\"ZB001\",\"ZB002\"", "\"zb001\",\"ZB002\""),
                requirementsJson(), validPlanJson());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request());

        assertNotNull(response.getBankQueryPlan());
        verify(model, times(3)).generate(anyString());
    }

    @Test
    void transportFailureDoesNotUseADeterministicFallbackPlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenThrow(new RuntimeException("connection timeout"));

        BankNl2SqlError error = assertThrows(BankNl2SqlError.class,
                () -> new TestBankPlanGenStrategy(model).generate(request()));

        assertEquals(BankNl2SqlError.Category.MODEL_FAILURE, error.getCategory());
    }

    @Test
    void toolRepairKeepsModelRequirementsAndReturnsACompleteReplacementPlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validPlanJson());
        LLMReq request = request();
        request.setBankRequestContract(new BankRequestContractResponseParser().parse(requirementsJson(),
                request.getSemanticIntentHints()));
        request.setPreviousBankQueryPlanJson(validPlanJson());
        request.setBankPlanToolResult(BankPlanToolResult.failed(1, "trace-1", "fingerprint-1",
                BankPlanToolResult.Stage.COMPILE, "UNSUPPORTED_PLAN_COMBINATION", Map.of(),
                List.of("根据错误码修正计划")));

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals("MODEL_TOOL_REPAIR",
                response.getBankCandidateDiagnostics().get("bank.nl2sql.planSource"));
        verify(model).generate(org.mockito.ArgumentMatchers.<String>argThat(prompt ->
                prompt.contains("<tool_result>") && prompt.contains("<requirements_contract>")
                        && prompt.contains("UNSUPPORTED_PLAN_COMBINATION")));
    }

    @Test
    void modelCanRequestAUserClarificationInsteadOfGuessingAnIdentifier() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("""
                {"version":"1.0","action":"CLARIFY","intent":null,"metricCodes":[],
                "derivedMetrics":[],"organizationCodes":[],"time":null,"filters":[],
                "requiredLimit":null,"answerFactTypes":[],"clarification":"请明确要查询的具体指标。"}
                """);

        BankNl2SqlError error = assertThrows(BankNl2SqlError.class,
                () -> new TestBankPlanGenStrategy(model).generate(request()));

        assertEquals(BankNl2SqlError.Category.CLARIFICATION_REQUIRED, error.getCategory());
        assertEquals("请明确要查询的具体指标。", error.toParserErrorMessage()
                .replace("[BANK_CONSTRAINED_PLAN]", ""));
    }

    private LLMReq request() {
        LLMReq request = new LLMReq();
        request.setQueryText("2025年7月末，江苏省D市农商行的各项存款余额和各项贷款余额与全省均值相比如何？");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY,
                ChatApp.builder().chatModelConfig(new ChatModelConfig()).build()));
        return request;
    }

    private String requirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"COMPARISON",
                "metricCodes":["ZB001","ZB002"],"derivedMetrics":[],"organizationCodes":["ORG004"],
                "time":{"startDate":"2025-07-31","endDate":"2025-07-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}],
                "requiredLimit":null,"answerFactTypes":["VALUE","PROVINCE_AVERAGE","GAP_VALUE"],"clarification":null}
                """;
    }

    private String validPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"COMPARISON",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null},{"bizName":"ZB002","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG004","bizName":null}],
                "time":{"startDate":"2025-07-31","endDate":"2025-07-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}],
                "calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB001","ZB002"],"orderSensitive":false}}
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
