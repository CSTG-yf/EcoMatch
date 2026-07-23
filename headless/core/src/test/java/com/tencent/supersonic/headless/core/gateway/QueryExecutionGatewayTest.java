package com.tencent.supersonic.headless.core.gateway;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryExecutionGatewayTest {

    @Test
    void rejectsWhenConcurrencyPermitCannotBeAcquired() throws Exception {
        QueryExecutionGateway gateway = new QueryExecutionGateway(1, 20, 1000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> first = executor.submit(() -> gateway.execute("SELECT 1", () -> {
            entered.countDown();
            await(release);
            return 1;
        }));

        entered.await(1, TimeUnit.SECONDS);
        assertThrows(QueryRejectedException.class, () -> gateway.execute("SELECT 2", () -> 2));
        release.countDown();

        assertEquals(1, first.get(1, TimeUnit.SECONDS));
        assertEquals(1, gateway.getAcceptedQueries().get());
        assertEquals(1, gateway.getRejectedQueries().get());
        assertEquals(1, gateway.snapshot().completedQueries());
        assertEquals(0, gateway.snapshot().activeQueries());
        assertEquals(1, gateway.snapshot().availablePermits());
        executor.shutdownNow();
    }

    @Test
    void recordsPolicyRejectionsAndExecutionFailures() {
        QueryExecutionGateway gateway = new QueryExecutionGateway(2, 20, 1000);

        assertThrows(SqlPolicyViolationException.class,
                () -> gateway.execute("DELETE FROM account", () -> 1));
        assertThrows(IllegalStateException.class,
                () -> gateway.execute("SELECT id FROM account", () -> {
                    throw new IllegalStateException("database unavailable");
                }));

        QueryExecutionGateway.QueryGatewayStats stats = gateway.snapshot();
        assertEquals(1, stats.acceptedQueries());
        assertEquals(1, stats.rejectedQueries());
        assertEquals(0, stats.completedQueries());
        assertEquals(1, stats.failedQueries());
        assertEquals(0, stats.activeQueries());
        assertEquals(2, stats.availablePermits());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
