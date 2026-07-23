package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartRecommendation;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.enums.SemanticType;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.core.gateway.QueryPerformanceMonitor;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Produces evidence-backed chart recommendations and deterministic business explanations. */
public class BusinessInsightProcessor implements ExecuteResultProcessor {

    private final BusinessInsightConfig configuredRules;
    private final BusinessInsightConsistencyValidator consistencyValidator =
            new BusinessInsightConsistencyValidator();

    public BusinessInsightProcessor() {
        this(null);
    }

    public BusinessInsightProcessor(BusinessInsightConfig configuredRules) {
        this.configuredRules = configuredRules;
    }

    @Override
    public boolean accept(ExecuteContext executeContext) {
        QueryResult result = executeContext.getResponse();
        return result != null && QueryState.SUCCESS.equals(result.getQueryState())
                && result.getQueryResults() != null && result.getQueryColumns() != null
                && !result.getQueryColumns().isEmpty();
    }

    @Override
    public void process(ExecuteContext executeContext) {
        long explainStart = System.nanoTime();
        try {
            BusinessInsightConfig rules = rules();
            QueryResult result = executeContext.getResponse();
            FieldProfile profile = profile(result);
            enrichMetricDefinitions(executeContext, profile);
            List<ChartRecommendation> charts =
                    recommendCharts(profile, result.getQueryResults().size(), rules);
            result.setRecommendedChart(charts.get(0));
            result.setCandidateCharts(charts.subList(1, charts.size()));

            BusinessExplanation explanation = explain(executeContext, result, profile, rules);
            result.setBusinessExplanation(explanation);
            result.setTextSummary(explanation.getSummary());
            consistencyValidator.validate(result);
        } finally {
            QueryPerformanceMonitor.record(QueryPerformanceMonitor.Stage.EXPLAIN,
                    System.nanoTime() - explainStart);
        }
    }

    private BusinessInsightConfig rules() {
        if (configuredRules != null) {
            return configuredRules;
        }
        try {
            return ContextUtils.getBean(BusinessInsightConfig.class);
        } catch (RuntimeException e) {
            return BusinessInsightConfig.defaults();
        }
    }

    private void enrichMetricDefinitions(ExecuteContext context, FieldProfile profile) {
        if (context.getParseInfo() == null || context.getParseInfo().getMetrics() == null) {
            return;
        }
        for (SchemaElement metric : context.getParseInfo().getMetrics()) {
            String field = resolveMetricField(metric, profile.metrics);
            String label = StringUtils.defaultIfBlank(metric.getName(), field);
            profile.metricLabels.put(field, label);
            if (StringUtils.isBlank(metric.getDescription())) {
                continue;
            }
            String definition = metric.getDescription();
            Object unit = metric.getExtInfo() == null ? null : metric.getExtInfo().get("unit");
            if (unit != null && StringUtils.isNotBlank(String.valueOf(unit))) {
                definition += "（单位：" + unit + "）";
            }
            profile.definitions.putIfAbsent(label, definition);
        }
    }

    private String resolveMetricField(SchemaElement metric, List<String> resultMetrics) {
        if (resultMetrics.contains(metric.getBizName())) {
            return metric.getBizName();
        }
        if (resultMetrics.contains(metric.getName())) {
            return metric.getName();
        }
        return resultMetrics.size() == 1 ? resultMetrics.get(0)
                : StringUtils.defaultIfBlank(metric.getBizName(), metric.getName());
    }

    private FieldProfile profile(QueryResult result) {
        List<String> metrics = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        Map<String, String> definitions = new LinkedHashMap<>();
        Map<String, String> metricLabels = new LinkedHashMap<>();

        for (QueryColumn column : result.getQueryColumns()) {
            String field = fieldName(column);
            if (SemanticType.NUMBER.name().equalsIgnoreCase(column.getShowType())
                    || result.getQueryResults().stream().map(row -> row.get(field))
                            .anyMatch(Number.class::isInstance)) {
                metrics.add(field);
                metricLabels.put(field, field);
                if (StringUtils.isNotBlank(column.getComment())) {
                    definitions.put(field, column.getComment());
                }
            } else if (SemanticType.DATE.name().equalsIgnoreCase(column.getShowType())
                    || looksLikeDateField(field)) {
                dates.add(field);
            } else {
                categories.add(field);
            }
        }
        boolean hasNegativeValue = result.getQueryResults().stream()
                .flatMap(row -> metrics.stream().map(row::get)).map(this::toDecimal)
                .filter(Objects::nonNull).anyMatch(value -> value.signum() < 0);
        return new FieldProfile(metrics, dates, categories, definitions, metricLabels,
                hasNegativeValue);
    }

    private List<ChartRecommendation> recommendCharts(FieldProfile profile, int rowCount,
            BusinessInsightConfig rules) {
        List<ChartRecommendation> charts = new ArrayList<>();
        if (rowCount == 0) {
            charts.add(chart("TABLE", rules.getLowConfidence(), "当前无数据，保留表格结构用于展示空结果",
                    profile.categories, profile.metrics));
        } else if (rowCount == 1 && !profile.metrics.isEmpty()) {
            charts.add(chart("KPI_CARD", 0.98, "单行数值结果适合使用指标卡", List.of(), profile.metrics));
        } else if (profile.categories.size() >= 3) {
            charts.add(
                    chart("TABLE", 0.95, "维度较多，表格更适合准确核对明细", profile.categories, profile.metrics));
        } else if (!profile.dates.isEmpty() && profile.metrics.size() > 1) {
            charts.add(chart("COMBO", 0.96, "时间维度与多个数值指标适合组合展示", List.of(profile.dates.get(0)),
                    profile.metrics));
        } else if (!profile.dates.isEmpty() && !profile.metrics.isEmpty()) {
            charts.add(chart("LINE", 0.96, "时间维度与数值指标适合展示趋势", List.of(profile.dates.get(0)),
                    profile.metrics));
        } else if (isComposition(profile, rowCount, rules)) {
            charts.add(chart("PIE", 0.93, "少量同口径正值分类适合展示构成占比", List.of(profile.categories.get(0)),
                    profile.metrics));
        } else if (!profile.categories.isEmpty() && !profile.metrics.isEmpty()) {
            charts.add(chart("BAR", 0.94, "分类维度与数值指标适合横向比较", List.of(profile.categories.get(0)),
                    profile.metrics));
        } else {
            charts.add(chart("TABLE", 0.90, "当前字段结构适合保留明细表格", profile.categories, profile.metrics));
        }

        Set<String> types = new LinkedHashSet<>();
        types.add(charts.get(0).getChartType());
        if (isComposition(profile, rowCount, rules) && types.add("PIE")) {
            charts.add(chart("PIE", 0.82, "分类数量较少，可用于展示构成占比", List.of(profile.categories.get(0)),
                    profile.metrics));
        }
        if (profile.metrics.size() > 1 && types.add("COMBO")) {
            charts.add(chart("COMBO", 0.80, "多个数值指标可使用组合图进行对比", firstDimension(profile),
                    profile.metrics));
        }
        if (types.add("TABLE")) {
            charts.add(chart("TABLE", 0.75, "表格可用于核对原始查询结果", profile.categories, profile.metrics));
        }
        return charts;
    }

    private boolean isComposition(FieldProfile profile, int rowCount, BusinessInsightConfig rules) {
        if (profile.categories.size() != 1 || profile.metrics.size() != 1 || rowCount < 2
                || rowCount > rules.getPieMaxCategories() || profile.hasNegativeValue) {
            return false;
        }
        String category = profile.categories.get(0).toLowerCase(Locale.ROOT);
        return category.matches(".*(metric|type|category|构成|类型|类别).*");
    }

    private BusinessExplanation explain(ExecuteContext context, QueryResult result,
            FieldProfile profile, BusinessInsightConfig rules) {
        List<String> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean emptyResult = result.getQueryResults().isEmpty();
        boolean smallSample = result.getQueryResults().size() < rules.getSmallSampleThreshold();
        for (String metric : profile.metrics) {
            List<BigDecimal> values = result.getQueryResults().stream().map(row -> row.get(metric))
                    .map(this::toDecimal).filter(Objects::nonNull).collect(Collectors.toList());
            if (values.isEmpty()) {
                continue;
            }
            BigDecimal min = values.stream().min(Comparator.naturalOrder()).orElseThrow();
            BigDecimal max = values.stream().max(Comparator.naturalOrder()).orElseThrow();
            String label = profile.metricLabels.getOrDefault(metric, metric);
            evidence.add(String.format("%s范围为%s至%s", label, format(min), format(max)));
            if (!smallSample && values.size() > 1) {
                BigDecimal first = values.get(0);
                BigDecimal last = values.get(values.size() - 1);
                evidence.add(
                        String.format("%s首条记录为%s，末条记录为%s", label, format(first), format(last)));
                if (first.compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal change =
                            last.subtract(first).divide(first.abs(), 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100));
                    evidence.add(String.format("%s首末记录变化%s%%", label, format(change)));
                }
                if (isComparisonMetric(label) || isComparisonMetric(metric)) {
                    evidence.add(String.format("%s最新记录为%s", label, format(last)));
                }
            }
            if (!smallSample) {
                appendAnomalyEvidence(label, values, evidence, rules);
            }
        }
        appendContributionEvidence(result.getQueryResults(), profile, evidence);
        if (!smallSample) {
            appendTemporalComparisonEvidence(result.getQueryResults(), profile, evidence);
        }

        String timeRange = resolveTimeRange(result.getQueryResults(), profile.dates);
        boolean hasQualityWarning = smallSample || profile.metrics.isEmpty() || evidence.isEmpty();
        warnings.add("结论仅适用于当前查询结果的数据范围，不得外推到未展示机构或时间");
        if (emptyResult) {
            warnings.add("当前查询未返回数据，不输出趋势、异常点、贡献度或因果结论");
        } else if (smallSample) {
            warnings.add(String.format("结果少于%d条，仅描述查询事实，不输出趋势、异常点或因果结论",
                    rules.getSmallSampleThreshold()));
        }
        if (profile.metrics.isEmpty()) {
            warnings.add("未识别到数值指标，不输出增减或贡献度结论");
        } else if (evidence.isEmpty()) {
            warnings.add("数值已脱敏或不可解析，不输出数值结论");
        }
        if (isRiskProfile(profile)) {
            warnings.add("风险指标结论仅供分析，不替代监管报送、授信审批或风险处置");
        }
        String queryText =
                context.getRequest() == null ? null : context.getRequest().getQueryText();
        String summary = buildSummary(queryText, result.getQueryResults().size(), timeRange,
                evidence, warnings, profile.definitions);
        double confidence = evidence.isEmpty() ? rules.getLowConfidence()
                : !hasQualityWarning ? rules.getHighConfidence() : rules.getEvidenceConfidence();
        return BusinessExplanation.builder().summary(summary).confidence(confidence)
                .timeRange(timeRange).evidence(evidence).warnings(warnings)
                .metricDefinitions(profile.definitions).build();
    }

    private void appendAnomalyEvidence(String metric, List<BigDecimal> values,
            List<String> evidence, BusinessInsightConfig rules) {
        if (values.size() < 5) {
            return;
        }
        double average = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(BigDecimal::doubleValue)
                .map(value -> Math.pow(value - average, 2)).average().orElse(0);
        double standardDeviation = Math.sqrt(variance);
        if (standardDeviation == 0) {
            return;
        }
        List<String> anomalies =
                values.stream()
                        .filter(value -> Math.abs(value.doubleValue() - average) > rules
                                .getAnomalyZScore() * standardDeviation)
                        .map(this::format).collect(Collectors.toList());
        if (!anomalies.isEmpty()) {
            evidence.add(String.format("%s存在统计异常候选值：%s", metric, String.join("、", anomalies)));
        }
    }

    private void appendContributionEvidence(List<Map<String, Object>> rows, FieldProfile profile,
            List<String> evidence) {
        if (profile.categories.size() != 1 || profile.metrics.size() != 1 || rows.size() < 2) {
            return;
        }
        String category = profile.categories.get(0);
        String metric = profile.metrics.get(0);
        List<Map.Entry<String, BigDecimal>> values = rows.stream()
                .map(row -> new java.util.AbstractMap.SimpleImmutableEntry<>(
                        String.valueOf(row.get(category)), toDecimal(row.get(metric))))
                .filter(entry -> entry.getValue() != null && entry.getValue().signum() >= 0)
                .collect(Collectors.toList());
        BigDecimal total =
                values.stream().map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (values.size() != rows.size() || total.signum() <= 0) {
            return;
        }
        Map.Entry<String, BigDecimal> top =
                values.stream().max(Map.Entry.comparingByValue()).orElseThrow();
        BigDecimal contribution = top.getValue().divide(total, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        evidence.add(String.format("%s的%s贡献度最高，为%s%%", top.getKey(), metric, format(contribution)));
    }

    private void appendTemporalComparisonEvidence(List<Map<String, Object>> rows,
            FieldProfile profile, List<String> evidence) {
        if (profile.dates.size() != 1 || !profile.categories.isEmpty()
                || profile.metrics.isEmpty()) {
            return;
        }
        String dateField = profile.dates.get(0);
        for (String metric : profile.metrics) {
            TreeMap<YearMonth, BigDecimal> monthlyValues = new TreeMap<>();
            for (Map<String, Object> row : rows) {
                YearMonth month = toYearMonth(row.get(dateField));
                BigDecimal value = toDecimal(row.get(metric));
                if (month == null || value == null || monthlyValues.put(month, value) != null) {
                    monthlyValues.clear();
                    break;
                }
            }
            if (monthlyValues.size() < 2) {
                continue;
            }
            YearMonth latestMonth = monthlyValues.lastKey();
            BigDecimal latest = monthlyValues.get(latestMonth);
            String label = profile.metricLabels.getOrDefault(metric, metric);
            appendComparison(label, "环比", latestMonth, latest, latestMonth.minusMonths(1),
                    monthlyValues, evidence);
            appendComparison(label, "同比", latestMonth, latest, latestMonth.minusYears(1),
                    monthlyValues, evidence);
        }
    }

    private void appendComparison(String label, String comparisonType, YearMonth latestMonth,
            BigDecimal latest, YearMonth baselineMonth, Map<YearMonth, BigDecimal> monthlyValues,
            List<String> evidence) {
        BigDecimal baseline = monthlyValues.get(baselineMonth);
        if (baseline == null || baseline.signum() == 0) {
            return;
        }
        BigDecimal change = latest.subtract(baseline)
                .divide(baseline.abs(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        evidence.add(String.format("%s%s变化%s%%（%s较%s）", label, comparisonType, format(change),
                latestMonth, baselineMonth));
    }

    private YearMonth toYearMonth(Object value) {
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

    private boolean isRiskMetric(String metric) {
        String normalized = metric.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(risk|overdue|nonperform|逾期|不良|风险).*");
    }

    private boolean isComparisonMetric(String metric) {
        String normalized = metric.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(同比|环比|yoy|mom|year.?over.?year|month.?over.?month).*");
    }

    private boolean isRiskProfile(FieldProfile profile) {
        return Stream
                .concat(profile.metricLabels.values().stream(),
                        profile.definitions.entrySet().stream()
                                .map(entry -> entry.getKey() + " " + entry.getValue()))
                .anyMatch(this::isRiskMetric);
    }

    private String buildSummary(String queryText, int rowCount, String timeRange,
            List<String> evidence, List<String> warnings, Map<String, String> definitions) {
        StringBuilder summary = new StringBuilder();
        if (StringUtils.isNotBlank(queryText)) {
            summary.append("问题范围：").append(queryText).append("。");
        }
        summary.append("查询返回").append(rowCount).append("条记录");
        if (StringUtils.isNotBlank(timeRange)) {
            summary.append("，时间范围为").append(timeRange);
        }
        if (!evidence.isEmpty()) {
            summary.append("。").append(String.join("；", evidence));
        }
        if (!definitions.isEmpty()) {
            summary.append("。指标口径：")
                    .append(definitions.entrySet().stream()
                            .map(entry -> entry.getKey() + "：" + entry.getValue())
                            .collect(Collectors.joining("；")));
        }
        if (!warnings.isEmpty()) {
            summary.append("。提示：").append(String.join("；", warnings));
        }
        return summary.append("。").toString();
    }

    private String resolveTimeRange(List<Map<String, Object>> rows, List<String> dateFields) {
        if (dateFields.isEmpty()) {
            return null;
        }
        String field = dateFields.get(0);
        List<String> values = rows.stream().map(row -> row.get(field)).filter(Objects::nonNull)
                .map(String::valueOf).sorted().collect(Collectors.toList());
        if (values.isEmpty()) {
            return null;
        }
        return values.get(0).equals(values.get(values.size() - 1)) ? values.get(0)
                : values.get(0) + "至" + values.get(values.size() - 1);
    }

    private ChartRecommendation chart(String type, double confidence, String reason,
            List<String> dimensions, List<String> metrics) {
        return ChartRecommendation.builder().chartType(type).confidence(confidence).reason(reason)
                .dimensionFields(new ArrayList<>(dimensions)).metricFields(new ArrayList<>(metrics))
                .build();
    }

    private List<String> firstDimension(FieldProfile profile) {
        if (!profile.dates.isEmpty()) {
            return List.of(profile.dates.get(0));
        }
        return profile.categories.isEmpty() ? List.of() : List.of(profile.categories.get(0));
    }

    private String fieldName(QueryColumn column) {
        if (StringUtils.isNotBlank(column.getBizName())) {
            return column.getBizName();
        }
        return StringUtils.defaultIfBlank(column.getNameEn(), column.getName());
    }

    private boolean looksLikeDateField(String field) {
        String normalized = StringUtils.defaultString(field).toLowerCase(Locale.ROOT);
        return normalized.matches(".*(date|time|day|month|year|日期|时间|月份|年度).*");
    }

    private BigDecimal toDecimal(Object value) {
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record FieldProfile(List<String> metrics, List<String> dates, List<String> categories,
            Map<String, String> definitions, Map<String, String> metricLabels,
            boolean hasNegativeValue) {}
}
