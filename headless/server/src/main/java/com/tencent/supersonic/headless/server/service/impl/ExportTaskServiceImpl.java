package com.tencent.supersonic.headless.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.tencent.supersonic.headless.server.service.DashboardService;
import com.tencent.supersonic.headless.server.service.ExportTaskService;
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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
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

@Slf4j
@Service
public class ExportTaskServiceImpl implements ExportTaskService {

    static final int MAX_QUERIES = 20;
    static final long MAX_ROWS = 10_000;
    static final int MAX_PDF_ROWS = 500;
    static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    static final Duration RETENTION = Duration.ofHours(24);
    private static final Semaphore EXPORT_PERMITS = new Semaphore(4);
    private static final List<String> ORGANIZATION_ATTRIBUTE_KEYS =
            List.of("organizationId", "organizationCode", "orgId", "departmentId");

    private final ExportTaskMapper exportTaskMapper;
    private final SemanticLayerService semanticLayerService;
    private final DashboardService dashboardService;
    private final DashboardExportQueryValidator dashboardExportQueryValidator;
    private final AuditEventPublisher auditEventPublisher;
    private final Path exportRoot;

    public ExportTaskServiceImpl(ExportTaskMapper exportTaskMapper,
            SemanticLayerService semanticLayerService, DashboardService dashboardService,
            DashboardExportQueryValidator dashboardExportQueryValidator,
            AuditEventPublisher auditEventPublisher,
            @Value("${s2.export.storage-dir:${java.io.tmpdir}/supersonic-exports}") String storageDirectory) {
        this.exportTaskMapper = exportTaskMapper;
        this.semanticLayerService = semanticLayerService;
        this.dashboardService = dashboardService;
        this.dashboardExportQueryValidator = dashboardExportQueryValidator;
        this.auditEventPublisher = auditEventPublisher;
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
        String resourceId = dashboard == null ? taskId : String.valueOf(dashboard.getId());
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
            ExportData data = executeQueries(request.getQueries(), user);
            if (request.getFormat() == ExportFormat.PDF && data.rowCount() > MAX_PDF_ROWS) {
                throw new InvalidArgumentException("PDF export cannot exceed 500 data rows");
            }
            Files.createDirectories(exportRoot);
            String extension = request.getFormat().name().toLowerCase();
            String storageKey = taskId + "." + extension;
            Path destination = resolveStorage(storageKey);
            temporary = Files.createTempFile(exportRoot, taskId + "-", ".tmp");
            if (request.getFormat() == ExportFormat.XLSX) {
                writeXlsx(temporary, request, dashboard, data);
            } else {
                writePdf(temporary, request, dashboard, data, user);
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
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder
                    .encode(task.getFileName(), StandardCharsets.UTF_8).replace("+", "%20"));
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
        return new ExportData(sheets, totalRows, masked, maskedColumns);
    }

    private void writeXlsx(Path file, ExportCreateReq request, DashboardResp dashboard,
            ExportData data) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                OutputStream output = Files.newOutputStream(file, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            workbook.setCompressTempFiles(true);
            SXSSFSheet summary = workbook.createSheet("Summary");
            int row = 0;
            row = writeKeyValue(summary, row, "Title", title(request, dashboard));
            row = writeKeyValue(summary, row, "Resource type", request.getResourceType().name());
            row = writeKeyValue(summary, row, "Generated at", new Date().toString());
            row = writeKeyValue(summary, row, "Data masked", String.valueOf(data.masked()));
            row = writeKeyValue(summary, row, "Row count", String.valueOf(data.rowCount()));
            if (dashboard != null) {
                row = writeKeyValue(summary, row, "Dashboard version",
                        String.valueOf(dashboard.getVersion()));
                writeKeyValue(summary, row, "Description",
                        StringUtils.defaultString(dashboard.getDescription()));
            }
            for (ExportSheet exportSheet : data.sheets()) {
                SXSSFSheet sheet = workbook.createSheet(safeSheetName(exportSheet.name()));
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
                        cell.setCellValue(safeCell(values.get(queryColumn.getBizName())));
                    }
                }
            }
            workbook.write(output);
        }
    }

    private void writePdf(Path file, ExportCreateReq request, DashboardResp dashboard,
            ExportData data, User user) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(title(request, dashboard));
        lines.add("Generated: " + new Date());
        lines.add("User: " + user.getDisplayName());
        lines.add("Rows: " + data.rowCount() + " | Masked: " + data.masked());
        if (dashboard != null && StringUtils.isNotBlank(dashboard.getDescription())) {
            lines.add("Description: " + dashboard.getDescription());
        }
        for (ExportSheet sheet : data.sheets()) {
            lines.add("");
            lines.add(sheet.name());
            lines.add(joinHeaders(sheet.columns()));
            for (Map<String, Object> row : sheet.rows()) {
                lines.add(joinRow(sheet.columns(), row));
            }
        }
        try (PDDocument document = new PDDocument()) {
            for (ExportChartReq chart : request.getCharts()) {
                drawChartPage(document, chart, data, user);
            }
            int lineIndex = 0;
            while (lineIndex < lines.size() || document.getNumberOfPages() == 0) {
                BufferedImage image = new BufferedImage(1600, 1100, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setColor(Color.DARK_GRAY);
                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
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
                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
                graphics.drawString(user.getDisplayName() + " | " + new Date(), 50, 1070);
                graphics.dispose();
                PDPage page = new PDPage(
                        new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
                document.addPage(page);
                PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(),
                            page.getMediaBox().getHeight());
                }
            }
            document.save(file.toFile());
        }
    }

    private void validateRequest(ExportCreateReq request) {
        if (request == null || request.getResourceType() == null || request.getFormat() == null) {
            throw new InvalidArgumentException("Export resource type and format are required");
        }
        List<QueryStructReq> queries =
                request.getQueries() == null ? List.of() : request.getQueries();
        request.setQueries(queries);
        if (queries.size() > MAX_QUERIES) {
            throw new InvalidArgumentException("Export cannot contain more than 20 queries");
        }
        if (request.getResourceType() == ExportResourceType.QUERY && queries.size() != 1) {
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
        for (ExportChartReq chart : charts) {
            if (chart == null || chart.getQueryIndex() == null || chart.getQueryIndex() < 0
                    || chart.getQueryIndex() >= queries.size() || chart.getType() == null
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
        task.setFileName(fileName(request));
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
        requireChartField(sheet, chart.getCategoryField());
        requireChartField(sheet, chart.getValueField());
        List<Map<String, Object>> rows = sheet.rows().stream().limit(30).toList();
        if (rows.isEmpty()) {
            return;
        }
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object raw = row.get(chart.getValueField());
            try {
                values.add(raw instanceof Number number ? number.doubleValue()
                        : Double.parseDouble(String.valueOf(raw)));
            } catch (RuntimeException e) {
                throw new InvalidArgumentException(
                        "Export chart value field must contain numeric data");
            }
        }
        double max = values.stream().mapToDouble(Math::abs).max().orElse(1D);
        max = max == 0 ? 1 : max;
        BufferedImage image = new BufferedImage(1600, 1100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.DARK_GRAY);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));
        graphics.drawString(StringUtils.defaultIfBlank(chart.getTitle(), "Chart"), 80, 70);
        int left = 120;
        int top = 130;
        int width = 1350;
        int height = 780;
        graphics.drawLine(left, top + height, left + width, top + height);
        graphics.drawLine(left, top, left, top + height);
        int step = Math.max(1, width / rows.size());
        int previousX = 0;
        int previousY = 0;
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        for (int index = 0; index < rows.size(); index++) {
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
            String label = truncate(safeCell(rows.get(index).get(chart.getCategoryField())), 12);
            graphics.drawString(label, x - Math.min(45, label.length() * 4), top + height + 28);
        }
        graphics.setColor(new Color(210, 210, 210));
        graphics.drawString(user.getDisplayName() + " | " + new Date(), 80, 1050);
        graphics.dispose();
        PDPage page =
                new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
        document.addPage(page);
        PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(),
                    page.getMediaBox().getHeight());
        }
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

    private int writeKeyValue(SXSSFSheet sheet, int rowIndex, String key, String value) {
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

    private String fileName(ExportCreateReq request) {
        String base = StringUtils.defaultIfBlank(request.getTitle(),
                request.getResourceType().name().toLowerCase() + "-export");
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return base + "." + request.getFormat().name().toLowerCase();
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
        return String.join(" | ",
                columns.stream().map(column -> safeCell(row.get(column.getBizName()))).toList());
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

    private record ExportData(List<ExportSheet> sheets, long rowCount, boolean masked,
            Set<String> maskedColumns) {}
}
