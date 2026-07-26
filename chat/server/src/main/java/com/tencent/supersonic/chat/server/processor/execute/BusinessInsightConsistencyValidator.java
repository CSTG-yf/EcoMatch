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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Independently verifies that generated charts and explanations are grounded in query results. */
final class BusinessInsightConsistencyValidator {

    private static final Set<String> SUPPORTED_CHART_TYPES =
            Set.of("KPI_CARD", "LINE", "BAR", "PIE", "COMBO", "TABLE");
    private static final Pattern RANGE =
            Pattern.compile("范围为(-?[0-9]+(?:\\.[0-9]+)?)至" + "(-?[0-9]+(?:\\.[0-9]+)?)$");
    private static final Pattern FIRST_LAST =
            Pattern.compile("首条记录为(-?[0-9]+(?:\\.[0-9]+)?)，" + "末条记录为(-?[0-9]+(?:\\.[0-9]+)?)$");
    private static final Pattern LATEST = Pattern.compile("最新记录为(-?[0-9]+(?:\\.[0-9]+)?)$");
    private static final Pattern PERCENT = Pattern.compile("(?:变化|最高，为)(-?[0-9]+(?:\\.[0-9]+)?)%");
    private static final Pattern CONTRIBUTION =
            Pattern.compile("^(.+)贡献度最高，为(-?[0-9]+(?:\\.[0-9]+)?)%$");
    private static final Pattern TEMPORAL = Pattern
            .compile("^(.+?)(环比|同比)变化(-?[0-9]+(?:\\.[0-9]+)?)%（(\\d{4}-\\d{2})较(\\d{4}-\\d{2})）$");

    void validate(QueryResult result) {
        validate(result, Map.of());
    }

    void validate(QueryResult result, Map<String, String> metricLabels) {
        if (result == null || result.getQueryColumns() == null
                || result.getQueryResults() == null) {
            throw inconsistent("query result structure is incomplete");
        }
        if (result.isDataMasked()
                && (result.getMaskedColumns() == null || result.getMaskedColumns().isEmpty())) {
            throw inconsistent("masked result is missing masked field metadata");
        }
        Set<String> fields = result.getQueryColumns().stream().map(this::fieldName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> maskedFields = normalizedMaskedFields(result);
        validateChart(result, result.getRecommendedChart(), fields, maskedFields,
                "recommended chart");
        if (result.getCandidateCharts() == null) {
            throw inconsistent("candidate charts are missing");
        }
        for (ChartRecommendation candidate : result.getCandidateCharts()) {
            validateChart(result, candidate, fields, maskedFields, "candidate chart");
        }
        validateExplanation(result, metricLabels == null ? Map.of() : metricLabels);
    }

    private void validateChart(QueryResult result, ChartRecommendation chart, Set<String> fields,
            Set<String> maskedFields, String location) {
        if (chart == null || StringUtils.isBlank(chart.getChartType())
                || StringUtils.isBlank(chart.getReason())) {
            throw inconsistent(location + " is incomplete");
        }
        validateConfidence(chart.getConfidence(), location);
        if (chart.getDimensionFields() == null || chart.getMetricFields() == null) {
            throw inconsistent(location + " field lists are missing");
        }
        if (!SUPPORTED_CHART_TYPES.contains(chart.getChartType())) {
            throw inconsistent(location + " type is unsupported: " + chart.getChartType());
        }
        validateChartFieldLists(chart, location);
        Set<String> referenced = new LinkedHashSet<>(chart.getDimensionFields());
        referenced.addAll(chart.getMetricFields());
        referenced.removeAll(fields);
        if (!referenced.isEmpty()) {
            throw inconsistent(location + " references unknown fields: " + referenced);
        }
        List<String> maskedReferences =
                Stream.concat(chart.getDimensionFields().stream(), chart.getMetricFields().stream())
                        .filter(field -> maskedFields.contains(normalize(field))).toList();
        if (!maskedReferences.isEmpty()) {
            throw inconsistent(location + " references masked fields: " + maskedReferences);
        }
        validateChartContract(result, chart, location);
    }

    private void validateChartFieldLists(ChartRecommendation chart, String location) {
        List<String> dimensions = chart.getDimensionFields();
        List<String> metrics = chart.getMetricFields();
        if (Stream.concat(dimensions.stream(), metrics.stream()).anyMatch(StringUtils::isBlank)) {
            throw inconsistent(location + " contains blank fields");
        }
        Set<String> normalizedDimensions =
                dimensions.stream().map(this::normalize).collect(Collectors.toSet());
        Set<String> normalizedMetrics =
                metrics.stream().map(this::normalize).collect(Collectors.toSet());
        if (normalizedDimensions.size() != dimensions.size()
                || normalizedMetrics.size() != metrics.size()) {
            throw inconsistent(location + " contains duplicate fields");
        }
        normalizedDimensions.retainAll(normalizedMetrics);
        if (!normalizedDimensions.isEmpty()) {
            throw inconsistent(location + " uses fields as both dimensions and metrics");
        }
    }

    private void validateChartContract(QueryResult result, ChartRecommendation chart,
            String location) {
        Set<String> numericFields =
                result.getQueryColumns().stream().filter(column -> isNumericColumn(result, column))
                        .map(this::fieldName).collect(Collectors.toSet());
        Set<String> dateFields = result.getQueryColumns().stream().filter(this::isDateColumn)
                .map(this::fieldName).collect(Collectors.toSet());
        List<String> invalidDimensions =
                chart.getDimensionFields().stream().filter(numericFields::contains).toList();
        List<String> invalidMetrics = chart.getMetricFields().stream()
                .filter(field -> !numericFields.contains(field)).toList();
        if (!invalidDimensions.isEmpty() || !invalidMetrics.isEmpty()) {
            throw inconsistent(location + " field roles do not match query column types");
        }
        if (!"TABLE".equals(chart.getChartType()) && (chart.getMetricFields().isEmpty()
                || !hasUsableMetricValue(result, chart.getMetricFields()))) {
            throw inconsistent(location + " has no usable metric values");
        }
        switch (chart.getChartType()) {
            case "KPI_CARD" -> requireChartShape(
                    result.getQueryResults().size() == 1 && chart.getDimensionFields().isEmpty(),
                    location, "KPI card");
            case "LINE" -> requireChartShape(
                    chart.getDimensionFields().size() == 1
                            && dateFields.contains(chart.getDimensionFields().get(0)),
                    location, "line chart");
            case "BAR" -> requireChartShape(chart.getDimensionFields().size() == 1, location,
                    "bar chart");
            case "PIE" -> requireChartShape(
                    result.getQueryResults().size() >= 2 && chart.getDimensionFields().size() == 1
                            && !dateFields.contains(chart.getDimensionFields().get(0))
                            && chart.getMetricFields().size() == 1
                            && !hasNegativeMetricValue(result, chart.getMetricFields().get(0)),
                    location, "pie chart");
            case "COMBO" -> requireChartShape(
                    chart.getDimensionFields().size() == 1 && chart.getMetricFields().size() >= 2,
                    location, "combo chart");
            case "TABLE" -> {
                // Tables may display any non-sensitive subset of the query fields.
            }
            default -> throw inconsistent(location + " type is unsupported");
        }
    }

    private void requireChartShape(boolean valid, String location, String chartType) {
        if (!valid) {
            throw inconsistent(location + " does not satisfy " + chartType + " field contract");
        }
    }

    private boolean hasUsableMetricValue(QueryResult result, List<String> metricFields) {
        return result.getQueryResults().stream().filter(Objects::nonNull)
                .flatMap(row -> metricFields.stream().map(row::get)).map(this::decimalOrNull)
                .anyMatch(Objects::nonNull);
    }

    private boolean hasNegativeMetricValue(QueryResult result, String metricField) {
        return result.getQueryResults().stream().filter(Objects::nonNull)
                .map(row -> decimalOrNull(row.get(metricField))).filter(Objects::nonNull)
                .anyMatch(value -> value.signum() < 0);
    }

    private void validateExplanation(QueryResult result, Map<String, String> metricLabels) {
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
        validateEvidence(result, explanation.getEvidence(), metricLabels);
    }

    private void requireSummaryContains(String summary, List<String> statements, String type) {
        for (String statement : statements) {
            if (StringUtils.isBlank(statement) || !summary.contains(statement)
                    || containsNonFiniteNumber(statement)) {
                throw inconsistent(type + " is blank, non-finite, or absent from summary");
            }
        }
    }

    private void validateEvidence(QueryResult result, List<String> evidence,
            Map<String, String> metricLabels) {
        NumericFacts allFacts = numericFacts(result);
        for (String statement : evidence) {
            NumericFacts facts = factsForEvidence(result, statement, allFacts, metricLabels);
            Matcher range = RANGE.matcher(statement);
            Matcher firstLast = FIRST_LAST.matcher(statement);
            Matcher latest = LATEST.matcher(statement);
            Matcher percent = PERCENT.matcher(statement);
            Matcher contribution = CONTRIBUTION.matcher(statement);
            Matcher temporal = TEMPORAL.matcher(statement);
            if (statement.contains("贡献度最高")) {
                if (!contribution.matches()) {
                    throw inconsistent("unsupported contribution evidence statement: " + statement);
                }
                validateContributionEvidence(result, contribution, metricLabels);
            } else if (statement.contains("环比变化") || statement.contains("同比变化")) {
                if (!temporal.matches()) {
                    throw inconsistent("unsupported temporal evidence statement: " + statement);
                }
                validateTemporalEvidence(result, temporal, metricLabels);
            } else if (range.find()) {
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
                requireValue(percentagesForStatement(facts, statement), decimal(percent.group(1)),
                        "percentage");
            } else {
                throw inconsistent("unsupported evidence statement: " + statement);
            }
        }
    }

    private void validateContributionEvidence(QueryResult result, Matcher matcher,
            Map<String, String> metricLabels) {
        ContributionReference reference =
                resolveContributionReference(result, matcher.group(1), metricLabels);
        List<String> categoryFields = result.getQueryColumns().stream()
                .filter(column -> !isMasked(result, fieldName(column)))
                .filter(column -> !isNumericColumn(result, column) && !isDateColumn(column))
                .map(this::fieldName).toList();
        if (categoryFields.size() != 1) {
            throw inconsistent("contribution category is ambiguous");
        }
        String categoryField = categoryFields.get(0);
        List<CategoryValue> values = new ArrayList<>();
        for (Map<String, Object> row : result.getQueryResults()) {
            if (row == null || row.get(categoryField) == null) {
                throw inconsistent("contribution category is not grounded in query results");
            }
            BigDecimal value = decimalOrNull(row.get(reference.metricField));
            if (value == null || value.signum() < 0) {
                throw inconsistent("contribution metric is not grounded in query results");
            }
            values.add(new CategoryValue(String.valueOf(row.get(categoryField)), value));
        }
        if (values.size() < 2) {
            throw inconsistent("contribution requires at least two categories");
        }
        BigDecimal total =
                values.stream().map(CategoryValue::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            throw inconsistent("contribution total is not positive");
        }
        BigDecimal maximum = values.stream().map(CategoryValue::value)
                .max(Comparator.naturalOrder()).orElseThrow();
        boolean categoryMatches =
                values.stream().anyMatch(value -> value.category.equals(reference.category)
                        && value.value.compareTo(maximum) == 0);
        BigDecimal expected =
                maximum.divide(total, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        if (!categoryMatches || !equal(expected, decimal(matcher.group(2)))) {
            throw inconsistent("contribution evidence is not grounded in query results");
        }
    }

    private ContributionReference resolveContributionReference(QueryResult result, String subject,
            Map<String, String> metricLabels) {
        List<FieldAlias> aliases = metricAliases(result, metricLabels)
                .filter(candidate -> subject.length() > candidate.alias.length()
                        && subject.charAt(subject.length() - candidate.alias.length() - 1) == '的'
                        && subject.regionMatches(true, subject.length() - candidate.alias.length(),
                                candidate.alias, 0, candidate.alias.length()))
                .toList();
        if (aliases.isEmpty()) {
            throw inconsistent("contribution metric is unknown");
        }
        int aliasLength = aliases.stream().mapToInt(candidate -> candidate.alias.length()).max()
                .orElseThrow();
        List<FieldAlias> longestAliases = aliases.stream()
                .filter(candidate -> candidate.alias.length() == aliasLength).toList();
        Set<String> metricFields = longestAliases.stream().map(FieldAlias::field)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (metricFields.size() != 1) {
            throw inconsistent("contribution metric is ambiguous");
        }
        String category = subject.substring(0, subject.length() - aliasLength - 1);
        if (StringUtils.isBlank(category)) {
            throw inconsistent("contribution category is missing");
        }
        return new ContributionReference(category, metricFields.iterator().next());
    }

    private void validateTemporalEvidence(QueryResult result, Matcher matcher,
            Map<String, String> metricLabels) {
        String metric = resolveMetricField(result, matcher.group(1), metricLabels);
        List<String> dateFields = result.getQueryColumns().stream()
                .filter(column -> !isMasked(result, fieldName(column))).filter(this::isDateColumn)
                .map(this::fieldName).toList();
        if (dateFields.size() != 1) {
            throw inconsistent("temporal evidence date field is ambiguous");
        }
        TreeMap<YearMonth, BigDecimal> values = new TreeMap<>();
        for (Map<String, Object> row : result.getQueryResults()) {
            if (row == null) {
                throw inconsistent("temporal evidence row is missing");
            }
            YearMonth month = yearMonth(row.get(dateFields.get(0)));
            BigDecimal value = decimalOrNull(row.get(metric));
            if (month == null || value == null || values.put(month, value) != null) {
                throw inconsistent("temporal evidence is not grounded in unique monthly values");
            }
        }
        YearMonth current = yearMonth(matcher.group(4));
        YearMonth baseline = yearMonth(matcher.group(5));
        if (current == null || baseline == null) {
            throw inconsistent("temporal comparison period is invalid");
        }
        YearMonth expectedBaseline =
                "环比".equals(matcher.group(2)) ? current.minusMonths(1) : current.minusYears(1);
        if (values.isEmpty() || !current.equals(values.lastKey())
                || !baseline.equals(expectedBaseline) || !values.containsKey(baseline)
                || values.get(baseline).signum() == 0) {
            throw inconsistent("temporal comparison period is not grounded in query results");
        }
        BigDecimal expected = percentageChange(values.get(baseline), values.get(current));
        if (!equal(expected, decimal(matcher.group(3)))) {
            throw inconsistent("temporal percentage is not grounded in query results");
        }
    }

    private String resolveMetricField(QueryResult result, String label,
            Map<String, String> metricLabels) {
        Set<String> fields = metricAliases(result, metricLabels)
                .filter(alias -> alias.alias.equalsIgnoreCase(label)).map(FieldAlias::field)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (fields.size() != 1) {
            throw inconsistent("evidence metric is unknown or ambiguous: " + label);
        }
        return fields.iterator().next();
    }

    private Stream<FieldAlias> metricAliases(QueryResult result, Map<String, String> metricLabels) {
        List<QueryColumn> metricColumns = result.getQueryColumns().stream()
                .filter(column -> !isMasked(result, fieldName(column)))
                .filter(column -> isNumericColumn(result, column)).toList();
        Set<String> metricFields =
                metricColumns.stream().map(this::fieldName).collect(Collectors.toSet());
        Stream<FieldAlias> columnAliases = metricColumns.stream()
                .flatMap(column -> Stream
                        .of(column.getBizName(), column.getNameEn(), column.getName())
                        .filter(StringUtils::isNotBlank)
                        .map(alias -> new FieldAlias(fieldName(column), alias)));
        Stream<FieldAlias> businessAliases = metricLabels.entrySet().stream()
                .filter(entry -> metricFields.contains(entry.getKey()))
                .filter(entry -> StringUtils.isNotBlank(entry.getValue()))
                .map(entry -> new FieldAlias(entry.getKey(), entry.getValue()));
        return Stream.concat(columnAliases, businessAliases);
    }

    private boolean isNumericColumn(QueryResult result, QueryColumn column) {
        String field = fieldName(column);
        return SemanticType.NUMBER.name().equalsIgnoreCase(column.getShowType())
                || result.getQueryResults().stream().filter(Objects::nonNull)
                        .map(row -> row.get(field)).anyMatch(Number.class::isInstance);
    }

    private boolean isDateColumn(QueryColumn column) {
        return SemanticType.DATE.name().equalsIgnoreCase(column.getShowType())
                || looksLikeDateField(fieldName(column));
    }

    private List<BigDecimal> percentagesForStatement(NumericFacts facts, String statement) {
        if (statement.contains("贡献度最高")) {
            return facts.contributionPercentages;
        }
        if (statement.contains("环比变化") || statement.contains("同比变化")) {
            return facts.temporalPercentages;
        }
        if (statement.contains("首末记录变化")) {
            return facts.firstLastPercentages;
        }
        return List.of();
    }

    private NumericFacts numericFacts(QueryResult result) {
        List<String> metrics = result.getQueryColumns().stream()
                .filter(column -> !isMasked(result, fieldName(column)))
                .filter(column -> SemanticType.NUMBER.name().equalsIgnoreCase(column.getShowType())
                        || result.getQueryResults().stream().map(row -> row.get(fieldName(column)))
                                .anyMatch(Number.class::isInstance))
                .map(this::fieldName).toList();
        return numericFacts(result, metrics);
    }

    private NumericFacts factsForEvidence(QueryResult result, String statement,
            NumericFacts allFacts, Map<String, String> metricLabels) {
        Stream<FieldAlias> columnAliases = result.getQueryColumns().stream()
                .filter(column -> !isMasked(result, fieldName(column)))
                .filter(column -> SemanticType.NUMBER.name().equalsIgnoreCase(column.getShowType())
                        || result.getQueryResults().stream().map(row -> row.get(fieldName(column)))
                                .anyMatch(Number.class::isInstance))
                .flatMap(column -> Stream
                        .of(column.getBizName(), column.getNameEn(), column.getName())
                        .filter(StringUtils::isNotBlank)
                        .map(alias -> new FieldAlias(fieldName(column), alias)));
        Set<String> metricFields = result.getQueryColumns().stream().map(this::fieldName)
                .filter(StringUtils::isNotBlank).collect(Collectors.toSet());
        Stream<FieldAlias> businessAliases = metricLabels.entrySet().stream()
                .filter(entry -> metricFields.contains(entry.getKey()))
                .filter(entry -> StringUtils.isNotBlank(entry.getValue()))
                .map(entry -> new FieldAlias(entry.getKey(), entry.getValue()));
        String metric = Stream.concat(columnAliases, businessAliases)
                .filter(alias -> startsWithMetricLabel(statement, alias.alias()))
                .max(Comparator.comparingInt(alias -> alias.alias().length()))
                .map(FieldAlias::field).orElse(null);
        return metric == null ? allFacts : numericFacts(result, List.of(metric));
    }

    private boolean startsWithMetricLabel(String statement, String alias) {
        if (!statement.regionMatches(true, 0, alias, 0, alias.length())) {
            return false;
        }
        String suffix = statement.substring(alias.length());
        return Stream.of("范围为", "首条记录为", "最新记录为", "首末记录变化", "存在统计异常候选值", "环比变化", "同比变化")
                .anyMatch(suffix::startsWith);
    }

    private NumericFacts numericFacts(QueryResult result, List<String> metrics) {
        List<BigDecimal> rawValues = new ArrayList<>();
        List<ValuePair> ranges = new ArrayList<>();
        List<ValuePair> firstLast = new ArrayList<>();
        List<BigDecimal> latest = new ArrayList<>();
        List<BigDecimal> firstLastPercentages = new ArrayList<>();
        List<BigDecimal> contributionPercentages = new ArrayList<>();
        List<BigDecimal> temporalPercentages = new ArrayList<>();
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
                firstLastPercentages.add(percentageChange(first, last));
            }
            BigDecimal total = values.stream().filter(value -> value.signum() >= 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (values.stream().allMatch(value -> value.signum() >= 0) && total.signum() > 0) {
                for (BigDecimal value : values) {
                    contributionPercentages.add(value.divide(total, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)));
                }
            }
        }
        appendTemporalPercentages(result, metrics, temporalPercentages);
        return new NumericFacts(rawValues, ranges, firstLast, latest, firstLastPercentages,
                contributionPercentages, temporalPercentages);
    }

    private void appendTemporalPercentages(QueryResult result, List<String> metrics,
            List<BigDecimal> percentages) {
        String dateField = result.getQueryColumns().stream()
                .filter(column -> !isMasked(result, fieldName(column)))
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
                .filter(column -> !isMasked(result, fieldName(column)))
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
        BigDecimal decimal = BusinessNumericUtils.parse(value);
        if (decimal == null) {
            throw inconsistent("evidence contains an invalid number");
        }
        return decimal;
    }

    private BigDecimal decimalOrNull(Object value) {
        return BusinessNumericUtils.parse(value);
    }

    private Set<String> normalizedMaskedFields(QueryResult result) {
        if (result.getMaskedColumns() == null) {
            return Set.of();
        }
        return result.getMaskedColumns().stream().filter(Objects::nonNull).map(this::normalize)
                .collect(Collectors.toSet());
    }

    private boolean isMasked(QueryResult result, String field) {
        return result.getMaskedColumns() != null && result.getMaskedColumns().stream()
                .filter(Objects::nonNull).anyMatch(masked -> masked.equalsIgnoreCase(field));
    }

    private String normalize(String field) {
        return StringUtils.defaultString(field).toLowerCase(Locale.ROOT);
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

    private record FieldAlias(String field, String alias) {}

    private record CategoryValue(String category, BigDecimal value) {}

    private record ContributionReference(String category, String metricField) {}

    private record NumericFacts(List<BigDecimal> rawValues, List<ValuePair> ranges,
            List<ValuePair> firstLast, List<BigDecimal> latest,
            List<BigDecimal> firstLastPercentages, List<BigDecimal> contributionPercentages,
            List<BigDecimal> temporalPercentages) {}
}
