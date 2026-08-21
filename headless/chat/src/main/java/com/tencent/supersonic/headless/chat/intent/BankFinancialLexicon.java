package com.tencent.supersonic.headless.chat.intent;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BankFinancialLexicon {

    private static final Map<String, MetricDefinition> METRICS = new LinkedHashMap<>();
    private static final Map<String, DerivedMetricDefinition> DERIVED_METRICS =
            new LinkedHashMap<>();
    private static final Map<String, OrganizationDefinition> ORGANIZATIONS = new LinkedHashMap<>();
    private static final Map<String, String> NORMALIZATIONS = new LinkedHashMap<>();
    private static final List<CompositionGroupDefinition> COMPOSITION_GROUPS = new ArrayList<>();

    static {
        metric("ZB001", "各项存款余额", "存款余额", "存款规模", "存款总额", "存款");
        metric("ZB002", "各项贷款余额", "贷款余额", "贷款规模", "贷款总额", "贷款");
        metric("ZB003", "对公存款余额", "对公存款", "公司存款");
        metric("ZB004", "个人存款余额", "个人存款", "储蓄存款", "零售存款");
        metric("ZB005", "对公贷款余额", "对公贷款", "公司贷款");
        metric("ZB006", "个人贷款余额", "个人贷款", "零售贷款");
        metric("ZB007", "中间业务收入", "中收", "中间收入", "手续费收入");
        metric("ZB008", "净利息收入", "净息收", "利息净收入");
        metric("ZB009", "营业收入", "营收");
        metric("ZB010", "营业支出", "营业成本");
        metric("ZB011", "净利润", "利润");
        metric("ZB012", "成本收入比", "成本收支比", "成本收人比");
        metric("ZB013", "不良贷款率", "不良率", "不良货款率");
        metric("ZB014", "不良贷款余额", "不良余额", "不良货款余额");
        metric("ZB015", "拨备覆盖率", "拨备率", "拨备覆盖", "拨备");
        metric("ZB016", "资本充足率", "资本充足");
        metric("ZB017", "逾期贷款率", "逾期率", "逾期货款率");
        metric("ZB018", "员工人数", "员工数", "人数");
        metric("ZB019", "网点数量", "网点数", "营业网点");
        metric("ZB020", "个人客户数", "个人客户", "零售客户数", "零售客户");
        metric("ZB021", "对公客户数", "对公客户", "公司客户数", "企业客户数");
        derivedMetric("DERIVED_ZB002_DIV_ZB001", "存贷比", "ZB002", "ZB001", "存贷比");
        derivedMetric("DERIVED_ZB011_DIV_ZB009", "净利润率", "ZB011", "ZB009", "净利润率");
        derivedMetric("DERIVED_ZB011_DIV_ZB018", "人均利润", "ZB011", "ZB018", "人均净利润");

        // Catalog facts (unit, business direction, one-line description) live here so every
        // consumer — prompt registry, compiler, projector — renders one source of truth.
        metricMeta("ZB001", "亿元", MetricDirection.NEUTRAL, "对公存款与个人存款合计的期末总余额");
        metricMeta("ZB002", "亿元", MetricDirection.NEUTRAL, "对公贷款与个人贷款合计的期末总余额");
        metricMeta("ZB003", "亿元", MetricDirection.NEUTRAL, "企业与机构客户存款的期末余额");
        metricMeta("ZB004", "亿元", MetricDirection.NEUTRAL, "个人与储蓄客户存款的期末余额");
        metricMeta("ZB005", "亿元", MetricDirection.NEUTRAL, "企业与机构客户贷款的期末余额");
        metricMeta("ZB006", "亿元", MetricDirection.NEUTRAL, "个人与零售客户贷款的期末余额");
        metricMeta("ZB007", "亿元", MetricDirection.NEUTRAL, "手续费及佣金等中间业务收入");
        metricMeta("ZB008", "亿元", MetricDirection.NEUTRAL, "利息收入扣除利息支出后的净额");
        metricMeta("ZB009", "亿元", MetricDirection.NEUTRAL, "全部营业业务收入合计");
        metricMeta("ZB010", "亿元", MetricDirection.NEUTRAL, "全部营业业务支出合计");
        metricMeta("ZB011", "万元", MetricDirection.NEUTRAL, "扣除成本与税费后的当期利润");
        metricMeta("ZB012", "%", MetricDirection.LOWER_BETTER, "营业支出占营业收入的比率");
        metricMeta("ZB013", "%", MetricDirection.LOWER_BETTER, "不良贷款余额占各项贷款余额的比率");
        metricMeta("ZB014", "亿元", MetricDirection.NEUTRAL, "五级分类不良贷款的期末余额");
        metricMeta("ZB015", "%", MetricDirection.HIGHER_BETTER, "贷款减值准备对不良贷款余额的覆盖比率");
        metricMeta("ZB016", "%", MetricDirection.HIGHER_BETTER, "资本总额对风险加权资产的充足比率");
        metricMeta("ZB017", "%", MetricDirection.LOWER_BETTER, "逾期贷款占各项贷款余额的比率");
        metricMeta("ZB018", "人", MetricDirection.NEUTRAL, "在职员工总数");
        metricMeta("ZB019", "个", MetricDirection.NEUTRAL, "营业网点总数");
        metricMeta("ZB020", "户", MetricDirection.NEUTRAL, "个人（零售）客户总数");
        metricMeta("ZB021", "户", MetricDirection.NEUTRAL, "对公（企业）客户总数");

        for (int index = 0; index < 13; index++) {
            char city = (char) ('A' + index);
            String code = String.format("ORG%03d", index + 1);
            String name = "江苏省" + city + "市农商行";
            organization(code, name, city + "行", city + "市农商行", city + "农商行");
        }
        // Catalog identity: total balance equals the sum of its parts. Order fixes the expected
        // metric-code order of a structure query (parts first, total last).
        compositionGroup("存款构成", "ZB001", List.of("ZB003", "ZB004"));
        compositionGroup("贷款构成", "ZB002", List.of("ZB006", "ZB005"));

        normalize("不良货款率", "不良贷款率");
        normalize("不良货款余额", "不良贷款余额");
        normalize("逾期货款率", "逾期贷款率");
        normalize("成本收人比", "成本收入比");
        normalize("不良率", "不良贷款率");
        normalize("逾期率", "逾期贷款率");
        normalize("拨备率", "拨备覆盖率");
        normalize("资本充足", "资本充足率");
        normalize("存款规模", "各项存款余额");
        normalize("贷款规模", "各项贷款余额");
        normalize("网点数", "网点数量");
        normalize("员工数", "员工人数");
    }

    private BankFinancialLexicon() {}

    private static void metric(String code, String name, String... aliases) {
        List<String> terms = new ArrayList<>();
        terms.add(name);
        terms.addAll(Arrays.asList(aliases));
        terms.sort(Comparator.comparingInt(String::length).reversed());
        METRICS.put(code, new MetricDefinition(code, name, terms));
    }

    private static void metricMeta(String code, String unit, MetricDirection direction,
            String description) {
        MetricDefinition base = METRICS.get(code);
        METRICS.put(code, new MetricDefinition(code, base.getName(), base.getAliases(), unit,
                direction, description));
    }

    private static void organization(String code, String name, String... aliases) {
        List<String> terms = new ArrayList<>();
        terms.add(name);
        terms.addAll(Arrays.asList(aliases));
        terms.sort(Comparator.comparingInt(String::length).reversed());
        ORGANIZATIONS.put(code, new OrganizationDefinition(code, name, terms));
    }

    private static void derivedMetric(String code, String name, String numerator,
            String denominator, String... aliases) {
        List<String> terms = new ArrayList<>();
        terms.add(name);
        terms.addAll(Arrays.asList(aliases));
        terms.sort(Comparator.comparingInt(String::length).reversed());
        DERIVED_METRICS.put(code,
                new DerivedMetricDefinition(code, name, numerator, denominator, terms));
    }

    private static void compositionGroup(String name, String totalCode, List<String> partCodes) {
        COMPOSITION_GROUPS.add(new CompositionGroupDefinition(name, totalCode, partCodes));
    }

    private static void normalize(String source, String target) {
        NORMALIZATIONS.put(source, target);
    }

    public static Map<String, MetricDefinition> metrics() {
        return Collections.unmodifiableMap(METRICS);
    }

    public static Map<String, DerivedMetricDefinition> derivedMetrics() {
        return Collections.unmodifiableMap(DERIVED_METRICS);
    }

    public static List<CompositionGroupDefinition> compositionGroups() {
        return Collections.unmodifiableList(COMPOSITION_GROUPS);
    }

    public static Map<String, OrganizationDefinition> organizations() {
        return Collections.unmodifiableMap(ORGANIZATIONS);
    }

    public static Map<String, String> normalizations() {
        return Collections.unmodifiableMap(NORMALIZATIONS);
    }

    /** Business direction of a metric: whether a higher or lower value is better. */
    public enum MetricDirection {
        HIGHER_BETTER, LOWER_BETTER, NEUTRAL
    }

    @Getter
    public static class MetricDefinition {
        private final String code;
        private final String name;
        private final List<String> aliases;
        private final String unit;
        private final MetricDirection direction;
        private final String description;

        private MetricDefinition(String code, String name, List<String> aliases) {
            this(code, name, aliases, "", MetricDirection.NEUTRAL, name);
        }

        private MetricDefinition(String code, String name, List<String> aliases, String unit,
                MetricDirection direction, String description) {
            this.code = code;
            this.name = name;
            this.aliases = aliases;
            this.unit = unit;
            this.direction = direction;
            this.description = description;
        }
    }

    @Getter
    public static class DerivedMetricDefinition {
        private final String code;
        private final String name;
        private final String numerator;
        private final String denominator;
        private final List<String> aliases;

        private DerivedMetricDefinition(String code, String name, String numerator,
                String denominator, List<String> aliases) {
            this.code = code;
            this.name = name;
            this.numerator = numerator;
            this.denominator = denominator;
            this.aliases = aliases;
        }
    }

    /**
     * A catalog-level composition identity (total = sum of parts). Any question naming at least
     * both parts belongs to the structure family regardless of the exact wording or aliases used.
     */
    @Getter
    public static class CompositionGroupDefinition {
        private final String name;
        private final String totalCode;
        private final List<String> partCodes;

        private CompositionGroupDefinition(String name, String totalCode, List<String> partCodes) {
            this.name = name;
            this.totalCode = totalCode;
            this.partCodes = partCodes;
        }

        /** Expected metric-code order of a structure query: parts in catalog order, total last. */
        public List<String> orderedCodes() {
            List<String> codes = new ArrayList<>(partCodes);
            codes.add(totalCode);
            return codes;
        }
    }

    @Getter
    public static class OrganizationDefinition {
        private final String code;
        private final String name;
        private final List<String> aliases;

        private OrganizationDefinition(String code, String name, List<String> aliases) {
            this.code = code;
            this.name = name;
            this.aliases = aliases;
        }
    }
}
