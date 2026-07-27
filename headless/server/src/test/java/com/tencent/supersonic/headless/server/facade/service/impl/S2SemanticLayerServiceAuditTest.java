package com.tencent.supersonic.headless.server.facade.service.impl;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.TaskStatusEnum;
import com.tencent.supersonic.headless.api.pojo.enums.SemanticType;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.chat.knowledge.KnowledgeBaseService;
import com.tencent.supersonic.headless.core.cache.QueryCache;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S2SemanticLayerServiceAuditTest {

    private StatUtils statUtils;
    private AuditEventPublisher auditEventPublisher;
    private QueryCache queryCache;
    private S2SemanticLayerService service;

    @BeforeEach
    void setUp() {
        statUtils = mock(StatUtils.class);
        auditEventPublisher = mock(AuditEventPublisher.class);
        queryCache = mock(QueryCache.class);
        service = new S2SemanticLayerService(statUtils, mock(QueryUtils.class),
                mock(SemanticSchemaManager.class), mock(DataSetService.class),
                mock(SchemaService.class), mock(SemanticTranslator.class),
                mock(MetricDrillDownChecker.class), mock(KnowledgeBaseService.class),
                mock(MetricService.class), mock(DimensionService.class), mock(DomainService.class),
                mock(TranslatorConfig.class), mock(DataMaskingService.class), auditEventPublisher,
                queryCache, List.of());
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
