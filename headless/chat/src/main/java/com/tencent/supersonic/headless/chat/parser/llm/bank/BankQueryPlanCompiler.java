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
                                        metric.identifier()))
                                .collect(Collectors.toList()),
                        dimensions.stream().map(ResolvedDimension::identifier).toList(),
                        dateField(index.partitionTime()), executionDimensionFilters, metricFilters);

        if (plan.getCalculation().getType() == BankQueryPlan.CalculationType.DIRECT
                && plan.getTime().getComparison() == BankQueryPlan.TimeComparison.NONE) {
            if (hasProvinceAverageBenchmark(plan)) {
                if (plan.getIntent() == BankIntentType.THRESHOLD) {
                    return CompiledQuery.s2sql(
                            templateFactory.compileProvinceAverageThreshold(templateContext),
                            List.of(ORGANIZATION_DIMENSION, "metric_value", "provincial_average",
                                    "meets_condition"),
                            provinceAverageThresholdResultContract(plan, index));
                }
                if (plan.getIntent() == BankIntentType.AGGREGATION) {
                    return CompiledQuery.s2sql(
                            templateFactory.compileProvinceAverageAggregation(templateContext),
                            List.of(ORGANIZATION_DIMENSION, "aggregate_value", "min_value",
                                    "max_value", "observation_count"),
                            provinceAverageAggregationResultContract(plan, metrics, index));
                }
                throw new BankPlanCompilationException(
                        BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                        "province-average benchmarks require a threshold or aggregation intent");
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
                yield CompiledQuery.s2sql(
                        monthAndYear ? templateFactory.compileMonthAndYearChange(templateContext)
                                : templateFactory.compileChange(templateContext),
                        calculatedOutputColumns(dimensions, "current_value", "baseline_value",
                                "absolute_change", "percent_change"),
                        monthAndYear ? BankResultProjector.Contract.builder()
                                .type(BankResultProjector.ProjectionType.MOM_YOY_CHANGE).build()
                                : null);
            }
            case RATIO -> {
                ResolvedMetric denominator = ratioDenominator(plan, metrics);
                yield CompiledQuery.s2sql(
                        templateFactory.compileRatio(templateContext, metrics.get(0).identifier(),
                                denominator.identifier()),
                        calculatedOutputColumns(dimensions, "numerator_value", "denominator_value",
                                "ratio_percent"),
                        ratioResultContract(plan, dimensions, index));
            }
            case DIRECT -> throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "time comparison requires a supported calculation type");
        };
    }

    private boolean hasProvinceAverageBenchmark(BankQueryPlan plan) {
        return plan.getFilters().stream()
                .anyMatch(filter -> "benchmark".equals(filter.getField())
                        && "COMPARE".equals(filter.getOperator())
                        && "PROVINCE_AVERAGE".equals(filter.getValue()));
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

    private BankResultProjector.Contract provinceAverageThresholdResultContract(BankQueryPlan plan,
            SchemaIndex index) {
        return provinceAverageContract(plan, index,
                BankResultProjector.ProjectionType.PROVINCIAL_AVERAGE_THRESHOLD, List.of());
    }

    private BankResultProjector.Contract provinceAverageAggregationResultContract(
            BankQueryPlan plan, List<ResolvedMetric> metrics, SchemaIndex index) {
        if (metrics.size() != 1) {
            throw new BankPlanCompilationException(
                    BankPlanCompilationException.Reason.UNSUPPORTED_CALCULATION,
                    "province-average aggregation requires exactly one metric");
        }
        return provinceAverageContract(plan, index,
                BankResultProjector.ProjectionType.AGGREGATION_SUMMARY,
                List.of(BankResultProjector.MetricBinding.builder()
                        .semanticColumn("aggregate_value")
                        .metricCode(metricCode(metrics.get(0).schemaElement())).build()));
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
                ranksSelectedOrganization(plan, dimensions) ? SemanticIntentHints.DEFAULT_MAX_LIMIT
                        : plan.getLimit() == null ? SemanticIntentHints.DEFAULT_MAX_LIMIT
                                : plan.getLimit());
        return CompiledQuery.struct(request, outputColumns,
                resultContract(plan, metrics, dimensions, index));
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
        List<BankResultProjector.MetricBinding> metricBindings = metrics.stream()
                .map(metric -> BankResultProjector.MetricBinding.builder()
                        .semanticColumn(metric.identifier())
                        .metricCode(metricCode(metric.schemaElement())).build())
                .sorted(java.util.Comparator
                        .comparing(BankResultProjector.MetricBinding::getMetricCode))
                .toList();
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
                .bottomRankLimit(rankFilterLimit(plan, "rank_from_bottom")).build();
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
            List<ResolvedDimension> dimensions, SchemaIndex index) {
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
        return BankResultProjector.Contract.builder().type(BankResultProjector.ProjectionType.RATIO)
                .organizationColumn(identifier(organization)).organizationNames(organizationNames)
                .selectedOrganizationCodes(
                        plan.getOrganizations().stream().map(BankQueryPlan.Organization::getCode)
                                .filter(StringUtils::isNotBlank).sorted().toList())
                .build();
    }

    private String metricCode(SchemaElement metric) {
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
                metrics.stream().filter(metric -> matches(metric.schemaElement(), requested))
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
                .filter(metric -> matches(metric.schemaElement(),
                        plan.getCalculation().getBaseline()))
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
            return metrics.containsKey(key(value));
        }

        private boolean hasDimension(String value) {
            return dimensions.containsKey(key(value));
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

        private static void put(Map<String, SchemaElement> index, String value,
                SchemaElement element) {
            if (StringUtils.isNotBlank(value)) {
                index.putIfAbsent(key(value), element);
            }
        }

        private static SchemaElement require(Map<String, SchemaElement> index, String value,
                BankPlanCompilationException.Reason reason, String message) {
            SchemaElement element = index.get(key(value));
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
