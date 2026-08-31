package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.persistence.repository.ChatQueryRepository;
import com.tencent.supersonic.chat.server.util.BankResultColumnLabels;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.enums.SemanticType;
import com.tencent.supersonic.headless.api.pojo.request.SchemaFilterReq;
import com.tencent.supersonic.headless.api.pojo.response.ChatSnapshotExportData;
import com.tencent.supersonic.headless.api.pojo.response.MetricSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import com.tencent.supersonic.headless.server.service.ChatSnapshotExportResolver;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads a chat query snapshot for export directly from chat history. Only the original asker
 * may export, and the visibility of every backing model is re-checked at export time.
 *
 * <p>Besides the raw snapshot facts, this resolver assembles the presentation metadata for the
 * business report: Chinese column labels (projector contract labels first, then semantic-layer
 * bizName→name), metric display formats, the conclusion text and the data date range.
 */
@Slf4j
@Service
public class ChatSnapshotExportResolverImpl implements ChatSnapshotExportResolver {

    private static final int QUERY_STATE_SUCCESS = 1;

    private final ChatQueryRepository chatQueryRepository;
    private final SchemaService schemaService;
    private final ModelService modelService;

    public ChatSnapshotExportResolverImpl(ChatQueryRepository chatQueryRepository,
            SchemaService schemaService, ModelService modelService) {
        this.chatQueryRepository = chatQueryRepository;
        this.schemaService = schemaService;
        this.modelService = modelService;
    }

    @Override
    public ChatSnapshotExportData resolve(long queryId, User user) {
        ChatQueryDO chatQuery = chatQueryRepository.getChatQueryDO(queryId);
        if (chatQuery == null) {
            throw new InvalidArgumentException("问数记录不存在或已删除");
        }
        if (user == null || !Objects.equals(user.getName(), chatQuery.getUserName())) {
            throw new InvalidPermissionException("仅提问人本人可以导出该问数结果");
        }
        QueryResult result = parseResult(chatQuery);
        Long dataSetId = result.getChatContext() == null ? null
                : result.getChatContext().getDataSetId();
        if (dataSetId == null) {
            throw new InvalidArgumentException("该结果不包含语义数据，无法导出");
        }
        SemanticSchemaResp schema = fetchSchema(dataSetId);
        checkModelVisible(user, schema);
        List<QueryColumn> columns =
                result.getQueryColumns() == null ? List.of() : result.getQueryColumns();
        List<Map<String, Object>> rows = result.getQueryResults();
        enrichColumns(columns, schema);
        return ChatSnapshotExportData.builder().queryId(queryId)
                .question(chatQuery.getQueryText()).dataSetId(dataSetId).columns(columns).rows(rows)
                .masked(result.isDataMasked()).maskedColumns(result.getMaskedColumns() == null
                        ? Set.of() : result.getMaskedColumns())
                .conclusion(conclusionOf(result))
                .chartType(chartTypeOf(result, columns, rows))
                .dateRange(resolveDateRange(result, columns, rows)).build();
    }

    /**
     * The bank answer path does not always persist a recommendedChart. When it is missing, infer
     * one from the result shape: a date column makes a trend (LINE); any other category column
     * together with a numeric column makes a comparison (BAR). Single-row or single-column
     * results stay chartless.
     */
    private String chartTypeOf(QueryResult result, List<QueryColumn> columns,
            List<Map<String, Object>> rows) {
        if (result.getRecommendedChart() != null
                && StringUtils.isNotBlank(result.getRecommendedChart().getChartType())) {
            return result.getRecommendedChart().getChartType();
        }
        if (columns.size() < 2 || rows == null || rows.size() < 2) {
            return null;
        }
        boolean hasNumeric = columns.stream().anyMatch(ChatSnapshotExportResolverImpl::isNumeric);
        if (!hasNumeric) {
            return null;
        }
        if (columns.stream().anyMatch(ChatSnapshotExportResolverImpl::isDate)) {
            return "LINE";
        }
        boolean hasCategory =
                columns.stream().anyMatch(column -> !isNumeric(column) && !isDate(column));
        return hasCategory ? "BAR" : null;
    }

    private static boolean isNumeric(QueryColumn column) {
        return "NUMBER".equalsIgnoreCase(StringUtils.defaultString(column.getType()))
                || "NUMBER".equalsIgnoreCase(StringUtils.defaultString(column.getShowType()));
    }

    private static boolean isDate(QueryColumn column) {
        return "DATE".equalsIgnoreCase(StringUtils.defaultString(column.getType()))
                || "DATE".equalsIgnoreCase(StringUtils.defaultString(column.getShowType()));
    }

    /**
     * The conclusion is the business explanation summary when the insight processor ran; the
     * bank final-answer path owns the user-visible answer instead and stores it in textSummary,
     * so fall back to it when no explanation was persisted.
     */
    private String conclusionOf(QueryResult result) {
        if (result.getBusinessExplanation() != null
                && StringUtils.isNotBlank(result.getBusinessExplanation().getSummary())) {
            return result.getBusinessExplanation().getSummary();
        }
        return StringUtils.defaultIfBlank(result.getTextSummary(), null);
    }

    private String resolveDateRange(QueryResult result, List<QueryColumn> columns,
            List<Map<String, Object>> rows) {
        DateConf dateInfo = result.getChatContext() == null ? null
                : result.getChatContext().getDateInfo();
        if (dateInfo != null && StringUtils.isNotBlank(dateInfo.getStartDate())
                && StringUtils.isNotBlank(dateInfo.getEndDate())) {
            return dateInfo.getStartDate().equals(dateInfo.getEndDate()) ? dateInfo.getStartDate()
                    : dateInfo.getStartDate() + " 至 " + dateInfo.getEndDate();
        }
        for (QueryColumn column : columns) {
            if (!isDateColumn(column)) {
                continue;
            }
            List<String> values = rows.stream().map(row -> row.get(column.getBizName()))
                    .filter(Objects::nonNull).map(String::valueOf).sorted().distinct().toList();
            if (!values.isEmpty()) {
                return values.size() == 1 ? values.get(0)
                        : values.get(0) + " 至 " + values.get(values.size() - 1);
            }
        }
        return null;
    }

    private boolean isDateColumn(QueryColumn column) {
        if (SemanticType.DATE.name().equalsIgnoreCase(column.getShowType())) {
            return true;
        }
        String field = StringUtils.defaultString(column.getBizName()).toLowerCase(Locale.ROOT);
        return field.matches(".*(date|day|month|year|日期|月份|年度).*");
    }

    /**
     * Assigns Chinese display names and metric display formats. Only the display name
     * ({@code QueryColumn.name}) is changed; rows stay keyed by {@code bizName}.
     */
    private void enrichColumns(List<QueryColumn> columns, SemanticSchemaResp schema) {
        Map<String, String> schemaNames = schemaNames(schema);
        Map<String, MetricSchemaResp> metrics = metricsByBizName(schema);
        for (QueryColumn column : columns) {
            if (column == null || StringUtils.isBlank(column.getBizName())) {
                continue;
            }
            String label = BankResultColumnLabels.labelOf(column.getBizName());
            if (label == null) {
                label = schemaNames.get(column.getBizName());
            }
            if (StringUtils.isNotBlank(label)) {
                column.setName(label);
            }
            MetricSchemaResp metric = metrics.get(column.getBizName());
            if (metric != null && StringUtils.isBlank(column.getDataFormatType())
                    && StringUtils.isNotBlank(metric.getDataFormatType())) {
                column.setDataFormatType(metric.getDataFormatType());
                column.setDataFormat(metric.getDataFormat());
            }
        }
    }

    private Map<String, String> schemaNames(SemanticSchemaResp schema) {
        if (schema == null) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        Stream
                .concat(schema.getDimensions() == null ? Stream.empty()
                        : schema.getDimensions().stream(),
                        schema.getMetrics() == null ? Stream.empty() : schema.getMetrics().stream())
                .filter(Objects::nonNull).forEach(item -> {
                    if (StringUtils.isNotBlank(item.getBizName())
                            && StringUtils.isNotBlank(item.getName())) {
                        names.putIfAbsent(item.getBizName(), item.getName());
                    }
                });
        return names;
    }

    private Map<String, MetricSchemaResp> metricsByBizName(SemanticSchemaResp schema) {
        if (schema == null || schema.getMetrics() == null) {
            return Map.of();
        }
        Map<String, MetricSchemaResp> metrics = new LinkedHashMap<>();
        schema.getMetrics().stream().filter(Objects::nonNull)
                .filter(metric -> StringUtils.isNotBlank(metric.getBizName()))
                .forEach(metric -> metrics.putIfAbsent(metric.getBizName(), metric));
        return metrics;
    }

    private QueryResult parseResult(ChatQueryDO chatQuery) {
        QueryResult result = null;
        if (Objects.equals(chatQuery.getQueryState(), QUERY_STATE_SUCCESS)
                && StringUtils.isNotBlank(chatQuery.getQueryResult())) {
            try {
                result = JsonUtil.toObject(chatQuery.getQueryResult(), QueryResult.class);
            } catch (RuntimeException e) {
                log.warn("Failed to parse stored query result: queryId={}",
                        chatQuery.getQuestionId());
            }
        }
        if (result == null || CollectionUtils.isEmpty(result.getQueryResults())) {
            throw new InvalidArgumentException("该问数没有成功结果，无法导出");
        }
        return result;
    }

    private SemanticSchemaResp fetchSchema(Long dataSetId) {
        SchemaFilterReq filter = new SchemaFilterReq();
        filter.setDataSetId(dataSetId);
        return schemaService.fetchSemanticSchema(filter);
    }

    private void checkModelVisible(User user, SemanticSchemaResp schema) {
        List<Long> modelIds = schema == null ? null : schema.getModelIds();
        if (CollectionUtils.isEmpty(modelIds)) {
            throw new InvalidArgumentException("该结果不包含语义数据，无法导出");
        }
        Set<Long> visible = modelService.getModelListWithAuth(user, null, AuthType.VIEWER)
                .stream().map(ModelResp::getId).collect(Collectors.toSet());
        if (!visible.containsAll(modelIds)) {
            throw new InvalidPermissionException("您已没有该数据所属模型的访问权限，导出被拒绝");
        }
    }
}
