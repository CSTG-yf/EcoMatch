package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Emits only compiler-owned S2SQL templates from canonical semantic identifiers. */
final class BankS2SqlTemplateFactory {

    String compileChange(TemplateContext context) {
        if (context.metrics().size() != 1 || !context.metricFilters().isEmpty()) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "change compilation currently requires one metric and no metric filter");
        }
        String metric = context.metrics().get(0).identifier();
        String groupColumns = String.join(", ", context.dimensions());
        LocalDate currentStartDate = context.plan().getTime()
                .getComparison() == BankQueryPlan.TimeComparison.START_OF_YEAR
                        ? context.plan().getTime().getEndDate()
                        : context.plan().getTime().getStartDate();
        String currentWhere = where(context.dimensionFilters(), context.dateField(),
                currentStartDate, context.plan().getTime().getEndDate());
        String baselineWhere = where(context.dimensionFilters(), context.dateField(),
                context.plan().getTime().getBaselineStartDate(),
                context.plan().getTime().getBaselineEndDate());
        String currentSelect = aggregateSelect(groupColumns, metric, "current_value");
        String baselineSelect = aggregateSelect(groupColumns, metric, "baseline_value");
        String groupBy = groupColumns.isEmpty() ? "" : "\n  GROUP BY " + groupColumns;
        if (groupColumns.isEmpty()) {
            return """
                    WITH bank_current AS (
                      SELECT %s
                      FROM %s
                      WHERE %s
                    ), bank_baseline AS (
                      SELECT %s
                      FROM %s
                      WHERE %s
                    )
                    SELECT current_value, baseline_value,
                           current_value - baseline_value AS absolute_change,
                           CASE WHEN baseline_value = 0 THEN NULL
                                ELSE (current_value - baseline_value) * 100.0 / baseline_value END AS percent_change
                    FROM bank_current CROSS JOIN bank_baseline
                    """
                    .formatted(currentSelect, context.dataSetName(), currentWhere, baselineSelect,
                            context.dataSetName(), baselineWhere)
                    .trim();
        }
        String dimensionSelect = context.dimensions().stream()
                .map(dimension -> "bank_current." + dimension + " AS " + dimension)
                .collect(Collectors.joining(", "));
        String joinConditions = context.dimensions().stream()
                .map(dimension -> "bank_current." + dimension + " = bank_baseline." + dimension)
                .collect(Collectors.joining(" AND "));
        String orderBy =
                context.dimensions().stream().map(dimension -> "bank_current." + dimension + " ASC")
                        .collect(Collectors.joining(", "));
        return """
                WITH bank_current AS (
                  SELECT %s
                  FROM %s
                  WHERE %s
                  %s
                ), bank_baseline AS (
                  SELECT %s
                  FROM %s
                  WHERE %s
                  %s
                )
                SELECT %s, current_value, baseline_value,
                       current_value - baseline_value AS absolute_change,
                       CASE WHEN baseline_value = 0 THEN NULL
                            ELSE (current_value - baseline_value) * 100.0 / baseline_value END AS percent_change
                FROM bank_current INNER JOIN bank_baseline
                  ON %s
                ORDER BY %s
                """
                .formatted(currentSelect, context.dataSetName(), currentWhere, groupBy,
                        baselineSelect, context.dataSetName(), baselineWhere, groupBy,
                        dimensionSelect, joinConditions, orderBy)
                .trim();
    }

    String compileMonthAndYearChange(TemplateContext context) {
        if (context.metrics().size() != 1 || !context.metricFilters().isEmpty()
                || !context.dimensions().isEmpty()) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "month-and-year comparison requires one ungrouped metric and no metric filter");
        }
        String metric = context.metrics().get(0).identifier();
        LocalDate currentDate = context.plan().getTime().getEndDate();
        LocalDate monthBaseline = YearMonth.from(currentDate).minusMonths(1).atEndOfMonth();
        LocalDate yearBaseline = currentDate.minusYears(1);
        String currentWhere =
                where(context.dimensionFilters(), context.dateField(), currentDate, currentDate);
        String monthWhere = where(context.dimensionFilters(), context.dateField(), monthBaseline,
                monthBaseline);
        String yearWhere =
                where(context.dimensionFilters(), context.dateField(), yearBaseline, yearBaseline);
        return """
                WITH bank_current AS (
                  SELECT SUM(%s) AS current_value
                  FROM %s
                  WHERE %s
                ), bank_month_baseline AS (
                  SELECT SUM(%s) AS baseline_value
                  FROM %s
                  WHERE %s
                ), bank_year_baseline AS (
                  SELECT SUM(%s) AS baseline_value
                  FROM %s
                  WHERE %s
                )
                SELECT bank_current.current_value,
                       bank_month_baseline.baseline_value AS mom_baseline_value,
                       bank_year_baseline.baseline_value AS yoy_baseline_value
                FROM bank_current
                CROSS JOIN bank_month_baseline
                CROSS JOIN bank_year_baseline
                """
                .formatted(metric, context.dataSetName(), currentWhere, metric,
                        context.dataSetName(), monthWhere, metric, context.dataSetName(), yearWhere)
                .trim();
    }

    private String aggregateSelect(String groupColumns, String metric, String alias) {
        String aggregate = "SUM(" + metric + ") AS " + alias;
        return groupColumns.isEmpty() ? aggregate : groupColumns + ", " + aggregate;
    }

    String compileRatio(TemplateContext context, String numerator, String denominator) {
        if (!context.metricFilters().isEmpty()) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "ratio compilation currently requires no metric filter");
        }
        String groupColumns = String.join(", ", context.dimensions());
        String where = where(context.dimensionFilters(), context.dateField(),
                context.plan().getTime().getStartDate(), context.plan().getTime().getEndDate());
        String aggregates = "SUM(" + numerator + ") AS numerator_value, SUM(" + denominator
                + ") AS denominator_value";
        String innerSelect = groupColumns.isEmpty() ? aggregates : groupColumns + ", " + aggregates;
        String groupBy = groupColumns.isEmpty() ? "" : "\nGROUP BY " + groupColumns;
        String orderBy = groupColumns.isEmpty() ? ""
                : "\nORDER BY " + context.dimensions().stream().map(dimension -> dimension + " ASC")
                        .collect(Collectors.joining(", "));
        String outerDimensions = groupColumns.isEmpty() ? "" : groupColumns + ", ";
        return """
                WITH bank_ratio AS (
                  SELECT %s
                  FROM %s
                  WHERE %s%s
                )
                SELECT %snumerator_value, denominator_value,
                       CASE WHEN denominator_value = 0 THEN NULL
                            ELSE numerator_value * 100.0 / denominator_value END AS ratio_percent
                FROM bank_ratio%s
                """.formatted(innerSelect, context.dataSetName(), where, groupBy, outerDimensions,
                orderBy).trim();
    }

    /**
     * Filters selected organizations outside the semantic aggregation. The semantic translator
     * renders a dimension as a physical column with its semantic name as an alias, so putting a
     * multi-value filter in the inner WHERE would incorrectly reference that alias before it
     * exists. The CTE makes the alias a real outer column before the filter is applied.
     */
    String compileOrganizationComparison(TemplateContext context, Filter organizationFilter) {
        if (context.metrics().size() != 1 || !context.metricFilters().isEmpty()
                || organizationFilter == null || context.dimensions().isEmpty()) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "organization comparison requires one metric, dimensions, and no metric filter");
        }
        List<Filter> innerFilters = new ArrayList<>(context.dimensionFilters());
        innerFilters.remove(organizationFilter);
        String groupColumns = String.join(", ", context.dimensions());
        String where = where(innerFilters, context.dateField(),
                context.plan().getTime().getStartDate(), context.plan().getTime().getEndDate());
        String orderBy = comparisonOrderBy(context.plan());
        String limit =
                context.plan().getLimit() == null ? "" : "\nLIMIT " + context.plan().getLimit();
        return """
                WITH bank_comparison AS (
                  SELECT %s, SUM(%s) AS metric_value
                  FROM %s
                  WHERE %s
                  GROUP BY %s
                )
                SELECT %s, metric_value
                FROM bank_comparison
                WHERE %s%s%s
                """.formatted(groupColumns, context.metrics().get(0).identifier(),
                context.dataSetName(), where, groupColumns, groupColumns,
                filter(organizationFilter), orderBy, limit).trim();
    }

    String compileProvinceAverageThreshold(TemplateContext context) {
        requireSingleMetricWithoutMetricFilters(context, "province-average threshold");
        Filter organizationFilter = organizationFilter(context);
        String where = where(withoutOrganizationFilter(context), context.dateField(),
                context.plan().getTime().getStartDate(), context.plan().getTime().getEndDate());
        String outerWhere =
                organizationFilter == null ? "" : "\nWHERE " + filter(organizationFilter);
        return """
                WITH bank_values AS (
                  SELECT bank_organization, SUM(%s) AS metric_value
                  FROM %s
                  WHERE %s
                  GROUP BY bank_organization
                ), province_average AS (
                  SELECT AVG(metric_value) AS provincial_average
                  FROM bank_values
                )
                SELECT bank_organization, metric_value, provincial_average,
                       CASE WHEN metric_value %s provincial_average THEN 1 ELSE 0 END AS meets_condition
                FROM bank_values CROSS JOIN province_average%s
                ORDER BY bank_organization ASC
                """
                .formatted(context.metrics().get(0).identifier(), context.dataSetName(), where,
                        provinceComparisonOperator(context), outerWhere)
                .trim();
    }

    String compileProvinceAverageAggregation(TemplateContext context) {
        requireSingleMetricWithoutMetricFilters(context, "province-average aggregation");
        Filter organizationFilter = organizationFilter(context);
        String where = where(withoutOrganizationFilter(context), context.dateField(),
                context.plan().getTime().getStartDate(), context.plan().getTime().getEndDate());
        String outerWhere =
                organizationFilter == null ? "" : "\nWHERE " + filter(organizationFilter);
        String metric = context.metrics().get(0).identifier();
        return """
                WITH bank_daily_values AS (
                  SELECT bank_organization, %s AS aggregation_date,
                         SUM(%s) AS metric_value
                  FROM %s
                  WHERE %s
                  GROUP BY bank_organization, %s
                ), bank_aggregation AS (
                  SELECT bank_organization, AVG(metric_value) AS aggregate_value,
                         MIN(metric_value) AS min_value, MAX(metric_value) AS max_value,
                         COUNT(metric_value) AS observation_count
                  FROM bank_daily_values
                  GROUP BY bank_organization
                )
                SELECT bank_organization, aggregate_value, min_value, max_value, observation_count
                FROM bank_aggregation%s
                ORDER BY aggregate_value DESC, bank_organization ASC
                """.formatted(context.dateField(), metric, context.dataSetName(), where,
                "aggregation_date", outerWhere).trim();
    }

    String compileDailyAverageRanking(TemplateContext context) {
        requireSingleMetricWithoutMetricFilters(context, "daily-average ranking");
        if (!context.dimensions().equals(List.of("bank_organization"))) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "daily-average ranking requires only the organization dimension");
        }
        String metric = context.metrics().get(0).identifier();
        String where = where(context.dimensionFilters(), context.dateField(),
                context.plan().getTime().getStartDate(), context.plan().getTime().getEndDate());
        String limit = "";
        return """
                SELECT bank_organization, %s, SUM(%s) AS %s
                FROM %s
                WHERE %s
                GROUP BY bank_organization, %s
                ORDER BY bank_organization ASC, %s ASC%s
                """.formatted(context.dateField(), metric, metric, context.dataSetName(), where,
                context.dateField(), context.dateField(), limit).trim();
    }

    private void requireSingleMetricWithoutMetricFilters(TemplateContext context,
            String operation) {
        if (context.metrics().size() != 1 || !context.metricFilters().isEmpty()) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    operation + " requires exactly one metric and no metric filter");
        }
    }

    private List<Filter> withoutOrganizationFilter(TemplateContext context) {
        Filter organizationFilter = organizationFilter(context);
        List<Filter> filters = new ArrayList<>(context.dimensionFilters());
        if (organizationFilter != null) {
            filters.remove(organizationFilter);
        }
        return filters;
    }

    private Filter organizationFilter(TemplateContext context) {
        return context.dimensionFilters().stream()
                .filter(filter -> "bank_organization".equals(filter.getBizName())).findFirst()
                .orElse(null);
    }

    private String provinceComparisonOperator(TemplateContext context) {
        return context.plan().getFilters().stream()
                .filter(filter -> "metric_value".equals(filter.getField())
                        && "PROVINCE_AVERAGE".equals(filter.getValue()))
                .map(BankQueryPlan.Filter::getOperator).findFirst()
                .map(operator -> switch (operator) {
                case "GT" -> ">";
                case "GTE" -> ">=";
                case "LT" -> "<";
                case "LTE" -> "<=";
                default -> null;
                }).orElse(">");
    }

    private String comparisonOrderBy(BankQueryPlan plan) {
        if (plan.getOrderBy() == null || plan.getOrderBy().isEmpty()) {
            return "";
        }
        BankQueryPlan.OrderBy order = plan.getOrderBy().get(0);
        return "\nORDER BY metric_value " + order.getDirection().name();
    }

    private String where(List<Filter> filters, String dateField, LocalDate startDate,
            LocalDate endDate) {
        List<String> conditions = filters.stream().map(this::filter).collect(Collectors.toList());
        conditions.add(dateField + " >= '" + startDate + "'");
        conditions.add(dateField + " <= '" + endDate + "'");
        return String.join(" AND ", conditions);
    }

    private String filter(Filter filter) {
        String field = filter.getBizName();
        FilterOperatorEnum operator = filter.getOperator();
        if (FilterOperatorEnum.IN.equals(operator) || FilterOperatorEnum.NOT_IN.equals(operator)) {
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) filter.getValue();
            return field + " " + operator.getValue() + " ("
                    + values.stream().map(this::literal).collect(Collectors.joining(", ")) + ")";
        }
        if (FilterOperatorEnum.LIKE.equals(operator)) {
            return field + " LIKE " + literal(String.valueOf(filter.getValue()));
        }
        return field + " " + operator.getValue() + " " + literal(String.valueOf(filter.getValue()));
    }

    private String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    record ResolvedMetric(String identifier) {}

    record TemplateContext(BankQueryPlan plan, String dataSetName, List<ResolvedMetric> metrics,
            List<String> dimensions, String dateField, List<Filter> dimensionFilters,
            List<Filter> metricFilters) {}
}
