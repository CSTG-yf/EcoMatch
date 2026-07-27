package com.tencent.supersonic.headless.server.utils;

import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.DateModeUtils;
import com.tencent.supersonic.common.util.SqlFilterUtils;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class QueryStructReqSecurityTest {

    @Test
    void invalidWhereExpressionFailsClosed() {
        SqlFilterUtils filterUtils = mock(SqlFilterUtils.class);
        when(filterUtils.getWhereClause(anyList(), anyBoolean()))
                .thenReturn("customer_id = 'unterminated");

        InvalidArgumentException error = assertThrows(InvalidArgumentException.class,
                () -> convertWithServices(requestWithDimensionFilter(), filterUtils));

        assertEquals("Structured query contains an invalid filter expression", error.getMessage());
    }

    @Test
    void invalidHavingExpressionFailsClosedInsteadOfDroppingFilter() {
        SqlFilterUtils filterUtils = mock(SqlFilterUtils.class);
        when(filterUtils.getWhereClause(anyList(), anyBoolean()))
                .thenReturn("SUM(amount) > 'unterminated");

        InvalidArgumentException error = assertThrows(InvalidArgumentException.class,
                () -> convertWithServices(requestWithMetricFilter(), filterUtils));

        assertEquals("Structured query contains an invalid metric filter expression",
                error.getMessage());
    }

    private QueryStructReq requestWithDimensionFilter() {
        QueryStructReq request = new QueryStructReq();
        request.setDataSetId(1L);
        request.setGroups(List.of("customer_id"));
        request.setDimensionFilters(List.of(new Filter()));
        return request;
    }

    private QueryStructReq requestWithMetricFilter() {
        QueryStructReq request = new QueryStructReq();
        request.setDataSetId(1L);
        request.setGroups(List.of("customer_id"));
        request.setMetricFilters(List.of(new Filter()));
        return request;
    }

    private void convertWithServices(QueryStructReq request, SqlFilterUtils filterUtils) {
        try (MockedStatic<ContextUtils> context = mockStatic(ContextUtils.class)) {
            context.when(() -> ContextUtils.getBean(SqlFilterUtils.class)).thenReturn(filterUtils);
            context.when(() -> ContextUtils.getBean(DateModeUtils.class))
                    .thenReturn(new DateModeUtils());
            request.convert();
        }
    }
}
