package com.tencent.supersonic.headless.chat.intent;

import com.tencent.supersonic.headless.chat.intent.BankIntentResult.Clarification;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void shouldRecognizeBothDepositComponentsForASeparatedShareQuestion() {
        BankIntentResult result = recognizer.recognize("江苏省B市农商行在2025-06-30的存款中，对公和个人分别占比多少？",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.RATIO, result.getIntent());
        assertEquals(Set.of("ZB001", "ZB003", "ZB004"), metricCodes(result));
        assertEquals("ORG002", result.getOrganizations().get(0).getCode());
        assertEquals(LocalDate.of(2025, 6, 30), result.getTime().getStartDate());
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
    void shouldResolveAnUnqualifiedYearEndAgainstTheExplicitHalfYear() {
        BankIntentResult result = recognizer.recognize(
                "江苏省A市农商行从2025年上半年末到年末，存款、贷款、不良率和净利润的变动方向分别是什么？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.CHANGE, result.getIntent());
        assertEquals(LocalDate.of(2025, 6, 30), result.getTime().getStartDate());
        assertEquals(LocalDate.of(2025, 12, 31), result.getTime().getEndDate());
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

    @Test
    void shouldRecognizeTheWinnerWithinAnExplicitOrganizationSubset() {
        BankIntentResult result = recognizer.recognize("2025年底，江苏省A市农商行、江苏省E市农商行、江苏省I市农商行三家谁存款最多？",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.RANKING, result.getIntent());
        assertEquals(Set.of("ORG001", "ORG005", "ORG009"), result.getOrganizations().stream()
                .map(BankIntentResult.OrganizationSlot::getCode).collect(Collectors.toSet()));
        assertTrue(result.getFilters().stream().anyMatch(
                filter -> "rank".equals(filter.getField()) && "1".equals(filter.getValue())));
    }

    @Test
    void shouldRecognizeTheLastPlaceAsABottomRankSlice() {
        BankIntentResult result =
                recognizer.recognize("2025年8月末，全省净利润排最后一名的是哪家？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.RANKING, result.getIntent());
        assertTrue(result.getFilters().stream()
                .anyMatch(filter -> "rank_from_bottom".equals(filter.getField())
                        && "1".equals(filter.getValue())));
    }

    @Test
    void shouldRecognizeAnAnnualDailyExtremaSummaryAsAggregation() {
        BankIntentResult result = recognizer.recognize(
                "江苏省J市农商行2025年全年的各项存款余额日均值是多少？最高日和最低日分别出现在什么水平？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.AGGREGATION, result.getIntent());
        assertEquals(Set.of("ZB001"), metricCodes(result));
        assertEquals("ORG010", result.getOrganizations().get(0).getCode());
        assertEquals(LocalDate.of(2025, 1, 1), result.getTime().getStartDate());
        assertEquals(LocalDate.of(2025, 12, 31), result.getTime().getEndDate());
        assertTrue(result.getFilters().isEmpty());
    }

    @Test
    void shouldNotTreatTheHighestQuarterInATrendAsARankingFilter() {
        BankIntentResult result = recognizer.recognize(
                "请分析江苏省A市农商行的各项存款余额从2025年一季度末到2026年一季度末的逐季变化，各季度末数值是多少？哪个季度数值最高？",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.TREND, result.getIntent());
        assertTrue(result.getFilters().stream().noneMatch(filter -> "rank".equals(filter.getField())
                || "rank_from_bottom".equals(filter.getField())));
    }

    @Test
    void shouldRecognizeADailyAverageQuestionAsAggregation() {
        BankIntentResult result =
                recognizer.recognize("江苏省J市农商行2025年全年各项存款余额日均是多少？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.AGGREGATION, result.getIntent());
        assertEquals(Set.of("ZB001"), metricCodes(result));
    }

    @Test
    void shouldNotTreatDailyAverageAsAggregationWhenRankingIsExpressed() {
        BankIntentResult result =
                recognizer.recognize("2025年全年各项存款余额日均排名前3和后3的农商行？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.RANKING, result.getIntent());
    }

    @Test
    void shouldNotTreatDailyAverageAsAggregationWhenTrendIsExpressed() {
        BankIntentResult result =
                recognizer.recognize("江苏省A市农商行各项存款余额日均的逐月趋势？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.TREND, result.getIntent());
    }

    @Test
    void shouldNotTreatDailyAverageAsAggregationWhenChangeIsExpressed() {
        BankIntentResult result =
                recognizer.recognize("江苏省A市农商行2025年各项存款余额日均较上季度末变化了多少？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.CHANGE, result.getIntent());
    }

    @Test
    void shouldNotTreatAMinimumRegulatoryRequirementAsARankingFilter() {
        BankIntentResult result = recognizer.recognize("2026年一季度末，江苏省H市农商行的资本充足率满足10.5%的最低要求吗？",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.THRESHOLD, result.getIntent());
        assertTrue(result.getFilters().stream().noneMatch(filter -> "rank".equals(filter.getField())
                || "rank_from_bottom".equals(filter.getField())));
        assertTrue(result.getFilters().stream()
                .anyMatch(filter -> "metric_value".equals(filter.getField())
                        && "GTE".equals(filter.getOperator())
                        && "10.5%".equals(filter.getValue())));
    }

    @Test
    void shouldRecognizeDaysAboveProvinceAverageAsAggregationWithBenchmark() {
        BankIntentResult result =
                recognizer.recognize("江苏省J市农商行2025年全年各项存款余额有多少天高于全省均值？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.AGGREGATION, result.getIntent());
        assertEquals(Set.of("ZB001"), metricCodes(result));
        assertEquals("ORG010", result.getOrganizations().get(0).getCode());
        assertEquals(LocalDate.of(2025, 1, 1), result.getTime().getStartDate());
        assertEquals(LocalDate.of(2025, 12, 31), result.getTime().getEndDate());
        assertTrue(result.getFilters().stream()
                .anyMatch(filter -> "benchmark".equals(filter.getField())
                        && "COMPARE".equals(filter.getOperator())
                        && "PROVINCE_AVERAGE".equals(filter.getValue())));
        assertFalse(result.isClarificationRequired());
    }

    @Test
    void shouldPreserveTheComparisonDirectionForProvinceAverageThresholds() {
        BankIntentResult result =
                recognizer.recognize("在2025-12-31，有多少家农商行的不良贷款率低于全省均值？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.THRESHOLD, result.getIntent());
        assertTrue(result.getFilters().stream()
                .anyMatch(filter -> "metric_value".equals(filter.getField())
                        && "LT".equals(filter.getOperator())
                        && "PROVINCE_AVERAGE".equals(filter.getValue())));
        assertTrue(result.getFilters().stream()
                .anyMatch(filter -> "benchmark".equals(filter.getField())
                        && "COMPARE".equals(filter.getOperator())
                        && "PROVINCE_AVERAGE".equals(filter.getValue())));
    }

    @Test
    void shouldKeepRankingStrongerThanDaysAboveProvinceAverageAggregation() {
        BankIntentResult result = recognizer.recognize("2025年全年各项存款余额有多少天在省均值以上，排名前3的农商行？",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.RANKING, result.getIntent());
    }

    @Test
    void shouldKeepTrendStrongerThanDaysAboveProvinceAverageAggregation() {
        BankIntentResult result = recognizer.recognize("江苏省J市农商行2025年各项存款余额有多少天高于全省均值的逐月趋势？",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.TREND, result.getIntent());
    }

    @Test
    void shouldKeepChangeStrongerThanDaysAboveProvinceAverageAggregation() {
        BankIntentResult result = recognizer.recognize("江苏省J市农商行2025年全年各项存款余额有多少天高于全省均值，较上季变化了多少？",
                LocalDate.of(2026, 7, 22));

        assertNotEquals(BankIntentType.AGGREGATION, result.getIntent());
    }

    @Test
    void shouldNotRecognizeDaysAboveProvinceAverageWithoutADayCountExpression() {
        BankIntentResult result =
                recognizer.recognize("江苏省J市农商行2025年全年各项存款余额高于全省均值吗？", LocalDate.of(2026, 7, 22));

        assertNotEquals(BankIntentType.AGGREGATION, result.getIntent());
    }

    @Test
    void shouldRecognizeDerivedLoanToDepositRatioWithRankingSemantics() {
        BankIntentResult result =
                recognizer.recognize("2025年一季度末全省哪家农商行的存贷比最高？", LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.RANKING, result.getIntent());
        assertEquals(1, result.getDerivedMetrics().size());
        BankIntentResult.DerivedMetricCandidate derived = result.getDerivedMetrics().get(0);
        assertEquals("DERIVED_ZB002_DIV_ZB001", derived.getCode());
        assertEquals("存贷比", derived.getName());
        assertEquals("ZB002", derived.getNumerator());
        assertEquals("ZB001", derived.getDenominator());
        assertTrue(result.getMetrics().stream()
                .noneMatch(metric -> metric.getCode().startsWith("DERIVED_")));
        assertEquals(Set.of("ZB002", "ZB001"), metricCodes(result));
    }

    @Test
    void shouldRecognizeNetProfitMarginWithItsDeclaredOperandOrder() {
        BankIntentResult result = recognizer.recognize("江苏省G市农商行2026年1月底，净利润率（净利润除以营业收入）是多少？",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.RATIO, result.getIntent());
        assertEquals(1, result.getDerivedMetrics().size());
        BankIntentResult.DerivedMetricCandidate derived = result.getDerivedMetrics().get(0);
        assertEquals("DERIVED_ZB011_DIV_ZB009", derived.getCode());
        assertEquals("ZB011", derived.getNumerator());
        assertEquals("ZB009", derived.getDenominator());
        assertEquals(Set.of("ZB011", "ZB009"), metricCodes(result));
    }

    @Test
    void shouldTreatGrowthRankingAsEndpointChangeInsteadOfPeriodAggregation() {
        BankIntentResult result = recognizer.recognize("从2024年末到2026-03-31，全省各项存款余额增幅排名前三的农商行有哪些？",
                LocalDate.of(2026, 7, 22));

        assertEquals(BankIntentType.CHANGE, result.getIntent());
        assertEquals(LocalDate.of(2024, 12, 31), result.getTime().getStartDate());
        assertEquals(LocalDate.of(2026, 3, 31), result.getTime().getEndDate());
    }

    private Set<String> metricCodes(BankIntentResult result) {
        return result.getMetrics().stream().map(BankIntentResult.MetricCandidate::getCode)
                .collect(Collectors.toSet());
    }
}
