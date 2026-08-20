package com.tencent.supersonic.auth.api.authorization.context;

import java.util.Map;
import java.util.Set;

/** Immutable, canonical identity snapshot used by every authorization downstream. */
public record UserSecurityContext(String userName, String primaryOrganizationId,
        Set<String> effectiveOrganizationIds, Set<String> roles,
        Map<String, String> attributes) {
}
