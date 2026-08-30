package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankFinancialLexicon;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
    private static final Set<String> REQUIREMENT_ACTIONS =
            enumNames(BankRequestContract.Action.class);
    private static final Set<String> INTENTS =
            Arrays.stream(BankIntentType.values()).filter(value -> value != BankIntentType.UNKNOWN)
                    .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    private static final Set<String> REQUIREMENT_INTENTS = enumNames(BankIntentType.class);
    private static final Set<String> ANSWER_FACT_TYPES =
            enumNames(BankRequestContract.AnswerFactType.class);
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
    private static final Map<String, Double> DERIVED_RATIO_SCALES = buildDerivedRatioScales();

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

    /**
     * Result-contract multiplier applied to a numerator/denominator ratio, owned here so the point
     * ratio compiler and the derived ranking template stay consistent. Percent-style catalog
     * ratios default to 100; pairs whose units already cancel keep the raw quotient (1.0), and
     * unit-conversion pairs (亿元→万元) scale by 10000.
     */
    public static double ratioScale(String numerator, String denominator) {
        Double scale = numerator == null || denominator == null ? null
                : DERIVED_RATIO_SCALES.get(ratioPairKey(numerator, denominator));
        return scale == null ? 100.0 : scale;
    }

    /**
     * Additive composite derived code shape: {@code DERIVED_SUM_<M1>_AND_<M2>} — no {@code _DIV_}
     * suffix. The shape is deliberately not part of {@link #derivedMetricCodes()} (the prompt
     * vocabulary stays pairwise-free); validators and parsers accept any canonical instantiation
     * whose operands are registered percent-unit catalog metrics.
     */
    private static final Pattern ADDITIVE_DERIVED_METRIC_CODE =
            Pattern.compile("DERIVED_SUM_([A-Z0-9]+)_AND_([A-Z0-9]+)");

    /** True when the catalog publishes the metric with the percent unit (%). */
    public static boolean isPercentUnitMetric(String code) {
        MetricDefinition definition =
                code == null ? null : METRICS.get(code.toUpperCase(Locale.ROOT));
        return definition != null && "%".equals(definition.unit());
    }

    /** Exact-shape check for the additive composite derived code (two operands, no _DIV_ suffix). */
    public static boolean isAdditiveDerivedMetricCode(String code) {
        return code != null && ADDITIVE_DERIVED_METRIC_CODE.matcher(code).matches();
    }

    /**
     * Canonical additive derived code for an operand pair: the operands are sorted
     * lexicographically so one metric pair always has exactly one code form
     * (e.g. ZB013+ZB017 → DERIVED_SUM_ZB013_AND_ZB017 regardless of mention order).
     */
    public static String additiveDerivedMetricCode(String first, String second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("additive derived operands are required");
        }
        String left = first.toUpperCase(Locale.ROOT);
        String right = second.toUpperCase(Locale.ROOT);
        return left.compareTo(right) < 0
                ? "DERIVED_SUM_" + left + "_AND_" + right
                : "DERIVED_SUM_" + right + "_AND_" + left;
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
                "metrics","derivedMetrics","dimensions","organizations","time","filters","calculation","orderBy",
                "limit","output"],"properties":{"version":{"const":"1.0"},
                "action":{"enum":%s},"intent":{"enum":%s},"metrics":{"type":"array",
                "items":{"type":"object","additionalProperties":false,"required":["bizName",
                "aggregation"],"properties":{"bizName":{"enum":%s},"aggregation":{"enum":%s},
                "alias":{"type":["string","null"]}}}},"dimensions":{"type":"array",
                "items":{"enum":%s}},"organizations":{"type":"array","items":{"type":"object",
                "additionalProperties":false,"required":["code"],"properties":{"code":{"enum":%s},
                "bizName":{"type":["string","null"]}}}},"time":{"type":"object",
                "additionalProperties":false,"required":["startDate","endDate","granularity",
                "comparison"],"properties":{"startDate":{"type":"string","format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$"},
                "endDate":{"type":"string","format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$"},"granularity":{"enum":%s},
                "comparison":{"enum":%s},"baselineStartDate":{"type":["string","null"],
                "format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$"},"baselineEndDate":{"type":["string","null"],"format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$"}}},
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
                "denominator":{"enum":%s},"name":{"type":"string"},
                "numeratorOperands":{"type":["array","null"],"items":{"enum":%s}}}}}}}
                """
                .formatted(jsonArray(PLAN_ACTIONS), jsonArray(INTENTS), jsonArray(metricCodes()),
                        jsonArray(AGGREGATIONS), jsonArray(DIMENSIONS),
                        jsonArray(organizationCodes()), jsonArray(TIME_GRANULARITIES),
                        jsonArray(TIME_COMPARISONS), jsonArray(filterFields()),
                        jsonArray(FILTER_OPERATORS), jsonArray(CALCULATION_TYPES),
                        jsonArray(SORT_DIRECTIONS), jsonArray(derivedMetricCodes()),
                        jsonArray(metricCodes()), jsonArray(metricCodes()),
                        jsonArray(metricCodes()))
                .strip();
    }

    /**
     * Strict REQUIREMENTS-stage schema. It constrains JSON shape and published identifiers, while
     * {@link BankRequestContractResponseParser} remains responsible for semantic cross-field rules.
     */
    public static String requestContractJsonSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["version","action","intent",
                "metricCodes","derivedMetrics","organizationCodes","time","filters","requiredLimit",
                "answerFactTypes","clarification"],"properties":{"version":{"const":"1.0"},
                "action":{"enum":%s},"intent":{"enum":%s},"metricCodes":{"type":"array",
                "items":{"enum":%s}},"derivedMetrics":{"type":"array","items":{"type":"object",
                "additionalProperties":false,"required":["metricCode","numerator","denominator","name"],
                "properties":{"metricCode":{"enum":%s},"numerator":{"enum":%s},"denominator":{"enum":%s},
                "name":{"type":"string"},
                "numeratorOperands":{"type":["array","null"],"items":{"enum":%s}}}}},
                "organizationCodes":{"type":"array","items":{"enum":%s}},
                "time":{"type":["object","null"],"additionalProperties":false,"required":["startDate",
                "endDate","granularity","comparison","baselineStartDate","baselineEndDate"],"properties":{
                "startDate":{"type":"string","format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$"},
                "endDate":{"type":"string","format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$"},
                "granularity":{"enum":%s},"comparison":{"enum":%s},"baselineStartDate":{"type":["string","null"],
                "format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$"},"baselineEndDate":{"type":["string","null"],
                "format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$"}}},"filters":{"type":"array",
                "items":{"type":"object","additionalProperties":false,"required":["field","operator","value","values"],
                "properties":{"field":{"enum":%s},"operator":{"enum":%s},"value":{"type":["string","null"]},
                "values":{"type":"array","items":{"type":"string"}}}}},"requiredLimit":{"type":["integer","null"],"minimum":1},
                "answerFactTypes":{"type":"array","items":{"enum":%s}},"clarification":{"type":["string","null"]}}}
                """
                .formatted(jsonArray(REQUIREMENT_ACTIONS), jsonArray(REQUIREMENT_INTENTS),
                        jsonArray(metricCodes()), jsonArray(derivedMetricCodes()),
                        jsonArray(metricCodes()), jsonArray(metricCodes()),
                        jsonArray(metricCodes()),
                        jsonArray(organizationCodes()), jsonArray(TIME_GRANULARITIES),
                        jsonArray(TIME_COMPARISONS), jsonArray(filterFields()),
                        jsonArray(FILTER_OPERATORS), jsonArray(ANSWER_FACT_TYPES))
                .strip();
    }

    /**
     * Compact, cache-stable facts shared by both prompt stages. This is the single source of truth
     * for every metric, derived metric and organization line rendered into any stage prefix; the
     * composer never re-types catalog rows.
     */
    public static String sharedCatalog() {
        String metricLines = METRICS.values().stream()
                .map(metric -> "%s %s（aliases=%s, unit=%s, defaultAgg=%s, direction=%s, desc=%s）"
                        .formatted(metric.code(), metric.name(), metric.aliases(), metric.unit(),
                                metric.defaultAggregation(), metric.direction(),
                                metric.description()))
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
                """.formatted(VERSION, PLAN_ACTIONS, INTENTS, TIME_GRANULARITIES, TIME_COMPARISONS,
                metricLines, derivedMetricLines, organizationLines).strip();
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

                limit 与 aggregationMode 语义补充（解释性质，合法取值仍以上方目录为准）：
                - limit 只服务排名切片：单侧「前N / 后N」填 N；「前N和后N」双侧切片填 2*N；
                  非排名查询族一律为 null，禁止把机构总数当作 limit。
                - output.aggregationMode 表示是否顺带输出极值：只问均值/日均值等平均口径时填
                  AVERAGE_ONLY；同时要求最高值与最低值时填 WITH_EXTREMA；都不是则保持 null。

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
                非 EQ 运算符的使用场景（示例仅示意写法，具体取值以题干为准）：
                - NE 排除单一取值，如 {"field":"bank_organization","operator":"NE",
                  "value":"ORG005","values":[]}
                - IN 命中列表内任一值，如 {"field":"bank_organization","operator":"IN",
                  "value":null,"values":["ORG001","ORG002"]}
                - NOT_IN 排除列表内全部值，如 {"field":"bank_organization","operator":"NOT_IN",
                  "value":null,"values":["ORG003","ORG004"]}
                - CONTAINS 名称包含匹配，如 {"field":"bank_organization","operator":"CONTAINS",
                  "value":"农商行","values":[]}
                全部样例 filters 中的 values 数组按上述规则填数；无列表运算时保持 values=[]。
                """.formatted(filterFields(), FILTER_OPERATORS).strip();
    }

    /**
     * Legacy combined catalog; superseded by {@link #sharedCatalog()} plus
     * {@link #planCapabilityCatalog()} in the split stage prefixes.
     */
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
            Direction direction = switch (source.getDirection()) {
                case HIGHER_BETTER -> Direction.HIGHER_BETTER;
                case LOWER_BETTER -> Direction.LOWER_BETTER;
                default -> Direction.NEUTRAL;
            };
            metrics.put(code,
                    new MetricDefinition(code, source.getName(), source.getAliases(),
                            source.getDescription(), source.getUnit(),
                            BankQueryPlan.Aggregation.DEFAULT.name(), AGGREGATIONS, direction, "",
                            Set.of(), INTENTS,
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

    /**
     * Ratio result scales keyed by operand pair. Catalog ratios are percent-style (scale 100)
     * unless the units cancel: 人均利润 = 净利润(万元) / 员工人数(人) is already a raw quotient
     * (scale 1.0), and 网点平均存款规模 = 各项存款(亿元) / 网点数量(个) converts to 万元 per
     * outlet (scale 10000). The point ratio compiler and the derived ranking template both read
     * this map, so the two routes can never drift apart again.
     */
    private static Map<String, Double> buildDerivedRatioScales() {
        LinkedHashMap<String, Double> scales = new LinkedHashMap<>();
        BankFinancialLexicon.derivedMetrics().forEach((code, source) -> scales.put(
                ratioPairKey(source.getNumerator(), source.getDenominator()), 100.0));
        scales.put(ratioPairKey("ZB011", "ZB018"), 1.0);
        scales.put(ratioPairKey("ZB001", "ZB019"), 10000.0);
        return Collections.unmodifiableMap(scales);
    }

    private static String ratioPairKey(String numerator, String denominator) {
        return numerator.toUpperCase(Locale.ROOT) + "|"
                + denominator.toUpperCase(java.util.Locale.ROOT);
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
