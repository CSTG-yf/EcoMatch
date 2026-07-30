package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.ExportCreateReq;
import com.tencent.supersonic.headless.api.pojo.response.ExportTaskResp;
import jakarta.servlet.http.HttpServletResponse;

public interface ExportTaskService {

    ExportTaskResp create(ExportCreateReq request, User user);

    ExportTaskResp get(String taskId, User user);

    void download(String taskId, User user, HttpServletResponse response);

    int cleanupExpired();
}
