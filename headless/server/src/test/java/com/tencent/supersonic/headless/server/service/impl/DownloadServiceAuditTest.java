package com.tencent.supersonic.headless.server.service.impl;

import com.alibaba.excel.util.FileUtils;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.enums.SemanticType;
import com.tencent.supersonic.headless.api.pojo.request.DownloadMetricReq;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadServiceAuditTest {

    private MetricService metricService;
    private SemanticLayerService queryService;
    private AuditEventPublisher auditEventPublisher;
    private DownloadServiceImpl service;
    private DownloadMetricReq request;

    @BeforeEach
    void setUp() throws Exception {
        metricService = mock(MetricService.class);
        queryService = mock(SemanticLayerService.class);
        auditEventPublisher = mock(AuditEventPublisher.class);
        service = new DownloadServiceImpl(metricService, mock(DimensionService.class), queryService,
                auditEventPublisher);
        request = new DownloadMetricReq();
        request.setDomainId(12L);
        request.setMetricIds(List.of(21L));
        request.setMetricNames(List.of("revenue"));

        QueryStructReq structRequest = mock(QueryStructReq.class);
        QuerySqlReq sqlRequest = new QuerySqlReq();
        sqlRequest.setSql("SELECT revenue FROM ds_12");
        when(structRequest.convert(true)).thenReturn(sqlRequest);
        when(metricService.convert(request)).thenReturn(structRequest);
        when(queryService.queryByReq(any(), any())).thenReturn(queryResponse());
    }

    @Test
    void shouldRecordSuccessOnlyAfterResponseIsWrittenAndDeleteTemporaryFile() throws Exception {
        Path temporaryFile = Files.createTempFile("audit-export-success-", ".xlsx");
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (MockedStatic<FileUtils> fileUtils = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
            fileUtils.when(() -> FileUtils.createTmpFile(any())).thenReturn(temporaryFile.toFile());

            service.downloadByStruct(request, User.get(1L, "alice"), response);
        }

        assertTrue(response.getContentAsByteArray().length > 0);
        assertFalse(Files.exists(temporaryFile));
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPublisher, times(2)).publishRequired(events.capture(), any());
        assertEquals(AuditEventType.EXPORT_STARTED, events.getAllValues().get(0).getEventType());
        AuditEvent succeeded = events.getAllValues().get(1);
        assertEquals(AuditEventType.EXPORT_SUCCEEDED, succeeded.getEventType());
        assertEquals(1L, succeeded.getExportRowCount());
        assertTrue(succeeded.getFileSize() > 0);
        assertEquals("MASKED_FIELDS:1", succeeded.getMaskingSummary());
    }

    @Test
    void shouldRecordFailureRethrowAndDeleteTemporaryFileWhenResponseWriteFails() throws Exception {
        Path temporaryFile = Files.createTempFile("audit-export-failure-", ".xlsx");
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("response closed"));
        try (MockedStatic<FileUtils> fileUtils = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
            fileUtils.when(() -> FileUtils.createTmpFile(any())).thenReturn(temporaryFile.toFile());

            assertThrows(IOException.class,
                    () -> service.downloadByStruct(request, User.get(1L, "alice"), response));
        }

        assertFalse(Files.exists(temporaryFile));
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPublisher, times(2)).publishRequired(events.capture(), any());
        assertEquals(AuditEventType.EXPORT_STARTED, events.getAllValues().get(0).getEventType());
        AuditEvent failed = events.getAllValues().get(1);
        assertEquals(AuditEventType.EXPORT_FAILED, failed.getEventType());
        assertEquals("IOException", failed.getMetadata().get("exceptionType"));
    }

    private SemanticQueryResp queryResponse() {
        SemanticQueryResp response = new SemanticQueryResp();
        QueryColumn column = new QueryColumn("revenue", "BIGINT", "revenue");
        column.setShowType(SemanticType.NUMBER.name());
        response.setColumns(List.of(column));
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("revenue", "****");
        response.setResultList(List.of(row));
        response.setDataMasked(true);
        response.setMaskedColumns(Set.of("revenue"));
        return response;
    }
}
