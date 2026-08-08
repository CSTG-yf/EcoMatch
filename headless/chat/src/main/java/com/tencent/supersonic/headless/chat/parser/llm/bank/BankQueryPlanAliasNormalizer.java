package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps model-emitted Chinese metric/dimension labels onto semantic identifiers before validation.
 *
 * <p>Question-only bank plan prompts intentionally omit per-question catalogs. Local models often
 * still emit display names such as {@code 各项存款余额} in {@code metrics} / {@code output.columns}
 * even when they already chose the correct ZB code elsewhere. Without this rewrite, the strict
 * validator rejects an otherwise correct plan with {@code UNKNOWN_OUTPUT_COLUMN}.
 *
 * <p>When the semantic whitelist already accepts the Chinese label (legacy fixtures / dual-name
 * schemas), the original token is kept so validation stays green.
 */
public final class BankQueryPlanAliasNormalizer {

    /** Longer aliases first so "各项存款余额" wins over "存款余额". */
    private static final Map<String, String> METRIC_ALIASES = new LinkedHashMap<>();
    private static final Map<String, String> DIMENSION_ALIASES = new LinkedHashMap<>();

    static {
        METRIC_ALIASES.put("不良贷款率", "ZB013");
        METRIC_ALIASES.put("不良率", "ZB013");
        METRIC_ALIASES.put("成本收入比", "ZB012");
        METRIC_ALIASES.put("拨备覆盖率", "ZB015");
        METRIC_ALIASES.put("资本充足率", "ZB016");
        METRIC_ALIASES.put("逾期贷款率", "ZB017");
        METRIC_ALIASES.put("逾期率", "ZB017");
        METRIC_ALIASES.put("净利润", "ZB011");
        METRIC_ALIASES.put("营业收入", "ZB009");
        METRIC_ALIASES.put("营业支出", "ZB010");
        METRIC_ALIASES.put("净利息收入", "ZB008");
        METRIC_ALIASES.put("中间业务收入", "ZB007");
        METRIC_ALIASES.put("各项贷款余额", "ZB002");
        METRIC_ALIASES.put("贷款余额", "ZB002");
        METRIC_ALIASES.put("各项存款余额", "ZB001");
        METRIC_ALIASES.put("存款余额", "ZB001");
        METRIC_ALIASES.put("对公贷款", "ZB005");
        METRIC_ALIASES.put("个人贷款", "ZB006");
        METRIC_ALIASES.put("对公存款", "ZB003");
        METRIC_ALIASES.put("个人存款", "ZB004");
        METRIC_ALIASES.put("员工人数", "ZB018");
        METRIC_ALIASES.put("员工数", "ZB018");
        METRIC_ALIASES.put("网点数量", "ZB019");
        METRIC_ALIASES.put("网点数", "ZB019");
        METRIC_ALIASES.put("个人客户数", "ZB020");
        METRIC_ALIASES.put("对公客户数", "ZB021");

        DIMENSION_ALIASES.put("机构", "bank_organization");
        DIMENSION_ALIASES.put("bank_organization", "bank_organization");
        DIMENSION_ALIASES.put("数据日期", "bank_data_date");
        DIMENSION_ALIASES.put("bank_data_date", "bank_data_date");
    }

    private BankQueryPlanAliasNormalizer() {}

    public static BankQueryPlan normalize(BankQueryPlan plan) {
        return normalize(plan, null);
    }

    public static BankQueryPlan normalize(BankQueryPlan plan, SemanticIntentHints hints) {
        if (plan == null) {
            return null;
        }
        Set<String> allowedMetrics = hints == null ? Set.of() : hints.getAllowedMetrics();
        Set<String> allowedDimensions = hints == null ? Set.of() : hints.getAllowedDimensions();

        if (plan.getMetrics() != null) {
            for (BankQueryPlan.Metric metric : plan.getMetrics()) {
                if (metric == null) {
                    continue;
                }
                metric.setBizName(preferAllowed(canonicalizeMetric(metric.getBizName()),
                        metric.getBizName(), allowedMetrics));
            }
        }
        if (plan.getDimensions() != null) {
            List<String> dimensions = new ArrayList<>(plan.getDimensions().size());
            for (String dimension : plan.getDimensions()) {
                dimensions.add(preferAllowed(canonicalizeDimension(dimension), dimension,
                        allowedDimensions));
            }
            plan.setDimensions(dimensions);
        }
        if (plan.getOrderBy() != null) {
            for (BankQueryPlan.OrderBy orderBy : plan.getOrderBy()) {
                if (orderBy == null) {
                    continue;
                }
                String field = orderBy.getField();
                String metric = canonicalizeMetric(field);
                if (!metric.equals(field)) {
                    orderBy.setField(preferAllowed(metric, field, allowedMetrics));
                } else {
                    orderBy.setField(preferAllowed(canonicalizeDimension(field), field,
                            allowedDimensions));
                }
            }
        }
        if (plan.getOutput() != null && plan.getOutput().getColumns() != null) {
            List<String> columns = new ArrayList<>(plan.getOutput().getColumns().size());
            for (String column : plan.getOutput().getColumns()) {
                String metric = canonicalizeMetric(column);
                if (!metric.equals(column)) {
                    columns.add(preferAllowed(metric, column, allowedMetrics));
                } else {
                    columns.add(preferAllowed(canonicalizeDimension(column), column,
                            allowedDimensions));
                }
            }
            plan.getOutput().setColumns(columns);
        }
        if (plan.getCalculation() != null
                && StringUtils.isNotBlank(plan.getCalculation().getBaseline())) {
            String baseline = plan.getCalculation().getBaseline();
            plan.getCalculation().setBaseline(preferAllowed(canonicalizeMetric(baseline), baseline,
                    allowedMetrics));
        }
        // When output still has non-semantic labels, rebuild from normalized metrics/dims.
        if (plan.getOutput() != null && plan.getMetrics() != null && !plan.getMetrics().isEmpty()) {
            boolean stillHasUnknownChinese = plan.getOutput().getColumns() != null
                    && plan.getOutput().getColumns().stream()
                            .anyMatch(column -> column != null && !looksLikeSemanticId(column)
                                    && !containsIgnoreCase(allowedMetrics, column)
                                    && !containsIgnoreCase(allowedDimensions, column));
            if (stillHasUnknownChinese || plan.getOutput().getColumns() == null
                    || plan.getOutput().getColumns().isEmpty()) {
                List<String> canonical = new ArrayList<>();
                if (plan.getDimensions() != null) {
                    canonical.addAll(plan.getDimensions());
                }
                plan.getMetrics().stream().map(BankQueryPlan.Metric::getBizName)
                        .filter(StringUtils::isNotBlank).forEach(canonical::add);
                if (!canonical.isEmpty()) {
                    if (plan.getOutput() == null) {
                        plan.setOutput(BankQueryPlan.Output.builder().build());
                    }
                    plan.getOutput().setColumns(canonical);
                }
            }
        }
        return plan;
    }

    static String canonicalizeMetric(String raw) {
        if (StringUtils.isBlank(raw)) {
            return raw;
        }
        String trimmed = raw.trim();
        if (METRIC_ALIASES.containsKey(trimmed)) {
            return METRIC_ALIASES.get(trimmed);
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.matches("ZB\\d{3}")) {
            return upper;
        }
        for (Map.Entry<String, String> entry : METRIC_ALIASES.entrySet()) {
            if (trimmed.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return trimmed;
    }

    static String canonicalizeDimension(String raw) {
        if (StringUtils.isBlank(raw)) {
            return raw;
        }
        String trimmed = raw.trim();
        return DIMENSION_ALIASES.getOrDefault(trimmed, trimmed);
    }

    /**
     * Prefer the rewritten semantic id when the whitelist accepts it (or is empty). Keep the raw
     * token when only the original form is whitelisted (e.g. Chinese dimension labels).
     */
    private static String preferAllowed(String rewritten, String original, Set<String> allowed) {
        if (StringUtils.isBlank(rewritten)) {
            return original;
        }
        if (allowed == null || allowed.isEmpty()) {
            return rewritten;
        }
        if (containsIgnoreCase(allowed, rewritten)) {
            return findCanonical(allowed, rewritten);
        }
        if (containsIgnoreCase(allowed, original)) {
            return findCanonical(allowed, original);
        }
        return rewritten;
    }

    private static boolean containsIgnoreCase(Set<String> allowed, String value) {
        if (allowed == null || value == null) {
            return false;
        }
        return allowed.stream().anyMatch(item -> item != null && item.equalsIgnoreCase(value));
    }

    private static String findCanonical(Set<String> allowed, String value) {
        return allowed.stream().filter(item -> item != null && item.equalsIgnoreCase(value))
                .findFirst().orElse(value);
    }

    private static boolean looksLikeSemanticId(String column) {
        if (StringUtils.isBlank(column)) {
            return false;
        }
        String upper = column.toUpperCase(Locale.ROOT);
        return upper.matches("ZB\\d{3}") || DIMENSION_ALIASES.containsValue(column)
                || upper.startsWith("ORG") || upper.startsWith("DERIVED_");
    }
}
