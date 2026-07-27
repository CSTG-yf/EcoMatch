package com.tencent.supersonic.auth.authorization.service;

import com.google.gson.Gson;
import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthGroup;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthRes;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthRule;
import com.tencent.supersonic.auth.api.authorization.pojo.DimensionFilter;
import com.tencent.supersonic.auth.api.authorization.request.QueryAuthResReq;
import com.tencent.supersonic.auth.api.authorization.response.AuthorizedResourceResp;
import com.tencent.supersonic.auth.api.authorization.service.AuthService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private JdbcTemplate jdbcTemplate;

    private UserService userService;
    private final AuthGroupMatcher authGroupMatcher = new AuthGroupMatcher();

    public AuthServiceImpl(JdbcTemplate jdbcTemplate, UserService userService) {
        this.jdbcTemplate = jdbcTemplate;
        this.userService = userService;
    }

    private List<AuthGroup> load() {
        List<String> rows =
                jdbcTemplate.queryForList("select config from s2_auth_groups", String.class);
        Gson g = new Gson();
        return rows.stream().map(row -> g.fromJson(row, AuthGroup.class))
                .collect(Collectors.toList());
    }

    @Override
    public List<AuthGroup> queryAuthGroups(String modelId, Integer groupId) {
        return load().stream()
                .filter(group -> (Objects.isNull(groupId) || groupId.equals(group.getGroupId()))
                        && modelId.equals(group.getModelId().toString()))
                .collect(Collectors.toList());
    }

    @Override
    public void addOrUpdateAuthGroup(AuthGroup group) {
        validateAuthGroup(group);
        Gson g = new Gson();
        if (group.getGroupId() == null) {
            int nextGroupId = 1;
            String sql = "select max(group_id) as group_id from s2_auth_groups";
            Integer obj = jdbcTemplate.queryForObject(sql, Integer.class);
            if (obj != null) {
                nextGroupId = obj + 1;
            }
            group.setGroupId(nextGroupId);
            jdbcTemplate.update("insert into s2_auth_groups (group_id, config) values (?, ?);",
                    nextGroupId, g.toJson(group));
        } else {
            jdbcTemplate.update("update s2_auth_groups set config = ? where group_id = ?;",
                    g.toJson(group), group.getGroupId());
        }
    }

    @Override
    public void removeAuthGroup(AuthGroup group) {
        if (group == null || group.getGroupId() == null || group.getGroupId() <= 0) {
            throw new IllegalArgumentException("Authorization group id must be positive");
        }
        jdbcTemplate.update("delete from s2_auth_groups where group_id = ?", group.getGroupId());
    }

    @Override
    public AuthorizedResourceResp queryAuthorizedResources(QueryAuthResReq req, User user) {
        if (CollectionUtils.isEmpty(req.getModelIds())) {
            return new AuthorizedResourceResp();
        }
        Set<String> userOrgIds = userService.getUserAllOrgId(user.getName());
        List<AuthGroup> groups =
                getAuthGroups(req.getModelIds(), user, new ArrayList<>(userOrgIds));
        AuthorizedResourceResp resource = new AuthorizedResourceResp();
        Map<Long, List<AuthGroup>> authGroupsByModelId =
                groups.stream().collect(Collectors.groupingBy(AuthGroup::getModelId));
        for (Long modelId : req.getModelIds()) {
            if (authGroupsByModelId.containsKey(modelId)) {
                List<AuthGroup> authGroups = authGroupsByModelId.get(modelId);
                for (AuthGroup authRuleGroup : authGroups) {
                    List<AuthRule> authRules = authRuleGroup.getAuthRules();
                    for (AuthRule authRule : authRules) {
                        for (String resBizName : authRule.resourceNames()) {
                            resource.getAuthResList().add(new AuthRes(modelId, resBizName));
                        }
                    }
                }
            }
        }
        Set<Map.Entry<Long, List<AuthGroup>>> entries = authGroupsByModelId.entrySet();
        for (Map.Entry<Long, List<AuthGroup>> entry : entries) {
            List<AuthGroup> authGroups = entry.getValue();
            for (AuthGroup authGroup : authGroups) {
                DimensionFilter df = new DimensionFilter();
                df.setDescription(authGroup.getDimensionFilterDescription());
                df.setExpressions(authGroup.getDimensionFilters());
                resource.getFilters().add(df);
            }
        }
        return resource;
    }

    private List<AuthGroup> getAuthGroups(List<Long> modelIds, User user,
            List<String> departmentIds) {
        List<AuthGroup> groups = load().stream().filter(group -> {
            if (!modelIds.contains(group.getModelId())) {
                return false;
            }
            return authGroupMatcher.matches(group, user, departmentIds);
        }).collect(Collectors.toList());
        log.debug(
                "Authorization groups resolved: user=[{}], departments=[{}], roles=[{}], count={}",
                SensitiveLogUtils.summarize(user.getName()),
                SensitiveLogUtils.summarize(departmentIds),
                SensitiveLogUtils.summarize(user.getRoles()), groups.size());
        return groups;
    }

    private void validateAuthGroup(AuthGroup group) {
        if (group == null) {
            throw new IllegalArgumentException("Authorization group is required");
        }
        if (group.getModelId() == null || group.getModelId() <= 0) {
            throw new IllegalArgumentException("Authorization group modelId must be positive");
        }
        if (!StringUtils.hasText(group.getName())) {
            throw new IllegalArgumentException("Authorization group name is required");
        }
        if (group.getGroupId() != null && group.getGroupId() <= 0) {
            throw new IllegalArgumentException("Authorization group id must be positive");
        }

        boolean hasSubject = validateIdentifiers(group.getAuthorizedUsers(), "user")
                | validateIdentifiers(group.getAuthorizedDepartmentIds(), "department")
                | validateIdentifiers(group.getAuthorizedRoles(), "role");
        if (!CollectionUtils.isEmpty(group.getAttributeConditions())) {
            group.getAttributeConditions().forEach((key, value) -> {
                if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                    throw new IllegalArgumentException(
                            "Authorization attribute keys and values must not be blank");
                }
            });
            hasSubject = true;
        }
        if (!hasSubject) {
            throw new IllegalArgumentException(
                    "Authorization group must define at least one effective subject");
        }

        if (CollectionUtils.isEmpty(group.getAuthRules())) {
            throw new IllegalArgumentException(
                    "Authorization group must define at least one resource rule");
        }
        group.getAuthRules().forEach(rule -> {
            if (rule == null) {
                throw new IllegalArgumentException("Authorization resource rule must not be null");
            }
            boolean hasResource = validateIdentifiers(rule.getMetrics(), "metric")
                    | validateIdentifiers(rule.getDimensions(), "dimension");
            if (!hasResource) {
                throw new IllegalArgumentException(
                        "Authorization resource rule must contain a metric or dimension");
            }
        });
        validateIdentifiers(group.getDimensionFilters(), "dimension filter");
    }

    private boolean validateIdentifiers(List<String> values, String type) {
        if (CollectionUtils.isEmpty(values)) {
            return false;
        }
        if (values.stream().anyMatch(value -> !StringUtils.hasText(value))) {
            throw new IllegalArgumentException(
                    "Authorization " + type + " values must not be blank");
        }
        return true;
    }
}
