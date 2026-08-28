package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.Parameter;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.parser.llm.LLMResponseService;
import com.tencent.supersonic.headless.chat.parser.llm.ParseResult;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Admission-matrix and wiring tests for the terminal-state interception. Only compiler terminal
 * failures with the four admitted reasons enter the fallback; malformed JSON, clarification,
 * validation misses and model failures never do; the disabled switch (the default) and bank-off
 * mode decline even admitted errors; a produced candidate carries the FREE contract and
 * planSource=FREE_SQL diagnostics.
 */
class BankFreeSqlFallbackHookTest {

    private static final String GOOD_SQL =
            "SELECT bank_organization AS org_code, SUM(ZB001) AS metric_value "
                    + "FROM bank_dataset WHERE bank_data_date = '2026-03-31' "
                    + "GROUP BY bank_organization";

    @Test
    void onlyTheFourCompilerTerminalReasonsAreAdmitted() {
        for (BankPlanCompilationException.Reason reason : List.of(
                BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE,
                BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                BankPlanCompilationException.Reason.UNSUPPORTED_FILTER,
                BankPlanCompilationException.Reason.S2SQL_RENDER_FAILED)) {
            assertTrue(BankFreeSqlFallbackHook.admits(BankNl2SqlError.compilationFailure(
                    new BankPlanCompilationException(reason, "detail"))), reason.name());
        }
        assertTrue(BankFreeSqlFallbackHook.admits(BankNl2SqlError.compilationFailure(
                new RuntimeException("wrapper",
                        new BankPlanCompilationException(
                                BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE,
                                "nested")))));
    }

    @Test
    void structuralClarificationAndModelFailuresAreNeverAdmitted() {
        assertFalse(BankFreeSqlFallbackHook.admits(BankNl2SqlError.afterSingleRepair(
                new BankQueryPlanParseException(BankQueryPlanParseException.Reason.MALFORMED_JSON,
                        "bad json"))));
        assertFalse(BankFreeSqlFallbackHook.admits(BankNl2SqlError.afterSingleRepair(
                new BankQueryPlanParseException(
                        BankQueryPlanParseException.Reason.SCHEMA_VIOLATION, "bad schema"))));
        assertFalse(BankFreeSqlFallbackHook.admits(
                BankNl2SqlError.clarificationRequired("请补充时间范围")));
        assertFalse(BankFreeSqlFallbackHook.admits(
                BankNl2SqlError.modelFailure(new RuntimeException("boom"))));
        assertFalse(BankFreeSqlFallbackHook.admits(BankNl2SqlError.noCandidate(null, null)));
        assertFalse(BankFreeSqlFallbackHook.admits(BankNl2SqlError.compilationFailure(
                new BankPlanCompilationException(BankPlanCompilationException.Reason.INVALID_PLAN,
                        "not admitted"))));
        assertFalse(BankFreeSqlFallbackHook.admits(
                BankNl2SqlError.compilationFailure(new RuntimeException("no compiler cause"))));
        assertFalse(BankFreeSqlFallbackHook.admits(null));
    }

    @Test
    void admittedTerminalErrorProducesAFreeSqlCandidateWithContractAndTelemetry() {
        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                new BankFreeSqlFallbackStrategy.FallbackSql(GOOD_SQL,
                        List.of("org_code", "metric_value"), 0.9D, 2, "UNSUPPORTED_QUERY_SHAPE");
        BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());
        doReturn(fallback).when(strategy).generate(any(), anyString());
        LLMResponseService responseService = mock(LLMResponseService.class);

        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        ParseResp parseResp = new ParseResp("query");
        queryCtx.setParseResp(parseResp);
        LLMReq llmReq = bankConstrainedRequest();

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(ParserConfig.class))
                    .thenReturn(configWithValue("true"));
            assertTrue(BankFreeSqlFallbackHook.tryRun(queryCtx, llmReq,
                    BankNl2SqlError.compilationFailure(new BankPlanCompilationException(
                            BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE, "shape")),
                    strategy, responseService));
        }

        ArgumentCaptor<Map<String, Object>> diagnostics =
                ArgumentCaptor.forClass(Map.class);
        verify(responseService).addParseInfo(eq(queryCtx), any(ParseResult.class), eq(GOOD_SQL),
                eq(1.0D), diagnostics.capture());
        Map<String, Object> properties = diagnostics.getValue();
        assertEquals("FREE_SQL", properties.get(BankFreeSqlFallbackStrategy.PLAN_SOURCE_PROPERTY));
        assertTrue(properties.get(BankResultProjector.CONTRACT_PROPERTY)
                instanceof BankResultProjector.Contract freeContract
                && freeContract.getType() == BankResultProjector.ProjectionType.FREE);
        @SuppressWarnings("unchecked")
        Map<String, Object> telemetry = (Map<String, Object>) properties.get("bankTelemetry");
        assertEquals("FREE_SQL", telemetry.get("generator"));

        ParseResp.BankRoutingAttemptTelemetry routingTelemetry =
                parseResp.getBankRoutingAttemptTelemetry();
        assertNotNull(routingTelemetry);
        assertEquals(ParseResp.BankRoutingSqlGenType.FREE_SQL,
                routingTelemetry.getSelectedSqlGenType());
        assertTrue(routingTelemetry.isLlmCandidateCreated());
    }

    @Test
    void switchOffByDefaultDeclinesEvenAdmittedErrors() {
        BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());
        LLMResponseService responseService = mock(LLMResponseService.class);
        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        queryCtx.setParseResp(new ParseResp("query"));

        // No Spring context: ContextUtils lookup fails and the hook fails closed (default false).
        assertFalse(BankFreeSqlFallbackHook.tryRun(queryCtx, bankConstrainedRequest(),
                BankNl2SqlError.compilationFailure(new BankPlanCompilationException(
                        BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE, "shape")),
                strategy, responseService));
        verify(strategy, never()).generate(any(), anyString());
        verify(responseService, never()).addParseInfo(any(), any(), any(), anyDouble(), any());
    }

    @Test
    void explicitSwitchOffDeclinesEvenAdmittedErrors() {
        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(ParserConfig.class))
                    .thenReturn(configWithValue("false"));
            BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());
            LLMResponseService responseService = mock(LLMResponseService.class);

            assertFalse(BankFreeSqlFallbackHook.tryRun(new ChatQueryContext(new QueryNLReq()),
                    bankConstrainedRequest(),
                    BankNl2SqlError.compilationFailure(new BankPlanCompilationException(
                            BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE, "shape")),
                    strategy, responseService));
            verify(strategy, never()).generate(any(), anyString());
        }
    }

    @Test
    void nonBankConstrainedRouteDeclinesInBankOffMode() {
        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(ParserConfig.class))
                    .thenReturn(configWithValue("true"));
            BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());
            LLMResponseService responseService = mock(LLMResponseService.class);
            LLMReq unconstrained = bankConstrainedRequest();
            unconstrained.setSqlGenType(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY);

            assertFalse(BankFreeSqlFallbackHook.tryRun(new ChatQueryContext(new QueryNLReq()),
                    unconstrained,
                    BankNl2SqlError.compilationFailure(new BankPlanCompilationException(
                            BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE, "shape")),
                    strategy, responseService));
            verify(strategy, never()).generate(any(), anyString());
        }
    }

    @Test
    void exhaustedFallbackBudgetKeepsTheTerminalError() {
        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(ParserConfig.class))
                    .thenReturn(configWithValue("true"));
            BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());
            doReturn(null).when(strategy).generate(any(), anyString());
            LLMResponseService responseService = mock(LLMResponseService.class);

            assertFalse(BankFreeSqlFallbackHook.tryRun(new ChatQueryContext(new QueryNLReq()),
                    bankConstrainedRequest(),
                    BankNl2SqlError.compilationFailure(new BankPlanCompilationException(
                            BankPlanCompilationException.Reason.S2SQL_RENDER_FAILED, "render")),
                    strategy, responseService));
            verify(responseService, never()).addParseInfo(any(), any(), any(), anyDouble(), any());
        }
    }

    @Test
    void publicEntryResolvesStrategyAndResponseServiceFromSpringContext() {
        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                new BankFreeSqlFallbackStrategy.FallbackSql(GOOD_SQL,
                        List.of("org_code", "metric_value"), 0.9D, 1, "UNSUPPORTED_FILTER");
        BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());
        doReturn(fallback).when(strategy).generate(any(), anyString());
        LLMResponseService responseService = mock(LLMResponseService.class);
        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        queryCtx.setParseResp(new ParseResp("query"));

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(ParserConfig.class))
                    .thenReturn(configWithValue("true"));
            contextUtils.when(() -> ContextUtils.getBean(BankFreeSqlFallbackStrategy.class))
                    .thenReturn(strategy);
            contextUtils.when(() -> ContextUtils.getBean(LLMResponseService.class))
                    .thenReturn(responseService);

            assertTrue(BankFreeSqlFallbackHook.tryRun(queryCtx, bankConstrainedRequest(),
                    BankNl2SqlError.compilationFailure(new BankPlanCompilationException(
                            BankPlanCompilationException.Reason.UNSUPPORTED_FILTER, "filter"))));
        }
        verify(responseService).addParseInfo(eq(queryCtx), any(ParseResult.class), eq(GOOD_SQL),
                eq(1.0D), any());
    }

    private ParserConfig configWithValue(String value) {
        return new ParserConfig() {
            @Override
            public String getParameterValue(Parameter parameter) {
                return ParserConfig.PARSER_BANK_FREE_SQL_FALLBACK_ENABLE.getName()
                        .equals(parameter.getName()) ? value : parameter.getDefaultValue();
            }
        };
    }

    private LLMReq bankConstrainedRequest() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(12L);
        schema.setDataSetName("bank_dataset");
        schema.setMetrics(List.of(
                SchemaElement.builder().name("各项存款余额").bizName("ZB001").defaultAgg("SUM")
                        .build()));
        schema.setDimensions(List.of(
                SchemaElement.builder().name("机构").bizName("bank_organization").build(),
                SchemaElement.builder().name("数据日期").bizName("bank_data_date").build()));
        LLMReq llmReq = new LLMReq();
        llmReq.setQueryText("各机构存款规模");
        llmReq.setSchema(schema);
        llmReq.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        Map<String, Object> telemetry = new HashMap<>();
        telemetry.put("bankConstrainedPlanEnabled", Boolean.TRUE);
        telemetry.put("bankDatasetQualified", Boolean.TRUE);
        llmReq.setBankRoutingTelemetry(telemetry);
        return llmReq;
    }
}
