package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.bank.BankDataDomain;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Enforces mapper evidence before a BankQueryPlan can reach a compiler or executor. */
public class BankQueryPlanValidator {

    private static final Logger KEY_PIPELINE_LOG =
            LoggerFactory.getLogger(BankDataDomain.KEY_PIPELINE_LOGGER_NAME);

    private static final Set<String> ORGANIZATION_DIMENSIONS = Set.of("bank_organization");
    private static final Set<String> TIME_DIMENSIONS = Set.of("bank_data_date");

    // Parentheses are legitimate in display names and aliases ("成本收入比(%)"); only statement
    // keywords, terminators and comment markers make a plan string an executable fragment.
    private static final Pattern FORBIDDEN_SQL = Pattern
            .compile("(?i)(;|--|/\\*|\\*/|\\b(select|insert|update|delete|drop|alter|create|merge|"
                    + "truncate|join|union|from|where|with)\\b)");
    private static final Pattern DERIVED_METRIC_CODE =
            Pattern.compile("DERIVED_([A-Z0-9]+)_DIV_([A-Z0-9]+)");
    private static final Pattern ADDITIVE_DERIVED_METRIC_CODE =
            Pattern.compile("DERIVED_SUM_([A-Z0-9]+)_AND_([A-Z0-9]+)");
    private static final Pattern BASE_METRIC_CODE = Pattern.compile("ZB\\d{3}");
    private static final Pattern NUMERIC_THRESHOLD = Pattern.compile("-?\\d+(?:\\.\\d+)?%?");
    private static final Set<String> ABSOLUTE_THRESHOLD_OPERATORS =
            Set.of("GT", "GTE", "LT", "LTE", "EQ");
    /** Direction operators that can aim a benchmark condition at PROVINCE_AVERAGE. */
    private static final Set<String> BENCHMARK_DIRECTION_OPERATORS = Set.of("GT", "GTE", "LT",
            "LTE");
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
        validateDatesWithinDataDomain(plan, errors);
        validateFilters(plan, hints, errors);
        validateCalculation(plan, hints, errors);
        validateDerivedMetrics(plan, hints, errors);
        validateRankChangeContract(plan, errors);
        validateOrderingAndLimit(plan, hints, errors);
        validateOutput(plan, hints, errors);
        validateAbsoluteThresholdContract(plan, errors);
        validateMonthAndYearComparisonContract(plan, errors);
        validateFamilyShapeGuards(plan, errors);
        return new ValidationResult(errors);
    }

    private void validateVersion(BankQueryPlan plan, List<ValidationError> errors) {
        if (!BankQueryPlan.CURRENT_VERSION.equals(plan.getVersion())) {
            errors.add(error("UNSUPPORTED_PLAN_VERSION",
                    "plan.version must be " + BankQueryPlan.CURRENT_VERSION
                            + ", got=" + plan.getVersion()));
        }
    }

    private void validateAction(BankQueryPlan plan, List<ValidationError> errors) {
        if (plan.getAction() != BankQueryPlan.PlanAction.EXECUTE) {
            errors.add(error("PLAN_ACTION_REQUIRED",
                    "plan.action must be EXECUTE for executable plans, got="
                            + (plan.getAction() == null ? "null" : plan.getAction().name())));
        }
    }

    private void validateForbiddenTokens(BankQueryPlan plan, List<ValidationError> errors) {
        Optional<String> offending = strings(plan).filter(StringUtils::isNotBlank)
                .filter(value -> FORBIDDEN_SQL.matcher(value).find()).findFirst();
        if (offending.isPresent()) {
            String snippet = offending.get();
            int maxForbiddenSnippetLength = 40;
            if (snippet.length() > maxForbiddenSnippetLength) {
                snippet = snippet.substring(0, maxForbiddenSnippetLength);
            }
            errors.add(error("FORBIDDEN_SQL_TOKEN",
                    "plan must not contain SQL syntax or executable fragments; remove the value \""
                            + snippet + "\" from every string slot"));
        }
    }

    private Stream<String> strings(BankQueryPlan plan) {
        Stream<String> metrics = safe(plan.getMetrics())
                .flatMap(metric -> Stream.of(metric.getBizName(), metric.getAlias()));
        Stream<String> derivedMetrics =
                safe(plan.getDerivedMetrics()).flatMap(derived -> Stream.of(
                        Stream.of(derived.getMetricCode(), derived.getNumerator(),
                                derived.getDenominator(), derived.getName()),
                        safe(derived.getNumeratorOperands())).flatMap(stream -> stream));
        Stream<String> organizations = safe(plan.getOrganizations()).flatMap(
                organization -> Stream.of(organization.getCode(), organization.getBizName()));
        Stream<String> filters = safe(plan.getFilters()).flatMap(filter -> Stream.concat(
                Stream.of(filter.getField(), filter.getOperator(), filter.getValue()),
                safe(filter.getValues())));
        Stream<String> orderBy = safe(plan.getOrderBy()).map(BankQueryPlan.OrderBy::getField);
        Stream<String> output =
                plan.getOutput() == null ? Stream.empty() : safe(plan.getOutput().getColumns());
        return Stream.of(metrics, derivedMetrics, safe(plan.getDimensions()), organizations,
                filters, orderBy, output).flatMap(stream -> stream);
    }

    private void validateIntent(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        if (plan.getIntent() == null || plan.getIntent() == BankIntentType.UNKNOWN) {
            errors.add(error("INTENT_REQUIRED", "plan intent is required"));
        } else if (hints.getExpectedIntent() != null
                && hints.getExpectedIntent() != BankIntentType.UNKNOWN
                && plan.getIntent() != hints.getExpectedIntent()) {
            errors.add(error("INTENT_MISMATCH",
                    "plan.intent must equal the requirements contract intent; expected_intent="
                            + hints.getExpectedIntent() + ", plan_intent=" + plan.getIntent()));
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
                && (plan.getCalculation().getType() == BankQueryPlan.CalculationType.CHANGE
                        || plan.getCalculation().getType() == BankQueryPlan.CalculationType.RANK_CHANGE)
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
            Set<String> unknown = new LinkedHashSet<>(planOrganizations);
            unknown.removeAll(BankSemanticRegistry.organizationCodes());
            errors.add(error("UNKNOWN_ORGANIZATION",
                    "plan.organizations contains codes outside the official bank registry: "
                            + String.join(",", unknown)));
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
            errors.add(error("TIME_RANGE_INVALID",
                    "time.startDate must not be after time.endDate; startDate=" + time.getStartDate()
                            + ", endDate=" + time.getEndDate()));
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
            errors.add(error("TIME_RANGE_MISMATCH",
                    "plan must preserve the recognized time range; expected startDate="
                            + hints.getRequiredStartDate() + ", endDate=" + hints.getRequiredEndDate()
                            + "; plan startDate=" + time.getStartDate() + ", endDate="
                            + time.getEndDate()));
        }
        if (hints.getRequiredTimeComparison() != null
                && time.getComparison() != hints.getRequiredTimeComparison()) {
            errors.add(error("TIME_COMPARISON_MISMATCH",
                    "time.comparison must equal the requirements contract; expected_comparison="
                            + hints.getRequiredTimeComparison() + ", plan_comparison="
                            + time.getComparison()));
        }
        if ((hints.getRequiredBaselineStartDate() != null && !Objects
                .equals(hints.getRequiredBaselineStartDate(), time.getBaselineStartDate()))
                || (hints.getRequiredBaselineEndDate() != null && !Objects
                        .equals(hints.getRequiredBaselineEndDate(), time.getBaselineEndDate()))) {
            errors.add(error("COMPARISON_BASELINE_MISMATCH",
                    "baseline dates must equal the requirements contract; expected baselineStartDate="
                            + hints.getRequiredBaselineStartDate() + ", baselineEndDate="
                            + hints.getRequiredBaselineEndDate() + "; plan baselineStartDate="
                            + time.getBaselineStartDate() + ", baselineEndDate="
                            + time.getBaselineEndDate()));
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
                    "baseline window must satisfy baselineStartDate<=baselineEndDate<startDate"
                            + "（基期只写较早期的两点，不是“从基期到当前期”）；plan baselineStartDate="
                            + time.getBaselineStartDate() + ", baselineEndDate="
                            + time.getBaselineEndDate() + ", startDate=" + time.getStartDate()));
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

    /** One keyPipeline note per process when the guard runs before the domain was observed. */
    private static final AtomicBoolean DATA_DOMAIN_SKIP_NOTED = new AtomicBoolean(false);

    /**
     * Family-level date-hallucination guard: every date slot of the plan time object (current
     * window and comparison baseline) must fall inside the dataset's real {@code data_date}
     * domain. A slot outside the domain provably matches no data row, so period-over-period and
     * other baseline joins would silently return empty results. This rejects only provable
     * errors — any in-domain plan shape is unaffected — and falls open (with a single keyPipeline
     * note) while the execution path has not yet observed the domain, so first parses are never
     * blocked by an uninitialized cache.
     */
    private void validateDatesWithinDataDomain(BankQueryPlan plan, List<ValidationError> errors) {
        BankDataDomain domain = BankDataDomain.current();
        if (domain == null) {
            if (DATA_DOMAIN_SKIP_NOTED.compareAndSet(false, true)) {
                KEY_PIPELINE_LOG.info("BankQueryPlanValidator date-domain guard fell open: "
                        + "BankDataDomain is not initialized yet (no executed query has observed "
                        + "the data_date range); date slots are not checked until then");
            }
            return;
        }
        BankQueryPlan.TimeRange time = plan.getTime();
        if (time == null) {
            // Missing time is already reported by TIME_REQUIRED.
            return;
        }
        rejectDateOutsideDataDomain("time.startDate", time.getStartDate(), domain, errors);
        rejectDateOutsideDataDomain("time.endDate", time.getEndDate(), domain, errors);
        rejectDateOutsideDataDomain("time.baselineStartDate", time.getBaselineStartDate(), domain,
                errors);
        rejectDateOutsideDataDomain("time.baselineEndDate", time.getBaselineEndDate(), domain,
                errors);
    }

    private void rejectDateOutsideDataDomain(String slot, LocalDate value, BankDataDomain domain,
            List<ValidationError> errors) {
        if (value == null || domain.contains(value)) {
            return;
        }
        errors.add(error("DATE_OUT_OF_DATA_DOMAIN", slot + "=" + value
                + " is outside the dataset's real data_date domain " + domain + "; no data rows "
                + "exist on that date, so the query provably returns an empty result. Replace "
                + slot + " with a real in-domain date consistent with the question's time wording"
                + "；" + slot + "=" + value + " 不在数据集真实数据域 " + domain
                + " 内，该日期无任何数据，查询必然返回空结果（日期幻觉）；请把 " + slot
                + " 修正为数据域内、与题面时间措辞一致的真实日期"));
    }

    private static boolean isFullCalendarYear(BankQueryPlan.TimeRange time) {
        return time != null && time.getStartDate() != null && time.getEndDate() != null
                && time.getStartDate().getMonthValue() == 1
                && time.getStartDate().getDayOfMonth() == 1
                && time.getEndDate().getMonthValue() == 12
                && time.getEndDate().getDayOfMonth() == 31
                && time.getStartDate().getYear() == time.getEndDate().getYear();
    }

    private void validateFilters(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        List<BankQueryPlan.Filter> filters = safe(plan.getFilters()).collect(Collectors.toList());
        boolean hasProvinceAverageBenchmark =
                filters.stream().anyMatch(this::isProvinceAverageBenchmark);
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
                    && !allowedFields.contains(filter.getField())
                    && !looksLikeMetricBenchmarkCondition(filter)) {
                // A per-metric benchmark direction aimed at PROVINCE_AVERAGE gets its own
                // repairable family guard below instead of the generic unknown-field error.
                errors.add(error("UNKNOWN_FILTER_FIELD",
                        "filter field must be a semantic identifier or approved logical field"));
            }
            if (StringUtils.isBlank(filter.getValue())
                    && safe(filter.getValues()).findAny().isEmpty()) {
                errors.add(error("FILTER_VALUE_REQUIRED", "filter value is required"));
            }
            boolean provinceAverageBenchmark = isProvinceAverageBenchmark(filter);
            boolean provinceAverageDirection = isProvinceAverageDirection(filter);
            boolean metricBenchmarkCondition = isMetricBenchmarkCondition(filter);
            boolean malformedMetricBenchmark =
                    !metricBenchmarkCondition && looksLikeMetricBenchmarkCondition(filter);
            if (("benchmark".equals(filter.getField()) || "COMPARE".equals(filter.getOperator()))
                    && !provinceAverageBenchmark) {
                errors.add(error("PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED",
                        "province average must use exact benchmark/COMPARE/PROVINCE_AVERAGE"));
            }
            if ("PROVINCE_AVERAGE".equals(filter.getValue()) && !provinceAverageBenchmark
                    && !provinceAverageDirection && !metricBenchmarkCondition
                    && !malformedMetricBenchmark) {
                errors.add(error("PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED",
                        "PROVINCE_AVERAGE may only be a benchmark filter, a metric_value "
                                + "direction object, or a per-metric benchmark condition "
                                + "{\"field\":\"<ZB###>\",\"operator\":\"GT|GTE|LT|LTE\","
                                + "\"value\":\"PROVINCE_AVERAGE\",\"values\":[]}"));
            }
            if ((provinceAverageDirection || metricBenchmarkCondition)
                    && !hasProvinceAverageBenchmark) {
                errors.add(error("PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED",
                        "province-average direction requires the exact benchmark filter"));
            }
            if (isRankFilter(filter) && (!rankFilterIntentAllowed(plan)
                    || !"LTE".equals(filter.getOperator()) || StringUtils.isBlank(filter.getValue())
                    || !filter.getValue().matches("[1-9]\\d*")
                    || safe(filter.getValues()).findAny().isPresent())) {
                errors.add(error("RANK_FILTER_CONTRACT_INVALID",
                        "rank and rank_from_bottom filters require intent=RANKING (or CHANGE "
                                + "with a non-NONE comparison), operator=LTE, a positive "
                                + "integer value, and values=[]"));
            }
            if ((provinceAverageBenchmark || provinceAverageDirection || metricBenchmarkCondition)
                    && safe(filter.getValues()).findAny().isPresent()) {
                errors.add(error("PROVINCE_AVERAGE_BENCHMARK_VALUES_FORBIDDEN",
                        "province-average benchmark values must be empty"));
            }
        }
        for (SemanticIntentHints.RequiredFilter required : hints.getRequiredFilters()) {
            if ((rankedChangeContract(plan) || isRankChangePlan(plan))
                    && isRankFieldName(required.field())) {
                // Ranked-change results carry the full organization population; echoing the
                // rank filter in the plan is optional metadata, not a compiled condition.
                continue;
            }
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

    /**
     * Loose shape detector for a per-metric benchmark condition (compound benchmark family):
     * a direction operator aimed at PROVINCE_AVERAGE from any slot outside the reserved logical
     * fields. It deliberately tolerates non-catalog fields so the family guard can answer a
     * malformed field with a repairable message instead of the generic unknown-field error.
     */
    static boolean looksLikeMetricBenchmarkCondition(BankQueryPlan.Filter filter) {
        return filter != null && "PROVINCE_AVERAGE".equals(filter.getValue())
                && BENCHMARK_DIRECTION_OPERATORS.contains(filter.getOperator())
                && !LOGICAL_FILTER_FIELDS.contains(filter.getField());
    }

    /**
     * Exact per-metric benchmark condition of the compound benchmark family: the field is a
     * registered ZB### catalog metric carrying a direction operator against PROVINCE_AVERAGE
     * ({@code field=<ZB###>, operator=GT|GTE|LT|LTE, value=PROVINCE_AVERAGE}). One such condition
     * per selected metric, beside the exact benchmark filter, declares the compound AND shape.
     */
    static boolean isMetricBenchmarkCondition(BankQueryPlan.Filter filter) {
        return looksLikeMetricBenchmarkCondition(filter) && filter.getField() != null
                && BankSemanticRegistry.metricCodes()
                        .contains(filter.getField().toUpperCase(Locale.ROOT));
    }

    private boolean isRankFilter(BankQueryPlan.Filter filter) {
        return filter != null && isRankFieldName(filter.getField());
    }

    private static boolean isRankFieldName(String field) {
        return "rank".equals(field) || "rank_from_bottom".equals(field);
    }

    /**
     * Rank filters are compiled only for RANKING plans, but a ranked-change question (growth
     * ranking over a comparison window) must keep intent=CHANGE for its time comparison, so the
     * filter is accepted there as advisory metadata the compiler skips.
     */
    private static boolean rankFilterIntentAllowed(BankQueryPlan plan) {
        return plan.getIntent() == BankIntentType.RANKING || rankedChangeContract(plan);
    }

    private static boolean rankedChangeContract(BankQueryPlan plan) {
        return plan.getIntent() == BankIntentType.CHANGE && plan.getTime() != null
                && plan.getTime().getComparison() != null
                && plan.getTime().getComparison() != BankQueryPlan.TimeComparison.NONE;
    }

    /**
     * Fail closed on the exact plan shape required by the compiler-owned absolute-threshold S2SQL
     * template. Province-average threshold plans have their own benchmark contract and are not
     * subject to this gate. Without this validation, a superficially valid THRESHOLD plan can fall
     * through to the generic STRUCT route and lose the organization/metric identity needed by the
     * result fact contract.
     */
    private void validateAbsoluteThresholdContract(BankQueryPlan plan,
            List<ValidationError> errors) {
        boolean hasAbsoluteMetricFilter = safe(plan.getFilters())
                .anyMatch(filter -> "metric_value".equals(filter.getField()));
        if (plan.getIntent() != BankIntentType.THRESHOLD || !hasAbsoluteMetricFilter
                || safe(plan.getFilters()).anyMatch(this::isProvinceAverageBenchmark)) {
            return;
        }
        List<String> metrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .filter(StringUtils::isNotBlank).collect(Collectors.toList());
        if (metrics.size() != 1) {
            errors.add(error("ABSOLUTE_THRESHOLD_SINGLE_METRIC_REQUIRED",
                    "absolute threshold requires exactly one selected metric"));
        }
        List<String> organizations =
                safe(plan.getOrganizations()).map(BankQueryPlan.Organization::getCode)
                        .filter(StringUtils::isNotBlank).collect(Collectors.toList());
        if (organizations.size() != 1) {
            errors.add(error("ABSOLUTE_THRESHOLD_SINGLE_ORGANIZATION_REQUIRED",
                    "absolute threshold requires exactly one selected organization"));
        }
        List<String> dimensions = safe(plan.getDimensions()).filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (!dimensions.equals(List.of("bank_organization"))) {
            errors.add(error("ABSOLUTE_THRESHOLD_ORGANIZATION_DIMENSION_REQUIRED",
                    "absolute threshold dimensions must be exactly [bank_organization]"));
        }
        List<BankQueryPlan.Filter> filters = safe(plan.getFilters()).collect(Collectors.toList());
        boolean exactThresholdFilter =
                filters.size() == 1 && "metric_value".equals(filters.get(0).getField())
                        && ABSOLUTE_THRESHOLD_OPERATORS.contains(filters.get(0).getOperator())
                        && StringUtils.isNotBlank(filters.get(0).getValue())
                        && NUMERIC_THRESHOLD.matcher(filters.get(0).getValue()).matches()
                        && safe(filters.get(0).getValues()).findAny().isEmpty();
        if (!exactThresholdFilter) {
            errors.add(error("ABSOLUTE_THRESHOLD_FILTER_REQUIRED",
                    "absolute threshold requires exactly one numeric metric_value filter using "
                            + "GT, GTE, LT, LTE, or EQ"));
        }
        if (plan.getCalculation() == null
                || plan.getCalculation().getType() != BankQueryPlan.CalculationType.DIRECT) {
            errors.add(error("ABSOLUTE_THRESHOLD_DIRECT_CALCULATION_REQUIRED",
                    "absolute threshold requires calculation.type=DIRECT"));
        }
        if (plan.getTime() != null && plan.getTime().getComparison() != null
                && plan.getTime().getComparison() != BankQueryPlan.TimeComparison.NONE) {
            errors.add(error("ABSOLUTE_THRESHOLD_NO_COMPARISON_REQUIRED",
                    "absolute threshold requires time.comparison=NONE"));
        }
        if (safe(plan.getOrderBy()).findAny().isPresent()) {
            errors.add(error("ABSOLUTE_THRESHOLD_NO_ORDER_REQUIRED",
                    "absolute threshold ordering is compiler-owned; set orderBy to []"));
        }
        if (plan.getLimit() != null) {
            errors.add(error("ABSOLUTE_THRESHOLD_NO_LIMIT_REQUIRED",
                    "absolute threshold requires limit=null"));
        }
        if (metrics.size() == 1) {
            List<String> expectedOutput = List.of("bank_organization", metrics.get(0));
            List<String> actualOutput = plan.getOutput() == null ? List.of()
                    : safe(plan.getOutput().getColumns()).collect(Collectors.toList());
            if (actualOutput.size() != expectedOutput.size()
                    || !containsAllIgnoreCase(new LinkedHashSet<>(actualOutput),
                            new LinkedHashSet<>(expectedOutput))) {
                errors.add(error("ABSOLUTE_THRESHOLD_OUTPUT_REQUIRED",
                        "absolute threshold output.columns must contain exactly " + expectedOutput));
            }
        }
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
        BankQueryPlan.TimeComparison comparison =
                plan.getTime() == null ? null : plan.getTime().getComparison();
        if (comparison != null && comparison != BankQueryPlan.TimeComparison.NONE
                && calculation.getType() != BankQueryPlan.CalculationType.CHANGE
                && calculation.getType() != BankQueryPlan.CalculationType.RANK_CHANGE) {
            errors.add(error("COMPARISON_CALCULATION_REQUIRED",
                    "a non-NONE time comparison requires calculation.type=CHANGE or "
                            + "calculation.type=RANK_CHANGE"));
        }
        if (calculation.getType() == BankQueryPlan.CalculationType.CHANGE
                && comparison == BankQueryPlan.TimeComparison.NONE) {
            errors.add(error("CHANGE_COMPARISON_REQUIRED",
                    "calculation.type=CHANGE requires an explicit non-NONE time comparison"));
        }
        BankQueryPlan.CalculationType expected = switch (hints.getExpectedIntent()) {
            case CHANGE -> BankQueryPlan.CalculationType.CHANGE;
            case RATIO -> BankQueryPlan.CalculationType.RATIO;
            case AGGREGATION -> calculation
                    .getType() == BankQueryPlan.CalculationType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE
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
                                || hints.getExpectedIntent() == BankIntentType.AGGREGATION))
                // Cross-period rank change keeps the CHANGE intent for its time comparison but
                // declares its own calculation family.
                && !(calculation.getType() == BankQueryPlan.CalculationType.RANK_CHANGE
                        && hints.getExpectedIntent() == BankIntentType.CHANGE)) {
            errors.add(error("CALCULATION_MISMATCH",
                    "calculation type conflicts with financial intent"));
        }
        if (calculation
                .getType() == BankQueryPlan.CalculationType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE) {
            validateDaysAboveProvinceAverageCount(plan, errors);
        }
        if (calculation.getType() == BankQueryPlan.CalculationType.RATIO) {
            List<String> metricOrder = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                    .filter(StringUtils::isNotBlank).toList();
            boolean compositeDenominator = compositeRatioDenominatorMatches(plan, metricOrder,
                    calculation.getBaseline());
            if (metricOrder.size() < 2 || StringUtils.isBlank(calculation.getBaseline())) {
                errors.add(error("RATIO_DENOMINATOR_REQUIRED",
                        "ratio requires an explicit second selected metric as denominator"));
            } else if (!metricOrder.get(1).equals(calculation.getBaseline())
                    && !compositeDenominator) {
                errors.add(error("RATIO_DENOMINATOR_MISMATCH",
                        "ratio denominator must be the second selected metric"));
            } else if (!compositeDenominator) {
                validateRatioOperands(hints, metricOrder, errors);
            }
        }
    }

    /**
     * Composite-numerator ratio plans (numerator = sum of K base metrics over one denominator)
     * keep the natural operand order: every sum operand first, the single denominator last. The
     * baseline must be that last selected metric and a matching composite derived metric must
     * declare the same denominator.
     */
    private boolean compositeRatioDenominatorMatches(BankQueryPlan plan,
            List<String> metricOrder, String baseline) {
        if (StringUtils.isBlank(baseline) || metricOrder.size() < 3
                || !metricOrder.get(metricOrder.size() - 1).equals(baseline)) {
            return false;
        }
        return safe(plan.getDerivedMetrics()).anyMatch(item -> isCompositeDerivedMetric(item)
                && baseline.equals(item.getDenominator()));
    }

    /**
     * Fail-closed gates for the ratio operand contract. The numerator and denominator must be
     * different catalog metrics (a ratio of an operand to itself would silently compile to a
     * constant), and when the runtime derived-metric evidence pins the direction (e.g.
     * 存贷比 = ZB002 / ZB001) the plan must keep the catalog numerator in metrics[0]; otherwise
     * the compiled ratio silently inverts into the reciprocal.
     */
    private void validateRatioOperands(SemanticIntentHints hints, List<String> metricOrder,
            List<ValidationError> errors) {
        String numerator = metricOrder.get(0);
        String denominator = metricOrder.get(1);
        if (numerator.equalsIgnoreCase(denominator)) {
            errors.add(error("RATIO_OPERAND_IDENTICAL",
                    "ratio numerator and denominator resolve to the same metric: metrics[0]="
                            + numerator + " 与 metrics[1]=calculation.baseline=" + denominator
                            + " 相同，比率恒为常数；必须选择两个不同的 ZB### 目录指标，metrics[0]=分子、"
                            + "metrics[1]=分母"));
            return;
        }
        SemanticIntentHints.DerivedMetricSpec spec = matchingRatioSpec(hints, numerator,
                denominator);
        if (spec != null && !spec.numerator().equalsIgnoreCase(numerator)) {
            errors.add(error("RATIO_DIRECTION_MISMATCH",
                    "ratio direction is inverted: 目录派生指标 " + spec.code() + "（" + spec.name()
                            + "）定义为 " + spec.numerator() + " / " + spec.denominator()
                            + "，metrics[0] 必须是分子 " + spec.numerator()
                            + "、metrics[1]=calculation.baseline 必须是分母 " + spec.denominator()
                            + "；当前 metrics=[" + numerator + ", " + denominator
                            + "] 会产生倒数比率"));
        }
    }

    /**
     * Returns the derived-metric spec whose operand pair is exactly the plan's two ratio
     * operands (in either order). Only such a spec locks the direction; unrecognized operand
     * pairs stay unlocked so evidence-free ratios are never falsely rejected.
     */
    private SemanticIntentHints.DerivedMetricSpec matchingRatioSpec(SemanticIntentHints hints,
            String numerator, String denominator) {
        for (SemanticIntentHints.DerivedMetricSpec spec : hints.getRequiredDerivedMetrics()) {
            if (spec == null || StringUtils.isBlank(spec.numerator())
                    || StringUtils.isBlank(spec.denominator())) {
                continue;
            }
            boolean forward = spec.numerator().equalsIgnoreCase(numerator)
                    && spec.denominator().equalsIgnoreCase(denominator);
            boolean reversed = spec.numerator().equalsIgnoreCase(denominator)
                    && spec.denominator().equalsIgnoreCase(numerator);
            if (forward || reversed) {
                return spec;
            }
        }
        return null;
    }

    /**
     * Fail-closed gate for the runtime derived metric contract (e.g. 存贷比 =
     * DERIVED_ZB002_DIV_ZB001). A derived metric is only accepted when the code equals
     * DERIVED_&lt;numerator&gt;_DIV_&lt;denominator&gt;, the operands are distinct legal ZB### base
     * metrics also selected as direct metrics, the plan is either a RANKING with a DIRECT
     * calculation or a point RATIO with a RATIO calculation, and the plan derived metrics match
     * the mapper evidence exactly in content and order. Missing,
     * duplicate, extra, reordered or illegal entries are all rejected.
     *
     * <p>The additive composite family ({@code DERIVED_SUM_<M1>_AND_<M2>}, two percent-unit
     * catalog metrics summed as one virtual point metric) is admitted beside those ratio shapes
     * on its own whitelist; the family shape itself is pinned by
     * {@link #validateAdditiveCompositeFamilyShape}.
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
        List<BankQueryPlan.DerivedMetric> single =
                derived.stream().filter(item -> !isCompositeDerivedMetric(item))
                        .collect(Collectors.toList());
        List<BankQueryPlan.DerivedMetric> additive =
                single.stream().filter(BankQueryPlanValidator::isAdditiveDerivedMetric)
                        .collect(Collectors.toList());
        // Composite-numerator ratios (sum of K base metrics over one denominator) have no
        // catalog-derived evidence to match; they are admitted on their own whitelist shape.
        // Any single-numerator derived metric still requires exact mapper evidence. Additive
        // composite metrics carry their own whitelist below and need no evidence either — any
        // DERIVED_SUM_-prefixed code is validated by the additive whitelist even when the code
        // itself is malformed, so repair always names the legal additive shape.
        if (required.isEmpty() && !single.isEmpty()
                && single.stream().noneMatch(BankQueryPlanValidator::looksAdditiveDerivedMetric)) {
            errors.add(error("DERIVED_METRIC_UNEXPECTED",
                    "plan contains a derived metric outside mapper evidence"));
            return;
        }
        boolean rankingDirect = plan.getIntent() == BankIntentType.RANKING
                && plan.getCalculation() != null
                && plan.getCalculation().getType() == BankQueryPlan.CalculationType.DIRECT;
        boolean pointRatio = plan.getIntent() == BankIntentType.RATIO
                && plan.getCalculation() != null
                && plan.getCalculation().getType() == BankQueryPlan.CalculationType.RATIO;
        boolean additiveComposite = !additive.isEmpty() && additive.size() == single.size();
        if (!rankingDirect && !pointRatio && !additiveComposite) {
            errors.add(error("DERIVED_METRIC_INTENT_REQUIRED",
                    "derived metrics require RANKING/DIRECT or RATIO/RATIO"));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (BankQueryPlan.DerivedMetric item : derived) {
            validateDerivedMetricItem(item, seen, errors);
        }
        if (additiveComposite && required.isEmpty()) {
            // Pure additive whitelist: no mapper evidence exists for a virtual sum metric, so
            // only the code shape/operand checks above apply.
        } else if (single.isEmpty()) {
            if (!required.isEmpty()) {
                errors.add(error("DERIVED_METRIC_MISMATCH",
                        "plan derived metrics must match the recognized derived metric specifications"));
            }
        } else if (single.size() != required.size()) {
            errors.add(error("DERIVED_METRIC_MISMATCH",
                    "plan derived metrics must match the recognized derived metric specifications"));
        } else {
            for (int index = 0; index < single.size(); index++) {
                if (!matches(required.get(index), single.get(index))) {
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
            List<String> operands = isCompositeDerivedMetric(item)
                    ? item.getNumeratorOperands() : List.of(item.getNumerator());
            for (String operand : operands) {
                if (!containsIgnoreCase(planMetrics, operand)) {
                    errors.add(error("DERIVED_METRIC_OPERAND_REQUIRED",
                            "derived metric numerator must also be selected as a direct metric: "
                                    + operand));
                }
            }
            if (!containsIgnoreCase(planMetrics, item.getDenominator())) {
                errors.add(error("DERIVED_METRIC_OPERAND_REQUIRED",
                        "derived metric denominator must also be selected as a direct metric: "
                                + item.getDenominator()));
            }
        }
    }

    /**
     * Shape predicate for the additive composite derived metric: the exact canonical code
     * {@code DERIVED_SUM_<M1>_AND_<M2>} (lexicographic operand order, no _DIV_ suffix) with no
     * composite numeratorOperands. Shared with the compiler routing table.
     */
    static boolean isAdditiveDerivedMetric(BankQueryPlan.DerivedMetric item) {
        return item != null && !isCompositeDerivedMetric(item)
                && BankSemanticRegistry.isAdditiveDerivedMetricCode(item.getMetricCode());
    }

    /**
     * Loose prefix twin of {@link #isAdditiveDerivedMetric}: a non-composite item whose code at
     * least carries the {@code DERIVED_SUM_} prefix. Such items never take the generic
     * "outside mapper evidence" exit so the whitelist can name the exact legal additive shape.
     */
    private static boolean looksAdditiveDerivedMetric(BankQueryPlan.DerivedMetric item) {
        return item != null && !isCompositeDerivedMetric(item)
                && item.getMetricCode() != null && item.getMetricCode().startsWith("DERIVED_SUM_");
    }

    private static boolean isCompositeDerivedMetric(BankQueryPlan.DerivedMetric item) {
        return item != null && item.getNumeratorOperands() != null
                && !item.getNumeratorOperands().isEmpty();
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
        if (isCompositeDerivedMetric(item)) {
            validateCompositeDerivedMetricItem(item, errors);
            return;
        }
        if (BankSemanticRegistry.isAdditiveDerivedMetricCode(code)) {
            validateAdditiveDerivedMetricItem(item, errors);
            return;
        }
        Matcher matcher = DERIVED_METRIC_CODE.matcher(code);
        if (!matcher.matches()) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "derived metric code must be DERIVED_<numerator>_DIV_<denominator>: " + code));
        } else if (!matcher.group(1).equals(numerator) || !matcher.group(2).equals(denominator)) {
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
                    "derived metric denominator must be a legal ZB### base metric: "
                            + denominator));
        }
    }

    /**
     * Whitelist shape for composite-numerator derived ratios: the code must equal
     * {@code DERIVED_SUM_<n1>_AND_<n2>[_AND_...]_DIV_<d>}, every operand a distinct legal ZB###
     * base metric (two or more), the denominator a single legal base metric, and the required
     * numerator field repeats the first operand so single-operand consumers stay coherent.
     */
    private void validateCompositeDerivedMetricItem(BankQueryPlan.DerivedMetric item,
            List<ValidationError> errors) {
        String code = item.getMetricCode();
        List<String> operands = item.getNumeratorOperands();
        if (operands.isEmpty()) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "composite derived metric numeratorOperands requires at least two distinct "
                            + "ZB### base metrics: " + code));
            return;
        }
        if (operands.stream().anyMatch(operand -> operand == null
                || !BASE_METRIC_CODE.matcher(operand).matches())) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "composite derived metric numeratorOperands must all be legal ZB### base "
                            + "metrics: " + code));
            return;
        }
        if (operands.size() < 2) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "composite derived metric numeratorOperands requires at least two distinct "
                            + "ZB### base metrics: " + code));
        }
        if (new LinkedHashSet<>(operands).size() != operands.size()) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "composite derived metric numeratorOperands must not repeat an operand: "
                            + code));
        }
        // Degenerate composite ratio (e.g. (ZB003+ZB001)/ZB001): a denominator hidden inside the
        // numerator sum inflates the ratio by a constant 1 and silently corrupts every ranked or
        // compared value. The index-0 case is already covered by the numerator==denominator check.
        String denominatorKey = item.getDenominator() == null ? null
                : item.getDenominator().toUpperCase(Locale.ROOT);
        for (int index = 1; index < operands.size(); index++) {
            if (denominatorKey != null
                    && operands.get(index).toUpperCase(Locale.ROOT).equals(denominatorKey)) {
                errors.add(error("DEGENERATE_COMPOSITE_RATIO",
                        "composite derived ratio must not contain the denominator inside "
                                + "numeratorOperands: " + code + " 的分母 " + item.getDenominator()
                                + " 同时出现在分子求和项中（如 (ZB003+ZB001)/ZB001），比率会被恒定膨胀；"
                                + "请把分母移出 numeratorOperands"));
                break;
            }
        }
        if (!item.getNumerator().equals(operands.get(0))) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "composite derived metric numerator must repeat the first numeratorOperands "
                            + "entry: " + code));
        }
        if (item.getNumerator().equals(item.getDenominator())) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "derived metric numerator and denominator must differ: " + code));
        }
        if (!BASE_METRIC_CODE.matcher(item.getDenominator()).matches()) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "derived metric denominator must be a legal ZB### base metric: "
                            + item.getDenominator()));
        }
        String expectedCode = "DERIVED_SUM_" + String.join("_AND_", operands) + "_DIV_"
                + item.getDenominator();
        if (!expectedCode.equals(code)) {
            errors.add(error("DERIVED_METRIC_INVALID",
                    "composite derived metric code must be " + expectedCode + ": " + code));
        }
    }

    /**
     * Whitelist for the additive composite derived metric {@code DERIVED_SUM_<M1>_AND_<M2>}
     * (两个同单位百分率指标的合计虚拟指标): both operands must be distinct registered catalog
     * metrics carrying the percent unit (%), and the code must be the canonical form — operands
     * sorted lexicographically, the smaller one repeated as numerator, the larger as
     * denominator, with no _DIV_ suffix. Percent values are already stored in %, so this virtual
     * metric is a plain point-day sum, never a scaled ratio. Every violation is a repairable
     * error that names the legal shape.
     */
    private void validateAdditiveDerivedMetricItem(BankQueryPlan.DerivedMetric item,
            List<ValidationError> errors) {
        String code = item.getMetricCode();
        String numerator = item.getNumerator();
        String denominator = item.getDenominator();
        if (!BankSemanticRegistry.metricCodes().contains(numerator)
                || !BankSemanticRegistry.metricCodes().contains(denominator)
                || numerator.equals(denominator)
                || !BankSemanticRegistry.isPercentUnitMetric(numerator)
                || !BankSemanticRegistry.isPercentUnitMetric(denominator)) {
            errors.add(error("ADDITIVE_OPERAND_INVALID",
                    "additive derived metric operands must be two distinct registered "
                            + "percent-unit (%) catalog metrics, got numerator=" + numerator
                            + ", denominator=" + denominator + " in " + code
                            + "；合法形状：DERIVED_SUM_<M1>_AND_<M2>，M1/M2 为两个互异、单位为 % 的目录指标"));
            return;
        }
        String canonical = BankSemanticRegistry.additiveDerivedMetricCode(numerator, denominator);
        Matcher matcher = ADDITIVE_DERIVED_METRIC_CODE.matcher(code);
        boolean fieldsAlignedWithCode = matcher.matches()
                && matcher.group(1).equals(numerator) && matcher.group(2).equals(denominator);
        if (!canonical.equals(code) || !fieldsAlignedWithCode) {
            errors.add(error("UNSUPPORTED_DERIVED_SHAPE",
                    "additive derived metric code must be the canonical form " + canonical
                            + " (operands sorted lexicographically, numerator=M1, "
                            + "denominator=M2, no _DIV_ suffix): " + code));
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
     * benchmark, without any absolute metric threshold, ordering, or TopN limit. An ordinary COUNT
     * or THRESHOLD plan must never be mistaken for this semantics.
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
        List<String> planOrganizations =
                safe(plan.getOrganizations()).map(BankQueryPlan.Organization::getCode)
                        .filter(StringUtils::isNotBlank).collect(Collectors.toList());
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
        boolean benchmark =
                safe(plan.getFilters()).anyMatch(filter -> "benchmark".equals(filter.getField())
                        && "COMPARE".equals(filter.getOperator())
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

    /**
     * Family contract for the cross-period rank-change query (当期排名 ⋈ 基期排名). Ranks are
     * computed over the whole (or explicitly selected) organization population for each metric,
     * so the family forbids derived metrics and rank slices, and requires both period windows:
     * the current period in startDate/endDate and the earlier baseline period in
     * baselineStartDate/baselineEndDate.
     */
    private void validateRankChangeContract(BankQueryPlan plan, List<ValidationError> errors) {
        if (!isRankChangePlan(plan)) {
            return;
        }
        if (safe(plan.getDerivedMetrics()).findAny().isPresent()) {
            errors.add(error("RANK_CHANGE_DERIVED_METRIC_FORBIDDEN",
                    "rank-change queries rank plain catalog metrics only; remove every derived "
                            + "metric"));
        }
        BankQueryPlan.TimeRange time = plan.getTime();
        if (time != null && (time.getComparison() == null
                || time.getComparison() == BankQueryPlan.TimeComparison.NONE
                || time.getComparison() == BankQueryPlan.TimeComparison.MOM_AND_YOY
                || time.getBaselineStartDate() == null || time.getBaselineEndDate() == null)) {
            errors.add(error("RANK_CHANGE_TWO_PERIODS_REQUIRED",
                    "rank-change queries require two explicit periods: time.comparison="
                            + "PERIOD_OVER_PERIOD (or YEAR_OVER_YEAR / START_OF_YEAR) with the "
                            + "earlier period in baselineStartDate/baselineEndDate"));
        }
        if (safe(plan.getFilters()).anyMatch(this::isRankFilter)) {
            errors.add(error("RANK_CHANGE_RANK_FILTER_FORBIDDEN",
                    "rank-change results carry the full population ranking; rank and "
                            + "rank_from_bottom filters are illegal in this family"));
        }
    }

    private static boolean isRankChangePlan(BankQueryPlan plan) {
        return plan != null && plan.getCalculation() != null
                && plan.getCalculation().getType() == BankQueryPlan.CalculationType.RANK_CHANGE;
    }

    /**
     * MOM_AND_YOY is a compiler-owned scalar comparison. Keeping the model plan to one metric and
     * one organization avoids ambiguous multi-series projections and lets the compiler derive both
     * baselines from the current date.
     */
    private void validateMonthAndYearComparisonContract(BankQueryPlan plan,
            List<ValidationError> errors) {
        BankQueryPlan.TimeRange time = plan.getTime();
        if (time == null || time.getComparison() != BankQueryPlan.TimeComparison.MOM_AND_YOY) {
            return;
        }
        List<String> metrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .filter(StringUtils::isNotBlank).toList();
        if (metrics.size() != 1 || safe(plan.getDerivedMetrics()).findAny().isPresent()) {
            errors.add(error("MOM_AND_YOY_SINGLE_METRIC_REQUIRED",
                    "MOM_AND_YOY requires exactly one direct metric"));
        }
        List<String> organizations = safe(plan.getOrganizations())
                .map(BankQueryPlan.Organization::getCode).filter(StringUtils::isNotBlank).toList();
        if (organizations.size() != 1) {
            errors.add(error("MOM_AND_YOY_SINGLE_ORGANIZATION_REQUIRED",
                    "MOM_AND_YOY requires exactly one organization"));
        }
        if (safe(plan.getDimensions()).findAny().isPresent()) {
            errors.add(error("MOM_AND_YOY_DIMENSIONS_FORBIDDEN",
                    "MOM_AND_YOY dimensions must be empty"));
        }
        if (safe(plan.getFilters()).findAny().isPresent()) {
            errors.add(error("MOM_AND_YOY_METRIC_FILTER_FORBIDDEN",
                    "MOM_AND_YOY filters must be empty"));
        }
        if (time.getBaselineStartDate() != null || time.getBaselineEndDate() != null) {
            errors.add(error("MOM_AND_YOY_BASELINES_MUST_BE_DERIVED",
                    "MOM_AND_YOY baseline dates must be null so the compiler can derive them"));
        }
    }

    /**
     * Family audit guards: reject only plan shapes that provably compile into a wrong result or a
     * broken projection downstream. Each guard mirrors a compiler/projector hard contract that was
     * previously enforced by silent override or silent degradation.
     */
    private void validateFamilyShapeGuards(BankQueryPlan plan, List<ValidationError> errors) {
        validateNoDuplicateMetrics(plan, errors);
        validateThresholdBenchmarkDimensionGate(plan, errors);
        validateThresholdAnchor(plan, errors);
        validateCompoundBenchmarkFamilyShape(plan, errors);
        validateChangePopulationDimension(plan, errors);
        validateTrendQuarterEndWindow(plan, errors);
        validatePercentMetricRangeAggregation(plan, errors);
        validateAdditiveCompositeFamilyShape(plan, errors);
    }

    /**
     * Family audit guard for the additive composite point query (两个同单位百分率指标的合计点查).
     * A plan that declares additive derived metrics must be exactly the point family shape —
     * POINT_QUERY/DIRECT with no time comparison, one organization, a single observation day,
     * only the organization dimension, the two operands as the only selected metrics, and no
     * filter/order/limit — because that is the only shape the compiler owns a template for. Any
     * other shape (or mixing additive with ratio derived metrics) is a guaranteed dead end and
     * fails closed with a repairable error instead of silently degrading into a near-miss family.
     */
    private void validateAdditiveCompositeFamilyShape(BankQueryPlan plan,
            List<ValidationError> errors) {
        List<BankQueryPlan.DerivedMetric> derived =
                safe(plan.getDerivedMetrics()).collect(Collectors.toList());
        if (derived.stream().noneMatch(BankQueryPlanValidator::isAdditiveDerivedMetric)) {
            return;
        }
        if (!derived.stream().allMatch(BankQueryPlanValidator::isAdditiveDerivedMetric)) {
            errors.add(error("UNSUPPORTED_DERIVED_SHAPE",
                    "additive composite derived metrics must not be mixed with ratio derived "
                            + "metrics in one plan"));
            return;
        }
        if (plan.getIntent() != BankIntentType.POINT_QUERY || plan.getCalculation() == null
                || plan.getCalculation().getType() != BankQueryPlan.CalculationType.DIRECT) {
            errors.add(error("ADDITIVE_FAMILY_INTENT_REQUIRED",
                    "additive composite metrics require intent=POINT_QUERY with "
                            + "calculation.type=DIRECT"));
        }
        BankQueryPlan.TimeRange time = plan.getTime();
        if (time == null || time.getComparison() != BankQueryPlan.TimeComparison.NONE
                || time.getStartDate() == null || !time.getStartDate().equals(time.getEndDate())) {
            errors.add(error("ADDITIVE_FAMILY_SINGLE_DAY_REQUIRED",
                    "additive composite metrics are a single-day point sum: time.comparison must "
                            + "be NONE and startDate must equal endDate"));
        }
        List<String> organizations = safe(plan.getOrganizations())
                .map(BankQueryPlan.Organization::getCode).filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (organizations.size() != 1) {
            errors.add(error("ADDITIVE_FAMILY_SINGLE_ORGANIZATION_REQUIRED",
                    "additive composite metrics require exactly one selected organization"));
        }
        if (!safe(plan.getDimensions()).filter(StringUtils::isNotBlank)
                .collect(Collectors.toList()).equals(List.of("bank_organization"))) {
            errors.add(error("ADDITIVE_FAMILY_ORGANIZATION_DIMENSION_REQUIRED",
                    "additive composite metrics require dimensions exactly "
                            + "[bank_organization]"));
        }
        List<String> metrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .filter(StringUtils::isNotBlank).toList();
        Set<String> expectedOperands = new LinkedHashSet<>();
        for (BankQueryPlan.DerivedMetric item : derived) {
            expectedOperands.add(item.getNumerator());
            expectedOperands.add(item.getDenominator());
        }
        if (metrics.size() != expectedOperands.size()
                || !new LinkedHashSet<>(metrics).equals(expectedOperands)) {
            errors.add(error("ADDITIVE_FAMILY_OPERANDS_REQUIRED",
                    "additive composite metrics require exactly the derived operands as the "
                            + "only selected metrics: " + expectedOperands));
        }
        if (safe(plan.getFilters()).findAny().isPresent()) {
            errors.add(error("ADDITIVE_FAMILY_FILTER_FORBIDDEN",
                    "additive composite point queries must not carry filters"));
        }
        if (safe(plan.getOrderBy()).findAny().isPresent()) {
            errors.add(error("ADDITIVE_FAMILY_NO_ORDER_REQUIRED",
                    "additive composite point queries must not carry ordering"));
        }
        if (plan.getLimit() != null) {
            errors.add(error("ADDITIVE_FAMILY_NO_LIMIT_REQUIRED",
                    "additive composite point queries must not carry a TopN limit"));
        }
    }

    /**
     * Duplicate selected metrics compile into identical aggregate aliases (SUM(x) AS x_value
     * twice) inside the multi-metric templates, which the executor rejects at run time.
     */
    private void validateNoDuplicateMetrics(BankQueryPlan plan, List<ValidationError> errors) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (BankQueryPlan.Metric metric : safe(plan.getMetrics())
                .collect(Collectors.toList())) {
            if (metric == null || StringUtils.isBlank(metric.getBizName())) {
                continue;
            }
            String code = metric.getBizName().toUpperCase(Locale.ROOT);
            if (!seen.add(code)) {
                duplicates.add(code);
            }
        }
        if (!duplicates.isEmpty()) {
            errors.add(error("DUPLICATE_METRIC",
                    "plan.metrics must not repeat a metric code (uppercase-normalized): "
                            + String.join(",", duplicates)
                            + "；重复指标会编译出同名聚合别名（SUM(x) AS x_value 出现两次），执行期必然失败；"
                            + "请去掉 metrics 中的重复项"));
        }
    }

    /**
     * Province-average threshold templates hardcode GROUP BY bank_organization; any extra
     * dimension would be silently dropped from the compiled rows and its output check bypassed.
     */
    private void validateThresholdBenchmarkDimensionGate(BankQueryPlan plan,
            List<ValidationError> errors) {
        if (plan.getIntent() != BankIntentType.THRESHOLD
                || safe(plan.getFilters()).noneMatch(this::isProvinceAverageBenchmark)) {
            return;
        }
        List<String> extraDimensions = safe(plan.getDimensions())
                .filter(StringUtils::isNotBlank)
                .filter(dimension -> !ORGANIZATION_DIMENSIONS.contains(dimension))
                .distinct().toList();
        if (!extraDimensions.isEmpty()) {
            errors.add(error("UNSUPPORTED_THRESHOLD_DIMENSION",
                    "province-average threshold templates group by bank_organization only; "
                            + "extra dimensions would be silently dropped: "
                            + String.join(",", extraDimensions)
                            + "；dimensions 只允许 [bank_organization] 或空，请移除其余维度"));
        }
    }

    /**
     * Family contract for the compound benchmark threshold (多指标复合基准阈值：每个所选指标各
     * 一条基准方向条件，结果按 AND 同时满足). The plan declares one exact benchmark filter plus
     * one per-metric direction condition {@code field=<ZB###>, operator=GT|GTE|LT|LTE,
     * value=PROVINCE_AVERAGE} per selected metric. The catalog owns the compiled sign
     * (higher-better metrics meet above the average, lower-better below), so a condition whose
     * operator contradicts the metric's catalog direction is a repairable model error instead of
     * a silent inversion. The family scans the full organization population without a baseline
     * comparison, ordering or TopN slice.
     */
    private void validateCompoundBenchmarkFamilyShape(BankQueryPlan plan,
            List<ValidationError> errors) {
        List<BankQueryPlan.Filter> conditions = safe(plan.getFilters())
                .filter(BankQueryPlanValidator::looksLikeMetricBenchmarkCondition).toList();
        if (conditions.isEmpty()) {
            return;
        }
        for (BankQueryPlan.Filter condition : conditions) {
            if (!isMetricBenchmarkCondition(condition)) {
                errors.add(error("COMPOUND_BENCHMARK_METRIC_UNKNOWN",
                        "compound benchmark condition field must be a registered ZB### catalog "
                                + "metric, got field=" + condition.getField()
                                + "；合法组合：一个 benchmark/COMPARE/PROVINCE_AVERAGE 基准对象，"
                                + "外加每个所选指标各一条 {\"field\":\"<ZB###>\","
                                + "\"operator\":\"GT|GTE|LT|LTE\",\"value\":\"PROVINCE_AVERAGE\","
                                + "\"values\":[]} 基准方向条件"));
            }
        }
        if (plan.getIntent() != BankIntentType.THRESHOLD) {
            errors.add(error("COMPOUND_BENCHMARK_INTENT_REQUIRED",
                    "compound benchmark conditions require intent=THRESHOLD, got="
                            + (plan.getIntent() == null ? "null" : plan.getIntent().name())));
        }
        List<String> metrics = selectedMetricCodes(plan);
        if (metrics.size() < 2) {
            errors.add(error("COMPOUND_BENCHMARK_METRICS_REQUIRED",
                    "compound benchmark AND requires at least two selected metrics, got="
                            + metrics.size()
                            + "；单指标高于/低于全省均值属于单指标 threshold 族，不要追加逐指标基准条件"));
        }
        if (plan.getCalculation() == null
                || plan.getCalculation().getType() != BankQueryPlan.CalculationType.DIRECT) {
            errors.add(error("COMPOUND_BENCHMARK_DIRECT_CALCULATION_REQUIRED",
                    "compound benchmark threshold requires calculation.type=DIRECT"));
        }
        if (plan.getTime() != null && plan.getTime().getComparison() != null
                && plan.getTime().getComparison() != BankQueryPlan.TimeComparison.NONE) {
            errors.add(error("COMPOUND_BENCHMARK_NO_COMPARISON_REQUIRED",
                    "compound benchmark threshold compares a single observation window; "
                            + "time.comparison must be NONE"));
        }
        if (safe(plan.getDerivedMetrics()).findAny().isPresent()) {
            errors.add(error("COMPOUND_BENCHMARK_DERIVED_METRIC_FORBIDDEN",
                    "compound benchmark conditions apply to plain catalog metrics only; "
                            + "remove every derived metric"));
        }
        Set<String> metricKeys = metrics.stream()
                .map(metric -> metric.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> conditionFields = conditions.stream()
                .map(BankQueryPlan.Filter::getField).filter(StringUtils::isNotBlank)
                .map(field -> field.toUpperCase(Locale.ROOT)).toList();
        for (String metricKey : metricKeys) {
            long count = conditionFields.stream().filter(metricKey::equals).count();
            if (count != 1) {
                errors.add(error("COMPOUND_BENCHMARK_CONDITION_UNPAIRED",
                        "compound benchmark conditions must pair one-to-one with the selected "
                                + "metrics: metric " + metricKey + " has " + count
                                + " benchmark conditions; declare exactly one {field=" + metricKey
                                + ", operator=GT|GTE|LT|LTE, value=PROVINCE_AVERAGE} per selected "
                                + "metric"));
            }
        }
        for (String field : conditionFields) {
            if (!metricKeys.contains(field)) {
                errors.add(error("COMPOUND_BENCHMARK_CONDITION_UNPAIRED",
                        "compound benchmark condition field " + field
                                + " is not a selected metric; conditions may only reference "
                                + "plan.metrics " + metricKeys + "，禁止为未选指标声明基准条件"));
            }
        }
        for (BankQueryPlan.Filter condition : conditions) {
            if (!isMetricBenchmarkCondition(condition)) {
                continue;
            }
            String code = condition.getField().toUpperCase(Locale.ROOT);
            BankSemanticRegistry.MetricDefinition definition =
                    BankSemanticRegistry.metrics().get(code);
            if (definition == null) {
                continue;
            }
            boolean declaredHigher = "GT".equals(condition.getOperator())
                    || "GTE".equals(condition.getOperator());
            boolean catalogHigher =
                    definition.direction() != BankSemanticRegistry.Direction.LOWER_BETTER;
            if (declaredHigher != catalogHigher) {
                errors.add(error("COMPOUND_BENCHMARK_DIRECTION_CONFLICT",
                        "benchmark condition " + condition.getField() + " "
                                + condition.getOperator() + " conflicts with the catalog direction "
                                + definition.direction() + " (" + definition.name()
                                + ")；编译符号由目录方向决定：higher-better 指标用 GT/GTE"
                                + "（高于全省均值），lower-better 指标用 LT/LTE（低于全省均值），"
                                + "请按目录方向修正该条件"));
            }
        }
        if (safe(plan.getFilters()).anyMatch(this::isProvinceAverageDirection)) {
            errors.add(error("COMPOUND_BENCHMARK_GLOBAL_DIRECTION_FORBIDDEN",
                    "compound benchmark conditions replace the single metric_value direction "
                            + "object; remove field=metric_value and keep exactly one condition "
                            + "per selected metric"));
        }
        if (safe(plan.getFilters()).anyMatch(filter -> "metric_value".equals(filter.getField())
                && !isProvinceAverageDirection(filter))) {
            errors.add(error("COMPOUND_BENCHMARK_METRIC_FILTER_FORBIDDEN",
                    "compound benchmark threshold must not carry a numeric metric_value filter"));
        }
        if (safe(plan.getOrganizations()).map(BankQueryPlan.Organization::getCode)
                .filter(StringUtils::isNotBlank).findAny().isPresent()) {
            errors.add(error("COMPOUND_BENCHMARK_POPULATION_REQUIRED",
                    "compound benchmark threshold scans every organization; organizations must "
                            + "be empty so the benchmark average and the meets_condition flag "
                            + "cover the full population"));
        }
        List<String> dimensions = safe(plan.getDimensions()).filter(StringUtils::isNotBlank)
                .toList();
        if (!dimensions.equals(List.of("bank_organization"))) {
            errors.add(error("COMPOUND_BENCHMARK_DIMENSION_REQUIRED",
                    "compound benchmark threshold dimensions must be exactly "
                            + "[bank_organization]"));
        }
        if (safe(plan.getOrderBy()).findAny().isPresent()) {
            errors.add(error("COMPOUND_BENCHMARK_NO_ORDER_REQUIRED",
                    "compound benchmark ordering is compiler-owned; set orderBy to []"));
        }
        if (plan.getLimit() != null) {
            errors.add(error("COMPOUND_BENCHMARK_NO_LIMIT_REQUIRED",
                    "compound benchmark threshold returns the full population; limit must be "
                            + "null"));
        }
    }

    /**
     * A THRESHOLD plan without any anchor (province-average benchmark, metric_value direction, or
     * a numeric metric_value threshold) silently degrades to the generic direct route and loses
     * the threshold semantics; fail closed instead so repair can restore the anchor.
     */
    private void validateThresholdAnchor(BankQueryPlan plan, List<ValidationError> errors) {
        if (plan.getIntent() != BankIntentType.THRESHOLD) {
            return;
        }
        boolean hasBenchmark = safe(plan.getFilters()).anyMatch(this::isProvinceAverageBenchmark);
        boolean hasMetricValueFilter = safe(plan.getFilters())
                .anyMatch(filter -> "metric_value".equals(filter.getField()));
        if (!hasBenchmark && !hasMetricValueFilter) {
            errors.add(error("THRESHOLD_UNANCHORED",
                    "threshold plans require an anchor: a benchmark COMPARE/PROVINCE_AVERAGE "
                            + "filter or a metric_value direction/threshold filter"
                            + "；无锚点的 THRESHOLD 会静默降级 GENERIC_DIRECT 丢失阈值语义，"
                            + "请补齐 benchmark 或 metric_value 过滤项"));
        }
    }

    /**
     * A province-wide CHANGE (no selected organization) without the organization dimension has no
     * per-organization key left for the change projection; the projector can never apply, so the
     * plan is a guaranteed dead end regardless of any TopN request.
     */
    private void validateChangePopulationDimension(BankQueryPlan plan,
            List<ValidationError> errors) {
        if (plan.getIntent() != BankIntentType.CHANGE || plan.getCalculation() == null
                || plan.getCalculation().getType() != BankQueryPlan.CalculationType.CHANGE) {
            return;
        }
        boolean hasOrganization = safe(plan.getOrganizations())
                .map(BankQueryPlan.Organization::getCode).anyMatch(StringUtils::isNotBlank);
        if (hasOrganization
                || safe(plan.getDimensions()).anyMatch(ORGANIZATION_DIMENSIONS::contains)) {
            return;
        }
        errors.add(error("CHANGE_POPULATION_DIMENSION_REQUIRED",
                "province-wide CHANGE requires the bank_organization dimension so the compiled "
                        + "rows keep an organization identity; without it the result projection "
                        + "can never apply"
                        + "；请在 dimensions 中加入 bank_organization（或显式选择机构）"));
    }

    /**
     * The trend contract projects a quarter-end point series; plans with any other granularity
     * would have their non-quarter rows silently discarded by the projection.
     */
    /**
     * Trend answers compile into a quarter-end point series: rows whose dates are not quarter
     * ends are dropped by the contract. A window covering fewer than two quarter ends would
     * collapse to a single point, so such plans are rejected instead of silently degrading. The
     * plan granularity itself is irrelevant here — real models emit quarter-end point windows
     * with DAY granularity and the series contract selects the quarter ends from the window.
     */
    private void validateTrendQuarterEndWindow(BankQueryPlan plan,
            List<ValidationError> errors) {
        if (plan.getIntent() != BankIntentType.TREND || plan.getTime() == null
                || plan.getTime().getStartDate() == null || plan.getTime().getEndDate() == null) {
            return;
        }
        LocalDate windowStart = plan.getTime().getStartDate();
        LocalDate windowEnd = plan.getTime().getEndDate();
        int quarterEnds = 0;
        for (LocalDate month = windowStart.withDayOfMonth(1);
                !month.isAfter(windowEnd); month = month.plusMonths(1)) {
            int monthValue = month.getMonthValue();
            if (monthValue == 3 || monthValue == 6 || monthValue == 9 || monthValue == 12) {
                LocalDate monthEnd = month.withDayOfMonth(month.lengthOfMonth());
                if (!monthEnd.isBefore(windowStart) && !monthEnd.isAfter(windowEnd)) {
                    quarterEnds++;
                }
            }
        }
        if (quarterEnds < 2) {
            errors.add(error("UNSUPPORTED_TREND_WINDOW",
                    "trend answers are compiled as a quarter-end point series; the time window "
                            + "must cover at least two quarter-end dates, window="
                            + windowStart + ".." + windowEnd + " covers " + quarterEnds
                            + "；窗口内季末点不足两个时趋势会坍缩为单点，请扩大窗口或改用其他意图"));
        }
    }

    /**
     * Percent-unit metrics must never be SUM-aggregated across a multi-day window: the compiled
     * sum of percentages is meaningless. AVG (period-average semantics) and point-day windows stay
     * legal, so every daily-average template family keeps passing. COUNT_DAYS plans are exempt:
     * the days-above family aggregates per day and never sums across the window.
     */
    private void validatePercentMetricRangeAggregation(BankQueryPlan plan,
            List<ValidationError> errors) {
        BankQueryPlan.TimeRange time = plan.getTime();
        if (time == null || time.getStartDate() == null || time.getEndDate() == null
                || !time.getStartDate().isBefore(time.getEndDate())
                || plan.getCalculation() != null && plan.getCalculation().getType()
                        == BankQueryPlan.CalculationType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE) {
            return;
        }
        List<String> offenders = new ArrayList<>();
        for (BankQueryPlan.Metric metric : safe(plan.getMetrics())
                .collect(Collectors.toList())) {
            if (metric == null || StringUtils.isBlank(metric.getBizName())
                    || metric.getAggregation() != BankQueryPlan.Aggregation.DEFAULT
                            && metric.getAggregation() != BankQueryPlan.Aggregation.SUM) {
                continue;
            }
            BankSemanticRegistry.MetricDefinition definition = BankSemanticRegistry.metrics()
                    .get(metric.getBizName().toUpperCase(Locale.ROOT));
            if (definition != null && "%".equals(definition.unit())) {
                offenders.add(definition.code() + "(" + metric.getAggregation() + ")");
            }
        }
        if (!offenders.isEmpty()) {
            errors.add(error("PERCENT_METRIC_RANGE_SUM",
                    "percent-unit metrics must not be summed across a date range: "
                            + String.join(",", offenders)
                            + "；区间求和会把百分数相加，结果必然错误；请改用 AVG（日均/期间均值语义），"
                            + "或将窗口收窄为单日"));
        }
    }

    private void validateOrderingAndLimit(BankQueryPlan plan, SemanticIntentHints hints,
            List<ValidationError> errors) {
        List<BankQueryPlan.OrderBy> orderBy = safe(plan.getOrderBy()).collect(Collectors.toList());
        boolean compilerOwnedRankingOrder = usesCompilerOwnedRankingOrder(plan);
        if (plan.getIntent() == BankIntentType.RANKING && orderBy.isEmpty()
                && !compilerOwnedRankingOrder) {
            errors.add(error("RANKING_ORDER_REQUIRED", "ranking requires explicit sort direction"));
        }
        if (plan.getIntent() == BankIntentType.CHANGE && !orderBy.isEmpty()) {
            errors.add(error("CHANGE_RESULT_ORDER_FORBIDDEN",
                    "CHANGE result ordering is compiler-owned; set orderBy to [] and do not use "
                            + "percent_change, current_value, baseline_value, or absolute_change"));
        }
        if (plan.getCalculation() != null
                && plan.getCalculation().getType() == BankQueryPlan.CalculationType.RATIO
                && !orderBy.isEmpty()) {
            errors.add(error("RATIO_RESULT_ORDER_FORBIDDEN",
                    "RATIO result ordering is compiler-owned; set orderBy to []"));
        }
        Set<String> selectedFields = Stream
                .concat(selectedMetricCodes(plan).stream(), selectedDimensions(plan).stream())
                .collect(Collectors.toSet());
        if (plan.getIntent() != BankIntentType.CHANGE) {
            for (BankQueryPlan.OrderBy order : orderBy) {
                if (StringUtils.isBlank(order.getField()) || order.getDirection() == null
                        || (!BankSemanticRegistry.metricCodes().contains(order.getField())
                                && !BankSemanticRegistry.dimensions().contains(order.getField()))
                        || !selectedFields.contains(order.getField())) {
                    errors.add(error("INVALID_ORDER_BY",
                            "orderBy.field must be one selected metric code "
                                    + selectedMetricCodes(plan)
                                    + " or one selected dimension "
                                    + selectedDimensions(plan)
                                    + "; do not use display names, metric_value, aggregate_value, "
                                    + "rank, or other result/physical fields; direction must be ASC or DESC"));
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
                && !(plan.getIntent() == BankIntentType.CHANGE && plan.getTime() != null && plan
                        .getTime()
                        .getComparison() == BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD)) {
            errors.add(error("LIMIT_MISMATCH", "plan must preserve the recognized TopN limit"));
        }
    }

    /**
     * Derived-metric ranking is compiled as one full-population ranking per metric. Its direction
     * comes from the semantic catalog (and derived-ratio definition), so one model-provided
     * {@code orderBy} cannot represent mixed higher-is-better and lower-is-better metrics.
     */
    private boolean usesCompilerOwnedRankingOrder(BankQueryPlan plan) {
        return plan.getIntent() == BankIntentType.RANKING
                && safe(plan.getDerivedMetrics()).findAny().isPresent();
    }

    private List<String> selectedMetricCodes(BankQueryPlan plan) {
        return safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .filter(StringUtils::isNotBlank).toList();
    }

    private List<String> selectedDimensions(BankQueryPlan plan) {
        return safe(plan.getDimensions()).filter(StringUtils::isNotBlank).toList();
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
        return top != null && bottom != null && Objects.equals(plan.getLimit(), top + bottom)
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
        Set<String> dimensions =
                safe(plan.getDimensions()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!containsAllIgnoreCase(output, dimensions)) {
            errors.add(error("OUTPUT_MISSING_DIMENSION",
                    "output must retain every requested dimension"));
        }
        Set<String> selected =
                Stream.concat(metrics.stream(), dimensions.stream()).collect(Collectors.toSet());
        for (String column : output) {
            if (!containsIgnoreCase(selected, column)) {
                errors.add(error("OUTPUT_EXTRA_COLUMN",
                        "output must not contain valid but unselected fields: " + column));
            }
        }
    }

    /**
     * Point-query 存贷比 / structure share is often recognized with derived-metric evidence, but the
     * deterministic RATIO template expresses the same operands as direct metrics + baseline.
     */
    private boolean ratioPlanCoversRequiredDerived(BankQueryPlan plan,
            List<SemanticIntentHints.DerivedMetricSpec> required) {
        if (!isRatioPlan(plan) || required == null || required.isEmpty()) {
            return false;
        }
        List<String> planMetrics = safe(plan.getMetrics()).map(BankQueryPlan.Metric::getBizName)
                .filter(StringUtils::isNotBlank).collect(Collectors.toList());
        return required.stream().allMatch(spec -> containsIgnoreCase(planMetrics, spec.numerator())
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

    private boolean containsAllIgnoreCase(Collection<String> haystack, Collection<String> needles) {
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
