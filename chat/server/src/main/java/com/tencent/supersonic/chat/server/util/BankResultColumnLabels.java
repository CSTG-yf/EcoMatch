package com.tencent.supersonic.chat.server.util;

import java.util.Map;

/**
 * Fixed Chinese display labels for the bank projector contract columns emitted by
 * {@code BankResultProjector} (org_code/org_name/metric_code/metric_value/rank_position and
 * other gold-contract fields). Used only for presentation in snapshot exports; data rows stay
 * keyed by bizName.
 */
public final class BankResultColumnLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("org_code", "机构代码"),
            Map.entry("org_name", "机构名称"),
            Map.entry("metric_code", "指标代码"),
            Map.entry("metric_value", "指标值"),
            Map.entry("rank_position", "排名"),
            Map.entry("metric_role", "指标角色"),
            Map.entry("aggregate_value", "汇总值"),
            Map.entry("min_value", "最小值"),
            Map.entry("max_value", "最大值"),
            Map.entry("avg_value", "平均值"),
            Map.entry("ratio_percent", "占比(%)"),
            Map.entry("days_above_average", "高于均值天数"),
            Map.entry("total_days", "总天数"),
            Map.entry("data_date", "数据日期"),
            Map.entry("quarter_change", "季度变化"),
            Map.entry("current_value", "当前值"),
            Map.entry("baseline_value", "基线值"),
            Map.entry("value_difference", "差值"),
            Map.entry("provincial_average", "全省均值"),
            Map.entry("numerator_value", "分子值"),
            Map.entry("denominator_value", "分母值"),
            Map.entry("deposit_value", "存款值"),
            Map.entry("net_profit", "净利润"));

    private BankResultColumnLabels() {}

    /** Returns the fixed Chinese label for a projector contract column, or null if unknown. */
    public static String labelOf(String bizName) {
        return bizName == null ? null : LABELS.get(bizName);
    }
}
