package com.tencent.supersonic.headless.server.security.audit;

import com.tencent.supersonic.auth.api.authorization.audit.AuthorizationAuditSink;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Persists authorization policy lifecycle events into the same append-only audit chain. */
@Component
public class AuthorizationAuditSinkImpl implements AuthorizationAuditSink {

    private final AuditEventPublisher auditEventPublisher;

    public AuthorizationAuditSinkImpl(AuditEventPublisher auditEventPublisher) {
        this.auditEventPublisher = auditEventPublisher;
    }

    @Override
    public void publish(String eventType, Long modelId, Integer groupId, Long policyVersion,
            User user) {
        AuditEventType type = AuditEventType.valueOf(eventType);
        String resourceId = "model=" + String.valueOf(modelId)
                + (groupId == null ? "" : ",group=" + groupId);
        auditEventPublisher.publishRequired(AuditEvent.builder().eventType(type)
                .outcome(AuditOutcome.SUCCESS).resourceType("AUTH_POLICY").resourceId(resourceId)
                .policyIds(groupId == null ? List.of() : List.of(String.valueOf(groupId)))
                .metadata(Map.of("policyVersion", policyVersion == null ? 0L : policyVersion))
                .build(), user);
    }
}
