package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartRecommendation;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.persistence.repository.ChatQueryRepository;
import com.tencent.supersonic.common.pojo.DataFormat;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.ChatSnapshotExportData;
import com.tencent.supersonic.headless.api.pojo.response.DimSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatSnapshotExportResolverImplTest {

    private ChatQueryRepository chatQueryRepository;
    private SchemaService schemaService;
    private ModelService modelService;
    private ChatSnapshotExportResolverImpl resolver;

    @BeforeEach
    void setUp() {
        chatQueryRepository = mock(ChatQueryRepository.class);
        schemaService = mock(SchemaService.class);
        modelService = mock(ModelService.class);
        resolver = new ChatSnapshotExportResolverImpl(chatQueryRepository, schemaService,
                modelService);
    }

    @Test
    void rejectsMissingChatQuery() {
        when(chatQueryRepository.getChatQueryDO(99L)).thenReturn(null);

        assertThrows(InvalidArgumentException.class, () -> resolver.resolve(99L, user("alice")));
    }

    @Test
    void rejectsExportByAnyoneButTheAsker() {
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(storedQuery("alice"));

        assertThrows(InvalidPermissionException.class, () -> resolver.resolve(42L, user("bob")));
    }

    @Test
    void rejectsUnsuccessfulOrUnparseableResults() {
        ChatQueryDO failed = storedQuery("alice");
        failed.setQueryState(0);
        when(chatQueryRepository.getChatQueryDO(1L)).thenReturn(failed);
        ChatQueryDO broken = storedQuery("alice");
        broken.setQueryResult("{not-json");
        when(chatQueryRepository.getChatQueryDO(2L)).thenReturn(broken);
        ChatQueryDO emptyRows = storedQuery("alice");
        QueryResult emptyResult = successfulResult();
        emptyResult.setQueryResults(List.of());
        emptyRows.setQueryResult(JsonUtil.toString(emptyResult));
        when(chatQueryRepository.getChatQueryDO(3L)).thenReturn(emptyRows);

        assertThrows(InvalidArgumentException.class, () -> resolver.resolve(1L, user("alice")));
        assertThrows(InvalidArgumentException.class, () -> resolver.resolve(2L, user("alice")));
        assertThrows(InvalidArgumentException.class, () -> resolver.resolve(3L, user("alice")));
    }

    @Test
    void rejectsResultsWithoutSemanticContext() {
        ChatQueryDO stored = storedQuery("alice");
        QueryResult result = successfulResult();
        result.setChatContext(null);
        stored.setQueryResult(JsonUtil.toString(result));
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(stored);

        assertThrows(InvalidArgumentException.class, () -> resolver.resolve(42L, user("alice")));
    }

    @Test
    void rejectsWhenBackingModelIsNoLongerVisible() {
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(storedQuery("alice"));
        SemanticSchemaResp schema = new SemanticSchemaResp();
        schema.setModelIds(List.of(7L, 8L));
        when(schemaService.fetchSemanticSchema(any())).thenReturn(schema);
        ModelResp visible = new ModelResp();
        visible.setId(7L);
        when(modelService.getModelListWithAuth(any(), isNull(), eq(AuthType.VIEWER)))
                .thenReturn(List.of(visible));

        InvalidPermissionException failure = assertThrows(InvalidPermissionException.class,
                () -> resolver.resolve(42L, user("alice")));

        assertTrue(failure.getMessage().contains("访问权限"));
    }

    @Test
    void resolvesSnapshotWithConclusionAndChartType() {
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(storedQuery("alice"));
        SemanticSchemaResp schema = new SemanticSchemaResp();
        schema.setModelIds(List.of(7L));
        when(schemaService.fetchSemanticSchema(any())).thenReturn(schema);
        ModelResp visible = new ModelResp();
        visible.setId(7L);
        when(modelService.getModelListWithAuth(any(), isNull(), eq(AuthType.VIEWER)))
                .thenReturn(List.of(visible));

        ChatSnapshotExportData data = resolver.resolve(42L, user("alice"));

        assertEquals(42L, data.getQueryId());
        assertEquals("各机构存款余额是多少", data.getQuestion());
        assertEquals(5L, data.getDataSetId());
        assertEquals(1, data.getColumns().size());
        assertEquals("城东支行", data.getRows().get(0).get("account_name"));
        assertTrue(data.isMasked());
        assertEquals(Set.of("account_name"), data.getMaskedColumns());
        assertEquals("存款余额环比上升", data.getConclusion());
        assertEquals("bar", data.getChartType());
    }

    @Test
    void fallsBackToTextSummaryWhenExplanationIsMissing() {
        QueryResult result = successfulResult();
        result.setBusinessExplanation(null);
        result.setTextSummary("A市农商行2025年6月15日存款总额为42.02亿元。");
        ChatQueryDO stored = storedQuery("alice");
        stored.setQueryResult(JsonUtil.toString(result));
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(stored);
        stubVisibleSchema(new SemanticSchemaResp());

        ChatSnapshotExportData data = resolver.resolve(42L, user("alice"));

        assertEquals("A市农商行2025年6月15日存款总额为42.02亿元。", data.getConclusion());
    }

    @Test
    void mapsContractColumnsToChineseLabelsAndEnrichesMetricFormat() {
        QueryColumn orgCode = new QueryColumn("org_code", "STRING", "org_code");
        QueryColumn orgName = new QueryColumn("org_name", "STRING", "org_name");
        QueryColumn metricValue = new QueryColumn("metric_value", "NUMBER", "metric_value");
        QueryColumn rankPosition = new QueryColumn("rank_position", "NUMBER", "rank_position");
        QueryColumn custom = new QueryColumn("stat_month", "STRING", "stat_month");
        QueryResult result = successfulResult();
        result.setQueryColumns(List.of(orgCode, orgName, metricValue, rankPosition, custom));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("org_code", "A01");
        row.put("org_name", "城东支行");
        row.put("metric_value", 1234567);
        row.put("rank_position", 1);
        row.put("stat_month", "2025-06");
        result.setQueryResults(List.of(row));
        ChatQueryDO stored = storedQuery("alice");
        stored.setQueryResult(JsonUtil.toString(result));
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(stored);

        SemanticSchemaResp schema = new SemanticSchemaResp();
        DimSchemaResp dim = new DimSchemaResp();
        dim.setBizName("stat_month");
        dim.setName("统计月份");
        schema.setDimensions(List.of(dim));
        MetricSchemaResp metric = new MetricSchemaResp();
        metric.setBizName("metric_value");
        metric.setName("指标值（语义层）");
        metric.setDataFormatType("percent");
        DataFormat dataFormat = new DataFormat();
        dataFormat.setNeedMultiply100(true);
        dataFormat.setDecimalPlaces(2);
        metric.setDataFormat(dataFormat);
        schema.setMetrics(List.of(metric));
        stubVisibleSchema(schema);

        ChatSnapshotExportData data = resolver.resolve(42L, user("alice"));

        assertEquals(List.of("机构代码", "机构名称", "指标值", "排名", "统计月份"),
                data.getColumns().stream().map(QueryColumn::getName).toList());
        // contract label wins over the semantic-layer name for metric_value
        assertEquals("指标值", data.getColumns().get(2).getName());
        assertEquals("percent", data.getColumns().get(2).getDataFormatType());
        assertEquals(dataFormat, data.getColumns().get(2).getDataFormat());
        // rows stay keyed by bizName
        assertEquals(1234567, data.getRows().get(0).get("metric_value"));
        // date range inferred from the date-like column when dateInfo is absent
        assertEquals("2025-06", data.getDateRange());
    }

    @Test
    void keepsOriginalNameWhenNoLabelIsKnownAndReadsDateInfo() {
        QueryResult result = successfulResult();
        DateConf dateInfo = new DateConf();
        dateInfo.setStartDate("2025-06-01");
        dateInfo.setEndDate("2025-06-15");
        result.getChatContext().setDateInfo(dateInfo);
        ChatQueryDO stored = storedQuery("alice");
        stored.setQueryResult(JsonUtil.toString(result));
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(stored);
        stubVisibleSchema(new SemanticSchemaResp());

        ChatSnapshotExportData data = resolver.resolve(42L, user("alice"));

        assertEquals("Account", data.getColumns().get(0).getName());
        assertEquals("2025-06-01 至 2025-06-15", data.getDateRange());
    }

    @Test
    void omitsDateRangeWhenNothingIsAvailable() {
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(storedQuery("alice"));
        stubVisibleSchema(new SemanticSchemaResp());

        ChatSnapshotExportData data = resolver.resolve(42L, user("alice"));

        assertNull(data.getDateRange());
    }

    @Test
    void infersLineChartForTrendResultsWhenRecommendationIsMissing() {
        QueryResult result = successfulResult();
        result.setRecommendedChart(null);
        result.setQueryColumns(List.of(new QueryColumn("数据日期", "DATE", "data_date"),
                new QueryColumn("指标值", "NUMBER", "metric_value")));
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("data_date", "2025-02-28");
        first.put("metric_value", 100);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("data_date", "2025-03-31");
        second.put("metric_value", 120);
        result.setQueryResults(List.of(first, second));
        ChatQueryDO stored = storedQuery("alice");
        stored.setQueryResult(JsonUtil.toString(result));
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(stored);
        stubVisibleSchema(new SemanticSchemaResp());

        assertEquals("LINE", resolver.resolve(42L, user("alice")).getChartType());
    }

    @Test
    void infersBarChartForCategoryComparisonAndSkipsSingleRowResults() {
        QueryResult result = successfulResult();
        result.setRecommendedChart(null);
        result.setQueryColumns(List.of(new QueryColumn("机构名称", "VARCHAR", "org_name"),
                new QueryColumn("指标值", "NUMBER", "metric_value")));
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("org_name", "城东支行");
        first.put("metric_value", 100);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("org_name", "城西支行");
        second.put("metric_value", 90);
        result.setQueryResults(List.of(first, second));
        ChatQueryDO stored = storedQuery("alice");
        stored.setQueryResult(JsonUtil.toString(result));
        when(chatQueryRepository.getChatQueryDO(42L)).thenReturn(stored);
        stubVisibleSchema(new SemanticSchemaResp());

        assertEquals("BAR", resolver.resolve(42L, user("alice")).getChartType());

        // single-row results stay chartless
        result.setQueryResults(List.of(first));
        stored.setQueryResult(JsonUtil.toString(result));
        assertNull(resolver.resolve(42L, user("alice")).getChartType());
    }

    private void stubVisibleSchema(SemanticSchemaResp schema) {
        schema.setModelIds(List.of(7L));
        when(schemaService.fetchSemanticSchema(any())).thenReturn(schema);
        ModelResp visible = new ModelResp();
        visible.setId(7L);
        when(modelService.getModelListWithAuth(any(), isNull(), eq(AuthType.VIEWER)))
                .thenReturn(List.of(visible));
    }

    private ChatQueryDO storedQuery(String asker) {
        ChatQueryDO stored = new ChatQueryDO();
        stored.setQuestionId(42L);
        stored.setUserName(asker);
        stored.setQueryState(1);
        stored.setQueryText("各机构存款余额是多少");
        stored.setQueryResult(JsonUtil.toString(successfulResult()));
        return stored;
    }

    private QueryResult successfulResult() {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        SchemaElement dataSet = new SchemaElement();
        dataSet.setDataSetId(5L);
        parseInfo.setDataSet(dataSet);
        QueryColumn column = new QueryColumn("Account", "VARCHAR", "account_name");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("account_name", "城东支行");
        QueryResult result = new QueryResult();
        result.setQueryColumns(List.of(column));
        result.setQueryResults(List.of(row));
        result.setChatContext(parseInfo);
        result.setDataMasked(true);
        result.setMaskedColumns(Set.of("account_name"));
        result.setBusinessExplanation(
                BusinessExplanation.builder().summary("存款余额环比上升").build());
        result.setRecommendedChart(ChartRecommendation.builder().chartType("bar").build());
        return result;
    }

    private User user(String name) {
        return User.get(1L, name);
    }
}
