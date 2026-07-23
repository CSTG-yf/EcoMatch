package com.tencent.supersonic.headless.server.gateway;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.core.gateway.QueryExecutionGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryGatewayMonitorServiceTest {

    private final QueryExecutionGateway gateway = new QueryExecutionGateway(4, 100, 1000);
    private final QueryGatewayMonitorService service = new QueryGatewayMonitorService(gateway);

    @Test
    void exposesGatewayAndStageMetricsToSuperAdmin() {
        gateway.execute("SELECT id FROM account", () -> 1);

        QueryGatewayMonitorService.GatewayMonitorSnapshot snapshot =
                service.snapshot(User.getDefaultUser());

        assertEquals(1, snapshot.gateway().completedQueries());
        assertTrue(snapshot.stages().containsKey("parse"));
        assertTrue(snapshot.stages().containsKey("model"));
        assertTrue(snapshot.stages().containsKey("translate"));
        assertTrue(snapshot.stages().containsKey("execute"));
        assertTrue(snapshot.stages().containsKey("explain"));
        assertEquals(0, snapshot.cache().requests());
    }

    @Test
    void rejectsNonAdminMetricAccess() {
        assertThrows(InvalidPermissionException.class,
                () -> service.snapshot(User.get(2L, "analyst")));
    }
}
