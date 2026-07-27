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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final int MAX_STORED_GROUPS = 10_000;
    private static final int MAX_CONFIG_LENGTH = 262_144;
    private static final int MAX_TOTAL_CONFIG_LENGTH = 33_554_432;
    private static final int MAX_MODEL_IDS = 1_000;
    private static final int MAX_RULES_PER_GROUP = 1_000;
    private static final int MAX_IDENTIFIERS_PER_FIELD = 1_000;
    private static final int MAX_ATTRIBUTES_PER_GROUP = 100;
    private static final int MAX_AUTH_TEXT_LENGTH = 4_096;

    private JdbcTemplate jdbcTemplate;

    private UserService userService;
    private final AuthGroupMatcher authGroupMatcher = new AuthGroupMatcher();
    private final Set<String> invalidConfigWarnings = ConcurrentHashMap.newKeySet();

    public AuthServiceImpl(JdbcTemplate jdbcTemplate, UserService userService) {
        this.jdbcTemplate = jdbcTemplate;
        this.userService = userService;
    }

    private List<AuthGroup> load() {
        List<String> rows =
                jdbcTemplate.queryForList("select config from s2_auth_groups", String.class);
        if (rows.size() > MAX_STORED_GROUPS) {
            throw new IllegalStateException(
                    "Authorization group count exceeds maximum: " + MAX_STORED_GROUPS);
        }
        long totalConfigLength =
                rows.stream().filter(Objects::nonNull).mapToLong(String::length).sum();
        if (totalConfigLength > MAX_TOTAL_CONFIG_LENGTH) {
            throw new IllegalStateException("Authorization config text exceeds maximum total "
                    + "length: " + MAX_TOTAL_CONFIG_LENGTH);
        }
        Gson g = new Gson();
        List<AuthGroup> groups = new ArrayList<>();
        for (String row : rows) {
            try {
                if (row == null || row.length() > MAX_CONFIG_LENGTH) {
                    throw new IllegalArgumentException(
                            "Authorization group config exceeds maximum length");
                }
                AuthGroup group = g.fromJson(row, AuthGroup.class);
                validateAuthGroup(group);
                groups.add(group);
            } catch (RuntimeException e) {
                String configMetadata = configMetadata(row);
                if (invalidConfigWarnings.add(configMetadata)) {
                    log.warn("Ignoring invalid authorization group: config=[{}], errorType={}",
                            configMetadata, e.getClass().getSimpleName());
                }
            }
        }
        return groups;
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
        if (req == null) {
            throw new IllegalArgumentException("Authorization resource request is required");
        }
        if (CollectionUtils.isEmpty(req.getModelIds())) {
            return new AuthorizedResourceResp();
        }
        if (req.getModelIds().size() > MAX_MODEL_IDS) {
            throw new IllegalArgumentException(
                    "Authorization model id count exceeds maximum: " + MAX_MODEL_IDS);
        }
        if (req.getModelIds().stream().anyMatch(modelId -> modelId == null || modelId <= 0)) {
            throw new IllegalArgumentException("Authorization model ids must be positive");
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
        Set<Long> requestedModelIds = new HashSet<>(modelIds);
        List<AuthGroup> groups = load().stream().filter(group -> {
            if (!requestedModelIds.contains(group.getModelId())) {
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
        validateText(group.getName(), "group name");
        validateText(group.getDimensionFilterDescription(), "dimension filter description");
        if (group.getGroupId() != null && group.getGroupId() <= 0) {
            throw new IllegalArgumentException("Authorization group id must be positive");
        }

        boolean hasSubject = validateIdentifiers(group.getAuthorizedUsers(), "user")
                | validateIdentifiers(group.getAuthorizedDepartmentIds(), "department")
                | validateIdentifiers(group.getAuthorizedRoles(), "role");
        if (!CollectionUtils.isEmpty(group.getAttributeConditions())) {
            if (group.getAttributeConditions().size() > MAX_ATTRIBUTES_PER_GROUP) {
                throw new IllegalArgumentException(
                        "Authorization attribute condition count exceeds " + "maximum: "
                                + MAX_ATTRIBUTES_PER_GROUP);
            }
            group.getAttributeConditions().forEach((key, value) -> {
                if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                    throw new IllegalArgumentException(
                            "Authorization attribute keys and values must not be blank");
                }
                validateText(key, "attribute key");
                validateText(value, "attribute value");
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
        if (group.getAuthRules().size() > MAX_RULES_PER_GROUP) {
            throw new IllegalArgumentException(
                    "Authorization resource rule count exceeds maximum: " + MAX_RULES_PER_GROUP);
        }
        group.getAuthRules().forEach(rule -> {
            if (rule == null) {
                throw new IllegalArgumentException("Authorization resource rule must not be null");
            }
            validateText(rule.getName(), "resource rule name");
            validateText(rule.getDescription(), "resource rule description");
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
        if (values.size() > MAX_IDENTIFIERS_PER_FIELD) {
            throw new IllegalArgumentException("Authorization " + type
                    + " value count exceeds maximum: " + MAX_IDENTIFIERS_PER_FIELD);
        }
        values.forEach(value -> validateText(value, type));
        return true;
    }

    private void validateText(String value, String type) {
        if (value != null && value.length() > MAX_AUTH_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Authorization " + type + " exceeds maximum length: " + MAX_AUTH_TEXT_LENGTH);
        }
    }

    private String configMetadata(String row) {
        if (row == null) {
            return "null";
        }
        if (row.length() > MAX_CONFIG_LENGTH) {
            return "oversized,chars=" + row.length();
        }
        return SensitiveLogUtils.summarize(row);
    }
}
