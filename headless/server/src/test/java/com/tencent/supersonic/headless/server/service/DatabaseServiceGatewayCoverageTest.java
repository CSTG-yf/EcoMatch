package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.core.gateway.QueryExecutionGateway;
import com.tencent.supersonic.headless.core.gateway.QueryRejectedException;
import com.tencent.supersonic.headless.core.gateway.SqlPolicyViolationException;
import com.tencent.supersonic.headless.core.utils.SqlUtils;
import com.tencent.supersonic.headless.server.service.impl.DatabaseServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseServiceGatewayCoverageTest {

    @Test
    void rejectsUnsafeSqlBeforeJdbcExecution() {
        SqlUtils sqlUtils = mock(SqlUtils.class);
        when(sqlUtils.init(any(DatabaseResp.class))).thenReturn(sqlUtils);
        DatabaseServiceImpl service = new DatabaseServiceImpl();
        ReflectionTestUtils.setField(service, "sqlUtils", sqlUtils);
        ReflectionTestUtils.setField(service, "queryExecutionGateway",
                new QueryExecutionGateway(1, 100, 10_000));

        assertThrows(SqlPolicyViolationException.class, () -> service
                .executeSql("DROP TABLE bank_account", DatabaseResp.builder().build()));
        verify(sqlUtils, never()).queryInternal(any(String.class), any(SemanticQueryResp.class));
    }

    @Test
    void sanitizesDirectJdbcExecutionFailures() {
        SqlUtils sqlUtils = mock(SqlUtils.class);
        when(sqlUtils.init(any(DatabaseResp.class))).thenReturn(sqlUtils);
        doThrow(new RuntimeException("secret_table connection detail")).when(sqlUtils)
                .queryInternal(any(String.class), any(SemanticQueryResp.class));
        DatabaseServiceImpl service = new DatabaseServiceImpl();
        ReflectionTestUtils.setField(service, "sqlUtils", sqlUtils);
        ReflectionTestUtils.setField(service, "queryExecutionGateway",
                new QueryExecutionGateway(1, 100, 10_000));

        QueryRejectedException exception = assertThrows(QueryRejectedException.class, () -> service
                .executeSql("SELECT balance FROM account", DatabaseResp.builder().build()));

        assertEquals("Query execution failed", exception.getMessage());
        assertFalse(exception.getMessage().contains("secret_table"));
    }
}
