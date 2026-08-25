package com.tencent.supersonic.headless.server.facade.service.impl;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.QueryStat;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.common.pojo.enums.TaskStatusEnum;
import com.tencent.supersonic.headless.api.pojo.enums.SemanticType;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticTranslateResp;
import com.tencent.supersonic.headless.chat.knowledge.KnowledgeBaseService;
import com.tencent.supersonic.headless.core.cache.QueryCache;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import com.tencent.supersonic.headless.core.translator.SemanticTranslator;
import com.tencent.supersonic.headless.core.translator.TranslatorConfig;
import com.tencent.supersonic.headless.server.manager.SemanticSchemaManager;
import com.tencent.supersonic.headless.server.security.DataMaskingService;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.service.DataSetService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import com.tencent.supersonic.headless.server.utils.MetricDrillDownChecker;
import com.tencent.supersonic.headless.server.utils.QueryUtils;
import com.tencent.supersonic.headless.server.utils.StatUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S2SemanticLayerServiceAuditTest {

    private StatUtils statUtils;
    private AuditEventPublisher auditEventPublisher;
    private QueryCache queryCache;
    private SemanticTranslator semanticTranslator;
    private TranslatorConfig translatorConfig;
    private SchemaService schemaService;
    private DataMaskingService dataMaskingService;
    private S2SemanticLayerService service;

    @BeforeEach
    void setUp() {
        statUtils = mock(StatUtils.class);
        auditEventPublisher = mock(AuditEventPublisher.class);
        queryCache = mock(QueryCache.class);
        semanticTranslator = mock(SemanticTranslator.class);
        translatorConfig = mock(TranslatorConfig.class);
        schemaService = mock(SchemaService.class);
        dataMaskingService = mock(DataMaskingService.class);
        when(translatorConfig.getParameterValue(TranslatorConfig.TRANSLATOR_RESULT_LIMIT))
                .thenReturn("1000");
        service = new S2SemanticLayerService(statUtils, mock(QueryUtils.class),
                mock(SemanticSchemaManager.class), mock(DataSetService.class),
                schemaService, semanticTranslator,
                mock(MetricDrillDownChecker.class), mock(KnowledgeBaseService.class),
                mock(MetricService.class), mock(DimensionService.class), mock(DomainService.class),
                translatorConfig, dataMaskingService, auditEventPublisher, queryCache,
                List.of());
    }

    @Test
    void shouldAuditStartedAndSucceededForCachedQuery() {
        User user = User.get(7L, "alice");
        QuerySqlReq request = queryRequest();
        SemanticQueryResp response = queryResponse();
        when(queryCache.getCacheKey(request, user)).thenReturn("cache-key");
        when(queryCache.query(request, "cache-key")).thenReturn(response);

        assertEquals(response, service.queryByReq(request, user));

        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPublisher, times(2)).publishBestEffort(events.capture(), eq(user));
        AuditEvent started = events.getAllValues().get(0);
        AuditEvent succeeded = events.getAllValues().get(1);
        assertEquals(AuditEventType.QUERY_STARTED, started.getEventType());
        assertEquals("SELECT revenue FROM ds_9 WHERE account_no = '6222000012345678'",
                started.getRawSql());
        assertEquals(AuditEventType.QUERY_SUCCEEDED, succeeded.getEventType());
        assertEquals("SELECT revenue FROM physical_table", succeeded.getRawSql());
        assertEquals(1, succeeded.getMetadata().get("rowCount"));
        assertEquals(1, succeeded.getMetadata().get("columnCount"));
        assertEquals(true, succeeded.getMetadata().get("cacheHit"));
        assertEquals("MASKED_FIELDS:1", succeeded.getMaskingSummary());
        assertTrue(succeeded.getMetricCodes().contains("revenue"));
        verify(statUtils).statInfo2DbAsync(TaskStatusEnum.SUCCESS);
    }

    @Test
    void shouldAuditFailureAndRethrowQueryException() {
        User user = User.get(7L, "alice");
        QuerySqlReq request = queryRequest();
        when(queryCache.getCacheKey(request, user))
                .thenThrow(new IllegalStateException("cache unavailable"));

        assertThrows(IllegalStateException.class, () -> service.queryByReq(request, user));

        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPublisher, times(2)).publishBestEffort(events.capture(), eq(user));
        AuditEvent failed = events.getAllValues().get(1);
        assertEquals(AuditEventType.QUERY_FAILED, failed.getEventType());
        assertEquals("QUERY_EXCEPTION", failed.getReasonCode());
        assertEquals("IllegalStateException", failed.getMetadata().get("exceptionType"));
        assertEquals(request.getSql(), failed.getRawSql());
        verify(statUtils).statInfo2DbAsync(TaskStatusEnum.ERROR);
    }

    @Test
    void shouldNotMaskTrustedResultOnlyFactsBeforeCaching() {
        User user = User.get(7L, "alice");
        QuerySqlReq request = queryRequest();
        request.setNeedAuth(false);
        SemanticQueryResp response = queryResponse();
        response.getResultList().get(0).put("revenue", 41.96D);
        response.setDataMasked(false);
        response.setMaskedColumns(Set.of());

        ReflectionTestUtils.invokeMethod(service, "maskBeforeCache", request, response, user);

        assertEquals(41.96D, response.getResultList().get(0).get("revenue"));
        verify(dataMaskingService, never()).mask(any(), any(), eq(user), any());
    }

    @Test
    void shouldNotOverrideWithUntrustedStoredPhysicalSql() throws Exception {
        QuerySqlReq request = modelScopedRequest();
        translateToScopeSql();

        SemanticTranslateResp response = service.translate(request, User.get(7L, "alice"));

        assertEquals(request.getSql(), response.getQuerySQL());
        verify(semanticTranslator).translate(any(QueryStatement.class));
    }

    @Test
    void shouldNotOverrideRowPermissionSqlWithAnyStoredPhysicalSql() throws Exception {
        QuerySqlReq request = modelScopedRequest();
        request.setTrustedCompiledSql(true);
        request.setRowPermissionApplied(true);
        request.getSqlInfo().setCorrectedQuerySQL("SELECT repaired FROM physical_account");
        translateToScopeSql();

        SemanticTranslateResp response = service.translate(request, User.get(7L, "alice"));

        assertEquals(request.getSql(), response.getQuerySQL());
        verify(semanticTranslator).translate(any(QueryStatement.class));
    }

    @Test
    void shouldReuseTrustedStoredPhysicalSqlWithoutRowPermission() throws Exception {
        QuerySqlReq request = modelScopedRequest();
        request.setTrustedCompiledSql(true);

        SemanticTranslateResp response = service.translate(request, User.get(7L, "alice"));

        assertEquals(request.getSqlInfo().getQuerySQL(), response.getQuerySQL());
    }

    @Test
    void shouldExecuteCorrectedPhysicalSqlAsUntrusted() throws Exception {
        QuerySqlReq request = modelScopedRequest();
        request.setTrustedCompiledSql(true);
        request.getSqlInfo().setCorrectedQuerySQL("SELECT repaired FROM physical_account");

        SemanticTranslateResp response = service.translate(request, User.get(7L, "alice"));

        ArgumentCaptor<QueryStatement> statementCaptor =
                ArgumentCaptor.forClass(QueryStatement.class);
        verify(semanticTranslator).translate(statementCaptor.capture());
        assertEquals(request.getSqlInfo().getCorrectedQuerySQL(), response.getQuerySQL());
        assertFalse(statementCaptor.getValue().isTrustedCompiledSql());
        assertTrue(statementCaptor.getValue().isTranslated());
    }

    @Test
    void shouldAuditNoQueryExecutorOnlyOnce() throws Exception {
        User user = User.get(7L, "alice");
        QuerySqlReq request = modelScopedRequest();
        request.getSqlInfo().setQuerySQL(null);
        when(queryCache.getCacheKey(request, user)).thenReturn("cache-key");
        doAnswer(invocation -> {
            QueryStatement statement = invocation.getArgument(0);
            statement.setSql(statement.getSqlQuery().getSql());
            statement.setIsTranslated(true);
            return null;
        }).when(semanticTranslator).translate(any(QueryStatement.class));
        StatUtils.set(new QueryStat());

        assertThrows(IllegalStateException.class, () -> service.queryByReq(request, user));

        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPublisher, times(2)).publishBestEffort(events.capture(), eq(user));
        List<AuditEvent> failedEvents = events.getAllValues().stream()
                .filter(event -> event.getEventType() == AuditEventType.QUERY_FAILED).toList();
        assertEquals(1, failedEvents.size());
        assertEquals("NO_QUERY_EXECUTOR", failedEvents.get(0).getReasonCode());
        verify(statUtils).statInfo2DbAsync(TaskStatusEnum.ERROR);
        StatUtils.remove();
    }

    private void translateToScopeSql() throws Exception {
        doAnswer(invocation -> {
            QueryStatement statement = invocation.getArgument(0);
            statement.setSql(statement.getSqlQuery().getSql());
            statement.setIsTranslated(true);
            return null;
        }).when(semanticTranslator).translate(any(QueryStatement.class));
    }

    private QuerySqlReq modelScopedRequest() {
        QuerySqlReq request = new QuerySqlReq();
        request.setSql("SELECT revenue FROM account WHERE branch_id = '001'");
        request.setModelIds(Set.of(3L));
        SqlInfo sqlInfo = new SqlInfo();
        sqlInfo.setQuerySQL("WITH ranked AS (SELECT revenue FROM physical_account) SELECT * FROM ranked");
        request.setSqlInfo(sqlInfo);
        return request;
    }

    private QuerySqlReq queryRequest() {
        QuerySqlReq request = new QuerySqlReq();
        request.setSql("SELECT revenue FROM ds_9 WHERE account_no = '6222000012345678'");
        request.setDataSetId(9L);
        request.setModelIds(Set.of(3L));
        return request;
    }

    private SemanticQueryResp queryResponse() {
        SemanticQueryResp response = new SemanticQueryResp();
        QueryColumn column = new QueryColumn("revenue", "BIGINT", "revenue");
        column.setShowType(SemanticType.NUMBER.name());
        response.setColumns(List.of(column));
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("revenue", "****");
        response.setResultList(List.of(row));
        response.setSql("SELECT revenue FROM physical_table");
        response.setDataMasked(true);
        response.setMaskedColumns(Set.of("revenue"));
        return response;
    }
}
