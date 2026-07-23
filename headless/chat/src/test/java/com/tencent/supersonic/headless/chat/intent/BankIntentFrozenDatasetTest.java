package com.tencent.supersonic.headless.chat.intent;

import com.tencent.supersonic.common.util.JsonUtil;
import lombok.Data;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BankIntentFrozenDatasetTest {

    private final BankFinancialIntentRecognizer recognizer = new BankFinancialIntentRecognizer();

    @Test
    void shouldMeetFrozenDatasetThresholds() throws IOException {
        List<DatasetCase> cases =
                Files.readAllLines(datasetPath()).stream().filter(line -> !line.isBlank())
                        .map(line -> JsonUtil.toObject(line, DatasetCase.class))
                        .collect(Collectors.toList());
        int intentCorrect = 0;
        int metricCorrect = 0;
        int clarificationCorrect = 0;
        List<String> intentErrors = new ArrayList<>();
        for (DatasetCase item : cases) {
            BankIntentResult actual = recognizer.recognize(item.getQuestion(),
                    LocalDate.parse(item.getReferenceDate()));
            if (item.getIntent() == actual.getIntent()) {
                intentCorrect++;
            } else {
                intentErrors.add(item.getId() + ": expected=" + item.getIntent() + ", actual="
                        + actual.getIntent());
            }
            Set<String> expectedMetrics = item.getMetrics().stream().map(MetricLabel::getCode)
                    .collect(Collectors.toSet());
            Set<String> actualMetrics = actual.getMetrics().stream()
                    .map(BankIntentResult.MetricCandidate::getCode).collect(Collectors.toSet());
            if (expectedMetrics.equals(actualMetrics)) {
                metricCorrect++;
            }
            if (item.isClarificationExpected() == actual.isClarificationRequired()) {
                clarificationCorrect++;
            }
        }
        double intentAccuracy = ratio(intentCorrect, cases.size());
        double metricAccuracy = ratio(metricCorrect, cases.size());
        double clarificationAccuracy = ratio(clarificationCorrect, cases.size());
        System.out.printf("BANK_INTENT_EVAL cases=%d intent=%.4f metric=%.4f clarification=%.4f%n",
                cases.size(), intentAccuracy, metricAccuracy, clarificationAccuracy);

        assertTrue(cases.size() >= 40, "frozen test set must contain at least 40 cases");
        assertTrue(intentAccuracy >= 0.94D,
                "intent accuracy=" + intentAccuracy + ", errors=" + intentErrors);
        assertTrue(metricAccuracy >= 0.94D, "metric accuracy=" + metricAccuracy);
        assertTrue(clarificationAccuracy >= 0.90D,
                "clarification accuracy=" + clarificationAccuracy);
    }

    private Path datasetPath() {
        List<Path> candidates = List.of(Path.of("evaluation", "bank_intent", "test.jsonl"),
                Path.of("..", "..", "evaluation", "bank_intent", "test.jsonl"));
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IllegalStateException("DATA-01 test.jsonl not found"));
    }

    private double ratio(int value, int total) {
        return total == 0 ? 0D : (double) value / total;
    }

    @Data
    static class DatasetCase {
        private String id;
        private String question;
        private BankIntentType intent;
        private List<MetricLabel> metrics = new ArrayList<>();
        private boolean clarificationExpected;
        private String referenceDate;
    }

    @Data
    static class MetricLabel {
        private String code;
    }
}
