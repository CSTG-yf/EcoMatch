package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.BusinessInsightReq;
import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartInsightResp;
import com.tencent.supersonic.chat.server.processor.execute.BusinessInsightConfig;
import com.tencent.supersonic.common.pojo.QueryColumn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessInsightServiceTest {

    @Test
    void appliesConfiguredChartThresholdThroughServiceApi() {
        BusinessInsightConfig config = new BusinessInsightConfig(3, 2, 2.0, 0.65, 0.82, 0.95);
        BusinessInsightService service = new BusinessInsightService(config);
        BusinessInsightReq request = request();

        ChartInsightResp response = service.recommend(request);

        assertEquals("BAR", response.getRecommendedChart().getChartType());
        assertTrue(response.getCandidateCharts().stream()
                .noneMatch(chart -> "PIE".equals(chart.getChartType())));
    }

    @Test
    void exposesEvidenceBackedExplanationApi() {
        BusinessInsightService service =
                new BusinessInsightService(BusinessInsightConfig.defaults());

        BusinessExplanation explanation = service.explain(request());

        assertTrue(explanation.getSummary().contains("问题范围：各机构贷款余额"));
        assertTrue(explanation.getEvidence().contains("metric_value范围为10至30"));
    }

    @Test
    void rejectsMissingResultData() {
        BusinessInsightService service =
                new BusinessInsightService(BusinessInsightConfig.defaults());

        assertThrows(RuntimeException.class, () -> service.recommend(new BusinessInsightReq()));
    }

    @Test
    void rejectsResultThatDoesNotMatchDeclaredColumns() {
        BusinessInsightService service =
                new BusinessInsightService(BusinessInsightConfig.defaults());
        BusinessInsightReq request = request();
        request.setQueryResults(List.of(Map.of("unknown", 1)));

        assertThrows(RuntimeException.class, () -> service.explain(request));
    }

    @Test
    void explainsEmptyResultWithoutMakingClaims() {
        BusinessInsightService service =
                new BusinessInsightService(BusinessInsightConfig.defaults());
        BusinessInsightReq request = request();
        request.setQueryResults(List.of());

        BusinessExplanation explanation = service.explain(request);

        assertTrue(explanation.getEvidence().isEmpty());
        assertTrue(
                explanation.getWarnings().stream().anyMatch(warning -> warning.contains("未返回数据")));
    }

    @Test
    void rejectsInputThatExceedsConfiguredRowLimit() {
        BusinessInsightConfig config =
                new BusinessInsightConfig(3, 6, 2.0, 0.65, 0.82, 0.95, 2, 100);

        assertThrows(RuntimeException.class,
                () -> new BusinessInsightService(config).explain(request()));
    }

    @Test
    void rejectsInputThatExceedsConfiguredColumnLimit() {
        BusinessInsightConfig config =
                new BusinessInsightConfig(3, 6, 2.0, 0.65, 0.82, 0.95, 10_000, 1);

        assertThrows(RuntimeException.class,
                () -> new BusinessInsightService(config).recommend(request()));
    }

    private BusinessInsightReq request() {
        BusinessInsightReq request = new BusinessInsightReq();
        request.setQueryText("各机构贷款余额");
        request.setQueryColumns(
                List.of(column("category_name", "CATEGORY"), column("metric_value", "NUMBER")));
        request.setQueryResults(List.of(Map.of("category_name", "A", "metric_value", 10),
                Map.of("category_name", "B", "metric_value", 20),
                Map.of("category_name", "C", "metric_value", 30)));
        return request;
    }

    private QueryColumn column(String name, String showType) {
        QueryColumn column = new QueryColumn(name, "VARCHAR", name);
        column.setShowType(showType);
        return column;
    }
}
