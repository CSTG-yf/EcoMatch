package com.tencent.supersonic.headless.core.gateway;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts estimated row counts from common EXPLAIN formats and enforces a ceiling. */
public class ExplainCostPolicy {

    private static final Pattern TEXT_ROWS =
            Pattern.compile("(?i)\\b(?:plan_)?rows\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)");

    private final long maxEstimatedRows;

    public ExplainCostPolicy(long maxEstimatedRows) {
        this.maxEstimatedRows = maxEstimatedRows;
    }

    public long validate(List<Map<String, Object>> plan) {
        long estimatedRows = estimateRows(plan);
        if (estimatedRows > maxEstimatedRows) {
            throw new QueryRejectedException(String.format(
                    "EXPLAIN estimated %,d rows, exceeding the configured maximum of %,d",
                    estimatedRows, maxEstimatedRows));
        }
        return estimatedRows;
    }

    long estimateRows(List<Map<String, Object>> plan) {
        long max = 0;
        for (Map<String, Object> row : plan) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                Object value = entry.getValue();
                if ((key.equals("rows") || key.equals("plan_rows") || key.equals("estimated_rows"))
                        && value instanceof Number) {
                    max = Math.max(max, ((Number) value).longValue());
                }
                if (value != null) {
                    Matcher matcher = TEXT_ROWS.matcher(String.valueOf(value));
                    while (matcher.find()) {
                        max = Math.max(max, (long) Double.parseDouble(matcher.group(1)));
                    }
                }
            }
        }
        return max;
    }
}
