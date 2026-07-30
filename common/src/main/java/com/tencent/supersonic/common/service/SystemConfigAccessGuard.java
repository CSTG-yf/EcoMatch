package com.tencent.supersonic.common.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface SystemConfigAccessGuard {

    void requireAdministrator(HttpServletRequest request, HttpServletResponse response);
}
