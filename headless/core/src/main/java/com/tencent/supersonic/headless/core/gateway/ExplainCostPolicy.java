package com.tencent.supersonic.headless.core.gateway;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts estimated row counts from common EXPLAIN formats and enforces a ceiling. */
public class ExplainCostPolicy {

    private static final Pattern TEXT_ROWS =
            Pattern.compile("(?i)(?:\\bplan[\\s_-]*rows|\\bestimated[\\s_-]*rows|\\brows)"
                    + "\\s*[\"']?\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final int MAX_PLAN_DEPTH = 32;

    private final long maxEstimatedRows;
    private final boolean requireEstimate;

    public ExplainCostPolicy(long maxEstimatedRows) {
        this(maxEstimatedRows, false);
    }

    public ExplainCostPolicy(long maxEstimatedRows, boolean requireEstimate) {
        this.maxEstimatedRows = maxEstimatedRows;
        this.requireEstimate = requireEstimate;
    }

    public long validate(List<Map<String, Object>> plan) {
        Estimate estimate = estimate(plan);
        if (requireEstimate && !estimate.found()) {
            throw new QueryRejectedException(
                    "EXPLAIN did not return a supported estimated row count");
        }
        long estimatedRows = estimate.rows();
        if (estimatedRows > maxEstimatedRows) {
            throw new QueryRejectedException(String.format(
                    "EXPLAIN estimated %,d rows, exceeding the configured maximum of %,d",
                    estimatedRows, maxEstimatedRows));
        }
        return estimatedRows;
    }

    long estimateRows(List<Map<String, Object>> plan) {
        return estimate(plan).rows();
    }

    private Estimate estimate(Object plan) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return estimate(plan, visited, 0);
    }

    private Estimate estimate(Object value, Set<Object> visited, int depth) {
        if (value == null || depth > MAX_PLAN_DEPTH) {
            return Estimate.missing();
        }
        if (value instanceof CharSequence) {
            return estimateText(String.valueOf(value));
        }
        if (value instanceof Map<?, ?> map) {
            if (!visited.add(value)) {
                return Estimate.missing();
            }
            Estimate estimate = Estimate.missing();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = normalizeKey(entry.getKey());
                Object nested = entry.getValue();
                if (isRowEstimateKey(key)) {
                    estimate = estimate.merge(estimateKnownRowValue(nested));
                }
                estimate = estimate.merge(estimate(nested, visited, depth + 1));
            }
            return estimate;
        }
        if (value instanceof Iterable<?> iterable) {
            if (!visited.add(value)) {
                return Estimate.missing();
            }
            Estimate estimate = Estimate.missing();
            for (Object nested : iterable) {
                estimate = estimate.merge(estimate(nested, visited, depth + 1));
            }
            return estimate;
        }
        if (value.getClass().isArray()) {
            if (!visited.add(value)) {
                return Estimate.missing();
            }
            Estimate estimate = Estimate.missing();
            for (int i = 0; i < Array.getLength(value); i++) {
                estimate = estimate.merge(estimate(Array.get(value, i), visited, depth + 1));
            }
            return estimate;
        }
        return Estimate.missing();
    }

    private Estimate estimateText(String text) {
        Matcher matcher = TEXT_ROWS.matcher(text);
        Estimate estimate = Estimate.missing();
        while (matcher.find()) {
            estimate = estimate.merge(estimateKnownRowValue(matcher.group(1)));
        }
        return estimate;
    }

    private String normalizeKey(Object key) {
        return String.valueOf(key).trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ',
                '_');
    }

    private boolean isRowEstimateKey(String key) {
        return key.equals("rows") || key.equals("plan_rows") || key.equals("estimated_rows");
    }

    private Estimate estimateKnownRowValue(Object value) {
        if (!(value instanceof Number) && !(value instanceof CharSequence)) {
            return Estimate.missing();
        }
        try {
            double rows = Double.parseDouble(String.valueOf(value).trim());
            if (!Double.isFinite(rows) || rows < 0) {
                return Estimate.missing();
            }
            return new Estimate(true,
                    rows >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(rows));
        } catch (NumberFormatException e) {
            return Estimate.missing();
        }
    }

    private record Estimate(boolean found, long rows) {

        private static Estimate missing() {
            return new Estimate(false, 0);
        }

        private Estimate merge(Estimate other) {
            return new Estimate(found || other.found, Math.max(rows, other.rows));
        }
    }
}
