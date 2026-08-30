package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.pojo.Parameter;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankFreeSqlFallbackStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankNl2SqlError;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanCompilationException;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanParseException;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LLMSqlParserTest {

    @Test
    void compilationRepairExplainsRankingAndProvinceAverageConflictWithoutRawExceptionText() {
        List<String> hints = LLMSqlParser.compilationCorrectionHints(
                BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION, """
                        {"intent":"RANKING","filters":[{"field":"benchmark",
                        "operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}]}
                        """);

        assertEquals(List.of("当前计划是“全省排名”而不是“全省均值”比较：删除 "
                + "benchmark/COMPARE/PROVINCE_AVERAGE，令 filters=[]；保留 intent=RANKING "
                + "和 bank_organization 维度后，重新输出完整 BankQueryPlan。"), hints);
    }

    @Test
    void compilationRepairExplainsThatTimeComparisonRequiresChangeCalculation() {
        List<String> hints = LLMSqlParser.compilationCorrectionHints(
                BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION, """
                        {"intent":"AGGREGATION","time":{"comparison":"START_OF_YEAR"},
                        "calculation":{"type":"DIRECT"}}
                        """);

        assertEquals(List.of("当前计划已声明 time.comparison 非 NONE，却填写了 "
                + "calculation.type=DIRECT：只将 calculation.type 改为 CHANGE；"
                + "保留已合法的 intent、指标、机构、日期、基期、dimensions、filters、"
                + "output 和 limit 后，重新输出完整 BankQueryPlan。"), hints);
    }

    @Test
    void compilationRepairExplainsTheExactMonthAndYearComparisonShape() {
        List<String> hints = LLMSqlParser.compilationCorrectionHints(
                BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION, """
                        {"intent":"CHANGE","metrics":[{"bizName":"ZB002"}],
                        "organizations":[{"code":"ORG006"}],"time":{"comparison":"MOM_AND_YOY"},
                        "calculation":{"type":"DIRECT"}}
                        """);

        assertEquals(1, hints.size());
        assertTrue(hints.get(0).contains("恰好一个机构"));
        assertTrue(hints.get(0).contains("time.comparison=MOM_AND_YOY"));
        assertTrue(hints.get(0).contains("baselineStartDate=null"));
    }

    @Test
    void shouldRetryCompilationOnceWithStructuredToolFeedbackThenStopOnRepeatedFailure() {
        LLMRequestService requestService = mock(LLMRequestService.class);
        LLMResponseService responseService = mock(LLMResponseService.class);
        LLMParserConfig parserConfig = new LLMParserConfig();
        parserConfig.setRecallMaxRetries(3);

        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        ParseResp parseResp = new ParseResp("query");
        queryCtx.setParseResp(parseResp);

        LLMReq llmReq = new LLMReq();
        llmReq.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);

        when(requestService.getDataSetId(queryCtx)).thenReturn(33L);
        when(requestService.getLlmReq(queryCtx, 33L)).thenReturn(llmReq);
        when(requestService.runText2SQL(llmReq)).thenThrow(new BankPlanCompilationException(
                BankPlanCompilationException.Reason.DIMENSION_UNAVAILABLE, "unknown dim: user_id"));

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(LLMRequestService.class))
                    .thenReturn(requestService);
            contextUtils.when(() -> ContextUtils.getBean(LLMResponseService.class))
                    .thenReturn(responseService);
            contextUtils.when(() -> ContextUtils.getBean(LLMParserConfig.class))
                    .thenReturn(parserConfig);

            new LLMSqlParser().parse(queryCtx);
        }

        assertEquals(ParseResp.ParseState.FAILED, parseResp.getState());
        assertTrue(BankNl2SqlError.isTerminalParserError(parseResp.getErrorMsg()));
        verify(requestService, times(2)).runText2SQL(llmReq);
        assertEquals(BankPlanToolResult.Stage.COMPILE,
                llmReq.getBankPlanToolResult().getFailedStage());
        assertEquals("DIMENSION_UNAVAILABLE", llmReq.getBankPlanToolResult().getErrorCode());
        // The sanitized in-house compiler message travels through; whitelists fill allowedValues.
        String feedback = llmReq.getBankPlanToolResult().toRepairFeedback();
        assertTrue(feedback.contains("编译器拒绝原因（原文）：unknown dim: user_id"));
        assertTrue(feedback.contains("dimensions"));
        assertTrue(feedback.contains("bank_data_date"));
    }

    @Test
    void planStageBudgetExhaustionTriggersControlledFreeSqlFallbackWhenEnabled() {
        LLMRequestService requestService = mock(LLMRequestService.class);
        LLMResponseService responseService = mock(LLMResponseService.class);
        LLMParserConfig parserConfig = new LLMParserConfig();
        parserConfig.setRecallMaxRetries(3);
        BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());
        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                mock(BankFreeSqlFallbackStrategy.FallbackSql.class);
        when(fallback.getSql()).thenReturn(
                "SELECT bank_organization AS org_code, SUM(ZB001) AS metric_value "
                        + "FROM bank_dataset WHERE bank_data_date = '2026-03-31' "
                        + "GROUP BY bank_organization");
        when(fallback.getDeclaredColumns()).thenReturn(List.of("org_code", "metric_value"));
        when(fallback.getConfidence()).thenReturn(0.9D);
        when(fallback.getModelAttempts()).thenReturn(2);
        when(fallback.getTriggerReason()).thenReturn("PLAN_STAGE_EXHAUSTED:SCHEMA_VIOLATION");
        doReturn(fallback).when(strategy).generate(any(), anyString());

        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        ParseResp parseResp = new ParseResp("query");
        queryCtx.setParseResp(parseResp);

        LLMReq llmReq = bankConstrainedPlanReq();

        when(requestService.getDataSetId(queryCtx)).thenReturn(33L);
        when(requestService.getLlmReq(queryCtx, 33L)).thenReturn(llmReq);
        when(requestService.runText2SQL(llmReq)).thenThrow(BankNl2SqlError.afterSingleRepair(
                new BankQueryPlanParseException(
                        BankQueryPlanParseException.Reason.SCHEMA_VIOLATION, "bad schema")));

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(LLMRequestService.class))
                    .thenReturn(requestService);
            contextUtils.when(() -> ContextUtils.getBean(LLMResponseService.class))
                    .thenReturn(responseService);
            contextUtils.when(() -> ContextUtils.getBean(LLMParserConfig.class))
                    .thenReturn(parserConfig);
            contextUtils.when(() -> ContextUtils.getBean(ParserConfig.class))
                    .thenReturn(fallbackSwitchConfig("true"));
            contextUtils.when(() -> ContextUtils.getBean(BankFreeSqlFallbackStrategy.class))
                    .thenReturn(strategy);

            new LLMSqlParser().parse(queryCtx);
        }

        // One model round only: the plan-stage terminal error is absorbed by the fallback.
        verify(requestService, times(1)).runText2SQL(llmReq);
        verify(strategy).generate(any(), eq("PLAN_STAGE_EXHAUSTED:SCHEMA_VIOLATION"));
        ArgumentCaptor<Map<String, Object>> diagnostics = ArgumentCaptor.forClass(Map.class);
        verify(responseService).addParseInfo(eq(queryCtx), any(ParseResult.class), anyString(),
                eq(1.0D), diagnostics.capture());
        assertEquals(BankFreeSqlFallbackStrategy.PLAN_SOURCE,
                diagnostics.getValue().get(BankFreeSqlFallbackStrategy.PLAN_SOURCE_PROPERTY));
        assertEquals("PLAN_STAGE_EXHAUSTED:SCHEMA_VIOLATION",
                diagnostics.getValue().get("bank.nl2sql.freeSql.triggerReason"));
        assertNull(parseResp.getErrorMsg());
        // The parse request must not surface the terminal plan failure after a successful fallback.
        assertEquals(ParseResp.ParseState.PENDING, parseResp.getState());
        assertEquals(ParseResp.BankRoutingSqlGenType.FREE_SQL,
                parseResp.getBankRoutingAttemptTelemetry().getSelectedSqlGenType());
        assertTrue(parseResp.getBankRoutingAttemptTelemetry().isLlmCandidateCreated());
    }

    @Test
    void planStageBudgetExhaustionKeepsTerminalErrorWhenFallbackDisabled() {
        LLMRequestService requestService = mock(LLMRequestService.class);
        LLMResponseService responseService = mock(LLMResponseService.class);
        LLMParserConfig parserConfig = new LLMParserConfig();
        parserConfig.setRecallMaxRetries(3);
        BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());

        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        ParseResp parseResp = new ParseResp("query");
        queryCtx.setParseResp(parseResp);

        LLMReq llmReq = bankConstrainedPlanReq();

        when(requestService.getDataSetId(queryCtx)).thenReturn(33L);
        when(requestService.getLlmReq(queryCtx, 33L)).thenReturn(llmReq);
        when(requestService.runText2SQL(llmReq)).thenThrow(BankNl2SqlError.afterSingleRepair(
                new BankQueryPlanParseException(
                        BankQueryPlanParseException.Reason.SCHEMA_VIOLATION, "bad schema")));

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(LLMRequestService.class))
                    .thenReturn(requestService);
            contextUtils.when(() -> ContextUtils.getBean(LLMResponseService.class))
                    .thenReturn(responseService);
            contextUtils.when(() -> ContextUtils.getBean(LLMParserConfig.class))
                    .thenReturn(parserConfig);
            contextUtils.when(() -> ContextUtils.getBean(ParserConfig.class))
                    .thenReturn(fallbackSwitchConfig("false"));

            new LLMSqlParser().parse(queryCtx);
        }

        verify(requestService, times(1)).runText2SQL(llmReq);
        verify(strategy, never()).generate(any(), anyString());
        verify(responseService, never()).addParseInfo(any(), any(), any(), anyDouble(), any());
        assertEquals(ParseResp.ParseState.FAILED, parseResp.getState());
        assertTrue(BankNl2SqlError.isTerminalParserError(parseResp.getErrorMsg()));
    }

    @Test
    void planGateShapeRejectionReachesTheControlledFreeSqlFallbackAfterTheRepairRound() {
        LLMRequestService requestService = mock(LLMRequestService.class);
        LLMResponseService responseService = mock(LLMResponseService.class);
        LLMParserConfig parserConfig = new LLMParserConfig();
        parserConfig.setRecallMaxRetries(3);
        BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());
        BankFreeSqlFallbackStrategy.FallbackSql fallback =
                mock(BankFreeSqlFallbackStrategy.FallbackSql.class);
        when(fallback.getSql()).thenReturn(
                "SELECT bank_organization AS org_code, SUM(ZB001) AS metric_value "
                        + "FROM bank_dataset WHERE bank_data_date = '2024-12-31' "
                        + "GROUP BY bank_organization");
        when(fallback.getDeclaredColumns()).thenReturn(List.of("org_code", "metric_value"));
        when(fallback.getConfidence()).thenReturn(0.9D);
        when(fallback.getModelAttempts()).thenReturn(2);
        when(fallback.getTriggerReason()).thenReturn("UNSUPPORTED_QUERY_SHAPE");
        doReturn(fallback).when(strategy).generate(any(), anyString());

        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        ParseResp parseResp = new ParseResp("query");
        queryCtx.setParseResp(parseResp);

        LLMReq llmReq = bankConstrainedPlanReq();
        // The rejected attempt that the plan gate pinned on the request before the terminal shape
        // rejection (no plan has ever been returned successfully on this request).
        llmReq.setPreviousBankQueryPlanJson(
                "{\"intent\":\"CHANGE\",\"metrics\":[{\"bizName\":\"ZB001\"}]}");

        when(requestService.getDataSetId(queryCtx)).thenReturn(33L);
        when(requestService.getLlmReq(queryCtx, 33L)).thenReturn(llmReq);
        when(requestService.runText2SQL(llmReq)).thenThrow(new BankPlanCompilationException(
                BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE,
                "rank_change_across_periods_unsupported: shape cannot be expressed"));

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(LLMRequestService.class))
                    .thenReturn(requestService);
            contextUtils.when(() -> ContextUtils.getBean(LLMResponseService.class))
                    .thenReturn(responseService);
            contextUtils.when(() -> ContextUtils.getBean(LLMParserConfig.class))
                    .thenReturn(parserConfig);
            contextUtils.when(() -> ContextUtils.getBean(ParserConfig.class))
                    .thenReturn(fallbackSwitchConfig("true"));
            contextUtils.when(() -> ContextUtils.getBean(BankFreeSqlFallbackStrategy.class))
                    .thenReturn(strategy);

            new LLMSqlParser().parse(queryCtx);
        }

        // Round 1 keeps its COMPILE repair chance; the pinned previous plan must survive the
        // tool-result setup so round 2 can actually rebuild its prompt. Round 2 repeats the
        // signature, so the terminal shape rejection falls back instead of degenerating into a
        // model failure.
        verify(requestService, times(2)).runText2SQL(llmReq);
        assertEquals("{\"intent\":\"CHANGE\",\"metrics\":[{\"bizName\":\"ZB001\"}]}",
                llmReq.getPreviousBankQueryPlanJson());
        assertEquals(BankPlanToolResult.Stage.COMPILE,
                llmReq.getBankPlanToolResult().getFailedStage());
        assertEquals("UNSUPPORTED_QUERY_SHAPE", llmReq.getBankPlanToolResult().getErrorCode());
        verify(strategy).generate(any(), eq("UNSUPPORTED_QUERY_SHAPE"));
        verify(responseService).addParseInfo(eq(queryCtx), any(ParseResult.class), anyString(),
                eq(1.0D), any());
        assertNull(parseResp.getErrorMsg());
        // The parse request must not surface the terminal shape failure after a successful
        // fallback: the state stays untouched (PENDING) instead of FAILED.
        assertEquals(ParseResp.ParseState.PENDING, parseResp.getState());
        assertEquals(ParseResp.BankRoutingSqlGenType.FREE_SQL,
                parseResp.getBankRoutingAttemptTelemetry().getSelectedSqlGenType());
    }

    @Test
    void intermediateCompileFailureWithRepairBudgetLeftDoesNotTriggerFreeSqlFallback() {
        LLMRequestService requestService = mock(LLMRequestService.class);
        LLMResponseService responseService = mock(LLMResponseService.class);
        LLMParserConfig parserConfig = new LLMParserConfig();
        parserConfig.setRecallMaxRetries(3);
        BankFreeSqlFallbackStrategy strategy = spy(new BankFreeSqlFallbackStrategy());

        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        ParseResp parseResp = new ParseResp("query");
        queryCtx.setParseResp(parseResp);

        LLMReq llmReq = bankConstrainedPlanReq();

        when(requestService.getDataSetId(queryCtx)).thenReturn(33L);
        when(requestService.getLlmReq(queryCtx, 33L)).thenReturn(llmReq);
        when(requestService.runText2SQL(llmReq))
                .thenThrow(new BankPlanCompilationException(
                        BankPlanCompilationException.Reason.UNSUPPORTED_QUERY_SHAPE, "shape"))
                .thenReturn(null);

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(LLMRequestService.class))
                    .thenReturn(requestService);
            contextUtils.when(() -> ContextUtils.getBean(LLMResponseService.class))
                    .thenReturn(responseService);
            contextUtils.when(() -> ContextUtils.getBean(LLMParserConfig.class))
                    .thenReturn(parserConfig);
            contextUtils.when(() -> ContextUtils.getBean(ParserConfig.class))
                    .thenReturn(fallbackSwitchConfig("true"));

            new LLMSqlParser().parse(queryCtx);
        }

        // Round 1 owns a repair chance, so even an admitted compile reason must not fall back;
        // only the terminal no-candidate state after the repair round fails the request.
        verify(requestService, times(2)).runText2SQL(llmReq);
        verify(strategy, never()).generate(any(), anyString());
        verify(responseService, never()).addParseInfo(any(), any(), any(), anyDouble(), any());
        assertEquals(BankPlanToolResult.Stage.COMPILE,
                llmReq.getBankPlanToolResult().getFailedStage());
        assertEquals(ParseResp.ParseState.FAILED, parseResp.getState());
        assertTrue(BankNl2SqlError.isTerminalParserError(parseResp.getErrorMsg()));
    }

    private static LLMReq bankConstrainedPlanReq() {
        LLMReq llmReq = new LLMReq();
        llmReq.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(33L);
        schema.setDataSetName("bank_dataset");
        schema.setMetrics(List.of(com.tencent.supersonic.headless.api.pojo.SchemaElement.builder()
                .name("各项存款余额").bizName("ZB001").defaultAgg("SUM").build()));
        schema.setDimensions(List.of(
                com.tencent.supersonic.headless.api.pojo.SchemaElement.builder()
                        .name("机构").bizName("bank_organization").build(),
                com.tencent.supersonic.headless.api.pojo.SchemaElement.builder()
                        .name("数据日期").bizName("bank_data_date").build()));
        llmReq.setSchema(schema);
        Map<String, Object> routingTelemetry = new HashMap<>();
        routingTelemetry.put("bankConstrainedPlanEnabled", Boolean.TRUE);
        routingTelemetry.put("bankDatasetQualified", Boolean.TRUE);
        llmReq.setBankRoutingTelemetry(routingTelemetry);
        return llmReq;
    }

    private ParserConfig fallbackSwitchConfig(String value) {
        return new ParserConfig() {
            @Override
            public String getParameterValue(Parameter parameter) {
                return ParserConfig.PARSER_BANK_FREE_SQL_FALLBACK_ENABLE.getName()
                        .equals(parameter.getName()) ? value : parameter.getDefaultValue();
            }
        };
    }

    @Test
    void compilationToolFeedbackKeepsDedicatedTemplatesAndAddsSingleLineRawReason() {
        BankPlanCompilationException special = new BankPlanCompilationException(
                BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                "direct calculation with comparison START_OF_YEAR");
        List<String> hints = LLMSqlParser.compilationToolFeedback(special, """
                {"intent":"AGGREGATION","time":{"comparison":"START_OF_YEAR"},
                "calculation":{"type":"DIRECT"}}
                """);

        assertEquals("编译器拒绝原因（原文）：direct calculation with comparison START_OF_YEAR",
                hints.get(0));
        assertEquals(List.of("当前计划已声明 time.comparison 非 NONE，却填写了 "
                + "calculation.type=DIRECT：只将 calculation.type 改为 CHANGE；"
                + "保留已合法的 intent、指标、机构、日期、基期、dimensions、filters、"
                + "output 和 limit 后，重新输出完整 BankQueryPlan。"), hints.subList(1, 2));
        assertEquals("修正后必须重新输出完整 BankQueryPlan；未指出的槽位保持上一份计划原值。",
                hints.get(hints.size() - 1));
    }

    @Test
    void compilationToolFeedbackFlattensTruncatesAndDropsCatalogDumps() {
        BankPlanCompilationException dumped = new BankPlanCompilationException(
                BankPlanCompilationException.Reason.INVALID_PLAN,
                "可填写值目录（只能从下列内容中选择）：\n- intent: [POINT_QUERY]");
        assertTrue(LLMSqlParser.compilationToolFeedback(dumped, null).stream()
                .noneMatch(hint -> hint.contains("可填写值目录")));

        StringBuilder longMessage = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            longMessage.append("fragment-").append(index).append(' ');
        }
        BankPlanCompilationException verbose = new BankPlanCompilationException(
                BankPlanCompilationException.Reason.S2SQL_RENDER_FAILED,
                longMessage.toString());
        String rawHint = LLMSqlParser.compilationToolFeedback(verbose, null).get(0);
        assertTrue(rawHint.startsWith("编译器拒绝原因（原文）：fragment-0 "));
        assertTrue(rawHint.length() <= 230, "raw compiler reason must stay bounded in one line");
        assertFalse(rawHint.contains("\n"));
    }

    @Test
    void compilationAllowedValuesExposeRegistryWhitelistsPerReason() {
        Map<String, List<String>> metricValues = LLMSqlParser.compilationAllowedValues(
                BankPlanCompilationException.Reason.METRIC_UNAVAILABLE);
        assertTrue(metricValues.get("metrics[].bizName").contains("ZB001"));

        Map<String, List<String>> filterValues = LLMSqlParser.compilationAllowedValues(
                BankPlanCompilationException.Reason.UNSUPPORTED_FILTER);
        assertTrue(filterValues.containsKey("filterFields"));
        assertTrue(filterValues.get("filterOperators").containsAll(
                List.of("EQ", "IN", "CONTAINS", "COMPARE")));

        Map<String, List<String>> calculationValues = LLMSqlParser.compilationAllowedValues(
                BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION);
        assertTrue(calculationValues.get("calculation.type").contains("COUNT_DAYS_ABOVE_PROVINCE_AVERAGE"));
        assertTrue(calculationValues.get("time.comparison").contains("START_OF_YEAR"));

        Map<String, List<String>> orderValues = LLMSqlParser.compilationAllowedValues(
                BankPlanCompilationException.Reason.ORDER_FIELD_NOT_SELECTED);
        assertEquals(List.of("ASC", "DESC"), orderValues.get("orderBy[].direction"));

        Map<String, List<String>> clarifyValues = LLMSqlParser.compilationAllowedValues(
                BankPlanCompilationException.Reason.CLARIFICATION_REQUIRED);
        assertEquals(List.of("CLARIFY", "EXECUTE"), clarifyValues.get("action"));
    }

    @Test
    void genericCompilationHintsCoverEveryRemainingReason() {
        for (BankPlanCompilationException.Reason reason : List.of(
                BankPlanCompilationException.Reason.METRIC_UNAVAILABLE,
                BankPlanCompilationException.Reason.DIMENSION_UNAVAILABLE,
                BankPlanCompilationException.Reason.ORGANIZATION_DIMENSION_UNAVAILABLE,
                BankPlanCompilationException.Reason.TIME_DIMENSION_UNAVAILABLE,
                BankPlanCompilationException.Reason.OUTPUT_ORDER_MISMATCH,
                BankPlanCompilationException.Reason.UNSUPPORTED_FILTER,
                BankPlanCompilationException.Reason.S2SQL_RENDER_FAILED,
                BankPlanCompilationException.Reason.INVALID_PLAN,
                BankPlanCompilationException.Reason.CLARIFICATION_REQUIRED)) {
            List<String> hints = LLMSqlParser.compilationCorrectionHints(reason, "{}");
            assertEquals(1, hints.size(), reason.name());
            assertFalse(hints.get(0).isBlank(), reason.name());
            List<String> feedback = LLMSqlParser.compilationToolFeedback(
                    new BankPlanCompilationException(reason, reason.name().toLowerCase(Locale.ROOT)),
                    "{}");
            assertTrue(feedback.get(feedback.size() - 1)
                            .contains("重新输出完整 BankQueryPlan"),
                    reason + " must receive the full-replan closing instruction");
        }
    }

    @Test
    void constrainedPlanWithoutPlanDropsSqlOutputAndSqlRespMap() {
        LLMResp llmResp = freeSqlResponse();

        LLMSqlParser.dropUnconstrainedSqlWhenPlanMissing(llmResp, true);

        assertNull(llmResp.getSqlOutput());
        assertNull(llmResp.getSqlRespMap());
    }

    @Test
    void constrainedPlanWithValidPlanKeepsFreeSqlUntouched() {
        LLMResp llmResp = freeSqlResponse();
        llmResp.setBankQueryPlan(new BankQueryPlan());

        LLMSqlParser.dropUnconstrainedSqlWhenPlanMissing(llmResp, true);

        assertEquals("SELECT 1 FROM free_sql", llmResp.getSqlOutput());
        assertEquals(1, llmResp.getSqlRespMap().size());
    }

    @Test
    void unconstrainedModeKeepsFreeSqlUntouched() {
        LLMResp llmResp = freeSqlResponse();

        LLMSqlParser.dropUnconstrainedSqlWhenPlanMissing(llmResp, false);

        assertEquals("SELECT 1 FROM free_sql", llmResp.getSqlOutput());
        assertEquals(1, llmResp.getSqlRespMap().size());
    }

    @Test
    void constrainedPlanWithoutPlanRetriesThenFailsInsteadOfProducingParseCandidate() {
        LLMRequestService requestService = mock(LLMRequestService.class);
        LLMResponseService responseService = mock(LLMResponseService.class);
        LLMParserConfig parserConfig = new LLMParserConfig();
        parserConfig.setRecallMaxRetries(1);

        ChatQueryContext queryCtx = new ChatQueryContext(new QueryNLReq());
        ParseResp parseResp = new ParseResp("query");
        queryCtx.setParseResp(parseResp);

        LLMReq llmReq = new LLMReq();
        llmReq.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);

        when(requestService.getDataSetId(queryCtx)).thenReturn(33L);
        when(requestService.getLlmReq(queryCtx, 33L)).thenReturn(llmReq);
        when(requestService.runText2SQL(llmReq)).thenAnswer(invocation -> freeSqlResponse());
        when(responseService.getDeduplicationSqlRespWithOutcome(anyInt(), any(), any()))
                .thenReturn(new LLMResponseService.DeduplicationOutcome(Map.of(), false, null));

        try (MockedStatic<ContextUtils> contextUtils = mockStatic(ContextUtils.class)) {
            contextUtils.when(() -> ContextUtils.getBean(LLMRequestService.class))
                    .thenReturn(requestService);
            contextUtils.when(() -> ContextUtils.getBean(LLMResponseService.class))
                    .thenReturn(responseService);
            contextUtils.when(() -> ContextUtils.getBean(LLMParserConfig.class))
                    .thenReturn(parserConfig);

            new LLMSqlParser().parse(queryCtx);
        }

        verify(requestService, times(2)).runText2SQL(llmReq);
        verify(responseService, never()).addParseInfo(any(), any(), any(), anyDouble(), any());
        assertEquals(ParseResp.ParseState.FAILED, parseResp.getState());
        assertTrue(BankNl2SqlError.isTerminalParserError(parseResp.getErrorMsg()));
    }

    private static LLMResp freeSqlResponse() {
        LLMResp llmResp = new LLMResp();
        llmResp.setSqlOutput("SELECT 1 FROM free_sql");
        llmResp.setSqlRespMap(Map.of("SELECT 1 FROM free_sql",
                LLMSqlResp.builder().sqlWeight(1D).build()));
        return llmResp;
    }
}
