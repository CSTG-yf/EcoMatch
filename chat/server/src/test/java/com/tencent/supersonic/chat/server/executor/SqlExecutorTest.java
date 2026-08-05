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
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

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
    }
}
