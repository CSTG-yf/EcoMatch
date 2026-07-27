package com.tencent.supersonic.headless.server.security.audit;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;

public interface AuditEventPublisher {

    /** Required write for sensitive operations such as exports. Throws when persistence fails. */
    String publishRequired(AuditEvent event, User user);

    /** Best-effort write for observational events. Never changes the business response. */
    void publishBestEffort(AuditEvent event, User user);
}
