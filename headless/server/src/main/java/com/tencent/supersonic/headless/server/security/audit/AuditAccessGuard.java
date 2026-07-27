package com.tencent.supersonic.headless.server.security.audit;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AuditAccessGuard {

    private static final Set<String> READ_ROLES =
            Set.of("SECURITY_ADMIN", "SECURITY_AUDITOR", "RISK_AUDITOR");
    private static final Set<String> WRITE_ROLES = Set.of("SECURITY_ADMIN");
    private static final List<String> ORGANIZATION_ATTRIBUTE_KEYS =
            List.of("organizationId", "organizationCode", "orgId", "departmentId");
    private static final String READ_DENIAL_MESSAGE =
            "Only security administrators or auditors can view audit data";
    private static final String WRITE_DENIAL_MESSAGE =
            "Only a security administrator can modify alerts or audit rules";

    private final AuditEventPublisher auditEventPublisher;
    private final Set<String> readUsers;
    private final Set<String> writeUsers;

    public AuditAccessGuard(AuditEventPublisher auditEventPublisher,
            @Value("${s2.security.audit.admin-users:}") String adminUsers,
            @Value("${s2.security.audit.auditor-users:}") String auditorUsers) {
        this.auditEventPublisher = auditEventPublisher;
        this.writeUsers = parseUsers(adminUsers);
        this.readUsers = new HashSet<>(writeUsers);
        this.readUsers.addAll(parseUsers(auditorUsers));
    }

    public void requireRead(User user) {
        requireRead(user, "AUDIT_MANAGEMENT");
    }

    public void requireRead(User user, String resourceType) {
        require(user, READ_ROLES, readUsers, AuditEventType.AUDIT_ACCESSED, resourceType,
                "AUDIT_READ_FORBIDDEN", READ_DENIAL_MESSAGE);
    }

    public void requireWrite(User user) {
        requireWrite(user, AuditEventType.AUDIT_ACCESSED, "AUDIT_MANAGEMENT");
    }

    public void requireWrite(User user, AuditEventType attemptedEventType, String resourceType) {
        require(user, WRITE_ROLES, writeUsers, attemptedEventType, resourceType,
                "AUDIT_WRITE_FORBIDDEN", WRITE_DENIAL_MESSAGE);
    }

    public void requireGlobalWrite(User user, AuditEventType attemptedEventType,
            String resourceType) {
        requireWrite(user, attemptedEventType, resourceType);
        String organizationScope = organizationScope(user);
        if (organizationScope != null) {
            deny(user, attemptedEventType, resourceType, "AUDIT_GLOBAL_SCOPE_REQUIRED",
                    "Only a global security administrator can modify audit rules");
        }
    }

    /** Returns a signed ABAC organization scope; null denotes an explicitly global identity. */
    public String organizationScope(User user) {
        if (user == null || user.isSuperAdmin() || isConfiguredGlobalUser(user)
                || hasGlobalScopeAttribute(user)) {
            return null;
        }
        Map<String, String> attributes = user.getAttributes();
        String scope = attributes == null ? null
                : ORGANIZATION_ATTRIBUTE_KEYS.stream().map(attributes::get)
                        .filter(StringUtils::isNotBlank).map(String::trim).findFirst().orElse(null);
        if (scope == null) {
            deny(user, AuditEventType.AUDIT_ACCESSED, "AUDIT_MANAGEMENT",
                    "AUDIT_ORGANIZATION_REQUIRED",
                    "A non-global audit role must have a trusted organization attribute");
        }
        return scope;
    }

    public void requireOrganizationAccess(User user, String resourceOrganization,
            String resourceType) {
        String requiredOrganization = organizationScope(user);
        if (requiredOrganization == null
                || Objects.equals(requiredOrganization, resourceOrganization)) {
            return;
        }
        deny(user, AuditEventType.AUDIT_ACCESSED, resourceType, "AUDIT_ORGANIZATION_FORBIDDEN",
                "Audit data is outside the user's organization scope");
    }

    private void require(User user, Set<String> allowedRoles, Set<String> allowedUsers,
            AuditEventType attemptedEventType, String resourceType, String reasonCode,
            String denialMessage) {
        if (isAllowed(user, allowedRoles, allowedUsers)) {
            return;
        }
        deny(user, attemptedEventType, resourceType, reasonCode, denialMessage);
    }

    private void deny(User user, AuditEventType attemptedEventType, String resourceType,
            String reasonCode, String denialMessage) {
        InvalidPermissionException denial = new InvalidPermissionException(denialMessage);
        try {
            auditEventPublisher.publishRequired(
                    AuditEvent.builder().eventType(attemptedEventType).resourceType(resourceType)
                            .outcome(AuditOutcome.DENIED).reasonCode(reasonCode).build(),
                    user);
        } catch (RuntimeException auditFailure) {
            denial.addSuppressed(auditFailure);
        }
        throw denial;
    }

    private boolean isConfiguredGlobalUser(User user) {
        return StringUtils.isNotBlank(user.getName())
                && (readUsers.contains(user.getName()) || writeUsers.contains(user.getName()));
    }

    private boolean hasGlobalScopeAttribute(User user) {
        return user.getAttributes() != null
                && "GLOBAL".equalsIgnoreCase(user.getAttributes().get("auditScope"));
    }

    private boolean isAllowed(User user, Set<String> allowedRoles, Set<String> allowedUsers) {
        if (user == null) {
            return false;
        }
        if (user.isSuperAdmin()) {
            return true;
        }
        if (StringUtils.isNotBlank(user.getName()) && allowedUsers.contains(user.getName())) {
            return true;
        }
        return user.getRoles() != null && user.getRoles().stream().filter(StringUtils::isNotBlank)
                .map(String::trim).map(role -> role.toUpperCase(Locale.ROOT))
                .anyMatch(allowedRoles::contains);
    }

    private Set<String> parseUsers(String configuredUsers) {
        return Arrays.stream(StringUtils.defaultString(configuredUsers).split(","))
                .map(String::trim).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
    }
}
