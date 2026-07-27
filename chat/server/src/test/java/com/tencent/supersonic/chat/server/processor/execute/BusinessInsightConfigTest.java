package com.tencent.supersonic.chat.server.processor.execute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessInsightConfigTest {

    @Test
    void rejectsInvalidStructuralAndResourceLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> config(0, 6, 2.0, 0.65, 0.82, 0.95, 10_000, 100));
        assertThrows(IllegalArgumentException.class,
                () -> config(3, 1, 2.0, 0.65, 0.82, 0.95, 10_000, 100));
        assertThrows(IllegalArgumentException.class,
                () -> config(3, 6, 2.0, 0.65, 0.82, 0.95, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> config(3, 6, 2.0, 0.65, 0.82, 0.95, 10_000, 0));
    }

    @Test
    void rejectsNonFiniteOrInconsistentScoringThresholds() {
        assertThrows(IllegalArgumentException.class,
                () -> config(3, 6, Double.NaN, 0.65, 0.82, 0.95, 10_000, 100));
        assertThrows(IllegalArgumentException.class,
                () -> config(3, 6, 2.0, Double.NaN, 0.82, 0.95, 10_000, 100));
        assertThrows(IllegalArgumentException.class,
                () -> config(3, 6, 2.0, -0.1, 0.82, 0.95, 10_000, 100));
        assertThrows(IllegalArgumentException.class,
                () -> config(3, 6, 2.0, 0.9, 0.82, 0.95, 10_000, 100));
        assertThrows(IllegalArgumentException.class,
                () -> config(3, 6, 2.0, 0.65, 0.99, 0.95, 10_000, 100));
    }

    private BusinessInsightConfig config(int smallSampleThreshold, int pieMaxCategories,
            double anomalyZScore, double lowConfidence, double evidenceConfidence,
            double highConfidence, int maxInputRows, int maxInputColumns) {
        return new BusinessInsightConfig(smallSampleThreshold, pieMaxCategories, anomalyZScore,
                lowConfidence, evidenceConfidence, highConfidence, maxInputRows, maxInputColumns);
    }
}
