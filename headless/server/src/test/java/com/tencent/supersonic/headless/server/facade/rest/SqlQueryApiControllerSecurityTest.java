package com.tencent.supersonic.headless.server.facade.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlsReq;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SqlQueryApiControllerSecurityTest {

    private final SemanticLayerService semanticLayerService = mock(SemanticLayerService.class);
    private final ChatLayerService chatLayerService = mock(ChatLayerService.class);
    private final HttpServletRequest servletRequest = mock(HttpServletRequest.class);
    private final HttpServletResponse servletResponse = mock(HttpServletResponse.class);
    private final User user = User.get(1L, "alice");
    private SqlQueryApiController controller;

    @BeforeEach
    void setUp() {
        controller = new SqlQueryApiController();
        ReflectionTestUtils.setField(controller, "semanticLayerService", semanticLayerService);
        ReflectionTestUtils.setField(controller, "chatLayerService", chatLayerService);
    }

    @Test
    void tolerantBatchFailsClosedWhenAnyQueryFails() throws Exception {
        QuerySqlsReq request = request("select metric from t limit 1");
        when(semanticLayerService.queryByReq(any(), eq(user)))
                .thenThrow(new RuntimeException("jdbc leaked select secret"));

        InvalidArgumentException error =
                withAuthenticatedUser(() -> assertThrows(InvalidArgumentException.class,
                        () -> controller.queryBySqls(request, servletRequest, servletResponse)));

        assertEquals("Batch query execution failed", error.getMessage());
    }

    @Test
    void strictBatchDoesNotExposeUnderlyingExceptionOrRequireCause() throws Exception {
        QuerySqlsReq request = request("select metric from t limit 1");
        when(semanticLayerService.queryByReq(any(), eq(user)))
                .thenThrow(new RuntimeException("jdbc leaked select secret"));

        InvalidArgumentException error = withAuthenticatedUser(
                () -> assertThrows(InvalidArgumentException.class, () -> controller
                        .queryBySqlsWithException(request, servletRequest, servletResponse)));

        assertEquals("Batch query execution failed", error.getMessage());
    }

    @Test
    void batchRejectsEmptyAndOversizedRequestsBeforeAuthentication() {
        InvalidArgumentException empty = assertThrows(InvalidArgumentException.class,
                () -> controller.queryBySqls(request(), servletRequest, servletResponse));
        QuerySqlsReq oversized = new QuerySqlsReq();
        oversized.setSqls(IntStream.range(0, 101).mapToObj(i -> "select 1").toList());

        InvalidArgumentException tooLarge = assertThrows(InvalidArgumentException.class,
                () -> controller.queryBySqls(oversized, servletRequest, servletResponse));

        assertEquals("Batch query must contain at least one SQL statement", empty.getMessage());
        assertEquals("Batch query exceeds the maximum of 100 statements", tooLarge.getMessage());
    }

    @Test
    void batchRejectsBlankStatement() {
        QuerySqlsReq request = new QuerySqlsReq();
        request.setSqls(List.of("select 1", " "));

        InvalidArgumentException error = assertThrows(InvalidArgumentException.class,
                () -> controller.queryBySqls(request, servletRequest, servletResponse));

        assertEquals("Batch query contains an empty SQL statement", error.getMessage());
    }

    private QuerySqlsReq request(String... sqls) {
        QuerySqlsReq request = new QuerySqlsReq();
        request.setSqls(sqls.length == 0 ? Collections.emptyList() : List.of(sqls));
        return request;
    }

    private <T> T withAuthenticatedUser(ThrowingSupplier<T> supplier) throws Exception {
        try (MockedStatic<UserHolder> userHolder = mockStatic(UserHolder.class)) {
            userHolder.when(() -> UserHolder.findUser(servletRequest, servletResponse))
                    .thenReturn(user);
            return supplier.get();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
