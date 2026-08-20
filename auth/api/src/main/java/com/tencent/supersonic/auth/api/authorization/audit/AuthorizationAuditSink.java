package com.tencent.supersonic.auth.api.authorization.audit;

import com.tencent.supersonic.common.pojo.User;

/** Optional bridge allowing the authorization module to record policy lifecycle events. */
public interface AuthorizationAuditSink {

    void publish(String eventType, Long modelId, Integer groupId, Long policyVersion, User user);
}
