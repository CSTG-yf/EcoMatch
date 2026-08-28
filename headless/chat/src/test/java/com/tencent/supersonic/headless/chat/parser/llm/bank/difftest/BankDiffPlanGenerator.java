package com.tencent.supersonic.headless.chat.parser.llm.bank.difftest;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlan;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankQueryPlanValidator;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankSemanticRegistry;
import com.tencent.supersonic.headless.chat.parser.llm.bank.difftest.BankDiffOracle.Variant;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Fixed-seed random plan generator for the four target query families. Every plan is built
 * directly against the documented routing shape of its family, then must pass
 * {@link BankQueryPlanValidator} together with the mapper-style hints; rejected candidates are
 * discarded and regenerated, and the discard rate is reported.
 */
public final class BankDiffPlanGenerator {

    public enum Family {
        AGGREGATION_SUMMARY, RATIO, CHANGE, PROVINCE_AVERAGE
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
    private static final List<BankQueryPlan.TimeComparison> CHANGE_COMPARISONS = List.of(
            BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
            BankQueryPlan.TimeComparison.YEAR_OVER_YEAR,
            BankQueryPlan.TimeComparison.START_OF_YEAR);

    private final Family family;
    private final Random random;
    private final BankQueryPlanValidator validator = new BankQueryPlanValidator();
    private int attempts;
    private int discarded;

    public BankDiffPlanGenerator(Family family, long seed) {
        this.family = family;
        this.random = new Random(seed);
    }

    /**
     * Generates {@code count} validator-accepted plans; fails after a generous attempt budget so
     * a broken generator surfaces instead of looping forever.
     */
    public List<Generated> generate(int count) {
        List<Generated> plans = new ArrayList<>(count);
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
            };
            attempts++;
            if (!validator.validate(candidate.plan(), candidate.hints()).isValid()) {
                discarded++;
                continue;
            }
            plans.add(candidate);
        }
        System.out.printf("[BankFamilyDiff] family=%s requested=%d attempts=%d discarded=%d "
                + "(discard rate %.1f%%)%n", family, count, attempts, discarded,
                attempts == 0 ? 0.0 : 100.0 * discarded / attempts);
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
                .requiredDerivedMetrics(List.of()).requiredFilters(List.of())
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
