package com.tencent.supersonic.headless.chat.parser.llm.bank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Positive shape skeletons for structured plan repair, keyed by stable error code.
 *
 * <p>
 * A repair round's {@code <error>} message names the violated slot but never states the correct
 * overall shape; weak models then fix one violation by introducing another. Each entry below is
 * the positive skeleton transcribed from the whitelist conditions of the owning gate —
 * {@code BankPlanGenStrategy} query-family gates and {@code BankQueryPlanValidator} family
 * contracts. Skeletons describe abstract slot combinations only: placeholders such as
 * {@code ZB###}, {@code ORG###}, {@code YYYY-MM-DD}, and {@code N} stand for catalog codes and
 * question-supplied values. They never contain sample ids, official question wording, gold SQL,
 * or answer numbers, and they add no condition that the owning gate does not itself enforce.
 *
 * <p>
 * Lookup is {@link #forCode(String)} with the code recovered by
 * {@code BankPlanGenStrategy#repairErrorCode} (the case-preserved {@code CODE:} message prefix,
 * falling back to the parse reason). {@link #forRawCodePrefix(String)} remains as a compatibility
 * fallback for callers that supply the original validation message directly.
 * Messages produced by the requirements-contract parser carry no code prefix at all;
 * {@link #forMessage(String)} matches those by their stable message signature instead.
 */
public final class BankRepairShapeGuidance {

    /** Raw leading {@code CODE:} token of a validation message, case preserved. */
    private static final Pattern RAW_CODE_PREFIX =
            Pattern.compile("^([A-Za-z][A-Za-z0-9_]{2,63}):");

    /**
     * Additive composite family (两个同单位百分率指标的合计). Gate:
     * {@code BankPlanGenStrategy#validateAdditiveCompositeQuery} — intent/metric/derived/organ
     * ization/time/filters must all hold; the derived metric is the one canonical
     * {@code DERIVED_SUM_<M1>_AND_<M2>} item, not an empty list and not a RATIO plan.
     */
    private static final String ADDITIVE_COMPOSITE = """
            正确整体形状骨架：action=EXECUTE、intent=POINT_QUERY；metricCodes=[ZB###, ZB###]（直选两\
            个同单位(%)指标操作数，两个操作数都必须出现，加合与顺序无关）；derivedMetrics 恰好一项 \
            [{"metricCode":"DERIVED_SUM_<M1>_AND_<M2>","numerator":"<M1>","denominator":"<M2>"}]\
            （<M1>/<M2> 为两个操作数码按字典序的规范形，numerator 取较小码）；organizationCodes=[\
            <ORG###>]；time 为单日点：startDate=endDate=YYYY-MM-DD、comparison=NONE；filters=[]。\
            两个 % 指标的合计不得编译成 RATIO 或普通双指标点查。""";

    /**
     * Province-wide institution ranking. Gate:
     * {@code BankPlanGenStrategy#validateProvinceWideInstitutionRanking} — whole-population
     * contract with empty organizations, one metric, the question's explicit single-day time,
     * exactly one rank filter and the matching requiredLimit.
     */
    private static final String PROVINCE_WIDE_RANKING = """
            正确整体形状骨架：action=EXECUTE、intent=RANKING；organizationCodes=[]（全省全机构口径，\
            禁止点名或任选机构）；metricCodes=[单一指标 ZB###]；time 单日 startDate=endDate=\
            YYYY-MM-DD 且与题面显式日期一致；filters 恰好一条 {"field":"rank"或"rank_from_bottom",\
            "operator":"LTE","value":"<正整数N>","values":[]}；requiredLimit=<同一个正整数N>。\
            不得改成 COMPARISON、点查，也不得为全机构问法自行挑选机构。""";

    /**
     * Generic two-metric point ratio. Gate: {@code BankPlanGenStrategy#validateQueryFamily} via
     * {@code validateGenericPointRatioQuery} — RATIO intent, both operands direct-selected in
     * recognition order, no derived metric.
     */
    private static final String GENERIC_POINT_RATIO = """
            正确整体形状骨架：intent=RATIO；metricCodes=[<分子ZB###>, <分母ZB###>]（两个目录操作数按\
            分子在前、分母在后直选，不得增删、去重或交换）；derivedMetrics=[]。该族编译产出 \
            numerator_value/denominator_value/ratio_percent 列，不得退化成 POINT_QUERY 双指标点查\
            或结构占比。""";

    /**
     * Named derived ratio (存贷比/净利润率/人均利润). Gate:
     * {@code BankPlanGenStrategy#validateQueryFamily} via
     * {@code validateDerivedPointRatioQuery} — RATIO intent, numerator/denominator operands in
     * order, plus the single named derived specification. {@code per_capita_profit_mismatch} is
     * the legacy per-capita code of the same gate and shares the skeleton.
     */
    private static final String DERIVED_POINT_RATIO = """
            正确整体形状骨架：intent=RATIO；metricCodes=[<分子基期ZB###>, <分母基期ZB###>]（命名比率\
            的两个目录操作数按分子/分母顺序直选）；derivedMetrics=[{"metricCode":"DERIVED_<分子ZB###\
            >_DIV_<分母ZB###>","numerator":"<分子ZB###>","denominator":"<分母ZB###>"}]（与目录发布\
            的派生码完全一致）。不得退化为两指标透视点查（POINT_QUERY/STRUCT）。""";

    /**
     * Province-average benchmark slot whitelist. Gate:
     * {@code BankQueryPlanValidator} filter loop — PROVINCE_AVERAGE may only appear as the exact
     * benchmark filter, a metric_value direction object, or a per-metric benchmark condition,
     * and direction/condition forms require the exact benchmark filter beside them.
     */
    private static final String PROVINCE_AVERAGE_BENCHMARK_CONTRACT = """
            正确整体形状骨架：PROVINCE_AVERAGE 只允许三类基准形态——(1) 精确基准过滤器 \
            {"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}；\
            (2) 单指标方向对象 {"field":"metric_value","operator":"GT|GTE|LT|LTE",\
            "value":"PROVINCE_AVERAGE","values":[]}；(3) 逐指标基准条件 {"field":"<ZB###>",\
            "operator":"GT|GTE|LT|LTE","value":"PROVINCE_AVERAGE","values":[]}。出现 (2) 或 (3) \
            时必须同时保留 (1) 的精确基准过滤器。""";

    /** Gate: {@code BankQueryPlanValidator} — province-average filters carry empty values. */
    private static final String PROVINCE_AVERAGE_BENCHMARK_VALUES = """
            正确整体形状骨架：所有省份均值相关过滤器（benchmark/COMPARE/PROVINCE_AVERAGE 基准对象、\
            metric_value 方向对象、逐指标基准条件）的 values 必须恰为 []；基准指向由 value 字段承载，\
            不得把值放进 values。""";

    /**
     * Compound benchmark threshold family (多指标复合基准阈值). Gate:
     * {@code BankQueryPlanValidator#validateCompoundBenchmarkFamilyShape} — one exact benchmark
     * filter plus exactly one per-metric direction condition per selected metric, scanned over
     * the full population without baseline, ordering or TopN.
     */
    private static final String COMPOUND_BENCHMARK_FAMILY = """
            正确整体形状骨架（多指标复合基准阈值族）：intent=THRESHOLD、calculation.type=DIRECT；\
            metrics 直选至少两个目录指标且 derivedMetrics=[]；filters = 精确基准过滤器 \
            {"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]} 加上\
            每个所选指标恰好一条 {"field":"<该指标ZB###>","operator":"GT|GTE|LT|LTE",\
            "value":"PROVINCE_AVERAGE","values":[]}；time.comparison=NONE；organizations=[]\
            （全机构扫描）；dimensions=["bank_organization"]；orderBy=[]；limit=null。""";

    /**
     * Published derived-metric item shape. Source:
     * {@code BankRequestContractResponseParser#validateDerivedMetrics} — one exact published
     * code, distinct registry numerator/denominator, and the additive canonical form for
     * percent-unit sums.
     */
    private static final String DERIVED_METRIC_SPEC = """
            正确整体形状骨架：derivedMetrics 每项必须是 {"metricCode":"DERIVED_<分子ZB###>_DIV_<分母\
            ZB###>","numerator":"<分子ZB###>","denominator":"<分母ZB###>"}——metricCode 为目录已发布\
            的唯一派生码，numerator/denominator 为两个不同且都已发布的目录指标码，metricCode 与 \
            numerator/denominator 完全对应；加合形 {"metricCode":"DERIVED_SUM_<M1>_AND_<M2>"} 仅\
            用于两个同单位(%)指标的字典序规范形；不得自造目录外的派生码。""";

    /**
     * Percent-unit metric over a date range. Gate:
     * {@code BankQueryPlanValidator#validatePercentMetricRangeAggregation}. The common point-series
     * case is a TREND plan grouped by the semantic date dimension at DAY grain; AVG then preserves
     * each date's point value and the result contract selects the quarter-end rows. Other query
     * families may use AVG only when the question asks for a period average, or DEFAULT on a true
     * single-day window.
     */
    private static final String PERCENT_METRIC_RANGE = """
            正确整体形状骨架（百分率时间序列）：题目要求逐季/各季度末序列时，使用 action=EXECUTE、\
            intent=TREND；metrics 恰好一个百分率目录指标 \
            [{"bizName":"ZB###","aggregation":"AVG"}]（禁止 DEFAULT/SUM 跨日期相加）；\
            dimensions=["bank_data_date"]；organizations 只保留题面机构；time.startDate/endDate \
            保留题面完整起止范围、granularity=DAY、comparison=NONE、baselineStartDate/\
            baselineEndDate=null；filters=[]；calculation.type=DIRECT；orderBy=[{"field":\
            "bank_data_date","direction":"ASC"}]；limit=null；output.columns=["bank_data_date",\
            "ZB###"]、orderSensitive=true。DAY 分组下 AVG 保留每天的点值，结果投影再选季度末日期。\
            若原题确实问期间均值，则保留原查询族并使用 AVG；若原题只问单日，则必须 \
            startDate=endDate。不得缩短题面时间范围来规避校验。""";

    private static final Map<String, String> SKELETONS = buildSkeletons();

    private BankRepairShapeGuidance() {
    }

    /**
     * Returns the registered shape skeleton for an error code, or empty when the code has no
     * skeleton (the repair message then stays exactly as produced by the failing gate).
     */
    public static Optional<String> forCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(SKELETONS.get(code));
    }

    /**
     * Prefix fallback for validator failures. {@code repairErrorCode} only recovers lowercase
     * snake_case prefixes, so validator UPPER_CASE codes ({@code PROVINCE_AVERAGE_BENCHMARK_*},
     * {@code COMPOUND_BENCHMARK_*}, ...) never reach {@link #forCode}; this re-extracts the raw
     * leading {@code CODE:} token (case preserved, exact match against the registered keys) and
     * resolves it. Empty when the message has no leading token or the token is not registered.
     */
    static Optional<String> forRawCodePrefix(String validationMessage) {
        if (validationMessage == null) {
            return Optional.empty();
        }
        Matcher matcher = RAW_CODE_PREFIX.matcher(validationMessage);
        if (matcher.find()) {
            return Optional.ofNullable(SKELETONS.get(matcher.group(1)));
        }
        return Optional.empty();
    }

    /**
     * Signature fallback for requirements-contract parser failures. Those errors are joined
     * without a {@code snake_case:} prefix, so {@code repairErrorCode} degrades them to
     * {@code VALIDATION_FAILED}; the stable message text is the only reliable key. Match on the
     * exact stable substring, never on question wording.
     */
    static Optional<String> forMessage(String validationMessage) {
        if (validationMessage == null) {
            return Optional.empty();
        }
        if (validationMessage.contains(
                "derivedMetrics must declare one exact published code")) {
            return Optional.of(DERIVED_METRIC_SPEC);
        }
        return Optional.empty();
    }

    /** All registered codes; exposes the map for exhaustive guidance tests. */
    static Set<String> registeredCodes() {
        return Collections.unmodifiableSet(SKELETONS.keySet());
    }

    private static Map<String, String> buildSkeletons() {
        Map<String, String> skeletons = new LinkedHashMap<>();
        skeletons.put("additive_composite_mismatch", ADDITIVE_COMPOSITE);
        skeletons.put("province_wide_institution_ranking_mismatch", PROVINCE_WIDE_RANKING);
        skeletons.put("generic_point_ratio_mismatch", GENERIC_POINT_RATIO);
        skeletons.put("derived_point_ratio_mismatch", DERIVED_POINT_RATIO);
        // Legacy per-capita code emitted by the same derived-ratio family gate.
        skeletons.put("per_capita_profit_mismatch", DERIVED_POINT_RATIO);
        skeletons.put("PERCENT_METRIC_RANGE_SUM", PERCENT_METRIC_RANGE);
        skeletons.put("PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED",
                PROVINCE_AVERAGE_BENCHMARK_CONTRACT);
        skeletons.put("PROVINCE_AVERAGE_BENCHMARK_VALUES_FORBIDDEN",
                PROVINCE_AVERAGE_BENCHMARK_VALUES);
        skeletons.put("COMPOUND_BENCHMARK_METRIC_UNKNOWN",
                COMPOUND_BENCHMARK_FAMILY + "\n本码要点：条件 field 必须是已注册的 ZB### 目录指标码。");
        skeletons.put("COMPOUND_BENCHMARK_INTENT_REQUIRED",
                COMPOUND_BENCHMARK_FAMILY + "\n本码要点：intent 必须为 THRESHOLD。");
        skeletons.put("COMPOUND_BENCHMARK_METRICS_REQUIRED", COMPOUND_BENCHMARK_FAMILY
                + "\n本码要点：至少直选两个目录指标；单指标高于/低于全省均值属于单指标 threshold 族，"
                + "不得追加逐指标基准条件。");
        skeletons.put("COMPOUND_BENCHMARK_DIRECT_CALCULATION_REQUIRED",
                COMPOUND_BENCHMARK_FAMILY + "\n本码要点：calculation.type 必须为 DIRECT。");
        skeletons.put("COMPOUND_BENCHMARK_NO_COMPARISON_REQUIRED",
                COMPOUND_BENCHMARK_FAMILY + "\n本码要点：time.comparison 必须为 NONE。");
        skeletons.put("COMPOUND_BENCHMARK_DERIVED_METRIC_FORBIDDEN",
                COMPOUND_BENCHMARK_FAMILY + "\n本码要点：derivedMetrics 必须为 []。");
        skeletons.put("COMPOUND_BENCHMARK_CONDITION_UNPAIRED", COMPOUND_BENCHMARK_FAMILY
                + "\n本码要点：每个所选指标恰好一条基准方向条件，条件 field 必须都在所选指标内，"
                + "禁止为未选指标声明条件。");
        skeletons.put("COMPOUND_BENCHMARK_DIRECTION_CONFLICT", COMPOUND_BENCHMARK_FAMILY
                + "\n本码要点：比较符号由目录方向决定——higher-better 指标用 GT/GTE（高于全省均值），"
                + "lower-better 指标用 LT/LTE（低于全省均值）。");
        skeletons.put("COMPOUND_BENCHMARK_GLOBAL_DIRECTION_FORBIDDEN", COMPOUND_BENCHMARK_FAMILY
                + "\n本码要点：用逐指标基准条件取代单个 metric_value 方向对象，删除 field=metric_value "
                + "的过滤器。");
        skeletons.put("COMPOUND_BENCHMARK_METRIC_FILTER_FORBIDDEN", COMPOUND_BENCHMARK_FAMILY
                + "\n本码要点：不得携带数值型 metric_value 过滤器。");
        skeletons.put("COMPOUND_BENCHMARK_POPULATION_REQUIRED", COMPOUND_BENCHMARK_FAMILY
                + "\n本码要点：organizations=[]，基准均值与 meets_condition 覆盖全机构。");
        skeletons.put("COMPOUND_BENCHMARK_DIMENSION_REQUIRED", COMPOUND_BENCHMARK_FAMILY
                + "\n本码要点：dimensions 恰为 [\"bank_organization\"]。");
        skeletons.put("COMPOUND_BENCHMARK_NO_ORDER_REQUIRED", COMPOUND_BENCHMARK_FAMILY
                + "\n本码要点：orderBy=[]，排序由编译器负责。");
        skeletons.put("COMPOUND_BENCHMARK_NO_LIMIT_REQUIRED", COMPOUND_BENCHMARK_FAMILY
                + "\n本码要点：limit=null，返回全机构结果。");
        return Collections.unmodifiableMap(skeletons);
    }
}
