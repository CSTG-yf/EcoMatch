package com.tencent.supersonic.headless.core.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tencent.supersonic.common.pojo.Aggregator;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AggOperatorEnum;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

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

    @Test
    void separatesAuthorizationAndNativeExecutionModes() {
        QuerySqlReq authorized = QuerySqlReq.builder().sql("SELECT balance FROM account").build();
        QuerySqlReq authorizationBypassed =
                QuerySqlReq.builder().sql("SELECT balance FROM account").build();
        authorizationBypassed.setNeedAuth(false);
        QuerySqlReq nativeLayer = QuerySqlReq.builder().sql("SELECT balance FROM account").build();
        nativeLayer.setInnerLayerNative(true);

        assertNotEquals(queryCache.commandScope(authorized),
                queryCache.commandScope(authorizationBypassed));
        assertNotEquals(queryCache.commandScope(authorized), queryCache.commandScope(nativeLayer));
    }

    @Test
    void snapshotsRowsAndMaskingMetadataForCacheIsolation() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("balance", "****");
        SemanticQueryResp source = new SemanticQueryResp();
        source.setResultList(List.of(row));
        source.setDataMasked(true);
        source.setMaskedColumns(Set.of("balance"));

        SemanticQueryResp cached = (SemanticQueryResp) queryCache.defensiveCopy(source);
        row.put("balance", "changed-after-cache-write");

        assertEquals("****", cached.getResultList().get(0).get("balance"));
        assertTrue(cached.isDataMasked());
        assertEquals(Set.of("balance"), cached.getMaskedColumns());

        SemanticQueryResp response = (SemanticQueryResp) queryCache.defensiveCopy(cached);
        response.getResultList().get(0).put("balance", "changed-after-cache-read");

        assertEquals("****", cached.getResultList().get(0).get("balance"));
    }

    @Test
    void asynchronousCacheFailureDoesNotLogSensitiveExceptionOrStackTrace() throws Exception {
        String secret = "SELECT account_no FROM customer_secret_201";
        CacheManager cacheManager = mock(CacheManager.class);
        CacheCommonConfig cacheConfig = new CacheCommonConfig();
        cacheConfig.setCacheEnable(true);
        when(cacheManager.put(anyString(), any()))
                .thenThrow(new IllegalStateException("cache failure contains " + secret));

        CountDownLatch logged = new CountDownLatch(1);
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultQueryCache.class);
        logger.setLevel(Level.WARN);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                super.append(event);
                logged.countDown();
            }
        };
        appender.start();
        logger.addAppender(appender);
        try (MockedStatic<ContextUtils> context = mockStatic(ContextUtils.class)) {
            context.when(() -> ContextUtils.getBean(CacheManager.class)).thenReturn(cacheManager);
            context.when(() -> ContextUtils.getBean(CacheCommonConfig.class))
                    .thenReturn(cacheConfig);

            queryCache.put(new QuerySqlReq(), "cache-key", new SemanticQueryResp());

            assertTrue(logged.await(3, TimeUnit.SECONDS));
            String message = appender.list.get(0).getFormattedMessage();
            assertFalse(message.contains(secret));
            assertFalse(message.contains("cache failure contains"));
            assertTrue(message.contains("type=CompletionException"));
            assertTrue(message.contains("error=[sha256="));
            assertNull(appender.list.get(0).getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
