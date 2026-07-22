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
    private static final Map<String, OrganizationDefinition> ORGANIZATIONS = new LinkedHashMap<>();
    private static final Map<String, String> NORMALIZATIONS = new LinkedHashMap<>();

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

        for (int index = 0; index < 13; index++) {
            char city = (char) ('A' + index);
            String code = String.format("ORG%03d", index + 1);
            String name = "江苏省" + city + "市农商行";
            organization(code, name, city + "行", city + "市农商行", city + "农商行");
        }

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

    private static void organization(String code, String name, String... aliases) {
        List<String> terms = new ArrayList<>();
        terms.add(name);
        terms.addAll(Arrays.asList(aliases));
        terms.sort(Comparator.comparingInt(String::length).reversed());
        ORGANIZATIONS.put(code, new OrganizationDefinition(code, name, terms));
    }

    private static void normalize(String source, String target) {
        NORMALIZATIONS.put(source, target);
    }

    public static Map<String, MetricDefinition> metrics() {
        return Collections.unmodifiableMap(METRICS);
    }

    public static Map<String, OrganizationDefinition> organizations() {
        return Collections.unmodifiableMap(ORGANIZATIONS);
    }

    public static Map<String, String> normalizations() {
        return Collections.unmodifiableMap(NORMALIZATIONS);
    }

    @Getter
    public static class MetricDefinition {
        private final String code;
        private final String name;
        private final List<String> aliases;

        private MetricDefinition(String code, String name, List<String> aliases) {
            this.code = code;
            this.name = name;
            this.aliases = aliases;
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
