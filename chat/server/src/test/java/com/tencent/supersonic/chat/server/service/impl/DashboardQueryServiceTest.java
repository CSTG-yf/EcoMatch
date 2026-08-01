package com.tencent.supersonic.chat.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.chat.api.pojo.request.ChatQueryDataReq;
import com.tencent.supersonic.chat.api.pojo.request.DashboardQueryDataReq;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.server.service.DashboardService;
import com.tencent.supersonic.headless.server.service.DataSetService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardQueryServiceTest {

    private final DashboardService dashboardService = mock(DashboardService.class);
    private final ChatQueryServiceImpl chatQueryService = mock(ChatQueryServiceImpl.class);
    private final ChatManageService chatManageService = mock(ChatManageService.class);
    private final DataSetService dataSetService = mock(DataSetService.class);
    private final DashboardQueryService service = new DashboardQueryService(dashboardService,
            chatQueryService, chatManageService, dataSetService, new ObjectMapper());
    private final User viewer = User.get(3L, "viewer");

    @Test
    void resolvesTheStoredComponentAfterDashboardReadAuthorization() throws Exception {
        DashboardResp dashboard = new DashboardResp();
        dashboard.setId(7L);
        dashboard.setDomainId(10L);
        dashboard.setConfig("""
                {
                  "globalFilters": [
                    {"field": "机构", "operator": "=", "value": "B市"}
                  ],
                  "components": [
                    {
                      "id": "component-1",
                      "query": {
                        "queryId": 20,
                        "parseId": 1,
                        "dimensions": [],
                        "metrics": [],
                        "dimensionFilters": []
                      }
                    }
                  ]
                }
                """);
        when(dashboardService.get(7L, viewer)).thenReturn(dashboard);
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setDataSet(com.tencent.supersonic.headless.api.pojo.SchemaElement.builder()
                .dataSetId(3L).build());
        when(chatManageService.getParseInfo(20L, 1)).thenReturn(parseInfo);
        DataSetResp dataSet = new DataSetResp();
        dataSet.setDomainId(10L);
        when(dataSetService.getDataSet(3L)).thenReturn(dataSet);
        when(chatQueryService.queryDataForDashboard(
                org.mockito.ArgumentMatchers.any(ChatQueryDataReq.class), eq(viewer)))
                        .thenReturn("result");

        DashboardQueryDataReq request = new DashboardQueryDataReq();
        request.setDashboardId(7L);
        request.setComponentId("component-1");

        assertEquals("result", service.queryData(request, viewer));

        ArgumentCaptor<ChatQueryDataReq> queryCaptor =
                ArgumentCaptor.forClass(ChatQueryDataReq.class);
        verify(dashboardService).get(7L, viewer);
        verify(chatQueryService).queryDataForDashboard(queryCaptor.capture(), eq(viewer));
        ChatQueryDataReq query = queryCaptor.getValue();
        assertEquals(20L, query.getQueryId());
        assertEquals(1, query.getParseId());
        assertEquals(1, query.getDimensionFilters().size());
        assertEquals("机构", query.getDimensionFilters().iterator().next().getBizName());
        assertEquals(FilterOperatorEnum.EQUALS,
                query.getDimensionFilters().iterator().next().getOperator());
    }

    @Test
    void rejectsAComponentThatIsNotStoredInTheAuthorizedDashboard() {
        DashboardResp dashboard = new DashboardResp();
        dashboard.setId(7L);
        dashboard.setConfig("{\"globalFilters\":[],\"components\":[]}");
        when(dashboardService.get(7L, viewer)).thenReturn(dashboard);
        DashboardQueryDataReq request = new DashboardQueryDataReq();
        request.setDashboardId(7L);
        request.setComponentId("missing");

        assertThrows(InvalidArgumentException.class, () -> service.queryData(request, viewer));
    }

    @Test
    void rejectsAStoredQueryFromAnotherDomain() {
        DashboardResp dashboard = new DashboardResp();
        dashboard.setId(7L);
        dashboard.setDomainId(10L);
        dashboard.setConfig("{\"globalFilters\":[],\"components\":[{\"id\":\"component-1\","
                + "\"query\":{\"queryId\":20,\"parseId\":1}}]}");
        when(dashboardService.get(7L, viewer)).thenReturn(dashboard);
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setDataSet(com.tencent.supersonic.headless.api.pojo.SchemaElement.builder()
                .dataSetId(3L).build());
        when(chatManageService.getParseInfo(20L, 1)).thenReturn(parseInfo);
        DataSetResp dataSet = new DataSetResp();
        dataSet.setDomainId(11L);
        when(dataSetService.getDataSet(3L)).thenReturn(dataSet);
        DashboardQueryDataReq request = new DashboardQueryDataReq();
        request.setDashboardId(7L);
        request.setComponentId("component-1");

        assertThrows(InvalidArgumentException.class, () -> service.queryData(request, viewer));
    }

    @Test
    void batchesSharedComponentsWithoutFailingTheWholeDashboard() throws Exception {
        DashboardResp dashboard = new DashboardResp();
        dashboard.setId(7L);
        dashboard.setDomainId(10L);
        dashboard.setConfig("""
                {
                  "globalFilters": [],
                  "components": [
                    {"id":"ok","query":{"queryId":20,"parseId":1}},
                    {"id":"invalid","query":{"queryId":21}}
                  ]
                }
                """);
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setDataSet(com.tencent.supersonic.headless.api.pojo.SchemaElement.builder()
                .dataSetId(3L).build());
        when(chatManageService.getParseInfo(20L, 1)).thenReturn(parseInfo);
        DataSetResp dataSet = new DataSetResp();
        dataSet.setDomainId(10L);
        when(dataSetService.getDataSet(3L)).thenReturn(dataSet);
        when(chatQueryService.queryDataForDashboard(
                org.mockito.ArgumentMatchers.any(ChatQueryDataReq.class), eq(viewer)))
                        .thenReturn("result");

        DashboardQueryService.BatchQueryResult batch = service.queryAll(dashboard, viewer);

        assertEquals("result", batch.data().get("ok"));
        assertEquals("QUERY_FAILED", batch.errors().get("invalid"));
    }
}
