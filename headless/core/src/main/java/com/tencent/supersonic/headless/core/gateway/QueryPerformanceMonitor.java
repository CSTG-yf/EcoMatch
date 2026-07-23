package com.tencent.supersonic.headless.core.gateway;

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

    private static final Map<Stage, StageAccumulator> ACCUMULATORS = createAccumulators();

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

    static void reset() {
        ACCUMULATORS.values().forEach(StageAccumulator::reset);
    }

    private static Map<Stage, StageAccumulator> createAccumulators() {
        Map<Stage, StageAccumulator> accumulators = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            accumulators.put(stage, new StageAccumulator());
        }
        return accumulators;
    }

    public record StageStats(long count, double totalTimeMs, double averageTimeMs,
            double maxTimeMs) {}

    private static final class StageAccumulator {

        private LongAdder count = new LongAdder();
        private LongAdder totalNanos = new LongAdder();
        private LongAccumulator maxNanos = new LongAccumulator(Long::max, 0);

        private void record(long elapsedNanos) {
            count.increment();
            totalNanos.add(elapsedNanos);
            maxNanos.accumulate(elapsedNanos);
        }

        private StageStats snapshot() {
            long sampleCount = count.sum();
            long total = totalNanos.sum();
            return new StageStats(sampleCount, nanosToMillis(total),
                    sampleCount == 0 ? 0 : nanosToMillis(total) / sampleCount,
                    nanosToMillis(maxNanos.get()));
        }

        private void reset() {
            count = new LongAdder();
            totalNanos = new LongAdder();
            maxNanos = new LongAccumulator(Long::max, 0);
        }

        private double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }
}
