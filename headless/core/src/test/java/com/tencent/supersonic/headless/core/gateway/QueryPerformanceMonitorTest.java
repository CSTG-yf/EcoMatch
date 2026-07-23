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

        Map<String, QueryPerformanceMonitor.StageStats> snapshot =
                QueryPerformanceMonitor.snapshot();

        assertEquals(2, snapshot.get("parse").count());
        assertEquals(20.0, snapshot.get("parse").averageTimeMs());
        assertEquals(30.0, snapshot.get("parse").maxTimeMs());
        assertEquals(1, snapshot.get("model").count());
        assertTrue(snapshot.containsKey("translate"));
        assertTrue(snapshot.containsKey("execute"));
        assertTrue(snapshot.containsKey("explain"));
    }
}
