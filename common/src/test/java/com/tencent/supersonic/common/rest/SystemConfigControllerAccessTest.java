package com.tencent.supersonic.common.rest;

import com.tencent.supersonic.common.config.SystemConfig;
import com.tencent.supersonic.common.service.SystemConfigAccessGuard;
import com.tencent.supersonic.common.service.SystemConfigService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class SystemConfigControllerAccessTest {

    private final SystemConfigService service = mock(SystemConfigService.class);
    private final SystemConfigAccessGuard guard = mock(SystemConfigAccessGuard.class);
    private final SystemConfigController controller = new SystemConfigController(service, guard);

    @Test
    void checksAdministratorBeforeReadingConfiguration() {
        controller.get(null, null);

        var order = inOrder(guard, service);
        order.verify(guard).requireAdministrator(null, null);
        order.verify(service).getSystemConfig();
    }

    @Test
    void checksAdministratorBeforeSavingConfiguration() {
        SystemConfig config = new SystemConfig();

        controller.save(config, null, null);

        var order = inOrder(guard, service);
        order.verify(guard).requireAdministrator(null, null);
        order.verify(service).save(config);
    }
}
