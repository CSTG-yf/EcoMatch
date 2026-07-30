package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.common.service.SystemConfigAccessGuard;
import com.tencent.supersonic.headless.server.service.EvaluationReportService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class EvaluationReportControllerTest {

    @Test
    void checksAdministratorBeforeReadingReports() {
        EvaluationReportService service = mock(EvaluationReportService.class);
        SystemConfigAccessGuard guard = mock(SystemConfigAccessGuard.class);
        EvaluationReportController controller = new EvaluationReportController(service, guard);

        controller.dashboard(null, null);

        var order = inOrder(guard, service);
        order.verify(guard).requireAdministrator(null, null);
        order.verify(service).dashboard();
    }
}
