package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.provider.ModelProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Budget and dual-output contract tests for the controlled free-SQL fallback strategy, using a
 * mocked chat model: whitelist violations get exactly one deterministic repair round, the
 * declared-columns mismatch fails closed without any further model call, and the diagnostics and
 * FREE projection contract carry planSource=FREE_SQL.
 */
class BankFreeSqlFallbackStrategyTest {

    private static final String GOOD_SQL =
            "SELECT bank_organization AS org_code, SUM(ZB001) AS metric_value "
                    + "FROM bank_dataset WHERE bank_data_date = '2026-03-31' "
                    + "GROUP BY bank_organization";

    private static final String WHITELIST_BAD_SQL =
            "SELECT ZB001 AS metric_value FROM other_dataset";

    private final BankFreeSqlFallbackStrategy strategy = new BankFreeSqlFallbackStrategy();

    @Test
    void whitelistViolationGetsOneRepairRoundThenSucceeds() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                dualResponse(WHITELIST_BAD_SQL, List.of("metric_value")),
                dualResponse(GOOD_SQL, List.of("org_code", "metric_value")));

        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                generateWithModel(model, "UNSUPPORTED_QUERY_SHAPE");

        assertNotNull(fallback);
        assertEquals(GOOD_SQL, fallback.getSql());
        assertEquals(List.of("org_code", "metric_value"), fallback.getDeclaredColumns());
        assertEquals(0.86D, fallback.getConfidence());
        assertEquals(2, fallback.getModelAttempts());
        assertEquals("UNSUPPORTED_QUERY_SHAPE", fallback.getTriggerReason());

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(2)).generate(prompts.capture());
        String repairPrompt = prompts.getAllValues().get(1);
        assertTrue(repairPrompt.contains("上一轮输出未通过白名单校验"));
        assertTrue(repairPrompt.contains("other_dataset"));
        assertTrue(repairPrompt.contains("重新只输出一条符合系统规则的 JSON"));
    }

    @Test
    void budgetIsStrictlyOneGenerationPlusOneRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                dualResponse(WHITELIST_BAD_SQL, List.of("metric_value")),
                dualResponse(WHITELIST_BAD_SQL, List.of("metric_value")));

        assertNull(generateWithModel(model, "UNSUPPORTED_CALCULATION"));
        verify(model, times(2)).generate(anyString());
    }

    @Test
    void dualOutputMismatchFailsClosedWithoutAnotherModelCall() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        // SQL emits org_code + metric_value but the model declares only metric_value.
        when(model.generate(anyString()))
                .thenReturn(dualResponse(GOOD_SQL, List.of("metric_value")));

        assertNull(generateWithModel(model, "UNSUPPORTED_FILTER"));
        verify(model, times(1)).generate(anyString());
    }

    @Test
    void malformedJsonIsTreatedAsOneRepairableRound() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("抱歉，我无法回答。",
                dualResponse(GOOD_SQL, List.of("org_code", "metric_value")));

        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                generateWithModel(model, "S2SQL_RENDER_FAILED");
        assertNotNull(fallback);
        assertEquals(GOOD_SQL, fallback.getSql());
        assertEquals(2, fallback.getModelAttempts());
    }

    @Test
    void nonBankSchemaDeclinesBeforeAnyModelCall() {
        LLMReq llmReq = new LLMReq();
        llmReq.setSchema(new LLMReq.LLMSchema());
        llmReq.getSchema().setDataSetName("generic_dataset");

        assertNull(strategy.generate(llmReq, "UNSUPPORTED_QUERY_SHAPE"));
    }

    @Test
    void diagnosticsCarryPlanSourceFreeSqlAndTelemetry() {
        BankFreeSqlFallbackStrategy.FallbackSql fallback = new BankFreeSqlFallbackStrategy.FallbackSql(
                GOOD_SQL, List.of("org_code", "metric_value"), 0.9D, 2, "UNSUPPORTED_QUERY_SHAPE");
        BankResultProjector.Contract contract = strategy.buildResultContract(fallback);
        Map<String, Object> diagnostics = strategy.buildDiagnostics(fallback, contract);

        assertEquals("FREE_SQL", diagnostics.get("bank.nl2sql.planSource"));
        assertEquals("FREE_SQL", diagnostics.get("bank.nl2sql.route"));
        assertEquals("UNSUPPORTED_QUERY_SHAPE", diagnostics.get("bank.nl2sql.freeSql.triggerReason"));
        assertEquals(2, diagnostics.get("bank.nl2sql.freeSql.modelAttempts"));
        assertEquals(contract, diagnostics.get(BankResultProjector.CONTRACT_PROPERTY));
        @SuppressWarnings("unchecked")
        Map<String, Object> telemetry = (Map<String, Object>) diagnostics.get("bankTelemetry");
        assertEquals("FREE_SQL", telemetry.get("generator"));
        assertEquals("FREE_SQL", telemetry.get("route"));
        assertEquals("FREE_SQL", telemetry.get("templateCategory"));
        assertEquals("UNSUPPORTED_QUERY_SHAPE", telemetry.get("triggerReason"));
    }

    @Test
    void resultContractBindsDeclaredColumnsAsFreeProjection() {
        BankFreeSqlFallbackStrategy.FallbackSql fallback = new BankFreeSqlFallbackStrategy.FallbackSql(
                GOOD_SQL, List.of("org_code", "metric_value"), 0.9D, 1, "UNSUPPORTED_QUERY_SHAPE");
        BankResultProjector.Contract contract = strategy.buildResultContract(fallback);

        assertEquals(BankResultProjector.ProjectionType.FREE, contract.getType());
        assertEquals(List.of("org_code", "metric_value"), contract.getMetrics().stream()
                .map(BankResultProjector.MetricBinding::getSemanticColumn).toList());
    }

    @Test
    void parseInfoIsRecognizedOnlyByPlanSourceProperty() {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        assertFalse(BankFreeSqlFallbackStrategy.isFreeSqlFallbackParse(parseInfo));
        assertFalse(BankFreeSqlFallbackStrategy.isFreeSqlFallbackParse(null));
        parseInfo.setProperties(Map.of(BankFreeSqlFallbackStrategy.PLAN_SOURCE_PROPERTY,
                BankFreeSqlFallbackStrategy.PLAN_SOURCE));
        assertTrue(BankFreeSqlFallbackStrategy.isFreeSqlFallbackParse(parseInfo));
    }

    @Test
    void repairUserContentListsEveryViolationAndRestatesTheJsonContract() {
        String content = BankFreeSqlFallbackStrategy.buildRepairUserContent(" 网点存款情况 ",
                List.of("表 \"x\" 不在语义数据集白名单内", "函数 median 不在白名单内"));
        assertTrue(content.startsWith("网点存款情况"));
        assertTrue(content.contains("1. 表 \"x\" 不在语义数据集白名单内"));
        assertTrue(content.contains("2. 函数 median 不在白名单内"));
        assertTrue(content.contains("columns"));
    }

    @Test
    void probePassPublishesTheCandidateUnchanged() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString()))
                .thenReturn(dualResponse(GOOD_SQL, List.of("org_code", "metric_value")));
        BankFallbackSqlProbe probe = mock(BankFallbackSqlProbe.class);
        // Case-insensitive column containment: physical labels may differ in case.
        when(probe.probe(any(LLMReq.class), eq(GOOD_SQL)))
                .thenReturn(BankFallbackSqlProbe.ProbeReport.pass(
                        List.of("ORG_CODE", "METRIC_VALUE"), 1));

        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                generateWithModelAndProbe(model, probe, "UNSUPPORTED_QUERY_SHAPE");

        assertNotNull(fallback);
        assertEquals(GOOD_SQL, fallback.getSql());
        assertEquals(List.of("org_code", "metric_value"), fallback.getDeclaredColumns());
        assertEquals(1, fallback.getModelAttempts());
        verify(probe).probe(any(LLMReq.class), eq(GOOD_SQL));
    }

    @Test
    void probeFailureFeedsExecutionErrorIntoRepairRound() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                dualResponse(GOOD_SQL, List.of("org_code", "metric_value")),
                dualResponse(GOOD_SQL, List.of("org_code", "metric_value")));
        BankFallbackSqlProbe probe = mock(BankFallbackSqlProbe.class);
        when(probe.probe(any(LLMReq.class), anyString()))
                .thenReturn(BankFallbackSqlProbe.ProbeReport.fail(
                        BankFallbackSqlProbe.ERROR_EXECUTION_FAILED,
                        "Query execution failed (failureLayer=JDBC_GRAMMAR)"))
                .thenReturn(BankFallbackSqlProbe.ProbeReport.pass(
                        List.of("org_code", "metric_value"), 1));

        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                generateWithModelAndProbe(model, probe, "S2SQL_RENDER_FAILED");

        assertNotNull(fallback);
        assertEquals(GOOD_SQL, fallback.getSql());
        assertEquals(2, fallback.getModelAttempts());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(2)).generate(prompts.capture());
        String repairPrompt = prompts.getAllValues().get(1);
        assertTrue(repairPrompt.contains("上一轮输出已通过白名单校验，但发布前试执行未通过"));
        assertTrue(repairPrompt.contains("试执行失败[EXECUTION_FAILED]"));
        assertTrue(repairPrompt.contains("failureLayer=JDBC_GRAMMAR"));
        assertTrue(repairPrompt.contains("重新只输出一条符合系统规则的 JSON"));
    }

    @Test
    void probeFailureInBothRoundsFailsClosed() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString()))
                .thenReturn(dualResponse(GOOD_SQL, List.of("org_code", "metric_value")));
        BankFallbackSqlProbe probe = mock(BankFallbackSqlProbe.class);
        when(probe.probe(any(LLMReq.class), anyString()))
                .thenReturn(BankFallbackSqlProbe.ProbeReport.fail(
                        BankFallbackSqlProbe.ERROR_TRANSLATE_FAILED,
                        "parse exception: unknown metric"));

        assertNull(generateWithModelAndProbe(model, probe, "UNSUPPORTED_QUERY_SHAPE"));
        verify(model, times(2)).generate(anyString());
        verify(probe, times(2)).probe(any(LLMReq.class), anyString());
    }

    @Test
    void probeMissingDeclaredColumnFeedsRepairRound() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                dualResponse(GOOD_SQL, List.of("org_code", "metric_value")),
                dualResponse(GOOD_SQL, List.of("org_code", "metric_value")));
        BankFallbackSqlProbe probe = mock(BankFallbackSqlProbe.class);
        when(probe.probe(any(LLMReq.class), anyString()))
                .thenReturn(BankFallbackSqlProbe.ProbeReport.pass(List.of("metric_value"), 1))
                .thenReturn(BankFallbackSqlProbe.ProbeReport.pass(
                        List.of("org_code", "metric_value"), 2));

        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                generateWithModelAndProbe(model, probe, "UNSUPPORTED_FILTER");

        assertNotNull(fallback);
        assertEquals(2, fallback.getModelAttempts());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(2)).generate(prompts.capture());
        String repairPrompt = prompts.getAllValues().get(1);
        assertTrue(repairPrompt.contains("试执行结果缺少声明列: [org_code]"));
        assertTrue(repairPrompt.contains("实际返回列: [metric_value]"));
    }

    @Test
    void probeExceptionIsTreatedAsRepairableProbeFailure() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                dualResponse(GOOD_SQL, List.of("org_code", "metric_value")),
                dualResponse(GOOD_SQL, List.of("org_code", "metric_value")));
        BankFallbackSqlProbe probe = mock(BankFallbackSqlProbe.class);
        when(probe.probe(any(LLMReq.class), anyString()))
                .thenThrow(new IllegalStateException("probe infra down"))
                .thenReturn(BankFallbackSqlProbe.ProbeReport.pass(
                        List.of("org_code", "metric_value"), 1));

        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                generateWithModelAndProbe(model, probe, "UNSUPPORTED_QUERY_SHAPE");

        assertNotNull(fallback);
        assertEquals(2, fallback.getModelAttempts());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(2)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("试执行失败[OTHER]"));
        assertTrue(prompts.getAllValues().get(1).contains("probe infra down"));
    }

    @Test
    void missingProbeBeanKeepsLegacyBehavior() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString()))
                .thenReturn(dualResponse(GOOD_SQL, List.of("org_code", "metric_value")));

        BankFreeSqlFallbackStrategy.FallbackSql fallback;
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
                MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(BankFallbackSqlProbe.class))
                    .thenThrow(new IllegalStateException("no spring context"));
            modelProvider.when(() -> ModelProvider.getChatModel(any(ChatModelConfig.class)))
                    .thenReturn(model);
            fallback = strategy.generate(bankRequest(), "UNSUPPORTED_QUERY_SHAPE");
        }

        assertNotNull(fallback);
        assertEquals(GOOD_SQL, fallback.getSql());
        assertEquals(1, fallback.getModelAttempts());
    }

    private BankFreeSqlFallbackStrategy.FallbackSql generateWithModelAndProbe(
            ChatLanguageModel model, BankFallbackSqlProbe probe, String triggerReason) {
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
                MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(BankFallbackSqlProbe.class))
                    .thenReturn(probe);
            modelProvider.when(() -> ModelProvider.getChatModel(any(ChatModelConfig.class)))
                    .thenReturn(model);
            return strategy.generate(bankRequest(), triggerReason);
        }
    }

    private BankFreeSqlFallbackStrategy.FallbackSql generateWithModel(ChatLanguageModel model,
            String triggerReason) {
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
            modelProvider.when(() -> ModelProvider.getChatModel(any(ChatModelConfig.class)))
                    .thenReturn(model);
            return strategy.generate(bankRequest(), triggerReason);
        }
    }

    private LLMReq bankRequest() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(12L);
        schema.setDataSetName("bank_dataset");
        schema.setMetrics(List.of(
                SchemaElement.builder().name("各项存款余额").bizName("ZB001").defaultAgg("SUM")
                        .build(),
                SchemaElement.builder().name("各项贷款余额").bizName("ZB002").defaultAgg("SUM")
                        .build()));
        schema.setDimensions(List.of(
                SchemaElement.builder().name("机构").bizName("bank_organization").build(),
                SchemaElement.builder().name("数据日期").bizName("bank_data_date").build()));
        LLMReq llmReq = new LLMReq();
        llmReq.setQueryText("2026年3月末各机构的存款规模是多少");
        llmReq.setSchema(schema);
        llmReq.setChatAppConfig(Map.of(OnePassSCSqlGenStrategy.APP_KEY,
                ChatApp.builder().chatModelConfig(new ChatModelConfig()).build()));
        return llmReq;
    }

    private static String dualResponse(String sql, List<String> aliases) {
        String columns = aliases.stream()
                .map(alias -> "{\"alias\":\"" + alias + "\",\"semantic_type\":\"NUMBER\","
                        + "\"unit\":\"元\"}")
                .reduce((left, right) -> left + "," + right).orElse("");
        return "{\"sql\":\"" + sql + "\",\"columns\":[" + columns + "],\"confidence\":0.86}";
    }
}
