package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartRecommendation;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.common.pojo.QueryColumn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void rejectsExplanationWithFabricatedNumericEvidence() {
        QueryResult result = validResult();
        result.getBusinessExplanation().setEvidence(List.of("balance范围为100至999"));
        result.getBusinessExplanation()
                .setSummary("查询返回2条记录，时间范围为2026-01至2026-02。balance范围为100至999。提示：范围限制。");
        result.setTextSummary(result.getBusinessExplanation().getSummary());

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
        result.setQueryColumns(List.of(month, balance));
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
}
