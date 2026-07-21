package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricApprovalDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricGovernanceDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricOrgMappingDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricVersionDO;
import com.tencent.supersonic.headless.server.pojo.governance.MetricApprovalDecisionReq;
import com.tencent.supersonic.headless.server.pojo.governance.MetricConflict;
import com.tencent.supersonic.headless.server.pojo.governance.MetricGovernanceDetail;
import com.tencent.supersonic.headless.server.pojo.governance.MetricGovernanceReq;
import com.tencent.supersonic.headless.server.pojo.governance.MetricLineage;
import com.tencent.supersonic.headless.server.pojo.governance.MetricOrgMappingReq;
import com.tencent.supersonic.headless.server.service.governance.MetricConflictService;
import com.tencent.supersonic.headless.server.service.governance.MetricGovernanceService;
import com.tencent.supersonic.headless.server.service.governance.MetricLineageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/semantic/metric-governance")
public class MetricGovernanceController {

    private final MetricGovernanceService governanceService;
    private final MetricConflictService conflictService;
    private final MetricLineageService lineageService;

    public MetricGovernanceController(MetricGovernanceService governanceService,
            MetricConflictService conflictService, MetricLineageService lineageService) {
        this.governanceService = governanceService;
        this.conflictService = conflictService;
        this.lineageService = lineageService;
    }

    @PostMapping("/models/{modelId}/bootstrap")
    public Integer bootstrap(@PathVariable Long modelId, @RequestBody MetricGovernanceReq request,
            HttpServletRequest servletRequest, HttpServletResponse response) {
        return governanceService.bootstrapModel(modelId, request, user(servletRequest, response));
    }

    @PutMapping("/metrics/{metricId}")
    public MetricGovernanceDetail update(@PathVariable Long metricId,
            @RequestBody MetricGovernanceReq request, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        return governanceService.updateGovernance(metricId, request,
                user(servletRequest, response));
    }

    @GetMapping("/metrics/{metricId}")
    public MetricGovernanceDetail detail(@PathVariable Long metricId,
            HttpServletRequest servletRequest, HttpServletResponse response) {
        return governanceService.detail(metricId, user(servletRequest, response));
    }

    @GetMapping("/models/{modelId}/metrics")
    public List<MetricGovernanceDO> metrics(@PathVariable Long modelId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ownerDepartment,
            @RequestParam(required = false) String sourceSystem) {
        return governanceService.listGovernance(modelId, status, ownerDepartment, sourceSystem);
    }

    @PostMapping("/metrics/{metricId}/versions")
    public MetricVersionDO createVersion(@PathVariable Long metricId,
            @RequestParam(required = false) String changeSummary, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        return governanceService.createVersion(metricId, changeSummary,
                user(servletRequest, response));
    }

    @PostMapping("/metrics/{metricId}/versions/{versionId}/submit")
    public MetricApprovalDO submit(@PathVariable Long metricId, @PathVariable Long versionId,
            @RequestParam(required = false) String comment, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        return governanceService.submit(metricId, versionId, comment,
                user(servletRequest, response));
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public MetricApprovalDO approve(@PathVariable Long approvalId,
            @RequestBody(required = false) MetricApprovalDecisionReq request,
            HttpServletRequest servletRequest, HttpServletResponse response) {
        return governanceService.decide(approvalId, true, request, user(servletRequest, response));
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public MetricApprovalDO reject(@PathVariable Long approvalId,
            @RequestBody(required = false) MetricApprovalDecisionReq request,
            HttpServletRequest servletRequest, HttpServletResponse response) {
        return governanceService.decide(approvalId, false, request, user(servletRequest, response));
    }

    @PostMapping("/metrics/{metricId}/versions/{versionId}/publish")
    public MetricGovernanceDetail publish(@PathVariable Long metricId, @PathVariable Long versionId,
            @RequestParam(required = false) String comment, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        return governanceService.publish(metricId, versionId, comment,
                user(servletRequest, response));
    }

    @PostMapping("/metrics/{metricId}/deactivate")
    public MetricGovernanceDetail deactivate(@PathVariable Long metricId,
            @RequestParam(required = false) String comment, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        return governanceService.deactivate(metricId, comment, user(servletRequest, response));
    }

    @PostMapping("/metrics/{metricId}/versions/{versionNo}/rollback")
    public MetricVersionDO rollback(@PathVariable Long metricId, @PathVariable Integer versionNo,
            @RequestParam(required = false) String comment, HttpServletRequest servletRequest,
            HttpServletResponse response) throws Exception {
        return governanceService.rollback(metricId, versionNo, comment,
                user(servletRequest, response));
    }

    @PutMapping("/mappings")
    public MetricOrgMappingDO saveMapping(@RequestBody MetricOrgMappingReq request,
            HttpServletRequest servletRequest, HttpServletResponse response) {
        return governanceService.saveMapping(request, user(servletRequest, response));
    }

    @GetMapping("/metrics/{metricId}/mappings")
    public List<MetricOrgMappingDO> mappings(@PathVariable Long metricId) {
        return governanceService.listMappings(metricId);
    }

    @GetMapping("/models/{modelId}/conflicts")
    public List<MetricConflict> conflicts(@PathVariable Long modelId) {
        return conflictService.detectByModel(modelId);
    }

    @GetMapping("/metrics/{metricId}/lineage")
    public MetricLineage lineage(@PathVariable Long metricId, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        return lineageService.getLineage(metricId, user(servletRequest, response));
    }

    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }
}
