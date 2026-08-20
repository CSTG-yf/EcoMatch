package com.tencent.supersonic.auth.api.authorization.context;

import com.tencent.supersonic.auth.api.authorization.pojo.ResourcePermission;

import java.util.List;

/** Request-local authorization snapshot shared by query, cache and masking layers. */
public final class AuthorizationContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private AuthorizationContext() {}

    public static void install(List<ResourcePermission> permissions, long policyVersion) {
        CURRENT.set(new Snapshot(permissions == null ? List.of() : List.copyOf(permissions),
                Math.max(0L, policyVersion)));
    }

    public static Snapshot current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Snapshot(List<ResourcePermission> resourcePermissions, long policyVersion) {}
}
