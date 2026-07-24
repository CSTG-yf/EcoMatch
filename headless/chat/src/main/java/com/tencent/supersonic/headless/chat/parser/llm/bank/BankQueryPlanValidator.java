package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Enforces mapper evidence before a BankQueryPlan can reach a compiler or executor. */
public class BankQueryPlanValidator {

    private static final Set<String> ORGANIZATION_DIMENSIONS = Set.of("bank_organization", "机构");
    private static final Set<String> TIME_DIMENSIONS =
            Set.of("bank_data_date", "\u6570\u636e\u65e5\u671f");

    private static final Pattern FORBIDDEN_SQL = Pattern
            .compile("(?i)(;|--|/\\*|\\*/|\\b(select|insert|update|delete|drop|alter|create|merge|"
                    + "truncate|join|union|from|where|with)\\b|[()])");
    private static final Set<String> FILTER_OPERATORS =
            Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "CONTAINS", "COMPARE");
    private static final Set<String> LOGICAL_FILTER_FIELDS =
            Set.of("metric_value", "benchmark", "rank", "rank_from_bottom");

    public ValidationResult validate(BankQueryPlan plan, SemanticIntentHints hints) {
        List<ValidationError> errors = new ArrayList<>();
        if (plan == null) {
            errors.add(error("PLAN_REQUIRED", "BankQueryPlan is required"));
            return new ValidationResult(errors);
        }
        if (hints == null) {
            errors.add(error("HINTS_REQUIRED", "semantic intent hints are required"));
            return new ValidationResult(errors);
        }
        validateVersion(plan, errors);
        validateForbiddenTokens(plan, errors);
        validateIntent(plan, hints, errors);
        validateMetrics(plan, hints, errors);
        validateDimensions(plan, hints, errors);
        validateOrganizations(plan, hints, errors);
        validateTime(plan, hints, errors);
        validateFilters(plan, hints, errors);
        validateCalculation(plan, hints, errors);
        validateOrderingAndLimit(plan, hints, errors);
        validateOutput(plan, hints, errors);
        return new ValidationResult(errors);
    }

    private void validateVersion(BankQueryPlan plan, List<ValidationError> errors) {
        if (!BankQueryPlan.CURRENT_VERSION.equals(plan.getVersion())) {
            errors.add(error("UNSUPPORTED_PLAN_VERSION",
                    "plan version must be " + BankQueryPlan.CURRENT_VERSION));
        }
    }

    private void validateForbiddenTokens(BankQueryPlan plan, List<ValidationError> errors) {
        if (strings(plan).filter(StringUtils::isNotBlank)
                .anyMatch(value -> FORBIDDEN_SQL.matcher(value).find())) {
            errors.add(error("FORBIDDEN_SQL_TOKEN",
                    "plan must not contain SQL syntax or executable fragments"));
        }
    }

    private Stream<String> strings(BankQueryPlan plan) {
        Stream<String> metrics = safe(plan.getMetrics())
                .flatMap(metric -> Stream.of(metric.getBizName(), metric.getAlias()));
        Stream<String> organizations = safe(plan.getOrganizations()).flatMap(
                organization -> Stream.of(organization.getCode(), organization.getBizName()));
        Stream<String> filters = safe(plan.getFilters()).flatMap(filter -> Stream.concat(
                Stream.of(filter.getField(), filter.getOperator(), filter.getValue()),
                safe(filter.getValues())));
        Stream<String> orderBy = safe(plan.getOrderBy()).map(BankQueryPlan.OrderBy::getField);
        Stream<String> output =
                plan.getOutput() == null ? Stream.empty() : safe(plan.getOutput().getColumns());
        return Stream
                .of(metrics, safe(plan.getDimensions()), organizations, filters, orderBy, output)
                .flatMap(stream -> stream);
    }

    private void validateIntent(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        if (plan.getIntent() == null || plan.getIntent() == BankIntentType.UNKNOWN) {
            errors.add(error("INTENT_REQUIRED", "plan intent is required"));
        } else if (hints.getExpectedIntent() != null
                && hints.getExpectedIntent() != BankIntentType.UNKNOWN
                && plan.getIntent() != hints.getExpectedIntent()) {
            errors.add(error("INTENT_MISMATCH",
                    "plan intent conflicts with financial intent evidence"));
        }
    }

    private void validateMetrics(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        Set<String> planMetrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (planMetrics.isEmpty()) {
            errors.add(error("METRIC_REQUIRED", "at least one metric is required"));
        }
        for (String metric : planMetrics) {
            if (!hints.getAllowedMetrics().contains(metric)) {
                errors.add(error("UNKNOWN_METRIC",
                        "metric is not available in the semantic schema: " + metric));
            }
        }
        if (!planMetrics.containsAll(hints.getRequiredMetrics())) {
            errors.add(error("MISSING_REQUIRED_METRIC",
                    "plan omitted a metric recognized from the question"));
        }
    }

    private void validateDimensions(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        Set<String> dimensions = safe(plan.getDimensions()).filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String dimension : dimensions) {
            if (!hints.getAllowedDimensions().contains(dimension)) {
                errors.add(error("UNKNOWN_DIMENSION",
                        "dimension is not available in the semantic schema: " + dimension));
            }
        }
        if (plan.getIntent() == BankIntentType.RANKING && dimensions.isEmpty()) {
            errors.add(
                    error("DIMENSION_REQUIRED", "ranking requires an explicit grouping dimension"));
        }
        if (plan.getIntent() == BankIntentType.RANKING
                && dimensions.stream().noneMatch(ORGANIZATION_DIMENSIONS::contains)) {
            errors.add(error("RANKING_ORGANIZATION_DIMENSION_REQUIRED",
                    "ranking requires the semantic organization dimension"));
        }
        if (plan.getIntent() == BankIntentType.TREND
                && dimensions.stream().noneMatch(TIME_DIMENSIONS::contains)) {
            errors.add(error("TREND_TIME_DIMENSION_REQUIRED",
                    "trend requires the semantic date dimension"));
        }
    }

    private void validateOrganizations(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        Set<String> planOrganizations = safe(plan.getOrganizations())
                .map(BankQueryPlan.Organization::getCode).filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!planOrganizations.containsAll(hints.getRequiredOrganizationCodes())) {
            errors.add(error("MISSING_REQUIRED_ORGANIZATION",
                    "plan omitted an organization recognized from the question"));
        }
        if (!hints.getRequiredOrganizationCodes().containsAll(planOrganizations)) {
            errors.add(error("UNKNOWN_ORGANIZATION",
                    "plan contains an organization outside mapper evidence"));
        }
    }

    private void validateTime(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        BankQueryPlan.TimeRange time = plan.getTime();
        if (time == null || time.getStartDate() == null || time.getEndDate() == null) {
            errors.add(error("TIME_REQUIRED", "absolute start and end dates are required"));
            return;
        }
        if (time.getStartDate().isAfter(time.getEndDate())) {
            errors.add(error("TIME_RANGE_INVALID", "start date must not be after end date"));
        }
        if (time.getGranularity() == null) {
            errors.add(error("TIME_GRANULARITY_REQUIRED",
                    "time granularity must be explicit for deterministic compilation"));
        }
        if (time.getComparison() == null) {
            errors.add(error("TIME_COMPARISON_REQUIRED", "time comparison type must be explicit"));
        }
        if (hints.getExpectedIntent() == BankIntentType.CHANGE
                && time.getComparison() == BankQueryPlan.TimeComparison.NONE) {
            errors.add(error("CHANGE_COMPARISON_REQUIRED",
                    "change queries require an explicit non-NONE baseline comparison"));
        }
        if (!Objects.equals(hints.getRequiredStartDate(), time.getStartDate())
                || !Objects.equals(hints.getRequiredEndDate(), time.getEndDate())) {
            errors.add(
                    error("TIME_RANGE_MISMATCH", "plan must preserve the recognized time range"));
        }
        if (time.getComparison() != null
                && time.getComparison() != BankQueryPlan.TimeComparison.NONE
                && time.getComparison() != BankQueryPlan.TimeComparison.MOM_AND_YOY
                && (time.getBaselineStartDate() == null || time.getBaselineEndDate() == null)) {
            errors.add(error("COMPARISON_BASELINE_REQUIRED",
                    "comparison queries require an absolute baseline range"));
        }
        if (time.getComparison() != null
                && time.getComparison() != BankQueryPlan.TimeComparison.NONE
                && time.getComparison() != BankQueryPlan.TimeComparison.MOM_AND_YOY
                && time.getBaselineStartDate() != null && time.getBaselineEndDate() != null
                && (time.getBaselineStartDate().isAfter(time.getBaselineEndDate())
                        || !time.getBaselineEndDate().isBefore(time.getStartDate()))) {
            errors.add(error("COMPARISON_BASELINE_INVALID",
                    "comparison baseline must be a complete range earlier than the query range"));
        }
    }

    private void validateFilters(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        List<BankQueryPlan.Filter> filters = safe(plan.getFilters()).collect(Collectors.toList());
        Set<String> allowedFields = Stream
                .concat(Stream.concat(hints.getAllowedMetrics().stream(),
                        hints.getAllowedDimensions().stream()), LOGICAL_FILTER_FIELDS.stream())
                .collect(Collectors.toSet());
        for (BankQueryPlan.Filter filter : filters) {
            if (StringUtils.isBlank(filter.getField()) || StringUtils.isBlank(filter.getOperator())
                    || !FILTER_OPERATORS.contains(filter.getOperator())) {
                errors.add(error("INVALID_FILTER",
                        "filter field and supported operator are required"));
            }
            if (StringUtils.isNotBlank(filter.getField())
                    && !allowedFields.contains(filter.getField())) {
                errors.add(error("UNKNOWN_FILTER_FIELD",
                        "filter field must be a semantic identifier or approved logical field"));
            }
            if (StringUtils.isBlank(filter.getValue())
                    && safe(filter.getValues()).findAny().isEmpty()) {
                errors.add(error("FILTER_VALUE_REQUIRED", "filter value is required"));
            }
        }
        for (SemanticIntentHints.RequiredFilter required : hints.getRequiredFilters()) {
            boolean present = filters.stream()
                    .anyMatch(filter -> Objects.equals(required.field(), filter.getField())
                            && Objects.equals(required.operator(), filter.getOperator())
                            && Objects.equals(required.value(), filter.getValue()));
            if (!present) {
                errors.add(error("MISSING_REQUIRED_FILTER",
                        "plan omitted a filter recognized from the question"));
            }
        }
    }

    private void validateCalculation(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        BankQueryPlan.Calculation calculation = plan.getCalculation();
        if (calculation == null || calculation.getType() == null) {
            errors.add(error("CALCULATION_REQUIRED", "calculation type is required"));
            return;
        }
        BankQueryPlan.CalculationType expected = switch (hints.getExpectedIntent()) {
            case CHANGE -> BankQueryPlan.CalculationType.CHANGE;
            case RATIO -> BankQueryPlan.CalculationType.RATIO;
            default -> BankQueryPlan.CalculationType.DIRECT;
        };
        if (calculation.getType() != expected) {
            errors.add(error("CALCULATION_MISMATCH",
                    "calculation type conflicts with financial intent"));
        }
        if (calculation.getType() == BankQueryPlan.CalculationType.RATIO) {
            List<String> metricOrder = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                    .filter(StringUtils::isNotBlank).toList();
            if (metricOrder.size() < 2 || StringUtils.isBlank(calculation.getBaseline())) {
                errors.add(error("RATIO_DENOMINATOR_REQUIRED",
                        "ratio requires an explicit second selected metric as denominator"));
            } else if (!Objects.equals(metricOrder.get(1), calculation.getBaseline())) {
                errors.add(error("RATIO_DENOMINATOR_MISMATCH",
                        "ratio denominator must be the second selected metric"));
            }
        }
    }

    private void validateOrderingAndLimit(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        List<BankQueryPlan.OrderBy> orderBy = safe(plan.getOrderBy()).collect(Collectors.toList());
        if (plan.getIntent() == BankIntentType.RANKING && orderBy.isEmpty()) {
            errors.add(error("RANKING_ORDER_REQUIRED", "ranking requires explicit sort direction"));
        }
        Set<String> fields = Stream
                .concat(hints.getAllowedMetrics().stream(), hints.getAllowedDimensions().stream())
                .collect(Collectors.toSet());
        for (BankQueryPlan.OrderBy order : orderBy) {
            if (StringUtils.isBlank(order.getField()) || order.getDirection() == null
                    || !fields.contains(order.getField())) {
                errors.add(error("INVALID_ORDER_BY",
                        "order field and direction must be semantic identifiers"));
            }
        }
        if (plan.getLimit() != null
                && (plan.getLimit() < 1 || plan.getLimit() > hints.getMaxLimit())) {
            errors.add(error("INVALID_LIMIT", "limit must be within the configured maximum"));
        }
        boolean ranksSelectedOrganization = safe(plan.getOrganizations())
                .map(BankQueryPlan.Organization::getCode).anyMatch(StringUtils::isNotBlank);
        if (plan.getIntent() == BankIntentType.RANKING && plan.getLimit() == null
                && !ranksSelectedOrganization) {
            errors.add(error("RANKING_LIMIT_REQUIRED", "ranking requires a TopN limit"));
        }
        if (hints.getRequiredLimit() != null
                && !Objects.equals(hints.getRequiredLimit(), plan.getLimit())) {
            errors.add(error("LIMIT_MISMATCH", "plan must preserve the recognized TopN limit"));
        }
    }

    private void validateOutput(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        if (plan.getOutput() == null || safe(plan.getOutput().getColumns()).findAny().isEmpty()) {
            errors.add(error("OUTPUT_REQUIRED", "ordered output columns are required"));
            return;
        }
        Set<String> output = safe(plan.getOutput().getColumns()).collect(Collectors.toSet());
        Set<String> validColumns = Stream
                .concat(hints.getAllowedMetrics().stream(), hints.getAllowedDimensions().stream())
                .collect(Collectors.toSet());
        if (!validColumns.containsAll(output)) {
            errors.add(
                    error("UNKNOWN_OUTPUT_COLUMN", "output columns must be semantic identifiers"));
        }
        Set<String> metrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .collect(Collectors.toSet());
        if (!output.containsAll(metrics)) {
            errors.add(error("OUTPUT_MISSING_METRIC", "output must retain every requested metric"));
        }
    }

    private <T> Stream<T> safe(Collection<T> values) {
        return values == null ? Stream.empty() : values.stream().filter(Objects::nonNull);
    }

    private ValidationError error(String code, String message) {
        return new ValidationError(code, message);
    }

    public record ValidationError(String code, String message) {}

    public record ValidationResult(List<ValidationError> errors) {
        public ValidationResult {
            errors = List.copyOf(errors);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public Set<String> codes() {
            return errors.stream().map(ValidationError::code).collect(Collectors.toSet());
        }

        public String summary() {
            return errors.stream().map(error -> error.code() + ": " + error.message())
                    .collect(Collectors.joining("; "));
        }
    }
}
