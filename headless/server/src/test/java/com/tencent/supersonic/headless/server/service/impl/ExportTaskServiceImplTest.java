package com.tencent.supersonic.headless.server.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.enums.ExportChartType;
import com.tencent.supersonic.headless.api.pojo.enums.ExportFormat;
import com.tencent.supersonic.headless.api.pojo.enums.ExportResourceType;
import com.tencent.supersonic.headless.api.pojo.enums.ExportStatus;
import com.tencent.supersonic.headless.api.pojo.request.ExportChartReq;
import com.tencent.supersonic.headless.api.pojo.request.ExportCreateReq;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.response.ExportTaskResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.persistence.dataobject.ExportTaskDO;
import com.tencent.supersonic.headless.server.persistence.mapper.ExportTaskMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.service.DashboardService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportTaskServiceImplTest {

    @TempDir
    Path exportRoot;

    private ExportTaskMapper mapper;
    private SemanticLayerService semanticLayerService;
    private AuditEventPublisher auditPublisher;
    private ExportTaskServiceImpl service;
    private ExportTaskDO persisted;

    @BeforeEach
    void setUp() {
        mapper = mock(ExportTaskMapper.class);
        semanticLayerService = mock(SemanticLayerService.class);
        auditPublisher = mock(AuditEventPublisher.class);
        service = new ExportTaskServiceImpl(mapper, semanticLayerService,
                mock(DashboardService.class), mock(DashboardExportQueryValidator.class),
                auditPublisher, exportRoot.toString());
        when(mapper.insert(any(ExportTaskDO.class))).thenAnswer(invocation -> {
            persisted = invocation.getArgument(0);
            persisted.setId(1L);
            return 1;
        });
        when(mapper.updateById(any(ExportTaskDO.class))).thenReturn(1);
    }

    @AfterEach
    void cleanFiles() throws Exception {
        try (var paths = Files.list(exportRoot)) {
            paths.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Test
    void queryXlsxReexecutesAuthorizedQueryAndEscapesFormulaCells() throws Exception {
        QueryStructReq structured = mock(QueryStructReq.class);
        QuerySqlReq sql = new QuerySqlReq();
        sql.setSql("SELECT account_name FROM ds_1");
        when(structured.convert(true)).thenReturn(sql);
        when(structured.getLimit()).thenReturn(100L);
        when(semanticLayerService.queryByReq(any(), any())).thenReturn(maskedResult("=2+2"));

        ExportTaskResp response =
                service.create(request(ExportFormat.XLSX, structured), user("alice"));

        assertEquals(ExportStatus.SUCCEEDED, response.getStatus());
        assertTrue(response.isDownloadable());
        assertTrue(sql.isNeedAuth());
        Path file = exportRoot.resolve(persisted.getStorageKey());
        try (InputStream input = Files.newInputStream(file);
                XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            assertEquals("'=2+2",
                    workbook.getSheet("Query 1").getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void queryPdfIsGeneratedAndContainsNoPersistedRequestSnapshot() throws Exception {
        QueryStructReq structured = mock(QueryStructReq.class);
        QuerySqlReq sql = new QuerySqlReq();
        sql.setSql("SELECT account_name FROM ds_1");
        when(structured.convert(true)).thenReturn(sql);
        when(structured.getLimit()).thenReturn(100L);
        when(semanticLayerService.queryByReq(any(), any())).thenReturn(maskedResult("masked"));

        ExportCreateReq request = request(ExportFormat.PDF, structured);
        ExportChartReq chart = new ExportChartReq();
        chart.setQueryIndex(0);
        chart.setType(ExportChartType.BAR);
        chart.setTitle("Account distribution");
        chart.setCategoryField("account_name");
        chart.setValueField("balance");
        request.setCharts(List.of(chart));
        when(semanticLayerService.queryByReq(any(), any())).thenReturn(chartResult());

        ExportTaskResp response = service.create(request, user("alice"));

        assertEquals(ExportStatus.SUCCEEDED, response.getStatus());
        Path pdf = exportRoot.resolve(persisted.getStorageKey());
        byte[] prefix = Files.readAllBytes(pdf);
        assertTrue(new String(prefix, 0, 5).startsWith("%PDF"));
        try (PDDocument document = PDDocument.load(pdf.toFile())) {
            assertEquals(2, document.getNumberOfPages());
        }
        assertFalse(ExportTaskDO.class.getDeclaredFields().length == 0);
        assertThrows(NoSuchFieldException.class,
                () -> ExportTaskDO.class.getDeclaredField("requestJson"));
    }

    @Test
    void taskCannotBeReadByAnotherUser() {
        ExportTaskDO task = new ExportTaskDO();
        task.setTaskId("a5ae39bd-55fc-4805-97ba-f4b409924bf0");
        task.setOwner("alice");
        task.setStatus(ExportStatus.SUCCEEDED.name());
        task.setResourceType(ExportResourceType.QUERY.name());
        task.setFormat(ExportFormat.XLSX.name());
        task.setExpiresAt(new java.util.Date(System.currentTimeMillis() + 60_000));
        when(mapper.selectOne(any())).thenReturn(task);

        assertThrows(InvalidPermissionException.class,
                () -> service.get(task.getTaskId(), user("bob")));
    }

    @Test
    void listExpiresFilesBeforeTheyCanBeDownloaded() {
        ExportTaskDO newest = task("newest-task", "alice", ExportStatus.SUCCEEDED,
                new java.util.Date(System.currentTimeMillis() + 60_000));
        newest.setStorageKey("cfba75d5-5a22-4b5d-9f46-68f561ea9141.xlsx");
        ExportTaskDO expired = task("expired-task", "alice", ExportStatus.SUCCEEDED,
                new java.util.Date(System.currentTimeMillis() - 60_000));
        expired.setStorageKey("d78f79fe-0a1c-4e0f-a8d8-09ea3a7b5c30.xlsx");
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            Page<ExportTaskDO> taskPage = PageHelper.getLocalPage();
            taskPage.add(newest);
            taskPage.add(expired);
            taskPage.setTotal(2);
            return taskPage;
        });

        PageInfo<ExportTaskResp> page = service.list(1, 20, user("alice"));

        assertEquals(List.of("newest-task", "expired-task"),
                page.getList().stream().map(ExportTaskResp::getTaskId).toList());
        assertTrue(page.getList().get(0).isDownloadable());
        assertEquals(ExportStatus.EXPIRED, page.getList().get(1).getStatus());
        assertFalse(page.getList().get(1).isDownloadable());
        verify(mapper).selectList(any());
        verify(mapper).updateById(expired);
    }

    @Test
    void listRejectsUnsafePagination() {
        assertThrows(InvalidArgumentException.class, () -> service.list(0, 20, user("alice")));
        assertThrows(InvalidArgumentException.class, () -> service.list(1, 101, user("alice")));
    }

    @Test
    void auditRecordsStartedAndSucceededWithoutCellValues() throws Exception {
        QueryStructReq structured = mock(QueryStructReq.class);
        when(structured.getLimit()).thenReturn(10L);
        when(structured.convert(true)).thenReturn(new QuerySqlReq());
        when(semanticLayerService.queryByReq(any(), any()))
                .thenReturn(maskedResult("customer-secret"));

        service.create(request(ExportFormat.XLSX, structured), user("alice"));

        ArgumentCaptor<com.tencent.supersonic.headless.server.security.audit.model.AuditEvent> events =
                ArgumentCaptor.forClass(
                        com.tencent.supersonic.headless.server.security.audit.model.AuditEvent.class);
        verify(auditPublisher, org.mockito.Mockito.times(2)).publishRequired(events.capture(),
                any());
        assertNotEquals("customer-secret", events.getAllValues().toString());
    }

    @Test
    void pdfRowLimitFailsAndLeavesNoFile() throws Exception {
        QueryStructReq structured = mock(QueryStructReq.class);
        when(structured.getLimit()).thenReturn(1_000L);
        when(structured.convert(true)).thenReturn(new QuerySqlReq());
        SemanticQueryResp result = maskedResult("masked");
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("account_name", "masked");
        result.setResultList(java.util.Collections.nCopies(501, row));
        when(semanticLayerService.queryByReq(any(), any())).thenReturn(result);

        assertThrows(InvalidArgumentException.class,
                () -> service.create(request(ExportFormat.PDF, structured), user("alice")));

        assertEquals(ExportStatus.FAILED.name(), persisted.getStatus());
        assertEquals("EXPORT_LIMIT_OR_REQUEST_INVALID", persisted.getFailureCode());
        try (var files = Files.list(exportRoot)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void downloadStreamsOnlyOwnedUnexpiredFile() throws Exception {
        QueryStructReq structured = mock(QueryStructReq.class);
        when(structured.getLimit()).thenReturn(10L);
        when(structured.convert(true)).thenReturn(new QuerySqlReq());
        when(semanticLayerService.queryByReq(any(), any())).thenReturn(maskedResult("masked"));
        ExportTaskResp created =
                service.create(request(ExportFormat.XLSX, structured), user("alice"));
        when(mapper.selectOne(any())).thenReturn(persisted);
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();

        service.download(created.getTaskId(), user("alice"), response);

        assertTrue(response.getContentAsByteArray().length > 0);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertThrows(InvalidPermissionException.class,
                () -> service.download(created.getTaskId(), user("bob"), response));
    }

    @Test
    void requiredSuccessAuditFailureDeletesGeneratedFile() throws Exception {
        QueryStructReq structured = mock(QueryStructReq.class);
        when(structured.getLimit()).thenReturn(10L);
        when(structured.convert(true)).thenReturn(new QuerySqlReq());
        when(semanticLayerService.queryByReq(any(), any())).thenReturn(maskedResult("masked"));
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("audit unavailable");
            }
            return "event";
        }).when(auditPublisher).publishRequired(any(), any());

        assertThrows(IllegalStateException.class,
                () -> service.create(request(ExportFormat.XLSX, structured), user("alice")));

        assertEquals(ExportStatus.FAILED.name(), persisted.getStatus());
        try (var files = Files.list(exportRoot)) {
            assertEquals(0, files.count());
        }
    }

    private ExportCreateReq request(ExportFormat format, QueryStructReq structured) {
        ExportCreateReq request = new ExportCreateReq();
        request.setResourceType(ExportResourceType.QUERY);
        request.setFormat(format);
        request.setTitle("Bank report");
        request.setQueries(List.of(structured));
        return request;
    }

    private ExportTaskDO task(String taskId, String owner, ExportStatus status,
            java.util.Date expiresAt) {
        ExportTaskDO task = new ExportTaskDO();
        task.setTaskId(taskId);
        task.setOwner(owner);
        task.setStatus(status.name());
        task.setResourceType(ExportResourceType.QUERY.name());
        task.setFormat(ExportFormat.XLSX.name());
        task.setCreatedAt(new java.util.Date());
        task.setExpiresAt(expiresAt);
        return task;
    }

    private SemanticQueryResp maskedResult(String value) {
        QueryColumn column = new QueryColumn("Account", "VARCHAR", "account_name");
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("account_name", value);
        SemanticQueryResp response = new SemanticQueryResp();
        response.setColumns(List.of(column));
        response.setResultList(List.of(row));
        response.setDataMasked(true);
        response.setMaskedColumns(Set.of("account_name"));
        return response;
    }

    private SemanticQueryResp chartResult() {
        QueryColumn category = new QueryColumn("Account", "VARCHAR", "account_name");
        QueryColumn value = new QueryColumn("Balance", "DECIMAL", "balance");
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("account_name", "A");
        row.put("balance", 100);
        SemanticQueryResp response = new SemanticQueryResp();
        response.setColumns(List.of(category, value));
        response.setResultList(List.of(row));
        return response;
    }

    private User user(String name) {
        return User.get(1L, name);
    }
}
