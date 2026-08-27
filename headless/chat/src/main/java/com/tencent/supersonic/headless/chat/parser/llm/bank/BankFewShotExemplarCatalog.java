package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small, auditable contract-shape examples for the model-owned bank planning route.
 *
 * <p>The examples deliberately contain no executable SQL, answer values, evaluation identifiers,
 * or complete benchmark questions. They are only rendered into the dynamic user message when
 * the explicit few-shot experiment switch is enabled; the fixed system prefixes never depend on
 * this catalog.
 */
public final class BankFewShotExemplarCatalog {

    public enum QueryFamily {
        RANKED_CHANGE, PROVINCE_AVERAGE, STRUCTURE_SHARE, DERIVED_RATIO, MOM_AND_YOY,
        BOTTOM_RANKING
    }

    public record Exemplar(QueryFamily family, String question, String requirementsJson,
            String planJson) {}

    private static final List<Exemplar> EXEMPLARS = List.of(
            new Exemplar(QueryFamily.RANKED_CHANGE,
                    "按某项指标从基期到当前期的增幅，列出全省前两家机构。",
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"CHANGE","metricCodes":["ZB011"],"derivedMetrics":[],"organizationCodes":[],"time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"PERIOD_OVER_PERIOD","baselineStartDate":"2024-12-31","baselineEndDate":"2024-12-31"},"filters":[],"requiredLimit":2,"answerFactTypes":["VALUE","RANK","CHANGE_RATE"],"clarification":null}
                            """,
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"CHANGE","metrics":[{"bizName":"ZB011","aggregation":"DEFAULT","alias":null}],"derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[],"time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"PERIOD_OVER_PERIOD","baselineStartDate":"2024-12-31","baselineEndDate":"2024-12-31"},"filters":[],"calculation":{"type":"CHANGE","baseline":null},"orderBy":[],"limit":2,"output":{"columns":["bank_organization","ZB011"],"orderSensitive":true}}
                            """),
            new Exemplar(QueryFamily.PROVINCE_AVERAGE,
                    "比较某机构在指定日期的存款与全省日均值的差额。",
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"AGGREGATION","metricCodes":["ZB001"],"derivedMetrics":[],"organizationCodes":["ORG001"],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}],"requiredLimit":null,"answerFactTypes":["VALUE","GAP_VALUE"],"clarification":null}
                            """,
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"AGGREGATION","metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],"derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG001","bizName":null}],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,"output":{"columns":["bank_organization","ZB001"],"orderSensitive":false}}
                            """),
            new Exemplar(QueryFamily.STRUCTURE_SHARE,
                    "某机构某日的对公、个人存款分别占各项存款多少？",
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY","metricCodes":["ZB003","ZB004","ZB001"],"derivedMetrics":[],"organizationCodes":["ORG001"],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","RATIO_VALUE"],"clarification":null}
                            """,
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY","metrics":[{"bizName":"ZB003","aggregation":"DEFAULT","alias":null},{"bizName":"ZB004","aggregation":"DEFAULT","alias":null},{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],"derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG001","bizName":null}],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,"output":{"columns":["bank_organization","ZB003","ZB004","ZB001"],"orderSensitive":true}}
                            """),
            new Exemplar(QueryFamily.DERIVED_RATIO,
                    "某机构某日的贷款占存款比例是多少？",
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"RATIO","metricCodes":["ZB002","ZB001"],"derivedMetrics":[{"metricCode":"DERIVED_ZB002_DIV_ZB001","numerator":"ZB002","denominator":"ZB001","name":"贷款占存款比例"}],"organizationCodes":["ORG001"],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"requiredLimit":null,"answerFactTypes":["RATIO_VALUE"],"clarification":null}
                            """,
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"RATIO","metrics":[{"bizName":"ZB002","aggregation":"DEFAULT","alias":null},{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],"derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG001","bizName":null}],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"RATIO","baseline":"ZB001"},"orderBy":[],"limit":null,"output":{"columns":["bank_organization","ZB002","ZB001"],"orderSensitive":false}}
                            """),
            new Exemplar(QueryFamily.MOM_AND_YOY,
                    "同一机构同一月末同时查看某指标的环比和同比变化。",
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"CHANGE","metricCodes":["ZB001"],"derivedMetrics":[],"organizationCodes":["ORG001"],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"MOM_AND_YOY","baselineStartDate":null,"baselineEndDate":null},"filters":[],"requiredLimit":null,"answerFactTypes":["CHANGE_VALUE","CHANGE_RATE"],"clarification":null}
                            """,
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"CHANGE","metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],"derivedMetrics":[],"dimensions":[],"organizations":[{"code":"ORG001","bizName":null}],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"MOM_AND_YOY","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"CHANGE","baseline":null},"orderBy":[],"limit":null,"output":{"columns":["ZB001"],"orderSensitive":false}}
                            """),
            new Exemplar(QueryFamily.BOTTOM_RANKING,
                    "按某项指标列出全省倒数前两家机构。",
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"RANKING","metricCodes":["ZB013"],"derivedMetrics":[],"organizationCodes":[],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[{"field":"rank_from_bottom","operator":"LTE","value":"2","values":[]}],"requiredLimit":2,"answerFactTypes":["VALUE","RANK"],"clarification":null}
                            """,
                    """
                            {"version":"1.0","action":"EXECUTE","intent":"RANKING","metrics":[{"bizName":"ZB013","aggregation":"DEFAULT","alias":null}],"derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[],"time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[{"field":"rank_from_bottom","operator":"LTE","value":"2","values":[]}],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[{"field":"ZB013","direction":"ASC"}],"limit":2,"output":{"columns":["bank_organization","ZB013"],"orderSensitive":true}}
                            """)
    );

    private BankFewShotExemplarCatalog() {}

    public static List<Exemplar> exemplars() {
        return EXEMPLARS;
    }

    /** Returns one high-signal example for the query family recognized from the user question. */
    public static String renderRequirementsExamples(String queryText) {
        QueryFamily family = familyForQuestion(queryText);
        return render(family, false);
    }

    /** Returns one example whose contract shape matches the already validated requirements. */
    public static String renderPlanExamples(BankRequestContract contract) {
        return render(familyForContract(contract), true);
    }

    /** Returns one complete one-pass response example selected only from the abstract query family. */
    public static String renderSinglePassExamples(String queryText) {
        QueryFamily family = familyForQuestion(queryText);
        if (family == null) {
            return "";
        }
        Exemplar exemplar = EXEMPLARS.stream().filter(item -> item.family() == family).findFirst()
                .orElse(null);
        if (exemplar == null) {
            return "";
        }
        return "family=" + family.name() + "\n问句：" + exemplar.question().strip()
                + "\nBankPlanningResponse 契约形态：\n{\"requirements\":"
                + exemplar.requirementsJson().strip() + ",\"plan\":"
                + exemplar.planJson().strip() + "}";
    }

    private static String render(QueryFamily family, boolean includePlan) {
        if (family == null) {
            return "";
        }
        Exemplar exemplar = EXEMPLARS.stream().filter(item -> item.family() == family).findFirst()
                .orElse(null);
        if (exemplar == null) {
            return "";
        }
        StringBuilder result = new StringBuilder("family=").append(family.name())
                .append("\n问句：").append(exemplar.question().strip())
                .append("\nrequirements 契约形态：\n").append(exemplar.requirementsJson().strip());
        if (includePlan) {
            result.append("\nplan 契约形态：\n").append(exemplar.planJson().strip());
        }
        return result.toString();
    }

    private static QueryFamily familyForQuestion(String queryText) {
        String text = queryText == null ? "" : queryText.toLowerCase(Locale.ROOT);
        if ((text.contains("环比") && text.contains("同比")) || text.contains("mom_and_yoy")) {
            return QueryFamily.MOM_AND_YOY;
        }
        if ((text.contains("增幅") || text.contains("增速"))
                && (text.contains("排名") || text.contains("前"))) {
            return QueryFamily.RANKED_CHANGE;
        }
        if ((text.contains("全省均值") || text.contains("省均值"))
                && (text.contains("比较") || text.contains("相比") || text.contains("高于")
                        || text.contains("低于")
                        || text.contains("差额") || text.contains("相差"))) {
            return QueryFamily.PROVINCE_AVERAGE;
        }
        if ((text.contains("对公") && text.contains("个人"))
                && (text.contains("存款") || text.contains("贷款"))
                && (text.contains("比例") || text.contains("占比") || text.contains("构成"))) {
            return QueryFamily.STRUCTURE_SHARE;
        }
        if (text.contains("存贷比") || text.contains("人均") || text.contains("派生比例")) {
            return QueryFamily.DERIVED_RATIO;
        }
        if (text.contains("倒数") || text.contains("排最后") || text.contains("末位")) {
            return QueryFamily.BOTTOM_RANKING;
        }
        return null;
    }

    private static QueryFamily familyForContract(BankRequestContract contract) {
        if (contract == null) {
            return null;
        }
        if (contract.getIntent() == BankIntentType.CHANGE && contract.getTime() != null
                && contract.getTime().getComparison() == BankQueryPlan.TimeComparison.MOM_AND_YOY) {
            return QueryFamily.MOM_AND_YOY;
        }
        if (contract.getIntent() == BankIntentType.RANKING && hasFilter(contract, "rank_from_bottom")) {
            return QueryFamily.BOTTOM_RANKING;
        }
        if (contract.getIntent() == BankIntentType.CHANGE && contract.getRequiredLimit() != null) {
            return QueryFamily.RANKED_CHANGE;
        }
        if (hasProvinceAverageFilter(contract)) {
            return QueryFamily.PROVINCE_AVERAGE;
        }
        Set<String> metrics = new LinkedHashSet<>(contract.getMetricCodes() == null
                ? List.of() : contract.getMetricCodes());
        if (metrics.equals(Set.of("ZB001", "ZB003", "ZB004"))
                || metrics.equals(Set.of("ZB002", "ZB005", "ZB006"))) {
            return QueryFamily.STRUCTURE_SHARE;
        }
        if (contract.getIntent() == BankIntentType.RATIO
                || contract.getDerivedMetrics() != null && !contract.getDerivedMetrics().isEmpty()) {
            return QueryFamily.DERIVED_RATIO;
        }
        return null;
    }

    private static boolean hasProvinceAverageFilter(BankRequestContract contract) {
        return contract.getFilters() != null && contract.getFilters().stream().anyMatch(filter ->
                filter != null && "benchmark".equals(filter.getField())
                        && "COMPARE".equals(filter.getOperator())
                        && "PROVINCE_AVERAGE".equals(filter.getValue()));
    }

    private static boolean hasFilter(BankRequestContract contract, String field) {
        return contract.getFilters() != null && contract.getFilters().stream().anyMatch(filter ->
                filter != null && field.equals(filter.getField()));
    }
}
