package com.tencent.supersonic.headless.server.utils;

import com.tencent.supersonic.common.pojo.Aggregator;
import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AggOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.TaskStatusEnum;
import com.tencent.supersonic.common.util.SqlFilterUtils;
import com.tencent.supersonic.common.util.TraceIdUtil;
import com.tencent.supersonic.headless.api.pojo.QueryStat;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.server.persistence.repository.StatRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class StatUtilsSecurityTest {

    private static final String TRACE_ID = "supersonic_stat_security_test";
    private static final String ACCOUNT_NUMBER = "6222000012345678";

    private StatRepository statRepository;
    private StatUtils statUtils;

    @BeforeEach
    void setUp() {
        statRepository = mock(StatRepository.class);
        statUtils = new StatUtils(statRepository, new SqlFilterUtils());
        TraceIdUtil.setTraceId(TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        StatUtils.remove();
        TraceIdUtil.remove();
    }

    @Test
    void shouldUseCurrentTraceAndKeepOnlySha256DigestsForSqlRequest() {
        QuerySqlReq request = new QuerySqlReq();
        request.setSql("SELECT SUM(revenue) FROM ds_1 WHERE account_no = '" + ACCOUNT_NUMBER + "'");
        request.setDataSetId(1L);

        statUtils.initSqlStatInfo(request, User.get(7L, "alice"));

        QueryStat stat = StatUtils.get();
        assertEquals(TRACE_ID, stat.getTraceId());
        assertNull(stat.getQuerySqlCmd());
        assertNull(stat.getSql());
        assertEquals(64, stat.getQuerySqlCmdMd5().length());
        assertEquals(64, stat.getSqlMd5().length());
        assertFalse(stat.getQuerySqlCmdMd5().contains(ACCOUNT_NUMBER));
        assertFalse(stat.getSqlMd5().contains(ACCOUNT_NUMBER));
    }

    @Test
    void shouldPersistOnlyFilterColumnNamesForStructuredRequest() {
        QueryStructReq request = new QueryStructReq();
        request.setDataSetId(1L);
        request.setGroups(List.of("branch_code"));
        request.setAggregators(List.of(new Aggregator("revenue", AggOperatorEnum.SUM)));
        request.setDimensionFilters(
                List.of(new Filter("account_no", FilterOperatorEnum.EQUALS, ACCOUNT_NUMBER)));

        statUtils.initStructStatInfo(request, User.get(7L, "alice"));

        QueryStat stat = StatUtils.get();
        assertEquals(TRACE_ID, stat.getTraceId());
        assertNull(stat.getQueryStructCmd());
        assertEquals(64, stat.getQueryStructCmdMd5().length());
        assertEquals("[\"account_no\"]", stat.getFilterCols());
        assertFalse(stat.getFilterCols().contains(ACCOUNT_NUMBER));
    }

    @Test
    void shouldRemoveSensitivePayloadAgainAtPersistenceBoundary() throws Exception {
        QuerySqlReq request = new QuerySqlReq();
        request.setSql("SELECT revenue FROM ds_1");
        statUtils.initSqlStatInfo(request, User.get(7L, "alice"));
        StatUtils.get().setQuerySqlCmd(ACCOUNT_NUMBER).setQueryStructCmd(ACCOUNT_NUMBER)
                .setSql(ACCOUNT_NUMBER);
        CountDownLatch persisted = new CountDownLatch(1);
        AtomicReference<QueryStat> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            persisted.countDown();
            return true;
        }).when(statRepository).createRecord(any(QueryStat.class));

        statUtils.statInfo2DbAsync(TaskStatusEnum.SUCCESS);

        assertTrue(persisted.await(3, TimeUnit.SECONDS));
        assertNull(captured.get().getQuerySqlCmd());
        assertNull(captured.get().getQueryStructCmd());
        assertNull(captured.get().getSql());
    }
}
