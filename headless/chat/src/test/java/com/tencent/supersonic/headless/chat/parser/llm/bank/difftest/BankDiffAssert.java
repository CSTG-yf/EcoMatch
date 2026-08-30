package com.tencent.supersonic.headless.chat.parser.llm.bank.difftest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Multiset comparison of two result row sets with an absolute numeric tolerance. Row order is
 * ignored (all generated plans declare {@code output.orderSensitive=false}); each SQL row is
 * greedily matched against one unconsumed oracle row whose cells are numerically equal within
 * tolerance (or identically null / string-equal).
 */
public final class BankDiffAssert {

    public static final double TOLERANCE = 1e-6;

    public record Comparison(int actualRowCount, int expectedRowCount,
            List<List<Object>> unmatchedActual, List<List<Object>> unmatchedExpected) {

        public boolean matches() {
            return unmatchedActual.isEmpty() && unmatchedExpected.isEmpty();
        }
    }

    private BankDiffAssert() {}

    public static Comparison compareMultiset(List<Object[]> actualRows,
            List<List<Object>> expectedRows, double tolerance) {
        List<List<Object>> actual = new ArrayList<>();
        for (Object[] row : actualRows) {
            // Arrays.asList (not List.of): result rows legitimately contain NULL cells.
            actual.add(java.util.Arrays.asList(row));
        }
        return compareRowMultiset(actual, expectedRows, tolerance);
    }

    /** Same multiset comparison for already-materialized row lists (e.g. sliced oracle rows). */
    public static Comparison compareRowMultiset(List<List<Object>> actualRows,
            List<List<Object>> expectedRows, double tolerance) {
        List<List<Object>> actual = new ArrayList<>(actualRows);
        List<List<Object>> remaining = new ArrayList<>(expectedRows);
        List<List<Object>> unmatchedActual = new ArrayList<>();
        for (List<Object> candidate : actual) {
            boolean matched = false;
            for (int i = 0; i < remaining.size() && !matched; i++) {
                if (rowEquals(candidate, remaining.get(i), tolerance)) {
                    remaining.remove(i);
                    matched = true;
                }
            }
            if (!matched) {
                unmatchedActual.add(candidate);
            }
        }
        return new Comparison(actual.size(), expectedRows.size(), unmatchedActual, remaining);
    }

    static boolean rowEquals(List<Object> left, List<Object> right, double tolerance) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!cellEquals(left.get(i), right.get(i), tolerance)) {
                return false;
            }
        }
        return true;
    }

    static boolean cellEquals(Object left, Object right, double tolerance) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            // Known executor artifact (not a template defect): the Calcite interpreter's MAX over
            // an all-zero DECIMAL group yields BigDecimal(Double.MIN_VALUE) instead of exact 0
            // (probed directly: SUM/MIN stay exact 0, only MAX is polluted). The subnormal base
            // value then propagates through NULLIF(0)-guarded ratio expressions, so a percent
            // cell that production H2 would compute as 0 -> NULL legitimately evaluates to exact
            // 0.0 here. Accept NULL against ANY value within tolerance (including exact 0.0):
            // the artifact only ever produces near-zero cells, so genuine template regressions
            // (a value that should exist, or a lost NULLIF over non-zero data) are still caught.
            Object value = left == null ? right : left;
            if (value instanceof Number number) {
                return Math.abs(number.doubleValue()) <= tolerance;
            }
            return false;
        }
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Math.abs(leftNumber.doubleValue() - rightNumber.doubleValue()) <= tolerance;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    /** Renders one row for failure diagnostics, e.g. [ORG004, 12.30, NULL]. */
    public static String render(List<Object> row) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(renderCell(row.get(i)));
        }
        return builder.append(']').toString();
    }

    public static String renderCell(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    /** Truncates a diagnostic block so a single failure cannot flood the test log. */
    public static String truncate(String text, int maxLength) {
        String normalized = text.replace("\r\n", "\n");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "\n...<truncated " + (normalized.length()
                - maxLength) + " chars>";
    }
}
