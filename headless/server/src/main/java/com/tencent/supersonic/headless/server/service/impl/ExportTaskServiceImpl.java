package com.tencent.supersonic.headless.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.DataFormat;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.DataFormatTypeEnum;
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
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.api.pojo.response.ExportTaskResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.persistence.dataobject.ExportTaskDO;
import com.tencent.supersonic.headless.server.persistence.mapper.ExportTaskMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.service.ChatSnapshotExportResolver;
import com.tencent.supersonic.headless.server.service.DashboardService;
import com.tencent.supersonic.headless.server.service.ExportTaskService;
import com.tencent.supersonic.headless.server.utils.ChartImageRenderer;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

@Slf4j
@Service
public class ExportTaskServiceImpl implements ExportTaskService {

    static final int MAX_QUERIES = 20;
    static final int MAX_LIST_PAGE_SIZE = 100;
    static final long MAX_ROWS = 10_000;
    static final int MAX_PDF_ROWS = 500;
    static final int MAX_CHART_ROWS = 30;
    static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    static final String CHART_SHEET_NAME = "图表";
    static final Duration RETENTION = Duration.ofHours(24);
    private static final Semaphore EXPORT_PERMITS = new Semaphore(4);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_NAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    static final int MAX_FILE_NAME_BASE = 60;
    /** CJK-capable font candidates, in preference order; PDF pages are rasterized via AWT. */
    private static final List<String> CJK_FONT_CANDIDATES = List.of("Microsoft YaHei", "SimSun",
            "Noto Sans CJK SC", "PingFang SC", "WenQuanYi Micro Hei");
    private static volatile String cjkFontFamily;
    private static final List<String> ORGANIZATION_ATTRIBUTE_KEYS =
            List.of("organizationId", "organizationCode", "orgId", "departmentId");

    private final ExportTaskMapper exportTaskMapper;
    private final SemanticLayerService semanticLayerService;
    private final DashboardService dashboardService;
    private final DashboardExportQueryValidator dashboardExportQueryValidator;
    private final AuditEventPublisher auditEventPublisher;
    private final ObjectProvider<ChatSnapshotExportResolver> snapshotResolvers;
    private final ObjectProvider<ChartImageRenderer> chartImageRenderers;
    private final Path exportRoot;

    public ExportTaskServiceImpl(ExportTaskMapper exportTaskMapper,
            SemanticLayerService semanticLayerService, DashboardService dashboardService,
            DashboardExportQueryValidator dashboardExportQueryValidator,
            AuditEventPublisher auditEventPublisher,
            ObjectProvider<ChatSnapshotExportResolver> snapshotResolvers,
            ObjectProvider<ChartImageRenderer> chartImageRenderers,
            @Value("${s2.export.storage-dir:${java.io.tmpdir}/supersonic-exports}") String storageDirectory) {
        this.exportTaskMapper = exportTaskMapper;
        this.semanticLayerService = semanticLayerService;
        this.dashboardService = dashboardService;
        this.dashboardExportQueryValidator = dashboardExportQueryValidator;
        this.auditEventPublisher = auditEventPublisher;
        this.snapshotResolvers = snapshotResolvers;
        this.chartImageRenderers = chartImageRenderers;
        this.exportRoot = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    @Override
    public ExportTaskResp create(ExportCreateReq request, User user) {
        requireAuthenticated(user);
        validateRequest(request);
        String taskId = UUID.randomUUID().toString();
        DashboardResp dashboard = request.getResourceType() == ExportResourceType.DASHBOARD
                ? dashboardService.get(request.getDashboardId(), user)
                : null;
        if (dashboard != null) {
            dashboardExportQueryValidator.validate(dashboard, request.getQueries());
        }
        String resourceId = dashboard != null ? String.valueOf(dashboard.getId())
                : request.getSnapshotQueryId() != null ? "chat-query-" + request.getSnapshotQueryId()
                        : taskId;
        ExportTaskDO task = newTask(taskId, request, resourceId, user);
        exportTaskMapper.insert(task);
        long started = System.nanoTime();
        publish(task, user, AuditEventType.EXPORT_STARTED, AuditOutcome.UNKNOWN, null, started);
        Path temporary = null;
        Path generated = null;
        boolean permit = false;
        try {
            permit = EXPORT_PERMITS.tryAcquire();
            if (!permit) {
                throw new InvalidArgumentException("Too many concurrent export requests");
            }
            task.setStatus(ExportStatus.RUNNING.name());
            task.setUpdatedAt(new Date());
            exportTaskMapper.updateById(task);
            ExportData data = request.getSnapshotQueryId() != null ? resolveSnapshot(request, user)
                    : executeQueries(request.getQueries(), user);
            if (request.getFormat() == ExportFormat.PDF && data.rowCount() > MAX_PDF_ROWS) {
                throw new InvalidArgumentException("PDF export cannot exceed 500 data rows");
            }
            Files.createDirectories(exportRoot);
            String extension = request.getFormat().name().toLowerCase();
            String storageKey = taskId + "." + extension;
            Path destination = resolveStorage(storageKey);
            temporary = Files.createTempFile(exportRoot, taskId + "-", ".tmp");
            if (request.getFormat() == ExportFormat.XLSX) {
                writeXlsx(temporary, request, dashboard, data, user, taskId);
            } else {
                writePdf(temporary, request, dashboard, data, user, taskId);
            }
            long fileSize = Files.size(temporary);
            if (fileSize > MAX_FILE_BYTES) {
                throw new InvalidArgumentException("Export file exceeds the 25 MB limit");
            }
            moveIntoPlace(temporary, destination);
            temporary = null;
            generated = destination;
            complete(task, request, data, storageKey, fileSize);
            publish(task, user, AuditEventType.EXPORT_SUCCEEDED, AuditOutcome.SUCCESS, null,
                    started);
            ExportTaskResp response = toResponse(task);
            generated = null;
            return response;
        } catch (Exception failure) {
            deleteQuietly(generated);
            fail(task, failure);
            try {
                publish(task, user, AuditEventType.EXPORT_FAILED, AuditOutcome.FAILURE,
                        "EXPORT_EXCEPTION", started);
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Export generation failed", failure);
        } finally {
            deleteQuietly(temporary);
            if (permit) {
                EXPORT_PERMITS.release();
            }
        }
    }

    @Override
    public ExportTaskResp get(String taskId, User user) {
        return toResponse(requireOwned(taskId, user));
    }

    @Override
    public PageInfo<ExportTaskResp> list(int pageNum, int pageSize, User user) {
        requireAuthenticated(user);
        validatePage(pageNum, pageSize);
        LambdaQueryWrapper<ExportTaskDO> query =
                new LambdaQueryWrapper<ExportTaskDO>().eq(ExportTaskDO::getOwner, user.getName())
                        .orderByDesc(ExportTaskDO::getCreatedAt).orderByDesc(ExportTaskDO::getId);
        PageInfo<ExportTaskDO> page = PageHelper.startPage(pageNum, pageSize)
                .doSelectPageInfo(() -> exportTaskMapper.selectList(query));
        PageInfo<ExportTaskResp> response = new PageInfo<>();
        BeanUtils.copyProperties(page, response, "list");
        response.setList(page.getList().stream().map(task -> {
            expireIfNeeded(task);
            return toResponse(task);
        }).toList());
        return response;
    }

    @Override
    public void download(String taskId, User user, HttpServletResponse response) {
        ExportTaskDO task = requireOwned(taskId, user);
        expireIfNeeded(task);
        if (!ExportStatus.SUCCEEDED.name().equals(task.getStatus())
                || StringUtils.isBlank(task.getStorageKey())) {
            throw new InvalidArgumentException("Export file is not available");
        }
        Path file = resolveStorage(task.getStorageKey());
        if (!Files.isRegularFile(file)) {
            throw new InvalidArgumentException("Export file is not available");
        }
        try {
            response.reset();
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(
                    task.getFormat().equals(ExportFormat.PDF.name()) ? "application/pdf"
                            : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            String fileName = StringUtils.defaultIfBlank(task.getFileName(),
                    task.getTaskId() + "." + task.getFormat().toLowerCase());
            String encodedName =
                    URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            String asciiName = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace('"', '_');
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName);
            response.setHeader("Content-Length", String.valueOf(Files.size(file)));
            response.setHeader("Cache-Control", "no-store");
            try (OutputStream output = new BufferedOutputStream(response.getOutputStream())) {
                Files.copy(file, output);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Export download failed", e);
        }
    }

    @Override
    public void delete(String taskId, User user) {
        long started = System.nanoTime();
        ExportTaskDO task = requireOwned(taskId, user);
        // A RUNNING task is generated on the create() request thread and is never
        // interrupted here; it also carries no storage key yet. Since the expiry sweep
        // only visits rows still in the table, the file is removed here for every
        // non-RUNNING status; an already-cleaned file only logs a warning.
        if (!ExportStatus.RUNNING.name().equals(task.getStatus())
                && StringUtils.isNotBlank(task.getStorageKey())) {
            deleteQuietly(resolveStorage(task.getStorageKey()));
        }
        exportTaskMapper.deleteById(task.getId());
        publish(task, user, AuditEventType.EXPORT_DELETED, AuditOutcome.SUCCESS, null, started);
    }

    @Override
    @Scheduled(fixedDelayString = "${s2.export.cleanup-delay-ms:3600000}")
    public int cleanupExpired() {
        Date now = new Date();
        List<ExportTaskDO> expired = exportTaskMapper.selectList(
                new LambdaQueryWrapper<ExportTaskDO>().lt(ExportTaskDO::getExpiresAt, now)
                        .ne(ExportTaskDO::getStatus, ExportStatus.EXPIRED.name()));
        for (ExportTaskDO task : expired) {
            if (StringUtils.isNotBlank(task.getStorageKey())) {
                deleteQuietly(resolveStorage(task.getStorageKey()));
            }
            task.setStatus(ExportStatus.EXPIRED.name());
            task.setStorageKey(null);
            task.setUpdatedAt(now);
            exportTaskMapper.updateById(task);
        }
        return expired.size();
    }

    private ExportData executeQueries(List<QueryStructReq> requests, User user) throws Exception {
        List<ExportSheet> sheets = new ArrayList<>();
        long totalRows = 0;
        Set<String> maskedColumns = new LinkedHashSet<>();
        boolean masked = false;
        int index = 1;
        for (QueryStructReq structured : requests) {
            if (structured == null) {
                throw new InvalidArgumentException("Export query cannot be null");
            }
            if (structured.getOffset() < 0) {
                throw new InvalidArgumentException("Export query offset cannot be negative");
            }
            structured.setLimit(Math.min(Math.max(1, structured.getLimit()), MAX_ROWS));
            QuerySqlReq query = structured.convert(true);
            query.setNeedAuth(true);
            SemanticQueryResp result = semanticLayerService.queryByReq(query, user);
            List<QueryColumn> columns =
                    result.getColumns() == null ? List.of() : result.getColumns();
            List<Map<String, Object>> rows =
                    result.getResultList() == null ? List.of() : result.getResultList();
            totalRows += rows.size();
            if (totalRows > MAX_ROWS) {
                throw new InvalidArgumentException("Export cannot exceed 10000 data rows");
            }
            masked = masked || result.isDataMasked();
            if (result.getMaskedColumns() != null) {
                maskedColumns.addAll(result.getMaskedColumns());
            }
            sheets.add(new ExportSheet("Query " + index++, columns, rows));
        }
        return new ExportData(sheets, totalRows, masked, maskedColumns, null, null, null, null);
    }

    private ExportData resolveSnapshot(ExportCreateReq request, User user) {
        ChatSnapshotExportResolver resolver = snapshotResolvers.getIfAvailable();
        if (resolver == null) {
            throw new InvalidArgumentException("当前部署不支持快照导出");
        }
        ChatSnapshotExportData snapshot = resolver.resolve(request.getSnapshotQueryId(), user);
        List<QueryColumn> columns =
                snapshot.getColumns() == null ? List.of() : snapshot.getColumns();
        List<Map<String, Object>> rows = snapshot.getRows() == null ? List.of() : snapshot.getRows();
        if (rows.size() > MAX_ROWS) {
            throw new InvalidArgumentException("Export cannot exceed 10000 data rows");
        }
        String sheetName = StringUtils.defaultIfBlank(snapshot.getQuestion(), "Snapshot");
        ExportSheet sheet = new ExportSheet(truncate(sheetName.trim(), 30), columns, rows);
        Set<String> maskedColumns =
                snapshot.getMaskedColumns() == null ? Set.of() : snapshot.getMaskedColumns();
        return new ExportData(List.of(sheet), rows.size(), snapshot.isMasked(), maskedColumns,
                snapshot.getConclusion(), snapshot.getQuestion(), snapshot.getDateRange(),
                snapshot.getChartType());
    }

    private void writeXlsx(Path file, ExportCreateReq request, DashboardResp dashboard,
            ExportData data, User user, String taskId) throws IOException {
        List<ExportChartReq> charts = effectiveCharts(request, data);
        // SXSSF (streaming) cannot carry native chart parts, so chart exports use an in-memory
        // XSSF workbook; the row limit (MAX_ROWS) keeps memory usage bounded.
        if (charts.isEmpty()) {
            try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                    OutputStream output = Files.newOutputStream(file, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                workbook.setCompressTempFiles(true);
                writeSummarySheet(workbook, request, dashboard, data, user, taskId);
                for (int index = 0; index < data.sheets().size(); index++) {
                    writeDataSheet(workbook, data.sheets().get(index), Set.of(), null, null);
                }
                workbook.write(output);
            }
            return;
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeSummarySheet(workbook, request, dashboard, data, user, taskId);
            CellStyle numberStyle = numberStyle(workbook, "#,##0.##");
            CellStyle percentStyle = numberStyle(workbook, "0.00%");
            List<String> sheetNames = new ArrayList<>();
            for (int index = 0; index < data.sheets().size(); index++) {
                ExportSheet exportSheet = data.sheets().get(index);
                sheetNames.add(safeSheetName(exportSheet.name()));
                Set<String> numericFields = numericValueFields(charts, index);
                writeDataSheet(workbook, exportSheet, numericFields, numberStyle, percentStyle);
            }
            Map<String, String> chartParts = Map.of();
            try {
                chartParts = embedCharts(workbook, data, charts, sheetNames);
            } catch (RuntimeException e) {
                // Chart embedding is best-effort; the plain data workbook must still export.
                log.warn("Skipped embedded XLSX charts: errorType={}",
                        e.getClass().getSimpleName());
            }
            try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                workbook.write(output);
            }
            if (!chartParts.isEmpty()) {
                rewriteChartParts(file, chartParts);
            }
        }
    }

    /** Explicit charts from the request, else the implicit snapshot chart (same rule as PDF). */
    private List<ExportChartReq> effectiveCharts(ExportCreateReq request, ExportData data) {
        if (!request.getCharts().isEmpty()) {
            return request.getCharts();
        }
        ExportChartReq snapshotChart = snapshotChart(data);
        return snapshotChart == null ? List.of() : List.of(snapshotChart);
    }

    private void writeSummarySheet(Workbook workbook, ExportCreateReq request,
            DashboardResp dashboard, ExportData data, User user, String taskId) {
        Sheet summary = workbook.createSheet("Summary");
        int row = 0;
        row = writeKeyValue(summary, row, "报表标题", reportTitle(request, dashboard, data));
        row = writeKeyValue(summary, row, "资源类型", resourceTypeLabel(request));
        row = writeKeyValue(summary, row, "生成时间", timestamp());
        row = writeKeyValue(summary, row, "导出人", user.getDisplayName());
        row = writeKeyValue(summary, row, "是否脱敏", data.masked() ? "是" : "否");
        row = writeKeyValue(summary, row, "数据行数", String.valueOf(data.rowCount()));
        if (StringUtils.isNotBlank(data.dateRange())) {
            row = writeKeyValue(summary, row, "数据日期范围", data.dateRange());
        }
        if (StringUtils.isNotBlank(data.conclusion())) {
            row = writeKeyValue(summary, row, "分析结论", data.conclusion());
        }
        row = writeKeyValue(summary, row, "数据脱敏说明", maskingNote(data));
        row = writeKeyValue(summary, row, "行数说明", rowCountNote(data, ExportFormat.XLSX));
        row = writeKeyValue(summary, row, "导出水印", watermark(user, taskId));
        if (dashboard != null) {
            row = writeKeyValue(summary, row, "看板版本", String.valueOf(dashboard.getVersion()));
            writeKeyValue(summary, row, "看板描述",
                    StringUtils.defaultString(dashboard.getDescription()));
        }
    }

    /**
     * Writes one data sheet. Columns referenced as a chart value axis are stored as numeric
     * cells (with a percent/thousands display format matching cellText semantics) so the
     * embedded chart can reference them; everything else stays text.
     */
    private void writeDataSheet(Workbook workbook, ExportSheet exportSheet,
            Set<String> numericFields, CellStyle numberStyle, CellStyle percentStyle) {
        Sheet sheet = workbook.createSheet(safeSheetName(exportSheet.name()));
        Row header = sheet.createRow(0);
        for (int column = 0; column < exportSheet.columns().size(); column++) {
            header.createCell(column)
                    .setCellValue(safeCell(exportSheet.columns().get(column).getName()));
        }
        int rowIndex = 1;
        for (Map<String, Object> values : exportSheet.rows()) {
            Row dataRow = sheet.createRow(rowIndex++);
            for (int column = 0; column < exportSheet.columns().size(); column++) {
                QueryColumn queryColumn = exportSheet.columns().get(column);
                Cell cell = dataRow.createCell(column);
                Object raw = values.get(queryColumn.getBizName());
                if (numericFields.contains(queryColumn.getBizName())
                        && writeNumericCell(cell, queryColumn, raw, numberStyle, percentStyle)) {
                    continue;
                }
                cell.setCellValue(safeCell(cellText(queryColumn, raw)));
            }
        }
    }

    private boolean writeNumericCell(Cell cell, QueryColumn column, Object raw,
            CellStyle numberStyle, CellStyle percentStyle) {
        Double number = chartNumber(raw);
        if (number == null) {
            return false;
        }
        cell.setCellValue(number);
        boolean percent =
                DataFormatTypeEnum.PERCENT.getName().equalsIgnoreCase(column.getDataFormatType());
        cell.setCellStyle(percent ? percentStyle : numberStyle);
        return true;
    }

    private CellStyle numberStyle(Workbook workbook, String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }

    private static Set<String> numericValueFields(List<ExportChartReq> charts, int sheetIndex) {
        Set<String> fields = new LinkedHashSet<>();
        for (ExportChartReq chart : charts) {
            if (chart.getQueryIndex() != null && chart.getQueryIndex() == sheetIndex) {
                fields.add(chart.getValueField());
            }
        }
        return fields;
    }

    /**
     * Creates one native chart part per chart on a dedicated "图表" sheet and returns the chart
     * XML keyed by package part name ("/xl/charts/chartN.xml"). The chart XML is generated as
     * text because POI 3.17 ships reduced OOXML schema classes without bar-chart bindings; the
     * parts are swapped into the written workbook by {@link #rewriteChartParts}.
     */
    private Map<String, String> embedCharts(XSSFWorkbook workbook, ExportData data,
            List<ExportChartReq> charts, List<String> sheetNames) {
        XSSFSheet chartSheet = workbook.createSheet(CHART_SHEET_NAME);
        XSSFDrawing drawing = chartSheet.createDrawingPatriarch();
        Map<String, String> parts = new LinkedHashMap<>();
        int position = 0;
        for (ExportChartReq chart : charts) {
            try {
                // Build the XML before creating the part so a broken chart never leaves a
                // placeholder chart part behind; one bad chart must not block the others.
                ExportSheet sheet = data.sheets().get(chart.getQueryIndex());
                ChartSeries series = chartSeries(sheet, chart);
                if (series == null) {
                    continue;
                }
                String xml = chartXml(chart, sheet, series, sheetNames.get(chart.getQueryIndex()),
                        position);
                int row = 1 + position * 18;
                XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 1, row, 10, row + 15);
                XSSFChart xssfChart = drawing.createChart(anchor);
                parts.put(xssfChart.getPackagePart().getPartName().getName(), xml);
                position++;
            } catch (RuntimeException e) {
                log.warn("Skipped embedded XLSX chart '{}': errorType={}", chart.getTitle(),
                        e.getClass().getSimpleName());
            }
        }
        if (parts.isEmpty()) {
            workbook.removeSheetAt(workbook.getSheetIndex(chartSheet));
        }
        return parts;
    }

    private String chartXml(ExportChartReq chart, ExportSheet sheet, ChartSeries series,
            String sheetName, int position) {
        int categoryColumn = columnIndex(sheet, chart.getCategoryField());
        int valueColumn = columnIndex(sheet, chart.getValueField());
        int lastRow = series.values().size() + 1;
        String categoryRef = quoteSheetName(sheetName) + "!$" + columnLetter(categoryColumn)
                + "$2:$" + columnLetter(categoryColumn) + "$" + lastRow;
        String valueRef = quoteSheetName(sheetName) + "!$" + columnLetter(valueColumn) + "$2:$"
                + columnLetter(valueColumn) + "$" + lastRow;
        long categoryAxisId = 100_000_001L + position * 2L;
        long valueAxisId = categoryAxisId + 1;
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        xml.append("<c:chart>");
        xml.append(chartTitle(StringUtils.defaultIfBlank(chart.getTitle(), "图表")));
        xml.append("<c:autoTitleDeleted val=\"0\"/><c:plotArea><c:layout/>");
        boolean line = chart.getType() == ExportChartType.LINE;
        xml.append(line ? "<c:lineChart><c:grouping val=\"standard\"/>"
                : "<c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/>");
        xml.append("<c:varyColors val=\"0\"/><c:ser>");
        xml.append("<c:idx val=\"0\"/><c:order val=\"0\"/><c:tx><c:v>")
                .append(escapeXml(columnLabel(sheet, chart.getValueField())))
                .append("</c:v></c:tx>");
        if (line) {
            xml.append("<c:marker><c:symbol val=\"circle\"/></c:marker>");
        }
        xml.append("<c:cat><c:strRef><c:f>").append(categoryRef)
                .append("</c:f><c:strCache><c:ptCount val=\"").append(series.categories().size())
                .append("\"/>");
        for (int i = 0; i < series.categories().size(); i++) {
            xml.append("<c:pt idx=\"").append(i).append("\"><c:v>")
                    .append(escapeXml(series.categories().get(i))).append("</c:v></c:pt>");
        }
        xml.append("</c:strCache></c:strRef></c:cat>");
        xml.append("<c:val><c:numRef><c:f>").append(valueRef)
                .append("</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"")
                .append(series.values().size()).append("\"/>");
        for (int i = 0; i < series.values().size(); i++) {
            xml.append("<c:pt idx=\"").append(i).append("\"><c:v>")
                    .append(BigDecimal.valueOf(series.values().get(i)).stripTrailingZeros()
                            .toPlainString())
                    .append("</c:v></c:pt>");
        }
        xml.append("</c:numCache></c:numRef></c:val>");
        xml.append(line ? "<c:smooth val=\"0\"/></c:ser><c:marker val=\"1\"/>"
                : "</c:ser><c:gapWidth val=\"150\"/>");
        xml.append("<c:axId val=\"").append(categoryAxisId).append("\"/><c:axId val=\"")
                .append(valueAxisId).append("\"/>");
        xml.append(line ? "</c:lineChart>" : "</c:barChart>");
        // CT_CatAx/CT_ValAx element order is schema-fixed: axId, scaling, delete, axPos,
        // gridlines, title, numFmt, tick marks/labels, crossAx, crosses, axis-specific extras.
        xml.append("<c:catAx><c:axId val=\"").append(categoryAxisId).append("\"/>")
                .append("<c:scaling><c:orientation val=\"minMax\"/></c:scaling>")
                .append("<c:delete val=\"0\"/><c:axPos val=\"b\"/>")
                .append(chartTitle(columnLabel(sheet, chart.getCategoryField())))
                .append("<c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"").append(valueAxisId)
                .append("\"/><c:crosses val=\"autoZero\"/><c:auto val=\"1\"/>")
                .append("<c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/>")
                .append("<c:noMultiLvlLbl val=\"0\"/></c:catAx>");
        xml.append("<c:valAx><c:axId val=\"").append(valueAxisId).append("\"/>")
                .append("<c:scaling><c:orientation val=\"minMax\"/></c:scaling>")
                .append("<c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/>")
                .append(chartTitle(columnLabel(sheet, chart.getValueField())))
                .append("<c:numFmt formatCode=\"General\" sourceLinked=\"1\"/>")
                .append("<c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"").append(categoryAxisId)
                .append("\"/><c:crosses val=\"autoZero\"/>")
                .append(line ? "<c:crossBetween val=\"midCat\"/>"
                        : "<c:crossBetween val=\"between\"/>")
                .append("</c:valAx>");
        xml.append("</c:plotArea>");
        xml.append("<c:legend><c:legendPos val=\"b\"/><c:overlay val=\"0\"/></c:legend>");
        xml.append("<c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/>");
        xml.append("</c:chart></c:chartSpace>");
        return xml.toString();
    }

    private String chartTitle(String text) {
        return "<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr/></a:pPr>"
                + "<a:r><a:t>" + escapeXml(text) + "</a:t></a:r></a:p></c:rich></c:tx>"
                + "<c:overlay val=\"0\"/></c:title>";
    }

    /** Replaces the placeholder chart parts of a written workbook with the real chart XML. */
    private void rewriteChartParts(Path xlsx, Map<String, String> chartParts) throws IOException {
        Path replaced = Files.createTempFile(xlsx.getParent(), "chart-", ".xlsx");
        try (ZipFile source = new ZipFile(xlsx.toFile());
                OutputStream targetStream = Files.newOutputStream(replaced);
                ZipOutputStream target = new ZipOutputStream(targetStream)) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                target.putNextEntry(new ZipEntry(entry.getName()));
                String xml = chartParts.get("/" + entry.getName());
                if (xml != null) {
                    target.write(xml.getBytes(StandardCharsets.UTF_8));
                } else {
                    try (InputStream input = source.getInputStream(entry)) {
                        input.transferTo(target);
                    }
                }
                target.closeEntry();
            }
        }
        moveIntoPlace(replaced, xlsx);
    }

    private int columnIndex(ExportSheet sheet, String field) {
        for (int index = 0; index < sheet.columns().size(); index++) {
            if (field.equals(sheet.columns().get(index).getBizName())) {
                return index;
            }
        }
        throw new InvalidArgumentException("Export chart references an unknown field");
    }

    private String columnLetter(int columnIndex) {
        StringBuilder letter = new StringBuilder();
        int value = columnIndex;
        do {
            letter.insert(0, (char) ('A' + value % 26));
            value = value / 26 - 1;
        } while (value >= 0);
        return letter.toString();
    }

    private String quoteSheetName(String sheetName) {
        return "'" + sheetName.replace("'", "''") + "'";
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** Parses a chart value; returns null when the value is absent or not numeric. */
    private Double chartNumber(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writePdf(Path file, ExportCreateReq request, DashboardResp dashboard,
            ExportData data, User user, String taskId) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("报表标题：" + reportTitle(request, dashboard, data));
        lines.add("生成时间：" + timestamp());
        lines.add("导出人：" + user.getDisplayName());
        if (StringUtils.isNotBlank(data.dateRange())) {
            lines.add("数据日期范围：" + data.dateRange());
        }
        lines.add("数据行数：" + data.rowCount() + " | 是否脱敏：" + (data.masked() ? "是" : "否"));
        if (StringUtils.isNotBlank(data.conclusion())) {
            lines.add("");
            lines.add("分析结论：");
            lines.addAll(wrapText(data.conclusion(), 55));
        }
        if (dashboard != null && StringUtils.isNotBlank(dashboard.getDescription())) {
            lines.add("看板描述：" + dashboard.getDescription());
        }
        for (ExportSheet sheet : data.sheets()) {
            lines.add("");
            lines.add(sheet.name());
            lines.add(joinHeaders(sheet.columns()));
            for (Map<String, Object> row : sheet.rows()) {
                lines.add(joinRow(sheet.columns(), row));
            }
        }
        lines.add("");
        lines.add(maskingNote(data));
        lines.add(rowCountNote(data, ExportFormat.PDF));
        lines.add(watermark(user, taskId));
        try (PDDocument document = new PDDocument()) {
            for (ExportChartReq chart : request.getCharts()) {
                addChartPage(document, chart, data, user);
            }
            ExportChartReq snapshotChart = snapshotChart(data);
            if (request.getCharts().isEmpty() && snapshotChart != null) {
                try {
                    addChartPage(document, snapshotChart, data, user);
                } catch (RuntimeException e) {
                    // The implicit snapshot chart is best-effort; never fail the export over it.
                    log.warn("Skipped snapshot chart page: errorType={}",
                            e.getClass().getSimpleName());
                }
            }
            int lineIndex = 0;
            while (lineIndex < lines.size() || document.getNumberOfPages() == 0) {
                BufferedImage image = new BufferedImage(1600, 1100, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setColor(Color.DARK_GRAY);
                graphics.setFont(cjkFont(Font.PLAIN, 24));
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int y = 55;
                int pageLines = 0;
                while (lineIndex < lines.size() && pageLines < 38) {
                    graphics.drawString(truncate(lines.get(lineIndex++), 105), 50, y);
                    y += 27;
                    pageLines++;
                }
                graphics.setColor(new Color(220, 220, 220));
                graphics.setFont(cjkFont(Font.PLAIN, 18));
                graphics.drawString(watermark(user, taskId), 50, 1070);
                graphics.dispose();
                addImagePage(document, image);
            }
            document.save(file.toFile());
        }
    }

    /**
     * Renders one chart page: a real ECharts screenshot via headless Edge when the renderer is
     * available, otherwise the hand-drawn Graphics2D chart as fallback.
     */
    private void addChartPage(PDDocument document, ExportChartReq chart, ExportData data,
            User user) throws IOException {
        byte[] png = renderChartImage(chart, data);
        if (png == null) {
            drawChartPage(document, chart, data, user);
            return;
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
        if (source == null) {
            log.warn("Headless chart screenshot is not a readable image; using fallback");
            drawChartPage(document, chart, data, user);
            return;
        }
        BufferedImage image = new BufferedImage(1600, 1100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.drawImage(source, 80, 150, 1440, 800, null);
        graphics.setColor(new Color(210, 210, 210));
        graphics.setFont(cjkFont(Font.PLAIN, 18));
        graphics.drawString(user.getDisplayName() + " | " + timestamp(), 80, 1050);
        graphics.dispose();
        addImagePage(document, image);
    }

    /**
     * Returns the ECharts screenshot for a chart, or null when no renderer is deployed or
     * rendering fails; chart data problems propagate as before so the fallback still validates
     * the request the same way.
     */
    private byte[] renderChartImage(ExportChartReq chart, ExportData data) {
        ChartImageRenderer renderer = chartImageRenderers.getIfAvailable();
        if (renderer == null) {
            return null;
        }
        ExportSheet sheet = data.sheets().get(chart.getQueryIndex());
        ChartSeries series = chartSeries(sheet, chart);
        if (series == null) {
            return null;
        }
        String type = chart.getType() == ExportChartType.LINE ? "line" : "bar";
        return renderer.renderPng(new ChartImageRenderer.ChartSpec(
                StringUtils.defaultIfBlank(chart.getTitle(), "图表"), type, series.categories(),
                series.values(), columnLabel(sheet, chart.getCategoryField()),
                columnLabel(sheet, chart.getValueField())));
    }

    private void addImagePage(PDDocument document, BufferedImage image) throws IOException {
        PDPage page =
                new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
        document.addPage(page);
        PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(),
                    page.getMediaBox().getHeight());
        }
    }

    /**
     * Extracts the chart series (categories + numeric values, capped at MAX_CHART_ROWS rows)
     * shared by the XLSX and PDF chart paths. Returns null when there is no data to plot.
     */
    private ChartSeries chartSeries(ExportSheet sheet, ExportChartReq chart) {
        requireChartField(sheet, chart.getCategoryField());
        requireChartField(sheet, chart.getValueField());
        List<Map<String, Object>> rows = sheet.rows().stream().limit(MAX_CHART_ROWS).toList();
        if (rows.isEmpty()) {
            return null;
        }
        List<String> categories = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Double value = chartNumber(row.get(chart.getValueField()));
            if (value == null) {
                throw new InvalidArgumentException(
                        "Export chart value field must contain numeric data");
            }
            categories.add(safeCell(row.get(chart.getCategoryField())));
            values.add(value);
        }
        return new ChartSeries(categories, values);
    }

    /**
     * Builds an implicit chart for snapshot exports whose stored recommendation is a bar/column/
     * line chart: the first non-numeric column is the category axis, the first numeric column is
     * the value axis. Returns null when the snapshot does not support a chart.
     */
    private ExportChartReq snapshotChart(ExportData data) {
        String chartType = StringUtils.defaultString(data.chartType()).toUpperCase();
        if (!Set.of("BAR", "COLUMN", "LINE").contains(chartType) || data.sheets().isEmpty()) {
            return null;
        }
        ExportSheet sheet = data.sheets().get(0);
        QueryColumn category = null;
        QueryColumn value = null;
        for (QueryColumn column : sheet.columns()) {
            boolean numeric = "NUMBER".equalsIgnoreCase(column.getShowType());
            if (numeric && value == null) {
                value = column;
            } else if (!numeric && category == null) {
                category = column;
            }
        }
        if (category == null || value == null) {
            return null;
        }
        ExportChartReq chart = new ExportChartReq();
        chart.setQueryIndex(0);
        chart.setType("LINE".equals(chartType) ? ExportChartType.LINE : ExportChartType.BAR);
        chart.setTitle(StringUtils.defaultIfBlank(data.title(), "图表"));
        chart.setCategoryField(category.getBizName());
        chart.setValueField(value.getBizName());
        return chart;
    }

    private void validateRequest(ExportCreateReq request) {
        if (request == null || request.getResourceType() == null || request.getFormat() == null) {
            throw new InvalidArgumentException("Export resource type and format are required");
        }
        boolean snapshot = request.getSnapshotQueryId() != null;
        if (snapshot) {
            if (request.getResourceType() != ExportResourceType.QUERY) {
                throw new InvalidArgumentException("Snapshot export only supports query resources");
            }
            if (request.getSnapshotQueryId() <= 0) {
                throw new InvalidArgumentException("Snapshot query id is invalid");
            }
        }
        List<QueryStructReq> queries =
                request.getQueries() == null ? List.of() : request.getQueries();
        request.setQueries(queries);
        if (queries.size() > MAX_QUERIES) {
            throw new InvalidArgumentException("Export cannot contain more than 20 queries");
        }
        if (request.getResourceType() == ExportResourceType.QUERY && !snapshot
                && queries.size() != 1) {
            throw new InvalidArgumentException(
                    "Query export requires exactly one structured query");
        }
        if (request.getResourceType() == ExportResourceType.DASHBOARD
                && request.getDashboardId() == null) {
            throw new InvalidArgumentException("Dashboard export requires a dashboard id");
        }
        if (request.getTitle() != null && request.getTitle().length() > 200) {
            throw new InvalidArgumentException("Export title cannot exceed 200 characters");
        }
        List<ExportChartReq> charts = request.getCharts() == null ? List.of() : request.getCharts();
        request.setCharts(charts);
        if (charts.size() > MAX_QUERIES) {
            throw new InvalidArgumentException("Export cannot contain more than 20 charts");
        }
        int queryCount = snapshot ? 1 : queries.size();
        for (ExportChartReq chart : charts) {
            if (chart == null || chart.getQueryIndex() == null || chart.getQueryIndex() < 0
                    || chart.getQueryIndex() >= queryCount || chart.getType() == null
                    || StringUtils.isBlank(chart.getCategoryField())
                    || StringUtils.isBlank(chart.getValueField())) {
                throw new InvalidArgumentException("Export chart definition is invalid");
            }
            if (chart.getTitle() != null && chart.getTitle().length() > 200) {
                throw new InvalidArgumentException(
                        "Export chart title cannot exceed 200 characters");
            }
        }
    }

    private void validatePage(int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_LIST_PAGE_SIZE) {
            throw new InvalidArgumentException("Export pagination is invalid");
        }
    }

    private ExportTaskDO newTask(String taskId, ExportCreateReq request, String resourceId,
            User user) {
        Date now = new Date();
        ExportTaskDO task = new ExportTaskDO();
        task.setTaskId(taskId);
        task.setResourceType(request.getResourceType().name());
        task.setResourceId(resourceId);
        task.setFormat(request.getFormat().name());
        task.setStatus(ExportStatus.PENDING.name());
        task.setOwner(user.getName());
        task.setOrganizationId(organizationId(user));
        task.setExpiresAt(Date.from(now.toInstant().plus(RETENTION)));
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private void complete(ExportTaskDO task, ExportCreateReq request, ExportData data,
            String storageKey, long fileSize) {
        Date now = new Date();
        task.setStatus(ExportStatus.SUCCEEDED.name());
        task.setStorageKey(storageKey);
        task.setFileName(fileName(request, data));
        task.setFileSize(fileSize);
        task.setRowCount(data.rowCount());
        task.setMaskingSummary(
                data.masked() ? "MASKED_FIELDS:" + data.maskedColumns().size() : "NONE");
        task.setCompletedAt(now);
        task.setUpdatedAt(now);
        exportTaskMapper.updateById(task);
    }

    private void fail(ExportTaskDO task, Exception failure) {
        task.setStatus(ExportStatus.FAILED.name());
        task.setFailureCode(
                failure instanceof InvalidArgumentException ? "EXPORT_LIMIT_OR_REQUEST_INVALID"
                        : "EXPORT_GENERATION_FAILED");
        task.setCompletedAt(new Date());
        task.setUpdatedAt(new Date());
        task.setStorageKey(null);
        exportTaskMapper.updateById(task);
    }

    private ExportTaskDO requireOwned(String taskId, User user) {
        requireAuthenticated(user);
        if (StringUtils.isBlank(taskId) || taskId.length() > 64) {
            throw new InvalidArgumentException("Export task id is invalid");
        }
        ExportTaskDO task = exportTaskMapper.selectOne(
                new LambdaQueryWrapper<ExportTaskDO>().eq(ExportTaskDO::getTaskId, taskId));
        if (task == null) {
            throw new InvalidArgumentException("Export task does not exist");
        }
        if (!Objects.equals(task.getOwner(), user.getName())) {
            throw new InvalidPermissionException("No permission to access export task");
        }
        expireIfNeeded(task);
        return task;
    }

    private void expireIfNeeded(ExportTaskDO task) {
        if (task.getExpiresAt() != null && task.getExpiresAt().before(new Date())
                && !ExportStatus.EXPIRED.name().equals(task.getStatus())) {
            if (StringUtils.isNotBlank(task.getStorageKey())) {
                deleteQuietly(resolveStorage(task.getStorageKey()));
            }
            task.setStatus(ExportStatus.EXPIRED.name());
            task.setStorageKey(null);
            task.setUpdatedAt(new Date());
            exportTaskMapper.updateById(task);
        }
    }

    private ExportTaskResp toResponse(ExportTaskDO task) {
        ExportTaskResp response = new ExportTaskResp();
        BeanUtils.copyProperties(task, response, "resourceType", "format", "status");
        response.setResourceType(ExportResourceType.valueOf(task.getResourceType()));
        response.setFormat(ExportFormat.valueOf(task.getFormat()));
        response.setStatus(ExportStatus.valueOf(task.getStatus()));
        response.setDownloadable(response.getStatus() == ExportStatus.SUCCEEDED
                && StringUtils.isNotBlank(task.getStorageKey()));
        return response;
    }

    private void publish(ExportTaskDO task, User user, AuditEventType eventType,
            AuditOutcome outcome, String reasonCode, long started) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", task.getTaskId());
        metadata.put("status", task.getStatus());
        metadata.put("masked", !Objects.equals(task.getMaskingSummary(), "NONE"));
        auditEventPublisher.publishRequired(
                AuditEvent.builder().eventType(eventType).outcome(outcome).reasonCode(reasonCode)
                        .resourceType(task.getResourceType()).resourceId(task.getResourceId())
                        .fileType(task.getFormat()).fileSize(task.getFileSize())
                        .exportRowCount(task.getRowCount()).maskingSummary(task.getMaskingSummary())
                        .durationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        .metadata(metadata).build(),
                user);
    }

    private Path resolveStorage(String storageKey) {
        if (StringUtils.isBlank(storageKey) || !storageKey.matches("[a-f0-9-]{36}\\.(xlsx|pdf)")) {
            throw new InvalidArgumentException("Export storage key is invalid");
        }
        Path path = exportRoot.resolve(storageKey).normalize();
        if (!path.startsWith(exportRoot)) {
            throw new InvalidArgumentException("Export storage path is invalid");
        }
        return path;
    }

    private void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void drawChartPage(PDDocument document, ExportChartReq chart, ExportData data,
            User user) throws IOException {
        ExportSheet sheet = data.sheets().get(chart.getQueryIndex());
        ChartSeries series = chartSeries(sheet, chart);
        if (series == null) {
            return;
        }
        List<String> categories = series.categories();
        List<Double> values = series.values();
        double max = values.stream().mapToDouble(Math::abs).max().orElse(1D);
        max = max == 0 ? 1 : max;
        BufferedImage image = new BufferedImage(1600, 1100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.DARK_GRAY);
        graphics.setFont(cjkFont(Font.BOLD, 32));
        graphics.drawString(StringUtils.defaultIfBlank(chart.getTitle(), "Chart"), 80, 70);
        int left = 120;
        int top = 130;
        int width = 1350;
        int height = 780;
        graphics.drawLine(left, top + height, left + width, top + height);
        graphics.drawLine(left, top, left, top + height);
        graphics.setFont(cjkFont(Font.PLAIN, 18));
        graphics.drawString(truncate(columnLabel(sheet, chart.getValueField()), 30), left,
                top - 12);
        int step = Math.max(1, width / categories.size());
        int previousX = 0;
        int previousY = 0;
        graphics.setFont(cjkFont(Font.PLAIN, 16));
        for (int index = 0; index < categories.size(); index++) {
            int x = left + index * step + step / 2;
            int valueHeight = (int) (Math.abs(values.get(index)) / max * (height - 60));
            int y = top + height - valueHeight;
            graphics.setColor(new Color(46, 114, 163));
            if (chart.getType() == ExportChartType.BAR) {
                graphics.fillRect(x - Math.max(2, step / 4), y, Math.max(4, step / 2), valueHeight);
            } else {
                graphics.fillOval(x - 5, y - 5, 10, 10);
                if (index > 0) {
                    graphics.drawLine(previousX, previousY, x, y);
                }
                previousX = x;
                previousY = y;
            }
            graphics.setColor(Color.DARK_GRAY);
            String label = truncate(categories.get(index), 12);
            graphics.drawString(label, x - Math.min(45, label.length() * 4), top + height + 28);
        }
        graphics.setColor(new Color(210, 210, 210));
        graphics.setFont(cjkFont(Font.PLAIN, 18));
        graphics.drawString(user.getDisplayName() + " | " + timestamp(), 80, 1050);
        graphics.dispose();
        addImagePage(document, image);
    }

    private void requireChartField(ExportSheet sheet, String field) {
        if (sheet.columns().stream().map(QueryColumn::getBizName).noneMatch(field::equals)) {
            throw new InvalidArgumentException("Export chart references an unknown field");
        }
    }

    private void requireAuthenticated(User user) {
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw new InvalidPermissionException("User identity is required");
        }
    }

    private String organizationId(User user) {
        if (user.getAttributes() == null) {
            return null;
        }
        return ORGANIZATION_ATTRIBUTE_KEYS.stream().map(user.getAttributes()::get)
                .filter(StringUtils::isNotBlank).map(String::trim).findFirst().orElse(null);
    }

    private int writeKeyValue(Sheet sheet, int rowIndex, String key, String value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(safeCell(value));
        return rowIndex + 1;
    }

    private String title(ExportCreateReq request, DashboardResp dashboard) {
        if (StringUtils.isNotBlank(request.getTitle())) {
            return request.getTitle().trim();
        }
        return dashboard == null ? "Query export" : dashboard.getName();
    }

    /** The report title is the snapshot question when present, else the request/dashboard title. */
    private String reportTitle(ExportCreateReq request, DashboardResp dashboard, ExportData data) {
        return StringUtils.defaultIfBlank(data.title(), title(request, dashboard));
    }

    private String resourceTypeLabel(ExportCreateReq request) {
        return request.getResourceType() == ExportResourceType.DASHBOARD ? "看板" : "问数查询";
    }

    private String timestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }

    private String watermark(User user, String taskId) {
        return "导出水印：" + user.getDisplayName() + " | " + timestamp() + " | " + taskId;
    }

    private String maskingNote(ExportData data) {
        if (!data.masked()) {
            return "数据脱敏说明：本报表数据未脱敏。";
        }
        int count = data.maskedColumns() == null ? 0 : data.maskedColumns().size();
        String detail = count > 0
                ? "，涉及字段 " + count + " 个（"
                        + data.maskedColumns().stream().sorted().collect(
                                java.util.stream.Collectors.joining("、"))
                        + "）"
                : "";
        return "数据脱敏说明：查询结果已脱敏" + detail + "，报表按脱敏后的值导出。";
    }

    private String rowCountNote(ExportData data, ExportFormat format) {
        String note = "行数说明：本报表共导出 " + data.rowCount() + " 行数据，单次导出上限 "
                + String.format("%,d", MAX_ROWS) + " 行";
        return format == ExportFormat.PDF ? note + "，PDF 最多展示前 " + MAX_PDF_ROWS + " 行。"
                : note + "。";
    }

    private String columnLabel(ExportSheet sheet, String field) {
        return sheet.columns().stream().filter(column -> field.equals(column.getBizName()))
                .map(QueryColumn::getName).filter(StringUtils::isNotBlank).findFirst()
                .orElse(field);
    }

    /**
     * Formats a cell value for display: percent metrics as xx.xx% (following the webapp
     * needMultiply100 semantics), other numeric values with thousands separators. Non-numeric
     * values pass through unchanged.
     */
    private String cellText(QueryColumn column, Object value) {
        if (!(value instanceof Number number)) {
            return value == null ? "" : String.valueOf(value);
        }
        if (DataFormatTypeEnum.PERCENT.getName().equalsIgnoreCase(column.getDataFormatType())) {
            BigDecimal decimal = toBigDecimal(number);
            DataFormat format = column.getDataFormat();
            if (format == null || format.isNeedMultiply100()) {
                decimal = decimal.multiply(BigDecimal.valueOf(100));
            }
            int places = format == null || format.getDecimalPlaces() == null ? 2
                    : format.getDecimalPlaces();
            return decimal.setScale(places, RoundingMode.HALF_UP).stripTrailingZeros()
                    .toPlainString() + "%";
        }
        return groupThousands(toBigDecimal(number).stripTrailingZeros().toPlainString());
    }

    private BigDecimal toBigDecimal(Number number) {
        return number instanceof BigDecimal decimal ? decimal
                : new BigDecimal(number.toString());
    }

    private String groupThousands(String plain) {
        int dot = plain.indexOf('.');
        String integer = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);
        String sign = integer.startsWith("-") ? "-" : "";
        String digits = sign.isEmpty() ? integer : integer.substring(1);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(digits.charAt(i));
        }
        return sign + grouped + fraction;
    }

    private List<String> wrapText(String text, int width) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > width) {
            lines.add(remaining.substring(0, width));
            remaining = remaining.substring(width);
        }
        if (!remaining.isEmpty()) {
            lines.add(remaining);
        }
        return lines;
    }

    /**
     * PDF pages are rasterized from AWT images, so text is CJK-safe as long as the JVM has a
     * CJK-capable font. Picks the first installed candidate; falls back to the logical
     * SansSerif font with a warning when none is found.
     */
    static Font cjkFont(int style, int size) {
        return new Font(cjkFontFamily(), style, size);
    }

    private static String cjkFontFamily() {
        String family = cjkFontFamily;
        if (family == null) {
            synchronized (ExportTaskServiceImpl.class) {
                if (cjkFontFamily == null) {
                    Set<String> available = Set.of(GraphicsEnvironment
                            .getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
                    cjkFontFamily = CJK_FONT_CANDIDATES.stream().filter(available::contains)
                            .filter(name -> new Font(name, Font.PLAIN, 12).canDisplay('中'))
                            .findFirst().orElse(Font.SANS_SERIF);
                    if (Font.SANS_SERIF.equals(cjkFontFamily)) {
                        log.warn("No CJK font found on this system; PDF export falls back to "
                                + "logical SansSerif, Chinese text may not render");
                    }
                }
                family = cjkFontFamily;
            }
        }
        return family;
    }

    /**
     * The download file name is semantic: "<report title>_<yyyyMMdd-HHmm>.<ext>". The title is the
     * request title when given, else the snapshot question; illegal file-name characters are
     * replaced and the base is truncated so the stored name stays portable.
     */
    private String fileName(ExportCreateReq request, ExportData data) {
        String base = StringUtils.defaultIfBlank(request.getTitle(), data.title());
        base = StringUtils.defaultIfBlank(base,
                request.getResourceType().name().toLowerCase() + "-export");
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.length() > MAX_FILE_NAME_BASE) {
            base = base.substring(0, MAX_FILE_NAME_BASE);
        }
        if (base.isEmpty()) {
            base = request.getResourceType().name().toLowerCase() + "-export";
        }
        return base + "_" + LocalDateTime.now().format(FILE_NAME_TIMESTAMP) + "."
                + request.getFormat().name().toLowerCase();
    }

    private String safeSheetName(String name) {
        String safe = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    private String safeCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            return "'" + text;
        }
        return text.length() > 32_767 ? text.substring(0, 32_767) : text;
    }

    private String joinHeaders(List<QueryColumn> columns) {
        return String.join(" | ",
                columns.stream().map(QueryColumn::getName).map(this::safeCell).toList());
    }

    private String joinRow(List<QueryColumn> columns, Map<String, Object> row) {
        return String.join(" | ", columns.stream()
                .map(column -> safeCell(cellText(column, row.get(column.getBizName())))).toList());
    }

    private String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length - 3) + "...";
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete export file: errorType={}", e.getClass().getSimpleName());
        }
    }

    private record ExportSheet(String name, List<QueryColumn> columns,
            List<Map<String, Object>> rows) {}

    private record ChartSeries(List<String> categories, List<Double> values) {}

    private record ExportData(List<ExportSheet> sheets, long rowCount, boolean masked,
            Set<String> maskedColumns, String conclusion, String title, String dateRange,
            String chartType) {}
}
