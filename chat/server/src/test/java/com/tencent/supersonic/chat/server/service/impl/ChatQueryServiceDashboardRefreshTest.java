package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.ChatQueryDataReq;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.DatePeriodEnum;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.request.QueryFilter;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.chat.query.SemanticQuery;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatQueryServiceDashboardRefreshTest {

    @Test
    void reusesPhysicalSqlWhenTheCompleteRefreshRequestIsUnchanged() {
        SemanticParseInfo parseInfo = parseInfo();
        ChatQueryDataReq request = matchingRequest();

        assertTrue(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo, request));
    }

    @Test
    void marksOnlyPersistedUncorrectedDashboardPhysicalSqlAsTrusted() throws Exception {
        SemanticLayerService semanticLayer = mock(SemanticLayerService.class);
        ChatQueryServiceImpl service = new ChatQueryServiceImpl();
        ReflectionTestUtils.setField(service, "semanticLayerService", semanticLayer);
        SemanticQuery semanticQuery = mock(SemanticQuery.class);
        SemanticParseInfo parseInfo = parseInfo();
        parseInfo.setQueryMode("LLM_S2SQL");
        QuerySqlReq request = new QuerySqlReq();
        request.setSqlInfo(parseInfo.getSqlInfo());
        when(semanticQuery.buildSemanticQueryReq()).thenReturn(request);
        when(semanticQuery.getParseInfo()).thenReturn(parseInfo);
        SemanticQueryResp response = new SemanticQueryResp();
        response.setSql(parseInfo.getSqlInfo().getQuerySQL());
        when(semanticLayer.queryByReq(eq(request), eq(User.get(1L, "tester"))))
                .thenReturn(response);

        ReflectionTestUtils.invokeMethod(service, "executeQuery", semanticQuery,
                User.get(1L, "tester"), true);

        ArgumentCaptor<SemanticQueryReq> requestCaptor =
                ArgumentCaptor.forClass(SemanticQueryReq.class);
        verify(semanticLayer).queryByReq(requestCaptor.capture(), eq(User.get(1L, "tester")));
        assertTrue(requestCaptor.getValue().isTrustedCompiledSql());

        request.setTrustedCompiledSql(false);
        parseInfo.getSqlInfo().setCorrectedQuerySQL("SELECT repaired FROM account");
        ReflectionTestUtils.invokeMethod(service, "executeQuery", semanticQuery,
                User.get(1L, "tester"), true);
        assertFalse(request.isTrustedCompiledSql());
    }

    @Test
    void reusesPhysicalSqlWhenDashboardOmitsNonRangeDateMetadata() {
        SemanticParseInfo parseInfo = parseInfo();
        parseInfo.getDateInfo().setDetectWord("January transactions");
        parseInfo.getDateInfo().setInherited(true);
        parseInfo.getDateInfo().setGroupByDate(true);
        parseInfo.getDateInfo().setDateField("trade_date");
        ChatQueryDataReq request = matchingRequest();
        request.getDateInfo().setDetectWord(null);
        request.getDateInfo().setInherited(false);
        request.getDateInfo().setGroupByDate(false);
        request.getDateInfo().setDateField(null);

        assertTrue(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo, request));
    }

    @Test
    void doesNotReusePhysicalSqlWhenDateIsAddedModifiedOrRemoved() {
        SemanticParseInfo parseInfo = parseInfo();
        ChatQueryDataReq modified = matchingRequest();
        modified.getDateInfo().setEndDate("2026-02-01");
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo, modified));

        ChatQueryDataReq removed = matchingRequest();
        removed.setDateInfo(null);
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo, removed));

        SemanticParseInfo withoutDate = parseInfo();
        withoutDate.setDateInfo(null);
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(withoutDate, matchingRequest()));
    }

    @Test
    void reusesOnlyStaticDateModes() {
        SemanticParseInfo recentParse = parseInfo();
        ChatQueryDataReq recentRequest = matchingRequest();
        recentParse.getDateInfo().setDateMode(DateConf.DateMode.RECENT);
        recentRequest.getDateInfo().setDateMode(DateConf.DateMode.RECENT);
        recentParse.getDateInfo().setUnit(7);
        recentRequest.getDateInfo().setUnit(7);
        recentParse.getDateInfo().setPeriod(DatePeriodEnum.DAY);
        recentRequest.getDateInfo().setPeriod(DatePeriodEnum.DAY);
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(recentParse, recentRequest));

        SemanticParseInfo availableParse = parseInfo();
        ChatQueryDataReq availableRequest = matchingRequest();
        availableParse.getDateInfo().setDateMode(DateConf.DateMode.AVAILABLE);
        availableRequest.getDateInfo().setDateMode(DateConf.DateMode.AVAILABLE);
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(availableParse, availableRequest));

        SemanticParseInfo listParse = parseInfo();
        ChatQueryDataReq listRequest = matchingRequest();
        listParse.getDateInfo().setDateMode(DateConf.DateMode.LIST);
        listRequest.getDateInfo().setDateMode(DateConf.DateMode.LIST);
        listParse.getDateInfo().setDateList(java.util.List.of("2026-01-01", "2026-01-15"));
        listRequest.getDateInfo().setDateList(java.util.List.of("2026-01-01", "2026-01-15"));
        assertTrue(ChatQueryServiceImpl.canReusePhysicalSql(listParse, listRequest));
        listRequest.getDateInfo().setDateList(java.util.List.of("2026-01-01"));
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(listParse, listRequest));

        SemanticParseInfo allParse = parseInfo();
        ChatQueryDataReq allRequest = matchingRequest();
        allParse.getDateInfo().setDateMode(DateConf.DateMode.ALL);
        allRequest.getDateInfo().setDateMode(DateConf.DateMode.ALL);
        allRequest.getDateInfo().setStartDate("1999-01-01");
        allRequest.getDateInfo().setUnit(99);
        assertTrue(ChatQueryServiceImpl.canReusePhysicalSql(allParse, allRequest));
    }

    @Test
    void doesNotReusePhysicalSqlWhenMetricsOrDimensionsChange() {
        ChatQueryDataReq metricAdded = matchingRequest();
        metricAdded.setMetrics(new HashSet<>(metricAdded.getMetrics()));
        metricAdded.getMetrics().add(element(13L, "profit", SchemaElementType.METRIC, null));
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), metricAdded));

        ChatQueryDataReq metricModified = matchingRequest();
        metricModified.setMetrics(Set.of(element(11L, "profit", SchemaElementType.METRIC, null)));
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), metricModified));

        ChatQueryDataReq metricRemoved = matchingRequest();
        metricRemoved.setMetrics(Set.of());
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), metricRemoved));

        ChatQueryDataReq dimensionAdded = matchingRequest();
        dimensionAdded.setDimensions(new HashSet<>(dimensionAdded.getDimensions()));
        dimensionAdded.getDimensions()
                .add(element(14L, "account_type", SchemaElementType.DIMENSION, null));
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), dimensionAdded));

        ChatQueryDataReq dimensionModified = matchingRequest();
        dimensionModified.setDimensions(
                Set.of(element(12L, "account_type", SchemaElementType.DIMENSION, null)));
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), dimensionModified));

        ChatQueryDataReq dimensionRemoved = matchingRequest();
        dimensionRemoved.setDimensions(Set.of());
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), dimensionRemoved));
    }

    @Test
    void doesNotReusePhysicalSqlWhenExecutionRelevantSchemaFieldsChange() {
        SemanticParseInfo parseInfo = parseInfo();
        ChatQueryDataReq defaultAggChanged = matchingRequest();
        defaultAggChanged.getMetrics().iterator().next().setDefaultAgg("MAX");
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo, defaultAggChanged));

        ChatQueryDataReq modelChanged = matchingRequest();
        modelChanged.getMetrics().iterator().next().setModel(99L);
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo, modelChanged));
    }

    @Test
    void rejectsUnsupportedSchemaChanges() {
        ChatQueryServiceImpl service = new ChatQueryServiceImpl();

        ChatQueryDataReq dateRemoved = matchingRequest();
        dateRemoved.setDateInfo(null);
        assertThrows(InvalidArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "validateSupportedDashboardRefresh", parseInfo(), dateRemoved));

        ChatQueryDataReq dimensionChanged = matchingRequest();
        dimensionChanged.setDimensions(Set.of(
                element(14L, "account_type", SchemaElementType.DIMENSION, null)));
        assertThrows(InvalidArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "validateSupportedDashboardRefresh", parseInfo(), dimensionChanged));

        ChatQueryDataReq metricAdded = matchingRequest();
        metricAdded.setMetrics(new HashSet<>(metricAdded.getMetrics()));
        metricAdded.getMetrics().add(element(13L, "profit", SchemaElementType.METRIC, null));
        assertThrows(InvalidArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "validateSupportedDashboardRefresh", parseInfo(), metricAdded));

        ChatQueryDataReq metricRemoved = matchingRequest();
        metricRemoved.setMetrics(Set.of());
        assertThrows(InvalidArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "validateSupportedDashboardRefresh", parseInfo(), metricRemoved));

        ChatQueryDataReq defaultAggChanged = matchingRequest();
        defaultAggChanged.getMetrics().iterator().next().setDefaultAgg("MAX");
        assertThrows(InvalidArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "validateSupportedDashboardRefresh", parseInfo(), defaultAggChanged));
    }

    @Test
    void doesNotReusePhysicalSqlWhenDimensionFiltersChange() {
        ChatQueryDataReq added = matchingRequest();
        added.setDimensionFilters(new HashSet<>(added.getDimensionFilters()));
        added.getDimensionFilters().add(filter("region", "east"));
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), added));

        ChatQueryDataReq modified = matchingRequest();
        modified.setDimensionFilters(Set.of(filter("branch", "002")));
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), modified));

        ChatQueryDataReq removed = matchingRequest();
        removed.setDimensionFilters(Set.of());
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), removed));
    }

    @Test
    void doesNotReusePhysicalSqlWhenMetricFiltersChange() {
        ChatQueryDataReq added = matchingRequest();
        added.setMetricFilters(new HashSet<>(added.getMetricFilters()));
        added.getMetricFilters().add(filter("profit", "10"));
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), added));

        ChatQueryDataReq modified = matchingRequest();
        modified.setMetricFilters(Set.of(filter("revenue", "200")));
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), modified));

        ChatQueryDataReq removed = matchingRequest();
        removed.setMetricFilters(Set.of());
        assertFalse(ChatQueryServiceImpl.canReusePhysicalSql(parseInfo(), removed));
    }

    @Test
    void removesDeletedDimensionFilterWithoutRemovingOtherWhereConditions() {
        SemanticParseInfo parseInfo = parseInfo();
        parseInfo.getSqlInfo().setCorrectedS2SQL("SELECT revenue FROM account "
                + "WHERE branch = '001' AND trade_date >= '2026-01-01' AND status = 'active'");
        parseInfo.setMetricFilters(Set.of());
        ChatQueryDataReq request = matchingRequest();
        request.setDateInfo(null);
        request.setDimensionFilters(Set.of());
        request.setMetricFilters(Set.of());

        String rebuilt = ReflectionTestUtils.invokeMethod(new ChatQueryServiceImpl(),
                "replaceFilters", request, parseInfo, new DataSetSchema());

        assertFalse(rebuilt.contains("branch"));
        assertTrue(rebuilt.contains("trade_date >= '2026-01-01'"));
        assertTrue(rebuilt.contains("status = 'active'"));
    }

    @Test
    void appendsNewDimensionFilterToFinalSql() {
        SemanticParseInfo parseInfo = parseInfo();
        parseInfo.getSqlInfo().setCorrectedS2SQL(
                "SELECT revenue FROM account WHERE branch = '001'");
        parseInfo.setMetricFilters(Set.of());
        ChatQueryDataReq request = matchingRequest();
        request.setDateInfo(null);
        request.setMetricFilters(Set.of());
        request.setDimensionFilters(Set.of(filter("branch", "001"), filter("region", "east")));

        String rebuilt = ReflectionTestUtils.invokeMethod(new ChatQueryServiceImpl(),
                "replaceFilters", request, parseInfo, new DataSetSchema());

        assertTrue(rebuilt.contains("branch ="), rebuilt);
        assertTrue(rebuilt.contains("region = 'east'"), rebuilt);
    }

    @Test
    void refreshesRecentDayWeekAndMonthRangesInFinalSql() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        assertRecentDateRange(DatePeriodEnum.DAY, 1, yesterday.toString(), yesterday.toString());
        assertRecentDateRange(DatePeriodEnum.WEEK, 2, yesterday.minusDays(14).toString(),
                yesterday.toString());
        assertRecentDateRange(DatePeriodEnum.MONTH, 3,
                yesterday.minusMonths(3).format(DateTimeFormatter.ofPattern("yyyy-MM")),
                yesterday.format(DateTimeFormatter.ofPattern("yyyy-MM")));
    }

    @Test
    void rejectsAvailableDateRefreshInsteadOfUsingStaleRange() {
        ChatQueryDataReq request = matchingRequest();
        request.getDateInfo().setDateMode(DateConf.DateMode.AVAILABLE);

        assertThrows(InvalidArgumentException.class, () -> ReflectionTestUtils.invokeMethod(
                new ChatQueryServiceImpl(), "validateSupportedDashboardRefresh", parseInfo(),
                request));
    }

    private void assertRecentDateRange(DatePeriodEnum period, int unit, String expectedStart,
            String expectedEnd) {
        SemanticParseInfo parseInfo = parseInfo();
        parseInfo.getSqlInfo().setCorrectedS2SQL("SELECT revenue FROM account "
                + "WHERE trade_date >= '2000-01-01' AND trade_date <= '2000-01-31'");
        parseInfo.setDimensionFilters(Set.of());
        parseInfo.setMetricFilters(Set.of());
        ChatQueryDataReq request = matchingRequest();
        request.setDimensionFilters(Set.of());
        request.setMetricFilters(Set.of());
        request.getDateInfo().setDateMode(DateConf.DateMode.RECENT);
        request.getDateInfo().setPeriod(period);
        request.getDateInfo().setUnit(unit);
        DataSetSchema schema = mock(DataSetSchema.class);
        when(schema.getPartitionDimension()).thenReturn(
                element(15L, "trade_date", SchemaElementType.DIMENSION, 7L));

        String rebuilt = ReflectionTestUtils.invokeMethod(new ChatQueryServiceImpl(),
                "replaceFilters", request, parseInfo, schema);

        assertEquals(expectedStart, request.getDateInfo().getStartDate());
        assertEquals(expectedEnd, request.getDateInfo().getEndDate());
        assertEquals(request.getDateInfo(), parseInfo.getDateInfo());
        assertTrue(rebuilt.contains("trade_date >= '" + expectedStart + "'"), rebuilt);
        assertTrue(rebuilt.contains("trade_date <= '" + expectedEnd + "'"), rebuilt);
        assertFalse(rebuilt.contains("2000-01-01"), rebuilt);
        assertFalse(rebuilt.contains("2000-01-31"), rebuilt);
    }

    private SemanticParseInfo parseInfo() {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getSqlInfo().setQuerySQL("WITH ranked AS (SELECT 1) SELECT * FROM ranked");
        parseInfo.getMetrics().add(element(11L, "revenue", SchemaElementType.METRIC, 7L));
        parseInfo.getDimensions().add(element(12L, "branch", SchemaElementType.DIMENSION, 7L));
        parseInfo.setDimensionFilters(Set.of(filter("branch", "001")));
        parseInfo.setMetricFilters(Set.of(filter("revenue", "100")));
        parseInfo.setDateInfo(date());
        return parseInfo;
    }

    private ChatQueryDataReq matchingRequest() {
        ChatQueryDataReq request = new ChatQueryDataReq();
        // Dashboard JSON may omit dataSetId for otherwise identical schema elements.
        request.setMetrics(Set.of(element(11L, "revenue", SchemaElementType.METRIC, null)));
        request.setDimensions(Set.of(element(12L, "branch", SchemaElementType.DIMENSION, null)));
        request.setDimensionFilters(Set.of(filter("branch", "001")));
        request.setMetricFilters(Set.of(filter("revenue", "100")));
        request.setDateInfo(date());
        return request;
    }

    private SchemaElement element(Long id, String name, SchemaElementType type, Long dataSetId) {
        return SchemaElement.builder().id(id).name(name).bizName(name).type(type)
                .dataSetId(dataSetId).build();
    }

    private QueryFilter filter(String name, String value) {
        QueryFilter filter = new QueryFilter();
        filter.setName(name);
        filter.setBizName(name);
        filter.setOperator(FilterOperatorEnum.EQUALS);
        filter.setValue(value);
        return filter;
    }

    private DateConf date() {
        DateConf date = new DateConf();
        date.setDateMode(DateConf.DateMode.BETWEEN);
        date.setStartDate("2026-01-01");
        date.setEndDate("2026-01-31");
        date.setDateField("trade_date");
        return date;
    }
}
