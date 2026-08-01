package com.tencent.supersonic.headless.server.integration;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class IntegrationIdempotencyStore {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final Clock clock;
    private final Semaphore capacity;

    public IntegrationIdempotencyStore(int maximumEntries, Duration ttl, Clock clock) {
        if (maximumEntries <= 0 || ttl == null || ttl.isNegative() || ttl.isZero()
                || clock == null) {
            throw new IllegalArgumentException("integration idempotency limits are invalid");
        }
        this.ttlMs = ttl.toMillis();
        this.clock = clock;
        this.capacity = new Semaphore(maximumEntries);
    }

    public IntegrationResponse execute(String systemId, String key, String fingerprint,
            Duration waitTimeout, Supplier<IntegrationResponse> action) {
        cleanup();
        String storageKey = systemId + ':' + key;
        Entry existing = entries.get(storageKey);
        if (existing != null) {
            return await(existing, fingerprint, waitTimeout);
        }
        if (!capacity.tryAcquire()) {
            throw new IntegrationException(IntegrationErrorCode.RATE_LIMITED,
                    "integration idempotency capacity is exhausted", true);
        }
        Entry candidate = new Entry(fingerprint, clock.millis());
        existing = entries.putIfAbsent(storageKey, candidate);
        if (existing != null) {
            capacity.release();
        }
        Entry selected = existing == null ? candidate : existing;
        if (!selected.fingerprint.equals(fingerprint)) {
            throw new IntegrationException(IntegrationErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key was reused with a different request", false);
        }
        if (existing == null) {
            try {
                IntegrationResponse response = action.get();
                candidate.result.complete(response);
                return response;
            } catch (RuntimeException | Error failure) {
                if (entries.remove(storageKey, candidate)) {
                    capacity.release();
                }
                candidate.result.completeExceptionally(failure);
                throw failure;
            }
        }
        return await(selected, fingerprint, waitTimeout);
    }

    private IntegrationResponse await(Entry selected, String fingerprint, Duration waitTimeout) {
        if (!selected.fingerprint.equals(fingerprint)) {
            throw new IntegrationException(IntegrationErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key was reused with a different request", false);
        }
        try {
            return selected.result.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IntegrationException(IntegrationErrorCode.INTERNAL_ERROR,
                    "interrupted while waiting for an idempotent request", true, exception);
        } catch (TimeoutException exception) {
            throw new IntegrationException(IntegrationErrorCode.UPSTREAM_TIMEOUT,
                    "timed out waiting for an idempotent request", true, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IntegrationException(IntegrationErrorCode.INTERNAL_ERROR,
                    "idempotent request failed", true, cause);
        }
    }

    private void cleanup() {
        long cutoff = clock.millis() - ttlMs;
        entries.forEach((key, entry) -> {
            if (entry.createdAtMs < cutoff && entry.result.isDone() && entries.remove(key, entry)) {
                capacity.release();
            }
        });
    }

    private static class Entry {
        private final String fingerprint;
        private final long createdAtMs;
        private final CompletableFuture<IntegrationResponse> result = new CompletableFuture<>();

        private Entry(String fingerprint, long createdAtMs) {
            this.fingerprint = fingerprint;
            this.createdAtMs = createdAtMs;
        }
    }
}
