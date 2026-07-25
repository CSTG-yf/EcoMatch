package com.tencent.supersonic.headless.core.executor;

import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.core.gateway.QueryExecutionGateway;
import com.tencent.supersonic.headless.core.gateway.QueryRejectedException;
import com.tencent.supersonic.headless.core.gateway.SqlPolicyViolationException;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void hidesDriverDetailsButKeepsPolicyRejectionReason() {
        assertEquals("Query execution failed", JdbcExecutor.safeErrorMessage(
                new RuntimeException("SELECT * FROM customer WHERE id_card='secret'")));
        assertEquals("Only read-only SELECT statements are allowed", JdbcExecutor.safeErrorMessage(
                new SqlPolicyViolationException("Only read-only SELECT statements are allowed")));
    }

    @Test
    void rejectsOversizedAcceleratorOrExecutorResult() {
        SemanticQueryResp response = new SemanticQueryResp();
        response.setResultList(List.of(Map.of("id", 1), Map.of("id", 2)));

        QueryRejectedException rejection = org.junit.jupiter.api.Assertions.assertThrows(
                QueryRejectedException.class, () -> JdbcExecutor.enforceResultLimit(response, 1));

        assertEquals("Query result row limit exceeded: 1", rejection.getMessage());
    }
}
