package com.tencent.supersonic.auth.api.authorization.context;

import com.tencent.supersonic.auth.api.authorization.pojo.ResourcePermission;
import com.tencent.supersonic.common.pojo.User;

import java.util.List;
import java.util.Set;

/** Request-local authorization snapshot shared by query, cache and masking layers. */
public final class AuthorizationContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private AuthorizationContext() {}

    public static void install(List<ResourcePermission> permissions, long policyVersion) {
        install(permissions, policyVersion, null, Set.of());
    }

    public static void install(List<ResourcePermission> permissions, long policyVersion,
            User user, Set<String> organizationIds) {
        CURRENT.set(new Snapshot(permissions == null ? List.of() : List.copyOf(permissions),
                Math.max(0L, policyVersion), UserSecurityContextResolver.resolve(user,
                        organizationIds)));
    }

    public static Snapshot current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Snapshot(List<ResourcePermission> resourcePermissions, long policyVersion,
            UserSecurityContext userContext) {
    }
}
