package com.tencent.supersonic.common.rest;

import com.tencent.supersonic.common.config.SystemConfig;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.service.SystemConfigAccessGuard;
import com.tencent.supersonic.common.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/semantic/parameter"})
public class SystemConfigController {

    private final SystemConfigService sysConfigService;
    private final SystemConfigAccessGuard accessGuard;

    @PostMapping
    public Boolean save(@RequestBody SystemConfig systemConfig, HttpServletRequest request,
            HttpServletResponse response) {
        accessGuard.requireAdministrator(request, response);
        sysConfigService.save(systemConfig);
        return true;
    }

    @GetMapping
    public SystemConfig get(HttpServletRequest request, HttpServletResponse response) {
        accessGuard.requireAdministrator(request, response);
        return sysConfigService.getSystemConfig();
    }

    @Autowired
    public SystemConfigController(SystemConfigService sysConfigService,
            ObjectProvider<SystemConfigAccessGuard> accessGuardProvider) {
        this(sysConfigService, accessGuardProvider.getIfAvailable(() -> (request, response) -> {
            throw new InvalidPermissionException(
                    "System configuration access guard is unavailable");
        }));
    }

    SystemConfigController(SystemConfigService sysConfigService,
            SystemConfigAccessGuard accessGuard) {
        this.sysConfigService = sysConfigService;
        this.accessGuard = accessGuard;
    }
}
