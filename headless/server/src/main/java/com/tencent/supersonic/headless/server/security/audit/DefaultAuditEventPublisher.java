package com.tencent.supersonic.headless.server.security.audit;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DefaultAuditEventPublisher implements AuditEventPublisher {

    private final AuditEventService auditEventService;
    private final AuditAnomalyEngine anomalyEngine;
    private final AuditProperties properties;

    public DefaultAuditEventPublisher(AuditEventService auditEventService,
            AuditAnomalyEngine anomalyEngine, AuditProperties properties) {
        this.auditEventService = auditEventService;
        this.anomalyEngine = anomalyEngine;
        this.properties = properties;
    }

    @Override
    public String publishRequired(AuditEvent event, User user) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Required security auditing is disabled");
        }
        AuditEventDO persisted = auditEventService.persist(event, user);
        try {
            anomalyEngine.evaluate(persisted);
        } catch (RuntimeException e) {
            log.error("Audit anomaly evaluation failed for event [{}]: type={}",
                    SensitiveLogUtils.summarize(persisted.getEventId()),
                    e.getClass().getSimpleName());
        }
        return persisted.getEventId();
    }

    @Override
    public void publishBestEffort(AuditEvent event, User user) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            publishRequired(event, user);
        } catch (RuntimeException e) {
            log.error("Best-effort audit write failed: eventType={}, errorType={}",
                    event == null ? null : event.getEventType(), e.getClass().getSimpleName());
        }
    }
}
