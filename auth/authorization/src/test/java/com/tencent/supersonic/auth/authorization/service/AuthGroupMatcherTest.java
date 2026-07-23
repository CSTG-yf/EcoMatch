package com.tencent.supersonic.auth.authorization.service;

import com.tencent.supersonic.auth.api.authorization.pojo.AuthGroup;
import com.tencent.supersonic.common.pojo.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthGroupMatcherTest {

    private final AuthGroupMatcher matcher = new AuthGroupMatcher();

    @Test
    void supportsRoleAndAttributeConditions() {
        AuthGroup group = new AuthGroup();
        group.setAuthorizedRoles(List.of("risk_manager"));
        group.setAttributeConditions(Map.of("region", "jiangsu", "clearance", "high"));
        User user = User.get(2L, "analyst");
        user.setRoles(Set.of("risk_manager"));
        user.setAttributes(Map.of("region", "jiangsu", "clearance", "high"));

        assertTrue(matcher.matches(group, user, List.of()));

        user.setAttributes(Map.of("region", "jiangsu", "clearance", "low"));
        assertFalse(matcher.matches(group, user, List.of()));
    }

    @Test
    void supportsUserAndDepartmentSubjects() {
        AuthGroup group = new AuthGroup();
        group.setAuthorizedUsers(List.of("alice"));
        group.setAuthorizedDepartmentIds(List.of("branch-001"));

        assertTrue(matcher.matches(group, User.get(2L, "alice"), List.of()));
        assertTrue(matcher.matches(group, User.get(3L, "bob"), List.of("branch-001")));
        assertFalse(matcher.matches(group, User.get(4L, "carol"), List.of("branch-002")));
    }

    @Test
    void supportsPureAttributePolicies() {
        AuthGroup group = new AuthGroup();
        group.setAttributeConditions(Map.of("job", "branch_manager"));
        User user = User.get(2L, "alice");
        user.setAttributes(Map.of("job", "branch_manager"));

        assertTrue(matcher.matches(group, user, List.of()));
    }
}
