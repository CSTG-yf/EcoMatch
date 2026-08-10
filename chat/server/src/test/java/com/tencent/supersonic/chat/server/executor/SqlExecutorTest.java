package com.tencent.supersonic.chat.server.executor;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.server.pojo.ChatContext;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.service.ChatContextService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanToolResult;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlExecutorTest {

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
        assertTrue(parseInfo.getProperties().values().stream()
                .noneMatch(value -> String.valueOf(value).contains("opaque-details")));
        assertTrue(!parseInfo.getProperties().containsKey("sqlExecutionFeedback"));
        BankPlanToolResult toolResult =
                (BankPlanToolResult) parseInfo.getProperties().get(BankPlanToolResult.PROPERTY_KEY);
        assertEquals(BankPlanToolResult.Status.FAILED, toolResult.getStatus());
        assertEquals(BankPlanToolResult.Stage.DATABASE_EXECUTE, toolResult.getFailedStage());
        assertEquals("JDBC_GRAMMAR", toolResult.getErrorCode());
        assertTrue(toolResult.toRepairFeedback().contains("JDBC_GRAMMAR"));
        assertTrue(!toolResult.toRepairFeedback().contains("opaque-details"));
    }
}
