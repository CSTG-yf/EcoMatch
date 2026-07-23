package com.tencent.supersonic.chat.server.processor.execute;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class BusinessInsightConfig {

    private final int smallSampleThreshold;
    private final int pieMaxCategories;
    private final double anomalyZScore;
    private final double lowConfidence;
    private final double evidenceConfidence;
    private final double highConfidence;
    private final int maxInputRows;
    private final int maxInputColumns;

    @Autowired
    public BusinessInsightConfig(
            @Value("${s2.business-insight.small-sample-threshold:3}") int smallSampleThreshold,
            @Value("${s2.business-insight.pie-max-categories:6}") int pieMaxCategories,
            @Value("${s2.business-insight.anomaly-z-score:2.0}") double anomalyZScore,
            @Value("${s2.business-insight.low-confidence:0.65}") double lowConfidence,
            @Value("${s2.business-insight.evidence-confidence:0.82}") double evidenceConfidence,
            @Value("${s2.business-insight.high-confidence:0.95}") double highConfidence,
            @Value("${s2.business-insight.max-input-rows:10000}") int maxInputRows,
            @Value("${s2.business-insight.max-input-columns:100}") int maxInputColumns) {
        this.smallSampleThreshold = Math.max(1, smallSampleThreshold);
        this.pieMaxCategories = Math.max(2, pieMaxCategories);
        this.anomalyZScore = Math.max(0.1, anomalyZScore);
        this.lowConfidence = clamp(lowConfidence);
        this.evidenceConfidence = clamp(evidenceConfidence);
        this.highConfidence = clamp(highConfidence);
        this.maxInputRows = Math.max(1, maxInputRows);
        this.maxInputColumns = Math.max(1, maxInputColumns);
    }

    public BusinessInsightConfig(int smallSampleThreshold, int pieMaxCategories,
            double anomalyZScore, double lowConfidence, double evidenceConfidence,
            double highConfidence) {
        this(smallSampleThreshold, pieMaxCategories, anomalyZScore, lowConfidence,
                evidenceConfidence, highConfidence, 10_000, 100);
    }

    public static BusinessInsightConfig defaults() {
        return new BusinessInsightConfig(3, 6, 2.0, 0.65, 0.82, 0.95, 10_000, 100);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
