package com.tencent.supersonic.headless.core.gateway;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Aggregates latency counters for the main NL2SQL query stages. */
public final class QueryPerformanceMonitor {

    public enum Stage {
        PARSE, MODEL, TRANSLATE, EXECUTE, EXPLAIN
    }

    private static final int LATENCY_SAMPLE_CAPACITY = 2048;
    private static final Map<Stage, StageAccumulator> ACCUMULATORS = createAccumulators();
    private static final LongAdder CACHE_HITS = new LongAdder();
    private static final LongAdder CACHE_MISSES = new LongAdder();
    private static final LongAdder HOT_METRIC_CACHE_HITS = new LongAdder();
    private static final LongAdder HOT_METRIC_CACHE_MISSES = new LongAdder();

    private QueryPerformanceMonitor() {}

    public static void record(Stage stage, long elapsedNanos) {
        if (stage == null || elapsedNanos < 0) {
            return;
        }
        ACCUMULATORS.get(stage).record(elapsedNanos);
    }

    public static Map<String, StageStats> snapshot() {
        Map<String, StageStats> snapshot = new LinkedHashMap<>();
        for (Stage stage : Stage.values()) {
            snapshot.put(stage.name().toLowerCase(), ACCUMULATORS.get(stage).snapshot());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public static void recordCacheLookup(boolean hit) {
        recordCacheLookup(hit, false);
    }

    public static void recordCacheLookup(boolean hit, boolean hotMetric) {
        if (hit) {
            CACHE_HITS.increment();
            if (hotMetric) {
                HOT_METRIC_CACHE_HITS.increment();
            }
        } else {
            CACHE_MISSES.increment();
            if (hotMetric) {
                HOT_METRIC_CACHE_MISSES.increment();
            }
        }
    }

    public static CacheStats cacheSnapshot() {
        long hits = CACHE_HITS.sum();
        long misses = CACHE_MISSES.sum();
        long requests = hits + misses;
        long hotHits = HOT_METRIC_CACHE_HITS.sum();
        long hotMisses = HOT_METRIC_CACHE_MISSES.sum();
        long hotRequests = hotHits + hotMisses;
        return new CacheStats(hits, misses, requests, rate(hits, requests), hotHits, hotMisses,
                hotRequests, rate(hotHits, hotRequests));
    }

    static void reset() {
        ACCUMULATORS.values().forEach(StageAccumulator::reset);
        CACHE_HITS.reset();
        CACHE_MISSES.reset();
        HOT_METRIC_CACHE_HITS.reset();
        HOT_METRIC_CACHE_MISSES.reset();
    }

    private static Map<Stage, StageAccumulator> createAccumulators() {
        Map<Stage, StageAccumulator> accumulators = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            accumulators.put(stage, new StageAccumulator());
        }
        return accumulators;
    }

    public record StageStats(long count, double totalTimeMs, double averageTimeMs, double maxTimeMs,
            double p50TimeMs, double p95TimeMs, double p99TimeMs) {}

    public record CacheStats(long hits, long misses, long requests, double hitRate,
            long hotMetricHits, long hotMetricMisses, long hotMetricRequests,
            double hotMetricHitRate) {}

    private static double rate(long hits, long requests) {
        return requests == 0 ? 0 : (double) hits / requests;
    }

    private static final class StageAccumulator {

        private LongAdder count = new LongAdder();
        private LongAdder totalNanos = new LongAdder();
        private LongAccumulator maxNanos = new LongAccumulator(Long::max, 0);
        private final long[] recentNanos = new long[LATENCY_SAMPLE_CAPACITY];
        private int nextSampleIndex;
        private int sampleSize;

        private void record(long elapsedNanos) {
            count.increment();
            totalNanos.add(elapsedNanos);
            maxNanos.accumulate(elapsedNanos);
            synchronized (this) {
                recentNanos[nextSampleIndex] = elapsedNanos;
                nextSampleIndex = (nextSampleIndex + 1) % recentNanos.length;
                sampleSize = Math.min(sampleSize + 1, recentNanos.length);
            }
        }

        private StageStats snapshot() {
            long sampleCount = count.sum();
            long total = totalNanos.sum();
            long[] samples;
            synchronized (this) {
                samples = Arrays.copyOf(recentNanos, sampleSize);
            }
            Arrays.sort(samples);
            return new StageStats(sampleCount, nanosToMillis(total),
                    sampleCount == 0 ? 0 : nanosToMillis(total) / sampleCount,
                    nanosToMillis(maxNanos.get()), percentile(samples, 0.50),
                    percentile(samples, 0.95), percentile(samples, 0.99));
        }

        private void reset() {
            count = new LongAdder();
            totalNanos = new LongAdder();
            maxNanos = new LongAccumulator(Long::max, 0);
            synchronized (this) {
                Arrays.fill(recentNanos, 0);
                nextSampleIndex = 0;
                sampleSize = 0;
            }
        }

        private double percentile(long[] sortedSamples, double percentile) {
            if (sortedSamples.length == 0) {
                return 0;
            }
            int index = Math.max(0, (int) Math.ceil(percentile * sortedSamples.length) - 1);
            return nanosToMillis(sortedSamples[index]);
        }

        private double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }
}
