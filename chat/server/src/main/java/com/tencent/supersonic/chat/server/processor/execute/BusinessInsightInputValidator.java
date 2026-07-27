package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;

import java.util.Arrays;
import java.util.Map;

/** Enforces one resource contract for main-query, plugin, and independent insight inputs. */
final class BusinessInsightInputValidator {

    private BusinessInsightInputValidator() {}

    static void validate(ExecuteContext context, QueryResult result, BusinessInsightConfig rules) {
        if (result.getQueryResults().size() > rules.getMaxInputRows()) {
            throw invalid("input exceeds maximum row count: " + rules.getMaxInputRows());
        }
        if (result.getQueryColumns().size() > rules.getMaxInputColumns()) {
            throw invalid("input exceeds maximum column count: " + rules.getMaxInputColumns());
        }

        String queryText =
                context.getRequest() == null ? null : context.getRequest().getQueryText();
        if (length(queryText) > rules.getMaxQueryTextLength()) {
            throw invalid("query text exceeds maximum length: " + rules.getMaxQueryTextLength());
        }

        long total = length(queryText);
        for (QueryColumn column : result.getQueryColumns()) {
            if (column == null) {
                continue;
            }
            total = add(total,
                    validateMetadata(Arrays.asList(column.getName(), column.getType(),
                            column.getBizName(), column.getNameEn(), column.getShowType(),
                            column.getDataFormatType(), column.getComment()), rules),
                    rules);
        }

        if (context.getParseInfo() != null && context.getParseInfo().getMetrics() != null) {
            if (context.getParseInfo().getMetrics().size() > rules.getMaxInputColumns()) {
                throw invalid(
                        "metric definition count exceeds maximum: " + rules.getMaxInputColumns());
            }
            for (SchemaElement metric : context.getParseInfo().getMetrics()) {
                if (metric == null) {
                    throw invalid("metric definitions contain a null entry");
                }
                total = add(total,
                        validateMetadata(
                                Arrays.asList(metric.getDataSetName(), metric.getName(),
                                        metric.getBizName(), metric.getDefaultAgg(),
                                        metric.getDataFormatType(), metric.getDescription()),
                                rules),
                        rules);
                if (metric.getAlias() != null
                        && metric.getAlias().size() > rules.getMaxInputColumns()) {
                    throw invalid(
                            "metric alias count exceeds maximum: " + rules.getMaxInputColumns());
                }
                total = add(total, validateMetadata(metric.getAlias(), rules), rules);
                Object unit = metric.getExtInfo() == null ? null : metric.getExtInfo().get("unit");
                total = add(total,
                        validateScalarLength(unit, rules.getMaxMetadataTextLength(), "metric unit"),
                        rules);
            }
        }

        if (result.getMaskedColumns() != null) {
            if (result.getMaskedColumns().size() > rules.getMaxInputColumns()) {
                throw invalid("masked column count exceeds maximum: " + rules.getMaxInputColumns());
            }
            total = add(total, validateMetadata(result.getMaskedColumns(), rules), rules);
        }

        for (Map<String, Object> row : result.getQueryResults()) {
            if (row == null) {
                continue;
            }
            total = add(total, validateMetadata(row.keySet(), rules), rules);
            for (Object value : row.values()) {
                total = add(total,
                        validateScalarLength(value, rules.getMaxCellTextLength(), "cell value"),
                        rules);
            }
        }
    }

    private static long validateMetadata(Iterable<String> values, BusinessInsightConfig rules) {
        long total = 0;
        if (values == null) {
            return total;
        }
        for (String value : values) {
            if (length(value) > rules.getMaxMetadataTextLength()) {
                throw invalid("metadata value exceeds maximum length: "
                        + rules.getMaxMetadataTextLength());
            }
            total += length(value);
        }
        return total;
    }

    private static int validateScalarLength(Object value, int maximum, String type) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>
                || value.getClass().isArray()) {
            throw invalid(type + " must be a scalar value");
        }
        int valueLength = value instanceof CharSequence ? ((CharSequence) value).length()
                : String.valueOf(value).length();
        if (valueLength > maximum) {
            throw invalid(type + " exceeds maximum length: " + maximum);
        }
        return valueLength;
    }

    private static long add(long current, long additional, BusinessInsightConfig rules) {
        long total = current + additional;
        if (total > rules.getMaxTotalInputCharacters()) {
            throw invalid("input exceeds maximum text size: " + rules.getMaxTotalInputCharacters());
        }
        return total;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Business insight " + message);
    }
}
