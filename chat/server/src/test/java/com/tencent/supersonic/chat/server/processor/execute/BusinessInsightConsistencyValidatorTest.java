package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartRecommendation;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.common.pojo.QueryColumn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessInsightConsistencyValidatorTest {

    private final BusinessInsightConsistencyValidator validator =
            new BusinessInsightConsistencyValidator();

    @Test
    void rejectsChartFieldThatIsNotInQueryResult() {
        QueryResult result = validResult();
        result.getRecommendedChart().setMetricFields(List.of("fabricated_metric"));

        assertThrows(IllegalStateException.class, () -> validator.validate(result));
    }

    @Test
    void rejectsRecommendedOrCandidateChartThatReferencesMaskedFields() {
        QueryResult recommended = validResult();
        recommended.setDataMasked(true);
        recommended.setMaskedColumns(Set.of("BALANCE"));

        assertThrows(IllegalStateException.class, () -> validator.validate(recommended));

        QueryResult candidate = validResult();
        candidate.setDataMasked(true);
        candidate.setMaskedColumns(Set.of("balance"));
        candidate.getRecommendedChart().setMetricFields(List.of());
        candidate.setCandidateCharts(List.of(ChartRecommendation.builder().chartType("BAR")
                .confidence(0.8).reason("candidate").dimensionFields(List.of("month"))
                .metricFields(List.of("balance")).build()));

        assertThrows(IllegalStateException.class, () -> validator.validate(candidate));
    }

    @Test
    void rejectsUnsupportedTypesAndInvalidChartFieldRoles() {
        QueryResult unsupported = validResult();
        unsupported.getRecommendedChart().setChartType("SCATTER");
        assertThrows(IllegalStateException.class, () -> validator.validate(unsupported));

        QueryResult swappedRoles = validResult();
        swappedRoles.getRecommendedChart().setDimensionFields(List.of("balance"));
        swappedRoles.getRecommendedChart().setMetricFields(List.of("month"));
        assertThrows(IllegalStateException.class, () -> validator.validate(swappedRoles));

        QueryResult invalidPie = validResult();
        invalidPie.getRecommendedChart().setChartType("PIE");
        assertThrows(IllegalStateException.class, () -> validator.validate(invalidPie));
    }

    @Test
    void rejectsMaskedResultWithoutFieldMetadata() {
        QueryResult result = validResult();
        result.setDataMasked(true);
        result.setMaskedColumns(Set.of());

        assertThrows(IllegalStateException.class, () -> validator.validate(result));
    }

    @Test
    void rejectsExplanationWithFabricatedNumericEvidence() {
        QueryResult result = validResult();
        result.getBusinessExplanation().setEvidence(List.of("balance范围为100至999"));
        result.getBusinessExplanation()
                .setSummary("查询返回2条记录，时间范围为2026-01至2026-02。balance范围为100至999。提示：范围限制。");
        result.setTextSummary(result.getBusinessExplanation().getSummary());

        assertThrows(IllegalStateException.class, () -> validator.validate(result));
    }

    @Test
    void rejectsEvidenceUsingValuesFromAnotherMetric() {
        QueryResult result = validResult();
        result.getQueryColumns().add(column("deposit", "NUMBER"));
        result.setQueryResults(List.of(Map.of("month", "2026-01", "balance", 100, "deposit", 1),
                Map.of("month", "2026-02", "balance", 120, "deposit", 2)));
        result.getBusinessExplanation().setEvidence(List.of("deposit范围为100至120"));
        result.getBusinessExplanation()
                .setSummary("查询返回2条记录，时间范围为2026-01至2026-02。deposit范围为100至120。提示：范围限制。");
        result.setTextSummary(result.getBusinessExplanation().getSummary());

        assertThrows(IllegalStateException.class, () -> validator.validate(result));
    }

    @Test
    void rejectsBusinessLabelEvidenceUsingValuesFromAnotherMetric() {
        QueryResult result = validResult();
        result.getQueryColumns().add(column("deposit", "NUMBER"));
        result.setQueryResults(List.of(Map.of("month", "2026-01", "balance", 100, "deposit", 1),
                Map.of("month", "2026-02", "balance", 120, "deposit", 2)));
        result.getBusinessExplanation().setEvidence(List.of("存款余额范围为100至120"));
        result.getBusinessExplanation()
                .setSummary("查询返回2条记录，时间范围为2026-01至2026-02。存款余额范围为100至120。提示：范围限制。");
        result.setTextSummary(result.getBusinessExplanation().getSummary());

        assertThrows(IllegalStateException.class,
                () -> validator.validate(result, Map.of("deposit", "存款余额")));
    }

    @Test
    void rejectsPercentageBorrowedFromAnotherEvidenceType() {
        QueryResult result = validResult();
        result.getBusinessExplanation().setEvidence(List.of("balance首末记录变化45.45%"));
        result.getBusinessExplanation()
                .setSummary("查询返回2条记录，时间范围为2026-01至2026-02。balance首末记录变化45.45%。提示：范围限制。");
        result.setTextSummary(result.getBusinessExplanation().getSummary());

        assertThrows(IllegalStateException.class, () -> validator.validate(result));
    }

    @Test
    void validatesContributionCategoryAndMetricTogether() {
        QueryResult result = validResult();
        result.setQueryColumns(new java.util.ArrayList<>(
                List.of(column("branch", "STRING"), column("balance", "NUMBER"))));
        result.setQueryResults(List.of(Map.of("branch", "A", "balance", 30),
                Map.of("branch", "B", "balance", 70)));
        result.getRecommendedChart().setChartType("BAR");
        result.getRecommendedChart().setDimensionFields(List.of("branch"));
        result.getBusinessExplanation().setTimeRange(null);
        setEvidence(result, "B的balance贡献度最高，为70%", "查询返回2条记录。B的balance贡献度最高，为70%。提示：范围限制。");

        assertDoesNotThrow(() -> validator.validate(result));

        setEvidence(result, "A的balance贡献度最高，为70%", "查询返回2条记录。A的balance贡献度最高，为70%。提示：范围限制。");
        assertThrows(IllegalStateException.class, () -> validator.validate(result));
    }

    @Test
    void validatesTemporalComparisonTypeAndPeriodsTogether() {
        QueryResult result = validResult();
        result.setQueryResults(List.of(Map.of("month", "2025-01", "balance", 100),
                Map.of("month", "2025-12", "balance", 200),
                Map.of("month", "2026-01", "balance", 300)));
        result.getBusinessExplanation().setTimeRange("2025-01至2026-01");
        setEvidence(result, "balance同比变化200%（2026-01较2025-01）",
                "查询返回3条记录，时间范围为2025-01至2026-01。" + "balance同比变化200%（2026-01较2025-01）。提示：范围限制。");

        assertDoesNotThrow(() -> validator.validate(result));

        setEvidence(result, "balance同比变化50%（2026-01较2025-12）",
                "查询返回3条记录，时间范围为2025-01至2026-01。" + "balance同比变化50%（2026-01较2025-12）。提示：范围限制。");
        assertThrows(IllegalStateException.class, () -> validator.validate(result));
    }

    @Test
    void rejectsExplanationWithIncorrectTimeRange() {
        QueryResult result = validResult();
        result.getBusinessExplanation().setTimeRange("2025-01至2026-02");

        assertThrows(IllegalStateException.class, () -> validator.validate(result));
    }

    private QueryResult validResult() {
        QueryColumn month = column("month", "DATE");
        QueryColumn balance = column("balance", "NUMBER");
        ChartRecommendation chart =
                ChartRecommendation.builder().chartType("LINE").confidence(0.95).reason("时间趋势")
                        .dimensionFields(List.of("month")).metricFields(List.of("balance")).build();
        String summary = "查询返回2条记录，时间范围为2026-01至2026-02。balance范围为100至120。提示：范围限制。";
        BusinessExplanation explanation = BusinessExplanation.builder().summary(summary)
                .confidence(0.9).timeRange("2026-01至2026-02").evidence(List.of("balance范围为100至120"))
                .warnings(List.of("范围限制")).build();
        QueryResult result = new QueryResult();
        result.setQueryColumns(new java.util.ArrayList<>(List.of(month, balance)));
        result.setQueryResults(List.of(Map.of("month", "2026-01", "balance", 100),
                Map.of("month", "2026-02", "balance", 120)));
        result.setRecommendedChart(chart);
        result.setCandidateCharts(List.of());
        result.setBusinessExplanation(explanation);
        result.setTextSummary(summary);
        return result;
    }

    private QueryColumn column(String name, String showType) {
        QueryColumn column = new QueryColumn(name, "VARCHAR", name);
        column.setShowType(showType);
        return column;
    }

    private void setEvidence(QueryResult result, String evidence, String summary) {
        result.getBusinessExplanation().setEvidence(List.of(evidence));
        result.getBusinessExplanation().setSummary(summary);
        result.setTextSummary(summary);
    }
}
