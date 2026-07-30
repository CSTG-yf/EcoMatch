package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.common.service.SystemConfigAccessGuard;
import com.tencent.supersonic.headless.server.service.EvaluationReportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/semantic/evaluation")
public class EvaluationReportController {

    private final EvaluationReportService reportService;
    private final SystemConfigAccessGuard accessGuard;

    public EvaluationReportController(EvaluationReportService reportService,
            SystemConfigAccessGuard accessGuard) {
        this.reportService = reportService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(HttpServletRequest request, HttpServletResponse response) {
        accessGuard.requireAdministrator(request, response);
        return reportService.dashboard();
    }
}
