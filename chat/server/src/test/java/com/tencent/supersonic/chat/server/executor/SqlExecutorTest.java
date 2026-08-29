package com.tencent.supersonic.chat.server.executor;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.pojo.ChatContext;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.service.ChatContextService;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.chat.corrector.LLMPhysicalSqlCorrector;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlQuery;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlExecutorTest {

    @Test
    void doesNotUsePhysicalSqlRepairForAConstrainedBankPlan() {
        SemanticParseInfo bankPlanParse = new SemanticParseInfo();
        bankPlanParse.getProperties().put(BankPlanToolResult.PROPERTY_KEY,
                BankPlanToolResult.started(1, "trace", "fingerprint", "STRUCT",
                        List.of("metric_value")));

        assertFalse(SqlExecutor.shouldAttemptPhysicalSqlRepair(bankPlanParse));
        bankPlanParse.getProperties().put(BankPlanToolResult.PROPERTY_KEY,
                Map.of("malformed", true));
        assertFalse(SqlExecutor.shouldAttemptPhysicalSqlRepair(bankPlanParse));
        assertTrue(SqlExecutor.shouldAttemptPhysicalSqlRepair(new SemanticParseInfo()));
    }

    @Test
    void sendsCorrectedS2SqlForAuthorizationWhilePreservingPhysicalSqlInfo() throws Exception {
        String correctedS2Sql = "SELECT `存款余额` FROM `银行指标`";
        String physicalSql = "SELECT deposit_balance FROM bank_metric";
        SemanticLayerService semanticLayer = mock(SemanticLayerService.class);
        ChatContextService chatContextService = mock(ChatContextService.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(SemanticLayerService.class)).thenReturn(semanticLayer);
        when(applicationContext.getBean(ChatContextService.class)).thenReturn(chatContextService);
        new ContextUtils().setApplicationContext(applicationContext);

        User user = User.get(1L, "tester");
        when(chatContextService.getOrCreateContext(7)).thenReturn(new ChatContext());
        when(semanticLayer.queryByReq(any(SemanticQueryReq.class), eq(user)))
                .thenReturn(new SemanticQueryResp());

        SemanticParseInfo parseInfo = new SemanticParseInfo();
        SqlInfo sqlInfo = parseInfo.getSqlInfo();
        sqlInfo.setCorrectedS2SQL(correctedS2Sql);
        sqlInfo.setQuerySQL(physicalSql);
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY,
                BankPlanToolResult.started(1, "trace-success", "fingerprint-1", "STRUCT",
                        List.of("metric_value")));
        ExecuteContext executeContext = new ExecuteContext(ChatExecuteReq.builder()
                .user(user).chatId(7).queryId(9L).queryText("查询存款余额").build());
        executeContext.setParseInfo(parseInfo);

        new SqlExecutor().execute(executeContext);

        ArgumentCaptor<SemanticQueryReq> requestCaptor =
                ArgumentCaptor.forClass(SemanticQueryReq.class);
        verify(semanticLayer).queryByReq(requestCaptor.capture(), eq(user));
        QuerySqlReq scopeRequest = (QuerySqlReq) requestCaptor.getValue();
        assertEquals(correctedS2Sql, scopeRequest.getSql());
        assertSame(sqlInfo, scopeRequest.getSqlInfo());
        assertEquals(physicalSql, scopeRequest.getSqlInfo().getQuerySQL());
        assertTrue(scopeRequest.isNeedAuth());
        assertTrue(scopeRequest.isTrustedCompiledSql());
        BankPlanToolResult toolResult =
                (BankPlanToolResult) parseInfo.getProperties().get(BankPlanToolResult.PROPERTY_KEY);
        assertEquals(BankPlanToolResult.Status.IN_PROGRESS, toolResult.getStatus());
        assertEquals(List.of(BankPlanToolResult.Stage.SQL_SAFETY,
                BankPlanToolResult.Stage.DATABASE_PREPARE,
                BankPlanToolResult.Stage.DATABASE_EXECUTE),
                toolResult.getStageResults().stream().map(BankPlanToolResult.StageResult::getStage)
                        .filter(stage -> stage.ordinal() >= BankPlanToolResult.Stage.SQL_SAFETY.ordinal())
                        .toList());
    }

    @Test
    void disablesAuthorizationAndMaskingOnlyForResultOnlyExecution() throws Exception {
        SemanticLayerService semanticLayer = mock(SemanticLayerService.class);
        ChatContextService chatContextService = mock(ChatContextService.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(SemanticLayerService.class)).thenReturn(semanticLayer);
        when(applicationContext.getBean(ChatContextService.class)).thenReturn(chatContextService);
        new ContextUtils().setApplicationContext(applicationContext);

        User user = User.get(1L, "tester");
        when(chatContextService.getOrCreateContext(7)).thenReturn(new ChatContext());
        when(semanticLayer.queryByReq(any(SemanticQueryReq.class), eq(user)))
                .thenReturn(new SemanticQueryResp());
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getSqlInfo().setCorrectedS2SQL("SELECT deposit_balance FROM bank_metric");
        ExecuteContext executeContext = new ExecuteContext(ChatExecuteReq.builder().user(user)
                .chatId(7).queryId(9L).resultOnly(true).build());
        executeContext.setParseInfo(parseInfo);

        new SqlExecutor().execute(executeContext);

        ArgumentCaptor<SemanticQueryReq> requestCaptor =
                ArgumentCaptor.forClass(SemanticQueryReq.class);
        verify(semanticLayer).queryByReq(requestCaptor.capture(), eq(user));
        assertFalse(requestCaptor.getValue().isNeedAuth());
    }

    @Test
    void retriesWithCorrectedPhysicalSqlAsUntrusted() throws Exception {
        String scopeSql = "SELECT metric FROM semantic_account";
        String originalPhysicalSql = "SELECT metric FROM physical_account";
        String repairedPhysicalSql = "SELECT metric FROM physical_account WHERE active = 1";
        SemanticLayerService semanticLayer = mock(SemanticLayerService.class);
        ChatContextService chatContextService = mock(ChatContextService.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(SemanticLayerService.class)).thenReturn(semanticLayer);
        when(applicationContext.getBean(ChatContextService.class)).thenReturn(chatContextService);
        new ContextUtils().setApplicationContext(applicationContext);

        User user = User.get(1L, "tester");
        when(chatContextService.getOrCreateContext(7)).thenReturn(new ChatContext());
        SemanticQueryResp failed = new SemanticQueryResp();
        failed.setErrorMsg("syntax error");
        SemanticQueryResp succeeded = new SemanticQueryResp();
        List<Boolean> trustedAtExecution = new ArrayList<>();
        List<String> correctedSqlAtExecution = new ArrayList<>();
        doAnswer(invocation -> {
            QuerySqlReq request = invocation.getArgument(0);
            trustedAtExecution.add(request.isTrustedCompiledSql());
            correctedSqlAtExecution.add(request.getSqlInfo().getCorrectedQuerySQL());
            return trustedAtExecution.size() == 1 ? failed : succeeded;
        }).when(semanticLayer).queryByReq(any(SemanticQueryReq.class), eq(user));

        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setQueryMode(LLMSqlQuery.QUERY_MODE);
        parseInfo.getSqlInfo().setCorrectedS2SQL(scopeSql);
        parseInfo.getSqlInfo().setQuerySQL(originalPhysicalSql);
        Agent agent = new Agent();
        ChatApp repairApp = mock(ChatApp.class);
        agent.setChatAppConfig(Map.of(LLMPhysicalSqlCorrector.EXECUTION_APP_KEY, repairApp));
        ExecuteContext executeContext = new ExecuteContext(ChatExecuteReq.builder()
                .user(user).chatId(7).queryId(9L).queryText("query").build());
        executeContext.setAgent(agent);
        executeContext.setParseInfo(parseInfo);

        try (MockedStatic<LLMPhysicalSqlCorrector> corrector =
                org.mockito.Mockito.mockStatic(LLMPhysicalSqlCorrector.class)) {
            corrector.when(() -> LLMPhysicalSqlCorrector.repairExecutionError(repairApp, "query",
                    originalPhysicalSql, "syntax error")).thenReturn(repairedPhysicalSql);

            QueryResult result = ReflectionTestUtils.invokeMethod(new SqlExecutor(), "doExecute",
                    executeContext);

            assertEquals(repairedPhysicalSql, result.getQuerySql());
        }
        assertEquals(List.of(true, false), trustedAtExecution);
        assertEquals(java.util.Arrays.asList(null, repairedPhysicalSql), correctedSqlAtExecution);
        assertEquals(repairedPhysicalSql, parseInfo.getSqlInfo().getCorrectedQuerySQL());
        assertEquals(repairedPhysicalSql, parseInfo.getSqlInfo().getQuerySQL());
        assertFalse(trustedAtExecution.get(1));
    }

    @Test
    void persistsOnlyAllowlistedExecutionTelemetryWhenExecutionFails() throws Exception {
        SemanticLayerService semanticLayer = mock(SemanticLayerService.class);
        ChatContextService chatContextService = mock(ChatContextService.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(SemanticLayerService.class)).thenReturn(semanticLayer);
        when(applicationContext.getBean(ChatContextService.class)).thenReturn(chatContextService);
        new ContextUtils().setApplicationContext(applicationContext);

        User user = User.get(1L, "tester");
        when(chatContextService.getOrCreateContext(7)).thenReturn(new ChatContext());
        SemanticQueryResp failed = new SemanticQueryResp();
        failed.setErrorMsg("opaque-details");
        failed.setExecutionTelemetry(Map.of("failureLayer", "JDBC_GRAMMAR"));
        when(semanticLayer.queryByReq(any(SemanticQueryReq.class), eq(user))).thenReturn(failed);

        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getSqlInfo().setCorrectedS2SQL("SELECT metric");
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY,
                BankPlanToolResult.started(1, "trace-failure", "fingerprint-1", "STRUCT",
                        List.of("metric_value")));
        ExecuteContext executeContext = new ExecuteContext(ChatExecuteReq.builder()
                .user(user).chatId(7).queryId(9L).queryText("query").build());
        executeContext.setParseInfo(parseInfo);

        new SqlExecutor().execute(executeContext);

        @SuppressWarnings("unchecked")
        Map<String, Object> telemetry =
                (Map<String, Object>) parseInfo.getProperties().get("executionTelemetry");
        assertEquals(Map.of("failureLayer", "JDBC_GRAMMAR", "repairAttempted", false,
                "repaired", false), telemetry);
        assertTrue(!parseInfo.getProperties().containsKey("sqlExecutionFeedback"));
        BankPlanToolResult toolResult =
                (BankPlanToolResult) parseInfo.getProperties().get(BankPlanToolResult.PROPERTY_KEY);
        assertEquals(BankPlanToolResult.Status.FAILED, toolResult.getStatus());
        assertEquals(BankPlanToolResult.Stage.DATABASE_EXECUTE, toolResult.getFailedStage());
        assertEquals("JDBC_GRAMMAR", toolResult.getErrorCode());
        // The raw execution error is the dynamic root-cause channel (hints), while the toolResult
        // message keeps the generic contract text.
        assertTrue(toolResult.toRepairFeedback().contains("failed_layer=JDBC_GRAMMAR"));
        assertTrue(toolResult.toRepairFeedback().contains("root_message=opaque-details"));
        assertEquals("数据库执行失败，请根据允许值修正完整计划。", toolResult.getMessage());
        assertTrue(!toolResult.getMessage().contains("opaque-details"));
        assertEquals(List.of("failed_layer=JDBC_GRAMMAR", "root_message=opaque-details",
                "根据失败阶段重新生成完整 BankQueryPlan"), toolResult.getCorrectionHints());
    }

    @Test
    void safetyPolicyFailureCarriesLayerAndTruncatesLongRootMessage() throws Exception {
        SemanticLayerService semanticLayer = mock(SemanticLayerService.class);
        ChatContextService chatContextService = mock(ChatContextService.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(SemanticLayerService.class)).thenReturn(semanticLayer);
        when(applicationContext.getBean(ChatContextService.class)).thenReturn(chatContextService);
        new ContextUtils().setApplicationContext(applicationContext);

        User user = User.get(1L, "tester");
        when(chatContextService.getOrCreateContext(7)).thenReturn(new ChatContext());
        String longMessage = "SQL policy violation: " + "x".repeat(260);
        SemanticQueryResp failed = new SemanticQueryResp();
        failed.setErrorMsg(longMessage);
        failed.setExecutionTelemetry(Map.of("failureLayer", "SQL_SAFETY_POLICY"));
        when(semanticLayer.queryByReq(any(SemanticQueryReq.class), eq(user))).thenReturn(failed);

        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getSqlInfo().setCorrectedS2SQL("SELECT metric");
        parseInfo.getProperties().put(BankPlanToolResult.PROPERTY_KEY,
                BankPlanToolResult.started(1, "trace-safety", "fingerprint-1", "STRUCT",
                        List.of("metric_value")));
        ExecuteContext executeContext = new ExecuteContext(ChatExecuteReq.builder()
                .user(user).chatId(7).queryId(9L).queryText("query").build());
        executeContext.setParseInfo(parseInfo);

        new SqlExecutor().execute(executeContext);

        BankPlanToolResult toolResult =
                (BankPlanToolResult) parseInfo.getProperties().get(BankPlanToolResult.PROPERTY_KEY);
        assertEquals(BankPlanToolResult.Status.FAILED, toolResult.getStatus());
        assertEquals(BankPlanToolResult.Stage.SQL_SAFETY, toolResult.getFailedStage());
        assertEquals("SQL_SAFETY_POLICY", toolResult.getErrorCode());
        assertEquals("SQL 安全检查失败，请修正计划而不是直接生成 SQL。", toolResult.getMessage());
        assertEquals(List.of("failed_layer=SQL_SAFETY_POLICY",
                "root_message=" + longMessage.substring(0, 200),
                "只修正 BankQueryPlan，不要直接生成或修改物理 SQL"), toolResult.getCorrectionHints());
        String feedback = toolResult.toRepairFeedback();
        assertTrue(feedback.contains("failed_layer=SQL_SAFETY_POLICY"));
        assertTrue(feedback.contains("root_message=" + longMessage.substring(0, 200)));
        assertFalse(feedback.contains(longMessage));
    }
}
