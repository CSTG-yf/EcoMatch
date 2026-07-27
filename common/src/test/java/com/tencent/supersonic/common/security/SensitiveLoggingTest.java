package com.tencent.supersonic.common.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.service.impl.EmbeddingServiceImpl;
import com.tencent.supersonic.common.util.DifyClient;
import com.tencent.supersonic.common.util.DifyRequest;
import com.tencent.supersonic.common.util.DifyResult;
import com.tencent.supersonic.common.util.HttpUtils;
import com.tencent.supersonic.common.util.SqlFilterUtils;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.provider.ModelProvider;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreFactory;
import dev.langchain4j.store.embedding.EmbeddingStoreFactoryProvider;
import dev.langchain4j.store.embedding.TextSegmentConvert;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SensitiveLoggingTest {

    @Test
    void difyRequestLogsDoNotExposeCredentialsOrPrompt() {
        String apiKey = "TOP_SECRET_DIFY_KEY_90";
        String prompt = "TOP_SECRET_DIFY_PROMPT_90";
        String user = "TOP_SECRET_DIFY_USER_90";
        DifyRequest request = new DifyRequest();
        request.setQuery(prompt);
        request.setUser(user);
        ListAppender<ILoggingEvent> appender = attach(DifyClient.class, Level.DEBUG);
        try (MockedStatic<HttpUtils> httpUtils = mockStatic(HttpUtils.class)) {
            httpUtils.when(
                    () -> HttpUtils.post(anyString(), anyString(), anyMap(), eq(DifyResult.class)))
                    .thenReturn(new DifyResult());

            new DifyClient("https://dify.example/v1", apiKey).sendRequest(request,
                    Map.of("Authorization", "Bearer " + apiKey));

            String logs = messages(appender);
            assertFalse(logs.contains(apiKey));
            assertFalse(logs.contains(prompt));
            assertFalse(logs.contains(user));
            assertTrue(logs.contains("Dify request headers [sha256="));
            assertTrue(logs.contains("Dify request payload [sha256="));
        } finally {
            detach(DifyClient.class, appender);
        }
    }

    @Test
    void sqlParseFailureLogsOnlyDigestAndExceptionType() {
        String secret = "TOP_SECRET_SQL_91";
        ListAppender<ILoggingEvent> appender = attach(SqlSelectHelper.class, Level.ERROR);
        try {
            assertThrows(RuntimeException.class,
                    () -> SqlSelectHelper.getSelect("SELECT '" + secret + "' FROM ("));

            String logs = messages(appender);
            assertFalse(logs.contains(secret));
            assertTrue(logs.contains("sha256="));
            assertTrue(logs.contains("JSQLParserException"));
            assertNull(appender.list.get(0).getThrowableProxy());
        } finally {
            detach(SqlSelectHelper.class, appender);
        }
    }

    @Test
    void whereClauseLogsDoNotExposeFilterValues() {
        String secret = "TOP_SECRET_FILTER_92";
        ListAppender<ILoggingEvent> appender = attach(SqlFilterUtils.class, Level.DEBUG);
        try {
            String whereClause = new SqlFilterUtils().getWhereClause(
                    List.of(new Filter("customer_id", FilterOperatorEnum.EQUALS, secret)));

            assertTrue(whereClause.contains(secret));
            String logs = messages(appender);
            assertFalse(logs.contains(secret));
            assertTrue(logs.contains("criterionMetadata:[sha256="));
            assertTrue(logs.contains("whereMetadata:[sha256="));
        } finally {
            detach(SqlFilterUtils.class, appender);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void embeddingFailureLogsDoNotExposeQuestionOrExceptionMessage() {
        String secret = "TOP_SECRET_QUESTION_93";
        String collection = "embedding-collection";
        EmbeddingStoreFactory factory = mock(EmbeddingStoreFactory.class);
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(factory.create(collection)).thenReturn(store);
        ListAppender<ILoggingEvent> appender = attach(EmbeddingServiceImpl.class, Level.ERROR);
        try (MockedStatic<EmbeddingStoreFactoryProvider> storeProvider =
                mockStatic(EmbeddingStoreFactoryProvider.class);
                MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
            storeProvider.when(EmbeddingStoreFactoryProvider::getFactory).thenReturn(factory);
            modelProvider.when(ModelProvider::getEmbeddingModel)
                    .thenThrow(new IllegalStateException("failure contains " + secret));

            new EmbeddingServiceImpl().addQuery(collection, List.of(TextSegment.from(secret)));

            String logs = messages(appender);
            assertFalse(logs.contains(secret));
            assertFalse(logs.contains("failure contains"));
            assertTrue(logs.contains("questionMetadata:[sha256="));
            assertTrue(logs.contains("errorType:IllegalStateException"));
            assertNull(appender.list.get(0).getThrowableProxy());
        } finally {
            detach(EmbeddingServiceImpl.class, appender);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void embeddingDeleteFailureLogsOnlyCollectionAndQueryDigests() {
        String secret = "TOP_SECRET_DELETE_QUERY_94";
        String collection = "TOP_SECRET_COLLECTION_94";
        EmbeddingStoreFactory factory = mock(EmbeddingStoreFactory.class);
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(factory.create(collection)).thenReturn(store);
        doThrow(new IllegalStateException("failure contains " + secret)).when(store)
                .removeAll(any(dev.langchain4j.store.embedding.filter.Filter.class));
        TextSegment query = TextSegment.from(secret);
        TextSegmentConvert.addQueryId(query, "query-94");
        ListAppender<ILoggingEvent> appender = attach(EmbeddingServiceImpl.class, Level.ERROR);
        try (MockedStatic<EmbeddingStoreFactoryProvider> storeProvider =
                mockStatic(EmbeddingStoreFactoryProvider.class)) {
            storeProvider.when(EmbeddingStoreFactoryProvider::getFactory).thenReturn(factory);

            new EmbeddingServiceImpl().deleteQuery(collection, List.of(query));

            String logs = messages(appender);
            assertFalse(logs.contains(secret));
            assertFalse(logs.contains(collection));
            assertFalse(logs.contains("failure contains"));
            assertTrue(logs.contains("collectionMetadata:[sha256="));
            assertTrue(logs.contains("queriesMetadata:[sha256="));
            assertTrue(logs.contains("errorType:IllegalStateException"));
        } finally {
            detach(EmbeddingServiceImpl.class, appender);
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
