package com.tencent.supersonic.headless.core.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryPerformanceMonitorTest {

    @BeforeEach
    void reset() {
        QueryPerformanceMonitor.reset();
    }

    @Test
    void aggregatesAllQueryStages() {
        QueryPerformanceMonitor.record(QueryPerformanceMonitor.Stage.PARSE,
                TimeUnit.MILLISECONDS.toNanos(10));
        QueryPerformanceMonitor.record(QueryPerformanceMonitor.Stage.PARSE,
                TimeUnit.MILLISECONDS.toNanos(30));
        QueryPerformanceMonitor.record(QueryPerformanceMonitor.Stage.MODEL,
                TimeUnit.MILLISECONDS.toNanos(50));
        QueryPerformanceMonitor.recordCacheLookup(true);
        QueryPerformanceMonitor.recordCacheLookup(false);
        QueryPerformanceMonitor.recordCacheLookup(true, true);

        Map<String, QueryPerformanceMonitor.StageStats> snapshot =
                QueryPerformanceMonitor.snapshot();

        assertEquals(2, snapshot.get("parse").count());
        assertEquals(40.0, snapshot.get("parse").totalTimeMs());
        assertEquals(20.0, snapshot.get("parse").averageTimeMs());
        assertEquals(30.0, snapshot.get("parse").maxTimeMs());
        assertEquals(10.0, snapshot.get("parse").p50TimeMs());
        assertEquals(30.0, snapshot.get("parse").p95TimeMs());
        assertEquals(30.0, snapshot.get("parse").p99TimeMs());
        assertEquals(2, QueryPerformanceMonitor.cacheSnapshot().hits());
        assertEquals(1, QueryPerformanceMonitor.cacheSnapshot().misses());
        assertEquals(2.0 / 3.0, QueryPerformanceMonitor.cacheSnapshot().hitRate());
        assertEquals(1, QueryPerformanceMonitor.cacheSnapshot().hotMetricHits());
        assertEquals(1, QueryPerformanceMonitor.cacheSnapshot().hotMetricRequests());
        assertEquals(1.0, QueryPerformanceMonitor.cacheSnapshot().hotMetricHitRate());
        assertEquals(1, snapshot.get("model").count());
        assertTrue(snapshot.containsKey("translate"));
        assertTrue(snapshot.containsKey("execute"));
        assertTrue(snapshot.containsKey("explain"));
    }
}
