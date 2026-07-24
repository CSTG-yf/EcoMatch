package com.tencent.supersonic.headless.chat.parser.llm.bank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts the pivoted semantic response for supported bank plans into the stable, long-form
 * response contract used by bank clients and offline evaluation. The projector never changes the
 * executed query or creates values; it only selects, names and orders values already returned by
 * the semantic execution layer.
 */
public class BankResultProjector {

    public static final String CONTRACT_PROPERTY = "bank.nl2sql.resultContract";
    private static final Set<String> LOWER_VALUE_IS_BETTER_METRICS =
            Set.of("ZB012", "ZB013", "ZB017");

    public Projection project(Contract contract, List<Map<String, Object>> sourceRows) {
        if (contract == null || contract.getType() == null) {
            return Projection.notApplied();
        }
        return switch (contract.getType()) {
            case RATIO -> projectRatio(contract, sourceRows);
            case COMPARISON -> projectComparison(contract, sourceRows);
            case PROVINCIAL_AVERAGE_THRESHOLD -> projectProvinceAverageThreshold(contract,
                    sourceRows);
            case AGGREGATION_SUMMARY -> projectAggregationSummary(contract, sourceRows);
            case TREND -> projectTrend(contract, sourceRows);
            case LONG_FORM -> projectLongForm(contract, sourceRows);
            case RANKED_LONG_FORM -> projectRankedLongForm(contract, sourceRows);
        };
    }

    static String rankingDirection(String metricCode) {
        return LOWER_VALUE_IS_BETTER_METRICS.contains(StringUtils.upperCase(metricCode)) ? "ASC"
                : "DESC";
    }

    private Projection projectTrend(Contract contract, List<Map<String, Object>> sourceRows) {
        if (StringUtils.isBlank(contract.getTimeColumn()) || contract.getMetrics().size() != 1) {
            return Projection.notApplied();
        }
        String metricColumn = contract.getMetrics().get(0).getSemanticColumn();
        List<TrendValue> values = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            ValueLookup date = value(sourceRow, contract.getTimeColumn());
            ValueLookup metric = value(sourceRow, metricColumn);
            BigDecimal numericValue = metric.found() ? decimal(metric.value()) : null;
            if (!date.found() || date.value() == null || numericValue == null) {
                return Projection.notApplied();
            }
            String dateValue = String.valueOf(date.value());
            if (!contract.getSelectedDates().isEmpty()
                    && !contract.getSelectedDates().contains(dateValue)) {
                continue;
            }
            values.add(new TrendValue(dateValue, date.value(), metric.value(), numericValue));
        }
        values.sort(java.util.Comparator.comparing(TrendValue::sortKey));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal previous = null;
        for (TrendValue value : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("data_date", value.date());
            row.put("metric_value", value.value());
            row.put("quarter_change",
                    previous == null ? null : value.numericValue().subtract(previous));
            rows.add(row);
            previous = value.numericValue();
        }
        return Projection.applied(columns(contract), rows);
    }

    private Projection projectLongForm(Contract contract, List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics().isEmpty()) {
            return Projection.notApplied();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            if (StringUtils.isBlank(organizationCode)) {
                return Projection.notApplied();
            }
            for (MetricBinding metric : contract.getMetrics()) {
                ValueLookup value = value(sourceRow, metric.getSemanticColumn());
                if (!value.found()) {
                    return Projection.notApplied();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("org_code", organizationCode);
                row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                        organizationCode));
                row.put("metric_code", metric.getMetricCode());
                row.put("metric_value", value.value());
                rows.add(row);
            }
        }
        return Projection.applied(columns(contract), rows);
    }

    private Projection projectRankedLongForm(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics().isEmpty()) {
            return Projection.notApplied();
        }
        List<Map<String, Object>> rankedRows = new ArrayList<>();
        List<Map<String, Object>> inputRows = sourceRows == null ? List.of() : sourceRows;
        for (MetricBinding metric : contract.getMetrics()) {
            List<RankedValue> values = new ArrayList<>();
            for (Map<String, Object> sourceRow : inputRows) {
                String organizationCode = resolveOrganizationCode(contract, sourceRow);
                ValueLookup metricValue = value(sourceRow, metric.getSemanticColumn());
                BigDecimal numericValue = metricValue.found() ? decimal(metricValue.value()) : null;
                if (StringUtils.isBlank(organizationCode) || numericValue == null) {
                    return Projection.notApplied();
                }
                values.add(new RankedValue(organizationCode, metricValue.value(), numericValue));
            }
            Comparator<RankedValue> comparator = Comparator.comparing(RankedValue::numericValue);
            if ("DESC".equals(rankingDirection(metric.getMetricCode()))) {
                comparator = comparator.reversed();
            }
            values.sort(comparator.thenComparing(RankedValue::organizationCode));

            BigDecimal previous = null;
            int rank = 0;
            for (int index = 0; index < values.size(); index++) {
                RankedValue value = values.get(index);
                if (index == 0 || value.numericValue().compareTo(previous) != 0) {
                    rank = index + 1;
                }
                previous = value.numericValue();
                if (!contract.getSelectedOrganizationCodes().isEmpty() && !contract
                        .getSelectedOrganizationCodes().contains(value.organizationCode())) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                if (rankedMetricCodeFirst(contract)) {
                    row.put("metric_code", metric.getMetricCode());
                }
                row.put("org_code", value.organizationCode());
                row.put("org_name", contract.getOrganizationNames()
                        .getOrDefault(value.organizationCode(), value.organizationCode()));
                if (!rankedMetricCodeFirst(contract)) {
                    row.put("metric_code", metric.getMetricCode());
                }
                row.put("metric_value", value.value());
                row.put("rank_position", rank);
                rankedRows.add(row);
            }
        }
        return Projection.applied(columns(contract), rankedRows);
    }

    private Projection projectRatio(Contract contract, List<Map<String, Object>> sourceRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup numerator = value(sourceRow, "numerator_value");
            ValueLookup denominator = value(sourceRow, "denominator_value");
            ValueLookup ratio = value(sourceRow, "ratio_percent");
            if (StringUtils.isBlank(organizationCode) || !numerator.found() || !denominator.found()
                    || !ratio.found()) {
                return Projection.notApplied();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", organizationCode);
            row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                    organizationCode));
            row.put("numerator_value", numerator.value());
            row.put("denominator_value", denominator.value());
            row.put("ratio_percent", ratio.value());
            rows.add(row);
        }
        return Projection.applied(columns(contract), rows);
    }

    private Projection projectComparison(Contract contract, List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics().size() != 1) {
            return Projection.notApplied();
        }
        String metricColumn = contract.getMetrics().get(0).getSemanticColumn();
        List<ComparisonValue> values = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup value = value(sourceRow, metricColumn);
            BigDecimal numericValue = value.found() ? decimal(value.value()) : null;
            if (StringUtils.isBlank(organizationCode) || numericValue == null) {
                return Projection.notApplied();
            }
            values.add(new ComparisonValue(organizationCode, value.value(), numericValue));
        }
        if (values.size() < 2) {
            return Projection.notApplied();
        }
        BigDecimal maximum = values.stream().map(ComparisonValue::numericValue)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal minimum = values.stream().map(ComparisonValue::numericValue)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal valueDifference = maximum.subtract(minimum).abs();
        values.sort((left, right) -> right.numericValue().compareTo(left.numericValue()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ComparisonValue value : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", value.organizationCode());
            row.put("org_name", contract.getOrganizationNames()
                    .getOrDefault(value.organizationCode(), value.organizationCode()));
            row.put("metric_value", value.value());
            row.put("value_difference", valueDifference);
            rows.add(row);
        }
        return Projection.applied(columns(contract), rows);
    }

    private Projection projectProvinceAverageThreshold(Contract contract,
            List<Map<String, Object>> sourceRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup metricValue = value(sourceRow, "metric_value");
            ValueLookup provincialAverage = value(sourceRow, "provincial_average");
            ValueLookup meetsCondition = value(sourceRow, "meets_condition");
            if (StringUtils.isBlank(organizationCode) || !metricValue.found()
                    || !provincialAverage.found() || !meetsCondition.found()) {
                return Projection.notApplied();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", organizationCode);
            row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                    organizationCode));
            row.put("metric_value", metricValue.value());
            row.put("provincial_average", provincialAverage.value());
            row.put("meets_condition", meetsCondition.value());
            rows.add(row);
        }
        return Projection.applied(columns(contract), rows);
    }

    private Projection projectAggregationSummary(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics().size() != 1) {
            return Projection.notApplied();
        }
        String metricCode = contract.getMetrics().get(0).getMetricCode();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup aggregate = value(sourceRow, "aggregate_value");
            ValueLookup minimum = value(sourceRow, "min_value");
            ValueLookup maximum = value(sourceRow, "max_value");
            ValueLookup count = value(sourceRow, "observation_count");
            if (StringUtils.isBlank(organizationCode) || !aggregate.found() || !minimum.found()
                    || !maximum.found() || !count.found()) {
                return Projection.notApplied();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", organizationCode);
            row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                    organizationCode));
            row.put("metric_code", metricCode);
            row.put("aggregate_value", aggregate.value());
            row.put("min_value", minimum.value());
            row.put("max_value", maximum.value());
            row.put("observation_count", count.value());
            rows.add(row);
        }
        return Projection.applied(columns(contract), rows);
    }

    private String resolveOrganizationCode(Contract contract, Map<String, Object> sourceRow) {
        ValueLookup value = value(sourceRow, contract.getOrganizationColumn());
        if (value.found() && value.value() != null) {
            String candidate = String.valueOf(value.value());
            if (contract.getOrganizationNames().containsKey(candidate)) {
                return candidate;
            }
            return contract.getOrganizationNames().entrySet().stream()
                    .filter(entry -> Objects.equals(entry.getValue(), candidate))
                    .map(Map.Entry::getKey).findFirst().orElse(candidate);
        }
        return contract.getSelectedOrganizationCodes().size() == 1
                ? contract.getSelectedOrganizationCodes().get(0)
                : null;
    }

    private ValueLookup value(Map<String, Object> row, String key) {
        if (row == null || StringUtils.isBlank(key)) {
            return ValueLookup.missing();
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (StringUtils.equalsIgnoreCase(entry.getKey(), key)) {
                return ValueLookup.present(entry.getValue());
            }
        }
        return ValueLookup.missing();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> columns(Contract contract) {
        if (contract.getType() == ProjectionType.RATIO) {
            return List.of("org_code", "org_name", "numerator_value", "denominator_value",
                    "ratio_percent");
        }
        if (contract.getType() == ProjectionType.COMPARISON) {
            return List.of("org_code", "org_name", "metric_value", "value_difference");
        }
        if (contract.getType() == ProjectionType.PROVINCIAL_AVERAGE_THRESHOLD) {
            return List.of("org_code", "org_name", "metric_value", "provincial_average",
                    "meets_condition");
        }
        if (contract.getType() == ProjectionType.AGGREGATION_SUMMARY) {
            return List.of("org_code", "org_name", "metric_code", "aggregate_value", "min_value",
                    "max_value", "observation_count");
        }
        if (contract.getType() == ProjectionType.TREND) {
            return List.of("data_date", "metric_value", "quarter_change");
        }
        if (contract.getType() == ProjectionType.RANKED_LONG_FORM
                && rankedMetricCodeFirst(contract)) {
            return List.of("metric_code", "org_code", "org_name", "metric_value", "rank_position");
        }
        List<String> columns =
                new ArrayList<>(List.of("org_code", "org_name", "metric_code", "metric_value"));
        if (contract.getType() == ProjectionType.RANKED_LONG_FORM) {
            columns.add("rank_position");
        }
        return columns;
    }

    private boolean rankedMetricCodeFirst(Contract contract) {
        return !contract.getSelectedOrganizationCodes().isEmpty()
                && contract.getMetrics().size() > 1;
    }

    public enum ProjectionType {
        LONG_FORM,
        RANKED_LONG_FORM,
        RATIO,
        COMPARISON,
        PROVINCIAL_AVERAGE_THRESHOLD,
        AGGREGATION_SUMMARY,
        TREND
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Contract implements Serializable {
        private static final long serialVersionUID = 1L;

        private ProjectionType type;
        private String organizationColumn;
        private String timeColumn;
        @Builder.Default
        private List<String> selectedDates = new ArrayList<>();
        @Builder.Default
        private Map<String, String> organizationNames = new LinkedHashMap<>();
        @Builder.Default
        private List<String> selectedOrganizationCodes = new ArrayList<>();
        @Builder.Default
        private List<MetricBinding> metrics = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricBinding implements Serializable {
        private static final long serialVersionUID = 1L;

        private String semanticColumn;
        private String metricCode;
    }

    @Getter
    public static class Projection {
        private final boolean applied;
        private final List<String> columns;
        private final List<Map<String, Object>> rows;

        private Projection(boolean applied, List<String> columns, List<Map<String, Object>> rows) {
            this.applied = applied;
            this.columns = List.copyOf(columns);
            this.rows = List.copyOf(rows);
        }

        private static Projection applied(List<String> columns, List<Map<String, Object>> rows) {
            return new Projection(true, columns, rows);
        }

        private static Projection notApplied() {
            return new Projection(false, List.of(), List.of());
        }
    }

    private record ValueLookup(boolean found, Object value) {
        private static ValueLookup present(Object value) {
            return new ValueLookup(true, value);
        }

        private static ValueLookup missing() {
            return new ValueLookup(false, null);
        }
    }

    private record ComparisonValue(String organizationCode, Object value,
            BigDecimal numericValue) {}

    private record RankedValue(String organizationCode, Object value, BigDecimal numericValue) {}

    private record TrendValue(String sortKey, Object date, Object value, BigDecimal numericValue) {}
}
