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

    @Test
    void shouldRecognizeIsoQuarterRangeAsTrend() {
        BankIntentResult result = recognizer.recognize(
                "\u5206\u6790\u6c5f\u82cf\u7701D\u5e02\u519c\u5546\u884c\u5404\u9879\u5b58\u6b3e\u4f59\u989d\u4ece2025Q1\u672b\u52302026Q1\u672b\u7684\u9010\u5b63\u53d8\u5316",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.TREND, result.getIntent());
        assertEquals(Set.of("ZB001"), metricCodes(result));
        assertEquals("ORG004", result.getOrganizations().get(0).getCode());
        assertEquals(LocalDate.of(2025, 3, 31), result.getTime().getStartDate());
        assertEquals(LocalDate.of(2026, 3, 31), result.getTime().getEndDate());
    }

    @Test
    void shouldExpandComprehensivePerformanceRankingToTheBankProfile() {
        BankIntentResult result = recognizer.recognize(
                "\u6c5f\u82cf\u7701F\u5e02\u519c\u5546\u884c\u57282025-11-30\u7684\u6307\u6807\u4e2d\u54ea\u4e9b\u8868\u73b0\u8f83\u597d\uff1f\u54ea\u4e9b\u8868\u73b0\u8f83\u5dee\uff1f",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.RANKING, result.getIntent());
        assertEquals(Set.of("ZB001", "ZB002", "ZB011", "ZB012", "ZB013", "ZB015", "ZB016", "ZB017"),
                metricCodes(result));
        assertTrue(result.getFilters().isEmpty());
        assertFalse(result.isClarificationRequired());
    }

    @Test
    void shouldKeepTheTopThreeFilterForGoodPerformanceOnly() {
        BankIntentResult result =
                recognizer.recognize("江苏省F市农商行在2025-11-30的指标中哪些表现较好？", LocalDate.of(2026, 7, 22));

        assertEquals(1, result.getFilters().size());
        assertEquals("rank", result.getFilters().get(0).getField());
        assertEquals("3", result.getFilters().get(0).getValue());
    }

    private Set<String> metricCodes(BankIntentResult result) {
        return result.getMetrics().stream().map(BankIntentResult.MetricCandidate::getCode)
                .collect(Collectors.toSet());
    }
}
