package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.BusinessInsightReq;
import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartInsightResp;
import com.tencent.supersonic.chat.server.processor.execute.BusinessInsightConfig;
import com.tencent.supersonic.common.pojo.QueryColumn;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void rejectsMaskedInputWithoutFieldMetadata() {
        BusinessInsightReq request = request();
        request.setDataMasked(true);

        assertThrows(RuntimeException.class,
                () -> new BusinessInsightService(BusinessInsightConfig.defaults())
                        .recommend(request));
    }

    @Test
    void excludesDeclaredMaskedMetricsFromIndependentInsightApi() {
        BusinessInsightReq request = request();
        request.setDataMasked(true);
        request.setMaskedColumns(Set.of("metric_value"));

        BusinessInsightService service =
                new BusinessInsightService(BusinessInsightConfig.defaults());
        ChartInsightResp chart = service.recommend(request);
        BusinessExplanation explanation = service.explain(request);

        assertEquals("TABLE", chart.getRecommendedChart().getChartType());
        assertTrue(explanation.getEvidence().isEmpty());
        assertTrue(
                explanation.getWarnings().stream().anyMatch(warning -> warning.contains("脱敏字段")));
    }

    @Test
    void normalizesMaskedDisplayAliasBeforeInsightProcessing() {
        BusinessInsightReq request = request();
        request.getQueryColumns().get(1).setName("loan_balance");
        request.setDataMasked(true);
        request.setMaskedColumns(Set.of("LOAN_BALANCE"));

        BusinessInsightService service =
                new BusinessInsightService(BusinessInsightConfig.defaults());
        ChartInsightResp chart = service.recommend(request);
        BusinessExplanation explanation = service.explain(request);

        assertEquals("TABLE", chart.getRecommendedChart().getChartType());
        assertTrue(explanation.getEvidence().isEmpty());
        assertTrue(
                explanation.getWarnings().stream().anyMatch(warning -> warning.contains("脱敏字段")));
    }

    @Test
    void rejectsDuplicateAliasesForOneMaskedField() {
        BusinessInsightReq request = request();
        request.getQueryColumns().get(1).setName("loan_balance");
        request.setDataMasked(true);
        request.setMaskedColumns(Set.of("metric_value", "loan_balance"));

        assertThrows(RuntimeException.class,
                () -> new BusinessInsightService(BusinessInsightConfig.defaults())
                        .recommend(request));
    }

    @Test
    void rejectsContradictoryMaskingState() {
        BusinessInsightReq request = request();
        request.setMaskedColumns(Set.of("metric_value"));

        assertThrows(RuntimeException.class,
                () -> new BusinessInsightService(BusinessInsightConfig.defaults())
                        .recommend(request));
    }

    @Test
    void rejectsUnknownMaskedColumnMetadata() {
        BusinessInsightReq request = request();
        request.setDataMasked(true);
        request.setMaskedColumns(Set.of("not_a_result_field"));

        assertThrows(RuntimeException.class,
                () -> new BusinessInsightService(BusinessInsightConfig.defaults())
                        .recommend(request));
    }

    @Test
    void rejectsUndeclaredResultFields() {
        BusinessInsightReq request = request();
        request.setQueryResults(
                List.of(Map.of("category_name", "A", "metric_value", 10, "hidden_value", 99)));

        assertThrows(RuntimeException.class,
                () -> new BusinessInsightService(BusinessInsightConfig.defaults())
                        .explain(request));
    }

    @Test
    void rejectsSparseResultRows() {
        BusinessInsightReq request = request();
        request.setQueryResults(List.of(Map.of("category_name", "A", "metric_value", 10),
                Map.of("category_name", "B")));

        assertThrows(RuntimeException.class,
                () -> new BusinessInsightService(BusinessInsightConfig.defaults())
                        .explain(request));
    }

    @Test
    void rejectsCaseInsensitiveColumnAliasCollisions() {
        BusinessInsightReq request = request();
        QueryColumn duplicateAlias = column("another_metric", "NUMBER");
        duplicateAlias.setName("METRIC_VALUE");
        request.setQueryColumns(List.of(column("category_name", "CATEGORY"),
                column("metric_value", "NUMBER"), duplicateAlias));

        assertThrows(RuntimeException.class,
                () -> new BusinessInsightService(BusinessInsightConfig.defaults())
                        .recommend(request));
    }

    @Test
    void rejectsCaseVariantAndEmptyResultRows() {
        BusinessInsightService service =
                new BusinessInsightService(BusinessInsightConfig.defaults());
        BusinessInsightReq caseVariant = request();
        caseVariant.setQueryResults(List.of(Map.of("category_name", "A", "metric_value", 10),
                Map.of("category_name", "B", "METRIC_VALUE", 20)));

        assertThrows(RuntimeException.class, () -> service.explain(caseVariant));

        BusinessInsightReq duplicateVariant = request();
        Map<String, Object> ambiguousRow = new LinkedHashMap<>();
        ambiguousRow.put("category_name", "A");
        ambiguousRow.put("metric_value", 10);
        ambiguousRow.put("METRIC_VALUE", 20);
        duplicateVariant.setQueryResults(List.of(ambiguousRow));

        assertThrows(RuntimeException.class, () -> service.explain(duplicateVariant));

        BusinessInsightReq emptyRow = request();
        emptyRow.setQueryResults(
                List.of(Map.of("category_name", "A", "metric_value", 10), Map.of()));

        assertThrows(RuntimeException.class, () -> service.explain(emptyRow));
    }

    @Test
    void rejectsOversizedQuestionMetadataAndCellValues() {
        BusinessInsightReq oversizedQuestion = request();
        oversizedQuestion.setQueryText("12345");
        assertThrows(RuntimeException.class,
                () -> boundedService(4, 100, 100, 10_000).explain(oversizedQuestion));

        BusinessInsightReq oversizedMetadata = request();
        oversizedMetadata.getQueryColumns().get(0).setComment("12345");
        assertThrows(RuntimeException.class,
                () -> boundedService(100, 4, 100, 10_000).explain(oversizedMetadata));

        BusinessInsightReq oversizedCell = request();
        oversizedCell
                .setQueryResults(List.of(Map.of("category_name", "12345", "metric_value", 10)));
        assertThrows(RuntimeException.class,
                () -> boundedService(100, 100, 4, 10_000).explain(oversizedCell));
    }

    @Test
    void rejectsNestedAndCumulativelyOversizedCellValues() {
        BusinessInsightReq nestedCell = request();
        nestedCell.setQueryResults(
                List.of(Map.of("category_name", List.of("A"), "metric_value", 10)));
        assertThrows(RuntimeException.class,
                () -> boundedService(100, 100, 100, 10_000).explain(nestedCell));

        BusinessInsightReq oversizedTotal = request();
        oversizedTotal.setQueryResults(
                List.of(Map.of("category_name", "A".repeat(40), "metric_value", 10),
                        Map.of("category_name", "B".repeat(40), "metric_value", 20)));
        assertThrows(RuntimeException.class,
                () -> boundedService(100, 100, 100, 100).explain(oversizedTotal));
    }

    private BusinessInsightService boundedService(int maxQueryTextLength, int maxMetadataTextLength,
            int maxCellTextLength, int maxTotalInputCharacters) {
        BusinessInsightConfig config = new BusinessInsightConfig(3, 6, 2.0, 0.65, 0.82, 0.95,
                10_000, 100, maxQueryTextLength, maxMetadataTextLength, maxCellTextLength,
                maxTotalInputCharacters);
        return new BusinessInsightService(config);
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
