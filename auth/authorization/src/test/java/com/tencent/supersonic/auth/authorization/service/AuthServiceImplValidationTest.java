package com.tencent.supersonic.auth.authorization.service;

import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthGroup;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthRule;
import com.tencent.supersonic.auth.api.authorization.request.QueryAuthResReq;
import com.tencent.supersonic.auth.api.authorization.response.AuthorizedResourceResp;
import com.tencent.supersonic.common.pojo.User;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceImplValidationTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final UserService userService = mock(UserService.class);
    private final AuthServiceImpl authService = new AuthServiceImpl(jdbcTemplate, userService);

    @Test
    void rejectsPermissionGroupWithoutModelOrEffectiveSubject() {
        AuthGroup missingModel = validGroup();
        missingModel.setModelId(null);
        assertThrows(IllegalArgumentException.class,
                () -> authService.addOrUpdateAuthGroup(missingModel));

        AuthGroup blankSubject = validGroup();
        blankSubject.setAuthorizedUsers(List.of(" "));
        assertThrows(IllegalArgumentException.class,
                () -> authService.addOrUpdateAuthGroup(blankSubject));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsMalformedAttributeRulesResourcesAndFilters() {
        AuthGroup blankAttribute = validGroup();
        blankAttribute.setAuthorizedUsers(List.of());
        blankAttribute.setAttributeConditions(Map.of("branch", " "));
        assertThrows(IllegalArgumentException.class,
                () -> authService.addOrUpdateAuthGroup(blankAttribute));

        AuthGroup blankResource = validGroup();
        blankResource.getAuthRules().get(0).setMetrics(List.of(" "));
        assertThrows(IllegalArgumentException.class,
                () -> authService.addOrUpdateAuthGroup(blankResource));

        AuthGroup blankFilter = validGroup();
        blankFilter.setDimensionFilters(List.of(" "));
        assertThrows(IllegalArgumentException.class,
                () -> authService.addOrUpdateAuthGroup(blankFilter));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void persistsAValidPermissionGroup() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);

        authService.addOrUpdateAuthGroup(validGroup());

        verify(jdbcTemplate).update(anyString(), eq(1), anyString());
    }

    @Test
    void isolatesMalformedStoredGroupsWithoutDroppingValidAuthorization() {
        String valid = """
                {"modelId":1,"name":"valid","groupId":1,
                 "authRules":[{"metrics":["loan_balance"]}],
                 "authorizedUsers":["analyst"],"dimensionFilters":[]}
                """;
        String invalid = """
                {"modelId":1,"name":"invalid","groupId":2,
                 "authRules":null,"authorizedUsers":["analyst"]}
                """;
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("{", invalid, valid));
        when(userService.getUserAllOrgId("analyst")).thenReturn(Set.of());
        QueryAuthResReq request = new QueryAuthResReq();
        request.setModelIds(List.of(1L));

        AuthorizedResourceResp response =
                authService.queryAuthorizedResources(request, User.get(2L, "analyst"));

        assertEquals(1, response.getAuthResList().size());
        assertEquals("loan_balance", response.getAuthResList().get(0).getName());
    }

    private AuthGroup validGroup() {
        AuthRule rule = new AuthRule();
        rule.setMetrics(List.of("loan_balance"));
        AuthGroup group = new AuthGroup();
        group.setModelId(1L);
        group.setName("branch permission");
        group.setAuthorizedUsers(List.of("analyst"));
        group.setAuthRules(List.of(rule));
        group.setDimensionFilters(List.of("branch_id = '001'"));
        return group;
    }
}
