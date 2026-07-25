package com.tencent.supersonic.headless.server.gateway;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.core.gateway.QueryExecutionGateway;
import com.tencent.supersonic.headless.core.gateway.QueryPerformanceMonitor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class QueryGatewayMonitorService {

    private final QueryExecutionGateway queryExecutionGateway;

    public QueryGatewayMonitorService(QueryExecutionGateway queryExecutionGateway) {
        this.queryExecutionGateway = queryExecutionGateway;
    }

    public GatewayMonitorSnapshot snapshot(User user) {
        if (user == null || !user.isSuperAdmin()) {
            throw new InvalidPermissionException(
                    "Only a super administrator can view query gateway metrics");
        }
        return new GatewayMonitorSnapshot(queryExecutionGateway.snapshot(),
                QueryPerformanceMonitor.snapshot(), QueryPerformanceMonitor.cacheSnapshot());
    }

    public record GatewayMonitorSnapshot(QueryExecutionGateway.QueryGatewayStats gateway,
            Map<String, QueryPerformanceMonitor.StageStats> stages,
            QueryPerformanceMonitor.CacheStats cache) {}
}
