package com.tencent.supersonic.headless.core.executor;

import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.core.gateway.QueryExecutionGateway;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExecutorGatewayCoverageTest {

    @Test
    void rejectsUnsafeSqlBeforeEnteringAnyExecutionPath() {
        QueryExecutionGateway gateway = new QueryExecutionGateway(1, 100, 10_000);
        QueryStatement statement = new QueryStatement();
        statement.setSql("DELETE FROM bank_account");

        try (MockedStatic<ContextUtils> context = Mockito.mockStatic(ContextUtils.class)) {
            context.when(() -> ContextUtils.getBean(QueryExecutionGateway.class))
                    .thenReturn(gateway);

            SemanticQueryResp response = new JdbcExecutor().execute(statement);

            assertTrue(response.getErrorMsg().contains("Only read-only SELECT"));
        }
    }
}
