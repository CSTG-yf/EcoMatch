package com.tencent.supersonic.headless.server.security.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuditRuleInitializer implements ApplicationRunner {

    private final AuditRuleService auditRuleService;

    public AuditRuleInitializer(AuditRuleService auditRuleService) {
        this.auditRuleService = auditRuleService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            auditRuleService.initializeDefaults();
        } catch (DuplicateKeyException e) {
            // Last-resort protection for concurrent startup on a shared database.
            log.info("Default audit rules were initialized by another application instance");
        }
    }
}
