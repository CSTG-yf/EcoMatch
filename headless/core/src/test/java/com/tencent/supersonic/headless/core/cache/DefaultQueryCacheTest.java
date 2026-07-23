package com.tencent.supersonic.headless.core.cache;

import com.tencent.supersonic.common.pojo.Aggregator;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AggOperatorEnum;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryCacheTest {

    private final DefaultQueryCache queryCache = new DefaultQueryCache();

    @Test
    void identifiesStructuredMetricQuery() {
        QueryStructReq request = new QueryStructReq();
        request.setAggregators(List.of(new Aggregator("loan_balance", AggOperatorEnum.SUM)));

        assertTrue(queryCache.isHotMetricQuery(request));
    }

    @Test
    void identifiesAggregateSqlButNotDetailSql() {
        QuerySqlReq aggregate = QuerySqlReq.builder()
                .sql("SELECT branch, SUM(balance) FROM account GROUP BY branch").build();
        QuerySqlReq detail =
                QuerySqlReq.builder().sql("SELECT branch, balance FROM account").build();

        assertTrue(queryCache.isHotMetricQuery(aggregate));
        assertFalse(queryCache.isHotMetricQuery(detail));
    }

    @Test
    void hashesUserSecurityScopeWithoutLosingIsolation() {
        User analyst = User.get(2L, "analyst");
        analyst.setAttributes(java.util.Map.of("organization", "branch-a"));
        User auditor = User.get(3L, "auditor");

        String analystScope = queryCache.securityScope(analyst);
        String auditorScope = queryCache.securityScope(auditor);

        assertEquals(64, analystScope.length());
        assertFalse(analystScope.contains("analyst"));
        assertFalse(analystScope.contains("branch-a"));
        assertNotEquals(analystScope, auditorScope);
    }
}
