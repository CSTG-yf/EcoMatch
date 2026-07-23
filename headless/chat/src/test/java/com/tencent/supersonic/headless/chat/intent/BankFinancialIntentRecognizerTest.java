package com.tencent.supersonic.headless.chat.intent;

import com.tencent.supersonic.headless.chat.intent.BankIntentResult.Clarification;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankFinancialIntentRecognizerTest {

    private final BankFinancialIntentRecognizer recognizer = new BankFinancialIntentRecognizer();

    @Test
    void shouldRecognizeRiskRankingWithAbsoluteTime() {
        BankIntentResult result =
                recognizer.recognize("2026年一季度末全省哪家拨备覆盖率最高", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.RANKING, result.getIntent());
        assertEquals(BankBusinessScene.RISK_CONTROL, result.getScene());
        assertEquals(Set.of("ZB015"), metricCodes(result));
        assertEquals(LocalDate.of(2026, 3, 31), result.getTime().getEndDate());
        assertFalse(result.isClarificationRequired());
    }

    @Test
    void shouldNormalizeTypoAbbreviationAndRelativeTime() {
        BankIntentResult result = recognizer.recognize("帮我看下A行今年的不良货款率", LocalDate.of(2026, 7, 22));

        assertTrue(result.getNormalizedText().contains("江苏省A市农商行"));
        assertTrue(result.getNormalizedText().contains("不良贷款率"));
        assertEquals("ORG001", result.getOrganizations().get(0).getCode());
        assertEquals(Set.of("ZB013"), metricCodes(result));
        assertEquals(LocalDate.of(2026, 1, 1), result.getTime().getStartDate());
    }

    @Test
    void shouldClarifyBroadMetricAndVagueTime() {
        BankIntentResult result = recognizer.recognize("最近贷款情况怎么样", LocalDate.of(2026, 7, 22));

        assertTrue(result.isClarificationRequired());
        Set<String> types = result.getClarifications().stream().map(Clarification::getType)
                .collect(Collectors.toSet());
        assertTrue(types.contains("METRIC"));
        assertTrue(types.contains("TIME"));
        assertTrue(types.contains("ORGANIZATION"));
        assertTrue(result.getConfidence() < BankFinancialIntentRecognizer.CLARIFICATION_THRESHOLD);
    }

    @Test
    void shouldNotExpandCanonicalNamesTwice() {
        BankIntentResult result =
                recognizer.recognize("江苏省A市农商行2026年3月末资本充足率是多少", LocalDate.of(2026, 7, 22));

        assertEquals("江苏省A市农商行2026年3月末资本充足率是多少", result.getNormalizedText());
        assertEquals(Set.of("ZB016"), metricCodes(result));
    }

    private Set<String> metricCodes(BankIntentResult result) {
        return result.getMetrics().stream().map(BankIntentResult.MetricCandidate::getCode)
                .collect(Collectors.toSet());
    }
}
