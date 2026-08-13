package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.Aggregator;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.Order;
import com.tencent.supersonic.common.pojo.enums.AggOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.DatePeriodEnum;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.QueryType;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaValueMap;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Converts a validated bank query plan into a semantic request without accepting model-owned SQL or
 * physical identifiers. The compiler is intentionally stateless so equivalent plans always produce
 * equivalent output.
 */
public class BankQueryPlanCompiler {

    private static final String ORGANIZATION_DIMENSION = "bank_organization";
    private static final String TIME_DIMENSION = "bank_data_date";
    private static final int DAILY_AVERAGE_RANKING_MAX_LIMIT = 10_000;

    private final BankQueryPlanValidator validator;
    private final BankS2SqlTemplateFactory templateFactory;

    public BankQueryPlanCompiler() {
        this(new BankQueryPlanValidator(), new BankS2SqlTemplateFactory());
    }

    BankQueryPlanCompiler(BankQueryPlanValidator validator,
            BankS2SqlTemplateFactory templateFactory) {
        this.validator = validator;
        this.templateFactory = templateFactory;
    }

    public CompiledQuery compile(BankQueryPlan plan, SemanticIntentHints hints,
            LLMReq.LLMSchema schema) {
        BankQueryPlanValidator.ValidationResult validation = validator.validate(plan, hints);
        if (!validation.isValid()) {
            throw new BankPlanCompilationException(BankPlanCompilationException.Reason.INVALID_PLAN,
                    validation.summary());
        }
        if (plan.getAction() == BankQueryPlan.PlanAction.CLARIFY) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.CLARIFICATION_REQUIRED,
                    "clarification plans must not be compiled for execution");
        }
        if (schema == null) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.SCHEMA_REQUIRED,
                    "semantic schema is required for compilation");
        }
        if (schema.getDataSetId() == null || StringUtils.isBlank(schema.getDataSetName())) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.DATASET_REQUIRED,
                    "semantic dataset identity is required for compilation");
        }

        SchemaIndex index = new SchemaIndex(schema);
        List<ResolvedMetric> metrics = resolveMetrics(plan, index);
        List<ResolvedDimension> dimensions = resolveDimensions(plan, index);
        List<String> outputColumns = verifyOutputOrder(plan, metrics, dimensions, index);
        List<Filter> dimensionFilters = compileDimensionFilters(plan, index, metrics);
        List<Filter> executionDimensionFilters = executionDimensionFilters(plan, dimensions,
                dimensionFilters);
        List<Filter> metricFilters = compileMetricFilters(plan, index, metrics);
        BankS2SqlTemplateFactory.TemplateContext templateContext =
                new BankS2SqlTemplateFactory.TemplateContext(plan, schema.getDataSetName(),
                        metrics.stream()
                                .map(metric -> new BankS2SqlTemplateFactory.ResolvedMetric(
                                        metric.identifier(),
                                        metricCode(metric.schemaElement())))
                                .collect(Collectors.toList()),
                        dimensions.stream().map(ResolvedDimension::identifier).toList(),
                        dateField(index.partitionTime()), executionDimensionFilters, metricFilters);

        boolean directCalculation = plan.getCalculation().getType() == BankQueryPlan.CalculationType.DIRECT
                || plan.getCalculation().getType() == BankQueryPlan.CalculationType
                        .COUNT_DAYS_ABOVE_PROVINCE_AVERAGE;
        if (directCalculation
                && plan.getTime().getComparison() == BankQueryPlan.TimeComparison.NONE) {
            if (!plan.getDerivedMetrics().isEmpty()) {
                return CompiledQuery.s2sql(
                        templateFactory.compileDerivedMetricRanking(templateContext),
                        List.of("metric_code", ORGANIZATION_DIMENSION, "metric_value",
                                "rank_position"),
                        derivedRankingResultContract(plan, index));
            }
            if (plan.getCalculation().getType() == BankQueryPlan.CalculationType
                    .COUNT_DAYS_ABOVE_PROVINCE_AVERAGE) {
                if (plan.getIntent() == BankIntentType.AGGREGATION) {
                    return CompiledQuery.s2sql(
                            templateFactory.compileDaysAboveProvinceAverage(templateContext),
                            List.of(ORGANIZATION_DIMENSION, "days_above_province_average",
                                    "observation_count", "above_ratio_percent"),
                            daysAboveProvinceAverageResultContract(plan, metrics, index));
                }
                throw new BankPlanCompilationException(
                        BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                        "days-above-province-average count requires an aggregation intent");
            }
            if (hasProvinceAverageBenchmark(plan)) {
                if (plan.getIntent() == BankIntentType.THRESHOLD
                        || plan.getIntent() == BankIntentType.COMPARISON && metrics.size() > 1) {
                    boolean multi = metrics.size() > 1;
                    return CompiledQuery.s2sql(
                            multi ? templateFactory
                                    .compileMultiMetricProvinceAverageAggregation(templateContext)
                                    : templateFactory.compileProvinceAverageThreshold(templateContext),
                            multi ? aggregationSummaryOutputColumns(metrics)
                                    : List.of(ORGANIZATION_DIMENSION, "metric_value",
                                            "provincial_average", "meets_condition"),
                            multi ? multiMetricProvinceAverageResultContract(plan, metrics, index)
                                    : provinceAverageThresholdResultContract(plan, metrics, index));
                }
                if (plan.getIntent() == BankIntentType.AGGREGATION) {
                    return CompiledQuery.s2sql(
                            templateFactory.compileProvinceAverageAggregation(templateContext),
                            multiMetricProvinceAverageOutputColumns(metrics),
                            multiMetricProvinceAverageResultContract(plan, metrics, index));
                }
                throw new BankPlanCompilationException(
                        BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                        "province-average benchmarks require a threshold, aggregation, or "
                                + "multi-metric comparison intent");
            }
            if (requiresAbsoluteThreshold(plan, metrics, dimensions, metricFilters)) {
                return CompiledQuery.s2sql(
                        templateFactory.compileAbsoluteThreshold(templateContext),
                        List.of(ORGANIZATION_DIMENSION, "metric_value", "meets_condition"),
                        absoluteThresholdResultContract(plan, metrics, index));
            }
            if (requiresDailyAggregationSummary(plan, metrics, dimensions, metricFilters)) {
                    return CompiledQuery.s2sql(
                            templateFactory.compileDailyAggregationSummary(templateContext),
                            aggregationSummaryOutputColumns(metrics),
                            aggregationSummaryResultContract(plan, metrics, index));
            }
            if (requiresOrganizationComparisonTemplate(plan, metrics, dimensions, metricFilters)) {
                return CompiledQuery.s2sql(
                        templateFactory.compileOrganizationComparison(templateContext,
                                dimensionFilters.get(0)),
                        calculatedOutputColumns(dimensions, "metric_value"),
                        comparisonResultContract(plan, metrics, dimensions, index));
            }
            if (requiresDailyAverageRankingTemplate(plan, metrics, dimensions, metricFilters)) {
                return dailyAverageRanking(plan, schema, metrics, dimensions,
                        executionDimensionFilters, metricFilters, index.partitionTime(), index);
            }
            return direct(plan, schema, metrics, dimensions, executionDimensionFilters, metricFilters,
                    outputColumns, index.partitionTime(), index);
        }
        return switch (plan.getCalculation().getType()) {
            case CHANGE -> {
                boolean monthAndYear =
                        plan.getTime().getComparison() == BankQueryPlan.TimeComparison.MOM_AND_YOY;
                List<String> changeDimensions = new ArrayList<>(templateContext.dimensions());
                if (!monthAndYear && metrics.size() > 1 && !plan.getOrganizations().isEmpty()
                        && !changeDimensions.contains(ORGANIZATION_DIMENSION)) {
                    // Long-form multi-metric projection requires an organization key even for a
                    // single selected bank. Keeping it in SELECT/GROUP BY also makes the physical
                    // translator retain the dimension used by the organization predicate.
                    changeDimensions.add(0, ORGANIZATION_DIMENSION);
                }
                BankS2SqlTemplateFactory.TemplateContext changeContext =
                        new BankS2SqlTemplateFactory.TemplateContext(templateContext.plan(),
                                templateContext.dataSetName(), templateContext.metrics(),
                                List.copyOf(changeDimensions), templateContext.dateField(),
                                templateContext.dimensionFilters(), templateContext.metricFilters());
                yield CompiledQuery.s2sql(
                        monthAndYear ? templateFactory.compileMonthAndYearChange(changeContext)
                                : templateFactory.compileChange(changeContext),
                        metrics.size() == 1
                                ? calculatedOutputColumns(dimensions, "current_value",
                                        "baseline_value", "absolute_change", "percent_change")
                                : Stream.concat(changeDimensions.stream(),
                                        Stream.concat(Stream.of(dateField(index.partitionTime())),
                                                java.util.stream.IntStream.range(0, metrics.size())
                                                        .mapToObj(i -> "metric_value_" + i)))
                                        .toList(),
                        monthAndYear ? BankResultProjector.Contract.builder()
                                .type(BankResultProjector.ProjectionType.MOM_YOY_CHANGE).build()
                                // Single-org scalar change keeps raw columns; multi-org/metric
                                // projects long-form org_code/metric_code contract.
                                : (plan.getOrganizations().size() == 1 && metrics.size() == 1
                                        && plan.getDimensions().isEmpty()
                                                ? null
                                                : multiMetricChangeResultContract(plan, index)));
            }
            case RATIO -> {
                ResolvedMetric denominator = ratioDenominator(plan, metrics);
                double ratioScale = ratioScale(metrics, denominator);
                BankResultProjector.Contract ratioContract =
                        ratioResultContract(plan, metrics, dimensions, index);
                yield CompiledQuery.s2sql(
                        templateFactory.compileRatio(templateContext, metrics.get(0).identifier(),
                                denominator.identifier(), ratioScale),
                        calculatedOutputColumns(dimensions, "numerator_value", "denominator_value",
                                "ratio_percent"),
                        ratioContract);
            }
            case DIRECT -> throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "time comparison requires a supported calculation type");
            case COUNT_DAYS_ABOVE_PROVINCE_AVERAGE -> throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "days-above-province-average count requires a NONE time comparison");
        };
    }

    private boolean hasProvinceAverageBenchmark(BankQueryPlan plan) {
        return plan.getFilters().stream()
                .anyMatch(filter -> "benchmark".equals(filter.getField())
                        && "COMPARE".equals(filter.getOperator())
                        && "PROVINCE_AVERAGE".equals(filter.getValue()));
    }

    /** Selects the business unit scale for a controlled ratio family. */
    private double ratioScale(List<ResolvedMetric> metrics, ResolvedMetric denominator) {
        if (metrics == null || metrics.size() < 1 || denominator == null) {
            return 100.0;
        }
        String num = metricCode(metrics.get(0).schemaElement());
        String den = metricCode(denominator.schemaElement());
        if ("ZB001".equalsIgnoreCase(num) && "ZB019".equalsIgnoreCase(den)) {
            // Deposits are stored in 亿元 while outlet_count is a count: convert to 万元/outlet.
            return 10000.0;
        }
        if ("ZB011".equalsIgnoreCase(num) && "ZB018".equalsIgnoreCase(den)) {
            // Net profit / employee count is already 万元/person and is not a percentage.
            return 1.0;
        }
        return 100.0;
    }

    private List<String> calculatedOutputColumns(List<ResolvedDimension> dimensions,
            String... calculationColumns) {
        return Stream.concat(dimensions.stream().map(ResolvedDimension::identifier),
                Stream.of(calculationColumns)).toList();
    }

    private boolean requiresOrganizationComparisonTemplate(BankQueryPlan plan,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions,
            List<Filter> metricFilters) {
        return plan.getIntent() == BankIntentType.COMPARISON && plan.getOrganizations().size() > 1
                && metrics.size() == 1 && metricFilters.isEmpty()
                && dimensions.stream().map(ResolvedDimension::identifier)
                        .anyMatch(ORGANIZATION_DIMENSION::equals);
    }

    private boolean requiresDailyAverageRankingTemplate(BankQueryPlan plan,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions,
            List<Filter> metricFilters) {
        return plan.getIntent() == BankIntentType.RANKING && metrics.size() == 1
                && metrics.get(0).planMetric().getAggregation() == BankQueryPlan.Aggregation.AVG
                && metricFilters.isEmpty() && dimensions.stream().map(ResolvedDimension::identifier)
                        .toList().equals(List.of(ORGANIZATION_DIMENSION));
    }

    private boolean requiresDailyAggregationSummary(BankQueryPlan plan,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions,
            List<Filter> metricFilters) {
        // size 0 = province-wide annual extrema (all institutions); size 1 = single-org summary.
        return plan.getIntent() == BankIntentType.AGGREGATION && plan.getOrganizations().size() <= 1
                && !metrics.isEmpty()
                && metrics.stream()
                        .allMatch(metric -> metric.planMetric()
                                .getAggregation() == BankQueryPlan.Aggregation.AVG)
                && metricFilters.isEmpty() && dimensions.stream().map(ResolvedDimension::identifier)
                        .toList().equals(List.of(ORGANIZATION_DIMENSION));
    }

    private boolean requiresAbsoluteThreshold(BankQueryPlan plan, List<ResolvedMetric> metrics,
            List<ResolvedDimension> dimensions, List<Filter> metricFilters) {
        return plan.getIntent() == BankIntentType.THRESHOLD && plan.getOrganizations().size() == 1
                && metrics.size() == 1 && metricFilters.size() == 1
                && dimensions.stream().map(ResolvedDimension::identifier).toList()
                        .equals(List.of(ORGANIZATION_DIMENSION));
    }

    private List<Filter> executionDimensionFilters(BankQueryPlan plan,
            List<ResolvedDimension> dimensions, List<Filter> dimensionFilters) {
        if (!ranksSelectedOrganization(plan, dimensions)) {
            return dimensionFilters;
        }
        return dimensionFilters.stream()
                .filter(filter -> !ORGANIZATION_DIMENSION.equals(filter.getBizName())).toList();
    }

    private boolean ranksSelectedOrganization(BankQueryPlan plan,
            List<ResolvedDimension> dimensions) {
        return plan.getIntent() == BankIntentType.RANKING && !plan.getOrganizations().isEmpty()
                && dimensions.stream().map(ResolvedDimension::identifier)
                        .anyMatch(ORGANIZATION_DIMENSION::equals);
    }

    private BankResultProjector.Contract comparisonResultContract(BankQueryPlan plan,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions, SchemaIndex index) {
        BankResultProjector.Contract directContract =
                resultContract(plan, metrics, dimensions, index);
        if (directContract == null || metrics.size() != 1) {
            return directContract;
        }
        return BankResultProjector.Contract.builder().type(directContract.getType())
                .organizationColumn(directContract.getOrganizationColumn())
                .organizationNames(new LinkedHashMap<>(directContract.getOrganizationNames()))
                .selectedOrganizationCodes(
                        List.copyOf(directContract.getSelectedOrganizationCodes()))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("metric_value")
                                .metricCode(metricCode(metrics.get(0).schemaElement())).build()))
                .build();
    }

    private BankResultProjector.Contract multiMetricChangeResultContract(BankQueryPlan plan,
            SchemaIndex index) {
        // Province-wide growth change uses empty organizations; still need the org dimension map.
        SchemaElement organization = organizationDimension(plan, index);
        Map<String, String> organizationNames = new LinkedHashMap<>();
        if (organization.getSchemaValueMaps() != null) {
            for (SchemaValueMap valueMap : organization.getSchemaValueMaps()) {
                if (valueMap != null && StringUtils.isNotBlank(valueMap.getTechName())
                        && StringUtils.isNotBlank(valueMap.getBizName())) {
                    organizationNames.put(valueMap.getTechName(), valueMap.getBizName());
                }
            }
        }
        List<BankResultProjector.MetricBinding> metricBindings = new ArrayList<>();
        for (int metricIndex = 0; metricIndex < plan.getMetrics().size(); metricIndex++) {
            BankQueryPlan.Metric metric = plan.getMetrics().get(metricIndex);
            metricBindings.add(BankResultProjector.MetricBinding.builder()
                    .semanticColumn("metric_value_" + metricIndex)
                    .metricCode(StringUtils.upperCase(metric.getBizName())).build());
        }
        return BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.MULTI_METRIC_CHANGE)
                .organizationColumn(identifier(organization)).organizationNames(organizationNames)
                .timeColumn(identifier(index.partitionTime()))
                .selectedDates(List.of(plan.getTime().getStartDate().toString(),
                        plan.getTime().getEndDate().toString(),
                        plan.getTime().getBaselineStartDate().toString(),
                        plan.getTime().getBaselineEndDate().toString()))
                .selectedOrganizationCodes(
                        plan.getOrganizations().stream().map(BankQueryPlan.Organization::getCode)
                                .filter(StringUtils::isNotBlank).sorted().toList())
                .metrics(metricBindings).build();
    }

    private BankResultProjector.Contract provinceAverageThresholdResultContract(BankQueryPlan plan,
            List<ResolvedMetric> metrics, SchemaIndex index) {
        List<BankResultProjector.MetricBinding> bindings = metrics.stream()
                .map(metric -> BankResultProjector.MetricBinding.builder()
                        .semanticColumn("metric_value")
                        .metricCode(metricCode(metric.schemaElement())).build())
                .toList();
        return provinceAverageContract(plan, index,
                BankResultProjector.ProjectionType.PROVINCIAL_AVERAGE_THRESHOLD, bindings);
    }

    private BankResultProjector.Contract multiMetricProvinceAverageResultContract(
            BankQueryPlan plan, List<ResolvedMetric> metrics, SchemaIndex index) {
        List<BankResultProjector.MetricBinding> bindings = metrics.stream()
                .map(metric -> BankResultProjector.MetricBinding.builder()
                        .semanticColumn("metric_value")
                        .metricCode(metricCode(metric.schemaElement())).build())
                .sorted(java.util.Comparator
                        .comparing(BankResultProjector.MetricBinding::getMetricCode))
                .toList();
        return provinceAverageContract(plan, index,
                BankResultProjector.ProjectionType.MULTI_METRIC_PROVINCIAL_AVERAGE, bindings);
    }

    /**
     * The auditable projection contract for the per-day province-average comparison. The fixed
     * output column order org_code, org_name, days_above_province_average, observation_count,
     * above_ratio_percent is shared verbatim by the projector and any downstream golden-label
     * generator.
     */
    private BankResultProjector.Contract daysAboveProvinceAverageResultContract(BankQueryPlan plan,
            List<ResolvedMetric> metrics, SchemaIndex index) {
        if (metrics.size() != 1) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "days-above-province-average count requires exactly one metric");
        }
        return provinceAverageContract(plan, index,
                BankResultProjector.ProjectionType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE,
                List.of(BankResultProjector.MetricBinding.builder()
                        .semanticColumn("days_above_province_average")
                        .metricCode(metricCode(metrics.get(0).schemaElement())).build()));
    }

    private BankResultProjector.Contract absoluteThresholdResultContract(BankQueryPlan plan,
            List<ResolvedMetric> metrics, SchemaIndex index) {
        return provinceAverageContract(plan, index,
                BankResultProjector.ProjectionType.ABSOLUTE_THRESHOLD,
                List.of(BankResultProjector.MetricBinding.builder().semanticColumn("metric_value")
                        .metricCode(metricCode(metrics.get(0).schemaElement())).build()));
    }

    private BankResultProjector.Contract aggregationSummaryResultContract(BankQueryPlan plan,
            List<ResolvedMetric> metrics, SchemaIndex index) {
        if (metrics.isEmpty()) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "daily aggregation summary requires at least one metric");
        }
        BankResultProjector.ProjectionType type = isDailyExtremaOrgPlan(plan, metrics)
                ? BankResultProjector.ProjectionType.DAILY_EXTREMA_ORG
                : BankResultProjector.ProjectionType.AGGREGATION_SUMMARY;
        if (metrics.size() == 1) {
            BankResultProjector.Contract contract = provinceAverageContract(plan, index, type,
                    List.of(BankResultProjector.MetricBinding.builder()
                            .semanticColumn("aggregate_value")
                            .metricCode(metricCode(metrics.get(0).schemaElement())).build()));
            contract.setDailyAverageOnly(plan.getOutput() != null && plan.getOutput()
                    .getAggregationMode() == BankQueryPlan.AggregationResultMode.AVERAGE_ONLY);
            return contract;
        }
        return provinceAverageContract(plan, index, type,
                metrics.stream()
                        .map(metric -> BankResultProjector.MetricBinding
                                .builder().semanticColumn("aggregate_value")
                                .metricCode(metricCode(metric.schemaElement())).build())
                        .sorted(java.util.Comparator
                                .comparing(BankResultProjector.MetricBinding::getMetricCode))
                        .toList());
    }

    /**
     * Province-wide annual "单日最高/最低在哪家" uses empty organizations + limit 2 so the projector can
     * collapse per-org min/max into the two extreme orgs.
     */
    private boolean isDailyExtremaOrgPlan(BankQueryPlan plan, List<ResolvedMetric> metrics) {
        return plan.getIntent() == BankIntentType.AGGREGATION && plan.getOrganizations().isEmpty()
                && plan.getLimit() != null && plan.getLimit() == 2 && metrics.size() == 1
                && metrics.get(0).planMetric().getAggregation() == BankQueryPlan.Aggregation.AVG;
    }

    private List<String> aggregationSummaryOutputColumns(List<ResolvedMetric> metrics) {
        return metrics.size() == 1
                ? List.of(ORGANIZATION_DIMENSION, "aggregate_value", "min_value", "max_value",
                        "observation_count")
                : List.of(ORGANIZATION_DIMENSION, "metric_code", "aggregate_value", "min_value",
                        "max_value", "observation_count");
    }

    private List<String> multiMetricProvinceAverageOutputColumns(List<ResolvedMetric> metrics) {
        return List.of(ORGANIZATION_DIMENSION, "metric_code", "aggregate_value", "min_value",
                "max_value", "observation_count");
    }

    /**
     * The auditable projection contract for the compiler-owned derived-metric ranking template. The
     * SQL already ranks over the full organization population, so the projector must preserve the
     * source rank_position verbatim instead of recomputing ranks from the returned rows.
     */
    private BankResultProjector.Contract derivedRankingResultContract(BankQueryPlan plan,
            SchemaIndex index) {
        if (!index.hasDimension(ORGANIZATION_DIMENSION)) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.ORGANIZATION_DIMENSION_UNAVAILABLE,
                    "derived-metric ranking requires the semantic organization dimension");
        }
        SchemaElement organization = index.dimension(ORGANIZATION_DIMENSION);
        Map<String, String> organizationNames = new LinkedHashMap<>();
        if (organization.getSchemaValueMaps() != null) {
            for (SchemaValueMap valueMap : organization.getSchemaValueMaps()) {
                if (valueMap != null && StringUtils.isNotBlank(valueMap.getTechName())
                        && StringUtils.isNotBlank(valueMap.getBizName())) {
                    organizationNames.put(valueMap.getTechName(), valueMap.getBizName());
                }
            }
        }
        return BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.DERIVED_RANKING)
                .organizationColumn(identifier(organization)).organizationNames(organizationNames)
                .selectedOrganizationCodes(
                        plan.getOrganizations().stream().map(BankQueryPlan.Organization::getCode)
                                .filter(StringUtils::isNotBlank).sorted().toList())
                .build();
    }

    private BankResultProjector.Contract provinceAverageContract(BankQueryPlan plan,
            SchemaIndex index, BankResultProjector.ProjectionType type,
            List<BankResultProjector.MetricBinding> metrics) {
        if (!index.hasDimension(ORGANIZATION_DIMENSION)) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.ORGANIZATION_DIMENSION_UNAVAILABLE,
                    "province-average queries require the semantic organization dimension");
        }
        SchemaElement organization = index.dimension(ORGANIZATION_DIMENSION);
        Map<String, String> organizationNames = new LinkedHashMap<>();
        if (organization.getSchemaValueMaps() != null) {
            for (SchemaValueMap valueMap : organization.getSchemaValueMaps()) {
                if (valueMap != null && StringUtils.isNotBlank(valueMap.getTechName())
                        && StringUtils.isNotBlank(valueMap.getBizName())) {
                    organizationNames.put(valueMap.getTechName(), valueMap.getBizName());
                }
            }
        }
        return BankResultProjector.Contract.builder().type(type)
                .organizationColumn(identifier(organization)).organizationNames(organizationNames)
                .selectedOrganizationCodes(
                        plan.getOrganizations().stream().map(BankQueryPlan.Organization::getCode)
                                .filter(StringUtils::isNotBlank).sorted().toList())
                .metrics(metrics).build();
    }

    private CompiledQuery direct(BankQueryPlan plan, LLMReq.LLMSchema schema,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions,
            List<Filter> dimensionFilters, List<Filter> metricFilters, List<String> outputColumns,
            SchemaElement partitionTime, SchemaIndex index) {
        QueryStructReq request = new QueryStructReq();
        request.setDataSetId(schema.getDataSetId());
        request.setDataSetName(schema.getDataSetName());
        request.setQueryType(QueryType.AGGREGATE);
        request.setGroups(dimensions.stream().map(ResolvedDimension::identifier)
                .collect(Collectors.toList()));
        request.setAggregators(metrics.stream().map(metric -> new Aggregator(metric.identifier(),
                toAggregation(metric), metric.identifier())).collect(Collectors.toList()));
        request.setDimensionFilters(dimensionFilters);
        request.setMetricFilters(metricFilters);
        request.setDateInfo(dateInfo(plan, partitionTime, dimensions));
        request.setOrders(orders(plan, metrics, dimensions));
        request.setLimit(
                requiresFullRankingInput(plan, dimensions) ? SemanticIntentHints.DEFAULT_MAX_LIMIT
                        : plan.getLimit() == null ? SemanticIntentHints.DEFAULT_MAX_LIMIT
                                : plan.getLimit());
        return CompiledQuery.struct(request, outputColumns,
                resultContract(plan, metrics, dimensions, index));
    }

    private boolean requiresFullRankingInput(BankQueryPlan plan,
            List<ResolvedDimension> dimensions) {
        return ranksSelectedOrganization(plan, dimensions)
                || rankFilterLimit(plan, "rank_from_bottom") != null;
    }

    /**
     * Retrieves the daily values through the semantic query API, then lets the result projector
     * calculate each organization's full-period average and top/bottom ranks. The S2SQL translator
     * cannot safely preserve a metric's generated CASE expression inside nested aggregation CTEs,
     * while a structured request expands the metric before it reaches the physical query.
     */
    private CompiledQuery dailyAverageRanking(BankQueryPlan plan, LLMReq.LLMSchema schema,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions,
            List<Filter> dimensionFilters, List<Filter> metricFilters, SchemaElement partitionTime,
            SchemaIndex index) {
        List<ResolvedDimension> dailyDimensions = new ArrayList<>(dimensions);
        dailyDimensions.add(new ResolvedDimension(partitionTime));

        QueryStructReq request = new QueryStructReq();
        request.setDataSetId(schema.getDataSetId());
        request.setDataSetName(schema.getDataSetName());
        request.setQueryType(QueryType.AGGREGATE);
        request.setGroups(dailyDimensions.stream().map(ResolvedDimension::identifier)
                .collect(Collectors.toList()));
        request.setAggregators(List.of(new Aggregator(metrics.get(0).identifier(),
                AggOperatorEnum.SUM, metrics.get(0).identifier())));
        request.setDimensionFilters(dimensionFilters);
        request.setMetricFilters(metricFilters);
        request.setDateInfo(dateInfo(plan, partitionTime, dailyDimensions));
        request.setOrders(List.of());
        request.setLimit(DAILY_AVERAGE_RANKING_MAX_LIMIT);
        return CompiledQuery.struct(request,
                List.of(ORGANIZATION_DIMENSION, dateField(partitionTime),
                        metrics.get(0).identifier()),
                dailyAverageRankingResultContract(plan, metrics, dimensions, index));
    }

    private BankResultProjector.Contract resultContract(BankQueryPlan plan,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions, SchemaIndex index) {
        if (plan.getCalculation().getType() != BankQueryPlan.CalculationType.DIRECT
                || plan.getTime().getComparison() != BankQueryPlan.TimeComparison.NONE) {
            return null;
        }
        if (plan.getIntent() == BankIntentType.TREND) {
            return trendResultContract(plan, metrics, dimensions);
        }
        if (plan.getIntent() != BankIntentType.POINT_QUERY
                && plan.getIntent() != BankIntentType.RANKING
                && plan.getIntent() != BankIntentType.COMPARISON) {
            return null;
        }
        SchemaElement organization = dimensions.stream().map(ResolvedDimension::schemaElement)
                .filter(element -> matches(element, ORGANIZATION_DIMENSION)).findFirst()
                .orElseGet(() -> plan.getOrganizations().isEmpty() ? null
                        : index.hasDimension(ORGANIZATION_DIMENSION)
                                ? index.dimension(ORGANIZATION_DIMENSION)
                                : null);
        if (organization == null) {
            return null;
        }
        // Preserve plan metric order for POINT_QUERY multi-metric structure share (对公/个人/合计).
        // Ranking long-form still benefits from a stable metric_code ASC when many metrics appear.
        Stream<BankResultProjector.MetricBinding> bindingStream = metrics.stream()
                .map(metric -> BankResultProjector.MetricBinding.builder()
                        .semanticColumn(metric.identifier())
                        .metricCode(metricCode(metric.schemaElement())).build());
        List<BankResultProjector.MetricBinding> metricBindings =
                plan.getIntent() == BankIntentType.RANKING ? bindingStream
                        .sorted(java.util.Comparator
                                .comparing(BankResultProjector.MetricBinding::getMetricCode))
                        .toList() : bindingStream.toList();
        Map<String, String> organizationNames = new LinkedHashMap<>();
        if (organization.getSchemaValueMaps() != null) {
            for (SchemaValueMap valueMap : organization.getSchemaValueMaps()) {
                if (valueMap != null && StringUtils.isNotBlank(valueMap.getTechName())
                        && StringUtils.isNotBlank(valueMap.getBizName())) {
                    organizationNames.put(valueMap.getTechName(), valueMap.getBizName());
                }
            }
        }
        boolean structureShare = plan.getOutput() != null && plan.getOutput().isOrderSensitive()
                && isDepositStructureShareMetrics(metricBindings);
        return BankResultProjector.Contract.builder()
                .type(plan.getIntent() == BankIntentType.RANKING
                        ? BankResultProjector.ProjectionType.RANKED_LONG_FORM
                        : plan.getIntent() == BankIntentType.COMPARISON
                                ? BankResultProjector.ProjectionType.COMPARISON
                                : BankResultProjector.ProjectionType.LONG_FORM)
                .organizationColumn(identifier(organization)).organizationNames(organizationNames)
                .selectedOrganizationCodes(
                        plan.getOrganizations().stream().map(BankQueryPlan.Organization::getCode)
                                .filter(StringUtils::isNotBlank).sorted().toList())
                .metrics(metricBindings).topRankLimit(rankFilterLimit(plan, "rank"))
                .bottomRankLimit(rankFilterLimit(plan, "rank_from_bottom"))
                .structureShare(structureShare).build();
    }

    private static boolean isDepositStructureShareMetrics(
            List<BankResultProjector.MetricBinding> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return false;
        }
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (BankResultProjector.MetricBinding metric : metrics) {
            if (metric != null && metric.getMetricCode() != null) {
                codes.add(StringUtils.upperCase(metric.getMetricCode()));
            }
        }
        return codes.contains("ZB001") && codes.contains("ZB003") && codes.contains("ZB004");
    }

    private Integer rankFilterLimit(BankQueryPlan plan, String field) {
        return plan.getFilters().stream().filter(filter -> field.equals(filter.getField()))
                .map(BankQueryPlan.Filter::getValue).filter(StringUtils::isNotBlank).findFirst()
                .map(Integer::valueOf).orElse(null);
    }

    private BankResultProjector.Contract dailyAverageRankingResultContract(BankQueryPlan plan,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions, SchemaIndex index) {
        BankResultProjector.Contract contract = resultContract(plan, metrics, dimensions, index);
        if (contract != null) {
            contract.setType(BankResultProjector.ProjectionType.DAILY_AVERAGE_RANKING);
        }
        return contract;
    }

    private BankResultProjector.Contract trendResultContract(BankQueryPlan plan,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions) {
        if (metrics.size() != 1) {
            return null;
        }
        ResolvedDimension time = dimensions.stream()
                .filter(dimension -> matches(dimension.schemaElement(), TIME_DIMENSION)).findFirst()
                .orElse(null);
        if (time == null) {
            return null;
        }
        ResolvedMetric metric = metrics.get(0);
        return BankResultProjector.Contract.builder().type(BankResultProjector.ProjectionType.TREND)
                .timeColumn(time.identifier())
                .selectedDates(
                        quarterEndDates(plan.getTime().getStartDate(), plan.getTime().getEndDate()))
                .metrics(List.of(BankResultProjector.MetricBinding.builder()
                        .semanticColumn(metric.identifier())
                        .metricCode(metricCode(metric.schemaElement())).build()))
                .build();
    }

    private BankResultProjector.Contract ratioResultContract(BankQueryPlan plan,
            List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions, SchemaIndex index) {
        SchemaElement organization = dimensions.stream().map(ResolvedDimension::schemaElement)
                .filter(element -> matches(element, ORGANIZATION_DIMENSION)).findFirst()
                .orElseGet(() -> plan.getOrganizations().isEmpty() ? null
                        : index.hasDimension(ORGANIZATION_DIMENSION)
                                ? index.dimension(ORGANIZATION_DIMENSION)
                                : null);
        if (organization == null) {
            return null;
        }
        Map<String, String> organizationNames = new LinkedHashMap<>();
        if (organization.getSchemaValueMaps() != null) {
            for (SchemaValueMap valueMap : organization.getSchemaValueMaps()) {
                if (valueMap != null && StringUtils.isNotBlank(valueMap.getTechName())
                        && StringUtils.isNotBlank(valueMap.getBizName())) {
                    organizationNames.put(valueMap.getTechName(), valueMap.getBizName());
                }
            }
        }
        List<BankResultProjector.MetricBinding> metricBindings = metrics.stream()
                .map(metric -> BankResultProjector.MetricBinding.builder()
                        .semanticColumn(metric.identifier())
                        .metricCode(metricCode(metric.schemaElement())).build())
                .toList();
        return BankResultProjector.Contract.builder().type(BankResultProjector.ProjectionType.RATIO)
                .organizationColumn(identifier(organization)).organizationNames(organizationNames)
                .selectedOrganizationCodes(
                        plan.getOrganizations().stream().map(BankQueryPlan.Organization::getCode)
                                .filter(StringUtils::isNotBlank).sorted().toList())
                .metrics(metricBindings).build();
    }

    private String metricCode(SchemaElement metric) {
        String registryCode = BankSemanticRegistry.metrics().values().stream()
                .filter(definition -> matchesRegistryMetric(metric, definition))
                .map(BankSemanticRegistry.MetricDefinition::code).findFirst().orElse(null);
        if (registryCode != null) {
            return registryCode;
        }
        if (metric.getExtInfo() != null && metric.getExtInfo().get("indicatorCode") != null) {
            return String.valueOf(metric.getExtInfo().get("indicatorCode"));
        }
        if (metric.getAlias() != null) {
            return metric.getAlias().stream().filter(StringUtils::isNotBlank)
                    .filter(value -> value.matches("(?i)ZB\\d+")).findFirst()
                    .orElseGet(() -> identifier(metric).toUpperCase(Locale.ROOT));
        }
        return identifier(metric).toUpperCase(Locale.ROOT);
    }

    private boolean matchesRegistryMetric(SchemaElement element,
            BankSemanticRegistry.MetricDefinition definition) {
        return matchesIdentifier(element, definition.code())
                || matchesIdentifier(element, definition.name()) || definition.aliases().stream()
                        .anyMatch(alias -> matchesIdentifier(element, alias));
    }

    private boolean matchesIdentifier(SchemaElement element, String value) {
        return SchemaIndex.key(element.getBizName()).equals(SchemaIndex.key(value))
                || SchemaIndex.key(element.getName()).equals(SchemaIndex.key(value));
    }

    private List<ResolvedMetric> resolveMetrics(BankQueryPlan plan, SchemaIndex index) {
        return plan.getMetrics().stream()
                .map(metric -> new ResolvedMetric(metric, index.metric(metric.getBizName())))
                .collect(Collectors.toList());
    }

    private List<ResolvedDimension> resolveDimensions(BankQueryPlan plan, SchemaIndex index) {
        return plan.getDimensions().stream()
                .map(identifier -> new ResolvedDimension(index.dimension(identifier)))
                .collect(Collectors.toList());
    }

    private List<String> verifyOutputOrder(BankQueryPlan plan, List<ResolvedMetric> metrics,
            List<ResolvedDimension> dimensions, SchemaIndex index) {
        List<String> expected = Stream
                .concat(dimensions.stream().map(ResolvedDimension::identifier),
                        metrics.stream().map(ResolvedMetric::identifier))
                .collect(Collectors.toList());
        List<String> actual = plan.getOutput().getColumns().stream()
                .map(column -> canonicalOutputColumn(column, index)).collect(Collectors.toList());
        if (actual.size() != expected.size()
                || !new LinkedHashSet<>(actual).equals(new LinkedHashSet<>(expected))
                || !actual.equals(expected)) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.OUTPUT_ORDER_MISMATCH,
                    "output columns must be the selected dimensions followed by metrics in plan order");
        }
        return List.copyOf(expected);
    }

    private String canonicalOutputColumn(String column, SchemaIndex index) {
        if (index.hasDimension(column)) {
            return identifier(index.dimension(column));
        }
        if (index.hasMetric(column)) {
            return identifier(index.metric(column));
        }
        return column == null ? "" : column.trim();
    }

    private List<Filter> compileDimensionFilters(BankQueryPlan plan, SchemaIndex index,
            List<ResolvedMetric> metrics) {
        List<Filter> filters = new ArrayList<>();
        if (!plan.getOrganizations().isEmpty()) {
            SchemaElement organization = organizationDimension(plan, index);
            List<String> codes = plan.getOrganizations().stream()
                    .map(BankQueryPlan.Organization::getCode).collect(Collectors.toList());
            filters.add(new Filter(identifier(organization),
                    codes.size() == 1 ? FilterOperatorEnum.EQUALS : FilterOperatorEnum.IN,
                    codes.size() == 1 ? codes.get(0) : codes));
        }
        for (BankQueryPlan.Filter filter : plan.getFilters()) {
            if (isRankFilter(filter)) {
                validateRankFilter(plan, filter);
                continue;
            }
            if ("benchmark".equals(filter.getField())) {
                continue;
            }
            if ("metric_value".equals(filter.getField())) {
                continue;
            }
            if (index.hasDimension(filter.getField())) {
                filters.add(toFilter(index.dimension(filter.getField()), filter));
            } else if (!index.hasMetric(filter.getField())) {
                throw unsupportedFilter(filter);
            }
        }
        return filters;
    }

    private List<Filter> compileMetricFilters(BankQueryPlan plan, SchemaIndex index,
            List<ResolvedMetric> metrics) {
        List<Filter> filters = new ArrayList<>();
        for (BankQueryPlan.Filter filter : plan.getFilters()) {
            if (isRankFilter(filter)) {
                continue;
            }
            if ("metric_value".equals(filter.getField())) {
                // Logical direction for province-average threshold (value=PROVINCE_AVERAGE) is
                // consumed by the S2SQL template, not as a numeric metric filter.
                if ("PROVINCE_AVERAGE".equals(filter.getValue())) {
                    continue;
                }
                if (metrics.size() != 1) {
                    throw unsupportedFilter(filter);
                }
                filters.add(toFilter(metrics.get(0).schemaElement(), filter));
            } else if (index.hasMetric(filter.getField())) {
                filters.add(toFilter(index.metric(filter.getField()), filter));
            }
        }
        return filters;
    }

    private List<String> quarterEndDates(LocalDate startDate, LocalDate endDate) {
        int quarterEndMonth = ((startDate.getMonthValue() - 1) / 3 + 1) * 3;
        LocalDate current = YearMonth.of(startDate.getYear(), quarterEndMonth).atEndOfMonth();
        if (current.isBefore(startDate)) {
            current = YearMonth.from(current).plusMonths(3).atEndOfMonth();
        }
        List<String> dates = new ArrayList<>();
        while (!current.isAfter(endDate)) {
            dates.add(current.toString());
            current = YearMonth.from(current).plusMonths(3).atEndOfMonth();
        }
        return dates;
    }

    private Filter toFilter(SchemaElement element, BankQueryPlan.Filter filter) {
        FilterOperatorEnum operator = filterOperator(filter);
        Object value;
        if (operator == FilterOperatorEnum.IN || operator == FilterOperatorEnum.NOT_IN) {
            value = filterValues(filter);
        } else if (operator == FilterOperatorEnum.LIKE) {
            value = "%" + filter.getValue() + "%";
        } else {
            value = filter.getValue();
        }
        return new Filter(identifier(element), operator, value);
    }

    private List<String> filterValues(BankQueryPlan.Filter filter) {
        if (filter.getValues() != null && !filter.getValues().isEmpty()) {
            return List.copyOf(filter.getValues());
        }
        if (StringUtils.isNotBlank(filter.getValue())) {
            return List.of(filter.getValue());
        }
        throw unsupportedFilter(filter);
    }

    private FilterOperatorEnum filterOperator(BankQueryPlan.Filter filter) {
        return switch (filter.getOperator()) {
            case "EQ" -> FilterOperatorEnum.EQUALS;
            case "NE" -> FilterOperatorEnum.NOT_EQUALS;
            case "GT" -> FilterOperatorEnum.GREATER_THAN;
            case "GTE" -> FilterOperatorEnum.GREATER_THAN_EQUALS;
            case "LT" -> FilterOperatorEnum.MINOR_THAN;
            case "LTE" -> FilterOperatorEnum.MINOR_THAN_EQUALS;
            case "IN" -> FilterOperatorEnum.IN;
            case "NOT_IN" -> FilterOperatorEnum.NOT_IN;
            case "CONTAINS" -> FilterOperatorEnum.LIKE;
            default -> throw unsupportedFilter(filter);
        };
    }

    private BankPlanCompilationException unsupportedFilter(BankQueryPlan.Filter filter) {
        return new BankPlanCompilationException(
                BankPlanCompilationException.Reason.UNSUPPORTED_FILTER,
                "filter cannot be compiled without dropping its condition: " + filter.getField());
    }

    private boolean isRankFilter(BankQueryPlan.Filter filter) {
        return "rank".equals(filter.getField()) || "rank_from_bottom".equals(filter.getField());
    }

    private void validateRankFilter(BankQueryPlan plan, BankQueryPlan.Filter filter) {
        if (plan.getIntent() != BankIntentType.RANKING || !"LTE".equals(filter.getOperator())
                || filter.getValue() == null || !filter.getValue().matches("[1-9]\\d*")) {
            throw unsupportedFilter(filter);
        }
    }

    private SchemaElement organizationDimension(BankQueryPlan plan, SchemaIndex index) {
        Set<String> explicit = plan.getOrganizations().stream()
                .map(BankQueryPlan.Organization::getBizName).filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (explicit.size() > 1) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.ORGANIZATION_DIMENSION_UNAVAILABLE,
                    "organizations must use one semantic organization dimension");
        }
        String identifier =
                explicit.isEmpty() ? ORGANIZATION_DIMENSION : explicit.iterator().next();
        if (!index.hasDimension(identifier)) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.ORGANIZATION_DIMENSION_UNAVAILABLE,
                    "organization filter requires semantic dimension: " + identifier);
        }
        return index.dimension(identifier);
    }

    private DateConf dateInfo(BankQueryPlan plan, SchemaElement partitionTime,
            List<ResolvedDimension> dimensions) {
        DateConf dateInfo = new DateConf();
        dateInfo.setDateMode(DateConf.DateMode.BETWEEN);
        dateInfo.setStartDate(plan.getTime().getStartDate().toString());
        dateInfo.setEndDate(plan.getTime().getEndDate().toString());
        dateInfo.setDateField(dateField(partitionTime));
        dateInfo.setPeriod(toPeriod(plan.getTime().getGranularity()));
        dateInfo.setGroupByDate(dimensions.stream().map(ResolvedDimension::identifier)
                .anyMatch(identifier(partitionTime)::equals));
        return dateInfo;
    }

    private DatePeriodEnum toPeriod(BankQueryPlan.TimeGranularity granularity) {
        return switch (granularity) {
            case MONTH -> DatePeriodEnum.MONTH;
            case QUARTER -> DatePeriodEnum.QUARTER;
            case YEAR -> DatePeriodEnum.YEAR;
            default -> DatePeriodEnum.DAY;
        };
    }

    private String dateField(SchemaElement partitionTime) {
        if (partitionTime == null) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.TIME_DIMENSION_UNAVAILABLE,
                    "a semantic partition time dimension is required for compilation");
        }
        return StringUtils.defaultIfBlank(partitionTime.getName(), identifier(partitionTime));
    }

    private List<Order> orders(BankQueryPlan plan, List<ResolvedMetric> metrics,
            List<ResolvedDimension> dimensions) {
        List<Order> result = new ArrayList<>();
        for (BankQueryPlan.OrderBy orderBy : plan.getOrderBy()) {
            String identifier = selectedIdentifier(orderBy.getField(), metrics, dimensions);
            if (identifier == null) {
                throw new BankPlanCompilationException(
                        BankPlanCompilationException.Reason.ORDER_FIELD_NOT_SELECTED,
                        "order field must be selected by the plan: " + orderBy.getField());
            }
            result.add(new Order(identifier, rankingDirection(plan, identifier, metrics, orderBy)));
        }
        Set<String> ordered = result.stream().map(Order::getColumn).collect(Collectors.toSet());
        dimensions.stream().map(ResolvedDimension::identifier)
                .filter(identifier -> !ordered.contains(identifier))
                .forEach(identifier -> result.add(new Order(identifier, "ASC")));
        return result;
    }

    private String rankingDirection(BankQueryPlan plan, String identifier,
            List<ResolvedMetric> metrics, BankQueryPlan.OrderBy orderBy) {
        if (plan.getIntent() != BankIntentType.RANKING) {
            return orderBy.getDirection().name();
        }
        return metrics.stream().filter(metric -> metric.identifier().equals(identifier)).findFirst()
                .map(metric -> BankResultProjector
                        .rankingDirection(metricCode(metric.schemaElement())))
                .orElse(orderBy.getDirection().name());
    }

    private String selectedIdentifier(String requested, List<ResolvedMetric> metrics,
            List<ResolvedDimension> dimensions) {
        return Stream.concat(
                metrics.stream()
                        .filter(metric -> metric.planMetric().getBizName().equals(requested)
                                || matches(metric.schemaElement(), requested))
                        .map(ResolvedMetric::identifier),
                dimensions.stream()
                        .filter(dimension -> matches(dimension.schemaElement(), requested))
                        .map(ResolvedDimension::identifier))
                .findFirst().orElse(null);
    }

    private AggOperatorEnum toAggregation(ResolvedMetric metric) {
        if (metric.planMetric().getAggregation() == null
                || metric.planMetric().getAggregation() == BankQueryPlan.Aggregation.DEFAULT) {
            return AggOperatorEnum
                    .of(StringUtils.defaultIfBlank(metric.schemaElement().getDefaultAgg(), "SUM"));
        }
        return AggOperatorEnum.of(metric.planMetric().getAggregation().name());
    }

    private ResolvedMetric ratioDenominator(BankQueryPlan plan, List<ResolvedMetric> metrics) {
        if (StringUtils.isBlank(plan.getCalculation().getBaseline())) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "ratio calculation requires an explicit denominator metric");
        }
        return metrics.stream()
                .filter(metric -> metric.planMetric().getBizName()
                        .equals(plan.getCalculation().getBaseline()))
                .findFirst().filter(metric -> metric != metrics.get(0))
                .orElseThrow(() -> new BankPlanCompilationException(
                        BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                        "ratio denominator must be a second requested metric"));
    }

    private String identifier(SchemaElement element) {
        return StringUtils.defaultIfBlank(element.getBizName(), element.getName());
    }

    private boolean matches(SchemaElement element, String value) {
        return SchemaIndex.key(element.getBizName()).equals(SchemaIndex.key(value))
                || SchemaIndex.key(element.getName()).equals(SchemaIndex.key(value));
    }

    private record ResolvedMetric(BankQueryPlan.Metric planMetric, SchemaElement schemaElement) {
        String identifier() {
            return StringUtils.defaultIfBlank(schemaElement.getBizName(), schemaElement.getName());
        }
    }

    private record ResolvedDimension(SchemaElement schemaElement) {
        String identifier() {
            return StringUtils.defaultIfBlank(schemaElement.getBizName(), schemaElement.getName());
        }
    }

    private static final class SchemaIndex {
        private final Map<String, SchemaElement> metrics;
        private final Map<String, SchemaElement> dimensions;
        private final SchemaElement partitionTime;

        private SchemaIndex(LLMReq.LLMSchema schema) {
            this.metrics = index(schema.getMetrics());
            this.dimensions = index(Stream
                    .concat(safe(schema.getDimensions()),
                            schema.getPartitionTime() == null ? Stream.empty()
                                    : Stream.of(schema.getPartitionTime()))
                    .collect(Collectors.toList()));
            this.partitionTime = schema.getPartitionTime();
            // A model plan may only carry strict semantic identifiers. The compiler is allowed to
            // bind those identifiers to a live schema that exposes Chinese display names, but it
            // must never accept a display name as model input.
            registerBankMetricIdentifiers(this.metrics);
            registerBankDimensionIdentifiers(this.dimensions);
        }

        private SchemaElement metric(String value) {
            return require(metrics, value, BankPlanCompilationException.Reason.METRIC_UNAVAILABLE,
                    "metric is not available in the semantic schema: ");
        }

        private SchemaElement dimension(String value) {
            return require(dimensions, value,
                    BankPlanCompilationException.Reason.DIMENSION_UNAVAILABLE,
                    "dimension is not available in the semantic schema: ");
        }

        private boolean hasMetric(String value) {
            return resolve(metrics, value) != null;
        }

        private boolean hasDimension(String value) {
            return resolve(dimensions, value) != null;
        }

        private SchemaElement partitionTime() {
            return partitionTime;
        }

        private static Map<String, SchemaElement> index(Collection<SchemaElement> elements) {
            Map<String, SchemaElement> index = new LinkedHashMap<>();
            safe(elements).filter(Objects::nonNull).forEach(element -> {
                put(index, element.getBizName(), element);
                put(index, element.getName(), element);
            });
            return index;
        }

        private static void registerBankMetricIdentifiers(Map<String, SchemaElement> metrics) {
            BankSemanticRegistry.metrics().values().forEach(metric -> {
                SchemaElement element = metrics.get(key(metric.code()));
                if (element == null) {
                    element = metrics.get(key(metric.name()));
                }
                if (element == null) {
                    element = metric.aliases().stream().map(SchemaIndex::key).map(metrics::get)
                            .filter(Objects::nonNull).findFirst().orElse(null);
                }
                if (element != null) {
                    put(metrics, metric.code(), element);
                }
            });
        }

        private static void registerBankDimensionIdentifiers(
                Map<String, SchemaElement> dimensions) {
            registerDimensionIdentifier(dimensions, "bank_organization", "机构");
            registerDimensionIdentifier(dimensions, "bank_data_date", "数据日期");
        }

        private static void registerDimensionIdentifier(Map<String, SchemaElement> dimensions,
                String identifier, String displayName) {
            SchemaElement element = dimensions.get(key(identifier));
            if (element == null) {
                element = dimensions.get(key(displayName));
            }
            if (element != null) {
                put(dimensions, identifier, element);
            }
        }

        private static void put(Map<String, SchemaElement> index, String value,
                SchemaElement element) {
            if (StringUtils.isNotBlank(value)) {
                index.putIfAbsent(key(value), element);
            }
        }

        private static SchemaElement resolve(Map<String, SchemaElement> index, String value) {
            if (value == null) {
                return null;
            }
            SchemaElement element = index.get(key(value));
            if (element != null) {
                return element;
            }
            return element;
        }

        private static SchemaElement require(Map<String, SchemaElement> index, String value,
                BankPlanCompilationException.Reason reason, String message) {
            SchemaElement element = resolve(index, value);
            if (element == null) {
                throw new BankPlanCompilationException(reason, message + value);
            }
            return element;
        }

        private static Stream<SchemaElement> safe(Collection<SchemaElement> values) {
            return values == null ? Stream.empty() : values.stream();
        }

        private static String key(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    @Getter
    public static final class CompiledQuery {
        private final CompilationRoute route;
        private final QueryStructReq structReq;
        private final String s2sql;
        private final List<String> outputColumns;
        private final BankResultProjector.Contract resultContract;
        private final String fingerprint;

        private CompiledQuery(CompilationRoute route, QueryStructReq structReq, String s2sql,
                List<String> outputColumns, BankResultProjector.Contract resultContract) {
            this.route = route;
            this.structReq = structReq;
            this.s2sql = s2sql;
            this.outputColumns = List.copyOf(outputColumns);
            this.resultContract = resultContract;
            this.fingerprint = BankPlanFingerprint.of(this);
        }

        static CompiledQuery struct(QueryStructReq request, List<String> outputColumns,
                BankResultProjector.Contract resultContract) {
            return new CompiledQuery(CompilationRoute.STRUCT, request, null, outputColumns,
                    resultContract);
        }

        static CompiledQuery s2sql(String s2sql, List<String> outputColumns) {
            return s2sql(s2sql, outputColumns, null);
        }

        static CompiledQuery s2sql(String s2sql, List<String> outputColumns,
                BankResultProjector.Contract resultContract) {
            return new CompiledQuery(CompilationRoute.S2SQL_TEMPLATE, null, s2sql, outputColumns,
                    resultContract);
        }
    }

    public enum CompilationRoute {
        STRUCT, S2SQL_TEMPLATE
    }
}
