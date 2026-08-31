package com.tencent.supersonic.headless.server.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.DataFormat;
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
import com.tencent.supersonic.headless.api.pojo.response.ChatSnapshotExportData;
import com.tencent.supersonic.headless.api.pojo.response.ExportTaskResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.persistence.dataobject.ExportTaskDO;
import com.tencent.supersonic.headless.server.persistence.mapper.ExportTaskMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.service.ChatSnapshotExportResolver;
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
import static org.mockito.ArgumentMatchers.anyLong;
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
    private org.springframework.beans.factory.ObjectProvider<ChatSnapshotExportResolver> snapshotResolvers;
    private ExportTaskServiceImpl service;
    private ExportTaskDO persisted;

    @BeforeEach
    void setUp() {
        mapper = mock(ExportTaskMapper.class);
        semanticLayerService = mock(SemanticLayerService.class);
        auditPublisher = mock(AuditEventPublisher.class);
        snapshotResolvers = mock(org.springframework.beans.factory.ObjectProvider.class);
        service = new ExportTaskServiceImpl(mapper, semanticLayerService,
                mock(DashboardService.class), mock(DashboardExportQueryValidator.class),
                auditPublisher, snapshotResolvers, exportRoot.toString());
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
        String disposition = response.getHeader("Content-Disposition");
        assertTrue(disposition.contains("filename=\"Bank report_"),
                "plain filename fallback should be present");
        assertTrue(disposition.contains("filename*=UTF-8''Bank%20report_"),
                "RFC 5987 encoded filename should be present");
        assertTrue(disposition.endsWith(".xlsx"));
        assertThrows(InvalidPermissionException.class,
                () -> service.download(created.getTaskId(), user("bob"), response));
    }

    @Test
    void deleteRemovesOwnedTaskRecordAndFile() throws Exception {
        QueryStructReq structured = mock(QueryStructReq.class);
        when(structured.getLimit()).thenReturn(10L);
        when(structured.convert(true)).thenReturn(new QuerySqlReq());
        when(semanticLayerService.queryByReq(any(), any())).thenReturn(maskedResult("masked"));
        ExportTaskResp created =
                service.create(request(ExportFormat.XLSX, structured), user("alice"));
        Path file = exportRoot.resolve(persisted.getStorageKey());
        assertTrue(Files.isRegularFile(file));
        when(mapper.selectOne(any())).thenReturn(persisted);

        service.delete(created.getTaskId(), user("alice"));

        verify(mapper).deleteById(persisted.getId());
        assertFalse(Files.exists(file));
    }

    @Test
    void deleteRejectsAnotherUsersTask() {
        ExportTaskDO task = task("a5ae39bd-55fc-4805-97ba-f4b409924bf0", "alice",
                ExportStatus.SUCCEEDED, new java.util.Date(System.currentTimeMillis() + 60_000));
        task.setId(7L);
        when(mapper.selectOne(any())).thenReturn(task);

        assertThrows(InvalidPermissionException.class,
                () -> service.delete(task.getTaskId(), user("bob")));
        verify(mapper, org.mockito.Mockito.never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deleteFailsWhenTaskDoesNotExist() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThrows(InvalidArgumentException.class,
                () -> service.delete("a5ae39bd-55fc-4805-97ba-f4b409924bf0", user("alice")));
        verify(mapper, org.mockito.Mockito.never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deletePublishesSuccessAuditEvent() {
        ExportTaskDO task = task("d78f79fe-0a1c-4e0f-a8d8-09ea3a7b5c30", "alice",
                ExportStatus.FAILED, new java.util.Date(System.currentTimeMillis() + 60_000));
        task.setId(9L);
        when(mapper.selectOne(any())).thenReturn(task);

        service.delete(task.getTaskId(), user("alice"));

        ArgumentCaptor<com.tencent.supersonic.headless.server.security.audit.model.AuditEvent> events =
                ArgumentCaptor.forClass(
                        com.tencent.supersonic.headless.server.security.audit.model.AuditEvent.class);
        verify(auditPublisher).publishRequired(events.capture(), any());
        assertEquals(AuditEventType.EXPORT_DELETED, events.getValue().getEventType());
        assertEquals(AuditOutcome.SUCCESS, events.getValue().getOutcome());
    }

    @Test
    void fileNameUsesSnapshotQuestionWhenTitleIsBlankAndSanitizesIllegalCharacters()
            throws Exception {
        ChatSnapshotExportData snapshot = snapshotData();
        snapshot.setQuestion("各机构/存款:余额?<排名>\"明细\"|" + "长".repeat(80));
        ChatSnapshotExportResolver resolver = mock(ChatSnapshotExportResolver.class);
        when(snapshotResolvers.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(anyLong(), any())).thenReturn(snapshot);
        ExportCreateReq request = snapshotRequest(ExportFormat.PDF, 42L);
        request.setTitle(null);

        ExportTaskResp response = service.create(request, user("alice"));

        assertEquals(ExportStatus.SUCCEEDED, response.getStatus());
        String fileName = response.getFileName();
        assertTrue(fileName.startsWith("各机构_存款_余额__排名__明细__"),
                "illegal characters should be replaced with underscores");
        assertTrue(fileName.matches(".{60}_\\d{8}-\\d{4}\\.pdf"),
                "base should be truncated to 60 chars with a timestamp suffix");
        assertFalse(fileName.substring(0, 60).matches(".*[\\\\/:*?\"<>|].*"),
                "no illegal characters may remain in the base name");
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

    @Test
    void snapshotExportResolvesChatHistoryWithoutReexecutingQueries() throws Exception {
        ChatSnapshotExportResolver resolver = mock(ChatSnapshotExportResolver.class);
        when(snapshotResolvers.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(anyLong(), any())).thenReturn(snapshotData());

        ExportTaskResp response =
                service.create(snapshotRequest(ExportFormat.XLSX, 42L), user("alice"));

        assertEquals(ExportStatus.SUCCEEDED, response.getStatus());
        assertEquals("chat-query-42", persisted.getResourceId());
        assertTrue(response.getFileName().matches("问数回答快照_\\d{8}-\\d{4}\\.xlsx"),
                "semantic file name should be <title>_<yyyyMMdd-HHmm>.xlsx");
        verify(resolver).resolve(anyLong(), any());
        verify(semanticLayerService, org.mockito.Mockito.never()).queryByReq(any(), any());
        Path file = exportRoot.resolve(persisted.getStorageKey());
        try (InputStream input = Files.newInputStream(file);
                XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            var summary = workbook.getSheet("Summary");
            assertEquals("报表标题", summary.getRow(0).getCell(0).getStringCellValue());
            assertEquals("各机构存款余额", summary.getRow(0).getCell(1).getStringCellValue());
            assertEquals("问数查询", summary.getRow(1).getCell(1).getStringCellValue());
            assertEquals("导出人", summary.getRow(3).getCell(0).getStringCellValue());
            assertEquals("是", summaryValue(summary, "是否脱敏"));
            assertEquals("存款余额环比上升", summaryValue(summary, "分析结论"));
            assertTrue(summaryValue(summary, "数据脱敏说明").contains("脱敏"));
            assertTrue(summaryValue(summary, "导出水印").contains(persisted.getTaskId()));
            assertEquals("城东支行",
                    workbook.getSheet("各机构存款余额").getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void snapshotExportWritesChineseHeadersAndFormatsMetricValues() throws Exception {
        QueryColumn orgName = new QueryColumn("机构名称", "VARCHAR", "org_name");
        QueryColumn metricValue = new QueryColumn("指标值", "NUMBER", "metric_value");
        metricValue.setShowType("NUMBER");
        metricValue.setDataFormatType("percent");
        DataFormat percent = new DataFormat();
        percent.setNeedMultiply100(true);
        percent.setDecimalPlaces(2);
        metricValue.setDataFormat(percent);
        QueryColumn balance = new QueryColumn("存款余额", "NUMBER", "deposit_value");
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("org_name", "城东支行");
        row.put("metric_value", 0.4202);
        row.put("deposit_value", 1234567);
        ChatSnapshotExportData snapshot = ChatSnapshotExportData.builder().queryId(42L)
                .question("各机构存款余额").dataSetId(7L)
                .columns(List.of(orgName, metricValue, balance)).rows(List.of(row)).build();
        ChatSnapshotExportResolver resolver = mock(ChatSnapshotExportResolver.class);
        when(snapshotResolvers.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(anyLong(), any())).thenReturn(snapshot);

        ExportTaskResp response =
                service.create(snapshotRequest(ExportFormat.XLSX, 42L), user("alice"));

        assertEquals(ExportStatus.SUCCEEDED, response.getStatus());
        Path file = exportRoot.resolve(persisted.getStorageKey());
        try (InputStream input = Files.newInputStream(file);
                XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            var sheet = workbook.getSheet("各机构存款余额");
            assertEquals("机构名称", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("指标值", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("存款余额", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("城东支行", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("42.02%", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("1,234,567", sheet.getRow(1).getCell(2).getStringCellValue());
        }
    }

    @Test
    void snapshotPdfRendersChineseTextAndImplicitChart() throws Exception {
        QueryColumn orgName = new QueryColumn("机构名称", "VARCHAR", "org_name");
        QueryColumn metricValue = new QueryColumn("指标值", "NUMBER", "metric_value");
        metricValue.setShowType("NUMBER");
        LinkedHashMap<String, Object> row1 = new LinkedHashMap<>();
        row1.put("org_name", "城东支行");
        row1.put("metric_value", 116.98);
        LinkedHashMap<String, Object> row2 = new LinkedHashMap<>();
        row2.put("org_name", "城西支行");
        row2.put("metric_value", 88.5);
        ChatSnapshotExportData snapshot = ChatSnapshotExportData.builder().queryId(42L)
                .question("各机构存款余额排名").dataSetId(7L)
                .columns(List.of(orgName, metricValue))
                .rows(List.<java.util.Map<String, Object>>of(row1, row2))
                .conclusion("存款余额排名前三的机构占比超过六成。").dateRange("2025-06-01 至 2025-06-15")
                .chartType("BAR").build();
        ChatSnapshotExportResolver resolver = mock(ChatSnapshotExportResolver.class);
        when(snapshotResolvers.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(anyLong(), any())).thenReturn(snapshot);

        ExportTaskResp response =
                service.create(snapshotRequest(ExportFormat.PDF, 42L), user("alice"));

        assertEquals(ExportStatus.SUCCEEDED, response.getStatus());
        Path pdf = exportRoot.resolve(persisted.getStorageKey());
        try (PDDocument document = PDDocument.load(pdf.toFile())) {
            // chart page + at least one text page
            assertEquals(2, document.getNumberOfPages());
            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                assertTrue(hasNonWhitePixels(renderer.renderImage(page)),
                        "page " + page + " should not be blank");
            }
        }
        // the chosen font must actually be able to render CJK on this machine
        java.awt.Font font = ExportTaskServiceImpl.cjkFont(java.awt.Font.PLAIN, 12);
        org.junit.jupiter.api.Assumptions.assumeTrue(cjkFontAvailable());
        assertTrue(font.canDisplay('中'), "selected PDF font must support CJK");
    }

    private static boolean cjkFontAvailable() {
        return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames() != null
                && java.util.Arrays.stream(java.awt.GraphicsEnvironment
                        .getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                        .anyMatch(name -> new java.awt.Font(name, java.awt.Font.PLAIN, 12)
                                .canDisplay('中'));
    }

    private static boolean hasNonWhitePixels(java.awt.image.BufferedImage image) {
        for (int x = 0; x < image.getWidth(); x += 7) {
            for (int y = 0; y < image.getHeight(); y += 7) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    return true;
                }
            }
        }
        return false;
    }

    private String summaryValue(org.apache.poi.ss.usermodel.Sheet summary, String key) {
        for (int row = 0; row <= summary.getLastRowNum(); row++) {
            var current = summary.getRow(row);
            if (current != null && current.getCell(0) != null
                    && key.equals(current.getCell(0).getStringCellValue())) {
                return current.getCell(1).getStringCellValue();
            }
        }
        return null;
    }

    @Test
    void snapshotExportFailsWhenDeploymentHasNoResolver() throws Exception {
        when(snapshotResolvers.getIfAvailable()).thenReturn(null);

        InvalidArgumentException failure = assertThrows(InvalidArgumentException.class,
                () -> service.create(snapshotRequest(ExportFormat.XLSX, 42L), user("alice")));

        assertTrue(failure.getMessage().contains("不支持快照导出"));
        assertEquals(ExportStatus.FAILED.name(), persisted.getStatus());
        try (var files = Files.list(exportRoot)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void snapshotExportPropagatesResolverPermissionFailures() {
        ChatSnapshotExportResolver resolver = mock(ChatSnapshotExportResolver.class);
        when(snapshotResolvers.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(anyLong(), any()))
                .thenThrow(new InvalidPermissionException("仅提问人本人可以导出该问数结果"));

        assertThrows(InvalidPermissionException.class,
                () -> service.create(snapshotRequest(ExportFormat.XLSX, 42L), user("bob")));

        assertEquals(ExportStatus.FAILED.name(), persisted.getStatus());
    }

    @Test
    void snapshotRequestMustBeQueryResourceWithPositiveId() {
        ExportCreateReq dashboardSnapshot = snapshotRequest(ExportFormat.XLSX, 42L);
        dashboardSnapshot.setResourceType(ExportResourceType.DASHBOARD);
        dashboardSnapshot.setDashboardId(9L);
        assertThrows(InvalidArgumentException.class,
                () -> service.create(dashboardSnapshot, user("alice")));
        assertThrows(InvalidArgumentException.class,
                () -> service.create(snapshotRequest(ExportFormat.XLSX, 0L), user("alice")));
    }

    @Test
    void snapshotChartDefinitionMayReferenceTheImplicitSingleQuery() throws Exception {
        ExportCreateReq request = snapshotRequest(ExportFormat.PDF, 42L);
        ExportChartReq chart = new ExportChartReq();
        chart.setQueryIndex(0);
        chart.setType(ExportChartType.BAR);
        chart.setCategoryField("account_name");
        chart.setValueField("balance");
        request.setCharts(List.of(chart));
        ChatSnapshotExportData snapshot = snapshotData();
        QueryColumn balance = new QueryColumn("Balance", "DECIMAL", "balance");
        LinkedHashMap<String, Object> row = new LinkedHashMap<>(snapshot.getRows().get(0));
        row.put("balance", 100);
        snapshot.setColumns(List.of(snapshot.getColumns().get(0), balance));
        snapshot.setRows(List.of(row));
        ChatSnapshotExportResolver resolver = mock(ChatSnapshotExportResolver.class);
        when(snapshotResolvers.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(anyLong(), any())).thenReturn(snapshot);

        ExportTaskResp response = service.create(request, user("alice"));

        assertEquals(ExportStatus.SUCCEEDED, response.getStatus());
        Path pdf = exportRoot.resolve(persisted.getStorageKey());
        try (PDDocument document = PDDocument.load(pdf.toFile())) {
            assertEquals(2, document.getNumberOfPages());
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

    private ExportCreateReq snapshotRequest(ExportFormat format, Long snapshotQueryId) {
        ExportCreateReq request = new ExportCreateReq();
        request.setResourceType(ExportResourceType.QUERY);
        request.setFormat(format);
        request.setTitle("问数回答快照");
        request.setQueries(List.of());
        request.setSnapshotQueryId(snapshotQueryId);
        return request;
    }

    private ChatSnapshotExportData snapshotData() {
        QueryColumn column = new QueryColumn("Account", "VARCHAR", "account_name");
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("account_name", "城东支行");
        return ChatSnapshotExportData.builder().queryId(42L).question("各机构存款余额")
                .dataSetId(7L).columns(List.of(column)).rows(List.of(row)).masked(true)
                .maskedColumns(Set.of("account_name")).conclusion("存款余额环比上升").build();
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
