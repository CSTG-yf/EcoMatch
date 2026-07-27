package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.BusinessInsightReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartInsightResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.processor.execute.BusinessInsightConfig;
import com.tencent.supersonic.chat.server.processor.execute.BusinessInsightProcessor;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class BusinessInsightService {

    private final BusinessInsightProcessor processor;
    private final BusinessInsightConfig config;

    public BusinessInsightService(BusinessInsightConfig config) {
        this.config = config;
        this.processor = new BusinessInsightProcessor(config);
    }

    public ChartInsightResp recommend(BusinessInsightReq request) {
        QueryResult result = analyze(request);
        return ChartInsightResp.builder().recommendedChart(result.getRecommendedChart())
                .candidateCharts(result.getCandidateCharts()).build();
    }

    public BusinessExplanation explain(BusinessInsightReq request) {
        return analyze(request).getBusinessExplanation();
    }

    private QueryResult analyze(BusinessInsightReq request) {
        if (request == null || CollectionUtils.isEmpty(request.getQueryColumns())
                || request.getQueryResults() == null) {
            throw new InvalidArgumentException(
                    "queryColumns must not be empty and queryResults must not be null");
        }
        if (request.getQueryResults().size() > config.getMaxInputRows()) {
            throw new InvalidArgumentException(
                    "queryResults exceeds maximum row count: " + config.getMaxInputRows());
        }
        if (request.getQueryColumns().size() > config.getMaxInputColumns()) {
            throw new InvalidArgumentException(
                    "queryColumns exceeds maximum column count: " + config.getMaxInputColumns());
        }
        validateTextBudget(request);
        if (request.getQueryResults().stream().anyMatch(Objects::isNull)) {
            throw new InvalidArgumentException("queryResults contains a null row");
        }
        Set<String> declaredFields = validateColumns(request);
        validateMaskedColumns(request, declaredFields);
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryColumns(request.getQueryColumns());
        result.setQueryResults(request.getQueryResults());
        result.setDataMasked(request.isDataMasked());
        result.setMaskedColumns(request.getMaskedColumns() == null ? Collections.emptySet()
                : new HashSet<>(request.getMaskedColumns()));

        ExecuteContext context = new ExecuteContext(
                ChatExecuteReq.builder().queryText(request.getQueryText()).build());
        context.setResponse(result);
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setMetrics(
                request.getMetrics() == null ? Collections.emptySet() : request.getMetrics());
        context.setParseInfo(parseInfo);
        processor.process(context);
        return result;
    }

    private Set<String> validateColumns(BusinessInsightReq request) {
        Set<String> fields = new HashSet<>();
        Set<String> canonicalFields = new HashSet<>();
        Set<String> declaredFields = new HashSet<>();
        for (QueryColumn column : request.getQueryColumns()) {
            String field = column == null ? null
                    : StringUtils.firstNonBlank(column.getBizName(), column.getNameEn(),
                            column.getName());
            if (StringUtils.isBlank(field)) {
                throw new InvalidArgumentException("queryColumns contains an unnamed field");
            }
            if (!fields.add(field.toLowerCase(Locale.ROOT))) {
                throw new InvalidArgumentException(
                        "queryColumns contains duplicate field: " + field);
            }
            canonicalFields.add(field);
            Set<String> aliases = Stream
                    .of(column.getBizName(), column.getNameEn(), column.getName())
                    .filter(StringUtils::isNotBlank).map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            if (aliases.stream().anyMatch(declaredFields::contains)) {
                throw new InvalidArgumentException(
                        "queryColumns contains a duplicate field alias: " + field);
            }
            declaredFields.addAll(aliases);
            if (!request.getQueryResults().isEmpty() && request.getQueryResults().stream()
                    .noneMatch(row -> row != null && row.containsKey(field))) {
                throw new InvalidArgumentException(
                        "queryResults does not contain declared field: " + field);
            }
        }
        request.getQueryResults()
                .forEach(row -> validateResultRowFields(row.keySet(), canonicalFields));
        return declaredFields;
    }

    private void validateResultRowFields(Set<String> rowFields, Set<String> canonicalFields) {
        if (rowFields.isEmpty()) {
            throw new InvalidArgumentException("queryResults contains an empty row");
        }
        Set<String> normalizedFields = new HashSet<>();
        for (String field : rowFields) {
            if (StringUtils.isBlank(field)) {
                throw new InvalidArgumentException("queryResults contains an unnamed result field");
            }
            if (!normalizedFields.add(field.toLowerCase(Locale.ROOT))) {
                throw new InvalidArgumentException(
                        "queryResults contains case-insensitive duplicate fields");
            }
            if (!canonicalFields.contains(field)) {
                throw new InvalidArgumentException(
                        "queryResults contains a non-canonical or undeclared field: " + field);
            }
        }
    }

    private void validateMaskedColumns(BusinessInsightReq request, Set<String> declaredFields) {
        Set<String> maskedColumns = request.getMaskedColumns() == null ? Collections.emptySet()
                : request.getMaskedColumns();
        if (maskedColumns.stream().anyMatch(StringUtils::isBlank)
                || maskedColumns.stream().map(value -> value.toLowerCase(Locale.ROOT))
                        .anyMatch(value -> !declaredFields.contains(value))) {
            throw new InvalidArgumentException("maskedColumns contains an unknown or blank field");
        }
    }

    private void validateTextBudget(BusinessInsightReq request) {
        if (length(request.getQueryText()) > config.getMaxQueryTextLength()) {
            throw new InvalidArgumentException(
                    "queryText exceeds maximum length: " + config.getMaxQueryTextLength());
        }
        if (request.getMetrics() != null
                && request.getMetrics().size() > config.getMaxInputColumns()) {
            throw new InvalidArgumentException(
                    "metrics exceeds maximum count: " + config.getMaxInputColumns());
        }
        if (request.getMaskedColumns() != null
                && request.getMaskedColumns().size() > config.getMaxInputColumns()) {
            throw new InvalidArgumentException(
                    "maskedColumns exceeds maximum count: " + config.getMaxInputColumns());
        }

        long total = length(request.getQueryText());
        for (QueryColumn column : request.getQueryColumns()) {
            if (column == null) {
                continue;
            }
            total += validateMetadata(Arrays.asList(column.getName(), column.getType(),
                    column.getBizName(), column.getNameEn(), column.getShowType(),
                    column.getDataFormatType(), column.getComment()));
        }
        if (request.getMetrics() != null) {
            for (SchemaElement metric : request.getMetrics()) {
                if (metric == null) {
                    throw new InvalidArgumentException("metrics contains a null definition");
                }
                total += validateMetadata(Arrays.asList(metric.getDataSetName(), metric.getName(),
                        metric.getBizName(), metric.getDefaultAgg(), metric.getDataFormatType(),
                        metric.getDescription()));
                if (metric.getAlias() != null
                        && metric.getAlias().size() > config.getMaxInputColumns()) {
                    throw new InvalidArgumentException(
                            "metric aliases exceeds maximum count: " + config.getMaxInputColumns());
                }
                total += validateMetadata(metric.getAlias());
                Object unit = metric.getExtInfo() == null ? null : metric.getExtInfo().get("unit");
                total += validateScalarLength(unit, config.getMaxMetadataTextLength(),
                        "metric unit");
            }
        }
        if (request.getMaskedColumns() != null) {
            total += validateMetadata(request.getMaskedColumns());
        }
        for (Map<String, Object> row : request.getQueryResults()) {
            if (row == null) {
                continue;
            }
            total += validateMetadata(row.keySet());
            for (Object value : row.values()) {
                total += validateScalarLength(value, config.getMaxCellTextLength(), "cell value");
                if (total > config.getMaxTotalInputCharacters()) {
                    throw new InvalidArgumentException("business insight input exceeds maximum "
                            + "text size: " + config.getMaxTotalInputCharacters());
                }
            }
        }
        if (total > config.getMaxTotalInputCharacters()) {
            throw new InvalidArgumentException("business insight input exceeds maximum text size: "
                    + config.getMaxTotalInputCharacters());
        }
    }

    private long validateMetadata(Iterable<String> values) {
        long total = 0;
        if (values == null) {
            return total;
        }
        for (String value : values) {
            if (length(value) > config.getMaxMetadataTextLength()) {
                throw new InvalidArgumentException("metadata value exceeds maximum length: "
                        + config.getMaxMetadataTextLength());
            }
            total += length(value);
        }
        return total;
    }

    private int validateScalarLength(Object value, int maximum, String type) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>
                || value.getClass().isArray()) {
            throw new InvalidArgumentException(type + " must be a scalar value");
        }
        int length = value instanceof CharSequence ? ((CharSequence) value).length()
                : String.valueOf(value).length();
        if (length > maximum) {
            throw new InvalidArgumentException(type + " exceeds maximum length: " + maximum);
        }
        return length;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
