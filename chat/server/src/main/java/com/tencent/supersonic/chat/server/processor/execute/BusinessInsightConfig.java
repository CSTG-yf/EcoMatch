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
    private final int maxQueryTextLength;
    private final int maxMetadataTextLength;
    private final int maxCellTextLength;
    private final int maxTotalInputCharacters;

    @Autowired
    public BusinessInsightConfig(
            @Value("${s2.business-insight.small-sample-threshold:3}") int smallSampleThreshold,
            @Value("${s2.business-insight.pie-max-categories:6}") int pieMaxCategories,
            @Value("${s2.business-insight.anomaly-z-score:2.0}") double anomalyZScore,
            @Value("${s2.business-insight.low-confidence:0.65}") double lowConfidence,
            @Value("${s2.business-insight.evidence-confidence:0.82}") double evidenceConfidence,
            @Value("${s2.business-insight.high-confidence:0.95}") double highConfidence,
            @Value("${s2.business-insight.max-input-rows:10000}") int maxInputRows,
            @Value("${s2.business-insight.max-input-columns:100}") int maxInputColumns,
            @Value("${s2.business-insight.max-query-text-length:4096}") int maxQueryTextLength,
            @Value("${s2.business-insight.max-metadata-text-length:4096}") int maxMetadataTextLength,
            @Value("${s2.business-insight.max-cell-text-length:16384}") int maxCellTextLength,
            @Value("${s2.business-insight.max-total-input-characters:2000000}") int maxTotalInputCharacters) {
        requireAtLeast(smallSampleThreshold, 1, "small-sample-threshold");
        requireAtLeast(pieMaxCategories, 2, "pie-max-categories");
        requirePositiveFinite(anomalyZScore, "anomaly-z-score");
        requireProbability(lowConfidence, "low-confidence");
        requireProbability(evidenceConfidence, "evidence-confidence");
        requireProbability(highConfidence, "high-confidence");
        if (lowConfidence > evidenceConfidence || evidenceConfidence > highConfidence) {
            throw new IllegalArgumentException(
                    "Business insight confidence thresholds must be ordered low <= evidence <= high");
        }
        requireAtLeast(maxInputRows, 1, "max-input-rows");
        requireAtLeast(maxInputColumns, 1, "max-input-columns");
        requireAtLeast(maxQueryTextLength, 1, "max-query-text-length");
        requireAtLeast(maxMetadataTextLength, 1, "max-metadata-text-length");
        requireAtLeast(maxCellTextLength, 1, "max-cell-text-length");
        requireAtLeast(maxTotalInputCharacters, 1, "max-total-input-characters");
        this.smallSampleThreshold = smallSampleThreshold;
        this.pieMaxCategories = pieMaxCategories;
        this.anomalyZScore = anomalyZScore;
        this.lowConfidence = lowConfidence;
        this.evidenceConfidence = evidenceConfidence;
        this.highConfidence = highConfidence;
        this.maxInputRows = maxInputRows;
        this.maxInputColumns = maxInputColumns;
        this.maxQueryTextLength = maxQueryTextLength;
        this.maxMetadataTextLength = maxMetadataTextLength;
        this.maxCellTextLength = maxCellTextLength;
        this.maxTotalInputCharacters = maxTotalInputCharacters;
    }

    public BusinessInsightConfig(int smallSampleThreshold, int pieMaxCategories,
            double anomalyZScore, double lowConfidence, double evidenceConfidence,
            double highConfidence, int maxInputRows, int maxInputColumns) {
        this(smallSampleThreshold, pieMaxCategories, anomalyZScore, lowConfidence,
                evidenceConfidence, highConfidence, maxInputRows, maxInputColumns, 4096, 4096,
                16_384, 2_000_000);
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

    private void requireAtLeast(int value, int minimum, String property) {
        if (value < minimum) {
            throw new IllegalArgumentException(
                    "s2.business-insight." + property + " must be at least " + minimum);
        }
    }

    private void requirePositiveFinite(double value, String property) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(
                    "s2.business-insight." + property + " must be finite and greater than zero");
        }
    }

    private void requireProbability(double value, String property) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(
                    "s2.business-insight." + property + " must be finite and within [0,1]");
        }
    }
}
