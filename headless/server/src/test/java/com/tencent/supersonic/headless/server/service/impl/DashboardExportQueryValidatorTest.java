package com.tencent.supersonic.headless.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.Aggregator;
import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.enums.AggOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.server.service.DataSetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardExportQueryValidatorTest {

    private final DataSetService dataSetService = mock(DataSetService.class);
    private final DashboardExportQueryValidator validator =
            new DashboardExportQueryValidator(dataSetService, new ObjectMapper());
    private DashboardResp dashboard;
    private QueryStructReq query;

    @BeforeEach
    void setUp() {
        dashboard = new DashboardResp();
        dashboard.setId(7L);
        dashboard.setDomainId(10L);
        dashboard.setConfig("""
                {
                  "globalFilters":[{"field":"机构","operator":"=","value":"B市"}],
                  "components":[{"query":{
                    "dataSetId":3,"modelId":5,
                    "dimensions":[{"bizName":"机构"}],
                    "metrics":[{"bizName":"存款余额","defaultAgg":"SUM"}],
                    "dimensionFilters":[],"dateInfo":null
                  }}]
                }
                """);
        query = new QueryStructReq();
        query.setDataSetId(3L);
        query.setModelIds(Set.of(5L));
        query.setGroups(List.of("机构"));
        query.setAggregators(List.of(new Aggregator("存款余额", AggOperatorEnum.SUM)));
        query.setDimensionFilters(List.of(new Filter("机构", FilterOperatorEnum.EQUALS, "B市")));
        DataSetResp dataSet = new DataSetResp();
        dataSet.setDomainId(10L);
        when(dataSetService.getDataSet(3L)).thenReturn(dataSet);
    }

    @Test
    void acceptsThePersistedDashboardQuery() {
        assertDoesNotThrow(() -> validator.validate(dashboard, List.of(query)));
    }

    @Test
    void rejectsCrossDomainOrReplacedQueries() {
        query.setGroups(List.of("客户号"));
        assertThrows(InvalidArgumentException.class,
                () -> validator.validate(dashboard, List.of(query)));

        query.setGroups(List.of("机构"));
        query.setDataSetId(9L);
        assertThrows(InvalidArgumentException.class,
                () -> validator.validate(dashboard, List.of(query)));
    }

    @Test
    void rejectsRemovedDashboardFilters() {
        query.setDimensionFilters(List.of());
        assertThrows(InvalidArgumentException.class,
                () -> validator.validate(dashboard, List.of(query)));
    }
}
