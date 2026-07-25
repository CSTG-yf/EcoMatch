package com.tencent.supersonic.headless.server.security.audit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class AuditRuleInitializerTest {

    @Test
    void duplicateDefaultDoesNotAbortApplicationStartup() {
        AuditRuleService service = mock(AuditRuleService.class);
        doThrow(new DuplicateKeyException("concurrent startup")).when(service).initializeDefaults();

        AuditRuleInitializer initializer = new AuditRuleInitializer(service);

        assertDoesNotThrow(() -> initializer.run(null));
    }
}
