package com.tencent.supersonic.headless.api.pojo.bank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide cache of the bank dataset's real {@code data_date} domain ({@code [min, max]}).
 *
 * <p>The validator layer ({@code BankQueryPlanValidator}) runs on the parse path where no JDBC
 * connection exists, so it cannot observe the dataset by itself. The execution path instead calls
 * {@link #tryInitialize(LocalDate, LocalDate)} the first time a live connection is available
 * (a single {@code SELECT MIN(data_date), MAX(data_date)} observation). Every later parse reuses
 * the cached domain to reject plan date slots that provably match no data row (date
 * hallucination).
 *
 * <p>Until the cache is initialized, consumers must fall open (skip the guard) instead of
 * blocking: {@link #current()} returns {@code null} and the guard is silently inactive.
 *
 * <p>Thread safety: the immutable snapshot is published through an {@link AtomicReference}
 * compare-and-set, so concurrent executions race at most on who performs the first observation.
 */
public final class BankDataDomain {

    /** All bank pipeline diagnostics flow into the keyPipeline logger (s2-llm.log). */
    public static final String KEY_PIPELINE_LOGGER_NAME = "keyPipeline";

    private static final Logger KEY_PIPELINE_LOG =
            LoggerFactory.getLogger(KEY_PIPELINE_LOGGER_NAME);

    private static final AtomicReference<BankDataDomain> INSTANCE = new AtomicReference<>();

    private final LocalDate minDataDate;
    private final LocalDate maxDataDate;

    private BankDataDomain(LocalDate minDataDate, LocalDate maxDataDate) {
        this.minDataDate = minDataDate;
        this.maxDataDate = maxDataDate;
    }

    /**
     * Publishes the data domain observed from the database. The first valid observation wins;
     * later observations are ignored because the historical dataset domain is immutable for the
     * lifetime of the process. Invalid observations (null or inverted range) never initialize.
     *
     * @return true when this call initialized the cache
     */
    public static boolean tryInitialize(LocalDate minDataDate, LocalDate maxDataDate) {
        if (minDataDate == null || maxDataDate == null || minDataDate.isAfter(maxDataDate)) {
            return false;
        }
        BankDataDomain domain = new BankDataDomain(minDataDate, maxDataDate);
        if (INSTANCE.compareAndSet(null, domain)) {
            KEY_PIPELINE_LOG.info("BankDataDomain initialized: data_date domain [{}..{}]",
                    minDataDate, maxDataDate);
            return true;
        }
        return false;
    }

    /**
     * Returns the cached domain snapshot, or {@code null} when no executed query has observed the
     * {@code data_date} range yet. Callers must fall open (skip the guard) on {@code null}.
     */
    public static BankDataDomain current() {
        return INSTANCE.get();
    }

    /** Test-only: drops the cached domain so guards fall open again. */
    public static void reset() {
        INSTANCE.set(null);
    }

    /** A date is in-domain only when it lies inside the closed range {@code [min, max]}. */
    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(minDataDate) && !date.isAfter(maxDataDate);
    }

    public LocalDate minDataDate() {
        return minDataDate;
    }

    public LocalDate maxDataDate() {
        return maxDataDate;
    }

    @Override
    public String toString() {
        return "[" + minDataDate + ".." + maxDataDate + "]";
    }
}
