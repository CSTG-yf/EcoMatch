package com.tencent.supersonic.headless.chat.parser.llm.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Canonicalizes recognizable province-average filter slots in the requirements contract.
 *
 * <p>The repair loop shows the model repeatedly expressing an unambiguous province-average
 * comparison in a slightly non-canonical filter shape (wrong operator, wrong field, extra
 * values) and failing to correct it within the retry budget. Semantics are never in doubt
 * when the PROVINCE_AVERAGE value sits in a comparison slot, so the shape is rewritten to the
 * one published benchmark filter instead of bouncing it back to the model. Filters without a
 * recognizable province-average slot are returned untouched and keep failing validation.</p>
 */
public final class BankProvinceAverageFilterNormalizer {

    private static final Set<String> DIRECTION_OPERATORS = Set.of("GT", "GTE", "LT", "LTE");

    private BankProvinceAverageFilterNormalizer() {
    }

    public static List<BankQueryPlan.Filter> normalize(List<BankQueryPlan.Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return filters;
        }
        List<BankQueryPlan.Filter> normalized = new ArrayList<>(filters.size());
        boolean changed = false;
        boolean hasBenchmark = false;
        boolean hasDirection = false;
        for (BankQueryPlan.Filter filter : filters) {
            if (filter == null || !mentionsProvinceAverage(filter)) {
                normalized.add(filter);
                continue;
            }
            if ("benchmark".equals(filter.getField()) || "COMPARE".equals(filter.getOperator())) {
                if (hasBenchmark) {
                    changed = true;
                    continue;
                }
                normalized.add(canonicalBenchmark());
                hasBenchmark = true;
                changed = true;
                continue;
            }
            if ("metric_value".equals(filter.getField())
                    && DIRECTION_OPERATORS.contains(filter.getOperator())) {
                normalized.add(BankQueryPlan.Filter.builder()
                        .field("metric_value")
                        .operator(filter.getOperator())
                        .value("PROVINCE_AVERAGE")
                        .values(List.of())
                        .build());
                hasDirection = true;
                changed = changed || !isCanonicalDirection(filter);
                continue;
            }
            normalized.add(filter);
        }
        if (hasDirection && !hasBenchmark) {
            normalized.add(canonicalBenchmark());
            changed = true;
        }
        return changed ? normalized : filters;
    }

    private static boolean mentionsProvinceAverage(BankQueryPlan.Filter filter) {
        if ("PROVINCE_AVERAGE".equals(filter.getValue())) {
            return true;
        }
        return filter.getValues() != null && filter.getValues().contains("PROVINCE_AVERAGE");
    }

    private static boolean isCanonicalDirection(BankQueryPlan.Filter filter) {
        return "PROVINCE_AVERAGE".equals(filter.getValue())
                && (filter.getValues() == null || filter.getValues().isEmpty());
    }

    private static BankQueryPlan.Filter canonicalBenchmark() {
        return BankQueryPlan.Filter.builder()
                .field("benchmark")
                .operator("COMPARE")
                .value("PROVINCE_AVERAGE")
                .values(List.of())
                .build();
    }
}
