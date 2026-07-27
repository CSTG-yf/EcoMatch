package com.tencent.supersonic.headless.server.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.api.pojo.request.QueryMultiStructReq;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.chat.corrector.SemanticCorrector;
import com.tencent.supersonic.headless.chat.utils.ComponentFactory;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import com.tencent.supersonic.headless.core.utils.SqlGenerateUtils;
import com.tencent.supersonic.headless.server.facade.service.impl.S2ChatLayerService;
import com.tencent.supersonic.headless.server.service.DataSetService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import com.tencent.supersonic.headless.server.utils.QueryUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SensitiveQueryLoggingTest {

    @Test
    void correctedSqlLogContainsOnlyDigest() {
        String secretSql = "SELECT account_no FROM customer_secret_101";
        S2ChatLayerService service = new S2ChatLayerService();
        SchemaService schemaService = mock(SchemaService.class);
        DataSetService dataSetService = mock(DataSetService.class);
        ReflectionTestUtils.setField(service, "schemaService", schemaService);
        ReflectionTestUtils.setField(service, "dataSetService", dataSetService);
        when(schemaService.getSemanticSchema(any())).thenReturn(mock(SemanticSchema.class));
        SemanticCorrector corrector =
                (context, parseInfo) -> parseInfo.getSqlInfo().setCorrectedS2SQL(secretSql);
        QuerySqlReq query = new QuerySqlReq();
        query.setDataSetId(1L);
        query.setSql("SELECT original_secret FROM source");
        ListAppender<ILoggingEvent> appender = attach(S2ChatLayerService.class, Level.INFO);
        try (MockedStatic<ComponentFactory> componentFactory = mockStatic(ComponentFactory.class)) {
            componentFactory.when(ComponentFactory::getSemanticCorrectors)
                    .thenReturn(List.of(corrector));

            service.correct(query, User.getDefaultUser());

            assertEquals(secretSql, query.getSql());
            String logs = messages(appender);
            assertFalse(logs.contains(secretSql));
            assertTrue(logs.contains("Corrected SQL metadata:[sha256="));
        } finally {
            detach(S2ChatLayerService.class, appender);
        }
    }

    @Test
    void unionSqlLogContainsOnlyDigestWhileResultIsUnchanged() {
        String firstSecretSql = "SELECT account_no FROM customer_secret_102";
        String secondSecretSql = "SELECT id_card FROM customer_secret_103";
        QueryStatement first = new QueryStatement();
        first.setSql(firstSecretSql);
        QueryStatement second = new QueryStatement();
        second.setSql(secondSecretSql);
        QueryStructReq firstRequest = new QueryStructReq();
        QueryStructReq secondRequest = new QueryStructReq();
        QueryMultiStructReq multiRequest = new QueryMultiStructReq();
        multiRequest.setQueryStructReqs(List.of(firstRequest, secondRequest));
        ListAppender<ILoggingEvent> appender = attach(QueryUtils.class, Level.INFO);
        try (MockedStatic<SqlGenerateUtils> sqlGenerateUtils = mockStatic(SqlGenerateUtils.class)) {
            sqlGenerateUtils.when(() -> SqlGenerateUtils.getUnionSelect(any(QueryStructReq.class)))
                    .thenReturn("value1");

            QueryStatement result = new QueryUtils().unionAll(multiRequest, List.of(first, second));

            assertTrue(result.getSql().contains(firstSecretSql));
            assertTrue(result.getSql().contains(secondSecretSql));
            String logs = messages(appender);
            assertFalse(logs.contains(firstSecretSql));
            assertFalse(logs.contains(secondSecretSql));
            assertTrue(logs.contains("union SQL metadata:[sha256="));
        } finally {
            detach(QueryUtils.class, appender);
        }
    }

    private ListAppender<ILoggingEvent> attach(Class<?> type, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        logger.setLevel(level);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }

    private String messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
