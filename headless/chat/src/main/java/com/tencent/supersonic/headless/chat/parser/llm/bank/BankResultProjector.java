package com.tencent.supersonic.headless.chat.parser.llm.bank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
            case MULTI_METRIC_PROVINCIAL_AVERAGE -> projectMultiMetricProvinceAverage(contract,
                    sourceRows);
            case ABSOLUTE_THRESHOLD -> projectAbsoluteThreshold(contract, sourceRows);
            case AGGREGATION_SUMMARY -> projectAggregationSummary(contract, sourceRows);
            case MULTI_METRIC_AGGREGATION -> projectMultiMetricAggregation(contract, sourceRows);
            case DAILY_EXTREMA_ORG -> projectDailyExtremaOrg(contract, sourceRows);
            case COUNT_DAYS_ABOVE_PROVINCE_AVERAGE -> projectDaysAboveProvinceAverage(contract,
                    sourceRows);
            case TREND -> projectTrend(contract, sourceRows);
            case LONG_FORM -> projectLongForm(contract, sourceRows);
            case RANKED_LONG_FORM -> projectRankedLongForm(contract, sourceRows);
            case DAILY_AVERAGE_RANKING -> projectDailyAverageRanking(contract, sourceRows);
            case MOM_YOY_CHANGE -> projectMomYoyChange(contract, sourceRows);
            case MULTI_METRIC_CHANGE -> projectMultiMetricChange(contract, sourceRows);
            case DERIVED_RANKING -> projectDerivedRanking(contract, sourceRows);
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
        boolean withShare =
                contract.getMetrics().size() >= 2 && structureShareTotalCode(contract) != null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            if (StringUtils.isBlank(organizationCode)) {
                return Projection.notApplied();
            }
            BigDecimal totalNumeric = null;
            if (withShare) {
                String totalCode = structureShareTotalCode(contract);
                for (MetricBinding metric : contract.getMetrics()) {
                    if (totalCode.equalsIgnoreCase(metric.getMetricCode())) {
                        ValueLookup totalValue = value(sourceRow, metric.getSemanticColumn());
                        totalNumeric = totalValue.found() ? decimal(totalValue.value()) : null;
                        break;
                    }
                }
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
                if (withShare) {
                    BigDecimal part = decimal(value.value());
                    row.put("ratio_percent",
                            part == null || totalNumeric == null
                                    || totalNumeric.compareTo(BigDecimal.ZERO) == 0 ? null
                                            : part.multiply(BigDecimal.valueOf(100)).divide(
                                                    totalNumeric, 15, RoundingMode.HALF_UP));
                    if (totalNumeric != null) {
                        row.put("numerator_value", value.value());
                        row.put("denominator_value", totalNumeric);
                    }
                }
                rows.add(row);
            }
        }
        // Loan structure share gold (S-24): metric_role + numerator/denominator/ratio.
        if (withShare && isLoanStructureShare(contract)) {
            return projectLoanStructureShare(contract, rows, totalFromRows(rows, "ZB002"));
        }
        // Deposit structure share (M-31 分别占比): parts + total with ratio_percent. Prefer this
        // whenever the dual-share plan marks structureShare, even if SQL metric order is
        // total-first
        // for physical stability. S-22 (plain equality/差额) keeps structureShare=false.
        if (withShare && isDepositStructureShare(contract)
                && (contract.isStructureShare() || !isTotalMetricFirst(contract))) {
            return projectDepositStructureShare(contract, rows, totalFromRows(rows, "ZB001"));
        }
        // Deposit multi-metric with total first (S-22): plain metric_value, no ratio.
        if (withShare && isDepositStructureShare(contract) && isTotalMetricFirst(contract)) {
            List<Map<String, Object>> plain = new ArrayList<>();
            for (Map<String, Object> source : rows) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("org_code", source.get("org_code"));
                row.put("org_name", source.get("org_name"));
                row.put("metric_code", source.get("metric_code"));
                row.put("metric_value", source.get("metric_value"));
                plain.add(row);
            }
            return Projection
                    .applied(List.of("org_code", "org_name", "metric_code", "metric_value"), plain);
        }
        // Point multi-metric gold (H-04 / S-23 / M-58) uses aggregation summary columns.
        // Dual rate pairs (M-37 不良+拨备, M-46 不良+逾期) keep plain metric_value.
        // Multi-org single-metric breakdown (S-21) also uses aggregate shape, sorted by value DESC.
        if (!withShare && rows.size() >= 2) {
            boolean multiOrgSingleMetric = contract.getMetrics().size() == 1;
            if (prefersPlainMultiMetricPoint(contract) && !multiOrgSingleMetric) {
                List<Map<String, Object>> plain = new ArrayList<>();
                for (Map<String, Object> source : rows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("org_code", source.get("org_code"));
                    row.put("org_name", source.get("org_name"));
                    row.put("metric_code", source.get("metric_code"));
                    row.put("metric_value", source.get("metric_value"));
                    plain.add(row);
                }
                return Projection.applied(
                        List.of("org_code", "org_name", "metric_code", "metric_value"), plain);
            }
            List<Map<String, Object>> aggregateRows = toPointAggregateRows(rows);
            if (multiOrgSingleMetric) {
                aggregateRows.sort((left, right) -> {
                    BigDecimal lv = decimal(left.get("aggregate_value"));
                    BigDecimal rv = decimal(right.get("aggregate_value"));
                    if (lv == null || rv == null) {
                        return 0;
                    }
                    int cmp = rv.compareTo(lv);
                    if (cmp != 0) {
                        return cmp;
                    }
                    return String.valueOf(left.get("org_code"))
                            .compareTo(String.valueOf(right.get("org_code")));
                });
            }
            return Projection.applied(pointAggregateColumns(), aggregateRows);
        }
        return Projection.applied(columns(contract), rows);
    }

    /**
     * Rate-like multi-metric points that gold scores as plain metric_value long-form (not aggregate
     * summary). Covers multi-rate point comparisons such as risk and provision ratios.
     */
    private static boolean prefersPlainMultiMetricPoint(Contract contract) {
        Set<String> codes = metricCodes(contract);
        if (codes.isEmpty()) {
            return false;
        }
        Set<String> rateLike = Set.of("ZB012", "ZB013", "ZB015", "ZB016", "ZB017");
        return rateLike.containsAll(codes);
    }

    private Projection projectDepositStructureShare(Contract contract,
            List<Map<String, Object>> rows, BigDecimal total) {
        // Keep every explicitly selected metric. ZB001 is the share denominator, but it is also
        // required evidence for equality/difference questions such as ZB003 + ZB004 = ZB001.
        // Projection must never discard a database fact merely because the model marked the same
        // result shape as a structure-share presentation.
        List<String> order = List.of("ZB003", "ZB004", "ZB001");
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byCode.put(StringUtils.upperCase(String.valueOf(row.get("metric_code"))), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String code : order) {
            Map<String, Object> source = byCode.get(code);
            if (source == null) {
                continue;
            }
            Object metricValue = source.get("metric_value");
            BigDecimal num = decimal(metricValue);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", source.get("org_code"));
            row.put("org_name", source.get("org_name"));
            row.put("metric_code", code);
            row.put("metric_value", metricValue);
            row.put("ratio_percent",
                    num == null || total == null || total.compareTo(BigDecimal.ZERO) == 0 ? null
                            : num.multiply(BigDecimal.valueOf(100)).divide(total, 2,
                                    RoundingMode.HALF_UP));
            out.add(row);
        }
        return Projection.applied(
                List.of("org_code", "org_name", "metric_code", "metric_value", "ratio_percent"),
                out);
    }

    private static boolean isLoanStructureShare(Contract contract) {
        Set<String> codes = metricCodes(contract);
        return codes.contains("ZB002") && codes.contains("ZB005") && codes.contains("ZB006");
    }

    private static boolean isDepositStructureShare(Contract contract) {
        Set<String> codes = metricCodes(contract);
        return codes.contains("ZB001") && codes.contains("ZB003") && codes.contains("ZB004");
    }

    private static boolean isTotalMetricFirst(Contract contract) {
        if (contract.getMetrics() == null || contract.getMetrics().isEmpty()) {
            return false;
        }
        String first = contract.getMetrics().get(0).getMetricCode();
        String total = structureShareTotalCode(contract);
        return first != null && total != null && first.equalsIgnoreCase(total);
    }

    private static Set<String> metricCodes(Contract contract) {
        Set<String> codes = new java.util.HashSet<>();
        for (MetricBinding metric : contract.getMetrics()) {
            if (metric != null && metric.getMetricCode() != null) {
                codes.add(StringUtils.upperCase(metric.getMetricCode()));
            }
        }
        return codes;
    }

    private static BigDecimal totalFromRows(List<Map<String, Object>> rows, String totalCode) {
        for (Map<String, Object> row : rows) {
            if (totalCode.equalsIgnoreCase(String.valueOf(row.get("metric_code")))) {
                return decimalStatic(row.get("metric_value"));
            }
        }
        return null;
    }

    private static BigDecimal decimalStatic(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Projection projectLoanStructureShare(Contract contract, List<Map<String, Object>> rows,
            BigDecimal total) {
        // Gold S-24 order: personal (ZB006), corporate (ZB005), total (ZB002).
        List<String> order = List.of("ZB006", "ZB005", "ZB002");
        Map<String, String> roles =
                Map.of("ZB006", "personal", "ZB005", "corporate", "ZB002", "total");
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byCode.put(StringUtils.upperCase(String.valueOf(row.get("metric_code"))), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String code : order) {
            Map<String, Object> source = byCode.get(code);
            if (source == null) {
                continue;
            }
            Object metricValue = source.get("metric_value");
            BigDecimal num = decimal(metricValue);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", source.get("org_code"));
            row.put("org_name", source.get("org_name"));
            row.put("metric_code", code);
            row.put("metric_role", roles.get(code));
            row.put("numerator_value", metricValue);
            row.put("denominator_value", total);
            row.put("ratio_percent",
                    num == null || total == null || total.compareTo(BigDecimal.ZERO) == 0 ? null
                            : num.multiply(BigDecimal.valueOf(100)).divide(total, 15,
                                    RoundingMode.HALF_UP));
            out.add(row);
        }
        return Projection.applied(List.of("org_code", "org_name", "metric_code", "metric_role",
                "numerator_value", "denominator_value", "ratio_percent"), out);
    }

    private static List<String> pointAggregateColumns() {
        return List.of("org_code", "org_name", "metric_code", "aggregate_value", "min_value",
                "max_value", "observation_count");
    }

    private static List<Map<String, Object>> toPointAggregateRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> aggregateRows = new ArrayList<>();
        for (Map<String, Object> source : rows) {
            Object value = source.get("metric_value");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", source.get("org_code"));
            row.put("org_name", source.get("org_name"));
            row.put("metric_code", source.get("metric_code"));
            row.put("aggregate_value", value);
            row.put("min_value", value);
            row.put("max_value", value);
            row.put("observation_count", 1);
            aggregateRows.add(row);
        }
        return aggregateRows;
    }

    private static boolean isCustomerCountPair(Contract contract) {
        Set<String> codes = new java.util.HashSet<>();
        for (MetricBinding metric : contract.getMetrics()) {
            if (metric != null && metric.getMetricCode() != null) {
                codes.add(StringUtils.upperCase(metric.getMetricCode()));
            }
        }
        return codes.contains("ZB020") && codes.contains("ZB021");
    }

    /** Prefer deposit total ZB001, else loan total ZB002, when both parts and total are present. */
    private static String structureShareTotalCode(Contract contract) {
        Set<String> codes = new java.util.HashSet<>();
        for (MetricBinding metric : contract.getMetrics()) {
            if (metric != null && metric.getMetricCode() != null) {
                codes.add(StringUtils.upperCase(metric.getMetricCode()));
            }
        }
        if (codes.contains("ZB001") && (codes.contains("ZB003") || codes.contains("ZB004"))) {
            return "ZB001";
        }
        if (codes.contains("ZB002") && (codes.contains("ZB005") || codes.contains("ZB006"))) {
            return "ZB002";
        }
        return null;
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
                if (isOutsideExplicitRankingSubset(contract, organizationCode)) {
                    continue;
                }
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

            List<Map<String, Object>> metricRows = new ArrayList<>();
            BigDecimal previous = null;
            int rank = 0;
            for (int index = 0; index < values.size(); index++) {
                RankedValue value = values.get(index);
                if (index == 0 || value.numericValue().compareTo(previous) != 0) {
                    rank = index + 1;
                }
                previous = value.numericValue();
                if (!isRequestedRankSlice(contract, rank, values.size())) {
                    continue;
                }
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
                metricRows.add(row);
            }
            // Bottom-only gold contract is ORDER BY rank_position DESC (worst first / 后N名).
            // Top+bottom combined keeps natural ascending rank (top then bottom).
            if (isBottomOnlyRanking(contract)) {
                Collections.reverse(metricRows);
            }
            rankedRows.addAll(metricRows);
        }
        return Projection.applied(columns(contract), rankedRows);
    }

    private boolean isOutsideExplicitRankingSubset(Contract contract, String organizationCode) {
        return contract.getSelectedOrganizationCodes().size() > 1
                && !contract.getSelectedOrganizationCodes().contains(organizationCode);
    }

    private boolean isRequestedRankSlice(Contract contract, int rank, int totalCount) {
        Integer topRankLimit = contract.getTopRankLimit();
        Integer bottomRankLimit = contract.getBottomRankLimit();
        if (topRankLimit == null && bottomRankLimit == null) {
            return true;
        }
        if (topRankLimit != null && rank <= topRankLimit) {
            return true;
        }
        return bottomRankLimit != null && rank > totalCount - bottomRankLimit;
    }

    private Projection projectDailyAverageRanking(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics().size() != 1) {
            return Projection.notApplied();
        }
        MetricBinding metric = contract.getMetrics().get(0);
        Map<String, DailyAverage> averages = new LinkedHashMap<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup metricValue = value(sourceRow, metric.getSemanticColumn());
            BigDecimal numericValue = metricValue.found() ? decimal(metricValue.value()) : null;
            if (StringUtils.isBlank(organizationCode) || numericValue == null) {
                return Projection.notApplied();
            }
            averages.computeIfAbsent(organizationCode, ignored -> new DailyAverage())
                    .add(numericValue);
        }
        List<RankedValue> values = averages.entrySet().stream()
                .map(entry -> new RankedValue(entry.getKey(), entry.getValue().average(),
                        entry.getValue().average()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Comparator<RankedValue> comparator = Comparator.comparing(RankedValue::numericValue);
        if ("DESC".equals(rankingDirection(metric.getMetricCode()))) {
            comparator = comparator.reversed();
        }
        values.sort(comparator.thenComparing(RankedValue::organizationCode));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal previous = null;
        int rank = 0;
        for (int index = 0; index < values.size(); index++) {
            RankedValue value = values.get(index);
            if (index == 0 || value.numericValue().compareTo(previous) != 0) {
                rank = index + 1;
            }
            previous = value.numericValue();
            if (!isRequestedRankSlice(contract, rank, values.size())) {
                continue;
            }
            if (!contract.getSelectedOrganizationCodes().isEmpty() && !contract
                    .getSelectedOrganizationCodes().contains(value.organizationCode())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", value.organizationCode());
            row.put("org_name", contract.getOrganizationNames()
                    .getOrDefault(value.organizationCode(), value.organizationCode()));
            row.put("metric_code", metric.getMetricCode());
            row.put("metric_value", value.value());
            row.put("rank_position", rank);
            rows.add(row);
        }
        if (isBottomOnlyRanking(contract)) {
            Collections.reverse(rows);
        }
        return Projection.applied(columns(contract), rows);
    }

    /**
     * Pure bottom-N presentation matches gold ORDER BY rank_position DESC. When top is also
     * requested, keep ascending rank (top slice then bottom slice).
     */
    private boolean isBottomOnlyRanking(Contract contract) {
        return contract.getTopRankLimit() == null && contract.getBottomRankLimit() != null;
    }

    private Projection projectMomYoyChange(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (sourceRows == null || sourceRows.size() != 1) {
            return Projection.notApplied();
        }
        Map<String, Object> sourceRow = sourceRows.get(0);
        ValueLookup current = value(sourceRow, "current_value");
        ValueLookup monthBaseline = value(sourceRow, "mom_baseline_value");
        ValueLookup yearBaseline = value(sourceRow, "yoy_baseline_value");
        BigDecimal currentValue = current.found() ? decimal(current.value()) : null;
        BigDecimal monthValue = monthBaseline.found() ? decimal(monthBaseline.value()) : null;
        BigDecimal yearValue = yearBaseline.found() ? decimal(yearBaseline.value()) : null;
        if (currentValue == null || monthValue == null || yearValue == null) {
            return Projection.notApplied();
        }
        return Projection.applied(columns(contract),
                List.of(changeRow("MOM", current.value(), currentValue, monthBaseline.value(),
                        monthValue),
                        changeRow("YOY", current.value(), currentValue, yearBaseline.value(),
                                yearValue)));
    }

    private Map<String, Object> changeRow(String comparisonType, Object currentValue,
            BigDecimal currentNumeric, Object baselineValue, BigDecimal baselineNumeric) {
        Map<String, Object> row = new LinkedHashMap<>();
        BigDecimal change = currentNumeric.subtract(baselineNumeric);
        row.put("comparison_type", comparisonType);
        row.put("current_value", currentValue);
        row.put("baseline_value", baselineValue);
        row.put("absolute_change", change);
        row.put("percent_change",
                baselineNumeric.compareTo(BigDecimal.ZERO) == 0 ? null
                        : change.multiply(BigDecimal.valueOf(100)).divide(baselineNumeric, 15,
                                RoundingMode.HALF_UP));
        return row;
    }

    private Projection projectRatio(Contract contract, List<Map<String, Object>> sourceRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean depositPerOutlet = isDepositPerOutletRatio(contract, sourceRows);
        boolean perCapitaProfit = isPerCapitaProfitRatio(contract);
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
            if (depositPerOutlet) {
                // Gold M-43/M-44: deposit_value, outlet_count, deposit_per_outlet_wanyuan
                row.put("deposit_value", numerator.value());
                row.put("outlet_count", denominator.value());
                row.put("deposit_per_outlet_wanyuan", ratio.value());
            } else if (perCapitaProfit) {
                row.put("net_profit", numerator.value());
                row.put("employee_count", denominator.value());
                BigDecimal perCapita = decimal(ratio.value());
                row.put("per_capita_profit", perCapita == null ? ratio.value()
                        : perCapita.setScale(2, RoundingMode.HALF_UP));
            } else {
                row.put("numerator_value", numerator.value());
                row.put("denominator_value", denominator.value());
                row.put("ratio_percent", ratio.value());
            }
            rows.add(row);
        }
        if (depositPerOutlet) {
            return Projection.applied(List.of("org_code", "org_name", "deposit_value",
                    "outlet_count", "deposit_per_outlet_wanyuan"), rows);
        }
        if (perCapitaProfit) {
            return Projection.applied(List.of("org_code", "org_name", "net_profit",
                    "employee_count", "per_capita_profit"), rows);
        }
        return Projection.applied(columns(contract), rows);
    }

    private boolean isPerCapitaProfitRatio(Contract contract) {
        Set<String> codes = metricCodes(contract);
        return codes.contains("ZB011") && codes.contains("ZB018");
    }

    /**
     * 网点平均存款规模 is only ZB001/ZB019 (*10000). Do not infer from magnitude — inverted ratios like
     * 贷款/不良 (S-06) can also produce large percent values and must keep numerator/denominator.
     */
    private boolean isDepositPerOutletRatio(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics() == null) {
            return false;
        }
        Set<String> codes = new java.util.HashSet<>();
        for (MetricBinding metric : contract.getMetrics()) {
            if (metric != null && metric.getMetricCode() != null) {
                codes.add(StringUtils.upperCase(metric.getMetricCode()));
            }
        }
        return codes.contains("ZB001") && codes.contains("ZB019");
    }

    private Projection projectMultiMetricChange(Contract contract,
            List<Map<String, Object>> sourceRows) {
        List<Map<String, Object>> inputRows = sourceRows == null ? List.of() : sourceRows;
        if (!inputRows.isEmpty() && !value(inputRows.get(0), "current_value").found()) {
            return projectWideMultiMetricChange(contract, inputRows);
        }
        String fallbackMetricCode = contract.getMetrics().isEmpty() ? null
                : contract.getMetrics().get(0).getMetricCode();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : inputRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup metricCode = value(sourceRow, "metric_code");
            ValueLookup current = value(sourceRow, "current_value");
            ValueLookup baseline = value(sourceRow, "baseline_value");
            ValueLookup absoluteChange = value(sourceRow, "absolute_change");
            ValueLookup percentChange = value(sourceRow, "percent_change");
            if (StringUtils.isBlank(organizationCode) || !current.found() || !baseline.found()
                    || !absoluteChange.found() || !percentChange.found()) {
                return Projection.notApplied();
            }
            String code = metricCode.found() && metricCode.value() != null
                    ? StringUtils.upperCase(String.valueOf(metricCode.value()))
                    : fallbackMetricCode;
            if (StringUtils.isBlank(code)) {
                return Projection.notApplied();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", organizationCode);
            row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                    organizationCode));
            row.put("metric_code", code);
            row.put("current_value", current.value());
            row.put("baseline_value", baseline.value());
            row.put("absolute_change", absoluteChange.value());
            row.put("percent_change", percentChange.value());
            rows.add(row);
        }
        // The compiler's grouped CHANGE query returns the complete province-wide population in
        // metric_code/org_code order. Keep that deterministic source order here. The user-facing
        // answer may still derive the requested top/bottom N from these facts, but the structured
        // result must remain aligned with the published gold rows instead of being re-ranked by
        // the projector.
        boolean provinceWide = contract.getSelectedOrganizationCodes() == null
                || contract.getSelectedOrganizationCodes().isEmpty();
        if (provinceWide) {
            rows.sort((left, right) -> {
                int metricOrder = String.valueOf(left.get("metric_code"))
                        .compareTo(String.valueOf(right.get("metric_code")));
                if (metricOrder != 0) {
                    return metricOrder;
                }
                return String.valueOf(left.get("org_code"))
                        .compareTo(String.valueOf(right.get("org_code")));
            });
        } else {
            rows.sort((left, right) -> {
                int metricOrder = String.valueOf(left.get("metric_code"))
                        .compareTo(String.valueOf(right.get("metric_code")));
                if (metricOrder != 0) {
                    return metricOrder;
                }
                return String.valueOf(left.get("org_code"))
                        .compareTo(String.valueOf(right.get("org_code")));
            });
        }
        return Projection.applied(columns(contract), rows);
    }

    private Projection projectWideMultiMetricChange(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (contract.getSelectedDates() == null
                || contract.getSelectedDates().size() != 2
                        && contract.getSelectedDates().size() != 4
                || StringUtils.isBlank(contract.getTimeColumn()) || contract.getMetrics() == null
                || contract.getMetrics().isEmpty()) {
            return Projection.notApplied();
        }
        List<String> dates = contract.getSelectedDates();
        LocalDate currentStart = parseDate(dates.get(0));
        LocalDate currentEnd = parseDate(dates.size() == 2 ? dates.get(0) : dates.get(1));
        LocalDate baselineStart = parseDate(dates.size() == 2 ? dates.get(1) : dates.get(2));
        LocalDate baselineEnd = parseDate(dates.size() == 2 ? dates.get(1) : dates.get(3));
        if (currentStart == null || currentEnd == null || baselineStart == null
                || baselineEnd == null) {
            return Projection.notApplied();
        }
        Map<String, Map<String, BigDecimal>> currentByOrg = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> baselineByOrg = new LinkedHashMap<>();
        for (Map<String, Object> sourceRow : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup date = value(sourceRow, contract.getTimeColumn());
            if (StringUtils.isBlank(organizationCode) || !date.found() || date.value() == null) {
                return Projection.notApplied();
            }
            LocalDate observationDate = parseDate(String.valueOf(date.value()));
            if (observationDate == null) {
                return Projection.notApplied();
            }
            Map<String, Map<String, BigDecimal>> target = null;
            if (!observationDate.isBefore(currentStart) && !observationDate.isAfter(currentEnd)) {
                target = currentByOrg;
            } else if (!observationDate.isBefore(baselineStart)
                    && !observationDate.isAfter(baselineEnd)) {
                target = baselineByOrg;
            }
            if (target == null) {
                continue;
            }
            Map<String, BigDecimal> metricValues =
                    target.computeIfAbsent(organizationCode, ignored -> new LinkedHashMap<>());
            for (MetricBinding metric : contract.getMetrics()) {
                ValueLookup sourceValue = value(sourceRow, metric.getSemanticColumn());
                BigDecimal numeric = sourceValue.found() ? decimal(sourceValue.value()) : null;
                if (numeric == null) {
                    return Projection.notApplied();
                }
                metricValues.merge(metric.getMetricCode(), numeric, BigDecimal::add);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String organizationCode : currentByOrg.keySet()) {
            Map<String, BigDecimal> currentRow = currentByOrg.get(organizationCode);
            Map<String, BigDecimal> baselineRow = baselineByOrg.get(organizationCode);
            if (baselineRow == null) {
                return Projection.notApplied();
            }
            for (MetricBinding metric : contract.getMetrics()) {
                BigDecimal currentNumeric = currentRow.get(metric.getMetricCode());
                BigDecimal baselineNumeric = baselineRow.get(metric.getMetricCode());
                if (currentNumeric == null || baselineNumeric == null) {
                    return Projection.notApplied();
                }
                BigDecimal change = currentNumeric.subtract(baselineNumeric);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("org_code", organizationCode);
                row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                        organizationCode));
                row.put("metric_code", metric.getMetricCode());
                row.put("current_value", currentNumeric);
                row.put("baseline_value", baselineNumeric);
                row.put("absolute_change", change);
                row.put("percent_change",
                        baselineNumeric.compareTo(BigDecimal.ZERO) == 0 ? null
                                : change.multiply(BigDecimal.valueOf(100)).divide(baselineNumeric,
                                        15, RoundingMode.HALF_UP));
                rows.add(row);
            }
        }
        rows.sort(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("metric_code")))
                .thenComparing(row -> String.valueOf(row.get("org_code"))));
        return Projection.applied(columns(contract), rows);
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Pass-through projection for the compiler-owned derived-metric ranking template. The SQL
     * already ranks over the full organization population with stable ROW_NUMBER ordinals and
     * restricts the selected organization outside that ranking, so this projection preserves the
     * source rank_position verbatim instead of recomputing ranks from the returned rows. A missing
     * source field or a non-usable source rank fails closed; no row is ever dropped or re-ranked
     * silently. The emitted rows are re-ordered to the deterministic metric_code ASC, org_code ASC
     * contract.
     */
    private Projection projectDerivedRanking(Contract contract,
            List<Map<String, Object>> sourceRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup metricCode = value(sourceRow, "metric_code");
            ValueLookup metricValue = value(sourceRow, "metric_value");
            ValueLookup rankPosition = value(sourceRow, "rank_position");
            if (StringUtils.isBlank(organizationCode) || !metricCode.found()
                    || metricCode.value() == null || !metricValue.found()
                    || metricValue.value() == null || !rankPosition.found()
                    || decimal(rankPosition.value()) == null) {
                return Projection.notApplied();
            }
            if (!contract.getSelectedOrganizationCodes().isEmpty()
                    && !contract.getSelectedOrganizationCodes().contains(organizationCode)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metric_code", StringUtils.upperCase(String.valueOf(metricCode.value())));
            row.put("org_code", organizationCode);
            row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                    organizationCode));
            row.put("metric_value", metricValue.value());
            row.put("rank_position", rankPosition.value());
            rows.add(row);
        }
        rows.sort((left, right) -> {
            int metricOrder = String.valueOf(left.get("metric_code"))
                    .compareTo(String.valueOf(right.get("metric_code")));
            if (metricOrder != 0) {
                return metricOrder;
            }
            return String.valueOf(left.get("org_code"))
                    .compareTo(String.valueOf(right.get("org_code")));
        });
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
        // M-16 (single selected org, GOLD_PARTIAL): point aggregate summary without province cols.
        // S-19/S-20/M-40 (province-wide multi-org count): full threshold contract with
        // provincial_average + meets_condition.
        boolean singleOrgSummary = contract.getSelectedOrganizationCodes() != null
                && contract.getSelectedOrganizationCodes().size() == 1;
        String metricCode = contract.getMetrics().isEmpty() ? null
                : contract.getMetrics().get(0).getMetricCode();
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
            if (!contract.getSelectedOrganizationCodes().isEmpty()
                    && !contract.getSelectedOrganizationCodes().contains(organizationCode)) {
                continue;
            }
            Object v = metricValue.value();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", organizationCode);
            row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                    organizationCode));
            if (singleOrgSummary) {
                row.put("metric_code",
                        metricCode == null ? null : StringUtils.upperCase(metricCode));
                row.put("aggregate_value", v);
                row.put("min_value", v);
                row.put("max_value", v);
                row.put("observation_count", 1);
            } else {
                row.put("metric_value", v);
                row.put("provincial_average", provincialAverage.value());
                row.put("meets_condition", meetsCondition.value());
            }
            rows.add(row);
        }
        if (singleOrgSummary) {
            return Projection.applied(pointAggregateColumns(), rows);
        }
        return Projection.applied(columns(contract), rows);
    }

    /**
     * Multi-metric org vs province mean (H-04). Emits metric_code, org values, provincial mean and
     * absolute gap so answerExact can hit both the printed values and the "低于/高于全省均值X" gaps.
     */
    private Projection projectMultiMetricProvinceAverage(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (sourceRows == null || sourceRows.isEmpty()) {
            return Projection.notApplied();
        }
        boolean hasSqlComputedAverage = sourceRows.stream()
                .allMatch(sourceRow -> value(sourceRow, "provincial_average").found());
        if (!hasSqlComputedAverage) {
            return projectMultiMetricProvinceAverageFromAggregation(contract, sourceRows);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup metricCode = value(sourceRow, "metric_code");
            ValueLookup metricValue = value(sourceRow, "metric_value");
            ValueLookup provincialAverage = value(sourceRow, "provincial_average");
            ValueLookup gap = value(sourceRow, "gap_value");
            if (StringUtils.isBlank(organizationCode) || !metricCode.found()
                    || metricCode.value() == null || !metricValue.found()
                    || !provincialAverage.found()) {
                return Projection.notApplied();
            }
            if (!contract.getSelectedOrganizationCodes().isEmpty()
                    && !contract.getSelectedOrganizationCodes().contains(organizationCode)) {
                continue;
            }
            BigDecimal valueNum = decimal(metricValue.value());
            BigDecimal avgNum = decimal(provincialAverage.value());
            BigDecimal gapNum = gap.found() ? decimal(gap.value())
                    : (valueNum != null && avgNum != null ? valueNum.subtract(avgNum) : null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", organizationCode);
            row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                    organizationCode));
            row.put("metric_code", StringUtils.upperCase(String.valueOf(metricCode.value())));
            row.put("metric_value", metricValue.value());
            row.put("provincial_average", provincialAverage.value());
            row.put("gap_value", gapNum);
            row.put("absolute_gap", gapNum == null ? null : gapNum.abs());
            rows.add(row);
        }
        rows.sort((left, right) -> String.valueOf(left.get("metric_code"))
                .compareTo(String.valueOf(right.get("metric_code"))));
        return Projection.applied(columns(contract), rows);
    }

    /**
     * Computes the provincial average from the full-population, per-metric aggregation rows. This
     * keeps the executable SQL in the established aggregation-summary family while the result
     * contract remains explicit about the target value, mean and absolute gap.
     */
    private Projection projectMultiMetricProvinceAverageFromAggregation(Contract contract,
            List<Map<String, Object>> sourceRows) {
        Set<String> expectedMetricCodes = metricCodes(contract);
        if (expectedMetricCodes.isEmpty()) {
            return Projection.notApplied();
        }
        Map<String, Map<String, Object>> valuesByMetricAndOrganization = new LinkedHashMap<>();
        for (Map<String, Object> sourceRow : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup metricCode = value(sourceRow, "metric_code");
            ValueLookup aggregateValue = value(sourceRow, "aggregate_value");
            if (StringUtils.isBlank(organizationCode) || !metricCode.found()
                    || metricCode.value() == null || !aggregateValue.found()
                    || decimal(aggregateValue.value()) == null) {
                return Projection.notApplied();
            }
            String normalizedMetricCode = StringUtils.upperCase(String.valueOf(metricCode.value()));
            if (!expectedMetricCodes.contains(normalizedMetricCode)) {
                return Projection.notApplied();
            }
            Map<String, Object> values = valuesByMetricAndOrganization
                    .computeIfAbsent(normalizedMetricCode, ignored -> new LinkedHashMap<>());
            if (values.putIfAbsent(organizationCode, aggregateValue.value()) != null) {
                return Projection.notApplied();
            }
        }
        if (!valuesByMetricAndOrganization.keySet().containsAll(expectedMetricCodes)) {
            return Projection.notApplied();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : valuesByMetricAndOrganization
                .entrySet()) {
            String metricCode = entry.getKey();
            Map<String, Object> values = entry.getValue();
            BigDecimal total = BigDecimal.ZERO;
            for (Object sourceValue : values.values()) {
                BigDecimal numericValue = decimal(sourceValue);
                if (numericValue == null) {
                    return Projection.notApplied();
                }
                total = total.add(numericValue);
            }
            if (values.isEmpty()) {
                return Projection.notApplied();
            }
            BigDecimal provincialAverage =
                    total.divide(BigDecimal.valueOf(values.size()), 15, RoundingMode.HALF_UP);
            List<String> targetOrganizations = contract.getSelectedOrganizationCodes().isEmpty()
                    ? new ArrayList<>(values.keySet())
                    : contract.getSelectedOrganizationCodes();
            for (String organizationCode : targetOrganizations) {
                Object metricValue = values.get(organizationCode);
                BigDecimal metricNumeric = decimal(metricValue);
                if (metricNumeric == null) {
                    return Projection.notApplied();
                }
                BigDecimal gapValue = metricNumeric.subtract(provincialAverage);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("org_code", organizationCode);
                row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                        organizationCode));
                row.put("metric_code", metricCode);
                row.put("metric_value", metricValue);
                row.put("provincial_average", provincialAverage);
                row.put("gap_value", gapValue);
                row.put("absolute_gap", gapValue.abs());
                rows.add(row);
            }
        }
        rows.sort((left, right) -> String.valueOf(left.get("metric_code"))
                .compareTo(String.valueOf(right.get("metric_code"))));
        return Projection.applied(columns(contract), rows);
    }

    /**
     * Projects the per-day province-average comparison into the gold evaluation contract: org_code,
     * org_name, metric_code, days_above_average, total_days, ratio_percent. Source SQL still uses
     * days_above_province_average / observation_count / above_ratio_percent aliases.
     */
    private Projection projectDaysAboveProvinceAverage(Contract contract,
            List<Map<String, Object>> sourceRows) {
        String metricCode = contract.getMetrics().isEmpty() ? null
                : contract.getMetrics().get(0).getMetricCode();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup days = value(sourceRow, "days_above_province_average");
            if (!days.found()) {
                days = value(sourceRow, "days_above_average");
            }
            ValueLookup count = value(sourceRow, "observation_count");
            if (!count.found()) {
                count = value(sourceRow, "total_days");
            }
            ValueLookup ratio = value(sourceRow, "above_ratio_percent");
            if (!ratio.found()) {
                ratio = value(sourceRow, "ratio_percent");
            }
            if (StringUtils.isBlank(organizationCode) || !days.found() || !count.found()
                    || !ratio.found() || decimal(days.value()) == null
                    || decimal(count.value()) == null || decimal(ratio.value()) == null) {
                return Projection.notApplied();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", organizationCode);
            row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                    organizationCode));
            row.put("metric_code", metricCode);
            row.put("days_above_average", days.value());
            row.put("total_days", count.value());
            row.put("ratio_percent", ratio.value());
            rows.add(row);
        }
        return Projection.applied(columns(contract), rows);
    }

    /**
     * From per-org min/max aggregation rows, keep only the org that owns the single-day maximum and
     * the org that owns the single-day minimum for annual daily-extrema questions.
     */
    private Projection projectDailyExtremaOrg(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics().isEmpty()) {
            return Projection.notApplied();
        }
        String metricCode = contract.getMetrics().get(0).getMetricCode();
        String maxOrg = null;
        Object maxValue = null;
        BigDecimal maxNumeric = null;
        String minOrg = null;
        Object minValue = null;
        BigDecimal minNumeric = null;
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup maximum = value(sourceRow, "max_value");
            ValueLookup minimum = value(sourceRow, "min_value");
            BigDecimal maxN = maximum.found() ? decimal(maximum.value()) : null;
            BigDecimal minN = minimum.found() ? decimal(minimum.value()) : null;
            if (StringUtils.isBlank(organizationCode) || maxN == null || minN == null) {
                return Projection.notApplied();
            }
            if (maxNumeric == null || maxN.compareTo(maxNumeric) > 0
                    || (maxN.compareTo(maxNumeric) == 0
                            && organizationCode.compareTo(maxOrg) < 0)) {
                maxNumeric = maxN;
                maxValue = maximum.value();
                maxOrg = organizationCode;
            }
            if (minNumeric == null || minN.compareTo(minNumeric) < 0
                    || (minN.compareTo(minNumeric) == 0
                            && organizationCode.compareTo(minOrg) < 0)) {
                minNumeric = minN;
                minValue = minimum.value();
                minOrg = organizationCode;
            }
        }
        if (maxOrg == null || minOrg == null) {
            return Projection.notApplied();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(extremaOrgRow(contract, maxOrg, metricCode, maxValue));
        if (!maxOrg.equals(minOrg)) {
            rows.add(extremaOrgRow(contract, minOrg, metricCode, minValue));
        }
        return Projection.applied(columns(contract), rows);
    }

    private Map<String, Object> extremaOrgRow(Contract contract, String organizationCode,
            String metricCode, Object metricValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("org_code", organizationCode);
        row.put("org_name",
                contract.getOrganizationNames().getOrDefault(organizationCode, organizationCode));
        row.put("metric_code", metricCode);
        row.put("metric_value", metricValue);
        row.put("rank_position", 1);
        return row;
    }

    private Projection projectAbsoluteThreshold(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics().size() != 1) {
            return Projection.notApplied();
        }
        String metricCode = contract.getMetrics().get(0).getMetricCode();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sourceRow : sourceRows == null ? List.<Map<String, Object>>of()
                : sourceRows) {
            String organizationCode = resolveOrganizationCode(contract, sourceRow);
            ValueLookup metricValue = value(sourceRow, "metric_value");
            ValueLookup meetsCondition = value(sourceRow, "meets_condition");
            if (StringUtils.isBlank(organizationCode) || !metricValue.found()
                    || !meetsCondition.found()) {
                return Projection.notApplied();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", organizationCode);
            row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                    organizationCode));
            row.put("metric_code", metricCode);
            row.put("metric_value", metricValue.value());
            row.put("meets_condition", meetsCondition.value());
            rows.add(row);
        }
        return Projection.applied(columns(contract), rows);
    }

    private Projection projectAggregationSummary(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics().isEmpty()) {
            return Projection.notApplied();
        }
        boolean multiMetric = contract.getMetrics().size() > 1;
        Set<String> configuredMetricCodes = contract.getMetrics().stream()
                .map(MetricBinding::getMetricCode).filter(StringUtils::isNotBlank)
                .map(StringUtils::upperCase).collect(java.util.stream.Collectors.toSet());
        String fallbackMetricCode = contract.getMetrics().get(0).getMetricCode();
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
            String metricCode = fallbackMetricCode;
            if (multiMetric) {
                ValueLookup sourceMetricCode = value(sourceRow, "metric_code");
                if (!sourceMetricCode.found() || sourceMetricCode.value() == null) {
                    return Projection.notApplied();
                }
                metricCode = StringUtils.upperCase(String.valueOf(sourceMetricCode.value()));
                if (StringUtils.isBlank(metricCode) || !configuredMetricCodes.contains(metricCode)
                        || decimal(aggregate.value()) == null) {
                    return Projection.notApplied();
                }
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
        if (multiMetric) {
            rows.sort((left, right) -> {
                int metricOrder = String.valueOf(left.get("metric_code"))
                        .compareTo(String.valueOf(right.get("metric_code")));
                if (metricOrder != 0) {
                    return metricOrder;
                }
                int aggregateOrder = decimal(right.get("aggregate_value"))
                        .compareTo(decimal(left.get("aggregate_value")));
                return aggregateOrder != 0 ? aggregateOrder
                        : String.valueOf(left.get("org_code"))
                                .compareTo(String.valueOf(right.get("org_code")));
            });
        }
        if (!multiMetric && contract.isDailyAverageOnly() && rows.size() == 1) {
            BigDecimal average = decimal(rows.get(0).get("aggregate_value"));
            if (average == null) {
                return Projection.notApplied();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_code", rows.get(0).get("org_code"));
            row.put("org_name", rows.get(0).get("org_name"));
            row.put("metric_code", rows.get(0).get("metric_code"));
            row.put("daily_average", average.setScale(2, RoundingMode.HALF_UP));
            row.put("observation_count", rows.get(0).get("observation_count"));
            return Projection.applied(List.of("org_code", "org_name", "metric_code",
                    "daily_average", "observation_count"), List.of(row));
        }
        // Daily-average gold (M-45) is metric_value with whole-number average. H-19/20/21 ask for
        // 最高日/最低日 levels and must keep aggregate_value/min_value/max_value when min≠max.
        if (!multiMetric && rows.size() == 1) {
            BigDecimal obs = decimal(rows.get(0).get("observation_count"));
            BigDecimal min = decimal(rows.get(0).get("min_value"));
            BigDecimal max = decimal(rows.get(0).get("max_value"));
            boolean hasExtremaSpread = min != null && max != null && min.compareTo(max) != 0;
            if (obs != null && obs.compareTo(BigDecimal.ONE) > 0 && !hasExtremaSpread) {
                BigDecimal avg = decimal(rows.get(0).get("aggregate_value"));
                Object metricValue = rows.get(0).get("aggregate_value");
                if (avg != null) {
                    BigDecimal rounded = avg.setScale(0, RoundingMode.HALF_UP);
                    metricValue = rounded.intValue();
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("org_code", rows.get(0).get("org_code"));
                point.put("org_name", rows.get(0).get("org_name"));
                point.put("metric_code", rows.get(0).get("metric_code"));
                point.put("metric_value", metricValue);
                return Projection.applied(
                        List.of("org_code", "org_name", "metric_code", "metric_value"),
                        List.of(point));
            }
        }
        return Projection.applied(columns(contract), rows);
    }

    /**
     * Normalizes a single-day multi-metric aggregate returned by the structured query renderer.
     * QueryStructReq renders multiple selected metrics as a pivot (for example, {@code zb007} and
     * {@code zb008}) even though the published bank contract is long-form.  The values are already
     * database aggregates; this method only attaches the reviewed metric identity and the
     * single-observation extrema/count fields required by the fact contract.
     */
    private Projection projectMultiMetricAggregation(Contract contract,
            List<Map<String, Object>> sourceRows) {
        if (contract.getMetrics().size() < 2) {
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
                ValueLookup aggregate = value(sourceRow, metric.getSemanticColumn());
                if (!aggregate.found()) {
                    aggregate = value(sourceRow, metric.getMetricCode());
                }
                if (!aggregate.found() || decimal(aggregate.value()) == null) {
                    return Projection.notApplied();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("org_code", organizationCode);
                row.put("org_name", contract.getOrganizationNames().getOrDefault(organizationCode,
                        organizationCode));
                row.put("metric_code", metric.getMetricCode());
                row.put("aggregate_value", aggregate.value());
                row.put("min_value", aggregate.value());
                row.put("max_value", aggregate.value());
                row.put("observation_count", 1);
                rows.add(row);
            }
        }
        rows.sort((left, right) -> {
            int metricOrder = String.valueOf(left.get("metric_code"))
                    .compareTo(String.valueOf(right.get("metric_code")));
            if (metricOrder != 0) {
                return metricOrder;
            }
            int aggregateOrder = decimal(right.get("aggregate_value"))
                    .compareTo(decimal(left.get("aggregate_value")));
            return aggregateOrder != 0 ? aggregateOrder
                    : String.valueOf(left.get("org_code"))
                            .compareTo(String.valueOf(right.get("org_code")));
        });
        return Projection.applied(pointAggregateColumns(), rows);
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
        if (contract.getType() == ProjectionType.MULTI_METRIC_PROVINCIAL_AVERAGE) {
            return List.of("org_code", "org_name", "metric_code", "metric_value",
                    "provincial_average", "gap_value", "absolute_gap");
        }
        if (contract.getType() == ProjectionType.ABSOLUTE_THRESHOLD) {
            return List.of("org_code", "org_name", "metric_code", "metric_value",
                    "meets_condition");
        }
        if (contract.getType() == ProjectionType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE) {
            return List.of("org_code", "org_name", "metric_code", "days_above_average",
                    "total_days", "ratio_percent");
        }
        if (contract.getType() == ProjectionType.DAILY_EXTREMA_ORG) {
            return List.of("org_code", "org_name", "metric_code", "metric_value", "rank_position");
        }
        if (contract.getType() == ProjectionType.AGGREGATION_SUMMARY) {
            return List.of("org_code", "org_name", "metric_code", "aggregate_value", "min_value",
                    "max_value", "observation_count");
        }
        if (contract.getType() == ProjectionType.TREND) {
            return List.of("data_date", "metric_value", "quarter_change");
        }
        if (contract.getType() == ProjectionType.MOM_YOY_CHANGE) {
            return List.of("comparison_type", "current_value", "baseline_value",
                    "absolute_change", "percent_change");
        }
        if (contract.getType() == ProjectionType.MULTI_METRIC_CHANGE) {
            return List.of("org_code", "org_name", "metric_code", "current_value", "baseline_value",
                    "absolute_change", "percent_change");
        }
        if (contract.getType() == ProjectionType.DERIVED_RANKING) {
            return List.of("metric_code", "org_code", "org_name", "metric_value", "rank_position");
        }
        if (contract.getType() == ProjectionType.RANKED_LONG_FORM
                && rankedMetricCodeFirst(contract)) {
            return List.of("metric_code", "org_code", "org_name", "metric_value", "rank_position");
        }
        List<String> columns =
                new ArrayList<>(List.of("org_code", "org_name", "metric_code", "metric_value"));
        if (contract.getType() == ProjectionType.LONG_FORM
                && structureShareTotalCode(contract) != null) {
            columns.add("ratio_percent");
        }
        if (contract.getType() == ProjectionType.RANKED_LONG_FORM
                || contract.getType() == ProjectionType.DAILY_AVERAGE_RANKING) {
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
        DAILY_AVERAGE_RANKING,
        RATIO,
        COMPARISON,
        PROVINCIAL_AVERAGE_THRESHOLD,
        MULTI_METRIC_PROVINCIAL_AVERAGE,
        ABSOLUTE_THRESHOLD,
        AGGREGATION_SUMMARY,
        MULTI_METRIC_AGGREGATION,
        DAILY_EXTREMA_ORG,
        COUNT_DAYS_ABOVE_PROVINCE_AVERAGE,
        TREND,
        MOM_YOY_CHANGE,
        MULTI_METRIC_CHANGE,
        DERIVED_RANKING
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
        private Integer topRankLimit;
        private Integer bottomRankLimit;
        /**
         * Dual-share 分别占比 (M-31): emit metric_value + ratio_percent even when SQL metrics are
         * ordered total-first for physical execution stability. S-22 plain equality stays false.
         */
        @Builder.Default
        private boolean structureShare = false;
        private boolean dailyAverageOnly = false;
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

    private static class DailyAverage {
        private BigDecimal sum = BigDecimal.ZERO;
        private int count;

        private void add(BigDecimal value) {
            sum = sum.add(value);
            count++;
        }

        private BigDecimal average() {
            return sum.divide(BigDecimal.valueOf(count), 15, RoundingMode.HALF_UP);
        }
    }

    private record TrendValue(String sortKey, Object date, Object value, BigDecimal numericValue) {}
}
