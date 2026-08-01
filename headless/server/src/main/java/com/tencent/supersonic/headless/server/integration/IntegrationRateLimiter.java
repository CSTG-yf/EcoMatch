package com.tencent.supersonic.headless.server.integration;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

public class IntegrationRateLimiter {

    private final int capacity;
    private final double refillPerSecond;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public IntegrationRateLimiter(int capacity, double refillPerSecond, Clock clock) {
        if (capacity <= 0 || refillPerSecond <= 0 || clock == null) {
            throw new IllegalArgumentException("integration rate limits are invalid");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
    }

    public void acquire(String systemId) {
        Bucket bucket =
                buckets.computeIfAbsent(systemId, ignored -> new Bucket(capacity, clock.millis()));
        synchronized (bucket) {
            long now = clock.millis();
            double refill = Math.max(0, now - bucket.lastRefillMs) / 1_000.0 * refillPerSecond;
            bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            bucket.lastRefillMs = now;
            if (bucket.tokens < 1) {
                throw new IntegrationException(IntegrationErrorCode.RATE_LIMITED,
                        "integration system rate limit exceeded", true);
            }
            bucket.tokens -= 1;
        }
    }

    private static class Bucket {
        private double tokens;
        private long lastRefillMs;

        private Bucket(double tokens, long lastRefillMs) {
            this.tokens = tokens;
            this.lastRefillMs = lastRefillMs;
        }
    }
}
