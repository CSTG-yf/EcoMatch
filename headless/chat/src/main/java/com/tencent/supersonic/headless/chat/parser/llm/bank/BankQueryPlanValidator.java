package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Enforces mapper evidence before a BankQueryPlan can reach a compiler or executor. */
public class BankQueryPlanValidator {

    private static final Set<String> ORGANIZATION_DIMENSIONS = Set.of("bank_organization");
    private static final Set<String> TIME_DIMENSIONS = Set.of("bank_data_date");

    private static final Pattern FORBIDDEN_SQL = Pattern
            .compile("(?i)(;|--|/\\*|\\*/|\\b(select|insert|update|delete|drop|alter|create|merge|"
                    + "truncate|join|union|from|where|with)\\b|[()])");
    private static final Pattern DERIVED_METRIC_CODE =
            Pattern.compile("DERIVED_([A-Z0-9]+)_DIV_([A-Z0-9]+)");
    private static final Pattern BASE_METRIC_CODE = Pattern.compile("ZB\\d{3}");
    private static final Set<String> FILTER_OPERATORS = BankSemanticRegistry.filterOperators();
    private static final Set<String> LOGICAL_FILTER_FIELDS =
            BankSemanticRegistry.logicalFilterFields();

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
        validateAction(plan, errors);
        validateForbiddenTokens(plan, errors);
        validateIntent(plan, hints, errors);
        validateMetrics(plan, hints, errors);
        validateDimensions(plan, hints, errors);
        validateOrganizations(plan, hints, errors);
        validateTime(plan, hints, errors);
        validateFilters(plan, hints, errors);
        validateCalculation(plan, hints, errors);
        validateDerivedMetrics(plan, hints, errors);
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

    private void validateAction(BankQueryPlan plan, List<ValidationError> errors) {
        if (plan.getAction() != BankQueryPlan.PlanAction.EXECUTE) {
            errors.add(error("PLAN_ACTION_REQUIRED",
                    "execution plans must set action to EXECUTE"));
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
        Stream<String> derivedMetrics = safe(plan.getDerivedMetrics()).flatMap(
                derived -> Stream.of(derived.getMetricCode(), derived.getNumerator(),
                        derived.getDenominator(), derived.getName()));
        Stream<String> organizations = safe(plan.getOrganizations()).flatMap(
                organization -> Stream.of(organization.getCode(), organization.getBizName()));
        Stream<String> filters = safe(plan.getFilters()).flatMap(filter -> Stream.concat(
                Stream.of(filter.getField(), filter.getOperator(), filter.getValue()),
                safe(filter.getValues())));
        Stream<String> orderBy = safe(plan.getOrderBy()).map(BankQueryPlan.OrderBy::getField);
        Stream<String> output =
                plan.getOutput() == null ? Stream.empty() : safe(plan.getOutput().getColumns());
        return Stream
                .of(metrics, derivedMetrics, safe(plan.getDimensions()), organizations, filters,
                        orderBy, output)
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
                    "plan intent must equal the model requirements contract"));
        }
    }

    private static boolean isRatioPlan(BankQueryPlan plan) {
        return plan.getIntent() == BankIntentType.RATIO && plan.getCalculation() != null
                && plan.getCalculation().getType() == BankQueryPlan.CalculationType.RATIO;
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
            if (!BankSemanticRegistry.metricCodes().contains(metric)
                    || !hints.getAllowedMetrics().isEmpty()
                            && !hints.getAllowedMetrics().contains(metric)) {
                errors.add(error("UNKNOWN_METRIC",
                        "metric is not available in the semantic schema: " + metric));
            }
        }
        Set<String> missing = new LinkedHashSet<>(hints.getRequiredMetrics());
        missing.removeAll(planMetrics);
        if (!missing.isEmpty()) {
            errors.add(error("MISSING_REQUIRED_METRIC",
                    "required_metrics_missing: " + String.join(",", missing)));
        }
        if (!hints.getRequiredMetrics().isEmpty()) {
            Set<String> unexpected = new LinkedHashSet<>(planMetrics);
            unexpected.removeAll(hints.getRequiredMetrics());
            if (!unexpected.isEmpty()) {
                errors.add(error("UNEXPECTED_METRIC",
                        "plan contains metrics outside the model requirements contract: "
                                + String.join(",", unexpected)));
            }
        }
    }

    private boolean requiredMetricsSatisfied(Collection<String> planOrHaystack,
            Collection<String> required) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        for (String req : required) {
            if (!metricIdentityPresent(planOrHaystack, req)) {
                return false;
            }
        }
        return true;
    }

    private boolean requiredMetricsIntersect(Collection<String> planMetrics,
            Collection<String> required) {
        if (required == null || required.isEmpty() || planMetrics == null || planMetrics.isEmpty()) {
            return true;
        }
        for (String planMetric : planMetrics) {
            if (metricIdentityPresent(required, planMetric)) {
                return true;
            }
        }
        return false;
    }

    private boolean metricIdentityPresent(Collection<String> haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        for (String item : haystack) {
            if (needle.equals(item)) {
                return true;
            }
        }
        return false;
    }

    private void validateDimensions(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        Set<String> dimensions = safe(plan.getDimensions()).filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String dimension : dimensions) {
            if (!BankSemanticRegistry.dimensions().contains(dimension)
                    || !hints.getAllowedDimensions().isEmpty()
                            && !dimensionAllowed(hints.getAllowedDimensions(), dimension)) {
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
        if (plan.getCalculation() != null
                && plan.getCalculation().getType() == BankQueryPlan.CalculationType.CHANGE
                && dimensions.stream().anyMatch(TIME_DIMENSIONS::contains)) {
            errors.add(error("CHANGE_DATE_DIMENSION_FORBIDDEN",
                    "change comparison dates belong in time and must not group by bank_data_date"));
        }
    }

    private void validateOrganizations(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        Set<String> planOrganizations = safe(plan.getOrganizations())
                .map(BankQueryPlan.Organization::getCode).filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!BankSemanticRegistry.organizationCodes().containsAll(planOrganizations)) {
            errors.add(error("UNKNOWN_ORGANIZATION",
                    "plan contains an organization outside the official bank registry"));
        }
        if (!planOrganizations.containsAll(hints.getRequiredOrganizationCodes())) {
            Set<String> missing = new LinkedHashSet<>(hints.getRequiredOrganizationCodes());
            missing.removeAll(planOrganizations);
            errors.add(error("MISSING_REQUIRED_ORGANIZATION",
                    "required_organizations_missing: " + String.join(",", missing)));
        }
        if (!hints.getRequiredOrganizationCodes().isEmpty()
                && !hints.getRequiredOrganizationCodes().containsAll(planOrganizations)) {
            errors.add(error("UNKNOWN_ORGANIZATION",
                    "plan contains an organization outside the model requirements contract"));
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
        if (!matchesRecognizedTimeRange(time, hints)) {
            errors.add(
                    error("TIME_RANGE_MISMATCH", "plan must preserve the recognized time range"));
        }
        if (hints.getRequiredTimeComparison() != null
                && time.getComparison() != hints.getRequiredTimeComparison()) {
            errors.add(error("TIME_COMPARISON_MISMATCH",
                    "plan must preserve the model requirements time comparison"));
        }
        if ((hints.getRequiredBaselineStartDate() != null
                && !Objects.equals(hints.getRequiredBaselineStartDate(),
                        time.getBaselineStartDate()))
                || (hints.getRequiredBaselineEndDate() != null
                        && !Objects.equals(hints.getRequiredBaselineEndDate(),
                                time.getBaselineEndDate()))) {
            errors.add(error("COMPARISON_BASELINE_MISMATCH",
                    "plan must preserve the model requirements baseline range"));
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
        if (time.getComparison() == BankQueryPlan.TimeComparison.START_OF_YEAR
                && time.getBaselineStartDate() != null && time.getBaselineEndDate() != null) {
            LocalDate priorYearEnd = LocalDate.of(time.getEndDate().getYear() - 1, 12, 31);
            if (!priorYearEnd.equals(time.getBaselineStartDate())
                    || !priorYearEnd.equals(time.getBaselineEndDate())) {
                errors.add(error("START_OF_YEAR_BASELINE_INVALID",
                        "START_OF_YEAR baseline must be the prior calendar year's 12-31, "
                                + "not current-year 01-01"));
            }
        }
    }

    private boolean matchesRecognizedTimeRange(BankQueryPlan.TimeRange time,
            SemanticIntentHints hints) {
        return (hints.getRequiredStartDate() == null && hints.getRequiredEndDate() == null)
                || Objects.equals(hints.getRequiredStartDate(), time.getStartDate())
                        && Objects.equals(hints.getRequiredEndDate(), time.getEndDate());
    }

    private static boolean isFullCalendarYear(BankQueryPlan.TimeRange time) {
        return time != null && time.getStartDate() != null && time.getEndDate() != null
                && time.getStartDate().getMonthValue() == 1 && time.getStartDate().getDayOfMonth() == 1
                && time.getEndDate().getMonthValue() == 12 && time.getEndDate().getDayOfMonth() == 31
                && time.getStartDate().getYear() == time.getEndDate().getYear();
    }

    private void validateFilters(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        List<BankQueryPlan.Filter> filters = safe(plan.getFilters()).collect(Collectors.toList());
        boolean hasProvinceAverageBenchmark = filters.stream()
                .anyMatch(this::isProvinceAverageBenchmark);
        Set<String> allowedFields = Stream
                .concat(Stream.concat(hints.getAllowedMetrics().stream(),
                        hints.getAllowedDimensions().stream()), LOGICAL_FILTER_FIELDS.stream())
                .collect(Collectors.toSet());
        for (BankQueryPlan.Filter filter : filters) {
            if (filter == null) {
                errors.add(error("INVALID_FILTER", "filter must be an object"));
                continue;
            }
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
            boolean provinceAverageBenchmark = isProvinceAverageBenchmark(filter);
            boolean provinceAverageDirection = isProvinceAverageDirection(filter);
            if (("benchmark".equals(filter.getField()) || "COMPARE".equals(filter.getOperator()))
                    && !provinceAverageBenchmark) {
                errors.add(error("PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED",
                        "province average must use exact benchmark/COMPARE/PROVINCE_AVERAGE"));
            }
            if ("PROVINCE_AVERAGE".equals(filter.getValue()) && !provinceAverageBenchmark
                    && !provinceAverageDirection) {
                errors.add(error("PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED",
                        "PROVINCE_AVERAGE may only be a benchmark or metric_value direction"));
            }
            if (provinceAverageDirection && !hasProvinceAverageBenchmark) {
                errors.add(error("PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED",
                        "province-average direction requires the exact benchmark filter"));
            }
            if ((provinceAverageBenchmark || provinceAverageDirection)
                    && safe(filter.getValues()).findAny().isPresent()) {
                errors.add(error("PROVINCE_AVERAGE_BENCHMARK_VALUES_FORBIDDEN",
                        "province-average benchmark values must be empty"));
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

    private boolean isProvinceAverageBenchmark(BankQueryPlan.Filter filter) {
        return filter != null && "benchmark".equals(filter.getField())
                && "COMPARE".equals(filter.getOperator())
                && "PROVINCE_AVERAGE".equals(filter.getValue());
    }

    private boolean isProvinceAverageDirection(BankQueryPlan.Filter filter) {
        return filter != null && "metric_value".equals(filter.getField())
                && ("GT".equals(filter.getOperator()) || "GTE".equals(filter.getOperator())
                        || "LT".equals(filter.getOperator()) || "LTE".equals(filter.getOperator()))
                && "PROVINCE_AVERAGE".equals(filter.getValue());
    }

    private static boolean isMultiMetricPointPlan(BankQueryPlan plan) {
        return plan != null && plan.getIntent() == BankIntentType.POINT_QUERY
                && plan.getCalculation() != null
                && plan.getCalculation().getType() == BankQueryPlan.CalculationType.DIRECT
                && plan.getMetrics() != null && plan.getMetrics().size() >= 2;
    }

    private void validateCalculation(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        BankQueryPlan.Calculation calculation = plan.getCalculation();
        if (calculation == null || calculation.getType() == null) {
            errors.add(error("CALCULATION_REQUIRED", "calculation type is required"));
            return;
        }
        BankQueryPlan.TimeComparison comparison = plan.getTime() == null ? null
                : plan.getTime().getComparison();
        if (comparison != null && comparison != BankQueryPlan.TimeComparison.NONE
                && calculation.getType() != BankQueryPlan.CalculationType.CHANGE) {
            errors.add(error("COMPARISON_CALCULATION_REQUIRED",
                    "a non-NONE time comparison requires calculation.type=CHANGE"));
        }
        if (calculation.getType() == BankQueryPlan.CalculationType.CHANGE
                && comparison == BankQueryPlan.TimeComparison.NONE) {
            errors.add(error("CHANGE_COMPARISON_REQUIRED",
                    "calculation.type=CHANGE requires an explicit non-NONE time comparison"));
        }
        BankQueryPlan.CalculationType expected = switch (hints.getExpectedIntent()) {
            case CHANGE -> BankQueryPlan.CalculationType.CHANGE;
            case RATIO -> BankQueryPlan.CalculationType.RATIO;
            case AGGREGATION -> calculation.getType() == BankQueryPlan.CalculationType
                    .COUNT_DAYS_ABOVE_PROVINCE_AVERAGE
                            ? BankQueryPlan.CalculationType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE
                            : calculation.getType() == BankQueryPlan.CalculationType.DIRECT
                                    ? BankQueryPlan.CalculationType.DIRECT
                                    : BankQueryPlan.CalculationType.DIRECT;
            // Growth "增幅排名" is labeled RANKING but executes as CHANGE.
            case RANKING -> calculation.getType() == BankQueryPlan.CalculationType.CHANGE
                    ? BankQueryPlan.CalculationType.CHANGE
                    : BankQueryPlan.CalculationType.DIRECT;
            default -> BankQueryPlan.CalculationType.DIRECT;
        };
        if (calculation.getType() != expected
                && !(isRatioPlan(plan) && (hints.getExpectedIntent() == BankIntentType.POINT_QUERY
                        || hints.getExpectedIntent() == BankIntentType.UNKNOWN
                        || hints.getExpectedIntent() == BankIntentType.RATIO
                        // M-43 网点平均存款规模 arrives as AGGREGATION / DERIVED.
                        || hints.getExpectedIntent() == BankIntentType.AGGREGATION))
                && !(calculation.getType() == BankQueryPlan.CalculationType.DIRECT
                        && plan.getIntent() == BankIntentType.POINT_QUERY
                        && plan.getMetrics() != null && plan.getMetrics().size() >= 2
                        && (hints.getExpectedIntent() == BankIntentType.RATIO
                                || hints.getExpectedIntent() == BankIntentType.UNKNOWN
                                || hints.getExpectedIntent() == BankIntentType.AGGREGATION))) {
            errors.add(error("CALCULATION_MISMATCH",
                    "calculation type conflicts with financial intent"));
        }
        if (calculation.getType() == BankQueryPlan.CalculationType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE) {
            validateDaysAboveProvinceAverageCount(plan, errors);
        }
        if (calculation.getType() == BankQueryPlan.CalculationType.RATIO) {
            List<String> metricOrder = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                    .filter(StringUtils::isNotBlank).toList();
            if (metricOrder.size() < 2 || StringUtils.isBlank(calculation.getBaseline())) {
                errors.add(error("RATIO_DENOMINATOR_REQUIRED",
                        "ratio requires an explicit second selected metric as denominator"));
            } else if (!metricOrder.get(1).equals(calculation.getBaseline())) {
                errors.add(error("RATIO_DENOMINATOR_MISMATCH",
                        "ratio denominator must be the second selected metric"));
            }
        }
    }

    /**
     * Fail-closed gate for the runtime derived metric contract (e.g. 存贷比 =
     * DERIVED_ZB002_DIV_ZB001). A derived metric is only accepted when the code equals
     * DERIVED_&lt;numerator&gt;_DIV_&lt;denominator&gt;, the operands are distinct legal ZB###
     * base metrics also selected as direct metrics, the plan is RANKING with a DIRECT
     * calculation, and the plan derived metrics match the mapper evidence exactly in content and
     * order. Missing, duplicate, extra, reordered or illegal entries are all rejected.
     */
    private void validateDerivedMetrics(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        List<BankQueryPlan.DerivedMetric> derived =
                safe(plan.getDerivedMetrics()).collect(Collectors.toList());
        List<SemanticIntentHints.DerivedMetricSpec> required = hints.getRequiredDerivedMetrics();
        if (derived.isEmpty()) {
            if (!required.isEmpty() && !ratioPlanCoversRequiredDerived(plan, required)) {
                errors.add(error("DERIVED_METRIC_MISSING",
                        "plan omitted a derived metric recognized from the question"));
            }
            return;
        }
        if (required.isEmpty()) {
            errors.add(error("DERIVED_METRIC_UNEXPECTED",
                    "plan contains a derived metric outside mapper evidence"));
            return;
        }
        if (plan.getIntent() != BankIntentType.RANKING) {
            errors.add(error("DERIVED_METRIC_INTENT_REQUIRED",
                    "derived metrics currently require ranking intent"));
        }
        if (plan.getCalculation() == null
                || plan.getCalculation().getType() != BankQueryPlan.CalculationType.DIRECT) {
            errors.add(error("DERIVED_METRIC_CALCULATION_REQUIRED",
                    "derived metrics currently require a DIRECT calculation"));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (BankQueryPlan.DerivedMetric item : derived) {
            validateDerivedMetricItem(item, seen, errors);
        }
        if (derived.size() != required.size()) {
            errors.add(error("DERIVED_METRIC_MISMATCH",
                    "plan derived metrics must match the recognized derived metric specifications"));
        } else {
            for (int index = 0; index < derived.size(); index++) {
                if (!matches(required.get(index), derived.get(index))) {
                    errors.add(error("DERIVED_METRIC_MISMATCH",
                            "plan derived metrics must match the recognized derived metric "
                                    + "specifications exactly, in order"));
                    break;
                }
            }
        }
        List<String> planMetrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .filter(StringUtils::isNotBlank).collect(Collectors.toList());
        for (BankQueryPlan.DerivedMetric item : derived) {
            if (!containsIgnoreCase(planMetrics, item.getNumerator())) {
                errors.add(error("DERIVED_METRIC_OPERAND_REQUIRED",
                        "derived metric numerator must also be selected as a direct metric: "
                                + item.getNumerator()));
            }
            if (!containsIgnoreCase(planMetrics, item.getDenominator())) {
                errors.add(error("DERIVED_METRIC_OPERAND_REQUIRED",
                        "derived metric denominator must also be selected as a direct metric: "
                                + item.getDenominator()));
            }
        }
    }

    private void validateDerivedMetricItem(BankQueryPlan.DerivedMetric item, Set<String> seen,
            List<ValidationError> errors) {
        String code = item.getMetricCode();
        String numerator = item.getNumerator();
        String denominator = item.getDenominator();
        if (StringUtils.isBlank(code) || StringUtils.isBlank(numerator)
                || StringUtils.isBlank(denominator) || StringUtils.isBlank(item.getName())) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "derived metric requires metricCode, numerator, denominator and name"));
            return;
        }
        if (!seen.add(code)) {
            errors.add(error("DERIVED_METRIC_DUPLICATE",
                    "derived metrics must not repeat a metric code: " + code));
        }
        Matcher matcher = DERIVED_METRIC_CODE.matcher(code);
        if (!matcher.matches()) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "derived metric code must be DERIVED_<numerator>_DIV_<denominator>: " + code));
        } else if (!matcher.group(1).equals(numerator)
                || !matcher.group(2).equals(denominator)) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "derived metric code operands must match the declared numerator and "
                            + "denominator: " + code));
        }
        if (numerator.equals(denominator)) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "derived metric numerator and denominator must differ: " + code));
        }
        if (!BASE_METRIC_CODE.matcher(numerator).matches()) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "derived metric numerator must be a legal ZB### base metric: " + numerator));
        }
        if (!BASE_METRIC_CODE.matcher(denominator).matches()) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "derived metric denominator must be a legal ZB### base metric: " + denominator));
        }
    }

    private boolean matches(SemanticIntentHints.DerivedMetricSpec spec,
            BankQueryPlan.DerivedMetric item) {
        return spec.code().equals(item.getMetricCode())
                && spec.numerator().equals(item.getNumerator())
                && spec.denominator().equals(item.getDenominator())
                && Objects.equals(spec.name(), item.getName());
    }

    /**
     * Fail-closed gate for the explicit per-day province-average comparison contract. The
     * calculation is only legal as an AGGREGATION over exactly one metric and one organization,
     * scoped to a DAY range, grouped on the organization dimension, carrying the PROVINCE_AVERAGE
     * benchmark, without any absolute metric threshold, ordering, or TopN limit. An ordinary
     * COUNT or THRESHOLD plan must never be mistaken for this semantics.
     */
    private void validateDaysAboveProvinceAverageCount(BankQueryPlan plan,
            List<ValidationError> errors) {
        if (plan.getIntent() != BankIntentType.AGGREGATION) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_INTENT_REQUIRED",
                    "days-above-province-average count requires aggregation intent"));
        }
        List<String> planMetrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .filter(StringUtils::isNotBlank).collect(Collectors.toList());
        if (planMetrics.size() != 1) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_SINGLE_METRIC_REQUIRED",
                    "days-above-province-average count requires exactly one metric"));
        }
        List<String> planOrganizations = safe(plan.getOrganizations())
                .map(BankQueryPlan.Organization::getCode).filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (planOrganizations.size() != 1) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_SINGLE_ORGANIZATION_REQUIRED",
                    "days-above-province-average count requires exactly one organization"));
        }
        BankQueryPlan.TimeRange time = plan.getTime();
        if (time == null || time.getGranularity() != BankQueryPlan.TimeGranularity.DAY) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_DAY_GRANULARITY_REQUIRED",
                    "days-above-province-average count requires DAY time granularity"));
        }
        if (time != null && time.getComparison() != null
                && time.getComparison() != BankQueryPlan.TimeComparison.NONE) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_NO_COMPARISON_REQUIRED",
                    "days-above-province-average count requires no baseline comparison"));
        }
        boolean benchmark = safe(plan.getFilters()).anyMatch(filter -> "benchmark".equals(
                filter.getField()) && "COMPARE".equals(filter.getOperator())
                && "PROVINCE_AVERAGE".equals(filter.getValue()));
        if (!benchmark) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_BENCHMARK_REQUIRED",
                    "days-above-province-average count requires the PROVINCE_AVERAGE benchmark"));
        }
        List<String> dimensions = safe(plan.getDimensions()).filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (dimensions.size() != 1 || !ORGANIZATION_DIMENSIONS.contains(dimensions.get(0))) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_ORGANIZATION_DIMENSION_REQUIRED",
                    "days-above-province-average count requires only the organization dimension"));
        }
        boolean absoluteThreshold = safe(plan.getFilters())
                .anyMatch(filter -> "metric_value".equals(filter.getField()));
        if (absoluteThreshold) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_METRIC_FILTER_FORBIDDEN",
                    "days-above-province-average count must not carry an absolute metric threshold"));
        }
        if (plan.getLimit() != null) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_NO_LIMIT_REQUIRED",
                    "days-above-province-average count requires no TopN limit"));
        }
        if (safe(plan.getOrderBy()).findAny().isPresent()) {
            errors.add(error("DAYS_ABOVE_PROVINCE_AVERAGE_NO_ORDER_REQUIRED",
                    "days-above-province-average count requires no ordering"));
        }
    }

    private void validateOrderingAndLimit(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        List<BankQueryPlan.OrderBy> orderBy = safe(plan.getOrderBy()).collect(Collectors.toList());
        if (plan.getIntent() == BankIntentType.RANKING && orderBy.isEmpty()) {
            errors.add(error("RANKING_ORDER_REQUIRED", "ranking requires explicit sort direction"));
        }
        if (plan.getIntent() == BankIntentType.CHANGE && !orderBy.isEmpty()) {
            errors.add(error("CHANGE_RESULT_ORDER_FORBIDDEN",
                    "CHANGE result ordering is compiler-owned; set orderBy to [] and do not use "
                            + "percent_change, current_value, baseline_value, or absolute_change"));
        }
        Set<String> fields = Stream
                .concat(hints.getAllowedMetrics().stream(), hints.getAllowedDimensions().stream())
                .collect(Collectors.toSet());
        if (plan.getIntent() != BankIntentType.CHANGE) {
            for (BankQueryPlan.OrderBy order : orderBy) {
                if (StringUtils.isBlank(order.getField()) || order.getDirection() == null
                        || (!BankSemanticRegistry.metricCodes().contains(order.getField())
                                && !BankSemanticRegistry.dimensions().contains(order.getField()))
                        || (!fields.isEmpty() && !metricAllowed(fields, order.getField())
                                && !dimensionAllowed(fields, order.getField()))) {
                    errors.add(error("INVALID_ORDER_BY",
                            "order field and direction must be semantic identifiers"));
                }
            }
        }
        if (plan.getLimit() != null
                && (plan.getLimit() < 1 || plan.getLimit() > hints.getMaxLimit())) {
            errors.add(error("INVALID_LIMIT", "limit must be within the configured maximum"));
        }
        boolean ranksSelectedOrganization = safe(plan.getOrganizations())
                .map(BankQueryPlan.Organization::getCode).anyMatch(StringUtils::isNotBlank);
        boolean provinceWideChangeTopN = plan.getIntent() == BankIntentType.CHANGE
                && hints.getRequiredLimit() != null && !ranksSelectedOrganization
                && safe(plan.getDimensions()).noneMatch(ORGANIZATION_DIMENSIONS::contains);
        if (provinceWideChangeTopN) {
            errors.add(error("CHANGE_TOPN_ORGANIZATION_DIMENSION_REQUIRED",
                    "province-wide CHANGE TopN requires the bank_organization dimension so the "
                            + "compiler can return per-organization facts"));
        }
        if (plan.getIntent() == BankIntentType.RANKING && plan.getLimit() == null
                && !ranksSelectedOrganization) {
            errors.add(error("RANKING_LIMIT_REQUIRED", "ranking requires a TopN limit"));
        }
        if (hints.getRequiredLimit() != null
                && !Objects.equals(hints.getRequiredLimit(), plan.getLimit())
                && !isCompatibleTopBottomLimit(plan, hints)
                // Province growth CHANGE returns the full org set; TopN is applied by answer text.
                && !(plan.getIntent() == BankIntentType.CHANGE
                        && plan.getTime() != null && plan.getTime()
                                .getComparison() == BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD)) {
            errors.add(error("LIMIT_MISMATCH", "plan must preserve the recognized TopN limit"));
        }
    }

    /**
     * When the question asks for both top-N and bottom-N, the plan limit is top+bottom while the
     * recognizer often only reports the first TopN as requiredLimit. Accept that deterministic
     * expansion instead of forcing a single-sided limit.
     */
    private boolean isCompatibleTopBottomLimit(BankQueryPlan plan, SemanticIntentHints hints) {
        if (plan.getLimit() == null || hints.getRequiredLimit() == null) {
            return false;
        }
        Integer top = rankFilterLimit(plan, "rank");
        Integer bottom = rankFilterLimit(plan, "rank_from_bottom");
        return top != null && bottom != null
                && Objects.equals(plan.getLimit(), top + bottom)
                && (Objects.equals(hints.getRequiredLimit(), top)
                        || Objects.equals(hints.getRequiredLimit(), bottom));
    }

    private Integer rankFilterLimit(BankQueryPlan plan, String field) {
        return safe(plan.getFilters()).filter(filter -> field.equals(filter.getField()))
                .map(BankQueryPlan.Filter::getValue).filter(StringUtils::isNotBlank).findFirst()
                .map(value -> {
                    try {
                        return Integer.valueOf(value);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }).orElse(null);
    }

    private void validateOutput(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        if (plan.getOutput() == null || safe(plan.getOutput().getColumns()).findAny().isEmpty()) {
            errors.add(error("OUTPUT_REQUIRED", "ordered output columns are required"));
            return;
        }
        List<String> outputColumns =
                safe(plan.getOutput().getColumns()).collect(Collectors.toList());
        Set<String> output = new LinkedHashSet<>(outputColumns);
        Set<String> validColumns = Stream
                .concat(hints.getAllowedMetrics().stream(), hints.getAllowedDimensions().stream())
                .collect(Collectors.toSet());
        // Empty allow-list: skip catalog membership; selected dimensions/metrics still gate extras.
        if (!validColumns.isEmpty()
                && output.stream().anyMatch(column -> !columnAllowed(validColumns, column))) {
            errors.add(
                    error("UNKNOWN_OUTPUT_COLUMN", "output columns must be semantic identifiers"));
        }
        if (output.size() != outputColumns.size()) {
            errors.add(error("OUTPUT_EXTRA_COLUMN", "output columns must not contain duplicates"));
        }
        Set<String> metrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!containsAllIgnoreCase(output, metrics)) {
            errors.add(error("OUTPUT_MISSING_METRIC", "output must retain every requested metric"));
        }
        Set<String> dimensions = safe(plan.getDimensions())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!containsAllIgnoreCase(output, dimensions)) {
            errors.add(error("OUTPUT_MISSING_DIMENSION",
                    "output must retain every requested dimension"));
        }
        Set<String> selected = Stream.concat(metrics.stream(), dimensions.stream())
                .collect(Collectors.toSet());
        for (String column : output) {
            if (!containsIgnoreCase(selected, column)) {
                errors.add(error("OUTPUT_EXTRA_COLUMN",
                        "output must not contain valid but unselected fields: " + column));
            }
        }
    }

    /**
     * Point-query 存贷比 / structure share is often recognized with derived-metric evidence, but
     * the deterministic RATIO template expresses the same operands as direct metrics + baseline.
     */
    private boolean ratioPlanCoversRequiredDerived(BankQueryPlan plan,
            List<SemanticIntentHints.DerivedMetricSpec> required) {
        if (!isRatioPlan(plan) || required == null || required.isEmpty()) {
            return false;
        }
        List<String> planMetrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .filter(StringUtils::isNotBlank).collect(Collectors.toList());
        return required.stream()
                .allMatch(spec -> containsIgnoreCase(planMetrics, spec.numerator())
                        && containsIgnoreCase(planMetrics, spec.denominator()));
    }

    private boolean containsIgnoreCase(Collection<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }
        return values.stream().anyMatch(value -> value != null && value.equalsIgnoreCase(target));
    }

    private boolean dimensionAllowed(Collection<String> allowed, String dimension) {
        return allowed != null && allowed.contains(dimension);
    }

    private boolean columnAllowed(Collection<String> allowed, String column) {
        return metricAllowed(allowed, column) || dimensionAllowed(allowed, column);
    }

    private boolean metricAllowed(Collection<String> allowed, String metric) {
        return allowed != null && allowed.contains(metric);
    }

    private boolean containsAllIgnoreCase(Collection<String> haystack,
            Collection<String> needles) {
        if (needles == null || needles.isEmpty()) {
            return true;
        }
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (!containsIgnoreCase(haystack, needle)) {
                return false;
            }
        }
        return true;
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
