package com.tencent.supersonic.headless.server.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.util.FileUtils;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.google.common.collect.Lists;
import com.tencent.supersonic.common.pojo.*;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.headless.api.pojo.DrillDownDimension;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.RelateDimension;
import com.tencent.supersonic.headless.api.pojo.enums.SemanticType;
import com.tencent.supersonic.headless.api.pojo.request.BatchDownloadReq;
import com.tencent.supersonic.headless.api.pojo.request.DownloadMetricReq;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.core.utils.DataTransformUtils;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.pojo.DataDownload;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DownloadService;
import com.tencent.supersonic.headless.server.service.MetricService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DownloadServiceImpl implements DownloadService {

    private static final String internMetricCol = "指标名称";

    private static final long downloadLimit = Constants.DEFAULT_DOWNLOAD_LIMIT;

    private static final String dateFormat = "yyyyMMddHHmmss";

    private final MetricService metricService;

    private final DimensionService dimensionService;

    private final SemanticLayerService queryService;

    private final AuditEventPublisher auditEventPublisher;

    public DownloadServiceImpl(MetricService metricService, DimensionService dimensionService,
            SemanticLayerService queryService, AuditEventPublisher auditEventPublisher) {
        this.metricService = metricService;
        this.dimensionService = dimensionService;
        this.queryService = queryService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Override
    public void downloadByStruct(DownloadMetricReq downloadMetricReq, User user,
            HttpServletResponse response) throws Exception {
        long exportStart = System.nanoTime();
        Collection<String> metricCodes =
                metricCodes(downloadMetricReq.getMetricNames(), downloadMetricReq.getMetricIds());
        String resourceId = resourceId(downloadMetricReq.getDomainId());
        publishExportStarted(user, "METRIC", resourceId, metricCodes, 1);
        String fileName =
                String.format("%s_%s.xlsx", "supersonic", DateUtils.format(new Date(), dateFormat));
        File file = null;
        ExportSummary summary = ExportSummary.empty();
        try {
            file = FileUtils.createTmpFile(fileName);
            QueryStructReq queryStructReq = metricService.convert(downloadMetricReq);
            SemanticQueryResp queryResult =
                    queryService.queryByReq(queryStructReq.convert(true), user);
            DataDownload dataDownload =
                    buildDataDownload(queryResult, queryStructReq, downloadMetricReq.isTransform());
            summary = ExportSummary.from(queryResult, dataDownload.getData().size(), 1);
            EasyExcel.write(file).sheet("Sheet1").head(dataDownload.getHeaders())
                    .doWrite(dataDownload.getData());
            downloadFile(response, file, fileName);
            publishExportSucceeded(user, "METRIC", resourceId, metricCodes, summary, file.length(),
                    exportStart, 1);
        } catch (Exception e) {
            publishExportFailed(user, "METRIC", resourceId, metricCodes, summary, file, exportStart,
                    e, 1);
            throw e;
        } finally {
            deleteTemporaryFile(file);
        }
    }

    @Override
    public void batchDownload(BatchDownloadReq batchDownloadReq, User user,
            HttpServletResponse response) throws Exception {
        long exportStart = System.nanoTime();
        Collection<String> metricCodes = metricCodes(List.of(), batchDownloadReq.getMetricIds());
        int batchSize = safeSize(batchDownloadReq.getMetricIds());
        publishExportStarted(user, "BATCH_METRIC", "batch", metricCodes, batchSize);
        String fileName =
                String.format("%s_%s.xlsx", "supersonic", DateUtils.format(new Date(), dateFormat));
        File file = null;
        ExportSummary summary = ExportSummary.empty();
        try {
            if (CollectionUtils.isEmpty(batchDownloadReq.getMetricIds())) {
                throw new IllegalArgumentException("At least one metric is required for export");
            }
            file = FileUtils.createTmpFile(fileName);
            summary = writeBatchDownload(batchDownloadReq, user, file);
            downloadFile(response, file, fileName);
            publishExportSucceeded(user, "BATCH_METRIC", "batch", metricCodes, summary,
                    file.length(), exportStart, batchSize);
        } catch (Exception e) {
            publishExportFailed(user, "BATCH_METRIC", "batch", metricCodes, summary, file,
                    exportStart, e, batchSize);
            throw e;
        } finally {
            deleteTemporaryFile(file);
        }
    }

    public void batchDownload(BatchDownloadReq batchDownloadReq, User user, File file)
            throws Exception {
        writeBatchDownload(batchDownloadReq, user, file);
    }

    private ExportSummary writeBatchDownload(BatchDownloadReq batchDownloadReq, User user,
            File file) throws Exception {
        List<Long> metricIds = batchDownloadReq.getMetricIds();
        if (CollectionUtils.isEmpty(metricIds)) {
            throw new IllegalArgumentException("At least one metric is required for export");
        }
        MetaFilter metaFilter = new MetaFilter();
        metaFilter.setIds(metricIds);
        List<MetricResp> metricResps = metricService.getMetrics(metaFilter);
        Map<String, List<MetricResp>> metricMap = getMetricMap(metricResps);
        List<Long> dimensionIds = metricResps.stream()
                .map(metricResp -> metricService.getDrillDownDimension(metricResp.getId()))
                .flatMap(Collection::stream).map(DrillDownDimension::getDimensionId)
                .collect(Collectors.toList());
        metaFilter.setIds(dimensionIds);
        Map<Long, DimensionResp> dimensionRespMap = dimensionService.getDimensions(metaFilter)
                .stream().collect(Collectors.toMap(DimensionResp::getId, d -> d));
        ExcelWriter excelWriter = EasyExcel.write(file).build();
        int sheetCount = 1;
        long rowCount = 0;
        boolean dataMasked = false;
        Set<String> maskedFields = new LinkedHashSet<>();
        try {
            for (List<MetricResp> metrics : metricMap.values()) {
                if (CollectionUtils.isEmpty(metrics)) {
                    continue;
                }
                MetricResp metricResp = metrics.get(0);
                List<DimensionResp> dimensions =
                        getMetricRelaDimensions(metricResp, dimensionRespMap);
                for (MetricResp metric : metrics) {
                    QueryStructReq queryStructReq =
                            buildDownloadReq(dimensions, metric, batchDownloadReq);
                    QuerySqlReq querySqlReq = queryStructReq.convert();
                    querySqlReq.setNeedAuth(true);
                    SemanticQueryResp queryResult = queryService.queryByReq(querySqlReq, user);
                    DataDownload dataDownload = buildDataDownload(queryResult, queryStructReq,
                            batchDownloadReq.isTransform());
                    WriteSheet writeSheet = EasyExcel.writerSheet("Sheet" + sheetCount)
                            .head(dataDownload.getHeaders()).build();
                    excelWriter.write(dataDownload.getData(), writeSheet);
                    rowCount += dataDownload.getData().size();
                    dataMasked = dataMasked || queryResult.isDataMasked();
                    if (queryResult.getMaskedColumns() != null) {
                        maskedFields.addAll(queryResult.getMaskedColumns());
                    }
                }
                sheetCount++;
            }
        } finally {
            excelWriter.finish();
        }
        return new ExportSummary(rowCount, Math.max(0, sheetCount - 1), dataMasked, maskedFields);
    }

    private List<List<String>> buildHeader(SemanticQueryResp semanticQueryResp) {
        List<List<String>> header = Lists.newArrayList();
        for (QueryColumn column : semanticQueryResp.getColumns()) {
            header.add(Lists.newArrayList(column.getName()));
        }
        return header;
    }

    private List<List<String>> buildHeader(List<QueryColumn> queryColumns, List<String> dateList) {
        List<List<String>> headers = Lists.newArrayList();
        for (QueryColumn queryColumn : queryColumns) {
            if (SemanticType.DATE.name().equals(queryColumn.getShowType())) {
                continue;
            }
            headers.add(Lists.newArrayList(queryColumn.getName()));
        }
        for (String date : dateList) {
            headers.add(Lists.newArrayList(date));
        }
        headers.add(Lists.newArrayList(internMetricCol));
        return headers;
    }

    private List<List<String>> buildData(SemanticQueryResp semanticQueryResp) {
        List<List<String>> data = new ArrayList<>();
        for (Map<String, Object> row : semanticQueryResp.getResultList()) {
            List<String> rowData = new ArrayList<>();
            for (QueryColumn column : semanticQueryResp.getColumns()) {
                rowData.add(String.valueOf(row.get(column.getBizName())));
            }
            data.add(rowData);
        }
        return data;
    }

    private List<List<String>> buildData(List<List<String>> headers, Map<String, String> nameMap,
            List<Map<String, Object>> dataTransformed, String metricName) {
        List<List<String>> data = Lists.newArrayList();
        for (Map<String, Object> map : dataTransformed) {
            List<String> row = Lists.newArrayList();
            for (List<String> header : headers) {
                String head = header.get(0);
                if (internMetricCol.equals(head)) {
                    continue;
                }
                Object object = map.getOrDefault(nameMap.getOrDefault(head, head), "");
                if (object == null) {
                    row.add("");
                } else {
                    row.add(String.valueOf(object));
                }
            }
            row.add(metricName);
            data.add(row);
        }
        return data;
    }

    private DataDownload buildDataDownload(SemanticQueryResp queryResult,
            QueryStructReq queryStructReq, boolean isTransform) {
        List<QueryColumn> metricColumns = queryResult.getMetricColumns();
        List<QueryColumn> dimensionColumns = queryResult.getDimensionColumns();
        if (isTransform && !CollectionUtils.isEmpty(metricColumns)) {
            QueryColumn metric = metricColumns.get(0);
            List<String> groups = queryStructReq.getGroups();
            List<Map<String, Object>> dataTransformed =
                    DataTransformUtils.transform(queryResult.getResultList(), metric.getBizName(),
                            groups, queryStructReq.getDateInfo());
            List<List<String>> headers =
                    buildHeader(dimensionColumns, queryStructReq.getDateInfo().getDateList());
            List<List<String>> data = buildData(headers, getDimensionNameMap(dimensionColumns),
                    dataTransformed, metric.getName());
            return DataDownload.builder().headers(headers).data(data).build();
        } else {
            List<List<String>> data = buildData(queryResult);
            List<List<String>> header = buildHeader(queryResult);
            return DataDownload.builder().data(data).headers(header).build();
        }
    }

    private QueryStructReq buildDownloadReq(List<DimensionResp> dimensionResps,
            MetricResp metricResp, BatchDownloadReq batchDownloadReq) {
        DateConf dateConf = batchDownloadReq.getDateInfo();
        Set<Long> modelIds =
                dimensionResps.stream().map(DimensionResp::getModelId).collect(Collectors.toSet());
        modelIds.add(metricResp.getModelId());
        QueryStructReq queryStructReq = new QueryStructReq();
        queryStructReq.setGroups(dimensionResps.stream().map(DimensionResp::getBizName)
                .collect(Collectors.toList()));
        queryStructReq.getGroups().add(0, dateConf.getDateField());
        Aggregator aggregator = new Aggregator();
        aggregator.setColumn(metricResp.getBizName());
        queryStructReq.setAggregators(Lists.newArrayList(aggregator));
        queryStructReq.setDateInfo(dateConf);
        queryStructReq.setModelIds(modelIds);
        queryStructReq.setLimit(downloadLimit);
        return queryStructReq;
    }

    private Map<String, List<MetricResp>> getMetricMap(List<MetricResp> metricResps) {
        for (MetricResp metricResp : metricResps) {
            List<DrillDownDimension> drillDownDimensions =
                    metricService.getDrillDownDimension(metricResp.getId());
            RelateDimension relateDimension =
                    RelateDimension.builder().drillDownDimensions(drillDownDimensions).build();
            metricResp.setRelateDimension(relateDimension);
        }
        return metricResps.stream()
                .collect(Collectors.groupingBy(MetricResp::getRelaDimensionIdKey));
    }

    private Map<String, String> getDimensionNameMap(List<QueryColumn> queryColumns) {
        return queryColumns.stream()
                .collect(Collectors.toMap(QueryColumn::getName, QueryColumn::getBizName));
    }

    private List<DimensionResp> getMetricRelaDimensions(MetricResp metricResp,
            Map<Long, DimensionResp> dimensionRespMap) {
        if (metricResp.getRelateDimension() == null || CollectionUtils
                .isEmpty(metricResp.getRelateDimension().getDrillDownDimensions())) {
            return Lists.newArrayList();
        }
        return metricResp.getRelateDimension().getDrillDownDimensions().stream().map(
                drillDownDimension -> dimensionRespMap.get(drillDownDimension.getDimensionId()))
                .filter(Objects::nonNull).collect(Collectors.toList());
    }

    private void downloadFile(HttpServletResponse response, File file, String filename)
            throws IOException {
        response.reset();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.addHeader("Content-Disposition",
                "attachment;filename=" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        response.addHeader("Content-Length", String.valueOf(file.length()));
        response.setContentType("application/octet-stream");
        try (OutputStream outputStream = new BufferedOutputStream(response.getOutputStream())) {
            Files.copy(file.toPath(), outputStream);
            outputStream.flush();
        }
    }

    private void publishExportStarted(User user, String resourceType, String resourceId,
            Collection<String> metricCodes, int batchSize) {
        auditEventPublisher.publishRequired(AuditEvent.builder()
                .eventType(AuditEventType.EXPORT_STARTED).outcome(AuditOutcome.UNKNOWN)
                .resourceType(resourceType).resourceId(resourceId).metricCodes(metricCodes)
                .fileType("XLSX")
                .metadata(exportMetadata("STARTED", batchSize, 0, 0, Set.of(), null)).build(),
                user);
    }

    private void publishExportSucceeded(User user, String resourceType, String resourceId,
            Collection<String> metricCodes, ExportSummary summary, long fileSize, long exportStart,
            int batchSize) {
        auditEventPublisher.publishRequired(AuditEvent.builder()
                .eventType(AuditEventType.EXPORT_SUCCEEDED).outcome(AuditOutcome.SUCCESS)
                .resourceType(resourceType).resourceId(resourceId).metricCodes(metricCodes)
                .maskingSummary(summary.maskingSummary()).exportRowCount(summary.rowCount())
                .fileType("XLSX").fileSize(fileSize).durationMs(elapsedMillis(exportStart))
                .metadata(exportMetadata("SUCCEEDED", batchSize, summary.sheetCount(),
                        summary.rowCount(), summary.maskedFields(), null))
                .build(), user);
    }

    private void publishExportFailed(User user, String resourceType, String resourceId,
            Collection<String> metricCodes, ExportSummary summary, File file, long exportStart,
            Exception failure, int batchSize) {
        try {
            auditEventPublisher.publishRequired(AuditEvent.builder()
                    .eventType(AuditEventType.EXPORT_FAILED).outcome(AuditOutcome.FAILURE)
                    .resourceType(resourceType).resourceId(resourceId)
                    .reasonCode("EXPORT_EXCEPTION").metricCodes(metricCodes)
                    .maskingSummary(summary.maskingSummary()).exportRowCount(summary.rowCount())
                    .fileType("XLSX").fileSize(file == null ? null : file.length())
                    .durationMs(elapsedMillis(exportStart))
                    .metadata(exportMetadata("FAILED", batchSize, summary.sheetCount(),
                            summary.rowCount(), summary.maskedFields(),
                            failure.getClass().getSimpleName()))
                    .build(), user);
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
        }
    }

    private Map<String, Object> exportMetadata(String stage, int batchSize, int sheetCount,
            long rowCount, Collection<String> maskedFields, String exceptionType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stage", stage);
        metadata.put("entryPoint", "download");
        metadata.put("batchSize", batchSize);
        metadata.put("sheetCount", sheetCount);
        metadata.put("rowCount", rowCount);
        metadata.put("maskedFields", maskedFields);
        if (exceptionType != null) {
            metadata.put("exceptionType", exceptionType);
        }
        return metadata;
    }

    private Collection<String> metricCodes(Collection<String> metricNames,
            Collection<Long> metricIds) {
        Set<String> metricCodes = new LinkedHashSet<>();
        if (metricNames != null) {
            metricCodes.addAll(metricNames);
        }
        if (metricIds != null) {
            metricIds.stream().filter(Objects::nonNull).map(String::valueOf)
                    .forEach(metricCodes::add);
        }
        return metricCodes;
    }

    private String resourceId(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private int safeSize(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    private long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private void deleteTemporaryFile(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warn("Failed to delete export temporary file: errorType={}",
                    e.getClass().getSimpleName());
        }
    }

    private record ExportSummary(long rowCount, int sheetCount, boolean dataMasked,
            Set<String> maskedFields) {

        private static ExportSummary empty() {
            return new ExportSummary(0, 0, false, Set.of());
        }

        private static ExportSummary from(SemanticQueryResp response, long rowCount,
                int sheetCount) {
            Set<String> maskedFields = response.getMaskedColumns() == null ? Set.of()
                    : new LinkedHashSet<>(response.getMaskedColumns());
            return new ExportSummary(rowCount, sheetCount, response.isDataMasked(), maskedFields);
        }

        private String maskingSummary() {
            return dataMasked ? "MASKED_FIELDS:" + maskedFields.size() : "NONE";
        }
    }
}
