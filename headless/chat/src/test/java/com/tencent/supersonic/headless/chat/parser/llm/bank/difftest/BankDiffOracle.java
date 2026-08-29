package com.tencent.supersonic.headless.chat.parser.llm.bank.difftest;

import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankSemanticRegistry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.TreeMap;

/**
 * Naive, SQL-free evaluation of a compiled query family against {@link BankDiffDataset}.
 *
 * <p>The oracle re-implements the documented semantics of the four target template families by
 * directly filtering, aggregating, deriving, comparing and sorting the synthetic rows in Java.
 * It emits rows whose columns line up position-for-position with the outer SELECT of the compiled
 * family SQL (i.e. {@code CompiledQuery#getOutputColumns()}), so the differential assertion is a
 * pure multiset comparison of independently computed results.
 */
public final class BankDiffOracle {

    /**
     * One oracle per template variant the generator produces. The variant is chosen by the plan
     * generator (which owns the routing-relevant shape facts) exactly along the boundaries of
     * {@code BankQueryPlanCompiler#declaredFamily} and the template-side single/multi splits.
     */
    public enum Variant {
        /** AGGREGATION_SUMMARY, single metric: per-org AVG/MIN/MAX/COUNT over daily sums. */
        AGGREGATION_SINGLE,
        /** AGGREGATION_SUMMARY, multiple metrics: long form with metric_code, org filter applied. */
        AGGREGATION_MULTI,
        /** PROVINCE_AVERAGE via aggregation/threshold-multi templates: full population long form. */
        PROVINCE_AVERAGE_FULL_POPULATION,
        /** PROVINCE_AVERAGE threshold template: per-org SUM vs provincial average + meets flag. */
        PROVINCE_AVERAGE_THRESHOLD,
        /** RATIO: SUM(numerator), SUM(denominator), scaled ratio with NULLIF(0) semantics. */
        RATIO,
        /** CHANGE, single metric, ungrouped: current vs baseline scalar plus abs/percent change. */
        CHANGE_SCALAR,
        /** CHANGE, single metric, org-grouped: endpoint-day pivot plus abs/percent change. */
        CHANGE_PIVOT,
        /** CHANGE, multiple metrics: long-form per-(org,)date sums over the union window. */
        CHANGE_MULTI_METRIC,
        /** CHANGE MOM_AND_YOY: scalar current + derived month/year baselines. */
        CHANGE_MOM_AND_YOY,
        /**
         * DERIVED_RANKING: full-population per-metric ranking (direct metrics + derived ratios),
         * long-form metric_code / bank_organization / metric_value / rank_position rows over the
         * complete organization population (the selected-organization restriction is applied after
         * ranking, exactly like the template's outer WHERE).
         */
        DERIVED_RANKING,
        /** ABSOLUTE_THRESHOLD: single-org SUM vs a numeric metric_value literal + meets flag. */
        ABSOLUTE_THRESHOLD
    }

    private static final MathContext DIVISION = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal(100);

    private final BankDiffDataset dataset;

    public BankDiffOracle(BankDiffDataset dataset) {
        this.dataset = dataset;
    }

    public List<List<Object>> evaluate(BankQueryPlan plan, Variant variant) {
        return switch (variant) {
            case AGGREGATION_SINGLE -> aggregationSingle(plan);
            case AGGREGATION_MULTI -> aggregationLongForm(plan, false);
            case PROVINCE_AVERAGE_FULL_POPULATION -> aggregationLongForm(plan, true);
            case PROVINCE_AVERAGE_THRESHOLD -> provinceAverageThreshold(plan);
            case RATIO -> ratio(plan);
            case CHANGE_SCALAR -> changeScalar(plan);
            case CHANGE_PIVOT -> changePivot(plan);
            case CHANGE_MULTI_METRIC -> changeMultiMetric(plan);
            case CHANGE_MOM_AND_YOY -> changeMomAndYear(plan);
            case DERIVED_RANKING -> derivedRanking(plan);
            case ABSOLUTE_THRESHOLD -> absoluteThreshold(plan);
        };
    }

    /** [bank_organization, aggregate_value, min_value, max_value, observation_count]. */
    private List<List<Object>> aggregationSingle(BankQueryPlan plan) {
        String code = plan.getMetrics().get(0).getBizName();
        List<String> orgScope = organizationCodes(plan);
        LocalDate start = plan.getTime().getStartDate();
        LocalDate end = plan.getTime().getEndDate();
        List<List<Object>> rows = new ArrayList<>();
        for (String org : BankDiffDataset.ORGS) {
            if (!orgScope.isEmpty() && !orgScope.contains(org)) {
                continue;
            }
            List<BigDecimal> daily = dataset.dailySums(org, code, start, end);
            rows.add(Arrays.asList(org, average(daily), min(daily), max(daily),
                    (long) daily.size()));
        }
        return rows;
    }

    /** [bank_organization, metric_code, aggregate_value, min_value, max_value, observation_count]. */
    private List<List<Object>> aggregationLongForm(BankQueryPlan plan, boolean fullPopulation) {
        List<String> codes = plan.getMetrics().stream().map(BankQueryPlan.Metric::getBizName)
                .sorted().toList();
        List<String> orgScope = fullPopulation ? List.of() : organizationCodes(plan);
        LocalDate start = plan.getTime().getStartDate();
        LocalDate end = plan.getTime().getEndDate();
        List<List<Object>> rows = new ArrayList<>();
        for (String code : codes) {
            for (String org : BankDiffDataset.ORGS) {
                if (!orgScope.isEmpty() && !orgScope.contains(org)) {
                    continue;
                }
                List<BigDecimal> daily = dataset.dailySums(org, code, start, end);
                rows.add(Arrays.asList(org, code, average(daily), min(daily), max(daily),
                        (long) daily.size()));
            }
        }
        return rows;
    }

    /** [bank_organization, metric_value, provincial_average, meets_condition]. */
    private List<List<Object>> provinceAverageThreshold(BankQueryPlan plan) {
        String code = plan.getMetrics().get(0).getBizName();
        List<String> orgScope = organizationCodes(plan);
        LocalDate start = plan.getTime().getStartDate();
        LocalDate end = plan.getTime().getEndDate();
        Map<String, BigDecimal> sums = new TreeMap<>();
        for (String org : BankDiffDataset.ORGS) {
            sums.put(org, dataset.sumOverOrgs(List.of(org), code, start, end));
        }
        BigDecimal provincialAverage = average(new ArrayList<>(sums.values()));
        String operator = plan.getFilters().stream()
                .filter(filter -> "metric_value".equals(filter.getField())
                        && "PROVINCE_AVERAGE".equals(filter.getValue()))
                .map(BankQueryPlan.Filter::getOperator).findFirst().orElse("GT");
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : sums.entrySet()) {
            if (!orgScope.isEmpty() && !orgScope.contains(entry.getKey())) {
                continue;
            }
            int comparison = entry.getValue().compareTo(provincialAverage);
            boolean meets = switch (operator) {
                case "GTE" -> comparison >= 0;
                case "LT" -> comparison < 0;
                case "LTE" -> comparison <= 0;
                default -> comparison > 0;
            };
            rows.add(Arrays.asList(entry.getKey(), entry.getValue(), provincialAverage,
                    meets ? 1 : 0));
        }
        return rows;
    }

    /** [group dims..., numerator_value, denominator_value, ratio_percent]. */
    private List<List<Object>> ratio(BankQueryPlan plan) {
        String numerator = plan.getMetrics().get(0).getBizName();
        String denominator = plan.getMetrics().get(1).getBizName();
        double scale = BankSemanticRegistry.ratioScale(numerator.toUpperCase(Locale.ROOT),
                denominator.toUpperCase(Locale.ROOT));
        BigDecimal scaleLiteral = BigDecimal.valueOf(scale);
        List<String> orgScope = organizationCodes(plan);
        LocalDate start = plan.getTime().getStartDate();
        LocalDate end = plan.getTime().getEndDate();
        List<List<Object>> rows = new ArrayList<>();
        boolean grouped = plan.getDimensions().contains("bank_organization");
        if (grouped) {
            for (String org : BankDiffDataset.ORGS) {
                if (!orgScope.isEmpty() && !orgScope.contains(org)) {
                    continue;
                }
                BigDecimal numeratorSum = dataset.sumOverOrgs(List.of(org), numerator, start, end);
                BigDecimal denominatorSum =
                        dataset.sumOverOrgs(List.of(org), denominator, start, end);
                rows.add(Arrays.asList(org, numeratorSum, denominatorSum,
                        ratioOf(numeratorSum, denominatorSum, scaleLiteral)));
            }
        } else {
            BigDecimal numeratorSum = dataset.sumOverOrgs(orgScope, numerator, start, end);
            BigDecimal denominatorSum = dataset.sumOverOrgs(orgScope, denominator, start, end);
            rows.add(Arrays.asList(numeratorSum, denominatorSum,
                    ratioOf(numeratorSum, denominatorSum, scaleLiteral)));
        }
        return rows;
    }

    /** [current_value, baseline_value, absolute_change, percent_change]. */
    private List<List<Object>> changeScalar(BankQueryPlan plan) {
        String code = plan.getMetrics().get(0).getBizName();
        List<String> orgScope = organizationCodes(plan);
        LocalDate currentStart = currentStartDate(plan);
        LocalDate currentEnd = plan.getTime().getEndDate();
        BigDecimal current =
                dataset.sumOverOrgs(orgScope, code, currentStart, currentEnd);
        BigDecimal baseline = dataset.sumOverOrgs(orgScope, code,
                plan.getTime().getBaselineStartDate(), plan.getTime().getBaselineEndDate());
        return List.of(Arrays.asList(current, baseline, current.subtract(baseline),
                percentChange(current, baseline)));
    }

    /** [bank_organization, current_value, baseline_value, absolute_change, percent_change]. */
    private List<List<Object>> changePivot(BankQueryPlan plan) {
        String code = plan.getMetrics().get(0).getBizName();
        List<String> orgScope = organizationCodes(plan);
        LocalDate currentEnd = plan.getTime().getEndDate();
        LocalDate baselineEnd = plan.getTime().getBaselineEndDate();
        List<List<Object>> rows = new ArrayList<>();
        for (String org : BankDiffDataset.ORGS) {
            if (!orgScope.isEmpty() && !orgScope.contains(org)) {
                continue;
            }
            BigDecimal current = dataset.value(org, currentEnd, code);
            BigDecimal baseline = dataset.value(org, baselineEnd, code);
            if (current == null || baseline == null) {
                continue; // template drops rows whose pivot cell has no data.
            }
            rows.add(Arrays.asList(org, current, baseline, current.subtract(baseline),
                    percentChange(current, baseline)));
        }
        return rows;
    }

    /** [group dims..., date, metric_value_0..n]. */
    private List<List<Object>> changeMultiMetric(BankQueryPlan plan) {
        List<String> codes = plan.getMetrics().stream().map(BankQueryPlan.Metric::getBizName)
                .toList();
        List<String> orgScope = organizationCodes(plan);
        LocalDate currentStart = currentStartDate(plan);
        LocalDate currentEnd = plan.getTime().getEndDate();
        LocalDate baselineStart = plan.getTime().getBaselineStartDate();
        LocalDate baselineEnd = plan.getTime().getBaselineEndDate();
        boolean organizationColumn =
                plan.getDimensions().contains("bank_organization") || !orgScope.isEmpty();
        Map<String, Object[]> grouped = new LinkedHashMap<>();
        for (BankDiffDataset.Row row : dataset.rows()) {
            if (!orgScope.isEmpty() && !orgScope.contains(row.organization())) {
                continue;
            }
            boolean inCurrent =
                    !row.date().isBefore(currentStart) && !row.date().isAfter(currentEnd);
            boolean inBaseline =
                    !row.date().isBefore(baselineStart) && !row.date().isAfter(baselineEnd);
            if (!inCurrent && !inBaseline) {
                continue;
            }
            String key = (organizationColumn ? row.organization() + "|" : "") + row.date();
            Object[] cells = grouped.computeIfAbsent(key, ignored -> {
                List<Object> created = new ArrayList<>(codes.size() + 2);
                if (organizationColumn) {
                    created.add(row.organization());
                }
                created.add(row.date().toString());
                for (int i = 0; i < codes.size(); i++) {
                    created.add(BigDecimal.ZERO);
                }
                return created.toArray();
            });
            for (int i = 0; i < codes.size(); i++) {
                int cellIndex = cells.length - codes.size() + i;
                BigDecimal value = row.metrics().get(BankDiffDataset.metricIndex(codes.get(i)));
                cells[cellIndex] = ((BigDecimal) cells[cellIndex]).add(value);
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] cells : grouped.values()) {
            rows.add(Arrays.asList(cells));
        }
        return rows;
    }

    /** [current_value, mom_baseline_value, yoy_baseline_value]. */
    private List<List<Object>> changeMomAndYear(BankQueryPlan plan) {
        String code = plan.getMetrics().get(0).getBizName();
        List<String> orgScope = organizationCodes(plan);
        LocalDate currentEnd = plan.getTime().getEndDate();
        LocalDate monthBaseline = YearMonth.from(currentEnd).minusMonths(1).atEndOfMonth();
        LocalDate yearBaseline = currentEnd.minusYears(1);
        BigDecimal current = dataset.sumOverOrgs(orgScope, code, currentEnd, currentEnd);
        BigDecimal monthBaselineValue = dataset.sumOverOrgs(orgScope, code, monthBaseline,
                monthBaseline);
        BigDecimal yearBaselineValue = dataset.sumOverOrgs(orgScope, code, yearBaseline,
                yearBaseline);
        return List.of(Arrays.asList(current, monthBaselineValue, yearBaselineValue));
    }

    /**
     * [metric_code, bank_organization, metric_value, rank_position] for every metric over the
     * complete organization population. Mirrors {@code compileDerivedMetricRanking} exactly:
     *
     * <ul>
     *   <li>direct metrics aggregate {@code SUM(zb)} per organization over the window and rank
     *       with the metric's catalog direction ({@link #rankingDirection}),</li>
     *   <li>derived ratios compute {@code SUM(numerator) / NULLIF(SUM(denominator), 0) * scale}
     *       (composite numerators sum every operand) and always rank DESC,</li>
     *   <li>an org whose ratio has a zero denominator yields NULL and is dropped before
     *       numbering, so it can never occupy or shift a rank position,</li>
     *   <li>ranks are ROW_NUMBER ordinals — ordered by (metric_value direction, bank_organization
     *       ASC), one per surviving org, no competition-ranking gaps on ties,</li>
     *   <li>the selected-organization restriction is applied only after ranking (the template
     *       keeps the ranking population-wide and filters outside it).</li>
     * </ul>
     */
    private List<List<Object>> derivedRanking(BankQueryPlan plan) {
        List<String> orgScope = organizationCodes(plan);
        LocalDate start = plan.getTime().getStartDate();
        LocalDate end = plan.getTime().getEndDate();
        List<List<Object>> rows = new ArrayList<>();
        List<BankQueryPlan.Metric> directMetrics = plan.getMetrics().stream()
                .sorted(java.util.Comparator.comparing(BankQueryPlan.Metric::getBizName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (BankQueryPlan.Metric metric : directMetrics) {
            String code = metric.getBizName().toUpperCase(Locale.ROOT);
            Map<String, BigDecimal> valuesByOrg = new TreeMap<>();
            for (String org : BankDiffDataset.ORGS) {
                valuesByOrg.put(org, dataset.sumOverOrgs(List.of(org), code, start, end));
            }
            collectRankedRows(rows, code, valuesByOrg, rankingDirection(code), orgScope);
        }
        for (BankQueryPlan.DerivedMetric derived : plan.getDerivedMetrics()) {
            List<String> operands = derived.getNumeratorOperands() == null
                    || derived.getNumeratorOperands().isEmpty()
                            ? List.of(derived.getNumerator())
                            : derived.getNumeratorOperands();
            BigDecimal scale = BigDecimal.valueOf(BankSemanticRegistry.ratioScale(
                    derived.getNumerator(), derived.getDenominator()));
            Map<String, BigDecimal> valuesByOrg = new TreeMap<>();
            for (String org : BankDiffDataset.ORGS) {
                BigDecimal numeratorSum = BigDecimal.ZERO;
                for (String operand : operands) {
                    numeratorSum = numeratorSum.add(
                            dataset.sumOverOrgs(List.of(org), operand, start, end));
                }
                BigDecimal denominatorSum =
                        dataset.sumOverOrgs(List.of(org), derived.getDenominator(), start, end);
                // NULLIF(denominator, 0) contract: a zero denominator becomes a NULL metric
                // value that the ranked select drops before ROW_NUMBER.
                valuesByOrg.put(org, denominatorSum.compareTo(BigDecimal.ZERO) == 0 ? null
                        : numeratorSum.divide(denominatorSum, DIVISION).multiply(scale));
            }
            collectRankedRows(rows, derived.getMetricCode(), valuesByOrg, "DESC", orgScope);
        }
        return rows;
    }

    /** ROW_NUMBER semantics: (value direction, org ASC) ordinals over non-null values only. */
    private static void collectRankedRows(List<List<Object>> rows, String metricCode,
            Map<String, BigDecimal> valuesByOrg, String direction, List<String> orgScope) {
        Comparator<Map.Entry<String, BigDecimal>> byOrg = Map.Entry.comparingByKey();
        Comparator<Map.Entry<String, BigDecimal>> rankingOrder = ("ASC".equals(direction)
                ? Comparator.<Map.Entry<String, BigDecimal>, BigDecimal>comparing(
                        Map.Entry::getValue)
                : Comparator.<Map.Entry<String, BigDecimal>, BigDecimal>comparing(
                        Map.Entry::getValue, Comparator.reverseOrder())).thenComparing(byOrg);
        List<Map.Entry<String, BigDecimal>> ranked = valuesByOrg.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .sorted(rankingOrder)
                .toList();
        long rank = 0;
        for (Map.Entry<String, BigDecimal> entry : ranked) {
            rank++;
            if (!orgScope.isEmpty() && !orgScope.contains(entry.getKey())) {
                continue; // selected-organization restriction lives outside the ranking.
            }
            rows.add(Arrays.asList(metricCode, entry.getKey(), entry.getValue(), rank));
        }
    }

    /**
     * Mirrors {@code BankResultProjector.rankingDirection} from the shared registry: catalog
     * LOWER_BETTER metrics rank ASC (lower is better), everything else DESC.
     */
    private static String rankingDirection(String metricCode) {
        return BankSemanticRegistry.metrics().get(metricCode).direction()
                == BankSemanticRegistry.Direction.LOWER_BETTER ? "ASC" : "DESC";
    }

    /**
     * [bank_organization, metric_value, meets_condition] for the single selected organization.
     * Mirrors {@code compileAbsoluteThreshold}: the SUM is computed over every row in the window
     * (the threshold filter is the CASE comparison, never a WHERE predicate), and the plan's
     * numeric literal keeps its exact value after the template's percent-sign strip.
     */
    private List<List<Object>> absoluteThreshold(BankQueryPlan plan) {
        String code = plan.getMetrics().get(0).getBizName();
        String org = plan.getOrganizations().get(0).getCode();
        LocalDate start = plan.getTime().getStartDate();
        LocalDate end = plan.getTime().getEndDate();
        BigDecimal metricValue = dataset.sumOverOrgs(List.of(org), code, start, end);
        BankQueryPlan.Filter threshold = plan.getFilters().stream()
                .filter(filter -> "metric_value".equals(filter.getField())).findFirst()
                .orElseThrow();
        BigDecimal literal = new BigDecimal(
                threshold.getValue().replace("%", "").trim());
        int comparison = metricValue.compareTo(literal);
        boolean meets = switch (threshold.getOperator()) {
            case "GTE" -> comparison >= 0;
            case "LT" -> comparison < 0;
            case "LTE" -> comparison <= 0;
            case "EQ" -> comparison == 0;
            default -> comparison > 0;
        };
        return List.of(Arrays.asList(org, metricValue, meets ? 1 : 0));
    }

    private static LocalDate currentStartDate(BankQueryPlan plan) {
        return plan.getTime().getComparison() == BankQueryPlan.TimeComparison.START_OF_YEAR
                ? plan.getTime().getEndDate()
                : plan.getTime().getStartDate();
    }

    private static List<String> organizationCodes(BankQueryPlan plan) {
        return plan.getOrganizations().stream().map(BankQueryPlan.Organization::getCode)
                .filter(code -> code != null && !code.isBlank()).sorted().toList();
    }

    private static BigDecimal ratioOf(BigDecimal numerator, BigDecimal denominator,
            BigDecimal scale) {
        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return null; // NULLIF(denominator, 0) contract.
        }
        return numerator.multiply(scale).divide(denominator, DIVISION);
    }

    private static BigDecimal percentChange(BigDecimal current, BigDecimal baseline) {
        if (baseline.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.subtract(baseline).multiply(HUNDRED).divide(baseline, DIVISION);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total.divide(BigDecimal.valueOf(values.size()), DIVISION);
    }

    private static BigDecimal min(List<BigDecimal> values) {
        BigDecimal best = null;
        for (BigDecimal value : values) {
            if (best == null || value.compareTo(best) < 0) {
                best = value;
            }
        }
        return best;
    }

    private static BigDecimal max(List<BigDecimal> values) {
        BigDecimal best = null;
        for (BigDecimal value : values) {
            if (best == null || value.compareTo(best) > 0) {
                best = value;
            }
        }
        return best;
    }
}
