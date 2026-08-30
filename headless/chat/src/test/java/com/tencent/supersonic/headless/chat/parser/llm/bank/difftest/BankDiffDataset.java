package com.tencent.supersonic.headless.chat.parser.llm.bank.difftest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Fixed-seed synthetic wide bank dataset for differential property testing.
 *
 * <p>Shape: 10 organizations (ORG001..ORG010) x 24 month-end dates (2023-01 .. 2024-12) x
 * metrics zb001..zb019, one row per (org, date) with DECIMAL values of scale 2. The random
 * generator deliberately plants the boundaries the templates must survive:
 *
 * <ul>
 *   <li>ZB007 carries exact 0 values with ~15% probability (zero-valued observations),</li>
 *   <li>ZB013 is drawn from a 6-value domain so SUM/AVG results collide across orgs (ties),</li>
 *   <li>ORG004 has ZB019 == 0 on every date (zero ratio denominator -> NULLIF path),</li>
 *   <li>ORG007 has ZB018 == 0 on every date (second zero-denominator pair),</li>
 *   <li>ZB018/ZB019 are quantized so their sums duplicate across orgs.</li>
 * </ul>
 *
 * <p>Chinese-to-ASCII identifier mirroring ({@link #mirror(String)}) is kept identical to
 * {@code DiagH03CalciteReproTest}: the semantic dataset name and the partition-time display name
 * are the only two Chinese identifiers the compiler ever renders into S2SQL.
 */
public final class BankDiffDataset {

    public static final String DATA_SET_NAME = "银行业智能问数数据集";
    public static final String DATE_FIELD_NAME = "数据日期";
    public static final String MIRROR_TABLE = "BANK_METRIC_DATASET";
    public static final String MIRROR_DATE = "BANK_DATA_DATE";

    /** Fixed master seed so every failure is reproducible from the test source alone. */
    public static final long SEED = 20260827L;
    public static final int METRIC_COUNT = 19;

    /** Ten registry organizations; the registry defines ORG001..ORG013 and we use the first ten. */
    public static final List<String> ORGS = buildOrgs();

    /** Month-end dates for 2023-01 .. 2024-12 (12 months of CHANGE baselines + 12 current). */
    public static final List<LocalDate> DATES = buildDates();

    /** One synthetic fact row: organization code, date, metric values zb001..zb019. */
    public record Row(String organization, LocalDate date, List<BigDecimal> metrics) {}

    private final List<Row> rows;

    private BankDiffDataset(List<Row> rows) {
        this.rows = List.copyOf(rows);
    }

    /** Builds the deterministic dataset; always returns the same values for the same seed. */
    public static BankDiffDataset build() {
        Random random = new Random(SEED);
        List<Row> rows = new ArrayList<>(ORGS.size() * DATES.size());
        for (int orgIndex = 0; orgIndex < ORGS.size(); orgIndex++) {
            for (LocalDate date : DATES) {
                List<BigDecimal> values = new ArrayList<>(METRIC_COUNT);
                for (int metricIndex = 0; metricIndex < METRIC_COUNT; metricIndex++) {
                    values.add(nextValue(random, orgIndex, metricIndex));
                }
                rows.add(new Row(ORGS.get(orgIndex), date, List.copyOf(values)));
            }
        }
        return new BankDiffDataset(rows);
    }

    public List<Row> rows() {
        return rows;
    }

    /** Maps a plan metric code (ZB001..) to the zero-based column index in {@link Row}. */
    public static int metricIndex(String metricCode) {
        return Integer.parseInt(metricCode.trim().substring(2)) - 1;
    }

    /** DiagH03 identifier mirroring: the only two Chinese identifiers S2SQL may carry. */
    public static String mirror(String sql) {
        return sql.replace(DATA_SET_NAME, MIRROR_TABLE).replace(DATE_FIELD_NAME, MIRROR_DATE);
    }

    /** Raw single-cell value, or null when no row exists for the (org, date) pair. */
    public BigDecimal value(String organization, LocalDate date, String metricCode) {
        for (Row row : rows) {
            if (row.organization().equals(organization) && row.date().equals(date)) {
                return row.metrics().get(metricIndex(metricCode));
            }
        }
        return null;
    }

    /**
     * Daily sums of one metric for one organization inside [start, end], one entry per date with
     * data, ascending by date. Mirrors the {@code GROUP BY bank_organization, aggregation_date}
     * daily CTEs of the aggregation templates.
     */
    public List<BigDecimal> dailySums(String organization, String metricCode, LocalDate start,
            LocalDate end) {
        List<BigDecimal> daily = new ArrayList<>();
        LocalDate previous = null;
        for (Row row : rows) {
            if (!row.organization().equals(organization) || row.date().isBefore(start)
                    || row.date().isAfter(end)) {
                continue;
            }
            BigDecimal value = row.metrics().get(metricIndex(metricCode));
            if (row.date().equals(previous)) {
                daily.set(daily.size() - 1, daily.get(daily.size() - 1).add(value));
            } else {
                daily.add(value);
                previous = row.date();
            }
        }
        return daily;
    }

    /**
     * Sum of one metric over every row whose organization is in {@code orgScope} (empty scope =
     * all organizations) and whose date is inside [start, end]. Mirrors an ungrouped
     * {@code SUM(metric)} over the filtered table.
     */
    public BigDecimal sumOverOrgs(List<String> orgScope, String metricCode, LocalDate start,
            LocalDate end) {
        BigDecimal total = BigDecimal.ZERO;
        int index = metricIndex(metricCode);
        for (Row row : rows) {
            if (!orgScope.isEmpty() && !orgScope.contains(row.organization())) {
                continue;
            }
            if (row.date().isBefore(start) || row.date().isAfter(end)) {
                continue;
            }
            total = total.add(row.metrics().get(index));
        }
        return total;
    }

    private static List<String> buildOrgs() {
        List<String> orgs = new ArrayList<>(10);
        for (int index = 1; index <= 10; index++) {
            orgs.add(String.format(Locale.ROOT, "ORG%03d", index));
        }
        return List.copyOf(orgs);
    }

    private static List<LocalDate> buildDates() {
        List<LocalDate> dates = new ArrayList<>(24);
        for (int year = 2023; year <= 2024; year++) {
            for (int month = 1; month <= 12; month++) {
                dates.add(YearMonth.of(year, month).atEndOfMonth());
            }
        }
        return List.copyOf(dates);
    }

    private static BigDecimal nextValue(Random random, int orgIndex, int metricIndex) {
        // ZB007 中间业务收入: plant exact zeros so aggregate filters meet 0-valued observations.
        if (metricIndex == 6 && random.nextInt(7) == 0) {
            return BigDecimal.ZERO;
        }
        // ZB013 不良贷款率: six-value domain forces duplicate aggregates (tied rankings).
        if (metricIndex == 12) {
            return BigDecimal.valueOf(50L + random.nextInt(6) * 50L, 2);
        }
        // ZB018 员工人数: ORG007 is the permanent zero denominator of 人均利润 (ZB011/ZB018).
        if (metricIndex == 17) {
            if (orgIndex == 6) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(50L + random.nextInt(40) * 10L);
        }
        // ZB019 网点数量: ORG004 is the permanent zero denominator of 网点平均存款规模.
        if (metricIndex == 18) {
            if (orgIndex == 3) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(5L + random.nextInt(6) * 5L);
        }
        // Everything else: 1.00 .. 8001.00 with scale 2 (exact decimal cents).
        return BigDecimal.valueOf(100L + random.nextInt(800_000), 2);
    }
}
