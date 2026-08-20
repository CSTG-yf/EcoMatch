package com.tencent.supersonic.auth.api.authorization.context;

import com.tencent.supersonic.common.pojo.User;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Canonicalizes historical organization attributes and role declarations once per request. */
public final class UserSecurityContextResolver {

    private static final String[] ORGANIZATION_KEYS = {
            "organizationId", "organizationCode", "orgId", "departmentId"
    };

    private UserSecurityContextResolver() {
    }

    public static UserSecurityContext resolve(User user, Collection<String> organizationIds) {
        if (user == null) {
            return new UserSecurityContext(null, null, Set.of(), Set.of(), Map.of());
        }
        Set<String> effectiveOrganizations = new LinkedHashSet<>();
        if (organizationIds != null) {
            organizationIds.stream().filter(StringUtils::hasText).map(String::trim)
                    .forEach(effectiveOrganizations::add);
        }
        Map<String, String> attributes = user.getAttributes() == null ? Map.of()
                : new TreeMap<>(user.getAttributes());
        String primaryOrganization = null;
        for (String key : ORGANIZATION_KEYS) {
            String value = attributes.get(key);
            if (StringUtils.hasText(value)) {
                primaryOrganization = value.trim();
                effectiveOrganizations.add(primaryOrganization);
                break;
            }
        }
        Set<String> roles = user.getRoles() == null ? Set.of()
                : user.getRoles().stream().filter(StringUtils::hasText).map(String::trim)
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new UserSecurityContext(user.getName(), primaryOrganization,
                Collections.unmodifiableSet(effectiveOrganizations),
                Collections.unmodifiableSet(roles), Collections.unmodifiableMap(attributes));
    }
}
