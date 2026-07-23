package com.tencent.supersonic.auth.authorization.service;

import com.tencent.supersonic.auth.api.authorization.pojo.AuthGroup;
import com.tencent.supersonic.common.pojo.User;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Matches user, organization, role, and attribute authorization subjects. */
public class AuthGroupMatcher {

    public boolean matches(AuthGroup group, User user, List<String> departmentIds) {
        if (group == null || user == null) {
            return false;
        }
        boolean attributesMatched =
                matchesAttributes(group.getAttributeConditions(), user.getAttributes());
        boolean hasSubjectRules = !CollectionUtils.isEmpty(group.getAuthorizedUsers())
                || !CollectionUtils.isEmpty(group.getAuthorizedDepartmentIds())
                || !CollectionUtils.isEmpty(group.getAuthorizedRoles());
        if (!hasSubjectRules) {
            return !CollectionUtils.isEmpty(group.getAttributeConditions()) && attributesMatched;
        }
        boolean subjectMatched = matchesUser(group, user) || matchesDepartment(group, departmentIds)
                || matchesRole(group, user.getRoles());
        return subjectMatched && attributesMatched;
    }

    private boolean matchesUser(AuthGroup group, User user) {
        return !CollectionUtils.isEmpty(group.getAuthorizedUsers())
                && group.getAuthorizedUsers().contains(user.getName());
    }

    private boolean matchesDepartment(AuthGroup group, List<String> departmentIds) {
        return !CollectionUtils.isEmpty(group.getAuthorizedDepartmentIds())
                && !CollectionUtils.isEmpty(departmentIds)
                && !Collections.disjoint(group.getAuthorizedDepartmentIds(), departmentIds);
    }

    private boolean matchesRole(AuthGroup group, Set<String> roles) {
        return !CollectionUtils.isEmpty(group.getAuthorizedRoles())
                && !CollectionUtils.isEmpty(roles)
                && !Collections.disjoint(group.getAuthorizedRoles(), roles);
    }

    private boolean matchesAttributes(Map<String, String> conditions,
            Map<String, String> attributes) {
        if (CollectionUtils.isEmpty(conditions)) {
            return true;
        }
        if (CollectionUtils.isEmpty(attributes)) {
            return false;
        }
        return conditions.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(attributes.get(entry.getKey())));
    }
}
