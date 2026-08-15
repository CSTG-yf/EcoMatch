package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankFinancialLexicon;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single capability registry for the constrained bank plan contract.
 *
 * <p>
 * The model prompt, JSON Schema and validator consume the same immutable values from this registry.
 * Business aliases remain owned by {@link BankFinancialLexicon}; this class enriches that catalog
 * with plan capabilities and result-fact semantics.
 */
public final class BankSemanticRegistry {

    public static final String VERSION = "bank-semantic-v1";

    public enum Direction {
        HIGHER_BETTER, LOWER_BETTER, NEUTRAL
    }

    public record PlanFieldDefinition(String type, boolean required, String defaultValue,
            Set<String> allowedValues, Set<String> dependencies, Set<String> mutuallyExclusive,
            String exampleShape) {}

    public record MetricDefinition(String code, String name, List<String> aliases,
            String description, String unit, String defaultAggregation,
            Set<String> supportedAggregations, Direction direction, String formula,
            Set<String> dependencies, Set<String> supportedIntents,
            Set<String> supportedOutputFacts) {}

    public record OrganizationDefinition(String code, String name, List<String> aliases,
            String scope) {}

    public record OutputFactDefinition(String description, String dataType, String unit,
            Set<String> supportedIntents, String outputColumn) {}

    private static final Set<String> PLAN_ACTIONS = enumNames(BankQueryPlan.PlanAction.class);
    private static final Set<String> INTENTS =
            Arrays.stream(BankIntentType.values()).filter(value -> value != BankIntentType.UNKNOWN)
                    .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    private static final Set<String> AGGREGATIONS = enumNames(BankQueryPlan.Aggregation.class);
    private static final Set<String> TIME_GRANULARITIES =
            enumNames(BankQueryPlan.TimeGranularity.class);
    private static final Set<String> TIME_COMPARISONS =
            enumNames(BankQueryPlan.TimeComparison.class);
    private static final Set<String> CALCULATION_TYPES =
            enumNames(BankQueryPlan.CalculationType.class);
    private static final Set<String> SORT_DIRECTIONS = enumNames(BankQueryPlan.SortDirection.class);
    private static final Set<String> DIMENSIONS =
            immutableSet("bank_organization", "bank_data_date");
    private static final Set<String> DIMENSION_ALIASES =
            immutableSet("bank_organization", "机构", "bank_data_date", "数据日期");
    private static final Set<String> FILTER_OPERATORS = immutableSet("EQ", "NE", "GT", "GTE", "LT",
            "LTE", "IN", "NOT_IN", "CONTAINS", "COMPARE");
    private static final Set<String> LOGICAL_FILTER_FIELDS =
            immutableSet("metric_value", "benchmark", "rank", "rank_from_bottom");

    private static final Map<String, OutputFactDefinition> OUTPUT_FACTS = buildOutputFacts();
    private static final Map<String, MetricDefinition> METRICS = buildMetrics();
    private static final Map<String, MetricDefinition> DERIVED_METRICS = buildDerivedMetrics();
    private static final Map<String, OrganizationDefinition> ORGANIZATIONS = buildOrganizations();
    private static final Map<String, PlanFieldDefinition> PLAN_FIELDS = buildPlanFields();

    private BankSemanticRegistry() {}

    public static Set<String> planActions() {
        return PLAN_ACTIONS;
    }

    public static Set<String> intents() {
        return INTENTS;
    }

    public static Set<String> aggregations() {
        return AGGREGATIONS;
    }

    public static Set<String> timeGranularities() {
        return TIME_GRANULARITIES;
    }

    public static Set<String> timeComparisons() {
        return TIME_COMPARISONS;
    }

    public static Set<String> calculationTypes() {
        return CALCULATION_TYPES;
    }

    public static Set<String> sortDirections() {
        return SORT_DIRECTIONS;
    }

    public static Set<String> dimensions() {
        return DIMENSIONS;
    }

    public static Set<String> dimensionAliases() {
        return DIMENSION_ALIASES;
    }

    public static Set<String> filterOperators() {
        return FILTER_OPERATORS;
    }

    public static Set<String> logicalFilterFields() {
        return LOGICAL_FILTER_FIELDS;
    }

    public static Set<String> filterFields() {
        LinkedHashSet<String> fields = new LinkedHashSet<>(metricCodes());
        fields.addAll(DIMENSIONS);
        fields.addAll(LOGICAL_FILTER_FIELDS);
        return Collections.unmodifiableSet(fields);
    }

    public static Set<String> metricCodes() {
        return METRICS.keySet();
    }

    public static Set<String> derivedMetricCodes() {
        return DERIVED_METRICS.keySet();
    }

    public static Set<String> organizationCodes() {
        return ORGANIZATIONS.keySet();
    }

    public static Map<String, PlanFieldDefinition> planFields() {
        return PLAN_FIELDS;
    }

    public static Map<String, MetricDefinition> metrics() {
        return METRICS;
    }

    public static Map<String, MetricDefinition> derivedMetrics() {
        return DERIVED_METRICS;
    }

    public static Map<String, OrganizationDefinition> organizations() {
        return ORGANIZATIONS;
    }

    public static Map<String, OutputFactDefinition> outputFacts() {
        return OUTPUT_FACTS;
    }

    /** Values that must stay identical in the prompt and JSON Schema. */
    public static Map<String, Set<String>> schemaEnumValues() {
        LinkedHashMap<String, Set<String>> values = new LinkedHashMap<>();
        values.put("action", PLAN_ACTIONS);
        values.put("intent", INTENTS);
        values.put("metrics.bizName", metricCodes());
        values.put("derivedMetrics.metricCode", derivedMetricCodes());
        values.put("dimensions", DIMENSIONS);
        values.put("organizations.code", organizationCodes());
        values.put("metrics.aggregation", AGGREGATIONS);
        values.put("time.granularity", TIME_GRANULARITIES);
        values.put("time.comparison", TIME_COMPARISONS);
        values.put("filters.field", filterFields());
        values.put("filters.operator", FILTER_OPERATORS);
        values.put("calculation.type", CALCULATION_TYPES);
        values.put("orderBy.direction", SORT_DIRECTIONS);
        return Collections.unmodifiableMap(values);
    }

    public static String jsonSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["version","action","intent",
                "metrics","dimensions","organizations","time","filters","calculation","orderBy",
                "limit","output"],"properties":{"version":{"const":"1.0"},
                "action":{"enum":%s},"intent":{"enum":%s},"metrics":{"type":"array",
                "items":{"type":"object","additionalProperties":false,"required":["bizName",
                "aggregation"],"properties":{"bizName":{"enum":%s},"aggregation":{"enum":%s},
                "alias":{"type":"string"}}}},"dimensions":{"type":"array","items":{"enum":%s}},
                "organizations":{"type":"array","items":{"type":"object",
                "additionalProperties":false,"required":["code"],"properties":{"code":{"enum":%s},
                "bizName":{"type":"string"}}}},"time":{"type":"object",
                "additionalProperties":false,"required":["startDate","endDate","granularity",
                "comparison"],"properties":{"startDate":{"type":"string","format":"date"},
                "endDate":{"type":"string","format":"date"},"granularity":{"enum":%s},
                "comparison":{"enum":%s},"baselineStartDate":{"type":["string","null"],
                "format":"date"},"baselineEndDate":{"type":["string","null"],"format":"date"}}},
                "filters":{"type":"array","items":{"type":"object","additionalProperties":false,
                "required":["field","operator"],"properties":{"field":{"enum":%s},
                "operator":{"enum":%s},"value":{"type":["string","null"]},"values":{"type":"array",
                "items":{"type":"string"}}}}},"calculation":{"type":"object",
                "additionalProperties":false,"required":["type"],"properties":{"type":{"enum":%s},
                "baseline":{"type":["string","null"]}}},"orderBy":{"type":"array","items":{
                "type":"object","additionalProperties":false,"required":["field","direction"],
                "properties":{"field":{"type":"string"},"direction":{"enum":%s}}}},
                "limit":{"type":["integer","null"],"minimum":1},"output":{"type":"object",
                "additionalProperties":false,"required":["columns","orderSensitive"],"properties":{
                "columns":{"type":"array","items":{"type":"string"}},"orderSensitive":{
                "type":"boolean"},"aggregationMode":{"enum":["AVERAGE_ONLY","WITH_EXTREMA",null]}}},
                "derivedMetrics":{"type":"array","items":{"type":"object",
                "additionalProperties":false,"required":["metricCode","numerator","denominator","name"],
                "properties":{"metricCode":{"enum":%s},"numerator":{"enum":%s},
                "denominator":{"enum":%s},"name":{"type":"string"}}}}}}
                """
                .formatted(jsonArray(PLAN_ACTIONS), jsonArray(INTENTS), jsonArray(metricCodes()),
                        jsonArray(AGGREGATIONS), jsonArray(DIMENSIONS),
                        jsonArray(organizationCodes()), jsonArray(TIME_GRANULARITIES),
                        jsonArray(TIME_COMPARISONS), jsonArray(filterFields()),
                        jsonArray(FILTER_OPERATORS), jsonArray(CALCULATION_TYPES),
                        jsonArray(SORT_DIRECTIONS), jsonArray(derivedMetricCodes()),
                        jsonArray(metricCodes()), jsonArray(metricCodes()))
                .strip();
    }

    /**
     * Compact, cache-stable facts shared by both prompt stages. This is the single source of
     * truth for every metric, derived metric and organization line rendered into any stage
     * prefix; the composer never re-types catalog rows.
     */
    public static String sharedCatalog() {
        String metricLines = METRICS.values().stream()
                .map(metric -> "%s %s（aliases=%s, unit=%s, defaultAgg=%s, direction=%s）".formatted(
                        metric.code(), metric.name(), metric.aliases(), metric.unit(),
                        metric.defaultAggregation(), metric.direction()))
                .collect(Collectors.joining("\n"));
        String derivedMetricLines = DERIVED_METRICS.values().stream()
                .map(metric -> "%s %s（formula=%s, unit=%s, direction=%s）".formatted(metric.code(),
                        metric.name(), metric.formula(), metric.unit(), metric.direction()))
                .collect(Collectors.joining("\n"));
        String organizationLines =
                ORGANIZATIONS
                        .values().stream().map(org -> "%s %s（aliases=%s, scope=%s）"
                                .formatted(org.code(), org.name(), org.aliases(), org.scope()))
                        .collect(Collectors.joining("\n"));
        return """
                ════════════════════════════════
                权威语义目录（%s；以下值同时驱动 JSON Schema 与 Validator）
                ════════════════════════════════
                action: %s
                intent: %s
                time granularity: %s
                time comparison: %s

                指标代码、单位与方向：
                %s

                派生指标（代码、公式、单位与方向）：
                %s

                机构代码与范围（organizations=[] 表示全省/各家范围）：
                %s
                """.formatted(VERSION, PLAN_ACTIONS, INTENTS, TIME_GRANULARITIES,
                TIME_COMPARISONS, metricLines, derivedMetricLines, organizationLines).strip();
    }

    /** PLAN-only enums and compiler output facts; rendered only into the PLAN stage prefix. */
    public static String planCapabilityCatalog() {
        String planFieldLines = PLAN_FIELDS.entrySet().stream().map(entry -> {
            PlanFieldDefinition field = entry.getValue();
            return "%s: type=%s, required=%s, default=%s, allowed=%s, example=%s".formatted(
                    entry.getKey(), field.type(), field.required(),
                    field.defaultValue() == null ? "none" : field.defaultValue(),
                    field.allowedValues(), field.exampleShape());
        }).collect(Collectors.joining("\n"));
        String factLines =
                OUTPUT_FACTS.entrySet().stream()
                        .map(entry -> entry.getKey() + " -> " + entry.getValue().outputColumn()
                                + "（" + entry.getValue().description() + "）")
                        .collect(Collectors.joining("\n"));
        return """
                plan fields（类型/必填/默认值/允许值/示例）：
                %s

                aggregation: %s
                dimensions: %s
                calculation type: %s
                sort direction: %s
                filter field/operator/value contract:
                  fields=%s
                  operators=%s
                  benchmark + COMPARE 仅允许 value=PROVINCE_AVERAGE

                编译结果可产生的输出事实（output.columns 仍只声明所选维度和指标）：
                %s
                """.formatted(planFieldLines, AGGREGATIONS, DIMENSIONS, CALCULATION_TYPES,
                SORT_DIRECTIONS, filterFields(), FILTER_OPERATORS, factLines).strip();
    }

    /**
     * Compact, cache-stable filter contract owned by {@link BankRequestContract} and shared with
     * {@link BankQueryPlan}. Rendered from registry constants so the REQUIREMENTS stage prefix and
     * the validators never drift on allowed field categories, operators or value shape.
     */
    public static String filterContract() {
        return """
                filter field/operator/value contract（字段类别与运算符由注册表统一提供）：
                field categories: %s
                operators: %s
                value 语义：
                - EQ/NE/GT/GTE/LT/LTE：value 填单个比较值（数字或百分数字符串），values=[]
                - IN/NOT_IN：values 填完整取值列表，value=null
                - CONTAINS：value 填包含文本
                - COMPARE 仅与 benchmark 搭配：value=PROVINCE_AVERAGE、values=[]
                - metric_value 与 GT/GTE/LT/LTE 且 value=PROVINCE_AVERAGE 表示高于/低于全省均值的方向，
                  需同时声明上面的 benchmark 过滤项
                """.formatted(filterFields(), FILTER_OPERATORS).strip();
    }

    /** Legacy combined catalog; superseded by {@link #sharedCatalog()} plus
     * {@link #planCapabilityCatalog()} in the split stage prefixes. */
    public static String promptCatalog() {
        return sharedCatalog() + "\n\n" + planCapabilityCatalog();
    }

    private static Map<String, PlanFieldDefinition> buildPlanFields() {
        LinkedHashMap<String, PlanFieldDefinition> fields = new LinkedHashMap<>();
        fields.put("version", field("string", true, "1.0", Set.of("1.0"), "\"1.0\""));
        fields.put("action", field("enum", true, "EXECUTE", PLAN_ACTIONS, "\"EXECUTE\""));
        fields.put("intent", field("enum", true, null, INTENTS, "\"POINT_QUERY\""));
        fields.put("metrics", field("array", true, "[]", metricCodes(),
                "[{\"bizName\":\"ZB001\",\"aggregation\":\"DEFAULT\"}]"));
        fields.put("derivedMetrics", field("array", false, "[]", derivedMetricCodes(), "[]"));
        fields.put("dimensions", field("array", true, "[]", DIMENSIONS, "[]"));
        fields.put("organizations", field("array", true, "[]", organizationCodes(), "[]"));
        fields.put("time", field("object", true, null, TIME_GRANULARITIES,
                "{\"startDate\":\"YYYY-MM-DD\",\"endDate\":\"YYYY-MM-DD\"}"));
        fields.put("filters", field("array", true, "[]", filterFields(), "[]"));
        fields.put("calculation", field("object", true, null, CALCULATION_TYPES,
                "{\"type\":\"DIRECT\",\"baseline\":null}"));
        fields.put("orderBy", field("array", true, "[]", SORT_DIRECTIONS, "[]"));
        fields.put("limit", field("integer|null", true, "null", Set.of(), "null"));
        fields.put("output", field("object", true, null, OUTPUT_FACTS.keySet(),
                "{\"columns\":[\"ZB001\"],\"orderSensitive\":false}"));
        return Collections.unmodifiableMap(fields);
    }

    private static PlanFieldDefinition field(String type, boolean required, String defaultValue,
            Set<String> allowedValues, String example) {
        return new PlanFieldDefinition(type, required, defaultValue, allowedValues, Set.of(),
                Set.of(), example);
    }

    private static Map<String, MetricDefinition> buildMetrics() {
        LinkedHashMap<String, MetricDefinition> metrics = new LinkedHashMap<>();
        BankFinancialLexicon.metrics().forEach((code, source) -> {
            Direction direction = switch (code) {
                case "ZB012", "ZB013", "ZB017" -> Direction.LOWER_BETTER;
                case "ZB015", "ZB016" -> Direction.HIGHER_BETTER;
                default -> Direction.NEUTRAL;
            };
            String unit = switch (code) {
                case "ZB012", "ZB013", "ZB015", "ZB016", "ZB017" -> "%";
                case "ZB011" -> "万元";
                case "ZB018" -> "人";
                case "ZB019" -> "个";
                case "ZB020", "ZB021" -> "户";
                default -> "亿元";
            };
            metrics.put(code,
                    new MetricDefinition(code, source.getName(), source.getAliases(),
                            source.getName(), unit, BankQueryPlan.Aggregation.DEFAULT.name(),
                            AGGREGATIONS, direction, "", Set.of(), INTENTS,
                            immutableSet("METRIC_VALUE", "CURRENT_VALUE", "BASELINE_VALUE",
                                    "ABSOLUTE_CHANGE", "PERCENT_CHANGE", "RANK_POSITION",
                                    "PROVINCIAL_AVERAGE", "DAILY_AVERAGE", "MINIMUM_VALUE",
                                    "MAXIMUM_VALUE")));
        });
        return Collections.unmodifiableMap(metrics);
    }

    private static Map<String, MetricDefinition> buildDerivedMetrics() {
        LinkedHashMap<String, MetricDefinition> metrics = new LinkedHashMap<>();
        BankFinancialLexicon.derivedMetrics().forEach((code, source) -> metrics.put(code,
                new MetricDefinition(code, source.getName(), source.getAliases(), source.getName(),
                        "%", BankQueryPlan.Aggregation.DEFAULT.name(),
                        Set.of(BankQueryPlan.Aggregation.DEFAULT.name()), Direction.NEUTRAL,
                        source.getNumerator() + " / " + source.getDenominator(),
                        immutableSet(source.getNumerator(), source.getDenominator()), INTENTS,
                        immutableSet("RATIO_PERCENT", "RANK_POSITION", "METRIC_ROLE"))));
        return Collections.unmodifiableMap(metrics);
    }

    private static Map<String, OrganizationDefinition> buildOrganizations() {
        return Collections.unmodifiableMap(BankFinancialLexicon.organizations().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new OrganizationDefinition(entry.getKey(),
                                entry.getValue().getName(), entry.getValue().getAliases(), "BANK"),
                        (left, right) -> left, LinkedHashMap::new)));
    }

    private static Map<String, OutputFactDefinition> buildOutputFacts() {
        LinkedHashMap<String, OutputFactDefinition> facts = new LinkedHashMap<>();
        fact(facts, "METRIC_VALUE", "指标值", "number", "metric_value");
        fact(facts, "CURRENT_VALUE", "当前期值", "number", "current_value");
        fact(facts, "BASELINE_VALUE", "基期值", "number", "baseline_value");
        fact(facts, "ABSOLUTE_CHANGE", "变化额", "number", "absolute_change");
        fact(facts, "PERCENT_CHANGE", "变化率", "number", "percent_change");
        fact(facts, "VALUE_DIFFERENCE", "机构或基准间差值", "number", "value_difference");
        fact(facts, "RATIO_PERCENT", "比率百分比", "number", "ratio_percent");
        fact(facts, "RANK_POSITION", "排名位次", "integer", "rank_position");
        fact(facts, "PROVINCIAL_AVERAGE", "全省均值", "number", "provincial_average");
        fact(facts, "DAYS_ABOVE_AVERAGE", "高于全省均值天数", "integer", "days_above_average");
        fact(facts, "TOTAL_DAYS", "统计总天数", "integer", "total_days");
        fact(facts, "DAILY_AVERAGE", "期间日均值", "number", "daily_average");
        fact(facts, "MINIMUM_VALUE", "期间最小值", "number", "minimum_value");
        fact(facts, "MAXIMUM_VALUE", "期间最大值", "number", "maximum_value");
        fact(facts, "COMPARISON_TYPE", "比较类型", "string", "comparison_type");
        fact(facts, "METRIC_ROLE", "指标在结果中的角色", "string", "metric_role");
        return Collections.unmodifiableMap(facts);
    }

    private static void fact(Map<String, OutputFactDefinition> facts, String name,
            String description, String dataType, String outputColumn) {
        facts.put(name, new OutputFactDefinition(description, dataType, "metric-dependent", INTENTS,
                outputColumn));
    }

    private static <E extends Enum<E>> Set<String> enumNames(Class<E> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Enum::name)
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new),
                        Collections::unmodifiableSet));
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }

    private static String jsonArray(Set<String> values) {
        return values.stream().map(BankSemanticRegistry::jsonQuote)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String jsonQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
