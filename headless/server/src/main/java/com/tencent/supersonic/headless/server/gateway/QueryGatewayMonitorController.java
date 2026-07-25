package com.tencent.supersonic.headless.server.gateway;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semantic/query/gateway")
public class QueryGatewayMonitorController {

    private final QueryGatewayMonitorService monitorService;

    public QueryGatewayMonitorController(QueryGatewayMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/stats")
    public QueryGatewayMonitorService.GatewayMonitorSnapshot stats(HttpServletRequest request,
            HttpServletResponse response) {
        return monitorService.snapshot(UserHolder.findUser(request, response));
    }
}
