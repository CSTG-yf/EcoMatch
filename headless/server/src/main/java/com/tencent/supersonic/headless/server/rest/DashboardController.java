package com.tencent.supersonic.headless.server.rest;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.enums.DashboardStatus;
import com.tencent.supersonic.headless.api.pojo.request.DashboardCopyReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardCreateReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardUpdateReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardVersionReq;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.server.service.DashboardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semantic/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public PageInfo<DashboardResp> list(@RequestParam("domainId") Long domainId,
            @RequestParam(value = "status", required = false) DashboardStatus status,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request, HttpServletResponse response) {
        return dashboardService.list(domainId, status, pageNum, pageSize, user(request, response));
    }

    @GetMapping("/{id}")
    public DashboardResp get(@PathVariable("id") Long id, HttpServletRequest request,
            HttpServletResponse response) {
        return dashboardService.get(id, user(request, response));
    }

    @PostMapping
    public DashboardResp create(@RequestBody DashboardCreateReq createReq,
            HttpServletRequest request, HttpServletResponse response) {
        return dashboardService.create(createReq, user(request, response));
    }

    @PutMapping("/{id}")
    public DashboardResp update(@PathVariable("id") Long id,
            @RequestBody DashboardUpdateReq updateReq, HttpServletRequest request,
            HttpServletResponse response) {
        return dashboardService.update(id, updateReq, user(request, response));
    }

    @PostMapping("/{id}/copy")
    public DashboardResp copy(@PathVariable("id") Long id,
            @RequestBody(required = false) DashboardCopyReq copyReq, HttpServletRequest request,
            HttpServletResponse response) {
        return dashboardService.copy(id, copyReq, user(request, response));
    }

    @PostMapping("/{id}/publish")
    public DashboardResp publish(@PathVariable("id") Long id,
            @RequestBody DashboardVersionReq versionReq, HttpServletRequest request,
            HttpServletResponse response) {
        return dashboardService.publish(id, version(versionReq), user(request, response));
    }

    @PostMapping("/{id}/disable")
    public DashboardResp disable(@PathVariable("id") Long id,
            @RequestBody DashboardVersionReq versionReq, HttpServletRequest request,
            HttpServletResponse response) {
        return dashboardService.disable(id, version(versionReq), user(request, response));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id, HttpServletRequest request,
            HttpServletResponse response) {
        dashboardService.delete(id, user(request, response));
    }

    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }

    private Integer version(DashboardVersionReq request) {
        return request == null ? null : request.getVersion();
    }
}
