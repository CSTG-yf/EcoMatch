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
    private final long acquireTimeoutMs;

    @Getter
    private final AtomicLong acceptedQueries = new AtomicLong();
    @Getter
    private final AtomicLong rejectedQueries = new AtomicLong();
    @Getter
    private final AtomicLong totalExecutionTimeMs = new AtomicLong();

    public QueryExecutionGateway(
            @Value("${s2.query-gateway.max-concurrency:20}") int maxConcurrency,
            @Value("${s2.query-gateway.acquire-timeout-ms:1000}") long acquireTimeoutMs,
            @Value("${s2.query-gateway.max-sql-length:100000}") int maxSqlLength) {
        this.permits = new Semaphore(Math.max(1, maxConcurrency), true);
        this.acquireTimeoutMs = Math.max(1, acquireTimeoutMs);
        this.safetyPolicy = new SqlSafetyPolicy(Math.max(1, maxSqlLength));
    }

    public <T> T execute(String sql, Supplier<T> action) {
        safetyPolicy.validate(sql);
        boolean acquired = false;
        long start = System.currentTimeMillis();
        try {
            acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                rejectedQueries.incrementAndGet();
                throw new QueryRejectedException("Query concurrency limit reached");
            }
            acceptedQueries.incrementAndGet();
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejectedQueries.incrementAndGet();
            throw new QueryRejectedException("Interrupted while waiting to execute query", e);
        } finally {
            if (acquired) {
                permits.release();
                totalExecutionTimeMs.addAndGet(System.currentTimeMillis() - start);
            }
        }
    }
}
