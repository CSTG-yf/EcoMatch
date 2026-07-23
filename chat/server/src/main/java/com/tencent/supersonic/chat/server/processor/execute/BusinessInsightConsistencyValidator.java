package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartRecommendation;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.headless.api.pojo.enums.SemanticType;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Independently verifies that generated charts and explanations are grounded in query results. */
final class BusinessInsightConsistencyValidator {

    private static final Pattern RANGE =
            Pattern.compile("范围为(-?[0-9]+(?:\\.[0-9]+)?)至" + "(-?[0-9]+(?:\\.[0-9]+)?)$");
    private static final Pattern FIRST_LAST =
            Pattern.compile("首条记录为(-?[0-9]+(?:\\.[0-9]+)?)，" + "末条记录为(-?[0-9]+(?:\\.[0-9]+)?)$");
    private static final Pattern LATEST = Pattern.compile("最新记录为(-?[0-9]+(?:\\.[0-9]+)?)$");
    private static final Pattern PERCENT = Pattern.compile("(?:变化|最高，为)(-?[0-9]+(?:\\.[0-9]+)?)%");

    void validate(QueryResult result) {
        if (result == null || result.getQueryColumns() == null
                || result.getQueryResults() == null) {
            throw inconsistent("query result structure is incomplete");
        }
        Set<String> fields = result.getQueryColumns().stream().map(this::fieldName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        validateChart(result.getRecommendedChart(), fields, "recommended chart");
        if (result.getCandidateCharts() == null) {
            throw inconsistent("candidate charts are missing");
        }
        for (ChartRecommendation candidate : result.getCandidateCharts()) {
            validateChart(candidate, fields, "candidate chart");
        }
        validateExplanation(result);
    }

    private void validateChart(ChartRecommendation chart, Set<String> fields, String location) {
        if (chart == null || StringUtils.isBlank(chart.getChartType())
                || StringUtils.isBlank(chart.getReason())) {
            throw inconsistent(location + " is incomplete");
        }
        validateConfidence(chart.getConfidence(), location);
        if (chart.getDimensionFields() == null || chart.getMetricFields() == null) {
            throw inconsistent(location + " field lists are missing");
        }
        Set<String> referenced = new LinkedHashSet<>(chart.getDimensionFields());
        referenced.addAll(chart.getMetricFields());
        referenced.removeAll(fields);
        if (!referenced.isEmpty()) {
            throw inconsistent(location + " references unknown fields: " + referenced);
        }
    }

    private void validateExplanation(QueryResult result) {
        BusinessExplanation explanation = result.getBusinessExplanation();
        if (explanation == null || StringUtils.isBlank(explanation.getSummary())) {
            throw inconsistent("business explanation is missing");
        }
        validateConfidence(explanation.getConfidence(), "business explanation");
        if (!Objects.equals(result.getTextSummary(), explanation.getSummary())) {
            throw inconsistent("text summary differs from business explanation");
        }
        if (explanation.getEvidence() == null || explanation.getWarnings() == null
                || explanation.getMetricDefinitions() == null) {
            throw inconsistent("business explanation collections are missing");
        }
        requireSummaryContains(explanation.getSummary(), explanation.getEvidence(), "evidence");
        requireSummaryContains(explanation.getSummary(), explanation.getWarnings(), "warning");
        requireSummaryContains(explanation.getSummary(),
                explanation.getMetricDefinitions().entrySet().stream()
                        .map(entry -> entry.getKey() + "：" + entry.getValue()).toList(),
                "metric definition");
        if (!explanation.getSummary().contains("查询返回" + result.getQueryResults().size() + "条记录")) {
            throw inconsistent("summary row count is not grounded in query results");
        }
        String expectedTimeRange = resolveTimeRange(result);
        if (!Objects.equals(expectedTimeRange, explanation.getTimeRange())) {
            throw inconsistent("time range is not grounded in query results");
        }
        validateEvidence(result, explanation.getEvidence());
    }

    private void requireSummaryContains(String summary, List<String> statements, String type) {
        for (String statement : statements) {
            if (StringUtils.isBlank(statement) || !summary.contains(statement)
                    || containsNonFiniteNumber(statement)) {
                throw inconsistent(type + " is blank, non-finite, or absent from summary");
            }
        }
    }

    private void validateEvidence(QueryResult result, List<String> evidence) {
        NumericFacts facts = numericFacts(result);
        for (String statement : evidence) {
            Matcher range = RANGE.matcher(statement);
            Matcher firstLast = FIRST_LAST.matcher(statement);
            Matcher latest = LATEST.matcher(statement);
            Matcher percent = PERCENT.matcher(statement);
            if (range.find()) {
                requirePair(facts.ranges, decimal(range.group(1)), decimal(range.group(2)),
                        "range");
            } else if (firstLast.find()) {
                requirePair(facts.firstLast, decimal(firstLast.group(1)),
                        decimal(firstLast.group(2)), "first/last values");
            } else if (latest.find()) {
                requireValue(facts.latest, decimal(latest.group(1)), "latest value");
            } else if (statement.contains("异常候选值：")) {
                String rawValues = statement.substring(statement.indexOf('：') + 1);
                for (String value : rawValues.split("、")) {
                    requireValue(facts.rawValues, decimal(value), "anomaly value");
                }
            } else if (percent.find()) {
                requireValue(facts.percentages, decimal(percent.group(1)), "percentage");
            } else {
                throw inconsistent("unsupported evidence statement: " + statement);
            }
        }
    }

    private NumericFacts numericFacts(QueryResult result) {
        List<String> metrics = result.getQueryColumns().stream()
                .filter(column -> SemanticType.NUMBER.name().equalsIgnoreCase(column.getShowType())
                        || result.getQueryResults().stream().map(row -> row.get(fieldName(column)))
                                .anyMatch(Number.class::isInstance))
                .map(this::fieldName).toList();
        List<BigDecimal> rawValues = new ArrayList<>();
        List<ValuePair> ranges = new ArrayList<>();
        List<ValuePair> firstLast = new ArrayList<>();
        List<BigDecimal> latest = new ArrayList<>();
        List<BigDecimal> percentages = new ArrayList<>();
        for (String metric : metrics) {
            List<BigDecimal> values = result.getQueryResults().stream().map(row -> row.get(metric))
                    .map(this::decimalOrNull).filter(Objects::nonNull).toList();
            if (values.isEmpty()) {
                continue;
            }
            rawValues.addAll(values);
            BigDecimal min = values.stream().min(Comparator.naturalOrder()).orElseThrow();
            BigDecimal max = values.stream().max(Comparator.naturalOrder()).orElseThrow();
            ranges.add(new ValuePair(min, max));
            BigDecimal first = values.get(0);
            BigDecimal last = values.get(values.size() - 1);
            firstLast.add(new ValuePair(first, last));
            latest.add(last);
            if (first.signum() != 0) {
                percentages.add(percentageChange(first, last));
            }
            BigDecimal total = values.stream().filter(value -> value.signum() >= 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (values.stream().allMatch(value -> value.signum() >= 0) && total.signum() > 0) {
                for (BigDecimal value : values) {
                    percentages.add(value.divide(total, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)));
                }
            }
        }
        appendTemporalPercentages(result, metrics, percentages);
        return new NumericFacts(rawValues, ranges, firstLast, latest, percentages);
    }

    private void appendTemporalPercentages(QueryResult result, List<String> metrics,
            List<BigDecimal> percentages) {
        String dateField = result.getQueryColumns().stream()
                .filter(column -> SemanticType.DATE.name().equalsIgnoreCase(column.getShowType())
                        || looksLikeDateField(fieldName(column)))
                .map(this::fieldName).findFirst().orElse(null);
        if (dateField == null) {
            return;
        }
        for (String metric : metrics) {
            TreeMap<YearMonth, BigDecimal> values = new TreeMap<>();
            for (Map<String, Object> row : result.getQueryResults()) {
                YearMonth month = yearMonth(row.get(dateField));
                BigDecimal value = decimalOrNull(row.get(metric));
                if (month == null || value == null || values.put(month, value) != null) {
                    values.clear();
                    break;
                }
            }
            if (values.size() < 2) {
                continue;
            }
            BigDecimal current = values.lastEntry().getValue();
            values.headMap(values.lastKey()).values().stream()
                    .filter(baseline -> baseline.signum() != 0)
                    .map(baseline -> percentageChange(baseline, current)).forEach(percentages::add);
        }
    }

    private BigDecimal percentageChange(BigDecimal baseline, BigDecimal current) {
        return current.subtract(baseline).divide(baseline.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private YearMonth yearMonth(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim().replace('/', '-');
        if (text.length() < 7) {
            return null;
        }
        try {
            return YearMonth.parse(text.substring(0, 7));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String resolveTimeRange(QueryResult result) {
        String dateField = result.getQueryColumns().stream()
                .filter(column -> SemanticType.DATE.name().equalsIgnoreCase(column.getShowType())
                        || looksLikeDateField(fieldName(column)))
                .map(this::fieldName).findFirst().orElse(null);
        if (dateField == null) {
            return null;
        }
        List<String> values = result.getQueryResults().stream().map(row -> row.get(dateField))
                .filter(Objects::nonNull).map(String::valueOf).sorted().toList();
        if (values.isEmpty()) {
            return null;
        }
        return values.get(0).equals(values.get(values.size() - 1)) ? values.get(0)
                : values.get(0) + "至" + values.get(values.size() - 1);
    }

    private void validateConfidence(double confidence, String location) {
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw inconsistent(location + " confidence is outside [0, 1]");
        }
    }

    private void requirePair(List<ValuePair> pairs, BigDecimal first, BigDecimal second,
            String description) {
        if (pairs.stream()
                .noneMatch(pair -> equal(pair.first, first) && equal(pair.second, second))) {
            throw inconsistent(description + " is not grounded in query results");
        }
    }

    private void requireValue(List<BigDecimal> values, BigDecimal expected, String description) {
        if (values.stream().noneMatch(value -> equal(value, expected))) {
            throw inconsistent(description + " is not grounded in query results");
        }
    }

    private boolean equal(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) == 0;
    }

    private BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw inconsistent("evidence contains an invalid number");
        }
    }

    private BigDecimal decimalOrNull(Object value) {
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean containsNonFiniteNumber(String value) {
        String normalized = value.toLowerCase();
        return normalized.contains("nan") || normalized.contains("infinity");
    }

    private String fieldName(QueryColumn column) {
        if (StringUtils.isNotBlank(column.getBizName())) {
            return column.getBizName();
        }
        return StringUtils.defaultIfBlank(column.getNameEn(), column.getName());
    }

    private boolean looksLikeDateField(String field) {
        return StringUtils.defaultString(field).toLowerCase()
                .matches(".*(date|time|day|month|year|日期|时间|月份|年度).*");
    }

    private IllegalStateException inconsistent(String message) {
        return new IllegalStateException("Business insight consistency check failed: " + message);
    }

    private record ValuePair(BigDecimal first, BigDecimal second) {}

    private record NumericFacts(List<BigDecimal> rawValues, List<ValuePair> ranges,
            List<ValuePair> firstLast, List<BigDecimal> latest, List<BigDecimal> percentages) {}
}
