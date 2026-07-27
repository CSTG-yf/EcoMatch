package com.tencent.supersonic.auth.authorization.service;

import com.google.gson.Gson;
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
    void rejectsOversizedPermissionCollectionsAndText() {
        AuthGroup tooManyRules = validGroup();
        tooManyRules.setAuthRules(
                java.util.Collections.nCopies(1_001, tooManyRules.getAuthRules().get(0)));
        assertThrows(IllegalArgumentException.class,
                () -> authService.addOrUpdateAuthGroup(tooManyRules));

        AuthGroup oversizedIdentifier = validGroup();
        oversizedIdentifier.setAuthorizedUsers(List.of("u".repeat(4_097)));
        assertThrows(IllegalArgumentException.class,
                () -> authService.addOrUpdateAuthGroup(oversizedIdentifier));

        AuthGroup tooManyResources = validGroup();
        AuthRule resourceRule = new AuthRule();
        resourceRule.setMetrics(
                java.util.stream.IntStream.range(0, 1_000).mapToObj(i -> "metric_" + i).toList());
        tooManyResources.setAuthRules(java.util.Collections.nCopies(11, resourceRule));
        assertThrows(IllegalArgumentException.class,
                () -> authService.addOrUpdateAuthGroup(tooManyResources));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsOversizedAuthorizationRequestsBeforeLookup() {
        QueryAuthResReq request = new QueryAuthResReq();
        request.setModelIds(java.util.stream.LongStream.rangeClosed(1, 1_001).boxed().toList());

        assertThrows(IllegalArgumentException.class,
                () -> authService.queryAuthorizedResources(request, User.get(2L, "analyst")));
        request.setModelIds(List.of(1L));
        assertThrows(IllegalArgumentException.class,
                () -> authService.queryAuthorizedResources(request, null));

        verifyNoInteractions(userService, jdbcTemplate);
    }

    @Test
    void failsClosedWhenStoredGroupCountExceedsMaximum() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(java.util.Collections.nCopies(10_001, "{}"));

        assertThrows(IllegalStateException.class, () -> authService.queryAuthGroups("1", null));
    }

    @Test
    void isolatesMalformedStoredGroupsWithoutDroppingValidAuthorization() {
        String valid = """
                {"modelId":1,"name":"valid","groupId":1,
                 "authRules":[{"metrics":["loan_balance","LOAN_BALANCE"]}],
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
        request.setModelIds(List.of(1L, 1L));

        AuthorizedResourceResp response =
                authService.queryAuthorizedResources(request, User.get(2L, "analyst"));

        assertEquals(1, response.getAuthResList().size());
        assertEquals("loan_balance", response.getAuthResList().get(0).getName());
        assertEquals(1L, response.getFilters().get(0).getModelId());
        assertEquals(List.of(), response.getFilters().get(0).getExpressions());
    }

    @Test
    void rejectsAuthorizationAggregationThatExceedsRuntimeBudget() {
        String valid = """
                {"modelId":1,"name":"valid","groupId":1,
                 "authRules":[{"metrics":["loan_balance"]}],
                 "authorizedUsers":["analyst"],"dimensionFilters":[]}
                """;
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(java.util.Collections.nCopies(1_001, valid));
        when(userService.getUserAllOrgId("analyst")).thenReturn(Set.of());
        QueryAuthResReq request = new QueryAuthResReq();
        request.setModelIds(List.of(1L));

        assertThrows(IllegalStateException.class,
                () -> authService.queryAuthorizedResources(request, User.get(2L, "analyst")));
    }

    @Test
    void rejectsUniqueResourceAndRowFilterAggregationBeyondRuntimeBudgets() {
        when(userService.getUserAllOrgId("analyst")).thenReturn(Set.of());
        QueryAuthResReq request = new QueryAuthResReq();
        request.setModelIds(List.of(1L));

        AuthGroup firstResources = groupWithResources("a", 6_000);
        AuthGroup secondResources = groupWithResources("b", 6_000);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(
                List.of(new Gson().toJson(firstResources), new Gson().toJson(secondResources)));
        assertThrows(IllegalStateException.class,
                () -> authService.queryAuthorizedResources(request, User.get(2L, "analyst")));

        AuthGroup filterGroup = validGroup();
        filterGroup.setDimensionFilters(java.util.Collections.nCopies(1_000, "branch_id = '001'"));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(java.util.Collections.nCopies(11, new Gson().toJson(filterGroup)));
        assertThrows(IllegalStateException.class,
                () -> authService.queryAuthorizedResources(request, User.get(2L, "analyst")));
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

    private AuthGroup groupWithResources(String prefix, int resourceCount) {
        List<AuthRule> rules = new java.util.ArrayList<>();
        for (int ruleIndex = 0; ruleIndex < resourceCount / 1_000; ruleIndex++) {
            int offset = ruleIndex * 1_000;
            AuthRule rule = new AuthRule();
            rule.setMetrics(java.util.stream.IntStream.range(offset, offset + 1_000)
                    .mapToObj(index -> prefix + "_metric_" + index).toList());
            rules.add(rule);
        }
        AuthGroup group = validGroup();
        group.setAuthRules(rules);
        return group;
    }
}
