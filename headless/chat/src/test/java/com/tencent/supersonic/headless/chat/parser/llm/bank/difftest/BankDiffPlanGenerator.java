package com.tencent.supersonic.headless.chat.parser.llm.bank.difftest;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanValidator;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankSemanticRegistry;
import com.tencent.supersonic.headless.chat.parser.llm.bank.difftest.BankDiffOracle.Variant;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Fixed-seed random plan generator for the target query families. Every plan is built
 * directly against the documented routing shape of its family, then must pass
 * {@link BankQueryPlanValidator} together with the mapper-style hints; rejected candidates are
 * discarded and regenerated, and the discard rate is reported.
 *
 * <p>Family-specific boundaries honored here:
 *
 * <ul>
 *   <li>DERIVED_RANKING plans are always multi-metric and/or derived-ratio; slice variants carry
 *       rank/rank_from_bottom LTE filters (limit = N or top+bottom), non-slice variants carry
 *       selected organizations and no limit. Limit-only plans are never generated.</li>
 *   <li>ABSOLUTE_THRESHOLD plans carry exactly one numeric metric_value filter anchored near the
 *       true aggregate; no province-average benchmark or direction object is ever mixed in.</li>
 * </ul>
 */
public final class BankDiffPlanGenerator {

    public enum Family {
        AGGREGATION_SUMMARY, RATIO, CHANGE, PROVINCE_AVERAGE, DERIVED_RANKING, ABSOLUTE_THRESHOLD
    }

    public record Generated(BankQueryPlan plan, SemanticIntentHints hints,
            BankDiffOracle.Variant variant) {}

    /** Catalog metrics covered by the synthetic dataset (ZB001..ZB019). */
    private static final List<String> METRICS = BankSemanticRegistry.metricCodes().stream()
            .filter(code -> BankDiffDataset.metricIndex(code) < BankDiffDataset.METRIC_COUNT)
            .toList();
    private static final List<String[]> KNOWN_RATIO_PAIRS = List.of(
            new String[] {"ZB002", "ZB001"}, // 存贷比 scale 100
            new String[] {"ZB011", "ZB018"}, // 人均利润 scale 1.0 (zero denominator on ORG007)
            new String[] {"ZB001", "ZB019"}); // 网点平均存款规模 scale 10000 (zero den on ORG004)
    /** Catalog derived ratios usable as plan derived metrics (dataset-backed operand pairs). */
    private static final List<String[]> CATALOG_DERIVED_PAIRS = List.of(
            new String[] {"DERIVED_ZB002_DIV_ZB001", "ZB002", "ZB001"}, // 存贷比 scale 100
            new String[] {"DERIVED_ZB011_DIV_ZB009", "ZB011", "ZB009"}, // 净利润率 scale 100
            new String[] {"DERIVED_ZB011_DIV_ZB018", "ZB011", "ZB018"}); // 人均利润 scale 1.0
    private static final List<BankQueryPlan.TimeComparison> CHANGE_COMPARISONS = List.of(
            BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
            BankQueryPlan.TimeComparison.YEAR_OVER_YEAR,
            BankQueryPlan.TimeComparison.START_OF_YEAR);

    private final Family family;
    private final Random random;
    private final BankDiffDataset dataset;
    private final BankQueryPlanValidator validator = new BankQueryPlanValidator();
    private int attempts;
    private int discarded;

    public BankDiffPlanGenerator(Family family, long seed) {
        this(family, seed, BankDiffDataset.build());
    }

    /** Dataset-aware constructor: the threshold family anchors literals at true aggregates. */
    public BankDiffPlanGenerator(Family family, long seed, BankDiffDataset dataset) {
        this.family = family;
        this.random = new Random(seed);
        this.dataset = dataset;
    }

    /**
     * Generates {@code count} validator-accepted plans; fails after a generous attempt budget so
     * a broken generator surfaces instead of looping forever.
     */
    public List<Generated> generate(int count) {
        List<Generated> plans = new ArrayList<>(count);
        Map<String, Integer> variantCounts = new LinkedHashMap<>();
        int maxAttempts = count * 50 + 100;
        while (plans.size() < count) {
            if (attempts >= maxAttempts) {
                throw new IllegalStateException("generator for " + family + " produced only "
                        + plans.size() + " valid plans out of " + attempts + " attempts");
            }
            Generated candidate = switch (family) {
                case AGGREGATION_SUMMARY -> aggregationSummary();
                case RATIO -> ratio();
                case CHANGE -> change();
                case PROVINCE_AVERAGE -> provinceAverage();
                case DERIVED_RANKING -> derivedRanking();
                case ABSOLUTE_THRESHOLD -> absoluteThreshold();
            };
            attempts++;
            if (!validator.validate(candidate.plan(), candidate.hints()).isValid()) {
                discarded++;
                continue;
            }
            plans.add(candidate);
            variantCounts.merge(candidate.variant().name(), 1, Integer::sum);
        }
        System.out.printf("[BankFamilyDiff] family=%s requested=%d attempts=%d discarded=%d "
                + "(discard rate %.1f%%) variants=%s%n", family, count, attempts, discarded,
                attempts == 0 ? 0.0 : 100.0 * discarded / attempts, variantCounts);
        return plans;
    }

    // ------------------------------------------------------------------ family builders

    private Generated aggregationSummary() {
        List<String> codes = pickMetrics(1 + random.nextInt(3));
        List<BankQueryPlan.Organization> orgs = random.nextBoolean()
                ? List.of(organization())
                : List.of();
        TimeWindow window = anyWindow(0.4);
        List<BankQueryPlan.Metric> metrics = codes.stream()
                .map(code -> BankQueryPlan.Metric.builder().bizName(code)
                        .aggregation(BankQueryPlan.Aggregation.AVG).build())
                .toList();
        BankQueryPlan plan = base(BankIntentType.AGGREGATION, metrics, List.of("bank_organization"),
                orgs, window, BankQueryPlan.CalculationType.DIRECT,
                BankQueryPlan.TimeComparison.NONE, List.of(), List.of(), null);
        Variant variant = codes.size() == 1 ? Variant.AGGREGATION_SINGLE : Variant.AGGREGATION_MULTI;
        return new Generated(plan, hints(plan, BankIntentType.AGGREGATION, null, null, null),
                variant);
    }

    private Generated ratio() {
        String[] pair = random.nextBoolean()
                ? KNOWN_RATIO_PAIRS.get(random.nextInt(KNOWN_RATIO_PAIRS.size()))
                : randomDistinctPair();
        String numerator = pair[0];
        String denominator = pair[1];
        boolean grouped = random.nextBoolean();
        List<String> dimensions = grouped ? List.of("bank_organization") : List.of();
        List<BankQueryPlan.Organization> orgs = random.nextBoolean()
                ? List.of(organization())
                : List.of();
        TimeWindow window = anyWindow(0.4);
        List<BankQueryPlan.Metric> metrics = List.of(
                BankQueryPlan.Metric.builder().bizName(numerator)
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build(),
                BankQueryPlan.Metric.builder().bizName(denominator)
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build());
        BankQueryPlan plan = base(BankIntentType.RATIO, metrics, dimensions, orgs, window,
                BankQueryPlan.CalculationType.RATIO, BankQueryPlan.TimeComparison.NONE,
                List.of(), List.of(), null);
        plan.getCalculation().setBaseline(denominator);
        return new Generated(plan, hints(plan, BankIntentType.RATIO, null, null, null),
                Variant.RATIO);
    }

    private Generated change() {
        if (random.nextInt(5) == 0) {
            return changeMomAndYear();
        }
        BankQueryPlan.TimeComparison comparison =
                CHANGE_COMPARISONS.get(random.nextInt(CHANGE_COMPARISONS.size()));
        int metricCount = random.nextInt(10) < 6 ? 1 : 2 + random.nextInt(2);
        List<String> codes = pickMetrics(metricCount);
        // Current window inside 2024 (dataset indices 12..23).
        int startIndex = 12 + random.nextInt(12);
        int endIndex = startIndex + random.nextInt(24 - startIndex);
        if (random.nextInt(2) == 0) {
            endIndex = startIndex;
        }
        TimeWindow current = new TimeWindow(BankDiffDataset.DATES.get(startIndex),
                BankDiffDataset.DATES.get(endIndex));
        TimeRange time = BankQueryPlan.TimeComparison.START_OF_YEAR == comparison
                ? new TimeRange(current.start(), current.end(),
                        LocalDate.of(current.end().getYear() - 1, 12, 31),
                        LocalDate.of(current.end().getYear() - 1, 12, 31))
                : baselineWindow(current);
        List<String> dimensions = random.nextBoolean()
                ? List.of("bank_organization")
                : List.of();
        List<BankQueryPlan.Organization> orgs = random.nextBoolean()
                ? List.of(organization())
                : List.of();
        List<BankQueryPlan.Metric> metrics = codes.stream()
                .map(code -> BankQueryPlan.Metric.builder().bizName(code)
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build())
                .toList();
        BankQueryPlan plan = base(BankIntentType.CHANGE, metrics, dimensions, orgs,
                new TimeWindow(time.start(), time.end()), BankQueryPlan.CalculationType.CHANGE,
                comparison, List.of(), List.of(), null);
        setTimeBaselines(plan, time.baselineStart(), time.baselineEnd());
        Variant variant = metricCount == 1
                ? (dimensions.isEmpty() ? Variant.CHANGE_SCALAR : Variant.CHANGE_PIVOT)
                : Variant.CHANGE_MULTI_METRIC;
        return new Generated(plan, hints(plan, BankIntentType.CHANGE, comparison,
                time.baselineStart(), time.baselineEnd()), variant);
    }

    private Generated changeMomAndYear() {
        List<String> codes = pickMetrics(1);
        TimeWindow current = monthEndOf2024();
        BankQueryPlan plan = base(BankIntentType.CHANGE,
                List.of(BankQueryPlan.Metric.builder().bizName(codes.get(0))
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()),
                List.of(), List.of(organization()), current, BankQueryPlan.CalculationType.CHANGE,
                BankQueryPlan.TimeComparison.MOM_AND_YOY, List.of(), List.of(), null);
        return new Generated(plan, hints(plan, BankIntentType.CHANGE,
                BankQueryPlan.TimeComparison.MOM_AND_YOY, null, null), Variant.CHANGE_MOM_AND_YOY);
    }

    private Generated provinceAverage() {
        BankQueryPlan.Filter benchmark = BankQueryPlan.Filter.builder().field("benchmark")
                .operator("COMPARE").value("PROVINCE_AVERAGE").build();
        int mode = random.nextInt(10);
        if (mode < 4) {
            // THRESHOLD + single metric + benchmark (+ optional direction) -> threshold template.
            List<String> codes = pickMetrics(1);
            List<BankQueryPlan.Filter> filters = new ArrayList<>();
            filters.add(benchmark);
            if (random.nextInt(10) < 6) {
                filters.add(BankQueryPlan.Filter.builder().field("metric_value")
                        .operator(directionOperator()).value("PROVINCE_AVERAGE").build());
            }
            TimeWindow window = anyWindow(0.5);
            BankQueryPlan plan = base(BankIntentType.THRESHOLD,
                    List.of(BankQueryPlan.Metric.builder().bizName(codes.get(0))
                            .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()),
                    List.of("bank_organization"), maybeOrganization(), window,
                    BankQueryPlan.CalculationType.DIRECT, BankQueryPlan.TimeComparison.NONE,
                    filters, List.of(), null);
            return new Generated(plan, hints(plan, BankIntentType.THRESHOLD, null, null, null),
                    Variant.PROVINCE_AVERAGE_THRESHOLD);
        }
        // AGGREGATION / COMPARISON + benchmark -> full-population long-form summary.
        BankIntentType intent = mode < 7 ? BankIntentType.AGGREGATION
                : BankIntentType.COMPARISON;
        int metricCount = intent == BankIntentType.COMPARISON
                ? 2 + random.nextInt(2)
                : 1 + random.nextInt(3);
        List<String> codes = pickMetrics(metricCount);
        TimeWindow window = anyWindow(0.3);
        BankQueryPlan plan = base(intent,
                codes.stream().map(code -> BankQueryPlan.Metric.builder().bizName(code)
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()).toList(),
                List.of("bank_organization"), maybeOrganization(), window,
                BankQueryPlan.CalculationType.DIRECT, BankQueryPlan.TimeComparison.NONE,
                List.of(benchmark), List.of(), null);
        return new Generated(plan, hints(plan, intent, null, null, null),
                Variant.PROVINCE_AVERAGE_FULL_POPULATION);
    }

    private Generated derivedRanking() {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        List<BankQueryPlan.DerivedMetric> derivedMetrics = new ArrayList<>();
        List<SemanticIntentHints.DerivedMetricSpec> evidence = new ArrayList<>();
        int derivedMode = random.nextInt(10);
        if (derivedMode < 3) {
            // Single catalog ratio with exact mapper evidence (code/numerator/denominator/name).
            String[] pair =
                    CATALOG_DERIVED_PAIRS.get(random.nextInt(CATALOG_DERIVED_PAIRS.size()));
            derivedMetrics.add(catalogDerivedMetric(pair[0], pair[1], pair[2]));
            evidence.add(new SemanticIntentHints.DerivedMetricSpec(pair[0], pair[1], pair[2],
                    BankSemanticRegistry.derivedMetrics().get(pair[0]).name()));
            codes.add(pair[1]);
            codes.add(pair[2]);
        } else if (derivedMode < 5) {
            // Two catalog ratios: evidence must list them in plan derived-metric order.
            String[] first =
                    CATALOG_DERIVED_PAIRS.get(random.nextInt(CATALOG_DERIVED_PAIRS.size()));
            String[] second = first;
            while (second[0].equals(first[0])) {
                second = CATALOG_DERIVED_PAIRS.get(random.nextInt(CATALOG_DERIVED_PAIRS.size()));
            }
            for (String[] pair : List.of(first, second)) {
                derivedMetrics.add(catalogDerivedMetric(pair[0], pair[1], pair[2]));
                evidence.add(new SemanticIntentHints.DerivedMetricSpec(pair[0], pair[1], pair[2],
                        BankSemanticRegistry.derivedMetrics().get(pair[0]).name()));
                codes.add(pair[1]);
                codes.add(pair[2]);
            }
        } else if (derivedMode < 7) {
            // Composite derived ratio: whitelist shape, needs no mapper evidence.
            List<String> operands = pickMetrics(2 + random.nextInt(2));
            List<String> denominatorPool = new ArrayList<>(METRICS);
            denominatorPool.remove(operands.get(0));
            String denominator = denominatorPool.get(random.nextInt(denominatorPool.size()));
            derivedMetrics.add(compositeDerivedMetric(operands, denominator));
            codes.addAll(operands);
            codes.add(denominator);
        }
        // Top up to 2..4 direct metrics (multi-metric shape; a direct-only single metric would
        // route into the generic struct family instead of the ranked template family).
        int directTarget = 2 + random.nextInt(3);
        List<String> pool = new ArrayList<>(METRICS);
        Collections.shuffle(pool, random);
        for (String code : pool) {
            if (codes.size() >= directTarget) {
                break;
            }
            codes.add(code);
        }
        List<BankQueryPlan.Metric> metrics = codes.stream()
                .map(code -> BankQueryPlan.Metric.builder().bizName(code)
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build())
                .toList();
        List<BankQueryPlan.Organization> orgs;
        List<BankQueryPlan.Filter> filters = new ArrayList<>();
        Integer limit = null;
        if (random.nextInt(10) < 4) {
            // Slice plan: rank filters over the whole population with the documented limit
            // convention (single side N, both sides top+bottom — equal N gives 2*N).
            orgs = List.of();
            int top = rankSliceBound();
            int bottom = rankSliceBound();
            int sliceMode = random.nextInt(10);
            if (sliceMode < 4) {
                filters.add(rankFilter("rank", top));
                limit = top;
            } else if (sliceMode < 7) {
                filters.add(rankFilter("rank", top));
                filters.add(rankFilter("rank_from_bottom", bottom));
                limit = top + bottom;
            } else {
                filters.add(rankFilter("rank_from_bottom", bottom));
                limit = bottom;
            }
        } else {
            // Non-slice plan: 1..3 selected organizations and no limit. Limit-only plans
            // (limit without a rank filter) are deliberately never generated: their slice
            // semantics sit exactly on the compiler/projector boundary under active change.
            List<String> orgPool = new ArrayList<>(BankDiffDataset.ORGS);
            Collections.shuffle(orgPool, random);
            orgs = orgPool.subList(0, 1 + random.nextInt(3)).stream()
                    .map(code -> BankQueryPlan.Organization.builder().code(code).build())
                    .toList();
        }
        // Direct-only rankings must declare a sort field; with derived metrics present the
        // direction is compiler-owned (per-metric catalog direction) and orderBy stays empty.
        List<BankQueryPlan.OrderBy> orderBy = derivedMetrics.isEmpty()
                ? List.of(new BankQueryPlan.OrderBy(
                        random.nextBoolean() ? "bank_organization" : codes.iterator().next(),
                        random.nextBoolean() ? BankQueryPlan.SortDirection.ASC
                                : BankQueryPlan.SortDirection.DESC))
                : List.of();
        BankQueryPlan plan = base(BankIntentType.RANKING, metrics, List.of("bank_organization"),
                orgs, anyWindow(0.4), BankQueryPlan.CalculationType.DIRECT,
                BankQueryPlan.TimeComparison.NONE, filters, orderBy, limit);
        plan.setDerivedMetrics(List.copyOf(derivedMetrics));
        return new Generated(plan,
                hints(plan, BankIntentType.RANKING, null, null, null, evidence),
                Variant.DERIVED_RANKING);
    }

    /**
     * ABSOLUTE_THRESHOLD plans: single metric, single organization, dimensions exactly
     * [bank_organization], exactly one numeric metric_value filter (GT/GTE/LT/LTE) and no
     * benchmark, orderBy or limit. The literal sits at a random signed cent offset from the true
     * aggregate so plans hit both CASE branches plus the exact-equality boundary (offset 0); a
     * trailing percent occasionally exercises the template's literal normalization.
     */
    private Generated absoluteThreshold() {
        String code = pickMetrics(1).get(0);
        BankQueryPlan.Organization org = organization();
        TimeWindow window = anyWindow(0.4);
        BigDecimal aggregate =
                dataset.sumOverOrgs(List.of(org.getCode()), code, window.start(), window.end());
        BigDecimal offset = random.nextInt(10) == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(random.nextInt(2_000_001) - 1_000_000, 2);
        String literal = aggregate.add(offset).toPlainString()
                + (random.nextInt(10) == 0 ? "%" : "");
        BankQueryPlan.Filter threshold = BankQueryPlan.Filter.builder().field("metric_value")
                .operator(directionOperator()).value(literal).build();
        BankQueryPlan plan = base(BankIntentType.THRESHOLD,
                List.of(BankQueryPlan.Metric.builder().bizName(code)
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()),
                List.of("bank_organization"), List.of(org), window,
                BankQueryPlan.CalculationType.DIRECT, BankQueryPlan.TimeComparison.NONE,
                List.of(threshold), List.of(), null);
        return new Generated(plan, hints(plan, BankIntentType.THRESHOLD, null, null, null),
                Variant.ABSOLUTE_THRESHOLD);
    }

    /** 1..4, with a 1-in-10 oversized bound (>= ranked population) for saturated slices. */
    private int rankSliceBound() {
        return random.nextInt(10) == 0 ? 9 + random.nextInt(4) : 1 + random.nextInt(4);
    }

    private static BankQueryPlan.Filter rankFilter(String field, int bound) {
        return BankQueryPlan.Filter.builder().field(field).operator("LTE")
                .value(Integer.toString(bound)).build();
    }

    private static BankQueryPlan.DerivedMetric catalogDerivedMetric(String code, String numerator,
            String denominator) {
        return BankQueryPlan.DerivedMetric.builder().metricCode(code).numerator(numerator)
                .denominator(denominator)
                .name(BankSemanticRegistry.derivedMetrics().get(code).name())
                .build();
    }

    /**
     * Whitelist-shape composite derived ratio (numerator = sum of >= 2 distinct base metrics over
     * one denominator). Requires no mapper evidence, so the plan hints carry no derived spec.
     */
    private static BankQueryPlan.DerivedMetric compositeDerivedMetric(List<String> operands,
            String denominator) {
        String code = "DERIVED_SUM_" + String.join("_AND_", operands) + "_DIV_" + denominator;
        return BankQueryPlan.DerivedMetric.builder().metricCode(code)
                .numerator(operands.get(0)).denominator(denominator).name("组合派生比率")
                .numeratorOperands(List.copyOf(operands)).build();
    }

    // ------------------------------------------------------------------ shared helpers

    private BankQueryPlan base(BankIntentType intent, List<BankQueryPlan.Metric> metrics,
            List<String> dimensions, List<BankQueryPlan.Organization> orgs, TimeWindow window,
            BankQueryPlan.CalculationType calculationType,
            BankQueryPlan.TimeComparison comparison, List<BankQueryPlan.Filter> filters,
            List<BankQueryPlan.OrderBy> orderBy, Integer limit) {
        List<String> outputColumns = new ArrayList<>(dimensions);
        metrics.forEach(metric -> outputColumns.add(metric.getBizName()));
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .action(BankQueryPlan.PlanAction.EXECUTE).intent(intent).metrics(metrics)
                .derivedMetrics(List.of()).dimensions(dimensions).organizations(orgs)
                .time(BankQueryPlan.TimeRange.builder().startDate(window.start())
                        .endDate(window.end())
                        .granularity(BankQueryPlan.TimeGranularity.DAY).comparison(comparison)
                        .build())
                .filters(filters)
                .calculation(BankQueryPlan.Calculation.builder().type(calculationType).build())
                .orderBy(orderBy).limit(limit)
                .output(BankQueryPlan.Output.builder().columns(outputColumns)
                        .orderSensitive(false).build())
                .build();
    }

    private static void setTimeBaselines(BankQueryPlan plan, LocalDate baselineStart,
            LocalDate baselineEnd) {
        plan.getTime().setBaselineStartDate(baselineStart);
        plan.getTime().setBaselineEndDate(baselineEnd);
    }

    private SemanticIntentHints hints(BankQueryPlan plan, BankIntentType intent,
            BankQueryPlan.TimeComparison comparison, LocalDate baselineStart,
            LocalDate baselineEnd) {
        return hints(plan, intent, comparison, baselineStart, baselineEnd, List.of());
    }

    private SemanticIntentHints hints(BankQueryPlan plan, BankIntentType intent,
            BankQueryPlan.TimeComparison comparison, LocalDate baselineStart,
            LocalDate baselineEnd, List<SemanticIntentHints.DerivedMetricSpec> derivedEvidence) {
        Set<String> metricCodes = new LinkedHashSet<>(
                plan.getMetrics().stream().map(BankQueryPlan.Metric::getBizName).toList());
        Set<String> orgCodes = new LinkedHashSet<>(plan.getOrganizations().stream()
                .map(BankQueryPlan.Organization::getCode).filter(code -> code != null).toList());
        return SemanticIntentHints.builder().expectedIntent(intent)
                .allowedMetrics(new LinkedHashSet<>(BankSemanticRegistry.metricCodes()))
                .allowedDimensions(new LinkedHashSet<>(BankSemanticRegistry.dimensions()))
                .requiredMetrics(metricCodes).requiredOrganizationCodes(orgCodes)
                .requiredStartDate(plan.getTime().getStartDate())
                .requiredEndDate(plan.getTime().getEndDate())
                .requiredTimeComparison(comparison).requiredBaselineStartDate(baselineStart)
                .requiredBaselineEndDate(baselineEnd)
                .requiredDerivedMetrics(derivedEvidence).requiredFilters(List.of())
                .maxLimit(SemanticIntentHints.DEFAULT_MAX_LIMIT).build();
    }

    private BankQueryPlan.Organization organization() {
        return BankQueryPlan.Organization.builder()
                .code(BankDiffDataset.ORGS.get(random.nextInt(BankDiffDataset.ORGS.size())))
                .build();
    }

    private List<BankQueryPlan.Organization> maybeOrganization() {
        return random.nextBoolean() ? List.of(organization()) : List.of();
    }

    private List<String> pickMetrics(int count) {
        List<String> pool = new ArrayList<>(METRICS);
        Collections.shuffle(pool, random);
        return List.copyOf(pool.subList(0, count));
    }

    private String[] randomDistinctPair() {
        List<String> pool = pickMetrics(2);
        return new String[] {pool.get(0), pool.get(1)};
    }

    /** Random [start, end] window inside the dataset; point windows with the given probability. */
    private TimeWindow anyWindow(double pointProbability) {
        int startIndex = random.nextInt(BankDiffDataset.DATES.size());
        int endIndex = startIndex + random.nextInt(BankDiffDataset.DATES.size() - startIndex);
        if (random.nextDouble() < pointProbability) {
            endIndex = startIndex;
        }
        return new TimeWindow(BankDiffDataset.DATES.get(startIndex),
                BankDiffDataset.DATES.get(endIndex));
    }

    /** Baseline window in 2023 (dataset indices 0..11), always before the 2024 current window. */
    private TimeRange baselineWindow(TimeWindow current) {
        int baselineStartIndex = random.nextInt(12);
        int baselineEndIndex = baselineStartIndex + random.nextInt(12 - baselineStartIndex);
        if (random.nextInt(2) == 0) {
            baselineEndIndex = baselineStartIndex;
        }
        return new TimeRange(current.start(), current.end(),
                BankDiffDataset.DATES.get(baselineStartIndex),
                BankDiffDataset.DATES.get(baselineEndIndex));
    }

    /** Point window on one 2024 month-end (MOM/YOY baselines then land on existing dates). */
    private TimeWindow monthEndOf2024() {
        int index = 12 + random.nextInt(12);
        LocalDate date = BankDiffDataset.DATES.get(index);
        return new TimeWindow(date, date);
    }

    private String directionOperator() {
        return switch (random.nextInt(4)) {
            case 0 -> "GTE";
            case 1 -> "LT";
            case 2 -> "LTE";
            default -> "GT";
        };
    }

    private record TimeWindow(LocalDate start, LocalDate end) {}

    private record TimeRange(LocalDate start, LocalDate end, LocalDate baselineStart,
            LocalDate baselineEnd) {}
}
