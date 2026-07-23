package com.tencent.supersonic.headless.core.gateway;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Applies SQL policy and bounds concurrent physical query execution. */
@Component
public class QueryExecutionGateway {

    private final SqlSafetyPolicy safetyPolicy;
    private final Semaphore permits;
    private final int maxConcurrency;
    private final long acquireTimeoutMs;

    @Getter
    private final AtomicLong acceptedQueries = new AtomicLong();
    @Getter
    private final AtomicLong rejectedQueries = new AtomicLong();
    @Getter
    private final AtomicLong completedQueries = new AtomicLong();
    @Getter
    private final AtomicLong failedQueries = new AtomicLong();
    @Getter
    private final AtomicLong activeQueries = new AtomicLong();
    @Getter
    private final AtomicLong totalExecutionTimeMs = new AtomicLong();
    private final AtomicLong totalExecutionTimeNanos = new AtomicLong();

    public QueryExecutionGateway(
            @Value("${s2.query-gateway.max-concurrency:20}") int maxConcurrency,
            @Value("${s2.query-gateway.acquire-timeout-ms:1000}") long acquireTimeoutMs,
            @Value("${s2.query-gateway.max-sql-length:100000}") int maxSqlLength) {
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.permits = new Semaphore(this.maxConcurrency, true);
        this.acquireTimeoutMs = Math.max(1, acquireTimeoutMs);
        this.safetyPolicy = new SqlSafetyPolicy(Math.max(1, maxSqlLength));
    }

    public <T> T execute(String sql, Supplier<T> action) {
        try {
            safetyPolicy.validate(sql);
        } catch (RuntimeException e) {
            rejectedQueries.incrementAndGet();
            throw e;
        }
        boolean acquired = false;
        long executionStart = 0;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                rejectedQueries.incrementAndGet();
                throw new QueryRejectedException("Query concurrency limit reached");
            }
            acceptedQueries.incrementAndGet();
            activeQueries.incrementAndGet();
            executionStart = System.nanoTime();
            try {
                T result = action.get();
                completedQueries.incrementAndGet();
                return result;
            } catch (RuntimeException | Error e) {
                failedQueries.incrementAndGet();
                throw e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejectedQueries.incrementAndGet();
            throw new QueryRejectedException("Interrupted while waiting to execute query", e);
        } finally {
            if (acquired) {
                long elapsedNanos = System.nanoTime() - executionStart;
                totalExecutionTimeNanos.addAndGet(elapsedNanos);
                totalExecutionTimeMs.addAndGet(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
                QueryPerformanceMonitor.record(QueryPerformanceMonitor.Stage.EXECUTE, elapsedNanos);
                activeQueries.decrementAndGet();
                permits.release();
            }
        }
    }

    public QueryGatewayStats snapshot() {
        long finished = completedQueries.get() + failedQueries.get();
        double averageExecutionTimeMs =
                finished == 0 ? 0 : totalExecutionTimeNanos.get() / 1_000_000.0 / finished;
        return new QueryGatewayStats(maxConcurrency, permits.availablePermits(),
                activeQueries.get(), acceptedQueries.get(), rejectedQueries.get(),
                completedQueries.get(), failedQueries.get(), averageExecutionTimeMs);
    }

    public record QueryGatewayStats(int maxConcurrency, int availablePermits, long activeQueries,
            long acceptedQueries, long rejectedQueries, long completedQueries, long failedQueries,
            double averageExecutionTimeMs) {}
}
