package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.ExportCreateReq;
import com.tencent.supersonic.headless.api.pojo.response.ExportTaskResp;
import com.tencent.supersonic.headless.server.service.ExportTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semantic/export")
public class ExportController {

    private final ExportTaskService exportTaskService;

    public ExportController(ExportTaskService exportTaskService) {
        this.exportTaskService = exportTaskService;
    }

    @PostMapping
    public ExportTaskResp create(@RequestBody ExportCreateReq createReq, HttpServletRequest request,
            HttpServletResponse response) {
        return exportTaskService.create(createReq, user(request, response));
    }

    @GetMapping("/{taskId}")
    public ExportTaskResp get(@PathVariable("taskId") String taskId, HttpServletRequest request,
            HttpServletResponse response) {
        return exportTaskService.get(taskId, user(request, response));
    }

    @GetMapping("/{taskId}/download")
    public void download(@PathVariable("taskId") String taskId, HttpServletRequest request,
            HttpServletResponse response) {
        exportTaskService.download(taskId, user(request, response), response);
    }

    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }
}
